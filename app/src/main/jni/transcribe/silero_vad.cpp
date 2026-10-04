// Silero VAD on ggml: finds the speech in a recording so the model is only run over the parts that
// carry it. The model file is the same ggml-silero-v5.1.2.bin whisper.cpp downloads, and the graph
// and the probabilities-to-spans state machine are a port of its implementation, which is MIT
// licensed and Copyright (c) 2023-2025 Georgi Gerganov. Ours differs in three ways: it runs on the
// cpu backend the rest of the app already has, it keeps one backend instead of picking a buffer
// type per tensor, and it reports milliseconds instead of centiseconds.

#include "silero_vad.h"

#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <limits>
#include <map>
#include <string>
#include <vector>

namespace {

const char *kTag = "ScribVad";

void vad_log(const char *fmt, ...) {
    char message[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(message, sizeof(message), fmt, args);
    va_end(args);
    __android_log_write(ANDROID_LOG_WARN, kTag, message);
}

const int kSampleRate = 16000;
const uint32_t kGgmlMagic = 0x67676d6c;

struct vad_hparams {
    int32_t n_encoder_layers = 0;
    std::vector<int32_t> encoder_in_channels;
    std::vector<int32_t> encoder_out_channels;
    std::vector<int32_t> kernel_sizes;
    int32_t lstm_input_size = 0;
    int32_t lstm_hidden_size = 0;
    int32_t final_conv_in = 0;
    int32_t final_conv_out = 0;
};

struct reader {
    std::FILE *file = nullptr;

    ~reader() {
        if (file != nullptr) {
            std::fclose(file);
        }
    }

    bool open(const char *path) {
        file = std::fopen(path, "rb");
        return file != nullptr;
    }

    template <typename T> bool read(T &value) {
        return std::fread(&value, sizeof(T), 1, file) == 1;
    }

    bool read_raw(void *out, size_t bytes) {
        return std::fread(out, 1, bytes, file) == bytes;
    }

    long tell() const {
        return file == nullptr ? -1 : std::ftell(file);
    }

    bool at_end() {
        const int byte = std::fgetc(file);
        if (byte == EOF) {
            return true;
        }
        std::ungetc(byte, file);
        return false;
    }
};

}

struct vad_context {
    vad_hparams hparams;

    int n_window = 512;
    int n_context = 64;
    int n_threads = 4;

    ggml_backend_t backend = nullptr;
    ggml_context *ctx_model = nullptr;
    ggml_context *ctx_state = nullptr;
    ggml_backend_buffer_t buffer = nullptr;
    ggml_backend_buffer_t state_buffer = nullptr;
    ggml_backend_sched_t sched = nullptr;

    ggml_tensor *stft_forward_basis = nullptr;
    ggml_tensor *encoder_weights[4] = {nullptr, nullptr, nullptr, nullptr};
    ggml_tensor *encoder_biases[4] = {nullptr, nullptr, nullptr, nullptr};
    ggml_tensor *lstm_ih_weight = nullptr;
    ggml_tensor *lstm_hh_weight = nullptr;
    ggml_tensor *lstm_ih_bias = nullptr;
    ggml_tensor *lstm_hh_bias = nullptr;
    ggml_tensor *final_conv_weight = nullptr;
    ggml_tensor *final_conv_bias = nullptr;

    ggml_tensor *h_state = nullptr;
    ggml_tensor *c_state = nullptr;

    std::vector<float> probs;
};

namespace {

bool create_tensors(vad_context *ctx) {
    const vad_hparams &hp = ctx->hparams;
    const int hidden = hp.lstm_hidden_size;
    const int hstate_dim = hidden * 4;

    ggml_init_params meta = {
        /*.mem_size   =*/ 32 * ggml_tensor_overhead(),
        /*.mem_buffer =*/ nullptr,
        /*.no_alloc   =*/ true,
    };
    ggml_context *meta_ctx = ggml_init(meta);
    if (meta_ctx == nullptr) {
        return false;
    }

    auto declare = [&](const char *name, ggml_tensor *shape) {
        ggml_tensor *tensor = ggml_dup_tensor(ctx->ctx_model, shape);
        ggml_set_name(tensor, name);
        return tensor;
    };

    ctx->stft_forward_basis =
        declare("_model.stft.forward_basis_buffer", ggml_new_tensor_3d(meta_ctx, GGML_TYPE_F16, 256, 1, 258));
    for (int i = 0; i < hp.n_encoder_layers && i < 4; ++i) {
        const std::string prefix = "_model.encoder." + std::to_string(i) + ".reparam_conv.";
        ctx->encoder_weights[i] = declare(
            (prefix + "weight").c_str(),
            ggml_new_tensor_3d(meta_ctx, GGML_TYPE_F16, hp.kernel_sizes[i], hp.encoder_in_channels[i],
                               hp.encoder_out_channels[i]));
        ctx->encoder_biases[i] = declare(
            (prefix + "bias").c_str(),
            ggml_new_tensor_1d(meta_ctx, GGML_TYPE_F32, hp.encoder_out_channels[i]));
    }
    ctx->lstm_ih_weight =
        declare("_model.decoder.rnn.weight_ih", ggml_new_tensor_2d(meta_ctx, GGML_TYPE_F32, hidden, hstate_dim));
    ctx->lstm_hh_weight =
        declare("_model.decoder.rnn.weight_hh", ggml_new_tensor_2d(meta_ctx, GGML_TYPE_F32, hidden, hstate_dim));
    ctx->lstm_ih_bias =
        declare("_model.decoder.rnn.bias_ih", ggml_new_tensor_1d(meta_ctx, GGML_TYPE_F32, hstate_dim));
    ctx->lstm_hh_bias =
        declare("_model.decoder.rnn.bias_hh", ggml_new_tensor_1d(meta_ctx, GGML_TYPE_F32, hstate_dim));
    ctx->final_conv_weight = declare("_model.decoder.decoder.2.weight",
                                     ggml_new_tensor_2d(meta_ctx, GGML_TYPE_F16, hp.final_conv_in, 1));
    ctx->final_conv_bias =
        declare("_model.decoder.decoder.2.bias", ggml_new_tensor_1d(meta_ctx, GGML_TYPE_F32, 1));

    ggml_free(meta_ctx);
    return true;
}

bool load_weights(vad_context *ctx, reader &rd) {
    std::map<std::string, ggml_tensor *> expected;
    auto add = [&](ggml_tensor *tensor) {
        if (tensor != nullptr) {
            expected[ggml_get_name(tensor)] = tensor;
        }
    };
    add(ctx->stft_forward_basis);
    for (int i = 0; i < 4; ++i) {
        add(ctx->encoder_weights[i]);
        add(ctx->encoder_biases[i]);
    }
    add(ctx->lstm_ih_weight);
    add(ctx->lstm_hh_weight);
    add(ctx->lstm_ih_bias);
    add(ctx->lstm_hh_bias);
    add(ctx->final_conv_weight);
    add(ctx->final_conv_bias);

    int loaded = 0;
    std::string name_hint = "(the first one)";
    while (!rd.at_end()) {
        int32_t n_dims = 0;
        int32_t name_len = 0;
        int32_t type = 0;
        if (!rd.read(n_dims) || !rd.read(name_len) || !rd.read(type) || n_dims < 0 || name_len < 0) {
            vad_log("could not read a tensor header at the end of the file");
            return false;
        }
        int64_t ne[4] = {1, 1, 1, 1};
        int64_t nelements = 1;
        for (int i = 0; i < n_dims; ++i) {
            int32_t dim = 0;
            if (!rd.read(dim)) {
                vad_log("could not read the shape of %s", name_hint.c_str());
                return false;
            }
            ne[i] = dim;
            nelements *= dim;
        }
        std::string name(static_cast<size_t>(name_len), '\0');
        if (name_len > 0 && !rd.read_raw(&name[0], static_cast<size_t>(name_len))) {
            vad_log("could not read the name of a tensor");
            return false;
        }

        auto found = expected.find(name);
        if (found == expected.end()) {
            vad_log("unexpected tensor %s", name.c_str());
            return false;
        }
        ggml_tensor *tensor = found->second;
        if (ggml_nelements(tensor) != nelements || tensor->ne[0] != ne[0] || tensor->ne[1] != ne[1] ||
            tensor->ne[2] != ne[2] || static_cast<int32_t>(ggml_type(tensor->type)) != type) {
            vad_log("%s: file [%lld %lld %lld] type %d, model [%lld %lld %lld] type %d", name.c_str(),
                    (long long) ne[0], (long long) ne[1], (long long) ne[2], type,
                    (long long) tensor->ne[0], (long long) tensor->ne[1], (long long) tensor->ne[2],
                    (int) ggml_type(tensor->type));
            return false;
        }
        if (!rd.read_raw(tensor->data, ggml_nbytes(tensor))) {
            vad_log("could not read the weights of %s", name.c_str());
            return false;
        }
        ++loaded;
    }
    if (loaded != static_cast<int>(expected.size())) {
        vad_log("read %d of %zu weights", loaded, expected.size());
    }
    return loaded == static_cast<int>(expected.size());
}

ggml_tensor *build_stft_layer(ggml_context *ctx, vad_context *vctx, ggml_tensor *cur) {
    ggml_tensor *padded = ggml_pad_reflect_1d(ctx, cur, 64, 64);
    ggml_tensor *stft = ggml_conv_1d(ctx, vctx->stft_forward_basis, padded, vctx->hparams.lstm_input_size, 0, 1);
    const int cutoff = static_cast<int>(vctx->stft_forward_basis->ne[2]) / 2;
    ggml_tensor *real_part = ggml_view_2d(ctx, stft, 4, cutoff, stft->nb[1], 0);
    ggml_tensor *imag_part = ggml_view_2d(ctx, stft, 4, cutoff, stft->nb[1], cutoff * stft->nb[1]);
    ggml_tensor *sum_squares =
        ggml_add(ctx, ggml_mul(ctx, real_part, real_part), ggml_mul(ctx, imag_part, imag_part));
    return ggml_sqrt(ctx, sum_squares);
}

ggml_tensor *build_encoder_layer(ggml_context *ctx, vad_context *vctx, ggml_tensor *cur) {
    const int out_channels[4] = {
            vctx->hparams.encoder_out_channels[0],
            vctx->hparams.encoder_out_channels[1],
            vctx->hparams.encoder_out_channels[2],
            vctx->hparams.encoder_out_channels[3],
    };
    const int stride[4] = {1, 2, 2, 1};
    for (int i = 0; i < 4; ++i) {
        cur = ggml_conv_1d(ctx, vctx->encoder_weights[i], cur, stride[i], 1, 1);
        cur = ggml_add(ctx, cur, ggml_reshape_3d(ctx, vctx->encoder_biases[i], 1, out_channels[i], 1));
        cur = ggml_relu(ctx, cur);
    }
    return cur;
}

ggml_tensor *build_lstm_layer(ggml_context *ctx, vad_context *vctx, ggml_tensor *cur, ggml_cgraph *gf) {
    const int hidden = vctx->hparams.lstm_hidden_size;
    ggml_tensor *x_t = ggml_cont(ctx, ggml_transpose(ctx, cur));

    ggml_tensor *inp_gate = ggml_add(ctx, ggml_mul_mat(ctx, vctx->lstm_ih_weight, x_t), vctx->lstm_ih_bias);
    ggml_tensor *hid_gate = ggml_add(ctx, ggml_mul_mat(ctx, vctx->lstm_hh_weight, vctx->h_state), vctx->lstm_hh_bias);
    ggml_tensor *out_gate = ggml_add(ctx, inp_gate, hid_gate);

    const size_t gate_bytes = ggml_row_size(out_gate->type, hidden);
    ggml_tensor *i_t = ggml_sigmoid(ctx, ggml_view_1d(ctx, out_gate, hidden, 0 * gate_bytes));
    ggml_tensor *f_t = ggml_sigmoid(ctx, ggml_view_1d(ctx, out_gate, hidden, 1 * gate_bytes));
    ggml_tensor *g_t = ggml_tanh(ctx, ggml_view_1d(ctx, out_gate, hidden, 2 * gate_bytes));
    ggml_tensor *o_t = ggml_sigmoid(ctx, ggml_view_1d(ctx, out_gate, hidden, 3 * gate_bytes));

    ggml_tensor *c_out =
        ggml_add(ctx, ggml_mul(ctx, f_t, vctx->c_state), ggml_mul(ctx, i_t, g_t));
    ggml_build_forward_expand(gf, ggml_cpy(ctx, c_out, vctx->c_state));

    ggml_tensor *out = ggml_mul(ctx, o_t, ggml_tanh(ctx, c_out));
    ggml_build_forward_expand(gf, ggml_cpy(ctx, out, vctx->h_state));
    return out;
}

ggml_cgraph *build_graph(ggml_context *ctx, vad_context *vctx) {
    ggml_cgraph *gf = ggml_new_graph_custom(ctx, GGML_DEFAULT_GRAPH_SIZE, false);

    ggml_tensor *frame = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, vctx->n_window, 1);
    ggml_set_name(frame, "frame");
    ggml_set_input(frame);

    ggml_tensor *cur = build_stft_layer(ctx, vctx, frame);
    cur = build_encoder_layer(ctx, vctx, cur);
    cur = ggml_view_2d(ctx, cur, 1, 128, cur->nb[1], 0);
    cur = build_lstm_layer(ctx, vctx, cur, gf);
    cur = ggml_relu(ctx, cur);
    cur = ggml_conv_1d(ctx, vctx->final_conv_weight, cur, 1, 0, 1);
    cur = ggml_add(ctx, cur, vctx->final_conv_bias);
    cur = ggml_sigmoid(ctx, cur);
    ggml_set_name(cur, "prob");
    ggml_set_output(cur);

    ggml_build_forward_expand(gf, cur);
    return gf;
}

bool spans_from_probs(const vad_context *vctx, const vad_params &params, int n_samples,
                      std::vector<std::pair<int64_t, int64_t>> &out) {
    const std::vector<float> &probs = vctx->probs;
    const int n_probs = static_cast<int>(probs.size());
    const int window = vctx->n_window;

    const int min_speech_samples = kSampleRate * params.min_speech_duration_ms / 1000;
    const int min_silence_samples = kSampleRate * params.min_silence_duration_ms / 1000;
    const int speech_pad_samples = kSampleRate * params.speech_pad_ms / 1000;
    const int audio_length = n_samples;

    int max_speech_samples = std::numeric_limits<int>::max() / 2;
    if (params.max_speech_duration_s <= 100000.0f) {
        const int64_t limit = static_cast<int64_t>(kSampleRate) *
                                  static_cast<int64_t>(params.max_speech_duration_s) -
                              window - 2 * speech_pad_samples;
        max_speech_samples = limit > 0 ? static_cast<int>(std::min<int64_t>(limit, max_speech_samples))
                                       : std::numeric_limits<int>::max() / 2;
    }
    const int min_silence_at_max_speech = kSampleRate * 98 / 1000;

    float neg_threshold = params.threshold - 0.15f;
    if (neg_threshold < 0.01f) {
        neg_threshold = 0.01f;
    }

    struct span {
        int start;
        int end;
    };
    std::vector<span> speeches;

    bool in_speech = false;
    int temp_end = 0;
    int prev_end = 0;
    int next_start = 0;
    int speech_start = 0;
    bool has_speech = false;

    for (int i = 0; i < n_probs; ++i) {
        const float prob = probs[i];
        const int at = window * i;

        if (prob >= params.threshold && temp_end != 0) {
            temp_end = 0;
            if (next_start < prev_end) {
                next_start = at;
            }
        }

        if (prob >= params.threshold && !in_speech) {
            in_speech = true;
            speech_start = at;
            has_speech = true;
            continue;
        }

        if (in_speech && (at - speech_start) > max_speech_samples) {
            if (prev_end != 0) {
                speeches.push_back({speech_start, prev_end});
                has_speech = true;
                if (next_start < prev_end) {
                    in_speech = false;
                    has_speech = false;
                } else {
                    speech_start = next_start;
                }
                prev_end = next_start = temp_end = 0;
            } else {
                speeches.push_back({speech_start, at});
                prev_end = next_start = temp_end = 0;
                in_speech = false;
                has_speech = false;
                continue;
            }
        }

        if (prob < neg_threshold && in_speech) {
            if (temp_end == 0) {
                temp_end = at;
            }
            if ((at - temp_end) > min_silence_at_max_speech) {
                prev_end = temp_end;
            }
            if ((at - temp_end) < min_silence_samples) {
                continue;
            }
            if ((temp_end - speech_start) > min_speech_samples) {
                speeches.push_back({speech_start, temp_end});
            }
            prev_end = next_start = temp_end = 0;
            in_speech = false;
            has_speech = false;
            continue;
        }
    }

    if (has_speech && (audio_length - speech_start) > min_speech_samples) {
        speeches.push_back({speech_start, audio_length});
    }

    for (size_t i = 0; i + 1 < speeches.size();) {
        const int max_merge_gap = kSampleRate * 200 / 1000;
        if (speeches[i + 1].start - speeches[i].end < max_merge_gap) {
            speeches[i].end = speeches[i + 1].end;
            speeches.erase(speeches.begin() + static_cast<long>(i) + 1);
        } else {
            ++i;
        }
    }

    speeches.erase(std::remove_if(speeches.begin(), speeches.end(),
                                  [&](const span &s) { return s.end - s.start < min_speech_samples; }),
                   speeches.end());

    out.clear();
    for (size_t i = 0; i < speeches.size(); ++i) {
        int start = speeches[i].start;
        int end = speeches[i].end;
        if (i == 0) {
            start = start > speech_pad_samples ? start - speech_pad_samples : 0;
        }
        if (i + 1 < speeches.size()) {
            const int silence = speeches[i + 1].start - speeches[i].end;
            if (silence < 2 * speech_pad_samples) {
                end += silence / 2;
                speeches[i + 1].start = speeches[i + 1].start > silence / 2 ? speeches[i + 1].start - silence / 2 : 0;
            } else {
                end = end + speech_pad_samples < audio_length ? end + speech_pad_samples : audio_length;
                speeches[i + 1].start =
                    speeches[i + 1].start > speech_pad_samples ? speeches[i + 1].start - speech_pad_samples : 0;
            }
        } else {
            end = end + speech_pad_samples < audio_length ? end + speech_pad_samples : audio_length;
        }
        out.emplace_back(static_cast<int64_t>(start) * 1000 / kSampleRate,
                         static_cast<int64_t>(end) * 1000 / kSampleRate);
    }
    return true;
}

}

vad_context *vad_open(const char *path_model, int n_threads) {
    reader rd;
    if (!rd.open(path_model)) {
        return nullptr;
    }

    uint32_t magic = 0;
    if (!rd.read(magic) || magic != kGgmlMagic) {
        vad_log("%s is not a ggml model file", path_model);
        return nullptr;
    }

    vad_context *ctx = new vad_context();
    ctx->n_threads = n_threads > 0 ? n_threads : 2;

    int32_t str_len = 0;
    if (!rd.read(str_len) || str_len < 0 || str_len > 1024) {
        vad_log("model type length %d out of range", str_len);
        delete ctx;
        return nullptr;
    }
    std::string type(static_cast<size_t>(str_len), '\0');
    if (str_len > 0 && !rd.read_raw(&type[0], static_cast<size_t>(str_len))) {
        delete ctx;
        return nullptr;
    }
    int32_t major = 0;
    int32_t minor = 0;
    int32_t patch = 0;
    if (!rd.read(major) || !rd.read(minor) || !rd.read(patch)) {
        delete ctx;
        return nullptr;
    }
    if (!rd.read(ctx->n_window) || !rd.read(ctx->n_context) || ctx->n_window <= 0) {
        vad_log("window %d out of range", ctx->n_window);
        delete ctx;
        return nullptr;
    }
    vad_log("%s %d.%d.%d, window %d, context %d", type.c_str(), major, minor, patch, ctx->n_window, ctx->n_context);

    vad_hparams &hp = ctx->hparams;
    if (!rd.read(hp.n_encoder_layers) || hp.n_encoder_layers <= 0 || hp.n_encoder_layers > 4) {
        vad_log("%d encoder layers out of range", hp.n_encoder_layers);
        delete ctx;
        return nullptr;
    }
    hp.encoder_in_channels.resize(hp.n_encoder_layers);
    hp.encoder_out_channels.resize(hp.n_encoder_layers);
    hp.kernel_sizes.resize(hp.n_encoder_layers);
    for (int32_t i = 0; i < hp.n_encoder_layers; ++i) {
        if (!rd.read(hp.encoder_in_channels[i]) || !rd.read(hp.encoder_out_channels[i]) ||
            !rd.read(hp.kernel_sizes[i])) {
            delete ctx;
            return nullptr;
        }
    }
    if (!rd.read(hp.lstm_input_size) || !rd.read(hp.lstm_hidden_size) || !rd.read(hp.final_conv_in) ||
        !rd.read(hp.final_conv_out)) {
        delete ctx;
        return nullptr;
    }

    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (dev == nullptr) {
        vad_log("no cpu backend registered");
        delete ctx;
        return nullptr;
    }
    ctx->backend = ggml_backend_dev_init(dev, nullptr);
    if (ctx->backend == nullptr) {
        delete ctx;
        return nullptr;
    }

    ggml_init_params model_params = {
        /*.mem_size   =*/ (4 + 2 * hp.n_encoder_layers + 6) * ggml_tensor_overhead(),
        /*.mem_buffer =*/ nullptr,
        /*.no_alloc   =*/ true,
    };
    ctx->ctx_model = ggml_init(model_params);
    if (ctx->ctx_model == nullptr || !create_tensors(ctx)) {
        vad_close(ctx);
        return nullptr;
    }
    ctx->buffer = ggml_backend_alloc_ctx_tensors(ctx->ctx_model, ctx->backend);
    if (ctx->buffer == nullptr) {
        vad_log("could not allocate the model weights");
        vad_close(ctx);
        return nullptr;
    }
    if (!load_weights(ctx, rd)) {
        vad_log("the weights did not match what the model declares");
        vad_close(ctx);
        return nullptr;
    }

    ggml_init_params state_params = {
        /*.mem_size   =*/ 2 * ggml_tensor_overhead(),
        /*.mem_buffer =*/ nullptr,
        /*.no_alloc   =*/ true,
    };
    ctx->ctx_state = ggml_init(state_params);
    if (ctx->ctx_state == nullptr) {
        vad_close(ctx);
        return nullptr;
    }
    ctx->h_state = ggml_new_tensor_1d(ctx->ctx_state, GGML_TYPE_F32, hp.lstm_hidden_size);
    ggml_set_name(ctx->h_state, "h_state");
    ctx->c_state = ggml_new_tensor_1d(ctx->ctx_state, GGML_TYPE_F32, hp.lstm_hidden_size);
    ggml_set_name(ctx->c_state, "c_state");
    ctx->state_buffer = ggml_backend_alloc_ctx_tensors(ctx->ctx_state, ctx->backend);
    if (ctx->state_buffer == nullptr) {
        vad_close(ctx);
        return nullptr;
    }

    ggml_backend_t backends[1] = {ctx->backend};
    ctx->sched = ggml_backend_sched_new(backends, nullptr, 1, GGML_DEFAULT_GRAPH_SIZE, false, true);
    if (ctx->sched == nullptr) {
        vad_log("could not create the compute scheduler");
        vad_close(ctx);
        return nullptr;
    }
    return ctx;
}

void vad_close(vad_context *ctx) {
    if (ctx == nullptr) {
        return;
    }
    if (ctx->sched != nullptr) {
        ggml_backend_sched_free(ctx->sched);
    }
    if (ctx->ctx_state != nullptr) {
        ggml_free(ctx->ctx_state);
    }
    if (ctx->ctx_model != nullptr) {
        ggml_free(ctx->ctx_model);
    }
    if (ctx->backend != nullptr) {
        ggml_backend_free(ctx->backend);
    }
    delete ctx;
}

bool vad_spans(vad_context *ctx, const float *samples, int n_samples, const vad_params &params,
               std::vector<std::pair<int64_t, int64_t>> &out) {
    if (ctx == nullptr || samples == nullptr || n_samples <= 0) {
        return false;
    }

    const int window = ctx->n_window;
    const int n_chunks = (n_samples + window - 1) / window;
    ctx->probs.assign(static_cast<size_t>(n_chunks), 0.0f);
    ggml_backend_buffer_clear(ctx->state_buffer, 0);

    ggml_init_params graph_params = {
        /*.mem_size   =*/ ggml_tensor_overhead() * GGML_DEFAULT_GRAPH_SIZE * 2 + ggml_graph_overhead(),
        /*.mem_buffer =*/ nullptr,
        /*.no_alloc   =*/ true,
    };

    std::vector<float> chunk(window, 0.0f);
    for (int i = 0; i < n_chunks; ++i) {
        const int from = i * window;
        const int length = std::min(window, n_samples - from);
        std::fill(chunk.begin(), chunk.end(), 0.0f);
        std::copy(samples + from, samples + from + length, chunk.begin());

        ggml_context *graph_ctx = ggml_init(graph_params);
        if (graph_ctx == nullptr) {
            return false;
        }
        ggml_cgraph *gf = build_graph(graph_ctx, ctx);
        if (!ggml_backend_sched_alloc_graph(ctx->sched, gf)) {
            ggml_free(graph_ctx);
            return false;
        }
        ggml_tensor *frame = ggml_graph_get_tensor(gf, "frame");
        ggml_tensor *prob = ggml_graph_get_tensor(gf, "prob");
        if (frame == nullptr || prob == nullptr) {
            ggml_free(graph_ctx);
            return false;
        }
        ggml_backend_tensor_set(frame, chunk.data(), 0, ggml_nbytes(frame));
        if (ggml_backend_sched_graph_compute(ctx->sched, gf) != GGML_STATUS_SUCCESS) {
            ggml_backend_sched_reset(ctx->sched);
            ggml_free(graph_ctx);
            return false;
        }
        ggml_backend_tensor_get(prob, &ctx->probs[static_cast<size_t>(i)], 0, sizeof(float));
        ggml_backend_sched_reset(ctx->sched);
        ggml_free(graph_ctx);
    }

    return spans_from_probs(ctx, params, n_samples, out);
}
