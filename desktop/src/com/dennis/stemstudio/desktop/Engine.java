package com.dennis.stemstudio.desktop;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the six-stem engine (stemengine.exe, the phone's engine built for Windows) and reports what it says.
 * Lives in the app folder: engine\stemengine.exe with its DLLs, and models\htdemucs_6s.onnx.
 */
final class Engine {
    private Engine() {}

    /** The app's own folder (where the jar is), or the source tree when run from there. */
    static File appDir() {
        try {
            File jar = new File(Engine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() ? jar.getParentFile() : jar;
        } catch (Exception e) {
            return new File(".");
        }
    }

    static File exe() {
        String p = System.getProperty("stemstudio.engine");
        if (p != null) return new File(p);
        File dir = new File(appDir(), "engine");
        File win = new File(dir, "stemengine.exe");
        return win.isFile() ? win : new File(dir, "stemengine");
    }

    static File model() {
        String p = System.getProperty("stemstudio.model");
        return p != null ? new File(p) : new File(new File(appDir(), "models"), "htdemucs_6s.onnx");
    }

    static boolean ready() { return exe().isFile() && model().isFile(); }

    record Probe(String gpu, long gpuMb, int cores) {
        boolean hasGpu() { return gpu != null; }
    }

    /** Which graphics card would be used, and how many processor threads there are. */
    static Probe probe() {
        String gpu = null;
        long mb = 0;
        int cores = Runtime.getRuntime().availableProcessors();
        if (!exe().isFile()) return new Probe(null, 0, cores);
        try {
            Process p = new ProcessBuilder(exe().getAbsolutePath(), "--probe").redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(20, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                if (line.startsWith("GPU ") && !line.equals("GPU none")) {
                    String[] parts = line.substring(4).split("\\|");
                    gpu = parts[0].trim();
                    try { mb = Long.parseLong(parts[1].trim()); } catch (RuntimeException ignored) { }
                } else if (line.startsWith("CORES ")) {
                    try { cores = Integer.parseInt(line.substring(6).trim()); } catch (NumberFormatException ignored) { }
                }
            }
        } catch (IOException | InterruptedException ignored) { }
        return new Probe(gpu, mb, cores);
    }

    /** What the engine says as it goes. Called on the engine's reading thread. */
    interface Listener {
        void device(boolean gpu, String name);
        void fellBack(String why);
        void section(int done, int total);
        void stage(String name, int done, int total);
        void peaks(String stem, int from, float[] values);
    }

    record Result(File[] stems, int sections, boolean gpu, double aiSeconds, long peakMb) {}

    private static volatile Process running;

    /**
     * Separates a 44.1 or 48 kHz WAV into six 48 kHz 24-bit stems named "&lt;prefix&gt;_&lt;Stem&gt;_24bit.wav"
     * in the folder. @param gpu false for the processor only
     * @return the stems in Theme.STEMS order
     */
    static Result separate(File wav, File out, String prefix, boolean gpu, int columns, Listener l) throws IOException {
        if (!ready()) throw new IOException("The separation engine is missing from the app folder. Reinstall Stem Studio.");
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Could not create " + out);
        List<String> cmd = new ArrayList<>(List.of(exe().getAbsolutePath(), "--model", model().getAbsolutePath(), "--in", wav.getAbsolutePath(),
            "--out", out.getAbsolutePath(), "--prefix", prefix, "--device", gpu ? "auto" : "cpu", "--peaks", String.valueOf(columns), "--watch-stdin"));
        // The experimental build separates the pipelined way (same files; see Benchmark); the release stays serial.
        if (System.getProperty("stemstudio.experimental") != null) cmd.addAll(List.of("--mode", "pipelined"));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        running = p;
        File[] stems = new File[6];
        String error = null, done = null;
        StringBuilder log = new StringBuilder(String.format("%n%s  %s  (%s)%n", java.time.LocalDateTime.now().withNano(0), wav.getName(), gpu ? "graphics card first" : "processor"));
        boolean onGpu = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("PEAKS ") && !line.startsWith("SECTION ") && !line.startsWith("STAGE ")) log.append(line).append(System.lineSeparator());
                if (line.startsWith("PEAKS ")) {
                    String[] parts = line.split(" ", 4);
                    if (parts.length == 4) {
                        String[] v = parts[3].split(",");
                        float[] values = new float[v.length];
                        for (int i = 0; i < v.length; i++) values[i] = Float.parseFloat(v[i]);
                        l.peaks(parts[1], Integer.parseInt(parts[2]), values);
                    }
                } else if (line.startsWith("SECTION ")) {
                    String[] parts = line.split(" ");
                    l.section(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                } else if (line.startsWith("STAGE ")) {
                    String[] parts = line.split(" ");
                    l.stage(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                } else if (line.startsWith("DEVICE ")) {
                    onGpu = line.startsWith("DEVICE gpu");
                    l.device(onGpu, line.substring(onGpu ? 11 : 11).trim());
                } else if (line.startsWith("FALLBACK ")) {
                    l.fellBack(line.substring(9));
                } else if (line.startsWith("STEM ")) {
                    String[] parts = line.split(" ", 3);
                    for (int i = 0; i < 6; i++) if (Theme.STEMS[i].equals(parts[1])) stems[i] = new File(parts[2]);
                } else if (line.startsWith("DONE ")) {
                    done = line.substring(5);
                } else if (line.startsWith("ERROR ")) {
                    error = line.substring(6);
                }
            }
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Stopped");
        } finally {
            running = null;
            keepLog(log.toString());
        }
        if (done == null) throw new IOException(error != null ? error : "The engine stopped without finishing (exit " + (p.isAlive() ? "?" : p.exitValue()) + ")");
        for (File f : stems) if (f == null || !f.isFile()) throw new IOException("A stem is missing after separating");
        // song|sections|device|threads|aiSeconds|peakMB|gain
        String[] d = done.split("\\|");
        int sections = 0; double ai = 0; long mb = 0;
        try { sections = Integer.parseInt(d[1]); ai = Double.parseDouble(d[4]); mb = Long.parseLong(d[5]); } catch (RuntimeException ignored) { }
        return new Result(stems, sections, d.length > 2 && d[2].equals("gpu"), ai, mb);
    }

    /**
     * What the engine said on each run (devices, why the graphics card was not used, errors), kept in
     * Documents\Stem Studio\engine log.txt so a problem can be looked into afterwards. Kept short.
     */
    static void keepLog(String text) {
        try {
            File f = new File(Places.root(), "engine log.txt");
            f.getParentFile().mkdirs();
            String old = f.isFile() ? java.nio.file.Files.readString(f.toPath(), StandardCharsets.UTF_8) : "";
            String all = old + text;
            if (all.length() > 100_000) all = all.substring(all.length() - 100_000);
            java.nio.file.Files.writeString(f.toPath(), all, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    /** Asks the engine to stop at the end of the part it is on (it tidies up its temporary files). */
    static void cancel() {
        Process p = running;
        if (p == null) return;
        try {
            OutputStream o = p.getOutputStream();
            o.write("cancel\n".getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (IOException e) {
            p.destroy();
        }
    }
}
