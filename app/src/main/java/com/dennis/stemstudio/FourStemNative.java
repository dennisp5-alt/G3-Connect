package com.dennis.stemstudio;

/** Separate experimental 4-stem JNI bridge. Protected NativeSeparator untouched. */
final class FourStemNative {
    static {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-c-api");
        System.loadLibrary("dennisfour");
    }
    interface Progress { void onProgress(int phase, int chunk, int total); }
    static native String[] separate(String vocalModel, String drumModel, String bassModel, String wav,
       String directory, String prefix, int startSeconds, int durationSeconds, int threads, Progress callback);
    static native void cancel();
    private FourStemNative() {}
}
