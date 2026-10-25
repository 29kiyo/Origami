package io.github.kiyo29.origami.bridge.world;

import io.github.kiyo29.origami.bridge.UnimplementedCalls;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Chunk;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Minimal org.bukkit.Chunk (Proxy). Knows its coordinates and world only.
 * isGenerated() is approximated by "currently loaded". load() must run on the server thread.
 */
final class ChunkBridge implements InvocationHandler {
    private final Supplier<ServerLevel> levelSupplier;
    private final int x;
    private final int z;

    private ChunkBridge(Supplier<ServerLevel> levelSupplier, int x, int z) {
        this.levelSupplier = levelSupplier;
        this.x = x;
        this.z = z;
    }

    static Chunk of(Supplier<ServerLevel> levelSupplier, int x, int z) {
        return (Chunk) Proxy.newProxyInstance(
                Chunk.class.getClassLoader(), new Class<?>[] {Chunk.class}, new ChunkBridge(levelSupplier, x, z));
    }

    /** Bukkit World#getChunkAt: always returns a Chunk, loading it first (generating when asked). */
    static Chunk load(Supplier<ServerLevel> levelSupplier, int x, int z, boolean generate) {
        ServerLevel level = levelSupplier.get();
        if (generate) {
            level.getChunk(x, z);
        } else {
            level.getChunk(x, z, ChunkStatus.FULL, false);
        }
        return of(levelSupplier, x, z);
    }

    /** Paper getChunkAtAsync semantics: null when the chunk does not exist and generate is false. */
    static Chunk loadOrNull(Supplier<ServerLevel> levelSupplier, int x, int z, boolean generate) {
        ChunkAccess chunk = levelSupplier.get().getChunk(x, z, ChunkStatus.FULL, generate);
        return chunk == null ? null : of(levelSupplier, x, z);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> args[0] != null && Proxy.isProxyClass(args[0].getClass())
                        && Proxy.getInvocationHandler(args[0]) instanceof ChunkBridge other
                        && other.x == x && other.z == z && other.levelSupplier.get() == levelSupplier.get();
                case "hashCode" -> Objects.hash(x, z);
                case "toString" -> "OrigamiChunk{" + x + "," + z + "}";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        ServerLevel level = levelSupplier.get();
        switch (method.getName()) {
            case "getX" -> {
                return x;
            }
            case "getZ" -> {
                return z;
            }
            case "getWorld" -> {
                return LevelBridge.of(level);
            }
            case "isLoaded", "isGenerated", "isEntitiesLoaded" -> {
                return level.getChunkSource().hasChunk(x, z);
            }
            case "load" -> {
                boolean generate = args == null || args.length == 0 || (Boolean) args[0];
                return level.getChunk(x, z, ChunkStatus.FULL, generate) != null;
            }
            default -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw UnimplementedCalls.exception(method);
            }
        }
    }
}
