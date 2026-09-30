package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 洼地成塘（水文）：地形凹陷处（四周地表明显高于本列）按确定性规则积水成 WATER 塘，
 * 与 WaterFlowSystem/FloodSystem 协同塑造静态水景。纯确定性（无 rng，固定迭代序）。
 */
public final class PondSystem implements System {
    // SIM-PERF-2：Pond 是幂等慢水文变量（只在盆地低洼处注入 WATER，不读任何跨 tick 累积状态、
    // 不使用 rng），其演化结果在稳定态下与执行频率无关。每 4 tick 重算一次即可把 41% 的仿真
    // 热点降到约 1/4，且视觉无差（首次 tick 即执行，水景初始即成形）。K 固定 → 同种子逐字节可复现。
    private int step = 0;
    private static final int K = 4;

    @Override public String name() { return "pond"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (step++ % K != 0) return;
        // SIM-PERF-5：原实现每个活跃 tick 都**重新从 surfaceCells 反推**每列最顶 solid
        // （`int[SX][SZ]` 缓冲 = 161 次分配 ≈100KB/活跃 tick + 一遍 surfaceCells 迭代 + 一遍初始化）。
        // 而 World 增量维护的 `surfaceY[x][z]` 定义**正是**「每列最顶 solid 格」
        // （见 World.recomputeColumn：自顶向下找第一个 solid）——两者恒等：
        // 顶实心格头顶必为空气/越界 → 必属 surfaceCells，而 surfaceCells 各格向下扫描取最大值
        // 也恰得该列最顶 solid。故直接读 surfaceY，**删缓冲与整个 pass-1**。
        // 邻居判定 / 阈值(higher>=5) / 填充规则(fillTo=min(minN,top+3)) **完全不变** → 写集不变
        // （DET 指纹即等价性门禁：pond 写 WATER 属 mat，行为一变指纹必变）。
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int top = w.surfaceY[x][z];
                if (top < 0) continue;                 // 无实心底 → 非洼地
                if (top + 1 >= w.SY) continue;
                int higher = 0, minN = w.SY;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = x + dx, nz = z + dz;
                        if (!w.inBounds(nx, 0, nz)) continue;
                        int nt = w.surfaceY[nx][nz];
                        if (nt < 0) continue;          // 邻格为空 → 不视为盆壁
                        if (nt > top) higher++;
                        if (nt < minN) minN = nt;
                    }
                if (higher >= 5) {                     // 至少 5/8 邻格更高 → 盆地
                    int fillTo = Math.min(minN, top + 3);   // 封顶，避免无限注水
                    for (int y = top + 1; y <= fillTo; y++)
                        if (w.mat[x][y][z] == Blocks.AIR.index)
                            w.setBlock(x, y, z, Blocks.WATER.index);
                }
            }
    }
}
