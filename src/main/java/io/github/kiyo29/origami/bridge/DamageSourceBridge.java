package io.github.kiyo29.origami.bridge;

import net.kyori.adventure.pointer.Pointers;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Minimal DamageSource and DamageSource.Builder (Proxy based).
 * Only the plain data the builder was given is kept; exhaustion and
 * difficulty scaling are not derived from the MC damage type yet.
 */
final class DamageSourceBridge {
    private DamageSourceBridge() { }

    static DamageSource.Builder builder(DamageType type) {
        return (DamageSource.Builder) Proxy.newProxyInstance(
                DamageSource.class.getClassLoader(), new Class<?>[] {DamageSource.Builder.class},
                new BuilderHandler(type));
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, String name) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> name;
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    private static final class BuilderHandler implements InvocationHandler {
        private final DamageType type;
        private Entity causing;
        private Entity direct;
        private Location location;
        private Pointers context = Pointers.empty();

        BuilderHandler(DamageType type) {
            this.type = type;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "OrigamiDamageSourceBuilder{" + type + "}");
            }
            switch (method.getName()) {
                case "withCausingEntity" -> {
                    causing = (Entity) args[0];
                    return proxy;
                }
                case "withDirectEntity" -> {
                    direct = (Entity) args[0];
                    return proxy;
                }
                case "withDamageLocation" -> {
                    location = (Location) args[0];
                    return proxy;
                }
                case "withDamageContext" -> {
                    Pointers.Builder b = Pointers.builder();
                    ((Consumer<Pointers.Builder>) args[0]).accept(b);
                    context = b.build();
                    return proxy;
                }
                case "build" -> {
                    return Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                            new Class<?>[] {DamageSource.class},
                            new SourceHandler(type, causing, direct, location, context));
                }
                default -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    throw UnimplementedCalls.exception(method);
                }
            }
        }
    }

    private record SourceHandler(DamageType type, Entity causing, Entity direct, Location location,
                                 Pointers context) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "OrigamiDamageSource{" + type + "}");
            }
            return switch (method.getName()) {
                case "getDamageType" -> type;
                case "getCausingEntity" -> causing;
                case "getDirectEntity" -> direct;
                case "getDamageLocation" -> location == null ? null : location.clone();
                case "getSourceLocation" -> location == null ? null : location.clone();
                case "isIndirect" -> causing != direct;
                case "getDamageContext" -> context;
                default -> {
                    if (method.isDefault()) {
                        yield InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    throw UnimplementedCalls.exception(method);
                }
            };
        }
    }
}
