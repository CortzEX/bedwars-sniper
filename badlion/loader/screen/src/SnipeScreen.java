import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-game menu, opened by the loader on F6.
 *
 * <p>Lives in the default package on purpose: it extends the game's {@code GuiScreen}
 * ({@code axu} in 1.8.9), and obfuscated game classes are unpackaged, so a packaged
 * class could not extend one. It also links against nothing from the loader — the
 * two sides talk through files in the work directory instead, which keeps the menu
 * loadable from whichever class loader the game happens to use.
 *
 * <p>Written by the menu, read by the loader, every save:
 * <pre>
 * snipe_ui.txt    target=, apiKey=, partyJoin=, autoQueue=
 * ui_status.txt   last status lines, read back for the panel at the bottom
 * </pre>
 *
 * <p>Compiled against the stubs in {@code screen/stubs}, never against the game jar.
 */
public final class SnipeScreen extends axu {

    private static final String DIR_PROP = "blsnip.dir";
    private static final String UI_FILE = "snipe_ui.txt";
    private static final String STATUS_FILE = "ui_status.txt";

    private static final int ESC = 1;
    private static final int BACK = 14;
    private static final int TAB = 15;
    private static final int RETURN = 28;

    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xF0141414;
    private static final int EDGE = 0xFF3C3C3C;
    private static final int FIELD = 0xFF0B0B0B;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFF9A9A9A;
    private static final int ACCENT = 0xFF55FF55;
    private static final int WARN = 0xFFFF6B6B;

    private static final int W = 384;
    private static final int H = 250;
    private static final int PAD = 18;
    private static final int INNER = W - PAD * 2;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private String target = "";
    private String apiKey = "";
    private boolean partyJoin = true;
    private boolean autoQueue;
    private int focus;

    private String flash = "";
    private long flashAt;

    private List<String> status = new ArrayList<String>();
    private long statusAt;

    private int px;
    private int py;

    @Override
    public void b() {
        readUi();
        focus = target.isEmpty() ? 0 : 1;
        refreshStatus();
    }

    /** A menu should never freeze the game behind it. */
    @Override
    public boolean d() {
        return false;
    }

    // ------------------------------------------------------------------ draw

    @Override
    public void a(int mouseX, int mouseY, float partial) {
        layout();
        refreshStatus();

        avp.a(0, 0, l, m, SCRIM);
        avp.a(px - 1, py - 1, px + W + 1, py + H + 1, EDGE);
        avp.a(px, py, px + W, py + H, PANEL);

        q.a("blsnip", px + PAD, py + 10, ACCENT);
        q.a("F6", px + W - PAD - q.a("F6"), py + 10, DIM);

        q.a("target name", px + PAD, py + 28, DIM);
        field(px + PAD, py + 39, target, focus == 0);

        q.a("hypixel api key", px + PAD, py + 63, DIM);
        field(px + PAD, py + 74, apiKey, focus == 1);
        q.a(apiKey.isEmpty() ? "empty - tab list only" : "set - mode + map polling on",
            px + PAD + 96, py + 63, apiKey.isEmpty() ? DIM : ACCENT);

        toggle(py + 104, "party join   (/p join on sight)", partyJoin);
        toggle(py + 124, "auto queue   (/play after the join)", autoQueue);

        q.a("status", px + PAD, py + 148, DIM);
        for (int i = 0; i < status.size(); i++) {
            q.a(clip(status.get(i), INNER), px + PAD, py + 160 + i * 10, TEXT);
        }

        button(px + PAD, py + H - 26, 84, "save", ACCENT);
        button(px + W - PAD - 84, py + H - 26, 84, "close", DIM);

        String hint = "tab next - enter save - esc close";
        q.a(hint, px + W - PAD - q.a(hint), py + H - 38, DIM);

        if (!flash.isEmpty() && System.currentTimeMillis() - flashAt < 2500L) {
            q.a(flash, px + PAD + 96, py + H - 21, WARN);
        }
    }

    private void layout() {
        px = Math.max(4, (l - W) / 2);
        py = Math.max(4, (m - H) / 2);
    }

    private void field(int x, int y, String value, boolean active) {
        avp.a(x - 1, y - 1, x + INNER + 1, y + 17, active ? ACCENT : EDGE);
        avp.a(x, y, x + INNER, y + 16, FIELD);
        String shown = clip(value, INNER - 8);
        q.a(shown, x + 4, y + 4, TEXT);
        if (active && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            q.a("_", x + 5 + q.a(shown), y + 4, TEXT);
        }
    }

    private void toggle(int y, String label, boolean on) {
        avp.a(px + PAD - 1, y - 1, px + PAD + INNER + 1, y + 15, EDGE);
        avp.a(px + PAD, y, px + PAD + INNER, y + 14, FIELD);
        q.a(label, px + PAD + 4, y + 3, TEXT);
        String state = on ? "ON" : "OFF";
        q.a(state, px + PAD + INNER - 4 - q.a(state), y + 3, on ? ACCENT : DIM);
    }

    private void button(int x, int y, int width, String label, int colour) {
        avp.a(x, y, x + width, y + 18, EDGE);
        avp.a(x + 1, y + 1, x + width - 1, y + 17, FIELD);
        q.a(label, x + (width - q.a(label)) / 2, y + 5, colour);
    }

    private String clip(String text, int width) {
        if (text == null) return "";
        if (q.a(text) <= width) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (q.a(sb.toString() + text.charAt(i)) > width - 8) break;
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- input

    @Override
    public void a(int mouseX, int mouseY, int button) {
        layout();
        if (button != 0) return;

        if (inside(mouseX, mouseY, px + PAD, py + 39, INNER, 18)) {
            focus = 0;
            return;
        }
        if (inside(mouseX, mouseY, px + PAD, py + 74, INNER, 18)) {
            focus = 1;
            return;
        }
        if (inside(mouseX, mouseY, px + PAD, py + 104, INNER, 16)) {
            partyJoin = !partyJoin;
            save();
            return;
        }
        if (inside(mouseX, mouseY, px + PAD, py + 124, INNER, 16)) {
            autoQueue = !autoQueue;
            save();
            return;
        }
        if (inside(mouseX, mouseY, px + PAD, py + H - 26, 84, 18)) {
            save();
            return;
        }
        if (inside(mouseX, mouseY, px + W - PAD - 84, py + H - 26, 84, 18)) {
            close();
        }
    }

    @Override
    public void a(char typed, int key) {
        if (key == ESC) {
            close();
            return;
        }
        if (key == RETURN) {
            save();
            return;
        }
        if (key == TAB) {
            focus = focus == 0 ? 1 : 0;
            return;
        }
        if (key == BACK) {
            if (focus == 0 && !target.isEmpty()) {
                target = target.substring(0, target.length() - 1);
            } else if (focus == 1 && !apiKey.isEmpty()) {
                apiKey = apiKey.substring(0, apiKey.length() - 1);
            }
            return;
        }
        if (typed < 32 || typed > 126) return;

        if (focus == 0) {
            if (target.length() >= 16) return;
            boolean ok = Character.isLetterOrDigit(typed) || typed == '_';
            if (ok) target += typed;
        } else {
            if (apiKey.length() >= 36) return;
            apiKey += typed;
        }
    }

    private static boolean inside(int mx, int my, int x, int y, int width, int height) {
        return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }

    private void close() {
        j.a((axu) null);
    }

    // ----------------------------------------------------------------- files

    private void save() {
        String text = "target=" + target.trim() + "\n"
            + "apiKey=" + apiKey.trim() + "\n"
            + "partyJoin=" + partyJoin + "\n"
            + "autoQueue=" + autoQueue + "\n";
        if (write(UI_FILE, text)) {
            flash = "saved";
            status = new ArrayList<String>(status);
            status.add("saved: " + (target.trim().isEmpty() ? "no target" : target.trim()));
            while (status.size() > 6) status.remove(0);
        } else {
            flash = "cannot write " + UI_FILE;
        }
        flashAt = System.currentTimeMillis();
    }

    private void readUi() {
        String text = read(UI_FILE);
        if (text == null) return;
        for (String line : text.split("\r?\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if ("target".equals(key)) target = value;
            else if ("apiKey".equals(key)) apiKey = value;
            else if ("partyJoin".equals(key)) partyJoin = "true".equalsIgnoreCase(value);
            else if ("autoQueue".equals(key)) autoQueue = "true".equalsIgnoreCase(value);
        }
    }

    /** Re-reads the loader's feed at most twice a second. */
    private void refreshStatus() {
        long now = System.currentTimeMillis();
        if (now - statusAt < 500L) return;
        statusAt = now;

        String text = read(STATUS_FILE);
        if (text == null) return;
        List<String> lines = new ArrayList<String>();
        for (String line : text.split("\r?\n")) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        while (lines.size() > 6) lines.remove(0);
        status = lines;
    }

    private File file(String name) {
        String dir = System.getProperty(DIR_PROP);
        if (dir == null || dir.trim().isEmpty()) dir = ".";
        return new File(dir, name);
    }

    private String read(String name) {
        InputStream in = null;
        try {
            File f = file(name);
            if (!f.isFile()) return null;
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            byte[] buf = new byte[512];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                if (out.size() > 8 * 1024) break;
            }
            return new String(out.toByteArray(), UTF8);
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    private boolean write(String name, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(name));
            out.write(text.getBytes(UTF8));
            out.flush();
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }
}
