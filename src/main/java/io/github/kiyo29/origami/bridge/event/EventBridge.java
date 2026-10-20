package io.github.kiyo29.origami.bridge.event;

import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.entity.PlayerBridge;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Fabric API のコールバックを Bukkit イベントに変換する (Mixin なし)。
 * Phase 5 時点: Join / Quit。参加・退出メッセージはバニラ側で先に送られるため、プラグインからは書き換えられない。
 */
public final class EventBridge {
    private static boolean registered;

    private EventBridge() { }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerPlayerEvents.JOIN.register(EventBridge::onJoin);
        ServerPlayerEvents.LEAVE.register(EventBridge::onLeave);
    }

    private static void onJoin(ServerPlayer serverPlayer) {
        Player player = PlayerBridge.of(serverPlayer);
        Component message = Component.translatable("multiplayer.player.joined",
                Component.text(serverPlayer.getName().getString())).color(NamedTextColor.YELLOW);
        fire(new PlayerJoinEvent(player, message));
    }

    private static void onLeave(ServerPlayer serverPlayer) {
        Player player = PlayerBridge.of(serverPlayer);
        Component message = Component.translatable("multiplayer.player.left",
                Component.text(serverPlayer.getName().getString())).color(NamedTextColor.YELLOW);
        try {
            fire(new PlayerQuitEvent(player, message, PlayerQuitEvent.QuitReason.DISCONNECTED));
        } finally {
            PlayerBridge.release(serverPlayer);
        }
    }

    /** プラグイン側の例外でサーバー本体を止めない。callEvent 自体も個別のリスナー例外は捕捉する。 */
    public static void fire(Event event) {
        try {
            OrigamiServer.get().pluginManager().callEvent(event);
        } catch (Throwable t) {
            Origami.LOGGER.error("Failed to dispatch " + event.getEventName(), t);
        }
    }
}
