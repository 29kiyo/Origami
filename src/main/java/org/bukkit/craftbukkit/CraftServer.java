package org.bukkit.craftbukkit;

/**
 * Empty marker class. Some plugins (EssentialsX ReflUtil) probe Class.forName("org.bukkit.craftbukkit.CraftServer")
 * to decide "this is a CraftBukkit based server of an unknown (newer) version".
 * It has no members and is not a Server. Only this exact class name is allowed through
 * loader/LoaderRules (CraftBukkit compat allow-list, see COMPATIBILITY.md).
 */
public final class CraftServer {
    private CraftServer() { }
}
