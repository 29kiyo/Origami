package io.github.kiyo29.origami.bridge;

import net.kyori.adventure.text.Component;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.entity.EntityCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enchantments backed by the dynamic Minecraft registry (server.registryAccess()).
 * Enchantment is an abstract class, so OrigamiEnchantment (Impl) extends it. It stores only the
 * Identifier and resolves the Holder on every call (the registry is replaced when a world is reopened).
 * Partial: treasure / cursed / tradeable / discoverable / item target / rarity / slots / item sets
 * are not implemented (UnimplementedCalls).
 */
final class EnchantmentBridge {
    private static final Map<Identifier, Impl> CACHE = new ConcurrentHashMap<>();
    private static volatile Registry<?> registry;

    private EnchantmentBridge() { }

    static Registry<?> registry() {
        Registry<?> r = registry;
        if (r == null) {
            r = (Registry<?>) Proxy.newProxyInstance(Registry.class.getClassLoader(),
                    new Class<?>[] {Registry.class}, new RegistryHandler());
            registry = r;
        }
        return r;
    }

    static net.minecraft.core.Registry<net.minecraft.world.item.enchantment.Enchantment> mcRegistry() {
        MinecraftServer s = OrigamiServer.get().minecraft();
        if (s == null) {
            return null;
        }
        try {
            return s.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Enchantment of(Identifier id) {
        return CACHE.computeIfAbsent(id, Impl::new);
    }

    /** null when the server is not running or the enchantment does not exist. */
    static Enchantment lookup(Identifier id) {
        var reg = mcRegistry();
        if (id == null || reg == null || !reg.containsKey(id)) {
            return null;
        }
        return of(id);
    }

    static Holder<net.minecraft.world.item.enchantment.Enchantment> holderOf(Enchantment e) {
        if (e instanceof Impl i) {
            return i.holder();
        }
        throw new IllegalArgumentException("Unsupported Enchantment: " + e);
    }

    static final class Impl extends Enchantment {
        private final Identifier id;

        Impl(Identifier id) {
            this.id = id;
        }

        Holder<net.minecraft.world.item.enchantment.Enchantment> holder() {
            var reg = mcRegistry();
            if (reg == null) {
                throw new IllegalStateException("Enchantment registry is not available (server not running)");
            }
            return reg.get(ResourceKey.create(Registries.ENCHANTMENT, id))
                    .orElseThrow(() -> new IllegalStateException("Unknown enchantment: " + id));
        }

        private net.minecraft.world.item.enchantment.Enchantment value() {
            return holder().value();
        }

        public NamespacedKey getKey() {
            return new NamespacedKey(id.getNamespace(), id.getPath());
        }

        @Override
        public String getName() {
            return id.getPath().toUpperCase(java.util.Locale.ROOT);
        }

        @Override
        public int getMaxLevel() {
            return value().getMaxLevel();
        }

        @Override
        public int getStartLevel() {
            return value().getMinLevel();
        }

        @Override
        public EnchantmentTarget getItemTarget() {
            throw UnimplementedCalls.exception("Enchantment#getItemTarget()");
        }

        @Override
        public boolean isTreasure() {
            throw UnimplementedCalls.exception("Enchantment#isTreasure()");
        }

        @Override
        public boolean isCursed() {
            throw UnimplementedCalls.exception("Enchantment#isCursed()");
        }

        @Override
        public boolean conflictsWith(Enchantment other) {
            if (other == null || other.equals(this)) {
                return false;
            }
            return !net.minecraft.world.item.enchantment.Enchantment.areCompatible(holder(), holderOf(other));
        }

        @Override
        public boolean canEnchantItem(ItemStack item) {
            if (item == null || item.getType() == Material.AIR) {
                return false;
            }
            net.minecraft.world.item.ItemStack h = item instanceof OrigamiItemStack o
                    ? o.handle() : OrigamiItemStack.create(item.getType(), 1).handle();
            return value().isSupportedItem(h);
        }

        @Override
        public Component displayName(int level) {
            return ItemMetaBridge.fromMc(net.minecraft.world.item.enchantment.Enchantment.getFullname(holder(), level));
        }

        @Override
        public boolean isTradeable() {
            throw UnimplementedCalls.exception("Enchantment#isTradeable()");
        }

        @Override
        public boolean isDiscoverable() {
            throw UnimplementedCalls.exception("Enchantment#isDiscoverable()");
        }

        @Override
        public int getMinModifiedCost(int level) {
            return value().getMinCost(level);
        }

        @Override
        public int getMaxModifiedCost(int level) {
            return value().getMaxCost(level);
        }

        @Override
        public int getAnvilCost() {
            return value().definition().anvilCost();
        }

        @Override
        public io.papermc.paper.enchantments.EnchantmentRarity getRarity() {
            throw UnimplementedCalls.exception("Enchantment#getRarity()");
        }

        @Override
        public float getDamageIncrease(int level, EntityCategory category) {
            throw UnimplementedCalls.exception("Enchantment#getDamageIncrease(int, EntityCategory)");
        }

        @Override
        public float getDamageIncrease(int level, EntityType type) {
            throw UnimplementedCalls.exception("Enchantment#getDamageIncrease(int, EntityType)");
        }

        @Override
        public Set<EquipmentSlotGroup> getActiveSlotGroups() {
            throw UnimplementedCalls.exception("Enchantment#getActiveSlotGroups()");
        }

        @Override
        public Component description() {
            return ItemMetaBridge.fromMc(value().description());
        }

        @Override
        public io.papermc.paper.registry.set.RegistryKeySet<ItemType> getSupportedItems() {
            throw UnimplementedCalls.exception("Enchantment#getSupportedItems()");
        }

        @Override
        public io.papermc.paper.registry.set.RegistryKeySet<ItemType> getPrimaryItems() {
            throw UnimplementedCalls.exception("Enchantment#getPrimaryItems()");
        }

        @Override
        public int getWeight() {
            return value().definition().weight();
        }

        @Override
        public io.papermc.paper.registry.set.RegistryKeySet<Enchantment> getExclusiveWith() {
            throw UnimplementedCalls.exception("Enchantment#getExclusiveWith()");
        }

        @Override
        public String translationKey() {
            return "enchantment." + id.getNamespace() + "." + id.getPath();
        }

        public String getTranslationKey() {
            return translationKey();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Impl i && i.id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "Enchantment[" + id + "]";
        }
    }

    private static List<Identifier> names() {
        var reg = mcRegistry();
        if (reg == null) {
            return List.of();
        }
        return reg.keySet().stream().sorted(Comparator.comparing(Identifier::toString)).toList();
    }

    private static final class RegistryHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "OrigamiRegistry{ENCHANTMENT}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            Object[] a = args == null ? new Object[0] : args;
            switch (method.getName()) {
                case "get" -> {
                    if (!method.isDefault() && a[0] instanceof NamespacedKey k) {
                        return lookup(Identifier.tryParse(k.getNamespace() + ":" + k.getKey()));
                    }
                }
                case "getKey" -> {
                    if (!method.isDefault()) {
                        return a[0] == null ? null : ((Enchantment) a[0]).getKey();
                    }
                }
                case "keyStream" -> {
                    return names().stream().map(i -> new NamespacedKey(i.getNamespace(), i.getPath()));
                }
                case "stream" -> {
                    return names().stream().map(EnchantmentBridge::of);
                }
                case "iterator" -> {
                    return names().stream().map(EnchantmentBridge::of).iterator();
                }
                case "size" -> {
                    return names().size();
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
}
