package io.github.kiyo29.origami.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server#getTag / Server#getTags backed by MC's tags.
 * A Tag is a lazy Proxy: every call looks the MC registry up again, so it follows tag reloads.
 * Supported: "blocks" and "items" (Material), "entity_types" (EntityType).
 * "fluids" and "game_events" still return null.
 */
final class TagBridge {
    private TagBridge() { }

    private enum Kind {
        BLOCKS("blocks", Material.class),
        ITEMS("items", Material.class),
        ENTITY_TYPES("entity_types", EntityType.class);

        final String registry;
        final Class<?> type;

        Kind(String registry, Class<?> type) {
            this.registry = registry;
            this.type = type;
        }

        static Kind of(String registry, Class<?> type) {
            for (Kind k : values()) {
                if (k.registry.equals(registry) && k.type == type) {
                    return k;
                }
            }
            return null;
        }
    }

    private static final Map<String, Object> TAGS = new ConcurrentHashMap<>();

    static Object get(String registry, NamespacedKey key, Class<?> type) {
        Kind kind = Kind.of(registry, type);
        return kind == null || key == null ? null : tag(kind, key);
    }

    static Iterable<Object> getAll(String registry, Class<?> type) {
        Kind kind = Kind.of(registry, type);
        List<Object> out = new ArrayList<>();
        if (kind != null) {
            for (Identifier id : tagIds(kind)) {
                out.add(tag(kind, new NamespacedKey(id.getNamespace(), id.getPath())));
            }
        }
        return out;
    }

    private static Object tag(Kind kind, NamespacedKey key) {
        return TAGS.computeIfAbsent(kind + "|" + key, k -> Proxy.newProxyInstance(
                org.bukkit.Tag.class.getClassLoader(), new Class<?>[] {org.bukkit.Tag.class},
                new TagHandler(kind, key, Identifier.fromNamespaceAndPath(key.getNamespace(), key.getKey()))));
    }

    private static Identifier idOf(Object o) {
        try {
            if (o instanceof Keyed k) {
                NamespacedKey nk = k.getKey();
                return Identifier.fromNamespaceAndPath(nk.getNamespace(), nk.getKey());
            }
        } catch (RuntimeException ignored) {
            // e.g. EntityType.UNKNOWN has no key
        }
        return null;
    }

    private static boolean tagged(Kind kind, Identifier id, Identifier tag) {
        return switch (kind) {
            case BLOCKS -> has(BuiltInRegistries.BLOCK, Registries.BLOCK, id, tag);
            case ITEMS -> has(BuiltInRegistries.ITEM, Registries.ITEM, id, tag);
            case ENTITY_TYPES -> has(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE, id, tag);
        };
    }

    private static <T> boolean has(net.minecraft.core.Registry<T> reg,
                                   ResourceKey<? extends net.minecraft.core.Registry<T>> rk,
                                   Identifier id, Identifier tag) {
        return reg.get(id).map(h -> h.is(TagKey.create(rk, tag))).orElse(false);
    }

    private static List<Identifier> tagIds(Kind kind) {
        return switch (kind) {
            case BLOCKS -> names(BuiltInRegistries.BLOCK);
            case ITEMS -> names(BuiltInRegistries.ITEM);
            case ENTITY_TYPES -> names(BuiltInRegistries.ENTITY_TYPE);
        };
    }

    private static <T> List<Identifier> names(net.minecraft.core.Registry<T> reg) {
        return reg.getTags().map(n -> n.key().location()).toList();
    }

    private record TagHandler(Kind kind, NamespacedKey key, Identifier id) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "OrigamiTag{" + kind.registry + ":" + key + "}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            switch (method.getName()) {
                case "getKey", "key" -> {
                    return key;
                }
                case "isTagged" -> {
                    if (!kind.type.isInstance(args[0])) {
                        return false;
                    }
                    Identifier v = idOf(args[0]);
                    return v != null && tagged(kind, v, id);
                }
                case "getValues" -> {
                    Set<Object> out = new LinkedHashSet<>();
                    if (kind.type == Material.class) {
                        for (Material m : Material.values()) {
                            Identifier v = m.isLegacy() ? null : idOf(m);
                            if (v != null && tagged(kind, v, id)) {
                                out.add(m);
                            }
                        }
                    } else {
                        for (EntityType e : EntityType.values()) {
                            Identifier v = idOf(e);
                            if (v != null && tagged(kind, v, id)) {
                                out.add(e);
                            }
                        }
                    }
                    return Collections.unmodifiableSet(out);
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
}
