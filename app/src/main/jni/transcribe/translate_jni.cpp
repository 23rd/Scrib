// llama.cpp bridge, kotlin pulls tokens one by one so no callbacks needed

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "scrib_shared.h"

#define TAG "ScribTranslate"

namespace {

struct llm {
    llama_model        *model = nullptr;
    llama_context      *ctx = nullptr;
    const llama_vocab  *vocab = nullptr;
    llama_sampler      *sampler = nullptr;
    std::string         arch;
    int                 n_batch = 512;
    // generation in flight
    bool                generating = false;
    int                 n_generated = 0;
    int                 max_new = 0;
    int                 n_prompt = 0;
};

void llm_log(ggml_log_level level, const char *text, void *) {
    if (text == nullptr) {
        return;
    }
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) {
        priority = ANDROID_LOG_ERROR;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        priority = ANDROID_LOG_WARN;
    } else if (level == GGML_LOG_LEVEL_INFO) {
        priority = ANDROID_LOG_INFO;
    }
    // drop the trailing newline, logcat adds its own
    size_t len = strlen(text);
    std::string line(text, len > 0 && text[len - 1] == '\n' ? len - 1 : len);
    if (!line.empty()) {
        __android_log_write(priority, TAG, line.c_str());
    }
}

std::string jstr(JNIEnv *env, jstring value) {
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

std::string meta(llama_model *model, const char *key) {
    char buf[512];
    int n = llama_model_meta_val_str(model, key, buf, sizeof(buf));
    return n < 0 ? std::string() : std::string(buf, static_cast<size_t>(n) < sizeof(buf) ? n : sizeof(buf) - 1);
}

void throw_runtime(JNIEnv *env, const std::string &message) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), message.c_str());
}

// decode n_batch tokens at a time, false on failure or abort
bool decode_tokens(llm *h, std::vector<llama_token> &tokens) {
    for (size_t i = 0; i < tokens.size(); i += h->n_batch) {
        int n = static_cast<int>(std::min<size_t>(h->n_batch, tokens.size() - i));
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        int ret = llama_decode(h->ctx, batch);
        if (ret != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "llama_decode failed: %d", ret);
            return false;
        }
    }
    return true;
}

}

bool abort_reached(void *user);

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_scrib_transcriber_LlamaLib_00024Companion_nativeLoad(JNIEnv *env, jobject, jstring path, jint threads, jint nCtx) {
    static bool logging_set = false;
    if (!logging_set) {
        logging_set = true;
        llama_log_set(llm_log, nullptr);
    }
    load_cpu_backend();
    llama_backend_init();

    std::string file = jstr(env, path);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(file.c_str(), mparams);
    if (model == nullptr) {
        throw_runtime(env, "Could not load translation model " + file);
        return 0;
    }

    auto *h = new (std::nothrow) llm();
    if (h == nullptr) {
        llama_model_free(model);
        throw_runtime(env, "Out of memory");
        return 0;
    }
    h->model = model;
    h->vocab = llama_model_get_vocab(model);
    h->arch = meta(model, "general.architecture");

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? nCtx : 4096;
    cparams.n_batch = h->n_batch;
    cparams.n_ubatch = h->n_batch;
    cparams.n_threads = threads > 0 ? threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.no_perf = true;
    h->ctx = llama_init_from_model(model, cparams);
    if (h->ctx == nullptr) {
        llama_model_free(model);
        delete h;
        throw_runtime(env, "Could not create a context for " + file);
        return 0;
    }

    // greedy with a mild repetition penalty so small models dont loop
    h->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(h->sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(h->vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(h->sampler, llama_sampler_init_greedy());

    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));
    __android_log_print(ANDROID_LOG_INFO, TAG, "Loaded %s (%s), ctx %u, %d threads", desc, h->arch.c_str(), cparams.n_ctx, cparams.n_threads);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_org_scrib_transcriber_LlamaLib_00024Companion_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<llm *>(ptr);
    if (h == nullptr) {
        return;
    }
    if (h->sampler != nullptr) {
        llama_sampler_free(h->sampler);
    }
    if (h->ctx != nullptr) {
        llama_free(h->ctx);
    }
    if (h->model != nullptr) {
        llama_model_free(h->model);
    }
    delete h;
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_LlamaLib_00024Companion_nativeDescription(JNIEnv *env, jobject, jlong ptr) {
    auto *h = reinterpret_cast<llm *>(ptr);
    if (h == nullptr) {
        return nullptr;
    }
    char desc[256];
    llama_model_desc(h->model, desc, sizeof(desc));
    return env->NewStringUTF(desc);
}

JNIEXPORT jstring JNICALL
Java_org_scrib_transcriber_LlamaLib_00024Companion_nativeArch(JNIEnv *env, jobject, jlong ptr) {
    auto *h = reinterpret_cast<llm *>(ptr);
    return h == nullptr ? nullptr : env->NewStringUTF(h->arch.c_str());
}

// fresh exchange, returns prompt token count or -1 if it wont fit
JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_LlamaLib_00024Companion_nativeBegin(JNIEnv *env, jobject, jlong ptr, jstring message,
                                                                jint maxNew, jlong abortPtr) {
    auto *h = reinterpret_cast<llm *>(ptr);
    if (h == nullptr) {
        throw_runtime(env, "No model loaded");
        return -1;
    }
    h->generating = false;
    llama_set_abort_callback(h->ctx, abortPtr != 0 ? abort_reached : nullptr, reinterpret_cast<void *>(abortPtr));
    llama_memory_clear(llama_get_memory(h->ctx), true);
    llama_sampler_reset(h->sampler);

    std::string user = jstr(env, message);
    const char *tmpl = llama_model_chat_template(h->model, nullptr);
    if (tmpl == nullptr) {
        tmpl = "chatml";
    }
    llama_chat_message msg = {"user", user.c_str()};
    std::vector<char> formatted(user.size() * 2 + 1024);
    int len = llama_chat_apply_template(tmpl, &msg, 1, true, formatted.data(), static_cast<int32_t>(formatted.size()));
    if (len > static_cast<int>(formatted.size())) {
        formatted.resize(static_cast<size_t>(len) + 1);
        len = llama_chat_apply_template(tmpl, &msg, 1, true, formatted.data(), static_cast<int32_t>(formatted.size()));
    }
    std::string prompt;
    if (len < 0) {
        // unknown template, chatml is the usual fallback
        __android_log_write(ANDROID_LOG_WARN, TAG, "Unknown chat template, falling back to chatml");
        prompt = "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
    } else {
        prompt.assign(formatted.data(), static_cast<size_t>(len));
    }
    // qwen thinks out loud unless handed an empty think block
    if (h->arch == "qwen3" || h->arch == "qwen3moe" || h->arch == "qwen35" || h->arch == "qwen35moe") {
        prompt += "<think>\n\n</think>\n\n";
    }

    int n_tokens = -llama_tokenize(h->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n_tokens <= 0) {
        throw_runtime(env, "Could not tokenize the prompt");
        return -1;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_tokens));
    if (llama_tokenize(h->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n_tokens, true, true) < 0) {
        throw_runtime(env, "Could not tokenize the prompt");
        return -1;
    }
    int n_ctx = static_cast<int>(llama_n_ctx(h->ctx));
    if (n_tokens + maxNew > n_ctx) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Prompt of %d tokens plus %d of answer exceeds context %d", n_tokens, maxNew, n_ctx);
        return -1;
    }
    if (!decode_tokens(h, tokens)) {
        auto *flag = reinterpret_cast<abort_flag *>(abortPtr);
        if (flag != nullptr && flag->cancelled.load()) {
            return -2;
        }
        throw_runtime(env, "Decoding the prompt failed");
        return -1;
    }
    h->n_prompt = n_tokens;
    h->n_generated = 0;
    h->max_new = maxNew;
    h->generating = true;
    return n_tokens;
}

// next piece as raw utf-8 bytes (tokens can split a character), null when done
JNIEXPORT jbyteArray JNICALL
Java_org_scrib_transcriber_LlamaLib_00024Companion_nativeNext(JNIEnv *env, jobject, jlong ptr) {
    auto *h = reinterpret_cast<llm *>(ptr);
    if (h == nullptr || !h->generating) {
        return nullptr;
    }
    if (h->n_generated >= h->max_new) {
        h->generating = false;
        return nullptr;
    }
    llama_token id = llama_sampler_sample(h->sampler, h->ctx, -1);
    if (llama_vocab_is_eog(h->vocab, id)) {
        h->generating = false;
        return nullptr;
    }
    char buf[512];
    int n = llama_token_to_piece(h->vocab, id, buf, sizeof(buf), 0, true);
    if (n < 0) {
        n = 0;
    }
    h->n_generated++;
    llama_batch batch = llama_batch_get_one(&id, 1);
    if (llama_decode(h->ctx, batch) != 0) {
        // cancelled or failed, either way we are done
        h->generating = false;
    }
    jbyteArray out = env->NewByteArray(n);
    if (out != nullptr && n > 0) {
        env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(buf));
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_LlamaLib_00024Companion_nativeGenerated(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<llm *>(ptr);
    return h == nullptr ? 0 : h->n_generated;
}

}
