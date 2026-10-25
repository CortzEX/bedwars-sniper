package fx.sniper.bl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hypixel and Mojang lookups, run on the worker thread.
 *
 * <p>Two calls per refresh: Mojang turns a name into a UUID (no key needed), then
 * Hypixel's {@code /v2/status} turns the UUID into mode + map. Both are cached —
 * UUIDs never change, and a status poll is capped by the caller's interval.
 *
 * <p>Every failure is a value, never an exception: {@code null} means "could not
 * ask", which the caller reports differently from "online but not in a game".
 */
public final class Status {

    private static final int TIMEOUT_MS = 4000;
    private static final Map<String, String> UUID_CACHE = new HashMap<String, String>();

    private Status() {
    }

    /** What Hypixel says about a player right now. */
    public static final class Result {

        /** False when the player has no session, or has hidden it. */
        public final boolean online;
        public final String gameType;
        public final String mode;
        public final String map;

        Result(boolean online, String gameType, String mode, String map) {
            this.online = online;
            this.gameType = gameType == null ? "" : gameType;
            this.mode = mode == null ? "" : mode;
            this.map = map == null ? "" : map;
        }

        static final Result NOT_ASKED = new Result(false, "", "", "");

        public boolean bedwars() {
            return online && "BEDWARS".equalsIgnoreCase(gameType);
        }

        /** {@code /play} argument for this mode, or {@code null} when unknown. */
        public String queueCommand() {
            if (mode.isEmpty()) return null;
            String slug = mode.toLowerCase(Locale.ROOT).replace(' ', '_');
            for (int i = 0; i < slug.length(); i++) {
                char c = slug.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
                if (!ok) return null;
            }
            return "play bedwars_" + slug;
        }

        public String describe() {
            if (!online) return "offline";
            String type = gameType.isEmpty() ? "?" : gameType;
            if (map.isEmpty()) return "online (" + type + ")";
            return type + " \u2014 " + (mode.isEmpty() ? "?" : mode) + " \u2014 " + map;
        }
    }

    /** Mojang UUID for a name, without dashes. {@code null} on any failure. */
    public static String uuidFor(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (UUID_CACHE) {
            String cached = UUID_CACHE.get(key);
            if (cached != null) return cached.isEmpty() ? null : cached;
        }

        String body = get("https://api.mojang.com/users/profiles/minecraft/" + key, null);
        String id = body == null ? null : field(body, "id");
        if (id == null || id.length() != 32) {
            synchronized (UUID_CACHE) {
                UUID_CACHE.put(key, "");
            }
            return null;
        }
        synchronized (UUID_CACHE) {
            UUID_CACHE.put(key, id);
        }
        return id;
    }

    /**
     * Hypixel status for a UUID.
     *
     * @param apiKey Hypixel API key; a missing or rejected key reports as not asked.
     * @return the status, or {@code null} when the request could not be made.
     */
    public static Result status(String uuid, String apiKey) {
        if (uuid == null || uuid.isEmpty()) return null;
        if (apiKey == null || apiKey.isEmpty()) return null;

        String body = get("https://api.hypixel.net/v2/status?uuid=" + uuid, apiKey);
        if (body == null) return null;
        if (!"true".equals(field(body, "success"))) {
            String cause = field(body, "cause");
            Log.once("status.cause", "hypixel refused the status call"
                + (cause == null ? "" : ": " + cause));
            return null;
        }

        String session = object(body, "session");
        if (session == null) return new Result(false, "", "", "");
        boolean online = "true".equals(field(session, "online"));
        return new Result(online, field(session, "gameType"), field(session, "mode"),
            field(session, "map"));
    }

    // ------------------------------------------------------------ transport

    private static String get(String url, String apiKey) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "blsnip-loader");
            if (apiKey != null) conn.setRequestProperty("API-Key", apiKey);

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return null;
            byte[] bytes = drain(in);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (code >= 400 && !text.contains("\"success\"")) return null;
            return text;
        } catch (Throwable e) {
            Log.once("http:" + url, "request failed: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] drain(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buf = new byte[1024];
        int read;
        while ((read = in.read(buf)) > 0) {
            out.write(buf, 0, read);
            if (out.size() > 64 * 1024) break;
        }
        in.close();
        return out.toByteArray();
    }

    // -------------------------------------------------------------- minimal JSON
    // Responses here are one flat object with string/scalar members, so a parser is
    // not worth the dependency; nested values are pulled by slicing to the object.

    /** Value of a top-level-ish string or scalar member, unquoted. */
    static String field(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return null;

        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;

        char first = json.charAt(i);
        if (first == '"') {
            StringBuilder sb = new StringBuilder();
            for (i++; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(++i));
                    continue;
                }
                if (c == '"') break;
                sb.append(c);
            }
            return sb.toString();
        }
        if (first == 'n') return null; // null
        int end = i;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(i, end).trim();
    }

    /** Raw text of a nested object member, braces included. */
    static String object(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int open = json.indexOf('{', at + needle.length());
        if (open < 0) return null;

        int depth = 0;
        boolean inString = false;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(open, i + 1);
            }
        }
        return null;
    }
}
