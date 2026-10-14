// shared between the transcribe and translate bridges
#pragma once

#include <atomic>

struct abort_flag {
    std::atomic<bool> cancelled{false};
};

// ggml abort callback, true once cancelled
bool abort_reached(void *user);

// loads the cpu backend once per process
void load_cpu_backend();
