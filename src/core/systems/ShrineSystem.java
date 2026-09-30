package core.systems;

import core.rng.SeededRNG;
import core.world.Player;
import core.world.World;

/**
 * 祭坛系统（P2-B 塞尔达能力-探索闭环：“能力 → 探索 → 回报”）。
 *
 * 零漂移铁律（同 BeastSystem）：
 *  - 只经 {@link World#simStream(String)} 取随机（确定性子流）；
 *  - 祭坛是“实体”（存入 world.shrines），绝不写 mat/mass 网格，也不进 {@link World#hashState()} 指纹；
 *  - 授予能力只改 Player 实体（不进指纹）；邻近交互是玩家位置（确定性输入）的纯函数 → 同种子同输入逐字节一致。
 *
 * 行为：
 *  1) 确定性放置：首 tick（tick==1）用 simStream("shrine:place") 在世界中确定地散落 6 座祭坛，
 *     各自授予一种能力（GLIDE 缓降 / DASH 长冲 / BOMB 炸开方块 / FLINT 火种 / AQUA 引水 / THUNDER 引雷），
 *     立于地表上方；
 *  2) 邻近觉醒：玩家进入 3 格内且未觉醒 → 授予能力 + 奖励（魂 + 繁荣 + 世界记忆续篇）。
 */
public final class ShrineSystem implements System {

    private static final String[] ABILITY = {"GLIDE", "DASH", "BOMB", "FLINT", "AQUA", "THUNDER"};
    private static final String[] NAME    = {"Shrine of Glide", "Shrine of Dash", "Shrine of Bomb",
                                             "Shrine of Flame", "Shrine of Tides", "Shrine of Storms"};
    private static final float CLAIM_R2 = 9f;   // 3 格半径平方

    @Override
    public String name() { return "ShrineSystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 首 tick 确定性放置 3 座祭坛（仅一次；之后不再消耗 rng，保持确定性）
        if (w.shrines.isEmpty() && w.tick == 1) {
            SeededRNG pr = w.simStream("shrine:place");   // 仅此处取随机，基于主种子
            int cx0 = w.SX / 2, cz0 = w.SZ / 2;
            for (int i = 0; i < ABILITY.length; i++) {
                float ang = pr.nextFloat() * (float) (2.0 * Math.PI);
                float dist = 12f + pr.nextFloat() * 10f;  // 12~22 格，散布在出生点周围
                int sx = (int) Math.floor(cx0 + StrictMath.cos(ang) * dist);
                int sz = (int) Math.floor(cz0 + StrictMath.sin(ang) * dist);
                sx = Math.max(2, Math.min(w.SX - 3, sx));
                sz = Math.max(2, Math.min(w.SZ - 3, sz));
                int sy = w.surfaceY[sx][sz] + 1;
                w.shrines.add(new World.Shrine(sx + 0.5f, sy, sz + 0.5f, ABILITY[i], NAME[i]));
            }
        }
        Player p = w.player;
        if (p == null) return;
        for (World.Shrine s : w.shrines) {
            if (s.claimed) continue;
            float dx = p.x - s.x, dy = p.y - s.y, dz = p.z - s.z;
            if (dx * dx + dy * dy + dz * dz <= CLAIM_R2) {
                s.claimed = true;
                p.grantAbility(s.ability);
                p.setCheckpoint(s.x, s.y, s.z);   // DaS 篝火：祭坛兼作复活点并补满药瓶
                p.souls += 20;
                w.prosperity += 1;
                w.log("shrine", "claim", s.ability, "ok");
                w.recordMemory("你在「" + s.name + "」前觉醒了能力：" + s.ability);
            }
        }
    }
}
