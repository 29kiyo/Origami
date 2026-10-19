package io.github.kiyo29.origami.loader;

import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.internal.Lang;
import org.bukkit.plugin.InvalidDescriptionException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** plugins/ の走査と plugin.yml の読み取り。読めない jar は警告を出して飛ばす。 */
final class PluginScanner {
    private PluginScanner() { }

    /** directory 直下の *.jar。ディレクトリが無ければ作る。名前順。 */
    static File[] jarsIn(File directory) {
        if (!directory.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    static List<PluginCandidate> describe(File[] files) {
        OrigamiPluginLoader loader = new OrigamiPluginLoader();
        List<PluginCandidate> result = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".jar")) {
                continue;
            }
            try {
                result.add(new PluginCandidate(file, loader.getPluginDescription(file)));
            } catch (InvalidDescriptionException e) {
                String detail = e.getMessage() + (e.getCause() != null ? ": " + e.getCause() : "");
                Origami.LOGGER.warn(Lang.tr("log.loader.scan_failed", file.getName(), detail));
            }
        }
        return result;
    }
}
