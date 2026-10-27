package io.github.kiyo29.origami.bridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.entity.PlayerBridge;
import io.github.kiyo29.origami.internal.Lang;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bukkit のコマンド (SimpleCommandMap) を Brigadier の dispatcher に載せる橋渡し。Mixin は使わない。
 *
 * - ラベルごとに literal(label) + greedyString("args") のノードを1回だけ登録する。実行・tab 補完・権限は
 *   毎回 commandMap を見て決めるため、ノードを消す必要がない (Brigadier には削除 API が無い)。
 *   コマンドが無くなったラベルは requires が false になり、Unknown command 扱いでクライアントにも見えない。
 * - バニラ (または他 Mod) と同名のラベルは上書きしない (名前空間つき plugin:label は使える)。
 * - Bukkit 既定コマンド (org.bukkit.command.defaults.*) は公開しない。
 * - dispatcher は /reload で作り直されるので、bind で差し替えて sync で載せ直す。
 */
public final class CommandBridge {
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile CommandDispatcher<CommandSourceStack> dispatcher;

    private CommandBridge() { }

    /** 使う dispatcher を設定する。別のものに変わったら登録済みの記録を捨てる。 */
    public static synchronized void bind(CommandDispatcher<CommandSourceStack> target) {
        if (dispatcher != target) {
            dispatcher = target;
            REGISTERED.clear();
        }
    }

    public static synchronized void unbind() {
        dispatcher = null;
        REGISTERED.clear();
        WARNED.clear();
    }

    /** commandMap にあるコマンドのうち、まだ載せていないラベルを dispatcher に追加する (追加のみ・冪等)。 */
    public static synchronized void sync(SimpleCommandMap map) {
        CommandDispatcher<CommandSourceStack> d = dispatcher;
        if (d == null) {
            return;
        }
        boolean added = false;
        for (Map.Entry<String, Command> e : new ArrayList<>(map.getKnownCommands().entrySet())) {
            String label = e.getKey();
            Command command = e.getValue();
            if (command.getClass().getName().startsWith("org.bukkit.command.defaults.")) {
                continue;
            }
            if (REGISTERED.contains(label)) {
                continue;
            }
            if (d.getRoot().getChild(label) != null) {
                if (WARNED.add(label)) {
                    String owner = command instanceof PluginIdentifiableCommand pic
                            ? pic.getPlugin().getName() : command.getClass().getSimpleName();
                    Origami.LOGGER.info(Lang.tr("log.command.conflict", label, owner));
                }
                continue;
            }
            d.register(buildNode(label, label));
            REGISTERED.add(label);
            added = true;
        }
        added |= syncAliases(d, map);
        if (added) {
            refreshClients();
        }
    }

    /** config の commandAliases (別名 -> 元のラベル) を載せる。既存のノードは触らず、新しい名前を足すだけ。 */
    private static boolean syncAliases(CommandDispatcher<CommandSourceStack> d, SimpleCommandMap map) {
        Map<String, String> aliases = Origami.config().commandAliases;
        if (aliases == null || aliases.isEmpty()) {
            return false;
        }
        boolean added = false;
        for (Map.Entry<String, String> e : new ArrayList<>(aliases.entrySet())) {
            String alias = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            String target = e.getValue() == null ? "" : e.getValue().trim().toLowerCase(java.util.Locale.ROOT);
            if (alias.isEmpty() || target.isEmpty() || alias.equals(target)
                    || alias.indexOf(' ') >= 0 || target.indexOf(' ') >= 0) {
                if (WARNED.add(alias + "|" + target)) {
                    Origami.LOGGER.warn(Lang.tr("log.command.alias_invalid", e.getKey(), e.getValue()));
                }
                continue;
            }
            if (REGISTERED.contains(alias)) {
                continue;
            }
            Command command = map.getKnownCommands().get(target);
            if (command == null || command.getClass().getName().startsWith("org.bukkit.command.defaults.")) {
                // 元のコマンドがまだ無い (プラグインが未有効)。次の sync で再試行する
                continue;
            }
            if (d.getRoot().getChild(alias) != null) {
                if (WARNED.add("alias:" + alias)) {
                    Origami.LOGGER.info(Lang.tr("log.command.alias_conflict", alias, target));
                }
                continue;
            }
            d.register(buildNode(alias, target));
            REGISTERED.add(alias);
            Origami.LOGGER.info(Lang.tr("log.command.alias_added", alias, target));
            added = true;
        }
        return added;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildNode(String name, String label) {
        return LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .requires(source -> usable(label, source))
                .executes(ctx -> run(ctx.getSource(), label, ""))
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> suggest(ctx.getSource(), label, builder))
                        .executes(ctx -> run(ctx.getSource(), label, StringArgumentType.getString(ctx, "args"))));
    }

    private static boolean usable(String label, CommandSourceStack source) {
        Command command = OrigamiServer.get().commandMap().getKnownCommands().get(label);
        if (command == null) {
            return false;
        }
        try {
            return command.testPermissionSilent(senderOf(source));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Bukkit コマンドの実行入口。Bukkit コマンドに限り PlayerCommandPreprocessEvent (プレイヤー) /
     * ServerCommandEvent (それ以外) を発火する。バニラのコマンドには発火しない (Mixin を使わないため)。
     */
    private static int run(CommandSourceStack source, String label, String args) {
        String line = args.isEmpty() ? label : label + " " + args;
        try {
            net.minecraft.server.level.ServerPlayer sp = source.getPlayer();
            if (sp != null) {
                org.bukkit.event.player.PlayerCommandPreprocessEvent event =
                        new org.bukkit.event.player.PlayerCommandPreprocessEvent(
                                io.github.kiyo29.origami.bridge.entity.PlayerBridge.of(sp), "/" + line);
                OrigamiServer.get().pluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return 1;
                }
                line = event.getMessage().startsWith("/") ? event.getMessage().substring(1) : event.getMessage();
            } else {
                org.bukkit.event.server.ServerCommandEvent event =
                        new org.bukkit.event.server.ServerCommandEvent(senderOf(source), line);
                OrigamiServer.get().pluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return 1;
                }
                line = event.getCommand();
            }
        } catch (Throwable t) {
            Origami.LOGGER.error("Error while firing command event for /" + line, t);
        }
        line = line.trim();
        if (line.isEmpty()) {
            return 1;
        }
        int space = line.indexOf(' ');
        String newLabel = space < 0 ? line : line.substring(0, space);
        String newArgs = space < 0 ? "" : line.substring(space + 1);
        return runDispatch(source, newLabel, newArgs);
    }

    private static int runDispatch(CommandSourceStack source, String label, String args) {
        CommandSender sender = senderOf(source);
        String line = args.isEmpty() ? label : label + " " + args;
        try {
            OrigamiServer.get().commandMap().dispatch(sender, line);
        } catch (CommandException e) {
            sender.sendMessage("\u00a7c" + Lang.tr("command.error"));
            Origami.LOGGER.error("Error while executing /" + line, e);
        } catch (Throwable t) {
            sender.sendMessage("\u00a7c" + Lang.tr("command.error"));
            Origami.LOGGER.error("Error while executing /" + line, t);
        }
        return 1;
    }

    private static CompletableFuture<Suggestions> suggest(CommandSourceStack source, String label, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        List<String> completions = null;
        try {
            completions = OrigamiServer.get().commandMap().tabComplete(senderOf(source), label + " " + remaining);
        } catch (Throwable t) {
            if (Origami.config().debug) {
                Origami.LOGGER.warn("Tab completion failed for /" + label, t);
            }
        }
        if (completions == null || completions.isEmpty()) {
            return builder.buildFuture();
        }
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + remaining.lastIndexOf(' ') + 1);
        for (String completion : completions) {
            offset.suggest(completion);
        }
        return offset.buildFuture();
    }

    /** Brigadier の source から Bukkit の CommandSender を作る。 */
    public static CommandSender senderOf(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return PlayerBridge.of(player);
        }
        // 本物のコンソールは source が MinecraftServer (textName "Server")。それ以外は rcon / コマンドブロック等
        if (source.getEntity() == null && "Server".equals(source.getTextName())) {
            return ConsoleSenderBridge.console();
        }
        return ConsoleSenderBridge.forSource(source);
    }

    /** Bukkit の CommandSender から Brigadier の source を作る (Server#dispatchCommand でバニラへ流すとき用)。 */
    public static CommandSourceStack stackOf(MinecraftServer server, CommandSender sender) {
        if (sender != null && Proxy.isProxyClass(sender.getClass())
                && Proxy.getInvocationHandler(sender) instanceof PlayerBridge bridge) {
            ServerPlayer player = bridge.serverPlayer();
            if (player != null) {
                return player.createCommandSourceStack();
            }
        }
        return server.createCommandSourceStack();
    }

    /** コマンドが増えたら、オンラインのプレイヤーにコマンドツリーを送り直す。 */
    private static void refreshClients() {
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (mc == null) {
            return;
        }
        PlayerList list = mc.getPlayerList();
        if (list == null || list.getPlayers().isEmpty()) {
            return;
        }
        mc.execute(() -> {
            for (ServerPlayer player : new ArrayList<>(list.getPlayers())) {
                mc.getCommands().sendCommands(player);
            }
        });
    }
}
