package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;

/**
 * 水沉降（涌现系统样板）：WATER 下方为空则下落填充（稳定，不扩散成洪水）。
 * 演示“物质守恒的涌现流动”在 Java 版的确定性复现。
 */
public final class WaterFlowSystem implements System {
    @Override public String name() { return "water"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // PERF-SIM：共享空间索引直接取 WATER 体素（x,y,z 升序，等价于全量扫描）；
        // 快照后遍历，水落入正下方空腔（不靠 rng，仍确定）。
        for (int[] c : new ArrayList<>(w.cellsOfType(Blocks.WATER.index))) {
            int x = c[0], y = c[1], z = c[2];
            if (y < 1) continue;
            if (w.mat[x][y - 1][z] == Blocks.AIR.index) {
                w.setBlock(x, y - 1, z, Blocks.WATER.index);
                w.setBlock(x, y, z, Blocks.AIR.index);
            }
        }
    }
}
