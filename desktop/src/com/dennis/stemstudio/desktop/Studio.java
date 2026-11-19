package com.dennis.stemstudio.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/** What the buttons do: open a song, separate it, play the stems, hand them on. */
final class Studio {
    static final String[] READS = {"wav", "mp3", "flac", "m4a", "aac", "ogg", "opus", "aif", "aiff", "wma"};
    static final Preferences PREFS = Preferences.userRoot().node(System.getProperty("stemstudio.prefs", "/com/dennis/stemstudio/desktop"));

    final StudioWindow w;
    final Session s;
    private final JFrame frame;
    private StemPlayer player;
    private File engineInput, tempWav;
    private Thread job;
    private Engine.Probe probe = new Engine.Probe(null, 0, Runtime.getRuntime().availableProcessors());
    private volatile boolean benchmarking;
    static final boolean EXPERIMENTAL = System.getProperty("stemstudio.experimental") != null;

    Studio(StudioWindow w, JFrame frame) {
        this.w = w;
        this.s = w.s;
        this.frame = frame;
        w.device.selected = PREFS.getInt("device", 0);
        s.engineReady = Engine.ready();
        w.drop.onClick(e -> choose());
        w.chooseAnother.onClick(e -> choose());
        w.separate.onClick(e -> { if (benchmarking) Benchmark.stop(); else if (s.stage == Session.Stage.SEPARATING) stop(); else separate(); });
        w.benchmark.setVisible(EXPERIMENTAL);
        w.benchmark.onClick(e -> benchmark());
        w.device.onChange(i -> { PREFS.putInt("device", i); estimate(); w.refresh(); });
        w.toMasterRoom.onClick(e -> sendToMasterRoom());
        w.saveWavs.onClick(e -> saveCopy());
        w.openFolder.onClick(e -> { if (s.outDir != null) Places.reveal(s.outDir); });
        w.transport.play.onClick(e -> togglePlay());
        w.lanes.changed = this::gains;
        w.lanes.seek = t -> { s.at = t; if (player != null) player.seek(t); repaint(); };
        // Drop a song anywhere on the window.
        w.root.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport t) { return s.stage != Session.Stage.SEPARATING && t.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override public boolean importData(TransferSupport t) {
                try {
                    @SuppressWarnings("unchecked") List<File> files = (List<File>) t.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) open(files.get(0));
                    return true;
                } catch (Exception e) { return false; }
            }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_SPACE && frame.isActive() && s.separated()
                && !(e.getComponent() instanceof Ui.Clickable && e.getComponent() != w.transport.play)) {
                togglePlay();
                return true;
            }
            return false;
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                Engine.cancel();
                if (player != null) player.close();
                if (tempWav != null) tempWav.delete();
            }
        });
        new Timer(40, e -> tick()).start();
        // Which graphics card there is (a moment's work, off the window's thread).
        new Thread(() -> {
            Engine.Probe p = Engine.probe();
            probe = p;
            SwingUtilities.invokeLater(() -> {
                s.onGpu = p.hasGpu();
                s.device = p.hasGpu() ? p.gpu() : "";
                s.cores = p.cores();
                estimate();
                w.refresh();
            });
        }, "probe").start();
        w.refresh();
    }

    private void repaint() { w.lanes.repaint(); w.transport.repaint(); }

    private boolean onGpu() { return s.onGpu && w.device.selected == 0; }

    /** From how fast the last runs went on this computer; before any, the processor's measured rate. */
    private void estimate() {
        if (s.seconds <= 0) { s.estimate = ""; return; }
        double perSecond = PREFS.getDouble(onGpu() ? "speed.gpu" : "speed.cpu", onGpu() ? Double.NaN : 0.95 * 4.0 / Math.max(2, s.cores - 1));
        s.estimate = Double.isNaN(perSecond) ? "" : "About " + Session.duration(s.seconds * perSecond + 5) + " for this song";
    }

    // ---------- Song ----------

    private void choose() {
        if (s.stage == Session.Stage.SEPARATING) return;
        FileDialog fd = new FileDialog(frame, "Choose a song", FileDialog.LOAD);
        String dir = PREFS.get("songDir", "");
        if (!dir.isEmpty()) fd.setDirectory(dir);
        fd.setFilenameFilter((d, n) -> { String l = n.toLowerCase(Locale.ROOT); for (String x : READS) if (l.endsWith("." + x)) return true; return false; });
        fd.setFile("*." + String.join(";*.", READS));
        fd.setVisible(true);
        if (fd.getFile() == null) return;
        open(new File(fd.getDirectory(), fd.getFile()));
    }

    void open(File f) {
        if (s.stage == Session.Stage.SEPARATING || !f.isFile()) return;
        PREFS.put("songDir", f.getParent() == null ? "" : f.getParent());
        closePlayer();
        s.setSong(f);
        s.title = Places.title(f);
        s.result = ""; s.resultBad = false; s.notice = "";
        w.songWhere.set("Reading the song…", Theme.MID);
        new SwingWorker<Void, Void>() {
            File input; float[] peaks; double seconds; int rate, bits; String format = "";
            File[] earlier;
            @Override protected Void doInBackground() throws Exception {
                boolean direct = false;
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".wav")) {
                    try (WavFile wav = new WavFile(f)) { direct = wav.engineReads(); rate = wav.rate; bits = wav.format == 3 ? 32 : wav.bits; }
                    catch (IOException ignored) { }
                }
                if (direct) input = f;
                else {
                    Ffmpeg.Info info = Ffmpeg.info(f);
                    rate = info.rate();
                    format = info.codec().toUpperCase(Locale.ROOT).replace("PCM_", "").replace("AAC", "AAC").replace("MP3FLOAT", "MP3");
                    if (format.startsWith("S16") || format.startsWith("S24") || format.startsWith("S32") || format.startsWith("F32")) format = "WAV";
                    File tmp = File.createTempFile("stemstudio-song", ".wav");
                    tmp.deleteOnExit();
                    Ffmpeg.toWav(f, tmp);
                    input = tmp;
                }
                try (WavFile wav = new WavFile(input)) { seconds = wav.seconds(); if (rate == 0) rate = wav.rate; }
                if (seconds > 20 * 60) throw new IOException("Songs up to 20 minutes can be separated");
                peaks = WavFile.peaks(input, Demo.N);
                // Stems made earlier for this song (same folder, newer than the song) open straight away.
                File dir = new File(Places.root(), Places.safe(Places.title(f)));
                File[] found = new File[6];
                boolean all = true;
                for (int i = 0; i < 6; i++) {
                    found[i] = new File(dir, Places.safe(Places.title(f)) + "_" + Theme.STEMS[i] + "_24bit.wav");
                    all &= found[i].isFile() && found[i].lastModified() >= f.lastModified();
                }
                if (all) earlier = found;
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    if (tempWav != null && !tempWav.equals(input)) tempWav.delete();
                    engineInput = input;
                    tempWav = input == f ? null : input;
                    s.mixPeaks = peaks; s.seconds = seconds; s.rate = rate; s.bits = bits; s.format = format;
                    s.outDir = new File(Places.root(), Places.safe(s.title));
                    for (int i = 0; i < 6; i++) { s.stemPeaks[i] = null; s.stemFiles[i] = null; s.gainDb[i] = 0; s.mute[i] = false; s.solo[i] = false; }
                    s.at = 0;
                    s.stage = Session.Stage.READY;
                    estimate();
                    if (earlier != null) useStems(earlier, "Separated earlier. Separate again for a fresh run.");
                } catch (Exception e) {
                    s.stage = s.mixPeaks == null ? Session.Stage.EMPTY : s.stage;
                    s.result = "Could not open that song: " + message(e);
                    s.resultBad = true;
                }
                w.refresh();
            }
        }.execute();
    }

    // ---------- Separating ----------

    private void separate() {
        if (engineInput == null || s.stage == Session.Stage.SEPARATING) return;
        closePlayer();
        s.stage = Session.Stage.SEPARATING;
        s.progress = 0; s.section = 0; s.sections = 0; s.secondsLeft = Double.NaN;
        s.stageText = "Starting"; s.result = ""; s.resultBad = false; s.notice = "";
        for (int i = 0; i < 6; i++) { s.stemPeaks[i] = new float[Demo.N]; s.stemFiles[i] = null; }
        s.at = 0;
        w.refresh();
        final boolean gpu = onGpu();
        final long started = System.nanoTime();
        final File input = engineInput, out = s.outDir;
        final String prefix = Places.safe(s.title);
        job = new Thread(() -> {
            long[] firstSection = {0};
            String[] whyNotGpu = {null};
            try {
                Engine.Result r = Engine.separate(input, out, prefix, gpu, Demo.N, new Engine.Listener() {
                    @Override public void device(boolean g, String name) {
                        ui(() -> s.stageText = g ? "Starting on the graphics card" : "Starting on the processor");
                    }
                    @Override public void fellBack(String why) {
                        whyNotGpu[0] = why;
                        ui(() -> s.result = "The graphics card could not run it, so the processor is doing it instead.");
                    }
                    @Override public void section(int done, int total) {
                        long now = System.nanoTime();
                        if (done == 1) firstSection[0] = now;
                        ui(() -> {
                            s.section = done; s.sections = total;
                            s.progress = 0.9 * done / total;
                            s.stageText = "Part " + (done + 1) + " of " + total;
                            // Time per part from the second part on (the first includes loading the model).
                            if (done >= 2) {
                                double per = (now - firstSection[0]) / 1e9 / (done - 1);
                                s.secondsLeft = per * (total - done) * 1.1 + 4;
                            }
                        });
                    }
                    @Override public void stage(String name, int done, int total) {
                        ui(() -> {
                            s.progress = name.equals("resample") ? 0.9 + 0.06 * done / total : name.equals("write") ? 0.96 + 0.04 * done / total : 1;
                            s.stageText = "Finishing the six files";
                            s.secondsLeft = Double.NaN;
                        });
                    }
                    @Override public void peaks(String stem, int from, float[] values) {
                        int i = java.util.Arrays.asList(Theme.STEMS).indexOf(stem);
                        if (i < 0) return;
                        ui(() -> {
                            float[] p = s.stemPeaks[i];
                            if (p == null) return;
                            for (int k = 0; k < values.length && from + k < p.length; k++) p[from + k] = Math.max(p[from + k], values[k]);
                        });
                    }
                });
                double took = (System.nanoTime() - started) / 1e9;
                PREFS.putDouble(r.gpu() ? "speed.gpu" : "speed.cpu", took / Math.max(1, s.seconds));
                String done = "Done in " + Session.duration(took) + " on the " + (r.gpu() ? "graphics card" : "processor") + ".";
                String result = whyNotGpu[0] == null ? done + " The six stems add back up to the song."
                    : done + " The graphics card could not run it: " + plain(whyNotGpu[0]);
                ui(() -> useStems(r.stems(), result));
            } catch (IOException e) {
                ui(() -> {
                    boolean stopped = message(e).contains("Cancel") || message(e).equals("Stopped");
                    s.stage = Session.Stage.READY;
                    for (int i = 0; i < 6; i++) s.stemPeaks[i] = null;
                    s.result = stopped ? "Stopped. Nothing was kept." : "Separating did not finish: " + message(e);
                    s.resultBad = !stopped;
                });
            }
            ui(() -> { estimate(); w.refresh(); });
        }, "separate");
        job.start();
    }

    /** Experimental: every way of running the engine on 60 s of this song, measured and checked. */
    private void benchmark() {
        if (engineInput == null || benchmarking || s.stage == Session.Stage.SEPARATING) return;
        closePlayer();
        benchmarking = true;
        s.result = "Benchmark starting. It runs the same 60 seconds about seven ways and takes several minutes.";
        s.resultBad = false;
        w.separate.set("Stop the benchmark", Ui.Kind.DANGER);
        w.benchmark.setEnabled(false);
        w.refresh();
        w.separate.set("Stop the benchmark", Ui.Kind.DANGER);
        final File input = engineInput;
        final int start = s.seconds >= 100 ? 30 : 0;
        new Thread(() -> {
            String done;
            File report = null;
            try {
                report = Benchmark.run(input, s.title, start, probe, msg -> ui(() -> { s.result = msg; w.refresh(); w.separate.set("Stop the benchmark", Ui.Kind.DANGER); }));
                done = "Benchmark finished. The report is in " + StudioWindow.shortPath(report.getParent()) + ".";
            } catch (Exception e) {
                done = "The benchmark did not finish: " + message(e);
            }
            final String text = done;
            final File shown = report;
            ui(() -> {
                benchmarking = false;
                s.result = text;
                w.benchmark.setEnabled(true);
                w.refresh();
                if (shown != null) {
                    try {
                        JTextArea t = new JTextArea(java.nio.file.Files.readString(shown.toPath()), 30, 130);
                        t.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                        t.setEditable(false);
                        JOptionPane.showMessageDialog(frame, new JScrollPane(t), "Stem Studio benchmark", JOptionPane.PLAIN_MESSAGE);
                    } catch (IOException ignored) { }
                    Places.reveal(shown);
                }
            });
        }, "benchmark").start();
    }

    private void stop() {
        s.stageText = "Stopping after this part";
        Engine.cancel();
        w.refresh();
    }

    /** The stems are ready: their overviews, the player, and the lanes come alive. */
    private void useStems(File[] stems, String result) {
        try {
            for (int i = 0; i < 6; i++) { s.stemFiles[i] = stems[i]; s.stemPeaks[i] = WavFile.peaks(stems[i], Demo.N); }
            closePlayer();
            player = new StemPlayer(stems);
            player.ended = () -> ui(() -> { s.playing = false; s.at = 0; });
            gains();
            s.stage = Session.Stage.DONE;
            s.progress = 1;
            s.result = result;
            s.resultBad = false;
        } catch (IOException e) {
            s.stage = Session.Stage.READY;
            s.result = "The stems could not be opened: " + message(e);
            s.resultBad = true;
        }
        w.refresh();
    }

    // ---------- Listening ----------

    private float[] gainsNow() {
        float[] g = new float[6];
        for (int i = 0; i < 6; i++) g[i] = s.heard(i) ? (float) Math.pow(10, s.gainDb[i] / 20) : 0f;
        return g;
    }

    private void gains() {
        if (player != null) player.setGains(gainsNow());
        repaint();
    }

    private void togglePlay() {
        if (player == null) return;
        try {
            if (player.isPlaying()) { player.pause(); s.playing = false; }
            else { player.seek(s.at >= 0.999 ? 0 : s.at); player.play(); s.playing = true; }
        } catch (Exception e) {
            s.notice = "Could not play: " + message(e);
            s.noticeBad = true;
            w.refresh();
        }
        repaint();
    }

    private void tick() {
        if (player == null) return;
        if (player.isPlaying()) {
            s.at = player.fraction();
            s.meterL = player.levelL; s.meterR = player.levelR;
            repaint();
        } else if (s.meterL > 0.001f || s.meterR > 0.001f) {
            s.meterL *= 0.8f; s.meterR *= 0.8f;
            if (s.playing) s.playing = false;
            repaint();
        }
    }

    private void closePlayer() {
        if (player != null) { player.close(); player = null; }
        s.playing = false;
        s.meterL = s.meterR = 0;
    }

    // ---------- Handing the stems on ----------

    private void sendToMasterRoom() {
        if (!s.separated()) return;
        File[] stems = s.stemFiles.clone();
        String title = s.title;
        File dir = s.outDir;
        s.notice = "Making the ZIP…"; s.noticeBad = false;
        w.refresh();
        new SwingWorker<File, Void>() {
            @Override protected File doInBackground() throws Exception { return Export.zip(stems, dir, title); }
            @Override protected void done() {
                try {
                    File zip = get();
                    s.notice = "Saved " + zip.getName() + " (" + Export.size(zip.length()) + "). In Master Room, press Open stems and pick it.";
                    s.noticeBad = false;
                    Places.reveal(zip);
                } catch (Exception e) {
                    s.notice = "Could not make the ZIP: " + message(e);
                    s.noticeBad = true;
                }
                w.refresh();
            }
        }.execute();
    }

    private void saveCopy() {
        if (!s.separated()) return;
        JFileChooser fc = new JFileChooser(PREFS.get("copyDir", Places.documents().getPath()));
        fc.setDialogTitle("Where should the six WAV files go?");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File to = fc.getSelectedFile();
        PREFS.put("copyDir", to.getPath());
        File[] stems = s.stemFiles.clone();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { Export.copy(stems, to); return null; }
            @Override protected void done() {
                try { get(); s.notice = "Copied the six WAV files to " + StudioWindow.shortPath(to.getPath()) + "."; s.noticeBad = false; Places.reveal(to); }
                catch (Exception e) { s.notice = "Could not copy them: " + message(e); s.noticeBad = true; }
                w.refresh();
            }
        }.execute();
    }

    // ---------- Small things ----------

    private static void ui(Runnable r) {
        SwingUtilities.invokeLater(() -> { r.run(); });
    }

    /** The engine's reason, without the runtime's source-file noise, short enough for the panel. */
    static String plain(String why) {
        String s = why.replace("AI runtime: ", "").replaceAll("[A-Z]:\\\\[^ ]*\\.(cc|cpp|h)\\(\\d+\\)\\S*", "").replaceAll("\\(caller: [^)]*\\)", "").replaceAll("Exception\\(\\d+\\) tid\\([0-9a-fA-F]+\\)", "").replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }

    static String message(Throwable e) {
        while (e.getCause() != null && (e.getMessage() == null || e instanceof java.util.concurrent.ExecutionException)) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
