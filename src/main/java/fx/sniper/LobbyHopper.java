package fx.sniper;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import java.util.Random;

/**
 * Cycles Bedwars lobbies so the tab scanner gets a chance to see a target that
 * never exposes itself through the Hypixel API.
 *
 * DISABLED BY DEFAULT. On a main account this is macro behaviour and reads as
 * such to anti-cheat. Enable it only on a throwaway account.
 *
 * Rate limits that matter: /swaplobby triggers a server transfer, ~4s before the
 * tab list is stable, and Hypixel kicks for "Too many requests" below ~6s.
 */
public final class LobbyHopper {

    private static final long HOP_INTERVAL_MS = 6500L;
    private static final int LOBBY_COUNT = 40;

    private final SniperConfig config;
    private final Random random = new Random();

    private long lastHopAt;
    private int hopsDone;
    private boolean finished;

    public LobbyHopper(SniperConfig config) {
        this.config = config;
    }

    public void reset() {
        hopsDone = 0;
        finished = false;
        lastHopAt = 0L;
    }

    public void tick(boolean targetVisible) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        if (targetVisible) {
            if (hopsDone > 0) {
                say("\u00a7a[sniper] \u00a7ftarget spotted after \u00a7e" + hopsDone + "\u00a7f hop(s)");
            }
            finished = true;
            return;
        }
        if (!config.lobbyHop || finished) return;

        long now = System.currentTimeMillis();
        if (now - lastHopAt < HOP_INTERVAL_MS) return;

        if (hopsDone >= config.hopMax) {
            say("\u00a7c[sniper] \u00a7fhop limit reached (" + config.hopMax + ")");
            finished = true;
            return;
        }

        lastHopAt = now;
        hopsDone++;

        if (now - config.lastLobbyAt < 120_000L) {
            mc.thePlayer.sendChatMessage("/swaplobby " + (1 + random.nextInt(LOBBY_COUNT)));
        } else {
            mc.thePlayer.sendChatMessage("/play bedwars");
        }
        say("\u00a77[sniper] hop " + hopsDone + "/" + config.hopMax);
    }

    public int hopsDone() {
        return hopsDone;
    }

    private void say(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(msg));
        }
    }
}
