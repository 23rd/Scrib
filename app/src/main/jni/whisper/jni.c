#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

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

    struct whisper_context *context = NULL;
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

    context = whisper_init(&loader);
    return (jlong) context;
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
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.flash_attn = true;
    context = whisper_init_from_file_with_params(model_path_chars, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
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

static void fgt_new_segment(struct whisper_context *ctx, struct whisper_state *state, int n_new, void *user_data) {
    UNUSED(state);
    UNUSED(n_new);
    struct fgt_segment_ctx *cb = (struct fgt_segment_ctx *) user_data;
    if (cb == NULL || cb->callback == NULL || cb->mid == NULL) {
        return;
    }
    int n = whisper_full_n_segments(ctx);
    for (int i = cb->reported; i < n; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t == NULL) {
            continue;
        }
        size_t add = strlen(t);
        if (cb->len + add + 1 > cb->cap) {
            size_t cap = cb->cap ? cb->cap : 1024;
            while (cb->len + add + 1 > cap) {
                cap *= 2;
            }
            char *grown = (char *) realloc(cb->text, cap);
            if (grown == NULL) {
                cb->reported = i;
                return;
            }
            cb->text = grown;
            cb->cap = cap;
        }
        memcpy(cb->text + cb->len, t, add + 1);
        cb->len += add;
    }
    cb->reported = n;
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

static void fgt_full_transcribe(
        JNIEnv *env, jlong context_ptr, jint num_threads, const float *samples, jint n_samples, jstring language, jstring prompt, jboolean suppress_non_speech, jobject segment_callback, jobject progress_callback, jlong abort_flag_ptr) {
    struct whisper_context *context = (struct whisper_context *) context_ptr;

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
    if (whisper_full(context, params, samples, n_samples) != 0) {
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
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data, jstring language, jobject segment_callback, jlong abort_flag_ptr) {
    UNUSED(thiz);
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    fgt_full_transcribe(env, context_ptr, num_threads, audio_data_arr, audio_data_length, language, NULL, JNI_FALSE, segment_callback, NULL, abort_flag_ptr);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

// Reads the samples from a direct buffer, so long recordings never need a Java-heap array.
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribeDirect(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jobject audio_buffer, jint n_samples, jstring language, jstring prompt, jboolean suppress_non_speech, jobject segment_callback, jobject progress_callback, jlong abort_flag_ptr) {
    UNUSED(thiz);
    const float *samples = (const float *) (*env)->GetDirectBufferAddress(env, audio_buffer);
    if (samples == NULL || n_samples <= 0) {
        LOGW("No direct buffer address, skipping transcription");
        return;
    }
    fgt_full_transcribe(env, context_ptr, num_threads, samples, n_samples, language, prompt, suppress_non_speech, segment_callback, progress_callback, abort_flag_ptr);
}

// The language whisper used for the last run, so a stream can pin auto-detection to its first
// chunk instead of re-detecting on every one.
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullLangId(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *code = whisper_lang_str(whisper_full_lang_id(context));
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
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
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
