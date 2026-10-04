// Silero VAD on ggml: finds the speech in a recording so the model is only run over the parts that
// carry it. The model file is the same ggml-silero-v5.1.2.bin whisper.cpp downloads, and the graph
// and the probabilities-to-spans state machine are a port of its implementation, which is MIT
// licensed and Copyright (c) 2023-2025 Georgi Gerganov. Ours differs in three ways: it runs on the
// cpu backend the rest of the app already has, it keeps one backend instead of picking a buffer
// type per tensor, and it reports milliseconds instead of centiseconds.

#pragma once

#include <cstdint>
#include <utility>
#include <vector>

struct vad_context;

struct vad_params {
    float threshold = 0.5f;
    int min_speech_duration_ms = 250;
    int min_silence_duration_ms = 100;
    float max_speech_duration_s = 1e9f;
    int speech_pad_ms = 30;
};

vad_context * vad_open(const char *path_model, int n_threads);

void vad_close(vad_context *ctx);

bool vad_spans(vad_context *ctx, const float *samples, int n_samples, const vad_params &params,
               std::vector<std::pair<int64_t, int64_t>> &out);
