// Mac-side unit test for ResampleIrToEngineRate.
// Build: see tests/run.sh

#include "../../app/src/main/cpp/room_correction/ir_resampler.h"

#include <cmath>
#include <cstdio>
#include <vector>

using couchfi::ResampleIrToEngineRate;

namespace {

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

constexpr double PI = 3.14159265358979323846;

// ── tests ────────────────────────────────────────────────────────────────

void test_passthrough_when_rates_equal() {
    std::printf("[test] src_rate == dst_rate returns input unchanged\n");
    std::vector<float> in = {0.1f, -0.2f, 0.3f, -0.4f, 0.5f};
    auto out = ResampleIrToEngineRate(in, 176400.0, 176400.0);
    check(out.size() == in.size(), "same length");
    for (std::size_t i = 0; i < in.size() && i < out.size(); ++i) {
        check_near(out[i], in[i], 1e-12, "passthrough sample unchanged");
    }
}

void test_empty_input() {
    std::printf("[test] empty input returns empty output, doesn't crash\n");
    std::vector<float> in;
    auto out = ResampleIrToEngineRate(in, 48000.0, 176400.0);
    check(out.empty(), "empty in -> empty out");
}

void test_output_length_matches_ratio() {
    std::printf("[test] 48000 -> 176400: output length matches the exact rate ratio\n");
    // A couple of REW-realistic lengths (a short one and one comparable in
    // order-of-magnitude to a real ~2-second export at 48kHz: 96000 frames).
    for (std::size_t n : {100u, 1000u, 4800u, 96000u}) {
        std::vector<float> in(n, 0.0f);
        in[0] = 1.0f; // impulse, so this also feeds test_impulse_energy_present below
        auto out = ResampleIrToEngineRate(in, 48000.0, 176400.0);
        const double expected =
            std::llround(static_cast<double>(n) * 176400.0 / 48000.0);
        char msg[96];
        std::snprintf(msg, sizeof(msg), "output length for n=%zu", n);
        check(out.size() == static_cast<std::size_t>(expected), msg);
    }
}

void test_all_finite() {
    std::printf("[test] output is finite for a realistic-shaped impulse tail\n");
    // A decaying "impulse response"-shaped signal, not just a single spike.
    const std::size_t n = 8000;
    std::vector<float> in(n);
    for (std::size_t i = 0; i < n; ++i) {
        in[i] = static_cast<float>(std::exp(-double(i) / 500.0) *
                                    std::sin(2.0 * PI * 300.0 * i / 48000.0));
    }
    auto out = ResampleIrToEngineRate(in, 48000.0, 176400.0);
    bool ok = true;
    for (float s : out) {
        if (!std::isfinite(s)) { ok = false; break; }
    }
    check(ok, "all resampled output samples finite");
}

void test_energy_roughly_preserved() {
    std::printf("[test] resampling roughly preserves signal energy per sample "
                "(sanity check the frequency response wasn't nuked or exploded)\n");
    // Not a strict correctness proof of the resampler itself (that's
    // r8brain-free-src's own job) -- just a smoke check that CouchFi's use of
    // it is wired up sanely: a low-frequency tone's average power per sample
    // should survive resampling within a generous tolerance, rather than
    // silently coming back near-zero (e.g. from a channel/rate mixup) or
    // wildly amplified (e.g. from a scaling bug).
    const std::size_t n = 4800; // 100ms @ 48kHz
    std::vector<float> in(n);
    for (std::size_t i = 0; i < n; ++i) {
        in[i] = static_cast<float>(std::sin(2.0 * PI * 200.0 * i / 48000.0));
    }
    auto out = ResampleIrToEngineRate(in, 48000.0, 176400.0);

    double in_power = 0.0;
    for (float s : in) in_power += double(s) * s;
    in_power /= in.size();

    double out_power = 0.0;
    for (float s : out) out_power += double(s) * s;
    out_power /= out.size();

    check(out_power > 0.1 * in_power && out_power < 4.0 * in_power,
          "per-sample power stays within a generous [0.1x, 4x] band");
    std::printf("  info: in_power=%.6f out_power=%.6f ratio=%.3f\n",
                in_power, out_power, out_power / in_power);
}

} // namespace

int main() {
    test_passthrough_when_rates_equal();
    test_empty_input();
    test_output_length_matches_ratio();
    test_all_finite();
    test_energy_roughly_preserved();

    std::printf("\n%d / %d checks passed\n", g_checks - g_fails, g_checks);
    return g_fails == 0 ? 0 : 1;
}
