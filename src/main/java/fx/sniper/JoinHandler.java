package fx.sniper;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends the join command for a detected target.
 *
 * Guard rails exist because Hypixel punishes /p spam faster than it punishes
 * anything else in the mod: 3s between sends, 5 sends per target per 10 min.
 */
public final class JoinHandler {

    private static final long SEND_GAP_MS = 3000L;
    private static final long TARGET_WINDOW_MS = 600_000L;
    private static final int MAX_PER_TARGET = 5;

    private final TargetManager targets;
    private final Map<String, Integer> attempts = new HashMap<>();

    private long lastJoinAt;
    private String lastJoined = "";
    private long windowStartedAt;

    public JoinHandler(TargetManager targets) {
        this.targets = targets;
        this.windowStartedAt = System.currentTimeMillis();
    }

    public void handle(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.getNetHandler() == null) return;
        if (name == null || name.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastJoinAt < SEND_GAP_MS) return;
        if (name.equalsIgnoreCase(lastJoined) && now - lastJoinAt < 20_000L) return;

        if (now - windowStartedAt > TARGET_WINDOW_MS) {
            attempts.clear();
            windowStartedAt = now;
        }
        String key = name.toLowerCase();
        int used = attempts.getOrDefault(key, 0);
        if (used >= MAX_PER_TARGET) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                "\u00a7c[sniper] \u00a7fattempt cap reached for " + name));
            return;
        }
        attempts.put(key, used + 1);

        lastJoinAt = now;
        lastJoined = name;

        mc.thePlayer.addChatMessage(new ChatComponentText(
            "\u00a7a[sniper] \u00a7ftarget \u00a7e" + name + "\u00a7f in instance (\u00a7e"
                + (used + 1) + "/" + MAX_PER_TARGET + "\u00a7f)"));

        if (!targets.config().autoJoin) return;

        mc.thePlayer.sendChatMessage("/p join " + name);

        if (targets.config().autoQueueBedwars) {
            String q = targets.config().queueCommand;
            if (q != null && !q.isEmpty()) {
                mc.thePlayer.sendChatMessage("/" + q.replaceFirst("^/", ""));
            }
        }
    }
}
