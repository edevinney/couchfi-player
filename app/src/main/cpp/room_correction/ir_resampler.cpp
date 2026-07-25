#include "ir_resampler.h"

#include "../third_party/r8brain-free-src/CDSPResampler.h"

#include <algorithm>
#include <cmath>

namespace couchfi {

std::vector<float> ResampleIrToEngineRate(const std::vector<float>& in,
                                           double src_rate,
                                           double dst_rate) {
    if (in.empty()) {
        return {};
    }
    if (src_rate <= 0.0 || dst_rate <= 0.0 || src_rate == dst_rate) {
        // Nothing to do (or the rates are already invalid in a way the
        // caller should have rejected before getting here) -- pass through
        // rather than guess.
        return in;
    }

    constexpr int kBlock = 8192;

    // The impulse response is a known, finite buffer (not a live stream), so
    // the exact output length the rate ratio implies can be computed up
    // front, and that's exactly how many samples this function collects.
    const std::size_t total_out = static_cast<std::size_t>(std::llround(
        static_cast<double>(in.size()) * dst_rate / src_rate));

    r8b::CDSPResampler24 resampler(src_rate, dst_rate, kBlock);

    std::vector<double> in_block(static_cast<std::size_t>(kBlock));
    std::vector<float> out;
    out.reserve(total_out);

    // Mirrors the block-feeding pattern in r8brain's own example.cpp: feed
    // real input in fixed-size blocks, then keep feeding zero-padded blocks
    // (as if the signal continued with silence) so the resampler's internal
    // filter latency drains and the tail of the real input reaches the
    // output. The iteration cap is a safety net against a library/usage bug
    // turning this into an infinite loop -- it's sized generously above the
    // number of blocks a correct run could ever need.
    const std::size_t max_iterations = (in.size() + total_out) / kBlock + 1000;

    std::size_t consumed = 0;
    for (std::size_t iter = 0; out.size() < total_out && iter < max_iterations; ++iter) {
        const std::size_t remaining = in.size() - std::min(consumed, in.size());
        const std::size_t take = std::min(static_cast<std::size_t>(kBlock), remaining);

        for (std::size_t i = 0; i < static_cast<std::size_t>(kBlock); ++i) {
            in_block[i] = (i < take) ? static_cast<double>(in[consumed + i]) : 0.0;
        }
        consumed += take;

        double* out_ptr = nullptr;
        const int produced = resampler.process(in_block.data(), kBlock, out_ptr);

        for (int i = 0; i < produced && out.size() < total_out; ++i) {
            out.push_back(static_cast<float>(out_ptr[i]));
        }
    }

    // If the iteration cap was somehow hit early (shouldn't happen in
    // practice -- see comment above), pad with silence rather than return a
    // short buffer the caller isn't expecting.
    out.resize(total_out, 0.0f);
    return out;
}

} // namespace couchfi
