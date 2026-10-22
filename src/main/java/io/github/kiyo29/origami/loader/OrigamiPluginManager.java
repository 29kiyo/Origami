package io.github.kiyo29.origami.loader;

import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerEventException;
import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.bridge.UnimplementedCalls;
import io.github.kiyo29.origami.internal.Lang;
import io.papermc.paper.plugin.PermissionManager;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommandYamlParser;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.AuthorNagException;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.UnknownDependencyException;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;

/**
 * PluginManager の Origami 実装 (paper-api の SimplePluginManager は Paper 内部の実装へ委譲するため使えない)。
 * プラグイン管理・イベント・権限を自前で持つ。HandlerList / RegisteredListener は paper-api のものをそのまま使う。
 * plugin.yml の commands / permissions は enablePlugin / disablePlugin で登録・解除する。
 */
@SuppressWarnings({"deprecation", "removal"})
public final class OrigamiPluginManager implements PluginManager {
    private final Server server;
    private final SimpleCommandMap commandMap;
    private final OrigamiPluginLoader loader = new OrigamiPluginLoader();

    private final List<Plugin> plugins = new ArrayList<>();
    private final Map<String, Plugin> lookupNames = new HashMap<>();
    private final Map<String, Permission> permissions = new HashMap<>();
    private final Map<Boolean, Set<Permission>> defaultPerms = new LinkedHashMap<>();
    private final Map<String, Map<Permissible, Boolean>> permSubs = new HashMap<>();
    private final Map<Boolean, Map<Permissible, Boolean>> defSubs = new HashMap<>();

    public OrigamiPluginManager(Server server, SimpleCommandMap commandMap) {
        this.server = server;
        this.commandMap = commandMap;
        defaultPerms.put(true, new LinkedHashSet<>());
        defaultPerms.put(false, new LinkedHashSet<>());
    }

    public SimpleCommandMap commandMap() {
        return commandMap;
    }

    // ---- プラグイン管理 ----

    @Override
    public void registerInterface(Class<? extends PluginLoader> loaderClass) {
        throw UnimplementedCalls.exception("PluginManager#registerInterface(Class)");
    }

    @Override
    public synchronized Plugin getPlugin(String name) {
        return lookupNames.get(name.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public synchronized Plugin[] getPlugins() {
        return plugins.toArray(new Plugin[0]);
    }

    @Override
    public boolean isPluginEnabled(String name) {
        return isPluginEnabled(getPlugin(name));
    }

    @Override
    public synchronized boolean isPluginEnabled(Plugin plugin) {
        return plugin != null && plugins.contains(plugin) && plugin.isEnabled();
    }

    @Override
    public synchronized Plugin loadPlugin(File file)
            throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
        PluginDescriptionFile description = loader.getPluginDescription(file);
        if (lookupNames.containsKey(description.getName().toLowerCase(Locale.ENGLISH))) {
            throw new InvalidPluginException(Lang.tr("log.loader.reason.duplicate", description.getName()));
        }
        Plugin plugin = loader.loadPlugin(file);
        plugins.add(plugin);
        lookupNames.put(description.getName().toLowerCase(Locale.ENGLISH), plugin);
        for (String provided : description.getProvides()) {
            lookupNames.putIfAbsent(provided.toLowerCase(Locale.ENGLISH), plugin);
        }
        return plugin;
    }

    @Override
    public Plugin[] loadPlugins(File directory) {
        return loadPlugins(PluginScanner.jarsIn(directory));
    }

    /** 依存順に読み込み、全部読み込んだあとで onLoad を呼ぶ (Bukkit と同じ順序)。 */
    @Override
    public Plugin[] loadPlugins(File[] files) {
        List<PluginCandidate> candidates = PluginScanner.describe(files);
        Set<String> available;
        synchronized (this) {
            available = new HashSet<>(lookupNames.keySet());
        }
        DependencyResolver.Result result = DependencyResolver.resolve(candidates, available);
        result.rejected().forEach((c, reason) ->
                Origami.LOGGER.warn(Lang.tr("log.loader.rejected", c.description().getName(), reason)));

        List<Plugin> loaded = new ArrayList<>();
        for (PluginCandidate candidate : result.ordered()) {
            try {
                loaded.add(loadPlugin(candidate.file()));
            } catch (InvalidPluginException | InvalidDescriptionException | RuntimeException e) {
                String message = Lang.tr("log.loader.load_failed", candidate.file().getName(), String.valueOf(e.getMessage()));
                if (Origami.config().debug) {
                    Origami.LOGGER.error(message, e);
                } else {
                    Origami.LOGGER.error(message);
                }
            }
        }
        for (Plugin plugin : loaded) {
            try {
                plugin.onLoad();
            } catch (Throwable t) {
                Origami.LOGGER.error(Lang.tr("log.loader.onload_failed", plugin.getName()), t);
            }
        }
        return loaded.toArray(new Plugin[0]);
    }

    @Override
    public void enablePlugin(Plugin plugin) {
        if (plugin.isEnabled()) {
            return;
        }
        try {
            registerCommands(plugin);
            loader.enablePlugin(plugin);
        } catch (Throwable t) {
            server.getLogger().log(Level.SEVERE, "Error occurred while enabling "
                    + plugin.getDescription().getFullName() + " (Is it up to date?)", t);
        }
        if (plugin.isEnabled()) {
            registerPermissions(plugin);
        }
        HandlerList.bakeAll();
    }

    @Override
    public void disablePlugin(Plugin plugin) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            loader.disablePlugin(plugin);
        } catch (Throwable t) {
            server.getLogger().log(Level.SEVERE, "Error occurred while disabling "
                    + plugin.getDescription().getFullName() + " (Is it up to date?)", t);
        }
        try {
            Bukkit.getServicesManager().unregisterAll(plugin);
        } catch (Throwable t) {
            server.getLogger().log(Level.SEVERE, "Error occurred while unregistering services of "
                    + plugin.getDescription().getFullName(), t);
        }
        try {
            HandlerList.unregisterAll(plugin);
        } catch (Throwable t) {
            server.getLogger().log(Level.SEVERE, "Error occurred while unregistering events of "
                    + plugin.getDescription().getFullName(), t);
        }
        unregisterCommands(plugin);
        unregisterPermissions(plugin);
        try {
            io.github.kiyo29.origami.bridge.scheduler.OrigamiScheduler.get().cancelTasks(plugin);
        } catch (Throwable t) {
            server.getLogger().log(Level.SEVERE, "Error occurred while cancelling tasks of "
                    + plugin.getDescription().getFullName(), t);
        }
    }

    @Override
    public void disablePlugins() {
        Plugin[] all = getPlugins();
        for (int i = all.length - 1; i >= 0; i--) {
            disablePlugin(all[i]);
        }
    }

    @Override
    public void clearPlugins() {
        disablePlugins();
        synchronized (this) {
            plugins.clear();
            lookupNames.clear();
        }
        HandlerList.unregisterAll();
        clearPermissions();
    }

    @Override
    public synchronized boolean isTransitiveDependency(PluginMeta pluginMeta, PluginMeta dependencyConfig) {
        Set<String> targets = new HashSet<>();
        targets.add(dependencyConfig.getName().toLowerCase(Locale.ENGLISH));
        for (String provided : dependencyConfig.getProvidedPlugins()) {
            targets.add(provided.toLowerCase(Locale.ENGLISH));
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(pluginMeta.getName());
        while (!queue.isEmpty()) {
            String name = queue.poll().toLowerCase(Locale.ENGLISH);
            if (!visited.add(name)) {
                continue;
            }
            Plugin plugin = lookupNames.get(name);
            if (plugin == null) {
                continue;
            }
            PluginDescriptionFile description = plugin.getDescription();
            List<String> deps = new ArrayList<>(description.getPluginDependencies());
            deps.addAll(description.getPluginSoftDependencies());
            for (String dep : deps) {
                if (targets.contains(dep.toLowerCase(Locale.ENGLISH))) {
                    return true;
                }
                queue.add(dep);
            }
        }
        return false;
    }

    // ---- plugin.yml の commands / permissions ----

    /** Bukkit と同じく onEnable の前に plugin.yml の commands を登録する (onEnable で getCommand できるように)。 */
    private void registerCommands(Plugin plugin) {
        try {
            List<Command> commands = PluginCommandYamlParser.parse(plugin);
            if (!commands.isEmpty()) {
                commandMap.registerAll(plugin.getDescription().getName(), commands);
            }
        } catch (Throwable t) {
            Origami.LOGGER.error(Lang.tr("log.command.register_failed", plugin.getName()), t);
        }
    }

    /** そのプラグインのコマンドを commandMap から外す。Brigadier 側のノードは requires が false になって使えなくなる。 */
    private void unregisterCommands(Plugin plugin) {
        try {
            commandMap.getKnownCommands().values().removeIf(command -> {
                if (command instanceof PluginIdentifiableCommand owner && owner.getPlugin() == plugin) {
                    command.unregister(commandMap);
                    return true;
                }
                return false;
            });
        } catch (Throwable t) {
            server.getLogger().log(Level.SEVERE, "Error occurred while unregistering commands of "
                    + plugin.getDescription().getFullName(), t);
        }
    }

    /** CraftServer#loadPlugin 相当: 有効化後に plugin.yml の permissions を登録する。 */
    private void registerPermissions(Plugin plugin) {
        for (Permission perm : plugin.getDescription().getPermissions()) {
            try {
                addPermission(perm, false);
            } catch (IllegalArgumentException ex) {
                Origami.LOGGER.warn(Lang.tr("log.command.perm_duplicate", plugin.getName(), perm.getName()));
            }
        }
        dirtyPermissibles(true);
        dirtyPermissibles(false);
    }

    private void unregisterPermissions(Plugin plugin) {
        for (Permission perm : plugin.getDescription().getPermissions()) {
            defaultPerms.get(true).remove(perm);
            defaultPerms.get(false).remove(perm);
            removePermission(perm);
        }
        dirtyPermissibles(true);
        dirtyPermissibles(false);
    }

    // ---- イベント ----

    @Override
    public void callEvent(Event event) {
        if (event.isAsynchronous()) {
            if (Thread.holdsLock(this)) {
                throw new IllegalStateException(event.getEventName() + " cannot be triggered asynchronously from inside synchronized code.");
            }
            if (server.isPrimaryThread()) {
                throw new IllegalStateException(event.getEventName() + " cannot be triggered asynchronously from primary server thread.");
            }
        } else if (!server.isPrimaryThread()) {
            throw new IllegalStateException(event.getEventName() + " cannot be triggered asynchronously from another thread.");
        }
        fireEvent(event);
    }

    private void fireEvent(Event event) {
        RegisteredListener[] listeners = event.getHandlers().getRegisteredListeners();
        for (RegisteredListener registration : listeners) {
            if (!registration.getPlugin().isEnabled()) {
                continue;
            }
            try {
                registration.callEvent(event);
            } catch (AuthorNagException ex) {
                Plugin plugin = registration.getPlugin();
                if (plugin.isNaggable()) {
                    plugin.setNaggable(false);
                    server.getLogger().log(Level.SEVERE, String.format(
                            "Nag author(s): '%s' of '%s' about the following: %s",
                            plugin.getDescription().getAuthors(), plugin.getDescription().getFullName(), ex.getMessage()));
                }
            } catch (Throwable ex) {
                String msg = "Could not pass event " + event.getEventName() + " to "
                        + registration.getPlugin().getDescription().getFullName();
                server.getLogger().log(Level.SEVERE, msg, ex);
                if (!(event instanceof ServerExceptionEvent)) {
                    callEvent(new ServerExceptionEvent(new ServerEventException(
                            msg, ex, registration.getPlugin(), registration.getListener(), event)));
                }
            }
        }
    }

    @Override
    public void registerEvents(Listener listener, Plugin plugin) {
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register " + listener + " while not enabled");
        }
        for (Map.Entry<Class<? extends Event>, Set<RegisteredListener>> entry
                : loader.createRegisteredListeners(listener, plugin).entrySet()) {
            getEventListeners(getRegistrationClass(entry.getKey())).registerAll(entry.getValue());
        }
    }

    @Override
    public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority,
                              EventExecutor executor, Plugin plugin) {
        registerEvent(event, listener, priority, executor, plugin, false);
    }

    @Override
    public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority,
                              EventExecutor executor, Plugin plugin, boolean ignoreCancelled) {
        if (listener == null || priority == null || executor == null || plugin == null) {
            throw new IllegalArgumentException("listener, priority, executor and plugin must not be null");
        }
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register " + event + " while not enabled");
        }
        getEventListeners(event).register(new RegisteredListener(listener, executor, priority, plugin, ignoreCancelled));
    }

    private HandlerList getEventListeners(Class<? extends Event> type) {
        try {
            Method method = getRegistrationClass(type).getDeclaredMethod("getHandlerList");
            method.setAccessible(true);
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalAccessException("getHandlerList must be static");
            }
            return (HandlerList) method.invoke(null);
        } catch (Exception e) {
            throw new IllegalPluginAccessException("Error while registering listener for event type " + type + ": " + e);
        }
    }

    private Class<? extends Event> getRegistrationClass(Class<? extends Event> clazz) {
        try {
            clazz.getDeclaredMethod("getHandlerList");
            return clazz;
        } catch (NoSuchMethodException e) {
            Class<?> parent = clazz.getSuperclass();
            if (parent != null && !parent.equals(Event.class) && Event.class.isAssignableFrom(parent)) {
                return getRegistrationClass(parent.asSubclass(Event.class));
            }
            throw new IllegalPluginAccessException(
                    "Unable to find handler list for event " + clazz.getName() + ". Static getHandlerList method required!");
        }
    }

    // ---- 権限 (SimplePluginManager と同じ構造) ----

    @Override
    public Permission getPermission(String name) {
        return permissions.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void addPermission(Permission perm) {
        addPermission(perm, true);
    }

    private void addPermission(Permission perm, boolean dirty) {
        String name = perm.getName().toLowerCase(Locale.ROOT);
        if (permissions.containsKey(name)) {
            throw new IllegalArgumentException("The permission " + name + " is already defined!");
        }
        permissions.put(name, perm);
        calculatePermissionDefault(perm, dirty);
    }

    @Override
    public void addPermissions(List<Permission> perms) {
        for (Permission perm : perms) {
            addPermission(perm, false);
        }
        dirtyPermissibles(true);
        dirtyPermissibles(false);
    }

    @Override
    public Set<Permission> getDefaultPermissions(boolean op) {
        return Set.copyOf(defaultPerms.get(op));
    }

    @Override
    public void removePermission(Permission perm) {
        removePermission(perm.getName());
    }

    @Override
    public void removePermission(String name) {
        permissions.remove(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void recalculatePermissionDefaults(Permission perm) {
        if (perm != null && permissions.containsKey(perm.getName().toLowerCase(Locale.ROOT))) {
            defaultPerms.get(true).remove(perm);
            defaultPerms.get(false).remove(perm);
            calculatePermissionDefault(perm, true);
        }
    }

    private void calculatePermissionDefault(Permission perm, boolean dirty) {
        PermissionDefault def = perm.getDefault();
        if (def == PermissionDefault.OP || def == PermissionDefault.TRUE) {
            defaultPerms.get(true).add(perm);
            if (dirty) {
                dirtyPermissibles(true);
            }
        }
        if (def == PermissionDefault.NOT_OP || def == PermissionDefault.TRUE) {
            defaultPerms.get(false).add(perm);
            if (dirty) {
                dirtyPermissibles(false);
            }
        }
    }

    private void dirtyPermissibles(boolean op) {
        for (Permissible p : getDefaultPermSubscriptions(op)) {
            p.recalculatePermissions();
        }
    }

    @Override
    public void subscribeToPermission(String permission, Permissible permissible) {
        permSubs.computeIfAbsent(permission.toLowerCase(Locale.ROOT), k -> new WeakHashMap<>()).put(permissible, true);
    }

    @Override
    public void unsubscribeFromPermission(String permission, Permissible permissible) {
        String name = permission.toLowerCase(Locale.ROOT);
        Map<Permissible, Boolean> map = permSubs.get(name);
        if (map != null) {
            map.remove(permissible);
            if (map.isEmpty()) {
                permSubs.remove(name);
            }
        }
    }

    @Override
    public Set<Permissible> getPermissionSubscriptions(String permission) {
        Map<Permissible, Boolean> map = permSubs.get(permission.toLowerCase(Locale.ROOT));
        return map == null ? Set.of() : Set.copyOf(map.keySet());
    }

    @Override
    public void subscribeToDefaultPerms(boolean op, Permissible permissible) {
        defSubs.computeIfAbsent(op, k -> new WeakHashMap<>()).put(permissible, true);
    }

    @Override
    public void unsubscribeFromDefaultPerms(boolean op, Permissible permissible) {
        Map<Permissible, Boolean> map = defSubs.get(op);
        if (map != null) {
            map.remove(permissible);
            if (map.isEmpty()) {
                defSubs.remove(op);
            }
        }
    }

    @Override
    public Set<Permissible> getDefaultPermSubscriptions(boolean op) {
        Map<Permissible, Boolean> map = defSubs.get(op);
        return map == null ? Set.of() : Set.copyOf(map.keySet());
    }

    @Override
    public Set<Permission> getPermissions() {
        return new HashSet<>(permissions.values());
    }

    public void clearPermissions() {
        permissions.clear();
        defaultPerms.get(true).clear();
        defaultPerms.get(false).clear();
    }

    @Override
    public boolean useTimings() {
        return false;
    }

    @Override
    public void overridePermissionManager(Plugin plugin, PermissionManager permissionManager) {
        UnimplementedCalls.note("PluginManager#overridePermissionManager(Plugin,PermissionManager)");
    }
}
