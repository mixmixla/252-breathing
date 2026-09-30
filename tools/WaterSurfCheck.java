import core.world.World;
import core.world.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * 水面索引等价探针（SIM-PERF-4）：跑代表性仿真 N tick，周期性对比 World.waterSurfaceCells 与
 * "期望集 = {c ∈ cellsOfType(WATER) : c 头顶是空气或越界}" 做集合相等断言。
 * 若每检查点期望集 == 实际集恒成立 → waterSurfaceCells 与"全水柱扫描取最顶水且头顶空气"逐格等价。
 *
 * 用法：javac -cp out -d out tools/WaterSurfCheck.java && java -cp out WaterSurfCheck
 *
 * <p>⚠️ 批⑯修正：原用**裸 `new World(...)`** ⇒ 世界无系统（`World.systems` 空，仅 `Simulation.reg()` 填充）
 * ⇒ "N tick" 期间**世界一格没变**，本探针实际只测了"生成后一瞬"（水面索引的**增量维护路径**从未被走到）。
 * 改为真 `Simulation` 驱动。
 */
public class WaterSurfCheck {
    public static void main(String[] a) {
        int SX = a.length > 0 ? Integer.parseInt(a[0]) : 96;
        int SY = a.length > 1 ? Integer.parseInt(a[1]) : 48;
        int SZ = a.length > 2 ? Integer.parseInt(a[2]) : 96;
        int TICKS = a.length > 3 ? Integer.parseInt(a[3]) : 600;
        long SEED = a.length > 4 ? Long.parseLong(a[4]) : 20260909L;
        int PERIOD = a.length > 5 ? Integer.parseInt(a[5]) : 10;

        World w = new core.sim.Simulation(SEED, SX, SY, SZ).world;
        int mismatches = 0, checked = 0;

        for (int t = 0; t < TICKS; t++) {
            w.tick();
            if (t % PERIOD == 0) {
                // 期望集：所有 WATER 中"头顶为空气或越界"的格——与 waterSurfaceCells 的硬定义一致。
                Set<Long> expected = new HashSet<>();
                for (int[] c : w.cellsOfType(Blocks.WATER.index)) {
                    int x = c[0], y = c[1], z = c[2];
                    if (y + 1 >= SY || w.mat[x][y + 1][z] == Blocks.AIR.index)
                        expected.add(World.cellKey(x, y, z));
                }
                Set<Long> actual = w.waterSurfaceCells;
                checked++;
                if (!expected.equals(actual)) {
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
                + " -> " + (mismatches == 0 ? "WATER-SURFACE EQUIVALENT" : "WATER-SURFACE FAILED"));
        if (mismatches != 0) System.exit(1);
    }
}
