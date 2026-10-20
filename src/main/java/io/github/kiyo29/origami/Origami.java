package io.github.kiyo29.origami;

import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.UnimplementedCalls;
import io.github.kiyo29.origami.bridge.command.CommandBridge;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import io.github.kiyo29.origami.config.OrigamiConfig;
import io.github.kiyo29.origami.internal.Lang;
import io.github.kiyo29.origami.loader.PluginLifecycle;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Origami implements ModInitializer {
    public static final String MOD_ID = "origami";
    public static final Logger LOGGER = LoggerFactory.getLogger("Origami");

    private static OrigamiConfig config = new OrigamiConfig();

    @Override
    public void onInitialize() {
        config = OrigamiConfig.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"), LOGGER);
        Lang.init(config.language);

        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOGGER.info(Lang.tr("log.init", version));

        // dispatcher は /reload のたびに作り直される。作り直されたら Bukkit コマンドを載せ直す
        // (CommandSelection.ALL はデータ検証用の使い捨て Commands なので無視する)
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            if (selection != Commands.CommandSelection.ALL) {
                CommandBridge.bind(dispatcher);
                CommandBridge.sync(OrigamiServer.get().commandMap());
            }
        });

        // OrigamiServer は JVM で1つ。Bukkit.setServer は最初のサーバー起動時にだけ呼ぶ。
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            OrigamiServer bridge = OrigamiServer.get();
            bridge.attach(server);
            if (Bukkit.getServer() == null) {
                Bukkit.setServer(bridge.bukkit());
            }
            LOGGER.info(Lang.tr("log.server.attached"));
            LOGGER.info(Lang.tr("log.server.ready", Bukkit.getName() + " " + Bukkit.getVersion()));
            CommandBridge.bind(server.getCommands().getDispatcher());
            runStep("load", PluginLifecycle::loadAndEnableStartup);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> runStep("enable", PluginLifecycle::enablePostWorld));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> runStep("disable", PluginLifecycle::disableAll));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            CommandBridge.unbind();
            OrigamiServer.get().detach();
            UnimplementedCalls.flush(FabricLoader.getInstance().getConfigDir().resolve("origami-unimplemented.json"));
            LOGGER.info(Lang.tr("log.server.detached"));
        });
    }

    /** プラグイン処理の失敗でサーバー本体の起動・停止を止めない。 */
    private static void runStep(String name, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            LOGGER.error(Lang.tr("log.loader.lifecycle_failed", name), t);
        }
    }

    public static OrigamiConfig config() {
        return config;
    }
}
