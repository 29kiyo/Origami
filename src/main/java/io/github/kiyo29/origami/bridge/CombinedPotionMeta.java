package io.github.kiyo29.origami.bridge;

import org.bukkit.inventory.meta.PotionMeta;

/**
 * CombinedMeta + PotionMeta for the potion items only (so that swords and stones are not "instanceof PotionMeta").
 * clone() is re-declared with a type that satisfies every parent interface.
 */
public interface CombinedPotionMeta extends CombinedMeta, PotionMeta {
    @Override
    CombinedPotionMeta clone();
}
