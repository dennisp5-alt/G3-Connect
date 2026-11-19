package com.dennis.stemstudio.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.File;

/**
 * The window: a header, the session column on the left (song, separate, use the stems), the lanes, and
 * the transport along the bottom. One primary button at a time: what to do next.
 */
class StudioWindow {
    static final int SIDE = 340;

    final Session s = new Session();
    final JPanel root = new JPanel(new BorderLayout(0, 0)) {
        @Override protected void paintComponent(Graphics g) { g.setColor(Theme.BG); g.fillRect(0, 0, getWidth(), getHeight()); }
    };
    final LanesView lanes = new LanesView(s);
    final Header header = new Header();
    final Transport transport = new Transport();

    // Song card.
    final Ui.Card songCard = new Ui.Card("Song");
    final DropZone drop = new DropZone();
    final Ui.Words songName = new Ui.Words("", Theme.strong(14.5f), Theme.TEXT, 2);
    final Ui.Words songWhere = new Ui.Words("", Theme.body(12.5f), Theme.DIM, 1);
    final Ui.Btn chooseAnother = new Ui.Btn("Choose another song", Ui.Kind.SECONDARY);
    // Separate card.
    final Ui.Card separateCard = new Ui.Card("Separate");
    final Ui.Segmented device = new Ui.Segmented("Graphics card", "Processor");
    final Ui.Words deviceNote = new Ui.Words("", Theme.body(12.5f), Theme.DIM, 2);
    final Ui.Btn separate = new Ui.Btn("Separate into six stems", Ui.Kind.PRIMARY);
    final Ui.Btn benchmark = new Ui.Btn("Benchmark this song (experimental)", Ui.Kind.GHOST);
    final Bar bar = new Bar();
    final Ui.Words progressText = new Ui.Words("", Theme.body(13f), Theme.MID, 4);
    // Use card.
    final Ui.Card useCard = new Ui.Card("Use the stems");
    final Ui.Btn toMasterRoom = new Ui.Btn("Send to Master Room", Ui.Kind.PRIMARY);
    final Ui.Btn saveWavs = new Ui.Btn("Save the six WAV files", Ui.Kind.SECONDARY);
    final Ui.Words savedWhere = new Ui.Words("", Theme.body(12.5f), Theme.DIM, 3);
    final Ui.Btn openFolder = new Ui.Btn("Open the folder", Ui.Kind.GHOST);

    StudioWindow() {
        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(SIDE, 600));

        songCard.add(drop);
        songCard.add(songName);
        songCard.add(Ui.gap(2));
        songCard.add(songWhere);
        songCard.add(Ui.gap(12));
        songCard.add(chooseAnother);

        separateCard.add(device);
        separateCard.add(Ui.gap(8));
        separateCard.add(deviceNote);
        separateCard.add(Ui.gap(10));
        separateCard.add(bar);
        separateCard.add(Ui.gap(8));
        separateCard.add(progressText);
        separateCard.add(Ui.gap(4));
        separateCard.add(separate);
        separateCard.add(Ui.gap(4));
        separateCard.add(benchmark);

        useCard.add(toMasterRoom);
        useCard.add(Ui.gap(6));
        useCard.add(saveWavs);
        useCard.add(Ui.gap(10));
        useCard.add(savedWhere);
        useCard.add(openFolder);

        benchmark.setVisible(false);
        benchmark.setPreferredSize(new Dimension(160, 34));
        for (JComponent c : new JComponent[]{benchmark, drop, songName, songWhere, chooseAnother, device, deviceNote, separate, bar, progressText, toMasterRoom, saveWavs, savedWhere, openFolder}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            Dimension p = c.getPreferredSize();
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.height));
        }
        chooseAnother.setPreferredSize(new Dimension(160, 38));
        openFolder.setPreferredSize(new Dimension(160, 34));
        toMasterRoom.note = "As a ZIP that Master Room opens as stems";
        saveWavs.glyph = Ui.DOWN;
        openFolder.glyph = Ui.FOLDER;
        toMasterRoom.setPreferredSize(new Dimension(160, 60));
        toMasterRoom.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        separate.setPreferredSize(new Dimension(160, 60));
        separate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        side.add(songCard);
        side.add(Ui.gap(14));
        side.add(separateCard);
        side.add(Ui.gap(14));
        side.add(useCard);
        side.add(Box.createVerticalGlue());

        JPanel middle = new JPanel(new BorderLayout(16, 0));
        middle.setOpaque(false);
        middle.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
        middle.add(side, BorderLayout.WEST);
        middle.add(lanes, BorderLayout.CENTER);

        root.add(header, BorderLayout.NORTH);
        root.add(middle, BorderLayout.CENTER);
        root.add(transport, BorderLayout.SOUTH);
        refresh();
    }

    /** Shows and hides what each stage needs. */
    void refresh() {
        Session.Stage st = s.stage;
        boolean empty = st == Session.Stage.EMPTY, busy = st == Session.Stage.SEPARATING, done = st == Session.Stage.DONE;
        drop.setVisible(empty);
        songName.setVisible(!empty); songWhere.setVisible(!empty); chooseAnother.setVisible(!empty);
        chooseAnother.setEnabled(!busy);
        if (s.song != null) {
            songName.set(s.fileName, null);
            songWhere.set(s.folder, null);
        }
        if (s.outDir != null && s.notice.isEmpty()) savedWhere.set("Saved in " + shortPath(s.outDir.getPath()), Theme.DIM);
        else if (!s.notice.isEmpty()) savedWhere.set(s.notice, s.noticeBad ? Theme.WARN : Theme.MID);
        device.setEnabled(!busy && !empty);
        device.setVisible(s.onGpu);
        deviceNote.set(!s.engineReady ? "The separation engine is missing from the app folder. Reinstall Stem Studio."
            : s.onGpu ? (device.selected == 0 ? "Uses " + s.device + ". If it cannot, the processor takes over." : "Uses the processor (" + s.cores + " threads). Slower, but always works.")
            : "Uses the processor (" + s.cores + " threads). No graphics card was found.", s.engineReady ? Theme.DIM : Theme.WARN);
        bar.setVisible(busy || done);
        progressText.setVisible(busy || done || !s.result.isEmpty());
        if (!busy && !done) progressText.set(s.result, s.resultBad ? Theme.WARN : Theme.MID);
        if (busy) {
            separate.set("Stop", Ui.Kind.DANGER);
            separate.note = null;
            separate.setPreferredSize(new Dimension(160, 44));
            separate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            String left = Double.isFinite(s.secondsLeft) ? "About " + Session.duration(s.secondsLeft) + " left" : "Working out how long";
            progressText.set(s.stageText + "\n" + left, Theme.MID);
        } else if (done) {
            separate.set("Separate again", Ui.Kind.SECONDARY);
            separate.note = null;
            separate.setPreferredSize(new Dimension(160, 40));
            separate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            progressText.set(s.result, s.resultBad ? Theme.WARN : Theme.MID);
        } else {
            separate.set("Separate into six stems", Ui.Kind.PRIMARY);
            separate.note = empty ? "Choose a song first" : s.estimate.isEmpty() ? null : s.estimate;
            separate.setPreferredSize(new Dimension(160, 60));
            separate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        }
        separate.setEnabled(!empty && s.engineReady);
        toMasterRoom.set("Send to Master Room", done ? Ui.Kind.PRIMARY : Ui.Kind.SECONDARY);
        toMasterRoom.note = done ? "As a ZIP that Master Room opens as stems" : null;
        toMasterRoom.setEnabled(done);
        saveWavs.setEnabled(done);
        savedWhere.setVisible(done);
        openFolder.setVisible(done);
        transport.setEnabled(done);
        root.revalidate();
        root.repaint();
    }

    /** "Documents\Stem Studio\Dusty Road" rather than the whole path. */
    static String shortPath(String p) {
        int at = p.indexOf("Documents");
        return at >= 0 ? p.substring(at) : p;
    }


    // ---------- Pieces ----------

    /** The top bar: the name and where separation will run. */
    final class Header extends JComponent {
        Header() { setPreferredSize(new Dimension(800, 68)); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Ui.smooth(g0);
            g.drawImage(AppIcon.draw(64), 20, 16, 36, 36, null);
            g.setFont(Theme.display(20f));
            g.setColor(Theme.TEXT);
            g.drawString("Stem Studio", 66, 41);
            if (s.engineReady) {
                String t = s.onGpu ? "Graphics card ready" : "Processor only";
                g.setFont(Theme.medium(12.5f));
                int tw = g.getFontMetrics().stringWidth(t) + 40, x = getWidth() - 20 - tw;
                g.setColor(Theme.PANEL);
                g.fill(new RoundRectangle2D.Float(x, 21, tw, 28, 14, 14));
                g.setColor(Theme.HAIR);
                g.draw(new RoundRectangle2D.Float(x + 0.5f, 21.5f, tw - 1, 27, 14, 14));
                g.setColor(s.onGpu ? Theme.STEM[3] : Theme.STEM[1]);
                g.fill(new Ellipse2D.Float(x + 14, 31, 8, 8));
                g.setColor(Theme.MID);
                g.drawString(t, x + 30, 40);
            }
            g.dispose();
        }
    }

    /** Before a song: a large target to drop on or click. */
    final class DropZone extends Ui.Clickable {
        DropZone() {
            setPreferredSize(new Dimension(280, 132));
            getAccessibleContext().setAccessibleName("Choose a song");
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Ui.smooth(g0);
            int w = getWidth(), h = getHeight();
            g.setColor(hover ? Theme.alpha(Theme.BRAND, 18) : Theme.BG);
            g.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 14, 14));
            g.setColor(hover ? Theme.alpha(Theme.BRAND, 200) : Theme.LINE);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 6}, 0));
            g.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, 14, 14));
            Ui.DOWN.draw(g, w / 2f - 11, 22, 22, hover ? Theme.BRAND : Theme.MID);
            g.setFont(Theme.strong(14.5f));
            g.setColor(Theme.TEXT);
            Ui.centred(g, "Choose a song", w / 2f, 78);
            g.setFont(Theme.body(12.5f));
            g.setColor(Theme.DIM);
            Ui.centred(g, "or drop it here. WAV, MP3, FLAC or M4A.", w / 2f, 100);
            focusRing(g, 14);
            g.dispose();
        }
    }

    /** Progress, in the brand colour, with the six stem colours appearing as it fills. */
    final class Bar extends JComponent {
        Bar() { setPreferredSize(new Dimension(280, 10)); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Ui.smooth(g0);
            int w = getWidth(), h = getHeight();
            g.setColor(Theme.BG);
            g.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            double p = s.stage == Session.Stage.DONE ? 1 : s.progress;
            float fw = (float) (w * p);
            if (fw > 0) {
                Shape clip = g.getClip();
                g.clip(new RoundRectangle2D.Float(0, 0, fw, h, h, h));
                float seg = w / 6f;
                for (int i = 0; i < 6; i++) { g.setColor(Theme.STEM[i]); g.fill(new Rectangle.Float(seg * i, 0, seg + 1, h)); }
                g.setClip(clip);
            }
            g.dispose();
        }
    }

    /** Play, where we are, and the output level. */
    final class Transport extends JComponent {
        final Ui.Clickable play = new Ui.Clickable() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = Ui.smooth(g0);
                boolean on = isEnabled();
                g.setColor(!on ? Theme.RAISED : pressed ? Theme.mix(Theme.BRAND, Color.BLACK, 0.12) : hover ? Theme.BRAND_HOVER : Theme.BRAND);
                g.fill(new Ellipse2D.Float(2, 2, getWidth() - 4, getHeight() - 4));
                (s.playing ? Ui.PAUSE : Ui.PLAY).draw(g, getWidth() / 2f - 9 + (s.playing ? 0 : 1.5f), getHeight() / 2f - 9, 18, on ? Theme.ON_BRAND : Theme.DIM);
                if (isFocusOwner()) { g.setColor(Theme.alpha(Theme.BRAND, 150)); g.setStroke(new BasicStroke(2)); g.draw(new Ellipse2D.Float(0, 0, getWidth() - 1, getHeight() - 1)); }
                g.dispose();
            }
        };

        Transport() {
            setLayout(null);
            setPreferredSize(new Dimension(800, 84));
            play.getAccessibleContext().setAccessibleName("Play or pause");
            add(play);
        }

        @Override public void setEnabled(boolean on) { super.setEnabled(on); play.setEnabled(on); repaint(); }

        @Override public void doLayout() { play.setBounds(18 + SIDE + 16 + 18, 16, 52, 52); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = Ui.smooth(g0);
            int w = getWidth();
            int x = 18 + SIDE + 16 + 18 + 70;
            // Time: fixed-width digits, so it does not jitter as it counts.
            g.setFont(Theme.display(22f));
            g.setColor(isEnabled() ? Theme.TEXT : Theme.DIM);
            float tx = digits(g, Session.clock(s.at * s.seconds), x, 50);
            g.setFont(Theme.display(22f));
            g.setColor(Theme.DIM);
            g.drawString("/", tx + 8, 50);
            g.setFont(Theme.body(15f));
            digits(g, Session.clock(s.seconds), tx + 24, 49);
            // Which stems are in what you hear.
            float cx = tx + 110;
            g.setFont(Theme.body(12.5f));
            g.setColor(Theme.DIM);
            g.drawString("Hearing", cx, 47);
            cx += g.getFontMetrics().stringWidth("Hearing") + 12;
            for (int i = 0; i < 6; i++) {
                boolean heard = s.separated() && s.heard(i);
                g.setColor(heard ? Theme.STEM[i] : Theme.LINE);
                g.fill(new RoundRectangle2D.Float(cx, 34, 22, 18, 9, 9));
                g.setFont(Theme.strong(10.5f));
                g.setColor(heard ? new Color(0x10141A) : Theme.DIM);
                Ui.centred(g, Theme.STEMS[i].substring(0, 1), cx + 11, 47);
                cx += 27;
            }
            // Output meter, right.
            int mw = 220, mx = w - 18 - mw;
            g.setFont(Theme.body(12.5f));
            g.setColor(Theme.DIM);
            g.drawString("Output", mx - 58, 47);
            meter(g, mx, 32, mw, s.meterL);
            meter(g, mx, 44, mw, s.meterR);
            g.setColor(Theme.DIM);
            g.setFont(Theme.body(12f));
            String hint = "Space plays and pauses";
            g.drawString(hint, mx - 58 - 22 - g.getFontMetrics().stringWidth(hint), 47);
            g.dispose();
        }

        private float digits(Graphics2D g, String t, float x, float y) {
            FontMetrics fm = g.getFontMetrics();
            int cell = fm.charWidth('0');
            for (char ch : t.toCharArray()) {
                int cw = Character.isDigit(ch) ? cell : fm.charWidth(ch);
                g.drawString(String.valueOf(ch), x + (cw - fm.charWidth(ch)) / 2f, y);
                x += cw;
            }
            return x;
        }

        private void meter(Graphics2D g, int x, int y, int w, float v) {
            g.setColor(Theme.RAISED);
            g.fill(new RoundRectangle2D.Float(x, y, w, 7, 7, 7));
            float fw = w * Math.max(0, Math.min(1, v));
            if (fw <= 0) return;
            g.setPaint(new LinearGradientPaint(x, 0, x + w, 0, new float[]{0, 0.72f, 0.9f, 1},
                new Color[]{Theme.BRAND, Theme.BRAND, Theme.STEM[1], Theme.WARN}));
            g.fill(new RoundRectangle2D.Float(x, y, fw, 7, 7, 7));
        }
    }

    // ---------- Screenshots (the checks, and to show the design) ----------

    static void screenshot(File png, int w, int h, String state) throws Exception {
        StudioWindow win = new StudioWindow();
        Demo.fill(win.s, state);
        win.refresh();
        win.root.setSize(w, h);
        layout(win.root);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        win.root.printAll(g);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", png);
    }

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof Container k) for (Component ch : k.getComponents()) layout(ch);
    }
}
