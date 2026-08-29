package dev.spa.insight.source;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 他プラグインのAPIをコンパイル時依存なしで叩くための最小限の道具。
 * バージョン差でメソッドが消えても、ここで null を返して欠測にする。
 */
public final class Reflect {

    private Reflect() {
    }

    public static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    public static Object staticField(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        try {
            Field field = type.getField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static Object call(Object target, String method, Object... arguments) {
        if (target == null) {
            return null;
        }
        return callOn(target.getClass(), target, method, arguments);
    }

    public static Object callStatic(Class<?> type, String method, Object... arguments) {
        return callOn(type, null, method, arguments);
    }

    public static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 親クラスを続けて探す。
            }
        }
        return null;
    }

    private static Object callOn(Class<?> type, Object target, String method, Object... arguments) {
        if (type == null) {
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method candidate : current.getMethods()) {
                if (!candidate.getName().equals(method) || candidate.getParameterCount() != arguments.length) {
                    continue;
                }
                if (!argumentsMatch(candidate.getParameterTypes(), arguments)) {
                    continue;
                }
                try {
                    candidate.setAccessible(true);
                    return candidate.invoke(target, arguments);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 同名メソッドが複数ある場合に、引数の型が合うものだけを選ぶ。
     */
    private static boolean argumentsMatch(Class<?>[] parameters, Object[] arguments) {
        for (int i = 0; i < parameters.length; i++) {
            Object argument = arguments[i];
            if (argument == null) {
                if (parameters[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> parameter = box(parameters[i]);
            if (!parameter.isInstance(argument)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    public static long asLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    public static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
