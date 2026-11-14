cmake_minimum_required(VERSION 3.22.1)

project(dotllm C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- llama.cpp, tuned for phones ---------------------------------------------------------------
# Everything links statically into one libdotllm.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # KleidiAI picks the fastest kernels (dotprod, i8mm, SME) at runtime on each phone.
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
else ()
    # x86_64 is for emulators and Chromebooks, which all have AVX2.
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_AVX  ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA  ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
endif ()

# Only the library, the common helpers (chat templates) and multimodal (screen vision).
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS     OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO           OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp build-llama)

# ---- The JNI bridge ----------------------------------------------------------------------------
add_library(dotllm SHARED dotllm.cpp)

target_include_directories(dotllm PRIVATE
        llama.cpp/include
        llama.cpp/common
        llama.cpp/tools/mtmd
        llama.cpp/ggml/include)

target_link_libraries(dotllm PRIVATE llama llama-common mtmd android log)

# Smaller, faster release binaries.
target_compile_options(dotllm PRIVATE -O3 -fvisibility=hidden)
target_link_options(dotllm PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
