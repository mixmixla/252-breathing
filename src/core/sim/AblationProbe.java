package core.sim;

import core.world.World;

/**
 * 消融探针（非门禁，人工运行）：用「关掉一个系统 → 总 tick 时间少多少」直接归因。
 *
 * <p>运行：{@code java -cp out core.sim.AblationProbe}
 *
 * <p><b>为什么用消融而不是逐系统计时</b>：逐系统计时会受「同一 tick 内谁先调用 `cellsOfType`
 * 谁就先付索引同步的钱」这类<b>归因错位</b>影响（惰性索引的代价记在第一个消费者头上）。
 * 消融问的是另一个问题：「拿掉它，世界便宜多少」—— 这个问题没有归因歧义。
 *
 * <p><b>代价（诚实声明）</b>：关掉系统会改变世界演化轨迹，故 Δ 是「该系统的边际成本」的近似，
 * 不是同状态下的精确切片。但量级足够回答「谁是大头」。
 */
public final class AblationProbe {

    private static final int WARM = 200, MEASURE = 300;

    public static void main(String[] args) {
        String[] suspects = {"lava", "flood", "sand", "pond", "water", "ash",
                "snowcap", "flower", "biodiversity", "vine"};
        double base = run(null);
        java.lang.System.out.printf("ABLATION  world=160x112x160  warm=%d measure=%d%n", WARM, MEASURE);
        java.lang.System.out.printf("  baseline（全 98 系统）  %8.3f ms/tick%n", base);
        java.lang.System.out.printf("  %-16s %10s %10s%n", "关掉的系统", "ms/tick", "Δ (省下)");
        for (String name : suspects) {
            double t = run(name);
            java.lang.System.out.printf("  %-16s %10.3f %10.3f  (%.1f%%)%n",
                    name, t, base - t, 100.0 * (base - t) / base);
        }
        // 全关（空注册表）→ 地板成本
        double none = runAllOff();
        java.lang.System.out.printf("  %-16s %10.3f %10.3f%n", "(全部系统关掉)", none, base - none);
    }

    private static double run(String disable) {
        World w = new Simulation(20260917L, 160, 112, 160).world;
        if (disable != null) w.registry.disable(disable);
        for (int i = 0; i < WARM; i++) w.tick();
        long t0 = java.lang.System.nanoTime();
        for (int i = 0; i < MEASURE; i++) w.tick();
        return (java.lang.System.nanoTime() - t0) / 1e6 / MEASURE;
    }

    private static double runAllOff() {
        World w = new Simulation(20260917L, 160, 112, 160).world;
        for (String n : w.registry.names()) w.registry.disable(n);
        for (int i = 0; i < WARM; i++) w.tick();
        long t0 = java.lang.System.nanoTime();
        for (int i = 0; i < MEASURE; i++) w.tick();
        return (java.lang.System.nanoTime() - t0) / 1e6 / MEASURE;
    }
}
