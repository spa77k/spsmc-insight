package dev.spa.insight.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReflectTest {

    public interface Api {
        String value();
    }

    /** 導入されていないプラグインの型の代わり。隔離したクラスローダーからは見えなくする。 */
    public static class Missing {
    }

    public static class Impl implements Api {
        @Override
        public String value() {
            return "ok";
        }

        public Missing optional() {
            return null;
        }
    }

    /**
     * Impl だけを自分で定義し、Missing は見つからないことにするクラスローダー。
     * QuickShop 本体が PlaceholderAPI なしで読み込まれた状態を再現する。
     */
    private static final class WithoutMissing extends ClassLoader {
        WithoutMissing() {
            super(ReflectTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(Missing.class.getName())) {
                throw new ClassNotFoundException(name);
            }
            if (!name.equals(Impl.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                String path = name.replace('.', '/') + ".class";
                try (InputStream in = getParent().getResourceAsStream(path)) {
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }

    @Test
    void callsThroughInterfaceWhenConcreteClassHasUnresolvableSignature() throws Exception {
        Class<?> isolated = new WithoutMissing().loadClass(Impl.class.getName());
        assertThrows(NoClassDefFoundError.class, isolated::getMethods);

        Object instance = isolated.getDeclaredConstructor().newInstance();

        assertEquals("ok", Reflect.call(instance, "value"));
        assertNull(Reflect.call(instance, "optional"));
    }

    @Test
    void searchesConcreteClassBeforeInterfaces() {
        List<Class<?>> order = Reflect.searchOrder(Impl.class);

        assertEquals(List.of(Impl.class, Object.class, Api.class), order);
    }
}
