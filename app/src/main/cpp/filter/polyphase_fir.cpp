#include "polyphase_fir.h"

#include <cmath>

#include "../audio_config.h"

namespace couchfi {
namespace {

constexpr double PI = 3.14159265358979323846;

// Normalized sinc: sinc(x) = sin(πx) / (πx), sinc(0) = 1.
double normalized_sinc(double x) {
    if (x == 0.0) return 1.0;
    const double px = PI * x;
    return std::sin(px) / px;
}

} // namespace

PolyphaseFir::PolyphaseFir() {
    // FILTER_CUTOFF is a fraction of Nyquist; convert to fraction of fs.
    const double fc     = static_cast<double>(FILTER_CUTOFF) / 2.0;
    const double center = (TAPS - 1) / 2.0;

    double sum = 0.0;
    for (int n = 0; n < TAPS; ++n) {
        const double m      = n - center;
        const double sinc_v = normalized_sinc(2.0 * fc * m);
        // Hann: w(n) = 0.5 * (1 − cos(2π n / (N−1)))
        const double window = 0.5 * (1.0 - std::cos(2.0 * PI * n / (TAPS - 1)));
        const double h      = 2.0 * fc * sinc_v * window;
        prototype_[n] = static_cast<float>(h);
        sum += h;
    }

    // Normalize so total sum == L → each polyphase sub-filter has unit DC gain.
    const double scale = static_cast<double>(L) / sum;
    for (int n = 0; n < TAPS; ++n) {
        prototype_[n] = static_cast<float>(prototype_[n] * scale);
    }

    // Decompose: phase p taps are prototype[p], prototype[p+L], prototype[p+2L], …
    for (int p = 0; p < L; ++p) {
        for (int k = 0; k < TAPS_PER_PHASE; ++k) {
            phases_[p][k] = prototype_[p + k * L];
        }
    }
}

void PolyphaseFir::reset() {
    for (int i = 0; i < TAPS_PER_PHASE; ++i) {
        delay_l_[i] = 0.0f;
        delay_r_[i] = 0.0f;
    }
    delay_pos_ = 0;
}

void PolyphaseFir::process(const float* in, std::size_t in_frames, float* out) {
    for (std::size_t i = 0; i < in_frames; ++i) {
        // Insert newest input frame at the write head.
        delay_l_[delay_pos_] = in[2 * i];
        delay_r_[delay_pos_] = in[2 * i + 1];

        // Produce L output frames, one per polyphase branch.
        for (int p = 0; p < L; ++p) {
            float acc_l = 0.0f;
            float acc_r = 0.0f;
            for (int k = 0; k < TAPS_PER_PHASE; ++k) {
                const int idx =
                    (delay_pos_ - k + TAPS_PER_PHASE) % TAPS_PER_PHASE;
                const float tap = phases_[p][k];
                acc_l += tap * delay_l_[idx];
                acc_r += tap * delay_r_[idx];
            }
            const std::size_t o = (i * L + p) * 2;
            out[o]     = acc_l;
            out[o + 1] = acc_r;
        }

        delay_pos_ = (delay_pos_ + 1) % TAPS_PER_PHASE;
    }
}

} // namespace couchfi
