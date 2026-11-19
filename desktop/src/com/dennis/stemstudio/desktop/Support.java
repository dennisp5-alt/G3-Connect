package com.dennis.stemstudio.desktop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Where things go, songs in other formats (through FFmpeg), and handing the stems on. */
final class Places {
    private Places() {}

    private static File documents;

    /** The Documents folder Explorer shows (Windows lets it move), else home\Documents. */
    static synchronized File documents() {
        if (documents != null) return documents;
        File home = new File(System.getProperty("user.home"));
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding=[Text.Encoding]::UTF8; [Environment]::GetFolderPath('MyDocuments')").redirectErrorStream(true).start();
                p.getOutputStream().close();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isEmpty() && !out.contains("\n") && new File(out).isDirectory())
                    return documents = new File(out);
            } catch (Exception ignored) { }
        }
        File d = new File(home, "Documents");
        return documents = d.isDirectory() ? d : home;
    }

    static File root() {
        String p = System.getProperty("stemstudio.out");
        return p != null ? new File(p) : new File(documents(), "Stem Studio");
    }

    static String safe(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "Song" : s;
    }

    /** "Dusty Road (final mix).wav" → "Dusty Road (final mix)". */
    static String title(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Shows a file selected in Explorer (or opens its folder elsewhere). */
    static void reveal(File f) {
        try {
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                new ProcessBuilder("explorer.exe", f.isFile() ? "/select," + f.getAbsolutePath() : f.getAbsolutePath()).start();
            } else if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(f.isFile() ? f.getParentFile() : f);
            }
        } catch (Exception ignored) { }
    }
}

final class Ffmpeg {
    private Ffmpeg() {}

    static String find() {
        String prop = System.getProperty("stemstudio.ffmpeg");
        if (prop != null && new File(prop).canExecute()) return prop;
        for (String n : new String[]{"ffmpeg/ffmpeg.exe", "ffmpeg.exe", "ffmpeg/ffmpeg"}) {
            File f = new File(Engine.appDir(), n);
            if (f.canExecute()) return f.getAbsolutePath();
        }
        for (String n : new String[]{"ffmpeg", "ffmpeg.exe"}) {
            try {
                Process p = new ProcessBuilder(n, "-hide_banner", "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return n;
            } catch (Exception ignored) { }
        }
        return null;
    }

    record Info(String codec, int rate) {}

    /** The song's format and sample rate as FFmpeg reports them ("mp3", 44100), for the facts line. */
    static Info info(File in) {
        String exe = find();
        if (exe == null) return new Info("", 0);
        try {
            Process p = new ProcessBuilder(exe, "-hide_banner", "-nostdin", "-i", in.getAbsolutePath()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("Audio: ([a-z0-9_]+)[^,]*, (\\d+) Hz").matcher(out);
            if (m.find()) return new Info(m.group(1), Integer.parseInt(m.group(2)));
        } catch (Exception ignored) { }
        return new Info("", 0);
    }

    /** Any song FFmpeg reads, as a 48 kHz 24-bit stereo WAV the engine takes. */
    static void toWav(File in, File out) throws IOException {
        String exe = find();
        if (exe == null) throw new IOException("This kind of file needs FFmpeg, which is missing from the app folder. WAV files work without it.");
        List<String> cmd = new ArrayList<>(List.of(exe, "-hide_banner", "-nostdin", "-v", "error", "-y", "-i", in.getAbsolutePath(),
            "-vn", "-ac", "2", "-ar", "48000", "-c:a", "pcm_s24le", out.getAbsolutePath()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("Reading the song took too long"); }
        } catch (InterruptedException e) { p.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("Stopped"); }
        if (p.exitValue() != 0 || !out.isFile()) throw new IOException("Could not read that file" + (said.isEmpty() ? "" : ": " + said.lines().reduce((a, b) -> b).orElse("")));
    }
}

final class Export {
    private Export() {}

    /**
     * The six stems in one ZIP that Master Room opens with Open stems (it reads the stem from each file's
     * name). Stored, not compressed: WAV hardly shrinks, and this is instant.
     */
    static File zip(File[] stems, File dir, String title) throws IOException {
        File zip = new File(dir, Places.safe(title) + " Stems.zip");
        File part = new File(dir, zip.getName() + ".part");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(part.toPath()))) {
            z.setMethod(ZipOutputStream.STORED);
            byte[] buf = new byte[1 << 16];
            for (int i = 0; i < stems.length; i++) {
                File f = stems[i];
                CRC32 crc = new CRC32();
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    int n;
                    while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
                }
                ZipEntry e = new ZipEntry(Places.safe(title) + "_" + Theme.STEMS[i] + "_24bit.wav");
                e.setMethod(ZipEntry.STORED);
                e.setSize(f.length());
                e.setCompressedSize(f.length());
                e.setCrc(crc.getValue());
                z.putNextEntry(e);
                Files.copy(f.toPath(), z);
                z.closeEntry();
            }
        }
        Files.move(part.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return zip;
    }

    /** Copies the six WAVs to another folder. */
    static void copy(File[] stems, File to) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Could not create " + to);
        for (File f : stems) Files.copy(f.toPath(), new File(to, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static String size(long bytes) { return String.format(Locale.US, bytes >= 1e9 ? "%.1f GB" : "%.0f MB", bytes >= 1e9 ? bytes / 1e9 : bytes / 1e6); }
}
