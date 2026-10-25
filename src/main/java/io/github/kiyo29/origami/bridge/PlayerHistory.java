package io.github.kiyo29.origami.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * First and last time a player was seen, kept in config/origami-players.json.
 * MC has no such record (player data files are rewritten on every save), but Bukkit's
 * getFirstPlayed/getLastPlayed/hasPlayedBefore need one. Times are epoch milliseconds.
 */
public final class PlayerHistory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, long[]> DATA = new HashMap<>();
    private static boolean loaded;

    private PlayerHistory() { }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("origami-players.json");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, long[]> read = GSON.fromJson(reader, new TypeToken<Map<String, long[]>>() { }.getType());
            if (read != null) {
                DATA.putAll(read);
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable file just means we start from an empty history
        }
    }

    private static void save() {
        try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            GSON.toJson(DATA, writer);
        } catch (IOException e) {
            // history is best effort
        }
    }

    /** Records a join. Returns true when this is the first time the player is seen. */
    public static synchronized boolean joined(UUID id) {
        load();
        long now = System.currentTimeMillis();
        long[] entry = DATA.get(id.toString());
        boolean isNew = entry == null;
        if (isNew) {
            entry = new long[] {now, now};
            DATA.put(id.toString(), entry);
        } else {
            entry[1] = now;
        }
        save();
        return isNew;
    }

    public static synchronized void left(UUID id) {
        load();
        long[] entry = DATA.get(id.toString());
        if (entry != null) {
            entry[1] = System.currentTimeMillis();
            save();
        }
    }

    public static synchronized long first(UUID id) {
        load();
        long[] entry = DATA.get(id.toString());
        return entry == null ? 0L : entry[0];
    }

    public static synchronized long last(UUID id) {
        load();
        long[] entry = DATA.get(id.toString());
        return entry == null ? 0L : entry[1];
    }
}
