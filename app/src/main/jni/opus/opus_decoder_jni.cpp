#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <new>

#include <opusfile.h>

// Synchronous Ogg/Opus decode to 16 kHz mono floats, up-calling the Kotlin
// sink per chunk. This path exists because MediaCodec's per-packet async
// machinery costs more than the Opus decode itself on slow SoCs (issue 41
// evidence in TODO.md): opusfile decodes 10 minutes in ~1-3 s where the
// MediaCodec path needs ~30 s, and a synchronous loop has no queue to drain,
// so the tail-race class of bugs is structurally gone.

#define TAG "AudioDecoder"
#define UNUSED(x) (void)(x)

static constexpr int OPUS_RATE = 48000;
static constexpr int TARGET_RATE = 16000;
// 240 ms of 48 kHz interleaved floats per read.
static constexpr int SRC_FLOATS = 120 * OPUS_RATE / 1000 * 2;

enum Status : jint {
    STATUS_OK = 0,
    STATUS_CANCELLED = 1,
    STATUS_DECODE_STOPPED = 2,
    STATUS_OPEN_FAILED = 3,
    STATUS_SEEK_FAILED = 4,
};

static ssize_t stream_read(void *stream, unsigned char *ptr, opus_int32 size) {
    int fd = (int)(intptr_t)stream;
    return read(fd, ptr, (size_t)size);
}

static int stream_seek(void *stream, opus_int64 offset, int whence) {
    int fd = (int)(intptr_t)stream;
    return lseek(fd, (off_t)offset, whence) == (off_t)-1 ? -1 : 0;
}

static opus_int64 stream_tell(void *stream) {
    int fd = (int)(intptr_t)stream;
    off_t pos = lseek(fd, 0, SEEK_CUR);
    return pos == (off_t)-1 ? -1 : (opus_int64)pos;
}

static int stream_close(void *stream) {
    int fd = (int)(intptr_t)stream;
    return close(fd);
}

static OpusFileCallbacks stream_callbacks() {
    OpusFileCallbacks cb;
    std::memset(&cb, 0, sizeof(cb));
    cb.read = stream_read;
    cb.seek = stream_seek;
    cb.tell = stream_tell;
    cb.close = stream_close;
    return cb;
}

// Emits every 16 kHz sample whose interpolation window is complete, matching
// the Kotlin ChunkConverter exactly for the 48k -> 16k integer step: output j
// carries the mono value of input frame 3j-1 (output 0 the initial history),
// so a native decode and a MediaCodec decode of the same file agree sample
// for sample.
class Converter {
public:
    explicit Converter(int channels) : channels_(channels) {}

    // Converts whole frames of interleaved floats; returns the output count.
    int push(const float *src, int frames, float *out) {
        int written = 0;
        for (int f = 0; f < frames; f++) {
            float acc = 0.0f;
            for (int c = 0; c < channels_; c++) {
                acc += src[(size_t)f * channels_ + c];
            }
            float cur = acc / channels_;
            while (3L * emitted_ <= frames_) {
                out[written++] = hist_;
                emitted_++;
            }
            hist_ = cur;
            frames_++;
        }
        return written;
    }

    // Emits the tail samples whose window never completed.
    int flush(float *out) {
        jlong total = frames_ * TARGET_RATE / OPUS_RATE;
        int written = 0;
        while (emitted_ < total) {
            out[written++] = hist_;
            emitted_++;
        }
        return written;
    }

private:
    int channels_;
    float hist_ = 0.0f;
    jlong frames_ = 0;
    jlong emitted_ = 0;
};

extern "C" JNIEXPORT jlong JNICALL
Java_org_scrib_transcriber_AudioDecoder_00024OpusNative_nativeProbeTotalSamples(
        JNIEnv *env, jobject thiz, jint fd) {
    UNUSED(env);
    UNUSED(thiz);
    // The dup shares the caller's file offset; op_open starts reading where
    // the descriptor sits, and a previous open on the same description can
    // have left it mid-file.
    lseek(fd, 0, SEEK_SET);
    OpusFileCallbacks cb = stream_callbacks();
    int err = 0;
    OggOpusFile *of = op_open_callbacks((void *)(intptr_t)fd, &cb, nullptr, 0, &err);
    if (of == nullptr) {
        close(fd);
        return -1;
    }
    ogg_int64_t total = op_pcm_total(of, -1);
    op_free(of);
    return total < 0 ? 0 : (jlong)total;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_scrib_transcriber_AudioDecoder_00024OpusNative_nativeDecode16kMono(
        JNIEnv *env, jobject thiz, jint fd, jlong startSample, jlong endSample,
        jobject bridge) {
    UNUSED(thiz);

    jclass bridgeClass = env->GetObjectClass(bridge);
    jmethodID onChunk = env->GetMethodID(bridgeClass, "onChunk", "([FI)Z");
    env->DeleteLocalRef(bridgeClass);
    if (onChunk == nullptr) {
        close(fd);
        return STATUS_DECODE_STOPPED;
    }

    // Rewind for the same reason as the probe: this descriptor shares the
    // offset with the caller, and the probe's open moved it.
    lseek(fd, 0, SEEK_SET);
    OpusFileCallbacks cb = stream_callbacks();
    int err = 0;
    OggOpusFile *of = op_open_callbacks((void *)(intptr_t)fd, &cb, nullptr, 0, &err);
    if (of == nullptr) {
        // The fd belongs to us; stream_close never ran because open failed.
        close(fd);
        return STATUS_OPEN_FAILED;
    }

    jint status = STATUS_OK;
    Converter *converter = nullptr;
    float src[SRC_FLOATS];
    float converted[SRC_FLOATS / 2];
    jfloatArray chunk = nullptr;
    jlong framesIn = 0;
    int written;
    int channels = op_channel_count(of, -1);
    if (channels < 1) {
        channels = 1;
    }
    converter = new (std::nothrow) Converter(channels);
    if (converter == nullptr) {
        status = STATUS_DECODE_STOPPED;
        goto exit;
    }
    if (startSample > 0 && op_pcm_seek(of, (ogg_int64_t)startSample) < 0) {
        status = STATUS_SEEK_FAILED;
        goto exit;
    }

    chunk = env->NewFloatArray(SRC_FLOATS / 2);
    if (chunk == nullptr) {
        status = STATUS_DECODE_STOPPED;
        goto exit;
    }

    for (;;) {
        int n = op_read_float(of, src, SRC_FLOATS, nullptr);
        if (n == 0) {
            break;
        }
        if (n < 0) {
            __android_log_write(ANDROID_LOG_WARN, TAG, "Native Opus decode stopped early");
            status = STATUS_DECODE_STOPPED;
            break;
        }
        framesIn += n;
        // The range ends by plan at endSample: trim the overshoot and treat
        // it as a normal end, never as a stopped decode.
        jlong consumed = startSample + framesIn;
        int process = n;
        if (consumed > endSample) {
            process = (int)(n - (consumed - endSample));
            if (process < 0) {
                process = 0;
            }
        }
        int chunkCount = converter->push(src, process, converted);
        if (chunkCount > 0) {
            env->SetFloatArrayRegion(chunk, 0, chunkCount, converted);
            jboolean keepGoing = env->CallBooleanMethod(bridge, onChunk, chunk, chunkCount);
            if (env->ExceptionCheck()) {
                status = STATUS_DECODE_STOPPED;
                break;
            }
            if (!keepGoing) {
                status = STATUS_CANCELLED;
                break;
            }
        }
        if (consumed >= endSample) {
            break;
        }
    }

exit:
    written = converter == nullptr ? 0 : converter->flush(converted);
    if (written > 0 && status == STATUS_OK) {
        env->SetFloatArrayRegion(chunk, 0, written, converted);
        env->CallBooleanMethod(bridge, onChunk, chunk, written);
        env->ExceptionClear();
    }
    delete converter;
    if (chunk != nullptr) {
        env->DeleteLocalRef(chunk);
    }
    op_free(of);
    return status;
}
