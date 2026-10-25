package fx.sniper.bl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Append-only text log next to the loader.
 *
 * <p>Nothing here throws: a logging failure must never break the worker. When the
 * file cannot be opened (read-only dir, handle held by an AV scanner) the logger
 * degrades to stdout and keeps going.
 */
public final class Log {

    private static final Set<String> SEEN = new HashSet<String>();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private static Path file;
    private static boolean broken;

    private Log() {
    }

    public static synchronized void init(Path dir) {
        try {
            Files.createDirectories(dir);
            file = dir.resolve("blsnip.log");
            Files.write(file, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            file = null;
            broken = true;
            System.out.println("[blsnip] cannot open log in " + dir + ": " + e);
        }
    }

    public static void info(String message) {
        write("INFO ", message);
    }

    public static void warn(String message) {
        write("WARN ", message);
    }

    public static void error(String message) {
        write("ERROR", message);
    }

    public static void error(String message, Throwable cause) {
        write("ERROR", message + " :: " + describe(cause));
    }

    /** Writes the message only the first time this key is seen. */
    public static void once(String key, String message) {
        synchronized (SEEN) {
            if (!SEEN.add(key)) return;
        }
        warn(message);
    }

    public static String describe(Throwable cause) {
        if (cause == null) return "null";
        StringWriter out = new StringWriter();
        cause.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static synchronized void write(String level, String message) {
        String line = "[" + STAMP.format(new Date()) + "] " + level + " " + message;
        System.out.println("[blsnip] " + message);
        if (file == null || broken) return;
        try {
            Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            broken = true;
            System.out.println("[blsnip] log write failed, continuing on stdout: " + e);
        }
    }
}
