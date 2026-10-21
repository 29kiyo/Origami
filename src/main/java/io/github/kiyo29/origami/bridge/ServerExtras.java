package io.github.kiyo29.origami.bridge;

import org.bukkit.UnsafeValues;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.StandardMessenger;

/**
 * OrigamiServer から呼ぶ小さな実装の置き場。
 * スポーン上限・間隔は Bukkit の既定値を返すだけで、バニラの実際のスポーン処理とは連動しない (部分実装)。
 */
public final class ServerExtras {
    private static final Messenger MESSENGER = new StandardMessenger();

    private ServerExtras() { }

    public static Messenger messenger() {
        return MESSENGER;
    }

    public static UnsafeValues unsafe() {
        return UnsafeBridge.get();
    }

    public static int spawnLimit(SpawnCategory category) {
        return switch (category.name()) {
            case "MONSTER" -> 70;
            case "ANIMAL" -> 10;
            case "WATER_ANIMAL" -> 5;
            case "WATER_AMBIENT" -> 20;
            case "WATER_UNDERGROUND_CREATURE" -> 5;
            case "AXOLOTL" -> 5;
            case "AMBIENT" -> 15;
            default -> 0;
        };
    }

    public static int ticksPerSpawns(SpawnCategory category) {
        return category.name().equals("ANIMAL") ? 400 : 1;
    }
}
