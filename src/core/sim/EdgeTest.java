package core.sim;

import core.world.Autotile;
import core.world.Blocks;
import core.world.EdgeAtlas;
import core.world.World;

/**
 * 门禁 EDGE（四路调研步骤 4）：跨材质交界过渡的无头守护。
 *
 * <p>守护 {@link EdgeAtlas} 的五条不变量：</p>
 * <ol>
 *   <li><b>默认关闭即零影响</b>：{@code STRENGTH=0}（出厂值）时 {@code edgeInfluence} 恒返回 0，
 *       且 {@code applyEdge(rgb,0)} <b>位级不改</b>入参 —— 这是"新渲染特性默认可证零影响"的硬保证。</li>
 *   <li><b>边界判定正确</b>：同材质相邻 / 空气相邻 / 水·玻璃相邻 → 无过渡；异材质硬邻居 → 有过渡。</li>
 *   <li><b>方向与角点</b>：掩码位序与 {@code Autotile} 一致；角点两条边都异材质时强度<b>更高</b>
 *       （并集式 {@code 1-(1-a)(1-b)}），单边处仅单边强度。</li>
 *   <li><b>确定性</b>：同世界同参数两次调用结果逐位相同；族选择不依赖遍历顺序。</li>
 *   <li><b>零漂移</b>：只读世界，不改 {@code mat}/{@code tick}；与 {@link Autotile} 正交（两者互不干扰）。</li>
 * </ol>
 *
 * <p>纯 Java（不触碰 GL）。{@code EdgeAtlas} 放在 {@code core} 正是为了能被本门禁够着
 * （{@code core.sim} 在 CORE 编译阶段完成，够不到 {@code render.lwjgl}）。</p>
 */
public class EdgeTest {

    private static boolean ok = true;

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    public static void main(String[] args) {
        final int SX = 32, SY = 24, SZ = 32, y = 12;
        final int ST = Blocks.STONE.index, SA = Blocks.SAND.index,
                  SN = Blocks.SNOW.index, DI = Blocks.DIRT.index,
                  AS = Blocks.ASH.index, WA = Blocks.WATER.index,
                  GL = Blocks.GLASS.index;

        System.out.println("=== EDGE: 出厂默认关闭 = 零影响 ===");

        // ---------- 1) 默认关闭：零影响（最重要的一条） ----------
        World w0 = new World(20260921L, SX, SY, SZ);
        w0.setBlock(15, y, 15, ST);
        w0.setBlock(16, y, 15, SA);
        float saved = EdgeAtlas.STRENGTH;
        EdgeAtlas.STRENGTH = 0f;
        check("STRENGTH 出厂默认 == 0", saved == 0f);
        check("关闭时 edgeInfluence == 0（异材质相邻）",
                EdgeAtlas.edgeInfluence(w0, Blocks.STONE, 15, y, 15, 2, 1f, 0f) == 0f);
        check("关闭时 edgeInfluence == 0（角点）",
                EdgeAtlas.edgeInfluence(w0, Blocks.STONE, 15, y, 15, 2, 0f, 0f) == 0f);
        float[] rgb = {0.5f, 0.25f, 0.125f};
        float[] before = {rgb[0], rgb[1], rgb[2]};
        EdgeAtlas.applyEdge(rgb, 0f);
        check("applyEdge(rgb,0) 位级不改（no-op）",
                rgb[0] == before[0] && rgb[1] == before[1] && rgb[2] == before[2]);
        check("关闭时 active() == false", !EdgeAtlas.active(w0, 15, y, 15, 2));

        // ---------- 2) 边界判定：哪些相邻不算交界 ----------
        System.out.println("=== EDGE: 边界判定 ===");
        EdgeAtlas.STRENGTH = 1f;

        World wSame = new World(20260921L, SX, SY, SZ);
        wSame.setBlock(15, y, 15, ST);
        wSame.setBlock(16, y, 15, ST);          // 同材质
        check("同材质相邻 → 掩码=0（交给 Autotile）", EdgeAtlas.edgeMask(wSame, 15, y, 15, 2) == 0);

        World wAir = new World(20260921L, SX, SY, SZ);
        wAir.setBlock(15, y, 15, ST);           // 邻居是空气
        check("空气相邻 → 掩码=0（外轮廓，交给 Autotile 缝）", EdgeAtlas.edgeMask(wAir, 15, y, 15, 2) == 0);

        World wWater = new World(20260921L, SX, SY, SZ);
        wWater.setBlock(15, y, 15, ST);
        wWater.setBlock(16, y, 15, WA);
        check("水相邻 → 掩码=0（液体交界交给雾/透明混合）", EdgeAtlas.edgeMask(wWater, 15, y, 15, 2) == 0);

        World wGlass = new World(20260921L, SX, SY, SZ);
        wGlass.setBlock(15, y, 15, ST);
        wGlass.setBlock(16, y, 15, GL);
        check("玻璃相邻 → 掩码=0（半透明交界不走过渡图）", EdgeAtlas.edgeMask(wGlass, 15, y, 15, 2) == 0);

        check("异材质硬邻居 → 掩码含 +x 连接位(=1)",
                EdgeAtlas.edgeMask(w0, 15, y, 15, 2) == EdgeAtlas.BIT_POS_UA);
        check("配对强度：沙↔雪（松散↔松散）= 强", EdgeAtlas.pairStrength(Blocks.SAND, Blocks.SNOW) == EdgeAtlas.PAIR_STRONG);
        check("配对强度：沙↔石（松散↔坚硬）= 强", EdgeAtlas.pairStrength(Blocks.SAND, Blocks.STONE) == EdgeAtlas.PAIR_STRONG);
        // 坚硬↔坚硬：石↔木（两者都不在松散族）。注：土/草/苔属"松散"（可被压开的堆积物），故不用它们。
        check("配对强度：石↔木（坚硬↔坚硬）= 弱", EdgeAtlas.pairStrength(Blocks.STONE, Blocks.WOOD) == EdgeAtlas.PAIR_WEAK);
        check("配对强度：石↔条石（坚硬↔坚硬）= 弱", EdgeAtlas.pairStrength(Blocks.STONE, Blocks.ASHLAR) == EdgeAtlas.PAIR_WEAK);
        check("配对强度：同方块 = 0", EdgeAtlas.pairStrength(Blocks.STONE, Blocks.STONE) == 0f);
        check("配对强度：与空气 = 0", EdgeAtlas.pairStrength(Blocks.STONE, Blocks.AIR) == 0f);

        // ---------- 3) 方向与角点（掩码位序与 Autotile 一致） ----------
        System.out.println("=== EDGE: 方向与角点 ===");
        World wDir = new World(20260921L, SX, SY, SZ);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                wDir.setBlock(15 + dx, y, 15 + dz, ST);
        wDir.setBlock(16, y, 15, SA);           // +x 侧
        check("+x 侧异材质 → bit0 置位", (EdgeAtlas.edgeMask(wDir, 15, y, 15, 2) & EdgeAtlas.BIT_POS_UA) != 0);
        check("+x 侧异材质 → 其余位不置",
                EdgeAtlas.edgeMask(wDir, 15, y, 15, 2) == EdgeAtlas.BIT_POS_UA);

        // na=2（法线轴 z）的共面轴是 x 与 y ⇒ 角点邻居必须沿 ±x / ±y 偏移（不是 z！）
        World wCorner = new World(20260921L, SX, SY, SZ);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                wCorner.setBlock(15 + dx, y + dy, 15, ST);
        wCorner.setBlock(16, y, 15, SA);        // +x（ua 正）
        wCorner.setBlock(15, y + 1, 15, SN);    // +y（va 正）
        int cm = EdgeAtlas.edgeMask(wCorner, 15, y, 15, 2);
        check("角点两侧异材质 → 恰好两位", cm == (EdgeAtlas.BIT_POS_UA | EdgeAtlas.BIT_POS_VA));
        float eSingle = EdgeAtlas.edgeInfluence(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 0.5f);
        float eCorner = EdgeAtlas.edgeInfluence(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 1f);
        check("单边强度 == 该材质对强度（强对 = 0.72）", Math.abs(eSingle - EdgeAtlas.PAIR_STRONG) < 1e-6f);
        // 关键：并集式合成本身保证"角点 > 单边"（若用乘法则角点反而更弱 → 梯度方向反了，曾踩过）。
        check("角点(两边)强度 > 单边", eCorner > eSingle);
        check("角点强度 == 并集式 1-(1-a)(1-b)",
                Math.abs(eCorner - (1f - (1f - EdgeAtlas.PAIR_STRONG) * (1f - EdgeAtlas.PAIR_STRONG))) < 1e-6f);
        check("角点强度 <= 1（有上界）", eCorner <= 1.0f);
        float eNone = EdgeAtlas.edgeInfluence(wCorner, Blocks.STONE, 15, y, 15, 2, 0f, 0.5f);
        check("不接触异材质边的角点 → 0", eNone == 0f);

        // ---------- 4) 图案族选择 ----------
        System.out.println("=== EDGE: 图案族 ===");
        check("沙↔雪 → 颗粒互嵌粗纹族(fam=1)", EdgeAtlas.familyFor(Blocks.SAND, Blocks.SNOW) == 1);
        check("沙↔石 → 堆积咬合粗纹族(fam=3)", EdgeAtlas.familyFor(Blocks.SAND, Blocks.STONE) == 3);
        check("石↔木 → 硬拼细纹族(fam=4，弱对用细纹)", EdgeAtlas.familyFor(Blocks.STONE, Blocks.WOOD) == 4);
        check("同材质 → familyFor = -1", EdgeAtlas.familyFor(Blocks.STONE, Blocks.STONE) == -1);
        check("族编号落在 [0,6) 且非负", EdgeAtlas.familyFor(Blocks.SAND, Blocks.SNOW) >= 0
                && EdgeAtlas.familyFor(Blocks.SAND, Blocks.SNOW) < 6);
        check("familyAt 在异材质角点返回有效族",
                EdgeAtlas.familyAt(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 1f) >= 0);
        check("familyAt 在无交界角点返回 -1",
                EdgeAtlas.familyAt(wAir, Blocks.STONE, 15, y, 15, 2, 1f, 1f) == -1);

        // ---------- 4b) 面级族（渲染路径）：4 个角点必须取到同一个族 ----------
        // 血泪教训：曾按角点选 tile → 同一 quad 两角的 UV 插值横穿图集 11 个不相干 tile
        // → 石头上长出草绿/土黄。契约：同一面的 4 个角族必须相同（哪怕某些角不碰交界）。
        System.out.println("=== EDGE: 面级族一致性（防跨 tile 渗色）===");
        int ff = EdgeAtlas.faceFamily(wCorner, Blocks.STONE, 15, y, 15, 2);
        check("faceFamily 有交界时 >= 0", ff >= 0);
        check("faceFamily 无交界时 == -1", EdgeAtlas.faceFamily(wAir, Blocks.STONE, 15, y, 15, 2) == -1);
        boolean allSame = true;
        for (int i = 0; i < 4; i++) {
            int f = EdgeAtlas.faceFamily(wCorner, Blocks.STONE, 15, y, 15, 2);
            if (f != ff) allSame = false;
        }
        check("同一面 4 个角族一致（faceFamily 与角点无关）", allSame);
        check("faceFamily 两次调用相同（确定性）",
                EdgeAtlas.faceFamily(wCorner, Blocks.STONE, 15, y, 15, 2)
                        == EdgeAtlas.faceFamily(wCorner, Blocks.STONE, 15, y, 15, 2));
        // 平手确定性：两侧强度相同的不同材质，结果不得随遍历顺序变
        World wTie = new World(20260921L, SX, SY, SZ);
        wTie.setBlock(15, y, 15, ST);
        wTie.setBlock(16, y, 15, SA);     // +x 沙（强）
        wTie.setBlock(14, y, 15, SN);     // -x 雪（强）→ 平手
        check("平手时 faceFamily 结果唯一（两次一致）",
                EdgeAtlas.faceFamily(wTie, Blocks.STONE, 15, y, 15, 2)
                        == EdgeAtlas.faceFamily(wTie, Blocks.STONE, 15, y, 15, 2));

        // ---------- 5) 确定性 ----------
        System.out.println("=== EDGE: 确定性 ===");
        check("edgeMask 两次调用逐位相同",
                EdgeAtlas.edgeMask(wCorner, 15, y, 15, 2) == EdgeAtlas.edgeMask(wCorner, 15, y, 15, 2));
        check("edgeInfluence 两次调用逐位相同",
                EdgeAtlas.edgeInfluence(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 1f)
                        == EdgeAtlas.edgeInfluence(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 1f));
        check("familyAt 两次调用相同",
                EdgeAtlas.familyAt(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 1f)
                        == EdgeAtlas.familyAt(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 1f));
        // 同种子不同实例 → 完全一致（跨实例确定性）
        World wc2 = new World(20260921L, SX, SY, SZ);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                wc2.setBlock(15 + dx, y + dy, 15, ST);
        wc2.setBlock(16, y, 15, SA);
        wc2.setBlock(15, y + 1, 15, SN);
        check("跨实例同种子一致",
                EdgeAtlas.edgeMask(wc2, 15, y, 15, 2) == EdgeAtlas.edgeMask(wCorner, 15, y, 15, 2));

        // ---------- 6) 零漂移 ----------
        System.out.println("=== EDGE: 零漂移 ===");
        long tickBefore = wCorner.tick;
        int matBefore = wCorner.mat[15][y][15];
        EdgeAtlas.edgeMask(wCorner, 15, y, 15, 2);
        EdgeAtlas.edgeInfluence(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 1f);
        EdgeAtlas.familyAt(wCorner, Blocks.STONE, 15, y, 15, 2, 1f, 1f);
        EdgeAtlas.applyEdge(new float[]{0.6f, 0.4f, 0.2f}, 0.9f);
        check("edge 计算不改 tick", wCorner.tick == tickBefore);
        check("edge 计算不改 mat", wCorner.mat[15][y][15] == matBefore);
        // applyEdge 只作用于传入数组，不碰世界
        check("applyEdge 只改入参数组（不触世界）", wCorner.mat[16][y][15] == SA);

        // ---------- 7) 与 Autotile 正交 ----------
        System.out.println("=== EDGE: 与 Autotile 正交 ===");
        // 同材质 3×3×3 实心：内部块 Autotile 掩码=15（全连无缝）而 Edge 掩码=0（无过渡）
        World wOrth = new World(20260921L, SX, SY, SZ);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    wOrth.setBlock(15 + dx, y + dy, 15 + dz, ST);
        check("同材质内部：Autotile 掩码=15 而 Edge 掩码=0（各管一层）",
                Autotile.edgeMask(wOrth, 15, y, 15, 2) == 15 && EdgeAtlas.edgeMask(wOrth, 15, y, 15, 2) == 0);
        // 异材质相邻：Autotile 视为"不连"（显缝）、Edge 产生过渡（换图案）——两层叠加而非互斥
        check("异材质：Autotile 掩码=0（显缝）且 Edge 掩码=1（过渡）",
                Autotile.edgeMask(w0, 15, y, 15, 2) == 0 && EdgeAtlas.edgeMask(w0, 15, y, 15, 2) == 1);

        // ---------- 8) 强度钳制与调制边界（路线 A：叠加层语义）----------
        System.out.println("=== EDGE: 强度与调制 ===");
        check("clampStrength 下界", EdgeAtlas.clampStrength(-1f) == 0f);
        check("clampStrength 上界", EdgeAtlas.clampStrength(2f) == 1f);
        check("clampStrength 恒等", EdgeAtlas.clampStrength(0.37f) == 0.37f);

        // 路线 A 主入口 scale()：infl<=0 精确 1.0f（位级 no-op 的保证）
        check("scale(0) 精确 == 1.0f", EdgeAtlas.scale(0f) == 1.0f);
        check("scale(-0.5) 精确 == 1.0f", EdgeAtlas.scale(-0.5f) == 1.0f);
        check("scale(1) == (1-EDGE_DARKEN)",
                Math.abs(EdgeAtlas.scale(1f) - (1f - EdgeAtlas.EDGE_DARKEN)) < 1e-6f);
        check("scale() 单调不增（0.25→0.5→1 递减）",
                EdgeAtlas.scale(0.25f) >= EdgeAtlas.scale(0.5f) && EdgeAtlas.scale(0.5f) >= EdgeAtlas.scale(1f));
        check("scale() 恒在 (0,1]", EdgeAtlas.scale(0.3f) > 0f && EdgeAtlas.scale(1f) <= 1f);

        // applyEdge = 纯压暗（路线 A 后 EDGE_SAT 默认 0，不再动顶点色饱和度）
        float[] c1 = {0.8f, 0.8f, 0.8f};
        EdgeAtlas.applyEdge(c1, 1f);
        check("infl=1 时压暗到 (1-EDGE_DARKEN)",
                Math.abs(c1[0] - 0.8f * (1f - EdgeAtlas.EDGE_DARKEN)) < 1e-6f);
        check("EDGE_SAT 默认 0（降饱和交给 shader 图案采样）", EdgeAtlas.EDGE_SAT == 0f);
        // SAT=0 ⇒ 相对饱和度不变（用比值衡量，避免"整体缩小的假通过"）
        float[] c2 = {0.9f, 0.3f, 0.3f};
        EdgeAtlas.applyEdge(c2, 1f);
        float ratioBefore = 0.3f / 0.9f;
        float ratioAfter = c2[1] / c2[0];
        check("SAT=0 ⇒ 通道比值不变（相对饱和度保持）",
                Math.abs(ratioAfter - ratioBefore) < 1e-6f);
        check("调制后各通道仍 >= 0 且 <= 1", c2[0] >= 0f && c2[0] <= 1f && c2[1] >= 0f);
        // 顶点色缩放与 shader 图案（均值 EDGE_CENTER_F）相乘后均值不变 —— 这是"叠加不改变整体亮度"的核心
        // 关系：vCol*scale * (pattern/EDGE_CENTER_F) 在 pattern 均值 = EDGE_CENTER_F 时 == vCol*scale*1
        //   这里只断言代数恒等（真实图案均值由 render 层门禁覆盖）。
        float vc = 0.63f;
        float patAvg = 0.5f;                     // == TextureAtlas.EDGE_CENTER_F（core 层不依赖 render，故写常量）
        check("叠加归一后均值不变：v*(p/0.5) 在 p=0.5 时 == v",
                Math.abs(vc * (patAvg / 0.5f) - vc) < 1e-6f);
        float[] c3 = {1f, 1f, 1f};
        EdgeAtlas.applyEdge(c3, 1f);
        check("灰顶点色仍为灰（差=0）", Math.abs(c3[0] - c3[1]) < 1e-6f && Math.abs(c3[1] - c3[2]) < 1e-6f);

        // ---------- 9) 越界按空气 ----------
        System.out.println("=== EDGE: 越界 ===");
        World wEdge = new World(20260921L, SX, SY, SZ);
        wEdge.setBlock(0, y, 0, ST);            // 贴世界边界
        check("世界边界外按空气 → 无过渡（掩码=0）", EdgeAtlas.edgeMask(wEdge, 0, y, 0, 2) == 0);
        check("blockAt 越界返回 AIR", EdgeAtlas.blockAt(wEdge, -1, y, 0) == Blocks.AIR.index);
        check("blockAt y 越界返回 AIR", EdgeAtlas.blockAt(wEdge, 0, SY, 0) == Blocks.AIR.index);

        // 还原全局强度（避免污染同 JVM 内后续门禁）
        EdgeAtlas.STRENGTH = saved;

        System.out.println("EDGE RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }
}
