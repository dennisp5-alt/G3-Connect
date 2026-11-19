package com.dennis.stemstudio.desktop;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The split: one white wave comes in and leaves as six coloured strands, one per stem. Drawn rather than
 * stored, so every size is sharp: the window, the program, the installer and the phone's launcher
 * ("java ... AppIcon out.ico [preview.png] [android-res-dir]").
 */
public final class AppIcon {
    private AppIcon() {}

    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    static BufferedImage draw(int size) { return draw(size, true); }

    /** @param tile false for the phone's adaptive foreground (no tile, safe zone respected) */
    static BufferedImage draw(int size, boolean tile) {
        BufferedImage im = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = size;
        if (tile) {
            float inset = s * 0.04f, corner = s * 0.26f;
            g.setPaint(new GradientPaint(0, 0, new Color(0x1E2733), 0, s, new Color(0x0B0F14)));
            g.fill(new RoundRectangle2D.Float(inset, inset, s - 2 * inset, s - 2 * inset, corner, corner));
            if (size >= 32) {
                g.setColor(new Color(0x34404E));
                g.setStroke(new BasicStroke(Math.max(1f, s / 110f)));
                g.draw(new RoundRectangle2D.Float(inset + 0.5f, inset + 0.5f, s - 2 * inset - 1, s - 2 * inset - 1, corner, corner));
            }
        } else {
            // Adaptive icons crop to the middle 66%: draw into that.
            g.translate(s * 0.17, s * 0.17);
            s *= 0.66f;
        }
        int strands = size <= 16 ? 3 : size <= 24 ? 4 : 6;
        int[] pick = strands == 3 ? new int[]{0, 2, 4} : strands == 4 ? new int[]{0, 1, 2, 4} : new int[]{0, 1, 2, 3, 4, 5};
        float w = Math.max(1.5f, s * (size <= 24 ? 0.085f : 0.052f));
        float splitX = s * 0.40f, splitY = s * 0.5f, endX = s * 0.84f, top = s * 0.23f, bottom = s * 0.77f;
        // The six strands, drawn back to front so the vocal sits on top.
        for (int k = strands - 1; k >= 0; k--) {
            float y = strands == 1 ? splitY : top + (bottom - top) * k / (strands - 1);
            Color c = Theme.STEM[pick[k]];
            CubicCurve2D.Float curve = new CubicCurve2D.Float(splitX, splitY, splitX + s * 0.20f, splitY, endX - s * 0.22f, y, endX, y);
            g.setPaint(new GradientPaint(splitX, splitY, Theme.mix(c, Color.WHITE, 0.55), endX, y, c));
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(curve);
        }
        // The song coming in: a short white waveform, into the split.
        int bars = size <= 24 ? 3 : 5;
        float[] heights = bars == 3 ? new float[]{0.40f, 0.85f, 0.55f} : new float[]{0.30f, 0.62f, 1.0f, 0.70f, 0.42f};
        float from = s * 0.13f, gapTo = splitX - w * 1.9f, step = (gapTo - from) / (bars - 1), tall = s * 0.30f;
        g.setColor(new Color(0xF4F7FA));
        g.setStroke(new BasicStroke(w * (bars == 3 ? 0.80f : 0.62f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < bars; i++) {
            float x = from + step * i, h = tall * heights[i] / 2;
            g.draw(new java.awt.geom.Line2D.Float(x, splitY - h, x, splitY + h));
        }
        // The split point.
        float dot = w * 1.55f;
        g.fill(new java.awt.geom.Ellipse2D.Float(splitX - dot / 2, splitY - dot / 2, dot, dot));
        g.dispose();
        return im;
    }

    static List<Image> images() {
        List<Image> out = new ArrayList<>();
        for (int s : SIZES) out.add(draw(s));
        return out;
    }

    /** An .ico with every size as PNG (Windows Vista and later read PNG entries). */
    static void writeIco(File f) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : SIZES) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(draw(s), "png", b);
            pngs.add(b.toByteArray());
        }
        ByteBuffer head = ByteBuffer.allocate(6 + 16 * SIZES.length).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        int offset = 6 + 16 * SIZES.length;
        for (int i = 0; i < SIZES.length; i++) {
            int s = SIZES[i];
            head.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0)
                .putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(head.array());
            for (byte[] p : pngs) out.write(p);
        }
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        writeIco(new File(args[0]));
        if (args.length > 1) {
            int[] show = {16, 24, 32, 48, 64, 128, 256};
            int width = 20;
            for (int s : show) width += s + 20;
            BufferedImage sheet = new BufferedImage(width, 296, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(new Color(0xF3F3F3)); g.fillRect(0, 0, width, 148);
            g.setColor(new Color(0x1F1F1F)); g.fillRect(0, 148, width, 148);
            int x = 20;
            for (int s : show) {
                g.drawImage(draw(Math.min(s, 128)), x, 10, null);
                g.drawImage(draw(Math.min(s, 128)), x, 158, null);
                x += s + 20;
            }
            g.dispose();
            ImageIO.write(sheet, "png", new File(args[1]));
        }
        System.out.println("ICON " + new File(args[0]).getAbsolutePath());
    }
}
