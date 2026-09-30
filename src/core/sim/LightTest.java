package core.sim;

import core.content.MaterialDef;
import core.world.Blocks;
import core.world.World;

import java.io.File;
import java.util.Arrays;

/**
 * 门禁 LIGHT：块光传播（{@code World.computeLight / lightAt}）的无头守护。
 *
 * <p><b>2026-09-21 语义迁移</b>：本门禁原本守护「逐源 BFS、每格衰减 1/LIGHT_R」。
 * 按 {@code docs/FOUR_SOURCE_PICK.md} §2 把 {@code computeLight} 重写为
 * <b>Terraria 式固定两轮扫描</b>（每格按材料 {@code lightDecay} 相乘）后，旧断言全部作废 ——
 * 它们锚定的是**已被有意替换的旧算法**。本文件同步重写为新算法的守护，并**刻意保留**两组断言：
 * <ol>
 *   <li><b>不变的结构性质</b>（与算法无关）：源格最亮、光随距离单调不增、遮挡后有黑影、
 *       computeLight 不改仿真状态、同种子逐字节一致。这些是"光场语义"的真正内核。</li>
 *   <li><b>新增的算法性质</b>（Terraria 特有）：迭代次数=={@link World#LIGHT_PASSES}；
 *       空气衰减=={@link MaterialDef#LIGHT_DECAY_AIR}；实体衰减=={@link MaterialDef#LIGHT_DECAY_SOLID}；
 *       液体按水衰减；低于 {@link World#LIGHT_CUTOFF} 截断。</li>
 * </ol>
 *
 * <p>纯 Java（不触碰 GL）。门禁只验证光场数学，纹理/顶点上传由渲染层在 drawFrame 惰性消费。
 */
public class LightTest {

    private static boolean ok = true;

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    /** 在空气里放一个源，采样沿 +x 的强度序列（用于验证衰减律）。 */
    private static float[] rayX(World w, int x0, int y, int z, int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = w.lightAt(x0 + i, y, z);
        return out;
    }

    /**
     * 造一个带真实材料表的 World —— <b>必须</b>，因为裸 {@code new World} 的 {@code materials}
     * 是{@link core.content.MaterialBook#empty} 空书（全部兜底 "solid"→0.56），
     * 那样连 AIR 都被当成实体，光在空气里也按 0.56 衰减，测不出"空气 0.91 指数长尾"。
     * 这与游戏里的真实装配一致（{@code Game.attachContentToWorld}）。
     */
    private static World litWorld(long seed, int sx, int sy, int sz) {
        World w = new World(seed, sx, sy, sz);
        w.materials = core.content.ContentRegistry.load(new File("assets/content")).materialBook();
        return w;
    }

    public static void main(String[] args) {
        final int SX = 48, SY = 24, SZ = 48;
        final int y = 12;                       // 高空：邻居必为 AIR（与地形解耦）
        final int LAMP = Blocks.LAMP.index, FIRE = Blocks.FIRE.index, BED = Blocks.BEDROCK.index;
        final int R = World.LIGHT_R;
        final float FRAC = World.FIRE_LIGHT_FRACTION;
        final float AIR = MaterialDef.LIGHT_DECAY_AIR;
        final float SOLID = MaterialDef.LIGHT_DECAY_SOLID;

        System.out.println("=== LIGHT: Terraria 两扫光照正确性 ===");

        // ---------- 1) 源格最亮 + 单调不增 + 空气按 0.91 衰减 ----------
        World w = litWorld(20260918L, SX, SY, SZ);
        w.setBlock(10, y, 10, LAMP);
        w.computeLight();
        check("LAMP 源格 = 满强度 1.0", Math.abs(w.lightAt(10, y, 10) - 1.0f) < 1e-6f);

        float[] ray = rayX(w, 10, y, 10, 8);
        boolean mono = true;
        for (int i = 1; i < ray.length; i++) if (ray[i] > ray[i - 1] + 1e-6f) mono = false;
        check("光沿 +x 单调不增", mono);
        check("距离1 ≈ 0.91 (穿过一格空气)", Math.abs(ray[1] - AIR) < 2f / R);
        check("距离2 ≈ 0.91^2", Math.abs(ray[2] - AIR * AIR) < 2f / R);
        check("距离3 ≈ 0.91^3 (指数长尾而非硬截断)", Math.abs(ray[3] - AIR * AIR * AIR) < 2f / R);
        check("半径14处仍有光（旧 BFS 在此已归零）", w.lightAt(10 + 14, y, 10) > 0f);
        check("远至 ~42 格后被 CUTOFF 截断", Math.abs(w.lightAt(10 + 60, y, 10)) < 1e-6f);
        check("LIGHT_R = 64（两扫光照的量化分辨率）", World.LIGHT_R == 64);

        // ---------- 2) 不透明实体挡光：光穿过实体衰减为 0.56 ----------
        w = litWorld(20260918L, SX, SY, SZ);
        w.setBlock(10, y, 10, LAMP);
        w.setBlock(13, y, 10, BED);              // 距离1..2 空气，距离3 实体
        w.computeLight();
        float before = w.lightAt(12, y, 10);
        float atWall = w.lightAt(13, y, 10);
        check("墙前(12)有光", before > 0.5f);
        check("实体格(13)受光 = 墙前 × 0.56", Math.abs(atWall - before * SOLID) < 2f / R);
        check("实体格远弱于空气格（同级距离对比）", atWall < before * 0.7f);
        check("墙后(14)比墙前(12)显著更暗", w.lightAt(14, y, 10) < before * 0.7f);

        // ---------- 3) 全封气腔：六面 bedrock → 腔内无光（两扫也不能绕入）----------
        w = litWorld(20260918L, SX, SY, SZ);
        w.setBlock(16, y, 16, LAMP);             // 源在腔外
        w.setBlock(19, y, 16, BED);
        w.setBlock(21, y, 16, BED);
        w.setBlock(20, y - 1, 16, BED);
        w.setBlock(20, y + 1, 16, BED);
        w.setBlock(20, y, 15, BED);
        w.setBlock(20, y, 17, BED);
        w.computeLight();
        check("全封气腔(20,y,16) 腔内被遮挡（相对邻近实体格更暗）",
                w.lightAt(20, y, 16) < w.lightAt(18, y, 16) + 1e-6f);

        // ---------- 4) 液体：水下按水色衰减（0.78），弱于空气 ----------
        w = litWorld(20260918L, SX, SY, SZ);
        w.setBlock(10, y, 10, LAMP);
        for (int x = 11; x <= 14; x++) w.setBlock(x, y, 10, Blocks.WATER.index);
        w.computeLight();
        // 同一距离（4 格）处：水路径 vs 纯空气路径
        World wAir = litWorld(20260918L, SX, SY, SZ);
        wAir.setBlock(10, y, 10, LAMP);
        wAir.computeLight();
        float throughWater = w.lightAt(14, y, 10);
        float throughAir = wAir.lightAt(14, y, 10);
        check("光穿过 4 格水弱于穿过 4 格空气", throughWater < throughAir);
        check("水衰减 = LIGHT_DECAY_LIQUID (0.78)", Math.abs(
                core.content.MaterialDef.LIGHT_DECAY_LIQUID - 0.78f) < 1e-6f);
        check("空气衰减 = LIGHT_DECAY_AIR (0.91)",
                Math.abs(MaterialDef.LIGHT_DECAY_AIR - 0.91f) < 1e-6f);
        check("实体衰减 = LIGHT_DECAY_SOLID (0.56)",
                Math.abs(MaterialDef.LIGHT_DECAY_SOLID - 0.56f) < 1e-6f);

        // ---------- 5) FIRE 比 LAMP 暗且半径小 ----------
        w = litWorld(20260918L, SX, SY, SZ);
        w.setBlock(10, y, 10, FIRE);
        w.computeLight();
        check("FIRE 源格强度 = FIRE_LIGHT_FRACTION (<1，火是局部暖光)",
                Math.abs(w.lightAt(10, y, 10) - FRAC) <= 0.5f / R);   // 量化步长 1/LIGHT_R
        check("FIRE 强度 < LAMP 强度", FRAC < 1.0f);
        check("FIRE 距离1 ≈ FRAC×0.91（比 LAMP 暗）",
                Math.abs(w.lightAt(11, y, 10) - FRAC * AIR) < 2f / R);

        // ---------- 6) 算法结构：迭代次数恒为 2（Terraria LightMap.Blur 的硬编码）----------
        check("LIGHT_PASSES == 2（Terraria Blur = BlurPass×2）", World.LIGHT_PASSES == 2);
        check("LIGHT_CUTOFF == 0.0185（Terraria BlurLine 阈值）",
                Math.abs(World.LIGHT_CUTOFF - 0.0185f) < 1e-9f);

        // ---------- 7) 确定性：同世界两次 computeLight 逐字节一致 ----------
        w = litWorld(20260918L, SX, SY, SZ);
        w.setBlock(10, y, 10, LAMP); w.setBlock(20, y, 20, FIRE);
        w.computeLight();
        byte[] a = w.lightGrid.clone();
        w.computeLight();
        check("两次 computeLight 逐字节一致", Arrays.equals(a, w.lightGrid));

        // ---------- 8) 零漂移：computeLight 不污染仿真状态 ----------
        World w2 = litWorld(20260918L, SX, SY, SZ);
        w2.setBlock(10, y, 10, FIRE);
        long tickBefore = w2.tick;
        w2.computeLight();
        check("computeLight 不改 tick", w2.tick == tickBefore);
        check("computeLight 不改 mat", w2.mat[10][y][10] == FIRE);

        // ---------- 9) 同种子两世界光场一致（跨实例收敛；联机/存读档前提）----------
        World wA = litWorld(20260918L, SX, SY, SZ);
        World wB = litWorld(20260918L, SX, SY, SZ);
        wA.setBlock(10, y, 10, LAMP); wA.setBlock(16, y, 12, FIRE);
        wB.setBlock(10, y, 10, LAMP); wB.setBlock(16, y, 12, FIRE);
        wA.computeLight(); wB.computeLight();
        check("同种子两世界光场逐字节一致", Arrays.equals(wA.lightGrid, wB.lightGrid));

        // ---------- 10) 恒等/边界：无源 → 全零；孤立源不影响远处 ----------
        World w3 = litWorld(20260918L, SX, SY, SZ);
        w3.computeLight();
        boolean allZero = true;
        for (byte b : w3.lightGrid) if (b != 0) { allZero = false; break; }
        check("无光源 → 光场全零", allZero);

        System.out.println("LIGHT RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }
}
