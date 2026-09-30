package core.world;

/**
 * 面剔除规则（渲染几何用）：纯函数、无状态、零 RNG、<b>不进 hashState</b>。
 *
 * <p>方块的 6 个面是否发射，取决于「邻居是否遮住它」。旧规则只看 {@code mat == AIR}，
 * 于是水/玻璃<b>会遮挡</b>邻居的面 —— 水面之下的地面没有顶面、玻璃之后的墙没有面。
 * 一旦把水做成透明，穿透看过去就是空洞。</p>
 *
 * <p>新规则（本类的 {@link #visible}）：</p>
 * <ul>
 *   <li>邻居是 AIR / 越界 / 世界顶以上 → <b>可见</b>（外表面）；</li>
 *   <li>邻居<b>半透明</b>（水/玻璃，见 {@link Blocks.Block#translucent}）且与本方块<b>不同类</b>
 *       → <b>可见</b>（看穿：水下的地、玻璃后的内壁）；</li>
 *   <li>其余（邻居不透明，或同种半透明相接）→ <b>剔除</b>（同种液体内部面不发射）。</li>
 *   <li><b>第十六批（几何基座）</b>：<b>任一方非满格</b>（{@code shapeHeight < 1}，台阶/半砖）→ <b>可见</b>
 *       —— 半格的高度差必然留下空腔，整片剔除会让玩家"看穿地形"。</li>
 * </ul>
 *
 * <p><b>已知代价（诚实记录）</b>：本判据<b>不知道面的方向</b>（{@code e,p,i} 只穿邻居坐标），
 * 所以"半砖与满格相邻"时半砖的侧面会被多画一张 —— 它与满格邻居的侧面<b>共面</b>（都在同一平面）。
 * 观感上之所以看不出，靠两条：① 深度测试 {@code LEQUAL} + 确定的发射顺序 ⇒ 稳定覆盖、不闪烁；
 * ② {@code Game.putV}/{@code Chunk.putV} 对非满格方块<b>关掉逐格随机色差</b> ⇒ 两面色差只剩母题差异
 * （而台阶刻意复用了同族整块的母题）。要精确到"逐面"需给 {@link #visible} 加方向参数（36 处调用点），
 * 留到 STAIRS 批次再动。</p>
 *
 * <p>放在 {@code core}（而非 render）是刻意的：{@code core.sim} 门禁在 CORE 编译阶段就编好了，
 * 够不着 {@code render.lwjgl}（见 {@code tools/GreedyCheck} 为何单独放 tools）。规则放进 core，
 * 才能被 {@code core.sim.MeshCullTest} 无头守护。几何等价性仍然逐面向下兼容：不透明世界
 * 下新旧规则完全一致（半透明方块不存在时，本规则退化为「只看 AIR」）。</p>
 */
public final class FaceCull {

    private FaceCull() { }

    /** 6 邻方向（与 {@code Chunk.N} 同序：+x,-x,+y,-y,+z,-z）。 */
    public static final int[] DX = { 1, -1, 0, 0, 0, 0 };
    public static final int[] DY = { 0, 0, 1, -1, 0, 0 };
    public static final int[] DZ = { 0, 0, 0, 0, 1, -1 };

    /** 邻居方块索引；越界或 y 在 [0,SY) 之外一律按 AIR（世界之外的天空/侧面）。 */
    public static int blockAt(World w, int x, int y, int z) {
        if (y >= w.SY || y < 0) return Blocks.AIR.index;
        if (!w.inBounds(x, y, z)) return Blocks.AIR.index;
        return w.mat[x][y][z];
    }

    /**
     * 位于 (nx,ny,nz) 的方块索引，其朝向本方块的面是否可见。
     * {@code selfIdx} = 本方块索引（用于「同种半透明相接 → 剔除」判定）。
     *
     * <p><b>第十六批（几何基座）</b>：邻居若是<b>非满格</b>方块（台阶/半砖，{@code shapeHeight < 1}）
     * ⇒ 它<b>没有遮住整个面</b> ⇒ 可见。
     *
     * <p>为什么必须加这一条：半砖只占下半格，于是它<b>上方/旁边</b>的整块方块会露出半格空腔
     * （底面在 y、半砖顶面在 y-0.5）。旧规则只问「邻居不透明吗」⇒ 那些面被整片剔掉
     * ⇒ 玩家从下方/侧面看过去<b>直接看到空洞</b>（地形破了一个口）。
     *
     * <p><b>零回归论证（就写在这里）</b>：全表 {@code shapeHeight == 1.0f} 时本行恒为 false
     * ⇒ 与第十六批之前<b>逐字节等价</b>。既有门禁世界不含任何非满格方块 ⇒ 全部断言原样通过。
     *
     * <p><b>已知代价（诚实记录）</b>：判据不知道"面的方向"，所以"并排两个半砖"会在贴合处
     * 各画一张共面四边形（过绘、不产生空洞）。观感上之所以看不出，是因为
     * {@code Game.putV}/{@code Chunk.putV} 对非满格方块<b>关掉了 VoxelVariety 的逐格随机色差</b>
     * （两个面颜色完全一致 → 重叠不可见）。要精确到"逐面"需要给本函数加方向参数
     * （36 处调用点）—— 留到 STAIRS 批次再动。</p>
     */
    public static boolean visible(World w, int selfIdx, int nx, int ny, int nz) {
        int n = blockAt(w, nx, ny, nz);
        if (n == Blocks.AIR.index) return true;
        if (Blocks.byIndex(n).translucent) return n != selfIdx;
        // 第十六批（几何基座）：**任一方非满格** ⇒ 这个面就不一定被完全遮住 ⇒ 可见。两个方向都要管：
        //  · 邻居非满格：它只占半格，盖不住整个面；
        //  · 本格非满格（半砖）：它的顶面在 y+0.5，而**任何**邻居的底面都在 y+1 ⇒ 之间必有半格空腔
        //    （这一条最容易漏：只判"邻居矮"会把半砖自己的顶面剔掉，玩家从缝里看进去就是空的）。
        // 判据刻意选"宁可多画"：过绘无害，少画是**看得见的空洞**。代价见类注释的"已知代价"。
        if (!Blocks.isFullShape(selfIdx) || !Blocks.isFullShape(n)) return true;
        return false;
    }

    /** 该方块是否属于「透明 pass」（水/玻璃）。 */
    public static boolean transparentPass(int idx) {
        return Blocks.byIndex(idx).translucent;
    }
}
