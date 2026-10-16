package io.github.kiyo29.origami;

import io.github.kiyo29.origami.config.OrigamiConfig;
import io.github.kiyo29.origami.internal.Lang;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
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
    }

    public static OrigamiConfig config() {
        return config;
    }
}
