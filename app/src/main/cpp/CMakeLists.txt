cmake_minimum_required(VERSION 3.22.1)

project(vuednative)

add_library(vuednative SHARED native_usb_reader.cpp ten_vad_jni.cpp)

target_compile_features(vuednative PRIVATE cxx_std_17)

# `dl` for dlopen/dlsym of the optional libten_vad.so at runtime.
target_link_libraries(vuednative log dl)
