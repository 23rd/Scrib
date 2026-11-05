
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_compile_options(-O3)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
endif ()

add_library(scrib_transcribe SHARED
    "${CMAKE_CURRENT_LIST_DIR}/transcribe_jni.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/silero_vad.cpp")

target_compile_features(scrib_transcribe PRIVATE cxx_std_17)

target_include_directories(scrib_transcribe PRIVATE
    "${CMAKE_SOURCE_DIR}/include"
    "${CMAKE_SOURCE_DIR}/src"
    "${CMAKE_SOURCE_DIR}/ggml/include")

set_target_properties(scrib_transcribe PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    CXX_STANDARD 17)

find_library(log-lib log)

target_link_libraries(scrib_transcribe PRIVATE transcribe ggml ggml-base ${log-lib} android)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(scrib_transcribe PRIVATE
        -ffunction-sections -fdata-sections)
    target_link_options(scrib_transcribe PRIVATE
        "-Wl,--gc-sections"
        "-Wl,--exclude-libs,ALL")
endif ()

# Ogg/Opus decode outside MediaCodec: on slow SoCs the per-packet async codec
# path costs more than the decode itself (see TODO.md, issue 41 evidence).
# A Scrib Max feature by owner decision (2026-11-05): the lean F-Droid build
# does not compile or ship it and keeps decoding Opus through MediaCodec.
# ogg and opus build from their own CMakeLists; opusfile's four sources join
# the JNI target directly. Upstream trees are pruned to what the build needs;
# each keeps its license file.
if (SCRIB_MAX)
    set(OPUS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../opus")
    set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
    # Decoder only: no opus tools or tests, their sources are pruned anyway.
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory("${OPUS_ROOT}/libogg-1.3.5" ogg-build EXCLUDE_FROM_ALL)
    add_subdirectory("${OPUS_ROOT}/opus-1.5.2" opus-build EXCLUDE_FROM_ALL)
    set_target_properties(ogg opus PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        C_STANDARD 99)

    add_library(scrib_opus SHARED
        "${OPUS_ROOT}/opus_decoder_jni.cpp"
        "${OPUS_ROOT}/opusfile-0.12/src/info.c"
        "${OPUS_ROOT}/opusfile-0.12/src/internal.c"
        "${OPUS_ROOT}/opusfile-0.12/src/opusfile.c"
        "${OPUS_ROOT}/opusfile-0.12/src/stream.c")

    target_include_directories(scrib_opus PRIVATE
        "${OPUS_ROOT}/opusfile-0.12/include"
        "${OPUS_ROOT}/opus-1.5.2/include"
        "${OPUS_ROOT}/libogg-1.3.5/include")

    target_compile_definitions(scrib_opus PRIVATE OP_DISABLE_HTTP)

    set_target_properties(scrib_opus PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
        C_STANDARD 99)

    target_link_libraries(scrib_opus PRIVATE opus ogg ${log-lib})

    if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        target_compile_options(scrib_opus PRIVATE
            -ffunction-sections -fdata-sections)
        target_link_options(scrib_opus PRIVATE
            "-Wl,--gc-sections"
            "-Wl,--exclude-libs,ALL")
    endif ()
endif ()
