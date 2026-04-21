#pragma once

namespace couchfi {

static constexpr int   INPUT_SAMPLE_RATE  = 44100;
static constexpr int   OUTPUT_SAMPLE_RATE = 176400;
static constexpr int   UPSAMPLE_FACTOR    = 4;
static constexpr int   FILTER_TAPS        = 64;
static constexpr float FILTER_CUTOFF      = 0.8f;   // × Nyquist
static constexpr int   BIT_DEPTH          = 24;
static constexpr int   CHANNELS           = 2;

} // namespace couchfi
