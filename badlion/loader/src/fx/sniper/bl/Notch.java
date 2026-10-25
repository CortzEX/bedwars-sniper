package fx.sniper.bl;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Maps a stable key ("Minecraft", "Scoreboard") onto the 1.8.9 class name.
 *
 * <p>Two names are kept per key:
 * <ul>
 *   <li><b>notch</b> — the obfuscated name shipped in the 1.8.9 jar. Authoritative
 *       values come from MCP 9.19 {@code joined.srg} {@code CL:} lines. This is what
 *       a vanilla / Badlion / Lunar launch actually has on the class path.</li>
 *   <li><b>mcp</b> — the deobfuscated path. Used when the runtime remaps names
 *       (Forge in a dev environment, or any client that ships a deobf jar).</li>
 * </ul>
 *
 * <p>Override file {@code notch.properties} on the class path replaces any entry.
 * Resolution never throws: an unresolvable key returns {@code null} and the caller
 * reports which step failed.
 */
public final class Notch {

    private static final Map<String, String> BUILT_IN = new LinkedHashMap<String, String>();

    static {
        // key | notch | MCP path  — see notch.properties for provenance
        put("Minecraft", "ave", "net.minecraft.client.Minecraft");
        put("EntityPlayerSP", "bew", "net.minecraft.client.entity.EntityPlayerSP");
        put("NetHandlerPlayClient", "bcy", "net.minecraft.client.network.NetHandlerPlayClient");
        put("NetworkPlayerInfo", "bdc", "net.minecraft.client.network.NetworkPlayerInfo");
        put("GuiIngame", "avo", "net.minecraft.client.gui.GuiIngame");
        put("GuiPlayerTabOverlay", "awh", "net.minecraft.client.gui.GuiPlayerTabOverlay");
        put("GuiNewChat", "avt", "net.minecraft.client.gui.GuiNewChat");
        put("Scoreboard", "auo", "net.minecraft.scoreboard.Scoreboard");
        put("ScoreObjective", "auk", "net.minecraft.scoreboard.ScoreObjective");
        put("ScorePlayerTeam", "aul", "net.minecraft.scoreboard.ScorePlayerTeam");
        put("Score", "aum", "net.minecraft.scoreboard.Score");
        put("World", "adm", "net.minecraft.world.World");
        put("IChatComponent", "eu", "net.minecraft.util.IChatComponent");
        put("ChatComponentText", "fa", "net.minecraft.util.ChatComponentText");
        put("GuiScreen", "axu", "net.minecraft.client.gui.GuiScreen");
    }

    private final Map<String, String[]> table = new HashMap<String, String[]>();

    public Notch() {
        for (Map.Entry<String, String> e : BUILT_IN.entrySet()) {
            String[] parts = e.getValue().split("\\|", -1);
            table.put(e.getKey(), new String[] { parts[0], parts.length > 1 ? parts[1] : null });
        }
        loadOverride();
    }

    private static void put(String key, String notch, String mcp) {
        BUILT_IN.put(key, notch + "|" + mcp);
    }

    private void loadOverride() {
        InputStream in = null;
        try {
            in = Notch.class.getResourceAsStream("/notch.properties");
            if (in == null) return;

            Properties props = new Properties();
            props.load(new InputStreamReader(in, "UTF-8"));

            for (String key : props.stringPropertyNames()) {
                String raw = props.getProperty(key, "").trim();
                if (raw.isEmpty()) continue;
                String[] parts = raw.split("\\|", -1);
                table.put(key, new String[] { parts[0].trim(), parts.length > 1 ? parts[1].trim() : null });
            }
            Log.info("notch.properties merged (" + props.size() + " key(s))");
        } catch (Exception e) {
            Log.error("notch.properties unreadable, using built-in table", e);
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Exception ignored) {
            // nothing useful to do
        }
    }

    /** Notch name for a key, or {@code null}. */
    public String notchName(String key) {
        String[] parts = table.get(key);
        return parts == null ? null : parts[0];
    }

    /** MCP path for a key, or {@code null} when the client is not deobfuscated. */
    public String mcpName(String key) {
        String[] parts = table.get(key);
        return parts == null ? null : parts[1];
    }

    /**
     * Resolves a key against {@code loader}, trying the notch name first.
     *
     * @return the class, or {@code null} when neither name exists. Never throws.
     */
    public Class<?> resolve(ClassLoader loader, String key) {
        String[] parts = table.get(key);
        if (parts == null || loader == null) return null;

        Class<?> found = load(loader, parts[0]);
        if (found != null) return found;
        return parts[1] == null ? null : load(loader, parts[1]);
    }

    /** True when this loader can see the game at all. */
    public boolean canLoad(ClassLoader loader, String key) {
        return resolve(loader, key) != null;
    }

    /** Name that actually resolved, for the log. Tries notch first, then MCP. */
    public String resolvedName(ClassLoader loader, String key) {
        String[] parts = table.get(key);
        if (parts == null || loader == null) return "?";
        if (load(loader, parts[0]) != null) return parts[0];
        if (parts[1] != null && load(loader, parts[1]) != null) return parts[1];
        return "?";
    }

    private static Class<?> load(ClassLoader loader, String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
