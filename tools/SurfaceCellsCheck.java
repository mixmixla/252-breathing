import core.world.World;
import core.world.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * 地表索引等价探针（SIM-PERF-3）：跑代表性仿真 N tick，周期性对比 World.surfaceCells 与
 * "期望集 = {c ∈ nonAirCells : c 头顶是空气}" 做集合相等断言。
 * 若每检查点期望集 == 实际集恒成立 → surfaceCells 与 nonAirCells 的"头顶空气"定义逐格等价，
 * 即 PondSystem 用 surfaceCells 还原的每列最顶实心与优化前遍历 nonAirCells 完全等价。
 *
 * 用法：javac -cp out -d out tools/SurfaceCellsCheck.java && java -cp out SurfaceCellsCheck
 *
 * <p>⚠️ 批⑯修正：原用**裸 `new World(...)`** ⇒ 世界无系统（`World.systems` 空，仅 `Simulation.reg()` 填充）
 * ⇒ "N tick" 期间**世界一格没变**，本探针实际只测了"生成后一瞬"。改为真 `Simulation` 驱动，并补
 * 失败退出码（原来检出不一致也只打印、`exit 0` ⇒ 若接成门禁就是假绿）。
 */
public class SurfaceCellsCheck {
    public static void main(String[] a) {
        int SX = a.length > 0 ? Integer.parseInt(a[0]) : 96;
        int SY = a.length > 1 ? Integer.parseInt(a[1]) : 48;
        int SZ = a.length > 2 ? Integer.parseInt(a[2]) : 96;
        int TICKS = a.length > 3 ? Integer.parseInt(a[3]) : 600;
        long SEED = a.length > 4 ? Long.parseLong(a[4]) : 20260909L;
        int PERIOD = a.length > 5 ? Integer.parseInt(a[5]) : 25;

        World w = new core.sim.Simulation(SEED, SX, SY, SZ).world;
        int mismatches = 0, checked = 0;

        for (int t = 0; t < TICKS; t++) {
            w.tick();
            if (t % PERIOD == 0) {
                // 期望集：每列最顶非空气格（其上方为空气或越界）——与 surfaceCells 的硬定义一致。
                // 注：此定义比"nonAirCells 中头顶为空气的格"多包含"非空气顶到最顶层(y=SY-1)的列"，
                // 这是为保证 Pond 行为与原遍历 nonAirCells 逐字节等价（树冠可顶到顶层，否则会漏列、破坏 DETERMINISM）。
                Set<Long> expected = new HashSet<>();
                for (int[] c : w.nonAirCells()) {
                    int x = c[0], y = c[1], z = c[2];
                    if (y + 1 >= SY || w.mat[x][y + 1][z] == Blocks.AIR.index)
                        expected.add(World.cellKey(x, y, z));
                }
                Set<Long> actual = w.surfaceCells;
                checked++;
                if (!expected.equals(actual)) {
                    // 统计差集以便定位
                    Set<Long> onlyExpected = new HashSet<>(expected); onlyExpected.removeAll(actual);
                    Set<Long> onlyActual = new HashSet<>(actual); onlyActual.removeAll(expected);
                    if (mismatches < 5) {
                        System.out.println("MISMATCH t=" + t + " expected=" + expected.size()
                                + " actual=" + actual.size()
                                + " onlyExpected(size)=" + onlyExpected.size()
                                + " onlyActual(size)=" + onlyActual.size());
                    }
                    mismatches++;
                }
            }
        }
        System.out.println("checked=" + checked + " mismatches=" + mismatches
                + " -> " + (mismatches == 0 ? "SURFACE-CELLS EQUIVALENT" : "SURFACE-CELLS FAILED"));
        if (mismatches != 0) System.exit(1);
    }
}
