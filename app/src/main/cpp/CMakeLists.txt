cmake_minimum_required(VERSION 3.22.1)
project(dennisstem LANGUAGES C CXX)

add_library(sherpa-c-api SHARED IMPORTED)
set_target_properties(sherpa-c-api PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so")

add_library(dennisstem SHARED stem_native.cpp)
target_include_directories(dennisstem PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(dennisstem sherpa-c-api log)

# Experimental HT-Demucs runtime directly uses the ONNX Runtime C API.
add_library(onnx-runtime SHARED IMPORTED)
set_target_properties(onnx-runtime PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so")
add_library(dennisfourstem SHARED four_stem_native.cpp)
target_include_directories(dennisfourstem PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(dennisfourstem onnx-runtime log)
