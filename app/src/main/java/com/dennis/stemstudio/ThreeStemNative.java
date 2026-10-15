package com.dennis.stemstudio;

/** Separate experimental 3-stem JNI bridge. Protected NativeSeparator untouched. */
final class ThreeStemNative {
    static {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-c-api");
        System.loadLibrary("dennisthree");
    }
    interface Progress { void onProgress(int phase, int chunk, int total); }
    static native String[] separate(String vocalModel, String drumModel, String wav,
       String directory, String prefix, int startSeconds, int durationSeconds, Progress callback);
    static native void cancel();
    private ThreeStemNative() {}
}
