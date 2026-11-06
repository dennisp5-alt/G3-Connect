cmake_minimum_required(VERSION 3.22.1)
project(dennisstem LANGUAGES C CXX)

add_library(sherpa-c-api SHARED IMPORTED)
set_target_properties(sherpa-c-api PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so")

add_library(dennisstem SHARED stem_native.cpp)
target_include_directories(dennisstem PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(dennisstem sherpa-c-api log)

# Experimental three-stem model path reuses pinned sherpa-onnx engine.
# Original two-stem native library remains unchanged.
add_library(dennisthree SHARED three_stem_native.cpp)
target_include_directories(dennisthree PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(dennisthree sherpa-c-api log)
# Experimental four-stem model path reuses pinned sherpa-onnx engine.
# Original two-stem native library remains unchanged.
add_library(dennisfour SHARED four_stem_sequential_native.cpp)
target_include_directories(dennisfour PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(dennisfour sherpa-c-api log)
# Debug APKs would otherwise leave the join and export code unoptimised.
target_compile_options(dennisfour PRIVATE -O3)
# Six stems runs HT-Demucs on the same pinned ONNX Runtime, without sherpa-onnx.
add_library(onnxruntime-lib SHARED IMPORTED)
set_target_properties(onnxruntime-lib PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so")
add_library(dennissix SHARED six_stem_native.cpp)
target_include_directories(dennissix PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(dennissix onnxruntime-lib log)
target_compile_options(dennissix PRIVATE -O3)
