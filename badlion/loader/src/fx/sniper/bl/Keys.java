package fx.sniper.bl;

import java.lang.reflect.Method;

/**
 * Reads raw key state from LWJGL without compiling against it.
 *
 * <p>{@code org.lwjgl.input.Keyboard.isKeyDown(int)} reads the array the window's
 * keyboard handler keeps filled, so it is safe to call from the worker thread. A
 * missing or broken LWJGL means "not pressed", never a crash.
 *
 * <p>The class is looked up through the game's loader first: a client that loads
 * the game from its own loader (Badlion does) keeps LWJGL out of reach of the
 * agent's loader, and a plain {@code Class.forName} here fails exactly the way it
 * did the first time.
 */
public final class Keys {

    public static final int F6 = 295;
    public static final int F7 = 296;

    private static volatile Method isKeyDown;
    private static volatile boolean looked;

    private Keys() {
    }

    public static boolean held(int key) {
        return held(key, null);
    }

    public static boolean held(int key, ClassLoader game) {
        Method m = isKeyDown(game);
        if (m == null) return false;
        try {
            Object value = m.invoke(null, Integer.valueOf(key));
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Throwable e) {
            return false;
        }
    }

    /** True once LWJGL answered at least once; useful for one log line. */
    public static boolean available() {
        return isKeyDown != null;
    }

    private static Method isKeyDown(ClassLoader game) {
        Method cached = isKeyDown;
        if (cached != null) return cached;
        if (looked) return null;
        looked = true;

        ClassLoader[] candidates = { game, Keys.class.getClassLoader(),
            ClassLoader.getSystemClassLoader() };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class<?> keyboard = Class.forName("org.lwjgl.input.Keyboard", false, candidate);
                Method found = keyboard.getMethod("isKeyDown", int.class);
                isKeyDown = found;
                Log.info("hotkey via " + candidate.getClass().getName());
                return found;
            } catch (Throwable e) {
                Log.info("no LWJGL keyboard in " + candidate.getClass().getName() + ": " + e);
            }
        }
        Log.warn("hotkey off: LWJGL keyboard unreachable, use chat commands (/blsnip help)");
        return null;
    }
}
