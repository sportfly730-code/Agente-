cmake_minimum_required(VERSION 3.22.1)
project("codex_llama" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# llama.cpp v1.0.0 — somente CPU, estático, sem testes/exemplos/server/backends.
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS            OFF CACHE BOOL "" FORCE)
set(LLAMA_STATIC                 ON  CACHE BOOL "" FORCE)
set(GGML_STATIC                  ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS            OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS            OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS             OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES          OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS           OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS            OFF CACHE BOOL "" FORCE)
set(LLAMA_CCACHE                 OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE                  OFF CACHE BOOL "" FORCE)
set(GGML_CUDA                    OFF CACHE BOOL "" FORCE)
set(GGML_METAL                   OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN                  OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL                  OFF CACHE BOOL "" FORCE)
set(GGML_SYCL                    OFF CACHE BOOL "" FORCE)
set(GGML_HIP                     OFF CACHE BOOL "" FORCE)
set(GGML_RPC                     OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE               OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS        OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE                  OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP                  OFF CACHE BOOL "" FORCE)
set(GGML_F16C                    OFF CACHE BOOL "" FORCE)
set(GGML_FMA                     OFF CACHE BOOL "" FORCE)
set(GGML_AVX                     OFF CACHE BOOL "" FORCE)
set(GGML_AVX2                    OFF CACHE BOOL "" FORCE)
set(GGML_AVX512                  OFF CACHE BOOL "" FORCE)
set(GGML_AMX                     OFF CACHE BOOL "" FORCE)
# Aceleracao ARM: DOTPROD (dot product int8) e essencial para Q4_K_M/Q2_K
set(GGML_CPU_ARM_ARCH            "armv8.2-a+dotprod" CACHE STRING "" FORCE)
set(GGML_CPU_KLEIDIAI            ON  CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# ---------------------------------------------------------------------------
# Ponte JNI usada por LocalLlamaProvider.kt
# ---------------------------------------------------------------------------
find_library(log-lib log)

add_library(codex_llama SHARED llama_bridge.cpp)
target_include_directories(codex_llama PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)
target_link_libraries(codex_llama llama ${log-lib})
