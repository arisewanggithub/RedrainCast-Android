#include <jni.h>
extern "C" {
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>
#include <vector>

namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    if (!env->ExceptionCheck()) {
        const auto exception = env->FindClass(type);
        if (exception) env->ThrowNew(exception, message);
    }
}
void ffmpegError(JNIEnv* env, int code) {
    char message[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(code, message, sizeof(message));
    fail(env, "java/lang/IllegalStateException", message);
}
struct Session {
    SwrContext* context = nullptr;
    int rate;
    int channels;
    int maxInput;
    int maxOutput;
    Session(int r, int c) : rate(r), channels(c), maxInput(r * 120 / 1000),
                          maxOutput(maxInput + 256) {}
    ~Session() { swr_free(&context); }
};
// IDs, rather than exposed pointers, reject stale handles and serialize close with use.
std::mutex sessionsMutex;
std::unordered_map<jlong, std::unique_ptr<Session>> sessions;
jlong nextId = 1;
Session* lookup(JNIEnv* env, jlong id) {
    const auto found = sessions.find(id);
    if (found != sessions.end()) return found->second.get();
    fail(env, "java/lang/IllegalStateException", "PCM resampler is closed or invalid");
    return nullptr;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_screencopy_receiver_media_FfmpegPcmResampler_create(
        JNIEnv* env, jobject, jint rate, jint channels) {
    if (rate < 8000 || rate > 96000 || (channels != 1 && channels != 2)) {
        fail(env, "java/lang/IllegalArgumentException", "PCM requires 8000..96000 Hz and one or two channels");
        return 0;
    }
    try {
        auto session = std::make_unique<Session>(rate, channels);
        AVChannelLayout layout{};
        av_channel_layout_default(&layout, channels);
        int result = swr_alloc_set_opts2(&session->context, &layout, AV_SAMPLE_FMT_S16, rate,
                                       &layout, AV_SAMPLE_FMT_S16, rate, 0, nullptr);
        av_channel_layout_uninit(&layout);
        if (result >= 0) result = av_opt_set_int(session->context, "flags", SWR_FLAG_RESAMPLE, 0);
        if (result >= 0) result = swr_init(session->context);
        if (result < 0) {
            ffmpegError(env, result);
            return 0;
        }
        std::lock_guard<std::mutex> lock(sessionsMutex);
        if (nextId == std::numeric_limits<jlong>::max()) {
            fail(env, "java/lang/IllegalStateException", "PCM session IDs exhausted");
            return 0;
        }
        const jlong id = nextId++;
        sessions.emplace(id, std::move(session));
        return id;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "Allocating PCM resampler");
        return 0;
    }
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_org_screencopy_receiver_media_FfmpegPcmResampler_convertNative(
        JNIEnv* env, jobject, jlong handle, jshortArray samples, jint offset, jint frames) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    auto* session = lookup(env, handle);
    if (!session) return nullptr;
    if (!samples || offset < 0 || frames < 0 || frames > session->maxInput ||
        (static_cast<int64_t>(offset) + frames) * session->channels > env->GetArrayLength(samples)) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid PCM frame range or input exceeds 120 ms");
        return nullptr;
    }
    if (frames == 0) return env->NewShortArray(0);
    const int capacity = swr_get_out_samples(session->context, frames);
    if (capacity < 0) {
        ffmpegError(env, capacity);
        return nullptr;
    }
    if (capacity > session->maxOutput) {
        fail(env, "java/lang/IllegalStateException", "PCM output estimate exceeds 120 ms plus 256 frames");
        return nullptr;
    }
    try {
        std::vector<jshort> input(static_cast<size_t>(frames * session->channels));
        std::vector<jshort> output(static_cast<size_t>(capacity * session->channels));
        env->GetShortArrayRegion(samples, offset * session->channels,
                                frames * session->channels, input.data());
        if (env->ExceptionCheck()) return nullptr;
        // Allocate Java output before consuming input so Java OOM does not advance history.
        auto scratch = env->NewShortArray(capacity * session->channels);
        if (!scratch) return nullptr;
        const uint8_t* in[] = {reinterpret_cast<const uint8_t*>(input.data())};
        uint8_t* out[] = {reinterpret_cast<uint8_t*>(output.data())};
        const int converted = swr_convert(session->context, out, capacity, in, frames);
        if (converted < 0) {
            ffmpegError(env, converted);
            return nullptr;
        }
        if (converted > capacity) {
            fail(env, "java/lang/IllegalStateException", "PCM output exceeded its checked capacity");
            return nullptr;
        }
        auto result = converted == capacity ? scratch : env->NewShortArray(converted * session->channels);
        if (!result) return nullptr;
        env->SetShortArrayRegion(result, 0, converted * session->channels, output.data());
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "Allocating bounded PCM buffers");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_screencopy_receiver_media_FfmpegPcmResampler_compensateNative(
        JNIEnv* env, jobject, jlong handle, jint delta, jint distance) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    auto* session = lookup(env, handle);
    if (!session) return;
    const int64_t magnitude = delta < 0 ? -static_cast<int64_t>(delta) : delta;
    if ((distance <= 0 && !(delta == 0 && distance == 0)) || magnitude * 50 > distance) {
        fail(env, "java/lang/IllegalArgumentException", "PCM compensation requires positive distance and at most 2 percent delta, or (0, 0) to cancel");
        return;
    }
    const int result = swr_set_compensation(session->context, delta, distance);
    if (result < 0) ffmpegError(env, result);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_screencopy_receiver_media_FfmpegPcmResampler_delayNative(
        JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    auto* session = lookup(env, handle);
    if (!session) return 0;
    const int64_t delay = swr_get_delay(session->context, session->rate);
    if (delay < 0 || delay > std::numeric_limits<jint>::max()) {
        fail(env, "java/lang/IllegalStateException", "PCM source-domain delay is out of range");
        return 0;
    }
    return static_cast<jint>(delay);
}

extern "C" JNIEXPORT void JNICALL
Java_org_screencopy_receiver_media_FfmpegPcmResampler_destroy(
        JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    sessions.erase(handle);
}
