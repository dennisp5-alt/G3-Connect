package com.dennis.stemstudio.desktop;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Reads WAV files a block at a time (PCM 16, 24, 32-bit or 32-bit float; mono is played as stereo). */
final class WavFile implements Closeable {
    final int format, channels, rate, bits, frameBytes;
    final long dataAt, frames;
    private final RandomAccessFile in;
    private byte[] raw = new byte[0];

    WavFile(File f) throws IOException {
        in = new RandomAccessFile(f, "r");
        try {
            byte[] head = new byte[12];
            in.readFully(head);
            if (!new String(head, 0, 4, "US-ASCII").equals("RIFF") || !new String(head, 8, 4, "US-ASCII").equals("WAVE"))
                throw new IOException("Not a WAV file");
            int fmt = 0, ch = 0, sr = 0, b = 0;
            long data = -1, length = 0;
            while (in.getFilePointer() + 8 <= in.length()) {
                byte[] c = new byte[8];
                in.readFully(c);
                String id = new String(c, 0, 4, "US-ASCII");
                long len = u32(c, 4);
                long start = in.getFilePointer();
                if (id.equals("fmt ")) {
                    byte[] f2 = new byte[(int) Math.min(len, 64)];
                    in.readFully(f2);
                    fmt = u16(f2, 0); ch = u16(f2, 2); sr = (int) u32(f2, 4); b = u16(f2, 14);
                    if (fmt == 0xFFFE && f2.length >= 26) fmt = u16(f2, 24);
                } else if (id.equals("data")) {
                    data = start;
                    length = Math.min(len, in.length() - start);
                    if (fmt != 0) break;
                }
                in.seek(start + len + (len & 1));
            }
            if (data < 0 || fmt == 0) throw new IOException("The WAV file has no audio in it");
            if (!((fmt == 1 && (b == 16 || b == 24 || b == 32)) || (fmt == 3 && b == 32))) throw new IOException("This kind of WAV needs converting first");
            if (ch < 1 || ch > 2) throw new IOException("Only mono and stereo WAV files");
            format = fmt; channels = ch; rate = sr; bits = b; frameBytes = ch * b / 8;
            dataAt = data; frames = length / frameBytes;
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    /** Whether the engine reads this file as it is (otherwise FFmpeg converts it first). */
    boolean engineReads() { return rate == 44100 || rate == 48000; }

    double seconds() { return frames / (double) rate; }

    void seek(long frame) throws IOException { in.seek(dataAt + Math.max(0, Math.min(frames, frame)) * frameBytes); }

    /** Reads up to n frames into l and r (from offset 0). Returns frames read; 0 at the end. */
    int read(float[] l, float[] r, int n) throws IOException {
        long left = (dataAt + frames * frameBytes - in.getFilePointer()) / frameBytes;
        n = (int) Math.max(0, Math.min(n, left));
        if (n == 0) return 0;
        int bytes = n * frameBytes;
        if (raw.length < bytes) raw = new byte[bytes];
        in.readFully(raw, 0, bytes);
        int bps = bits / 8;
        for (int i = 0; i < n; i++) {
            int p = i * frameBytes;
            float a = sample(raw, p, bps), b = channels == 2 ? sample(raw, p + bps, bps) : a;
            l[i] = a; r[i] = b;
        }
        return n;
    }

    private float sample(byte[] b, int p, int bps) {
        if (format == 3) return Float.intBitsToFloat((b[p] & 255) | (b[p + 1] & 255) << 8 | (b[p + 2] & 255) << 16 | b[p + 3] << 24);
        return switch (bps) {
            case 2 -> (short) ((b[p] & 255) | b[p + 1] << 8) / 32768f;
            case 3 -> ((b[p] & 255) | (b[p + 1] & 255) << 8 | b[p + 2] << 16) / 8388608f;
            default -> ((b[p] & 255) | (b[p + 1] & 255) << 8 | (b[p + 2] & 255) << 16 | b[p + 3] << 24) / 2147483648f;
        };
    }

    @Override public void close() throws IOException { in.close(); }

    /** The overview: the loudest sample in each of n columns, 0..1. */
    static float[] peaks(File f, int n) throws IOException {
        try (WavFile w = new WavFile(f)) {
            float[] out = new float[n];
            float[] l = new float[8192], r = new float[8192];
            long at = 0;
            w.seek(0);
            int got;
            while ((got = w.read(l, r, l.length)) > 0) {
                for (int i = 0; i < got; i++) {
                    int col = (int) Math.min(n - 1, (at + i) * n / Math.max(1, w.frames));
                    float v = Math.max(Math.abs(l[i]), Math.abs(r[i]));
                    if (v > out[col]) out[col] = v;
                }
                at += got;
            }
            return out;
        }
    }

    private static int u16(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8; }
    private static long u32(byte[] b, int p) { return (b[p] & 255L) | (b[p + 1] & 255L) << 8 | (b[p + 2] & 255L) << 16 | (b[p + 3] & 255L) << 24; }
}
