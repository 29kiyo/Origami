package io.github.kiyo29.origami.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bukkit ItemStack backed by a Minecraft ItemStack (the "craftDelegate" of org.bukkit.inventory.ItemStack).
 * It uses the protected no-arg constructor, so craftDelegate stays null and every public method
 * that reads craftDelegate must be overridden here (checked with javap, see COMPATIBILITY.md).
 * Phase 8 step 1: type / amount / durability / equality only.
 * ItemMeta and enchantments are NOT implemented yet (hasItemMeta = false, getItemMeta throws).
 * setType replaces the handle, so an inventory-mirrored stack stops mirroring after setType (partial).
 */
public final class OrigamiItemStack extends ItemStack {
    private net.minecraft.world.item.ItemStack handle;

    private OrigamiItemStack(net.minecraft.world.item.ItemStack handle) {
        super();
        this.handle = handle;
    }

    public static OrigamiItemStack create(Material material, int amount) {
        return new OrigamiItemStack(new net.minecraft.world.item.ItemStack(itemOf(material), amount));
    }

    public static OrigamiItemStack empty() {
        return new OrigamiItemStack(net.minecraft.world.item.ItemStack.EMPTY);
    }

    /** Live view of a Minecraft stack (used by inventories). Changes write through to the handle. */
    public static OrigamiItemStack mirror(net.minecraft.world.item.ItemStack handle) {
        return new OrigamiItemStack(handle);
    }

    public net.minecraft.world.item.ItemStack handle() {
        return handle;
    }

    static Item itemOf(Material material) {
        if (material == null) {
            throw new IllegalArgumentException("Material cannot be null");
        }
        if (material.isLegacy()) {
            throw new IllegalArgumentException("Legacy materials are not supported: " + material);
        }
        Identifier id = Identifier.tryParse("minecraft:" + material.name().toLowerCase(Locale.ROOT));
        Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
        if (item == null || (item == Items.AIR && material != Material.AIR)) {
            throw new IllegalArgumentException(material + " is not an item");
        }
        return item;
    }

    static Material materialOf(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null || !"minecraft".equals(id.getNamespace())) {
            return Material.AIR;
        }
        Material m = Material.getMaterial(id.getPath().toUpperCase(Locale.ROOT));
        return m == null ? Material.AIR : m;
    }

    @Override
    public Material getType() {
        return handle.isEmpty() ? Material.AIR : materialOf(handle.getItem());
    }

    @Override
    public void setType(Material type) {
        int amount = Math.max(1, getAmount());
        handle = type == Material.AIR
                ? net.minecraft.world.item.ItemStack.EMPTY
                : new net.minecraft.world.item.ItemStack(itemOf(type), amount);
    }

    @Override
    public ItemStack withType(Material type) {
        if (type == Material.AIR) {
            return empty();
        }
        return create(type, Math.max(1, getAmount()));
    }

    @Override
    public int getAmount() {
        return handle.isEmpty() ? 0 : handle.getCount();
    }

    @Override
    public void setAmount(int amount) {
        if (handle != net.minecraft.world.item.ItemStack.EMPTY) {
            handle.setCount(amount);
        }
    }

    @Override
    public short getDurability() {
        return handle.isEmpty() ? 0 : (short) handle.getDamageValue();
    }

    @Override
    public void setDurability(short durability) {
        if (!handle.isEmpty()) {
            handle.setDamageValue(durability);
        }
    }

    @Override
    public int getMaxStackSize() {
        return handle.isEmpty() ? Material.AIR.getMaxStackSize() : handle.getMaxStackSize();
    }

    @Override
    public boolean isEmpty() {
        return handle.isEmpty();
    }

    @Override
    public ItemStack clone() {
        return new OrigamiItemStack(handle.copy());
    }

    @Override
    public boolean isSimilar(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        if (stack == this) {
            return true;
        }
        if (stack instanceof OrigamiItemStack other) {
            return net.minecraft.world.item.ItemStack.isSameItemSameComponents(handle, other.handle);
        }
        return getType() == stack.getType();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof ItemStack other)) {
            return false;
        }
        return getAmount() == other.getAmount() && isSimilar(other);
    }

    @Override
    public int hashCode() {
        if (handle.isEmpty()) {
            return 0;
        }
        return net.minecraft.world.item.ItemStack.hashItemAndComponents(handle) * 31 + handle.getCount();
    }

    @Override
    public String toString() {
        return "ItemStack{" + getType() + " x " + getAmount() + "}";
    }

    @Override
    public String getTranslationKey() {
        return getType().asItemType().getTranslationKey();
    }

    // ---- ItemMeta / enchantments: not implemented yet (next step) ----

    @Override
    public boolean hasItemMeta() {
        return ItemMetaBridge.has(handle);
    }

    @Override
    public ItemMeta getItemMeta() {
        return handle.isEmpty() ? null : ItemMetaBridge.fromHandle(handle);
    }

    @Override
    public boolean setItemMeta(ItemMeta itemMeta) {
        return ItemMetaBridge.apply(handle, itemMeta);
    }

    @Override
    public boolean containsEnchantment(Enchantment ench) {
        return ench != null && getEnchantmentLevel(ench) > 0;
    }

    @Override
    public int getEnchantmentLevel(Enchantment ench) {
        if (ench == null) {
            throw new IllegalArgumentException("Enchantment cannot be null");
        }
        ItemMeta m = getItemMeta();
        return m == null ? 0 : m.getEnchantLevel(ench);
    }

    @Override
    public Map<Enchantment, Integer> getEnchantments() {
        ItemMeta m = getItemMeta();
        return m == null ? new HashMap<>() : new HashMap<>(m.getEnchants());
    }

    @Override
    public void addEnchantments(Map<Enchantment, Integer> enchantments) {
        if (enchantments == null) {
            throw new IllegalArgumentException("Enchantments cannot be null");
        }
        for (Map.Entry<Enchantment, Integer> e : enchantments.entrySet()) {
            addEnchantment(e.getKey(), e.getValue());
        }
    }

    @Override
    public void addEnchantment(Enchantment ench, int level) {
        if (ench == null) {
            throw new IllegalArgumentException("Enchantment cannot be null");
        }
        if (level < ench.getStartLevel() || level > ench.getMaxLevel()) {
            throw new IllegalArgumentException("Enchantment level is either too low or too high (given " + level
                    + ", bounds are " + ench.getStartLevel() + " to " + ench.getMaxLevel() + ")");
        }
        if (!ench.canEnchantItem(this)) {
            throw new IllegalArgumentException("Specified enchantment cannot be applied to this itemstack");
        }
        addUnsafeEnchantment(ench, level);
    }

    @Override
    public void addUnsafeEnchantments(Map<Enchantment, Integer> enchantments) {
        for (Map.Entry<Enchantment, Integer> e : enchantments.entrySet()) {
            addUnsafeEnchantment(e.getKey(), e.getValue());
        }
    }

    @Override
    public void addUnsafeEnchantment(Enchantment ench, int level) {
        if (ench == null) {
            throw new IllegalArgumentException("Enchantment cannot be null");
        }
        ItemMeta m = getItemMeta();
        if (m == null) {
            throw new IllegalStateException("Cannot enchant an empty stack");
        }
        m.addEnchant(ench, level, true);
        setItemMeta(m);
    }

    @Override
    public int removeEnchantment(Enchantment ench) {
        ItemMeta m = getItemMeta();
        if (m == null) {
            return 0;
        }
        int old = m.getEnchantLevel(ench);
        m.removeEnchant(ench);
        setItemMeta(m);
        return old;
    }

    @Override
    public void removeEnchantments() {
        for (Enchantment e : new java.util.ArrayList<>(getEnchantments().keySet())) {
            removeEnchantment(e);
        }
    }
}
