package com.dennis.stemstudio;

/** Experimental ONNX Runtime four-stem backend. Existing NativeSeparator stays untouched. */
final class FourStemNative {
    static {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("dennisfourstem");
    }
    interface Progress { void onProgress(int completedChunks, int totalChunks); }
    static native String[] separate(String model, String inputWav, String outputDirectory,
                                    String prefix, Progress listener);
    static native void cancel();
    private FourStemNative() { }
}
