package io.github.kiyo29.origami.bridge.scheduler;

import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.internal.Lang;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

/**
 * BukkitScheduler implementation driven by ServerTickEvents.START_SERVER_TICK (Fabric API, no Mixin).
 *
 * <p>Sync tasks run on the server thread inside {@link #heartbeat()}. Delayed / repeating async tasks are
 * also queued by tick and then handed to a cached thread pool. Async tasks with no delay and no period
 * run on the pool immediately.
 *
 * <p>Tick rules: a task scheduled with delay d (d &lt;= 1) first runs on the next heartbeat, delay d runs
 * on heartbeat (current + d). A repeating task is queued again at (tick it ran + period).
 * A task scheduled while a heartbeat is running never runs in that same heartbeat.
 *
 * <p>There is one instance per JVM. {@link #start()} / {@link #stop()} are called on server start / stop.
 */
public final class OrigamiScheduler implements BukkitScheduler {
    private static final long NO_REPEATING = -1L;
    private static final long STOP_TIMEOUT_SECONDS = 5L;
    private static final OrigamiScheduler INSTANCE = new OrigamiScheduler();

    private final Object lock = new Object();
    // Tasks waiting for their tick. Guarded by lock. Cancelled tasks are skipped lazily when popped.
    private final PriorityQueue<OrigamiTask> queue = new PriorityQueue<>(
            Comparator.comparingLong((OrigamiTask t) -> t.nextRun).thenComparingInt(t -> t.id));
    // Every live task (queued, running, or repeating). Removed on cancel or after a one-shot run.
    private final Map<Integer, OrigamiTask> tasks = new ConcurrentHashMap<>();
    private final List<Worker> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger(1);
    private final AtomicInteger threadCounter = new AtomicInteger();
    private long currentTick; // guarded by lock
    private ExecutorService pool; // guarded by this

    private OrigamiScheduler() {
    }

    public static OrigamiScheduler get() {
        return INSTANCE;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /** Called at SERVER_STARTING, before plugins are loaded. */
    public void start() {
        clearAll();
    }

    /** Called at SERVER_STOPPED. Drops every task and stops the async pool. */
    public void stop() {
        clearAll();
        ExecutorService ex;
        synchronized (this) {
            ex = pool;
            pool = null;
        }
        if (ex == null) {
            return;
        }
        ex.shutdown();
        try {
            if (!ex.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Origami.LOGGER.warn(Lang.tr("log.scheduler.stop_timeout", String.valueOf(STOP_TIMEOUT_SECONDS)));
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void clearAll() {
        synchronized (lock) {
            queue.clear();
            currentTick = 0L;
        }
        for (OrigamiTask t : tasks.values()) {
            t.cancelled = true;
        }
        tasks.clear();
    }

    /** One server tick. Must be called on the server thread. */
    public void heartbeat() {
        List<OrigamiTask> due = new ArrayList<>();
        long now;
        synchronized (lock) {
            now = ++currentTick;
            while (true) {
                OrigamiTask t = queue.peek();
                if (t == null || t.nextRun > now) {
                    break;
                }
                queue.poll();
                t.queued = false;
                if (!t.cancelled) {
                    due.add(t);
                }
            }
        }
        for (OrigamiTask t : due) {
            if (t.cancelled) {
                continue; // cancelled by an earlier task in this tick
            }
            if (t.sync) {
                execute(t);
            } else {
                submitAsync(t);
            }
            if (t.repeating() && !t.cancelled) {
                synchronized (lock) {
                    t.nextRun = now + t.period;
                    t.queued = true;
                    queue.add(t);
                }
            }
        }
    }

    // ---- internals -------------------------------------------------------------------------------

    private static void validate(Plugin plugin, Object task) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
        }
    }

    private static Consumer<OrigamiTask> wrap(Runnable r) {
        return r == null ? null : t -> r.run();
    }

    private static Consumer<OrigamiTask> wrapConsumer(Consumer<? super BukkitTask> c) {
        return c == null ? null : t -> c.accept(t);
    }

    private OrigamiTask schedule(Plugin plugin, Consumer<OrigamiTask> body, boolean sync, long delay, long period) {
        validate(plugin, body);
        long p = period == 0L ? 1L : (period < NO_REPEATING ? NO_REPEATING : period);
        long d = Math.max(delay, 0L);
        OrigamiTask task = new OrigamiTask(this, ids.getAndIncrement(), plugin, body, sync, p);
        tasks.put(task.id, task);
        if (!sync && d == 0L && p == NO_REPEATING) {
            submitAsync(task);
            return task;
        }
        synchronized (lock) {
            task.nextRun = currentTick + Math.max(d, 1L);
            task.queued = true;
            queue.add(task);
        }
        return task;
    }

    /** Runs the body on the current thread. Exceptions are logged and never propagate. */
    private void execute(OrigamiTask t) {
        t.running.incrementAndGet();
        try {
            t.body.accept(t);
        } catch (Throwable e) {
            logFailure(t, e);
        } finally {
            t.running.decrementAndGet();
            if (!t.repeating()) {
                tasks.remove(t.id, t);
            }
        }
    }

    private void submitAsync(OrigamiTask t) {
        try {
            executor().execute(() -> {
                if (t.cancelled) {
                    return;
                }
                Worker w = new Worker(t, Thread.currentThread());
                workers.add(w);
                try {
                    execute(t);
                } finally {
                    workers.remove(w);
                }
            });
        } catch (RejectedExecutionException e) {
            t.cancelled = true;
            tasks.remove(t.id, t);
        }
    }

    private synchronized ExecutorService executor() {
        if (pool == null) {
            pool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                Thread th = new Thread(r, "Origami Scheduler Thread - " + threadCounter.incrementAndGet());
                th.setDaemon(true);
                return th;
            });
        }
        return pool;
    }

    private void cancel0(OrigamiTask t) {
        t.cancelled = true;
        tasks.remove(t.id, t);
    }

    private static void logFailure(OrigamiTask t, Throwable e) {
        try {
            t.plugin.getLogger().log(Level.WARNING,
                    "Task #" + t.id + " for " + t.plugin.getDescription().getFullName() + " generated an exception", e);
        } catch (Throwable ignored) {
            Origami.LOGGER.warn("Task #{} generated an exception", t.id, e);
        }
    }

    // ---- BukkitScheduler: legacy int-returning API -----------------------------------------------

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        return schedule(plugin, wrap(task), true, delay, NO_REPEATING).id;
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task, long delay) {
        return scheduleSyncDelayedTask(plugin, (Runnable) task, delay);
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, Runnable task) {
        return schedule(plugin, wrap(task), true, 0L, NO_REPEATING).id;
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, BukkitRunnable task) {
        return scheduleSyncDelayedTask(plugin, (Runnable) task);
    }

    @Override
    public int scheduleSyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
        return schedule(plugin, wrap(task), true, delay, period).id;
    }

    @Override
    public int scheduleSyncRepeatingTask(Plugin plugin, BukkitRunnable task, long delay, long period) {
        return scheduleSyncRepeatingTask(plugin, (Runnable) task, delay, period);
    }

    @Override
    public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        return schedule(plugin, wrap(task), false, delay, NO_REPEATING).id;
    }

    @Override
    public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task) {
        return schedule(plugin, wrap(task), false, 0L, NO_REPEATING).id;
    }

    @Override
    public int scheduleAsyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
        return schedule(plugin, wrap(task), false, delay, period).id;
    }

    @Override
    public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task) {
        validate(plugin, task);
        FutureTask<T> future = new FutureTask<>(task);
        schedule(plugin, t -> future.run(), true, 0L, NO_REPEATING);
        return future;
    }

    // ---- BukkitScheduler: cancel / query ---------------------------------------------------------

    @Override
    public void cancelTask(int taskId) {
        if (taskId <= 0) {
            return;
        }
        OrigamiTask t = tasks.get(taskId);
        if (t != null) {
            cancel0(t);
        }
    }

    @Override
    public void cancelTasks(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Cannot cancel tasks of null plugin");
        }
        for (OrigamiTask t : tasks.values()) {
            if (plugin.equals(t.plugin)) {
                cancel0(t);
            }
        }
    }

    @Override
    public boolean isCurrentlyRunning(int taskId) {
        OrigamiTask t = tasks.get(taskId);
        return t != null && t.running.get() > 0;
    }

    @Override
    public boolean isQueued(int taskId) {
        OrigamiTask t = tasks.get(taskId);
        return t != null && !t.cancelled && t.queued;
    }

    @Override
    public List<BukkitWorker> getActiveWorkers() {
        return new ArrayList<>(workers);
    }

    @Override
    public List<BukkitTask> getPendingTasks() {
        List<OrigamiTask> live = new ArrayList<>();
        for (OrigamiTask t : tasks.values()) {
            if (!t.cancelled) {
                live.add(t);
            }
        }
        live.sort(Comparator.comparingInt(t -> t.id));
        return new ArrayList<BukkitTask>(live);
    }

    // ---- BukkitScheduler: runTask ----------------------------------------------------------------

    @Override
    public BukkitTask runTask(Plugin plugin, Runnable task) throws IllegalArgumentException {
        return schedule(plugin, wrap(task), true, 0L, NO_REPEATING);
    }

    @Override
    public void runTask(Plugin plugin, Consumer<? super BukkitTask> task) throws IllegalArgumentException {
        schedule(plugin, wrapConsumer(task), true, 0L, NO_REPEATING);
    }

    @Override
    public BukkitTask runTask(Plugin plugin, BukkitRunnable task) throws IllegalArgumentException {
        return runTask(plugin, (Runnable) task);
    }

    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) throws IllegalArgumentException {
        return schedule(plugin, wrap(task), false, 0L, NO_REPEATING);
    }

    @Override
    public void runTaskAsynchronously(Plugin plugin, Consumer<? super BukkitTask> task) throws IllegalArgumentException {
        schedule(plugin, wrapConsumer(task), false, 0L, NO_REPEATING);
    }

    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, BukkitRunnable task) throws IllegalArgumentException {
        return runTaskAsynchronously(plugin, (Runnable) task);
    }

    @Override
    public BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) throws IllegalArgumentException {
        return schedule(plugin, wrap(task), true, delay, NO_REPEATING);
    }

    @Override
    public void runTaskLater(Plugin plugin, Consumer<? super BukkitTask> task, long delay) throws IllegalArgumentException {
        schedule(plugin, wrapConsumer(task), true, delay, NO_REPEATING);
    }

    @Override
    public BukkitTask runTaskLater(Plugin plugin, BukkitRunnable task, long delay) throws IllegalArgumentException {
        return runTaskLater(plugin, (Runnable) task, delay);
    }

    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) throws IllegalArgumentException {
        return schedule(plugin, wrap(task), false, delay, NO_REPEATING);
    }

    @Override
    public void runTaskLaterAsynchronously(Plugin plugin, Consumer<? super BukkitTask> task, long delay)
            throws IllegalArgumentException {
        schedule(plugin, wrapConsumer(task), false, delay, NO_REPEATING);
    }

    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, BukkitRunnable task, long delay)
            throws IllegalArgumentException {
        return runTaskLaterAsynchronously(plugin, (Runnable) task, delay);
    }

    @Override
    public BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) throws IllegalArgumentException {
        return schedule(plugin, wrap(task), true, delay, period);
    }

    @Override
    public void runTaskTimer(Plugin plugin, Consumer<? super BukkitTask> task, long delay, long period)
            throws IllegalArgumentException {
        schedule(plugin, wrapConsumer(task), true, delay, period);
    }

    @Override
    public BukkitTask runTaskTimer(Plugin plugin, BukkitRunnable task, long delay, long period)
            throws IllegalArgumentException {
        return runTaskTimer(plugin, (Runnable) task, delay, period);
    }

    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period)
            throws IllegalArgumentException {
        return schedule(plugin, wrap(task), false, delay, period);
    }

    @Override
    public void runTaskTimerAsynchronously(Plugin plugin, Consumer<? super BukkitTask> task, long delay, long period)
            throws IllegalArgumentException {
        schedule(plugin, wrapConsumer(task), false, delay, period);
    }

    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, BukkitRunnable task, long delay, long period)
            throws IllegalArgumentException {
        return runTaskTimerAsynchronously(plugin, (Runnable) task, delay, period);
    }

    @Override
    public Executor getMainThreadExecutor(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        return command -> {
            if (command == null) {
                throw new IllegalArgumentException("Command cannot be null");
            }
            runTask(plugin, command);
        };
    }

    // ---- task / worker ---------------------------------------------------------------------------

    private static final class OrigamiTask implements BukkitTask {
        final OrigamiScheduler scheduler;
        final int id;
        final Plugin plugin;
        final Consumer<OrigamiTask> body;
        final boolean sync;
        final long period; // -1 = run once, > 0 = repeat every period ticks
        long nextRun; // guarded by scheduler.lock; only changed while the task is not in the queue
        volatile boolean queued;
        volatile boolean cancelled;
        final AtomicInteger running = new AtomicInteger();

        OrigamiTask(OrigamiScheduler scheduler, int id, Plugin plugin, Consumer<OrigamiTask> body,
                    boolean sync, long period) {
            this.scheduler = scheduler;
            this.id = id;
            this.plugin = plugin;
            this.body = body;
            this.sync = sync;
            this.period = period;
        }

        boolean repeating() {
            return period > 0L;
        }

        @Override
        public int getTaskId() {
            return id;
        }

        @Override
        public Plugin getOwner() {
            return plugin;
        }

        @Override
        public boolean isSync() {
            return sync;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            scheduler.cancel0(this);
        }
    }

    private record Worker(OrigamiTask task, Thread thread) implements BukkitWorker {
        @Override
        public int getTaskId() {
            return task.id;
        }

        @Override
        public Plugin getOwner() {
            return task.plugin;
        }

        @Override
        public Thread getThread() {
            return thread;
        }
    }
}
