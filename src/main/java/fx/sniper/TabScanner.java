package fx.sniper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.IChatComponent;

import java.util.Collection;

/**
 * Reads the 1.8.9 tab list straight out of the net handler.
 *
 * Names come from GameProfile (UUID-authoritative) rather than rendered text,
 * so rank prefixes and colour codes never break matching.
 */
public final class TabScanner {

    public interface Sink {
        void onTargetSeen(String name);
    }

    private final TargetManager targets;
    private long lastScan;
    private long lastHit;

    public TabScanner(TargetManager targets) {
        this.targets = targets;
        this.lastScan = 0L;
        this.lastHit = 0L;
    }

    /** Called from the client tick; rate-limited so it is cheap. */
    public void tick(Sink sink) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.getNetHandler() == null) return;

        long now = System.currentTimeMillis();
        long interval = targets.config().scanIntervalMs;
        if (now - lastScan < interval) return;
        lastScan = now;

        Collection<NetworkPlayerInfo> entries = mc.getNetHandler().getPlayerInfoMap();
        for (NetworkPlayerInfo info : entries) {
            if (info == null || info.getGameProfile() == null) continue;
            String name = info.getGameProfile().getName();
            if (name == null || !targets.isTarget(name)) continue;
            if (now - lastHit < 5000L) continue;
            lastHit = now;
            sink.onTargetSeen(name);
            return;
        }
    }

    /** Fallback for lobbies that mask tab entries: scrape visible chat text. */
    public void checkChatLine(IChatComponent line, Sink sink) {
        if (line == null) return;
        String plain = line.getUnformattedText();
        if (plain == null) return;

        for (String token : plain.split("[^A-Za-z0-9_]")) {
            if (token.length() < 3) continue;
            if (targets.isTarget(token)) {
                sink.onTargetSeen(token);
                return;
            }
        }
    }
}
