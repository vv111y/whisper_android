# Define your project and its version
cmake_minimum_required(VERSION 3.22.1)
project(TFLiteEngine VERSION 1.0)

# Specify where to find the header files for TF Lite C++
set(INCLUDE_DIRS
        ${CMAKE_CURRENT_LIST_DIR}/tf-lite-api/tensorflow_src
        ${CMAKE_CURRENT_LIST_DIR}/tf-lite-api/include/flatbuffers/include
        )

# # Check if the build is Android
if (ANDROID)
        # Optional WebRTC VAD sources (vendored C implementation)
        # Expected layout (standalone distribution):
        #   app/src/main/cpp/third_party/webrtc_vad/webrtc_vad.c
        #   app/src/main/cpp/third_party/webrtc_vad/webrtc_vad.h
        # NOTE: We validate these files actually compile (and are not HTML)
        # before enabling HAVE_WEBRTC_VAD. Otherwise we fall back gracefully.
        set(WEBRTC_VAD_DIR ${CMAKE_CURRENT_LIST_DIR}/third_party/webrtc_vad)
        set(WEBRTC_VAD_SRC ${WEBRTC_VAD_DIR}/webrtc_vad.c)
        set(WEBRTC_VAD_HDR ${WEBRTC_VAD_DIR}/webrtc_vad.h)

        set(VAD_LINK_LIB "")
        # Case 1: Upstream layout vendored under third_party/webrtc_vad/common_audio/...
        set(WEBRTC_UPSTREAM_COMMON_AUDIO_DIR ${WEBRTC_VAD_DIR}/common_audio)
        if (EXISTS ${WEBRTC_UPSTREAM_COMMON_AUDIO_DIR}/vad/webrtc_vad.c)
                file(GLOB WEBRTC_VAD_SRCS
                        ${WEBRTC_UPSTREAM_COMMON_AUDIO_DIR}/vad/*.c
                        ${WEBRTC_UPSTREAM_COMMON_AUDIO_DIR}/signal_processing/*.c
                )
                if (WEBRTC_VAD_SRCS)
                        add_library(webrtcvad STATIC ${WEBRTC_VAD_SRCS})
                        target_include_directories(webrtcvad PUBLIC
                                ${WEBRTC_UPSTREAM_COMMON_AUDIO_DIR}
                                ${WEBRTC_UPSTREAM_COMMON_AUDIO_DIR}/vad/include
                                ${WEBRTC_UPSTREAM_COMMON_AUDIO_DIR}/signal_processing/include
                        )
                        add_definitions(-DHAVE_WEBRTC_VAD)
                        set(VAD_LINK_LIB webrtcvad)
                else()
                        message(WARNING "WebRTC VAD upstream layout detected but no sources were found via GLOB. Falling back to heuristic VAD.")
                endif()
        # Case 2: Standalone two-file port in the root of third_party/webrtc_vad
        elseif (EXISTS ${WEBRTC_VAD_SRC} AND EXISTS ${WEBRTC_VAD_HDR})
                include(CheckCSourceCompiles)
                # Ensure the header compiles and exposes the expected API symbols (self-contained header)
                set(CMAKE_REQUIRED_INCLUDES ${WEBRTC_VAD_DIR})
                check_c_source_compiles(
                        "#include \"webrtc_vad.h\"\nint main(){ VadInst* v = WebRtcVad_Create(); WebRtcVad_Free(v); return 0; }"
                        HAVE_VALID_WEBRTC_VAD
                )
                if (HAVE_VALID_WEBRTC_VAD)
                        add_library(webrtcvad STATIC ${WEBRTC_VAD_SRC})
                        target_include_directories(webrtcvad PUBLIC ${WEBRTC_VAD_DIR})
                        add_definitions(-DHAVE_WEBRTC_VAD)
                        set(VAD_LINK_LIB webrtcvad)
                else()
                        message(WARNING "third_party/webrtc_vad present, but the two-file port is not self-contained (header includes external paths). To use upstream WebRTC VAD, vendor common_audio/{vad,signal_processing} and their headers under third_party/webrtc_vad/common_audio/. Falling back to heuristic VAD.")
                endif()
        endif()

        add_library(audioEngine SHARED TFLiteEngine.cpp TFLiteEngineJNI.cpp WebRtcVadJNI.cpp)
        target_include_directories(audioEngine PRIVATE ${INCLUDE_DIRS} ${CMAKE_CURRENT_LIST_DIR}/third_party/webrtc_vad)

    # Add 'tflite' library (imported)
    add_library(tflite SHARED IMPORTED)
    set_target_properties(tflite PROPERTIES IMPORTED_LOCATION
            ${CMAKE_CURRENT_LIST_DIR}/tf-lite-api/generated-libs/${ANDROID_ABI}/libtensorflowlite.so)

        # Android system libs
        find_library(log-lib log)
        if (VAD_LINK_LIB)
                target_link_libraries(audioEngine tflite ${VAD_LINK_LIB} ${log-lib})
        else()
                target_link_libraries(audioEngine tflite ${log-lib})
        endif()
endif ()

# Check if the build is not Android
if (NOT ANDROID)
    add_library(audioEngine SHARED TFLiteEngine.cpp)
    target_include_directories(audioEngine PRIVATE ${INCLUDE_DIRS})

    # Add 'tflite' library (imported)
    add_library(tflite SHARED IMPORTED)
    set_target_properties(tflite PROPERTIES IMPORTED_LOCATION
            ${CMAKE_CURRENT_LIST_DIR}/tf-lite-api/generated-libs/x86_64/libtensorflowlite.so)

    # Create an executable target that links with 'audioEngine' and 'tflite' libraries
    add_executable(my_audio_app main.cpp)
    target_link_libraries(my_audio_app PRIVATE audioEngine tflite)
endif ()






