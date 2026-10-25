package io.github.kiyo29.origami.bridge;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Potion effects backed by the static MC registry BuiltInRegistries.MOB_EFFECT.
 * PotionEffectType is an abstract class, so Impl extends it and stores only the Identifier.
 * Also routes the LivingEntity effect methods of a player (handle) to MC's MobEffectInstance API.
 * Approximations: isInstant is decided by name; getDurationModifier / getEffectAttributes /
 * getAttributeModifierAmount are not implemented (UnimplementedCalls).
 */
public final class EffectBridge {
    /** Returned by handle() when the method is not an effect method. */
    public static final Object NONE = new Object();

    /** Legacy numeric id = index + 1 (taken from paper-api PotionEffectType.&lt;clinit&gt;). */
    private static final String[] LEGACY_IDS = {
        "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage",
        "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing",
        "invisibility", "blindness", "night_vision", "hunger", "weakness", "poison", "wither",
        "health_boost", "absorption", "saturation", "glowing", "levitation", "luck", "unluck",
        "slow_falling", "conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village", "darkness",
        "trial_omen", "raid_omen", "wind_charged", "weaving", "oozing", "infested", "breath_of_the_nautilus"
    };
    /** modern path -> legacy Bukkit name (only where it differs from the path upper-cased). */
    private static final Map<String, String> LEGACY_NAMES = Map.ofEntries(
            Map.entry("slowness", "SLOW"), Map.entry("haste", "FAST_DIGGING"),
            Map.entry("mining_fatigue", "SLOW_DIGGING"), Map.entry("strength", "INCREASE_DAMAGE"),
            Map.entry("instant_health", "HEAL"), Map.entry("instant_damage", "HARM"),
            Map.entry("jump_boost", "JUMP"), Map.entry("nausea", "CONFUSION"),
            Map.entry("resistance", "DAMAGE_RESISTANCE"));
    private static final Map<String, String> LEGACY_TO_MODERN = new HashMap<>();
    private static final Set<String> INSTANT = Set.of("instant_health", "instant_damage", "saturation");

    static {
        LEGACY_NAMES.forEach((modern, legacy) -> LEGACY_TO_MODERN.put(legacy.toLowerCase(Locale.ROOT), modern));
    }

    private static final Map<Identifier, Impl> CACHE = new ConcurrentHashMap<>();
    private static volatile Registry<?> registry;
    private static volatile Map<MobEffect, Identifier> reverse;

    private EffectBridge() { }

    static Registry<?> registry() {
        Registry<?> r = registry;
        if (r == null) {
            r = (Registry<?>) Proxy.newProxyInstance(Registry.class.getClassLoader(),
                    new Class<?>[] {Registry.class}, new RegistryHandler());
            registry = r;
        }
        return r;
    }

    static PotionEffectType of(Identifier id) {
        return CACHE.computeIfAbsent(id, Impl::new);
    }

    /** null when the effect does not exist. Accepts legacy names (fast_digging) in the minecraft namespace. */
    static PotionEffectType lookup(String namespace, String path) {
        String p = path;
        if ("minecraft".equals(namespace)) {
            p = LEGACY_TO_MODERN.getOrDefault(path, path);
        }
        Identifier id = Identifier.tryParse(namespace + ":" + p);
        if (id == null || !BuiltInRegistries.MOB_EFFECT.containsKey(id)) {
            return null;
        }
        return of(id);
    }

    static Holder<MobEffect> holderOf(PotionEffectType t) {
        if (t instanceof Impl i) {
            return i.holder();
        }
        throw new IllegalArgumentException("Unsupported PotionEffectType: " + t);
    }

    private static Map<MobEffect, Identifier> reverse() {
        Map<MobEffect, Identifier> m = reverse;
        if (m == null) {
            Map<MobEffect, Identifier> tmp = new IdentityHashMap<>();
            var reg = BuiltInRegistries.MOB_EFFECT;
            for (Identifier id : reg.keySet()) {
                reg.get(ResourceKey.create(Registries.MOB_EFFECT, id)).ifPresent(h -> tmp.put(h.value(), id));
            }
            reverse = tmp;
            m = tmp;
        }
        return m;
    }

    static PotionEffectType typeOf(Holder<MobEffect> holder) {
        Identifier id = reverse().get(holder.value());
        return id == null ? null : of(id);
    }

    static MobEffectInstance toMc(PotionEffect e) {
        return new MobEffectInstance(holderOf(e.getType()), e.getDuration(), e.getAmplifier(),
                e.isAmbient(), e.hasParticles(), e.hasIcon());
    }

    static PotionEffect fromMc(MobEffectInstance i) {
        if (i == null) {
            return null;
        }
        PotionEffectType t = typeOf(i.getEffect());
        if (t == null) {
            return null;
        }
        return new PotionEffect(t, i.getDuration(), i.getAmplifier(), i.isAmbient(), i.isVisible(), i.showIcon());
    }

    private static boolean add(ServerPlayer p, PotionEffect e) {
        Holder<MobEffect> h = holderOf(e.getType());
        // Bukkit semantics: the new effect replaces an existing one of the same type
        p.removeEffect(h);
        return p.addEffect(toMc(e));
    }

    /** Effect methods of LivingEntity for a player. Returns NONE when the method is not one of them. */
    public static Object handle(ServerPlayer p, Method method, Object[] args) {
        if (p == null) {
            return NONE;
        }
        int n = args == null ? 0 : args.length;
        String name = method.getName();
        if (name.equals("getActivePotionEffects") && n == 0) {
            List<PotionEffect> out = new ArrayList<>();
            for (MobEffectInstance i : p.getActiveEffects()) {
                PotionEffect e = fromMc(i);
                if (e != null) {
                    out.add(e);
                }
            }
            return out;
        }
        if (name.equals("addPotionEffect") && n >= 1 && args[0] instanceof PotionEffect e) {
            return add(p, e);
        }
        if (name.equals("addPotionEffects") && n == 1 && args[0] instanceof Collection<?> c) {
            boolean all = true;
            for (Object o : c) {
                if (o instanceof PotionEffect e) {
                    all &= add(p, e);
                }
            }
            return all;
        }
        if (n == 1 && args[0] instanceof PotionEffectType t) {
            switch (name) {
                case "hasPotionEffect":
                    return p.hasEffect(holderOf(t));
                case "getPotionEffect":
                    return fromMc(p.getEffect(holderOf(t)));
                case "removePotionEffect": {
                    Holder<MobEffect> h = holderOf(t);
                    MobEffectInstance prev = p.getEffect(h);
                    p.removeEffect(h);
                    // old API returns void, newer paper-api returns the removed effect
                    return method.getReturnType() == void.class ? null : fromMc(prev);
                }
                default:
                    break;
            }
        }
        return NONE;
    }

    static final class Impl extends PotionEffectType {
        private final Identifier id;

        Impl(Identifier id) {
            this.id = id;
        }

        Holder<MobEffect> holder() {
            return BuiltInRegistries.MOB_EFFECT.get(ResourceKey.create(Registries.MOB_EFFECT, id))
                    .orElseThrow(() -> new IllegalStateException("Unknown mob effect: " + id));
        }

        private MobEffect value() {
            return holder().value();
        }

        public NamespacedKey getKey() {
            return new NamespacedKey(id.getNamespace(), id.getPath());
        }

        @Override
        public String getName() {
            String legacy = LEGACY_NAMES.get(id.getPath());
            return legacy != null ? legacy : id.getPath().toUpperCase(Locale.ROOT);
        }

        @Override
        public int getId() {
            if (!"minecraft".equals(id.getNamespace())) {
                return -1;
            }
            for (int i = 0; i < LEGACY_IDS.length; i++) {
                if (LEGACY_IDS[i].equals(id.getPath())) {
                    return i + 1;
                }
            }
            return -1;
        }

        @Override
        public PotionEffect createEffect(int duration, int amplifier) {
            return new PotionEffect(this, duration, amplifier);
        }

        @Override
        public boolean isInstant() {
            return INSTANT.contains(id.getPath());
        }

        @Override
        public PotionEffectTypeCategory getCategory() {
            return PotionEffectTypeCategory.valueOf(value().getCategory().name());
        }

        @Override
        public PotionEffectType.Category getEffectCategory() {
            return PotionEffectType.Category.valueOf(value().getCategory().name());
        }

        @Override
        public Color getColor() {
            return Color.fromRGB(value().getColor() & 0xFFFFFF);
        }

        @Override
        public double getDurationModifier() {
            throw UnimplementedCalls.exception("PotionEffectType#getDurationModifier()");
        }

        @Override
        public Map<Attribute, AttributeModifier> getEffectAttributes() {
            throw UnimplementedCalls.exception("PotionEffectType#getEffectAttributes()");
        }

        @Override
        public double getAttributeModifierAmount(Attribute attribute, int effectAmplifier) {
            throw UnimplementedCalls.exception("PotionEffectType#getAttributeModifierAmount(Attribute, int)");
        }

        @Override
        public String translationKey() {
            return value().getDescriptionId();
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
            return "PotionEffectType[" + id + "]";
        }
    }

    private static List<Identifier> names() {
        return BuiltInRegistries.MOB_EFFECT.keySet().stream()
                .sorted(Comparator.comparing(Identifier::toString)).toList();
    }

    private static final class RegistryHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "OrigamiRegistry{MOB_EFFECT}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            Object[] a = args == null ? new Object[0] : args;
            switch (method.getName()) {
                case "get" -> {
                    if (!method.isDefault() && a[0] instanceof NamespacedKey k) {
                        return lookup(k.getNamespace(), k.getKey());
                    }
                }
                case "getKey" -> {
                    if (!method.isDefault()) {
                        return a[0] == null ? null : ((PotionEffectType) a[0]).getKey();
                    }
                }
                case "keyStream" -> {
                    return names().stream().map(i -> new NamespacedKey(i.getNamespace(), i.getPath()));
                }
                case "stream" -> {
                    return names().stream().map(EffectBridge::of);
                }
                case "iterator" -> {
                    return names().stream().map(EffectBridge::of).iterator();
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
