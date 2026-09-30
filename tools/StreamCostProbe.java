import core.sim.Simulation;
import core.systems.System;
import core.world.World;

import java.util.List;

/**
 * 子流派生成本探针：量化 tick 循环里"每 tick 为 99 个系统各派生一次 simStream()
 * （`deriveStream` 内含 SHA-256 + MessageDigest.getInstance + 字符串拼接）"的固定开销。
 *
 * 用法：{@code java -cp out;LIBS StreamCostProbe [N]}
 */
public final class StreamCostProbe {
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        World w = new Simulation(20260917L, 160, 112, 160).world;
        List<System> sys = w.registry.ordered();
        // 预热
        for (int k = 0; k < 200; k++) for (System s : sys) w.simStream(s.name());

        long t0 = java.lang.System.nanoTime();
        for (int k = 0; k < N; k++) for (System s : sys) w.simStream(s.name());
        double perTickMs = (java.lang.System.nanoTime() - t0) / 1e6 / N;
        java.lang.System.out.printf("STREAMPROBE systems=%d N=%d%n", sys.size(), N);
        java.lang.System.out.printf("  simStream×%d 每 tick = %.4f ms  (%.1f ns/系统)%n",
                sys.size(), perTickMs, 1e6 * perTickMs / sys.size());
    }
}
