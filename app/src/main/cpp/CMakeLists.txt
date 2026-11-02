cmake_minimum_required(VERSION 3.22.1)
project(whisperjni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

# Anche nelle build "debug" serve codice ottimizzato, altrimenti Whisper è lentissimo
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# whisper.cpp viene scaricato dal workflow (vedi .github/workflows/build.yml)
add_subdirectory(whisper.cpp ${CMAKE_CURRENT_BINARY_DIR}/whisper)

add_library(whisperjni SHARED jni.cpp)
target_include_directories(whisperjni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/ggml/include)
target_link_libraries(whisperjni whisper log)
