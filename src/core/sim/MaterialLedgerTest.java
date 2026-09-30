package core.sim;

import core.content.ContentRegistry;
import core.content.MaterialBook;
import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

import java.io.File;

/**
 * 物质账本门禁（批⑮，2026-09-29）。
 *
 * <p><b>背景</b>：本项目<b>没有质量守恒律</b> —— 散布者系统（LEAF/MOSS/ICE/ASH…）从无到有地造方块，
 * 降雨/蒸发是开放源汇。批⑤/⑥ 只给了这些系统<b>数量</b>上限，从未把"世界的物质收支"算清楚过。
 * {@code tools/MassLedgerProbe} 实测（96³ / 20000 tick）：总物质质量 <b>+4.18%</b>（开放系统，净增重），
 * 最大源 ASH（+178k）、最大汇 STONE（-228k）。
 *
 * <p><b>更糟的是</b>：代码里至少三处注释<b>自称守恒</b>——
 * {@code CalamitySystem} 的地震"房屋->木残骸（材料重排，质量不变）"、
 * {@code CivilizationSystem} 自治"1:1 材料重排（质量不变）"。
 * 但按 {@code MaterialBook.density} 实际算：<b>SHELTER=14 ≠ WOOD=12</b>、<b>STONE=26 ≠ SHELTER=14</b>
 * ⇒ 这些变换<b>并不守恒</b>，注释是假的（属本项目明确列为 bug 类的"标签说谎"）。
 *
 * <p>本门禁把"物质经济"显式化并永久钉住，三部分：
 * <ol>
 *   <li><b>变换账目表</b>：登记代码里<b>每一条</b>物质变换 {@code from->to} 的密度差 Δ
 *       （以及它是 保守/源/汇）—— 任何 JSON 密度改动都会让此表报错并点名是哪条变换受影响；</li>
 *   <li><b>端到端对账</b>：对三条代表性变换（地震重排 / 结冰相变 / 蒸发汇）真跑系统，
 *       断言实测 {@code ΔΣmass == Δdensity × 格数} ⇒ 证明"登记表"与"真实行为"一致；</li>
 *   <li><b>总账自洽</b>：真跑仿真后断言 {@code Σ_types(countOf×density) == 逐格扫描的 Σmass}
 *       ⇒ 交叉校验类型索引（{@code CellSet}）与 {@code mat} 逐格真值一致。</li>
 * </ol>
 *
 * <p>守恒量定义：{@code massΣ = Σ_cells (density(mat) - density(AIR))}。减掉 AIR 基线（density=1，
 * 是"不适用"占位值而非物理量）后空格贡献 0。
 *
 * <p>运行：{@code java -cp "out;LIBS" core.sim.MaterialLedgerTest}（cwd 必须是工程根，assets/ 相对可寻）
 */
public final class MaterialLedgerTest {
    private static int fails = 0;
    private static MaterialBook MB;
    private static int AIRD;

    private static final int SX = 32, SY = 40, SZ = 32;
    private static final long SEED = 20260929L;

    public static void main(String[] args) {
        ContentRegistry reg = ContentRegistry.load(new File("assets/content"));
        if (reg == null) {
            ck("CONTENT_LOAD", false, "assets/content 加载失败 ⇒ 无法建立密度账本");
            done();
            return;
        }
        MB = reg.materialBook();
        AIRD = MB.density(Blocks.AIR.index);

        try {
            part1Transforms();
            part2EndToEnd();
            part3LedgerIdentity();
        } finally {
            core.systems.CalamitySystem.MATERIAL_WORKS = false;   // 复位静态开关
        }
        done();
    }

    // ================================================================ 1) 变换账目表
    /**
     * 登记代码里每一条物质变换。{@code kind}：0=保守(Δ=0)、+1=源(造物质)、-1=汇(消灭物质)。
     * 注释里自称"质量不变"但 Δ≠0 的，这里<b>如实登记为源/汇</b>并在 label 中标注"!! 原注释谎称守恒"。
     */
    private static void part1Transforms() {
        java.lang.System.out.println("[1] 变换账目表（Δdensity = density(to) - density(from)）");
        // —— 代码自称"守恒"但实测不守恒的三条（本批的核心发现）——
        tf("earthquake  SHELTER->WOOD    !! 原注释谎称\"质量不变\"", Blocks.SHELTER.index, Blocks.WOOD.index, -2, -1);
        tf("autonomy    STONE->SHELTER   !! 原注释谎称\"1:1 质量守恒\"", Blocks.STONE.index, Blocks.SHELTER.index, -12, -1);
        tf("autonomy    WOOD->SHELTER    !! 原注释谎称\"1:1 质量守恒\"", Blocks.WOOD.index, Blocks.SHELTER.index, 2, 1);
        // —— 相变（直观以为守恒，实际不守恒）——
        tf("iceform     WATER->ICE       (相变，非守恒)", Blocks.WATER.index, Blocks.ICE.index, -1, -1);
        tf("frozen      WATER->ICE       (相变，非守恒)", Blocks.WATER.index, Blocks.ICE.index, -1, -1);
        // —— 声明为开放源/汇 ——
        tf("evaporate   WATER->AIR       (声明：汇)", Blocks.WATER.index, Blocks.AIR.index, -9, -1);
        tf("raiseWater  AIR->WATER       (声明：源)", Blocks.AIR.index, Blocks.WATER.index, 9, 1);
        tf("hail        AIR->ICE         (声明：源)", Blocks.AIR.index, Blocks.ICE.index, 8, 1);
        tf("ashfall     AIR->ASH         (声明：源)", Blocks.AIR.index, Blocks.ASH.index, 13, 1);
        tf("canopy      AIR->LEAF        (声明：源)", Blocks.AIR.index, Blocks.LEAF.index, 3, 1);
        tf("flower      AIR->FLOWER      (声明：源)", Blocks.AIR.index, Blocks.FLOWER.index, 1, 1);
        tf("mossspread  STONE->MOSS      (声明：汇)", Blocks.STONE.index, Blocks.MOSS.index, -20, -1);
        // —— 真正保守者（正样本对照：位置交换，质量本就不该变）——
        tf("densityflow SAND<->WATER      (位置交换=恒保守)", Blocks.SAND.index, Blocks.SAND.index, 0, 0);

        long src = 0, snk = 0;
        for (int[] r : TRANSFORMS) { if (r[1] > 0) src++; else if (r[1] < 0) snk++; }
        ck("TABLE_SPLIT", src > 0 && snk > 0, "源=" + src + " 条 / 汇=" + snk + " 条 / 保守=1 条；世界确为开放系统");
    }

    private static final java.util.List<int[]> TRANSFORMS = new java.util.ArrayList<int[]>();

    /** label, Δ, kind —— Δ 由 density 实测填入，故与 JSON 同源（改 JSON 即报错）。 */
    private static void tf(String label, int fromIdx, int toIdx, int expectDelta, int kind) {
        int d = MB.density(toIdx) - MB.density(fromIdx);
        boolean ok = d == expectDelta
                && (kind == 0 ? d == 0 : (d > 0 == kind > 0));
        ck("TF", ok, String.format("%-46s Δ=%+4d (期望 %+d)", label, d, expectDelta));
        TRANSFORMS.add(new int[]{d, kind});
    }

    // ================================================================ 2) 端到端对账
    /** 真跑系统，断言实测 ΔΣmass 恰等于"登记表"的 Δdensity × 格数。 */
    private static void part2EndToEnd() {
        java.lang.System.out.println("[2] 端到端对账（实测 ΔΣmass == Δdensity × 格数）");

        // (a) 地震：SHELTER->WOOD，3 间房，Δmass 应为 3×(12-14) = -6
        core.systems.CalamitySystem.MATERIAL_WORKS = true;
        World w = flat(SEED);
        for (int x = 0; x < 10; x++) w.setBlock(x, 1, 0, Blocks.SHELTER.index);
        w.humidity = 0.9f; w.tick = 600;
        long m0 = massOf(w);
        int sh0 = w.countOf(Blocks.SHELTER.index), wd0 = w.countOf(Blocks.WOOD.index);
        new core.systems.CalamitySystem().update(w, new SeededRNG(11L));
        long dm = massOf(w) - m0;
        int dSh = w.countOf(Blocks.SHELTER.index) - sh0, dWd = w.countOf(Blocks.WOOD.index) - wd0;
        int exp = dSh * (MB.density(Blocks.SHELTER.index) - AIRD) + dWd * (MB.density(Blocks.WOOD.index) - AIRD);
        ck("E2E_EARTHQUAKE", dSh == -3 && dWd == 3 && dm == exp && dm == -6,
                "SHELTER" + dSh + " WOOD+" + dWd + "  Δmass=" + dm + "（期望 -6 = 3×(12-14)）");
        core.systems.CalamitySystem.MATERIAL_WORKS = false;

        // (b) 结冰：WATER->ICE 相变，Δmass 应为 nICE×(9-10) = -nICE
        World wi = flat(SEED);
        for (int x = 0; x < SX; x++) for (int z = 0; z < SZ; z++) wi.setBlock(x, 1, z, Blocks.WATER.index);
        long mi0 = massOf(wi);
        int wat0 = wi.countOf(Blocks.WATER.index), ice0 = wi.countOf(Blocks.ICE.index);
        core.systems.IceFormSystem ice = new core.systems.IceFormSystem();
        for (int t = 0; t < 600; t++) ice.update(wi, new SeededRNG(12L + t));
        int dIce = wi.countOf(Blocks.ICE.index) - ice0, dWat = wi.countOf(Blocks.WATER.index) - wat0;
        long dmi = massOf(wi) - mi0;
        ck("E2E_ICEFORM", dIce > 0 && dWat == -dIce && dmi == (long) dIce * (MB.density(Blocks.ICE.index) - MB.density(Blocks.WATER.index)),
                "ICE+" + dIce + " WATER" + dWat + "  Δmass=" + dmi + "（期望 " + (-dIce) + " = " + dIce + "×(9-10)）");

        // (c) 蒸发：WATER->AIR（声明为汇），4 格水，Δmass 应为 4×(1-10) = -36
        core.systems.CalamitySystem.MATERIAL_WORKS = true;
        World wd = flat(SEED);
        for (int x = 10; x <= 11; x++) for (int z = 10; z <= 11; z++) wd.setBlock(x, 1, z, Blocks.WATER.index);
        wd.humidity = 0.9f; wd.tick = 50; wd.raining = false;
        wd.calamity.rainStreak = 0; wd.calamity.dryTicks = 89;
        long md0 = massOf(wd);
        int wat1 = wd.countOf(Blocks.WATER.index);
        new core.systems.CalamitySystem().update(wd, new SeededRNG(13L));
        int dWat2 = wd.countOf(Blocks.WATER.index) - wat1;
        long dmd = massOf(wd) - md0;
        ck("E2E_EVAPORATE", dWat2 == -4 && dmd == 4L * (MB.density(Blocks.AIR.index) - MB.density(Blocks.WATER.index)),
                "WATER" + dWat2 + "  Δmass=" + dmd + "（期望 -36 = 4×(1-10)）");
        core.systems.CalamitySystem.MATERIAL_WORKS = false;
    }

    // ================================================================ 3) 总账自洽
    /** Σ_types(countOf×density) 必须等于逐格扫描的 Σmass ⇒ 交叉校验类型索引与 mat 真值一致。 */
    private static void part3LedgerIdentity() {
        Simulation sim = new Simulation(SEED, 96, 48, 96);
        World w = sim.world;
        w.materials = MB;                  // 用真实材料表（否则 density 全 20，账本无意义）
        for (int t = 0; t < 500; t++) w.tick();

        long direct = massOf(w);
        long viaIndex = 0;
        int cellsViaIndex = 0;
        for (int i = 0; i < Blocks.count(); i++) {
            int c = w.countOf(i);
            cellsViaIndex += c;
            viaIndex += (long) c * (MB.density(i) - AIRD);
        }
        // 类型索引**不含 AIR**（AIR 格对质量贡献 0，且不进 CellSet）⇒ 与"直接扫 mat 数非空气格"对齐。
        int cellsDirect = 0;
        for (int x = 0; x < w.SX; x++) for (int y = 0; y < w.SY; y++) for (int z = 0; z < w.SZ; z++)
            if (w.mat[x][y][z] != Blocks.AIR.index) cellsDirect++;
        ck("LEDGER_IDENTITY", viaIndex == direct && cellsViaIndex == cellsDirect,
                "Sigma_index=" + viaIndex + " Sigma_cell=" + direct
                        + "（非空气格 " + cellsViaIndex + "/" + cellsDirect + "）");
    }

    // ================================================================ 工具

    private static long massOf(World w) {
        if (w.materials != MB) w.materials = MB;   // 保证用真实密度
        long s = 0;
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++)
                    s += w.materials.density(w.mat[x][y][z]) - AIRD;
        return s;
    }

    /** 已知初始态小世界：同种子 ⇒ 地形逐字节相同；全清 AIR（顶向下）+ 铺 y=0 石底板。 */
    private static World flat(long seed) {
        World w = new World(seed, SX, SY, SZ);
        w.materials = MB;
        for (int y = SY - 1; y >= 0; y--)
            for (int x = 0; x < SX; x++)
                for (int z = 0; z < SZ; z++)
                    w.setBlock(x, y, z, Blocks.AIR.index);
        for (int x = 0; x < SX; x++)
            for (int z = 0; z < SZ; z++)
                w.setBlock(x, 0, z, Blocks.STONE.index);
        return w;
    }

    private static void ck(String name, boolean ok, String msg) {
        java.lang.System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + name + "  " + msg);
        if (!ok) fails++;
    }

    private static void done() {
        java.lang.System.out.println(fails == 0 ? "MATLEDGER PASS" : "MATLEDGER FAIL (" + fails + ")");
        if (fails > 0) java.lang.System.exit(1);
    }
}
