package core.systems;

import core.rng.SeededRNG;
import core.world.World;

/**
 * 集市（64·market，文明/社会）：当 w.prosperity 达到更高阈值（>=5，区别于 TradeCaravan 的 >=2），
 * 以低概率记 "market"."open" 事件并写入“商队常态化往来”的村庄记忆，表现“集市常态化”而非偶发商队。
 * 与 TradeCaravanSystem 区分：本系统阈值更高、叙事是常态化集市、不重复其阈值逻辑。
 * 确定性：随机严格走 simStream 入参 rng。
 */
public final class MarketSystem implements System {
    @Override public String name() { return "market"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.prosperity < 5) return;
        if (rng.nextDouble() >= 0.03) return;
        int cx = w.SX / 2, cz = w.SZ / 2;
        int x = clamp(cx + rng.nextInt(cx) - cx / 2, w.SX);
        int z = clamp(cz + rng.nextInt(cz) - cz / 2, w.SZ);
        w.recordMemory("集市常态化：商队于 (" + x + "," + z + ") 频繁往来，繁荣=" + w.prosperity);
        w.log("market", "open", "x=" + x + ",z=" + z, "prosperity=" + w.prosperity);
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v >= max ? max - 1 : v);
    }
}
