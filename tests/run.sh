#!/usr/bin/env bash
# Build + run Mac-side unit tests for the NDK filter code.
# No Android toolchain required — just clang++.

set -euo pipefail

cd "$(dirname "$0")/.."   # project root (CouchFiPlayer/)

OUT="build/tests"
mkdir -p "$OUT"

SDKROOT="$(xcrun --show-sdk-path)"

# The CLT's own /usr/include/c++/v1 is incomplete (e.g. missing <cstddef>).
# Point clang at the SDK's C++ headers, which have the full libc++ tree.
CXX_INC="$SDKROOT/usr/include/c++/v1"

echo "==> building test_polyphase_fir"
xcrun clang++ -std=c++17 -Wall -Wextra -O2 \
    -isysroot "$SDKROOT" \
    -nostdinc++ -isystem "$CXX_INC" \
    tests/filter/test_polyphase_fir.cpp \
    app/src/main/cpp/filter/polyphase_fir.cpp \
    -o "$OUT/test_polyphase_fir"

echo "==> running test_polyphase_fir"
"$OUT/test_polyphase_fir"

echo "==> building test_wav_ir_loader"
xcrun clang++ -std=c++17 -Wall -Wextra -O2 \
    -isysroot "$SDKROOT" \
    -nostdinc++ -isystem "$CXX_INC" \
    tests/room_correction/test_wav_ir_loader.cpp \
    app/src/main/cpp/room_correction/wav_ir_loader.cpp \
    -o "$OUT/test_wav_ir_loader"

echo "==> running test_wav_ir_loader"
"$OUT/test_wav_ir_loader"

echo "==> building test_ir_resampler"
xcrun clang++ -std=c++17 -Wall -Wextra -O2 \
    -isysroot "$SDKROOT" \
    -nostdinc++ -isystem "$CXX_INC" \
    -I app/src/main/cpp/third_party/r8brain-free-src \
    tests/room_correction/test_ir_resampler.cpp \
    app/src/main/cpp/room_correction/ir_resampler.cpp \
    -o "$OUT/test_ir_resampler"

echo "==> running test_ir_resampler"
"$OUT/test_ir_resampler"

# PFFFT is C, not C++, and the vendored source has a couple of
# intentional warnings under -Wall -Wextra we silence for the .c file
# only (not the wider build). Compile as an object first with clang
# (not clang++), then link into the C++ test binary.
echo "==> building test_pffft_smoke"
xcrun clang -std=c11 -Wall -Wextra -O2 \
    -isysroot "$SDKROOT" \
    -Wno-sign-compare -Wno-unused-function \
    -I app/src/main/cpp/third_party/pffft \
    -c app/src/main/cpp/third_party/pffft/pffft.c \
    -o "$OUT/pffft.o"
xcrun clang++ -std=c++17 -Wall -Wextra -O2 \
    -isysroot "$SDKROOT" \
    -nostdinc++ -isystem "$CXX_INC" \
    -I app/src/main/cpp/third_party/pffft \
    tests/room_correction/test_pffft_smoke.cpp \
    "$OUT/pffft.o" \
    -o "$OUT/test_pffft_smoke"

echo "==> running test_pffft_smoke"
"$OUT/test_pffft_smoke"

echo "==> building test_partitioned_fir"
xcrun clang++ -std=c++17 -Wall -Wextra -O2 \
    -isysroot "$SDKROOT" \
    -nostdinc++ -isystem "$CXX_INC" \
    -I app/src/main/cpp/room_correction \
    -I app/src/main/cpp/third_party/pffft \
    tests/room_correction/test_partitioned_fir.cpp \
    app/src/main/cpp/room_correction/partitioned_fir.cpp \
    "$OUT/pffft.o" \
    -o "$OUT/test_partitioned_fir"

echo "==> running test_partitioned_fir"
"$OUT/test_partitioned_fir"
