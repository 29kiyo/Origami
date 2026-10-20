package io.github.kiyo29.origami.bridge.entity;

import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.command.SenderBase;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * org.bukkit.entity.Player の Proxy 実装 (Phase 4 では CommandSender として必要な最小部分のみ)。
 * UUID キャッシュ + WeakReference。リスポーンで ServerPlayer が作り直されても同じ Player オブジェクトを返す。
 * Phase 6 で Player の主要メソッド (位置・テレポート等) をここへ足す。
 */
public final class PlayerBridge extends SenderBase {
    private static final Map<UUID, PlayerBridge> CACHE = new ConcurrentHashMap<>();

    private final UUID uuid;
    private volatile WeakReference<ServerPlayer> ref;
    private volatile String name;
    private final Player proxy;

    private PlayerBridge(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.ref = new WeakReference<>(player);
        this.name = player.getName().getString();
        this.proxy = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, this);
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
        if (method.getParameterCount() == 0) {
            switch (methodName) {
                case "getUniqueId":
                    return uuid;
                case "getDisplayName": {
                    ServerPlayer p = ref.get();
                    return p == null ? name : p.getDisplayName().getString();
                }
                case "isOnline":
                    return isOnline();
                default:
                    break;
            }
        }
        return UNHANDLED;
    }
}
