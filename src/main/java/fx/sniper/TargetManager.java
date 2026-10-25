package fx.sniper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lower-cased target set. Thread-safe so the scan path never tears the list. */
public final class TargetManager {

    private final SniperConfig config;
    private final Set<String> lowered = new HashSet<>();

    public TargetManager(SniperConfig config) {
        this.config = config;
        rebuild();
    }

    public synchronized void rebuild() {
        lowered.clear();
        for (String t : config.targets) {
            if (t != null && !t.trim().isEmpty()) {
                lowered.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    public synchronized boolean isTarget(String name) {
        return name != null && lowered.contains(name.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean add(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String n = name.trim();
        for (String existing : config.targets) {
            if (existing.equalsIgnoreCase(n)) return false;
        }
        config.targets.add(n);
        rebuild();
        config.save();
        return true;
    }

    public synchronized boolean remove(String name) {
        boolean removed = config.targets.removeIf(t -> t.equalsIgnoreCase(name));
        if (removed) {
            rebuild();
            config.save();
        }
        return removed;
    }

    public synchronized Set<String> snapshot() {
        return Collections.unmodifiableSet(new HashSet<>(lowered));
    }

    public SniperConfig config() {
        return config;
    }
}
