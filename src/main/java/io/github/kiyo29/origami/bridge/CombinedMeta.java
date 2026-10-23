package io.github.kiyo29.origami.bridge;

import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;

/**
 * Damageable and Repairable declare clone() with different return types, so a Proxy cannot
 * implement both directly. This interface re-declares clone() with a type that satisfies both.
 */
public interface CombinedMeta extends ItemMeta, Damageable, Repairable {
    @Override
    CombinedMeta clone();
}
