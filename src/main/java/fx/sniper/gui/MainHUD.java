package fx.sniper.gui;

import fx.sniper.ScoreboardProbe;
import fx.sniper.TargetManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;

/**
 * Top-left overlay: target count, last sighting, server id and map.
 *
 * Read-only by design — this overlay never sends a command.
 */
public final class MainHUD {

    private final TargetManager targets;

    private volatile String lastSeenName;
    private volatile long lastSeenAt;
    private volatile String lastSource = "tab";
    private volatile String statusLine = "\u00a77waiting";

    public MainHUD(TargetManager targets) {
        this.targets = targets;
    }

    public void markSeen(String name, String source) {
        lastSeenName = name;
        lastSeenAt = System.currentTimeMillis();
        lastSource = source;
        statusLine = "\u00a7cTARGET IN INSTANCE";
    }

    public void setStatus(String status) {
        statusLine = status;
    }

    @SubscribeEvent
    public void onRenderText(RenderGameOverlayEvent.Text ev) {
        if (ev.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.gameSettings.showDebugInfo) return;

        List<String> out = ev.left;

        out.add("");
        out.add("\u00a78[\u00a7bsniper\u00a78]");
        out.add("\u00a77targets: \u00a7f" + targets.snapshot().size());
        out.add("\u00a77state: " + statusLine);

        String sid = ScoreboardProbe.serverId();
        String map = ScoreboardProbe.mapName();
        if (sid != null) out.add("\u00a77server: \u00a7e" + sid);
        if (map != null) out.add("\u00a77map: \u00a7a" + map);

        if (lastSeenName != null) {
            long ago = (System.currentTimeMillis() - lastSeenAt) / 1000L;
            if (ago < 120L) {
                out.add("\u00a77seen: \u00a7c" + lastSeenName
                    + "\u00a77 (" + lastSource + ", " + ago + "s)");
            }
        }
    }
}
