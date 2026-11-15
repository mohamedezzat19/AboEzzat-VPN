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
