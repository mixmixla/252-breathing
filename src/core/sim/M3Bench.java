package core.sim;

import core.world.World;

/**
 * M3 性能基准（非门禁，人工运行）：对比 M3 前后的跨窗平移成本与分帧平滑度。
 * 运行：java -cp out core.sim.M3Bench
 *
 * 测量纪律（防 JIT 伪影）：
 *  - 预热 200 次平移后再测量（rebuildIndex 是长循环，需充分 JIT 才进入稳态）；
 *  - immediate 与 sliced 各自建独立 World 实例，避免「后跑的更热」偏袒；
 *  - 用中位数 + 最大值报告，剔除单次 GC 尖峰对结论的干扰。
 */
public final class M3Bench {
    public static void main(String[] args) throws Exception {
        warm();

        // ① 一次性同步平移（streamTo）成本：独立实例
        World wa = new World(20260914L, 160, 112, 160);
        for (int i = 0; i < 200; i++) { wa.streamTo(10 + i, 10); wa.tick(); }
        int N = 60;
        double[] imm = new double[N];
        for (int i = 0; i < N; i++) {
            long t0 = System.nanoTime();
            wa.streamTo(3000 + i, 3000);
            imm[i] = (System.nanoTime() - t0) / 1e6;
        }

        // ② 分帧平移：每帧预算 SHIFT_CHUNKS_PER_STEP 块，统计单帧最坏耗时；独立实例
        World wb = new World(20260914L, 160, 112, 160);
        for (int i = 0; i < 200; i++) { wb.streamTo(10 + i, 10); wb.tick(); }
        double worst = 0, total = 0, worstCommit = 0;
        int frames = 0;
        for (int i = 0; i < N; i++) {
            wb.streamToSliced(6000 + i, 6000);
            int guard = 0;
            while (wb.isShifting() && guard++ < 100000) {
                long f0 = System.nanoTime();
                boolean done = wb.stepShift(World.SHIFT_CHUNKS_PER_STEP);
                double ms = (System.nanoTime() - f0) / 1e6;
                if (ms > worst) worst = ms;
                if (done && ms > worstCommit) worstCommit = ms;
                total += ms; frames++;
            }
        }
        System.out.printf("M3BENCH  win=160x112x160 blocks=%d%n", wb.CX * wb.CZ);
        System.out.printf("  immediate(streamTo)     median = %7.2f ms   p95 = %7.2f ms%n", median(imm), p95(imm));
        System.out.printf("  sliced  per-frame worst = %7.2f ms   (avg %.2f ms over %d frames, budget=%d)%n",
                worst, total / Math.max(1, frames), frames, World.SHIFT_CHUNKS_PER_STEP);
        System.out.printf("  sliced  COMMIT-frame worst = %7.2f ms%n", worstCommit);
        System.out.printf("  smoothness gain = %.1fx  (immediate median / sliced worst)%n",
                median(imm) / Math.max(0.01, worst));
    }

    private static double median(double[] a) {
        double[] c = a.clone();
        java.util.Arrays.sort(c);
        return c[c.length / 2];
    }

    private static double p95(double[] a) {
        double[] c = a.clone();
        java.util.Arrays.sort(c);
        return c[Math.min(c.length - 1, (int) (c.length * 0.95))];
    }

    private static void warm() {
        World w = new World(1L, 160, 112, 160);
        for (int i = 0; i < 200; i++) { w.streamTo(10 + i, 10); w.tick(); }
    }
}
