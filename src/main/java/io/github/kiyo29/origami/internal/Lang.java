package io.github.kiyo29.origami.internal;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** サーバー側ログ用の簡易 lang。assets/origami/lang/<code>.json を読む。無いキーは en_us にフォールバック。 */
public final class Lang {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private static Map<String, String> table = Map.of();
    private static Map<String, String> fallback = Map.of();

    private Lang() { }

    public static void init(String code) {
        fallback = read("en_us");
        table = "en_us".equals(code) ? fallback : read(code);
    }

    public static String tr(String key, Object... args) {
        String text = table.getOrDefault(key, fallback.getOrDefault(key, key));
        return args.length == 0 ? text : String.format(text, args);
    }

    private static Map<String, String> read(String code) {
        String path = "/assets/origami/lang/" + code + ".json";
        try (InputStream in = Lang.class.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            Map<String, String> map = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), MAP_TYPE);
            return map == null ? Map.of() : map;
        } catch (IOException | JsonParseException e) {
            return Map.of();
        }
    }
}
