package io.github.kiyo29.origami.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** MC 26.2 向けの実装。 */
final class McCompat262 implements McCompat {
    static final McCompat262 INSTANCE = new McCompat262();

    private McCompat262() { }

    @Override
    public long dayTime(ServerLevel level) {
        return level.getDefaultClockTime();
    }

    @Override
    public void setDayTime(ServerLevel level, long ticks) {
        level.dimensionType().defaultClock().ifPresent(clock -> level.clockManager().setTotalTicks(clock, ticks));
    }

    @Override
    public boolean isFlying(ServerPlayer player) {
        return player.getAbilities().flying;
    }

    @Override
    public void setFlying(ServerPlayer player, boolean flying) {
        player.getAbilities().flying = flying;
        player.onUpdateAbilities();
    }

    @Override
    public boolean mayFly(ServerPlayer player) {
        return player.getAbilities().mayfly;
    }

    @Override
    public void setMayFly(ServerPlayer player, boolean mayFly) {
        player.getAbilities().mayfly = mayFly;
        player.onUpdateAbilities();
    }

    @Override
    public int dataVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
    }

    @Override
    public int protocolVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().protocolVersion();
    }
}
