// 仿真性能测速工具（无头，不需要 GPU/GL）
// 量化每 tick 平均耗时 + 每个系统的累计耗时，按耗时降序排 Top-N。
// 用法:
//   javac -cp out -d out tools/SimPerf.java
//   java  -cp out SimPerf [SX SY SZ TICKS SEED WARMUP TOPN]
//
// 零漂移纪律：计时只包裹 s.update(...) 的调用，不读取任何 RNG、不修改任何世界状态、
// 不进 hashState。计时本身不影响仿真随机序列（java.lang.System.nanoTime 不消耗 SeededRNG）。
// 复刻 World.tick() 的精确循环：tick++ → player.tick → 按固定序逐个 s.update(w, w.simStream(name))。
import core.sim.Simulation;
import core.systems.System;
import core.world.Player;
import core.world.World;

import java.lang.reflect.Field;
import java.util.*;

public class SimPerf {
    public static void main(String[] args) throws Exception {
        int SX = args.length > 0 ? Integer.parseInt(args[0]) : 96;
        int SY = args.length > 1 ? Integer.parseInt(args[1]) : 48;
        int SZ = args.length > 2 ? Integer.parseInt(args[2]) : 96;
        int TICKS = args.length > 3 ? Integer.parseInt(args[3]) : 600;
        long SEED = args.length > 4 ? Long.parseLong(args[4]) : 20260909L;
        int WARMUP = args.length > 5 ? Integer.parseInt(args[5]) : 30;
        int TOPN = args.length > 6 ? Integer.parseInt(args[6]) : 15;

        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        World w = sim.world;

        // 反射取出 World 的私有 systems 列表（只读，不修改任何生产状态）
        Field f = World.class.getDeclaredField("systems");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<System> systems = (List<System>) f.get(w);

        // 复刻 World.tick()：玩家照常驱动（与生产一致），系统逐个计时
        java.util.function.Consumer<Integer> drive = (t) -> {
            if (t % 7 == 0) w.player.setIntent(Player.Intent.repel());
            if (t % 3 == 0) w.player.setIntent(Player.Intent.move(1, 0));
            if (t % 11 == 0) w.player.setIntent(Player.Intent.move(0, 1));
        };

        // ---- 热身：跳过 JIT 冷启动 + 初始 transient ----
        for (int t = 0; t < WARMUP; t++) { drive.accept(t); w.tick(); }

        // ---- 正式测量 ----
        long[] sysNanos = new long[systems.size()];
        long tickTotal = 0;
        for (int t = 0; t < TICKS; t++) {
            drive.accept(t);
            long t0 = java.lang.System.nanoTime();
            // 复刻 World.tick() 内部循环
            w.tick++;
            if (w.player != null) w.player.tick(w);
            for (int i = 0; i < systems.size(); i++) {
                System s = systems.get(i);
                long s0 = java.lang.System.nanoTime();
                s.update(w, w.simStream(s.name()));
                sysNanos[i] += (java.lang.System.nanoTime() - s0);
            }
            tickTotal += (java.lang.System.nanoTime() - t0);
        }

        // ---- 汇总 ----
        double perTickMs = tickTotal / (double) TICKS / 1_000_000.0;
        java.lang.System.out.printf("=== SimPerf ===%n");
        java.lang.System.out.printf("world=%dx%dx%d ticks=%d (warmup=%d) seed=%d systems=%d%n",
                SX, SY, SZ, TICKS, WARMUP, SEED, systems.size());
        java.lang.System.out.printf("AVG tick = %.3f ms  (total %.1f ms over %d ticks)%n",
                perTickMs, tickTotal / 1_000_000.0, TICKS);

        // 每系统累计与平均
        double[] sysAvgMs = new double[systems.size()];
        for (int i = 0; i < systems.size(); i++)
            sysAvgMs[i] = sysNanos[i] / (double) TICKS / 1_000_000.0;

        Integer[] order = new Integer[systems.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(sysNanos[b], sysNanos[a]));

        java.lang.System.out.printf("%n--- Top-%d systems by total time (over %d ticks) ---%n",
                Math.min(TOPN, order.length), TICKS);
        java.lang.System.out.printf("%-4s %-18s %10s %10s %8s%n", "#", "SYSTEM", "total(ms)", "avg(ms)", "%tick");
        double shownPctSum = 0;
        for (int k = 0; k < Math.min(TOPN, order.length); k++) {
            int i = order[k];
            double pct = sysNanos[i] * 100.0 / tickTotal;
            shownPctSum += pct;
            java.lang.System.out.printf("%-4d %-18s %10.1f %10.4f %7.2f%%%n",
                    k + 1, systems.get(i).name(), sysNanos[i] / 1_000_000.0, sysAvgMs[i], pct);
        }
        // 累加所有系统耗时（含被排除在 TopN 之外的）
        long allSys = 0;
        for (long v : sysNanos) allSys += v;
        java.lang.System.out.printf("%naccounted(sys time / tick time) = %.1f%%%n",
                allSys * 100.0 / tickTotal);
        java.lang.System.out.printf("sum(Top%d avg ms) = %.4f ms of avg tick %.3f ms%n",
                Math.min(TOPN, order.length), shownPctSum * perTickMs / 100.0, perTickMs);
    }
}
