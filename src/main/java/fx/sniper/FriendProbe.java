package fx.sniper;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads /f list output.
 *
 * Hypixel prints "Currently playing: Bed Wars" per friend and that line is not
 * gated by the target's public-API toggle, which makes this the only free
 * location hint available without a second account.
 */
public final class FriendProbe {

    private static final Pattern PLAYING = Pattern.compile(
        "(?i)^\\s*(?:\\[[^\\]]{1,16}\\]\\s*)*(\\w{3,16})\\s+is\\s+currently\\s+(?:playing|in)\\s+(.+?)\\.?\\s*$");

    private static final long PROBE_INTERVAL_MS = 60_000L;

    private final SniperConfig config;
    private long lastProbeAt;

    private volatile String lastFriend;
    private volatile String lastGame;

    public FriendProbe(SniperConfig config) {
        this.config = config;
    }

    public void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !config.friendProbe) return;

        long now = System.currentTimeMillis();
        if (now - lastProbeAt < PROBE_INTERVAL_MS) return;
        lastProbeAt = now;
        mc.thePlayer.sendChatMessage("/f list");
    }

    public void onChat(IChatComponent msg) {
        if (msg == null) return;
        String plain = msg.getUnformattedText();
        if (plain == null) return;

        Matcher m = PLAYING.matcher(plain);
        if (!m.find()) return;

        String name = m.group(1);
        String game = m.group(2);
        lastFriend = name;
        lastGame = game;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                "\u00a7b[sniper/f] \u00a7f" + name + " \u00a77-> \u00a7e" + game));
        }
    }

    public String lastFriend() {
        return lastFriend;
    }

    public String lastGame() {
        return lastGame;
    }
}
