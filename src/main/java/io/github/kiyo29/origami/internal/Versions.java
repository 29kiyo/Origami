package io.github.kiyo29.origami.internal;

import io.github.kiyo29.origami.Origami;
import net.fabricmc.loader.api.FabricLoader;

/** FabricLoader のモッド情報からバージョン文字列を取る。MC 側のクラスには依存しない。 */
public final class Versions {
    private Versions() { }

    public static String minecraft() {
        return of("minecraft");
    }

    public static String origami() {
        return of(Origami.MOD_ID);
    }

    private static String of(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
