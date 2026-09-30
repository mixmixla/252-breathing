import core.sim.Simulation;
import core.systems.System;
import core.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 逐系统成本探针（headless）：在**同一个已预热的世界**上依次单独计时每个系统的
 * {@code update()}，按 ms/次降序打印前 N 名。
 *
 * <p>为什么不是 AblationProbe：消融会改变世界轨迹，世界"更满/更空"的代价 ≫ 单系统成本（归因失真）。
 * 本探针直接计时。⚠️ 诚实声明：各系统在**同一个 w2** 上顺序计时 ⇒ 前者会改变后者所见的世界；
 * 但对"全窗扫描"型系统（成本 = O(volume)、与状态无关）排序足够可靠；用于**挑下一个目标**而非精确切片。
 *
 * 用法：{@code java -cp out;LIBS SystemCostProbe [SX SY SZ WARM MEASURE seed topN]}
 */
public final class SystemCostProbe {
    public static void main(String[] args) {
        int SX = a(args, 0, 160), SY = a(args, 1, 112), SZ = a(args, 2, 160);
        int WARM = a(args, 3, 180), MEASURE = a(args, 4, 40), TOP = a(args, 6, 22);
        long SEED = args.length > 5 ? Long.parseLong(args[5]) : 20260917L;

        World wTick = new Simulation(SEED, SX, SY, SZ).world;
        for (int i = 0; i < WARM; i++) wTick.tick();
        long t0 = java.lang.System.nanoTime();
        for (int i = 0; i < MEASURE; i++) wTick.tick();
        double base = (java.lang.System.nanoTime() - t0) / 1e6 / MEASURE;

        World w2 = new Simulation(SEED, SX, SY, SZ).world;
        for (int i = 0; i < WARM; i++) w2.tick();

        List<double[]> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (System s : w2.registry.ordered()) {
            long ts = java.lang.System.nanoTime();
            for (int i = 0; i < MEASURE; i++) s.update(w2, w2.simStream(s.name() + "#probe"));
            double ms = (java.lang.System.nanoTime() - ts) / 1e6 / MEASURE;
            rows.add(new double[]{ms});
            names.add(s.name());
        }
        Integer[] idx = new Integer[rows.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, Comparator.comparingDouble(i -> -rows.get(i)[0]));

        java.lang.System.out.printf("SYSCOST world=%dx%dx%d warm=%d measure=%d%n", SX, SY, SZ, WARM, MEASURE);
        java.lang.System.out.printf("  full tick = %.3f ms   (systems=%d)%n", base, rows.size());
        java.lang.System.out.printf("  %-16s %10s %8s%n", "system", "ms/次", "%tick");
        double sum = 0;
        for (double[] r : rows) sum += r[0];
        int shown = 0;
        for (int k = 0; k < idx.length && shown < TOP; k++, shown++) {
            int i = idx[k];
            java.lang.System.out.printf("  %-16s %10.4f %7.1f%%%n", names.get(i), rows.get(i)[0], 100.0 * rows.get(i)[0] / base);
        }
        java.lang.System.out.printf("  (Σ 逐系统 = %.3f ms = %.1f%% of tick；余下为 tick 框架/索引同步开销)%n", sum, 100.0 * sum / base);
    }

    private static int a(String[] x, int i, int d) { return x.length > i ? Integer.parseInt(x[i]) : d; }
}
