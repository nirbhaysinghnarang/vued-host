cmake_minimum_required(VERSION 3.22.1)

project(vuednative)

# Vendored WavPack reference library (portable C; SIMD .S/.asm intentionally not
# vendored, so the default portable-C paths are compiled). See wavpack/COPYING.
file(GLOB WAVPACK_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/wavpack/src/*.c)

add_library(vuednative SHARED
    native_usb_reader.cpp
    ten_vad_jni.cpp
    wavpack_jni.cpp
    ${WAVPACK_SOURCES})

target_include_directories(vuednative PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/wavpack/include
    ${CMAKE_CURRENT_SOURCE_DIR}/wavpack/src)

target_compile_features(vuednative PRIVATE cxx_std_17)

# `dl` for dlopen/dlsym of the optional libten_vad.so at runtime.
target_link_libraries(vuednative log dl)
