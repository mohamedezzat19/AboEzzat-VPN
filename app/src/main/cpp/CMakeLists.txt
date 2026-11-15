cmake_minimum_required(VERSION 3.22.1)
project(abz_protector LANGUAGES CXX)

add_library(abz_protector SHARED
    native_socket_protector.cpp
)

target_compile_features(abz_protector PRIVATE cxx_std_17)
target_compile_options(abz_protector PRIVATE
    -Wall
    -Wextra
    -Werror
)

# The Rust static library is built for the selected Android ABI by CI before
# Gradle invokes CMake. Refuse to produce a JNI-only APK if it is missing.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(TUN2PROXY_RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(TUN2PROXY_RUST_TARGET "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(TUN2PROXY_RUST_TARGET "x86_64-linux-android")
else()
    message(FATAL_ERROR "Unsupported Android ABI for tun2proxy: ${ANDROID_ABI}")
endif()

set(TUN2PROXY_STATIC_LIB
    "${CMAKE_CURRENT_LIST_DIR}/../../../../native/tun2proxy/target/${TUN2PROXY_RUST_TARGET}/release/libtun2proxy.a"
)
if(NOT EXISTS "${TUN2PROXY_STATIC_LIB}")
    message(FATAL_ERROR "Missing patched tun2proxy static library: ${TUN2PROXY_STATIC_LIB}")
endif()

add_library(tun2proxy_rust STATIC IMPORTED GLOBAL)
set_target_properties(tun2proxy_rust PROPERTIES
    IMPORTED_LOCATION "${TUN2PROXY_STATIC_LIB}"
)

target_link_libraries(abz_protector PRIVATE
    tun2proxy_rust
    log
    android
    dl
    m
)
