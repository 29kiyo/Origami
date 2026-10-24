package io.github.kiyo29.origami.bridge;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * RegistryAccess (loaded by paper-api through ServiceLoader).
 * ITEM, BLOCK and ENCHANTMENT are real (see TypeRegistries / EnchantmentBridge).
 * Every other registry is "shallow": stream()/size() are empty, but get(key) for an
 * interface element type returns a minimal keyed Proxy so that constants such as
 * DamageType.ARROW can be initialised. Only the "minecraft" namespace is accepted.
 * Abstract-class element types (PotionEffectType, ...) still return null.
 */
public final class RegistryAccessBridge implements RegistryAccess {
    private static final Map<Object, Registry<?>> CACHE = new ConcurrentHashMap<>();
    /** One value per (element type, key), shared by the Class and RegistryKey access paths. */
    private static final Map<String, Object> VALUES = new ConcurrentHashMap<>();
    /** Registry id (minecraft:damage_type) to element type, built from RegistryKey's generics. */
    private static volatile Map<String, Class<?>> typeById;

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
        return cast(CACHE.computeIfAbsent(type, k -> create(type.getSimpleName(), type)));
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
        return cast(CACHE.computeIfAbsent(key, k -> create(id, types().get(id))));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Keyed> Registry<T> cast(Registry<?> registry) {
        return (Registry<T>) registry;
    }

    private static Registry<?> create(String label, Class<?> type) {
        return (Registry<?>) Proxy.newProxyInstance(
                Registry.class.getClassLoader(), new Class<?>[] {Registry.class}, new EmptyRegistry(label, type));
    }

    private static Map<String, Class<?>> types() {
        Map<String, Class<?>> m = typeById;
        if (m == null) {
            m = new HashMap<>();
            for (Field f : RegistryKey.class.getFields()) {
                try {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (!(f.getGenericType() instanceof ParameterizedType p) || p.getRawType() != RegistryKey.class) {
                        continue;
                    }
                    Type arg = p.getActualTypeArguments()[0];
                    Class<?> element = arg instanceof Class<?> c ? c
                            : arg instanceof ParameterizedType pp && pp.getRawType() instanceof Class<?> rc ? rc : null;
                    if (element != null) {
                        m.put(((RegistryKey<?>) f.get(null)).key().asString(), element);
                    }
                } catch (Throwable ignored) {
                    // a field we cannot read just stays unmapped
                }
            }
            typeById = m;
        }
        return m;
    }

    private static Object lookup(Class<?> type, Key k) {
        if (type == null || !type.isInterface() || !"minecraft".equals(k.namespace())) {
            return null;
        }
        NamespacedKey nk = k instanceof NamespacedKey n ? n : NamespacedKey.fromString(k.asString());
        if (nk == null) {
            return null;
        }
        return VALUES.computeIfAbsent(type.getName() + "|" + nk, x -> Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, new KeyedValue(type, nk)));
    }

    private record EmptyRegistry(String label, Class<?> type) implements InvocationHandler {
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
                case "get" -> {
                    if (args != null && args.length == 1 && args[0] instanceof Key k) {
                        yield lookup(type, k);
                    }
                    if (method.isDefault()) {
                        yield InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    yield null;
                }
                case "getKey" -> args != null && args.length == 1 && args[0] instanceof Keyed v ? v.getKey() : null;
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

    /** Minimal keyed value: knows only its key. Everything else is recorded as unimplemented. */
    private record KeyedValue(Class<?> type, NamespacedKey key) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Origami" + type.getSimpleName() + "{" + key + "}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            int n = args == null ? 0 : args.length;
            if (n == 0) {
                switch (method.getName()) {
                    case "getKey", "key" -> {
                        return key;
                    }
                    case "name" -> {
                        if (method.getReturnType() == String.class) {
                            return key.getKey().toUpperCase(Locale.ROOT).replace('.', '_');
                        }
                    }
                    default -> { }
                }
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw UnimplementedCalls.exception(method);
        }
    }
}
