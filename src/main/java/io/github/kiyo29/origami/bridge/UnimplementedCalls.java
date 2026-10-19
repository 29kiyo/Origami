package io.github.kiyo29.origami.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.internal.Lang;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 未実装の Bukkit API 呼び出しの処理。
 * 初回のみ警告ログを出し、設定 recordUnimplementedCalls が true のときは回数を記録する(デバッグ用)。
 */
public final class UnimplementedCalls {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private UnimplementedCalls() { }

    /** 呼び出しを記録し、呼び出し元がそのまま throw する例外を返す。 */
    public static UnsupportedOperationException exception(Method method) {
        return exception(signature(method));
    }

    /** シグネチャ文字列版 (生成スタブ用)。 */
    public static UnsupportedOperationException exception(String signature) {
        note(signature);
        return new UnsupportedOperationException("Not implemented by Origami: " + signature);
    }

    /** 例外を投げずに記録だけする (呼び出しを無視して続行する未対応 API 用)。 */
    public static void note(String signature) {
        if (WARNED.add(signature)) {
            Origami.LOGGER.warn(Lang.tr("log.unimplemented", signature));
        }
        if (Origami.config().recordUnimplementedCalls) {
            COUNTS.computeIfAbsent(signature, k -> new LongAdder()).increment();
        }
    }

    /** 記録した回数を多い順に JSON で書き出す。記録が無効、または空なら何もしない。 */
    public static void flush(Path file) {
        if (!Origami.config().recordUnimplementedCalls || COUNTS.isEmpty()) {
            return;
        }
        Map<String, Long> sorted = new LinkedHashMap<>();
        COUNTS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue().sum()));
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(sorted, writer);
            }
            Origami.LOGGER.info(Lang.tr("log.unimplemented.summary", sorted.size(), file));
        } catch (IOException e) {
            Origami.LOGGER.warn("Failed to write {}: {}", file, e.getMessage());
        }
    }

    private static String signature(Method method) {
        String params = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName() + "(" + params + ")";
    }
}
