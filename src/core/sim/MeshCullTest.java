package core.sim;

import core.world.Blocks;
import core.world.FaceCull;
import core.world.World;

/**
 * 门禁 MESHCULL：面剔除规则（{@link FaceCull}）的无头守护。
 *
 * <p>守护的是 P2-3「水/玻璃透明」赖以成立的前提：<b>半透明方块不遮挡邻居的面</b>。
 * 旧规则只看 {@code mat == AIR}，水面之下的地面没有顶面 → 水一旦透明就看穿成空洞。
 * 本门禁逐条钉死新语义，并守住「不透明邻居仍然遮挡」这条回归线。</p>
 *
 * <p>纯函数 + 显式 setBlock 布景 → 确定性；不触碰 GL / 不写 hashState。</p>
 */
public class MeshCullTest {

    private static boolean ok = true;

    /**
     * 允许"非满格"（{@code shapeHeight < 1}）的方块白名单 —— 新增台阶/半砖<b>必须登记到这里</b>。
     * 与 {@code ContentTest.addedAfterMigration} 同一条纪律：登记项本身也要被断言（集合相等），
     * 所以"漏登记"会当场 FAIL，而不会变成"悄悄多了一个半格方块、没人知道"。
     */
    private static final java.util.Set<String> PARTIAL_EXPECTED =
            new java.util.LinkedHashSet<String>(java.util.Arrays.asList(
                    "STONE_SLAB", "WOOD_SLAB",                                        // 第十六批：几何基座
                    "COBBLE_SLAB", "STONE_BRICK_SLAB", "SANDSTONE_SLAB",              // 第十七批：台阶家族
                    "BRICK_SLAB", "IRON_BLOCK_SLAB",
                    "STONE_SLAB_TOP", "WOOD_SLAB_TOP", "SNOW_LAYER",                  // 第十七批：形状二元化
                    "STONE_STAIRS",                                                   // 第十七批：台阶（L 形）
                    "WOOD_STAIRS", "BRICK_STAIRS", "SANDSTONE_STAIRS",                // 第十八批：台阶填表
                    "RAIL"));                                                         // 第十九批：轨道（薄片）

    /**
     * **自带母题的独立形状**：它们不复用任何同族整块的贴图（在 {@code TextureAtlas} 里有专属
     * {@code case}），因此不参与"必须能推出同族整块"那条断言。登记项本身也被断言覆盖
     * （清单里的每一项都必须是真实的非满形状方块 —— 防白名单腐化）。
     */
    private static final java.util.Set<String> STANDALONE_SHAPE =
            new java.util.LinkedHashSet<String>(java.util.Arrays.asList("RAIL"));

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    public static void main(String[] args) {
        final int SX = 32, SY = 24, SZ = 32;
        World w = new World(20260916L, SX, SY, SZ);
        final int y = 20;                                   // 高空：邻居必为 AIR（与地形解耦）
        final int S = Blocks.STONE.index, WA = Blocks.WATER.index, GL = Blocks.GLASS.index;

        System.out.println("=== MESHCULL: FaceCull 语义 ===");

        // 1) 石头 + 上方水 → 石头顶面必须可见（「水下地面」的核心用例）
        w.setBlock(5, y, 5, S);
        w.setBlock(5, y + 1, 5, WA);
        check("石上水面：石头顶面可见(看穿水)", FaceCull.visible(w, S, 5, y + 1, 5));
        check("同处：水的底面被石头遮挡(不自发光面)", !FaceCull.visible(w, WA, 5, y, 5));
        check("水面朝空气可见", FaceCull.visible(w, WA, 5, y + 2, 5));

        // 2) 水-水相接 → 内部面剔除（否则水下满是重叠面）
        w.setBlock(8, y, 8, WA);
        w.setBlock(8, y + 1, 8, WA);
        check("水-水内部面剔除", !FaceCull.visible(w, WA, 8, y + 1, 8) && !FaceCull.visible(w, WA, 8, y, 8));

        // 3) 玻璃 + 下方石头 → 玻璃后的石头顶面可见
        w.setBlock(11, y, 11, S);
        w.setBlock(11, y + 1, 11, GL);
        check("玻璃后：石头顶面可见", FaceCull.visible(w, S, 11, y + 1, 11));
        check("玻璃-玻璃内部面剔除", !FaceCull.visible(w, GL, 11, y + 1, 11));

        // 4) 回归：不透明邻居仍然遮挡（旧行为不变）
        w.setBlock(14, y, 14, S);
        w.setBlock(14, y + 1, 14, S);
        check("不透明邻居仍然遮挡", !FaceCull.visible(w, S, 14, y + 1, 14));

        // 5) 越界 / 世界顶以上按 AIR（外表面可见）
        w.setBlock(17, SY - 1, 17, S);
        check("世界顶以上视为空气(顶面可见)", FaceCull.visible(w, S, 17, SY, 17));
        check("越界邻居视为空气", FaceCull.blockAt(w, -1, y, y) == Blocks.AIR.index);

        // 6) translucent 分类唯一性：全注册表里只有 WATER / GLASS 半透明
        int trCount = 0;
        boolean flagsOk = true;
        for (Blocks.Block b : Blocks.all()) {
            boolean expected = (b == Blocks.WATER || b == Blocks.GLASS);
            if (b.translucent != expected) { flagsOk = false; System.out.println("    flag wrong: " + b.id); }
            if (b.translucent) trCount++;
        }
        check("translucent 标记与预期一致(仅水/玻璃)", flagsOk);
        check("translucent 方块数 == 2", trCount == 2);
        check("透明 pass 分类与 translucent 一致", FaceCull.transparentPass(WA) && FaceCull.transparentPass(GL)
                && !FaceCull.transparentPass(S) && !FaceCull.transparentPass(Blocks.AIR.index));

        // 7) 第十六批（几何基座）：非满格方块（台阶/半砖）的面剔除语义。
        //    核心不是"好看"，而是**不许出现空洞**：半砖只占下半格 ⇒ 它与邻居之间必然留半格空腔，
        //    空腔两侧的面都必须画出来（旧规则"邻居不透明就剔除"会整片剔掉 → 玩家看穿地形）。
        final int SL = Blocks.STONE_SLAB.index;
        w.setBlock(20, y, 20, SL);
        check("半砖上方空气：顶面可见", FaceCull.visible(w, SL, 20, y + 1, 20));
        // 7b) 半砖上方压一个满格：半砖顶面在 y+0.5、满格底面在 y+1 ⇒ 中间有半格空腔 ⇒ 两面都要画
        w.setBlock(21, y, 21, SL);
        w.setBlock(21, y + 1, 21, S);
        check("半砖上压满格：半砖顶面仍可见(半格空腔)", FaceCull.visible(w, SL, 21, y + 1, 21));
        check("同处：满格底面可见(看进空腔)", FaceCull.visible(w, S, 21, y, 21));
        // 7c) 满格下方是半砖：满格底面在 y、半砖顶面在 y-0.5 ⇒ 同样有空腔
        w.setBlock(23, y, 23, S);
        w.setBlock(23, y - 1, 23, SL);
        check("满格下方半砖：满格底面可见(否则看穿)", FaceCull.visible(w, S, 23, y - 1, 23));
        // 7d) 半砖并排：刻意**放行**（过绘不空洞）—— 代价与理由见 FaceCull 类注释的"已知代价"
        w.setBlock(25, y, 25, SL);
        w.setBlock(26, y, 25, SL);
        check("半砖并排：放行(宁可过绘，不可空洞)", FaceCull.visible(w, SL, 26, y, 25));
        // 7e) 零回归的**全表**论证：除本批这两块外，其余方块的 shapeHeight 必须**全都**是 1.0
        //     —— 有它才能理直气壮地说"既有世界与本批逐字节等价"（而不是只论证我想到的那几块）。
        java.util.Set<String> partialIds = new java.util.LinkedHashSet<String>();
        boolean shapeOk = true;
        for (Blocks.Block b : Blocks.all()) {
            if (!b.isFullShape()) partialIds.add(b.id);
            // 区间合法性三条件缺一都会得到"画不出来的形状"：base ∈ [0,1)、height ∈ (0,1]、base+height ≤ 1
            if (!(b.shapeBase >= 0f && b.shapeBase < 1f && b.shapeHeight > 0f
                    && b.shapeHeight <= 1f && b.shapeBase + b.shapeHeight <= 1f + 1e-6f)) {
                shapeOk = false;
                System.out.println("    shape wrong: " + b.id + " = [" + b.shapeBase + ", " + b.shapeHeight + "]");
            }
        }
        check("形状区间全表合法 (base>=0, height>0, base+height<=1)", shapeOk);
        check("非满形状方块 == 显式清单（实得 " + partialIds + "）", partialIds.equals(PARTIAL_EXPECTED));
        // 命名约定也是契约：非满形状方块的 id 必须是 `<同族整块>_SLAB` / `_SLAB_TOP` / `_LAYER`，
        // 且同族整块必须**真的存在** —— 贴图母题复用（TextureAtlas.paintTile）正是从这条推导的；
        // 推不出就会走通用 case（症状：某档台阶/薄层长得和别的方块一样，谁也不会发现）。
        // 这条断言把"约定"变成构建期事实 —— 与 addedAfterMigration 同一精神。
        // 独立形状白名单：每一项都必须是**真实的非满形状方块**（防白名单腐化 —— 与 addedAfterMigration 同一纪律）
        boolean standaloneOk = true;
        for (String s2 : STANDALONE_SHAPE) {
            if (!partialIds.contains(s2)) {
                standaloneOk = false;
                System.out.println("    独立形状白名单里有不存在的项: " + s2);
            }
        }
        check("独立形状白名单每一项都是非满形状方块", standaloneOk);

        boolean motifOk = true;
        for (String sid : partialIds) {
            if (STANDALONE_SHAPE.contains(sid)) continue;   // 自带母题：不复用同族贴图
            String base = sid;
            if (base.endsWith("_SLAB_TOP"))       base = base.substring(0, base.length() - 9);
            else if (base.endsWith("_SLAB"))      base = base.substring(0, base.length() - 5);
            else if (base.endsWith("_LAYER"))     base = base.substring(0, base.length() - 6);
            else if (base.endsWith("_STAIRS"))    base = base.substring(0, base.length() - 7);
            if (base.equals(sid) || base.isEmpty() || Blocks.byId(base) == null) {
                motifOk = false;
                System.out.println("    非满形状方块无同族整块: " + sid + " -> " + base);
            }
        }
        check("非满形状方块都有同族整块（母题复用前提）", motifOk);

        // 7f) 第十七批（形状二元化）：上半砖 / 雪层 —— **同一套判据**，区别只是 shapeBase ≠ 0。
        //     这一段的重点是"base 一加进来，判据不要退化"：上半砖与下方满格之间同样有半格空腔。
        final int ST = Blocks.STONE_SLAB_TOP.index, LY = Blocks.SNOW_LAYER.index;
        w.setBlock(30, y, 20, ST);
        check("上半砖下方空气：底面可见", FaceCull.visible(w, ST, 30, y - 1, 20));
        w.setBlock(31, y, 20, ST);
        w.setBlock(31, y - 1, 20, S);                       // 下方压满格 → 中间仍是空腔
        check("上半砖下压满格：底面仍可见(空腔)", FaceCull.visible(w, ST, 31, y - 1, 20));
        check("同处：满格顶面可见(看进空腔)", FaceCull.visible(w, S, 31, y, 20));
        w.setBlock(33, y, 20, LY);                          // 雪层：上下都要能看见（薄层）
        check("雪层上方空气：顶面可见", FaceCull.visible(w, LY, 33, y + 1, 20));
        check("雪层下方空气：底面可见", FaceCull.visible(w, LY, 33, y - 1, 20));
        check("形状真的不同：雪层比半砖矮、上半砖 base=0.5",
                Blocks.SNOW_LAYER.shapeHeight < Blocks.HALF_BLOCK
                && Blocks.SNOW_LAYER.shapeBase == 0f
                && Blocks.STONE_SLAB_TOP.shapeBase == Blocks.HALF_BLOCK);

        // 8) 第二十批：**侧壁 UV 跟随实心区间**（{@link core.world.ShapeUV}）。
        //    这条规则本来只能活在渲染层里（够不着任何门禁）—— 把它搬进 core 就是为了能这样钉住它。
        //    要点是"几何多高，贴图就采多少"：半砖取下半段、上半砖取上半段、雪层取最底一条，
        //    而**台阶必须原样返回**（它是 L 形、不是矮块 —— 这正是用 (base,height) 表达形状的红利）。
        check("满格侧壁 UV 恒等（零回归）",
                core.world.ShapeUV.sideV(Blocks.STONE, 0.37f) == 0.37f
                && core.world.ShapeUV.sideV(Blocks.STONE, 1f) == 1f);
        check("下半砖侧壁 UV = 贴图下半段",
                Math.abs(core.world.ShapeUV.sideV(Blocks.STONE_SLAB, 0f)) < 1e-6f
                && Math.abs(core.world.ShapeUV.sideV(Blocks.STONE_SLAB, 1f) - 0.5f) < 1e-6f);
        check("上半砖侧壁 UV = 贴图上半段",
                Math.abs(core.world.ShapeUV.sideV(Blocks.STONE_SLAB_TOP, 0f) - 0.5f) < 1e-6f
                && Math.abs(core.world.ShapeUV.sideV(Blocks.STONE_SLAB_TOP, 1f) - 1f) < 1e-6f);
        check("雪层侧壁 UV = 贴图最底一条",
                Math.abs(core.world.ShapeUV.sideV(Blocks.SNOW_LAYER, 1f) - Blocks.LAYER_BLOCK) < 1e-6f);
        check("台阶侧壁 UV 不变（L 形不是矮块）",
                Math.abs(core.world.ShapeUV.sideV(Blocks.STONE_STAIRS, 0.7f) - 0.7f) < 1e-6f);
        check("满格退化：石-石仍然剔除（旧行为）", !FaceCull.visible(w, S, 14, y + 1, 14));

        System.out.println("MESHCULL RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }
}
