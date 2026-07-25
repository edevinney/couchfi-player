// Mac-side unit test for LoadMonoWavIr.
// Build: see tests/run.sh

#include "../../app/src/main/cpp/room_correction/wav_ir_loader.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

using couchfi::LoadMonoWavIr;
using couchfi::WavIrResult;

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

// ── minimal WAV builders (test-only; not a general-purpose writer) ─────────

void put_u32le(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back(static_cast<uint8_t>(x));
    v.push_back(static_cast<uint8_t>(x >> 8));
    v.push_back(static_cast<uint8_t>(x >> 16));
    v.push_back(static_cast<uint8_t>(x >> 24));
}

void put_u16le(std::vector<uint8_t>& v, uint16_t x) {
    v.push_back(static_cast<uint8_t>(x));
    v.push_back(static_cast<uint8_t>(x >> 8));
}

void write_header(std::vector<uint8_t>& v, uint16_t format_tag, uint16_t channels,
                   uint32_t rate, uint16_t bits_per_sample, uint32_t data_bytes) {
    const uint16_t block_align = static_cast<uint16_t>(channels * (bits_per_sample / 8));
    const uint32_t byte_rate = rate * block_align;
    const uint32_t riff_size = 4 + (8 + 16) + (8 + data_bytes);

    v.insert(v.end(), {'R', 'I', 'F', 'F'});
    put_u32le(v, riff_size);
    v.insert(v.end(), {'W', 'A', 'V', 'E'});
    v.insert(v.end(), {'f', 'm', 't', ' '});
    put_u32le(v, 16);
    put_u16le(v, format_tag);
    put_u16le(v, channels);
    put_u32le(v, rate);
    put_u32le(v, byte_rate);
    put_u16le(v, block_align);
    put_u16le(v, bits_per_sample);
    v.insert(v.end(), {'d', 'a', 't', 'a'});
    put_u32le(v, data_bytes);
}

std::vector<uint8_t> make_mono_pcm16(uint32_t rate, const std::vector<int16_t>& samples) {
    std::vector<uint8_t> v;
    const uint32_t data_bytes = static_cast<uint32_t>(samples.size() * 2);
    write_header(v, /*format_tag=*/1, /*channels=*/1, rate, /*bits=*/16, data_bytes);
    for (int16_t s : samples) put_u16le(v, static_cast<uint16_t>(s));
    return v;
}

std::vector<uint8_t> make_mono_pcm24(uint32_t rate, const std::vector<int32_t>& samples) {
    std::vector<uint8_t> v;
    const uint32_t data_bytes = static_cast<uint32_t>(samples.size() * 3);
    write_header(v, 1, 1, rate, 24, data_bytes);
    for (int32_t s : samples) {
        const uint32_t u = static_cast<uint32_t>(s) & 0x00FFFFFFu;
        v.push_back(static_cast<uint8_t>(u));
        v.push_back(static_cast<uint8_t>(u >> 8));
        v.push_back(static_cast<uint8_t>(u >> 16));
    }
    return v;
}

std::vector<uint8_t> make_mono_float32(uint32_t rate, const std::vector<float>& samples) {
    std::vector<uint8_t> v;
    const uint32_t data_bytes = static_cast<uint32_t>(samples.size() * 4);
    write_header(v, /*format_tag=*/3, 1, rate, 32, data_bytes);
    for (float s : samples) {
        uint32_t bits;
        std::memcpy(&bits, &s, sizeof(bits));
        put_u32le(v, bits);
    }
    return v;
}

std::vector<uint8_t> make_stereo_pcm16(uint32_t rate, const std::vector<int16_t>& interleaved) {
    std::vector<uint8_t> v;
    const uint32_t data_bytes = static_cast<uint32_t>(interleaved.size() * 2);
    write_header(v, 1, 2, rate, 16, data_bytes);
    for (int16_t s : interleaved) put_u16le(v, static_cast<uint16_t>(s));
    return v;
}

// ── tests ────────────────────────────────────────────────────────────────

void test_pcm16_roundtrip() {
    std::printf("[test] mono 16-bit PCM: values and rate come back correctly\n");
    std::vector<int16_t> samples = {0, 16384, -16384, 32767, -32768, 1};
    auto wav = make_mono_pcm16(48000, samples);

    WavIrResult r = LoadMonoWavIr(wav.data(), wav.size());
    check(r.ok, "load succeeds");
    check(r.error.empty(), "no error message on success");
    check(r.sample_rate == 48000, "sample_rate == 48000");
    check(r.samples.size() == samples.size(), "sample count matches");
    if (r.samples.size() == samples.size()) {
        check_near(r.samples[0], 0.0, 1e-9, "sample[0] == 0");
        check_near(r.samples[1], 16384.0 / 32768.0, 1e-9, "sample[1]");
        check_near(r.samples[2], -16384.0 / 32768.0, 1e-9, "sample[2]");
        check_near(r.samples[3], 32767.0 / 32768.0, 1e-6, "sample[3] near +1");
        check_near(r.samples[4], -1.0, 1e-9, "sample[4] == -1 (most negative)");
    }
}

void test_pcm24_roundtrip() {
    std::printf("[test] mono 24-bit PCM: sign-extension and scale are correct\n");
    std::vector<int32_t> samples = {0, 4194304, -4194304, 8388607, -8388608};
    auto wav = make_mono_pcm24(48000, samples);

    WavIrResult r = LoadMonoWavIr(wav.data(), wav.size());
    check(r.ok, "load succeeds");
    check(r.samples.size() == samples.size(), "sample count matches");
    if (r.samples.size() == samples.size()) {
        check_near(r.samples[0], 0.0, 1e-9, "sample[0] == 0");
        check_near(r.samples[1], 4194304.0 / 8388608.0, 1e-9, "sample[1] == 0.5");
        check_near(r.samples[2], -4194304.0 / 8388608.0, 1e-9, "sample[2] == -0.5");
        check_near(r.samples[3], 8388607.0 / 8388608.0, 1e-6, "sample[3] near +1");
        check_near(r.samples[4], -1.0, 1e-9, "sample[4] == -1 (most negative)");
    }
}

void test_float32_roundtrip() {
    std::printf("[test] mono 32-bit IEEE float: values pass through unscaled\n");
    std::vector<float> samples = {0.0f, 0.5f, -0.5f, 1.0f, -1.0f};
    auto wav = make_mono_float32(44100, samples);

    WavIrResult r = LoadMonoWavIr(wav.data(), wav.size());
    check(r.ok, "load succeeds");
    check(r.sample_rate == 44100, "sample_rate == 44100");
    check(r.samples.size() == samples.size(), "sample count matches");
    if (r.samples.size() == samples.size()) {
        for (std::size_t i = 0; i < samples.size(); ++i) {
            check_near(r.samples[i], samples[i], 1e-9, "float sample passes through");
        }
    }
}

void test_rejects_non_wav() {
    std::printf("[test] non-WAV input is rejected, not crashed on\n");
    const uint8_t garbage[] = {'X', 'X', 'X', 'X', 0, 0, 0, 0};
    WavIrResult r = LoadMonoWavIr(garbage, sizeof(garbage));
    check(!r.ok, "load fails");
    check(!r.error.empty(), "error message is set");
    check(r.samples.empty(), "samples cleared on failure");
}

void test_rejects_empty_and_null() {
    std::printf("[test] empty/null input doesn't crash\n");
    WavIrResult r1 = LoadMonoWavIr(nullptr, 0);
    check(!r1.ok, "null data rejected");

    const uint8_t tiny[] = {'R', 'I'};
    WavIrResult r2 = LoadMonoWavIr(tiny, sizeof(tiny));
    check(!r2.ok, "too-short buffer rejected");
}

void test_rejects_stereo() {
    std::printf("[test] stereo file is rejected (REW exports one mono file per channel)\n");
    std::vector<int16_t> interleaved = {0, 0, 100, -100, 200, -200};
    auto wav = make_stereo_pcm16(48000, interleaved);
    WavIrResult r = LoadMonoWavIr(wav.data(), wav.size());
    check(!r.ok, "stereo file rejected");
    check(r.error.find("mono") != std::string::npos, "error message mentions mono");
}

void test_rejects_truncated_data_chunk() {
    std::printf("[test] data chunk shorter than declared size doesn't crash / isn't read OOB\n");
    auto wav = make_mono_pcm16(48000, {1, 2, 3, 4});
    // Claim more data than is actually present without shrinking the buffer's
    // real data — simulates a truncated/corrupt download.
    wav.resize(wav.size() - 4);
    WavIrResult r = LoadMonoWavIr(wav.data(), wav.size());
    check(!r.ok, "truncated file rejected rather than read out of bounds");
}

void test_rejects_unsupported_bit_depth() {
    std::printf("[test] unsupported bit depth (e.g. 8-bit PCM) is rejected cleanly\n");
    std::vector<uint8_t> v;
    write_header(v, /*format_tag=*/1, 1, 48000, /*bits=*/8, /*data_bytes=*/4);
    v.insert(v.end(), {128, 129, 127, 126});
    WavIrResult r = LoadMonoWavIr(v.data(), v.size());
    check(!r.ok, "8-bit PCM rejected");
}

void test_extensible_pcm() {
    std::printf("[test] WAVE_FORMAT_EXTENSIBLE wrapping PCM is unwrapped correctly\n");
    // Build a 40-byte fmt chunk manually (extended fmt with a PCM sub-format
    // GUID) since the shared write_header() helper only emits the plain
    // 16-byte fmt chunk.
    std::vector<uint8_t> v;
    std::vector<int16_t> samples = {0, 12345, -12345};
    const uint32_t data_bytes = static_cast<uint32_t>(samples.size() * 2);
    const uint32_t fmt_extra = 40;
    const uint32_t riff_size = 4 + (8 + fmt_extra) + (8 + data_bytes);

    v.insert(v.end(), {'R', 'I', 'F', 'F'});
    put_u32le(v, riff_size);
    v.insert(v.end(), {'W', 'A', 'V', 'E'});
    v.insert(v.end(), {'f', 'm', 't', ' '});
    put_u32le(v, fmt_extra);
    put_u16le(v, 0xFFFE);      // WAVE_FORMAT_EXTENSIBLE
    put_u16le(v, 1);           // mono
    put_u32le(v, 48000);
    put_u32le(v, 48000 * 2);
    put_u16le(v, 2);
    put_u16le(v, 16);
    put_u16le(v, 22);          // cbSize
    put_u16le(v, 16);          // valid bits per sample
    put_u32le(v, 0);           // channel mask
    put_u16le(v, 1);           // SubFormat GUID first 2 bytes: PCM (0x0001)
    for (int i = 0; i < 14; ++i) v.push_back(0); // rest of the GUID, don't-care
    v.insert(v.end(), {'d', 'a', 't', 'a'});
    put_u32le(v, data_bytes);
    for (int16_t s : samples) put_u16le(v, static_cast<uint16_t>(s));

    WavIrResult r = LoadMonoWavIr(v.data(), v.size());
    check(r.ok, "extensible-PCM file loads");
    if (r.ok) {
        check(r.samples.size() == samples.size(), "sample count matches");
        check_near(r.samples[1], 12345.0 / 32768.0, 1e-9, "extensible PCM sample scaled correctly");
    }
}

} // namespace

int main() {
    test_pcm16_roundtrip();
    test_pcm24_roundtrip();
    test_float32_roundtrip();
    test_rejects_non_wav();
    test_rejects_empty_and_null();
    test_rejects_stereo();
    test_rejects_truncated_data_chunk();
    test_rejects_unsupported_bit_depth();
    test_extensible_pcm();

    std::printf("\n%d / %d checks passed\n", g_checks - g_fails, g_checks);
    return g_fails == 0 ? 0 : 1;
}
