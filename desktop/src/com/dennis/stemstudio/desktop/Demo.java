package com.dennis.stemstudio.desktop;

import java.io.File;
import java.util.Random;

/** Sample sessions for screenshots only (never shown in the app). */
final class Demo {
    private Demo() {}

    static final int N = 1600;

    static void fill(Session s, String state) {
        s.device = "NVIDIA GeForce RTX 3050 Laptop GPU";
        s.onGpu = true;
        s.cores = 12;
        if (state.equals("empty")) return;
        s.setSong(new File("C:\\Users\\denni\\Music\\Suno\\Dusty Road (final mix).wav"));
        s.outDir = new File("C:\\Users\\denni\\Documents\\Stem Studio\\Dusty Road");
        s.title = "Dusty Road";
        s.seconds = 222;
        s.rate = 48000;
        s.bits = 24;
        float[][] stems = stems();
        s.mixPeaks = new float[N];
        for (int i = 0; i < N; i++) {
            double sum = 0;
            for (float[] st : stems) sum += st[i] * st[i];
            s.mixPeaks[i] = (float) Math.min(1, Math.sqrt(sum) * 0.82);
        }
        s.stage = Session.Stage.READY;
        s.estimate = "About 1 minute for this song";
        if (state.equals("ready")) return;
        for (int i = 0; i < 6; i++) s.stemPeaks[i] = stems[i];
        if (state.equals("separating")) {
            s.stage = Session.Stage.SEPARATING;
            s.section = 17; s.sections = 38;
            s.progress = 0.45;
            s.stageText = "Part 17 of 38";
            s.secondsLeft = 26;
            return;
        }
        s.stage = Session.Stage.DONE;
        s.result = "Done in 34 seconds on the graphics card. The six stems add back up to the song.";
        s.at = 0.372;
        s.playing = true;
        s.solo[0] = false;
        s.mute[5] = true;
        s.gainDb[2] = 2.5;
        s.gainDb[4] = -4.0;
        s.meterL = 0.68f; s.meterR = 0.64f;
    }

    /** Six believable stems: sung phrases, a beat, a bass line, strummed chords, sparse piano, pads. */
    static float[][] stems() {
        float[][] out = new float[6][N];
        Random r = new Random(5);
        for (int i = 0; i < N; i++) {
            double t = 222.0 * i / N, beat = t / 0.5;
            boolean intro = t < 12, bridge = t > 150 && t < 172, outro = t > 210;
            // Vocals: phrases of about 4 s with gaps, syllables inside.
            double phrase = ((t - 12) % 6) < 4.2 && !intro && !outro ? 1 : 0;
            double syll = 0.55 + 0.45 * Math.abs(Math.sin(t * 7.3)) * (0.7 + 0.3 * r.nextDouble());
            out[0][i] = (float) (phrase * syll * (bridge ? 0.5 : t > 100 ? 0.85 : 0.72));
            // Drums: a hit on every beat, the back beat stronger, a fill before each section.
            double since = beat - Math.floor(beat);
            double hit = Math.exp(-since * 9) * ((int) beat % 2 == 1 ? 0.95 : 0.75);
            out[1][i] = (float) (intro && t < 6 ? 0.04 : (hit + 0.12 * r.nextDouble()) * (bridge ? 0.55 : 1));
            // Bass: notes held, two per bar.
            double note = 0.5 + 0.12 * Math.sin(Math.floor(t / 1.0) * 1.7);
            out[2][i] = (float) (intro ? 0.08 : note * (0.85 + 0.15 * Math.exp(-(t % 1.0) * 4)));
            // Guitar: strummed chords from the start.
            double strum = Math.exp(-((t % 0.5) * 5)) * 0.35 + 0.28;
            out[3][i] = (float) (strum * (outro ? 0.6 : 1) * (0.9 + 0.1 * r.nextDouble()));
            // Piano: only in the second half and the bridge.
            double pn = t > 96 ? 0.35 + 0.3 * Math.abs(Math.sin(t * 2.1)) : 0.03;
            out[4][i] = (float) (bridge ? 0.7 * Math.abs(Math.sin(t * 1.4)) + 0.2 : pn);
            // Other: soft pads and room.
            out[5][i] = (float) (0.12 + 0.08 * Math.sin(t / 9) + 0.04 * r.nextDouble() + (bridge ? 0.15 : 0));
        }
        return out;
    }
}
