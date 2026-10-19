package io.github.kiyo29.origami.loader;

import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.internal.Lang;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginLoadOrder;

import java.io.File;

/**
 * プラグインの読み込み・有効化・無効化を Minecraft サーバーの起動段階に結びつける。
 * SERVER_STARTING: load (onLoad) + STARTUP を enable / SERVER_STARTED: POSTWORLD を enable /
 * SERVER_STOPPING: 逆順に disable して clear。
 * シングルプレイではワールドを開くたびにこの流れが繰り返される (停止時に全部解放するため)。
 */
public final class PluginLifecycle {
    private PluginLifecycle() { }

    public static void loadAndEnableStartup() {
        OrigamiPluginManager manager = OrigamiServer.get().pluginManager();
        File folder = Bukkit.getServer().getPluginsFolder();
        Plugin[] loaded = manager.loadPlugins(folder);
        Origami.LOGGER.info(Lang.tr("log.loader.summary", loaded.length, folder));
        enable(manager, PluginLoadOrder.STARTUP);
    }

    public static void enablePostWorld() {
        enable(OrigamiServer.get().pluginManager(), PluginLoadOrder.POSTWORLD);
    }

    public static void disableAll() {
        OrigamiPluginManager manager = OrigamiServer.get().pluginManager();
        manager.disablePlugins();
        manager.clearPlugins();
    }

    private static void enable(OrigamiPluginManager manager, PluginLoadOrder order) {
        for (Plugin plugin : manager.getPlugins()) {
            if (plugin.getDescription().getLoadOrder() == order && !plugin.isEnabled()) {
                manager.enablePlugin(plugin);
            }
        }
    }
}
