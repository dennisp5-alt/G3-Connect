package com.dennis.stemstudio.desktop;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Stem Studio's look. One quiet slate studio, and six stem colours that carry the identity: each stem keeps
 * its colour everywhere (icon, lanes, meters, exports), so the eye finds the bass without reading.
 */
final class Theme {
    private Theme() {}

    // Surfaces, darkest to lightest.
    static final Color BG = new Color(0x0F1319);
    static final Color PANEL = new Color(0x151B23);
    static final Color RAISED = new Color(0x1C242E);
    static final Color HOVER = new Color(0x232C38);
    static final Color LINE = new Color(0x29333F);
    static final Color HAIR = new Color(0x1F2731);
    // Text.
    static final Color TEXT = new Color(0xEAF0F5);
    static final Color MID = new Color(0xA5B1BF);
    static final Color DIM = new Color(0x6B7785);
    // The brand: the phone app's teal, deepened for buttons.
    static final Color BRAND = new Color(0x46D3CB);
    static final Color BRAND_HOVER = new Color(0x62DED7);
    static final Color ON_BRAND = new Color(0x062221);
    static final Color WARN = new Color(0xFF8A5B);

    /** Display order: the four everyone knows, the two extra, then everything else. */
    static final String[] STEMS = {"Vocals", "Drums", "Bass", "Guitar", "Piano", "Other"};
    static final Color[] STEM = {
        new Color(0xFF6E8E), // Vocals, coral rose
        new Color(0xFFB54A), // Drums, amber
        new Color(0x9C8CFF), // Bass, violet
        new Color(0x5BD68B), // Guitar, green
        new Color(0x4DB6FF), // Piano, sky
        new Color(0xB8C2CE), // Other, pewter
    };

    static Color stem(String name) {
        for (int i = 0; i < STEMS.length; i++) if (STEMS[i].equals(name)) return STEM[i];
        return STEM[5];
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }

    static Color mix(Color a, Color b, double t) {
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    // ---------- Type: Inter for the interface, Inter Display for names and big numbers ----------

    private static final Map<String, Font> BASE = new HashMap<>();
    private static final Map<String, Font> SIZED = new HashMap<>();

    static void load() {
        for (String n : new String[]{"Inter-Regular", "Inter-Medium", "Inter-SemiBold", "InterDisplay-SemiBold", "InterDisplay-Bold"}) {
            Font f = null;
            try (InputStream in = Theme.class.getResourceAsStream("/fonts/" + n + ".otf")) {
                if (in != null) f = Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (IOException | FontFormatException ignored) { }
            if (f == null) {
                // Running from the source tree (screenshots, checks).
                File local = new File("desktop/fonts/" + n + ".otf");
                try { if (local.isFile()) f = Font.createFont(Font.TRUETYPE_FONT, local); } catch (IOException | FontFormatException ignored) { }
            }
            if (f == null) f = new Font(Font.SANS_SERIF, n.contains("Bold") || n.contains("SemiBold") ? Font.BOLD : Font.PLAIN, 12);
            else GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            BASE.put(n, f);
        }
    }

    private static Font font(String name, float size) {
        return SIZED.computeIfAbsent(name + size, k -> BASE.get(name).deriveFont(size));
    }

    static Font body(float size) { return font("Inter-Regular", size); }
    static Font medium(float size) { return font("Inter-Medium", size); }
    static Font strong(float size) { return font("Inter-SemiBold", size); }
    static Font display(float size) { return font("InterDisplay-SemiBold", size); }
    static Font heavy(float size) { return font("InterDisplay-Bold", size); }
}
