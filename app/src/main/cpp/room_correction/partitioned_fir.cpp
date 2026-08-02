#include "partitioned_fir.h"

#include "pffft.h"

#include <algorithm>
#include <cstring>

namespace couchfi {

// ── AlignedFloatBuffer ──────────────────────────────────────────────────────

AlignedFloatBuffer::AlignedFloatBuffer(std::size_t n) { resize(n); }

AlignedFloatBuffer::~AlignedFloatBuffer() {
    if (data_) pffft_aligned_free(data_);
}

AlignedFloatBuffer::AlignedFloatBuffer(AlignedFloatBuffer&& other) noexcept
    : data_(other.data_), n_(other.n_) {
    other.data_ = nullptr;
    other.n_    = 0;
}

AlignedFloatBuffer& AlignedFloatBuffer::operator=(AlignedFloatBuffer&& other) noexcept {
    if (this != &other) {
        if (data_) pffft_aligned_free(data_);
        data_       = other.data_;
        n_          = other.n_;
        other.data_ = nullptr;
        other.n_    = 0;
    }
    return *this;
}

void AlignedFloatBuffer::resize(std::size_t n) {
    if (data_) { pffft_aligned_free(data_); data_ = nullptr; n_ = 0; }
    if (n == 0) return;
    data_ = static_cast<float*>(pffft_aligned_malloc(n * sizeof(float)));
    n_    = n;
    if (data_) std::memset(data_, 0, n * sizeof(float));
}

void AlignedFloatBuffer::zero() {
    if (data_ && n_ > 0) std::memset(data_, 0, n_ * sizeof(float));
}

// ── helpers ─────────────────────────────────────────────────────────────────

namespace {

bool is_power_of_two(std::size_t n) {
    return n >= 2 && (n & (n - 1)) == 0;
}

} // namespace

// ── PartitionedFirConvolver ─────────────────────────────────────────────────

PartitionedFirConvolver::PartitionedFirConvolver(
    const float* ir, std::size_t ir_len, std::size_t partition_size)
{
    if (ir == nullptr || ir_len == 0)                      return;
    if (!is_power_of_two(partition_size) || partition_size < 32) return;

    P_      = partition_size;
    N_fft_  = 2 * P_;
    ir_len_ = ir_len;

    setup_ = pffft_new_setup(int(N_fft_), PFFFT_REAL);
    if (!setup_) return;

    // Split IR into ceil(ir_len / P) partitions. Each partition gets
    // zero-padded to length 2P (first P samples are the partition, next
    // P are zeros — standard overlap-save requirement) and FFT'd.
    const std::size_t K = (ir_len + P_ - 1) / P_;
    partitions_.reserve(K);
    input_history_.reserve(K);

    AlignedFloatBuffer scratch_time(N_fft_);
    AlignedFloatBuffer scratch_work(N_fft_);

    for (std::size_t k = 0; k < K; ++k) {
        // Zero the whole 2P buffer first, then copy this partition's
        // slice of the IR into the first P samples.
        std::memset(scratch_time.data(), 0, N_fft_ * sizeof(float));
        const std::size_t off  = k * P_;
        const std::size_t take = std::min(P_, ir_len - off);
        std::memcpy(scratch_time.data(), ir + off, take * sizeof(float));

        AlignedFloatBuffer H(N_fft_);
        pffft_transform(setup_, scratch_time.data(), H.data(),
                        scratch_work.data(), PFFFT_FORWARD);
        partitions_.emplace_back(std::move(H));

        // Matching history slot, zeroed.
        input_history_.emplace_back(AlignedFloatBuffer(N_fft_));
    }

    input_block_.resize(P_);
    prev_input_block_.resize(P_);
    current_output_.resize(P_);
    fft_in_scratch_.resize(N_fft_);
    fft_freq_scratch_.resize(N_fft_);
    conv_accum_.resize(N_fft_);
    fft_out_scratch_.resize(N_fft_);
    pffft_work_.resize(N_fft_);

    // Full check that every alignment allocation succeeded before
    // declaring ok. If any of them returned nullptr we'd deref in
    // process() — safer to stay in bypass.
    if (!input_block_.data()      || !prev_input_block_.data() ||
        !current_output_.data()   || !fft_in_scratch_.data()   ||
        !fft_freq_scratch_.data() || !conv_accum_.data()       ||
        !fft_out_scratch_.data()  || !pffft_work_.data()) {
        return;
    }

    input_fill_    = 0;
    history_head_  = 0;
    // First output block is silence until we accumulate P input samples.
    output_read_   = P_;
    ok_            = true;
}

PartitionedFirConvolver::~PartitionedFirConvolver() {
    if (setup_) pffft_destroy_setup(setup_);
}

void PartitionedFirConvolver::reset() {
    input_fill_   = 0;
    history_head_ = 0;
    output_read_  = P_;   // silence for one partition after reset
    prev_input_block_.zero();
    input_block_.zero();
    current_output_.zero();
    for (auto& h : input_history_) h.zero();
}

void PartitionedFirConvolver::process(const float* in, float* out, std::size_t nframes) {
    if (!ok_) {
        // Passthrough — support in == out aliasing.
        if (in != out) std::memcpy(out, in, nframes * sizeof(float));
        return;
    }

    // Invariant: for each of `nframes` input samples consumed, produce
    // exactly one output sample. The output sample is either drained
    // from a previously-fired block's current_output_ (steady state)
    // or silence (first P samples after construction / reset). Fire a
    // fresh FFT block the moment input_fill_ reaches P.
    std::size_t i = 0;
    while (i < nframes) {
        const std::size_t input_room  = P_ - input_fill_;
        const std::size_t output_room = (output_read_ < P_) ? (P_ - output_read_) : 0;

        // Chunk size is bounded by caller's remaining, input room, and
        // (when we have output) output room. When startup-silent, no
        // output-room limit — we can emit silence for as many samples
        // as the caller asks up to what fits in the input accumulator.
        const std::size_t chunk = (output_room > 0)
            ? std::min({nframes - i, input_room, output_room})
            : std::min(nframes - i, input_room);

        // CRITICAL: read input BEFORE writing output, so aliasing
        // (in == out) doesn't clobber the source before we copy it.
        std::memcpy(input_block_.data() + input_fill_, in + i, chunk * sizeof(float));

        if (output_room > 0) {
            std::memcpy(out + i, current_output_.data() + output_read_,
                        chunk * sizeof(float));
            output_read_ += chunk;
        } else {
            std::memset(out + i, 0, chunk * sizeof(float));
        }

        input_fill_ += chunk;
        i           += chunk;

        if (input_fill_ == P_) {
            // Full block accumulated — compute the next P output samples
            // into current_output_ and reset the input accumulator.
            process_one_block();
        }
    }
}

void PartitionedFirConvolver::process_one_block() {
    // ── time-domain: build 2P input = [prev | current] ──────────────────
    std::memcpy(fft_in_scratch_.data(),        prev_input_block_.data(), P_ * sizeof(float));
    std::memcpy(fft_in_scratch_.data() + P_,   input_block_.data(),      P_ * sizeof(float));

    // ── FFT of new input block ──────────────────────────────────────────
    pffft_transform(setup_, fft_in_scratch_.data(), fft_freq_scratch_.data(),
                    pffft_work_.data(), PFFFT_FORWARD);

    // ── store into history at history_head_ (evicting the oldest) ──────
    std::memcpy(input_history_[history_head_].data(),
                fft_freq_scratch_.data(), N_fft_ * sizeof(float));

    // ── frequency-domain: accum = sum_p X_{n-p} * H_p ───────────────────
    std::memset(conv_accum_.data(), 0, N_fft_ * sizeof(float));
    const std::size_t K = partitions_.size();
    // Walk history: newest is at history_head_ (just written); step
    // BACKWARD through the ring to older blocks, pairing with H_p in
    // increasing partition index.
    for (std::size_t p = 0; p < K; ++p) {
        const std::size_t idx = (history_head_ + K - p) % K;
        // zconvolve_accumulate: conv_accum_ += input_history_[idx] * partitions_[p].
        // No pre-scaling here — apply 1/N_fft_ once at the IFFT stage instead.
        pffft_zconvolve_accumulate(setup_,
                                   input_history_[idx].data(),
                                   partitions_[p].data(),
                                   conv_accum_.data(),
                                   1.0f);
    }
    // Advance ring head to the next slot for the next block's FFT.
    history_head_ = (history_head_ + 1) % K;

    // ── IFFT → 2P time-domain result ────────────────────────────────────
    pffft_transform(setup_, conv_accum_.data(), fft_out_scratch_.data(),
                    pffft_work_.data(), PFFFT_BACKWARD);

    // ── overlap-save: output is second half, scaled by 1/N_fft_ ─────────
    const float scale = 1.0f / float(N_fft_);
    for (std::size_t s = 0; s < P_; ++s) {
        current_output_.data()[s] = fft_out_scratch_.data()[P_ + s] * scale;
    }
    output_read_ = 0;

    // ── shift: current block becomes previous, reset accumulator ────────
    std::memcpy(prev_input_block_.data(), input_block_.data(), P_ * sizeof(float));
    input_fill_ = 0;
}

} // namespace couchfi
