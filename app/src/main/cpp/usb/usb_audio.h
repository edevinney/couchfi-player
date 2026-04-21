#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <thread>
#include <vector>

struct libusb_context;
struct libusb_device_handle;
struct libusb_transfer;

namespace couchfi {

// UAC2 isochronous OUT streaming to an Amanero Combo768.
//
// Life cycle:
//   UsbAudio a;
//   a.open(fd, 176400);           // fd from Java UsbDeviceConnection
//   a.start([](uint8_t* buf, int nbytes){ /* fill with PCM */ });
//   ...
//   a.stop();
//   a.close();
//
// Format is hardcoded to 24-bit PCM, stereo, interleaved L,R little-endian.
class UsbAudio {
public:
    using PcmFiller = std::function<void(uint8_t* buf, int nbytes)>;

    UsbAudio();
    ~UsbAudio();

    UsbAudio(const UsbAudio&) = delete;
    UsbAudio& operator=(const UsbAudio&) = delete;

    // Opens the device, finds the right UAC2 alt setting, and configures the
    // clock source for `sample_rate_hz`. Does not start streaming.
    bool open(int fd, int sample_rate_hz);
    void close();

    // Starts submitting ISO transfers on a background event thread.
    // `filler` is called from that thread whenever a buffer needs samples.
    bool start(PcmFiller filler);
    void stop();

    // Diagnostics (populated after open()).
    int alt_setting()      const { return as_alt_; }
    int max_packet_bytes() const { return max_packet_; }
    int clock_source_id()  const { return clock_id_; }
    int subslot_size()     const { return subslot_size_; }  // bytes per sample
    int bit_resolution()   const { return bit_res_; }
    int channels()         const { return channels_; }

private:
    static void on_transfer_done_trampoline(libusb_transfer* xfer);
    void on_transfer_done(libusb_transfer* xfer);

    bool find_streaming_alt(int sample_rate_hz);
    bool find_clock_source();
    bool set_clock_frequency(int rate);

    libusb_context*        ctx_     = nullptr;
    libusb_device_handle*  handle_  = nullptr;

    int      as_if_num_     = -1;
    int      as_alt_        = 0;
    uint8_t  ep_out_        = 0;
    uint16_t max_packet_    = 0;
    int      ac_if_num_     = -1;   // AudioControl interface, for descriptors
    uint8_t  clock_id_      = 0;    // UAC2 Clock Source entity ID
    int      sample_rate_   = 0;
    int      subslot_size_  = 0;    // bytes per sample in the chosen alt
    int      bit_res_       = 0;
    int      channels_      = 0;

    PcmFiller filler_;
    std::vector<libusb_transfer*>        xfers_;
    std::vector<std::vector<uint8_t>>    buffers_;

    static constexpr int kNumXfers            = 4;
    static constexpr int kIsoPacketsPerXfer   = 8;

    // Fractional-rate accumulator: per microframe we add sample_rate_ and
    // divide by 8000 to get that packet's sample count, carrying the
    // remainder so the long-term rate is exactly sample_rate_ / 8000.
    int sample_acc_ = 0;
    // Populates per-packet lengths (bytes) for one ISO transfer.
    // Returns total bytes = sum of the per-packet lengths.
    int compute_packet_lengths(int* out_bytes, int count);

    std::atomic<bool>  running_{false};
    std::atomic<int>   in_flight_{0};
    std::thread        event_thread_;
};

} // namespace couchfi
