// Round-trip smoke test for the vendored PFFFT.
//
// Purpose: verify the library compiles and links cleanly on the host
// toolchain and that a forward-then-inverse real FFT reconstructs the
// input to float-precision. If this passes, PFFFT is usable and the
// partitioned convolver can be built on top of it. If it fails, the
// partitioned convolver's test failures would be harder to root-cause.

#include "pffft.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

// Following the existing tests/filter/test_polyphase_fir.cpp convention.
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
        std::fprintf(stderr, "  FAIL: %s  got=%.8f want=%.8f (|diff|=%.2e > tol=%.2e)\n",
                     what, got, want, std::fabs(got - want), tol);
    }
}

// PFFFT requires 16-byte-aligned buffers. Use pffft_aligned_malloc /
// pffft_aligned_free so the alignment is guaranteed and the test
// exercises the same allocator the real convolver will.
struct AlignedBuf {
    float* p = nullptr;
    std::size_t n = 0;
    AlignedBuf(std::size_t n_)
        : p(static_cast<float*>(pffft_aligned_malloc(n_ * sizeof(float)))), n(n_) {}
    ~AlignedBuf() { pffft_aligned_free(p); }
    AlignedBuf(const AlignedBuf&) = delete;
    AlignedBuf& operator=(const AlignedBuf&) = delete;
};

// Forward-then-inverse real-to-real FFT should reproduce the input to
// within float precision, scaled by 1/N.
static void test_round_trip_impulse() {
    std::printf("[test] real fft round-trip: impulse in → same impulse out (scale 1/N)\n");
    const int N = 512;
    PFFFT_Setup* s = pffft_new_setup(N, PFFFT_REAL);
    check(s != nullptr, "pffft_new_setup(N=512, REAL) returns non-null");
    if (!s) return;

    AlignedBuf in(N), freq(N), out(N), work(N);
    std::memset(in.p, 0, N * sizeof(float));
    in.p[0] = 1.0f;  // Kronecker delta

    pffft_transform_ordered(s, in.p, freq.p, work.p, PFFFT_FORWARD);
    pffft_transform_ordered(s, freq.p, out.p, work.p, PFFFT_BACKWARD);

    // PFFFT's inverse is unnormalized: y_reconstructed = N * y_input.
    // Divide out to compare.
    for (int i = 0; i < N; ++i) out.p[i] /= float(N);

    check_near(out.p[0], 1.0f, 1e-5f, "reconstructed impulse: sample 0 is 1.0");
    float max_other = 0.0f;
    for (int i = 1; i < N; ++i) {
        max_other = std::max(max_other, std::fabs(out.p[i]));
    }
    check(max_other < 1e-5f, "reconstructed impulse: samples 1..N-1 are ~zero");

    pffft_destroy_setup(s);
}

static void test_round_trip_sine() {
    std::printf("[test] real fft round-trip: 1-cycle sine reconstructs to within float precision\n");
    const int N = 1024;
    PFFFT_Setup* s = pffft_new_setup(N, PFFFT_REAL);
    check(s != nullptr, "pffft_new_setup(N=1024, REAL) returns non-null");
    if (!s) return;

    AlignedBuf in(N), freq(N), out(N), work(N);
    for (int i = 0; i < N; ++i) {
        in.p[i] = std::sin(2.0f * float(M_PI) * float(i) / float(N));
    }
    // Save a copy for comparison — inverse writes to `out`, but we want
    // to check `out ≈ in`, not `out ≈ freq` etc.
    std::vector<float> expected(in.p, in.p + N);

    pffft_transform_ordered(s, in.p, freq.p, work.p, PFFFT_FORWARD);
    pffft_transform_ordered(s, freq.p, out.p, work.p, PFFFT_BACKWARD);
    for (int i = 0; i < N; ++i) out.p[i] /= float(N);

    float max_err = 0.0f;
    for (int i = 0; i < N; ++i) {
        max_err = std::max(max_err, std::fabs(out.p[i] - expected[i]));
    }
    check(max_err < 1e-5f, "sine round-trip max |err| < 1e-5");
    std::printf("  info: max |err| over %d samples = %.2e\n", N, max_err);

    pffft_destroy_setup(s);
}

// zconvolve_accumulate is the frequency-domain multiply-and-add that
// the partitioned convolver relies on. Sanity-check it does what we
// expect: given FFT(a) and FFT(b), zconvolve_accumulate(dft(a), dft(b),
// accum, scaling) should give the FFT of the circular convolution of
// a and b (scaled).
static void test_zconvolve_scaling() {
    std::printf("[test] pffft_zconvolve_accumulate scales output by the caller-supplied factor\n");
    const int N = 128;
    PFFFT_Setup* s = pffft_new_setup(N, PFFFT_REAL);
    check(s != nullptr, "pffft_new_setup(N=128, REAL) returns non-null");
    if (!s) return;

    AlignedBuf a(N), b(N), fa(N), fb(N), accum(N), work(N), out(N);
    // a = Kronecker delta → FFT(a) = all ones (in PFFFT's internal ordering).
    // b = some real signal.
    std::memset(a.p, 0, N * sizeof(float));
    a.p[0] = 1.0f;
    for (int i = 0; i < N; ++i) b.p[i] = std::cos(2.0f * float(M_PI) * float(i) / 32.0f);

    pffft_transform(s, a.p, fa.p, work.p, PFFFT_FORWARD);
    pffft_transform(s, b.p, fb.p, work.p, PFFFT_FORWARD);
    std::memset(accum.p, 0, N * sizeof(float));

    // convolve delta with b → b. Scale factor 1/N cancels the inverse's
    // scaling. Use pffft_transform (not _ordered) since zconvolve
    // operates in PFFFT's internal order.
    pffft_zconvolve_accumulate(s, fa.p, fb.p, accum.p, 1.0f / float(N));
    pffft_transform(s, accum.p, out.p, work.p, PFFFT_BACKWARD);

    float max_err = 0.0f;
    for (int i = 0; i < N; ++i) {
        max_err = std::max(max_err, std::fabs(out.p[i] - b.p[i]));
    }
    check(max_err < 1e-4f, "delta * b == b via zconvolve_accumulate (max |err| < 1e-4)");
    std::printf("  info: delta * b vs b, max |err| = %.2e over %d samples\n", max_err, N);

    pffft_destroy_setup(s);
}

int main() {
    test_round_trip_impulse();
    test_round_trip_sine();
    test_zconvolve_scaling();

    std::printf("\n%d / %d checks passed\n", g_checks - g_fails, g_checks);
    return g_fails == 0 ? 0 : 1;
}
