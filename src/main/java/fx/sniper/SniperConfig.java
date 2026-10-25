package fx.sniper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted target list and behaviour flags. Lives outside the jar so it
 * survives mod updates.
 */
public final class SniperConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final transient File file;

    public final List<String> targets = new ArrayList<>();

    public boolean autoJoin = true;
    public boolean autoQueueBedwars = false;
    public String queueCommand = "play bedwars_eight_one";
    public long scanIntervalMs = 500L;

    /** Lobby hopping is OFF by default: on a main account it reads as a macro. */
    public boolean lobbyHop = false;
    public int hopMax = 0;

    public boolean friendProbe = true;

    /** Set by LobbyHopper so /swaplobby is only used where it resolves. */
    public transient long lastLobbyAt;

    public SniperConfig(File configDir) {
        this.file = new File(configDir, "bedwars-sniper.json");
        load();
    }

    public void load() {
        if (!file.isFile()) {
            save();
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            SniperConfig read = GSON.fromJson(json, SniperConfig.class);
            if (read == null) return;

            targets.clear();
            if (read.targets != null) targets.addAll(read.targets);

            autoJoin = read.autoJoin;
            autoQueueBedwars = read.autoQueueBedwars;
            queueCommand = read.queueCommand == null ? queueCommand : read.queueCommand;
            scanIntervalMs = read.scanIntervalMs > 0 ? read.scanIntervalMs : 500L;
            lobbyHop = read.lobbyHop;
            hopMax = Math.max(0, read.hopMax);
            friendProbe = read.friendProbe;
        } catch (IOException e) {
            System.err.println("[sniper] config load failed: " + e.getMessage());
        }
    }

    public void save() {
        try {
            Files.write(file.toPath(), GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[sniper] config save failed: " + e.getMessage());
        }
    }
}
