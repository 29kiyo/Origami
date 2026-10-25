package io.github.kiyo29.origami.bridge;

import io.github.kiyo29.origami.bridge.entity.PlayerBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal OfflinePlayer (Proxy based) for Server#getOfflinePlayer.
 * Only identity and online state are known. For players that are not online,
 * isOp() and hasPlayedBefore() answer false (approximation, see COMPATIBILITY.md).
 * A lookup by name alone uses the offline-mode UUID rule: UUID.nameUUIDFromBytes("OfflinePlayer:" + name).
 */
final class OfflinePlayerBridge {
    private OfflinePlayerBridge() { }

    static OfflinePlayer get(Object key) {
        if (key instanceof UUID id) {
            return create(id, null);
        }
        String name = (String) key;
        MinecraftServer mc = OrigamiServer.get().minecraft();
        PlayerList list = mc == null ? null : mc.getPlayerList();
        if (list != null) {
            for (ServerPlayer p : list.getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(name)) {
                    return create(p.getUUID(), p.getName().getString());
                }
            }
        }
        return create(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
    }

    private static OfflinePlayer create(UUID id, String knownName) {
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[] {OfflinePlayer.class}, new Handler(id, knownName));
    }

    private static ServerPlayer online(UUID id) {
        MinecraftServer mc = OrigamiServer.get().minecraft();
        PlayerList list = mc == null ? null : mc.getPlayerList();
        return list == null ? null : list.getPlayer(id);
    }

    private record Handler(UUID uuid, String knownName) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> args[0] instanceof OfflinePlayer o && uuid.equals(o.getUniqueId());
                    case "hashCode" -> uuid.hashCode();
                    case "toString" -> "OrigamiOfflinePlayer{" + uuid + "}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if (method.getParameterCount() == 0) {
                ServerPlayer p = online(uuid);
                switch (method.getName()) {
                    case "getUniqueId" -> {
                        return uuid;
                    }
                    case "getName" -> {
                        return p != null ? p.getName().getString() : knownName;
                    }
                    case "isOnline" -> {
                        return p != null;
                    }
                    case "hasPlayedBefore" -> {
                        return PlayerHistory.first(uuid) != 0L;
                    }
                    case "getFirstPlayed" -> {
                        return PlayerHistory.first(uuid);
                    }
                    case "getLastPlayed" -> {
                        return p != null ? System.currentTimeMillis() : PlayerHistory.last(uuid);
                    }
                    case "getPlayer" -> {
                        return p == null ? null : PlayerBridge.of(p);
                    }
                    case "isOp" -> {
                        return p != null && PlayerBridge.of(p).isOp();
                    }
                    default -> { }
                }
            }
            if (method.getName().equals("getStatistic") && method.getParameterCount() == 1) {
                ServerPlayer onlinePlayer = online(uuid);
                return onlinePlayer == null ? 0 : PlayerBridge.of(onlinePlayer).getStatistic((org.bukkit.Statistic) args[0]);
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw UnimplementedCalls.exception(method);
        }
    }
}
