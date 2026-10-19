package io.github.kiyo29.origami.loader;

import com.destroystokyo.paper.utils.PaperPluginLogger;
import io.github.kiyo29.origami.internal.JulToSlf4jHandler;
import io.github.kiyo29.origami.loader.remap.ClassRemapper;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import org.bukkit.Bukkit;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * プラグイン1つにつき1つのクラスローダー。JavaPlugin() コンストラクタが要求する
 * ConfiguredPluginClassLoader を実装する (Paper 内部の PluginClassLoader には依存しない)。
 *
 * 親はプラットフォームローダーだけにして、Fabric や他 Mod のクラスは見せない。
 * Origami 側 (paper-api など) のクラスは LoaderRules の許可リストに合致したものだけを委譲する。
 */
public final class OrigamiPluginClassLoader extends URLClassLoader implements ConfiguredPluginClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    /** 生きているプラグインローダー全部。他プラグインのクラス検索に使う (従来の Spigot と同じ全体検索)。 */
    private static final List<OrigamiPluginClassLoader> LOADERS = new CopyOnWriteArrayList<>();
    private static final ClassLoader ORIGAMI = OrigamiPluginClassLoader.class.getClassLoader();

    private final PluginDescriptionFile description;
    private final File dataFolder;
    private final File file;
    private final URL url;
    private final JarFile jar;
    private final ClassRemapper remapper;
    private final Logger logger;
    private volatile JavaPlugin plugin;
    private JavaPlugin pluginInit;

    public OrigamiPluginClassLoader(PluginDescriptionFile description, File dataFolder, File file,
                                    ClassRemapper remapper) throws IOException, InvalidPluginException {
        super(file.getName(), new URL[] {file.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
        this.description = description;
        this.dataFolder = dataFolder;
        this.file = file;
        this.url = file.toURI().toURL();
        this.remapper = remapper;
        this.jar = new JarFile(file);
        this.logger = createLogger(description);
        LOADERS.add(this);
        try {
            this.plugin = instantiate();
        } catch (InvalidPluginException | RuntimeException e) {
            closeQuietly();
            throw e;
        }
    }

    private JavaPlugin instantiate() throws InvalidPluginException {
        String main = description.getMain();
        try {
            Class<?> mainClass = Class.forName(main, true, this);
            Class<? extends JavaPlugin> pluginClass;
            try {
                pluginClass = mainClass.asSubclass(JavaPlugin.class);
            } catch (ClassCastException ex) {
                throw new InvalidPluginException("main class `" + main + "' must extend JavaPlugin", ex);
            }
            Constructor<? extends JavaPlugin> constructor;
            try {
                constructor = pluginClass.getDeclaredConstructor();
            } catch (NoSuchMethodException ex) {
                throw new InvalidPluginException("main class `" + main + "' must have a no-args constructor", ex);
            }
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ClassNotFoundException ex) {
            throw new InvalidPluginException("Cannot find main class `" + main + "'", ex);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException ex) {
            throw new InvalidPluginException("Cannot create main class `" + main + "'", ex);
        } catch (InvocationTargetException ex) {
            throw new InvalidPluginException("Exception initializing main class `" + main + "'", ex.getCause());
        } catch (LinkageError ex) {
            // NoClassDefFoundError / UnsupportedClassVersionError / ExceptionInInitializerError など
            throw new InvalidPluginException("Cannot load main class `" + main + "'", ex);
        }
    }

    private static Logger createLogger(PluginDescriptionFile description) {
        Logger l = PaperPluginLogger.getLogger((PluginMeta) description);
        boolean bridged = false;
        for (Handler h : l.getHandlers()) {
            if (h instanceof JulToSlf4jHandler) {
                bridged = true;
            }
        }
        if (!bridged) {
            l.setUseParentHandlers(false);
            l.addHandler(new JulToSlf4jHandler(LoggerFactory.getLogger(description.getName())));
        }
        return l;
    }

    // ---- ConfiguredPluginClassLoader ----

    @Override
    public PluginMeta getConfiguration() {
        return description;
    }

    @Override
    public synchronized void init(JavaPlugin javaPlugin) {
        if (javaPlugin == null) {
            throw new IllegalArgumentException("Initializing plugin cannot be null");
        }
        if (javaPlugin.getClass().getClassLoader() != this) {
            throw new IllegalArgumentException("Cannot initialize plugin outside of this class loader");
        }
        if (plugin != null || pluginInit != null) {
            throw new IllegalArgumentException("Plugin already initialized!");
        }
        pluginInit = javaPlugin;
        javaPlugin.init(Bukkit.getServer(), description, dataFolder, file, this, description, logger);
    }

    @Override
    public JavaPlugin getPlugin() {
        JavaPlugin p = plugin;
        return p != null ? p : pluginInit;
    }

    @Override
    public PluginClassLoaderGroup getGroup() {
        return null;
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries)
            throws ClassNotFoundException {
        return load(name, resolve, checkGlobal);
    }

    // ---- クラス検索 ----

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return load(name, resolve, true);
    }

    private Class<?> load(String name, boolean resolve, boolean checkGlobal) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = loadUncached(name, checkGlobal);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private Class<?> loadUncached(String name, boolean checkGlobal) throws ClassNotFoundException {
        if (LoaderRules.isDenied(name)) {
            throw new ClassNotFoundException(name);
        }
        if (LoaderRules.isAllowed(name)) {
            try {
                return ORIGAMI.loadClass(name);
            } catch (ClassNotFoundException ignored) {
                // 許可リストに合っても Origami 側に無ければ、プラグイン jar 側を探す
            }
        }
        try {
            return getParent().loadClass(name);
        } catch (ClassNotFoundException ignored) {
            // JDK のクラスではない
        }
        Class<?> own = findOwnClass(name);
        if (own != null) {
            return own;
        }
        if (checkGlobal) {
            for (OrigamiPluginClassLoader other : LOADERS) {
                if (other == this) {
                    continue;
                }
                Class<?> c = other.findOwnClass(name);
                if (c != null) {
                    return c;
                }
            }
        }
        throw new ClassNotFoundException(name);
    }

    /** この jar だけから探す。無ければ null。 */
    private Class<?> findOwnClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            String path = name.replace('.', '/').concat(".class");
            byte[] bytes;
            CodeSource source;
            try {
                JarEntry entry = jar.getJarEntry(path);
                if (entry == null) {
                    return null;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                source = new CodeSource(url, entry.getCodeSigners());
            } catch (IOException | IllegalStateException ex) {
                throw new ClassNotFoundException(name, ex);
            }
            bytes = remapper.remap(path, bytes);
            int dot = name.lastIndexOf('.');
            if (dot != -1) {
                String pkg = name.substring(0, dot);
                if (getDefinedPackage(pkg) == null) {
                    try {
                        definePackage(pkg, null, null, null, null, null, null, null);
                    } catch (IllegalArgumentException ignored) {
                        // 別スレッドが先に定義した
                    }
                }
            }
            return defineClass(name, bytes, 0, bytes.length, source);
        }
    }

    // ---- 終了処理 ----

    @Override
    public void close() throws IOException {
        LOADERS.remove(this);
        try {
            super.close();
        } finally {
            jar.close();
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
            // 失敗時の後始末なので無視する
        }
    }

    @Override
    public String toString() {
        return "OrigamiPluginClassLoader{" + file.getName() + '}';
    }
}
