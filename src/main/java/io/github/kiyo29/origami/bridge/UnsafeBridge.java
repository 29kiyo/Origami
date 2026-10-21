package io.github.kiyo29.origami.bridge;

import org.bukkit.Material;
import io.github.kiyo29.origami.compat.McCompat;
import org.bukkit.UnsafeValues;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * org.bukkit.UnsafeValues の Proxy 実装。多くのプラグインが Bukkit.getUnsafe() を触るため、
 * 害の少ないものだけ実装し、残りは UnimplementedCalls に流す。レガシー Material の変換は恒等 (レガシーは非対応)。
 */
final class UnsafeBridge implements InvocationHandler {
    private static final Object UNHANDLED = new Object();
    private static final UnsafeValues PROXY = (UnsafeValues) Proxy.newProxyInstance(
            UnsafeValues.class.getClassLoader(), new Class<?>[] {UnsafeValues.class}, new UnsafeBridge());

    private UnsafeBridge() { }

    static UnsafeValues get() {
        return PROXY;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "OrigamiUnsafeValues";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        Object result = implemented(method, args);
        if (result != UNHANDLED) {
            return result;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw UnimplementedCalls.exception(method);
    }

    private Object implemented(Method method, Object[] args) {
        return switch (method.getName()) {
            case "checkSupported" -> null;
            case "processClass" -> args[2];
            case "isSupportedApiVersion" -> true;
            // 暫定。実際のレベル名は Phase 6 後半で確認する
            case "getDataVersion" -> McCompat.get().dataVersion();
            case "getProtocolVersion" -> McCompat.get().protocolVersion();
            case "getMainLevelName" -> "world";
            case "toLegacy", "fromLegacy" -> args.length == 1 && args[0] instanceof Material ? args[0] : UNHANDLED;
            case "getMaterial" -> args.length == 2 && args[0] instanceof String s ? Material.getMaterial(s) : UNHANDLED;
            default -> UNHANDLED;
        };
    }
}
