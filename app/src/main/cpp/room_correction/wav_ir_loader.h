#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace couchfi {

// Result of parsing one REW-exported impulse-response WAV file.
struct WavIrResult {
    bool ok = false;
    std::string error;          // human-readable reason, set iff !ok
    int sample_rate = 0;        // native rate the file was captured at (e.g. 48000)
    std::vector<float> samples; // mono impulse response, range approx [-1, 1]
};

// Parses a mono impulse-response WAV file already sitting in memory.
//
// This takes a raw byte buffer rather than a file path so it has no
// filesystem or SMB dependency of its own — the buffer is expected to have
// already been fetched (over SMB, on the Kotlin side, following the same
// pattern as the existing "Internet Radio/" station-config delivery) before
// being handed to native code. That's also what makes this function testable
// in isolation on the Mac side, with no Android device or network share
// involved: see tests/room_correction/test_wav_ir_loader.cpp.
//
// REW's "File -> Export impulse response as WAV" produces one mono file per
// channel (left.wav / right.wav) — see couchfi-room-correction-plan.md,
// "File format & loading". This loader only needs to handle that shape.
//
// Supports 16-, 24-, and 32-bit integer PCM and 32-bit IEEE float samples,
// including WAVE_FORMAT_EXTENSIBLE fmt chunks that wrap one of those. Mono
// only: REW never produces interleaved multi-channel impulse exports, so a
// file reporting more than one channel is treated as unexpected input.
//
// Never throws and never crashes on malformed/truncated/unsupported input --
// on any problem this returns ok=false with a reason string, so the caller
// can fall back to bypass (no room correction applied) rather than take down
// playback over a bad filter file.
WavIrResult LoadMonoWavIr(const uint8_t* data, std::size_t size);

} // namespace couchfi
