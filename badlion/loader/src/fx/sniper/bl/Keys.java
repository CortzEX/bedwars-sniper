package fx.sniper.bl;

import java.lang.reflect.Method;

/**
 * Reads raw key state from LWJGL without compiling against it.
 *
 * <p>{@code org.lwjgl.input.Keyboard.isKeyDown(int)} reads the array the window's
 * keyboard handler keeps filled, so it is safe to call from the worker thread. A
 * missing or broken LWJGL means "not pressed", never a crash.
 */
public final class Keys {

    public static final int F6 = 295;
    public static final int F7 = 296;

    private static volatile Method isKeyDown;
    private static volatile boolean looked;

    private Keys() {
    }

    public static boolean held(int key) {
        Method m = isKeyDown();
        if (m == null) return false;
        try {
            Object value = m.invoke(null, Integer.valueOf(key));
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Throwable e) {
            return false;
        }
    }

    private static Method isKeyDown() {
        Method cached = isKeyDown;
        if (cached != null) return cached;
        if (looked) return null;
        looked = true;

        try {
            Class<?> keyboard = Class.forName("org.lwjgl.input.Keyboard");
            Method found = keyboard.getMethod("isKeyDown", int.class);
            isKeyDown = found;
            return found;
        } catch (Throwable e) {
            Log.once("keys", "LWJGL keyboard not reachable, menu hotkey disabled: " + e);
            return null;
        }
    }
}
