package io.github.kiyo29.origami.loader;

import java.util.List;

/**
 * プラグイン用クラスローダーの許可・拒否規則。
 * 許可リストに合致したクラスだけを Origami 側のクラスローダー (Fabric) へ委譲する。
 * それ以外の Fabric / 他 Mod のクラスはプラグインから見えない。
 * Phase 8 で CraftBukkit 互換クラスの許可リストを足すときは、ここだけを変える。
 */
final class LoaderRules {
    private static final List<String> DENIED = List.of(
            "net.minecraft.",
            "net.fabricmc.",
            "org.bukkit.craftbukkit.",
            "io.github.kiyo29.origami.",
            "com.mojang.");
    /** DENIED の中で例外的に通すもの。 */
    private static final List<String> DENIED_EXCEPTIONS = List.of(
            "com.mojang.brigadier.");
    private static final List<String> ALLOWED = List.of(
            "org.bukkit.",
            "io.papermc.paper.",
            "com.destroystokyo.paper.",
            "net.kyori.",
            "net.md_5.bungee.",
            "org.slf4j.",
            "org.apache.logging.",
            "org.apache.commons.",
            "org.yaml.snakeyaml.",
            "org.jetbrains.annotations.",
            "org.jspecify.",
            "org.intellij.lang.annotations.",
            "com.google.",
            "com.mojang.brigadier.",
            "it.unimi.dsi.fastutil.",
            "org.joml.");

    private LoaderRules() { }

    static boolean isDenied(String className) {
        if (className.equals("org.bukkit.craftbukkit.CraftServer")) {
            return false; // CraftBukkit compat allow-list (marker class)
        }
        for (String p : DENIED_EXCEPTIONS) {
            if (className.startsWith(p)) {
                return false;
            }
        }
        for (String p : DENIED) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    static boolean isAllowed(String className) {
        for (String p : ALLOWED) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
