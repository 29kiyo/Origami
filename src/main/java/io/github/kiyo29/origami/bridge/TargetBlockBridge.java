package io.github.kiyo29.origami.bridge;

import io.github.kiyo29.origami.bridge.world.BlockBridge;
import io.github.kiyo29.origami.bridge.world.LevelBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Line-of-sight block lookups of a player (LivingEntity#getTargetBlock and friends).
 * getTargetBlock / getLastTwoTargetBlocks walk the view ray in 0.25 steps (Bukkit BlockIterator semantics:
 * the first block whose type is not transparent; if none, the last block). getTargetBlockExact uses MC's
 * Entity#pick. Must run on the server thread. Not implemented: TargetBlockInfo / getTargetBlockFace / rayTrace*.
 */
public final class TargetBlockBridge {
    /** Returned by handle() when the method is not one of these. */
    public static final Object NONE = new Object();
    private static final double STEP = 0.25;

    private TargetBlockBridge() { }

    private static boolean transparent(Material m, Set<?> set) {
        if (set == null) {
            return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
        }
        return set.contains(m);
    }

    /** Returns [previous (may be null), target]. */
    private static Block[] walk(ServerPlayer p, Set<?> set, int max) {
        World world = LevelBridge.of(p.level());
        Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getViewVector(1.0F);
        Block prev = null;
        Block cur = null;
        int lx = Integer.MIN_VALUE;
        int ly = Integer.MIN_VALUE;
        int lz = Integer.MIN_VALUE;
        for (double d = 0; d <= max; d += STEP) {
            int x = (int) Math.floor(eye.x + dir.x * d);
            int y = (int) Math.floor(eye.y + dir.y * d);
            int z = (int) Math.floor(eye.z + dir.z * d);
            if (x == lx && y == ly && z == lz) {
                continue;
            }
            lx = x;
            ly = y;
            lz = z;
            Block b = BlockBridge.of(world, x, y, z);
            prev = cur;
            cur = b;
            if (!transparent(b.getType(), set)) {
                break;
            }
        }
        return new Block[] {prev, cur};
    }

    public static Object handle(ServerPlayer p, Method method, Object[] args) {
        if (p == null) {
            return NONE;
        }
        Class<?>[] t = method.getParameterTypes();
        String name = method.getName();
        if (t.length == 2 && t[0] == Set.class && t[1] == int.class) {
            Set<?> set = (Set<?>) args[0];
            int max = (Integer) args[1];
            if (name.equals("getTargetBlock")) {
                return walk(p, set, max)[1];
            }
            if (name.equals("getLastTwoTargetBlocks")) {
                Block[] r = walk(p, set, max);
                List<Block> out = new ArrayList<>();
                if (r[0] != null) {
                    out.add(r[0]);
                }
                if (r[1] != null) {
                    out.add(r[1]);
                }
                return out;
            }
        }
        if (name.equals("getTargetBlockExact") && t.length == 2 && t[0] == int.class && t[1] == FluidCollisionMode.class) {
            int max = (Integer) args[0];
            boolean liquids = args[1] != FluidCollisionMode.NEVER;
            HitResult h = p.pick(max, 1.0F, liquids);
            if (h instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
                return BlockBridge.of(LevelBridge.of(p.level()),
                        bh.getBlockPos().getX(), bh.getBlockPos().getY(), bh.getBlockPos().getZ());
            }
            return null;
        }
        return NONE;
    }
}
