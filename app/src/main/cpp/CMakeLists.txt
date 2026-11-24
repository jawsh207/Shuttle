# Native part of Shuttle: llama.cpp (a git submodule, pinned to a release) plus the small JNI
# bridge in shuttle_llm.cpp. Everything is linked into one library, libshuttle_llm.so.
#
# llama.cpp is built as static libraries for the CPU only: no GPU vendor drivers, no OpenMP
# runtime to ship, and nothing downloaded at build time (KleidiAI, OpenSSL and the HTTP
# client are all off).
cmake_minimum_required(VERSION 3.22.1)
project(shuttle_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Every Pixel GrapheneOS supports has dot-product and half-precision instructions,
    # which roughly double the speed of 4-bit models over the arm64 baseline.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(llama.cpp build-llama)

add_library(shuttle_llm SHARED shuttle_llm.cpp)
target_link_libraries(shuttle_llm PRIVATE llama android log)
