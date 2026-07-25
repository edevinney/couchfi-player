#include "wav_ir_loader.h"

#include <cstring>

namespace couchfi {
namespace {

constexpr uint16_t kFormatPcm        = 0x0001;
constexpr uint16_t kFormatIeeeFloat  = 0x0003;
constexpr uint16_t kFormatExtensible = 0xFFFE;

uint32_t ReadU32LE(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

uint16_t ReadU16LE(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | static_cast<uint16_t>(p[1] << 8);
}

// v holds a 24-bit two's-complement value in its low 24 bits; sign-extend
// into a full 32-bit int.
int32_t SignExtend24(uint32_t v) {
    if (v & 0x00800000u) {
        v |= 0xFF000000u;
    }
    return static_cast<int32_t>(v);
}

struct FmtChunk {
    uint16_t format_tag      = 0;
    uint16_t channels        = 0;
    uint32_t sample_rate     = 0;
    uint16_t bits_per_sample = 0;
};

} // namespace

WavIrResult LoadMonoWavIr(const uint8_t* data, std::size_t size) {
    WavIrResult result;

    auto fail = [&](const char* msg) -> WavIrResult {
        result.ok = false;
        result.error = msg;
        result.samples.clear();
        return result;
    };

    if (data == nullptr || size < 12) {
        return fail("file too small to be a WAV");
    }
    if (std::memcmp(data, "RIFF", 4) != 0 || std::memcmp(data + 8, "WAVE", 4) != 0) {
        return fail("not a RIFF/WAVE file");
    }

    bool have_fmt = false;
    FmtChunk fmt;
    const uint8_t* data_chunk = nullptr;
    uint32_t data_chunk_size = 0;

    std::size_t pos = 12; // just past "RIFF" + size(4) + "WAVE"
    while (pos + 8 <= size) {
        const uint8_t* chunk_id = data + pos;
        const uint32_t chunk_size = ReadU32LE(data + pos + 4);
        const std::size_t body = pos + 8;

        if (body + chunk_size > size) {
            // Truncated/corrupt trailing chunk. Stop parsing and work with
            // whatever complete chunks were already found, rather than
            // reading past the end of the buffer.
            break;
        }

        if (std::memcmp(chunk_id, "fmt ", 4) == 0) {
            if (chunk_size < 16) {
                return fail("fmt chunk too small");
            }
            fmt.format_tag      = ReadU16LE(data + body + 0);
            fmt.channels        = ReadU16LE(data + body + 2);
            fmt.sample_rate     = ReadU32LE(data + body + 4);
            fmt.bits_per_sample = ReadU16LE(data + body + 14);

            if (fmt.format_tag == kFormatExtensible && chunk_size >= 40) {
                // WAVEFORMATEXTENSIBLE layout: cbSize(2) @16,
                // ValidBitsPerSample(2) @18, ChannelMask(4) @20,
                // SubFormat GUID(16) @24. The GUID's first two bytes carry
                // the real format tag for the formats this loader supports
                // (KSDATAFORMAT_SUBTYPE_PCM / _IEEE_FLOAT share the same
                // trailing 14 GUID bytes as the plain WAVE_FORMAT_* tags).
                fmt.format_tag = ReadU16LE(data + body + 24);
            }
            have_fmt = true;
        } else if (std::memcmp(chunk_id, "data", 4) == 0) {
            data_chunk = data + body;
            data_chunk_size = chunk_size;
        }

        // RIFF chunks are word-aligned: skip one pad byte after an odd-sized
        // chunk body.
        pos = body + chunk_size + (chunk_size & 1u);
    }

    if (!have_fmt) {
        return fail("missing fmt chunk");
    }
    if (data_chunk == nullptr) {
        return fail("missing data chunk");
    }
    if (fmt.channels != 1) {
        return fail("expected a mono impulse-response file "
                     "(REW exports one file per channel)");
    }
    if (fmt.sample_rate == 0) {
        return fail("fmt chunk reports a 0Hz sample rate");
    }
    if (fmt.bits_per_sample == 0 || (fmt.bits_per_sample % 8) != 0) {
        return fail("unsupported bits-per-sample");
    }

    const int bytes_per_sample = fmt.bits_per_sample / 8;
    if (data_chunk_size % static_cast<uint32_t>(bytes_per_sample) != 0) {
        return fail("data chunk size is not a whole number of samples");
    }

    const std::size_t frame_count = data_chunk_size / static_cast<uint32_t>(bytes_per_sample);
    result.samples.resize(frame_count);

    if (fmt.format_tag == kFormatPcm && fmt.bits_per_sample == 16) {
        for (std::size_t i = 0; i < frame_count; ++i) {
            const int16_t v = static_cast<int16_t>(ReadU16LE(data_chunk + i * 2));
            result.samples[i] = static_cast<float>(v) / 32768.0f;
        }
    } else if (fmt.format_tag == kFormatPcm && fmt.bits_per_sample == 24) {
        for (std::size_t i = 0; i < frame_count; ++i) {
            const uint8_t* p = data_chunk + i * 3;
            const uint32_t raw = static_cast<uint32_t>(p[0]) |
                                  (static_cast<uint32_t>(p[1]) << 8) |
                                  (static_cast<uint32_t>(p[2]) << 16);
            result.samples[i] = static_cast<float>(SignExtend24(raw)) / 8388608.0f;
        }
    } else if (fmt.format_tag == kFormatPcm && fmt.bits_per_sample == 32) {
        for (std::size_t i = 0; i < frame_count; ++i) {
            const int32_t v = static_cast<int32_t>(ReadU32LE(data_chunk + i * 4));
            result.samples[i] = static_cast<float>(v) / 2147483648.0f;
        }
    } else if (fmt.format_tag == kFormatIeeeFloat && fmt.bits_per_sample == 32) {
        for (std::size_t i = 0; i < frame_count; ++i) {
            const uint32_t bits = ReadU32LE(data_chunk + i * 4);
            float v;
            std::memcpy(&v, &bits, sizeof(v));
            result.samples[i] = v;
        }
    } else {
        return fail("unsupported sample format "
                     "(need 16/24/32-bit PCM or 32-bit IEEE float)");
    }

    result.ok = true;
    result.error.clear();
    result.sample_rate = static_cast<int>(fmt.sample_rate);
    return result;
}

} // namespace couchfi
