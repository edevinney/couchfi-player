#include "usb_audio.h"

#include <android/log.h>
#include <libusb.h>

#include <algorithm>
#include <cstring>

#define LOG_TAG "couchfi.usb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace couchfi {

namespace {

// UAC2 constants
constexpr uint8_t kAudioClass              = 0x01;
constexpr uint8_t kSubclassAudioControl    = 0x01;
constexpr uint8_t kSubclassAudioStreaming  = 0x02;

// Class-specific descriptor type
constexpr uint8_t kCsInterface = 0x24;

// AC interface descriptor subtypes (UAC2)
constexpr uint8_t kAcSubtypeClockSource = 0x0A;

// AS interface descriptor subtypes (UAC2)
constexpr uint8_t kAsSubtypeGeneral    = 0x01;
constexpr uint8_t kAsSubtypeFormatType = 0x02;

// UAC2 Clock Source sample-frequency control selector
constexpr uint8_t kCsSamFreqControl = 0x01;

// UAC2 class-specific request codes
constexpr uint8_t kUac2RequestCur = 0x01;

} // namespace

UsbAudio::UsbAudio() = default;

UsbAudio::~UsbAudio() {
    stop();
    close();
}

// ── open / close ────────────────────────────────────────────────────────────

bool UsbAudio::open(int fd, int sample_rate_hz) {
    if (handle_) {
        LOGW("open() called twice");
        return false;
    }

    int r = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (r != LIBUSB_SUCCESS && r != LIBUSB_ERROR_NOT_SUPPORTED) {
        LOGE("libusb_set_option NO_DEVICE_DISCOVERY: %s", libusb_error_name(r));
    }

    r = libusb_init(&ctx_);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_init: %s", libusb_error_name(r));
        return false;
    }

    r = libusb_wrap_sys_device(ctx_, (intptr_t) fd, &handle_);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_wrap_sys_device(fd=%d): %s", fd, libusb_error_name(r));
        libusb_exit(ctx_);
        ctx_ = nullptr;
        return false;
    }

    if (!find_streaming_alt(sample_rate_hz)) {
        LOGE("no AudioStreaming alt setting with 24-bit stereo");
        close();
        return false;
    }

    if (!find_clock_source()) {
        LOGW("no Clock Source found — rate-set will be skipped");
    }

    libusb_set_auto_detach_kernel_driver(handle_, 1);

    // Claim the AudioControl interface first so the clock-source control
    // request lands on a claimed target. Needed on Android's usbfs.
    if (ac_if_num_ >= 0) {
        int rc = libusb_claim_interface(handle_, ac_if_num_);
        if (rc != LIBUSB_SUCCESS) {
            LOGW("claim_interface(AC=%d): %s (continuing)",
                 ac_if_num_, libusb_error_name(rc));
        } else {
            LOGI("claimed AC interface %d", ac_if_num_);
        }
    }

    // Set the sample rate BEFORE selecting an alt setting — XMOS firmware
    // tends to snap the clock to the rate currently programmed when the
    // streaming interface transitions from alt 0 to alt > 0.
    if (clock_id_ != 0 && !set_clock_frequency(sample_rate_hz)) {
        LOGW("set_clock_frequency(%d) failed; device may remain at prior rate",
             sample_rate_hz);
    }

    r = libusb_claim_interface(handle_, as_if_num_);
    if (r != LIBUSB_SUCCESS) {
        LOGE("claim_interface(AS=%d): %s", as_if_num_, libusb_error_name(r));
        close();
        return false;
    }

    r = libusb_set_interface_alt_setting(handle_, as_if_num_, as_alt_);
    if (r != LIBUSB_SUCCESS) {
        LOGE("set_interface_alt_setting(if=%d alt=%d): %s",
             as_if_num_, as_alt_, libusb_error_name(r));
        close();
        return false;
    }
    LOGI("claimed AS interface %d, alt %d, ep=0x%02x, max_packet=%u",
         as_if_num_, as_alt_, ep_out_, max_packet_);

    sample_rate_ = sample_rate_hz;
    return true;
}

void UsbAudio::close() {
    stop();
    if (handle_) {
        // Park the AS interface on alt 0 (zero-bandwidth) so the device
        // is quiet before we hand it back to the kernel.
        if (as_if_num_ >= 0) {
            libusb_set_interface_alt_setting(handle_, as_if_num_, 0);
            libusb_release_interface(handle_, as_if_num_);
        }
        if (ac_if_num_ >= 0) {
            libusb_release_interface(handle_, ac_if_num_);
        }
        // Re-attach the kernel USB-audio driver so AudioFlinger (and any
        // other app on this device) can see the DAC again. Without this,
        // libusb_release_interface leaves the interface "unclaimed" from
        // the user-space side but the kernel driver isn't automatically
        // re-attached, which keeps other apps locked out until the USB
        // cable is replugged. Errors are harmless (e.g. no driver to
        // attach), just log at debug.
        if (ac_if_num_ >= 0) {
            const int rc = libusb_attach_kernel_driver(handle_, ac_if_num_);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("attach_kernel_driver(AC=%d): %s",
                     ac_if_num_, libusb_error_name(rc));
            }
        }
        if (as_if_num_ >= 0) {
            const int rc = libusb_attach_kernel_driver(handle_, as_if_num_);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("attach_kernel_driver(AS=%d): %s",
                     as_if_num_, libusb_error_name(rc));
            }
        }
        libusb_close(handle_);
        handle_ = nullptr;
        LOGI("usb close: released AC=%d AS=%d, kernel driver reattach requested",
             ac_if_num_, as_if_num_);
    }
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
    as_if_num_ = -1;
    as_alt_    = 0;
    ep_out_    = 0;
    max_packet_ = 0;
    ac_if_num_ = -1;
    clock_id_  = 0;
}

// ── descriptor walk ─────────────────────────────────────────────────────────

bool UsbAudio::find_streaming_alt(int /*sample_rate_hz*/) {
    libusb_device* dev = libusb_get_device(handle_);
    libusb_config_descriptor* cfg = nullptr;
    int r = libusb_get_active_config_descriptor(dev, &cfg);
    if (r != LIBUSB_SUCCESS) {
        LOGE("get_active_config_descriptor: %s", libusb_error_name(r));
        return false;
    }

    struct Cand {
        int if_num;
        int alt_num;
        int subslot;
        int bit_res;
        int channels;
        uint8_t ep;
        uint16_t mp;
    };
    std::vector<Cand> candidates;

    LOGI("walking config: %d interfaces", cfg->bNumInterfaces);
    for (uint8_t i = 0; i < cfg->bNumInterfaces; ++i) {
        const libusb_interface& intf = cfg->interface[i];
        for (int a = 0; a < intf.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = intf.altsetting[a];

            if (alt.bInterfaceClass == kAudioClass &&
                alt.bInterfaceSubClass == kSubclassAudioControl) {
                if (ac_if_num_ < 0) ac_if_num_ = alt.bInterfaceNumber;
                continue;
            }
            if (alt.bInterfaceClass != kAudioClass ||
                alt.bInterfaceSubClass != kSubclassAudioStreaming) {
                continue;
            }

            int subslot = 0, bit_res = 0, channels = 0;
            const uint8_t* p   = alt.extra;
            const uint8_t* end = p + alt.extra_length;
            while (p + 2 <= end && p[0] >= 2 && p + p[0] <= end) {
                if (p[1] == kCsInterface && p[0] >= 3) {
                    // FORMAT_TYPE_I: bFormatType=1, subslot at 4, bitres at 5
                    if (p[2] == kAsSubtypeFormatType && p[0] >= 6 && p[3] == 1) {
                        subslot = p[4];
                        bit_res = p[5];
                    }
                    // UAC2 AS_GENERAL: bNrChannels at offset 10
                    if (p[2] == kAsSubtypeGeneral && p[0] >= 11) {
                        channels = p[10];
                    }
                }
                p += p[0];
            }

            uint8_t ep = 0;
            uint16_t mp = 0;
            for (uint8_t e = 0; e < alt.bNumEndpoints; ++e) {
                const libusb_endpoint_descriptor& epd = alt.endpoint[e];
                const bool is_iso =
                    (epd.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) ==
                    LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
                const bool is_out =
                    (epd.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK) ==
                    LIBUSB_ENDPOINT_OUT;
                if (is_iso && is_out) {
                    ep = epd.bEndpointAddress;
                    mp = epd.wMaxPacketSize & 0x07FF;
                    break;
                }
            }

            LOGI("  AS if=%d alt=%d  subslot=%d bits=%d ch=%d ep=0x%02x mp=%u extra=%d",
                 alt.bInterfaceNumber, alt.bAlternateSetting,
                 subslot, bit_res, channels, ep, mp, alt.extra_length);

            if (ep != 0 && alt.bAlternateSetting > 0) {
                candidates.push_back({
                    alt.bInterfaceNumber, alt.bAlternateSetting,
                    subslot, bit_res, channels, ep, mp
                });
            }
        }
    }

    libusb_free_config_descriptor(cfg);

    if (candidates.empty()) {
        LOGE("no AS alt settings with an ISO OUT endpoint");
        return false;
    }

    // Preference order:
    //   1) 24-bit stereo, subslot 3 or 4
    //   2) any stereo with a known bit_res
    //   3) any candidate (format unknown but has ISO OUT)
    auto pick = [&](auto pred) -> int {
        for (size_t i = 0; i < candidates.size(); ++i) {
            if (pred(candidates[i])) return int(i);
        }
        return -1;
    };
    int idx = pick([](const Cand& c) {
        return c.bit_res == 24 && (c.subslot == 3 || c.subslot == 4) && c.channels == 2;
    });
    if (idx < 0) idx = pick([](const Cand& c) {
        return c.bit_res >= 16 && c.channels == 2;
    });
    if (idx < 0) idx = pick([](const Cand& c) { return c.subslot > 0; });
    if (idx < 0) idx = 0;

    const Cand& c = candidates[idx];
    as_if_num_    = c.if_num;
    as_alt_       = c.alt_num;
    ep_out_       = c.ep;
    max_packet_   = c.mp;
    subslot_size_ = c.subslot;
    bit_res_      = c.bit_res;
    channels_     = c.channels > 0 ? c.channels : 2;

    LOGI("picked AS if=%d alt=%d  subslot=%d bits=%d ch=%d ep=0x%02x mp=%u",
         as_if_num_, as_alt_, subslot_size_, bit_res_, channels_, ep_out_, max_packet_);
    return true;
}

bool UsbAudio::find_clock_source() {
    if (ac_if_num_ < 0) return false;
    libusb_device* dev = libusb_get_device(handle_);
    libusb_config_descriptor* cfg = nullptr;
    if (libusb_get_active_config_descriptor(dev, &cfg) != LIBUSB_SUCCESS) {
        return false;
    }

    bool found = false;
    for (uint8_t i = 0; i < cfg->bNumInterfaces; ++i) {
        const libusb_interface& intf = cfg->interface[i];
        for (int a = 0; a < intf.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = intf.altsetting[a];
            if (alt.bInterfaceClass != kAudioClass ||
                alt.bInterfaceSubClass != kSubclassAudioControl) {
                continue;
            }
            LOGI("AC if=%d alt=%d extra_length=%d",
                 alt.bInterfaceNumber, alt.bAlternateSetting, alt.extra_length);
            const uint8_t* p   = alt.extra;
            const uint8_t* end = p + alt.extra_length;
            while (p + 3 <= end && p[0] >= 3 && p + p[0] <= end) {
                if (p[1] == kCsInterface) {
                    // UAC2 AC subtypes: 0x01 HEADER, 0x02 INPUT_TERMINAL,
                    //   0x03 OUTPUT_TERMINAL, 0x0A CLOCK_SOURCE,
                    //   0x0B CLOCK_SELECTOR, 0x0C CLOCK_MULTIPLIER,
                    //   0x06 FEATURE_UNIT
                    const int subtype = p[2];
                    const int id = (p[0] >= 4) ? p[3] : -1;
                    LOGI("  AC desc subtype=0x%02x id=0x%02x len=%u",
                         subtype, id, p[0]);
                    if (subtype == kAcSubtypeClockSource && p[0] >= 8 && !found) {
                        clock_id_ = p[3];
                        const uint8_t bmAttr     = p[4];
                        const uint8_t bmControls = p[5];
                        LOGI("    ClockSource id=0x%02x bmAttr=0x%02x bmControls=0x%02x",
                             clock_id_, bmAttr, bmControls);
                        found = true;
                    }
                }
                p += p[0];
            }
        }
    }
    libusb_free_config_descriptor(cfg);
    return found;
}

bool UsbAudio::set_clock_frequency(int rate) {
    // UAC2: SET_CUR on CS_SAM_FREQ_CONTROL of the Clock Source entity.
    // bmRequestType = 0x21 (Class | Interface | Host-to-Device)
    // wValue        = (CS << 8) | CN ; CS=CS_SAM_FREQ_CONTROL, CN=0
    // wIndex        = (EntityID << 8) | InterfaceNumber
    const uint16_t wValue = uint16_t(kCsSamFreqControl) << 8;
    const uint16_t wIndex = (uint16_t(clock_id_) << 8) | uint16_t(ac_if_num_ & 0xFF);

    // First, GET_CUR so we can see what rate the device is actually running at.
    uint8_t cur[4] = {0};
    int g = libusb_control_transfer(
        handle_, /*bmReqType*/ 0xA1, kUac2RequestCur,
        wValue, wIndex, cur, sizeof(cur), /*timeout*/ 1000);
    if (g >= 4) {
        uint32_t hz = uint32_t(cur[0]) | (uint32_t(cur[1]) << 8) |
                      (uint32_t(cur[2]) << 16) | (uint32_t(cur[3]) << 24);
        LOGI("GET_CUR SAM_FREQ → %u Hz (device current rate)", hz);
    } else {
        LOGW("GET_CUR SAM_FREQ failed: %s", libusb_error_name(g));
    }

    // Now try SET_CUR.
    uint8_t payload[4] = {
        (uint8_t)(rate         & 0xFF),
        (uint8_t)((rate >>  8) & 0xFF),
        (uint8_t)((rate >> 16) & 0xFF),
        (uint8_t)((rate >> 24) & 0xFF),
    };
    const int r = libusb_control_transfer(
        handle_, /*bmReqType*/ 0x21, kUac2RequestCur,
        wValue, wIndex, payload, sizeof(payload), /*timeout*/ 1000);
    if (r < 0) {
        LOGE("SET_CUR SAM_FREQ(%d) on clock 0x%02x/if%d: %s",
             rate, clock_id_, ac_if_num_, libusb_error_name(r));
        return false;
    }
    LOGI("SET_CUR SAM_FREQ(%d) on clock 0x%02x/if%d → %d bytes",
         rate, clock_id_, ac_if_num_, r);
    return true;
}

// ── streaming ───────────────────────────────────────────────────────────────

int UsbAudio::compute_packet_lengths(int* out_bytes, int count) {
    const int frame_bytes = subslot_size_ * channels_;
    int total = 0;
    for (int i = 0; i < count; ++i) {
        sample_acc_ += sample_rate_;
        int n_frames = sample_acc_ / 8000;
        sample_acc_ -= n_frames * 8000;
        int pkt = n_frames * frame_bytes;
        if (pkt > max_packet_) pkt = max_packet_;
        out_bytes[i] = pkt;
        total += pkt;
    }
    return total;
}

bool UsbAudio::start(PcmFiller filler) {
    if (!handle_ || running_) return false;
    filler_  = std::move(filler);
    running_ = true;
    sample_acc_ = 0;

    // Size each buffer for worst-case packets (ceil(rate/8000) frames each).
    const int frame_bytes = subslot_size_ * channels_;
    const int max_frames_per_pkt = (sample_rate_ + 7999) / 8000;
    const int max_bytes_per_pkt  = std::min<int>(max_frames_per_pkt * frame_bytes,
                                                 max_packet_);
    const int buf_size = max_bytes_per_pkt * kIsoPacketsPerXfer;

    xfers_.assign(kNumXfers, nullptr);
    buffers_.assign(kNumXfers, std::vector<uint8_t>(buf_size));

    for (int i = 0; i < kNumXfers; ++i) {
        xfers_[i] = libusb_alloc_transfer(kIsoPacketsPerXfer);
        if (!xfers_[i]) { LOGE("alloc_transfer[%d] failed", i); return false; }

        int pkt_lens[kIsoPacketsPerXfer];
        const int total = compute_packet_lengths(pkt_lens, kIsoPacketsPerXfer);

        filler_(buffers_[i].data(), total);

        libusb_fill_iso_transfer(
            xfers_[i], handle_, ep_out_,
            buffers_[i].data(), total,
            kIsoPacketsPerXfer,
            &UsbAudio::on_transfer_done_trampoline, this,
            /* timeout */ 0);

        for (int p = 0; p < kIsoPacketsPerXfer; ++p) {
            xfers_[i]->iso_packet_desc[p].length = pkt_lens[p];
        }

        const int r = libusb_submit_transfer(xfers_[i]);
        if (r != LIBUSB_SUCCESS) {
            LOGE("submit_transfer[%d]: %s", i, libusb_error_name(r));
            return false;
        }
        in_flight_++;
    }

    event_thread_ = std::thread([this]() {
        while (running_ || in_flight_ > 0) {
            timeval tv { 0, 100 * 1000 };
            libusb_handle_events_timeout_completed(ctx_, &tv, nullptr);
        }
    });
    LOGI("streaming started: %d xfers × %d iso-packets, variable size "
         "(mean %d B/pkt, max %d B/pkt, max_packet=%u)",
         kNumXfers, kIsoPacketsPerXfer,
         (sample_rate_ * frame_bytes) / 8000,
         max_bytes_per_pkt, max_packet_);
    return true;
}

void UsbAudio::stop() {
    if (!running_) return;
    running_ = false;
    for (libusb_transfer* x : xfers_) {
        if (x) libusb_cancel_transfer(x);
    }
    if (event_thread_.joinable()) event_thread_.join();
    for (libusb_transfer* x : xfers_) {
        if (x) libusb_free_transfer(x);
    }
    xfers_.clear();
    buffers_.clear();
    filler_ = nullptr;
    LOGI("streaming stopped");
}

// ── transfer callback ───────────────────────────────────────────────────────

void UsbAudio::on_transfer_done_trampoline(libusb_transfer* xfer) {
    static_cast<UsbAudio*>(xfer->user_data)->on_transfer_done(xfer);
}

void UsbAudio::on_transfer_done(libusb_transfer* xfer) {
    if (!running_ || xfer->status == LIBUSB_TRANSFER_CANCELLED) {
        in_flight_--;
        return;
    }
    if (xfer->status != LIBUSB_TRANSFER_COMPLETED) {
        LOGW("iso xfer status=%d", xfer->status);
    }

    int pkt_lens[kIsoPacketsPerXfer];
    const int total = compute_packet_lengths(pkt_lens, kIsoPacketsPerXfer);

    if (filler_) {
        filler_(xfer->buffer, total);
    }
    for (int p = 0; p < kIsoPacketsPerXfer; ++p) {
        xfer->iso_packet_desc[p].length = pkt_lens[p];
    }
    xfer->length = total;
    const int r = libusb_submit_transfer(xfer);
    if (r != LIBUSB_SUCCESS) {
        LOGE("resubmit: %s", libusb_error_name(r));
        in_flight_--;
    }
}

} // namespace couchfi
