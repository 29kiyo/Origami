package io.github.kiyo29.origami.loader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * プラグイン jar が Minecraft / CraftBukkit の内部クラスを直接参照していないかを調べる。
 * クラスファイルの定数プールには内部名 (スラッシュ区切り) がそのまま入るため、
 * バイト列の検索だけで判定できる (ASM は使わない)。
 */
final class NmsDetector {
    private static final String[] MARKERS = {"net/minecraft/", "org/bukkit/craftbukkit/"};
    private static final byte[][] MARKER_BYTES = new byte[MARKERS.length][];

    static {
        for (int i = 0; i < MARKERS.length; i++) {
            MARKER_BYTES[i] = MARKERS[i].getBytes(StandardCharsets.US_ASCII);
        }
    }

    record Finding(String classEntry, String reference) {
        String describe() {
            return reference + " (in " + classEntry + ")";
        }
    }

    private NmsDetector() { }

    /** 最初に見つかった参照を返す。無ければ empty。 */
    static Optional<Finding> scan(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                for (int i = 0; i < MARKER_BYTES.length; i++) {
                    if (indexOf(bytes, MARKER_BYTES[i]) >= 0) {
                        return Optional.of(new Finding(name, MARKERS[i]));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        int last = data.length - pattern.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
