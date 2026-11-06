package com.dennis.stemstudio;

/** Six-stem JNI bridge. Runs HT-Demucs through ONNX Runtime directly, without sherpa-onnx. */
final class SixStemNative {
    static {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("dennissix");
    }
    interface Progress { void onProgress(int phase, int chunk, int total); }
    static native String[] separate(String model, String wav, String directory, String prefix,
       int startSeconds, int durationSeconds, int threads, Progress callback);
    static native void cancel();
    private SixStemNative() {}
}
