package com.dennis.stemstudio.desktop;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.BooleanSupplier;

/**
 * The real window, driven as Dennis would: open a song, separate it, solo and mute, send to Master Room.
 * Pictures of each step are saved ("window-real-*.png"). Needs a display (the Windows runner has one).
 *
 *   java -cp classes;check com.dennis.stemstudio.desktop.WindowCheck song.wav outDir
 */
public final class WindowCheck {
    private WindowCheck() {}

    private static int fails;
    private static void ok(boolean c, String m) { if (!c) fails++; System.out.println((c ? "PASS " : "FAIL ") + "window: " + m); }

    public static void main(String[] a) throws Exception {
        File song = new File(a[0]), pics = new File(a.length > 1 ? a[1] : ".");
        pics.mkdirs();
        System.setProperty("stemstudio.prefs", "/com/dennis/stemstudio/check");
        Studio[] st = new Studio[1];
        JFrame[] fr = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.load();
            StudioWindow w = new StudioWindow();
            JFrame f = new JFrame("Stem Studio");
            f.setIconImages(AppIcon.images());
            f.setContentPane(w.root);
            f.setSize(1440, 880);
            f.setVisible(true);
            fr[0] = f;
            st[0] = new Studio(w, f);
        });
        Studio studio = st[0];
        Session s = studio.s;
        Thread.sleep(1500);
        shot(fr[0], new File(pics, "window-real-1-start.png"));
        ok(s.stage == Session.Stage.EMPTY, "opens on the start screen");

        SwingUtilities.invokeAndWait(() -> studio.open(song));
        waitFor(() -> s.stage == Session.Stage.READY || s.stage == Session.Stage.DONE, 60);
        ok(s.stage == Session.Stage.READY && s.mixPeaks != null && s.seconds > 1, "a song opens: " + s.title + ", " + Session.clock(s.seconds));
        Thread.sleep(300);
        shot(fr[0], new File(pics, "window-real-2-ready.png"));

        SwingUtilities.invokeAndWait(() -> studio.w.separate.fire());
        waitFor(() -> s.stage == Session.Stage.SEPARATING && s.progress > 0.3, 600);
        shot(fr[0], new File(pics, "window-real-3-separating.png"));
        ok(s.stage == Session.Stage.SEPARATING, "separating shows its progress (" + s.stageText + ")");
        waitFor(() -> s.stage == Session.Stage.DONE || (s.stage == Session.Stage.READY && !s.result.isEmpty()), 1200);
        ok(s.stage == Session.Stage.DONE, "six stems: " + s.result);
        if (s.stage != Session.Stage.DONE) { finish(fr[0]); return; }

        SwingUtilities.invokeAndWait(() -> { s.mute[5] = true; s.gainDb[2] = 2.5; s.at = 0.37; studio.w.lanes.changed.run(); studio.w.refresh(); });
        Thread.sleep(300);
        shot(fr[0], new File(pics, "window-real-4-stems.png"));

        SwingUtilities.invokeAndWait(() -> studio.w.toMasterRoom.fire());
        waitFor(() -> s.notice.startsWith("Saved") || s.noticeBad, 120);
        ok(s.notice.startsWith("Saved") && new File(s.outDir, Places.safe(s.title) + " Stems.zip").isFile(), "Send to Master Room makes the ZIP: " + s.notice);
        Thread.sleep(300);
        shot(fr[0], new File(pics, "window-real-5-sent.png"));
        finish(fr[0]);
    }

    private static void finish(JFrame f) throws Exception {
        SwingUtilities.invokeAndWait(f::dispose);
        if (fails > 0) { System.out.println(fails + " window check(s) failed"); System.exit(1); }
        System.out.println("WINDOW_CHECK_PASS");
        System.exit(0);
    }

    private static void waitFor(BooleanSupplier c, int seconds) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        boolean[] d = new boolean[1];
        while (System.currentTimeMillis() < end) {
            SwingUtilities.invokeAndWait(() -> d[0] = c.getAsBoolean());
            if (d[0]) return;
            Thread.sleep(150);
        }
    }

    private static void shot(JFrame f, File png) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            Container c = f.getContentPane();
            img[0] = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            c.printAll(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", png);
    }
}
