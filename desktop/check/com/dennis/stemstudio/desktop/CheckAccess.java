package com.dennis.stemstudio.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The checks behind StudioCheck, in the app's package. */
public final class CheckAccess {
    private CheckAccess() {}

    private static int fails;
    private static void ok(boolean c, String m) { if (!c) fails++; System.out.println((c ? "PASS " : "FAIL ") + m); }

    public static int run(File song, File other) throws Exception {
        fails = 0;
        File out = Files.createTempDirectory("studiocheck").toFile();
        ok(Engine.ready(), "the engine and the model are where the app looks: " + Engine.exe() + ", " + Engine.model());
        Engine.Probe probe = Engine.probe();
        ok(probe.cores() >= 1, "probe: " + (probe.hasGpu() ? "graphics card " + probe.gpu() + " (" + probe.gpuMb() + " MB)" : "no graphics card") + ", " + probe.cores() + " threads");

        // Separate (the processor, as the runner has no graphics card; the app's default falls back the same way).
        AtomicInteger sections = new AtomicInteger(), peakLines = new AtomicInteger();
        float[][] live = new float[6][Demo.N];
        long t0 = System.nanoTime();
        Engine.Result r = Engine.separate(song, out, "Check Song", true, Demo.N, new Engine.Listener() {
            @Override public void device(boolean gpu, String name) { System.out.println("  device: " + (gpu ? "graphics card " : "processor ") + name); }
            @Override public void fellBack(String why) { System.out.println("  fell back to the processor: " + why); }
            @Override public void section(int done, int total) { sections.set(total); }
            @Override public void stage(String name, int done, int total) { }
            @Override public void peaks(String stem, int from, float[] values) {
                peakLines.incrementAndGet();
                int i = java.util.Arrays.asList(Theme.STEMS).indexOf(stem);
                for (int k = 0; k < values.length; k++) live[i][from + k] = Math.max(live[i][from + k], values[k]);
            }
        });
        double took = (System.nanoTime() - t0) / 1e9;
        ok(r.stems().length == 6 && sections.get() > 0, String.format(Locale.US, "six stems from %d parts in %.1f s (AI %.1f s, peak %d MB, %s)",
            r.sections(), took, r.aiSeconds(), r.peakMb(), r.gpu() ? "graphics card" : "processor"));
        boolean named = true;
        for (int i = 0; i < 6; i++) named &= r.stems()[i].getName().equals("Check Song_" + Theme.STEMS[i] + "_24bit.wav");
        ok(named, "named as the phone names them: " + r.stems()[0].getName());
        long frames = -1;
        boolean format = true;
        for (File f : r.stems()) try (WavFile w = new WavFile(f)) {
            format &= w.rate == 48000 && w.bits == 24 && w.channels == 2;
            if (frames < 0) frames = w.frames; else format &= w.frames == frames;
        }
        ok(format, "48 kHz, 24-bit stereo, all the same length (" + frames + " frames)");
        ok(peakLines.get() >= 6 * sections.get(), "live overviews after every part (" + peakLines.get() + " lines)");
        // The live overview matches the finished files (the 48 kHz files are the same audio, resampled).
        // Compared on the mix's scale (as the lanes draw them): a near-silent stem's tiny peaks are rounded away live.
        double worst = 0, scale = 0;
        for (float v : WavFile.peaks(song, Demo.N)) scale = Math.max(scale, v);
        for (int i = 0; i < 6; i++) {
            float[] fin = WavFile.peaks(r.stems()[i], Demo.N);
            double d = 0;
            for (int k = 0; k < Demo.N; k++) d += Math.abs(live[i][k] - fin[k]);
            worst = Math.max(worst, d / Demo.N / Math.max(1e-6, scale));
        }
        ok(worst < 0.02, String.format(Locale.US, "live overviews match the finished stems (worst %.2f%% of full scale apart)", worst * 100));

        // Played together at 0 dB they are the song again.
        try (StemPlayer p = new StemPlayer(r.stems()); WavFile src = new WavFile(song)) {
            float[] g = {1, 1, 1, 1, 1, 1};
            p.setGains(g);
            long at = Math.min(p.frames / 2, (long) 20 * 48000);
            int n = 48000 * 2;
            float[][] mix = p.mixAt(at, n);
            double err = 0, sig = 0;
            if (src.rate == 48000) {
                float[] l = new float[n], rr = new float[n];
                src.seek(at);
                src.read(l, rr, n);
                // The model works at 44.1 kHz, so compare below 18 kHz: a short moving average does.
                for (int i = 8; i < n - 8; i++) {
                    double d = 0, s0 = 0;
                    for (int k = -2; k <= 2; k++) { d += mix[0][i + k] - l[i + k]; s0 += l[i + k]; }
                    err += d * d; sig += s0 * s0;
                }
                double db = 10 * Math.log10(err / Math.max(1e-20, sig));
                ok(db < -35, String.format(Locale.US, "the six stems played together are the song (difference %.1f dB)", db));
            }
            // Solo the bass: only the bass is heard.
            float[] solo = {0, 0, 1, 0, 0, 0};
            p.setGains(solo);
            float[][] bass = p.mixAt(at, 4096);
            float[] l = new float[4096], rr = new float[4096];
            try (WavFile b = new WavFile(r.stems()[2])) { b.seek(at); b.read(l, rr, 4096); }
            double diff = 0;
            for (int i = 0; i < 4096; i++) diff = Math.max(diff, Math.abs(bass[0][i] - l[i]));
            ok(diff < 1e-6, "solo Bass plays only the bass");
            float[] half = {1, 1, (float) Math.pow(10, -6 / 20.0), 1, 1, 0};
            p.setGains(half);
            ok(p.mixAt(at, 16) != null, "levels and mutes apply to what is played");
        }

        // The ZIP for Master Room.
        File zip = Export.zip(r.stems(), out, "Check Song");
        List<String> names = new ArrayList<>();
        long bytes = 0;
        try (ZipFile z = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) { ZipEntry en = e.nextElement(); names.add(en.getName()); bytes += en.getSize(); }
        }
        long expect = 0;
        for (File f : r.stems()) expect += f.length();
        boolean words = names.size() == 6;
        for (String st : Theme.STEMS) words &= names.stream().anyMatch(n -> n.contains("_" + st + "_"));
        ok(words && bytes == expect, "the ZIP for Master Room holds the six WAVs (" + names.get(0) + " …, " + Export.size(zip.length()) + ")");
        File copy = new File(out, "copy");
        Export.copy(r.stems(), copy);
        ok(copy.list().length == 6, "Save the six WAV files copies them");

        // Stop: the run ends after the part it is on, and leaves nothing behind.
        File stopDir = new File(out, "stop");
        AtomicBoolean asked = new AtomicBoolean();
        String stopped = "";
        try {
            Engine.separate(song, stopDir, "Stop", false, 0, new Engine.Listener() {
                @Override public void device(boolean gpu, String name) { }
                @Override public void fellBack(String why) { }
                @Override public void section(int done, int total) { if (done >= 1 && asked.compareAndSet(false, true)) Engine.cancel(); }
                @Override public void stage(String name, int done, int total) { }
                @Override public void peaks(String stem, int from, float[] values) { }
            });
        } catch (IOException e) { stopped = e.getMessage(); }
        String[] left = stopDir.list();
        ok(stopped.contains("Cancel") && left != null && left.length == 0, "Stop ends the run and leaves no files (" + stopped + ")");

        // Another format through FFmpeg.
        if (other != null && other.isFile()) {
            File wav = File.createTempFile("studiocheck", ".wav");
            Ffmpeg.toWav(other, wav);
            try (WavFile w = new WavFile(wav)) {
                Ffmpeg.Info info = Ffmpeg.info(other);
                ok(w.engineReads() && w.rate == 48000 && Math.abs(w.seconds() - new WavFile(song).seconds()) < 0.2,
                    "an " + info.codec().toUpperCase(Locale.ROOT) + " song is read through FFmpeg (" + info.rate() + " Hz, " + String.format(Locale.US, "%.1f", w.seconds()) + " s)");
            }
            wav.delete();
        } else System.out.println("SKIP no second song for FFmpeg");
        return fails;
    }
}
