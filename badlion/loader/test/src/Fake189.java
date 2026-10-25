import fx.sniper.bl.Bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline smoke test for the Badlion loader.
 *
 * <p>Builds a fake 1.8.9 client out of notch-named classes ({@code ave}, {@code bew},
 * {@code bcy}, ...) with the exact member shapes the loader discovers, then runs
 * {@link Bootstrap#install(String)} against it and asserts what lands in
 * {@code state.txt}, {@code probe.txt} and the player's sent-command list.
 *
 * <p>This is not a substitute for running inside Minecraft — it proves the
 * structural discovery, the sidebar/header parsing, the bridge whitelist and the
 * join path, all of which are ordinary Java and can be checked without the game.
 * What it cannot prove is that the notch names match the shipping jar; those come
 * from MCP 9.19 {@code joined.srg}.
 */
public final class Fake189 {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path dir = ownDir();
        for (String f : new String[] { "state.txt", "probe.txt", "blsnip.log", "snipe_cmd.txt",
            "snipe_ui.txt", "ui_status.txt", "blsnip.properties" }) {
            Files.deleteIfExists(dir.resolve(f));
        }
        write(dir.resolve("targets.txt"), "TargetPlayer1\n");
        write(dir.resolve("snipe_cmd.txt"), "/play bedwars_eight_two\n");

        // ---- fake client -------------------------------------------------
        ave mc = new ave();
        ave.S = mc;

        avo gui = new avo();
        awh overlay = new awh();
        overlay.header = new fa("\u00a77You are playing on \u00a7bmini42AB \u00a77- \u00a7eBed Wars");
        overlay.footer = new fa("\u00a7ewww.hypixel.net");
        gui.tab = overlay;
        mc.ingameGUI = gui;

        auo board = new auo();
        board.objective = new auk();
        board.scores = new ArrayList<aum>();
        for (String line : new String[] { "BED WARS", "Mode: Doubles", "Aquarium", "Players: 8" }) {
            aum score = new aum();
            score.name = line;
            board.scores.add(score);
        }
        // Vanilla draws a sidebar line as team.formatString(entryName), so one entry is
        // supplied the way Hypixel ships them: invisible-ish name, visible text in the
        // team prefix. The loader has to render it instead of printing the raw name.
        aul mapTeam = new aul();
        mapTeam.prefix = "Map: ";
        board.teamsByPlayer.put("Aquarium", mapTeam);
        adm world = new adm();
        world.board = board;
        mc.theWorld = world;

        bew player = new bew();
        bcy handler = new bcy();
        player.sendQueue = handler;
        mc.thePlayer = player;

        handler.playerInfoMap.put("a", new bdc(new com.mojang.authlib.GameProfile("TargetPlayer1")));
        handler.playerInfoMap.put("b", new bdc(new com.mojang.authlib.GameProfile("SomeoneElse")));

        // ---- run ----------------------------------------------------------
        Bootstrap.install("smoke");

        // Menu save path: the screen writes snipe_ui.txt, the worker picks it up.
        write(dir.resolve("snipe_ui.txt"),
            "target=TargetFromMenu\napiKey=\npartyJoin=true\nautoQueue=false\n");

        Thread.sleep(2500);

        String state = read(dir.resolve("state.txt"));
        String probe = read(dir.resolve("probe.txt"));
        String props = read(dir.resolve("blsnip.properties"));

        check("menu save wrote target", props.contains("target=TargetFromMenu"));
        check("menu save kept comments", props.contains("# blsnip loader"));
        check("menu save kept autoJoin", props.contains("autoJoin=true"));
        check("menu status feed mirrored", !read(dir.resolve("ui_status.txt")).isEmpty());
        check("menu status feed live", !fx.sniper.bl.Ui.lines().isEmpty());
        check("toast raised on bind", gui.titleMain != null);

        check("state written", !state.isEmpty());
        check("bound", state.contains("bound=true"));
        check("inWorld", state.contains("inWorld=true"));
        check("server id from tab header", state.contains("server=mini42AB"));
        check("mode from sidebar", state.contains("mode=Doubles"));
        check("map from sidebar", state.contains("map=Aquarium"));
        check("target seen in tab list", state.contains("seen=TargetPlayer1"));
        check("tab count", state.contains("tab=2"));

        check("probe has sidebar", probe.contains("|Map: Aquarium"));
        check("sidebar renders team prefix", !probe.contains("\n  |Aquarium\n"));
        check("probe has tab names", probe.contains("TargetPlayer1"));
        check("probe has header", probe.contains("mini42AB"));

        check("bridge command relayed", player.sent.contains("/play bedwars_eight_two"));
        check("join sent for target", player.sent.contains("/p join TargetPlayer1"));
        check("announced in chat", joined(player.chat).contains("TargetPlayer1"));

        // A command outside the whitelist must never reach the server.
        write(dir.resolve("snipe_cmd.txt"), "/op me\n");
        Thread.sleep(900);
        check("non-whitelisted bridge command blocked", !player.sent.contains("/op me"));

        System.out.println(failures == 0 ? "SMOKE PASS" : "SMOKE FAIL (" + failures + ")");
        if (failures != 0) {
            System.out.println("----- blsnip.log -----");
            System.out.println(read(dir.resolve("blsnip.log")));
            System.exit(1);
        }
    }

    private static String joined(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        return sb.toString();
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            System.out.println("  ok   " + label);
        } else {
            failures++;
            System.out.println("  FAIL " + label);
        }
    }

    private static Path ownDir() throws Exception {
        Path path = Paths.get(Fake189.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Files.isDirectory(path) ? path : path.getParent();
    }

    private static void write(Path path, String text) throws Exception {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private Fake189() {
    }
}

// ---------------------------------------------------------------- fake game
// Package-private on purpose: only reflection touches these, and the notch names
// are the whole point.

/** net.minecraft.client.Minecraft */
class ave {

    static ave S;

    bew thePlayer;
    adm theWorld;
    avo ingameGUI;
    axu m;

    /** Minecraft.addScheduledTask — runs inline, like the client thread would. */
    void addScheduledTask(Runnable task) {
        task.run();
    }

    /** Minecraft.displayGuiScreen */
    void a(axu screen) {
        this.m = screen;
    }
}

/** net.minecraft.client.gui.GuiScreen — only the shape the loader resolves. */
class axu {
}

/** net.minecraft.client.entity.EntityPlayerSP */
class bew {

    bcy sendQueue;

    final List<String> sent = new ArrayList<String>();
    final List<String> chat = new ArrayList<String>();

    void sendChatMessage(String message) {
        sent.add(message);
    }

    void addChatMessage(eu component) {
        chat.add(component.getUnformattedText());
    }
}

/** net.minecraft.client.network.NetHandlerPlayClient */
class bcy {

    final Map<String, bdc> playerInfoMap = new HashMap<String, bdc>();

    java.util.Collection<bdc> getPlayerInfoMap() {
        return playerInfoMap.values();
    }
}

/** net.minecraft.client.network.NetworkPlayerInfo */
class bdc {

    private final com.mojang.authlib.GameProfile profile;

    bdc(com.mojang.authlib.GameProfile profile) {
        this.profile = profile;
    }

    com.mojang.authlib.GameProfile getGameProfile() {
        return profile;
    }
}

/** net.minecraft.client.gui.GuiIngame */
class avo {

    awh tab;

    String titleMain;
    String titleSub;
    String record;

    awh getTabList() {
        return tab;
    }

    /** GuiIngame.displayTitle */
    void a(String main, String sub, int a, int b, int c) {
        this.titleMain = main;
        this.titleSub = sub;
    }

    /** GuiIngame.setRecordPlaying */
    void a(String line, boolean aboveHotbar) {
        this.record = line;
    }
}

/** net.minecraft.client.gui.GuiPlayerTabOverlay */
class awh {

    eu header;
    eu footer;
}

/** net.minecraft.scoreboard.Scoreboard */
class auo {

    auk objective;
    List<aum> scores;

    /** player name -> team, the map the client uses when drawing a line. */
    final Map<String, aul> teamsByPlayer = new HashMap<String, aul>();

    auk getObjectiveInDisplaySlot(int slot) {
        return objective;
    }

    java.util.Collection<aum> getSortedScores(auk objective) {
        return scores;
    }
}

/** net.minecraft.scoreboard.ScorePlayerTeam */
class aul {

    String prefix = "";
    String suffix = "";

    String formatString(String name) {
        return prefix + name + suffix;
    }
}

/** net.minecraft.scoreboard.ScoreObjective */
class auk {
}

/** net.minecraft.scoreboard.Score */
class aum {

    String name;

    String getPlayerName() {
        return name;
    }
}

/** net.minecraft.world.World */
class adm {

    auo board;

    auo getScoreboard() {
        return board;
    }
}

/** net.minecraft.util.IChatComponent */
interface eu {

    String getUnformattedText();
}

/** net.minecraft.util.ChatComponentText */
class fa implements eu {

    private final String text;

    fa(String text) {
        this.text = text;
    }

    @Override
    public String getUnformattedText() {
        return text;
    }
}
