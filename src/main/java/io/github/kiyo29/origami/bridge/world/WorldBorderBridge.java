package io.github.kiyo29.origami.bridge.world;

import io.github.kiyo29.origami.bridge.UnimplementedCalls;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * org.bukkit.WorldBorder (Proxy) over MC's WorldBorder. Read-only for now:
 * setters, warning time and max center coordinate are not implemented (units not verified).
 */
final class WorldBorderBridge implements InvocationHandler {
    private final Supplier<ServerLevel> levelSupplier;

    private WorldBorderBridge(Supplier<ServerLevel> levelSupplier) {
        this.levelSupplier = levelSupplier;
    }

    static WorldBorder of(Supplier<ServerLevel> levelSupplier) {
        return (WorldBorder) Proxy.newProxyInstance(
                WorldBorder.class.getClassLoader(), new Class<?>[] {WorldBorder.class},
                new WorldBorderBridge(levelSupplier));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "OrigamiWorldBorder";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        ServerLevel level = levelSupplier.get();
        net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
        switch (method.getName()) {
            case "getWorld" -> {
                return LevelBridge.of(level);
            }
            case "getSize" -> {
                return border.getSize();
            }
            case "getCenter" -> {
                return new Location(LevelBridge.of(level), border.getCenterX(), 0.0, border.getCenterZ());
            }
            case "isInside" -> {
                Location loc = (Location) args[0];
                World world = LevelBridge.of(level);
                return loc != null && loc.getWorld() != null && loc.getWorld().equals(world)
                        && border.isWithinBounds(loc.getX(), loc.getZ());
            }
            case "getDamageBuffer" -> {
                return border.getSafeZone();
            }
            case "getDamageAmount" -> {
                return border.getDamagePerBlock();
            }
            case "getWarningDistance" -> {
                return border.getWarningBlocks();
            }
            case "getMaxSize" -> {
                return (double) border.getAbsoluteMaxSize();
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
