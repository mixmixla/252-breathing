import core.sim.Simulation;

/**
 * 仿真 tick 成本探针（headless，无 GL）—— 回答两个问题：
 *
 * <ol>
 *   <li><b>一 tick 多贵</b>（median / p95 / p99 / max），以及有多少 tick 越过渲染层的仿真时间盒
 *       {@code Game.MAX_FRAME_SIM_MS = 12ms}（越过 ⇒ 那一帧停止推进 tick ⇒ 世界"限速"，表现是卡顿）。</li>
 *   <li><b>它随"世界年龄"增长吗</b>——按 tick 分段报均值。这是"玩久了越来越卡"的判据：
 *       若末段显著高于首段，说明某些系统的写入量单调增长（历史上熔岩/漫流两族被怀疑过）。</li>
 * </ol>
 *
 * <p><b>为什么要它</b>：用户报"帧数不稳定/走路一卡一卡"时，瓶颈可能在<b>仿真</b>而不在渲染。
 * 这条路径无法用 {@code -Dbw.snap} 测（那会冻结仿真），必须靠本探针。
 * 与 {@code why -Dbw.fpsdiag} 互补：fpsdiag 给"实机整帧"，本探针给"仿真那一块的分布与趋势"。</p>
 *
 * <p>运行：{@code java -cp "out;libs/*" TickCostProbe [ticks]}</p>
 */
public class TickCostProbe {

    public static void main(String[] args) {
        final int SX = 160, SY = 112, SZ = 160;          // 与 Game 的 new Simulation(seed,160,112,160) 同尺寸
        final int T = args.length > 0 ? Integer.parseInt(args[0]) : 6000;
        final long SEED = 20260928L;
        final int WARMUP = 60;                            // JIT 预热，不计入统计
        final double SIM_BOX_MS = 12.0;                   // Game.MAX_FRAME_SIM_MS（审计 C12 锁）

        System.out.println("=== TickCostProbe  world=" + SX + "x" + SY + "x" + SZ
                + "  ticks=" + T + "  seed=" + SEED + " ===");
        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        for (int i = 0; i < WARMUP; i++) sim.world.tick();

        // 逐系统耗时（只有 -Dbw.simdiag=1 时才真的在累计；见 core.systems.SimProfiler）
        core.systems.SimProfiler.reset();
        boolean prof = core.systems.SimProfiler.ON;
        // 首段 / 末段分别采样 —— 回答"谁随世界年龄变贵"（只看"谁最贵"会误判：最贵的 sand 是全图常量扫描）
        final int segN = Math.max(1, T / 10);      // 首/末段长度（与下面的趋势分段同口径，但名字避开重名）
        java.util.Map<String, long[]> firstSnap = null, lastSnap = null;
        int[] firstCounts = null, lastCounts = null;
        String firstMix = null, lastMix = null;

        double[] ms = new double[T];
        int over = 0;
        for (int t = 0; t < T; t++) {
            if (prof) {
                if (t == segN) {
                    firstSnap = core.systems.SimProfiler.snapshot();
                    firstMix = worldMix(sim.world);
                    firstCounts = countBlocks(sim.world);
                    core.systems.SimProfiler.reset();
                }
                if (t == T - segN) core.systems.SimProfiler.reset();
            }
            long t0 = System.nanoTime();
            sim.world.tick();
            ms[t] = (System.nanoTime() - t0) / 1000000.0;
            if (ms[t] >= SIM_BOX_MS) over++;
        }
        if (prof) { lastSnap = core.systems.SimProfiler.snapshot(); lastMix = worldMix(sim.world); }
        if (prof) lastCounts = countBlocks(sim.world);

        double[] sorted = ms.clone();
        java.util.Arrays.sort(sorted);
        double sum = 0; for (double v : ms) sum += v;
        System.out.printf(java.util.Locale.US,
                "tick ms   avg=%.3f  median=%.3f  p95=%.3f  p99=%.3f  max=%.3f%n",
                sum / T, q(sorted, 0.50), q(sorted, 0.95), q(sorted, 0.99), sorted[T - 1]);
        System.out.printf(java.util.Locale.US,
                "over %.0fms : %d / %d ticks (%.2f%%)   —— 越线即该帧停推进 tick%n",
                SIM_BOX_MS, over, T, 100.0 * over / T);

        // 世界年龄趋势：按 1/10 分段（首段 vs 末段是"玩久了变卡"的直接判据）
        int seg = Math.max(1, T / 10);
        double firstSum = 0, lastSum = 0; int firstN = 0, lastN = 0;
        StringBuilder line = new StringBuilder();
        for (int s = 0; s * seg < T; s++) {
            int lo = s * seg, hi = Math.min(T, lo + seg);
            double a = 0;
            for (int i = lo; i < hi; i++) a += ms[i];
            a /= (hi - lo);
            line.append(String.format(java.util.Locale.US, "  [%d..%d) %.3fms%n", lo, hi, a));
            if (s == 0) { firstSum = a; firstN = 1; }
            lastSum = a; lastN = 1;
        }
        System.out.print(line);
        if (firstN > 0 && lastN > 0) {
            System.out.printf(java.util.Locale.US,
                    "trend: 首段 %.3fms -> 末段 %.3fms  (%+.1f%%)%n",
                    firstSum, lastSum, 100.0 * (lastSum - firstSum) / Math.max(1e-9, firstSum));
        }

        // 尾段世界规模（解释"为什么变贵"：实体/容器/区块数）
        System.out.println("world: beasts=" + sim.world.beasts.size()
                + " npcs=" + sim.world.npcs.size()
                + " tick=" + sim.world.tick);

        // 逐系统 top-N（需要 -Dbw.simdiag=1；否则本段只提示怎么开）
        if (prof) {
            // ⚠️ 标签必须准确：因为首末段采样会在中途 reset，此刻累加器里装的是**末段**（不是全程）。
            // 写成"全程"就是一句谎话 —— 而诊断本身的标签说谎，比没有诊断更糟。
            System.out.println("--- 逐系统耗时 top-20（**末段** " + segN + "t，非全程）---");
            System.out.print(core.systems.SimProfiler.report(20, T));
            if (firstSnap != null && lastSnap != null) {
                System.out.println("--- 谁随世界年龄变贵：首段(" + segN + "t) vs 末段(" + segN + "t) ---");
                System.out.print(core.systems.SimProfiler.growthReport(firstSnap, segN, lastSnap, segN, 20));
                if (firstCounts != null && lastCounts != null) {
                    System.out.println("--- 世界成分增长：逐方块类型变化 top-15（首段 -> 末段）---");
                    System.out.print(blockGrowth(firstCounts, lastCounts, 15));
                }
                System.out.println("world mix 首段: " + firstMix);
                System.out.println("world mix 末段: " + lastMix);
            }
        } else {
            System.out.println("--- 逐系统耗时：未启用（加大 -Dbw.simdiag=1 可打印 top-20 与首末段对比）---");
        }
    }

    /** 全图**逐方块类型**计数（一次 O(体积) 扫描，只在首/末段各跑一次）。 */
    private static int[] countBlocks(core.world.World w) {
        int[] c = new int[core.world.Blocks.count()];
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++) {
                    int b = w.mat[x][y][z];
                    if (b >= 0 && b < c.length) c[b]++;
                }
        return c;
    }

    /**
     * 逐类型增长报告 —— 把"世界在长大"落到**具体是哪些方块**。
     *
     * <p>为什么必须落到类型：本项目实测 12000 tick 后 {@code nonAir} 从 1.07M 涨到 1.70M（+59%），
     * 而"蔓延/流动"类系统（vine ×8.2 / flood ×7.7 / lava ×11.9）的耗时同步暴涨。
     * 只有知道**是什么在长**，才能判断这是"生机勃勃的预期演化"还是"不收敛的缺陷"。</p>
     */
    private static String blockGrowth(int[] first, int[] last, int top) {
        int n = Math.min(first.length, last.length);
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final int[] d = new int[n];
        for (int i = 0; i < n; i++) d[i] = last[i] - first[i];
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Integer.compare(Math.abs(d[b]), Math.abs(d[a]));
            }
        });
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int k = 0; k < n && shown < top; k++) {
            int i = idx[k];
            if (d[i] == 0) break;
            sb.append(String.format(java.util.Locale.US,
                    "  %-20s %8d -> %8d  (%+d)%n",
                    core.world.Blocks.byIndex(i).id, first[i], last[i], d[i]));
            shown++;
        }
        int totD = 0;
        for (int i = 0; i < n; i++) if (i != core.world.Blocks.AIR.index) totD += d[i];
        // ⚠️ 求和必须**排除 AIR**：世界体积恒定 ⇒ 含 AIR 求和恒为 0（第一版就踩了这个，标签成了谎话）。
        sb.append("  ---- 含 AIR 的全部类型净增恒为 0（体积恒定）；**非空气**净增 = ")
          .append(totD).append(" 格\n");
        return sb.toString();
    }

    /**
     * 世界成分快照（非空气 / 各关键方块计数）——用来给"系统变贵"找物理解释：
     * 若某系统的耗时上升伴随着它作用对象的数量上升，那增长就是"随年龄累积"的，修法完全不同。
     */
    private static String worldMix(core.world.World w) {
        int nonAir = 0, sand = 0, water = 0, lava = 0, fire = 0, air = 0;
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++) {
                    int b = w.mat[x][y][z];
                    if (b == core.world.Blocks.AIR.index) { air++; continue; }
                    nonAir++;
                    if (b == core.world.Blocks.SAND.index) sand++;
                    else if (b == core.world.Blocks.WATER.index) water++;
                    else if (b == core.world.Blocks.LAVA.index) lava++;
                    else if (b == core.world.Blocks.FIRE.index) fire++;
                }
        return "nonAir=" + nonAir + " air=" + air + " sand=" + sand + " water=" + water
                + " lava=" + lava + " fire=" + fire;
    }

    /** 分位数（sorted 已升序）。 */
    private static double q(double[] sorted, double p) {
        int i = (int) Math.round(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }
}
