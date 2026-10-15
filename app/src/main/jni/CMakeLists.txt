cmake_minimum_required(VERSION 3.22)

# transcribe.cpp first (sets up ggml), then llama.cpp on top
project(scrib C CXX)

set(SCRIB_TRANSCRIBE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../transcribe.cpp")
get_filename_component(SCRIB_TRANSCRIBE_DIR "${SCRIB_TRANSCRIBE_DIR}" ABSOLUTE)

add_subdirectory("${SCRIB_TRANSCRIBE_DIR}" "${CMAKE_BINARY_DIR}/transcribe.cpp")

# transcribe.cpp assumes its the top level project, so add its headers back
target_include_directories(transcribe PUBLIC "${SCRIB_TRANSCRIBE_DIR}/include")

include("${CMAKE_CURRENT_SOURCE_DIR}/transcribe/llama.cmake")
