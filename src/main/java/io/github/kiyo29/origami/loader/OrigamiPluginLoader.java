package io.github.kiyo29.origami.loader;

import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.internal.Lang;
import io.github.kiyo29.origami.loader.remap.ClassRemapper;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * org.bukkit.plugin.PluginLoader の Origami 実装。
 * JavaPlugin.init が ServiceLoader で探すため、META-INF/services/org.bukkit.plugin.PluginLoader で登録する
 * (public クラス + public な引数なしコンストラクタが必要)。状態を持たないので、何個作っても同じ。
 */
@SuppressWarnings({"deprecation", "removal"})
public final class OrigamiPluginLoader implements PluginLoader {
    private static final Pattern[] FILTERS = {Pattern.compile("\\.jar$")};

    public OrigamiPluginLoader() { }

    @Override
    public Plugin loadPlugin(File file) throws InvalidPluginException {
        if (!file.exists()) {
            throw new InvalidPluginException(new FileNotFoundException(file.getPath() + " does not exist"));
        }
        PluginDescriptionFile description;
        try {
            description = getPluginDescription(file);
        } catch (InvalidDescriptionException ex) {
            throw new InvalidPluginException(ex);
        }
        checkNms(description, file);

        File dataFolder = new File(Bukkit.getServer().getPluginsFolder(), description.getName());
        if (dataFolder.exists() && !dataFolder.isDirectory()) {
            throw new InvalidPluginException(
                    "Projected datafolder: `" + dataFolder + "' for " + description.getName() + " exists and is not a directory");
        }
        try {
            OrigamiPluginClassLoader loader =
                    new OrigamiPluginClassLoader(description, dataFolder, file, ClassRemapper.NONE);
            return loader.getPlugin();
        } catch (IOException ex) {
            throw new InvalidPluginException(ex);
        }
    }

    private static void checkNms(PluginDescriptionFile description, File file) throws InvalidPluginException {
        String mode = Origami.config().nmsDetection;
        if (mode == null) {
            mode = "error";
        }
        if ("off".equalsIgnoreCase(mode)) {
            return;
        }
        Optional<NmsDetector.Finding> finding;
        try {
            finding = NmsDetector.scan(file);
        } catch (IOException ex) {
            throw new InvalidPluginException(ex);
        }
        if (finding.isEmpty()) {
            return;
        }
        String ref = finding.get().describe();
        if ("warn".equalsIgnoreCase(mode)) {
            Origami.LOGGER.warn(Lang.tr("log.loader.nms.warned", description.getName(), ref));
            return;
        }
        throw new InvalidPluginException(Lang.tr("log.loader.nms.rejected", description.getName(), ref));
    }

    @Override
    public PluginDescriptionFile getPluginDescription(File file) throws InvalidDescriptionException {
        try (JarFile jar = new JarFile(file)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                String message = jar.getJarEntry("paper-plugin.yml") != null
                        ? Lang.tr("log.loader.paper_plugin", file.getName())
                        : Lang.tr("log.loader.no_description", file.getName());
                throw new InvalidDescriptionException(message);
            }
            try (InputStream stream = jar.getInputStream(entry)) {
                return new PluginDescriptionFile(stream);
            }
        } catch (IOException | YAMLException ex) {
            throw new InvalidDescriptionException(ex);
        }
    }

    @Override
    public Pattern[] getPluginFileFilters() {
        return FILTERS.clone();
    }

    /** JavaPluginLoader と同じ登録方式。非推奨イベントの警告 (Server#getWarningState が必要) は未対応。 */
    @Override
    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(
            Listener listener, Plugin plugin) {
        Map<Class<? extends Event>, Set<RegisteredListener>> result = new HashMap<>();
        Set<Method> methods = new HashSet<>();
        try {
            for (Method m : listener.getClass().getMethods()) {
                methods.add(m);
            }
            for (Method m : listener.getClass().getDeclaredMethods()) {
                methods.add(m);
            }
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().severe("Plugin " + plugin.getDescription().getFullName()
                    + " has failed to register events for " + listener.getClass()
                    + " because " + e.getMessage() + " does not exist.");
            return result;
        }

        for (final Method method : methods) {
            final EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler == null || method.isBridge() || method.isSynthetic()) {
                continue;
            }
            final Class<?> checkClass;
            if (method.getParameterTypes().length != 1
                    || !Event.class.isAssignableFrom(checkClass = method.getParameterTypes()[0])) {
                plugin.getLogger().severe(plugin.getDescription().getFullName()
                        + " attempted to register an invalid EventHandler method signature \""
                        + method.toGenericString() + "\" in " + listener.getClass());
                continue;
            }
            final Class<? extends Event> eventClass = checkClass.asSubclass(Event.class);
            method.setAccessible(true);
            EventExecutor executor = (l, event) -> {
                try {
                    if (!eventClass.isAssignableFrom(event.getClass())) {
                        return;
                    }
                    method.invoke(l, event);
                } catch (InvocationTargetException ex) {
                    throw new EventException(ex.getCause());
                } catch (Throwable t) {
                    throw new EventException(t);
                }
            };
            result.computeIfAbsent(eventClass, k -> new HashSet<>())
                    .add(new RegisteredListener(listener, executor, handler.priority(), plugin, handler.ignoreCancelled()));
        }
        return result;
    }

    @Override
    public void enablePlugin(Plugin plugin) {
        if (!(plugin instanceof JavaPlugin javaPlugin)) {
            throw new IllegalArgumentException("Plugin is not associated with this PluginLoader");
        }
        if (plugin.isEnabled()) {
            return;
        }
        plugin.getLogger().info("Enabling " + plugin.getDescription().getFullName());
        try {
            javaPlugin.setEnabled(true);
        } catch (Throwable ex) {
            Bukkit.getServer().getLogger().log(Level.SEVERE, "Error occurred while enabling "
                    + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
            disablePlugin(plugin);
            return;
        }
        Bukkit.getPluginManager().callEvent(new PluginEnableEvent(plugin));
    }

    @Override
    public void disablePlugin(Plugin plugin) {
        if (!(plugin instanceof JavaPlugin javaPlugin)) {
            throw new IllegalArgumentException("Plugin is not associated with this PluginLoader");
        }
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getLogger().info("Disabling " + plugin.getDescription().getFullName());
        Bukkit.getPluginManager().callEvent(new PluginDisableEvent(plugin));
        try {
            javaPlugin.setEnabled(false);
        } catch (Throwable ex) {
            Bukkit.getServer().getLogger().log(Level.SEVERE, "Error occurred while disabling "
                    + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
        }
        ClassLoader cl = javaPlugin.getClass().getClassLoader();
        if (cl instanceof OrigamiPluginClassLoader loader) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // 無効化後の後始末なので無視する
            }
        }
    }
}
