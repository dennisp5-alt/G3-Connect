package com.dennis.stemstudio.desktop;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.IOException;
import java.util.Random;

/**
 * Plays the six stems together, live, from their files: each with its level, solo and mute, changed while
 * playing with a short ramp so nothing clicks. Output is 16-bit with dither at the stems' rate.
 */
final class StemPlayer implements AutoCloseable {
    static final int BLOCK = 2048;

    private final WavFile[] in = new WavFile[6];
    final int rate;
    final long frames;
    private final float[] want = new float[6], now = new float[6];
    private volatile long jump = -1;
    private volatile boolean playing, closed;
    private volatile long pos;            // frame at the read head
    private long written;                 // frames given to the line since it was last flushed
    private long base;                    // frame the line's count started from
    private SourceDataLine line;
    private Thread thread;
    volatile float levelL, levelR;        // output peak, 0..1, falling slowly
    Runnable ended = () -> {};

    StemPlayer(File[] stems) throws IOException {
        long n = Long.MAX_VALUE;
        int r = 0;
        try {
            for (int i = 0; i < 6; i++) {
                in[i] = new WavFile(stems[i]);
                n = Math.min(n, in[i].frames);
                r = in[i].rate;
            }
        } catch (IOException e) {
            close();
            throw e;
        }
        rate = r;
        frames = n;
        for (int i = 0; i < 6; i++) { want[i] = 1; now[i] = 1; }
    }

    /** Linear gain per stem (0 for muted or not soloed), applied from the next block. */
    void setGains(float[] g) { System.arraycopy(g, 0, want, 0, 6); }

    boolean isPlaying() { return playing; }

    /** Where the listener is, 0..1 (what has come out of the speakers, not what was read). */
    double fraction() {
        SourceDataLine l = line;
        long heard = l != null && playing ? base + l.getLongFramePosition() : pos;
        return frames == 0 ? 0 : Math.max(0, Math.min(1, heard / (double) frames));
    }

    void seek(double fraction) {
        long f = (long) (Math.max(0, Math.min(1, fraction)) * frames);
        if (playing) jump = f; else { pos = f; base = f; written = 0; }
    }

    synchronized void play() throws LineUnavailableException {
        if (playing || closed) return;
        if (pos >= frames - BLOCK) { pos = 0; }
        if (line == null) {
            AudioFormat fmt = new AudioFormat(rate, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, BLOCK * 4 * 4);
        }
        line.flush();
        base = pos; written = 0;
        // The line's frame count restarts from where it is now.
        base -= line.getLongFramePosition();
        playing = true;
        line.start();
        thread = new Thread(this::run, "stems");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void pause() {
        if (!playing) return;
        playing = false;
        try { if (thread != null) thread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (line != null) {
            long heard = base + line.getLongFramePosition();
            line.stop();
            line.flush();
            pos = Math.max(0, Math.min(frames, heard));
        }
    }

    private void run() {
        float[][] l = new float[6][BLOCK], r = new float[6][BLOCK];
        float[] ml = new float[BLOCK], mr = new float[BLOCK];
        byte[] out = new byte[BLOCK * 4];
        Random rnd = new Random();
        long at = pos;
        try {
            for (WavFile w : in) w.seek(at);
            while (playing && !closed) {
                long j = jump;
                if (j >= 0) {
                    jump = -1;
                    at = j;
                    for (WavFile w : in) w.seek(at);
                    line.flush();
                    base = at - line.getLongFramePosition();
                    written = 0;
                }
                int n = (int) Math.min(BLOCK, frames - at);
                if (n <= 0) break;
                java.util.Arrays.fill(ml, 0, n, 0f);
                java.util.Arrays.fill(mr, 0, n, 0f);
                for (int s = 0; s < 6; s++) {
                    float from = now[s], to = want[s];
                    if (from == 0 && to == 0) { in[s].seek(at + n); continue; }
                    in[s].read(l[s], r[s], n);
                    float step = (to - from) / n;
                    for (int i = 0; i < n; i++) { float g = from + step * i; ml[i] += l[s][i] * g; mr[i] += r[s][i] * g; }
                    now[s] = to;
                }
                float pl = 0, pr = 0;
                for (int i = 0; i < n; i++) {
                    float a = Math.max(-1, Math.min(1, ml[i])), b = Math.max(-1, Math.min(1, mr[i]));
                    pl = Math.max(pl, Math.abs(a)); pr = Math.max(pr, Math.abs(b));
                    int x = (int) Math.round(a * 32767 + rnd.nextFloat() - rnd.nextFloat()), y = (int) Math.round(b * 32767 + rnd.nextFloat() - rnd.nextFloat());
                    x = Math.max(-32768, Math.min(32767, x)); y = Math.max(-32768, Math.min(32767, y));
                    out[i * 4] = (byte) x; out[i * 4 + 1] = (byte) (x >> 8); out[i * 4 + 2] = (byte) y; out[i * 4 + 3] = (byte) (y >> 8);
                }
                levelL = Math.max(pl, levelL * 0.85f); levelR = Math.max(pr, levelR * 0.85f);
                line.write(out, 0, n * 4);
                written += n;
                at += n;
                pos = at;
            }
        } catch (IOException e) {
            playing = false;
        }
        if (at >= frames && playing) {
            line.drain();
            playing = false;
            pos = 0;
            ended.run();
        }
    }

    @Override public void close() {
        closed = true;
        pause();
        if (line != null) { line.close(); line = null; }
        for (WavFile w : in) try { if (w != null) w.close(); } catch (IOException ignored) { }
    }

    /** One block of the mix as it would be heard, for the checks (no sound card needed). */
    float[][] mixAt(long frame, int n) throws IOException {
        float[][] o = new float[2][n];
        float[] l = new float[n], r = new float[n];
        for (int s = 0; s < 6; s++) {
            in[s].seek(frame);
            int got = in[s].read(l, r, n);
            for (int i = 0; i < got; i++) { o[0][i] += l[i] * want[s]; o[1][i] += r[i] * want[s]; }
        }
        return o;
    }
}
