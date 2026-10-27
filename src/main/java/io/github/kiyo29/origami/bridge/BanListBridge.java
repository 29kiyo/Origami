package io.github.kiyo29.origami.bridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.IpBanList;
import net.minecraft.server.players.IpBanListEntry;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import org.bukkit.BanEntry;
import org.bukkit.BanList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * BanList (ProfileBanList / IpBanList) backed by MC's PlayerList#getBans() / getIpBans().
 * Proxy + handler. Entries are wrapped (MC entries are immutable): save() re-adds a new MC entry.
 * Partial: Profile entries' getBanTarget() (PlayerProfile) is not implemented; PlayerBanEvent etc. are not fired.
 */
public final class BanListBridge {
    private static volatile BanList<?> profileList;
    private static volatile BanList<?> ipList;

    private BanListBridge() { }

    public static BanList<?> profile() {
        BanList<?> l = profileList;
        if (l == null) {
            l = (BanList<?>) Proxy.newProxyInstance(BanList.class.getClassLoader(),
                    new Class<?>[] {org.bukkit.ban.ProfileBanList.class}, new Handler(false));
            profileList = l;
        }
        return l;
    }

    public static BanList<?> ip() {
        BanList<?> l = ipList;
        if (l == null) {
            l = (BanList<?>) Proxy.newProxyInstance(BanList.class.getClassLoader(),
                    new Class<?>[] {org.bukkit.ban.IpBanList.class}, new Handler(true));
            ipList = l;
        }
        return l;
    }

    public static BanList<?> of(BanList.Type type) {
        return type == BanList.Type.IP ? ip() : profile();
    }

    private static MinecraftServer need() {
        MinecraftServer mc = OrigamiServer.get().minecraft();
        if (mc == null || mc.getPlayerList() == null) {
            throw new IllegalStateException("Server is not running");
        }
        return mc;
    }

    private static Date expiry(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Date d) {
            return d;
        }
        if (o instanceof Instant i) {
            return Date.from(i);
        }
        if (o instanceof Duration d) {
            return new Date(System.currentTimeMillis() + d.toMillis());
        }
        throw new IllegalArgumentException("Unsupported expiry: " + o);
    }

    private static NameAndId resolve(MinecraftServer mc, String name) {
        ServerPlayer p = mc.getPlayerList().getPlayerByName(name);
        if (p != null) {
            return p.nameAndId();
        }
        for (UserBanListEntry e : mc.getPlayerList().getBans().getEntries()) {
            NameAndId u = e.getUser();
            if (u != null && u.name().equalsIgnoreCase(name)) {
                return u;
            }
        }
        return NameAndId.createOffline(name);
    }

    private static NameAndId userTarget(MinecraftServer mc, Object target) {
        if (target instanceof String s) {
            return resolve(mc, s);
        }
        if (target instanceof org.bukkit.profile.PlayerProfile pp) {
            if (pp.getUniqueId() != null) {
                String n = pp.getName() != null ? pp.getName() : pp.getUniqueId().toString();
                return new NameAndId(pp.getUniqueId(), n);
            }
            if (pp.getName() != null) {
                return resolve(mc, pp.getName());
            }
        }
        throw new IllegalArgumentException("Unsupported ban target: " + target);
    }

    private static String ipTarget(Object target) {
        if (target instanceof String s) {
            return s;
        }
        if (target instanceof InetAddress a) {
            return a.getHostAddress();
        }
        throw new IllegalArgumentException("Unsupported ban target: " + target);
    }

    @SuppressWarnings("rawtypes")
    private static final class Entry implements BanEntry {
        private final boolean ip;
        private final String target;
        private final NameAndId id;
        private Date created;
        private String source;
        private Date expires;
        private String reason;

        Entry(boolean ip, String target, NameAndId id, Date created, String source, Date expires, String reason) {
            this.ip = ip;
            this.target = target;
            this.id = id;
            this.created = created;
            this.source = source;
            this.expires = expires;
            this.reason = reason;
        }

        @Override
        public String getTarget() {
            return target;
        }

        @Override
        public Object getBanTarget() {
            if (ip) {
                try {
                    return InetAddress.getByName(target);
                } catch (UnknownHostException e) {
                    throw new IllegalStateException("Invalid IP: " + target, e);
                }
            }
            throw UnimplementedCalls.exception("BanEntry#getBanTarget() (profile)");
        }

        @Override
        public Date getCreated() {
            return created;
        }

        @Override
        public void setCreated(Date created) {
            this.created = created;
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setSource(String source) {
            this.source = source;
        }

        @Override
        public Date getExpiration() {
            return expires;
        }

        @Override
        public void setExpiration(Date expiration) {
            this.expires = expiration;
        }

        @Override
        public String getReason() {
            return reason;
        }

        @Override
        public void setReason(String reason) {
            this.reason = reason;
        }

        @Override
        public void save() {
            MinecraftServer mc = need();
            if (ip) {
                mc.getPlayerList().getIpBans().add(new IpBanListEntry(target, created, source, expires, reason));
            } else {
                mc.getPlayerList().getBans().add(new UserBanListEntry(id, created, source, expires, reason));
            }
        }

        @Override
        public void remove() {
            MinecraftServer mc = need();
            if (ip) {
                mc.getPlayerList().getIpBans().remove(target);
            } else {
                mc.getPlayerList().getBans().remove(id);
            }
        }
    }

    /** Returned by handlePlayer / handleOfflinePlayer when the method is not a ban method. */
    public static final Object NONE = new Object();

    /** Server#getBannedPlayers. */
    public static Set<org.bukkit.OfflinePlayer> bannedPlayers() {
        Set<org.bukkit.OfflinePlayer> out = new HashSet<>();
        for (UserBanListEntry e : need().getPlayerList().getBans().getEntries()) {
            NameAndId u = e.getUser();
            if (u != null) {
                out.add(OfflinePlayerBridge.of(u.id(), u.name()));
            }
        }
        return out;
    }

    /** Server#getIPBans. */
    public static Set<String> ipBans() {
        Set<String> out = new HashSet<>();
        for (IpBanListEntry e : need().getPlayerList().getIpBans().getEntries()) {
            if (e.getUser() != null) {
                out.add(e.getUser());
            }
        }
        return out;
    }

    /** Server#banIP (String or InetAddress). */
    public static void banIp(Object address) {
        need().getPlayerList().getIpBans().add(new IpBanListEntry(ipTarget(address)));
    }

    /** Server#unbanIP (String or InetAddress). */
    public static void unbanIp(Object address) {
        need().getPlayerList().getIpBans().remove(ipTarget(address));
    }

    private static NameAndId profileId(java.util.UUID id, String name) {
        return new NameAndId(id, name != null ? name : id.toString());
    }

    /** OfflinePlayer#isBanned / OfflinePlayer#ban (reason, expiry, source). */
    public static Object handleOfflinePlayer(java.util.UUID id, String name, Method method, Object[] args) {
        int n = args == null ? 0 : args.length;
        String m = method.getName();
        if (m.equals("isBanned") && n == 0) {
            return need().getPlayerList().getBans().isBanned(profileId(id, name));
        }
        Class<?>[] t = method.getParameterTypes();
        if (m.equals("ban") && (n == 3 || n == 4) && t[0] == String.class && t[2] == String.class) {
            UserBanListEntry e = new UserBanListEntry(profileId(id, name), null, (String) args[2], expiry(args[1]), (String) args[0]);
            need().getPlayerList().getBans().add(e);
            return wrap(e);
        }
        return NONE;
    }

    /** Player version of handleOfflinePlayer. ban(..., kickPlayer = true) also disconnects (approximation: the reason is the message). */
    public static Object handlePlayer(ServerPlayer p, Method method, Object[] args) {
        if (p == null) {
            return NONE;
        }
        Object r = handleOfflinePlayer(p.getUUID(), p.getName().getString(), method, args);
        if (r != NONE && method.getName().equals("ban") && args != null && args.length == 4 && Boolean.TRUE.equals(args[3])) {
            String reason = (String) args[0];
            p.connection.disconnect(net.minecraft.network.chat.Component.literal(reason == null ? "" : reason));
        }
        return r;
    }

    private static Entry wrap(UserBanListEntry e) {
        NameAndId u = e.getUser();
        return new Entry(false, u == null ? null : u.name(), u, e.getCreated(), e.getSource(), e.getExpires(), e.getReason());
    }

    private static Entry wrap(IpBanListEntry e) {
        return new Entry(true, e.getUser(), null, e.getCreated(), e.getSource(), e.getExpires(), e.getReason());
    }

    private record Handler(boolean ip) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] a) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == (a == null ? null : a[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "OrigamiBanList{" + (ip ? "IP" : "PROFILE") + "}";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            Object[] args = a == null ? new Object[0] : a;
            String name = method.getName();
            MinecraftServer mc = need();
            UserBanList users = mc.getPlayerList().getBans();
            IpBanList ips = mc.getPlayerList().getIpBans();
            switch (name) {
                case "addBan" -> {
                    if (args.length == 4) {
                        String reason = (String) args[1];
                        Date expires = expiry(args[2]);
                        String source = (String) args[3];
                        if (ip) {
                            IpBanListEntry e = new IpBanListEntry(ipTarget(args[0]), null, source, expires, reason);
                            ips.add(e);
                            return wrap(e);
                        }
                        UserBanListEntry e = new UserBanListEntry(userTarget(mc, args[0]), null, source, expires, reason);
                        users.add(e);
                        return wrap(e);
                    }
                }
                case "getBanEntry" -> {
                    if (args.length == 1) {
                        if (ip) {
                            IpBanListEntry e = ips.get(ipTarget(args[0]));
                            return e == null ? null : wrap(e);
                        }
                        UserBanListEntry e = users.get(userTarget(mc, args[0]));
                        return e == null ? null : wrap(e);
                    }
                }
                case "isBanned" -> {
                    if (args.length == 1) {
                        return ip ? ips.isBanned(ipTarget(args[0])) : users.isBanned(userTarget(mc, args[0]));
                    }
                }
                case "pardon" -> {
                    if (args.length == 1) {
                        if (ip) {
                            ips.remove(ipTarget(args[0]));
                        } else {
                            users.remove(userTarget(mc, args[0]));
                        }
                        return null;
                    }
                }
                case "getBanEntries", "getEntries" -> {
                    if (args.length == 0) {
                        Set<Object> out = new HashSet<>();
                        if (ip) {
                            for (IpBanListEntry e : ips.getEntries()) {
                                out.add(wrap(e));
                            }
                        } else {
                            for (UserBanListEntry e : users.getEntries()) {
                                out.add(wrap(e));
                            }
                        }
                        return out;
                    }
                }
                default -> { }
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, a);
            }
            throw UnimplementedCalls.exception(method);
        }
    }
}
