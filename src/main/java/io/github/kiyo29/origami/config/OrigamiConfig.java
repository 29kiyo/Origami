package io.github.kiyo29.origami.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/origami.json。フィールドを増やしても、読み込み時に自動で書き戻される。 */
public final class OrigamiConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** "en_us" または "ja_jp" */
    public String language = "en_us";
    /** 未実装 Bukkit API の呼び出しを回数付きで記録する(デバッグ用) */
    public boolean recordUnimplementedCalls = false;
    public boolean debug = false;

    public static OrigamiConfig load(Path path, Logger log) {
        OrigamiConfig cfg = new OrigamiConfig();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                OrigamiConfig read = GSON.fromJson(reader, OrigamiConfig.class);
                if (read != null) {
                    cfg = read;
                }
            } catch (IOException | JsonParseException e) {
                log.warn("Failed to read {}, using defaults: {}", path, e.getMessage());
            }
        }
        if (cfg.language == null) {
            cfg.language = "en_us";
        }
        cfg.save(path, log);
        return cfg;
    }

    private void save(Path path, Logger log) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", path, e.getMessage());
        }
    }
}
