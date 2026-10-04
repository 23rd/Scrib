
#include <jni.h>
#include <android/log.h>

#include <dlfcn.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <new>
#include <string>
#include <utility>
#include <vector>

#include "transcribe.h"
#include "silero_vad.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "gguf.h"

#define TAG "ScribTranscribe"
#define UNUSED(x) (void)(x)

static void bridge_log(transcribe_log_level level, const char *msg, void *user) {
    UNUSED(user);
    if (msg == nullptr) {
        return;
    }
    int priority = ANDROID_LOG_INFO;
    if (level == TRANSCRIBE_LOG_LEVEL_ERROR) {
        priority = ANDROID_LOG_ERROR;
    } else if (level == TRANSCRIBE_LOG_LEVEL_WARN) {
        priority = ANDROID_LOG_WARN;
    } else if (level == TRANSCRIBE_LOG_LEVEL_DEBUG) {
        priority = ANDROID_LOG_DEBUG;
    }
    __android_log_write(priority, TAG, msg);
}

static void load_cpu_backend() {
    static bool logging_set = false;
    if (!logging_set) {
        logging_set = true;
        transcribe_log_set(bridge_log, nullptr);
    }

    static const char *const backends[] = {
            "libggml-cpu-android_armv8.2_2.so",
            "libggml-cpu-android_armv8.0_1.so",
    };

    if (ggml_backend_reg_count() > 0) {
        return;
    }
    for (size_t i = 0; i < sizeof(backends) / sizeof(backends[0]); ++i) {
        if (ggml_backend_load(backends[i]) != nullptr) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "Loaded cpu backend %s", backends[i]);
            return;
        }
        const char *why = dlerror();
        __android_log_print(ANDROID_LOG_WARN, TAG, "Could not load %s: %s", backends[i], why == nullptr ? "?" : why);
    }
    __android_log_write(ANDROID_LOG_WARN, TAG, "Found no loadable cpu backend");
}

namespace {

struct loaded_model {
    transcribe_model                   *model = nullptr;
    std::string                         arch;
    std::string                         variant;
    std::vector<std::string>            languages;
    std::vector<std::string>            translate_targets;
    int64_t                             max_audio_ms = 0;
    bool                                streaming = false;
    bool                                translate = false;
    bool                                language_detect = false;
    bool                                context_prompt = false;
    bool                                long_form = false;
    transcribe_timestamp_kind           max_timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
};

struct live_session {
    transcribe_session *session = nullptr;
};

struct abort_flag {
    std::atomic<bool> cancelled{false};
};

std::string to_std(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return out;
}

jstring from_std(JNIEnv *env, const std::string &value) {
    return value.empty() ? nullptr : env->NewStringUTF(value.c_str());
}

std::string to_std_or_empty(JNIEnv *env, jstring value) {
    return value == nullptr ? std::string() : to_std(env, value);
}

void read_capabilities(loaded_model *out) {
    transcribe_capabilities caps;
    transcribe_capabilities_init(&caps);
    if (transcribe_model_get_capabilities(out->model, &caps) != TRANSCRIBE_OK) {
        return;
    }
    out->max_audio_ms = caps.max_audio_ms;
    out->streaming = caps.supports_streaming;
    out->translate = caps.supports_translate;
    out->language_detect = caps.supports_language_detect;
    out->max_timestamps = caps.max_timestamp_kind;
    for (int i = 0; i < caps.n_languages && caps.languages != nullptr; ++i) {
        if (caps.languages[i] != nullptr) {
            out->languages.emplace_back(caps.languages[i]);
        }
    }
    for (int i = 0; i < caps.n_translate_target_languages && caps.translate_target_languages != nullptr; ++i) {
        if (caps.translate_target_languages[i] != nullptr) {
            out->translate_targets.emplace_back(caps.translate_target_languages[i]);
        }
    }
    out->context_prompt = transcribe_model_supports(out->model, TRANSCRIBE_FEATURE_CONTEXT_PROMPT);
    out->long_form = transcribe_model_supports(out->model, TRANSCRIBE_FEATURE_LONG_FORM);
}

bool abort_reached(void *user) {
    auto *flag = static_cast<abort_flag *>(user);
    return flag != nullptr && flag->cancelled.load();
}

void fill_run_params(const loaded_model *meta, transcribe_run_params *params,
                     const std::string &language, const std::string &prompt, int task) {
    transcribe_run_params_init(params);
    if (task == 1 && meta != nullptr && meta->translate) {
        params->task = TRANSCRIBE_TASK_TRANSLATE;
    }
    if (!language.empty()) {
        params->language = language.c_str();
    }
    if (!prompt.empty() && (meta == nullptr || meta->context_prompt)) {
        params->prompt = prompt.c_str();
    }
    if (meta == nullptr || meta->max_timestamps == TRANSCRIBE_TIMESTAMPS_NONE) {
        params->timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    } else {
        params->timestamps = TRANSCRIBE_TIMESTAMPS_SEGMENT;
    }
}

void throw_status(JNIEnv *env, const char *what, transcribe_status status) {
    char message[512];
    snprintf(message, sizeof(message), "%s: %s (%d)", what, transcribe_status_string(status),
             static_cast<int>(status));
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), message);
}

}

extern "C" {

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeInit(JNIEnv *env, jclass) {
    load_cpu_backend();
    char info[256];
    snprintf(info, sizeof(info), "transcribe.cpp %s (%s), %d backend(s)",
             transcribe_version(), transcribe_version_commit(), ggml_backend_reg_count());
    return env->NewStringUTF(info);
}

JNIEXPORT jlong JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeVadOpen(JNIEnv *env, jobject, jstring path, jint threads) {
    load_cpu_backend();
    const std::string file = to_std(env, path);
    vad_context *vad = vad_open(file.c_str(), threads);
    if (vad == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      ("Couldn't load the silence-skipping model " + file).c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(vad);
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeVadClose(JNIEnv *, jobject, jlong vadPtr) {
    vad_close(reinterpret_cast<vad_context *>(vadPtr));
}

JNIEXPORT jlongArray JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeVadSpans(JNIEnv *env, jobject, jlong vadPtr,
                                                                       jobject buffer, jint sampleCount,
                                                                       jfloat threshold, jint minSpeechMs,
                                                                       jint minSilenceMs, jint speechPadMs) {
    auto *vad = reinterpret_cast<vad_context *>(vadPtr);
    if (vad == nullptr || buffer == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "no vad model or buffer");
        return env->NewLongArray(0);
    }
    const auto *pcm = static_cast<const float *>(env->GetDirectBufferAddress(buffer));
    if (pcm == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "buffer is not direct");
        return env->NewLongArray(0);
    }
    vad_params params;
    params.threshold = threshold;
    params.min_speech_duration_ms = minSpeechMs;
    params.min_silence_duration_ms = minSilenceMs;
    params.speech_pad_ms = speechPadMs;
    std::vector<std::pair<int64_t, int64_t>> spans;
    if (!vad_spans(vad, pcm, sampleCount, params, spans)) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "The silence-skipping model failed");
        return env->NewLongArray(0);
    }
    std::vector<jlong> flat;
    flat.reserve(spans.size() * 2);
    for (const auto &span : spans) {
        flat.push_back(static_cast<jlong>(span.first));
        flat.push_back(static_cast<jlong>(span.second));
    }
    jlongArray out = env->NewLongArray(static_cast<jsize>(flat.size()));
    env->SetLongArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    return out;
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeStatusString(JNIEnv *env, jobject, jint status) {
    return env->NewStringUTF(transcribe_status_string(static_cast<transcribe_status>(status)));
}

JNIEXPORT jboolean JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelUsesVad(JNIEnv *env, jobject, jstring path) {
    const std::string file = to_std(env, path);
    gguf_init_params params = {/*no_alloc =*/ true, /*ctx =*/ nullptr};
    gguf_context *ctx = gguf_init_from_file(file.c_str(), params);
    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s is not a gguf file; treating it as whisper", file.c_str());
        return JNI_TRUE;
    }
    const int64_t key = gguf_find_key(ctx, "general.architecture");
    const char *arch = key < 0 ? nullptr : gguf_get_val_str(ctx, key);
    const bool uses_vad = arch == nullptr || std::strcmp(arch, "parakeet") != 0;
    if (arch != nullptr) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s is %s", file.c_str(), arch);
    }
    gguf_free(ctx);
    return uses_vad ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelLoad(JNIEnv *env, jobject, jstring path) {
    load_cpu_backend();
    const std::string file = to_std(env, path);
    transcribe_model *model = nullptr;
    transcribe_model_load_params params;
    transcribe_model_load_params_init(&params);
    params.backend = TRANSCRIBE_BACKEND_CPU;
    const transcribe_status status = transcribe_model_load_file(file.c_str(), &params, &model);
    if (status != TRANSCRIBE_OK) {
        throw_status(env, "load model", status);
        return 0;
    }
    auto *held = new (std::nothrow) loaded_model();
    if (held == nullptr) {
        transcribe_model_free(model);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "model holder");
        return 0;
    }
    held->model = model;
    const char *arch = transcribe_model_arch_string(model);
    const char *variant = transcribe_model_variant_string(model);
    if (arch != nullptr) {
        held->arch = arch;
    }
    if (variant != nullptr) {
        held->variant = variant;
    }
    read_capabilities(held);
    return reinterpret_cast<jlong>(held);
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelFree(JNIEnv *, jobject, jlong ptr) {
    auto *held = reinterpret_cast<loaded_model *>(ptr);
    if (held == nullptr) {
        return;
    }
    transcribe_model_free(held->model);
    delete held;
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelArch(JNIEnv *env, jobject, jlong ptr) {
    auto *held = reinterpret_cast<loaded_model *>(ptr);
    return held == nullptr ? nullptr : from_std(env, held->arch);
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelVariant(JNIEnv *env, jobject, jlong ptr) {
    auto *held = reinterpret_cast<loaded_model *>(ptr);
    return held == nullptr ? nullptr : from_std(env, held->variant);
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelBackend(JNIEnv *env, jobject, jlong ptr) {
    auto *held = reinterpret_cast<loaded_model *>(ptr);
    if (held == nullptr) {
        return nullptr;
    }
    const char *backend = transcribe_model_backend(held->model);
    return backend == nullptr ? nullptr : env->NewStringUTF(backend);
}

JNIEXPORT jobjectArray JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelLanguages(JNIEnv *env, jobject, jlong ptr) {
    auto *held = reinterpret_cast<loaded_model *>(ptr);
    if (held == nullptr) {
        return nullptr;
    }
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(held->languages.size()), string_class, nullptr);
    for (size_t i = 0; i < held->languages.size(); ++i) {
        jstring item = env->NewStringUTF(held->languages[i].c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelFlags(JNIEnv *, jobject, jlong ptr) {
    auto *held = reinterpret_cast<loaded_model *>(ptr);
    if (held == nullptr) {
        return 0;
    }
    jint flags = 0;
    if (held->streaming) {
        flags |= 1;
    }
    if (held->translate) {
        flags |= 2;
    }
    if (held->language_detect) {
        flags |= 4;
    }
    if (held->context_prompt) {
        flags |= 8;
    }
    if (held->long_form) {
        flags |= 16;
    }
    return flags;
}

JNIEXPORT jlong JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeModelMaxAudioMs(JNIEnv *, jobject, jlong ptr) {
    auto *held = reinterpret_cast<loaded_model *>(ptr);
    return held == nullptr ? 0 : static_cast<jlong>(held->max_audio_ms);
}

JNIEXPORT jlong JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeSessionOpen(JNIEnv *env, jobject, jlong modelPtr, jint threads,
                                                            jint n_ctx) {
    auto *held = reinterpret_cast<loaded_model *>(modelPtr);
    if (held == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "no model");
        return 0;
    }
    transcribe_session_params params;
    transcribe_session_params_init(&params);
    params.n_threads = threads;
    params.n_ctx = n_ctx;
    transcribe_session *session = nullptr;
    const transcribe_status status = transcribe_session_init(held->model, &params, &session);
    if (status != TRANSCRIBE_OK) {
        throw_status(env, "open session", status);
        return 0;
    }
    auto *live = new (std::nothrow) live_session();
    if (live == nullptr) {
        transcribe_session_free(session);
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "session holder");
        return 0;
    }
    live->session = session;
    return reinterpret_cast<jlong>(live);
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeSessionClose(JNIEnv *, jobject, jlong ptr) {
    auto *live = reinterpret_cast<live_session *>(ptr);
    if (live == nullptr) {
        return;
    }
    transcribe_session_free(live->session);
    delete live;
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeSessionAttachAbort(JNIEnv *, jobject, jlong sessionPtr,
                                                                  jlong flagPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    if (live == nullptr) {
        return;
    }
    transcribe_set_abort_callback(live->session, abort_reached, reinterpret_cast<void *>(flagPtr));
}

JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeRun(JNIEnv *env, jobject, jlong sessionPtr, jlong modelPtr,
                                                    jobject buffer, jint sampleCount, jstring language,
                                                    jstring prompt, jint task, jlong flagPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    auto *held = reinterpret_cast<loaded_model *>(modelPtr);
    if (live == nullptr || buffer == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "no session or buffer");
        return TRANSCRIBE_ERR_INVALID_ARG;
    }
    const auto *pcm = static_cast<const float *>(env->GetDirectBufferAddress(buffer));
    if (pcm == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "buffer is not direct");
        return TRANSCRIBE_ERR_INVALID_ARG;
    }
    transcribe_set_abort_callback(live->session, abort_reached, reinterpret_cast<void *>(flagPtr));
    transcribe_run_params params;
    fill_run_params(held, &params, to_std_or_empty(env, language), to_std_or_empty(env, prompt), task);
    return static_cast<jint>(transcribe_run(live->session, pcm, sampleCount, &params));
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeFullText(JNIEnv *env, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    if (live == nullptr) {
        return nullptr;
    }
    const char *text = transcribe_full_text(live->session);
    return text == nullptr ? nullptr : env->NewStringUTF(text);
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeDetectedLanguage(JNIEnv *env, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    if (live == nullptr) {
        return nullptr;
    }
    const char *language = transcribe_detected_language(live->session);
    return language == nullptr ? nullptr : env->NewStringUTF(language);
}

JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeSegmentCount(JNIEnv *, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    return live == nullptr ? 0 : transcribe_n_segments(live->session);
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeSegmentText(JNIEnv *env, jobject, jlong sessionPtr, jint index) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    if (live == nullptr) {
        return nullptr;
    }
    transcribe_segment segment;
    transcribe_segment_init(&segment);
    if (transcribe_get_segment(live->session, index, &segment) != TRANSCRIBE_OK) {
        return nullptr;
    }
    return segment.text == nullptr ? nullptr : env->NewStringUTF(segment.text);
}

JNIEXPORT jlongArray JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeSegmentBounds(JNIEnv *env, jobject, jlong sessionPtr,
                                                              jint fromIndex) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    const jint count = live == nullptr ? 0 : transcribe_n_segments(live->session);
    if (count <= fromIndex) {
        return env->NewLongArray(0);
    }
    std::vector<jlong> bounds;
    bounds.reserve(static_cast<size_t>(count - fromIndex) * 2);
    for (jint i = fromIndex; i < count; ++i) {
        transcribe_segment segment;
        transcribe_segment_init(&segment);
        if (transcribe_get_segment(live->session, i, &segment) != TRANSCRIBE_OK) {
            bounds.push_back(0);
            bounds.push_back(0);
            continue;
        }
        bounds.push_back(static_cast<jlong>(segment.t0_ms));
        bounds.push_back(static_cast<jlong>(segment.t1_ms));
    }
    jlongArray out = env->NewLongArray(static_cast<jsize>(bounds.size()));
    env->SetLongArrayRegion(out, 0, static_cast<jsize>(bounds.size()), bounds.data());
    return out;
}

JNIEXPORT jboolean JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeWasAborted(JNIEnv *, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    return live != nullptr && transcribe_was_aborted(live->session) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeResetTimings(JNIEnv *, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    if (live != nullptr) {
        transcribe_reset_timings(live->session);
    }
}

JNIEXPORT jlong JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeAbortNew(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new (std::nothrow) abort_flag());
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeAbortSet(JNIEnv *, jobject, jlong flagPtr) {
    auto *flag = reinterpret_cast<abort_flag *>(flagPtr);
    if (flag != nullptr) {
        flag->cancelled.store(true);
    }
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeAbortFree(JNIEnv *, jobject, jlong flagPtr) {
    delete reinterpret_cast<abort_flag *>(flagPtr);
}

JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeStreamBegin(JNIEnv *env, jobject, jlong sessionPtr,
                                                            jlong modelPtr, jstring language, jstring prompt,
                                                            jint task, jlong flagPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    auto *held = reinterpret_cast<loaded_model *>(modelPtr);
    if (live == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "no session");
        return TRANSCRIBE_ERR_INVALID_ARG;
    }
    transcribe_set_abort_callback(live->session, abort_reached, reinterpret_cast<void *>(flagPtr));
    transcribe_run_params run;
    fill_run_params(held, &run, to_std_or_empty(env, language), to_std_or_empty(env, prompt), task);
    transcribe_stream_params stream;
    transcribe_stream_params_init(&stream);
    return static_cast<jint>(transcribe_stream_begin(live->session, &run, &stream));
}

JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeStreamFeed(JNIEnv *env, jobject, jlong sessionPtr,
                                                          jobject buffer, jint sampleCount) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    if (live == nullptr || buffer == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "no session or buffer");
        return TRANSCRIBE_ERR_INVALID_ARG;
    }
    const auto *pcm = static_cast<const float *>(env->GetDirectBufferAddress(buffer));
    if (pcm == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "buffer is not direct");
        return TRANSCRIBE_ERR_INVALID_ARG;
    }
    return static_cast<jint>(transcribe_stream_feed(live->session, pcm, sampleCount, nullptr));
}

JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeStreamFinalize(JNIEnv *, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    return live == nullptr ? TRANSCRIBE_ERR_INVALID_ARG
                           : static_cast<jint>(transcribe_stream_finalize(live->session, nullptr));
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeStreamReset(JNIEnv *, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    if (live != nullptr) {
        transcribe_stream_reset(live->session);
    }
}

JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeStreamRevision(JNIEnv *, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    return live == nullptr ? 0 : transcribe_stream_revision(live->session);
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_TranscribeLib_00024Companion_nativeStreamCommittedText(JNIEnv *env, jobject, jlong sessionPtr) {
    auto *live = reinterpret_cast<live_session *>(sessionPtr);
    if (live == nullptr) {
        return nullptr;
    }
    transcribe_stream_text text;
    transcribe_stream_text_init(&text);
    if (transcribe_stream_get_text(live->session, &text) != TRANSCRIBE_OK) {
        return nullptr;
    }
    if (text.committed_text == nullptr || text.committed_text_bytes == 0) {
        return nullptr;
    }
    return env->NewStringUTF(text.committed_text);
}

}
