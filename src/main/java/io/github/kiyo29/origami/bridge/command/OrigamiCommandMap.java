package io.github.kiyo29.origami.bridge.command;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.SimpleCommandMap;

import java.util.HashMap;
import java.util.List;

/**
 * SimpleCommandMap に「登録されたら Brigadier 側へ反映する」処理を足したもの。
 * プラグインが onEnable 中に getCommandMap().register(...) で動的に足したコマンドも拾うため、
 * 登録系の public メソッドをすべて上書きして CommandBridge.sync を呼ぶ (sync は冪等)。
 * コンストラクタ中に親クラスが register を呼ぶが、その時点では dispatcher が無いので sync は何もしない。
 */
public final class OrigamiCommandMap extends SimpleCommandMap {
    public OrigamiCommandMap(Server server) {
        super(server, new HashMap<>());
    }

    @Override
    public void registerAll(String fallbackPrefix, List<Command> commands) {
        super.registerAll(fallbackPrefix, commands);
        CommandBridge.sync(this);
    }

    @Override
    public boolean register(String fallbackPrefix, Command command) {
        boolean result = super.register(fallbackPrefix, command);
        CommandBridge.sync(this);
        return result;
    }

    @Override
    public boolean register(String label, String fallbackPrefix, Command command) {
        boolean result = super.register(label, fallbackPrefix, command);
        CommandBridge.sync(this);
        return result;
    }
}
