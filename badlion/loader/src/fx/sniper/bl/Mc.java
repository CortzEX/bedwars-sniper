package fx.sniper.bl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The whole game surface this loader needs, resolved by structure instead of by
 * name.
 *
 * <p>Obfuscation renames every member between point releases, so nothing below
 * looks up a member by its MCP name. Class names come from {@link Notch}; members
 * are matched on their signature shape:
 *
 * <ul>
 *   <li>Minecraft instance — the only static field of {@code Minecraft}'s own type</li>
 *   <li>{@code thePlayer} — the non-static field of type {@code EntityPlayerSP}</li>
 *   <li>connection — the field of type {@code NetHandlerPlayClient} in the player</li>
 *   <li>tab list entries — the {@code Map} field in the connection whose values are
 *       {@code NetworkPlayerInfo}; picked at runtime, because an empty map says
 *       nothing about its element type</li>
 *   <li>sidebar — {@code World} → {@code Scoreboard} → objective in display slot 1
 *       → sorted scores, each score's zero-argument {@code String}</li>
 *   <li>chat — a {@code (String)} constructor on the chat component, plus
 *       {@code (IChatComponent)} and {@code (String)} on the player</li>
 * </ul>
 *
 * <p>Every method is safe to call before the world exists: a missing piece returns
 * {@code null}/empty instead of throwing.
 */
public final class Mc {

    private static final int SIDEBAR_SLOT = 1;

    private final ClassLoader loader;
    private final Notch notch;

    private Class<?> mcClass;
    private Class<?> playerClass;
    private Class<?> netClass;
    private Class<?> infoClass;
    private Class<?> guiClass;
    private Class<?> tabClass;
    private Class<?> boardClass;
    private Class<?> objectiveClass;
    private Class<?> scoreClass;
    private Class<?> teamClass;
    private Class<?> worldClass;
    private Class<?> chatClass;
    private Class<?> chatTextClass;
    private Class<?> guiScreenClass;

    private Field mcInstanceField;
    private Field thePlayerField;
    private Field theWorldField;
    private Field guiField;
    private Field sendQueueField;
    private Field boardField;
    private Field scoreNameField;

    private Method scheduleMethod;
    private Method getTabListMethod;
    private Method getBoardMethod;
    private Method getObjectiveMethod;
    private Method getSortedScoresMethod;
    private Method scoreNameMethod;
    private Method teamFormatMethod;
    private Method addChatMethod;
    private Method sendChatMethod;
    private Method textMethod;
    private Method netGetterMethod;
    private Method profileMethod;
    private Method displayScreenMethod;
    private Method titleMethod;
    private Method recordMethod;

    private Constructor<?> chatTextCtor;

    private Field headerField;
    private Field footerField;
    private Field currentScreenField;

    private final List<Field> infoMapCandidates = new ArrayList<Field>();
    private Field infoMapField;

    private final List<Field> boardMapFields = new ArrayList<Field>();

    private String bindError = "not bound";
    private boolean bound;
    private boolean detailLogged;

    public Mc(ClassLoader loader) {
        this(loader, new Notch());
    }

    public Mc(ClassLoader loader, Notch notch) {
        this.loader = loader;
        this.notch = notch;
    }

    /** Writes the resolved member table once; retries stay quiet. */
    private void logResolved() {
        Log.info("methods on Minecraft    = " + Ref.allMethods(mcClass).size());
        Log.info("field mc.instance      = " + Ref.describe(mcInstanceField));
        Log.info("field thePlayer        = " + Ref.describe(thePlayerField));
        Log.info("field theWorld         = " + Ref.describe(theWorldField));
        Log.info("field ingameGUI        = " + Ref.describe(guiField));
        Log.info("field sendQueue        = " + Ref.describe(sendQueueField));
        Log.info("field board            = " + Ref.describe(boardField));
        Log.info("field header           = " + Ref.describe(headerField));
        Log.info("field footer           = " + Ref.describe(footerField));
        Log.info("field infoMap          = " + (infoMapField != null ? Ref.describe(infoMapField)
            : infoMapCandidates.size() + " candidate(s), picked on first read"));
        Log.info("meth  schedule         = " + Ref.describe(scheduleMethod));
        Log.info("meth  getTabList       = " + Ref.describe(getTabListMethod));
        Log.info("meth  getScoreboard    = " + Ref.describe(getBoardMethod));
        Log.info("meth  objective(slot)  = " + Ref.describe(getObjectiveMethod));
        Log.info("meth  sortedScores     = " + Ref.describe(getSortedScoresMethod));
        Log.info("meth  score.name       = " + Ref.describe(scoreNameMethod));
        Log.info("meth  team.format(name)= " + Ref.describe(teamFormatMethod));
        Log.info("meth  displayScreen    = " + Ref.describe(displayScreenMethod));
        Log.info("meth  title            = " + Ref.describe(titleMethod));
        Log.info("field currentScreen    = " + Ref.describe(currentScreenField));
        Log.info("field board maps       = " + boardMapFields.size());
        Log.info("meth  player.addChat   = " + Ref.describe(addChatMethod));
        Log.info("meth  player.sendChat  = " + Ref.describe(sendChatMethod));
        Log.info("meth  profile          = " + Ref.describe(profileMethod));
        Log.info("ctor  ChatComponent    = " + chatTextCtor);
    }

    // ------------------------------------------------------------------ bind

    /**
     * Resolves everything.
     *
     * @return true when the game is reachable and both the tab list and the sidebar
     *         can be read. Chat is optional (failures are logged, not fatal).
     */
    public boolean bind() {
        if (bound) return true;

        mcClass = notch.resolve(loader, "Minecraft");
        playerClass = notch.resolve(loader, "EntityPlayerSP");
        netClass = notch.resolve(loader, "NetHandlerPlayClient");
        infoClass = notch.resolve(loader, "NetworkPlayerInfo");
        guiClass = notch.resolve(loader, "GuiIngame");
        tabClass = notch.resolve(loader, "GuiPlayerTabOverlay");
        boardClass = notch.resolve(loader, "Scoreboard");
        objectiveClass = notch.resolve(loader, "ScoreObjective");
        scoreClass = notch.resolve(loader, "Score");
        teamClass = notch.resolve(loader, "ScorePlayerTeam");
        worldClass = notch.resolve(loader, "World");
        chatClass = notch.resolve(loader, "IChatComponent");
        chatTextClass = notch.resolve(loader, "ChatComponentText");
        guiScreenClass = notch.resolve(loader, "GuiScreen");

        String[] keys = { "Minecraft", "EntityPlayerSP", "NetHandlerPlayClient", "NetworkPlayerInfo",
            "GuiIngame", "GuiPlayerTabOverlay", "Scoreboard", "ScoreObjective", "ScorePlayerTeam",
            "Score", "World", "IChatComponent", "ChatComponentText", "GuiScreen" };
        if (!detailLogged) {
            for (String key : keys) {
                Log.info("class " + key + " = " + notch.resolvedName(loader, key)
                    + " (" + notch.notchName(key) + ")");
            }
        }

        if (mcClass == null) {
            bindError = "Minecraft class not visible from this loader";
            return false;
        }

        mcInstanceField = Ref.fieldByType(mcClass, mcClass, true);
        scheduleMethod = findScheduler();

        if (playerClass != null) {
            thePlayerField = Ref.fieldByType(mcClass, playerClass, false);
            sendQueueField = netClass == null ? null : Ref.fieldByType(playerClass, netClass, false);
            if (sendQueueField == null && netClass != null) {
                netGetterMethod = Ref.getter(playerClass, netClass);
            }
            addChatMethod = chatClass == null ? null : Ref.method(playerClass, chatClass, void.class);
            sendChatMethod = Ref.method(playerClass, String.class, void.class);
        }
        if (worldClass != null) {
            theWorldField = Ref.fieldAssignable(mcClass, worldClass, false);
        }
        if (guiClass != null) {
            guiField = Ref.fieldByType(mcClass, guiClass, false);
        }
        if (guiClass != null && tabClass != null) {
            getTabListMethod = Ref.getter(guiClass, tabClass);
            resolveTabFields();
        }
        if (worldClass != null && boardClass != null && objectiveClass != null) {
            getBoardMethod = Ref.getter(worldClass, boardClass);
            if (getBoardMethod == null) boardField = Ref.fieldByType(worldClass, boardClass, false);
        }
        if (boardClass != null && objectiveClass != null) {
            getObjectiveMethod = Ref.method(boardClass, int.class, objectiveClass);
        }
        if (boardClass != null && objectiveClass != null) {
            getSortedScoresMethod = sortedScoresMethod();
        }
        if (scoreClass != null) {
            scoreNameMethod = Ref.getter(scoreClass, String.class);
            if (scoreNameMethod == null) scoreNameField = Ref.fieldByType(scoreClass, String.class, false);
        }
        if (boardClass != null && teamClass != null) {
            // getPlayersTeam(String) shares its signature with getCreateTeam(String) and
            // getTeam(String), which differ only in which internal map they read — and one
            // of them creates a team as a side effect. Reading the maps directly avoids
            // picking blind.
            for (Field f : Ref.fieldsOfType(boardClass, Map.class, false)) {
                boardMapFields.add(Ref.accessible(f));
            }
            teamFormatMethod = Ref.method(teamClass, String.class, String.class);
        }
        if (guiScreenClass != null) {
            displayScreenMethod = Ref.method(mcClass, guiScreenClass, void.class);
            currentScreenField = Ref.fieldByType(mcClass, guiScreenClass, false);
        }
        if (guiClass != null) {
            titleMethod = Ref.method(guiClass,
                new Class<?>[] { String.class, String.class, int.class, int.class, int.class });
            recordMethod = Ref.method(guiClass, String.class, boolean.class);
        }
        if (netClass != null && infoClass != null) {
            for (Field f : Ref.allFields(netClass)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                infoMapCandidates.add(Ref.accessible(f));
            }
            if (infoMapCandidates.size() == 1) infoMapField = infoMapCandidates.get(0);
            resolveProfile();
        }
        if (chatTextClass != null) {
            try {
                chatTextCtor = chatTextClass.getDeclaredConstructor(String.class);
                if (chatTextCtor != null) chatTextCtor.setAccessible(true);
            } catch (Throwable ignored) {
                chatTextCtor = null;
            }
        }
        if (chatClass != null) {
            textMethod = Ref.noArgByName(chatClass, String.class,
                "getUnformattedText", "func_150260_c");
            if (textMethod == null) textMethod = Ref.getter(chatClass, String.class);
        }

        if (!detailLogged) {
            detailLogged = true;
            logResolved();
        }

        List<String> missing = new ArrayList<String>();
        if (mcInstanceField == null) missing.add("Minecraft.instance");
        if (thePlayerField == null) missing.add("thePlayer");
        if (theWorldField == null) missing.add("theWorld");
        if (guiField == null) missing.add("ingameGUI");
        if (getTabListMethod == null) missing.add("getTabList");
        if (headerField == null && footerField == null) missing.add("tab header");
        if (getBoardMethod == null && boardField == null) missing.add("scoreboard");
        if (getObjectiveMethod == null) missing.add("objective(slot)");
        if (getSortedScoresMethod == null) missing.add("sortedScores");
        if (scoreNameMethod == null && scoreNameField == null) missing.add("score.name");
        if (infoMapCandidates.isEmpty()) missing.add("playerInfoMap");

        if (!missing.isEmpty()) {
            bindError = "missing: " + join(missing, ", ");
            Log.once("bind:" + bindError, "bind incomplete — " + bindError);
            return false;
        }

        if (addChatMethod == null || chatTextCtor == null) {
            Log.warn("chat output unavailable — status goes to blsnip.log only");
        }

        bound = true;
        bindError = "ok";
        Log.info("bind complete");
        return true;
    }

    public boolean isBound() {
        return bound;
    }

    public String bindError() {
        return bindError;
    }

    private Method findScheduler() {
        for (Method m : Ref.allMethods(mcClass)) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != Runnable.class) continue;
            if (m.getReturnType() != void.class) continue;
            return Ref.accessible(m);
        }
        return null;
    }

    private Method sortedScoresMethod() {
        for (Method m : Ref.allMethods(boardClass)) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != objectiveClass) continue;
            if (!Collection.class.isAssignableFrom(m.getReturnType())) continue;
            return Ref.accessible(m);
        }
        return null;
    }

    private void resolveTabFields() {
        List<Field> candidates = new ArrayList<Field>();
        for (Field f : Ref.allFields(tabClass)) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (chatClass != null && f.getType() == chatClass) candidates.add(Ref.accessible(f));
        }
        if (candidates.size() >= 1) headerField = candidates.get(0);
        if (candidates.size() >= 2) footerField = candidates.get(1);
    }

    private void resolveProfile() {
        Class<?> profileClass = null;
        try {
            profileClass = Class.forName("com.mojang.authlib.GameProfile", false, loader);
        } catch (Throwable ignored) {
            profileClass = null;
        }
        if (profileClass != null) {
            profileMethod = Ref.getter(infoClass, profileClass);
            return;
        }
        // Unknown client: any zero-argument method whose type is named *GameProfile.
        for (Method m : Ref.allMethods(infoClass)) {
            if (m.getParameterTypes().length != 0) continue;
            if (!m.getReturnType().getName().endsWith("GameProfile")) continue;
            profileMethod = Ref.accessible(m);
            return;
        }
    }

    // ------------------------------------------------------- game accessors

    /** Runs {@code task} on the client thread when the game exposes a scheduler. */
    public boolean schedule(Runnable task) {
        if (scheduleMethod == null) return false;
        return Ref.callVoid(scheduleMethod, minecraft(), task);
    }

    public Object minecraft() {
        return Ref.getStatic(mcInstanceField);
    }

    /** The loader the game classes came from; the menu must be defined against it. */
    public ClassLoader gameLoader() {
        return loader;
    }

    public Object player() {
        Object mc = minecraft();
        return mc == null ? null : Ref.get(thePlayerField, mc);
    }

    public Object world() {
        Object mc = minecraft();
        return mc == null ? null : Ref.get(theWorldField, mc);
    }

    public boolean inWorld() {
        return player() != null && world() != null;
    }

    // ----------------------------------------------------------------- gui

    /** True when the game is showing any screen (chat, inventory, menu, ...). */
    public boolean screenOpen() {
        Object mc = minecraft();
        return mc != null && currentScreenField != null
            && Ref.get(currentScreenField, mc) != null;
    }

    /**
     * Hands the game a screen instance and shows it.
     *
     * <p>The instance comes from the separately compiled menu, which is why this
     * takes {@code Object}: the loader has no compile-time GuiScreen.
     */
    public boolean displayScreen(Object screen) {
        Object mc = minecraft();
        if (mc == null || displayScreenMethod == null) return false;
        return Ref.callVoid(displayScreenMethod, mc, screen);
    }

    public boolean screensSupported() {
        return displayScreenMethod != null && currentScreenField != null;
    }

    /** Big centre-screen toast, the same overlay the game uses for records. */
    public void title(String main, String sub) {
        Object mc = minecraft();
        if (mc == null || titleMethod == null) return;
        Ref.callVoid(titleMethod, Ref.get(guiField, mc), main, sub,
            Integer.valueOf(4), Integer.valueOf(40), Integer.valueOf(10));
    }

    /** Single-line overlay under the crosshair. */
    public void record(String line) {
        Object mc = minecraft();
        if (mc == null || recordMethod == null) return;
        Ref.callVoid(recordMethod, Ref.get(guiField, mc), line, Boolean.TRUE);
    }

    private Object netHandler() {
        Object p = player();
        if (p == null) return null;
        if (sendQueueField != null) {
            Object q = Ref.get(sendQueueField, p);
            if (q != null) return q;
        }
        if (netGetterMethod != null) return Ref.call(netGetterMethod, p);
        return null;
    }

    /** Tab list names, empty when the connection or the map is not readable. */
    public List<String> tabNames() {
        List<String> out = new ArrayList<String>();
        Object handler = netHandler();
        if (handler == null) return out;

        Map<?, ?> map = infoMap(handler);
        if (map == null) return out;

        for (Object entry : new ArrayList<Object>(map.values())) {
            if (entry == null) continue;
            String name = entryName(entry);
            if (name != null && !name.isEmpty()) out.add(name);
        }
        return out;
    }

    private String entryName(Object info) {
        if (profileMethod == null) return null;
        Object profile = Ref.call(profileMethod, info);
        if (profile == null) return null;
        try {
            Method getName = profile.getClass().getMethod("getName");
            Object value = getName.invoke(profile);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Map<?, ?> infoMap(Object handler) {
        if (infoMapField != null) {
            Object value = Ref.get(infoMapField, handler);
            return value instanceof Map ? (Map<?, ?>) value : null;
        }
        for (Field candidate : infoMapCandidates) {
            Object value = Ref.get(candidate, handler);
            if (!(value instanceof Map)) continue;
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) continue;
            boolean looksRight = true;
            int sampled = 0;
            for (Object v : map.values()) {
                if (v == null) continue;
                if (infoClass != null && !infoClass.isInstance(v)) {
                    looksRight = false;
                    break;
                }
                if (++sampled >= 8) break;
            }
            if (looksRight && sampled > 0) {
                infoMapField = candidate;
                Log.info("field infoMap          = " + Ref.describe(candidate) + " (verified)");
                return map;
            }
        }
        // Nothing verifiable yet: prefer the canonical name, else the biggest map.
        for (Field candidate : infoMapCandidates) {
            if (candidate.getName().equals("playerInfoMap")
                || candidate.getName().equals("field_175106_d")) {
                infoMapField = candidate;
                Object value = Ref.get(candidate, handler);
                return value instanceof Map ? (Map<?, ?>) value : null;
            }
        }
        return null;
    }

    /** Sidebar lines, colour codes stripped and trimmed. */
    public List<String> sidebar() {
        List<String> out = new ArrayList<String>();
        for (String raw : sidebarRaw()) {
            String line = strip(raw).trim();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    /**
     * Sidebar lines exactly as the game holds them, colour codes intact.
     *
     * <p>Hypixel builds each line out of a score entry, and on some boards the entry
     * is an invisible/placeholder token with the visible text in the team prefix.
     * Comparing this against {@link #probe()} is how that gets diagnosed.
     */
    public List<String> sidebarRaw() {
        List<String> out = new ArrayList<String>();
        Object board = board();
        if (board == null) return out;

        Object objective = Ref.call(getObjectiveMethod, board, Integer.valueOf(SIDEBAR_SLOT));
        if (objective == null) return out;

        Object sorted = Ref.call(getSortedScoresMethod, board, objective);
        if (!(sorted instanceof Collection)) return out;

        for (Object score : new ArrayList<Object>((Collection<?>) sorted)) {
            if (score == null) continue;
            Object raw = scoreNameMethod != null ? Ref.call(scoreNameMethod, score)
                : Ref.get(scoreNameField, score);
            if (!(raw instanceof String)) continue;
            out.add(rendered(board, (String) raw));
        }
        return out;
    }

    /**
     * The text the client actually draws for one score entry.
     *
     * <p>Vanilla renders a sidebar line as {@code team.formatString(entryName)}, where
     * the entry name is only the invisible key and the visible text rides in the team
     * prefix/suffix. Boards without a team render the entry name unchanged.
     */
    private String rendered(Object board, String entry) {
        Object team = teamFor(board, entry);
        if (team == null || teamFormatMethod == null) return entry;
        Object formatted = Ref.call(teamFormatMethod, team, entry);
        return formatted instanceof String ? (String) formatted : entry;
    }

    /** The team a score entry belongs to, or {@code null} when it has none. */
    private Object teamFor(Object board, String entry) {
        if (teamClass == null) return null;
        for (int i = 0; i < boardMapFields.size(); i++) {
            Object value = Ref.get(boardMapFields.get(i), board);
            if (!(value instanceof Map)) continue;
            Object team = ((Map<?, ?>) value).get(entry);
            if (team != null && teamClass.isInstance(team)) return team;
        }
        return null;
    }

    private Object board() {
        Object world = world();
        if (world == null) return null;
        return getBoardMethod != null ? Ref.call(getBoardMethod, world) : Ref.get(boardField, world);
    }

    /**
     * Every zero-argument String getter on the sidebar objective, with its value.
     *
     * <p>Diagnostic only: 1.8.9 keeps the objective's name and display name in two
     * same-typed getters with one-letter names, so the pair is dumped rather than
     * guessed.
     */
    public String objectiveInfo() {
        Object board = board();
        if (board == null || objectiveClass == null) return "";
        Object objective = Ref.call(getObjectiveMethod, board, Integer.valueOf(SIDEBAR_SLOT));
        if (objective == null) return "";

        StringBuilder sb = new StringBuilder();
        for (Method m : Ref.allMethods(objectiveClass)) {
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() != String.class) continue;
            sb.append("  ").append(objectiveClass.getName()).append('#').append(m.getName())
                .append("() = ").append(Ref.call(m, objective)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Server instance id from the tab header or footer, e.g. {@code mini42AB}.
     *
     * <p>Header and footer are two same-typed fields with no getter, so their order
     * is decided by content, never by field order: whichever holds {@code "mini"}
     * wins.
     */
    public String serverId() {
        String header = componentText(headerField);
        String footer = componentText(footerField);
        String fromHeader = miniToken(header);
        if (fromHeader != null) return fromHeader;
        return miniToken(footer);
    }

    private static String miniToken(String text) {
        if (text == null || text.isEmpty()) return null;
        int idx = text.indexOf("mini");
        if (idx < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = idx; i < text.length() && sb.length() < 24; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else break;
        }
        return sb.length() > 4 ? sb.toString() : null;
    }

    /** Header text, or footer when the header is empty. Colour codes stripped. */
    public String tabText() {
        String header = componentText(headerField);
        if (!header.isEmpty()) return header;
        return componentText(footerField);
    }

    public String footerText() {
        return componentText(footerField);
    }

    private String componentText(Field field) {
        if (field == null) return "";
        Object mc = minecraft();
        if (mc == null) return "";
        Object gui = Ref.get(guiField, mc);
        if (gui == null) return "";
        Object overlay = Ref.call(getTabListMethod, gui);
        if (overlay == null) return "";

        Object component = Ref.get(field, overlay);
        if (component == null) return "";
        Object raw = textMethod != null ? Ref.call(textMethod, component) : null;
        String text = raw instanceof String ? (String) raw : String.valueOf(component);
        return strip(text);
    }

    /** Map name from the sidebar, or {@code null}. */
    public String mapName() {
        List<String> lines = sidebar();
        if (lines.isEmpty()) return null;

        for (String line : lines) {
            if (line.length() < 4) continue;
            if (line.toLowerCase().startsWith("map:")) {
                String value = line.substring(4).trim();
                return value.isEmpty() ? null : value;
            }
        }
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.length() < 3 || lower.length() > 24) continue;
            if (lower.contains("lobby") || lower.contains("mode")) continue;
            if (lower.startsWith("player") || lower.startsWith("map") || lower.startsWith("next")) continue;
            if (!lower.matches("^[a-z0-9 _'-]+$")) continue;
            return line;
        }
        return null;
    }

    private static final String[][] MODE_TOKENS = {
        { "4V4V4V4", "FOUR_FOUR" },
        { "3V3V3V3", "FOUR_THREE" },
        { "4V4", "TWO_FOUR" },
        { "DOUBLES", "EIGHT_TWO" },
        { "SOLO", "EIGHT_ONE" },
        { "CASTLE", "CASTLE" },
    };

    /** Bedwars mode guessed from the sidebar and tab header text, or {@code null}. */
    public String modeName() {
        StringBuilder haystack = new StringBuilder();
        for (String line : sidebar()) haystack.append(' ').append(line);
        haystack.append(' ').append(tabText()).append(' ').append(footerText());
        String upper = haystack.toString().toUpperCase(java.util.Locale.ROOT);

        for (String line : sidebar()) {
            if (line.toLowerCase().startsWith("mode:")) {
                String value = line.substring(5).trim();
                if (!value.isEmpty()) return value;
            }
        }
        for (String[] token : MODE_TOKENS) {
            if (upper.contains(token[0])) return token[0];
        }
        return null;
    }

    // ------------------------------------------------------------ outgoing

    public boolean chat(String message) {
        if (addChatMethod == null || chatTextCtor == null) return false;
        Object player = player();
        if (player == null) return false;
        try {
            Object component = chatTextCtor.newInstance(message);
            return Ref.callVoid(addChatMethod, player, component);
        } catch (Throwable e) {
            Log.once("chat.new", "cannot build chat component: " + e);
            return false;
        }
    }

    public boolean sendChat(String command) {
        Object player = player();
        if (player == null || sendChatMethod == null) return false;
        return Ref.callVoid(sendChatMethod, player, command);
    }

    // --------------------------------------------------------------- probe

    /** Raw view of everything this loader can read, for first-run verification. */
    public String probe() {
        StringBuilder sb = new StringBuilder();
        sb.append("inWorld=").append(inWorld()).append('\n');
        sb.append("header=").append(tabText()).append('\n');
        sb.append("footer=").append(footerText()).append('\n');
        sb.append("server=").append(serverId()).append('\n');
        sb.append("mode=").append(modeName()).append('\n');
        sb.append("map=").append(mapName()).append('\n');
        sb.append("objective:\n").append(objectiveInfo());
        sb.append("sidebar:\n");
        for (String line : sidebar()) sb.append("  |").append(line).append('\n');
        sb.append("sidebarRaw:\n");
        for (String line : sidebarRaw()) sb.append("  |").append(line).append('\n');
        List<String> tab = tabNames();
        sb.append("tab(").append(tab.size()).append("):\n");
        for (int i = 0; i < tab.size() && i < 80; i++) sb.append("  ").append(tab.get(i)).append('\n');
        return sb.toString();
    }

    // -------------------------------------------------------------- helpers

    public static String strip(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
