package com.dennis.stemstudio.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.DoubleConsumer;

/**
 * The song and its six stems as lanes, one colour each. On the left the original mix splits into the six
 * (the icon's shape), so it reads at a glance as "this became these". Each lane has solo, mute and a level;
 * clicking a waveform moves the playhead.
 */
final class LanesView extends JComponent {
    static final int HEAD = 262, GUTTER = 46, TOP = 66, RULER = 24, MIX = 76, GAP = 8, PAD = 20;
    static final double MIN_DB = -24, MAX_DB = 6;

    private final Session s;
    Runnable changed = () -> {};
    DoubleConsumer seek = x -> {};
    private int dragFader = -1;
    private boolean dragHead;
    private int hoverLane = -1;

    LanesView(Session s) {
        this.s = s;
        setOpaque(false);
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { press(e); }
            @Override public void mouseDragged(MouseEvent e) { drag(e); }
            @Override public void mouseReleased(MouseEvent e) { dragFader = -1; dragHead = false; }
            @Override public void mouseMoved(MouseEvent e) {
                int l = laneAt(e.getY());
                if (l != hoverLane) { hoverLane = l; repaint(); }
                setCursor(Cursor.getPredefinedCursor(overControl(e) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
            @Override public void mouseExited(MouseEvent e) { hoverLane = -1; repaint(); }
            @Override public void mouseClicked(MouseEvent e) {
                int l = laneAt(e.getY());
                if (e.getClickCount() == 2 && l >= 0 && inFader(l, e.getX(), e.getY())) { s.gainDb[l] = 0; changed.run(); repaint(); }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    // ---------- Geometry ----------

    private int laneH() {
        int room = getHeight() - TOP - RULER - MIX - GAP - PAD - 6 * GAP;
        return Math.max(56, Math.min(104, room / 6));
    }
    private int mixY() { return TOP + RULER; }
    private int laneY(int i) { return mixY() + MIX + GAP + i * (laneH() + GAP); }
    private int waveX() { return PAD + HEAD; }
    private int waveW() { return getWidth() - waveX() - PAD; }

    private int laneAt(int y) {
        for (int i = 0; i < 6; i++) if (y >= laneY(i) && y < laneY(i) + laneH()) return i;
        return -1;
    }

    private Rectangle soloBox(int i) { int y = laneY(i) + laneH() - 34; return new Rectangle(PAD + GUTTER + 14, y, 30, 24); }
    private Rectangle muteBox(int i) { Rectangle r = soloBox(i); r.x += 36; return r; }
    private Rectangle faderBox(int i) { Rectangle r = muteBox(i); return new Rectangle(r.x + 44, r.y, HEAD - GUTTER - 14 - 80 - 44 - 8, 24); }

    private boolean inFader(int i, int x, int y) { Rectangle f = faderBox(i); f.grow(4, 6); return f.contains(x, y); }

    private boolean overControl(MouseEvent e) {
        int l = laneAt(e.getY());
        if (!s.separated()) return false;
        if (l >= 0 && (soloBox(l).contains(e.getPoint()) || muteBox(l).contains(e.getPoint()) || inFader(l, e.getX(), e.getY()))) return true;
        return e.getX() >= waveX() && e.getY() >= mixY() && e.getY() < laneY(5) + laneH();
    }

    // ---------- Mouse ----------

    private void press(MouseEvent e) {
        if (s.stage == Session.Stage.EMPTY) return;
        int l = laneAt(e.getY());
        if (s.separated() && l >= 0) {
            if (soloBox(l).contains(e.getPoint())) { s.solo[l] = !s.solo[l]; changed.run(); repaint(); return; }
            if (muteBox(l).contains(e.getPoint())) { s.mute[l] = !s.mute[l]; changed.run(); repaint(); return; }
            if (inFader(l, e.getX(), e.getY())) { dragFader = l; drag(e); return; }
        }
        if (e.getX() >= waveX() && e.getY() >= mixY() && e.getY() < laneY(5) + laneH()) { dragHead = true; drag(e); }
    }

    private void drag(MouseEvent e) {
        if (dragFader >= 0) {
            Rectangle f = faderBox(dragFader);
            double t = Math.max(0, Math.min(1, (e.getX() - f.x) / (double) f.width));
            double db = MIN_DB + t * (MAX_DB - MIN_DB);
            s.gainDb[dragFader] = Math.round(db * 2) / 2.0;
            changed.run();
            repaint();
        } else if (dragHead) {
            double t = Math.max(0, Math.min(1, (e.getX() - waveX()) / (double) waveW()));
            seek.accept(t);
        }
    }

    // ---------- Painting ----------

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = Ui.smooth(g0);
        int w = getWidth(), h = getHeight();
        g.setColor(Theme.PANEL);
        g.fill(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
        g.setColor(Theme.HAIR);
        g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 18, 18));
        if (s.stage == Session.Stage.EMPTY) { paintEmpty(g, w, h); g.dispose(); return; }
        paintTitle(g);
        paintRuler(g);
        paintSplit(g);
        paintMix(g);
        for (int i = 0; i < 6; i++) paintLane(g, i);
        paintPlayhead(g);
        g.dispose();
    }

    private void paintTitle(Graphics2D g) {
        g.setFont(Theme.display(23f));
        g.setColor(Theme.TEXT);
        int maxTitle = getWidth() - PAD * 2 - 360;
        Ui.text(g, s.title, PAD, 40, maxTitle);
        int x = PAD + Math.min(maxTitle, g.getFontMetrics().stringWidth(s.title)) + 18;
        g.setFont(Theme.body(13f));
        for (String fact : new String[]{Session.clock(s.seconds), s.rate / 1000.0 + " kHz", s.bits > 0 ? s.bits + "-bit" : null}) {
            if (fact == null) continue;
            String f = fact.replace(".0 kHz", " kHz");
            int fw = g.getFontMetrics().stringWidth(f) + 18;
            g.setColor(Theme.RAISED);
            g.fill(new RoundRectangle2D.Float(x, 22, fw, 24, 12, 12));
            g.setColor(Theme.MID);
            g.drawString(f, x + 9, 39);
            x += fw + 6;
        }
        // Right: what the lanes hold now.
        g.setFont(Theme.body(13f));
        g.setColor(Theme.DIM);
        String right = switch (s.stage) {
            case READY -> "Separate the song to fill the six lanes";
            case SEPARATING -> "Stems appear as each part of the song is done";
            default -> s.anySolo() ? "Solo on: only soloed stems are heard" : "Click a lane to move the playhead. S solos, M mutes.";
        };
        g.drawString(right, getWidth() - PAD - g.getFontMetrics().stringWidth(right), 39);
    }

    private void paintRuler(Graphics2D g) {
        int x0 = waveX(), ww = waveW(), y = TOP + RULER - 8;
        if (s.seconds <= 0) return;
        double step = s.seconds > 480 ? 60 : s.seconds > 180 ? 30 : s.seconds > 60 ? 15 : 5;
        g.setFont(Theme.body(11.5f));
        for (double t = 0; t <= s.seconds + 0.01; t += step) {
            float x = (float) (x0 + ww * t / s.seconds);
            g.setColor(Theme.LINE);
            g.draw(new Line2D.Float(x, y + 3, x, y + 8));
            g.setColor(Theme.DIM);
            String label = Session.clock(t);
            float lx = Math.min(x + 4, x0 + ww - g.getFontMetrics().stringWidth(label));
            if (t == 0 || x + 4 + g.getFontMetrics().stringWidth(label) < x0 + ww) g.drawString(label, lx, y + 2);
        }
    }

    /** The split: from the mix lane, six strands to the six lanes. */
    private void paintSplit(Graphics2D g) {
        float sx = PAD + 18, sy = mixY() + MIX / 2f, ex = PAD + GUTTER + 4;
        boolean on = s.stage != Session.Stage.READY;
        g.setColor(Theme.alpha(Theme.TEXT, on ? 230 : 120));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(sx, sy, ex, sy));
        for (int i = 5; i >= 0; i--) {
            float ty = laneY(i) + laneH() / 2f;
            Color c = Theme.STEM[i];
            double fill = s.stage == Session.Stage.SEPARATING ? s.progress : s.separated() ? 1 : 0;
            Color stroke = fill > 0 ? (s.separated() && !s.heard(i) ? Theme.LINE : c) : Theme.LINE;
            g.setColor(stroke);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new CubicCurve2D.Float(sx, sy, sx, sy + (ty - sy) * 0.55f, sx + 2, ty, ex, ty));
        }
        g.setColor(Theme.TEXT);
        g.fill(new Ellipse2D.Float(sx - 4, sy - 4, 8, 8));
    }

    private void paintMix(Graphics2D g) {
        int y = mixY();
        g.setColor(Theme.BG);
        g.fill(new RoundRectangle2D.Float(waveX(), y, waveW(), MIX, 12, 12));
        g.setFont(Theme.strong(14.5f));
        g.setColor(Theme.TEXT);
        g.drawString("Original mix", PAD + GUTTER + 14, y + 30);
        g.setFont(Theme.body(12.5f));
        g.setColor(Theme.DIM);
        g.drawString(s.separated() ? "All six stems together" : "The song as it came in", PAD + GUTTER + 14, y + 50);
        Color c = new Color(0xC9D3DD);
        bars(g, s.mixPeaks, waveX(), y, waveW(), MIX, c, s.stage == Session.Stage.SEPARATING ? -1 : 1, s.separated() ? 0.55f : 0.85f);
    }

    private void paintLane(Graphics2D g, int i) {
        int y = laneY(i), lh = laneH();
        Color c = Theme.STEM[i];
        boolean done = s.separated(), heard = !done || s.heard(i);
        // Header.
        if (hoverLane == i && done) {
            g.setColor(Theme.alpha(Theme.HOVER, 120));
            g.fill(new RoundRectangle2D.Float(PAD + GUTTER, y, HEAD - GUTTER - 8, lh, 12, 12));
        }
        g.setColor(done ? (heard ? c : Theme.alpha(c, 90)) : Theme.alpha(c, s.stage == Session.Stage.SEPARATING ? 200 : 110));
        g.fill(new RoundRectangle2D.Float(PAD + GUTTER + 4, y + 8, 4, lh - 16, 4, 4));
        g.setFont(Theme.strong(15f));
        g.setColor(heard ? Theme.TEXT : Theme.DIM);
        g.drawString(Theme.STEMS[i], PAD + GUTTER + 18, y + 26);
        if (done) {
            String level = s.mute[i] && !s.solo[i] ? "Muted" : s.gainDb[i] == 0 ? "0 dB" : String.format("%+.1f dB", s.gainDb[i]);
            g.setFont(Theme.body(12.5f));
            g.setColor(Theme.DIM);
            g.drawString(level, PAD + HEAD - 14 - g.getFontMetrics().stringWidth(level), y + 26);
            if (lh >= 64) { toggle(g, soloBox(i), "S", s.solo[i], new Color(0xFFD25A)); toggle(g, muteBox(i), "M", s.mute[i], Theme.WARN); fader(g, i, c, heard); }
        } else {
            g.setFont(Theme.body(12.5f));
            g.setColor(Theme.DIM);
            g.drawString(s.stage == Session.Stage.SEPARATING ? (s.progress >= 1 ? "Finishing" : "Separating") : "Waiting", PAD + GUTTER + 18, y + 46);
        }
        // Waveform.
        g.setColor(Theme.BG);
        g.fill(new RoundRectangle2D.Float(waveX(), y, waveW(), lh, 12, 12));
        if (s.stemPeaks[i] != null && s.stage != Session.Stage.READY) {
            double upTo = s.stage == Session.Stage.SEPARATING ? s.progress : 1;
            Color draw = heard ? c : new Color(0x47505B);
            bars(g, s.stemPeaks[i], waveX(), y, waveW(), lh, draw, upTo, 1f);
        } else {
            g.setColor(Theme.HAIR);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{3, 5}, 0));
            g.draw(new Line2D.Float(waveX() + 12, y + lh / 2f, waveX() + waveW() - 12, y + lh / 2f));
        }
        if (s.stage == Session.Stage.SEPARATING && s.progress < 1) {
            // The leading edge: where the separation has got to.
            float x = (float) (waveX() + waveW() * s.progress);
            g.setPaint(new GradientPaint(x - 40, 0, Theme.alpha(c, 0), x, 0, Theme.alpha(c, 70)));
            g.fill(new Rectangle.Float(Math.max(waveX(), x - 40), y + 2, Math.min(40, x - waveX()), lh - 4));
            g.setColor(Theme.alpha(c, 220));
            g.fill(new Rectangle.Float(x - 1, y + 4, 2, lh - 8));
        }
    }

    private void toggle(Graphics2D g, Rectangle r, String label, boolean on, Color onColor) {
        g.setColor(on ? onColor : Theme.RAISED);
        g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));
        if (!on) { g.setColor(Theme.LINE); g.draw(new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 8, 8)); }
        g.setFont(Theme.strong(12.5f));
        g.setColor(on ? new Color(0x16120A) : Theme.MID);
        Ui.centred(g, label, r.x + r.width / 2f, r.y + 16.5f);
    }

    private void fader(Graphics2D g, int i, Color c, boolean heard) {
        Rectangle f = faderBox(i);
        float cy = f.y + f.height / 2f;
        double t = (s.gainDb[i] - MIN_DB) / (MAX_DB - MIN_DB), zero = (0 - MIN_DB) / (MAX_DB - MIN_DB);
        g.setColor(Theme.LINE);
        g.fill(new RoundRectangle2D.Float(f.x, cy - 2, f.width, 4, 4, 4));
        float kx = (float) (f.x + f.width * t);
        g.setColor(heard ? c : Theme.DIM);
        g.fill(new RoundRectangle2D.Float(f.x, cy - 2, kx - f.x, 4, 4, 4));
        float zx = (float) (f.x + f.width * zero);
        g.setColor(Theme.MID);
        g.fill(new Rectangle.Float(zx - 0.5f, cy - 6, 1, 12));
        g.setColor(Theme.TEXT);
        g.fill(new Ellipse2D.Float(kx - 7, cy - 7, 14, 14));
        g.setColor(heard ? c : Theme.DIM);
        g.fill(new Ellipse2D.Float(kx - 3, cy - 3, 6, 6));
    }

    /**
     * A waveform as rounded bars mirrored about the middle. Left of the playhead is full colour, the rest a
     * little quieter. upTo &lt; 1 draws only that much (the separation so far); -1 draws all, unplayed.
     */
    private void bars(Graphics2D g, float[] peaks, int x0, int y0, int w, int h, Color c, double upTo, float strength) {
        if (peaks == null || peaks.length == 0) return;
        float mid = y0 + h / 2f, room = h / 2f - 7;
        float play = (float) (x0 + w * s.at);
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Float(x0, y0, w, h, 12, 12));
        int step = 3, last = upTo < 0 ? x0 + w : (int) (x0 + w * Math.min(1, upTo));
        for (int x = x0 + 6; x < x0 + w - 6; x += step) {
            if (x > last) break;
            int a = (int) ((long) (x - x0) * peaks.length / w), b = (int) Math.max(a + 1, (long) (x - x0 + step) * peaks.length / w);
            float p = 0;
            for (int k = a; k < Math.min(b, peaks.length); k++) p = Math.max(p, peaks[k]);
            float bh = Math.max(1.2f, (float) Math.pow(p, 0.8) * room);
            boolean played = upTo >= 0 && s.at > 0 && x <= play;
            Color col = Theme.alpha(c, (int) (255 * strength * (played ? 1 : upTo < 0 ? 0.55 : 0.72)));
            g.setColor(col);
            g.fill(new RoundRectangle2D.Float(x, mid - bh, 2, bh * 2, 2, 2));
        }
        g.setClip(clip);
    }

    private void paintPlayhead(Graphics2D g) {
        if (!s.separated() || s.at <= 0) return;
        float x = (float) (waveX() + waveW() * s.at);
        int top = mixY() - 4, bottom = laneY(5) + laneH();
        g.setColor(Theme.TEXT);
        g.fill(new Rectangle.Float(x - 1, top, 2, bottom - top));
        String t = Session.clock(s.at * s.seconds);
        g.setFont(Theme.medium(11.5f));
        int tw = g.getFontMetrics().stringWidth(t) + 14;
        float bx = Math.max(waveX(), Math.min(x - tw / 2f, waveX() + waveW() - tw));
        g.fill(new RoundRectangle2D.Float(bx, top - 20, tw, 19, 10, 10));
        g.setColor(Theme.BG);
        g.drawString(t, bx + 7, top - 6);
    }

    // ---------- Before a song: what this is for ----------

    private void paintEmpty(Graphics2D g, int w, int h) {
        float cx = w / 2f, cy = h / 2f - 60, size = 210;
        // The split, large: a waveform comes in and leaves as six coloured strands.
        float sx = cx - size * 0.10f, ex = cx + size * 0.55f;
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int k = 5; k >= 0; k--) {
            float ty = cy - size * 0.32f + size * 0.64f * k / 5;
            g.setPaint(new GradientPaint(sx, cy, Theme.mix(Theme.STEM[k], Color.WHITE, 0.5), ex, ty, Theme.STEM[k]));
            g.draw(new CubicCurve2D.Float(sx, cy, sx + size * 0.25f, cy, ex - size * 0.3f, ty, ex, ty));
        }
        float[] heights = {0.18f, 0.34f, 0.55f, 0.42f, 0.66f, 0.38f, 0.24f};
        g.setColor(new Color(0xF4F7FA));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < heights.length; i++) {
            float x = cx - size * 0.62f + i * size * 0.07f, hh = size * heights[i] / 2;
            g.draw(new Line2D.Float(x, cy - hh, x, cy + hh));
        }
        g.fill(new Ellipse2D.Float(sx - 6, cy - 6, 12, 12));
        // Names at the strands' ends.
        g.setFont(Theme.medium(13f));
        for (int k = 0; k < 6; k++) {
            float ty = cy - size * 0.32f + size * 0.64f * k / 5;
            g.setColor(Theme.STEM[k]);
            g.drawString(Theme.STEMS[k], ex + 14, ty + 5);
        }
        g.setFont(Theme.display(26f));
        g.setColor(Theme.TEXT);
        Ui.centred(g, "Six stems from any song", cx, cy + size * 0.5f + 46);
        g.setFont(Theme.body(14.5f));
        g.setColor(Theme.MID);
        Ui.centred(g, "Vocals, drums, bass, guitar, piano and everything else, each in its own lane.", cx, cy + size * 0.5f + 76);
        Ui.centred(g, "Drop a song anywhere in this window, or choose one on the left.", cx, cy + size * 0.5f + 98);
    }
}
