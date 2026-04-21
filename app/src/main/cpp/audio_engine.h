#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "filter/polyphase_fir.h"
#include "ring_buffer.h"
#include "usb/usb_audio.h"

namespace couchfi {

// End-to-end audio engine:
//   Kotlin decoder → push_pcm() → PolyphaseFir (44.1→176.4 kHz) →
//   24-in-32 packer → ByteRing → UsbAudio ISO OUT → DAC.
//
// Threading contract:
//   - start()/stop()/capacity_*()      : any thread (not concurrent with each other)
//   - push_pcm()                        : one producer thread (decode thread)
//   - the USB fill callback             : libusb event thread, invoked internally
class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    /**
     * Start streaming to the attached UAC2 device.
     * @param upsample_factor  1 → pass-through (Direct NOS: DAC runs at
     *   input sample rate, FIR bypassed); 4 → polyphase 4× oversampling
     *   to 176.4 kHz.
     * @param level_scale digital headroom multiplier applied to each
     *   sample before packing. 1.0 is full-scale; 0.4 is the ~-8 dB
     *   reserve needed to absorb the 4x polyphase FIR's branch-sum peak.
     */
    bool start(int fd, int output_rate_hz, int upsample_factor, double level_scale);
    void stop();

    // Push interleaved stereo 44.1 kHz float PCM. Non-blocking.
    // Returns the number of input frames accepted (may be < in_frames if
    // the ring is nearly full; caller should retry the remainder later).
    int push_pcm(const float* in, int in_frames);

    // Reset FIR state and drop all buffered output PCM. Call between tracks
    // or on seek so stale tail doesn't leak into the new position. Caller
    // must ensure no push_pcm() is in flight (typically: stop the decode
    // thread, flush, start a new decode).
    void flush();

    int pending_output_frames() const;
    int capacity_output_frames() const;

private:
    void fill_cb(uint8_t* buf, int nbytes);
    void pack_samples(const float* in_f, int n_samples, uint8_t* out_b,
                      double headroom);

    std::unique_ptr<UsbAudio>     usb_;
    std::unique_ptr<PolyphaseFir> fir_;
    std::unique_ptr<ByteRing>     ring_;

    // While true, fill_cb emits silence instead of reading from the ring.
    // Set during flush() so the consumer doesn't race the ring reset.
    std::atomic<bool>             mute_{false};

    int    subslot_            = 0;
    int    bit_res_            = 0;
    int    channels_           = 0;
    int    output_frame_bytes_ = 0;
    int    upsample_factor_    = PolyphaseFir::L;   // set in start()
    double level_scale_        = 1.0;               // set in start()

    // Reusable scratch buffers (producer thread only — no locking needed).
    std::vector<float>   scratch_out_float_;
    std::vector<uint8_t> scratch_packed_;
};

} // namespace couchfi
