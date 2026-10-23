package io.github.kiyo29.origami.bridge;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * RegistryAccess (loaded by paper-api through ServiceLoader).
 * Phase 8: ITEM and BLOCK are real (read-only, see TypeRegistries).
 * Every other registry is still empty: get() returns null, stream() is empty.
 */
public final class RegistryAccessBridge implements RegistryAccess {
    private static final Map<Object, Registry<?>> CACHE = new ConcurrentHashMap<>();

    /** Required by ServiceLoader. */
    public RegistryAccessBridge() { }

    @Override
    @SuppressWarnings("removal")
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        if (type == ItemType.class) {
            return cast(TypeRegistries.registry(TypeRegistries.Kind.ITEM));
        }
        if (type == BlockType.class) {
            return cast(TypeRegistries.registry(TypeRegistries.Kind.BLOCK));
        }
        if (type == org.bukkit.enchantments.Enchantment.class) {
            return cast(EnchantmentBridge.registry());
        }
        return cast(CACHE.computeIfAbsent(type, k -> create(type.getSimpleName())));
    }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        String id = key.key().asString();
        if (id.equals("minecraft:item")) {
            return cast(TypeRegistries.registry(TypeRegistries.Kind.ITEM));
        }
        if (id.equals("minecraft:block")) {
            return cast(TypeRegistries.registry(TypeRegistries.Kind.BLOCK));
        }
        if (id.equals("minecraft:enchantment")) {
            return cast(EnchantmentBridge.registry());
        }
        return cast(CACHE.computeIfAbsent(key, k -> create(id)));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Keyed> Registry<T> cast(Registry<?> registry) {
        return (Registry<T>) registry;
    }

    private static Registry<?> create(String label) {
        return (Registry<?>) Proxy.newProxyInstance(
                Registry.class.getClassLoader(), new Class<?>[] {Registry.class}, new EmptyRegistry(label));
    }

    private record EmptyRegistry(String label) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "OrigamiEmptyRegistry{" + label + "}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            return switch (method.getName()) {
                case "get", "getKey" -> null;
                case "hasTag" -> false;
                case "getTags" -> List.of();
                case "stream", "keyStream" -> Stream.empty();
                case "size" -> 0;
                case "iterator" -> Collections.emptyIterator();
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
