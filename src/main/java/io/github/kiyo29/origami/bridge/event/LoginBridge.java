package io.github.kiyo29.origami.bridge.event;

import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.bridge.OrigamiServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

/**
 * Approximates AsyncPlayerPreLoginEvent / PlayerLoginEvent (no Mixin).
 * Fabric's login-phase callbacks cannot read the profile (private fields), so both events are fired
 * from ServerPlayerEvents.JOIN, right before PlayerJoinEvent. The pre-login event runs on its own thread
 * and the server thread waits for it (permission plugins load user data there).
 * A disallow disconnects the player; vanilla's join message has already been broadcast by then.
 */
final class LoginBridge {
    private static final long WAIT_SECONDS = 15;
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Origami Login Thread");
        t.setDaemon(true);
        return t;
    });

    private LoginBridge() { }

    /** @return false if a plugin disallowed the login (the player has been disconnected). */
    /** Never blocks a join because of an unexpected failure inside the event plumbing. */
    static boolean allow(ServerPlayer sp, Player player) {
        try {
            return allow0(sp, player);
        } catch (Throwable t) {
            Origami.LOGGER.error("Login events failed; letting the player in", t);
            return true;
        }
    }

    private static boolean allow0(ServerPlayer sp, Player player) {
        InetAddress address = address(sp);
        UUID id = sp.getUUID();
        String name = sp.getName().getString();

        AsyncPlayerPreLoginEvent pre = new AsyncPlayerPreLoginEvent(name, address, id);
        Future<?> f = POOL.submit(() -> OrigamiServer.get().pluginManager().callEvent(pre));
        try {
            f.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Throwable t) {
            Origami.LOGGER.error("AsyncPlayerPreLoginEvent failed or timed out for " + name, t);
            return true;
        }
        if (pre.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            kick(sp, LegacyComponentSerializer.legacySection().serialize(pre.kickMessage()));
            return false;
        }

        PlayerLoginEvent login = new PlayerLoginEvent(player, "", address);
        EventBridge.fire(login);
        if (login.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            kick(sp, LegacyComponentSerializer.legacySection().serialize(login.kickMessage()));
            return false;
        }
        return true;
    }

    private static InetAddress address(ServerPlayer sp) {
        try {
            SocketAddress a = sp.connection.getRemoteAddress();
            if (a instanceof InetSocketAddress isa && isa.getAddress() != null) {
                return isa.getAddress();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return InetAddress.getLoopbackAddress();
    }

    private static void kick(ServerPlayer sp, String reason) {
        sp.connection.disconnect(net.minecraft.network.chat.Component.literal(reason));
    }
}
