import com.dennis.stemstudio.desktop.CheckAccess;

/**
 * Checks Stem Studio for Windows end to end without a window: the engine separates a song into six stems
 * (live overviews included), the stems add back up to the song when played together, solo and mute do what
 * they say, Stop leaves nothing behind, a non-WAV song is read through FFmpeg, and the ZIP for Master Room
 * holds the six files under names Master Room recognises.
 *
 *   java -Dstemstudio.engine=... -Dstemstudio.model=... -cp classes;check StudioCheck song.wav [song.mp3]
 */
public final class StudioCheck {
    public static void main(String[] a) throws Exception {
        int fails = CheckAccess.run(new java.io.File(a[0]), a.length > 1 ? new java.io.File(a[1]) : null);
        if (fails > 0) { System.out.println(fails + " check(s) failed"); System.exit(1); }
        System.out.println("STUDIO_CHECK_PASS");
    }
}
