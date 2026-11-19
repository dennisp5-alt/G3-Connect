package com.dennis.stemstudio.desktop;

import javax.swing.*;
import java.io.File;
import java.util.Arrays;

/** Stem Studio for Windows. "--screenshot out.png --state empty|ready|separating|done" draws a sample. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        int shot = Arrays.asList(args).indexOf("--screenshot");
        if (shot >= 0) {
            System.setProperty("java.awt.headless", System.getProperty("java.awt.headless", "true"));
            Theme.load();
            int st = Arrays.asList(args).indexOf("--state");
            String state = st >= 0 ? args[st + 1] : "done";
            File png = new File(args[shot + 1]);
            StudioWindow.screenshot(png, 1440, 860, state);
            System.out.println("SCREENSHOT " + png.getAbsolutePath());
            return;
        }
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        SwingUtilities.invokeLater(() -> {
            Theme.load();
            StudioWindow w = new StudioWindow();
            JFrame f = new JFrame("Stem Studio");
            f.setIconImages(AppIcon.images());
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setContentPane(w.root);
            f.setMinimumSize(new java.awt.Dimension(1200, 760));
            f.setSize(1440, 880);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
