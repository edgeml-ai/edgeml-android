cmake_minimum_required(VERSION 3.22.1)

project(octomil_runtime_jni LANGUAGES CXX)

add_library(octomil_runtime_jni SHARED native_runtime_jni.cpp)

target_compile_features(octomil_runtime_jni PRIVATE cxx_std_17)
target_compile_options(octomil_runtime_jni PRIVATE -Wall -Wextra -Wpedantic)

target_link_libraries(octomil_runtime_jni PRIVATE log dl)
