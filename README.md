# CouchFi Audio Player

A single-purpose Android TV music player. It reads music + radio configs from
an SMB share, decodes via Android's `MediaCodec`, and writes audio to a
USB-attached UAC2 DAC through `libusb`, with the option of 4× polyphase
oversampling before the pack stage. Built originally for an Nvidia Shield TV
driving an XMOS-bridged AD1862 NOS ladder DAC.

AudioFlinger is optionally bypassed: the app either routes PCM through
Android's normal audio path (for A/B comparison) or writes 24-bit samples
directly over USB isochronous OUT to the DAC at the source rate or 4× the
source rate.

## Features

- Leanback / D-pad navigation (MUSIC / RADIO tabs, artist / album / song
  grids, alphabet jumper).
- Three output modes, switchable from the player page:
  - **Native** — Android `AudioTrack`, default HAL.
  - **Direct NOS** — `libusb` direct, DAC runs at source rate (44.1 / 48 /
    88.2 / 96 / 176.4 / 192 kHz).
  - **Direct 4×** — `libusb` direct, source × 4 via a 64-tap Hann-windowed
    polyphase FIR. Source must be ≤ 48 kHz.
- Internet radio via Icecast / Icy streams; per-station gain trim.
- Foreground `PlaybackService` so playback survives screensaver / Home.
- Background library indexing; incremental Re-index adds new files without
  blocking the UI.
- YAML advanced-settings editor (FIR headroom + SMB creds / path).

## Repository layout

```
BUILD_SUMMARY.md       high-level architecture + lessons learned
app/
  build.gradle.kts     AGP / Kotlin / NDK config
  src/main/
    AndroidManifest.xml
    cpp/               native audio engine
      audio_engine.*   FIR → pack → ring → USB
      filter/          polyphase_fir.*
      usb/             usb_audio.* (libusb wrapper)
      libs/arm64-v8a/  libusb-1.0.a (prebuilt, see below)
      include/         libusb.h
    java/com/couchfi/player/  Kotlin app code
    res/               layouts, drawables, values
tests/                 mac-side clang++ unit test for the FIR
local.properties.example  scaffold for your build-time SMB defaults
```

## Building from a fresh checkout

### Prerequisites

- **Android SDK** (via Android Studio or `sdkmanager`). Last known-good:
  AGP 8.5, compileSdk 34, Kotlin 1.9.
- **Android NDK** 27 or newer. Install via `sdkmanager
  "ndk;27.0.12077973"` or from Android Studio's SDK Manager.
- **CMake** 3.22+ (usually bundled with the NDK).
- **JDK 17** (Zulu or Temurin). Newer JDKs (25+) break AGP's Kotlin
  compile task.

### 1. Clone

```sh
git clone https://github.com/edevinney/couchfi-player.git
cd couchfi-player
```

### 2. Create `local.properties`

This file is gitignored and holds your Android SDK / NDK paths plus
**default** SMB credentials that get baked into `BuildConfig`. The app
reads these the first time it launches and lets you override them at
runtime via the Advanced YAML editor, so the values here are just
initial seeds.

Copy the template:

```sh
cp local.properties.example local.properties
```

Fill in:

```properties
sdk.dir=/Users/YOU/Library/Android/sdk
ndk.dir=/Users/YOU/Library/Android/sdk/ndk/27.0.12077973

smb.host=192.168.1.x          # LAN hostname or IP of your SMB server
smb.user=your_username
smb.password=your_password
smb.share=music               # share name (e.g. "Music", "storage")
smb.testfile=Test/sample.m4a  # a known file, used by legacy dev tools
```

The `smb.*` values are **not** required for the app to build — they're
just pre-fill for the Advanced dialog on first run.

### 3. Set up the SMB share

Arrange your share to look like:

```
\\<smb.host>\<smb.share>\
    Music Library/            ← music_path (configurable in Advanced)
        Artist A/
            Album One/
                01 track.m4a
                cover.jpg     ← optional folder-level album art
            Album Two/
                ...
    Internet Radio/           ← hardcoded name; one YAML per station
        kexp.yaml
        wxpn.yaml
        ...
```

Each station file is a tiny YAML:

```yaml
name: KEXP
url: https://kexp.streamguys1.com/kexp320.aac
description: Seattle indie
# logo: optional, not currently rendered
```

### 4. Build

From the project root:

```sh
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

If you hit `25.0.2` or similar JVM errors from Gradle, your default JDK
is too new:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
./gradlew :app:assembleDebug
```

### 5. Install on the Shield (or any Android TV)

```sh
adb connect <your-shield-ip>:5555
adb -s <your-shield-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch from the TV's Leanback launcher. First run will ask for USB
permission when you attach the DAC.

## About the prebuilt libusb

`app/src/main/cpp/libs/arm64-v8a/libusb-1.0.a` and the matching header
are statically linked into `libcouchfi.so`. The archive was produced
from libusb 1.0.27 cross-compiled with the NDK 27 toolchain against
`android-26`. If you want to rebuild it (e.g. new NDK), see the build
recipe in `BUILD_SUMMARY.md` under "Lessons Learned → NDK / toolchain".
License-wise libusb is LGPL-2.1+; see `NOTICE`.

## Runtime configuration

Once installed and running:

- **⋯ button → Advanced…** opens a YAML editor showing
  `filesDir/config.yaml`:
  ```yaml
  fir_headroom: 0.5
  smb:
    host: nas.example
    share: music
    user: someone
    password: something
    music_path: Music Library
  ```
  Edit, tap Done, and the app reparses, persists, and reapplies
  (reconnects SMB + reopens the sink as needed).
- **Output mode** on the player page opens a picker. Direct NOS and
  Direct 4× both go through the custom USB engine; Native goes through
  `AudioTrack`.
- **Per-station gain** is adjustable from the player page when a radio
  stream is playing — the label to the left of the output-mode picker.

## Testing the FIR on your Mac

```sh
tests/run.sh
```

Requires Xcode Command Line Tools; builds and runs a clang++ unit test
for the polyphase FIR independent of Android.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
