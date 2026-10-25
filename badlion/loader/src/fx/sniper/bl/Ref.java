package fx.sniper.bl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection helpers that never throw.
 *
 * <p>Every lookup walks the full superclass chain, because 1.8.9 splits members
 * between {@code Minecraft}/{@code EntityPlayerSP}/{@code EntityPlayer}/{@code Entity}
 * and the split differs between clients. Shadowed names keep the most derived
 * declaration.
 */
public final class Ref {

    private Ref() {
    }

    /** All instance+static fields, most-derived first, shadowed names removed. */
    public static List<Field> allFields(Class<?> type) {
        Map<String, Field> out = new LinkedHashMap<String, Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] declared;
            try {
                declared = c.getDeclaredFields();
            } catch (Throwable e) {
                // A missing parameter type on any member aborts the whole enumeration.
                Log.once("fields:" + c.getName(), "getDeclaredFields failed on " + c.getName()
                    + ": " + e);
                continue;
            }
            for (Field f : declared) {
                if (!out.containsKey(f.getName())) out.put(f.getName(), f);
            }
        }
        return new ArrayList<Field>(out.values());
    }

    /** All methods, most-derived first, overridden signatures removed. */
    public static List<Method> allMethods(Class<?> type) {
        Map<String, Method> out = new LinkedHashMap<String, Method>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] declared;
            try {
                declared = c.getDeclaredMethods();
            } catch (Throwable e) {
                Log.once("methods:" + c.getName(), "getDeclaredMethods failed on " + c.getName()
                    + ": " + e);
                continue;
            }
            for (Method m : declared) {
                if (m.isSynthetic() || m.isBridge()) continue;
                out.put(signature(m), m);
            }
        }
        return new ArrayList<Method>(out.values());
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName());
        sb.append('(');
        Class<?>[] params = m.getParameterTypes();
        for (Class<?> p : params) sb.append(p.getName()).append(',');
        return sb.append(')').toString();
    }

    public static Field fieldByType(Class<?> type, Class<?> fieldType, boolean wantStatic) {
        for (Field f : allFields(type)) {
            if (Modifier.isStatic(f.getModifiers()) != wantStatic) continue;
            if (f.getType() == fieldType) return accessible(f);
        }
        return null;
    }

    public static Field fieldAssignable(Class<?> type, Class<?> fieldType, boolean wantStatic) {
        for (Field f : allFields(type)) {
            if (Modifier.isStatic(f.getModifiers()) != wantStatic) continue;
            if (fieldType.isAssignableFrom(f.getType())) return accessible(f);
        }
        return null;
    }

    public static Field fieldByTypes(Class<?> type, Class<?> fieldType, boolean wantStatic, int skip) {
        int seen = 0;
        for (Field f : allFields(type)) {
            if (Modifier.isStatic(f.getModifiers()) != wantStatic) continue;
            if (f.getType() != fieldType) continue;
            if (seen++ == skip) return accessible(f);
        }
        return null;
    }

    /** Zero-argument method returning exactly {@code returnType}. */
    public static Method getter(Class<?> type, Class<?> returnType) {
        for (Method m : allMethods(type)) {
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() == returnType) return accessible(m);
        }
        return null;
    }

    /** Zero-argument method returning exactly {@code returnType}, skipped {@code skip} times. */
    public static Method getterByTypes(Class<?> type, Class<?> returnType, int skip) {
        int seen = 0;
        for (Method m : allMethods(type)) {
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() != returnType) continue;
            if (seen++ == skip) return accessible(m);
        }
        return null;
    }

    /** Method with exactly these parameter types (return type ignored). */
    public static Method method(Class<?> type, Class<?>... params) {
        for (Method m : allMethods(type)) {
            if (matches(m, params)) return accessible(m);
        }
        return null;
    }

    /** One-argument method whose parameter is exactly {@code param}, returning {@code ret}. */
    public static Method method(Class<?> type, Class<?> param, Class<?> ret) {
        for (Method m : allMethods(type)) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != param) continue;
            if (ret != null && m.getReturnType() != ret) continue;
            return accessible(m);
        }
        return null;
    }

    /** Zero-argument method picked by name, in the order given. */
    public static Method noArgByName(Class<?> type, Class<?> ret, String... names) {
        for (String name : names) {
            for (Method m : allMethods(type)) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (ret != null && m.getReturnType() != ret) continue;
                return accessible(m);
            }
        }
        return null;
    }

    private static boolean matches(Method m, Class<?>[] params) {
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length != params.length) return false;
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] != params[i]) return false;
        }
        return true;
    }

    public static List<Field> fieldsOfType(Class<?> type, Class<?> fieldType, boolean wantStatic) {
        List<Field> out = new ArrayList<Field>();
        for (Field f : allFields(type)) {
            if (Modifier.isStatic(f.getModifiers()) != wantStatic) continue;
            if (f.getType() == fieldType) out.add(accessible(f));
        }
        return out;
    }

    public static Field accessible(Field f) {
        if (f == null) return null;
        try {
            if (!f.isAccessible()) f.setAccessible(true);
        } catch (Throwable ignored) {
            // SecurityManager: read will fail later and be reported at the call site
        }
        return f;
    }

    public static Method accessible(Method m) {
        if (m == null) return null;
        try {
            if (!m.isAccessible()) m.setAccessible(true);
        } catch (Throwable ignored) {
            // see above
        }
        return m;
    }

    public static Object get(Field f, Object target) {
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object getStatic(Field f) {
        return get(f, null);
    }

    public static Object call(Method m, Object target, Object... args) {
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean callVoid(Method m, Object target, Object... args) {
        if (m == null) return false;
        try {
            m.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Human-readable "declaringClass#member:type", used in the bind log. */
    public static String describe(Field f) {
        return f == null ? "none"
            : f.getDeclaringClass().getName() + "#" + f.getName() + ":" + f.getType().getName();
    }

    public static String describe(Method m) {
        if (m == null) return "none";
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName()).append('#').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ps[i].getName());
        }
        return sb.append("):").append(m.getReturnType().getName()).toString();
    }
}
