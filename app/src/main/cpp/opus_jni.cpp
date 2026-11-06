#include <jni.h>
#include <opus.h>
#include <cstdint>
#include <vector>

namespace {
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_screencopy_receiver_media_OpusDecoder_create(JNIEnv* env, jobject, jint channels, jint gain) {
    if (channels != 1 && channels != 2) {
        fail(env, "Opus requires one or two channels");
        return 0;
    }
    int error = OPUS_OK;
    auto* decoder = opus_decoder_create(48000, channels, &error);
    if (error != OPUS_OK) {
        fail(env, opus_strerror(error));
        return 0;
    }
    if (opus_decoder_ctl(decoder, OPUS_SET_GAIN(gain)) != OPUS_OK) {
        opus_decoder_destroy(decoder);
        fail(env, "Invalid Opus output gain");
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_org_screencopy_receiver_media_OpusDecoder_decodePacket(
        JNIEnv* env, jobject, jlong handle, jbyteArray packet, jint channels) {
    if (!handle || !packet || (channels != 1 && channels != 2)) {
        fail(env, "Invalid Opus decoder state");
        return nullptr;
    }
    const auto length = env->GetArrayLength(packet);
    if (length < 1 || length > 65536) {
        fail(env, "Invalid Opus packet length");
        return nullptr;
    }
    std::vector<unsigned char> input(static_cast<size_t>(length));
    env->GetByteArrayRegion(packet, 0, length, reinterpret_cast<jbyte*>(input.data()));
    std::vector<opus_int16> pcm(static_cast<size_t>(5760 * channels));
    const int frames = opus_decode(reinterpret_cast<::OpusDecoder*>(handle),
            input.data(), length, pcm.data(), 5760, 0);
    if (frames < 0) {
        fail(env, opus_strerror(frames));
        return nullptr;
    }
    auto output = env->NewShortArray(frames * channels);
    if (output) env->SetShortArrayRegion(output, 0, frames * channels, pcm.data());
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_org_screencopy_receiver_media_OpusDecoder_destroy(JNIEnv*, jobject, jlong handle) {
    if (handle) opus_decoder_destroy(reinterpret_cast<::OpusDecoder*>(handle));
}
