package io.github.kiyo29.origami.bridge;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PotionMeta state <-> MC DataComponents.POTION_CONTENTS (potion / tipped arrow items only).
 * PotionType and MC's Potion are matched by key. Partial: setMainEffect and the deprecated PotionData
 * methods are not implemented (UnimplementedCalls); potion data is not part of ItemMeta#serialize.
 */
final class PotionBridge {
    private static final Set<String> ITEMS = Set.of("potion", "splash_potion", "lingering_potion", "tipped_arrow");

    private PotionBridge() { }

    static boolean isPotionMaterial(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION
                || m == Material.LINGERING_POTION || m == Material.TIPPED_ARROW;
    }

    private static boolean isPotionItem(net.minecraft.world.item.ItemStack h) {
        return ITEMS.contains(BuiltInRegistries.ITEM.getKey(h.getItem()).getPath());
    }

    static boolean nonEmpty(ItemMetaBridge.State s) {
        return s.potionType != null || s.potionColor != null || !s.customEffects.isEmpty() || s.customPotionName != null;
    }

    static PotionType typeOf(Holder<Potion> holder) {
        Optional<ResourceKey<Potion>> key = holder.unwrapKey();
        if (key.isEmpty() || !"minecraft".equals(key.get().identifier().getNamespace())) {
            return null;
        }
        String path = key.get().identifier().getPath();
        for (PotionType t : PotionType.values()) {
            if (t.getKey().getKey().equals(path)) {
                return t;
            }
        }
        return null;
    }

    static Optional<Holder<Potion>> holderOf(PotionType t) {
        Identifier id = Identifier.tryParse("minecraft:" + t.getKey().getKey());
        if (id == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.POTION.get(ResourceKey.create(Registries.POTION, id)).map(x -> (Holder<Potion>) x);
    }

    /** Reads the potion fields into the state (only for potion items). */
    static void read(net.minecraft.world.item.ItemStack h, ItemMetaBridge.State s) {
        if (!isPotionItem(h)) {
            return;
        }
        s.potion = true;
        PotionContents pc = h.get(DataComponents.POTION_CONTENTS);
        if (pc == null) {
            return;
        }
        s.potionType = pc.potion().map(PotionBridge::typeOf).orElse(null);
        s.potionColor = pc.customColor().map(i -> Color.fromRGB(i & 0xFFFFFF)).orElse(null);
        for (MobEffectInstance i : pc.customEffects()) {
            PotionEffect e = EffectBridge.fromMc(i);
            if (e != null) {
                s.customEffects.add(e);
            }
        }
        s.customPotionName = pc.customName().orElse(null);
    }

    private static PotionContents contents(ItemMetaBridge.State s) {
        Optional<Holder<Potion>> base = s.potionType == null ? Optional.empty() : holderOf(s.potionType);
        Optional<Integer> color = Optional.ofNullable(s.potionColor).map(c -> c.asRGB());
        List<MobEffectInstance> effects = new ArrayList<>();
        for (PotionEffect e : s.customEffects) {
            effects.add(EffectBridge.toMc(e));
        }
        return new PotionContents(base, color, effects, Optional.ofNullable(s.customPotionName));
    }

    /** Writes the state back (only when the meta is a potion meta and the item is a potion item). */
    static void write(net.minecraft.world.item.ItemStack h, ItemMetaBridge.State s) {
        if (!s.potion || !isPotionItem(h)) {
            return;
        }
        h.set(DataComponents.POTION_CONTENTS, contents(s));
    }

    private static List<PotionEffect> allEffects(ItemMetaBridge.State s) {
        List<PotionEffect> out = new ArrayList<>();
        if (s.potionType != null) {
            holderOf(s.potionType).ifPresent(h -> {
                for (MobEffectInstance i : h.value().getEffects()) {
                    PotionEffect e = EffectBridge.fromMc(i);
                    if (e != null) {
                        out.add(e);
                    }
                }
            });
        }
        out.addAll(s.customEffects);
        return out;
    }

    /** The PotionMeta methods that are implemented. a = arguments. */
    static Object handle(ItemMetaBridge.State s, String name, Object[] a) {
        switch (name) {
            case "hasCustomEffects":
                return !s.customEffects.isEmpty();
            case "getCustomEffects":
                return new ArrayList<>(s.customEffects);
            case "getAllEffects":
                return allEffects(s);
            case "addCustomEffect": {
                PotionEffect e = (PotionEffect) a[0];
                boolean overwrite = (Boolean) a[1];
                for (int i = 0; i < s.customEffects.size(); i++) {
                    PotionEffect have = s.customEffects.get(i);
                    if (have.getType().equals(e.getType())) {
                        if (!overwrite || have.equals(e)) {
                            return false;
                        }
                        s.customEffects.set(i, e);
                        return true;
                    }
                }
                s.customEffects.add(e);
                return true;
            }
            case "removeCustomEffect":
                return s.customEffects.removeIf(e -> e.getType().equals(a[0]));
            case "hasCustomEffect":
                return s.customEffects.stream().anyMatch(e -> e.getType().equals(a[0]));
            case "clearCustomEffects": {
                boolean had = !s.customEffects.isEmpty();
                s.customEffects.clear();
                return had;
            }
            case "hasColor":
                return s.potionColor != null;
            case "getColor":
                return s.potionColor;
            case "setColor":
                s.potionColor = (Color) a[0];
                return null;
            case "computeEffectiveColor":
                return s.potionColor != null ? s.potionColor : Color.fromRGB(contents(s).getColor() & 0xFFFFFF);
            case "hasBasePotionType":
                return s.potionType != null;
            case "getBasePotionType":
                return s.potionType;
            case "setBasePotionType":
                s.potionType = (PotionType) a[0];
                return null;
            case "hasCustomPotionName":
                return s.customPotionName != null;
            case "getCustomPotionName":
                return s.customPotionName;
            case "setCustomPotionName":
                s.customPotionName = (String) a[0];
                return null;
            default:
                throw new IllegalStateException("Not a potion method: " + name);
        }
    }
}
