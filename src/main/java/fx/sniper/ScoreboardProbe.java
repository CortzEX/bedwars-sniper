package fx.sniper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.IChatComponent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the two places Hypixel leaks location without the API:
 *   1. tab header -> "You are playing on server mini123AB"
 *   2. sidebar    -> map name on Bedwars
 *
 * Both are rendered client-side from packets the server always sends.
 *
 * 1.8.9's GuiPlayerTabOverlay exposes setHeader/setFooter but no getters, so the
 * header is read reflectively. Forge runs deobfuscated names, so the field is
 * literally "header"/"footer". Any failure returns null instead of throwing.
 */
public final class ScoreboardProbe {

    private static Field headerField;
    private static boolean fieldResolved;

    /** Server id such as "mini42AB" — the exact instance shared with the target. */
    public static String serverId() {
        IChatComponent header = tabHeader();
        if (header == null) return null;

        String plain = strip(header.getUnformattedText());
        int idx = plain.indexOf("mini");
        if (idx < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = idx; i < plain.length() && sb.length() < 24; i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.length() > 4 ? sb.toString() : null;
    }

    /** Map name from the sidebar. Bedwars prints it bare on its own line. */
    public static String mapName() {
        List<String> lines = sidebarLines();
        if (lines.isEmpty()) return null;

        for (String line : lines) {
            if (line.length() < 3) continue;
            if (line.toLowerCase().startsWith("map:")) return line.substring(4).trim();
        }
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.contains("lobby") || lower.isEmpty()) continue;
            if (!lower.matches("^[a-z0-9 _'-]{3,24}$")) continue;
            if (lower.startsWith("players") || lower.startsWith("next")) continue;
            return line;
        }
        return null;
    }

    /** Gamemode from the sidebar, e.g. "Mode: Solo". */
    public static String modeName() {
        for (String line : sidebarLines()) {
            if (line.toLowerCase().startsWith("mode:")) return line.substring(5).trim();
        }
        return null;
    }

    private static IChatComponent tabHeader() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null) return null;

        GuiIngame gui = mc.ingameGUI;
        Object overlay = gui.getTabList();
        if (overlay == null) return null;

        try {
            if (!fieldResolved) {
                fieldResolved = true;
                headerField = findField(overlay.getClass());
            }
            if (headerField == null) return null;
            Object value = headerField.get(overlay);
            return value instanceof IChatComponent ? (IChatComponent) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Forge deobfuscates at runtime, but fall back to the notch name just in case. */
    private static Field findField(Class<?> type) {
        String[] candidates = { "header", "footer", "field_175256_f", "field_175255_g" };
        for (String name : candidates) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the next name
            } catch (SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> sidebarLines() {
        List<String> out = new ArrayList<>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return out;

        Scoreboard sb = mc.theWorld.getScoreboard();
        if (sb == null) return out;

        ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
        if (obj == null) return out;

        for (Score score : sb.getSortedScores(obj)) {
            String name = score.getPlayerName();
            if (name != null) out.add(strip(name).trim());
        }
        return out;
    }

    private static String strip(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '\u00a7') {
                i++; // skip the colour code character
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
