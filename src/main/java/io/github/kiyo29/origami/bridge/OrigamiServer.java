package io.github.kiyo29.origami.bridge;

import io.github.kiyo29.origami.internal.JulToSlf4jHandler;
import io.github.kiyo29.origami.internal.Versions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Server;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.SimpleServicesManager;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
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
    private final SimplePluginManager pluginManager;
    private final SimpleServicesManager servicesManager;
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
        // SimpleCommandMap / SimplePluginManager は Server を要求するため、Proxy を作ってから構築する
        this.commandMap = new SimpleCommandMap(proxy, new HashMap<>());
        this.pluginManager = new SimplePluginManager(proxy, commandMap);
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

    public SimplePluginManager pluginManager() {
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
        Object result = implemented(method);
        if (result != UNHANDLED) {
            return result;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxyObject, method, args);
        }
        throw UnimplementedCalls.exception(method);
    }

    /** 引数なしの実装済みメソッド。引数ありのオーバーロードは UNHANDLED のまま未実装として扱う。 */
    private Object implemented(Method method) {
        if (method.getParameterCount() != 0) {
            return UNHANDLED;
        }
        MinecraftServer s = minecraft;
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
            // Player / World のブリッジは Phase 6。それまでは空
            case "getOnlinePlayers", "getWorlds" -> List.of();
            case "getPort" -> s == null ? -1 : s.getPort();
            case "getMaxPlayers" -> s == null ? 0 : s.getPlayerList().getMaxPlayers();
            case "isPrimaryThread" -> s != null && s.isSameThread();
            default -> UNHANDLED;
        };
    }
}
