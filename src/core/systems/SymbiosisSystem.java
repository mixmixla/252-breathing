package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 共生增益（62 · symbiosis，生物/种群）：WOOD 与 FLOWER 相邻时低概率(rng) mutual boost——
 * 树旁的花更高概率在邻格空腔长新 FLOWER（花增益），花旁的树更高概率在树顶空腔长新 LEAF（树增益），
 * 表现“树↔花”共生增益。与 FlowerSystem/PollinateSystem 区分：它们只要 GRASS 就长花/散花，
 * 无树条件；与 CanopySystem 区分：它只给 WOOD 长树冠，无花条件。本系统要求 WOOD 与 FLOWER 同时相邻才触发。
 * 随机严格走 simStream 入参 rng。
 */
public final class SymbiosisSystem implements System {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    @Override public String name() { return "symbiosis"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.WOOD.index) continue;
            // 树旁须有花（共生伙伴，含树顶一格高度）
            boolean flower = false;
            for (int k = 0; k < 4; k++) {
                if (w.getBlock(x + DX[k], y, z + DZ[k]) == Blocks.FLOWER.index
                        || w.getBlock(x + DX[k], y + 1, z + DZ[k]) == Blocks.FLOWER.index) {
                    flower = true; break;
                }
            }
            if (!flower) continue;
            boolean placed = false;
            // 树增益：树顶空腔长新叶（郁闭更盛）
            if (y + 1 < w.SY && w.mat[x][y + 1][z] == Blocks.AIR.index && rng.nextDouble() < 0.1) {
                w.setBlock(x, y + 1, z, Blocks.LEAF.index);
                placed = true;
            }
            // 花增益：邻格空腔长新花
            for (int k = 0; k < 4; k++) {
                int nx = x + DX[k], nz = z + DZ[k];
                if (w.inBounds(nx, y, nz) && w.mat[nx][y][nz] == Blocks.AIR.index
                        && rng.nextDouble() < 0.1) {
                    w.setBlock(nx, y, nz, Blocks.FLOWER.index);
                    placed = true;
                    break;
                }
            }
            if (placed) w.log("symbiosis", "boost", "x=" + x + ",z=" + z, "wood-flower");
        }
    }
}
