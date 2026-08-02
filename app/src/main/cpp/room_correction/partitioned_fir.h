// Uniform-partition FFT overlap-save convolver. Streaming semantics:
// same input frame count in, same input frame count out. One instance
// per channel — L and R correction filters are independent.
//
// Construction: pass the impulse response (mono, at engine rate — RC-1's
// ir_resampler already handles the 48kHz → 176.4kHz resample from the
// REW export). The convolver splits the IR into fixed-size partitions,
// pre-computes their forward FFTs, and stores them for the real-time
// process() loop.
//
// Real-time contract: process(in, out, nframes) is called from the same
// thread as the rest of AudioEngine (single producer). Not thread-safe.
// nframes can be any positive count — the class buffers internally and
// only fires FFTs when it has accumulated a full partition's worth of
// input.
//
// Latency: one partition. At the default 4096-sample partition, that's
// ~23ms at 176.4kHz — well inside the existing ByteRing budget. The
// first partition_size output samples after construction (and after
// reset()) are silence, until the first input block fills.

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

struct PFFFT_Setup;  // opaque handle from pffft.h

namespace couchfi {

// RAII wrapper around pffft_aligned_malloc / pffft_aligned_free.
// PFFFT requires 16-byte-aligned buffers for SIMD; std::vector's
// default allocator doesn't guarantee that on every platform.
class AlignedFloatBuffer {
public:
    explicit AlignedFloatBuffer(std::size_t n = 0);
    ~AlignedFloatBuffer();

    AlignedFloatBuffer(AlignedFloatBuffer&& other) noexcept;
    AlignedFloatBuffer& operator=(AlignedFloatBuffer&& other) noexcept;

    AlignedFloatBuffer(const AlignedFloatBuffer&)            = delete;
    AlignedFloatBuffer& operator=(const AlignedFloatBuffer&) = delete;

    float*       data()        { return data_; }
    const float* data()  const { return data_; }
    std::size_t  size()  const { return n_; }

    void resize(std::size_t n);
    void zero();

private:
    float*      data_ = nullptr;
    std::size_t n_    = 0;
};

class PartitionedFirConvolver {
public:
    // partition_size must be a power of two and >= 32 (PFFFT's minimum
    // is 32 for PFFFT_REAL, and internally we use N = 2*partition_size).
    // Larger partitions raise latency but reduce per-block compute; the
    // default (4096 samples) is a good starting point at 176.4kHz.
    //
    // ir: the impulse response (mono, at engine sample rate). Any
    // length >= 1; longer IRs mean more partitions and more compute per
    // block but do not change per-sample latency.
    //
    // ok() returns false if construction failed (invalid partition size,
    // PFFFT setup failed, or IR empty/null). In that case process()
    // falls back to sample-copy passthrough, so callers can leave the
    // convolver in the signal path even after a load failure.
    PartitionedFirConvolver(const float* ir, std::size_t ir_len,
                            std::size_t partition_size = 4096);
    ~PartitionedFirConvolver();

    PartitionedFirConvolver(const PartitionedFirConvolver&)            = delete;
    PartitionedFirConvolver& operator=(const PartitionedFirConvolver&) = delete;

    bool ok() const { return ok_; }
    std::size_t partition_size() const { return P_; }
    std::size_t num_partitions() const { return partitions_.size(); }
    std::size_t ir_length()      const { return ir_len_; }
    // Startup latency in samples — first this many output samples after
    // construction (or reset()) are silence.
    std::size_t latency_samples() const { return P_; }

    // Stream nframes samples through the convolver. `in` and `out` MAY
    // alias (same pointer OK). When ok() is false, does a plain copy.
    void process(const float* in, float* out, std::size_t nframes);

    // Zero all internal buffers. Call on seek / track change so stale
    // tail doesn't leak into new content. Pre-computed IR partition
    // FFTs are untouched (only the streaming state resets).
    void reset();

private:
    bool ok_ = false;

    std::size_t P_      = 0;   // partition size (block size)
    std::size_t N_fft_  = 0;   // FFT size = 2 * P_
    std::size_t ir_len_ = 0;   // original IR length (diagnostic)

    PFFFT_Setup* setup_ = nullptr;

    // Pre-computed FFT of each IR partition, in PFFFT's internal
    // (non-canonical) ordering — kept opaque, only touched via
    // pffft_zconvolve_accumulate.
    std::vector<AlignedFloatBuffer> partitions_;

    // Rolling history of the last num_partitions() input-block FFTs,
    // same internal ordering as partitions_. Indexed as a ring:
    // history_head_ points at the slot to overwrite on the next block.
    std::vector<AlignedFloatBuffer> input_history_;
    std::size_t history_head_ = 0;

    // Time-domain: current input accumulator (0..P_ samples).
    AlignedFloatBuffer input_block_;
    std::size_t input_fill_ = 0;

    // Time-domain: previous partition's input, needed to fill the 2P
    // FFT input in overlap-save (input = [prev | current]).
    AlignedFloatBuffer prev_input_block_;

    // Time-domain: current output block, produced when the last FFT
    // fired. output_read_ tracks how much of it has been drained.
    AlignedFloatBuffer current_output_;
    std::size_t output_read_ = 0;

    // Scratch buffers reused across FFTs to avoid allocation on the
    // real-time path.
    AlignedFloatBuffer fft_in_scratch_;    // 2P (time-domain, input to FFT)
    AlignedFloatBuffer fft_freq_scratch_;  // 2P (frequency-domain, current-block FFT)
    AlignedFloatBuffer conv_accum_;        // 2P (frequency-domain accumulator)
    AlignedFloatBuffer fft_out_scratch_;   // 2P (time-domain output of IFFT)
    AlignedFloatBuffer pffft_work_;        // 2P (PFFFT internal scratch)

    // Consume the full input_block_ + prev_input_block_ as 2P samples,
    // compute Y = sum_p X_{n-p} * H_p, IFFT, write the second half to
    // current_output_. Rotates history_head_ and shifts prev/current
    // input. Called when input_fill_ reaches P_.
    void process_one_block();
};

} // namespace couchfi
