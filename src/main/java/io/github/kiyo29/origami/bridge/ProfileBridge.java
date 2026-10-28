package io.github.kiyo29.origami.bridge;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal PlayerProfile (Server#createProfile). Keeps id / name / properties only.
 * Not connected to Mojang's session server: complete() / update() do nothing. Textures are not supported.
 */
public final class ProfileBridge implements InvocationHandler {
    private UUID id;
    private String name;
    private final Map<String, ProfileProperty> props = new LinkedHashMap<>();

    private ProfileBridge(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    /** createProfile / createProfileExact: (UUID) / (String) / (UUID, String). */
    public static PlayerProfile create(Object[] args) {
        UUID id = null;
        String name = null;
        for (Object a : args) {
            if (a instanceof UUID u) {
                id = u;
            } else if (a instanceof String s) {
                name = s;
            }
        }
        return make(id, name);
    }

    private static PlayerProfile make(UUID id, String name) {
        return (PlayerProfile) Proxy.newProxyInstance(PlayerProfile.class.getClassLoader(),
                new Class<?>[] {PlayerProfile.class}, new ProfileBridge(id, name));
    }

    @Override
    public synchronized Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String n = method.getName();
        int c = method.getParameterCount();
        if (method.getDeclaringClass() == Object.class) {
            return switch (n) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "OrigamiPlayerProfile{" + id + "," + name + "}";
                default -> throw new UnsupportedOperationException(n);
            };
        }
        switch (n) {
            case "getName": return name;
            case "getId": return id;
            case "setName": { String old = name; name = (String) args[0]; return old; }
            case "setId": { UUID old = id; id = (UUID) args[0]; return old; }
            case "getProperties": return new LinkedHashSet<>(props.values());
            case "hasProperty": return props.containsKey((String) args[0]);
            case "setProperty": { ProfileProperty p = (ProfileProperty) args[0]; props.put(p.getName(), p); return null; }
            case "setProperties": {
                props.clear();
                for (ProfileProperty p : (Collection<ProfileProperty>) args[0]) {
                    props.put(p.getName(), p);
                }
                return null;
            }
            case "removeProperty":
                if (args[0] instanceof String s) {
                    return props.remove(s) != null;
                }
                break;
            case "clearProperties": props.clear(); return null;
            case "isComplete": return id != null && name != null;
            case "completeFromCache":
            case "complete": return false;
            case "update": return CompletableFuture.completedFuture((PlayerProfile) proxy);
            case "clone": {
                PlayerProfile copy = make(id, name);
                Set<ProfileProperty> all = new LinkedHashSet<>(props.values());
                copy.setProperties(all);
                return copy;
            }
            default: break;
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw UnimplementedCalls.exception(method);
    }
}
