package io.github.kiyo29.origami.bridge.entity;

import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.command.SenderBase;
import io.github.kiyo29.origami.bridge.world.LevelBridge;
import io.github.kiyo29.origami.compat.McCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * org.bukkit.entity.Player の Proxy 実装。
 * UUID キャッシュ + WeakReference。リスポーンで ServerPlayer が作り直されても同じ Player オブジェクトを返す。
 * Phase 6: 位置・ワールド・体力・ゲームモード・飛行・テレポートを追加 (インベントリ等は Phase 8)。
 * teleport は PlayerTeleportEvent を発火しない (未対応)。
 */
public final class PlayerBridge extends SenderBase {
    private static final Map<UUID, PlayerBridge> CACHE = new ConcurrentHashMap<>();

    private final UUID uuid;
    private volatile WeakReference<ServerPlayer> ref;
    private volatile String name;
    private volatile String displayName;
    private final Player proxy;

    private PlayerBridge(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.ref = new WeakReference<>(player);
        this.name = player.getName().getString();
        this.proxy = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, this);
    }

    /** 退出時にキャッシュから外す。別の ServerPlayer に差し替わっていたら外さない。 */
    public static void release(ServerPlayer player) {
        CACHE.computeIfPresent(player.getUUID(), (id, b) -> {
            ServerPlayer current = b.ref.get();
            return current == null || current == player ? null : b;
        });
    }

    public static Player of(ServerPlayer player) {
        CACHE.values().removeIf(b -> b.ref.get() == null);
        PlayerBridge bridge = CACHE.computeIfAbsent(player.getUUID(), id -> new PlayerBridge(player));
        if (bridge.ref.get() != player) {
            bridge.ref = new WeakReference<>(player);
        }
        bridge.name = player.getName().getString();
        return bridge.proxy;
    }

    /** まだオンラインでなければ null。 */
    public @Nullable ServerPlayer serverPlayer() {
        return ref.get();
    }

    private boolean isOnline() {
        ServerPlayer p = ref.get();
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (p == null || mc == null) {
            return false;
        }
        PlayerList list = mc.getPlayerList();
        return list != null && list.getPlayer(uuid) == p;
    }

    private ServerPlayer need() {
        ServerPlayer p = ref.get();
        if (p == null) {
            throw new IllegalStateException("Player is not online: " + name);
        }
        return p;
    }

    @Override
    protected String senderName() {
        return name;
    }

    @Override
    protected boolean senderIsOp() {
        ServerPlayer p = ref.get();
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (p == null || mc == null || mc.getPlayerList() == null) {
            return false;
        }
        return mc.getPlayerList().isOp(p.nameAndId());
    }

    @Override
    protected void senderSetOp(boolean value) {
        onServerThread(() -> {
            ServerPlayer p = ref.get();
            MinecraftServer mc = OrigamiServer.get().minecraft();
            if (p == null || mc == null || mc.getPlayerList() == null) {
                return;
            }
            if (value) {
                mc.getPlayerList().op(p.nameAndId());
            } else {
                mc.getPlayerList().deop(p.nameAndId());
            }
        });
    }

    @Override
    protected void deliver(String message) {
        onServerThread(() -> {
            ServerPlayer p = ref.get();
            if (p != null) {
                p.sendSystemMessage(Component.literal(message));
            }
        });
    }

    private Location location() {
        ServerPlayer p = need();
        return LevelBridge.location(p.level(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
    }

    /** サーバースレッドならその場で、そうでなければ投げて true を返す (結果は待たない)。 */
    private boolean teleport(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            throw new IllegalArgumentException("location or its world is null");
        }
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("non-finite coordinates");
        }
        ServerLevel level = LevelBridge.levelOf(loc.getWorld());
        float yaw = loc.getYaw();
        float pitch = loc.getPitch();
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (mc == null) {
            return false;
        }
        if (mc.isSameThread()) {
            return doTeleport(level, x, y, z, yaw, pitch);
        }
        mc.execute(() -> doTeleport(level, x, y, z, yaw, pitch));
        return true;
    }

    private boolean doTeleport(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        ServerPlayer p = ref.get();
        return p != null && p.teleportTo(level, x, y, z, Set.of(), yaw, pitch, true);
    }

    @Override
    protected Object extra(Object proxyObject, Method method, Object[] args) {
        String methodName = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            return switch (methodName) {
                case "equals" -> args[0] instanceof Player other
                        && Proxy.isProxyClass(other.getClass())
                        && Proxy.getInvocationHandler(other) instanceof PlayerBridge b
                        && b.uuid.equals(uuid);
                case "hashCode" -> uuid.hashCode();
                case "toString" -> "PlayerBridge{" + name + "}";
                default -> UNHANDLED;
            };
        }
        Object inventoryResult = io.github.kiyo29.origami.bridge.entity.PlayerInventoryBridge.handle(ref.get(), method, args);
        if (inventoryResult != io.github.kiyo29.origami.bridge.entity.PlayerInventoryBridge.NONE) {
            return inventoryResult;
        }
        Class<?>[] types = method.getParameterTypes();
        int n = types.length;
        if (methodName.equals("teleport") && n >= 1) {
            if (args[0] instanceof Location loc) {
                return teleport(loc);
            }
            if (args[0] instanceof org.bukkit.entity.Entity target) {
                return teleport(target.getLocation());
            }
            return UNHANDLED;
        }
        if (n == 0) {
            switch (methodName) {
                case "getUniqueId":
                    return uuid;
                case "getDisplayName": {
                    ServerPlayer p = ref.get();
                    return displayName != null ? displayName : p == null ? name : p.getDisplayName().getString();
                }
                case "getAddress": {
                    ServerPlayer p = ref.get();
                    return p != null && p.connection != null
                            && p.connection.getRemoteAddress() instanceof java.net.InetSocketAddress a ? a : null;
                }
                case "getHAProxyAddress":
                    return null;
                case "updateInventory": {
                    ServerPlayer p = need();
                    onServerThread(() -> p.containerMenu.sendAllDataToRemote());
                    return null;
                }
                case "isEmpty":
                    return need().getPassengers().isEmpty();
                case "getFireTicks":
                    return need().getRemainingFireTicks();
                case "getExhaustion":
                    return FoodAccess.get(need().getFoodData());
                case "getPotentialRespawnLocation":
                    return null;
                case "getPlayerListName":
                    return name;
                case "isOnline":
                case "isValid":
                    return isOnline();
                case "isDead": {
                    ServerPlayer p = ref.get();
                    return p == null || !p.isAlive();
                }
                case "getType":
                    return EntityType.PLAYER;
                case "getEntityId":
                    return need().getId();
                case "getLocation":
                    return location();
                case "getWorld":
                    return LevelBridge.of(need().level());
                case "getHealth":
                    return (double) need().getHealth();
                case "getMaxHealth":
                    return (double) need().getMaxHealth();
                case "getGameMode":
                    return GameMode.valueOf(need().gameMode().getSerializedName().toUpperCase(Locale.ROOT));
                case "getFoodLevel":
                    return need().getFoodData().getFoodLevel();
                case "getSaturation":
                    return need().getFoodData().getSaturationLevel();
                case "getAllowFlight":
                    return McCompat.get().mayFly(need());
                case "isFlying":
                    return McCompat.get().isFlying(need());
                default:
                    break;
            }
            return UNHANDLED;
        }
        if (n == 1) {
            switch (methodName) {
                case "getLocation": {
                    if (types[0] != Location.class) {
                        return UNHANDLED;
                    }
                    Location loc = (Location) args[0];
                    if (loc != null) {
                        Location here = location();
                        loc.setWorld(here.getWorld());
                        loc.setX(here.getX());
                        loc.setY(here.getY());
                        loc.setZ(here.getZ());
                        loc.setYaw(here.getYaw());
                        loc.setPitch(here.getPitch());
                    }
                    return loc;
                }
                case "getRespawnLocation":
                    return null;
                case "setFireTicks": {
                    ServerPlayer p = need();
                    int ticks = (Integer) args[0];
                    onServerThread(() -> p.setRemainingFireTicks(ticks));
                    return null;
                }
                case "setExhaustion": {
                    ServerPlayer p = need();
                    float value = (Float) args[0];
                    onServerThread(() -> FoodAccess.set(p.getFoodData(), value));
                    return null;
                }
                case "setDisplayName":
                    displayName = (String) args[0];
                    return null;
                case "setHealth": {
                    double health = (Double) args[0];
                    ServerPlayer p = need();
                    if (health < 0 || health > p.getMaxHealth() || Double.isNaN(health)) {
                        throw new IllegalArgumentException("Health must be between 0 and " + p.getMaxHealth() + ", but was " + health);
                    }
                    onServerThread(() -> p.setHealth((float) health));
                    return null;
                }
                case "setGameMode": {
                    GameMode mode = (GameMode) args[0];
                    if (mode == null) {
                        throw new IllegalArgumentException("mode cannot be null");
                    }
                    GameType type = GameType.byName(mode.name().toLowerCase(Locale.ROOT));
                    ServerPlayer p = need();
                    onServerThread(() -> p.setGameMode(type));
                    return null;
                }
                case "setFoodLevel": {
                    int food = Math.max(0, Math.min(20, (Integer) args[0]));
                    ServerPlayer p = need();
                    onServerThread(() -> p.getFoodData().setFoodLevel(food));
                    return null;
                }
                case "setSaturation": {
                    float value = (Float) args[0];
                    ServerPlayer p = need();
                    onServerThread(() -> p.getFoodData().setSaturation(value));
                    return null;
                }
                case "setAllowFlight": {
                    boolean allow = (Boolean) args[0];
                    ServerPlayer p = need();
                    onServerThread(() -> {
                        McCompat compat = McCompat.get();
                        if (!allow && compat.isFlying(p)) {
                            compat.setFlying(p, false);
                        }
                        compat.setMayFly(p, allow);
                    });
                    return null;
                }
                case "setFlying": {
                    boolean flying = (Boolean) args[0];
                    ServerPlayer p = need();
                    if (flying && !McCompat.get().mayFly(p)) {
                        throw new IllegalArgumentException("Cannot make player fly if getAllowFlight() is false");
                    }
                    onServerThread(() -> McCompat.get().setFlying(p, flying));
                    return null;
                }
                default:
                    break;
            }
        }
        return UNHANDLED;
    }
}
