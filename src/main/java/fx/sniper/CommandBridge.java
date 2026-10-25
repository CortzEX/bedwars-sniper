package fx.sniper;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Executes commands handed over by the external snipe engine.
 *
 * The engine writes a single line ("/play bedwars_eight_one") and this polls the
 * file every client tick. Anything that is not one of the expected queue or
 * party commands is rejected, so a stray write can never fire arbitrary chat.
 */
public final class CommandBridge {

    private static final int MAX_LEN = 64;

    private final Path file;
    private long lastModified;

    public CommandBridge(Path file) {
        this.file = file;
        this.lastModified = 0L;
    }

    public void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || file == null) return;

        try {
            if (!Files.isRegularFile(file)) return;

            long mod = Files.getLastModifiedTime(file).toMillis();
            if (mod == lastModified) return;
            lastModified = mod;

            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (!isAllowed(raw)) return;

            mc.thePlayer.sendChatMessage(raw);
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a78[bridge] \u00a7f" + raw));

            // Consume the command so it never fires twice.
            Files.write(file, new byte[0]);
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            System.err.println("[bridge] " + e.getMessage());
        }
    }

    private static boolean isAllowed(String raw) {
        if (raw.isEmpty() || raw.length() > MAX_LEN) return false;
        if (raw.charAt(0) != '/') return false;
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) return false;
        return raw.startsWith("/play ")
            || raw.startsWith("/lobby")
            || raw.startsWith("/p join ")
            || raw.startsWith("/swaplobby ");
    }
}
