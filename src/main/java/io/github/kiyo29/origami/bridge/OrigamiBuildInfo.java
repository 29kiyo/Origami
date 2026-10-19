package io.github.kiyo29.origami.bridge;

import io.github.kiyo29.origami.internal.Versions;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * ServerBuildInfo の実装。Bukkit.setServer は内部で ServerBuildInfo.buildInfo() を呼ぶため必須。
 * META-INF/services/io.papermc.paper.ServerBuildInfo から ServiceLoader 経由で読み込まれる
 * (public クラス + public の引数なしコンストラクタが必要)。
 */
public final class OrigamiBuildInfo implements ServerBuildInfo {
    private static final Key BRAND_ID = Key.key("origami", "origami");

    public OrigamiBuildInfo() { }

    @Override
    public Key brandId() {
        return BRAND_ID;
    }

    /** Origami は Paper 互換を目指すため、Paper のブランド ID も互換として扱う。 */
    @Override
    public boolean isBrandCompatible(Key brandId) {
        return BRAND_ID.equals(brandId) || BRAND_PAPER_ID.equals(brandId);
    }

    @Override
    public String brandName() {
        return "Origami";
    }

    @Override
    public String minecraftVersionId() {
        return Versions.minecraft();
    }

    @Override
    public String minecraftVersionName() {
        return Versions.minecraft();
    }

    @Override
    public OptionalInt buildNumber() {
        return OptionalInt.empty();
    }

    /** ビルド時刻は不明。EPOCH を「不明」として返す。 */
    @Override
    public Instant buildTime() {
        return Instant.EPOCH;
    }

    @Override
    public Optional<String> gitBranch() {
        return Optional.empty();
    }

    @Override
    public Optional<String> gitCommit() {
        return Optional.empty();
    }

    @Override
    public String asString(StringRepresentation representation) {
        String simple = Versions.origami();
        return representation == StringRepresentation.VERSION_SIMPLE
                ? simple
                : simple + " (MC: " + Versions.minecraft() + ")";
    }
}
