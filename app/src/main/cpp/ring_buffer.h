#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace couchfi {

// Single-producer / single-consumer byte ring buffer.
//
// Thread safety: exactly one producer thread calls write() / pending();
// exactly one consumer thread calls read(). capacity() is usable anywhere.
// One slot is always left unused so full vs empty can be distinguished.
class ByteRing {
public:
    explicit ByteRing(std::size_t capacity_bytes)
        : buf_(capacity_bytes + 1), cap_(capacity_bytes + 1) {}

    std::size_t capacity() const { return cap_ - 1; }

    // Drop all pending bytes. SAFE ONLY when no producer is writing AND no
    // consumer is mid-read; callers must arrange that (e.g. by joining the
    // producer thread and muting the consumer briefly before calling).
    void clear() {
        read_.store(0, std::memory_order_release);
        write_.store(0, std::memory_order_release);
    }

    std::size_t pending() const {
        const std::size_t r = read_.load(std::memory_order_acquire);
        const std::size_t w = write_.load(std::memory_order_acquire);
        return (w >= r) ? (w - r) : (cap_ - (r - w));
    }

    std::size_t free_space() const { return capacity() - pending(); }

    // Copy up to n bytes in; returns count actually written.
    std::size_t write(const uint8_t* src, std::size_t n) {
        const std::size_t w = write_.load(std::memory_order_relaxed);
        const std::size_t r = read_.load(std::memory_order_acquire);
        const std::size_t space = (w >= r) ? (cap_ - (w - r) - 1) : (r - w - 1);
        const std::size_t to_write = std::min(n, space);

        const std::size_t tail = std::min(to_write, cap_ - w);
        std::memcpy(&buf_[w], src, tail);
        if (to_write > tail) {
            std::memcpy(&buf_[0], src + tail, to_write - tail);
        }
        write_.store((w + to_write) % cap_, std::memory_order_release);
        return to_write;
    }

    // Copy up to n bytes out; returns count actually read.
    std::size_t read(uint8_t* dst, std::size_t n) {
        const std::size_t r = read_.load(std::memory_order_relaxed);
        const std::size_t w = write_.load(std::memory_order_acquire);
        const std::size_t avail = (w >= r) ? (w - r) : (cap_ - (r - w));
        const std::size_t to_read = std::min(n, avail);

        const std::size_t tail = std::min(to_read, cap_ - r);
        std::memcpy(dst, &buf_[r], tail);
        if (to_read > tail) {
            std::memcpy(dst + tail, &buf_[0], to_read - tail);
        }
        read_.store((r + to_read) % cap_, std::memory_order_release);
        return to_read;
    }

private:
    std::vector<uint8_t> buf_;
    const std::size_t    cap_;
    std::atomic<std::size_t> read_{0};
    std::atomic<std::size_t> write_{0};
};

} // namespace couchfi
