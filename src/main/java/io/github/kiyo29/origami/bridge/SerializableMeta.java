package io.github.kiyo29.origami.bridge;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/**
 * YAML carrier for ItemMeta. An ItemMeta Proxy class cannot carry @SerializableAs, so ItemStack#serialize
 * puts this wrapper under "meta" (alias "ItemMeta", Spigot compatible). On load, deserialize returns a real ItemMeta.
 */
@SerializableAs("ItemMeta")
public final class SerializableMeta implements ConfigurationSerializable {
    private final Map<String, Object> data;

    public SerializableMeta(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public Map<String, Object> serialize() {
        return data;
    }

    public static ItemMeta deserialize(Map<String, Object> map) {
        return ItemMetaBridge.fromMap(map);
    }
}
