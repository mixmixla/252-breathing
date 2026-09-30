package core.world;

/**
 * 四路调研步骤 4（Noita {@code materials_gfx/edge_files/}）：<b>跨材质交界过渡</b>的纯逻辑层。
 *
 * <h2>为什么需要它（与 {@link Autotile} 的分工）</h2>
 * {@link Autotile} 解决的是<b>同族内部</b>的连通感——同 group 相邻 → 无缝、异 group → 显缝。
 * 它只会让边缝"暗一点"，**不会换图案**。Noita 的那 158 张 {@code edge_*.png} 做的是另一件事：
 * 两个<b>不同材质</b>相接时，交界处铺一层**专门的过渡图案**（沙↔水、雪↔石、苔↔石各有专属花纹），
 * 让两种材质"咬合"起来，而不是像两张贴纸硬拼在一起。
 *
 * <h2>对 Noita 的有意偏离（两条，都写在明处）</h2>
 * <ol>
 *   <li><b>不照抄 158 张资产</b>。Noita 的 82 个基名是它自己材质表的产物（含 {@code soil_lush} /
 *       {@code earth_rainforest} / {@code steelpanel} 等我们根本没有的地貌族）。本作身份是
 *       "不加载外部素材"，所以过渡图同样**程序化烘焙**（见 {@code render.lwjgl.TextureAtlas#paintEdgeTiles}），
 *       且 tile 数量<B>由本作实际方块对推导</b>，而非硬编码一个外来数字。</li>
 *   <li><b>不做 {@code _hor}/{@code _ver} 全拆分</b>。Noita 为每个材质对准备了水平/垂直两套图案；
 *       我们的过渡是**按四边分别取权重**的顶点色调制 + 单张各向同性图案（见
 *       {@link #edgeInfluence}），用一张 tile 覆盖四个方向——收益的绝大部分在"有没有过渡"，
 *       而不在"过渡图案是否分横竖"，不值得为此翻倍 tile 数与选图逻辑。这一点是**有意的取舍**。</li>
 * </ol>
 *
 * <h2>核心机制</h2>
 * 给定方块 A 的一个暴露面，看它四条边各自外侧的邻居 B：
 * <ul>
 *   <li>B 是空气 → 不做过渡（那是外轮廓，交给 {@link Autotile} 的缝暗化）；</li>
 *   <li>B 与 A <b>同材质</b> → 不做过渡（内部无交界）；</li>
 *   <li>B 与 A <b>异材质</b> → 该边产生过渡，强度由材质对决定（{@link #pairStrength}）。</li>
 * </ul>
 * 四条边的强度写进一个 <b>4-bit 掩码</b>（与 {@link Autotile#edgeMask} 同位序，便于对照），
 * 顶点色按该顶点所处两条边的强度取<b>并集</b> {@code 1-(1-a)(1-b)}——于是角点自然比边中点更强，
 * 形成"咬合"梯度。
 *
 * <h2>路线 A（2026-09-21）：叠加而非替换</h2>
 * Noita 一手证据 {@code materials.xml} 中 64/64 处 {@code EdgeGraphics} 都是 {@code overwrite="0"}
 * —— 过渡图是<b>叠加（blend）</b>在材质之上的花纹，<b>不是替换 albedo</b>。初版实现把它当成
 * "换 tile"，结果是交界处整体暗 39%（材质 tile 亮度 SNOW=239/SAND=197/STONE=130/ASH=90，
 * 而 edge 图案均值仅≈0.5 的中性灰）——丢了材质身份。现在改为：
 * <ul>
 *   <li>{@link #scale} 返回一个<b>纯标量</b>（{@code (1-EDGE_DARKEN·infl)} 派生），烘进顶点色；</li>
 *   <li>图案仍在 albedo 之外<b>单独采样</b>，在 shader 里做叠加；</li>
 *   <li>{@code STRENGTH=0} 时 {@code scale} <b>精确返回 1.0f</b>（位级 no-op）。</li>
 * </ul>
 *
 * <p><b>纯函数 / 零漂移</b>：只读世界状态（邻居的方块索引），无 RNG、不写 {@code mat}/{@code tick}、
 * 不进 {@code hashState}。渲染层专用，与 {@link Autotile} 同一纪律。</p>
 */
public final class EdgeAtlas {

    private EdgeAtlas() { }

    /** 法线轴 na(0=x,1=y,2=z) 的两个共面轴：[ua, va]（与 {@link Autotile} 一致）。 */
    private static final int[][] INPLANE = { {1, 2}, {0, 2}, {0, 1} };

    /** 掩码位（与 {@link Autotile} 同位序）：bit0=+ua、bit1=-ua、bit2=+va、bit3=-va。 */
    public static final int BIT_POS_UA = 1;
    public static final int BIT_NEG_UA = 2;
    public static final int BIT_POS_VA = 4;
    public static final int BIT_NEG_VA = 8;

    // ------------------------------------------------------------------
    // 过渡强度：出厂值 0 = 关闭 = 逐字节等价旧行为（"加段而非加道"的落地纪律）
    // ------------------------------------------------------------------

    /**
     * 过渡总强度（0 = 完全关闭）。<b>出厂默认 0</b>：
     * 关闭时 {@link #edgeInfluence} 恒返回 0、顶点色一行不变 → 与改造前<b>逐字节等价</b>。
     * 这是本项目的落地纪律——新渲染特性必须能在默认态证明"零影响"，再单独开启验收视觉。
     *
     * <p>由 {@code Game} 的 {@code -Dbw.edge=<0..1>} 旋钮在启动时覆盖（QA 仪器用）。</p>
     */
    public static float STRENGTH = 0.0f;

    /** 单条异材质边的最大过渡量（强材质对，如沙↔水）。 */
    public static final float PAIR_STRONG = 0.72f;
    /** 弱材质对（双方都硬、都不透光，如石↔木）：过渡存在但克制。 */
    public static final float PAIR_WEAK = 0.30f;

    /** 强度钳制到 [0,1]（供 QA 旋钮使用）。 */
    public static float clampStrength(float s) {
        if (s < 0f) return 0f;
        if (s > 1f) return 1f;
        return s;
    }

    // ------------------------------------------------------------------
    // 材质对强度表：由「方块对」推导，而非硬编码 158 项
    // ------------------------------------------------------------------

    /**
     * 两个方块相接时的过渡强度（0 = 不产生过渡）。
     *
     * <p>判据是<b>视觉上的可分性</b>，不是材质 id 的大小关系：
     * <ul>
     *   <li>任一方为空气/水/玻璃这类"透"的介质 → 过渡弱（它们的交界交给雾/折射/透明混合，
     *       再叠过渡反而脏）；</li>
     *   <li>双方都属 <b>粉末/松散</b>族（沙/灰/雪/黏土）→ <b>强过渡</b>：松散物交界是颗粒互嵌，
     *       这是过渡图最有说服力的地方（Noita 的 {@code edge_sand} 正是全表最抢眼的一张）；</li>
     *   <li>一方松散、一方坚硬（沙↔石、雪↔石）→ 强过渡（堆积物压在地基上）；</li>
     *   <li>双方都坚硬（石↔土、石↔木）→ 弱过渡（硬拼，只需要一点点咬合感）。</li>
     * </ul>
     */
    public static float pairStrength(Blocks.Block a, Blocks.Block b) {
        if (a == null || b == null) return 0f;
        if (a == b) return 0f;                                  // 同材质：交给 Autotile
        if (isOpen(a) || isOpen(b)) return 0f;                   // 空气/水/玻璃：交给雾与透明混合
        boolean la = isLoose(a), lb = isLoose(b);
        if (la && lb) return PAIR_STRONG;                        // 松散↔松散：颗粒互嵌
        if (la != lb) return PAIR_STRONG;                        // 松散↔坚硬：堆积实体的接触面
        return PAIR_WEAK;                                        // 坚硬↔坚硬：克制的咬合
    }

    /** "透"的介质：空气 / 液体 / 半透明方块（玻璃）。它们的交界不走过渡图。 */
    private static boolean isOpen(Blocks.Block b) {
        return b == Blocks.AIR || b.liquid || b.translucent;
    }

    /**
     * "松散"介质：粉末质感、颗粒堆积、可被压开的一类。
     * 判据用<b>已有渲染属性的组合</b>（不新增字段）：非实心 或 低密度感的浅色系材质。
     * 这里显式列举——因为"松散感"是美术判断，做成隐式推导反而不可读、不可审计。
     */
    private static boolean isLoose(Blocks.Block b) {
        return b == Blocks.SAND || b == Blocks.SNOW || b == Blocks.ASH || b == Blocks.CLAY
                || b == Blocks.DIRT || b == Blocks.GRASS || b == Blocks.MOSS;
    }

    // ------------------------------------------------------------------
    // 边界判定与掩码
    // ------------------------------------------------------------------

    /** 邻居 (x,y,z) 的方块索引；越界或 y 越界一律按 AIR（与 {@link FaceCull#blockAt} 同纪律）。 */
    public static int blockAt(World w, int x, int y, int z) {
        if (y >= w.SY || y < 0) return Blocks.AIR.index;
        if (!w.inBounds(x, y, z)) return Blocks.AIR.index;
        return w.mat[x][y][z];
    }

    /**
     * 4 位掩码：位于 (bx,by,bz) 的方块，其 <b>na 轴上的暴露面</b>的四条边中，
     * 哪些边外侧是<b>异材质</b>邻居（需要过渡）。
     *
     * <p>位序与 {@link Autotile#edgeMask} 完全一致（bit0=+ua 1=-ua 2=+va 3=-va），
     * 于是两套 mask 可以直接对照打印。</p>
     */
    public static int edgeMask(World w, int bx, int by, int bz, int na) {
        Blocks.Block self = Blocks.byIndex(w.mat[bx][by][bz]);
        if (self == Blocks.AIR) return 0;
        int ua = INPLANE[na][0], va = INPLANE[na][1];
        int m = 0;
        int dx = (ua == 0 ? 1 : 0), dy = (ua == 1 ? 1 : 0), dz = (ua == 2 ? 1 : 0);
        if (pairStrength(self, Blocks.byIndex(blockAt(w, bx + dx, by + dy, bz + dz))) > 0f) m |= BIT_POS_UA;
        if (pairStrength(self, Blocks.byIndex(blockAt(w, bx - dx, by - dy, bz - dz))) > 0f) m |= BIT_NEG_UA;
        dx = (va == 0 ? 1 : 0); dy = (va == 1 ? 1 : 0); dz = (va == 2 ? 1 : 0);
        if (pairStrength(self, Blocks.byIndex(blockAt(w, bx + dx, by + dy, bz + dz))) > 0f) m |= BIT_POS_VA;
        if (pairStrength(self, Blocks.byIndex(blockAt(w, bx - dx, by - dy, bz - dz))) > 0f) m |= BIT_NEG_VA;
        return m;
    }

    /**
     * 四边形角点 (cu,cv)∈{0,1} 处的过渡影响量，**已乘上总强度 {@link #STRENGTH}**。
     *
     * <p>两条相邻边的强度按 <b>{@code 1-(1-a)(1-b)}</b> 合成（并集式）：只碰一条边 → 等于该边强度；
     * 两条边都异材质 → 严格更大、且以 1 为上界。这正是 Noita 角落过渡图比方边过渡图"更重"的同一件事，
     * 只是我们用连续函数而非第 3 张不同图案来表达。</p>
     *
     * <p><b>为什么不用乘法</b>：强度是 [0,1] 的分数，{@code a*b} 只会越乘越小 ——
     * 角点反而比边中点更弱，梯度方向反了。并集式与"两条边各自贡献一份过渡"的直觉一致。</p>
     *
     * <p><b>返回 0 当且仅当该角点不涉及任何异材质边</b>——保证非交界处逐字节零变化。</p>
     */
    public static float edgeInfluence(World w, Blocks.Block blk, int bx, int by, int bz,
                                      int na, float cu, float cv) {
        float s = STRENGTH;
        if (s <= 0f) return 0f;
        int m = edgeMask(w, bx, by, bz, na);
        if (m == 0) return 0f;
        float inv = 1f;                 // Π(1-edge_i)
        boolean touched = false;
        if (cu > 0.5f && (m & BIT_POS_UA) != 0) { inv *= 1f - pairStrength(blk, Blocks.byIndex(blockAt(w, bx + duX(na, 0), by + duY(na, 0), bz + duZ(na, 0)))); touched = true; }
        if (cu < 0.5f && (m & BIT_NEG_UA) != 0) { inv *= 1f - pairStrength(blk, Blocks.byIndex(blockAt(w, bx - duX(na, 0), by - duY(na, 0), bz - duZ(na, 0)))); touched = true; }
        if (cv > 0.5f && (m & BIT_POS_VA) != 0) { inv *= 1f - pairStrength(blk, Blocks.byIndex(blockAt(w, bx + duX(na, 1), by + duY(na, 1), bz + duZ(na, 1)))); touched = true; }
        if (cv < 0.5f && (m & BIT_NEG_VA) != 0) { inv *= 1f - pairStrength(blk, Blocks.byIndex(blockAt(w, bx - duX(na, 1), by - duY(na, 1), bz - duZ(na, 1)))); touched = true; }
        if (!touched) return 0f;
        float infl = (1f - inv) * s;
        return infl > 1f ? 1f : infl;   // 并集式天然 <1，此处仅兜底
    }

    private static int duX(int na, int which) { return INPLANE[na][which] == 0 ? 1 : 0; }
    private static int duY(int na, int which) { return INPLANE[na][which] == 1 ? 1 : 0; }
    private static int duZ(int na, int which) { return INPLANE[na][which] == 2 ? 1 : 0; }

    // ------------------------------------------------------------------
    // 顶点色调制（渲染层唯一入口）
    // ------------------------------------------------------------------

    /**
     * 过渡的<b>顶点色缩放因子</b>（路线 A 主入口）：{@code infl<=0} 时<b>精确返回 1.0f</b>。
     *
     * <p>这是"叠加"语义的载体：把 {@code scale} 乘进顶点色（由 {@code Chunk.putV} /
     * {@code Game.putV} 完成），shader 再把过渡图案乘回去。因为图案是<b>围绕
     * {@code EDGE_CENTER_F}=0.5</b> 波动的中性灰度，归一后均值 = 1.0，于是"叠加"在统计上
     * 不改变整体亮度（只改质感），不会重演初版"整体暗 39%"的事故。</p>
     *
     * <p>{@code infl==0} 走首行 return，<b>不做任何浮点运算</b> —— 这是"关闭即逐字节等价"的硬保证。</p>
     */
    public static float scale(float infl) {
        if (infl <= 0f) return 1.0f;
        float k = 1.0f - EDGE_DARKEN * infl;
        return k < 0f ? 0f : k;
    }

    /**
     * 把过渡影响量作用到顶点色上：<b>压暗 + 轻微降饱和</b>——交界处材质互相"吃"进去一点，
     * 而不是加一层亮边（加亮会在夜间显得发光、与块光语义冲突）。
     *
     * <p>{@code infl==0} 时返回值与入参<b>位级相同</b>（直接 return，不做任何浮点运算），
     * 这是"关闭即逐字节等价"的硬保证。</p>
     *
     * <p>路线 A 后 {@link #EDGE_SAT}=0（降饱和交给 shader 的图案采样表达），所以本方法退化为
     * 纯压暗 {@code rgb *= scale(infl)}；保留降饱和这一项是为了留一个可调旋钮，并把
     * "旧行为 = 新行为在 SAT=DESAT 时的特例"这一关系写在明处。</p>
     *
     * @param rgb  长度 ≥3 的顶点色暂存数组（原地修改）
     * @param infl {@link #edgeInfluence} 的返回值（0..1）
     */
    public static void applyEdge(float[] rgb, float infl) {
        if (infl <= 0f) return;                       // 位级 no-op
        float k = scale(infl);                        // 压暗
        rgb[0] *= k; rgb[1] *= k; rgb[2] *= k;
        float sat = EDGE_SAT;
        if (sat <= 0f) return;                        // 路线 A：默认不做顶点色降饱和
        float lum = (rgb[0] + rgb[1] + rgb[2]) * (1f / 3f);
        float desat = sat * infl;                     // 向自身亮度靠拢 = 降饱和
        rgb[0] += (lum - rgb[0]) * desat;
        rgb[1] += (lum - rgb[1]) * desat;
        rgb[2] += (lum - rgb[2]) * desat;
    }

    /**
     * 交界处最大压暗量（infl=1 时压到 84%）。
     *
     * <p><b>取值依据（本轮实测）</b>：初版给 0.38（压到 62%），在一面墙上有多种材质相接的
     * 场景下整片墙面被压暗 45%（实测 lum 150→85）——那不是"缝"而是"影子"。
     * 过渡的本质是"两种材质在缝上互相咬合"，**只需要一点点**：压暗 ≤16% 就足以把
     * 交界读出来，同时保留材质本身的明度。</p>
     */
    public static final float EDGE_DARKEN = 0.16f;
    /**
     * 顶点色<b>降饱和</b>旋钮（路线 A 默认 0）。
     *
     * <p>初版在顶点色上降饱和 22%，但那只在"换 tile"语义下才需要（因为换了图案就丢了板色，
     * 靠降饱和让缝"混"起来）。改成叠加层后，缝的观感由<b>图案采样</b>表达，顶点色再降饱和
     * 会把材质本色也洗掉 —— 所以默认关。想找回一点"互相吃掉"的味道可把它调到 0.22。</p>
     */
    public static final float EDGE_SAT = 0.0f;
    /** 初版（换 tile 语义）的降饱和量，保留作为旋钮参考值。 */
    public static final float EDGE_DESAT = 0.22f;

    // ------------------------------------------------------------------
    // 图案族选择（tile id 由 render 层的 TextureAtlas.edgeTile 映射，core 不反向依赖 render）
    // ------------------------------------------------------------------

    /**
     * 交界图案族编号（0..5），纯整数——<b>刻意不返回 tile id</b>，因为 core 层不能依赖
     * {@code render.lwjgl}（那是 CORE 编译阶段够不着的包，会砸掉无头门禁）。渲染层用
     * {@code TextureAtlas.edgeTile(fam)} 把它映射成图集槽位。
     *
     * <p>族语义见 {@code TextureAtlas#paintEdgeTiles}：0/1 颗粒互嵌、2/3 堆积咬合、4/5 硬拼裂纹；
     * 偶数=细纹、奇数=粗纹。这里选粗/细的依据是<b>强度</b>——强材质对用粗纹（存在感强），
     * 弱材质对用细纹（克制）。</p>
     */
    public static int familyFor(Blocks.Block a, Blocks.Block b) {
        float s = pairStrength(a, b);
        if (s <= 0f) return -1;
        int base;
        if (isLoose(a) && isLoose(b)) base = 0;                 // 颗粒互嵌
        else if (isLoose(a) != isLoose(b)) base = 2;            // 堆积咬合
        else base = 4;                                          // 硬拼裂纹
        return base + (s > PAIR_WEAK ? 1 : 0);                  // 强→粗纹(奇)，弱→细纹(偶)
    }

    /**
     * <b>整面</b>（而不是角点）的过渡图案族：返回该面四条边中强度最高者所对应的族，无过渡时 -1。
     *
     * <p><b>为什么必须按面而不是按角点</b>（血泪教训）：quad 的 UV 由 4 个顶点<b>线性插值</b>。
     * 若同一 quad 的两个角点分别取到"原方块 tile"和"过渡 tile"，且两者在图集里相隔很远，
     * 插值路径就会<b>横穿十几个不相干的 tile</b>（实测 11 个）→ 石头上长出草绿/土黄。
     * 按面统一族 ⇒ 4 个角的 UV 永远落在同一个 cell 内 ⇒ 插值不可能越界。</p>
     */
    public static int faceFamily(World w, Blocks.Block blk, int bx, int by, int bz, int na) {
        int m = edgeMask(w, bx, by, bz, na);
        if (m == 0) return -1;
        Blocks.Block best = null;
        float bestS = 0f;
        Blocks.Block[] cand = {
            (m & BIT_POS_UA) != 0 ? Blocks.byIndex(blockAt(w, bx + duX(na, 0), by + duY(na, 0), bz + duZ(na, 0))) : null,
            (m & BIT_NEG_UA) != 0 ? Blocks.byIndex(blockAt(w, bx - duX(na, 0), by - duY(na, 0), bz - duZ(na, 0))) : null,
            (m & BIT_POS_VA) != 0 ? Blocks.byIndex(blockAt(w, bx + duX(na, 1), by + duY(na, 1), bz + duZ(na, 1))) : null,
            (m & BIT_NEG_VA) != 0 ? Blocks.byIndex(blockAt(w, bx - duX(na, 1), by - duY(na, 1), bz - duZ(na, 1))) : null,
        };
        for (Blocks.Block nb : cand) {
            if (nb == null) continue;
            float s = pairStrength(blk, nb);
            // 平手时用方块索引打破（保证确定性：结果不依赖遍历出现的顺序）
            if (s > bestS || (s == bestS && best != null && nb.index < best.index)) { bestS = s; best = nb; }
        }
        return best == null ? -1 : familyFor(blk, best);
    }

    /** 该面是否需要过渡（掩码非 0 且总强度开启）。渲染层用它决定走普通 tile 还是 edge tile。 */
    public static boolean active(World w, int bx, int by, int bz, int na) {
        return STRENGTH > 0f && edgeMask(w, bx, by, bz, na) != 0;
    }

    /**
     * 角点 (cu,cv) 处的过渡图案族；该角点不涉及异材质边时返回 -1。
     *
     * <p><b>⚠️ 渲染层不要用这个方法选 tile</b>（保留它是为了门禁能断言"角点级语义"本身正确）。
     * 渲染必须用 {@link #faceFamily}：同 quad 的 4 个角若取到不同 tile，UV 线性插值会横穿
     * 图集里别的 cell（实测 11 个）→ 长出不相干颜色。详见 {@code Chunk.edgeUV}。</p>
     */
    public static int familyAt(World w, Blocks.Block blk, int bx, int by, int bz,
                               int na, float cu, float cv) {
        int m = edgeMask(w, bx, by, bz, na);
        if (m == 0) return -1;
        Blocks.Block air = Blocks.byIndex(Blocks.AIR.index);
        Blocks.Block best = null;
        float bestS = 0f;
        // 四边：取强度最高者
        Blocks.Block[] cand = {
            (m & BIT_POS_UA) != 0 ? Blocks.byIndex(blockAt(w, bx + duX(na, 0), by + duY(na, 0), bz + duZ(na, 0))) : air,
            (m & BIT_NEG_UA) != 0 ? Blocks.byIndex(blockAt(w, bx - duX(na, 0), by - duY(na, 0), bz - duZ(na, 0))) : air,
            (m & BIT_POS_VA) != 0 ? Blocks.byIndex(blockAt(w, bx + duX(na, 1), by + duY(na, 1), bz + duZ(na, 1))) : air,
            (m & BIT_NEG_VA) != 0 ? Blocks.byIndex(blockAt(w, bx - duX(na, 1), by - duY(na, 1), bz - duZ(na, 1))) : air,
        };
        // 只在本角点实际接触的那两条边里选（cu/cv 决定哪两条），保证"角点图案"与其邻边一致
        for (int i = 0; i < 4; i++) {
            boolean onU = (i < 2), pos = (i % 2 == 0);
            boolean touches = onU ? (cu > 0.5f) == pos : (cv > 0.5f) == pos;
            if (!touches) continue;
            Blocks.Block nb = cand[i];
            float s = pairStrength(blk, nb);
            if (s > bestS) { bestS = s; best = nb; }
        }
        if (best == null) return -1;
        return familyFor(blk, best);
    }
}
