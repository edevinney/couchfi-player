#pragma once

#include <vector>

namespace couchfi {

// Resamples a room-correction impulse response from its native measurement
// rate to the engine's fixed 176400Hz output rate.
//
// This runs once, at load time (or whenever the filter file changes) -- never
// in the real-time playback path. See couchfi-room-correction-plan.md,
// "File format & loading": with a UMIK-1 measuring at its true native
// 48000Hz, 176400/48000 = 3.675 is a fractional ratio, not the clean 4:1
// relationship the existing PolyphaseFir upsampler exploits, so this needs a
// real general-purpose sample-rate converter. Backed by r8brain-free-src
// (MIT) -- see app/src/main/cpp/third_party/r8brain-free-src/.
//
// The whole impulse response is known and finite ahead of time (REW's export
// is a fixed-length WAV, not a stream), so this resamples the entire buffer
// in one call and returns the exact number of output samples the rate ratio
// implies -- round(in.size() * dst_rate / src_rate).
//
// If src_rate == dst_rate this returns `in` unchanged without touching the
// resampling library at all.
std::vector<float> ResampleIrToEngineRate(const std::vector<float>& in,
                                           double src_rate,
                                           double dst_rate);

} // namespace couchfi
