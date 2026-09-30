package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 生物多样性（生态涌现，独立于 TreeGrowthSystem）：维护实例计数统计当前 LEAF/WOOD 体量，
 * 并在空旷地表按"越稀越易种"的概率抽样播种新树——让森林有涨落而非单向疯长。
 * 用 simStream 抽样 + 概率随计数浮动，确定。
 */
public final class BiodiversitySystem implements System {
    private int leafWoodCount = 0;   // 稀疏扫描统计（仅影响播种概率）
    private int treesSeeded = 0;     // 涌现计数（仅统计）

    @Override public String name() { return "biodiversity"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 批⑨：原为 stride-3 三维扫描（(SX/3)·(SY/3)·(SZ/3) ≈ 10.4 万格/tick 读 mat）只为统计
        // "稀疏网格上的 LEAF/WOOD 数"。改为直接枚举 LEAF/WOOD 体素（copyOf 零分配平铺快照）并筛
        // x%3==0 && y%3==0 && z%3==0 —— 计数**恒等**（LEAF/WOOD 互斥；网格点恰为"三坐标皆 ≡0 (mod 3)"，
        // 迭代起点为 0 故无需处理负数）。迭代量 ~2.2 万（≈4.7× 更少），且**只读 LEAF/WOOD**。
        final int LEAF = Blocks.LEAF.index, WOOD = Blocks.WOOD.index;
        leafWoodCount = 0;
        final int nLeaf = w.countOf(LEAF), nWood = w.countOf(WOOD);
        int[] bl = new int[nLeaf * 3];
        if (nLeaf > 0) w.copyOf(LEAF, bl);
        for (int i = 0, b = 0; i < nLeaf; i++, b += 3)
            if ((bl[b] % 3) == 0 && (bl[b + 1] % 3) == 0 && (bl[b + 2] % 3) == 0) leafWoodCount++;
        int[] bw = new int[nWood * 3];
        if (nWood > 0) w.copyOf(WOOD, bw);
        for (int i = 0, b = 0; i < nWood; i++, b += 3)
            if ((bw[b] % 3) == 0 && (bw[b + 1] % 3) == 0 && (bw[b + 2] % 3) == 0) leafWoodCount++;

        int samples = 12;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = surfaceY(w, x, z);
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (top + 5 >= w.SY) continue;
            double p = leafWoodCount < 40 ? 0.02 : 0.002;   // 稀疏时更易补种
            if (rng.nextDouble() < p) {
                growTree(w, x, top, z);
                treesSeeded++;
            }
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }

    private static void growTree(World w, int x, int base, int z) {
        for (int t = 1; t <= 4; t++) w.setBlock(x, base + t, z, Blocks.WOOD.index);
        int ty = base + 5;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    int lx = x + dx, ly = ty + dy, lz = z + dz;
                    if (w.inBounds(lx, ly, lz) && w.mat[lx][ly][lz] == Blocks.AIR.index
                            && (Math.abs(dx) + Math.abs(dz) + Math.abs(dy)) <= 2)
                        w.setBlock(lx, ly, lz, Blocks.LEAF.index);
                }
    }
}
