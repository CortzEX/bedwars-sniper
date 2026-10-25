package fx.sniper.bl;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forge-free entry point for Badlion Client.
 *
 * <p>Two ways in, one body:
 * <ul>
 *   <li>{@code -javaagent:C:\blsnip\blsnip-loader.jar} — the JVM calls
 *       {@link #premain(String, Instrumentation)} before any game class loads.
 *       Nothing touches the process, so no anti-cheat sees an injection.</li>
 *   <li>{@code inject.exe} + {@code blagent.dll} — the DLL attaches to the running
 *       VM and calls {@link #install()}. Blocked once Badlion Anti-Cheat has taken
 *       a handle on the game, which is why the agent path is the recommended one.</li>
 * </ul>
 *
 * <p>Everything runs on a daemon thread. Game calls are marshalled onto the client
 * thread through {@code Minecraft.addScheduledTask} when it exists.
 *
 * <h3>Files in the work directory</h3>
 * <pre>
 * targets.txt     one username per line, # comments
 * blsnip.properties  autoJoin, autoQueue, queueCommand, pollMs, joinGapMs, ...
 * snipe_cmd.txt   command bridge, same file the Python engine writes
 * state.txt       server / mode / map / seen, rewritten on change
 * probe.txt       raw header, footer, sidebar, tab list — first-run verification
 * blsnip.log      everything
 * </pre>
 *
 * <p>Work directory is {@code %BLSNIP_DIR%}, else the jar's own directory.
 */
public final class Bootstrap {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final long INSTANCE_WINDOW_MS = 20_000L;

    private static volatile Thread worker;

    private Bootstrap() {
    }

    // -------------------------------------------------------------- entry

    public static void premain(String args, Instrumentation inst) {
        install(args);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(args);
    }

    /** Called by blagent.dll. Signature is fixed: {@code ()V}. */
    public static void install() {
        install(null);
    }

    public static synchronized void install(String args) {
        if (!INSTALLED.compareAndSet(false, true)) {
            System.out.println("[blsnip] already installed");
            return;
        }
        try {
            Path dir = workDir();
            Log.init(dir);
            Log.info("starting — dir=" + dir + " args=" + args
                + " java=" + System.getProperty("java.version"));

            Thread t = new Thread(new Runner(dir), "blsnip-worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            t.start();
            worker = t;

            Log.info("worker started");
        } catch (Throwable e) {
            Log.error("install failed", e);
            INSTALLED.set(false);
        }
    }

    public static boolean isInstalled() {
        Thread t = worker;
        return INSTALLED.get() && t != null && t.isAlive();
    }

    private static Path workDir() {
        String env = System.getenv("BLSNIP_DIR");
        if (env != null && !env.trim().isEmpty()) return Paths.get(env.trim());

        try {
            CodeSource source = Bootstrap.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                URL url = source.getLocation();
                if (url != null && "file".equals(url.getProtocol())) {
                    Path path = Paths.get(url.toURI());
                    if (!Files.isDirectory(path)) path = path.getParent();
                    if (path != null) return path;
                }
            }
        } catch (Throwable e) {
            Log.warn("cannot resolve own location: " + e);
        }
        return Paths.get(System.getProperty("user.home", "."), ".blsnip");
    }

    // ------------------------------------------------------------- worker

    private static final class Runner implements Runnable {

        private final Path dir;
        private final Path targetsPath;
        private final Path propsPath;
        private final Path bridgePath;
        private final Path statePath;
        private final Path probePath;

        private volatile Set<String> targets = Collections.emptySet();
        private volatile Cfg cfg = new Cfg();

        private long targetsStamp = -1L;
        private long propsStamp = -1L;

        private Mc mc;
        private long nextBindAt;
        private int bindAttempts;

        private long nextScanAt;
        private long scanQueuedAt;
        private long lastProbeAt;
        private long bridgeStamp = -1L;

        private String lastState = "";
        private String lastBridge = "";
        private String lastSeen;

        private long lastJoinAt;
        private long lastJoinedAt;
        private long windowStart = System.currentTimeMillis();
        private String lastJoined = "";
        private final Map<String, Integer> attempts = new HashMap<String, Integer>();

        private volatile Snapshot snap = Snapshot.OFFLINE;

        Runner(Path dir) {
            this.dir = dir;
            this.targetsPath = dir.resolve("targets.txt");
            this.propsPath = dir.resolve("blsnip.properties");
            this.bridgePath = dir.resolve("snipe_cmd.txt");
            this.statePath = dir.resolve("state.txt");
            this.probePath = dir.resolve("probe.txt");
        }

        @Override
        public void run() {
            Log.info("worker alive, waiting for the game");
            while (true) {
                try {
                    step();
                } catch (Throwable e) {
                    Log.error("worker step failed", e);
                }
                try {
                    Thread.sleep(Math.max(100, cfg.pollMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.warn("worker interrupted, exiting");
                    return;
                }
            }
        }

        private void step() {
            reloadConfig();
            ensureBound();
            if (mc == null || !mc.isBound()) {
                writeState();
                return;
            }
            pumpBridge();
            scan();
            writeState();
            writeProbe();
        }

        // -------------------------------------------------------- files

        private void reloadConfig() {
            long tStamp = stamp(targetsPath);
            if (tStamp != targetsStamp) {
                targetsStamp = tStamp;
                targets = readTargets();
            }
            long pStamp = stamp(propsPath);
            if (pStamp != propsStamp) {
                propsStamp = pStamp;
                cfg = Cfg.load(propsPath);
            }
        }

        private Set<String> readTargets() {
            if (!Files.isRegularFile(targetsPath)) {
                writeFile(targetsPath,
                    "# One Minecraft username per line. Case does not matter.\n"
                        + "# Lines starting with # are ignored.\n"
                        + "# TargetPlayer1\n");
                Log.info("wrote targets.txt template");
                return Collections.emptySet();
            }

            Set<String> out = new HashSet<String>();
            List<String> ignored = new ArrayList<String>();
            for (String raw : readAll(targetsPath).split("\\r?\\n")) {
                String line = raw;
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!line.matches("[A-Za-z0-9_]{3,16}")) {
                    ignored.add(line);
                    continue;
                }
                if (out.size() >= 64) break;
                out.add(line.toLowerCase(Locale.ROOT));
            }
            if (!ignored.isEmpty()) Log.warn("targets.txt: ignored " + ignored);
            Log.info("targets loaded: " + out.size());
            return Collections.unmodifiableSet(out);
        }

        // --------------------------------------------------------- bind

        private void ensureBound() {
            if (mc != null && mc.isBound()) return;
            long now = System.currentTimeMillis();
            if (now < nextBindAt) return;
            nextBindAt = now + 2000L;
            bindAttempts++;

            if (mc == null) {
                ClassLoader loader = findGameLoader();
                if (loader == null) {
                    if (bindAttempts % 15 == 1) {
                        Log.info("waiting: Minecraft class not visible on any thread context loader");
                    }
                    return;
                }
                Log.info("game class loader: " + loader.getClass().getName());
                mc = new Mc(loader);
            }
            if (mc.bind()) {
                Log.info("bound after " + bindAttempts + " attempt(s)");
                mc.chat("\u00a7a[blsnip] \u00a7fready \u2014 \u00a7e" + targets.size()
                    + "\u00a7f target(s)");
            }
        }

        private ClassLoader findGameLoader() {
            Notch notch = new Notch();
            try {
                for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                    ClassLoader cl = entry.getKey().getContextClassLoader();
                    if (cl != null && notch.canLoad(cl, "Minecraft")) return cl;
                }
            } catch (Throwable e) {
                Log.once("loader.threads", "thread walk failed: " + e);
            }
            for (ClassLoader cl = Bootstrap.class.getClassLoader(); cl != null; cl = cl.getParent()) {
                if (notch.canLoad(cl, "Minecraft")) return cl;
            }
            ClassLoader system = ClassLoader.getSystemClassLoader();
            if (system != null && notch.canLoad(system, "Minecraft")) return system;
            return null;
        }

        // -------------------------------------------------------- bridge

        private void pumpBridge() {
            if (!Files.isRegularFile(bridgePath)) return;

            long modified;
            try {
                modified = Files.getLastModifiedTime(bridgePath).toMillis();
            } catch (Exception e) {
                return;
            }
            if (modified == bridgeStamp) return;
            bridgeStamp = modified;

            String raw = readAll(bridgePath).trim();
            if (raw.isEmpty()) return;

            String line = raw.split("\\r?\\n", 2)[0].trim();
            if (line.equals(lastBridge)) {
                truncate(bridgePath);
                return;
            }
            if (!allowed(line)) {
                Log.warn("bridge rejected: " + line);
                truncate(bridgePath);
                return;
            }
            if (!mc.inWorld()) {
                Log.once("bridge.world", "bridge command held: no world yet");
                return;
            }

            lastBridge = line;
            dispatch(line);
            truncate(bridgePath);
        }

        private void dispatch(final String command) {
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    if (!mc.sendChat(command)) {
                        Log.warn("bridge send failed: " + command);
                        return;
                    }
                    Log.info("bridge sent: " + command);
                    mc.chat("\u00a78[bridge] \u00a7f" + command);
                }
            };
            onClientThread(task);
        }

        private void onClientThread(Runnable task) {
            if (mc.schedule(task)) return;
            // No scheduler (pre-1.8 client or a client that renamed it).
            Log.once("schedule.missing", "no addScheduledTask — running game calls on the worker thread");
            task.run();
        }

        private static boolean allowed(String command) {
            if (command.isEmpty() || command.length() > 64) return false;
            if (command.charAt(0) != '/') return false;
            if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return false;
            return command.startsWith("/play ")
                || command.startsWith("/lobby")
                || command.startsWith("/p join ")
                || command.startsWith("/swaplobby ")
                || command.startsWith("/rejoin");
        }

        // --------------------------------------------------------- scan

        private void scan() {
            long now = System.currentTimeMillis();
            if (now < nextScanAt) return;
            if (now - scanQueuedAt < 3000L) return; // one outstanding scan at a time
            nextScanAt = now + cfg.pollMs;
            scanQueuedAt = now;

            onClientThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        scanNow();
                    } catch (Throwable e) {
                        Log.error("scan failed", e);
                    }
                }
            });
        }

        private void scanNow() {
            List<String> tab = mc.tabNames();
            List<String> sidebar = mc.sidebar();
            String header = mc.tabText();
            String footer = mc.footerText();
            String server = mc.serverId();
            String mode = mc.modeName();
            String map = mc.mapName();
            boolean inWorld = mc.inWorld();

            Set<String> wanted = targets;
            if (!wanted.isEmpty()) {
                for (String name : tab) {
                    if (name == null || name.isEmpty()) continue;
                    if (!wanted.contains(name.toLowerCase(Locale.ROOT))) continue;
                    lastSeen = name;
                    join(name);
                }
            }

            snap = new Snapshot(true, inWorld, server, mode, map, header, footer,
                bounded(tab, 200), bounded(sidebar, 40), lastSeen, wanted.size());
        }

        private synchronized void join(String name) {
            long now = System.currentTimeMillis();
            if (name.equalsIgnoreCase(lastJoined) && now - lastJoinedAt < INSTANCE_WINDOW_MS) return;
            if (now - lastJoinAt < cfg.joinGapMs) return;

            long windowMs = Math.max(1, cfg.windowMinutes) * 60_000L;
            if (now - windowStart > windowMs) {
                attempts.clear();
                windowStart = now;
            }

            String key = name.toLowerCase(Locale.ROOT);
            int used = attempts.containsKey(key) ? attempts.get(key) : 0;
            if (used >= cfg.maxPerTarget) {
                Log.once("cap:" + key, "attempt cap reached for " + name);
                return;
            }
            attempts.put(key, used + 1);

            lastJoinAt = now;
            lastJoined = name;
            lastJoinedAt = now;

            announce("\u00a7a[snip] \u00a7f" + name + " \u00a77(" + (used + 1) + "/"
                + cfg.maxPerTarget + ")");
            if (!cfg.autoJoin) return;

            mc.sendChat("/p join " + name);
            if (cfg.autoQueue && cfg.queueCommand != null && !cfg.queueCommand.isEmpty()) {
                mc.sendChat("/" + stripSlash(cfg.queueCommand));
            }
        }

        private void announce(String message) {
            if (cfg.chat) mc.chat(message);
            Log.info(Mc.strip(message));
        }

        // -------------------------------------------------------- state

        private void writeState() {
            Snapshot s = snap;
            String rendered = s.render(targets.size());
            if (rendered.equals(lastState)) return;
            lastState = rendered;
            writeFile(statePath, rendered);
        }

        private void writeProbe() {
            long now = System.currentTimeMillis();
            if (now - lastProbeAt < Math.max(1, cfg.probeSeconds) * 1000L) return;
            lastProbeAt = now;
            writeFile(probePath, snap.probe());
        }

        private long stamp(Path path) {
            try {
                return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
            } catch (Exception e) {
                return 0L;
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private static List<String> bounded(List<String> items, int max) {
        if (items == null) return Collections.emptyList();
        if (items.size() <= max) return new ArrayList<String>(items);
        return new ArrayList<String>(items.subList(0, max));
    }

    private static String stripSlash(String command) {
        String out = command.trim();
        while (out.startsWith("/")) out = out.substring(1);
        return out;
    }

    private static String readAll(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeFile(Path path, String text) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Log.once("write:" + path.getFileName(), "cannot write " + path + ": " + e);
        }
    }

    private static void truncate(Path path) {
        try {
            Files.write(path, new byte[0], StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            Log.once("truncate", "cannot clear " + path + ": " + e);
        }
    }

    // --------------------------------------------------------- snapshots

    /** Immutable read of the client, safe to touch from the worker thread. */
    private static final class Snapshot {

        static final Snapshot OFFLINE = new Snapshot(false, false, null, null, null,
            "", "", Collections.<String>emptyList(), Collections.<String>emptyList(), null, 0);

        private final boolean bound;
        private final boolean inWorld;
        private final String server;
        private final String mode;
        private final String map;
        private final String header;
        private final String footer;
        private final List<String> tab;
        private final List<String> sidebar;
        private final String seen;
        private final int targetCount;

        Snapshot(boolean bound, boolean inWorld, String server, String mode, String map,
                 String header, String footer, List<String> tab, List<String> sidebar,
                 String seen, int targetCount) {
            this.bound = bound;
            this.inWorld = inWorld;
            this.server = server;
            this.mode = mode;
            this.map = map;
            this.header = header;
            this.footer = footer;
            this.tab = tab;
            this.sidebar = sidebar;
            this.seen = seen;
            this.targetCount = targetCount;
        }

        String render(int targets) {
            StringBuilder sb = new StringBuilder();
            sb.append("bound=").append(bound).append('\n');
            sb.append("inWorld=").append(inWorld).append('\n');
            sb.append("server=").append(server).append('\n');
            sb.append("mode=").append(mode).append('\n');
            sb.append("map=").append(map).append('\n');
            sb.append("targets=").append(targets).append('\n');
            sb.append("seen=").append(seen).append('\n');
            sb.append("tab=").append(tab.size()).append('\n');
            sb.append("at=").append(System.currentTimeMillis()).append('\n');
            return sb.toString();
        }

        String probe() {
            StringBuilder sb = new StringBuilder();
            sb.append("inWorld=").append(inWorld).append('\n');
            sb.append("server=").append(server).append('\n');
            sb.append("mode=").append(mode).append('\n');
            sb.append("map=").append(map).append('\n');
            sb.append("header=").append(header).append('\n');
            sb.append("footer=").append(footer).append('\n');
            sb.append("sidebar:\n");
            for (String line : sidebar) sb.append("  |").append(line).append('\n');
            sb.append("tab(").append(tab.size()).append("):\n");
            for (String name : tab) sb.append("  ").append(name).append('\n');
            return sb.toString();
        }
    }

    // ------------------------------------------------------------ config

    /** {@code blsnip.properties}, written with defaults on first run. */
    private static final class Cfg {

        boolean autoJoin = true;
        boolean autoQueue = false;
        boolean chat = true;
        String queueCommand = "play bedwars_eight_one";
        int pollMs = 500;
        int joinGapMs = 3000;
        int maxPerTarget = 5;
        int windowMinutes = 10;
        int probeSeconds = 10;

        static Cfg load(Path path) {
            Cfg cfg = new Cfg();
            if (!Files.isRegularFile(path)) {
                writeFile(path, defaults());
                Log.info("wrote blsnip.properties with defaults");
                return cfg;
            }
            Properties props = new Properties();
            try {
                props.load(new java.io.ByteArrayInputStream(
                    readAll(path).getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                Log.error("blsnip.properties unreadable, using defaults", e);
                return cfg;
            }
            cfg.autoJoin = bool(props, "autoJoin", cfg.autoJoin);
            cfg.autoQueue = bool(props, "autoQueue", cfg.autoQueue);
            cfg.chat = bool(props, "chat", cfg.chat);
            String queue = props.getProperty("queueCommand", cfg.queueCommand).trim();
            if (!queue.isEmpty()) cfg.queueCommand = queue;
            cfg.pollMs = clamp(intOf(props, "pollMs", cfg.pollMs), 100, 5000);
            cfg.joinGapMs = clamp(intOf(props, "joinGapMs", cfg.joinGapMs), 1000, 600_000);
            cfg.maxPerTarget = clamp(intOf(props, "maxPerTarget", cfg.maxPerTarget), 0, 60);
            cfg.windowMinutes = clamp(intOf(props, "windowMinutes", cfg.windowMinutes), 1, 1440);
            cfg.probeSeconds = clamp(intOf(props, "probeSeconds", cfg.probeSeconds), 1, 3600);
            return cfg;
        }

        private static String defaults() {
            return "# blsnip loader — restart-free, re-read when the file changes\n"
                + "# Send /p join to a detected target. Off = detect and report only.\n"
                + "autoJoin=true\n"
                + "# Queue Bedwars right after the party invite goes out.\n"
                + "autoQueue=false\n"
                + "queueCommand=play bedwars_eight_one\n"
                + "# Print detections into the in-game chat.\n"
                + "chat=true\n"
                + "pollMs=500\n"
                + "joinGapMs=3000\n"
                + "maxPerTarget=5\n"
                + "windowMinutes=10\n"
                + "probeSeconds=10\n";
        }

        private static boolean bool(Properties props, String key, boolean fallback) {
            String raw = props.getProperty(key);
            if (raw == null) return fallback;
            String v = raw.trim().toLowerCase(Locale.ROOT);
            if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on")) return true;
            if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) return false;
            return fallback;
        }

        private static int intOf(Properties props, String key, int fallback) {
            try {
                return Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim());
            } catch (Exception e) {
                return fallback;
            }
        }

        private static int clamp(int value, int min, int max) {
            return value < min ? min : (value > max ? max : value);
        }
    }
}
