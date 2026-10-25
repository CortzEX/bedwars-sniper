package fx.sniper.bl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loader-side view of what the player set, plus the status feed the menu shows.
 *
 * <p>The menu itself never links against this class: it is compiled with the
 * game's obfuscated class names, so it meets the loader through the two files
 * {@code snipe_ui.txt} (menu → loader) and {@code ui_status.txt} (loader → menu).
 * These fields are the loader's copy, filled from the config file.
 */
public final class Ui {

    public static volatile String target = "";
    public static volatile String apiKey = "";
    public static volatile boolean partyJoin = true;
    public static volatile boolean autoQueue = false;

    /** Last few status lines, oldest first. Mirrored to {@code ui_status.txt}. */
    private static final List<String> LINES = Collections.synchronizedList(new ArrayList<String>());

    private Ui() {
    }

    /** Adds a line to the feed. Repeats are dropped so the file only moves on news. */
    public static void status(String line) {
        if (line == null || line.isEmpty()) return;
        synchronized (LINES) {
            if (!LINES.isEmpty() && LINES.get(LINES.size() - 1).equals(line)) return;
            LINES.add(line);
            while (LINES.size() > 6) LINES.remove(0);
        }
    }

    public static List<String> lines() {
        synchronized (LINES) {
            return new ArrayList<String>(LINES);
        }
    }
}
