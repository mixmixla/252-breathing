package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 漫流系统（水文）：暴露在空气中的 WATER 向相邻有支撑的空腔以低概率横向漫延，盆地被填平。
 * 确定性：体素全扫描、固定迭代序（x,y,z）、随机走 simStream 子流。
 * 注：源水不消耗（泉涌式），仅作“世界涨落”演示；质量账本不强制守恒（与 Python 宽松守恒同构）。
 */
public final class FloodSystem implements System {
    @Override public String name() { return "flood"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // PERF-SIM：以 WATER 索引为工作集，按 (y,x,z) 升序处理；新生成的 WATER 若落在当前位置
        // “之后”（(y,x,z) 更大）则插入工作集继续处理——等价于全量坐标扫描的实时传播语义，
        // 且遍历代价与 WATER 体素数成正比而非与体积成正比。
        List<int[]> work = new ArrayList<>(w.cellsOfType(Blocks.WATER.index));
        work.sort(YXZ);
        for (int i = 0; i < work.size(); i++) {
            int[] c = work.get(i);
            int x = c[0], y = c[1], z = c[2];
            if (w.getBlock(x, y, z) != Blocks.WATER.index) continue;
            trySpread(w, rng, x - 1, y, z, work);
            trySpread(w, rng, x + 1, y, z, work);
            trySpread(w, rng, x, y, z - 1, work);
            trySpread(w, rng, x, y, z + 1, work);
        }
    }

    private static final Comparator<int[]> YXZ = (a, b) -> {
        if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
        if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
        return Integer.compare(a[2], b[2]);
    };

    private static void trySpread(World w, SeededRNG rng, int x, int y, int z, List<int[]> work) {
        if (!w.inBounds(x, y, z)) return;
        if (w.getBlock(x, y, z) != Blocks.AIR.index) return;
        // 下方需有支撑（不悬空成瀑布）
        if (!Blocks.byIndex(w.getBlock(x, y - 1, z)).solid) return;
        if (rng.nextDouble() < 0.05) {
            w.setBlock(x, y, z, Blocks.WATER.index);
            // 插入工作集（保持 (y,x,z) 升序）；落点在当前之后才会在本次遍历中被处理
            int[] cell = new int[]{x, y, z};
            int lo = 0, hi = work.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (YXZ.compare(work.get(mid), cell) < 0) lo = mid + 1; else hi = mid;
            }
            work.add(lo, cell);
        }
    }
}
