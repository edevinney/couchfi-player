# CouchFi Audio Player

A single-purpose Android TV app for the Nvidia Shield: browses an SMB share,
decodes an audio file, upsamples 44.1 kHz → 176.4 kHz through a 64-tap
Hann-windowed polyphase FIR (matching Pine Player's CSF defaults), packs to
24-bit PCM, and pushes isochronous UAC2 frames via libusb to an external DAC.
AudioFlinger is bypassed entirely.

Target sink: any UAC2-compliant USB DAC. Originally scoped for an
Amanero Combo768 + AD1862 NOS chain; verified end-to-end on an XMOS-based
HU300 HiFi 2.0 (VID 0x20b1, PID 0x0008).

## Architecture

```
 ┌──────────────────────────────────────┐
 │ Kotlin / Android TV (Leanback theme) │
 │  • FilePickerFragment                │
 │  • PlayerFragment (Play/Pause/Stop)  │
 │  • PlaybackController                │
 │  • Decoder (MediaExtractor/Codec)    │
 │  • SmbClient / SmbMediaDataSource    │
 └──────────────┬───────────────────────┘
                │ 44.1 kHz stereo float
                ▼  (JNI: nativeEnginePushPcm)
 ┌──────────────────────────────────────┐
 │ NDK / C++                            │
 │  AudioEngine                         │
 │   ↳ PolyphaseFir  4× Hann sinc       │
 │   ↳ 24-in-32 LE packer               │
 │   ↳ ByteRing (SPSC, ~300 ms)         │
 │  UsbAudio                            │
 │   ↳ libusb_wrap_sys_device(fd)       │
 │   ↳ UAC2 descriptor walk             │
 │   ↳ SET_CUR SAM_FREQ(176400)         │
 │   ↳ ISO OUT, rate-accurate packets   │
 └──────────────┬───────────────────────┘
                │ USB 2.0 HS isoch
                ▼
            USB UAC2 DAC → analog
```

## What's Built (per build-order step)

**Step 0 — Scaffold.** AGP 8.5 + Kotlin 1.9 + NDK 27 + CMake 3.22 project at
[CouchFiPlayer/](.). Leanback-themed activity, USB-device intent filter, JNI
library `libcouchfi.so` for arm64-v8a. SMB credentials + test file path read
from `local.properties` into `BuildConfig`.

**Step 1 — Polyphase FIR + unit tests.**
[polyphase_fir.cpp](app/src/main/cpp/filter/polyphase_fir.cpp) generates a
64-tap Hann-windowed sinc at fc = 0.8 × Nyquist (= 70.56 kHz at fs\_out =
176.4 kHz), decomposes into 4 phases of 16 taps each, and streams stereo
interleaved float through a ring-buffered convolution.
[tests/run.sh](tests/run.sh) builds a standalone clang++ test
([tests/filter/test_polyphase_fir.cpp](tests/filter/test_polyphase_fir.cpp))
that verifies prototype symmetry, Hann endpoints, peak at center, polyphase
decomposition, DC-average preservation, impulse response, and ±1 % tracking
on a 1 kHz input sine. 612/612 checks pass.

**Step 2 — libusb + UAC2 ISO out.** libusb 1.0.27 cross-compiled for
`arm64-v8a` (NDK 27 + autotools, `--disable-udev`). Static `.a` committed to
[app/src/main/cpp/libs/arm64-v8a/](app/src/main/cpp/libs/arm64-v8a/).
[usb_audio.cpp](app/src/main/cpp/usb/usb_audio.cpp) wraps the file descriptor
handed over by Android's `UsbManager`, walks the configuration descriptor to
find a UAC2 AudioStreaming alt setting that advertises 24-bit stereo, parses
the AudioControl interface to locate the Clock Source entity, issues a
class-specific `SET_CUR` on `CS_SAM_FREQ_CONTROL` (176400 Hz), and submits
four isochronous transfers of eight packets each on a dedicated event thread.

**Step 3 — JNI audio engine.**
[audio_engine.cpp](app/src/main/cpp/audio_engine.cpp) +
[ring_buffer.h](app/src/main/cpp/ring_buffer.h) provide
`nativeEngineStart/Stop/PushPcm/PendingFrames/CapacityFrames`. The Kotlin
producer pushes 44.1 kHz float PCM; the engine runs the FIR, packs to the
subslot format reported by the device, and writes into a ~300 ms SPSC byte
ring. The libusb completion callback pulls from the ring; silence is emitted
on underrun.

**Step 4 — SMB + MediaCodec decode.**
[SmbClient](app/src/main/java/com/couchfi/player/smb/SmbClient.kt) uses
`smbj` to connect, authenticate, and pin a `DiskShare`.
[SmbMediaDataSource](app/src/main/java/com/couchfi/player/smb/SmbMediaDataSource.kt)
exposes a random-access `MediaDataSource` backed by an `SMB2` file handle, so
`MediaExtractor` seeks over the network without downloading.
[Decoder](app/src/main/java/com/couchfi/player/audio/Decoder.kt) drives
`MediaCodec` synchronously, converting int16 PCM to stereo float and handing
it to a `PcmSink`.

**Step 5 — Minimal TV UI.**
[FilePickerFragment](app/src/main/java/com/couchfi/player/FilePickerFragment.kt)
opens at `Music Library/`, lists directories + audio files (mp3/m4a/flac/wav/
aac/ogg/aif/aiff/alac), and D-pad-navigates with focus highlighting.
[PlayerFragment](app/src/main/java/com/couchfi/player/PlayerFragment.kt) is a
four-button Play/Pause/Stop/Choose panel.
[PlaybackController](app/src/main/java/com/couchfi/player/playback/PlaybackController.kt)
owns the decode thread, honours a volatile `paused` flag, preserves the
current path across `stop()` so Play-after-Stop restarts the track, and
broadcasts `PLAYING/PAUSED/IDLE` to the fragment.

## Lessons Learned

### NDK / toolchain

- **macOS CLT's `libc++` headers are incomplete.** On the Apple-Silicon dev
  box, `/Library/Developer/CommandLineTools/usr/include/c++/v1/` was missing
  `<cstddef>` (and others). Fix: `-nostdinc++ -isystem
  $(xcrun --show-sdk-path)/usr/include/c++/v1`. Without this the Mac-side
  FIR unit test won't even preprocess.
- **Autotools + paths with spaces = pain.** libusb's `make install` fell
  over on `"CouchFi Audio Player/…"` because `libtool` splits unquoted
  install paths on spaces. Workaround: skip `make install`, copy
  `libusb/.libs/libusb-1.0.a` and `libusb/libusb.h` straight out of the
  build tree.
- **AGP auto-installs a second NDK.** Even though we explicitly pinned NDK
  27, AGP 8.5 silently grabbed NDK 26 during the first sync and used it for
  `arm64-v8a`. Both work; left alone since it's self-contained under
  `~/Library/Android/sdk/ndk/`.

### UAC2 on Android

- **Claim the AudioControl interface BEFORE class control transfers.**
  `libusb_control_transfer()` to the Clock Source returned `LIBUSB_ERROR_IO`
  for both `GET_CUR` and `SET_CUR` until we called
  `libusb_claim_interface(AC)` first. This is not a libusb requirement per
  se — Android's `usbfs` enforces it. The symptom looks like "the DAC
  doesn't support this rate"; it's really a permission problem.
- **Samples in a UAC2 subslot are LEFT-justified (MSB-aligned).** UAC2
  §2.3.1.2 is explicit but easy to miss. For 24-bit-in-32-bit subslots,
  scale to 24-bit signed, then shift left by `(subslot_bytes * 8 -
  bit_resolution)` before packing LE. Scaling to a full 32-bit range and
  letting natural int packing handle it sounds distorted on some DACs and
  noisy on others.
- **ISO packet size must match the exact target rate.** At high-speed USB
  there are 8 microframes/ms. 176400 / 8000 = 22.05 samples per microframe
  — not integer. Sending 23 always is 4.3 % too fast and overruns the
  DAC; sending 22 always is 0.23 % too slow and underruns. Fix: a
  fractional accumulator over microframes emits 22, 22, 22, …, 23 in a
  pattern that averages to 22.05 exactly. Without this we heard rhythmic
  clicks, wrong pitch, or both.
- **XMOS DACs don't hand you the 44.1 k clock automatically.** After
  enumeration the HU300 was at 48 kHz. It only changed when we issued a
  successful `SET_CUR SAM_FREQ(176400)` on the clock source. Sending
  samples at 176.4 kHz packet sizes against a 48 kHz device produces the
  original "clicks + wrong pitch" failure mode.

### Pine Player's FIR character

- **Per-phase DC gains are deliberately unbalanced.** With fc = 0.8 ×
  Nyquist and only 64 Hann-windowed taps, the polyphase DC sums come out
  `{2.41, -0.41, -0.41, 2.41}`, not `{1, 1, 1, 1}`. The filter doesn't try
  to suppress the images near f\_s\_in; it lets them through for sonic
  character — that's what "NOS-flavoured" means. DC is preserved only in
  the L-frame *average*, not per sample. Tests that asserted per-phase
  unit gain were wrong; tests that assert L-averaged preservation pass.

### Streaming engine

- **FIR state must only advance by what you queue.** Early `push_pcm()` ran
  the FIR on all input frames but only wrote the subset the ring accepted.
  The caller re-pushed the rejected tail; the FIR was already past it, so
  the retry pushed stale-history output. Symptom was a sine with audible
  glitches that "looked jagged when graphed." Fix: compute free ring space
  first, run FIR on *only* the frames that will fit, advance state
  exactly that far.

### Android TV quirks

- **`UsbManager.requestPermission` broadcast is unreliable.** The
  `PendingIntent` result receiver didn't fire on the Shield. Workaround:
  register the receiver but don't rely on it — poll
  `usbManager.hasPermission(device)` in `onResume` and start the engine
  when it flips true. A `permissionPending` flag keeps us from re-
  requesting on every resume tick.
- **Activity pause/resume churn on launch.** The Shield fires
  `onActivityResumed` immediately followed by `onActivityStopped` on
  cold launch before settling. Teardown on `onStop` has to be idempotent.
- **Fragment properties don't survive recreation.** Direct `var onPause:
  () -> Unit = {}` on a fragment resets to `{}` when Android rebuilds
  the fragment from saved state (mid-session, triggered by TV lifecycle
  blips). Buttons visually "click" because the key listener fires, but the
  empty lambda is a no-op. Fix: a `Host` interface implemented by the
  activity; the fragment looks up `activity as? Host` every time and
  carries no mutable wiring state.
- **Gboard TV intercepts `DPAD_CENTER` on focused Buttons.** `onClick`
  never fires. Logcat shows
  `GoogleInputMethodService.onStartInput(fieldId=…)` every time focus
  moves between buttons. Workaround: install both an `OnClickListener` and
  an explicit `OnKeyListener` that catches `KEYCODE_DPAD_CENTER` /
  `KEYCODE_ENTER` on `ACTION_UP`.
- **Default focus highlight is invisible on a TV.** Stock `Button` +
  `Theme.Leanback` gives almost no focused-state feedback at 10 feet.
  Need a hand-rolled state-list drawable (bright background + white
  stroke on `state_focused`, lighter on `state_pressed`).

### SMB

- **The share is not the host.** `nas.example` is the NetBIOS / mDNS
  host name, not a share. Enumerating via `smbutil view` on the Mac
  surfaced the real list (`Music`, `Photos`, …). Mounting the share with
  `mount_smbfs` during development is the quickest way to confirm a
  file's actual path relative to the share root.
- **Paths are forward-slash in our code, back-slash on the wire.** `smbj`
  handles the conversion — we pass `"Music Library/Boston/Boston/03 Foreplay
  Long Time.mp3"` and the server receives
  `\\nas.example\Music\Music Library\Boston\Boston\…`.

## Verified Hardware Chain (current test rig)

```
Nvidia Shield TV
    │ USB HS
    ▼
HU300 HiFi 2.0   (XMOS, VID 0x20b1, PID 0x0008)
    │ 176.4 kHz / 24-bit / stereo, UAC2 alt=1, subslot=4, bits=24
    ▼
analog out
```

NAS source: any SMB share reachable from the Shield.

## Known Limitations / Non-Goals (for now)

- **Input rate must be 44.1 kHz.** No resampling path; 48 kHz / 96 kHz
  sources will play at wrong pitch. Decoder logs the rate so we notice.
- **No feedback endpoint handling.** Playback rate is open-loop; over a
  long track this could drift enough to underrun/overrun. Not observed
  yet at track length ~8 min.
- **Hardcoded picker root.** Opens at `"Music Library"` under the
  `BuildConfig`-configured share. No share selection, no favourites.
- **No transport polish.** No seek, no track-complete autoplay, no
  queue, no metadata (title / artist / album art) — just file name.
- **Screensaver / activity pause kills playback mid-track.** When
  `onStop` fires, the engine tears down and the decode thread is
  interrupted. A proper foreground media service + `WAKE_LOCK` will
  fix this; deferred.
- **Encoder delay / padding ignored.** AAC streams carry ~2112
  priming samples and `iTunSMPB` gapless metadata; we read neither,
  so the first ~48 ms of every AAC track is decoder ramp-up noise
  rather than intended silence. Exposed as `KEY_ENCODER_DELAY` /
  `KEY_ENCODER_PADDING` by MediaFormat on API 26+.

## Open audio-quality notes (deferred)

- **FIR peak-gain headroom is −8 dB.** Pine's 64-tap Hann / 0.8·Nyquist
  design has per-phase DC sums of `{1+√2, 1−√2, 1−√2, 1+√2}`, so the
  filter's instantaneous peak gain is ~2.414×. Without headroom, any
  near-0-dBFS transient — e.g. a high-bitrate AAC's vocal consonants —
  hard-clips in the 24-bit packer. [`audio_engine.cpp`](app/src/main/cpp/audio_engine.cpp)
  attenuates by `1/2.5` (≈ −8 dB) before scaling to fix this. A tight
  value would be `1/(1+√2) ≈ 0.414` (−7.66 dB); current value has
  ~0.83 dB of extra margin.
- **Subjective "compression" post-fix needs investigation.** After the
  headroom fix, listening tests reported the output sounds "a bit
  compressed, range compromised." Linear gain shouldn't compress, so
  this is either (a) a loudness-matching illusion from the −8 dB level
  drop (humans hear louder as more dynamic), or (b) the clamp at
  `±2.5` in `pack_samples` is engaging more often than expected.
  Next pass: instrument the engine with per-second peak logging and
  do a loudness-matched A/B. Revisit *after* we also handle encoder
  delay and a broader source sample (ALAC, hi-bitrate MP3, DR-heavy
  classical) so we're not chasing a coding artefact.
- **HU300 ≠ ΔΣ DAC.** The XMOS part in the HU300 HiFi 2.0 is a
  USB→I²S bridge only; downstream is a bespoke AD1862 R-2R NOS stage
  matching the target chain from the research doc. Any "the filter is
  asking the analog stage to do anti-aliasing it wasn't built for"
  hand-waving is wrong — the analog back-end here *is* built for it.

## Backlog (toward the feature-complete TV player)

Scope ordered by chunk. Chunk A has landed; B–E are the next sittings.

### Chunk A — engine ergonomics ✅

- `AudioEngine.flush()` + `ByteRing::clear()`: resets the FIR and drops
  buffered output PCM so seeking / track changes don't bleed stale tail
  into the new position. Consumer is muted (emits silence) across the
  clear to avoid racing the libusb event thread.
- `LinearResampler` (Kotlin) in the decode path: anything non-44.1 kHz
  is linearly resampled to 44.1 before it hits the FIR. Quality is fine
  for 48 kHz internet radio; we'll swap for a polyphase sinc SRC when
  hi-res local sources come in scope.

### Chunk B — tag-indexed library + TV browse UI

- `MediaMetadataRetriever` over `SmbMediaDataSource` for tag reads
  (title / artist / album artist / album / track# / disc# / duration /
  embedded cover). SQLite/Room schema: `artists`, `albums`, `tracks`.
- Blocking first-launch scan with `N / total` progress UI; manual
  "Rescan" action from the picker. No filesystem watcher (SMB can't
  reliably deliver change notifications).
- Replace the raw `FilePickerFragment` with a Strawberry-style browse:
  tabs / rows for **Artists**, **Albums**, **Songs**, **Internet Radio**.
  D-pad navigation throughout.

### Chunk C — queue + now playing transport

- Queue model: ordered list of track IDs, position cursor, shuffle
  toggle (Strawberry-style: reshuffles remaining at toggle, fixed
  thereafter). No repeat mode for now.
- Now-Playing upgrades: track title / artist / album text, progress
  bar with scrub (D-pad L/R while focused), ±10 s step seek on
  `MEDIA_REWIND` / `MEDIA_FAST_FORWARD`, prev / next track buttons,
  default focus on Play/Pause. Seek uses `AudioEngine.flush()` before
  `MediaExtractor.seekTo()` + fresh decode.

### Chunk D — internet radio ✅

- YAML files in `Internet Radio/` on the share, schema
  `name / url / description? / logo?`. Parsed via snakeyaml; the
  `yaml.load<Any>(text)` call is explicitly typed so Kotlin doesn't
  infer `T` as `Void` and throw a `LinkedHashMap → Void` cast.
- `AudioEngine` ring grew from ~300 ms to ~1.5 s of output-rate PCM
  to absorb WAN-side jitter; `PlaybackController.stop()` explicitly
  calls `nativeEngineFlush()` so stop is still audibly instant at
  the deeper buffer.
- `Decoder.decodeUrl` (non-seekable) with `.m3u` / `.m3u8` / `.pls`
  playlist-indirection resolved before hand-off.
- `IcyHttpDataSource` (`audio/IcyHttpDataSource.kt`): custom
  `MediaDataSource` that opens HTTP with `Icy-MetaData: 1`,
  interleaves audio and Icecast metadata blocks, exposes audio
  bytes to `MediaExtractor`, and reports `StreamTitle='…'` back up
  to the UI as "now playing" text. Sends `Connection: close` and
  `useCaches = false` to keep CDN-fronted Icecast edges happy.
- **64 KB backward read-window.** MediaExtractor re-reads a few KB
  near `nextPos` to re-align MP3 frame headers; returning `-1` on
  those short seek-backs made it declare EOS within a few seconds
  (very visible on WXPN — symptom: no welcome intro, cuts out in
  5–10 s). `IcyHttpDataSource` now keeps the last 64 KB of stream
  in a ring and serves backward seeks from it.
- Manifest: `android:usesCleartextTraffic="true"` so plain `http://`
  Icecast URLs work on API 28+.
- 48 kHz streams go through the Chunk A `LinearResampler` to
  44.1 kHz before the FIR.
- Verified stations: SomaFM Groove Salad, KEXP Seattle, WPFW 89.3,
  WXPN 88.5 Philadelphia, WXPN XPoNential HD2.

### Chunk E — album art

- Embedded cover from `MediaMetadataRetriever.getEmbeddedPicture()`
  first, then folder-level `cover.jpg` / `folder.jpg` / `album.jpg`.
- Cache to local storage keyed by album; decode on-demand at
  Now-Playing display size.

### Deferred (explicit non-goals for MVP)

- **Foreground `MediaSessionService` + wake lock** so playback
  survives screen-off, and the remote's play/pause/next/prev keys
  work from outside our activity. Current behaviour: the Shield's
  screensaver kicks in after a few minutes, activity goes to
  `onStop`, `stopIfRunning` tears everything down and audio dies
  mid-track. Especially noticeable on long radio listens. Fix
  shape: move the AudioEngine + PlaybackController + SmbClient into
  a foreground `Service` that outlives the activity, with
  a `MediaSession` for remote-key integration, a
  `PARTIAL_WAKE_LOCK`, and the activity (plus the remote) binding
  to it as a client. Non-trivial refactor.
- **Gapless decoding** — pre-decode the next queue track into the
  ring so there's no silence on track transitions. Essential for
  classical / live albums eventually.
- **Subjective "compression" investigation** of the −8 dB headroom
  fix (see Open audio-quality notes above).
- **Encoder delay / iTunSMPB handling** so AAC priming frames don't
  play as garbage at track start.
- **Remote source protocols** — UPnP/DLNA renderer, AirPlay 2
  receiver, Roon Ready endpoint. These let hi-res streaming apps
  push bit-perfect PCM into our pipeline without us having to be
  the streaming-service client.
- **Resampler quality upgrade** — replace linear interp with a
  polyphase sinc SRC (libsamplerate / soxr) when hi-res local
  sources become part of the story.
- **`SCAN_MAX_DIRS` is a dev knob.** Currently 20 so first-launch
  iteration is fast. Flip to `-1` once (a) the scan is incremental
  (skip files whose path + mtime are already in the DB), or (b) the
  scan runs in the background and the UI is usable during it. ~2400
  tracks over SMB at the current synchronous rate is on the order
  of half an hour, which is unacceptable as a blocking first
  launch.
- **SMB reconnect-on-demand is a bandaid.** `MainActivity.currentShare()`
  observes `isConnected` on every play and reopens the session if
  the NAS timed it out. A better shape is a lightweight heartbeat
  (e.g. `share.list("")` every 20 s) that keeps the session warm,
  so there's no multi-second hiccup when the user hits Play after a
  long browse.
- **Internet Radio button is a stub.** Tapping it logs "coming in
  Chunk D" and does nothing visible.

## Build + run

```bash
# bootstrap once
brew install --cask android-commandlinetools
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
           "ndk;27.0.12077973" "cmake;3.22.1"

# configure local.properties (sdk.dir + SMB creds + test file)
cp local.properties.example local.properties
$EDITOR local.properties

# build + install on an ADB-reachable Shield
./gradlew assembleDebug
adb connect <shield-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Mac-side FIR unit tests
bash tests/run.sh
```
