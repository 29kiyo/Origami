package io.github.kiyo29.origami.bridge.world;

import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.UnimplementedCalls;
import io.github.kiyo29.origami.bridge.entity.PlayerBridge;
import io.github.kiyo29.origami.compat.McCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * org.bukkit.World の Proxy 実装。ディメンションのキーごとに1つ。
 * シングルプレイでは ServerLevel が作り直されるため、ServerLevel は保持せず、
 * 呼び出しのたびに現在の MinecraftServer から引く。
 */
public final class LevelBridge implements InvocationHandler {
    private static final Object UNHANDLED = new Object();
    private static final Map<ResourceKey<Level>, World> CACHE = new ConcurrentHashMap<>();

    private final ResourceKey<Level> key;
    private final String name;
    private final UUID uid;
    private World proxy;

    private LevelBridge(ResourceKey<Level> key) {
        this.key = key;
        this.name = nameOf(key);
        this.uid = UUID.nameUUIDFromBytes(("origami:" + key.identifier()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static World of(ServerLevel level) {
        return of(level.dimension());
    }

    public static World of(ResourceKey<Level> key) {
        return CACHE.computeIfAbsent(key, k -> {
            LevelBridge bridge = new LevelBridge(k);
            World world = (World) Proxy.newProxyInstance(
                    World.class.getClassLoader(), new Class<?>[] {World.class}, bridge);
            bridge.proxy = world;
            return world;
        });
    }

    /** 読み込み済みの全ワールド。サーバー未起動なら空。 */
    public static List<World> all() {
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (mc == null) {
            return List.of();
        }
        List<World> result = new ArrayList<>();
        for (ServerLevel level : mc.getAllLevels()) {
            result.add(of(level));
        }
        return result;
    }

    public static @Nullable World byName(String name) {
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (mc == null || name == null) {
            return null;
        }
        for (ServerLevel level : mc.getAllLevels()) {
            if (nameOf(level.dimension()).equalsIgnoreCase(name)) {
                return of(level);
            }
        }
        return null;
    }

    public static @Nullable World byId(UUID id) {
        for (World world : all()) {
            if (world.getUID().equals(id)) {
                return world;
            }
        }
        return null;
    }

    /** Bukkit の World から現在の ServerLevel を引く。他の実装の World なら例外。 */
    public static ServerLevel levelOf(World world) {
        if (world != null && Proxy.isProxyClass(world.getClass())
                && Proxy.getInvocationHandler(world) instanceof LevelBridge bridge) {
            return bridge.level();
        }
        throw new IllegalArgumentException("Not an Origami world: " + world);
    }

    public static Location location(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        return new Location(of(level), x, y, z, yaw, pitch);
    }

    /** Bukkit 風のワールド名。overworld = world、nether = world_nether、end = world_the_end、それ以外は ns_path。 */
    static String nameOf(ResourceKey<Level> key) {
        String id = key.identifier().toString();
        return switch (id) {
            case "minecraft:overworld" -> "world";
            case "minecraft:the_nether" -> "world_nether";
            case "minecraft:the_end" -> "world_the_end";
            default -> id.replace(':', '_');
        };
    }

    ServerLevel level() {
        MinecraftServer mc = OrigamiServer.get().minecraft();
        ServerLevel level = mc == null ? null : mc.getLevel(key);
        if (level == null) {
            throw new IllegalStateException("World is not loaded: " + name);
        }
        return level;
    }

    private World.Environment environment() {
        return switch (key.identifier().toString()) {
            case "minecraft:overworld" -> World.Environment.NORMAL;
            case "minecraft:the_nether" -> World.Environment.NETHER;
            case "minecraft:the_end" -> World.Environment.THE_END;
            default -> World.Environment.CUSTOM;
        };
    }

    @Override
    public Object invoke(Object proxyObject, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxyObject == args[0];
                case "hashCode" -> key.hashCode();
                case "toString" -> "OrigamiWorld{name=" + name + "}";
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

    private Object implemented(Method method, Object[] args) {
        String methodName = method.getName();
        int n = method.getParameterCount();
        if (n == 0) {
            return switch (methodName) {
                case "getName" -> name;
                case "getUID" -> uid;
                case "getEnvironment" -> environment();
                case "getTime" -> McCompat.get().dayTime(level()) % 24000L;
                case "getFullTime" -> McCompat.get().dayTime(level());
                case "getGameTime" -> level().getGameTime();
                case "getMinHeight" -> level().getMinY();
                // Bukkit の maxHeight は上端の「次」(overworld なら 320)
                case "getMaxHeight" -> level().getMinY() + level().getHeight();
                case "getSeed" -> level().getSeed();
                case "getPlayers" -> players();
                case "getWorldBorder" -> WorldBorderBridge.of(this::level);
                case "getLogicalHeight" -> level().dimensionType().logicalHeight();
                case "hasStorm" -> level().getWeatherData().isRaining();
                case "isThundering" -> level().getWeatherData().isThundering();
                case "isClearWeather" -> !level().getWeatherData().isRaining();
                case "getWeatherDuration" -> level().getWeatherData().getRainTime();
                case "getThunderDuration" -> level().getWeatherData().getThunderTime();
                case "getClearWeatherDuration" -> level().getWeatherData().getClearWeatherTime();
                case "getSpawnLocation" -> spawn();
                default -> UNHANDLED;
            };
        }
        if (n == 1 && methodName.equals("getEntity") && args[0] instanceof UUID id) {
            // 現状はプレイヤーのみ。それ以外のエンティティは未対応のため null
            return level().getEntity(id) instanceof ServerPlayer p ? PlayerBridge.of(p) : null;
        }
        if (methodName.equals("getChunkAt") && n >= 2 && args[0] instanceof Integer) {
            boolean generate = n < 3 || (Boolean) args[2];
            return ChunkBridge.load(this::level, (Integer) args[0], (Integer) args[1], generate);
        }
        if (methodName.equals("isChunkLoaded") && n == 2 && args[0] instanceof Integer) {
            return level().getChunkSource().hasChunk((Integer) args[0], (Integer) args[1]);
        }
        if (methodName.equals("getChunkAtAsync") && n == 5) {
            int cx = (Integer) args[0];
            int cz = (Integer) args[1];
            boolean gen = (Boolean) args[2];
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<org.bukkit.Chunk> callback = (java.util.function.Consumer<org.bukkit.Chunk>) args[4];
            Runnable task = () -> callback.accept(ChunkBridge.loadOrNull(this::level, cx, cz, gen));
            MinecraftServer mc = OrigamiServer.get().minecraft();
            if (mc == null || mc.isSameThread()) {
                task.run();
            } else {
                mc.execute(task);
            }
            return null;
        }
        if (methodName.equals("getBlockAt") && n == 1 && args[0] instanceof Location at) {
            return BlockBridge.of(proxy, at.getBlockX(), at.getBlockY(), at.getBlockZ());
        }
        if (methodName.equals("getHighestBlockYAt") && n == 2 && args[0] instanceof Integer hx && args[1] instanceof Integer hz) {
            // Bukkit default height map is MOTION_BLOCKING; ChunkAccess#getHeight returns the highest block's y
            return level().getChunk(hx >> 4, hz >> 4).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, hx, hz);
        }
        if (n == 1 && (methodName.equals("setStorm") || methodName.equals("setThundering"))) {
            var weather = level().getWeatherData();
            boolean on = (Boolean) args[0];
            if (methodName.equals("setStorm")) {
                weather.setRaining(on);
                weather.setRainTime(0);
                weather.setClearWeatherTime(0);
            } else {
                weather.setThundering(on);
                weather.setThunderTime(0);
            }
            return null;
        }
        if (n == 1 && methodName.equals("setWeatherDuration")) {
            level().getWeatherData().setRainTime((Integer) args[0]);
            return null;
        }
        if (n == 1 && methodName.equals("setThunderDuration")) {
            level().getWeatherData().setThunderTime((Integer) args[0]);
            return null;
        }
        if (n == 1 && methodName.equals("setClearWeatherDuration")) {
            level().getWeatherData().setClearWeatherTime((Integer) args[0]);
            return null;
        }
        if (n == 1 && methodName.equals("setFullTime") && args[0] instanceof Long full) {
            McCompat.get().setDayTime(level(), full);
            return null;
        }
        if (n == 1 && methodName.equals("setTime") && args[0] instanceof Long time) {
            long current = McCompat.get().dayTime(level());
            long margin = (time - current) % 24000L;
            if (margin < 0) {
                margin += 24000L;
            }
            McCompat.get().setDayTime(level(), current + margin);
            return null;
        }
        if (n == 3 && methodName.equals("getBlockAt") && args[0] instanceof Integer) {
            return BlockBridge.of(proxy, (Integer) args[0], (Integer) args[1], (Integer) args[2]);
        }
        return UNHANDLED;
    }

    private List<Player> players() {
        List<Player> result = new ArrayList<>();
        for (ServerPlayer p : level().players()) {
            result.add(PlayerBridge.of(p));
        }
        return result;
    }

    private Location spawn() {
        var data = level().getRespawnData();
        BlockPos pos = data.pos();
        return new Location(of(data.dimension()), pos.getX(), pos.getY(), pos.getZ(), data.yaw(), data.pitch());
    }
}
