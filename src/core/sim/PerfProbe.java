package core.sim;

import core.world.World;
import java.util.Arrays;

/**
 * 引擎调参探针（非门禁，人工运行）：为 MAX_STEPS_PER_FRAME 与分块预算取实测依据。
 *
 * <p>运行：{@code java -cp out core.sim.PerfProbe}
 *
 * <p>回答两个问题：
 * <ol>
 *   <li><b>一 tick 到底多贵</b>（98 系统全开 + 160x112x160 世界）→ 决定单帧最多推几步；</li>
 *   <li><b>分块预算每档多贵</b>（4/8/16 块每帧）→ 决定流式加载的单帧尖峰。</li>
 * </ol>
 *
 * <p>测量纪律：先预热（JIT 稳态）再测；报中位数 + p95 + 最大值；用独立实例避免"后跑的更热"。
 */
public final class PerfProbe {

    public static void main(String[] args) {
        // 预热
        Simulation warm = new Simulation(20260917L, 160, 112, 160);
        warm.run(150);

        // ---------- ① 单 tick 成本（全 98 系统） ----------
        Simulation s = new Simulation(20260917L, 160, 112, 160);
        s.run(100);                                     // 预热到稳态
        int N = 300;
        double[] tickMs = new double[N];
        for (int i = 0; i < N; i++) {
            long t0 = java.lang.System.nanoTime();
            s.world.tick();
            tickMs[i] = (java.lang.System.nanoTime() - t0) / 1e6;
        }
        Arrays.sort(tickMs);
        double med = tickMs[N / 2], p95 = tickMs[(int) (N * 0.95)], max = tickMs[N - 1];
        java.lang.System.out.printf("PERF  world=%dx%dx%d systems=%d%n",
                s.world.SX, s.world.SY, s.world.SZ, s.world.registry.size());
        java.lang.System.out.printf("  tick  median=%6.2f ms  p95=%6.2f ms  max=%6.2f ms%n", med, p95, max);
        java.lang.System.out.printf("  60fps 预算 16.67ms -> 纯仿真可容 %.1f tick（p95）%n", 16.67 / p95);
        java.lang.System.out.printf("  假设渲染占 10ms -> 仿真预算 6.67ms -> 可容 %.1f tick（p95）%n", 6.67 / p95);
        for (int cap : new int[]{2, 3, 4, 5, 8}) {
            java.lang.System.out.printf("  MAX_STEPS_PER_FRAME=%d -> 单帧仿真最坏 %6.2f ms (p95) / %6.2f ms (max)%n",
                    cap, p95 * cap, max * cap);
        }

        // ---------- ② 分块预算成本 ----------
        for (int budget : new int[]{4, 8, 16}) {
            World w = new World(20260914L, 160, 112, 160);
            for (int i = 0; i < 120; i++) { w.streamTo(10 + i, 10); w.tick(); }
            double worst = 0, total = 0, worstCommit = 0;
            int frames = 0;
            for (int i = 0; i < 40; i++) {
                w.streamToSliced(5000 + i, 5000);
                int guard = 0;
                while (w.isShifting() && guard++ < 200000) {
                    long f0 = java.lang.System.nanoTime();
                    boolean done = w.stepShift(budget);
                    double ms = (java.lang.System.nanoTime() - f0) / 1e6;
                    if (ms > worst) worst = ms;
                    if (done && ms > worstCommit) worstCommit = ms;
                    total += ms;
                    frames++;
                }
            }
            java.lang.System.out.printf("  budget=%2d chunks/frame -> worst=%6.2f ms  commitWorst=%6.2f ms  frames/shift=%.1f%n",
                    budget, worst, worstCommit, frames / 40.0);
        }
    }
}
