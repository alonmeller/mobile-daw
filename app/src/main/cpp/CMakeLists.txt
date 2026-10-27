cmake_minimum_required(VERSION 3.22.1)
project(mobiledaw CXX)

find_package(oboe REQUIRED CONFIG)

add_library(mobiledaw SHARED
        AudioEngine.cpp
        Effects.cpp
        Instruments.cpp
        jni_bridge.cpp)

target_compile_options(mobiledaw PRIVATE -Wall -Wextra -O3)
target_link_libraries(mobiledaw oboe::oboe log)
