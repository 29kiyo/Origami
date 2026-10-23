package io.github.kiyo29.origami.bridge;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Server#getItemFactory. Partial: only meta creation / comparison and display name. */
final class ItemFactoryBridge implements InvocationHandler {
    private static volatile ItemFactory instance;

    private ItemFactoryBridge() { }

    static ItemFactory get() {
        ItemFactory f = instance;
        if (f == null) {
            synchronized (ItemFactoryBridge.class) {
                f = instance;
                if (f == null) {
                    f = (ItemFactory) Proxy.newProxyInstance(ItemFactory.class.getClassLoader(),
                            new Class<?>[] {ItemFactory.class}, new ItemFactoryBridge());
                    instance = f;
                }
            }
        }
        return f;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "OrigamiItemFactory";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        Object[] a = args == null ? new Object[0] : args;
        switch (method.getName()) {
            case "getItemMeta" -> {
                return a[0] == Material.AIR ? null : ItemMetaBridge.create();
            }
            case "isApplicable" -> {
                return a[0] == null || ItemMetaBridge.isMeta(a[0]);
            }
            case "equals" -> {
                if (a.length == 2) {
                    if (a[0] == null || a[1] == null) {
                        return a[0] == a[1];
                    }
                    return ItemMetaBridge.same(a[0], a[1]);
                }
            }
            case "asMetaFor", "ensureServerConversions" -> {
                return a[0];
            }
            case "getDefaultLeatherColor" -> {
                return Color.fromRGB(0xA06540);
            }
            case "displayName" -> {
                ItemStack stack = (ItemStack) a[0];
                if (stack.hasItemMeta()) {
                    ItemMeta meta = stack.getItemMeta();
                    if (meta != null && meta.customName() != null) {
                        return meta.customName();
                    }
                }
                return Component.translatable(stack.getType().asItemType().getTranslationKey());
            }
            default -> { }
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw UnimplementedCalls.exception(method);
    }
}
