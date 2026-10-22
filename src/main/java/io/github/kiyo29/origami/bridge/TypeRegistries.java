package io.github.kiyo29.origami.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only Registry.ITEM / Registry.BLOCK backed by BuiltInRegistries.
 * Only "minecraft:" entries that also exist in the Bukkit Material enum are exposed
 * (other mods' items / blocks have no Material, so they are left out: partial).
 * ItemType / BlockType are Proxies; unsupported methods go to UnimplementedCalls.
 */
final class TypeRegistries {
    enum Kind { ITEM, BLOCK }

    private static final Object UNHANDLED = new Object();
    private static final Set<String> AIR = Set.of("air", "cave_air", "void_air");
    private static final Map<Kind, Registry<?>> REGISTRIES = new ConcurrentHashMap<>();
    private static final Map<String, Object> TYPES = new ConcurrentHashMap<>();
    private static final Map<Kind, List<String>> NAMES = new ConcurrentHashMap<>();

    private TypeRegistries() { }

    static Registry<?> registry(Kind kind) {
        return REGISTRIES.computeIfAbsent(kind, k -> (Registry<?>) Proxy.newProxyInstance(
                Registry.class.getClassLoader(), new Class<?>[] {Registry.class}, new RegistryHandler(k)));
    }

    private static Identifier idOf(String path) {
        return Identifier.tryParse("minecraft:" + path);
    }

    private static Material materialOf(String path) {
        return Material.getMaterial(path.toUpperCase(Locale.ROOT));
    }

    static boolean has(Kind kind, String path) {
        Identifier id = idOf(path);
        if (id == null || materialOf(path) == null) {
            return false;
        }
        return kind == Kind.ITEM ? BuiltInRegistries.ITEM.containsKey(id) : BuiltInRegistries.BLOCK.containsKey(id);
    }

    static Object type(Kind kind, String path) {
        if (!has(kind, path)) {
            return null;
        }
        return TYPES.computeIfAbsent(kind.name() + ":" + path, k -> {
            Class<?> iface = kind == Kind.ITEM ? ItemType.class : BlockType.class;
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, new TypeHandler(kind, path));
        });
    }

    private static List<String> names(Kind kind) {
        List<String> cached = NAMES.get(kind);
        if (cached != null) {
            return cached;
        }
        Set<Identifier> keys = kind == Kind.ITEM ? BuiltInRegistries.ITEM.keySet() : BuiltInRegistries.BLOCK.keySet();
        List<String> out = new ArrayList<>();
        for (Identifier id : keys) {
            if ("minecraft".equals(id.getNamespace()) && has(kind, id.getPath())) {
                out.add(id.getPath());
            }
        }
        Collections.sort(out);
        List<String> frozen = List.copyOf(out);
        if (!frozen.isEmpty()) {
            NAMES.put(kind, frozen); // do not cache an empty result (registries not bootstrapped yet)
        }
        return frozen;
    }

    private record RegistryHandler(Kind kind) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "OrigamiRegistry{" + kind + "}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            Object[] a = args == null ? new Object[0] : args;
            switch (method.getName()) {
                case "get" -> {
                    if (!method.isDefault()) {
                        NamespacedKey k = (NamespacedKey) a[0];
                        return k != null && "minecraft".equals(k.getNamespace()) ? type(kind, k.getKey()) : null;
                    }
                }
                case "getKey" -> {
                    if (!method.isDefault()) {
                        return a[0] == null ? null : ((Keyed) a[0]).getKey();
                    }
                }
                case "keyStream" -> {
                    return names(kind).stream().map(NamespacedKey::minecraft);
                }
                case "stream" -> {
                    return names(kind).stream().map(p -> type(kind, p)).filter(Objects::nonNull);
                }
                case "iterator" -> {
                    return names(kind).stream().map(p -> type(kind, p)).filter(Objects::nonNull).iterator();
                }
                case "size" -> {
                    return names(kind).size();
                }
                case "hasTag" -> {
                    return false;
                }
                case "getTags" -> {
                    return List.of();
                }
                default -> { }
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw UnimplementedCalls.exception(method);
        }
    }

    private record TypeHandler(Kind kind, String path) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> kind.name() + "Type{minecraft:" + path + "}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            Object[] a = args == null ? new Object[0] : args;
            Object result = kind == Kind.ITEM ? item(method, a) : block(method);
            if (result != UNHANDLED) {
                return result;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw UnimplementedCalls.exception(method);
        }

        private String translationKey() {
            boolean block = kind == Kind.BLOCK || has(Kind.BLOCK, path);
            return (block ? "block." : "item.") + "minecraft." + path;
        }

        private Object item(Method method, Object[] a) {
            Material material = materialOf(path);
            return switch (method.getName()) {
                case "getKey" -> NamespacedKey.minecraft(path);
                case "asMaterial" -> material;
                case "getTranslationKey", "translationKey" -> translationKey();
                case "isEnabledByFeature" -> true;
                case "getMaxStackSize" -> BuiltInRegistries.ITEM.getValue(idOf(path)).getDefaultMaxStackSize();
                case "hasBlockType" -> has(Kind.BLOCK, path);
                case "getBlockType" -> {
                    Object b = type(Kind.BLOCK, path);
                    if (b == null) {
                        throw new IllegalStateException("minecraft:" + path + " has no block type");
                    }
                    yield b;
                }
                case "createItemStack" -> new ItemStack(material, a.length == 0 ? 1 : (Integer) a[0]);
                default -> UNHANDLED;
            };
        }

        private Object block(Method method) {
            return switch (method.getName()) {
                case "getKey" -> NamespacedKey.minecraft(path);
                case "asMaterial" -> materialOf(path);
                case "getTranslationKey", "translationKey" -> translationKey();
                case "isEnabledByFeature" -> true;
                case "isAir" -> AIR.contains(path);
                case "hasItemType" -> has(Kind.ITEM, path);
                case "getItemType" -> {
                    Object i = type(Kind.ITEM, path);
                    if (i == null) {
                        throw new IllegalStateException("minecraft:" + path + " has no item type");
                    }
                    yield i;
                }
                default -> UNHANDLED;
            };
        }
    }
}
