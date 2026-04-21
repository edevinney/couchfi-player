#include "audio_engine.h"

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#include "audio_config.h"
#include "usb/usb_audio.h"

#define LOG_TAG "couchfi.engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

namespace couchfi {

// ── AudioEngine ──────────────────────────────────────────────────────────────

AudioEngine::AudioEngine() = default;
AudioEngine::~AudioEngine() { stop(); }

bool AudioEngine::start(int fd, int output_rate_hz, int upsample_factor,
                        double level_scale) {
    if (usb_) return false;
    if (upsample_factor != 1 && upsample_factor != PolyphaseFir::L) {
        LOGW("unsupported upsample_factor=%d, only 1 and %d accepted",
             upsample_factor, PolyphaseFir::L);
        return false;
    }
    upsample_factor_ = upsample_factor;
    level_scale_     = level_scale;

    usb_ = std::make_unique<UsbAudio>();
    if (!usb_->open(fd, output_rate_hz)) {
        usb_.reset();
        return false;
    }

    subslot_            = usb_->subslot_size();
    bit_res_            = usb_->bit_resolution();
    channels_           = usb_->channels();
    output_frame_bytes_ = subslot_ * channels_;

    // ~1.5 s of output-rate PCM. 300 ms was fine for SMB reads but too
    // tight for internet radio over a flaky network — a single WAN hiccup
    // during MediaExtractor's HTTP fetch would underrun the ring audibly.
    // 1.5 s is a compromise: covers most stalls, yet pause latency after
    // nativeEngineFlush() is still fine (stop() flushes so it's instant).
    const std::size_t ring_bytes =
        std::size_t(output_rate_hz) * output_frame_bytes_ * 3 / 2;
    ring_ = std::make_unique<ByteRing>(ring_bytes);
    fir_  = (upsample_factor_ == 1) ? nullptr
                                    : std::make_unique<PolyphaseFir>();

    scratch_out_float_.clear();
    scratch_packed_.clear();

    const bool ok = usb_->start([this](uint8_t* buf, int nbytes) {
        fill_cb(buf, nbytes);
    });
    if (!ok) { stop(); return false; }

    LOGI("engine start: out=%d Hz subslot=%d bits=%d ch=%d ring=%zu bytes "
         "(~%.0f ms) upsample=%dx%s",
         output_rate_hz, subslot_, bit_res_, channels_, ring_->capacity(),
         double(ring_->capacity()) / double(output_rate_hz * output_frame_bytes_)
             * 1000.0,
         upsample_factor_,
         (upsample_factor_ == 1 ? " (FIR bypassed)" : ""));
    return true;
}

void AudioEngine::stop() {
    // Mute the USB callback so any ISO transfers still in libusb's queue
    // get filled with silence on completion, then give them a moment to
    // drain to the DAC before we cancel. Without this, libusb_cancel_transfer
    // drops in-flight transfers mid-packet, which the DAC renders as a
    // click and a brief hiss on shutdown.
    mute_.store(true, std::memory_order_release);
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    if (usb_) { usb_->stop(); usb_->close(); usb_.reset(); }
    fir_.reset();
    ring_.reset();
    scratch_out_float_.clear();
    scratch_packed_.clear();
    mute_.store(false, std::memory_order_release);
}

int AudioEngine::push_pcm(const float* in, int in_frames) {
    if (!ring_ || in_frames <= 0) return 0;

    // Cap to what the ring can accept — CRUCIAL: with FIR engaged, we
    // must not advance the delay line past frames the caller will retry.
    const std::size_t free_bytes     = ring_->free_space();
    const int         max_out_frames = int(free_bytes / output_frame_bytes_);
    const int         max_in_frames  = max_out_frames / upsample_factor_;
    const int         accept         = std::min(in_frames, max_in_frames);
    if (accept == 0) return 0;

    const int n_out       = accept * upsample_factor_;
    const int n_out_samp  = n_out * channels_;
    const int out_bytes   = n_out * output_frame_bytes_;

    if (int(scratch_packed_.size()) < out_bytes) {
        scratch_packed_.resize(out_bytes);
    }

    if (upsample_factor_ == 1) {
        // NOS path: pack input float PCM straight to the subslot. Scale
        // is whatever the caller asked for (full-scale by default,
        // FIR-matched when equal-level A/B comparison is enabled).
        pack_samples(in, n_out_samp, scratch_packed_.data(), level_scale_);
    } else {
        // Oversampling path: run polyphase FIR, then pack. The branch-DC
        // peak of 1+√2 mandates at least ~0.4; caller should pass that.
        if (int(scratch_out_float_.size()) < n_out_samp) {
            scratch_out_float_.resize(n_out_samp);
        }
        fir_->process(in, std::size_t(accept), scratch_out_float_.data());
        pack_samples(scratch_out_float_.data(), n_out_samp,
                     scratch_packed_.data(), level_scale_);
    }

    const std::size_t written = ring_->write(scratch_packed_.data(),
                                             std::size_t(out_bytes));
    return int(written / output_frame_bytes_) / upsample_factor_;
}

int AudioEngine::pending_output_frames() const {
    if (!ring_ || output_frame_bytes_ == 0) return 0;
    return int(ring_->pending() / output_frame_bytes_);
}

int AudioEngine::capacity_output_frames() const {
    if (!ring_ || output_frame_bytes_ == 0) return 0;
    return int(ring_->capacity() / output_frame_bytes_);
}

void AudioEngine::fill_cb(uint8_t* buf, int nbytes) {
    if (!ring_ || mute_.load(std::memory_order_acquire)) {
        std::memset(buf, 0, nbytes);
        return;
    }
    const std::size_t got = ring_->read(buf, std::size_t(nbytes));
    if (int(got) < nbytes) {
        std::memset(buf + got, 0, nbytes - got);
    }
}

void AudioEngine::flush() {
    // Consumer: emit silence until we clear the flag.
    mute_.store(true, std::memory_order_release);
    // Give the USB event thread at least one iso-transfer cycle (~1 ms)
    // to observe the flag before we touch the ring. 5 ms is plenty.
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    if (fir_)  fir_->reset();
    if (ring_) ring_->clear();
    mute_.store(false, std::memory_order_release);
    LOGI("engine flush: FIR reset, ring cleared");
}

void AudioEngine::pack_samples(const float* in_f, int n_samples,
                               uint8_t* out_b, double headroom) {
    // When the 4× polyphase FIR is engaged, its branch-DC sums peak at
    // 1+√2 ≈ 2.414 for a full-scale input. The caller passes `headroom`
    // ≈ 1/2.5 so those peaks fit; in NOS mode (factor=1, FIR bypassed)
    // `headroom` is 1.0 and ±1.0 input maps to full-scale output.
    const double clamp  = 1.0 / headroom;               // input limit
    const int    shift  = (subslot_ * 8) - bit_res_;    // UAC2 §2.3.1.2
    const double scale  = double((int64_t(1) << (bit_res_ - 1)) - 1) * headroom;

    for (int i = 0; i < n_samples; ++i) {
        double v = double(in_f[i]);
        if (v >  clamp) v =  clamp;
        if (v < -clamp) v = -clamp;
        const int32_t  s = int32_t(v * scale);
        const uint32_t u = uint32_t(s) << shift;
        uint8_t* q = out_b + i * subslot_;
        for (int b = 0; b < subslot_; ++b) {
            q[b] = uint8_t((u >> (b * 8)) & 0xFF);
        }
    }
}

} // namespace couchfi

// ── JNI glue ─────────────────────────────────────────────────────────────────

namespace {

std::mutex                              g_mu;
std::unique_ptr<couchfi::AudioEngine>  g_engine;

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_couchfi_player_audio_AudioEngine_nativeVersion(JNIEnv* env, jobject) {
    using namespace couchfi;
    std::string msg;
    msg += "CouchFi engine | ";
    msg += "in="    + std::to_string(INPUT_SAMPLE_RATE)  + "Hz ";
    msg += "out="   + std::to_string(OUTPUT_SAMPLE_RATE) + "Hz ";
    msg += "x"      + std::to_string(UPSAMPLE_FACTOR)    + " ";
    msg += "taps="  + std::to_string(FILTER_TAPS)        + " ";
    msg += "Hann fc=0.8";
    return env->NewStringUTF(msg.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_couchfi_player_audio_AudioEngine_nativeEngineStart(
        JNIEnv*, jobject, jint fd, jint output_rate_hz, jint upsample_factor,
        jfloat level_scale) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_engine) return -10;
    auto e = std::make_unique<couchfi::AudioEngine>();
    if (!e->start(int(fd), int(output_rate_hz), int(upsample_factor),
                  double(level_scale))) return -1;
    g_engine = std::move(e);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_couchfi_player_audio_AudioEngine_nativeEngineStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_engine) g_engine->stop();
    g_engine.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_couchfi_player_audio_AudioEngine_nativeEngineFlush(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_engine) g_engine->flush();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_couchfi_player_audio_AudioEngine_nativeEnginePushPcm(
        JNIEnv* env, jobject, jfloatArray data, jint offset_frames, jint frames) {
    couchfi::AudioEngine* eng = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mu);
        eng = g_engine.get();
    }
    if (!eng) return 0;

    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (!p) return 0;
    const float* start = p + std::size_t(offset_frames) * 2;  // stereo
    const int accepted = eng->push_pcm(start, int(frames));
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
    return jint(accepted);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_couchfi_player_audio_AudioEngine_nativeEnginePendingFrames(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    return g_engine ? jint(g_engine->pending_output_frames()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_couchfi_player_audio_AudioEngine_nativeEngineCapacityFrames(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    return g_engine ? jint(g_engine->capacity_output_frames()) : 0;
}
