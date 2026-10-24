package io.github.kiyo29.origami.bridge;

import io.github.kiyo29.origami.bridge.command.CommandBridge;
import io.github.kiyo29.origami.bridge.command.ConsoleSenderBridge;
import io.github.kiyo29.origami.bridge.command.OrigamiCommandMap;
import io.github.kiyo29.origami.bridge.entity.PlayerBridge;
import io.github.kiyo29.origami.bridge.world.LevelBridge;
import io.github.kiyo29.origami.internal.JulToSlf4jHandler;
import io.github.kiyo29.origami.internal.Versions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import io.github.kiyo29.origami.bridge.command.OrigamiHelpMap;
import org.bukkit.Server;
import org.bukkit.Warning;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import io.github.kiyo29.origami.loader.OrigamiPluginManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.SimpleServicesManager;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * org.bukkit.Server の Proxy 実装。JVM で1つだけ作る(Bukkit.setServer は1回しか呼べないため)。
 * シングルプレイではワールドを開くたびに MinecraftServer が作り直されるので、
 * 内部の MinecraftServer 参照だけを attach / detach で差し替える。
 *
 * 実装済みメソッドだけを implemented() に書く。default メソッドは標準実装に任せ、
 * それ以外は UnimplementedCalls 経由で UnsupportedOperationException にする。
 */
public final class OrigamiServer implements InvocationHandler {
    private static final Object UNHANDLED = new Object();
    private static OrigamiServer instance;

    private final String minecraftVersion;
    private final String version;
    private final java.util.logging.Logger logger;
    private final Server proxy;
    private final SimpleCommandMap commandMap;
    private final OrigamiPluginManager pluginManager;
    private final SimpleServicesManager servicesManager;
    private final OrigamiHelpMap helpMap = new OrigamiHelpMap();
    private volatile MinecraftServer minecraft;

    public static synchronized OrigamiServer get() {
        if (instance == null) {
            instance = new OrigamiServer();
        }
        return instance;
    }

    private OrigamiServer() {
        this.minecraftVersion = Versions.minecraft();
        this.version = "git-Origami-" + Versions.origami() + " (MC: " + minecraftVersion + ")";
        this.logger = createLogger();
        this.proxy = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(), new Class<?>[] {Server.class}, this);
        // SimpleCommandMap / OrigamiPluginManager は Server を要求するため、Proxy を作ってから構築する
        this.commandMap = new OrigamiCommandMap(proxy);
        this.pluginManager = new OrigamiPluginManager(proxy, commandMap);
        this.servicesManager = new SimpleServicesManager();
    }

    private static java.util.logging.Logger createLogger() {
        java.util.logging.Logger l = java.util.logging.Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        l.setLevel(Level.ALL);
        l.addHandler(new JulToSlf4jHandler(LoggerFactory.getLogger("Bukkit")));
        return l;
    }

    /** Bukkit.setServer に渡す Server。 */
    public Server bukkit() {
        return proxy;
    }

    public SimpleCommandMap commandMap() {
        return commandMap;
    }

    public OrigamiPluginManager pluginManager() {
        return pluginManager;
    }

    /** サーバー起動時に呼ぶ。 */
    public void attach(MinecraftServer server) {
        this.minecraft = server;
    }

    /** サーバー停止後に呼ぶ。 */
    public void detach() {
        this.minecraft = null;
    }

    /** 起動していないときは null。 */
    public MinecraftServer minecraft() {
        return minecraft;
    }

    @Override
    public Object invoke(Object proxyObject, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxyObject == args[0];
                case "hashCode" -> System.identityHashCode(proxyObject);
                case "toString" -> "OrigamiServer";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        Object result = implemented(method, args);
        if (result != UNHANDLED) {
            return result;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxyObject, method, args);
        }
        throw UnimplementedCalls.exception(method);
    }

    /** 実装済みメソッド。扱わないものは UNHANDLED (null は「結果が null」の意味)。 */
    private Object implemented(Method method, Object[] args) {
        MinecraftServer s = minecraft;
        // SERVER_STARTING は dedicated では initServer の前なので、PlayerList は null のことがある
        PlayerList list = s == null ? null : s.getPlayerList();
        if (method.getParameterCount() == 0) {
            return switch (method.getName()) {
                case "getName" -> "Origami";
                case "getVersion" -> version;
                // 暫定値。EssentialsX 等が形式を解析する可能性があるため Phase 8 で確定する
                case "getBukkitVersion" -> minecraftVersion + "-R0.1-SNAPSHOT";
                case "getMinecraftVersion" -> minecraftVersion;
                case "getLogger" -> logger;
                case "getPluginsFolder" -> FabricLoader.getInstance().getGameDir().resolve("plugins").toFile();
                case "getPluginManager" -> pluginManager;
                case "getServicesManager" -> servicesManager;
                case "getCommandMap" -> commandMap;
                case "getConsoleSender" -> ConsoleSenderBridge.console();
                case "getCommandAliases" -> new HashMap<String, String[]>();
                case "getHelpMap" -> helpMap;
                // ForwardingAudience の土台。Server#sendMessage / sendPlainMessage などの default が使う
                case "audiences" -> audiences(list);
                // 非推奨イベントの警告出力は未対応。DEFAULT は Spigot の既定と同じ (Warning#value に従う)
                case "getWarningState" -> Warning.WarningState.DEFAULT;
                case "getOnlinePlayers" -> list == null
                        ? List.<Player>of()
                        : list.getPlayers().stream().map(PlayerBridge::of).toList();
                case "getWorlds" -> LevelBridge.all();
                case "getUnsafe" -> ServerExtras.unsafe();
                case "getMessenger" -> ServerExtras.messenger();
                case "getScheduler" -> io.github.kiyo29.origami.bridge.scheduler.OrigamiScheduler.get();
                case "getItemFactory" -> ItemFactoryBridge.get();
                case "getPort" -> s == null ? -1 : s.getPort();
                case "getMaxPlayers" -> list == null ? 0 : list.getMaxPlayers();
                case "isPrimaryThread" -> s != null && s.isSameThread();
                case "isStopping" -> s != null && !s.isRunning();
                default -> UNHANDLED;
            };
        }
        Object first = args[0];
        return switch (method.getName()) {
            case "getPluginCommand" -> {
                Command command = commandMap.getCommand((String) first);
                yield command instanceof PluginCommand pluginCommand ? pluginCommand : null;
            }
            case "dispatchCommand" -> dispatchCommand((CommandSender) first, (String) args[1]);
            // Bukkit の getPlayer(String) は部分一致だが、現状は完全一致のみ (Phase 6 で見直す)
            case "getPlayer" -> first instanceof UUID id ? playerById(list, id) : playerByName(list, (String) first);
            case "getPlayerExact" -> playerByName(list, (String) first);
            case "getWorld" -> first instanceof String worldName ? LevelBridge.byName(worldName)
                    : first instanceof UUID worldId ? LevelBridge.byId(worldId) : UNHANDLED;
            case "getSpawnLimit" -> ServerExtras.spawnLimit((org.bukkit.entity.SpawnCategory) first);
            case "getTicksPerSpawns" -> ServerExtras.ticksPerSpawns((org.bukkit.entity.SpawnCategory) first);
            case "broadcast" -> method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == net.kyori.adventure.text.Component.class
                    ? broadcastTo(list, (net.kyori.adventure.text.Component) first, (String) args[1])
                    : UNHANDLED;
            default -> UNHANDLED;
        };
    }

    private static java.util.List<net.kyori.adventure.audience.Audience> audiences(PlayerList list) {
        java.util.List<net.kyori.adventure.audience.Audience> result = new ArrayList<>();
        result.add(ConsoleSenderBridge.console());
        if (list != null) {
            for (ServerPlayer player : list.getPlayers()) {
                result.add(PlayerBridge.of(player));
            }
        }
        return result;
    }

    /**
     * コンソールには常に送る。プレイヤーは bukkit.broadcast.user なら全員 (本物の Bukkit では default: true の権限だが
     * Origami には未登録のため特別扱い)、それ以外の権限は hasPermission で判定する。戻り値は受信者数。
     */
    private static int broadcastTo(PlayerList list, net.kyori.adventure.text.Component message, String permission) {
        int count = 0;
        ConsoleSenderBridge.console().sendMessage(message);
        count++;
        if (list != null) {
            for (ServerPlayer serverPlayer : new ArrayList<>(list.getPlayers())) {
                Player player = PlayerBridge.of(serverPlayer);
                if (Server.BROADCAST_CHANNEL_USERS.equals(permission) || player.hasPermission(permission)) {
                    player.sendMessage(message);
                    count++;
                }
            }
        }
        return count;
    }

    private static Player playerById(PlayerList list, UUID id) {
        ServerPlayer player = list == null ? null : list.getPlayer(id);
        return player == null ? null : PlayerBridge.of(player);
    }

    private static Player playerByName(PlayerList list, String name) {
        ServerPlayer player = list == null ? null : list.getPlayerByName(name);
        return player == null ? null : PlayerBridge.of(player);
    }

    /** Bukkit のコマンドを先に試し、無ければバニラのコマンドとして実行する (バニラの結果は成否を返せないため true)。 */
    private boolean dispatchCommand(CommandSender sender, String line) {
        MinecraftServer s = minecraft;
        if (s == null) {
            return false;
        }
        if (!s.isSameThread()) {
            throw new IllegalStateException("Command dispatched async: " + line);
        }
        String command = line.startsWith("/") ? line.substring(1) : line;
        if (commandMap.dispatch(sender, command)) {
            return true;
        }
        s.getCommands().performPrefixedCommand(CommandBridge.stackOf(s, sender), command);
        return true;
    }
}
