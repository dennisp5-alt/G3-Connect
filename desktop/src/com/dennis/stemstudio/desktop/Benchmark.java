package com.dennis.stemstudio.desktop;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Experimental: the same 60 seconds of the same song through every way the engine can work (the processor
 * alone, the graphics card alone, pipelined, card and processor together), with what the processor, card
 * and memory did, and a check that every run's six files match the reference run (the processor, serial)
 * sample for sample. Measures only; the shipping app's behaviour does not change.
 */
final class Benchmark {
    private Benchmark() {}

    record Way(String label, List<String> args, boolean gpu, boolean split) {
        Way(String label, List<String> args, boolean gpu) { this(label, args, gpu, false); }
    }

    /** The model split at the inverse STFT: the head for the card, the tail for the processor (built by CI). */
    static File head() { return new File(Engine.model().getParentFile(), "htdemucs_6s_head.onnx"); }
    static File tail() { return new File(Engine.model().getParentFile(), "htdemucs_6s_tail.onnx"); }
    static boolean canSplit() { return head().isFile() && tail().isFile(); }

    /** The runs, in order. Card runs get a cool-down first, so heat from one run does not slow the next. */
    static List<Way> ways(boolean hasGpu) {
        List<Way> w = new ArrayList<>();
        w.add(new Way("Processor, serial (reference)", List.of("--device", "cpu", "--mode", "serial"), false));
        w.add(new Way("Processor, pipelined", List.of("--device", "cpu", "--mode", "pipelined"), false));
        if (hasGpu && canSplit()) {
            // The model split in two: everything up to the inverse STFT on the card, the rest on the processor.
            w.add(new Way("Split fused, serial", List.of("--device", "gpu", "--gpu-way", "fused", "--mode", "serial"), true, true));
            w.add(new Way("Split unfused, serial", List.of("--device", "gpu", "--gpu-way", "unfused", "--mode", "serial"), true, true));
            w.add(new Way("Split fused, overlapped", List.of("--device", "gpu", "--gpu-way", "fused", "--mode", "pipelined"), true, true));
            w.add(new Way("Split unfused, overlapped", List.of("--device", "gpu", "--gpu-way", "unfused", "--mode", "pipelined"), true, true));
            w.add(new Way("Split fused + CPU sections", List.of("--device", "gpu", "--gpu-way", "fused", "--mode", "parallel"), true, true));
        }
        if (hasGpu) w.add(new Way("Whole model on card, fused", List.of("--device", "gpu", "--gpu-way", "fused", "--mode", "serial"), true));
        return w;
    }

    static final class Run {
        final Way way;
        boolean ok;
        String error = "";
        double wall;
        final Map<String, String> timing = new LinkedHashMap<>(), stats = new LinkedHashMap<>();
        final List<String> placement = new ArrayList<>();
        File[] stems;
        String match = "", match2 = "";
        Run(Way way) { this.way = way; }
    }

    private static volatile Process running;
    private static volatile boolean stopped;

    static void stop() {
        stopped = true;
        Process p = running;
        if (p != null) try { OutputStream o = p.getOutputStream(); o.write("cancel\n".getBytes(StandardCharsets.UTF_8)); o.flush(); } catch (IOException e) { p.destroy(); }
    }

    /**
     * Runs every way on [start, start + 60 s) of the song and writes the report.
     * @param said progress in words, for the panel
     * @return the report file
     */
    static File run(File wav, String title, int start, Engine.Probe probe, Consumer<String> said) throws IOException {
        stopped = false;
        File root = new File(Places.root(), "Benchmark " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")));
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("Could not create " + root);
        List<Way> ways = ways(probe.hasGpu());
        List<Run> runs = new ArrayList<>();
        Run reference = null;
        for (int i = 0; i < ways.size() && !stopped; i++) {
            Way way = ways.get(i);
            if (way.gpu() && i > 0) {
                for (int s = 20; s > 0 && !stopped; s--) {
                    said.accept("Cooling the card before run " + (i + 1) + " of " + ways.size() + " (" + s + " s)");
                    try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Stopped"); }
                }
            }
            said.accept("Run " + (i + 1) + " of " + ways.size() + ": " + way.label());
            Run r = one(way, wav, new File(root, "run" + (i + 1)), start, 60, null);
            runs.add(r);
            if (i == 0) reference = r;
            if (r.ok && reference != null && reference.ok && r != reference) r.match = compare(reference.stems, r.stems);
            // The card's own reference: the pipelined and parallel card runs against the card's serial run.
            // Each overlapped split run against the serial split run on the same card way (same devices, so any
            // difference is the overlapping's).
            String serialLabel = r.way.label().replace("overlapped", "serial").replace(" + CPU sections", ", serial");
            Run cardSerial = runs.stream().filter(x -> x.way.label().equals(serialLabel) && x.ok).findFirst().orElse(null);
            if (r.ok && cardSerial != null && r != cardSerial) r.match2 = compare(cardSerial.stems, r.stems);
        }
        // Where the card's steps ran, on 10 seconds with profiling (which slows it, so it is timed apart).
        Run placement = null;
        if (probe.hasGpu() && !stopped) {
            said.accept("Profiling which device runs each step (10 seconds)");
            String prefix = new File(root, "profile").getAbsolutePath();
            placement = one(new Way("Profile, split fused", List.of("--device", "gpu", "--gpu-way", "fused", "--mode", "serial", "--profile", prefix), true, canSplit()),
                wav, new File(root, "profile"), start, 10, null);
        }
        File report = new File(root, "benchmark report.txt");
        Files.writeString(report.toPath(), report(title, start, probe, runs, placement, reference), StandardCharsets.UTF_8);
        return report;
    }

    private static Run one(Way way, File wav, File out, int start, int seconds, Consumer<String> lines) {
        Run r = new Run(way);
        out.mkdirs();
        List<String> cmd = new ArrayList<>(List.of(Engine.exe().getAbsolutePath(), "--model", (way.split() ? head() : Engine.model()).getAbsolutePath(), "--in", wav.getAbsolutePath(),
            "--out", out.getAbsolutePath(), "--prefix", "b", "--start", String.valueOf(start), "--seconds", String.valueOf(seconds), "--monitor", "--watch-stdin"));
        cmd.addAll(way.args());
        if (way.split()) cmd.addAll(List.of("--tail", tail().getAbsolutePath()));
        long t0 = System.nanoTime();
        StringBuilder log = new StringBuilder(String.format("%n%s  benchmark: %s%n", LocalDateTime.now().withNano(0), way.label()));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            running = p;
            File[] stems = new File[6];
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("PEAKS") || line.startsWith("SECTION") || line.startsWith("STAGE")) continue;
                    log.append(line).append(System.lineSeparator());
                    if (lines != null) lines.accept(line);
                    if (line.startsWith("TIMING ")) parse(line.substring(7), r.timing);
                    else if (line.startsWith("STATS ")) parse(line.substring(6), r.stats);
                    else if (line.startsWith("PLACEMENT ")) r.placement.add(line.substring(10));
                    else if (line.startsWith("STEM ")) {
                        String[] q = line.split(" ", 3);
                        for (int i = 0; i < 6; i++) if (Theme.STEMS[i].equals(q[1])) stems[i] = new File(q[2]);
                    } else if (line.startsWith("DONE ")) r.ok = true;
                    else if (line.startsWith("ERROR ")) r.error = line.substring(6);
                    else if (line.startsWith("FALLBACK ")) r.error = line.substring(9);
                }
            }
            p.waitFor();
            r.stems = stems;
            if (r.ok) for (File f : stems) if (f == null || !f.isFile()) { r.ok = false; r.error = "a stem is missing"; }
        } catch (IOException | InterruptedException e) {
            r.error = e.getMessage();
        } finally {
            running = null;
            Engine.keepLog(log.toString());
        }
        r.wall = (System.nanoTime() - t0) / 1e9;
        return r;
    }

    private static void parse(String s, Map<String, String> into) {
        for (String kv : s.trim().split(" ")) {
            int eq = kv.indexOf('=');
            if (eq > 0) into.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
    }

    /**
     * Each stem against the reference run: same length, the largest difference in 24-bit steps, and the
     * difference as a level below the stem (identical files: "identical").
     */
    static String compare(File[] ref, File[] got) {
        StringBuilder b = new StringBuilder();
        boolean allSame = true;
        double worstDb = Double.NEGATIVE_INFINITY;
        long worstLsb = 0;
        try {
            for (int s = 0; s < 6; s++) {
                byte[] x = Files.readAllBytes(ref[s].toPath()), y = Files.readAllBytes(got[s].toPath());
                if (x.length != y.length) return "length differs on " + Theme.STEMS[s] + " (" + x.length + " vs " + y.length + " bytes)";
                if (java.util.Arrays.equals(x, y)) continue;
                allSame = false;
                double err = 0, sig = 0;
                long max = 0;
                for (int i = 44; i + 2 < x.length; i += 3) {
                    int a = (x[i] & 255) | (x[i + 1] & 255) << 8 | x[i + 2] << 16, c = (y[i] & 255) | (y[i + 1] & 255) << 8 | y[i + 2] << 16;
                    long d = Math.abs((long) a - c);
                    max = Math.max(max, d);
                    err += (double) d * d;
                    sig += (double) a * a;
                }
                worstLsb = Math.max(worstLsb, max);
                if (err > 0) worstDb = Math.max(worstDb, 10 * Math.log10(err / Math.max(1, sig)));
            }
        } catch (IOException e) {
            return "could not compare: " + e.getMessage();
        }
        if (allSame) return "identical to the reference, byte for byte";
        return String.format(Locale.US, "same length; largest difference %d steps of 24-bit, difference %.0f dB below the stem (worst stem)", worstLsb, worstDb);
    }

    private static String report(String title, int start, Engine.Probe probe, List<Run> runs, Run placement, Run reference) {
        StringBuilder b = new StringBuilder();
        b.append("Stem Studio benchmark (experimental)\n");
        b.append(String.format(Locale.US, "%s, 60 s from %d s. %s, %d processor threads.%n", title, start,
            probe.hasGpu() ? probe.gpu() + " (" + probe.gpuMb() + " MB)" : "No graphics card", probe.cores()));
        b.append("Same model (HT-Demucs 6s), same sections, weights and output for every run.\n\n");
        b.append(String.format(Locale.US, "%-32s %8s %8s %8s %8s %6s %6s %7s %7s %8s %6s%n", "Run", "Total s", "AI s", "Tail s", "CPU %", "GPU %", "GPU max",
            "VRAM MB", "Shared", "RAM MB", "Temp"));
        for (Run r : runs) {
            if (!r.ok) { b.append(String.format(Locale.US, "%-32s did not run: %s%n", r.way.label(), Studio.plain(r.error))); continue; }
            // Serial converts to 48 kHz after the last section; pipelined does it while the model works.
            double tail = "serial".equals(r.timing.get("mode")) ? num(r.timing, "upsample") + num(r.timing, "write") : num(r.timing, "write");
            b.append(String.format(Locale.US, "%-32s %8.1f %8.1f %8.1f %8s %6s %6s %7s %7s %8s %6s%n", r.way.label(), num(r.timing, "total"), num(r.timing, "infer"), tail,
                r.stats.getOrDefault("cpu_avg", "-"), na(r.stats.get("gpu_avg")), na(r.stats.get("gpu_max")), na(r.stats.get("vram_peak")), na(r.stats.get("shared_peak")),
                r.stats.getOrDefault("ram_peak", "-"), r.stats.containsKey("temp_max") && !"-1".equals(r.stats.get("temp_max")) ? r.stats.get("temp_start") + "→" + r.stats.get("temp_max") : "-"));
        }
        b.append("\nTotal = the engine's own time from opening the song to the last file. AI = time inside the model.\n");
        b.append("Tail = 48 kHz conversion and writing that the serial way leaves until the end.\n");
        b.append("CPU % = the whole processor, all threads. VRAM and Shared = this run's own card memory, from DirectX.\n");
        b.append("Split = the model cut at the inverse STFT: the card runs the first part, the processor the last.\n");
        b.append("Overlapped = the card starts the next section while the processor finishes the last one.\n\n");
        b.append("Files against the reference run:\n");
        for (Run r : runs) if (r.ok && r != reference) b.append("  ").append(r.way.label()).append(": ").append(r.match).append('\n');
        boolean any = false;
        for (Run r : runs) if (!r.match2.isEmpty()) {
            if (!any) { b.append("Against the card's own serial run (same device, so any difference is the pipeline's):\n"); any = true; }
            b.append("  ").append(r.way.label()).append(": ").append(r.match2).append('\n');
        }
        b.append("\nStage times (seconds):\n");
        for (Run r : runs) if (r.ok) b.append("  ").append(r.way.label()).append(": ").append(r.timing).append('\n');
        if (placement != null) {
            b.append("\nWhere each step ran (" + placement.way.label() + ", 10 s, profiled):\n");
            if (placement.ok) b.append("  ").append("card part ").append(placement.timing.getOrDefault("split_head", "-")).append(" s, processor part ").append(placement.timing.getOrDefault("split_tail", "-")).append(" s\n");
            if (!placement.ok) b.append("  did not run: ").append(Studio.plain(placement.error)).append('\n');
            for (String p : placement.placement) b.append("  ").append(p).append('\n');
        }
        Run best = null;
        for (Run r : runs) if (r.ok && (r.match.isEmpty() || r.match.startsWith("identical") || r.match.startsWith("same length")) && (best == null || num(r.timing, "total") < num(best.timing, "total"))) best = r;
        if (best != null) b.append(String.format(Locale.US, "%nFastest that matches the reference: %s, %.1f s.%n", best.way.label(), num(best.timing, "total")));
        return b.toString();
    }

    private static double num(Map<String, String> m, String k) {
        try { return Double.parseDouble(m.getOrDefault(k, "NaN")); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static String na(String v) { return v == null || v.equals("-1") ? "-" : v; }
}
