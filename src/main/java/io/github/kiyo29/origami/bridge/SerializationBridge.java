package io.github.kiyo29.origami.bridge;

import org.bukkit.configuration.serialization.ConfigurationSerialization;

/** One-time registration of Origami's ConfigurationSerializable classes. */
public final class SerializationBridge {
    private SerializationBridge() { }

    public static void register() {
        try {
            ConfigurationSerialization.registerClass(SerializableMeta.class);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("Origami").warn("Could not register ItemMeta serialization", t);
        }
    }
}
