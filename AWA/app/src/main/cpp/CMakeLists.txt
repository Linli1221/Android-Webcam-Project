cmake_minimum_required(VERSION 3.22.1)

project(awa_chromakey LANGUAGES CXX)

# Vulkan compute keyer. The SPIR-V blobs are built offline (AWA/tools/build_shaders.mjs) and
# shipped as assets, so this module does not need a shader compiler at build time.
add_library(awa_chromakey SHARED chroma_vulkan.cpp)

target_compile_features(awa_chromakey PRIVATE cxx_std_17)
# Vulkan's create-info structs are filled with designated initialisers that leave pNext out (it is
# optional and null for every one of them here), which -Wextra is noisy about.
target_compile_options(awa_chromakey PRIVATE -Wall -Wextra -Wno-unused-parameter
                                             -Wno-missing-field-initializers)

find_library(log-lib log)

target_link_libraries(awa_chromakey PRIVATE vulkan ${log-lib})
