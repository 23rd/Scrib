# llama.cpp reuses the ggml target transcribe.cpp already set up
set(SCRIB_LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../llama.cpp")
get_filename_component(SCRIB_LLAMA_DIR "${SCRIB_LLAMA_DIR}" ABSOLUTE)

if (NOT EXISTS "${SCRIB_LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp is missing at ${SCRIB_LLAMA_DIR} — clone with --recurse-submodules")
endif ()

set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

add_subdirectory("${SCRIB_LLAMA_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp")

target_sources(scrib_transcribe PRIVATE "${CMAKE_CURRENT_LIST_DIR}/translate_jni.cpp")
target_include_directories(scrib_transcribe PRIVATE "${SCRIB_LLAMA_DIR}/include")
target_link_libraries(scrib_transcribe PRIVATE llama)
