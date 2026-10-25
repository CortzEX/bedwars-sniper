package fx.sniper;

import fx.sniper.gui.MainHUD;
import fx.sniper.gui.SniperGui;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.nio.file.Path;

/**
 * Client-side entry point.
 *
 * Everything here is passive: read the tab list, read the scoreboard, read the
 * bridge file. The only outbound traffic is /p join when a target is already in
 * the same instance, plus /f list once a minute when friend probing is on.
 */
@Mod(modid = "bedwarssniper", name = "Bedwars Sniper", version = "1.2.0", acceptableRemoteVersions = "*")
public final class SniperMod {

    @Mod.Instance("bedwarssniper")
    public static SniperMod instance;

    private TargetManager targets;
    private TabScanner scanner;
    private JoinHandler joiner;
    private LobbyHopper hopper;
    private FriendProbe friends;
    private MainHUD hud;
    private CommandBridge bridge;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        File cfgDir = new File(Minecraft.getMinecraft().mcDataDir, "config");
        if (!cfgDir.isDirectory() && !cfgDir.mkdirs()) {
            System.err.println("[sniper] cannot create config dir");
            return;
        }

        SniperConfig cfg = new SniperConfig(cfgDir);
        targets = new TargetManager(cfg);
        scanner = new TabScanner(targets);
        joiner = new JoinHandler(targets);
        hopper = new LobbyHopper(cfg);
        friends = new FriendProbe(cfg);
        hud = new MainHUD(targets);

        Path bridgePath = new File(cfgDir, "snipe_cmd.txt").toPath();
        bridge = new CommandBridge(bridgePath);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(hud);
        FMLCommonHandler.instance().bus().register(this);

        System.out.println("[sniper] loaded, bridge=" + bridgePath);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || targets == null) return;

        if (Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) && mc.currentScreen == null) {
            mc.displayGuiScreen(new SniperGui(targets));
        }

        final boolean[] hit = { false };

        scanner.tick(name -> {
            hit[0] = true;
            hud.markSeen(name, "tab");
            joiner.handle(name);
        });

        hopper.tick(hit[0]);
        if (hit[0]) hopper.reset();

        friends.tick();
        bridge.tick();

        String sid = ScoreboardProbe.serverId();
        if (sid != null) hud.setStatus("\u00a7a" + sid);
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent ev) {
        if (ev.type != 0) return;
        if (scanner != null) {
            scanner.checkChatLine(ev.message, name -> {
                hud.markSeen(name, "chat");
                joiner.handle(name);
            });
        }
        if (friends != null) friends.onChat(ev.message);

        String plain = ev.message.getUnformattedText();
        if (plain != null && plain.contains("Sending you to mini")) {
            targets.config().lastLobbyAt = System.currentTimeMillis();
        }
    }

    /** Exposed for the GUI: current target count. */
    public TargetManager targets() {
        return targets;
    }

    /** Small helper used by loader sanity checks. */
    public static void say(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(msg));
        }
    }
}
