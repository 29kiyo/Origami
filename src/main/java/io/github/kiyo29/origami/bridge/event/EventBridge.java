package io.github.kiyo29.origami.bridge.event;

import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.command.ConsoleSenderBridge;
import io.github.kiyo29.origami.bridge.entity.PlayerBridge;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.github.kiyo29.origami.bridge.world.LevelBridge;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Fabric API のコールバックを Bukkit イベントに変換する (Mixin なし)。
 * Join / Quit: 参加・退出メッセージはバニラ側で先に送られるため、プラグインからは書き換えられない。
 * Chat: キャンセルのみ対応 (ALLOW_CHAT_MESSAGE)。メッセージ・書式・受信者の書き換えは未対応。
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
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(EventBridge::onAllowChat);
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
                fire(new PlayerChangedWorldEvent(PlayerBridge.of(player), LevelBridge.of(origin))));
        // Fired after the respawn, so the respawn location cannot be changed (partial). Bed / anchor / missing-spawn flags are always false. alive=true is END_PORTAL, otherwise DEATH.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                fire(new PlayerRespawnEvent(PlayerBridge.of(newPlayer),
                        LevelBridge.location(newPlayer.level(), newPlayer.getX(), newPlayer.getY(), newPlayer.getZ(),
                                newPlayer.getYRot(), newPlayer.getXRot()),
                        false, false, false,
                        alive ? PlayerRespawnEvent.RespawnReason.END_PORTAL : PlayerRespawnEvent.RespawnReason.DEATH)));
    }

    private static void onJoin(ServerPlayer serverPlayer) {
        Player player = PlayerBridge.of(serverPlayer);
        if (!LoginBridge.allow(serverPlayer, player)) {
            return;
        }
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

    /** false を返すとバニラ側でチャットが破棄される。失敗時は止めずに通す。 */
    private static boolean onAllowChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound bound) {
        try {
            Player player = PlayerBridge.of(sender);
            String text = message.signedContent();

            Set<Player> players = new LinkedHashSet<>();
            MinecraftServer mc = OrigamiServer.get().minecraft();
            PlayerList list = mc == null ? null : mc.getPlayerList();
            if (list != null) {
                for (ServerPlayer p : list.getPlayers()) {
                    players.add(PlayerBridge.of(p));
                }
            }
            Set<Audience> viewers = new LinkedHashSet<>(players);
            viewers.add((Audience) ConsoleSenderBridge.console());

            SignedMessage signed = new SimpleSignedMessage(message.sender(), message.timeStamp(), message.salt(), text);
            AsyncChatEvent paper = new AsyncChatEvent(false, player, viewers,
                    ChatRenderer.defaultRenderer(), Component.text(text), Component.text(text), signed);
            OrigamiServer.get().pluginManager().callEvent(paper);
            if (paper.isCancelled()) {
                return false;
            }

            AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(false, player, text, players);
            OrigamiServer.get().pluginManager().callEvent(legacy);
            return !legacy.isCancelled();
        } catch (Throwable t) {
            Origami.LOGGER.error("Failed to dispatch chat event", t);
            return true;
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

    /** 署名なしの簡易 SignedMessage (AsyncChatEvent に渡すための入れ物)。 */
    private record SimpleSignedMessage(UUID sender, Instant timestamp, long salt, String message) implements SignedMessage {
        @Override
        public Identity identity() {
            return Identity.identity(sender);
        }

        @Override
        public SignedMessage.Signature signature() {
            return null;
        }

        @Override
        public Component unsignedContent() {
            return null;
        }
    }
}
