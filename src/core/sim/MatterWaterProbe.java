package core.sim;

import core.world.Blocks;

/**
 * 探针：MATTER 门禁 {@code subs} 变成 0 的根因定位 + **可达性世界尺寸的选型**。
 *
 * <p>复现 {@code MatterDeterminismTest} 的参数（seed 987654321 / 1200 tick），
 * 打印 {@code deepsea()} 判定所需的两个量：<b>水体格数</b>（门 {@link core.systems.MatterSystem#SEA_MIN_WATER}）
 * 与 <b>research</b>（门 {@code SEA_TECH=60}）。
 *
 * <p>⚠️ 本探针原先把门槛硬编码成字面量（"gate&gt;=100"），而常量早已被下调到 50 ⇒ <b>标签说谎</b>。
 * 现改为**直接读常量**（常量是唯一来源，改它这里跟着变），并在低于门时显式标 {@code BELOW}。
 *
 * <p><b>尺寸可参数化</b>（第三十五批加）：门禁原来固定跑 64×40×64 —— 那里面只有 ~20~60 格水，
 * 阈值 50 成了**刀刃**，任何全局改动都会把它推过/推回。本探针用来找"水与科研都有余量"的代表性尺寸。
 *
 * <p>运行：{@code java -cp "out;libs/*" core.sim.MatterWaterProbe [SX SY SZ T]}
 * （缺省 64 40 64 1200，即原门禁参数）。
 */
public final class MatterWaterProbe {

    public static void main(String[] args) {
        long seed = 987654321L;
        int SX = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        int SY = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        int SZ = args.length > 2 ? Integer.parseInt(args[2]) : 64;
        int T  = args.length > 3 ? Integer.parseInt(args[3]) : 1200;
        final int seaMin = core.systems.MatterSystem.SEA_MIN_WATER;
        final float seaTech = 60f;
        System.out.println("=== MatterWaterProbe  world=" + SX + "x" + SY + "x" + SZ + "  T=" + T
                + "  seed=" + seed + "  门: water>=" + seaMin + ", research>=" + seaTech + " ===");
        for (int mode = 0; mode < 2; mode++) {
            boolean flow = (mode == 1);
            Simulation s = new Simulation(seed, SX, SY, SZ);
            s.world.config.densityFlow = flow;
            for (int t = 0; t < T; t++) s.world.tick();
            int water = s.world.cellsOfType(Blocks.WATER.index).size();
            int ash = s.world.cellsOfType(Blocks.ASH.index).size();
            int sand = s.world.cellsOfType(Blocks.SAND.index).size();
            float research = s.world.civ.research;
            boolean ok = water >= seaMin && research >= seaTech;
            System.out.println("  densityFlow=" + flow
                    + "  water=" + water + "/" + seaMin + (water >= seaMin ? "[OK]" : "[BELOW]")
                    + "  research=" + String.format(java.util.Locale.US, "%.2f", research)
                    + "/" + seaTech + (research >= seaTech ? "[OK]" : "[BELOW]")
                    + "  subs=" + s.world.matter.subs
                    + "  mineral=" + String.format(java.util.Locale.US, "%.2f", s.world.matter.mineral)
                    + "  colonies=" + String.format(java.util.Locale.US, "%.2f", s.world.matter.colonies)
                    + "  ash=" + ash + " sand=" + sand
                    + "  => " + (ok ? "可达" : "不可达"));
        }
    }

    private MatterWaterProbe() { }
}
