package io.github.kiyo29.origami.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Minecraft のバージョン差を隔離するインターフェース。
 * ブリッジ側は MC の時刻・能力 (Abilities) まわりを直接触らず、ここを経由する。
 * 26.3 以降で API が変わったら、別実装を追加して get() で切り替える (Phase 9)。
 */
public interface McCompat {
    /** そのワールドの既定クロックの時刻 (tick)。クロックが無いワールドは 0。 */
    long dayTime(ServerLevel level);

    boolean isFlying(ServerPlayer player);

    void setFlying(ServerPlayer player, boolean flying);

    boolean mayFly(ServerPlayer player);

    void setMayFly(ServerPlayer player, boolean mayFly);

    int dataVersion();

    int protocolVersion();

    static McCompat get() {
        return McCompat262.INSTANCE;
    }
}
