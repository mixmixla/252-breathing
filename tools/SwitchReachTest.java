import core.rng.SeededRNG;
import core.systems.CalamitySystem;
import core.world.Blocks;
import core.world.World;

/**
 * 休眠开关「行为可达性」门禁（批⑭，2026-09-29）。
 *
 * <p><b>为什么需要它</b>：批⑬ 的 {@code SWITCHSMOKE} 只证明了「{@code MATERIAL_WORKS=true} 不崩
 * + 自治真跑」。但「不崩」≠「真的接上了」—— 一次重构完全可以某个 {@code MATERIAL_WORKS} 分支
 * 里再塞一个恒假条件（或把守卫写反），冒烟测试照样绿（项目称之为「标签说谎」）。
 * 本门禁补上**行为可达性**：对灾害层 {@link CalamitySystem} 的四条材料子路径逐条<b>强制触发</b>，
 * 断言
 * <ol>
 *   <li><b>关 = 原样</b>：{@code WORKS=false} 时该子路径对网格<b>零改动</b>（计数与 pristine 一致）；</li>
 *   <li><b>开 = 生效</b>：{@code WORKS=true} 时网格<b>恰按预期</b>改变（且改动格数恰等于期望）；</li>
 *   <li><b>开关只切网格、不改逻辑</b>（铁律）：两世界的 {@code calamity.snapshot()}（旗标/计数）
 *       <b>逐字相同</b> —— 证明开关没有污染触发层。</li>
 * </ol>
 *
 * <p><b>方法</b>：两个世界**同种子 + 同 setup**（先全清为 AIR、再铺 y=0 石底板 ⇒ 已知初始态），
 * 一个 {@code WORKS=false}、一个 {@code WORKS=true}，各调一次 {@code CalamitySystem.update}，
 * 然后逐格比对 {@code mat}。用「同种子双世界差分」⇒ 断言与地形噪声无关。
 * （只装小世界 {@code SY=40}：走不到 {@code Megalith.MIN_SY=96} 的巨构材化，故初始态干净。）
 *
 * <p>四条子路径（镜像 Python {@code systems/disasters.py}）：
 * <ul>
 *   <li><b>wildfire</b>（干燥 + {@code tick%120==0}）→ 易燃表层格点火（{@code setBlock(FIRE)}）；</li>
 *   <li><b>flood</b>（{@code rainStreak>=12}）→ 低洼积水向邻格抬水位（{@code setBlock(WATER)}）；</li>
 *   <li><b>drought</b>（{@code dryTicks>=90}）→ 地表水加速蒸发（{@code setBlock(AIR)}）；</li>
 *   <li><b>earthquake</b>（{@code tick%600==0} 且有房）→ 房屋转木残骸（{@code SHELTER→WOOD}）。</li>
 * </ul>
 *
 * <p>运行：{@code javac -cp "out;LIBS" -d out tools/SwitchReachTest.java && java -cp "out;LIBS" SwitchReachTest}
 */
public final class SwitchReachTest {
    private static final int SX = 32, SY = 40, SZ = 32;
    private static final long SEED = 20260929L;

    private static int fails = 0;

    public static void main(String[] args) {
        try {
            run("WILDFIRE",   wildfire());
            run("FLOOD",      flood());
            run("DROUGHT",    drought());
            run("EARTHQUAKE", earthquake());
        } finally {
            setWorks(false);       // 复位静态开关（同 JVM 复用安全；门禁本就独占进程）
        }
        java.lang.System.out.println(fails == 0 ? "SWITCHREACH PASS" : "SWITCHREACH FAIL (" + fails + ")");
        if (fails > 0) System.exit(1);
    }

    // ================================================================ 四个场景

    /** 山火：干燥 + tick=120（120%120==0）→ 期望 on 时恰好 1 格 GRASS→FIRE。 */
    private static boolean wildfire() {
        setWorks(false);
        World a = flat(SEED); placeGrass(a); arm(a); a.humidity = 0.10f; a.tick = 120;
        new CalamitySystem().update(a, new SeededRNG(1L));

        setWorks(true);
        World b = flat(SEED); placeGrass(b); arm(b); b.humidity = 0.10f; b.tick = 120;
        new CalamitySystem().update(b, new SeededRNG(1L));
        setWorks(false);

        return logicSame("wildfire", a, b)
                & ck("wildfire/off=原样", a.countOf(Blocks.FIRE.index) == 0 && a.countOf(Blocks.GRASS.index) == 64,
                        "FIRE=" + a.countOf(Blocks.FIRE.index) + " GRASS=" + a.countOf(Blocks.GRASS.index))
                & ck("wildfire/on=生效", b.countOf(Blocks.FIRE.index) == 1 && b.countOf(Blocks.GRASS.index) == 63,
                        "FIRE=" + b.countOf(Blocks.FIRE.index) + " GRASS=" + b.countOf(Blocks.GRASS.index))
                & ck("wildfire/唯一差异", gridDiff(a, b) == 1, "diff=" + gridDiff(a, b) + "（期望 1）");
    }

    /** 洪涝：rainStreak 11→12（上升沿）→ 期望 on 时低洼积水向邻格抬水位（WATER 增加，且改动格数==增加数）。 */
    private static boolean flood() {
        setWorks(false);
        World a = flat(SEED); placeWater2x2(a); arm(a);
        a.humidity = 0.90f; a.tick = 100; a.raining = true; a.calamity.rainStreak = 11;
        new CalamitySystem().update(a, new SeededRNG(3L));

        setWorks(true);
        World b = flat(SEED); placeWater2x2(b); arm(b);
        b.humidity = 0.90f; b.tick = 100; b.raining = true; b.calamity.rainStreak = 11;
        new CalamitySystem().update(b, new SeededRNG(3L));
        setWorks(false);

        int wa = a.countOf(Blocks.WATER.index), wb = b.countOf(Blocks.WATER.index);
        int raised = wb - wa;
        return logicSame("flood", a, b)
                & ck("flood/off=原样", wa == 4, "WATER=" + wa)
                & ck("flood/on=抬水位", raised > 0, "WATER " + wa + "->" + wb + "（+" + raised + "）")
                & ck("flood/唯一差异", gridDiff(a, b) == raised, "diff=" + gridDiff(a, b) + "（期望=" + raised + "）");
    }

    /** 干旱：dryTicks 89→90（上升沿）→ 期望 on 时地表水全部蒸发（WATER→AIR）。 */
    private static boolean drought() {
        setWorks(false);
        World a = flat(SEED); placeWater2x2(a); arm(a);
        a.humidity = 0.90f; a.tick = 50; a.raining = false; a.calamity.rainStreak = 0; a.calamity.dryTicks = 89;
        new CalamitySystem().update(a, new SeededRNG(4L));

        setWorks(true);
        World b = flat(SEED); placeWater2x2(b); arm(b);
        b.humidity = 0.90f; b.tick = 50; b.raining = false; b.calamity.rainStreak = 0; b.calamity.dryTicks = 89;
        new CalamitySystem().update(b, new SeededRNG(4L));
        setWorks(false);

        return logicSame("drought", a, b)
                & ck("drought/off=原样", a.countOf(Blocks.WATER.index) == 4, "WATER=" + a.countOf(Blocks.WATER.index))
                & ck("drought/on=蒸发", b.countOf(Blocks.WATER.index) == 0, "WATER=" + b.countOf(Blocks.WATER.index))
                & ck("drought/唯一差异", gridDiff(a, b) == 4, "diff=" + gridDiff(a, b) + "（期望 4）");
    }

    /** 地震：tick=600（600%600==0）+ 一排 10 间房 → 期望 on 时 3 间 SHELTER→WOOD。 */
    private static boolean earthquake() {
        setWorks(false);
        World a = flat(SEED); placeShelterRow(a); arm(a); a.humidity = 0.90f; a.tick = 600;
        new CalamitySystem().update(a, new SeededRNG(5L));

        setWorks(true);
        World b = flat(SEED); placeShelterRow(b); arm(b); b.humidity = 0.90f; b.tick = 600;
        new CalamitySystem().update(b, new SeededRNG(5L));
        setWorks(false);

        return logicSame("earthquake", a, b)
                & ck("earthquake/off=原样", a.countOf(Blocks.SHELTER.index) == 10 && a.countOf(Blocks.WOOD.index) == 0,
                        "SHELTER=" + a.countOf(Blocks.SHELTER.index) + " WOOD=" + a.countOf(Blocks.WOOD.index))
                & ck("earthquake/on=房倒", b.countOf(Blocks.SHELTER.index) == 7 && b.countOf(Blocks.WOOD.index) == 3,
                        "SHELTER=" + b.countOf(Blocks.SHELTER.index) + " WOOD=" + b.countOf(Blocks.WOOD.index))
                & ck("earthquake/唯一差异", gridDiff(a, b) == 3, "diff=" + gridDiff(a, b) + "（期望 3）");
    }

    // ================================================================ 工具

    private static void run(String name, boolean ok) {
        java.lang.System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + name);
        if (!ok) fails++;
    }

    private static boolean ck(String name, boolean ok, String msg) {
        java.lang.System.out.println("      " + (ok ? "ok  " : "FAIL") + " " + name
                + (msg.isEmpty() ? "" : "  " + msg));
        return ok;
    }

    /** 铁律断言：两世界的灾害逻辑状态（旗标/计数/预警/撤离）必须逐字相同 —— 开关只切网格。 */
    private static boolean logicSame(String tag, World a, World b) {
        String sa = a.calamity.snapshot(), sb = b.calamity.snapshot();
        return ck(tag + "/开关只切网格(逻辑逐字同)", sa.equals(sb), "off[" + sa + "]  on[" + sb + "]");
    }

    /** 两世界 mat 逐格差异格数。 */
    private static int gridDiff(World a, World b) {
        int d = 0;
        for (int x = 0; x < a.SX; x++)
            for (int y = 0; y < a.SY; y++)
                for (int z = 0; z < a.SZ; z++)
                    if (a.mat[x][y][z] != b.mat[x][y][z]) d++;
        return d;
    }

    /** 复位天气/计数为"中性"（防上一场景残留；每场景都用全新世界，这里只是显式化）。 */
    private static void arm(World w) {
        w.raining = false;
        w.calamity.rainStreak = 0;
        w.calamity.dryTicks = 0;
    }

    /**
     * 已知初始态小世界：同种子 ⇒ 生成地形逐字节相同；再全清为 AIR（顶向下清，surfaceY 单调回落）
     * + 铺 y=0 石底板 ⇒ 每列 surfaceY=0，地面之上全空。之后各场景只在 y=1 放特征。
     */
    private static World flat(long seed) {
        World w = new World(seed, SX, SY, SZ);
        for (int y = SY - 1; y >= 0; y--)
            for (int x = 0; x < SX; x++)
                for (int z = 0; z < SZ; z++)
                    w.setBlock(x, y, z, Blocks.AIR.index);
        for (int x = 0; x < SX; x++)
            for (int z = 0; z < SZ; z++)
                w.setBlock(x, 0, z, Blocks.STONE.index);
        return w;
    }

    private static void placeGrass(World w) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++)
                w.setBlock(x, 1, z, Blocks.GRASS.index);      // 64 格易燃表层
    }

    private static void placeWater2x2(World w) {
        for (int x = 10; x <= 11; x++)
            for (int z = 10; z <= 11; z++)
                w.setBlock(x, 1, z, Blocks.WATER.index);      // 每格 ≥2 水邻 + 四周 AIR 覆石底板
    }

    private static void placeShelterRow(World w) {
        for (int x = 0; x < 10; x++)
            w.setBlock(x, 1, 0, Blocks.SHELTER.index);        // 10 间房成一排
    }

    private static void setWorks(boolean v) {
        CalamitySystem.MATERIAL_WORKS = v;
        core.systems.CivilizationSystem.MATERIAL_WORKS = v;
        core.systems.IndividualSystem.MATERIAL_WORKS = v;
        core.systems.MatterSystem.MATERIAL_WORKS = v;
    }
}
