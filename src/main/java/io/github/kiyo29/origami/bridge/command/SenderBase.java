package io.github.kiyo29.origami.bridge.command;

import io.github.kiyo29.origami.Origami;
import io.github.kiyo29.origami.bridge.OrigamiServer;
import io.github.kiyo29.origami.bridge.UnimplementedCalls;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.server.MinecraftServer;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.ServerOperator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * CommandSender / ConsoleCommandSender / Player の Proxy 用ハンドラの共通部分。
 * sendMessage 系、権限 (PermissibleBase へ委譲)、Object のメソッドを扱う。
 * 派生クラスの extra() が先に呼ばれ、扱わなければ UNHANDLED を返す。どれにも当たらない default メソッドは
 * 標準実装へ、それ以外は UnimplementedCalls (例外 + 初回警告 + 回数記録) になる。
 */
public abstract class SenderBase implements InvocationHandler {
    protected static final Object UNHANDLED = new Object();
    private static volatile boolean legacyWarned;

    private final ServerOperator operator = new ServerOperator() {
        @Override
        public boolean isOp() {
            return senderIsOp();
        }

        @Override
        public void setOp(boolean value) {
            senderSetOp(value);
        }
    };
    private PermissibleBase permissible;
    private boolean lastOp;

    protected abstract String senderName();

    protected abstract boolean senderIsOp();

    protected abstract void senderSetOp(boolean value);

    /** § 色コードつき (legacy) の文字列をこの送信先へ届ける。どのスレッドから呼ばれてもよい。 */
    protected abstract void deliver(String legacyMessage);

    protected Object extra(Object proxy, Method method, Object[] args) throws Throwable {
        return UNHANDLED;
    }

    /** サーバースレッドで実行する (すでにサーバースレッドならその場で実行)。 */
    protected static void onServerThread(Runnable task) {
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (mc == null) {
            return;
        }
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    /** PermissibleBase は初回に作る (コンストラクタ中は派生クラスのフィールドが未設定のため)。op が変わったら再計算する。 */
    private synchronized PermissibleBase permissible() {
        boolean op = senderIsOp();
        if (permissible == null) {
            permissible = new PermissibleBase(operator);
            lastOp = op;
        } else if (op != lastOp) {
            lastOp = op;
            permissible.recalculatePermissions();
        }
        return permissible;
    }

    @Override
    public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object extra = extra(proxy, method, args);
        if (extra != UNHANDLED) {
            return extra;
        }
        Class<?> owner = method.getDeclaringClass();
        if (owner == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> senderName();
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        if (owner == Permissible.class || owner == ServerOperator.class) {
            try {
                return method.invoke(permissible(), args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        Object handled = common(method, args);
        if (handled != UNHANDLED) {
            return handled;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw UnimplementedCalls.exception(method);
    }

    private Object common(Method method, Object[] args) {
        String name = method.getName();
        Class<?>[] types = method.getParameterTypes();
        int n = types.length;
        if ((name.equals("sendMessage") || name.equals("sendRawMessage")) && n >= 1) {
            Class<?> last = types[n - 1];
            Object value = args[n - 1];
            if (last == String.class) {
                send((String) value);
                return null;
            }
            if (last == String[].class) {
                if (value != null) {
                    for (String s : (String[]) value) {
                        send(s);
                    }
                }
                return null;
            }
            if (n == 1 && last == net.kyori.adventure.text.Component.class) {
                if (value != null) {
                    send(legacyOf((net.kyori.adventure.text.Component) value));
                }
                return null;
            }
            return UNHANDLED;
        }
        if (n == 0) {
            switch (name) {
                case "getServer":
                    return OrigamiServer.get().bukkit();
                case "getName":
                    return senderName();
                case "name":
                    return net.kyori.adventure.text.Component.text(senderName());
                case "isOp":
                    return permissible().isOp();
                default:
                    return UNHANDLED;
            }
        }
        if (n == 1 && name.equals("setOp")) {
            permissible().setOp((Boolean) args[0]);
            return null;
        }
        return UNHANDLED;
    }

    private void send(String message) {
        if (message != null) {
            deliver(message);
        }
    }

    private static String legacyOf(net.kyori.adventure.text.Component component) {
        try {
            return LegacyComponentSerializer.legacySection().serialize(component);
        } catch (LinkageError e) {
            if (!legacyWarned) {
                legacyWarned = true;
                Origami.LOGGER.warn("adventure legacy serializer is not available: {}", e.toString());
            }
            return String.valueOf(component);
        }
    }
}
