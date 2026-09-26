#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "parakeet.h"
#include "ggml.h"
#include "ggml-backend.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

// On arm64 the CPU kernels live in one library per instruction-set level rather than inside
// whisper's own, and one of them has to be registered before a context can be created. They are
// listed fastest first: ggml refuses to load a backend the processor cannot run, so the first
// one that loads is the best this phone supports. Loading by name rather than by scanning the
// library directory is deliberate -- the libraries are read straight out of the apk and never
// unpacked to a directory of their own. Nothing to do on the other architectures, where the
// kernels are still linked in.
static void fgt_log(enum ggml_log_level level, const char *text, void *user_data) {
    UNUSED(user_data);
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, TAG, text);
    }
}

static void load_cpu_backend(void) {
    static bool logging_set = false;
    if (!logging_set) {
        logging_set = true;
        whisper_log_set(fgt_log, NULL);
        parakeet_log_set(fgt_log, NULL);
    }

    static const char *const backends[] = {
            "libggml-cpu-android_armv8.2_2.so", // dot product, so anything from about 2019
            "libggml-cpu-android_armv8.0_1.so", // the plain arm64 every phone can run
    };

    if (ggml_backend_reg_count() > 0) {
        return;
    }
    for (size_t i = 0; i < sizeof(backends) / sizeof(backends[0]); ++i) {
        if (ggml_backend_load(backends[i]) != NULL) {
            LOGI("Loaded cpu backend %s\n", backends[i]);
            return;
        }
    }
    LOGW("Found no loadable cpu backend\n");
}


struct fgt_context {
    struct whisper_context *whisper;
    struct parakeet_context *parakeet;
};

#define FGT_WHISPER_N_TEXT_CTX 448

static bool fgt_is_parakeet_model(const char *path) {
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        return false;
    }
    int32_t header[7];
    const size_t got = fread(header, sizeof(header[0]), 7, file);
    fclose(file);
    return got == 7 && (uint32_t) header[0] == GGML_FILE_MAGIC && header[6] != FGT_WHISPER_N_TEXT_CTX;
}

static struct fgt_context *fgt_wrap(struct whisper_context *whisper, struct parakeet_context *parakeet) {
    if (whisper == NULL && parakeet == NULL) {
        return NULL;
    }
    struct fgt_context *context = (struct fgt_context *) calloc(1, sizeof(struct fgt_context));
    if (context == NULL) {
        whisper_free(whisper);
        parakeet_free(parakeet);
        return NULL;
    }
    context->whisper = whisper;
    context->parakeet = parakeet;
    return context;
}

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercppdemo_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    return (jlong) fgt_wrap(whisper_init(&loader), NULL);
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    load_cpu_backend();
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    struct whisper_context *whisper = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) fgt_wrap(whisper, NULL);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    load_cpu_backend();
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context *whisper = NULL;
    struct parakeet_context *parakeet = NULL;
    if (fgt_is_parakeet_model(model_path_chars)) {
        parakeet = parakeet_init_from_file_with_params(model_path_chars, parakeet_context_default_params());
    } else {
        struct whisper_context_params cparams = whisper_context_default_params();
        cparams.flash_attn = true;
        whisper = whisper_init_from_file_with_params(model_path_chars, cparams);
    }
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) fgt_wrap(whisper, parakeet);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    if (context == NULL) {
        return;
    }
    whisper_free(context->whisper);
    parakeet_free(context->parakeet);
    free(context);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_audioWindowSamples(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    if (context == NULL || context->parakeet == NULL) {
        return 0;
    }
    return parakeet_n_audio_ctx(context->parakeet) * PARAKEET_HOP_LENGTH;
}

// Whether this loaded context decodes through parakeet, without reading the model file again.
JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_isParakeetContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    return (context != NULL && context->parakeet != NULL) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_isParakeetModel(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    const bool parakeet = fgt_is_parakeet_model(model_path_chars);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return parakeet ? JNI_TRUE : JNI_FALSE;
}

// The text handed to the callback is cumulative, as the API contract requires, so it is grown in
// place: rebuilding it from every segment on each call walks the whole transcript again and turns a
// long recording's progress reporting into quadratic work.
struct fgt_segment_ctx {
    JNIEnv *env;
    jobject callback;
    jmethodID mid;
    char *text;
    size_t len;
    size_t cap;
    int reported;
};

static bool fgt_append_segment(struct fgt_segment_ctx *cb, const char *t) {
    if (t == NULL) {
        return true;
    }
    size_t add = strlen(t);
    if (cb->len + add + 1 > cb->cap) {
        size_t cap = cb->cap ? cb->cap : 1024;
        while (cb->len + add + 1 > cap) {
            cap *= 2;
        }
        char *grown = (char *) realloc(cb->text, cap);
        if (grown == NULL) {
            return false;
        }
        cb->text = grown;
        cb->cap = cap;
    }
    memcpy(cb->text + cb->len, t, add + 1);
    cb->len += add;
    return true;
}

static void fgt_emit_text(struct fgt_segment_ctx *cb) {
    if (cb->text == NULL) {
        return;
    }
    jstring js = (*cb->env)->NewStringUTF(cb->env, cb->text);
    (*cb->env)->CallVoidMethod(cb->env, cb->callback, cb->mid, js);
    if ((*cb->env)->ExceptionCheck(cb->env)) {
        (*cb->env)->ExceptionClear(cb->env);
    }
    (*cb->env)->DeleteLocalRef(cb->env, js);
}

static void fgt_new_segment(struct whisper_context *ctx, struct whisper_state *state, int n_new, void *user_data) {
    UNUSED(state);
    UNUSED(n_new);
    struct fgt_segment_ctx *cb = (struct fgt_segment_ctx *) user_data;
    if (cb == NULL || cb->callback == NULL || cb->mid == NULL) {
        return;
    }
    int n = whisper_full_n_segments(ctx);
    for (int i = cb->reported; i < n; i++) {
        if (!fgt_append_segment(cb, whisper_full_get_segment_text(ctx, i))) {
            cb->reported = i;
            return;
        }
    }
    cb->reported = n;
    fgt_emit_text(cb);
}

static void fgt_parakeet_new_segment(struct parakeet_context *ctx, struct parakeet_state *state, int n_new, void *user_data) {
    UNUSED(state);
    UNUSED(n_new);
    struct fgt_segment_ctx *cb = (struct fgt_segment_ctx *) user_data;
    if (cb == NULL || cb->callback == NULL || cb->mid == NULL) {
        return;
    }
    int n = parakeet_full_n_segments(ctx);
    for (int i = cb->reported; i < n; i++) {
        if (!fgt_append_segment(cb, parakeet_full_get_segment_text(ctx, i))) {
            cb->reported = i;
            return;
        }
    }
    cb->reported = n;
    fgt_emit_text(cb);
}

// Whisper reports how far through the audio it is, in whole percent. It fires on every decoded
// window, so the same percent arrives many times over — only the changes are worth a JNI call.
struct fgt_progress_ctx {
    JNIEnv *env;
    jobject callback;
    jmethodID mid;
    int reported;
};

static void fgt_progress(struct whisper_context *ctx, struct whisper_state *state, int progress, void *user_data) {
    UNUSED(ctx);
    UNUSED(state);
    struct fgt_progress_ctx *cb = (struct fgt_progress_ctx *) user_data;
    if (cb == NULL || cb->callback == NULL || cb->mid == NULL || progress == cb->reported) {
        return;
    }
    cb->reported = progress;
    (*cb->env)->CallVoidMethod(cb->env, cb->callback, cb->mid, (jint) progress);
    if ((*cb->env)->ExceptionCheck(cb->env)) {
        (*cb->env)->ExceptionClear(cb->env);
    }
}

static void fgt_parakeet_progress(struct parakeet_context *ctx, struct parakeet_state *state, int progress, void *user_data) {
    UNUSED(ctx);
    UNUSED(state);
    struct fgt_progress_ctx *cb = (struct fgt_progress_ctx *) user_data;
    if (cb == NULL || cb->callback == NULL || cb->mid == NULL || progress == cb->reported) {
        return;
    }
    cb->reported = progress;
    (*cb->env)->CallVoidMethod(cb->env, cb->callback, cb->mid, (jint) progress);
    if ((*cb->env)->ExceptionCheck(cb->env)) {
        (*cb->env)->ExceptionClear(cb->env);
    }
}

struct fgt_abort_flag {
    volatile int cancelled;
};

static bool fgt_should_abort(void *user_data) {
    struct fgt_abort_flag *flag = (struct fgt_abort_flag *) user_data;
    return flag != NULL && flag->cancelled != 0;
}

static bool fgt_encoder_begin(struct whisper_context *ctx, struct whisper_state *state, void *user_data) {
    UNUSED(ctx);
    UNUSED(state);
    return !fgt_should_abort(user_data);
}

static bool fgt_parakeet_encoder_begin(struct parakeet_context *ctx, struct parakeet_state *state, void *user_data) {
    UNUSED(ctx);
    UNUSED(state);
    return !fgt_should_abort(user_data);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_newAbortFlag(JNIEnv *env, jobject thiz) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_abort_flag *flag = (struct fgt_abort_flag *) calloc(1, sizeof(struct fgt_abort_flag));
    return (jlong) flag;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_setAbortFlag(JNIEnv *env, jobject thiz, jlong flag_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_abort_flag *flag = (struct fgt_abort_flag *) flag_ptr;
    if (flag != NULL) {
        flag->cancelled = 1;
    }
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeAbortFlag(JNIEnv *env, jobject thiz, jlong flag_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    free((void *) flag_ptr);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_languageCount(JNIEnv *env, jobject thiz) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_lang_max_id() + 1;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_languageId(JNIEnv *env, jobject thiz, jint index) {
    UNUSED(thiz);
    const char *code = whisper_lang_str(index);
    if (code == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, code);
}

static void fgt_whisper_transcribe(
        JNIEnv *env, struct whisper_context *context, jint num_threads, const float *samples, jint n_samples, jstring language, jstring prompt, jboolean suppress_non_speech, jstring vad_model, jobject segment_callback, jobject progress_callback, jlong abort_flag_ptr) {
    // The below adapted from the Objective-C iOS sample
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_nst = suppress_non_speech != JNI_FALSE;

    const char *lang_chars = NULL;
    if (language != NULL) {
        lang_chars = (*env)->GetStringUTFChars(env, language, NULL);
    }
    if (lang_chars != NULL && strlen(lang_chars) > 0) {
        params.language = lang_chars;
    } else {
        params.language = "auto";
    }
    params.detect_language = false;

    // Text decoded before this chunk, so a stream's words carry over a segment boundary.
    const char *prompt_chars = NULL;
    if (prompt != NULL) {
        prompt_chars = (*env)->GetStringUTFChars(env, prompt, NULL);
    }
    if (prompt_chars != NULL && strlen(prompt_chars) > 0) {
        params.initial_prompt = prompt_chars;
    }

    // With a VAD model whisper decodes only the stretches that hold speech: a recording with long
    // quiet spells finishes sooner, and the pauses stop being decoded into invented words. It
    // copies the speech into a second native buffer, so an hour of unbroken talk costs about twice
    // the audio in memory while it runs — which is why this is the caller's choice, not the default.
    const char *vad_chars = NULL;
    if (vad_model != NULL) {
        vad_chars = (*env)->GetStringUTFChars(env, vad_model, NULL);
    }
    if (vad_chars != NULL && strlen(vad_chars) > 0) {
        params.vad = true;
        params.vad_model_path = vad_chars;
    }

    struct fgt_segment_ctx seg_ctx;
    seg_ctx.env = env;
    seg_ctx.callback = segment_callback;
    seg_ctx.mid = NULL;
    seg_ctx.text = NULL;
    seg_ctx.len = 0;
    seg_ctx.cap = 0;
    seg_ctx.reported = 0;
    if (segment_callback != NULL) {
        jclass cb_class = (*env)->GetObjectClass(env, segment_callback);
        seg_ctx.mid = (*env)->GetMethodID(env, cb_class, "onSegment", "(Ljava/lang/String;)V");
        if (seg_ctx.mid != NULL) {
            params.new_segment_callback = fgt_new_segment;
            params.new_segment_callback_user_data = &seg_ctx;
        }
    }

    struct fgt_progress_ctx prog_ctx;
    prog_ctx.env = env;
    prog_ctx.callback = progress_callback;
    prog_ctx.mid = NULL;
    prog_ctx.reported = -1;
    if (progress_callback != NULL) {
        jclass cb_class = (*env)->GetObjectClass(env, progress_callback);
        prog_ctx.mid = (*env)->GetMethodID(env, cb_class, "onProgress", "(I)V");
        if (prog_ctx.mid != NULL) {
            params.progress_callback = fgt_progress;
            params.progress_callback_user_data = &prog_ctx;
        }
    }

    struct fgt_abort_flag *abort_flag = (struct fgt_abort_flag *) abort_flag_ptr;
    if (abort_flag != NULL) {
        params.abort_callback = fgt_should_abort;
        params.abort_callback_user_data = abort_flag;
        params.encoder_begin_callback = fgt_encoder_begin;
        params.encoder_begin_callback_user_data = abort_flag;
    }

    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    int result = whisper_full(context, params, samples, n_samples);
    // A VAD model that fails to load takes the whole run down with it, and an empty transcript is
    // a poor answer to a broken detector. Decoding the quiet parts as well is slower, but it is
    // the result the user asked for. A cancelled run fails the same way and must stay cancelled.
    if (result != 0 && params.vad && !fgt_should_abort(abort_flag)) {
        LOGW("Transcription with VAD failed, retrying without it");
        params.vad = false;
        params.vad_model_path = NULL;
        seg_ctx.len = 0;
        seg_ctx.reported = 0;
        result = whisper_full(context, params, samples, n_samples);
    }
    if (result != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }
    free(seg_ctx.text);
    if (lang_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, language, lang_chars);
    }
    if (prompt_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, prompt, prompt_chars);
    }
    if (vad_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, vad_model, vad_chars);
    }
}

static void fgt_parakeet_transcribe(
        JNIEnv *env, struct parakeet_context *context, jint num_threads, const float *samples, jint n_samples, jobject segment_callback, jobject progress_callback, jlong abort_flag_ptr) {
    struct parakeet_full_params params = parakeet_full_default_params(PARAKEET_SAMPLING_GREEDY);
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;

    struct fgt_segment_ctx seg_ctx;
    seg_ctx.env = env;
    seg_ctx.callback = segment_callback;
    seg_ctx.mid = NULL;
    seg_ctx.text = NULL;
    seg_ctx.len = 0;
    seg_ctx.cap = 0;
    seg_ctx.reported = 0;
    if (segment_callback != NULL) {
        jclass cb_class = (*env)->GetObjectClass(env, segment_callback);
        seg_ctx.mid = (*env)->GetMethodID(env, cb_class, "onSegment", "(Ljava/lang/String;)V");
        if (seg_ctx.mid != NULL) {
            params.new_segment_callback = fgt_parakeet_new_segment;
            params.new_segment_callback_user_data = &seg_ctx;
        }
    }

    struct fgt_progress_ctx prog_ctx;
    prog_ctx.env = env;
    prog_ctx.callback = progress_callback;
    prog_ctx.mid = NULL;
    prog_ctx.reported = -1;
    if (progress_callback != NULL) {
        jclass cb_class = (*env)->GetObjectClass(env, progress_callback);
        prog_ctx.mid = (*env)->GetMethodID(env, cb_class, "onProgress", "(I)V");
        if (prog_ctx.mid != NULL) {
            params.progress_callback = fgt_parakeet_progress;
            params.progress_callback_user_data = &prog_ctx;
        }
    }

    struct fgt_abort_flag *abort_flag = (struct fgt_abort_flag *) abort_flag_ptr;
    if (abort_flag != NULL) {
        params.abort_callback = fgt_should_abort;
        params.abort_callback_user_data = abort_flag;
        params.encoder_begin_callback = fgt_parakeet_encoder_begin;
        params.encoder_begin_callback_user_data = abort_flag;
    }

    parakeet_reset_timings(context);

    LOGI("About to run parakeet_full");
    if (parakeet_full(context, params, samples, n_samples) != 0) {
        LOGI("Failed to run the model");
    } else {
        parakeet_print_timings(context);
    }
    free(seg_ctx.text);
}

static void fgt_full_transcribe(
        JNIEnv *env, jlong context_ptr, jint num_threads, const float *samples, jint n_samples, jstring language, jstring prompt, jboolean suppress_non_speech, jstring vad_model, jobject segment_callback, jobject progress_callback, jlong abort_flag_ptr) {
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    if (context == NULL) {
        return;
    }
    if (context->parakeet != NULL) {
        fgt_parakeet_transcribe(env, context->parakeet, num_threads, samples, n_samples, segment_callback, progress_callback, abort_flag_ptr);
    } else {
        fgt_whisper_transcribe(env, context->whisper, num_threads, samples, n_samples, language, prompt, suppress_non_speech, vad_model, segment_callback, progress_callback, abort_flag_ptr);
    }
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data, jstring language, jobject segment_callback, jlong abort_flag_ptr) {
    UNUSED(thiz);
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    fgt_full_transcribe(env, context_ptr, num_threads, audio_data_arr, audio_data_length, language, NULL, JNI_FALSE, NULL, segment_callback, NULL, abort_flag_ptr);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

// Reads the samples from a direct buffer, so long recordings never need a Java-heap array.
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribeDirect(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jobject audio_buffer, jint n_samples, jstring language, jstring prompt, jboolean suppress_non_speech, jstring vad_model, jobject segment_callback, jobject progress_callback, jlong abort_flag_ptr) {
    UNUSED(thiz);
    const float *samples = (const float *) (*env)->GetDirectBufferAddress(env, audio_buffer);
    if (samples == NULL || n_samples <= 0) {
        LOGW("No direct buffer address, skipping transcription");
        return;
    }
    fgt_full_transcribe(env, context_ptr, num_threads, samples, n_samples, language, prompt, suppress_non_speech, vad_model, segment_callback, progress_callback, abort_flag_ptr);
}

// The language whisper used for the last run, so a stream can pin auto-detection to its first
// chunk instead of re-detecting on every one.
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullLangId(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    if (context == NULL || context->whisper == NULL) {
        return NULL;
    }
    const char *code = whisper_lang_str(whisper_full_lang_id(context->whisper));
    if (code == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, code);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    return context->parakeet != NULL
           ? parakeet_full_n_segments(context->parakeet)
           : whisper_full_n_segments(context->whisper);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    const char *text = context->parakeet != NULL
                       ? parakeet_full_get_segment_text(context->parakeet, index)
                       : whisper_full_get_segment_text(context->whisper, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    return context->parakeet != NULL
           ? parakeet_full_get_segment_t0(context->parakeet, index)
           : whisper_full_get_segment_t0(context->whisper, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    return context->parakeet != NULL
           ? parakeet_full_get_segment_t1(context->parakeet, index)
           : whisper_full_get_segment_t1(context->whisper, index);
}

// The stretches the VAD found speech in, on the original recording's timeline. The decoded
// segments run edge to edge — a removed silence ends up inside one of them, never between two —
// so these spans are the only place the pauses survive. Empty when the run had no VAD.
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSpeechSpanCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    return context->whisper != NULL ? whisper_full_n_vad_segments(context->whisper) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSpeechSpanT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    return whisper_full_get_vad_segment_t0(context->whisper, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSpeechSpanT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct fgt_context *context = (struct fgt_context *) context_ptr;
    return whisper_full_get_vad_segment_t1(context->whisper, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                          jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
