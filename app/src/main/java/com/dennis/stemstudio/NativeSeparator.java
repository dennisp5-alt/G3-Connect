package com.dennis.stemstudio;

final class NativeSeparator {
    static {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-c-api");
        System.loadLibrary("dennisstem");
    }

    private NativeSeparator() { }

    // CPU inference; returns [vocals.wav, instrumental.wav].
    static native String[] separate(String modelPath, String inputWav,
                                    String outputDir, String outputPrefix);
}
