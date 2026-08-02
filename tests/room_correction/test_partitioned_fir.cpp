// Tests for PartitionedFirConvolver. Ground-truth comparison is a
// straightforward time-domain direct-form convolution — same
// numerical result modulo the partition-size startup latency the
// overlap-save scheme introduces (first P output samples are silence).
//
// Style mirrors tests/filter/test_polyphase_fir.cpp: plain main(),
// check()/check_near() helpers, per-test heading, per-file counters.

#include "partitioned_fir.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <numeric>
#include <random>
#include <vector>

using couchfi::PartitionedFirConvolver;

static int g_checks = 0;
static int g_fails  = 0;

static void check(bool cond, const char* what) {
    ++g_checks;
    if (!cond) {
        ++g_fails;
        std::fprintf(stderr, "  FAIL: %s\n", what);
    }
}

static void check_near(float got, float want, float tol, const char* what) {
    ++g_checks;
    if (std::fabs(got - want) > tol) {
        ++g_fails;
        std::fprintf(stderr,
                     "  FAIL: %s  got=%.8f want=%.8f (|diff|=%.2e > tol=%.2e)\n",
                     what, got, want, std::fabs(got - want), tol);
    }
}

// Direct-form time-domain convolution: y[n] = sum_{k=0..L-1} x[n-k] * h[k],
// treating x as zero for indices < 0. Ground truth for the FFT convolver
// modulo the P-sample startup latency (the first P samples of the FFT
// convolver's output are silence — compare from index P onward).
static std::vector<float> direct_convolve(const std::vector<float>& x,
                                          const std::vector<float>& h) {
    std::vector<float> y(x.size(), 0.0f);
    for (std::size_t n = 0; n < x.size(); ++n) {
        float acc = 0.0f;
        const std::size_t k_max = std::min(h.size(), n + 1);
        for (std::size_t k = 0; k < k_max; ++k) {
            acc += x[n - k] * h[k];
        }
        y[n] = acc;
    }
    return y;
}

// ── tests ──────────────────────────────────────────────────────────────────

static void test_bypass_on_invalid_construction() {
    std::printf("[test] ok()=false → process() falls back to sample copy\n");
    PartitionedFirConvolver conv(nullptr, 0, 4096);
    check(!conv.ok(), "null IR → ok() == false");

    std::vector<float> in(1024), out(1024);
    for (std::size_t i = 0; i < in.size(); ++i) in[i] = float(i) * 0.001f;
    conv.process(in.data(), out.data(), in.size());
    for (std::size_t i = 0; i < in.size(); ++i) {
        check_near(out[i], in[i], 0.0f, "passthrough sample matches input");
    }
}

static void test_identity_ir_produces_delayed_passthrough() {
    std::printf("[test] IR = [1.0] → out is in, delayed by P samples\n");
    const std::size_t P = 64;
    std::vector<float> ir = { 1.0f };
    PartitionedFirConvolver conv(ir.data(), ir.size(), P);
    check(conv.ok(), "ok() true for {1.0} IR");
    check(conv.partition_size() == P, "reported partition_size matches");
    check(conv.num_partitions() == 1, "single-partition IR → K == 1");
    check(conv.latency_samples() == P, "reported latency == P");

    // Feed a ramp; expect first P samples of output to be silence, then
    // the ramp itself starting from output index P.
    const std::size_t N = 4 * P;
    std::vector<float> in(N), out(N);
    for (std::size_t i = 0; i < N; ++i) in[i] = float(i) * 0.01f;
    conv.process(in.data(), out.data(), N);

    for (std::size_t i = 0; i < P; ++i) {
        check_near(out[i], 0.0f, 1e-6f, "startup silence sample");
    }
    for (std::size_t i = P; i < N; ++i) {
        check_near(out[i], in[i - P], 1e-5f, "post-latency passthrough sample");
    }
}

static void test_impulse_response_reproduces_ir() {
    std::printf("[test] input = δ(0) → output reproduces the IR (offset by P)\n");
    const std::size_t P = 128;
    // Small IR spanning 3 partitions so the multi-partition sum-branch
    // is exercised.
    std::vector<float> ir(3 * P);
    for (std::size_t i = 0; i < ir.size(); ++i) {
        ir[i] = 0.5f * std::exp(-float(i) / float(P));  // decaying tail
    }
    PartitionedFirConvolver conv(ir.data(), ir.size(), P);
    check(conv.ok(), "ok() true for 3-partition IR");
    check(conv.num_partitions() == 3, "K == 3 for ir_len == 3P");

    // Enough samples to cover startup latency + full IR reproduction.
    const std::size_t N = 5 * P;
    std::vector<float> in(N, 0.0f), out(N, 0.0f);
    in[0] = 1.0f;
    conv.process(in.data(), out.data(), N);

    // Post-latency: out[P + k] should equal ir[k] for k in [0, ir.size()).
    for (std::size_t k = 0; k < ir.size(); ++k) {
        check_near(out[P + k], ir[k], 1e-5f, "output reproduces IR sample");
    }
    // After IR ends, output should return to silence.
    for (std::size_t k = ir.size(); P + k < N; ++k) {
        check_near(out[P + k], 0.0f, 1e-5f, "output silence past IR end");
    }
}

static void test_dc_gain_equals_sum_of_ir() {
    std::printf("[test] DC input → steady output at gain sum(IR)\n");
    const std::size_t P = 64;
    std::vector<float> ir(2 * P);
    std::mt19937 rng(0xC0DE);
    std::uniform_real_distribution<float> dist(-0.5f, 0.5f);
    for (auto& v : ir) v = dist(rng);
    const float expected_gain = std::accumulate(ir.begin(), ir.end(), 0.0f);

    PartitionedFirConvolver conv(ir.data(), ir.size(), P);
    check(conv.ok(), "ok() true");

    // Feed a long DC input. After 2P samples of latency + ramp-up the
    // output should sit at expected_gain (steady state).
    const std::size_t N = 8 * P;
    std::vector<float> in(N, 1.0f), out(N, 0.0f);
    conv.process(in.data(), out.data(), N);

    // Skip startup + IR ramp-up: check the tail is at expected_gain.
    const std::size_t settled = P + ir.size();
    for (std::size_t i = settled; i < N; ++i) {
        check_near(out[i], expected_gain, 1e-4f, "steady DC output at sum(IR)");
    }
}

static void test_matches_direct_convolution() {
    std::printf("[test] matches direct-form time-domain convolution on a random IR + signal\n");
    const std::size_t P = 64;
    const std::size_t IR_LEN = 200;  // spans across a partition boundary
    std::mt19937 rng(0xF00D);
    std::uniform_real_distribution<float> dist(-1.0f, 1.0f);

    std::vector<float> ir(IR_LEN);
    for (auto& v : ir) v = dist(rng) * 0.1f;

    const std::size_t N = 8 * P;
    std::vector<float> in(N);
    for (auto& v : in) v = dist(rng);

    std::vector<float> ref = direct_convolve(in, ir);

    PartitionedFirConvolver conv(ir.data(), ir.size(), P);
    check(conv.ok(), "ok() true");
    std::vector<float> got(N);
    conv.process(in.data(), got.data(), N);

    // The convolver has P samples of startup latency: got[i] carries
    // filter output for input index (i - P). Compare accordingly.
    float max_err = 0.0f;
    for (std::size_t i = P; i < N; ++i) {
        max_err = std::max(max_err, std::fabs(got[i] - ref[i - P]));
    }
    check(max_err < 1e-4f, "max |FFT - direct| < 1e-4 across settled samples");
    std::printf("  info: max |FFT - direct| = %.2e over %zu settled samples\n",
                max_err, N - P);
}

static void test_streaming_chunk_size_invariance() {
    std::printf("[test] output identical regardless of process() chunk sizes\n");
    const std::size_t P = 64;
    const std::size_t IR_LEN = 150;
    std::mt19937 rng(0xBEE);
    std::uniform_real_distribution<float> dist(-1.0f, 1.0f);

    std::vector<float> ir(IR_LEN);
    for (auto& v : ir) v = dist(rng) * 0.2f;

    const std::size_t N = 6 * P;
    std::vector<float> in(N);
    for (auto& v : in) v = dist(rng);

    // Reference: one big call.
    PartitionedFirConvolver conv_big(ir.data(), ir.size(), P);
    std::vector<float> out_big(N);
    conv_big.process(in.data(), out_big.data(), N);

    // Comparison: many small odd-sized chunks.
    PartitionedFirConvolver conv_small(ir.data(), ir.size(), P);
    std::vector<float> out_small(N);
    const std::size_t chunks[] = { 1, 7, 13, P, P + 5, 3, 250 };
    std::size_t i = 0, k = 0;
    while (i < N) {
        std::size_t c = std::min(chunks[k % (sizeof(chunks)/sizeof(chunks[0]))], N - i);
        conv_small.process(in.data() + i, out_small.data() + i, c);
        i += c;
        ++k;
    }

    float max_err = 0.0f;
    for (std::size_t j = 0; j < N; ++j) {
        max_err = std::max(max_err, std::fabs(out_big[j] - out_small[j]));
    }
    check(max_err < 1e-6f, "chunked call sequence == single big call, sample-for-sample");
    std::printf("  info: max |big - small_chunks| = %.2e\n", max_err);
}

static void test_in_place_aliasing() {
    std::printf("[test] in == out (aliasing) produces same result as separate buffers\n");
    const std::size_t P = 64;
    std::vector<float> ir(P);
    for (std::size_t i = 0; i < P; ++i) ir[i] = std::exp(-float(i) / 32.0f);

    const std::size_t N = 4 * P;
    std::vector<float> in(N);
    for (std::size_t i = 0; i < N; ++i) in[i] = std::sin(float(i) * 0.03f);

    PartitionedFirConvolver a(ir.data(), ir.size(), P);
    std::vector<float> out_ab(N);
    a.process(in.data(), out_ab.data(), N);

    PartitionedFirConvolver b(ir.data(), ir.size(), P);
    std::vector<float> inout = in;
    b.process(inout.data(), inout.data(), N);

    float max_err = 0.0f;
    for (std::size_t i = 0; i < N; ++i) {
        max_err = std::max(max_err, std::fabs(out_ab[i] - inout[i]));
    }
    check(max_err < 1e-6f, "aliased in==out produces identical output");
}

static void test_reset_returns_to_startup_state() {
    std::printf("[test] reset() clears streaming state; next output block starts with silence\n");
    const std::size_t P = 64;
    std::vector<float> ir(P);
    for (std::size_t i = 0; i < P; ++i) ir[i] = 0.1f;

    PartitionedFirConvolver conv(ir.data(), ir.size(), P);

    // Prime the convolver with a DC input long enough to leave a tail.
    std::vector<float> in(3 * P, 1.0f), out(3 * P, 0.0f);
    conv.process(in.data(), out.data(), in.size());

    // Verify the tail is real (non-zero) — sanity check we ran real work.
    check(std::fabs(out[2 * P]) > 0.1f, "primed convolver has non-zero output at tail");

    conv.reset();

    // Feed a silent input; expect silent output (no leaked tail).
    std::vector<float> silent(2 * P, 0.0f), fresh(2 * P, 42.0f);
    conv.process(silent.data(), fresh.data(), silent.size());
    for (std::size_t i = 0; i < fresh.size(); ++i) {
        check_near(fresh[i], 0.0f, 1e-6f, "post-reset silent input → silent output");
    }
}

static void test_finite_output_on_decaying_signal() {
    std::printf("[test] no NaN/Inf on realistic decaying signal + realistic IR\n");
    const std::size_t P = 128;
    std::vector<float> ir(4 * P);
    for (std::size_t i = 0; i < ir.size(); ++i) {
        // exponentially decaying, wiggly IR — mimics room decay shape.
        ir[i] = std::exp(-float(i) / float(P)) *
                std::sin(2.0f * float(M_PI) * float(i) / 32.0f);
    }
    PartitionedFirConvolver conv(ir.data(), ir.size(), P);
    check(conv.ok(), "ok() true");

    const std::size_t N = 16 * P;
    std::vector<float> in(N), out(N);
    for (std::size_t i = 0; i < N; ++i) {
        in[i] = std::sin(float(i) * 0.017f) * std::exp(-float(i) / float(N));
    }
    conv.process(in.data(), out.data(), N);

    bool all_finite = true;
    for (std::size_t i = 0; i < N; ++i) {
        if (!std::isfinite(out[i])) { all_finite = false; break; }
    }
    check(all_finite, "output is finite (no NaN/Inf)");
}

int main() {
    test_bypass_on_invalid_construction();
    test_identity_ir_produces_delayed_passthrough();
    test_impulse_response_reproduces_ir();
    test_dc_gain_equals_sum_of_ir();
    test_matches_direct_convolution();
    test_streaming_chunk_size_invariance();
    test_in_place_aliasing();
    test_reset_returns_to_startup_state();
    test_finite_output_on_decaying_signal();

    std::printf("\n%d / %d checks passed\n", g_checks - g_fails, g_checks);
    return g_fails == 0 ? 0 : 1;
}
