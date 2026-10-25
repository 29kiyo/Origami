package io.github.kiyo29.origami.bridge.entity;

import net.minecraft.world.food.FoodData;

import java.lang.reflect.Field;

/**
 * FoodData has no public setter or getter for exhaustion (only addExhaustion),
 * so Bukkit's get/setExhaustion go through the private field "exhaustionLevel".
 * The field name was verified in the MC sources; if it is ever missing, the calls fail loudly.
 */
final class FoodAccess {
    private static final Field EXHAUSTION = find();

    private FoodAccess() { }

    private static Field find() {
        try {
            Field f = FoodData.class.getDeclaredField("exhaustionLevel");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static float get(FoodData data) {
        try {
            return EXHAUSTION.getFloat(data);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new UnsupportedOperationException("FoodData exhaustion is not accessible", e);
        }
    }

    static void set(FoodData data, float value) {
        try {
            EXHAUSTION.setFloat(data, Math.max(0.0F, Math.min(value, 40.0F)));
        } catch (IllegalAccessException | RuntimeException e) {
            throw new UnsupportedOperationException("FoodData exhaustion is not accessible", e);
        }
    }
}
