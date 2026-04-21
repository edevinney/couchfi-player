#pragma once

#include <cstddef>

namespace couchfi {

// 4× polyphase upsampler: 44.1kHz → 176.4kHz.
// 64-tap Hann-windowed sinc, fc = 0.8 × Nyquist (matches Pine Player CSF).
// Prototype is scaled so each polyphase sub-filter has unit DC gain.
class PolyphaseFir {
public:
    static constexpr int TAPS = 64;
    static constexpr int L = 4;
    static constexpr int TAPS_PER_PHASE = TAPS / L;

    PolyphaseFir();

    // Zero the delay line. Coefficients are preserved.
    void reset();

    // in:  in_frames stereo float samples (interleaved L,R,L,R…) at 44.1kHz
    // out: L * in_frames stereo float samples at 176.4kHz (caller-allocated)
    void process(const float* in, std::size_t in_frames, float* out);

    // Prototype FIR of length TAPS. sum(h) == L.
    const float* prototype() const { return prototype_; }

    // One of L polyphase branches, length TAPS_PER_PHASE.
    // phase(p)[k] == prototype[p + k * L].
    const float* phase(int p) const { return phases_[p]; }

private:
    float prototype_[TAPS]{};
    float phases_[L][TAPS_PER_PHASE]{};
    float delay_l_[TAPS_PER_PHASE]{};
    float delay_r_[TAPS_PER_PHASE]{};
    int   delay_pos_ = 0;
};

} // namespace couchfi
