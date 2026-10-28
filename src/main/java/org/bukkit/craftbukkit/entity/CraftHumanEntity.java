package org.bukkit.craftbukkit.entity;

import org.bukkit.permissions.PermissibleBase;

/**
 * Marker class for plugin probes (LuckPerms looks up "entity.CraftHumanEntity" and its "perm" field).
 * Origami's Player implementation extends this class so that the field can be replaced reflectively.
 * Contains no Minecraft code. Allowed through LoaderRules as an exact-class exception.
 */
public abstract class CraftHumanEntity {
    /** Replaced by permission plugins (LuckPerms etc.) via reflection. */
    public PermissibleBase perm = new PermissibleBase(null);

    protected CraftHumanEntity() { }
}
