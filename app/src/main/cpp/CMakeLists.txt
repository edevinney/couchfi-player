cmake_minimum_required(VERSION 3.22.1)
project(couchfi LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(couchfi SHARED
    audio_engine.cpp
    filter/polyphase_fir.cpp
    usb/usb_audio.cpp
)

target_include_directories(couchfi PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/filter
    ${CMAKE_CURRENT_SOURCE_DIR}/usb
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# libusb static — cross-compiled per research/dev-environment-setup.md §4.
add_library(libusb STATIC IMPORTED)
set_target_properties(libusb PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libusb-1.0.a)

find_library(log-lib log)
target_link_libraries(couchfi libusb ${log-lib})
