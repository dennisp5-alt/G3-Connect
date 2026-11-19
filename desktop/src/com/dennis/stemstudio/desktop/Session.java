package com.dennis.stemstudio.desktop;

import java.io.File;

/** What is on screen: the song, where the separation is, and how each stem is being heard. */
final class Session {
    enum Stage { EMPTY, READY, SEPARATING, DONE }

    Stage stage = Stage.EMPTY;

    // The song.
    File song;
    String fileName = "", folder = "";   // shown as the person sees them (Windows paths)
    File outDir;                          // where the stems are
    String title = "";
    double seconds;
    int rate, bits;
    float[] mixPeaks;            // 0..1, one per column of the overview

    // Separation.
    double progress;             // 0..1 of the whole job
    int section, sections;       // AI sections done / total
    String stageText = "";       // what is happening now, in words
    double secondsLeft = Double.NaN;
    String device = "";          // the graphics card's name, or "" when there is none
    boolean onGpu;               // there is a graphics card to use
    boolean engineReady = true;
    int cores = Runtime.getRuntime().availableProcessors();
    String estimate = "";        // how long separating this song should take, in words
    String result = "";          // how the last run went, in words
    boolean resultBad;
    String notice = "";          // after saving or sending the stems
    boolean noticeBad;
    String format = "";          // "MP3", "FLAC" when the song is not a WAV

    // The six stems, in Theme.STEMS order.
    final float[][] stemPeaks = new float[6][];
    final File[] stemFiles = new File[6];
    final double[] gainDb = new double[6];
    final boolean[] mute = new boolean[6], solo = new boolean[6];

    // Listening.
    boolean playing;
    double at;                   // playhead, 0..1
    float meterL, meterR;        // output level, 0..1 (shown on the transport)

    boolean anySolo() { for (boolean s : solo) if (s) return true; return false; }

    /** Whether a stem is heard now (a solo silences the rest). */
    boolean heard(int i) { return anySolo() ? solo[i] : !mute[i]; }

    boolean separated() { return stage == Stage.DONE; }

    /** The song's file name and folder, split on either kind of slash. */
    void setSong(File f) {
        song = f;
        String p = f.getPath();
        int cut = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
        fileName = cut < 0 ? p : p.substring(cut + 1);
        folder = cut < 0 ? "" : p.substring(0, cut);
    }

    /** "45 seconds", "about 2 minutes" style lengths of time. */
    static String duration(double sec) {
        if (sec < 55) return Math.max(5, Math.round(sec / 5) * 5) + " seconds";
        if (sec < 90) return "1 minute";
        return Math.round(sec / 60) + " minutes";
    }

    static String clock(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) seconds = 0;
        int s = (int) Math.round(seconds);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}
