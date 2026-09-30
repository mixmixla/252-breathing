package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 蒸发系统（水文/气象）：暴露在空气中的表层 WATER 以极低概率蒸发为 AIR（烈日蒸干水洼）。
 * 与 FloodSystem 形成涨落平衡。确定性：逐列扫描、固定迭代序、随机走 simStream 子流。
 */
public final class DroughtSystem implements System {
    @Override public String name() { return "drought"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // PERF-SIM：用既有空间索引建"每列最顶水格"映射（精确等价 IceFormSystem.topWaterY），
        // 再按 (x,z) 序逐列判定，替代原全列扫描。
        java.util.List<int[]> water = new java.util.ArrayList<>(w.cellsOfType(Blocks.WATER.index));
        if (water.isEmpty()) return;   // 廉价早退：无水时本系统无任何作用，跳过 SX×SZ 建表与扫描（确定性强、等价）
        int[][] topWater = new int[w.SX][w.SZ];
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) topWater[x][z] = -1;
        for (int[] c : water) topWater[c[0]][c[2]] = c[1];   // 升序→末位=最顶水
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int ty = topWater[x][z];
                if (ty < 0) continue;
                if (w.getBlock(x, ty + 1, z) == Blocks.AIR.index
                        && rng.nextDouble() < 0.003) {
                    w.setBlock(x, ty, z, Blocks.AIR.index);
                }
            }
    }
}
