package core.systems;

import core.rng.SeededRNG;
import core.world.World;

/**
 * 行会（71·guild，文明/社会）：当 w.prosperity 达到 >=5 时按低频 rng 记 "guild"."founded" event
 * + recordMemory（行会成立叙事）。仅叙事，不解锁蓝图（避免牵动 skills.json 既有条目）。
 * 与 TradeCaravanSystem（商队往来）区分：本系统是“社会组织成立”叙事；
 * 与 MarketSystem（集市常态化）区分：本系统讲行会结社而非集市场景。
 * 确定性：随机严格走 simStream 入参 rng。
 */
public final class GuildSystem implements System {
    @Override public String name() { return "guild"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.prosperity < 5) return;
        if (rng.nextDouble() >= 0.01) return;
        w.recordMemory("行会成立：匠人、商旅与守卫结社，共襄繁盛，繁荣=" + w.prosperity);
        w.log("guild", "founded", "prosperity=" + w.prosperity, "society");
    }
}
