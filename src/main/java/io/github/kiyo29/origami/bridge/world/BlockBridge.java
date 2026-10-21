package io.github.kiyo29.origami.bridge.world;

import io.github.kiyo29.origami.bridge.UnimplementedCalls;
import io.github.kiyo29.origami.bridge.OrigamiServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Objects;

/**
 * org.bukkit.block.Block の Proxy 実装 (読み取りのみ)。座標とワールドだけを持ち、状態は毎回 ServerLevel から読む。
 * setType / getState / getBlockData などは未実装 (Phase 6 後半以降)。
 */
public final class BlockBridge implements InvocationHandler {
    private static final Object UNHANDLED = new Object();

    private final World world;
    private final int x;
    private final int y;
    private final int z;
    private Block proxy;

    private BlockBridge(World world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static Block of(World world, int x, int y, int z) {
        BlockBridge bridge = new BlockBridge(world, x, y, z);
        Block block = (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(), new Class<?>[] {Block.class}, bridge);
        bridge.proxy = block;
        return block;
    }

    /** Material#isAir は Registry (RegistryAccess) を引くため使わない。enum 定数の比較だけで判定する。 */
    private static boolean isAir(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    /** 他の Mod のブロックは Bukkit の Material に無いため AIR になる (部分実装)。 */
    private Material material() {
        ServerLevel level = LevelBridge.levelOf(world);
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (id.startsWith("minecraft:")) {
            Material m = Material.getMaterial(id.substring("minecraft:".length()).toUpperCase(Locale.ROOT));
            if (m != null) {
                return m;
            }
        }
        return Material.AIR;
    }

    @Override
    public Object invoke(Object proxyObject, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> args[0] != null && Proxy.isProxyClass(args[0].getClass())
                        && Proxy.getInvocationHandler(args[0]) instanceof BlockBridge o
                        && o.world == world && o.x == x && o.y == y && o.z == z;
                case "hashCode" -> Objects.hash(System.identityHashCode(world), x, y, z);
                case "toString" -> "Block{world=" + world.getName() + ",x=" + x + ",y=" + y + ",z=" + z + "}";
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

    /** Partial: applyPhysics=true uses UPDATE_ALL, false uses UPDATE_CLIENTS only. Block entities and BlockData are not handled. */
    private void setType(Material material, boolean applyPhysics) {
        if (material == null) {
            throw new IllegalArgumentException("material cannot be null");
        }
        String name = material.name();
        if (name.startsWith("LEGACY_")) {
            throw new IllegalArgumentException("Legacy materials are not supported: " + name);
        }
        var block = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(name.toLowerCase(Locale.ROOT)));
        if (block == net.minecraft.world.level.block.Blocks.AIR && !isAir(material)) {
            throw new IllegalArgumentException(name + " is not a block");
        }
        var state = block.defaultBlockState();
        int flags = applyPhysics ? net.minecraft.world.level.block.Block.UPDATE_ALL : net.minecraft.world.level.block.Block.UPDATE_CLIENTS;
        ServerLevel level = LevelBridge.levelOf(world);
        BlockPos pos = new BlockPos(x, y, z);
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (mc == null) {
            return;
        }
        if (mc.isSameThread()) {
            level.setBlock(pos, state, flags);
        } else {
            mc.execute(() -> level.setBlock(pos, state, flags));
        }
    }

    private Object implemented(Method method, Object[] args) {
        String methodName = method.getName();
        Class<?>[] types = method.getParameterTypes();
        int n = types.length;
        if (n == 0) {
            return switch (methodName) {
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, x, y, z);
                case "getType" -> material();
                case "isEmpty" -> isAir(material());
                default -> UNHANDLED;
            };
        }
        if (methodName.equals("getLocation") && n == 1 && types[0] == Location.class) {
            Location loc = (Location) args[0];
            if (loc != null) {
                loc.setWorld(world);
                loc.setX(x);
                loc.setY(y);
                loc.setZ(z);
                loc.setYaw(0);
                loc.setPitch(0);
            }
            return loc;
        }
        if (methodName.equals("setType") && args[0] instanceof Material material) {
            setType(material, n == 1 || (Boolean) args[1]);
            return null;
        }
        if (methodName.equals("getRelative")) {
            if (n == 3) {
                return of(world, x + (Integer) args[0], y + (Integer) args[1], z + (Integer) args[2]);
            }
            if (n == 1 && args[0] instanceof BlockFace face) {
                return of(world, x + face.getModX(), y + face.getModY(), z + face.getModZ());
            }
            if (n == 2 && args[0] instanceof BlockFace face) {
                int d = (Integer) args[1];
                return of(world, x + face.getModX() * d, y + face.getModY() * d, z + face.getModZ() * d);
            }
        }
        return UNHANDLED;
    }
}
