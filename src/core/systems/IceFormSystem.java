package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 结冰系统（气象/水文）：暴露在空气中的表层 WATER 以低概率冻成 ICE。
 * 确定性：固定迭代序（按 x,z 列序，与原 topWaterY 扫描完全一致）、随机走 simStream 子流。
 *
 * SIM-PERF-4：原实现每 tick 遍历全部 SX×SZ 列并对每列从 y=0 扫到 y<SY 调 topWaterY 找最顶 WATER
 * （≈44 万次 getBlock/tick，是最大热点）。现直接复用 World.waterSurfaceCells 索引——它恰为
 * "每列最顶 WATER 且头顶为空气/越界"的格。由于 WaterFlowSystem 每 tick 已把水柱竖直沉降为连续块，
 * 每列至多一个水面格，故 waterSurfaceCells 的每列元素与原 topWaterY 命中格逐列对应。
 *
 * 零漂移要点：原实现按 (x,z) 列序、对"有表层水"的列各消费一次 rng.nextDouble()。本实现把
 * waterSurfaceCells 桶排到 int[SX][SZ]（每列取最大 y = 最顶水），再严格按 (x,z) 列序遍历、对每列
 * 当且仅当"头顶为空气"时消费一次 rng.nextDouble()——调用顺序、短路条件与原实现逐字节一致，
 * 故 rng 序列与演化结果不变（DETERMINISM 指纹不变）。
 */
public final class IceFormSystem implements System {
    @Override public String name() { return "iceform"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 桶排 waterSurfaceCells 到每列最顶水面 y（同列取最大 y，等价 topWaterY 的最顶 WATER）。
        int[][] surf = new int[w.SX][w.SZ];
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) surf[x][z] = -1;
        for (long key : w.waterSurfaceCells) {            // key 编码见 World.cellKey：x<<20 | y<<10 | z
            int x = (int) ((key >> 20) & 0x3FFL);
            int y = (int) ((key >> 10) & 0x3FFL);
            int z = (int) (key & 0x3FFL);
            if (y > surf[x][z]) surf[x][z] = y;           // 同列取最大 y（最顶水）
        }
        // 批⑤：结冰全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
        int remain = w.spreadHeadroom(Blocks.ICE.index);
        // 严格按 (x,z) 列序遍历，行为逻辑与原 topWaterY 扫描法完全一致。
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int top = surf[x][z];
                if (top < 0) continue;
                // 上方是空气 → 表层水可结冰（getBlock 短路：仅头顶为空气才消费 rng，与原实现同序）
                if (w.getBlock(x, top + 1, z) == Blocks.AIR.index
                        && rng.nextDouble() < 0.012 && remain > 0) {
                    w.setBlock(x, top, z, Blocks.ICE.index);
                    remain--;
                }
            }
    }
}
