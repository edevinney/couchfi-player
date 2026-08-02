cmake_minimum_required(VERSION 3.22.1)
project(couchfi LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(couchfi SHARED
    audio_engine.cpp
    filter/polyphase_fir.cpp
    room_correction/wav_ir_loader.cpp
    room_correction/ir_resampler.cpp
    room_correction/partitioned_fir.cpp
    third_party/pffft/pffft.c
    usb/usb_audio.cpp
)

# PFFFT is C, not C++. It also has one intentional integer-signedness
# comparison the ISO C compiler warns about; silence just that warning
# for the vendored file rather than editing upstream source.
set_source_files_properties(third_party/pffft/pffft.c PROPERTIES
    COMPILE_FLAGS "-Wno-sign-compare -Wno-unused-function")

target_include_directories(couchfi PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/filter
    ${CMAKE_CURRENT_SOURCE_DIR}/room_correction
    ${CMAKE_CURRENT_SOURCE_DIR}/usb
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/r8brain-free-src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/pffft
)

# libusb static — cross-compiled per research/dev-environment-setup.md §4.
add_library(libusb STATIC IMPORTED)
set_target_properties(libusb PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libusb-1.0.a)

find_library(log-lib log)
target_link_libraries(couchfi libusb ${log-lib})
