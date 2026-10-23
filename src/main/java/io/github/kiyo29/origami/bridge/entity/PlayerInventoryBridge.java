package io.github.kiyo29.origami.bridge.entity;

import io.github.kiyo29.origami.bridge.OrigamiItemStack;
import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.UnimplementedCalls;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * PlayerInventory Proxy over a Minecraft Inventory. Bukkit slots 0-35 = storage (0-8 hotbar),
 * 36 boots / 37 leggings / 38 chestplate / 39 helmet / 40 off hand (same numbers as Minecraft).
 * Stacks returned from here are live mirrors of the Minecraft stacks (OrigamiItemStack.mirror).
 * Partial: no inventory events, no cursor / open-view handling beyond getItemOnCursor.
 * Writes must happen on the server thread.
 */
public final class PlayerInventoryBridge implements InvocationHandler {
    /** "not handled here" marker for PlayerBridge. */
    public static final Object NONE = new Object();
    private static final int STORAGE = 36;
    private static final int TOTAL = 41;

    private final ServerPlayer player;

    private PlayerInventoryBridge(ServerPlayer player) {
        this.player = player;
    }

    /** Player-level methods (getInventory / item in hand / cursor). Returns NONE when not ours. */
    public static Object handle(ServerPlayer p, Method m, Object[] args) {
        if (p == null) {
            return NONE;
        }
        int n = m.getParameterCount();
        Inventory inv = p.getInventory();
        switch (m.getName()) {
            case "getInventory":
                return n == 0 ? create(p) : NONE;
            case "getItemInHand":
                return n == 0 ? nonNull(inv.getItem(inv.getSelectedSlot())) : NONE;
            case "setItemInHand":
                if (n == 1) {
                    requireThread();
                    inv.setItem(inv.getSelectedSlot(), toMc((ItemStack) args[0]));
                    return null;
                }
                return NONE;
            case "getItemOnCursor":
                return n == 0 ? nonNull(p.containerMenu.getCarried()) : NONE;
            case "setItemOnCursor":
                if (n == 1) {
                    requireThread();
                    p.containerMenu.setCarried(toMc((ItemStack) args[0]));
                    return null;
                }
                return NONE;
            default:
                return NONE;
        }
    }

    public static PlayerInventory create(ServerPlayer p) {
        return (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[] {PlayerInventory.class}, new PlayerInventoryBridge(p));
    }

    // ---- helpers ----

    private static void requireThread() {
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (mc != null && !mc.isSameThread()) {
            throw new IllegalStateException("Inventory must be modified on the server thread");
        }
    }

    private static ItemStack bukkit(net.minecraft.world.item.ItemStack mc) {
        return mc.isEmpty() ? null : OrigamiItemStack.mirror(mc);
    }

    private static ItemStack nonNull(net.minecraft.world.item.ItemStack mc) {
        return mc.isEmpty() ? OrigamiItemStack.empty() : OrigamiItemStack.mirror(mc);
    }

    static net.minecraft.world.item.ItemStack toMc(ItemStack s) {
        if (s == null || s.getType() == Material.AIR || s.getAmount() <= 0) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        if (s instanceof OrigamiItemStack o) {
            return o.handle().copy();
        }
        OrigamiItemStack t = OrigamiItemStack.create(s.getType(), s.getAmount());
        if (s.hasItemMeta()) {
            t.setItemMeta(s.getItemMeta());
        }
        return t.handle();
    }

    private static int check(int slot) {
        if (slot < 0 || slot >= TOTAL) {
            throw new IndexOutOfBoundsException("Slot " + slot + " is out of range 0.." + (TOTAL - 1));
        }
        return slot;
    }

    private static int slotOf(Inventory inv, EquipmentSlot s) {
        return switch (s) {
            case HAND -> inv.getSelectedSlot();
            case OFF_HAND -> 40;
            case FEET -> 36;
            case LEGS -> 37;
            case CHEST -> 38;
            case HEAD -> 39;
            default -> throw new IllegalArgumentException("Unsupported slot: " + s);
        };
    }

    private void set(Inventory inv, int slot, ItemStack stack) {
        requireThread();
        inv.setItem(check(slot), toMc(stack));
    }

    private static int countMaterial(Inventory inv, Material mat) {
        int total = 0;
        for (int i = 0; i < TOTAL; i++) {
            net.minecraft.world.item.ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && OrigamiItemStack.mirror(st).getType() == mat) {
                total += st.getCount();
            }
        }
        return total;
    }

    private static int countSimilar(Inventory inv, ItemStack stack) {
        net.minecraft.world.item.ItemStack src = toMc(stack);
        int total = 0;
        for (int i = 0; i < TOTAL; i++) {
            net.minecraft.world.item.ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && net.minecraft.world.item.ItemStack.isSameItemSameComponents(st, src)) {
                total += st.getCount();
            }
        }
        return total;
    }

    private HashMap<Integer, ItemStack> addItems(Inventory inv, ItemStack[] items) {
        requireThread();
        HashMap<Integer, ItemStack> left = new HashMap<>();
        for (int idx = 0; idx < items.length; idx++) {
            net.minecraft.world.item.ItemStack src = toMc(items[idx]);
            if (src.isEmpty()) {
                continue;
            }
            int remaining = src.getCount();
            int max = src.getMaxStackSize();
            for (int s = 0; s < STORAGE && remaining > 0; s++) {
                net.minecraft.world.item.ItemStack cur = inv.getItem(s);
                if (!cur.isEmpty() && cur.getCount() < max
                        && net.minecraft.world.item.ItemStack.isSameItemSameComponents(cur, src)) {
                    int move = Math.min(remaining, max - cur.getCount());
                    cur.setCount(cur.getCount() + move);
                    remaining -= move;
                }
            }
            for (int s = 0; s < STORAGE && remaining > 0; s++) {
                if (inv.getItem(s).isEmpty()) {
                    int put = Math.min(remaining, max);
                    inv.setItem(s, src.copyWithCount(put));
                    remaining -= put;
                }
            }
            if (remaining > 0) {
                ItemStack rest = items[idx].clone();
                rest.setAmount(remaining);
                left.put(idx, rest);
            }
        }
        return left;
    }

    private HashMap<Integer, ItemStack> removeItems(Inventory inv, ItemStack[] items) {
        requireThread();
        HashMap<Integer, ItemStack> left = new HashMap<>();
        for (int idx = 0; idx < items.length; idx++) {
            net.minecraft.world.item.ItemStack src = toMc(items[idx]);
            int remaining = items[idx].getAmount();
            for (int s = 0; s < STORAGE && remaining > 0; s++) {
                net.minecraft.world.item.ItemStack cur = inv.getItem(s);
                if (!cur.isEmpty() && net.minecraft.world.item.ItemStack.isSameItemSameComponents(cur, src)) {
                    int take = Math.min(remaining, cur.getCount());
                    cur.setCount(cur.getCount() - take);
                    if (cur.getCount() <= 0) {
                        inv.setItem(s, net.minecraft.world.item.ItemStack.EMPTY);
                    }
                    remaining -= take;
                }
            }
            if (remaining > 0) {
                ItemStack rest = items[idx].clone();
                rest.setAmount(remaining);
                left.put(idx, rest);
            }
        }
        return left;
    }

    private ItemStack[] contents(Inventory inv, int from, int to) {
        ItemStack[] out = new ItemStack[to - from];
        for (int i = from; i < to; i++) {
            out[i - from] = bukkit(inv.getItem(i));
        }
        return out;
    }

    private void setContents(Inventory inv, ItemStack[] arr, int from, int size) {
        if (arr.length > size) {
            throw new IllegalArgumentException("Invalid inventory size (" + arr.length + "); expected " + size + " or less");
        }
        for (int i = 0; i < size; i++) {
            set(inv, from + i, i < arr.length ? arr[i] : null);
        }
    }

    // ---- InvocationHandler ----

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        if (m.getDeclaringClass() == Object.class) {
            return switch (m.getName()) {
                case "equals" -> args[0] != null && Proxy.isProxyClass(args[0].getClass())
                        && Proxy.getInvocationHandler(args[0]) instanceof PlayerInventoryBridge o && o.player == player;
                case "hashCode" -> System.identityHashCode(player);
                case "toString" -> "PlayerInventory{" + player.getName().getString() + "}";
                default -> throw new UnsupportedOperationException(m.getName());
            };
        }
        Object[] a = args == null ? new Object[0] : args;
        Object r = impl(m, a);
        if (r != NONE) {
            return r;
        }
        if (m.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, m, args);
        }
        throw UnimplementedCalls.exception(m);
    }

    private Object impl(Method m, Object[] a) {
        Inventory inv = player.getInventory();
        int c = a.length;
        switch (m.getName()) {
            case "getSize": return TOTAL;
            case "getMaxStackSize": return 64;
            case "setMaxStackSize": return null;
            case "getItem":
                if (a[0] instanceof Integer i) {
                    return bukkit(inv.getItem(check(i)));
                }
                if (a[0] instanceof EquipmentSlot s) {
                    return nonNull(inv.getItem(slotOf(inv, s)));
                }
                return NONE;
            case "setItem":
                if (a[0] instanceof Integer i) {
                    set(inv, i, (ItemStack) a[1]);
                    return null;
                }
                if (a[0] instanceof EquipmentSlot s) {
                    set(inv, slotOf(inv, s), (ItemStack) a[1]);
                    return null;
                }
                return NONE;
            case "addItem": return addItems(inv, (ItemStack[]) a[0]);
            case "removeItem", "removeItemAnySlot": return removeItems(inv, (ItemStack[]) a[0]);
            case "getContents": return contents(inv, 0, TOTAL);
            case "setContents":
                setContents(inv, (ItemStack[]) a[0], 0, TOTAL);
                return null;
            case "getStorageContents": return contents(inv, 0, STORAGE);
            case "setStorageContents":
                setContents(inv, (ItemStack[]) a[0], 0, STORAGE);
                return null;
            case "contains":
                if (a[0] instanceof Material mat) {
                    if (c == 1) {
                        return countMaterial(inv, mat) > 0;
                    }
                    int need = (Integer) a[1];
                    return need <= 0 || countMaterial(inv, mat) >= need;
                }
                if (a[0] instanceof ItemStack st) {
                    if (c == 1) {
                        for (int i = 0; i < TOTAL; i++) {
                            if (st.equals(bukkit(inv.getItem(i)))) {
                                return true;
                            }
                        }
                        return false;
                    }
                    int need = (Integer) a[1];
                    return need <= 0 || countSimilar(inv, st) >= need;
                }
                return false;
            case "containsAtLeast": {
                ItemStack st = (ItemStack) a[0];
                int need = (Integer) a[1];
                return st != null && (need <= 0 || countSimilar(inv, st) >= need);
            }
            case "all": {
                HashMap<Integer, ItemStack> out = new HashMap<>();
                for (int i = 0; i < TOTAL; i++) {
                    ItemStack cur = bukkit(inv.getItem(i));
                    if (cur != null && (a[0] instanceof Material mat ? cur.getType() == mat : cur.equals(a[0]))) {
                        out.put(i, cur);
                    }
                }
                return out;
            }
            case "first":
                for (int i = 0; i < TOTAL; i++) {
                    ItemStack cur = bukkit(inv.getItem(i));
                    if (cur != null && (a[0] instanceof Material mat ? cur.getType() == mat : cur.equals(a[0]))) {
                        return i;
                    }
                }
                return -1;
            case "firstEmpty":
                for (int i = 0; i < STORAGE; i++) {
                    if (inv.getItem(i).isEmpty()) {
                        return i;
                    }
                }
                return -1;
            case "isEmpty": return inv.isEmpty();
            case "remove":
                for (int i = 0; i < TOTAL; i++) {
                    ItemStack cur = bukkit(inv.getItem(i));
                    if (cur != null && (a[0] instanceof Material mat ? cur.getType() == mat : cur.equals(a[0]))) {
                        set(inv, i, null);
                    }
                }
                return null;
            case "clear":
                if (c == 1) {
                    set(inv, (Integer) a[0], null);
                } else {
                    for (int i = 0; i < TOTAL; i++) {
                        set(inv, i, null);
                    }
                }
                return null;
            case "close": return 0;
            case "getViewers": return List.of();
            case "getType": return InventoryType.PLAYER;
            case "getHolder": return PlayerBridge.of(player);
            case "getLocation": return PlayerBridge.of(player).getLocation();
            case "iterator":
                if (c == 0) {
                    return Arrays.asList(contents(inv, 0, TOTAL)).listIterator();
                }
                return Arrays.asList(contents(inv, 0, TOTAL)).listIterator((Integer) a[0]);
            // ---- PlayerInventory ----
            case "getArmorContents": return contents(inv, 36, 40);
            case "getExtraContents": return contents(inv, 40, 41);
            case "setArmorContents": {
                ItemStack[] arr = (ItemStack[]) a[0];
                for (int i = 0; i < 4; i++) {
                    set(inv, 36 + i, arr != null && i < arr.length ? arr[i] : null);
                }
                return null;
            }
            case "setExtraContents": {
                ItemStack[] arr = (ItemStack[]) a[0];
                set(inv, 40, arr != null && arr.length > 0 ? arr[0] : null);
                return null;
            }
            case "getHelmet": return bukkit(inv.getItem(39));
            case "getChestplate": return bukkit(inv.getItem(38));
            case "getLeggings": return bukkit(inv.getItem(37));
            case "getBoots": return bukkit(inv.getItem(36));
            case "setHelmet": set(inv, 39, (ItemStack) a[0]); return null;
            case "setChestplate": set(inv, 38, (ItemStack) a[0]); return null;
            case "setLeggings": set(inv, 37, (ItemStack) a[0]); return null;
            case "setBoots": set(inv, 36, (ItemStack) a[0]); return null;
            case "getItemInMainHand", "getItemInHand": return nonNull(inv.getItem(inv.getSelectedSlot()));
            case "setItemInMainHand", "setItemInHand": set(inv, inv.getSelectedSlot(), (ItemStack) a[0]); return null;
            case "getItemInOffHand": return nonNull(inv.getItem(40));
            case "setItemInOffHand": set(inv, 40, (ItemStack) a[0]); return null;
            case "getHeldItemSlot": return inv.getSelectedSlot();
            case "setHeldItemSlot": {
                int slot = (Integer) a[0];
                if (slot < 0 || slot > 8) {
                    throw new IllegalArgumentException("Slot is not between 0 and 8 inclusive");
                }
                requireThread();
                inv.setSelectedSlot(slot);
                return null;
            }
            default: return NONE;
        }
    }
}
