package io.github.kiyo29.origami.bridge;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.Unit;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ItemMeta as a Proxy (ItemMeta + Damageable + Repairable). The state is a plain object;
 * it is read from a Minecraft ItemStack by fromHandle() and written back by apply().
 * Partial: display name, lore, damage / max damage, unbreakable, custom model data.
 * Item flags and repair cost live only in the meta object (not written to the stack).
 * Enchantments are not supported yet (reads are empty, addEnchant throws).
 */
final class ItemMetaBridge {
    private static final Object UNHANDLED = new Object();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private ItemMetaBridge() { }

    static final class State {
        Component name;
        List<Component> lore;
        int damage;
        Integer maxDamage;
        boolean unbreakable;
        Integer customModelData;
        final EnumSet<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
        int repairCost;
        final Map<Enchantment, Integer> enchants = new LinkedHashMap<>();

        State copy() {
            State c = new State();
            c.name = name;
            c.lore = lore == null ? null : new ArrayList<>(lore);
            c.damage = damage;
            c.maxDamage = maxDamage;
            c.unbreakable = unbreakable;
            c.customModelData = customModelData;
            c.flags.addAll(flags);
            c.repairCost = repairCost;
            c.enchants.putAll(enchants);
            return c;
        }

        boolean sameAs(State o) {
            return Objects.equals(name, o.name) && Objects.equals(lore, o.lore) && damage == o.damage
                    && Objects.equals(maxDamage, o.maxDamage) && unbreakable == o.unbreakable
                    && Objects.equals(customModelData, o.customModelData) && flags.equals(o.flags)
                    && repairCost == o.repairCost && enchants.equals(o.enchants);
        }
    }

    static ItemMeta create() {
        return proxy(new State());
    }

    private static ItemMeta proxy(State s) {
        return (ItemMeta) Proxy.newProxyInstance(CombinedMeta.class.getClassLoader(),
                new Class<?>[] {CombinedMeta.class}, new MetaHandler(s));
    }

    static boolean isMeta(Object o) {
        return o != null && Proxy.isProxyClass(o.getClass()) && Proxy.getInvocationHandler(o) instanceof MetaHandler;
    }

    static State stateOf(Object meta) {
        if (isMeta(meta)) {
            return ((MetaHandler) Proxy.getInvocationHandler(meta)).s;
        }
        throw new IllegalArgumentException("Unsupported ItemMeta: " + meta);
    }

    static boolean same(Object a, Object b) {
        return stateOf(a).sameAs(stateOf(b));
    }

    // ---- Component conversion (adventure <-> Minecraft, via JSON) ----

    static net.minecraft.network.chat.Component toMc(Component c) {
        try {
            String json = GsonComponentSerializer.gson().serialize(c);
            return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result()
                    .orElseGet(() -> net.minecraft.network.chat.Component.literal(PlainTextComponentSerializer.plainText().serialize(c)));
        } catch (RuntimeException e) {
            return net.minecraft.network.chat.Component.literal(PlainTextComponentSerializer.plainText().serialize(c));
        }
    }

    static Component fromMc(net.minecraft.network.chat.Component c) {
        try {
            return ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, c).result()
                    .map(e -> GsonComponentSerializer.gson().deserialize(e.toString()))
                    .orElseGet(() -> Component.text(c.getString()));
        } catch (RuntimeException e) {
            return Component.text(c.getString());
        }
    }

    // ---- Minecraft ItemStack <-> meta ----

    static boolean has(net.minecraft.world.item.ItemStack h) {
        if (h.isEmpty()) {
            return false;
        }
        State s = read(h);
        return s.name != null || (s.lore != null && !s.lore.isEmpty()) || s.damage > 0 || s.unbreakable
                || s.customModelData != null || !s.enchants.isEmpty();
    }

    static ItemMeta fromHandle(net.minecraft.world.item.ItemStack h) {
        return proxy(read(h));
    }

    private static State read(net.minecraft.world.item.ItemStack h) {
        State s = new State();
        net.minecraft.network.chat.Component name = h.get(DataComponents.CUSTOM_NAME);
        s.name = name == null ? null : fromMc(name);
        ItemLore lore = h.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            s.lore = new ArrayList<>();
            for (net.minecraft.network.chat.Component line : lore.lines()) {
                s.lore.add(fromMc(line));
            }
        }
        Integer damage = h.get(DataComponents.DAMAGE);
        s.damage = damage == null ? 0 : damage;
        s.maxDamage = h.get(DataComponents.MAX_DAMAGE);
        s.unbreakable = h.has(DataComponents.UNBREAKABLE);
        CustomModelData cmd = h.get(DataComponents.CUSTOM_MODEL_DATA);
        s.customModelData = cmd == null || cmd.floats().isEmpty() ? null : cmd.floats().get(0).intValue();
        net.minecraft.world.item.enchantment.ItemEnchantments ench = h.get(DataComponents.ENCHANTMENTS);
        if (ench != null) {
            for (var holder : ench.keySet()) {
                var id = holder.unwrapKey().map(k -> k.identifier()).orElse(null);
                if (id != null) {
                    s.enchants.put(EnchantmentBridge.of(id), ench.getLevel(holder));
                }
            }
        }
        return s;
    }

    /** meta == null clears the supported components. Returns false for an empty stack. */
    static boolean apply(net.minecraft.world.item.ItemStack h, ItemMeta meta) {
        if (h.isEmpty()) {
            return false;
        }
        State s = meta == null ? new State() : stateOf(meta);
        if (s.name == null) {
            h.remove(DataComponents.CUSTOM_NAME);
        } else {
            h.set(DataComponents.CUSTOM_NAME, toMc(s.name));
        }
        if (s.lore == null || s.lore.isEmpty()) {
            h.remove(DataComponents.LORE);
        } else {
            List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
            for (Component c : s.lore) {
                lines.add(toMc(c));
            }
            h.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (h.isDamageableItem()) {
            h.set(DataComponents.DAMAGE, s.damage);
            if (s.maxDamage != null) {
                h.set(DataComponents.MAX_DAMAGE, s.maxDamage);
            }
        }
        if (s.unbreakable) {
            h.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        } else {
            h.remove(DataComponents.UNBREAKABLE);
        }
        if (s.customModelData == null) {
            h.remove(DataComponents.CUSTOM_MODEL_DATA);
        } else {
            h.set(DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(List.of(Float.valueOf(s.customModelData)), List.of(), List.of(), List.of()));
        }
        if (s.enchants.isEmpty()) {
            h.remove(DataComponents.ENCHANTMENTS);
        } else {
            Map<Enchantment, Integer> want = new LinkedHashMap<>(s.enchants);
            net.minecraft.world.item.enchantment.EnchantmentHelper.updateEnchantments(h, mut -> {
                mut.removeIf(x -> true);
                for (var e : want.entrySet()) {
                    mut.set(EnchantmentBridge.holderOf(e.getKey()), e.getValue());
                }
            });
        }
        return true;
    }

    // ---- the Proxy handler ----

    private static final class MetaHandler implements InvocationHandler {
        final State s;

        MetaHandler(State s) {
            this.s = s;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> isMeta(args[0]) && s.sameAs(stateOf(args[0]));
                    case "hashCode" -> Objects.hash(s.name, s.lore, s.damage, s.unbreakable, s.customModelData);
                    case "toString" -> "OrigamiItemMeta{name=" + s.name + ", lore=" + s.lore + ", damage=" + s.damage + "}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            Object[] a = args == null ? new Object[0] : args;
            int c = a.length;
            switch (method.getName()) {
                case "hasDisplayName", "hasCustomName" -> { return s.name != null; }
                case "getDisplayName" -> { return s.name == null ? null : LEGACY.serialize(s.name); }
                case "setDisplayName" -> {
                    s.name = a[0] == null ? null : LEGACY.deserialize((String) a[0]);
                    return null;
                }
                case "customName", "displayName" -> {
                    if (c == 0) {
                        return s.name;
                    }
                    s.name = (Component) a[0];
                    return null;
                }
                case "hasLore" -> { return s.lore != null && !s.lore.isEmpty(); }
                case "getLore" -> {
                    if (s.lore == null) {
                        return null;
                    }
                    List<String> out = new ArrayList<>();
                    for (Component line : s.lore) {
                        out.add(LEGACY.serialize(line));
                    }
                    return out;
                }
                case "setLore" -> {
                    if (a[0] == null) {
                        s.lore = null;
                    } else {
                        s.lore = new ArrayList<>();
                        for (String line : (List<String>) a[0]) {
                            s.lore.add(LEGACY.deserialize(line));
                        }
                    }
                    return null;
                }
                case "lore" -> {
                    if (c == 0) {
                        return s.lore == null ? null : new ArrayList<>(s.lore);
                    }
                    s.lore = a[0] == null ? null : new ArrayList<>((List<Component>) a[0]);
                    return null;
                }
                case "isUnbreakable" -> { return s.unbreakable; }
                case "setUnbreakable" -> { s.unbreakable = (Boolean) a[0]; return null; }
                case "hasCustomModelData" -> { return s.customModelData != null; }
                case "getCustomModelData" -> {
                    if (s.customModelData == null) {
                        throw new IllegalStateException("We don't have CustomModelData! Check hasCustomModelData first!");
                    }
                    return s.customModelData;
                }
                case "setCustomModelData" -> { s.customModelData = (Integer) a[0]; return null; }
                case "hasItemFlag" -> { return s.flags.contains((ItemFlag) a[0]); }
                case "getItemFlags" -> { return new HashSet<>(s.flags); }
                case "addItemFlags" -> { Collections.addAll(s.flags, (ItemFlag[]) a[0]); return null; }
                case "removeItemFlags" -> { for (ItemFlag f : (ItemFlag[]) a[0]) { s.flags.remove(f); } return null; }
                case "hasDamage", "hasDamageValue" -> { return s.damage > 0; }
                case "getDamage" -> { return s.damage; }
                case "setDamage" -> { s.damage = Math.max(0, (Integer) a[0]); return null; }
                case "resetDamage" -> { s.damage = 0; return null; }
                case "hasMaxDamage" -> { return s.maxDamage != null; }
                case "getMaxDamage" -> {
                    if (s.maxDamage == null) {
                        throw new IllegalStateException("We don't have max_damage! Check hasMaxDamage first!");
                    }
                    return s.maxDamage;
                }
                case "setMaxDamage" -> { s.maxDamage = (Integer) a[0]; return null; }
                case "hasRepairCost" -> { return s.repairCost > 0; }
                case "getRepairCost" -> { return s.repairCost; }
                case "setRepairCost" -> { s.repairCost = (Integer) a[0]; return null; }
                case "hasEnchants" -> { return !s.enchants.isEmpty(); }
                case "hasEnchant" -> { return a[0] != null && s.enchants.containsKey((Enchantment) a[0]); }
                case "getEnchants" -> { return new LinkedHashMap<>(s.enchants); }
                case "getEnchantLevel" -> { Integer l = s.enchants.get((Enchantment) a[0]); return l == null ? 0 : l; }
                case "addEnchant" -> {
                    Enchantment e = (Enchantment) a[0];
                    int lvl = (Integer) a[1];
                    boolean unsafe = (Boolean) a[2];
                    if (e == null) {
                        throw new IllegalArgumentException("Enchantment cannot be null");
                    }
                    if (!unsafe && (lvl < e.getStartLevel() || lvl > e.getMaxLevel())) {
                        return false;
                    }
                    Integer old = s.enchants.put(e, lvl);
                    return old == null || old != lvl;
                }
                case "removeEnchant" -> { return s.enchants.remove((Enchantment) a[0]) != null; }
                case "removeEnchantments" -> { s.enchants.clear(); return null; }
                case "hasConflictingEnchant" -> {
                    Enchantment e = (Enchantment) a[0];
                    for (Enchantment have : s.enchants.keySet()) {
                        if (have.conflictsWith(e)) {
                            return true;
                        }
                    }
                    return false;
                }
                case "hasAttributeModifiers" -> { return false; }
                case "setVersion" -> { return null; }
                case "clone" -> { return proxy(s.copy()); }
                case "serialize" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("meta-type", "UNSPECIFIC");
                    if (s.name != null) {
                        m.put("display-name", LEGACY.serialize(s.name));
                    }
                    if (s.lore != null) {
                        List<String> out = new ArrayList<>();
                        for (Component line : s.lore) {
                            out.add(LEGACY.serialize(line));
                        }
                        m.put("lore", out);
                    }
                    if (s.unbreakable) {
                        m.put("Unbreakable", true);
                    }
                    if (s.damage > 0) {
                        m.put("Damage", s.damage);
                    }
                    if (s.customModelData != null) {
                        m.put("custom-model-data", s.customModelData);
                    }
                    return m;
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
