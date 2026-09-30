package core.systems;

import core.rng.SeededRNG;
import core.world.World;

/**
 * 贸易商队（经济/世界回响）：当 w.prosperity 达到阈值，按 simStream 在村庄附近抽样生成
 * "商队抵达"事件，写入 villageMemory 与事件日志——把繁荣数值与叙事连起来，且严格确定。
 */
public final class TradeCaravanSystem implements System {
    private int caravans = 0;   // 涌现计数（仅统计）

    @Override public String name() { return "caravan"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.prosperity < 2) return;
        if (rng.nextDouble() < 0.02) {
            int cx = w.SX / 2, cz = w.SZ / 2;
            int x = clamp(cx + rng.nextInt(cx) - cx / 2, w.SX);
            int z = clamp(cz + rng.nextInt(cz) - cz / 2, w.SZ);
            caravans++;
            w.recordMemory("caravan:" + x + "," + z + " 繁荣=" + w.prosperity);
            w.log("caravan", "arrive", "x=" + x + ",z=" + z, "prosperity=" + w.prosperity);
        }
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v >= max ? max - 1 : v);
    }
}
