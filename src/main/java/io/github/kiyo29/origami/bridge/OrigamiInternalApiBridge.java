package io.github.kiyo29.origami.bridge;

import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.LifecycleEventHandlerConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.BooleanSupplier;

/**
 * io.papermc.paper.InternalAPIBridge の実装。JavaPlugin のフィールド初期化が
 * InternalAPIBridge.get() (ServiceLoader) を呼ぶため、META-INF/services で登録する。
 * ServiceLoader の都合で public クラス + public な引数なしコンストラクタが必要。
 * 実装するメソッドだけここに書き、残りは生成スタブ (InternalApiBridgeStubs) が未実装として扱う。
 */
public final class OrigamiInternalApiBridge extends InternalApiBridgeStubs {
    public OrigamiInternalApiBridge() { }

    @Override
    public LifecycleEventManager<Plugin> createPluginLifecycleEventManager(
            JavaPlugin plugin, BooleanSupplier registrationCheck) {
        return new IgnoringLifecycleEventManager();
    }

    /**
     * Paper の Lifecycle イベント (Brigadier コマンド登録など) は未対応。
     * onEnable を壊さないよう、登録を警告つきで無視する。対応は Phase 4 以降。
     */
    private static final class IgnoringLifecycleEventManager implements LifecycleEventManager<Plugin> {
        @Override
        public void registerEventHandler(LifecycleEventHandlerConfiguration<? super Plugin> handlerConfiguration) {
            UnimplementedCalls.note("LifecycleEventManager#registerEventHandler(LifecycleEventHandlerConfiguration)");
        }
    }
}
