// Mac-side unit test for PolyphaseFir.
// Build: see tests/run.sh

#include "../../app/src/main/cpp/filter/polyphase_fir.h"

#include <cmath>
#include <cstdio>
#include <vector>

using couchfi::PolyphaseFir;

namespace {

constexpr double PI = 3.14159265358979323846;

int g_checks = 0;
int g_fails  = 0;

void check(bool cond, const char* msg) {
    ++g_checks;
    if (!cond) {
        ++g_fails;
        std::printf("  FAIL: %s\n", msg);
    }
}

void check_near(double actual, double expected, double tol, const char* msg) {
    ++g_checks;
    const double diff = std::fabs(actual - expected);
    if (diff > tol) {
        ++g_fails;
        std::printf("  FAIL: %s  (got %.9g, want %.9g, |diff|=%.3g > %.3g)\n",
                    msg, actual, expected, diff, tol);
    }
}

// ── prototype shape ──────────────────────────────────────────────────────────

void test_symmetry() {
    std::printf("[test] prototype symmetry\n");
    PolyphaseFir fir;
    const float* h = fir.prototype();
    for (int n = 0; n < PolyphaseFir::TAPS / 2; ++n) {
        const int mirror = PolyphaseFir::TAPS - 1 - n;
        check_near(h[n], h[mirror], 1e-7,
                   "prototype symmetric about center (linear phase)");
    }
}

void test_hann_endpoints() {
    std::printf("[test] Hann endpoints zero\n");
    PolyphaseFir fir;
    const float* h = fir.prototype();
    check_near(h[0], 0.0, 1e-7, "h[0] == 0");
    check_near(h[PolyphaseFir::TAPS - 1], 0.0, 1e-7, "h[N-1] == 0");
}

void test_peak_at_center() {
    std::printf("[test] peak near center\n");
    PolyphaseFir fir;
    const float* h = fir.prototype();
    int argmax = 0;
    float max = h[0];
    for (int n = 1; n < PolyphaseFir::TAPS; ++n) {
        if (h[n] > max) { max = h[n]; argmax = n; }
    }
    // For TAPS=64, center is between taps 31 and 32.
    check(argmax == 31 || argmax == 32, "argmax is at center tap");
}

// ── DC gain ──────────────────────────────────────────────────────────────────

void test_prototype_sum_equals_L() {
    std::printf("[test] prototype sum == L\n");
    PolyphaseFir fir;
    const float* h = fir.prototype();
    double sum = 0.0;
    for (int n = 0; n < PolyphaseFir::TAPS; ++n) sum += h[n];
    check_near(sum, PolyphaseFir::L, 1e-5, "sum(h) == L");
}

void test_phase_sums_total_L() {
    std::printf("[test] sum of all phase DC gains == L (weaker than per-phase)\n");
    // Per-phase DC gain is NOT 1 for this design: Pine's filter uses a gentle
    // 0.8·Nyquist cutoff that lets images through, so the polyphase branches
    // have uneven DC sums. What IS preserved is the total — sum across all
    // phases equals the prototype sum == L.
    PolyphaseFir fir;
    double total = 0.0;
    for (int p = 0; p < PolyphaseFir::L; ++p) {
        const float* hp = fir.phase(p);
        for (int k = 0; k < PolyphaseFir::TAPS_PER_PHASE; ++k) total += hp[k];
    }
    check_near(total, PolyphaseFir::L, 1e-5, "sum of all phase coefficients == L");
}

void test_phase_sum_symmetry() {
    std::printf("[test] phase DC gains are symmetric (phase p ↔ L-1-p)\n");
    // For a symmetric prototype h[n]=h[N-1-n], phase p taps are the reverse of
    // phase (L-1-p) taps, so their sums must be equal.
    PolyphaseFir fir;
    double sums[PolyphaseFir::L] = {};
    for (int p = 0; p < PolyphaseFir::L; ++p) {
        const float* hp = fir.phase(p);
        for (int k = 0; k < PolyphaseFir::TAPS_PER_PHASE; ++k) sums[p] += hp[k];
    }
    for (int p = 0; p < PolyphaseFir::L / 2; ++p) {
        char msg[64];
        std::snprintf(msg, sizeof(msg), "sum(phase %d) == sum(phase %d)",
                      p, PolyphaseFir::L - 1 - p);
        check_near(sums[p], sums[PolyphaseFir::L - 1 - p], 1e-6, msg);
    }
}

// ── polyphase decomposition ──────────────────────────────────────────────────

void test_polyphase_decomposition() {
    std::printf("[test] polyphase branches are correct prototype slices\n");
    PolyphaseFir fir;
    const float* proto = fir.prototype();
    for (int p = 0; p < PolyphaseFir::L; ++p) {
        const float* hp = fir.phase(p);
        for (int k = 0; k < PolyphaseFir::TAPS_PER_PHASE; ++k) {
            check_near(hp[k], proto[p + k * PolyphaseFir::L], 1e-7,
                       "phase(p)[k] == prototype[p + k*L]");
        }
    }
}

// ── streaming behavior ───────────────────────────────────────────────────────

void test_dc_average_preserved() {
    std::printf("[test] DC input: L-frame rolling average of outputs == input\n");
    // Instantaneous samples aren't equal to the DC input (Pine's filter doesn't
    // preserve per-sample DC — see test_phase_sums_total_L). But any window of
    // L consecutive output frames covers all L phases once, and their sum
    // scales to L × input, so the average recovers the input exactly.
    PolyphaseFir fir;
    const int N_in = 64;
    const int L = PolyphaseFir::L;
    std::vector<float> in(N_in * 2, 1.0f);
    std::vector<float> out(N_in * 2 * L);
    fir.process(in.data(), N_in, out.data());

    const int warmup_frames = PolyphaseFir::TAPS_PER_PHASE * L;  // output-rate frames
    const int total_out_frames = N_in * L;
    for (int f = warmup_frames; f + L <= total_out_frames; ++f) {
        double sum_l = 0.0, sum_r = 0.0;
        for (int p = 0; p < L; ++p) {
            sum_l += out[(f + p) * 2];
            sum_r += out[(f + p) * 2 + 1];
        }
        check_near(sum_l / L, 1.0, 1e-5, "L-frame DC avg (L ch) == 1");
        check_near(sum_r / L, 1.0, 1e-5, "L-frame DC avg (R ch) == 1");
    }
}

void test_impulse_response_matches_prototype() {
    std::printf("[test] impulse → prototype reconstruction\n");
    // Feeding an impulse of amplitude 1 on L and 0 on R must produce,
    // on the L output channel, the prototype FIR's first TAPS samples
    // (because the polyphase output sequence is proto[0], proto[1], …).
    PolyphaseFir fir;
    const int N_in = PolyphaseFir::TAPS_PER_PHASE + 4;
    std::vector<float> in(N_in * 2, 0.0f);
    in[0] = 1.0f;
    std::vector<float> out(N_in * 2 * PolyphaseFir::L);
    fir.process(in.data(), N_in, out.data());

    const float* proto = fir.prototype();
    for (int n = 0; n < PolyphaseFir::TAPS; ++n) {
        check_near(out[n * 2],     proto[n], 1e-6, "L out matches proto[n]");
        check_near(out[n * 2 + 1], 0.0,      1e-6, "R out is zero");
    }
}

void test_finite_output_on_sine() {
    std::printf("[test] output finite on 1 kHz sine / 1 kHz cosine\n");
    PolyphaseFir fir;
    const int N_in = 1024;
    std::vector<float> in(N_in * 2);
    for (int i = 0; i < N_in; ++i) {
        const double t = double(i) / 44100.0;
        in[2 * i]     = float(std::sin(2.0 * PI * 1000.0 * t));
        in[2 * i + 1] = float(std::cos(2.0 * PI * 1000.0 * t));
    }
    std::vector<float> out(N_in * 2 * PolyphaseFir::L);
    fir.process(in.data(), N_in, out.data());

    bool ok = true;
    for (float s : out) {
        if (!std::isfinite(s)) { ok = false; break; }
    }
    check(ok, "all output samples finite");
}

void test_1khz_l_averaged_tracks_input() {
    std::printf("[test] 1 kHz sine: L-averaged output tracks input (±group delay)\n");
    // Individual output samples carry image content at fs_in and its harmonics.
    // But fs_in at fs_out rate has period L samples exactly, so averaging any
    // L consecutive output samples cancels those images, leaving the
    // low-frequency input delayed by the filter's group delay.
    //
    // Group delay of a symmetric N-tap FIR at fs_out is (N-1)/2 output samples.
    // For N=64, L=4: τ = 31.5 output samples = 7.875 input samples.
    //
    // So: avg(y[j*L + 30 .. j*L + 33]) (midpoint at j*L + 31.5 = j·L + τ)
    //     should equal x[j].
    PolyphaseFir fir;
    const int L = PolyphaseFir::L;
    const int N_in = 4096;
    std::vector<float> in(N_in * 2);
    for (int i = 0; i < N_in; ++i) {
        const double t = double(i) / 44100.0;
        in[2 * i]     = float(std::sin(2.0 * PI * 1000.0 * t));
        in[2 * i + 1] = in[2 * i];
    }
    std::vector<float> out(N_in * 2 * L);
    fir.process(in.data(), N_in, out.data());

    // Window [j*L + 30, j*L + 33] — midpoint exactly j*L + 31.5 = group delay.
    const int tau_floor = (PolyphaseFir::TAPS - 1) / 2;        // 31
    const int win_start_off = tau_floor - L / 2 + 1;           // 30
    const int warmup_frames = PolyphaseFir::TAPS_PER_PHASE;    // 16
    const int total_out_frames = N_in * L;
    const int j_max = (total_out_frames - win_start_off - L) / L;  // inclusive upper bound

    float max_abs_err = 0.0f;
    int compared = 0;
    for (int j = warmup_frames; j <= j_max; ++j) {
        const int n0 = j * L + win_start_off;
        double avg = 0.0;
        for (int p = 0; p < L; ++p) avg += out[(n0 + p) * 2];
        avg /= L;
        const float err = std::fabs(float(avg) - in[2 * j]);
        max_abs_err = std::max(max_abs_err, err);
        ++compared;
    }
    // 1 kHz changes ~0.14 rad per input sample; averaging across a 4-sample
    // window at fs_out adds ≤ ~8° of sample-offset distortion. 1% suffices.
    check(max_abs_err < 0.01f, "1 kHz L-avg tracks input within 1%");
    std::printf("  info: max |err| over %d samples = %.6f\n", compared, max_abs_err);
}

void test_reset_clears_history() {
    std::printf("[test] reset() zeroes the delay line\n");
    PolyphaseFir fir;
    std::vector<float> pulse(16 * 2, 1.0f);
    std::vector<float> out1(pulse.size() * PolyphaseFir::L);
    fir.process(pulse.data(), 16, out1.data());

    fir.reset();

    std::vector<float> zero(16 * 2, 0.0f);
    std::vector<float> out2(zero.size() * PolyphaseFir::L);
    fir.process(zero.data(), 16, out2.data());

    bool ok = true;
    for (float s : out2) {
        if (std::fabs(s) > 1e-7f) { ok = false; break; }
    }
    check(ok, "post-reset zero input produces zero output");
}

} // namespace

int main() {
    test_symmetry();
    test_hann_endpoints();
    test_peak_at_center();
    test_prototype_sum_equals_L();
    test_phase_sums_total_L();
    test_phase_sum_symmetry();
    test_polyphase_decomposition();
    test_dc_average_preserved();
    test_impulse_response_matches_prototype();
    test_finite_output_on_sine();
    test_1khz_l_averaged_tracks_input();
    test_reset_clears_history();

    std::printf("\n%d / %d checks passed\n", g_checks - g_fails, g_checks);
    return g_fails == 0 ? 0 : 1;
}
