package com.dennis.stemstudio.desktop;

import java.io.File;
import java.nio.file.Files;

/**
 * Runs the app's benchmark on a song, as the button does, and prints its report. On a machine without a
 * graphics card it measures the processor's serial and pipelined ways and checks their files match.
 *
 *   java -cp classes;check com.dennis.stemstudio.desktop.BenchmarkCheck song.wav
 */
public final class BenchmarkCheck {
    private BenchmarkCheck() {}

    public static void main(String[] a) throws Exception {
        Engine.Probe probe = Engine.probe();
        File report = Benchmark.run(new File(a[0]), "Check song", 0, probe, System.out::println);
        String text = Files.readString(report.toPath());
        System.out.println(text);
        System.out.println(text.contains("identical to the reference, byte for byte") ? "BENCHMARK_CHECK_PASS" : "BENCHMARK_CHECK_FAIL");
    }
}
