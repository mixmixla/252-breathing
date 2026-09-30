package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 种群增殖（56 · prey，生物/种群）：草原 GRASS 上方空腔以低概率(rng)浮现新的“兽群足迹”LEAF 标记，
 * 表示食草种群自然增殖。与 HerdSystem 区分：HerdSystem 只在“连片草原(≥6 GRASS 邻)"才记 memory+加 prosperity，
 * 本系统对普通草原低概率留稀疏足迹（不触发 memory、不加 prosperity），是更细粒度的种群波动。
 * 与 PredatorSystem 形成“增殖↔捕食”的足迹涨落对。随机严格走 simStream 入参 rng。
 */
public final class PreySystem implements System {
    @Override public String name() { return "prey"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (w.mat[x][top + 1][z] != Blocks.AIR.index) continue;  // 仅在空地留新足迹
            if (rng.nextDouble() < 0.02) {
                w.setBlock(x, top + 1, z, Blocks.LEAF.index);   // 新兽群足迹
                w.log("prey", "spawn", "x=" + x + ",z=" + z, "graze");
            }
        }
    }
}
