package io.github.kiyo29.origami.bridge.command;

import io.github.kiyo29.origami.Origami;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.bukkit.command.ConsoleCommandSender;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Pattern;

/**
 * ConsoleCommandSender の Proxy 実装。
 * source == null は本物のコンソール (JVM で1つ)。それ以外は rcon / コマンドブロック / エンティティ由来の
 * CommandSourceStack を包んだもので、出力はその source へ返す (Bukkit の BlockCommandSender 等の区別は未対応)。
 * コンソールは常に op 扱い。
 */
public final class ConsoleSenderBridge extends SenderBase {
    private static final Pattern LEGACY_CODES = Pattern.compile("(?i)\u00a7[0-9A-FK-ORX]");
    private static final Object LOCK = new Object();
    private static ConsoleCommandSender console;

    private final CommandSourceStack source;

    private ConsoleSenderBridge(CommandSourceStack source) {
        this.source = source;
    }

    public static ConsoleCommandSender console() {
        synchronized (LOCK) {
            if (console == null) {
                console = create(new ConsoleSenderBridge(null));
            }
            return console;
        }
    }

    public static ConsoleCommandSender forSource(CommandSourceStack source) {
        return create(new ConsoleSenderBridge(source));
    }

    private static ConsoleCommandSender create(ConsoleSenderBridge handler) {
        return (ConsoleCommandSender) Proxy.newProxyInstance(
                ConsoleCommandSender.class.getClassLoader(), new Class<?>[] {ConsoleCommandSender.class}, handler);
    }

    @Override
    protected String senderName() {
        return source == null ? "CONSOLE" : source.getTextName();
    }

    @Override
    protected boolean senderIsOp() {
        return true;
    }

    @Override
    protected void senderSetOp(boolean value) {
        // コンソールの op 状態は変えられない
    }

    @Override
    protected void deliver(String message) {
        if (source == null) {
            Origami.LOGGER.info(LEGACY_CODES.matcher(message).replaceAll(""));
        } else {
            onServerThread(() -> source.sendSystemMessage(Component.literal(message)));
        }
    }

    @Override
    protected Object extra(Object proxy, Method method, Object[] args) {
        if (method.getParameterCount() == 0 && method.getName().equals("isConversing")) {
            return false;
        }
        return UNHANDLED;
    }
}
