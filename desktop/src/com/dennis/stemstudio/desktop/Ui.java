package com.dennis.stemstudio.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.IntConsumer;

/** The few controls Stem Studio is built from, all drawn in its own look (Swing's own are never shown). */
final class Ui {
    private Ui() {}

    static Graphics2D smooth(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** Draws text cut to fit, with an ellipsis. */
    static void text(Graphics2D g, String s, float x, float y, float width) {
        if (s == null) return;
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= width) { g.drawString(s, x, y); return; }
        String out = s;
        while (out.length() > 1 && fm.stringWidth(out + "…") > width) out = out.substring(0, out.length() - 1);
        g.drawString(out + "…", x, y);
    }

    static void centred(Graphics2D g, String s, float cx, float y) {
        g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2f, y);
    }

    /** Lines of text broken to a width. Returns the y after the last line. */
    static float wrap(Graphics2D g, String s, float x, float y, float width, float leading) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(trial) > width && line.length() > 0) {
                g.drawString(line.toString(), x, y);
                y += leading;
                line = new StringBuilder(word);
            } else line = new StringBuilder(trial);
        }
        if (line.length() > 0) { g.drawString(line.toString(), x, y); y += leading; }
        return y;
    }

    /** A clickable thing with hover, press and keyboard focus. */
    abstract static class Clickable extends JComponent {
        boolean hover, pressed;
        private ActionListener action;

        Clickable() {
            setFocusable(true);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { if (isEnabled()) { pressed = true; repaint(); } }
                @Override public void mouseReleased(MouseEvent e) {
                    boolean was = pressed;
                    pressed = false; repaint();
                    if (was && isEnabled() && contains(e.getPoint())) fire();
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (isEnabled() && (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER)) { fire(); e.consume(); }
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
        }

        /** Read out as a button by Windows' screen reader and accessibility tools. */
        @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
                @Override public javax.accessibility.AccessibleRole getAccessibleRole() { return javax.accessibility.AccessibleRole.PUSH_BUTTON; }
            };
            return accessibleContext;
        }

        void onClick(ActionListener a) { action = a; }
        void fire() { if (action != null) action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "click")); }

        @Override public void setEnabled(boolean on) {
            super.setEnabled(on);
            setCursor(Cursor.getPredefinedCursor(on ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }

        /** The keyboard focus ring, outside the shape. */
        void focusRing(Graphics2D g, float arc) {
            if (!isFocusOwner()) return;
            g.setColor(Theme.alpha(Theme.BRAND, 150));
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, arc + 2, arc + 2));
        }
    }

    enum Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    /** A button. Primary is the one thing to do next; there is at most one on screen. */
    static final class Btn extends Clickable {
        String text;
        Kind kind;
        String note;   // a second, smaller line under the text (primary only)
        Glyph glyph;

        Btn(String text, Kind kind) {
            this.text = text; this.kind = kind;
            setPreferredSize(new Dimension(160, kind == Kind.PRIMARY ? 52 : 38));
            getAccessibleContext().setAccessibleName(text);
        }

        void set(String text, Kind kind) {
            this.text = text; this.kind = kind;
            getAccessibleContext().setAccessibleName(text);
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int w = getWidth(), h = getHeight();
            float arc = kind == Kind.PRIMARY ? 14 : 11;
            boolean on = isEnabled();
            Color fill, ink, edge = null;
            switch (kind) {
                case PRIMARY -> {
                    fill = !on ? Theme.RAISED : pressed ? Theme.mix(Theme.BRAND, Color.BLACK, 0.12) : hover ? Theme.BRAND_HOVER : Theme.BRAND;
                    ink = on ? Theme.ON_BRAND : Theme.DIM;
                }
                case DANGER -> {
                    fill = pressed ? Theme.mix(Theme.RAISED, Theme.WARN, 0.25) : hover ? Theme.mix(Theme.RAISED, Theme.WARN, 0.15) : Theme.RAISED;
                    ink = Theme.WARN; edge = Theme.alpha(Theme.WARN, 110);
                }
                case GHOST -> {
                    fill = pressed ? Theme.RAISED : hover ? Theme.HOVER : new Color(0, 0, 0, 0);
                    ink = on ? Theme.MID : Theme.DIM;
                }
                default -> {
                    fill = !on ? Theme.PANEL : pressed ? Theme.RAISED : hover ? Theme.HOVER : Theme.RAISED;
                    ink = on ? Theme.TEXT : Theme.DIM; edge = Theme.LINE;
                }
            }
            g.setColor(fill);
            g.fill(new RoundRectangle2D.Float(2, 2, w - 4, h - 4, arc, arc));
            if (edge != null) { g.setColor(edge); g.setStroke(new BasicStroke(1f)); g.draw(new RoundRectangle2D.Float(2.5f, 2.5f, w - 5, h - 5, arc, arc)); }
            Font f = kind == Kind.PRIMARY ? Theme.strong(15.5f) : Theme.medium(13.5f);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            int gw = glyph == null ? 0 : 22;
            float tx = (w - fm.stringWidth(text) - gw) / 2f + gw;
            float ty = note == null ? (h + fm.getAscent() - fm.getDescent()) / 2f : h / 2f - 1;
            if (glyph != null) glyph.draw(g, tx - 22, ty - fm.getAscent() / 2f - 1 + (note == null ? 0 : 0), 16, ink);
            g.setColor(ink);
            g.drawString(text, tx, ty);
            if (note != null) {
                g.setFont(Theme.body(12f));
                g.setColor(Theme.alpha(ink, 190));
                centred(g, note, w / 2f, ty + 17);
            }
            focusRing(g, arc);
            g.dispose();
        }
    }

    /** Small drawn symbols (no icon font needed). */
    interface Glyph { void draw(Graphics2D g, float x, float y, float size, Color c); }

    static final Glyph PLAY = (g, x, y, s, c) -> {
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        p.moveTo(x + s * 0.22, y); p.lineTo(x + s * 0.92, y + s / 2); p.lineTo(x + s * 0.22, y + s); p.closePath();
        g.setColor(c); g.fill(p);
    };
    static final Glyph PAUSE = (g, x, y, s, c) -> {
        g.setColor(c);
        g.fill(new RoundRectangle2D.Float(x + s * 0.18f, y, s * 0.24f, s, 3, 3));
        g.fill(new RoundRectangle2D.Float(x + s * 0.58f, y, s * 0.24f, s, 3, 3));
    };
    static final Glyph FOLDER = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        p.moveTo(x, y + s * 0.85); p.lineTo(x, y + s * 0.15); p.lineTo(x + s * 0.38, y + s * 0.15); p.lineTo(x + s * 0.48, y + s * 0.3);
        p.lineTo(x + s, y + s * 0.3); p.lineTo(x + s, y + s * 0.85); p.closePath();
        g.draw(p);
    };
    static final Glyph DOWN = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int) (x + s / 2), (int) y, (int) (x + s / 2), (int) (y + s * 0.7));
        g.drawLine((int) (x + s * 0.2), (int) (y + s * 0.42), (int) (x + s / 2), (int) (y + s * 0.72));
        g.drawLine((int) (x + s * 0.8), (int) (y + s * 0.42), (int) (x + s / 2), (int) (y + s * 0.72));
        g.drawLine((int) x, (int) (y + s), (int) (x + s), (int) (y + s));
    };
    /** The split (the icon's shape), for buttons that send stems on. */
    static final Glyph SPLIT = (g, x, y, s, c) -> {
        g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float sx = x + s * 0.35f, sy = y + s / 2;
        g.setColor(c);
        g.drawLine((int) x, (int) sy, (int) sx, (int) sy);
        for (int k = 0; k < 3; k++) {
            float ty = y + s * (0.12f + 0.38f * k);
            g.draw(new java.awt.geom.CubicCurve2D.Float(sx, sy, sx + s * 0.3f, sy, x + s * 0.65f, ty, x + s, ty));
        }
    };

    /** Two or three choices side by side. */
    static final class Segmented extends Clickable {
        final String[] options;
        int selected;
        private IntConsumer changed;
        private int hoverAt = -1;

        Segmented(String... options) {
            this.options = options;
            setPreferredSize(new Dimension(200, 36));
            addMouseMotionListener(new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { int a = at(e.getX()); if (a != hoverAt) { hoverAt = a; repaint(); } }
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseExited(MouseEvent e) { hoverAt = -1; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { if (isEnabled() && contains(e.getPoint())) choose(at(e.getX())); }
            });
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_LEFT) choose(Math.max(0, selected - 1));
                    if (e.getKeyCode() == KeyEvent.VK_RIGHT) choose(Math.min(options.length - 1, selected + 1));
                }
            });
        }

        @Override void fire() { choose((selected + 1) % options.length); }

        void onChange(IntConsumer c) { changed = c; }

        private int at(int x) { return Math.max(0, Math.min(options.length - 1, x * options.length / Math.max(1, getWidth()))); }

        void choose(int i) {
            if (i == selected) return;
            selected = i; repaint();
            if (changed != null) changed.accept(i);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.BG);
            g.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 11, 11));
            g.setColor(Theme.LINE);
            g.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, 11, 11));
            float cell = (w - 6f) / options.length;
            for (int i = 0; i < options.length; i++) {
                float x = 3 + cell * i;
                if (i == selected) {
                    g.setColor(Theme.RAISED);
                    g.fill(new RoundRectangle2D.Float(x, 3, cell, h - 6, 9, 9));
                    g.setColor(Theme.LINE);
                    g.draw(new RoundRectangle2D.Float(x + 0.5f, 3.5f, cell - 1, h - 7, 9, 9));
                } else if (i == hoverAt && isEnabled()) {
                    g.setColor(Theme.alpha(Theme.HOVER, 160));
                    g.fill(new RoundRectangle2D.Float(x, 3, cell, h - 6, 9, 9));
                }
                g.setFont(i == selected ? Theme.medium(13f) : Theme.body(13f));
                g.setColor(!isEnabled() ? Theme.DIM : i == selected ? Theme.TEXT : Theme.MID);
                FontMetrics fm = g.getFontMetrics();
                centred(g, options[i], x + cell / 2, (h + fm.getAscent() - fm.getDescent()) / 2f);
            }
            focusRing(g, 11);
            g.dispose();
        }
    }

    /** A panel drawn as a rounded card, with an optional heading. Children are laid out below the heading. */
    static class Card extends JPanel {
        final String heading;

        Card(String heading) {
            this.heading = heading;
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(heading == null ? 16 : 46, 18, 18, 18));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.PANEL);
            g.fill(new RoundRectangle2D.Float(0, 0, w, h, 16, 16));
            g.setColor(Theme.HAIR);
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 16, 16));
            if (heading != null) {
                g.setFont(Theme.strong(14f));
                g.setColor(Theme.TEXT);
                g.drawString(heading, 18, 30);
            }
            g.dispose();
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    static Component gap(int h) { return Box.createRigidArea(new Dimension(0, h)); }

    /** A line or two of text, wrapped to the card. */
    static final class Words extends JComponent {
        String text;
        Font font;
        Color color;
        private final int lines;

        Words(String text, Font font, Color color, int lines) {
            this.text = text; this.font = font; this.color = color; this.lines = lines;
            setAlignmentX(LEFT_ALIGNMENT);
        }

        void set(String text, Color color) { this.text = text; if (color != null) this.color = color; repaint(); }

        @Override public Dimension getPreferredSize() {
            int lh = getFontMetrics(font).getHeight() + 2;
            return new Dimension(260, lh * lines);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override protected void paintComponent(Graphics g0) {
            if (text == null || text.isEmpty()) return;
            Graphics2D g = smooth(g0);
            g.setFont(font);
            g.setColor(color);
            FontMetrics fm = g.getFontMetrics();
            float y = fm.getAscent();
            for (String para : text.split("\n")) y = wrap(g, para, 0, y, getWidth(), fm.getHeight() + 2);
            g.dispose();
        }
    }
}
