
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
