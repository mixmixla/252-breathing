package render.lwjgl;

import core.world.Blocks;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 程序化纹理图集（立项 A · MC_BENCHMARK_AUDIT 翻案项 1）。
 *
 * <p>动机：顶点色是<b>每面单色</b>，"每体素颗粒"只能缓解不能根治「色块塑料感」——
 * 根治方案是 MC 式<b>纹理采样</b>。本作的身份是"不加载外部素材"，不是"禁纹理"：
 * 因此图集在<b>启动时用固定种子整数哈希在 CPU 上烘焙</b>——零外部素材、零 RNG
 * （确定性纯函数，与 {@code Chunk.hash01} 同纪律），64×64 像素 tile × 16×16 格（ART_BIBLE §10 写实材质批次）。</p>
 *
 * <p>tile 寻址：{@code tile = blockIndex * 3 + kind}（kind: 0=侧面 / 1=顶面 / 2=底面），
 * 方块数 × 3 tile（当前 82 方块 → 占 0..245），另有保留 tile {@link #WHITE_TILE}（纯白微噪，远景剪影专用——
 * 剪影颜色全由顶点色承担，金饰 ×1.8 夜辉烘焙不变）。
 * <b>2026-09-23 图集 16→32 格（256→1024 槽位）</b>后保留区整体上移到方块区之后：
 * UI 占 496..511、UI_WHITE_TILE 512、边族占 520..537、裂纹占 552..561、detail 600、white 601。
 * 寻址一律走 {@link #cellX}/{@link #cellY}（唯一来源；禁止各处硬编码 {@code & 15 / >> 4}）。</p>
 *
 * <p>配色分工（关键约定）：实心方块 tile 烘焙<b>彩色图案</b>、顶点色退化为<b>灰度</b>（AO×方向明度）；
 * 风动植被（LEAF/WATER）与 FLOWER 相反——tile 烘焙<b>灰度图案</b>、顶点色保留<b>彩色</b>
 * （花田五色盘 {@code Chunk.BLOOM} 与树冠抖动得以保留）。两者在 shader 中相乘，各取所长。</p>
 *
 * <p>纯渲染层：不读不写任何仿真状态、不进指纹（四道基线逐字节不变）。</p>
 */
public final class TextureAtlas {

    public static final int TILE_PX = 64;
    /**
     * 图案尺度：本文件的花纹全部按「16px tile 时代」设计（图案坐标 0..15）。
     * tile 提到 64px 后，花纹必须用 {@code sx = x / S} 换算，否则纹样细 4 倍、且越界
     * （LAMP 辉光/FIRE 渐变会算出负值 → 整片黑块）。{@code S=4} 时 1 图案单位 = 4px。
     */
    public static final int S = TILE_PX / 16;
    /**
     * mipmap 防渗色（UE "Dilation/gutter" 思想）：每 tile 每边 8px 边缘扩展，高层级混合不跨 tile 采色。
     * <p><b>2026-09-20 由 4 提到 8</b>：CELL = 64+2G，要让 cell 边界在 mip 各级仍落在 texel 整数格上
     * （否则高层级会出现"半格错位渗色"），需要 {@code CELL % 2^L == 0}。G=4 → CELL=72=8×9 只对齐到 L3；
     * G=8 → CELL=80=16×5，对齐到 L4（此时每 tile 只剩 5px，再往下已无视觉意义）。</p>
     */
    public static final int GUTTER = 8;
    public static final int CELL_PX = TILE_PX + GUTTER * 2;   // 80px cell = 内容 64 + gutter 8×2
    /**
     * 图集边长（tile 槽位）：{@code ATLAS_TILES × ATLAS_TILES} 个槽位。
     * 列 / 行寻址走 {@link #cellX}/{@link #cellY}（{@code tile % ATLAS_TILES} / {@code tile / ATLAS_TILES}）
     * —— ⚠️ 用的是**取模 / 除法**，所以本常量**不必是 2 的幂**
     * （旧注释曾写 {@code & (n-1)} / {@code >> log2n}，与代码不符，已修）。
     *
     * <p><b>尺寸沿革</b>：16（256 槽）→ 32（1024 槽，2026-09-23）→ <b>48（2304 槽，2026-09-26）</b>。
     * 每次升格都是因为"方块 tile 撞上保留区"：方块寻址是 {@code blockIndex*3+kind} 的**连续编号**，
     * 而保留区（UI / 边族 / 裂纹 / …）必须占一段固定槽位 ⇒ 方块可用索引上限 = 保留区起点 / 3。
     * 16 时该上限只有 66，32 时 165，**48 时 378**。
     *
     * <p><b>2026-09-26 的关键改法</b>：同时把保留区基址从**硬编码数字**改成**由本常量推导**
     * （见下方"保留区"段）⇒ 本常量成为**唯一旋钮**：改它即自动迁移保留区 + 自动扩容方块区，
     * 不会再出现"保留区停在旧位置、被方块 tile 覆盖"（旧版踩过：EMERALD_BLOCK 侧 = UI_WHITE 槽 → 全白）。
     * 代价：图集边长 2560→3840px，显存 26 MB→59 MB（×2.25）。</p>
     */
    public static final int ATLAS_TILES = 48;                 // 48×48 = 2304 tile 槽位
    public static final int ATLAS_PX = CELL_PX * ATLAS_TILES; // 2560×2560（见 mipLevels 注释；NPOT 与旧 1280 同性质）
    /**
     * mip 级数：逐级减半，直到一个 cell 已不足 1px（80 → 6 级，level 6 每 tile 恰 1px）。
     * 与 {@link #bakeMipChainOffscreen} 的逐 cell 钳制配套，是"零跨 tile 串色"的前提。
     */
    public static final int MIP_LEVELS = mipLevels();

    // ---------- 保留区（**锚定图集中部，由 ATLAS_TILES 推导**） ----------
    // ⚠️ 2026-09-26（第二十四批"保留区迁移"）：以下基址原先是**硬编码数字**
    //    （UI 496 / UI_WHITE 512 / 边族 520 / 裂纹 552 / detail 600 / white 601 / 导线 620 / 端口 640）。
    //    硬编码的后果是：改 ATLAS_TILES 时它们**停在旧位置** ⇒ ①被"越过它们的方块 tile"覆盖、
    //    ②破坏"UI 落在图集中部"。改成推导式后，ATLAS_TILES 成为**唯一旋钮** ——
    //    改它即自动迁移全部保留区、自动扩容方块区。
    // ⚠️ **声明顺序是契约**（Java 静态初始化严格按声明序）：ATLAS_SLOTS → UI 区 → 其余按偏移。
    //    倒过来写是"非法前向引用"（编译期报错；本项目在第十七批踩过同型坑）。
    /** 图集 tile 槽总数。 */
    public static final int ATLAS_SLOTS = ATLAS_TILES * ATLAS_TILES;
    /** UI 区 slot 数（16 个连续槽：九宫格 + 纸纹 + 预留）。 */
    public static final int UI_TILE_COUNT = 16;
    /**
     * UI 区基址 —— 锚在**图集中部**。
     *
     * <p><b>为什么必须在中部</b>：UI 是<b>屏幕空间等比</b>绘制、只取 level 0 的锐利像素
     * （与地形的 mipmap + gutter + 逐 cell 钳制需求相反），靠图集边缘会在 REPEAT 采样下 wrap 到对侧；
     * 且 {@code ArtUiCheck} 有"UI 中央 UV 落 0.5 附近"的判据。
     * {@code ATLAS_SLOTS/2 - UI_TILE_COUNT} 使 UI 区紧贴中点左侧
     * （{@code ATLAS_TILES=32} 时 = 496..511；{@code 48} 时 = 1136..1151）。</p>
     */
    public static final int UI_TILE_BASE = ATLAS_SLOTS / 2 - UI_TILE_COUNT;
    /**
     * <b>严格纯白 tile</b>，HUD 顶点色的"恒等纹理" —— 紧跟 UI 区之后
     * （{@code UI_TILE_BASE + UI_TILE_COUNT}）。
     *
     * <p>为什么不能复用 {@link #WHITE_TILE}：那张是<b>纯白微噪</b>（0.96..1.04），是给远景剪影/字形
     * 点亮的"有质感白"。HUD 把它当恒等纹理用会在默认态造成<b>全屏约 1.5% 的确定性压暗</b>
     * （2026-09-21 实测：与改动前相差 23.025% 像素 / maxΔ 91 / 各通道均值比 0.985）。
     * 故单独开一张 <b>exact 255,255,255,255</b> 的 tile。</p>
     *
     * <p>恒等契约：HUD 默认发射器一律填本 tile 中心 UV → {@code texture()==vec4(1.0)} →
     * 与"无纹理 + 预乘"逐字节等价。</p>
     */
    public static final int UI_WHITE_TILE = UI_TILE_BASE + UI_TILE_COUNT;

    /** 保留 tile：纯白微噪（剪影 / 未覆盖兜底）。 */
    public static final int WHITE_TILE = UI_WHITE_TILE + 89;
    /** 保留 tile：高频灰度细节（AP-TX：近距 detail overlay，颜色由 shader 距离混合承担）。 */
    public static final int DETAIL_TILE = UI_WHITE_TILE + 88;
    /**
     * 红石导线「局部四臂」tile 库基址（第七批，2026-09-24）：{@link #WIRE_TILE_COUNT} 个连续 tile，
     * 索引 = <b>4 bit 连通掩码</b>（见 {@link #wireTile(int)}）。
     *
     * <p><b>为什么需要它</b>：第六批的红石导线只有"一张贯穿十字"的贴图 —— 无论有没有邻居，
     * 看起来都是同一个十字。玩家能靠 HUD 读出"通/断电"，却<b>看不出电路怎么连的</b>。
     * 要画出"六向连接外观"就必须让 tile 随<b>面内四邻是否也是导体</b>变化。
     *
     * <p><b>为什么是 16 个而不是 64 个</b>：本作的方块贴图是<b>逐面</b>画的，而一个面只能表达
     * <b>面内两个轴</b>的连接（垂直于面的那两向在该面上不可见）。所以每个面只需 4 bit
     * （局部 cu=0 / cu=1 / cv=0 / cv=1 四个边缘是否出臂）→ 16 种；同一套 16 张可被 <b>全部 6 个面复用</b>
     * （每面用自己的局部轴映射把世界方向翻到 (cu,cv) 上，映射表见 {@code Chunk.WIRE_ARMDIR}）。
     * 好处是既做到六向连通外观，又只吃 16 个槽位（对比"逐面各 16 张"要 96 个）。
     *
     * <p><b>放哪</b>：紧跟保留区段（{@code UI_WHITE_TILE + 108}），因此<b>不占用方块容量</b> ——
     * 方块索引上限由 {@link #UI_TILE_BASE} 决定，不因本功能下降。
     */
    public static final int WIRE_TILE_BASE = UI_WHITE_TILE + 108;
    /** 导线 tile 库大小（16 = 4 bit 掩码全组合）。 */
    public static final int WIRE_TILE_COUNT = 16;
    /**
     * 可定向方块的「<b>端口面</b>」tile 库基址（第十批，2026-09-24）：每个可定向方块一张，
     * 画在它<b>朝向的那一面</b>（{@link #frontTileFor}）。
     *
     * <p><b>为什么需要它</b>：朝向他个状态如果看不见，玩家就只能靠 HUD 猜"这个比较器哪面是输入"。
     * 端口图案让朝向<b>直接长在方块上</b>（MC 的活塞头/发射器口是同一手法）：找到有圆口的那一面，
     * 就知道信号往哪边出、活塞往哪边推。
     *
     * <p><b>为什么一张就够（不需要 6 张）</b>：正面本来就<b>由朝向决定</b>——"有端口图案的是哪个面"
     * 这条信息已经唯一确定了朝向（不需要在面内再表达旋转）。所以每方块 1 张，5 个可定向方块共 5 张。
     *
     * <p><b>放哪</b>：紧跟导线库之后的空档（{@code UI_WHITE_TILE + 128}），因此同样
     * <b>不占用方块容量</b>（容量仍由 {@link #UI_TILE_BASE} 决定）。次序 = {@code Blocks.ORIENTABLE} 的下标
     * —— <b>唯一来源</b>，不在本类再列一份方块名单。
     */
    public static final int FRONT_TILE_BASE = UI_WHITE_TILE + 128;
    /** 端口 tile 库大小 = 可定向方块数（由 {@code Blocks.ORIENTABLE} 决定，不在本类硬编码）。 */
    public static final int FRONT_TILE_COUNT = Blocks.ORIENTABLE.length;
    /**
     * 轨道「<b>形状</b>」tile 库（第二十五批）：{@link #RAIL_TILE_COUNT} 张 = 4 bit 邻居掩码全组合，
     * 索引 = {@link core.world.RailShape} 的掩码（bit0=+X bit1=-X bit2=+Z bit3=-Z）。
     *
     * <p><b>与红石导线臂形库（{@link #WIRE_TILE_BASE}）同一范式</b>：母题画"一条直轨"，
     * 这里按掩码只画<b>连出去的那几条臂</b> + 永远画中心（否则孤立轨是个空洞）。于是
     * "直线 / 弯 / 十字 / T 字"<b>共用这一个 16 张的库、零归一化逻辑</b>，且相邻两格的臂在
     * tile 边缘对接成连续轨道 —— 与导线"往哪连看得见"完全同构。
     *
     * <p><b>为什么不"每形状一张"</b>：那要写"掩码 → 规范形状"的归一化（还得分 直/弯/十字/T），
     * 而 16 张的边际成本只是同一段绘制跑 16 次（见 {@link #paintRailBank}），
     * 与导线库的取舍一致。判据本体（掩码 → 出口方向）仍是 {@link core.world.RailShape} 的纯函数。
     */
    public static final int RAIL_TILE_BASE = UI_WHITE_TILE + 148;
    /** 轨道形状 tile 库大小（16 = 4 bit 掩码全组合）。 */
    public static final int RAIL_TILE_COUNT = 16;
    /** 轨道掩码 → tile id（唯一入口；调用方只有 {@code Chunk.tileForFace} 一处）。 */
    public static int railTile(int mask) { return RAIL_TILE_BASE + (mask & 0xF); }
    /**
     * 破坏阶段裂纹 tile 基址（学 MC 的 {@code destroy_stage_0..9}）：{@link #CRACK_STAGES} 级，
     * 挖方块时把当前阶段的裂纹**叠在目标方块表面**（见 {@code Game.drawCrackOverlay}）。
     * 落位 = {@code UI_WHITE_TILE + 40}，与方块 tile 及其它保留区都不冲突。
     */
    public static final int CRACK_TILE_BASE = UI_WHITE_TILE + 40;
    public static final int CRACK_STAGES = 10;
    /**
     * 跨材质交界过渡 tile 基址（四路调研步骤 4，Noita {@code materials_gfx/edge_files/}）。
     *
 * <p>落位 = {@code UI_WHITE_TILE + 8}（与恒等纹理槽错开 8 槽）。
 * 注意：{@link #UI_WHITE_TILE} 是恒等纹理的保留槽，边族必须避开它（旧版曾误设为同槽导致
 * ARTUI 的"UI_WHITE_TILE 独占"判据 FAIL），故 base 与它错开。
 * 数量由 {@code core.world.EdgeAtlas} 的<b>材质对族</b>推导（{@link #EDGE_FAMILIES} 个），
 * <b>不照抄 Noita 的 158 张</b>——理由见 {@code EdgeAtlas} 类注释的"有意偏离"。</p>
 *
 * <p><b>2026-09-21 路线 A 改造</b>：由「每族 1 tile」改为「<b>每族 {@link #EDGE_TILES_PER_FAM} 个连续 tile</b>」。
 * 动机是 Noita 一手证据（{@code materials.xml} 中 64/64 处 {@code EdgeGraphics} 全为
 * {@code overwrite="0"}）——过渡图在 Noita 里是<b>叠加层</b>而非替换 albedo。既然要叠加，
 * 图案就得支持"与本体反复交错"，单个 tile 内自交不够自然，改成横条后 shader 可在段内
 * 按世界坐标取不同子 tile，得到"沿交界条纹重复"的效果（对齐 Noita 的 20×16 / 2×42 细条语义）。</p>
 *
 * <p>base 由 78 提到 <b>217</b>：217+6×3-1 = 234，正好吃满 217..234 的 18 个槽位。
 * <b>为什么要挪</b>：方块 tile 寻址是 {@code blockIndex*3+kind}，方块数从 26 膨胀到 60+ 后，
 * 旧 base 78（占 78..95）会与 blockIndex 26..31 的 tile 直接重叠 → 边族图案覆盖在方块纹理上。
 * 挪到 UI 区（200..215）之后、裂纹区（240..249）之前的空档，方块可一路长到索引 65（197 tile）都不冲突。</p>
     */
    public static final int EDGE_TILE_BASE = UI_WHITE_TILE + 8;
    // ⚠️ UI 区的**基址/数量**与 {@link #UI_WHITE_TILE} 已**上移**到保留区段最前（声明顺序是契约：
    //    它们必须早于引用它们的 WHITE_TILE / DETAIL_TILE）。UI 区的**内容布局**（相对索引）见下方。
    /** 九宫格各部位在该区内的相对索引（配合 {@link #UI_TILE_BASE}）。 */
    public static final int UI_CENTER = 0, UI_CORNER_TL = 1, UI_CORNER_TR = 2, UI_CORNER_BL = 3, UI_CORNER_BR = 4;
    public static final int UI_EDGE_T = 5, UI_EDGE_B = 6, UI_EDGE_L = 7, UI_EDGE_R = 8;
    public static final int UI_INNER_GLOW = 9, UI_PAPER = 10;
    /** 九宫格切片比例：每边占 tile 的 1/4（64px 下 = 16px 边框），中央可拉伸。 */
    public static final float UI_NINE_SLICE = 0.25f;
    // ⚠️ {@link #UI_WHITE_TILE} 已**上移**到保留区段最前（见文件上方"保留区"段）。
    /** 每族占用的连续 tile 数（横条：同一族第 k 段）。 */
    public static final int EDGE_TILES_PER_FAM = 3;
    /**
     * 过渡图案族数：按「松散 / 坚硬 / 有机」三类交界各一套，共 {@code 3×2}=6 张
     * （每类各备一张"细纹"与一张"粗纹"，由 {@code EdgeAtlas.pairStrength} 选强度后
     * 由顶点色调制决定实际观感）。
     *
     * <p>这远少于 Noita：我们<b>用的是同一套机制</b>（交界处铺专属图案 + 按材质对选图），
     * 但图案本身程序化、且用顶点色调制替代"每对一张"的组合爆炸。这是刻意的性价比取舍。</p>
     */
    public static final int EDGE_FAMILIES = 6;
    /**
     * 过渡图案的<b>中性均值</b>：图案灰度围绕它波动，shader 里按 {@code pattern / EDGE_CENTER_F}
     * 归一，于是"叠加"在统计上不改变整体亮度（只改质感）。{@code paintEdgeTiles} 的所有分支
     * 都以此为中心调参——改这里必须同步改图案生成，否则会出现整体提亮/压暗。
     */
    public static final float EDGE_CENTER_F = 0.5f;

    private static int mipLevels() {
        int l = 0;
        while ((ATLAS_PX >> (l + 1)) >= 1 && (CELL_PX >> (l + 1)) >= 1) l++;
        return l;
    }

    private TextureAtlas() { }

    // ---------- tile 寻址 ----------

    /** (blockIndex, faceDir) → tile id。dir 与 Chunk.N 语义一致：2=+y 顶 / 3=-y 底 / 其余侧面。 */
    public static int tileFor(int blockIndex, int dir) {
        int kind = (dir == 2) ? 1 : (dir == 3) ? 2 : 0;
        return blockIndex * 3 + kind;
    }

    /**
     * 导线连通掩码 → tile id（第七批）。
     *
     * <p>掩码语义（局部，与 {@link #WIRE_TILE_BASE} 的图案一致）：
     * <pre>
     *   bit0 (1)  = cu=0 边缘出臂      bit1 (2)  = cu=1 边缘出臂
     *   bit2 (4)  = cv=0 边缘出臂      bit3 (8)  = cv=1 边缘出臂
     * </pre>
     * 于是 {@code 0} = 孤立节点（只有中心方块），{@code 15} = 四面全连（贯穿十字）。</p>
     */
    public static int wireTile(int mask) { return WIRE_TILE_BASE + (mask & 0xF); }

    /**
     * 可定向方块的「端口面」tile；不可定向方块返回 <b>-1</b>（调用方据此跳过朝向查询）。
     *
     * <p>⚠️ 只允许在这里做 {@code 方块索引 → 端口 tile} 的映射（次序来源 = {@code Blocks.ORIENTABLE}），
     * 别在渲染路径里各写一份 —— 本项目已经栽过"同一件事有两份名单"（第九批断电不熄、第六批提亮只写一半）。
     */
    public static int frontTileFor(int blockIndex) {
        int ord = Blocks.orientableOrdinal(blockIndex);
        return ord < 0 ? -1 : FRONT_TILE_BASE + ord;
    }

    /**
     * tile 槽位 → 单元格**左上角像素坐标**（列 / 行）。**寻址唯一来源** —— 烘焙
     * （{@link #paintTile} 等）与全部离屏工具（{@code TileArtCheck}/{@code ArtUiCheck}/
     * {@code AtlasDump}/{@code UiTileDump}）都必须走本函数，禁止各自写 {@code & 15 / >> 4}。
     *
     * <p>2026-09-23 教训：图集边长 16 → 32 时，正因工具端各自硬编码 {@code & 15} 寻址，
     * 门禁一度读到错格（134 个 tile 里 58 个假 FAIL）。收敛到本函数后，"改边长"只改此一处。
     * 用 {@code % / /} 而非位运算 → 边长不必是 2 的幂。</p>
     */
    public static int cellX(int tile) { return (tile % ATLAS_TILES) * CELL_PX; }
    public static int cellY(int tile) { return (tile / ATLAS_TILES) * CELL_PX; }

    /** tile 内 UV（内容区偏移 GUTTER + 半像素内缩防渗色）。cu/cv ∈ [0,1] 为四边形角点参数。 */
    public static float uOf(int tile, float cu) {
        return (cellX(tile) + GUTTER + 0.5f + cu * (TILE_PX - 1)) / (float) ATLAS_PX;
    }

    public static float vOf(int tile, float cv) {
        return (cellY(tile) + GUTTER + 0.5f + cv * (TILE_PX - 1)) / (float) ATLAS_PX;
    }

    /**
     * 顶点色走<b>彩色</b>（纹理为灰度图案）的方块：风动植被 / FLOWER / WATER。
     * <p><b>修既有 bug</b>：WATER 此前不在列 → 水的顶点色走灰度、又叠加灰度波纹纹理，
     * 于是水被渲染成灰的（{@link Blocks#WATER} 的蓝 58/123/208 从未生效）。水应当吃自身方块色。</p>
     */
    public static boolean vertexColored(Blocks.Block blk) {
        return blk.wind || blk == Blocks.FLOWER || blk == Blocks.WATER;
    }

    /**
     * 交界过渡的 UV：{@code (u,v)} 落在 edge 族 tile 内容区，按<b>世界坐标</b>延续
     * （{@code wx/wz % TILE_PX} 取模），于是相邻方块上的过渡图案自然拼接、不出现逐块重复的"贴纸感"。
     *
     * <p><b>路线 A</b>：叠加层需要"沿交界条纹重复"的观感，所以 UV 先按世界坐标取模（跨块连续），
     * 再由 {@link #edgeTileSeg} 选段。这里 {@code seg} 由调用方给定（面级统一，避免同 quad 内跨 cell）。
     */
    public static float edgeU(int fam, int seg, int wx) {
        return uOf(edgeTileSeg(fam, seg), (Math.floorMod(wx, TILE_PX) + 0.5f) / (float) TILE_PX);
    }

    public static float edgeV(int fam, int seg, int wz) {
        return vOf(edgeTileSeg(fam, seg), (Math.floorMod(wz, TILE_PX) + 0.5f) / (float) TILE_PX);
    }

    // ---------- 自研艺术 UI：九宫格 UV ----------

    /**
     * UI 区 tile 的插槽 → 全局 tile id。
     * @param slot {@link #UI_CENTER} / {@link #UI_CORNER_TL} … 之一
     */
    public static int uiTile(int slot) {
        return UI_TILE_BASE + slot;
    }

    /**
     * 九宫格某一部位的局部 UV。{@code cu/cv ∈ [0,1]} 是该部位在<b>图集 tile 内</b>的参数坐标，
     * 但已按 {@link #UI_NINE_SLICE} 把整张贴图切成 3×3：中央格取中间 1/2 区域（可拉伸），
     * 角/边取靠外缘的那 1/4（不拉伸，边框宽度恒定）。
     *
     * <p><b>为什么要切片</b>：整张贴图直接拉伸会把 2px 描边拉成 N 像素宽；九宫格保证
     * "角不拉伸、边单轴拉伸、中央双轴拉伸"，于是任意尺寸面板的描边粗细都保持一致 —— 这是
     * MC/SLG 类 UI 的标准做法。</p>
     *
     * @param slot 部位（{@link #UI_CORNER_TL} 等）
     * @param cu   [0,1] 该部位内的横向参数
     * @param cv   [0,1] 该部位内的纵向参数
     */
    public static float uiU(int slot, float cu) {
        float lo, hi;
        if (slot == UI_CORNER_TL || slot == UI_CORNER_BL || slot == UI_EDGE_L) { lo = 0f; hi = UI_NINE_SLICE; }
        else if (slot == UI_CORNER_TR || slot == UI_CORNER_BR || slot == UI_EDGE_R) { lo = 1f - UI_NINE_SLICE; hi = 1f; }
        else { lo = UI_NINE_SLICE; hi = 1f - UI_NINE_SLICE; }
        return uOf(uiTile(slot), lo + (hi - lo) * cu);
    }

    public static float uiV(int slot, float cv) {
        float lo, hi;
        if (slot == UI_CORNER_TL || slot == UI_CORNER_TR || slot == UI_EDGE_T) { lo = 0f; hi = UI_NINE_SLICE; }
        else if (slot == UI_CORNER_BL || slot == UI_CORNER_BR || slot == UI_EDGE_B) { lo = 1f - UI_NINE_SLICE; hi = 1f; }
        else { lo = UI_NINE_SLICE; hi = 1f - UI_NINE_SLICE; }
        return vOf(uiTile(slot), lo + (hi - lo) * cv);
    }

    // ---------- 烘焙 ----------

    /** 像素级确定性哈希（固定种子整数混洗，与 Chunk.hash01 同纪律；零 RNG）。 */
    private static float hash(int x, int y, int s) {
        int h = x * 374761393 + y * 668265263 + s * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0x7fffffff) / (float) 0x7fffffff;
    }

    /**
     * 无头（不触碰 GL）：烘焙 albedo 图集到内存并返回 RGBA 缓冲。
     * 与 {@link #bake()} 走<b>同一套</b> {@code paintTile / paintDetailTile / dilateGutters} ——
     * <b>所见即 GL 所上传</b>，因此可在无 GPU 环境把它导成 PNG 审阅纹理细节（`tools/AtlasDump`）。
     */
    public static ByteBuffer bakeAlbedoOffscreen() {
        // 堆缓冲（非 direct）：烘焙是纯 CPU 数学，用堆内存即可 —— 这样审阅路径<b>零原生依赖</b>
        // （不需要 lwjgl.dll），无 GPU/无原生库的环境也能导出图集；GL 上传时 LWJGL 会自行拷贝。
        ByteBuffer px = ByteBuffer.allocate(ATLAS_PX * ATLAS_PX * 4);
        // 1) 默认填白微噪（覆盖 WHITE_TILE 与一切未显式绘制的槽位；gutter 区同样兜底）
        for (int t = 0; t < ATLAS_TILES * ATLAS_TILES; t++) {
            int tx0 = cellX(t), ty0 = cellY(t);
            for (int y = 0; y < CELL_PX; y++)
                for (int x = 0; x < CELL_PX; x++) {
                    float v = 0.96f + 0.08f * hash(x, y, t * 131 + 7);
                    put(px, tx0 + x, ty0 + y, v, v, v, 1f);
                }
        }
        // 2) 逐方块绘制 3 tile（侧/顶/底）+ 细节 tile
        for (int bi = 0; bi < Blocks.count(); bi++) {
            Blocks.Block blk = Blocks.byIndex(bi);
            if (blk == Blocks.AIR) continue;
            for (int kind = 0; kind < 3; kind++) paintTile(px, bi * 3 + kind, blk, kind);
        }
        paintDetailTile(px, DETAIL_TILE);
        paintCrackTiles(px);
        paintEdgeTiles(px);
        paintUiTiles(px);
        paintWireBank(px);          // 第七批：导线「局部四臂」tile 库（16 张，随连通性选）
        paintFrontBank(px);         // 第十批：可定向方块的「端口面」tile 库（6 张，画在朝的那一面）
        paintRailBank(px);          // 第二十五批：轨道「形状」tile 库（16 张，随四邻掩码选）
        // 3) gutter 扩展（UE Dilation 思想）：内容边缘像素向外复制 2px → mipmap 高层级不跨 tile 渗色
        dilateGutters(px);
        return px;
    }

    /**
     * 破坏阶段裂纹（学 MC {@code destroy_stage_0..9} + {@code RenderType.destroy}）。
     *
     * <p>MC 每次挖方块时，会在方块表面叠一张随进度递增的裂纹贴图（10 级），而不是"整块变暗"——
     * 好处是**方块本身的纹理仍然看得见**，玩家能读出"还差多少"。</p>
     *
     * <p>本作零外部素材，因此程序化生成（固定种子整数哈希，与 {@link #hash} 同纪律，零 RNG）：
     * 先铺 14 条随机游走折线并记下每条覆盖像素的<b>最小序号</b>（越早出现的越先裂），
     * 再按阶段 {@code s} 输出"序号 ≤ s 的像素"——于是第 s 级 = 前 s+1 条裂纹，且后段裂纹更粗更长，
     * 读得出"这一级又新裂了一道"。<b>alpha 当遮罩</b>（裂纹=1、其余=0），颜色与不透明度由 shader 给。</p>
     */
    private static void paintCrackTiles(ByteBuffer px) {
        final int N = 14, U = 16;                    // 图案坐标 0..15（与 paintTile 的 sx/sy 同尺度）
        int[][] rank = new int[U][U];
        for (int y = 0; y < U; y++) for (int x = 0; x < U; x++) rank[y][x] = 999;
        for (int i = 0; i < N; i++) {
            float x = 2f + 12f * hash(i, 1, 0x51A9);
            float y = 2f + 12f * hash(i, 2, 0x51A9);
            float ang = 6.2831855f * hash(i, 3, 0x51A9);
            int len = 5 + (int) (hash(i, 4, 0x51A9) * 8f);      // 5..12 图案单位
            int wide = (i >= 8) ? 1 : 0;                        // 后段裂纹更粗
            for (int s = 0; s <= len; s++) {
                ang += (hash(i, 6 + s, 0x51A9) - 0.5f) * 0.9f;  // 折线抖动
                x += (float) Math.cos(ang) * 0.85f;
                y += (float) Math.sin(ang) * 0.85f;
                int gx = (int) Math.floor(x), gy = (int) Math.floor(y);
                for (int oy = -wide; oy <= wide; oy++) {
                    for (int ox = -wide; ox <= wide; ox++) {
                        int cx = gx + ox, cy = gy + oy;
                        if (cx < 0 || cy < 0 || cx >= U || cy >= U) continue;
                        if (rank[cy][cx] > i) rank[cy][cx] = i;
                    }
                }
            }
        }
        for (int stage = 0; stage < CRACK_STAGES; stage++) {
            int tile = CRACK_TILE_BASE + stage;
            int tx0 = cellX(tile) + GUTTER, ty0 = cellY(tile) + GUTTER;
            for (int y = 0; y < TILE_PX; y++) {
                for (int x = 0; x < TILE_PX; x++) {
                    boolean crack = rank[y / S][x / S] <= stage;
                    // 裂纹芯再压暗一档，叠在浅色方块上不至于发灰
                    float v = crack ? 0.30f : 0.0f;
                    put(px, tx0 + x, ty0 + y, v, v, v, crack ? 1f : 0f);
                }
            }
        }
    }

    // ---------- 跨材质交界过渡（四路调研步骤 4，Noita edge_files）----------

    /**
     * 交界过渡图案。<b>灰度图案（均值 {@link #EDGE_CENTER_F}=0.5 的中性灰）</b>——路线 A 后它不再当
     * albedo 用，而是由 world shader 在 albedo 之外<b>单独采样并乘上去</b>（{@code vEdge.xy} 是 UV、
     * {@code vEdge.z} 是权重），归一因子 {@code 1/EDGE_CENTER_F} 保证"叠加不改变整体亮度"。
     * 交界处的压暗仍由顶点色承担（见 {@code core.world.EdgeAtlas.scale}）。</p>
     *
     * <p>每族占 {@link #EDGE_TILES_PER_FAM}=3 个连续 tile（横条）：k=0/1/2 是同族的相位变体，
     * 让条纹沿交界滚动而非整面同一张贴纸 —— 对齐 Noita"细条沿边界重复"的一手形态。</p>
     *
     * <p>六张族（{@link #EDGE_FAMILIES}）：</p>
     * <ul>
     *   <li>0/1 · <b>颗粒互嵌</b>（松散↔松散）：颗粒大小两级，读作"沙渗进雪里"；</li>
     *   <li>2/3 · <b>堆积咬合</b>（松散↔坚硬）：底部沉积 + 向上凸出的小块，读作"沙压在石上"；</li>
     *   <li>4/5 · <b>硬拼咬合</b>（坚硬↔坚硬）：克制的碎裂纹两级。</li>
     * </ul>
     *
     * <p>图案本身**各向同性**（不区分横竖）——这是对 Noita {@code _hor}/{@code _ver} 全拆分的
     * 有意简化，理由见 {@code EdgeAtlas} 类注释。图案为<b>中性灰</b>（均值 ≈0.5），
     * 这样"乘上去"只改质感、不改整体明度（明度变化由 {@code EdgeAtlas} 的压暗量单独控制）。
     *
     * <p>固定种子整数哈希，零 RNG、零外部素材——与 {@link #paintTile} 同纪律。</p>
     */
    private static void paintEdgeTiles(ByteBuffer px) {
        for (int fam = 0; fam < EDGE_FAMILIES; fam++) {
            // 两级颗粒尺度：偶数族=细、奇数族=粗（同族两张，供不同强度取用）
            int coarse = 1 + (fam & 1);                       // 1 或 2（图案单位）
            boolean cracked = fam >= 4;                       // 4/5 = 硬拼裂纹族
            // 每族 3 个连续 tile（路线 A 横条）：k=0 主图案、k=1/k=2 是同族的"相位偏移"变体，
            // 让 shader 能沿交界在不同子 tile 间切换 → 条纹重复而不是整面同一张贴纸。
            for (int k = 0; k < EDGE_TILES_PER_FAM; k++) {
                int tile = edgeTileSeg(fam, k);
                int tx0 = cellX(tile) + GUTTER, ty0 = cellY(tile) + GUTTER;
                int phase = k * 5 + fam * 3;                  // 每段独立的整数相位（确定性）
                for (int y = 0; y < TILE_PX; y++) {
                    for (int x = 0; x < TILE_PX; x++) {
                        int sx = x / S, sy = y / S;
                        float g;
                        if (cracked) {
                            // 硬拼：低频裂纹（斜向线段）+ 轻噪。裂而不断 → 只在中段显形
                            float d = Math.abs(((sx * 7 + sy * 5 + phase) % 16) - 8) / 8f;
                            g = EDGE_CENTER_F + 0.26f * (1f - d) * (hash(sx / 2, sy / 2, 0xED6E + fam + k * 32) * 0.8f + 0.2f)
                                    + 0.10f * (hash(sx, sy, 0xED6F + fam + k * 32) - 0.5f);
                        } else if (fam < 2) {
                            // 颗粒互嵌：两级颗粒团 + 细噪（颗粒尺度由 coarse 决定）
                            float lump = hash(sx / coarse, sy / coarse, 0xEDA1 + fam + k * 32);
                            g = EDGE_CENTER_F + 0.30f * (lump - 0.5f) + 0.14f * (hash(sx, sy, 0xEDA2 + fam + k * 32) - 0.5f);
                        } else {
                            // 堆积咬合：底重上轻的沉积带 + 向上凸出小块
                            float band = 1f - ((sy + phase) % 16) / 15f;   // 下亮上暗（沉积感），相位沿 y 滚动
                            float bump = hash(sx / 2, (sy + phase) / 2, 0xEDB1 + fam + k * 32) > 0.72f ? 1.22f : 1f;
                            g = (EDGE_CENTER_F - 0.04f + 0.20f * band) * bump + 0.12f * (hash(sx, sy, 0xEDB2 + fam + k * 32) - 0.5f);
                        }
                        if (g < 0f) g = 0f; else if (g > 1f) g = 1f;
                        put(px, tx0 + x, ty0 + y, g, g, g, 1f);
                    }
                }
            }
        }
    }

    /** 交界过渡族 → 该族主 tile id（{@code fam} 应来自 {@code EdgeAtlas} 的族选择）。 */
    public static int edgeTile(int fam) {
        return edgeTileSeg(fam, 0);
    }

    /** 交界过渡族 + 段内索引 {@code k}（0..{@link #EDGE_TILES_PER_FAM}-1）→ tile id。 */
    public static int edgeTileSeg(int fam, int k) {
        int f = fam % EDGE_FAMILIES;
        if (f < 0) f += EDGE_FAMILIES;
        int kk = k % EDGE_TILES_PER_FAM;
        if (kk < 0) kk += EDGE_TILES_PER_FAM;
        return EDGE_TILE_BASE + f * EDGE_TILES_PER_FAM + kk;
    }

    // ---------- mip 链（2026-09-20 学自 MC MipmapGenerator / UE 纹理构建链）----------

    /**
     * sRGB→线性 查表。来由：**平均必须在线性光里做**——MC {@code MipmapGenerator} 的 {@code POW22}
     * 就是干这个的。在 sRGB 空间直接盒滤波会让细节一缩就系统性变暗（0/1 棋盘平均得 0.5；
     * 正确结果是线性平均 0.5 → sRGB 0.73），这正是一直以来"远处地形偏暗发灰"的一个隐藏来源。
     */
    private static final float[] POW22 = new float[256];
    /** 线性→sRGB 量化（0..255）查表，配 {@link #POW22} 成对使用。索引 = 线性值量化到 0..255。 */
    private static final float[] INV22 = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            POW22[i] = (float) Math.pow(i / 255.0, 2.2);          // sRGB 字节 → 线性
            INV22[i] = (float) (255.0 * Math.pow(i / 255.0, 1.0 / 2.2));   // 线性 → sRGB 字节
        }
    }

    /** 线性光值（0..1）→ sRGB 字节 0..255。 */
    private static int encLin(float lin) {
        int i = (int) (lin * 255f + 0.5f);
        if (i < 0) i = 0; else if (i > 255) i = 255;
        return (int) (INV22[i] + 0.5f);
    }

    private static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 逐级 mip 链（纯 CPU / 零 GL / 确定性）。返回 {@code [0..MIP_LEVELS]}，每级尺寸 = {@code ATLAS_PX >> L}。
     * <b>取代 {@code glGenerateMipmap}</b>——GL 那个整图盒滤波有三个做不到的事，全部学自 MC {@code MipmapGenerator}：
     * <ol>
     *   <li><b>不跨 tile</b>：每级按 cell 钳制取样窗口。GL 的整图滤波在 cell 边界会把相邻 tile（图集里
     *       语义毫不相干的另一块，如草顶挨着沙底）平均进来 → 中远距 tile 边缘串色。MC 靠"拼接前逐 sprite
     *       生成 mip + {@code smallestFittingMinTexel} 对齐 2^mipLevel"根治；我们用等价做法：逐级钳到 cell。</li>
     *   <li><b>线性光平均</b>：RGB 先经 {@link #POW22} 转线性再平均再回 sRGB（见 POW22 注释）。</li>
     *   <li><b>跳过全透明 tap</b>：镂空 tile（玻璃框 / 花）的"透明黑"绝不进 RGB 平均，杜绝黑边——
     *       这是 MC {@code alphaBlend} 里 `if (像素>>24 != 0)` 那道门。</li>
     * </ol>
     * alpha 走<b>线性</b>平均（alpha 是覆盖率、不是颜色，故不做 gamma 变换；与 MC 的差别仅此一处，特此注明）。
     */
    public static ByteBuffer[] bakeMipChainOffscreen(ByteBuffer l0) {
        ByteBuffer[] lv = new ByteBuffer[MIP_LEVELS + 1];
        lv[0] = l0;
        for (int L = 1; L <= MIP_LEVELS; L++) {
            int srcSize = ATLAS_PX >> (L - 1), dstSize = ATLAS_PX >> L;
            if (dstSize < 1 || srcSize < 1) break;
            ByteBuffer s = lv[L - 1];
            ByteBuffer d = ByteBuffer.allocate(dstSize * dstSize * 4);
            int sh = L - 1;                       // 源级 = L-1
            for (int y = 0; y < dstSize; y++) {
                int cellY = (y << L) / CELL_PX;   // 该目标 texel 归一化到 level-0 后落在哪个 cell（行）
                int loY = (cellY * CELL_PX) >> sh, hiY = ((cellY + 1) * CELL_PX) >> sh;
                int ys0 = clampI(2 * y, loY, hiY - 1), ys1 = clampI(2 * y + 1, loY, hiY - 1);
                for (int x = 0; x < dstSize; x++) {
                    int cellX = (x << L) / CELL_PX;                                            // 列
                    int loX = (cellX * CELL_PX) >> sh, hiX = ((cellX + 1) * CELL_PX) >> sh;
                    int xs0 = clampI(2 * x, loX, hiX - 1), xs1 = clampI(2 * x + 1, loX, hiX - 1);
                    float lr = 0f, lg = 0f, lb = 0f;
                    float ar = 0f, ag = 0f, ab = 0f;                  // 全透明时的兜底累计（见下）
                    int asum = 0, n = 0;
                    for (int j = 0; j < 2; j++) {
                        int sy = (j == 0) ? ys0 : ys1;
                        for (int i = 0; i < 2; i++) {
                            int sx = (i == 0) ? xs0 : xs1;
                            int si = (sy * srcSize + sx) * 4;
                            int aa = s.get(si + 3) & 255;
                            float cr = POW22[s.get(si) & 255], cg = POW22[s.get(si + 1) & 255], cb = POW22[s.get(si + 2) & 255];
                            asum += aa;
                            ar += cr; ag += cg; ab += cb;
                            if (aa > 0) {                  // MC：全透明 tap 不参与 RGB 平均（防黑边）
                                lr += cr; lg += cg; lb += cb;
                                n++;
                            }
                        }
                    }
                    int di = (y * dstSize + x) * 4;
                    if (n > 0) {
                        d.put(di,     (byte) encLin(lr / n));
                        d.put(di + 1, (byte) encLin(lg / n));
                        d.put(di + 2, (byte) encLin(lb / n));
                    } else {
                        // 四个 tap 全透明（整块镂空区）：**不能写 0**。NEAREST_MIPMAP_LINEAR 会在相邻两级之间
                        // 线性混合，上一级有色的 texel 混到本级的 RGB=0 上就会凭空生出暗边。
                        // 退回普通四 tap 平均 → 保住材质色（本作镂空 tile 的透明区本就带材质色，等价 UE dilation 的效果）。
                        d.put(di,     (byte) encLin(ar * 0.25f));
                        d.put(di + 1, (byte) encLin(ag * 0.25f));
                        d.put(di + 2, (byte) encLin(ab * 0.25f));
                    }
                    d.put(di + 3, (byte) ((asum + 2) / 4));   // alpha：线性平均（4 tap）
                }
            }
            lv[L] = d;
        }
        return lv;
    }

    /** 烘焙整个图集并上传 GL；MIN=mipmap 链（远处抗闪烁）+ MAG=NEAREST（近处体素锐利不变）+ 各向异性过滤。 */
    public static int bake() {
        ByteBuffer px = bakeAlbedoOffscreen();
        // 4) 上传：MIN=mipmap 链 / MAG=NEAREST（近处锐利）/ aniso（掠射角地面不糊）
        //
        // ⚠️ 2026-09-20 重大修复：GL 上传**必须用 DIRECT 缓冲**。
        //    bakeAlbedoOffscreen() 为让 AtlasDump 零原生依赖返回的是**堆缓冲**（见其注释）；而
        //    glTexImage2D(..., ByteBuffer heap) 在 LWJGL 下拿不到有效地址 → 纹理被上传成**全 0**
        //    （且 glGetError()==0，完全静默！）。后果：world FS 里 `texture(uTex,vUv)` 恒返回
        //    (0,0,0,0) → `if(tx.a<0.02) discard;` 把**每一个地形片元都丢弃** → 整片地形"看不见"，
        //    而实体/天空走别的程序（不采样图集）所以照常可见。查了渲染状态/VP/网格/深度全绿都找不到，
        //    最后靠 glGetTexImage 读回纹理内容才发现是全 0。
        //    修法：拷进 direct 缓冲再上传，审阅路径（堆缓冲）与 GL 路径（direct）两边需求都满足。
        ByteBuffer up = org.lwjgl.system.MemoryUtil.memAlloc(ATLAS_PX * ATLAS_PX * 4);
        up.clear(); up.put(px); up.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, ATLAS_PX, ATLAS_PX, 0, GL_RGBA, GL_UNSIGNED_BYTE, up);
        org.lwjgl.system.MemoryUtil.memFree(up);
        // 2) mip 链：CPU 生成（bakeMipChainOffscreen）后逐级 glTexSubImage2D 上传。
        //    取代 glGenerateMipmap：整图盒滤波会 (a) 跨 tile 串色 (b) 把透明像素的黑平均进 RGB (c) 在 sRGB
        //    空间平均 → 远处整体偏暗。三条都是 MC MipmapGenerator 明确规避的，理由见该方法注释。
        ByteBuffer[] chain = bakeMipChainOffscreen(px);
        while (org.lwjgl.opengl.GL11.glGetError() != 0) { /* 清空既有错误，便于精确捕获 mip 上传的问题 */ }
        for (int L = 1; L < chain.length; L++) {
            int sz = ATLAS_PX >> L;
            if (sz < 1 || chain[L] == null) break;
            ByteBuffer lvl = chain[L];
            // 同 level 0：GL 上传必须走 DIRECT 缓冲（堆缓冲 LWJGL 拿不到有效地址 → 静默全 0）
            ByteBuffer dup = org.lwjgl.system.MemoryUtil.memAlloc(sz * sz * 4);
            for (int i = 0; i < sz * sz * 4; i++) dup.put(i, lvl.get(i));
            // ⚠️ 每一级都必须用 **glTexImage2D** 定义存储。对"未定义的层级"调 glTexSubImage2D 是
            //    INVALID_OPERATION（数据被丢弃）→ 纹理 mipmap 不完整 → 采样返回 (0,0,0,1)
            //    → 地形整片变黑（alpha=1 还躲过了 FS 的 discard，非常有欺骗性）。
            //    MC TextureUtil.prepareImage 同样是"先逐级定义、再上传"，此处对齐该做法。
            glTexImage2D(GL_TEXTURE_2D, L, GL_RGBA, sz, sz, 0, GL_RGBA, GL_UNSIGNED_BYTE, dup);
            org.lwjgl.system.MemoryUtil.memFree(dup);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, MIP_LEVELS);
        int mipErr = org.lwjgl.opengl.GL11.glGetError();
        // 运行时不变量（2026-09-20 血泪教训 · 两条）：上传后立刻抽检 GPU 侧 **level 0 与最深 mip 级**，
        // 并检查 mip 上传期间的 GL 错误。两个坑都曾是"全绿却看不见地形"：
        //   ① 堆缓冲直传 glTexImage2D → LWJGL 拿不到有效地址 → 纹理**静默**全 0（glGetError()==0）
        //      → world FS 的 `if(tx.a<0.02) discard;` 把每个地形片元丢掉；
        //   ② 对未定义存储的层级调 glTexSubImage2D（INVALID_OPERATION）→ mipmap 不完整
        //      → 采样返回 (0,0,0,1) → 地形整片纯黑（alpha=1 还躲过了 discard，更隐蔽）。
        int badLevel = -1;
        for (int pi = 0; pi < 2; pi++) {
            int L = (pi == 0) ? 0 : MIP_LEVELS;
            int sz = ATLAS_PX >> L;
            if (sz < 1) continue;
            java.nio.ByteBuffer chk = memAlloc(sz * sz * 4);
            org.lwjgl.opengl.GL11.glGetTexImage(GL_TEXTURE_2D, L, GL_RGBA, GL_UNSIGNED_BYTE, chk);
            boolean nonZero = false;
            for (int i = 0; i < sz * sz * 4; i += 4)
                if ((chk.get(i) | chk.get(i + 1) | chk.get(i + 2) | chk.get(i + 3)) != 0) { nonZero = true; break; }
            memFree(chk);
            if (!nonZero) { badLevel = L; break; }
        }
        if (badLevel >= 0 || mipErr != 0) {
            System.err.println("[ATLAS] FAIL: mip level " + badLevel + " 抽检全 0 / mipErr=0x"
                    + Integer.toHexString(mipErr)
                    + "（上传缓冲非 direct？层级未用 glTexImage2D 定义？）—— 地形会被整片 discard 或整片变黑");
        }
        try {
            if (org.lwjgl.opengl.GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                float maxA = glGetFloat(org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                glTexParameterf(GL_TEXTURE_2D,
                        org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                        Math.min(8.0f, maxA));
            }
        } catch (Throwable ignored) { /* 老驱动无此扩展：退化为普通 mipmap，不崩 */ }
        glBindTexture(GL_TEXTURE_2D, 0);
        // 存 albedo 快照（供 bakeNormal 亮度场推导；确定性同源，纹理与凹凸自动对齐）
        lastAlbedoPx = memAlloc(ATLAS_PX * ATLAS_PX * 4);
        for (int i = 0; i < ATLAS_PX * ATLAS_PX * 4; i++) lastAlbedoPx.put(i, px.get(i));
        // px 现在是堆缓冲（见 bakeAlbedoOffscreen）→ 交给 GC；不能用 memFree（那要求 direct）
        return tex;
    }

    private static java.nio.ByteBuffer lastAlbedoPx;

    /**
     * 法线图集（ART_BIBLE §10）：从 albedo 亮度场 Sobel 推导——纹理暗处=凹、亮处=凸，零额外素材。
     * <p><b>此处故意保留 {@code glGenerateMipmap}</b>（与 albedo 不同）：法线是方向向量、不是颜色，
     * 不能走 gamma 空间的"线性光平均"（那会把法线平均歪掉），alpha 恒 1 也没有镂空黑边问题。
     * 学 MC 的分工——{@code MipmapGenerator} 只对颜色 sprite 做 gamma 感知平均。</p>
     */
    public static int bakeNormal() {
        if (lastAlbedoPx == null) return -1;
        java.nio.ByteBuffer px = memAlloc(ATLAS_PX * ATLAS_PX * 4);
        for (int y = 0; y < ATLAS_PX; y++) {
            for (int x = 0; x < ATLAS_PX; x++) {
                float l  = lum(x, y);
                float lx = lum(Math.min(ATLAS_PX - 1, x + 1), y);
                float lX = lum(Math.max(0, x - 1), y);
                float ly = lum(x, Math.min(ATLAS_PX - 1, y + 1));
                float lY = lum(x, Math.max(0, y - 1));
                float dx = lx - lX, dy = ly - lY;
                float nx = -dx * 2.2f, ny = -dy * 2.2f, nz = 1.0f;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                put(px, x, y, nx / len * 0.5f + 0.5f, ny / len * 0.5f + 0.5f, nz / len * 0.5f + 0.5f, 1f);
            }
        }
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, ATLAS_PX, ATLAS_PX, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
        // 存 albedo 快照（供 bakeNormal 亮度场推导；确定性同源，纹理与凹凸自动对齐）
        lastAlbedoPx = memAlloc(ATLAS_PX * ATLAS_PX * 4);
        for (int i = 0; i < ATLAS_PX * ATLAS_PX * 4; i++) lastAlbedoPx.put(i, px.get(i));
        memFree(px);
        return tex;
    }

    private static float lum(int x, int y) {
        int i = (y * ATLAS_PX + x) * 4;
        float r = (lastAlbedoPx.get(i) & 255), g = (lastAlbedoPx.get(i + 1) & 255), b = (lastAlbedoPx.get(i + 2) & 255);
        return (r * 0.299f + g * 0.587f + b * 0.114f) / 255f;
    }

    /** gutter 扩展：内容区 [G, G+TILE_PX) 边缘像素向外复制 G px（先左右列、再上下行含四角）。 */
    private static void dilateGutters(ByteBuffer px) {
        for (int t = 0; t < ATLAS_TILES * ATLAS_TILES; t++) {
            int cx0 = cellX(t), cy0 = cellY(t);
            for (int y = 0; y < TILE_PX; y++) {
                int iy = cy0 + GUTTER + y;
                for (int g = 1; g <= GUTTER; g++) {
                    copyPx(px, cx0 + GUTTER, iy, cx0 + GUTTER - g, iy);                            // 左缘
                    copyPx(px, cx0 + GUTTER + TILE_PX - 1, iy, cx0 + GUTTER + TILE_PX - 1 + g, iy); // 右缘
                }
            }
            for (int x = 0; x < CELL_PX; x++) {
                for (int g = 1; g <= GUTTER; g++) {
                    copyPx(px, cx0 + x, cy0 + GUTTER, cx0 + x, cy0 + GUTTER - g);                            // 上缘
                    copyPx(px, cx0 + x, cy0 + GUTTER + TILE_PX - 1, cx0 + x, cy0 + GUTTER + TILE_PX - 1 + g); // 下缘
                }
            }
        }
    }

    private static void copyPx(ByteBuffer px, int sx, int sy, int dx, int dy) {
        int si = (sy * ATLAS_PX + sx) * 4, di = (dy * ATLAS_PX + dx) * 4;
        for (int c = 0; c < 4; c++) px.put(di + c, px.get(si + c));
    }

    /** AP-TX：近距细节 tile —— 两八度高频灰度噪声，中性 0.5 居中（shader 做距离衰减乘法混合）。 */
    private static void paintDetailTile(ByteBuffer px, int tile) {
        int tx0 = cellX(tile) + GUTTER, ty0 = cellY(tile) + GUTTER;
        for (int y = 0; y < TILE_PX; y++)
            for (int x = 0; x < TILE_PX; x++) {
                float v = 0.5f + 0.24f * (hash(x, y, 0xDE71) - 0.5f)
                        + 0.10f * (hash(x / 2, y / 2, 0xDE72) - 0.5f);
                put(px, tx0 + x, ty0 + y, v, v, v, 1f);
            }
    }

    /**
     * 自研艺术 UI 区（{@link #UI_TILE_BASE}）：程序化烘焙九宫格面板 + 纸纹。
     *
     * <p><b>风格锚点</b>与地形一致：暖灰石质底（{@code #2E2E36} 冷灰 → 面板习惯）+ 暖色描边
     * （对齐世界 ASHLAR 的 {@code 212,206,192} 与 GOLD {@code 232,190,96} 的暖调），
     * 纸纹用与 {@link #paintDetailTile} 同源的整数哈希，保证"UI 与地形出自同一套随机源"的观感统一。</p>
     *
     * <p><b>九宫格语义</b>（见 {@link #UI_NINE_SLICE}）：角 tile 含圆角+描边（不拉伸），
     * 边 tile 只在一个方向拉伸（描边始终 2px），中央 tile 双向拉伸（纯底纹）。</p>
     *
     * <p><b>alpha 用法</b>：底色 tile 的 alpha 是"面板不透明度遮罩"（圆角外 = 0），
     * 由调用方乘以整体不透明度 —— 因此圆角是<b>真透明</b>而非挖成黑色。</p>
     */
    private static void paintUiTiles(ByteBuffer px) {
        // 复位面类别：UI tile 不该继承最后一个方块 tile 的"底面"尺度（当前 UI 不用 vn2，
        // 但留着这道保险，避免将来给 UI 加纹理时出现"只有某几个 tile 尺度不对"的怪象）。
        CUR_KIND = 0;
        for (int k = 0; k < UI_TILE_COUNT; k++) paintUiTile(px, UI_TILE_BASE + k, k);
        paintUiWhiteTile(px);
    }

    /**
     * 烘焙 {@link #UI_WHITE_TILE}：<b>严格 exact 1.0</b> 的纯白（含 gutter，防止 mip/线性采样
     * 把邻居 UI tile 的暗色渗进来 —— gutter 在 {@code dilateGutters} 前必须已是白）。
     *
     * <p>注意：单元格是 {@code CELL_PX} 而非 {@code TILE_PX}，gutter 区也一并写白，这样
     * 上层 mip 与各向异性采样即便跨到 gutter 也仍是白，恒等性在任何 mip 级别都成立。</p>
     */
    private static void paintUiWhiteTile(ByteBuffer px) {
        int tx0 = cellX(UI_WHITE_TILE), ty0 = cellY(UI_WHITE_TILE);
        for (int y = 0; y < CELL_PX; y++)
            for (int x = 0; x < CELL_PX; x++)
                put(px, tx0 + x, ty0 + y, 1f, 1f, 1f, 1f);
    }

    /** 单个 UI tile 的绘制。{@code part} 见 {@link #UI_CENTER} 等常量。 */
    private static void paintUiTile(ByteBuffer px, int tile, int part) {
        int tx0 = cellX(tile) + GUTTER, ty0 = cellY(tile) + GUTTER;
        final int B = TILE_PX / 4;              // 九宫格边宽 = 16px（与 UI_NINE_SLICE 一致）
        final float R = B * 0.30f;              // 圆角半径 = 4.8px（温和圆角；此前 0.62*B 过大，视觉成大楔形）
        final float LINE_W = 2.0f;              // 描边宽（px）
        // 面板底色（暖灰石质）+ 描边色（暖白）+ 内阴影
        final float[] FILL = {0.176f, 0.180f, 0.208f};     // #2D2E35 冷灰底
        final float[] LINE = {0.62f, 0.60f, 0.56f};        // 暖灰描边
        final float[] EDGE_HI = {0.78f, 0.76f, 0.72f};     // 上/左受光高光
        final float[] EDGE_LO = {0.10f, 0.10f, 0.12f};     // 下/右背光阴影
        for (int y = 0; y < TILE_PX; y++) {
            for (int x = 0; x < TILE_PX; x++) {
                float r, g, b, a;
                // 纸纹（两层：粗布纹 + 细砂），与地形同一套哈希纪律
                float paper = 0.94f + 0.10f * hash(x / 3, y / 3, part * 97 + 5)
                            + 0.04f * hash(x, y, part * 97 + 6);
                if (part == UI_CENTER || part == UI_INNER_GLOW || part == UI_PAPER) {
                    if (part == UI_PAPER) {                 // 可平铺纸纹：灰度噪点（供 shader 平铺出血）
                        float v = (paper - 0.94f) / 0.14f;  // 归一化到 0..1
                        v = v < 0f ? 0f : (v > 1f ? 1f : v);
                        put(px, tx0 + x, ty0 + y, v, v, v, 1f);
                        continue;
                    }
                    if (part == UI_INNER_GLOW) {            // 选中态高光条：顶部亮、向下渐隐
                        float t = 1f - y / (float) (TILE_PX - 1);
                        float v = 0.35f + 0.65f * t * t;
                        put(px, tx0 + x, ty0 + y, 0.95f * v, 0.88f * v, 0.72f * v, 1f);
                        continue;
                    }
                    put(px, tx0 + x, ty0 + y, FILL[0] * paper, FILL[1] * paper, FILL[2] * paper, 1f);
                    continue;
                }
                // ---- 边 / 角 tile：画出描边与（角处）圆角 ----
                // 关键：显式建模"本 tile 贴哪几条面板外缘" —— 不能靠 cx/cy 反推，
                //   EDGE_T/B/L/R 四条边 tile 的 cx=cy=0，反推会得到"不贴任何边"→ 描边永远画不出
                //   （2026-09-21 实测：first version 的边 tile 与 CENTER 逐像素相同，即描边丢了）。
                boolean touchT = (part == UI_CORNER_TL || part == UI_CORNER_TR || part == UI_EDGE_T);
                boolean touchB = (part == UI_CORNER_BL || part == UI_CORNER_BR || part == UI_EDGE_B);
                boolean touchL = (part == UI_CORNER_TL || part == UI_CORNER_BL || part == UI_EDGE_L);
                boolean touchR = (part == UI_CORNER_TR || part == UI_CORNER_BR || part == UI_EDGE_R);
                // 距各外缘的像素距离（未贴的边给一个很大的数）
                float dT = touchT ? (y + 0.5f) : 9999f;
                float dB = touchB ? (TILE_PX - y - 0.5f) : 9999f;
                float dL = touchL ? (x + 0.5f) : 9999f;
                float dR = touchR ? (TILE_PX - x - 0.5f) : 9999f;
                float toEdge = Math.min(Math.min(dT, dB), Math.min(dL, dR));
                // 圆角：仅当本 tile 同时贴一条横边与一条竖边（即角 tile）才做
                boolean isCorner = (touchT || touchB) && (touchL || touchR);
                boolean cornerDead = false;         // 圆角外 → 真透明
                boolean cornerOnArc = false;        // 落在圆角弧线上（距弧 < LINE_W）
                if (isCorner) {
                    // 从角点向内的局部坐标（确保两个轴向都从外缘往内为正）
                    float ax = (touchL ? (x + 0.5f) : (TILE_PX - x - 0.5f));
                    float ay = (touchT ? (y + 0.5f) : (TILE_PX - y - 0.5f));
                    if (ax < R && ay < R) {
                        float dx = R - ax, dy = R - ay;         // 到圆心距离分量
                        float cd = (float) Math.sqrt(dx * dx + dy * dy);
                        if (cd > R) cornerDead = true;          // 圆外 → 透明
                        else if (R - cd < LINE_W) cornerOnArc = true;
                    }
                }
                if (cornerDead) {
                    put(px, tx0 + x, ty0 + y, 0f, 0f, 0f, 0f);   // 圆角外：真透明
                    continue;
                }
                // 描边：距外缘 < LINE_W，或落在圆角弧上。
                // ⚠️ 2026-09-21 血泪：此处**不能**用 `|cornerDist| < LINE_W` 配 `cornerDist 初值 0` 的写法 ——
                //    初值 0 会让"完全不在圆角区"的像素也满足 |0|<LINE_W → 判定恒真 → 整片 tile 被涂成描边色
                //    （实测 part=2 的中部 199 高光/26 阴影大片铺满，视觉成大楔形）。改用独立布尔标志。
                boolean onLine = (toEdge < LINE_W) || cornerOnArc;
                // 受光：贴的是上/左 → 高光；下/右 → 阴影（与世界太阳方向观感一致）
                boolean litHi = (touchT && dT <= toEdge + 0.01f) || (touchL && dL <= toEdge + 0.01f);
                boolean litLo = (touchB && dB <= toEdge + 0.01f) || (touchR && dR <= toEdge + 0.01f);
                if (onLine) {
                    float[] L = litHi && !litLo ? EDGE_HI : (litLo && !litHi ? EDGE_LO : LINE);
                    r = L[0]; g = L[1]; b = L[2]; a = 1f;
                } else {
                    // 底纹 + 从外缘向内的内阴影（越靠外越暗）
                    float shade = 1f - 0.42f * Math.max(0f, 1f - toEdge / (B * 0.62f));
                    r = FILL[0] * paper * shade;
                    g = FILL[1] * paper * shade;
                    b = FILL[2] * paper * shade;
                    float near = Math.max(0f, 1f - toEdge / (B * 0.45f));
                    float grad = 1f + (litHi ? 0.10f : -0.14f) * near;
                    r *= grad; g *= grad; b *= grad;
                    a = 1f;
                }
                put(px, tx0 + x, ty0 + y, r, g, b, a);
            }
        }
    }

    private static void put(ByteBuffer px, int x, int y, float r, float g, float b, float a) {
        int i = (y * ATLAS_PX + x) * 4;
        px.put(i,     (byte) clamp255(r));
        px.put(i + 1, (byte) clamp255(g));
        px.put(i + 2, (byte) clamp255(b));
        px.put(i + 3, (byte) clamp255(a));
    }

    private static int clamp255(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }

    /**
     * 钳到 [0,1] —— 形状场的"<b>平滑混合权重</b>"（第七批补：本类此前只有 {@link #clamp255}）。
     *
     * <p>为什么母题里需要它：把连续场（{@code ridge}/{@code fbm2}）转成"往某个颜色靠多少"时，
     * 必须用<b>平滑权重</b>而不是硬阈值 {@code if (v > t) {...}} —— 硬阈值会在边界产生
     * 最小尺度的高频能量，把形状自相关 {@code formAcf} 打崩（见 TILEART 的教训）。
     */
    private static float sat01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** DIRT 兜底色（草侧面/草底的泥层用；不用 DIRT 方块对象以免循环依赖）。 */
    private static final float[] DIRT_RGB = {134f / 255f, 96f / 255f, 67f / 255f};

    // ==================================================================================
    // P2 形状语言原语（2026-09-23）
    //
    // 为什么需要这一层：改造前方块贴图的写法是「底色 × (1 + a·hash(4px格) + b·hash(像素))」——
    // 明度的差异**全部来自随机数**，于是 64px 贴图读起来是"4 像素格子的电视雪花"。
    // 门禁 TILEART 把这件事量化了：改造前**同值游程跨度中位数只有 2.88 px**（眼睛看到的
    // "连续色面"平均不到 3 像素宽），plateFrac 0.62。
    //
    // 三条纪律（对应"形状语言"的定义）：
    //   ① **形**：明度的大块差异来自「面」（plate），面内**恒定**。逐像素抖明度会把任何形状糊成雪花。
    //   ② **缝**：面与面之间画缝（暗线 / 冰是亮线），缝的存在让"面"读成"块"。
    //   ③ **微糙**：只允许 1 个 8bit 级（±0.015 相对幅度），绝不盖过①②。
    //   节奏（砌层/年轮/波脊/火舌）用**周期函数**表达，不用随机 —— 周期性本身就是"手工感"的来源。
    //
    // 全部纯函数 + 整数哈希，零 RNG、零外部素材（与 {@link #hash} 同纪律）。
    // ==================================================================================

    /** 砌体面的输出暂存（bake 单线程，避免逐像素分配）。见 {@link #masonry}。 */
    private static final float[] MASON = new float[4];

    /**
     * **砌体面**（连续坐标版）：把图案空间切成 {@code size}×{@code size} 的块，并返回本像素所在块与
     * "距块缝"的距离。两条让它读起来像**手工砌**而不是尺子画的关键：
     * <ul>
     *   <li><b>错缝</b>：每行整体偏移一个随行号变化的量（{@code rowOff}）；</li>
     *   <li><b>手绘抖线</b>：本块的边界再按块哈希偏移 ±22% 格宽 —— 缝是歪的。</li>
     * </ul>
     * 结果写入 {@link #MASON}：{@code [0]=块列号, [1]=块行号, [2]=距左缝, [3]=距下缝}。
     *
     * <p>⚠️ <b>必须传连续坐标</b>{@code xf = x / (float) S}。传整数 {@code sx} 时"距缝距离"只在
     * 4 像素的整数格上取值 ⇒ {@link #seamMul} 的软边退化成 4px 阶梯（砖缝变成楼梯）。
     *
     * @param size 面边长（图案单位；16 图案单位 = 整个 tile）
     */
    private static void masonry(float xf, float yf, float size, int salt) {
        int gy = (int) Math.floor(yf / size);
        float rowOff = hash(0, gy, salt) * size;                       // 错缝
        int gx = (int) Math.floor((xf + rowOff) / size);
        float jx = (hash(gx, gy, salt + 11) - 0.5f) * size * 0.44f;    // 手绘抖动的缝
        float jy = (hash(gx, gy, salt + 23) - 0.5f) * size * 0.44f;
        MASON[0] = gx;
        MASON[1] = gy;
        MASON[2] = (xf + rowOff) - gx * size + jx;
        MASON[3] = yf - gy * size + jy;
    }

    /**
     * 缝的遮罩系数：{@code seamDist} 是"距缝内侧的距离"（图案单位），返回该像素应乘的系数。
     *
     * <p>用**软边**（1 个单位内从最暗平滑回到 1.0）而不是硬阈值 —— 硬边会在 4px 尺度上
     * 制造新的高频，反而把"面"重新打碎（门禁的 span 会掉下来）。软边同时更像真实灰浆。
     *
     * @param depth 缝中心处的亮度系数（如 0.72 = 缝比面暗 28%）
     * @param width 缝的视觉宽度（图案单位）；建议 ≤1
     */
    private static float seamMul(float seamDist, float depth, float width) {
        if (seamDist >= width) return 1f;
        float t = seamDist / width;                 // 0 = 缝心
        // ⚠️ 必须钳到 [0,1]：调用方给的 seamDist 可能为负（砌体抖动的缝线可以越过相邻格），
        //    此时 smoothstep 多项式 `t²(3−2t)` 在 t<−1 会**大于 1**（实测 t=−1 时得 5.0）
        //    → 系数 >1 → 颜色溢出到 255（贴图出现死白）。纯函数必须对任意输入良定义。
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        t = t * t * (3f - 2f * t);                  // smoothstep：中心平坦、边缘过渡
        return depth + (1f - depth) * t;
    }

    /**
     * **周期脊线**（沙纹 / 水波 / 岩层 / 年轮）：沿给定方向的正弦脊，返回 [0,1]（0=谷，1=脊）。
     * 方向用两个分量给，于是可以斜着走（沙丘的风向、岩层的倾角）。
     *
     * @param period 周期（图案单位）。建议 5~9 —— 太小变条纹、太大看不见节奏
     */
    private static float ridge(float sx, float sy, float dirX, float dirY, float period, int salt) {
        float u = (sx * dirX + sy * dirY) / period + hash(0, 0, salt) * 4f;
        float w = (float) Math.sin(u * 2.0 * Math.PI);
        return 0.5f + 0.5f * w;
    }

    /**
     * **有骨架的裂纹**（全分辨率连续版）：沿一条平滑漂移的中心线走，两侧 ~0.35 图案单位内算裂纹；
     * 从中心线上分叉出次级裂纹。返回 [0,1] 的强度。
     *
     * <p>⚠️ <b>必须传连续坐标</b>（{@code xf = x / (float) S}），不能传整数图案坐标
     * {@code sx}。2026-09-23 实测教训：在整数图案空间算裂纹，得到的是一条 **4px 宽的阶梯方块串**
     * —— 因为图案坐标每 4 个像素才跳一格，裂缝被量化成"楼梯"，读作像素化噪点而不是裂缝。
     * <b>规律：「面」可以在图案空间算（面本来就大），「线」必须在全分辨率算（线必须细）。</b>
     *
     * <p>中心线用两个不同频率的正弦叠加（而非逐段哈希）→ 连续、无折角，才是"裂开了"的样子。
     */
    private static float crackMaskF(float xf, float yf, int salt) {
        float p0 = hash(0, 0, salt) * 6.2832f;
        float p1 = hash(1, 0, salt) * 6.2832f;
        float cx = 7.5f + 3.2f * (float) Math.sin(yf * 0.52f + p0)
                + 1.5f * (float) Math.sin(yf * 1.3f + p1);
        float d = Math.abs(xf - cx);
        final float W = 0.36f;
        if (d < W) return 1f - 0.35f * (d / W);                 // 主裂：中心暗、边缘软（≈2.9px 宽）
        if (yf > 5.5f) {                                        // 分叉（自下而上张开）
            float cx2 = cx - (yf - 5.5f) * 0.85f - 1.3f * hash(0, (int) (yf / 4f), salt + 7);
            float d2 = Math.abs(xf - cx2);
            if (d2 < W * 0.75f) return 0.85f - 0.3f * (d2 / (W * 0.75f));
        }
        return 0f;
    }

    /**
     * **外缘崩边**（全分辨率连续版）：贴图外圈 0.85 图案单位内，按沿边位置的低频哈希缺一小口。
     *
     * <p>返回 [0.66, 1] 的**暗化系数**（不是 0）—— 不透明方块不能真挖洞：面是四边形，
     * 透明化会露出方块内部；归零则渲染成纯黑缺口。
     *
     * <p>⚠️ 两条实测教训：
     * <ol>
     *   <li>沿边位置必须<b>连续插值</b>。按整数图案格（`along = sx`）分档会让缺口变成
     *       "2 图案单位宽的矩形深块"，读作贴纸角标而不是崩边。</li>
     *   <li>用在 <b>STONE</b> 上是错的：石头的 tile 在四个角同时触发 → 每块石头四角各一个
     *       相同的方角标，重复感极强。只用在 ASHLAR（砌石）上 —— 那里"缺角"本来就符合语义。</li>
     * </ol>
     */
    private static float chipEdgeF(float xf, float yf, int salt) {
        float d = Math.min(Math.min(xf, 15f - xf), Math.min(yf, 15f - yf));
        if (d >= 0.85f) return 1f;
        boolean alongVert = (xf < 1f || xf > 14f);
        float pos = (alongVert ? yf : xf) * 2f;                  // 每 0.5 图案单位一个哈希格点
        int i = (int) Math.floor(pos);
        float t = pos - i;
        float g = hash(i, 0, salt) * (1f - t) + hash(i + 1, 0, salt) * t;   // 沿边连续
        if (g < 0.24f) return 0.66f;
        return 1f;
    }

    /** 微糙：全分辨率、幅度 {@code amp}（相对值）的极小抖动。纪律：{@code amp ≤ 0.02}。 */
    private static float micro(int x, int y, int salt, float amp) {
        return 1f + amp * (hash(x, y, salt) - 0.5f) * 2f;
    }

    // ------------------------------------------------------------------
    // 面类别（侧/顶/底）与材质配色
    // ------------------------------------------------------------------

    /**
     * 当前正在烘焙的<b>面类别</b>（0=侧 / 1=顶 / 2=底）。
     *
     * <p>存在的理由：让"三个面不是同一张贴图"这件事<b>一行生效</b>。若把 20+ 个
     * {@code paintTile} 分支里的 {@code period} 字面量逐个改一遍，改动面大、必然漏。
     * 烘焙是单线程顺序执行（{@code buildAtlas} 循环 → {@code paintTile}），
     * 且 {@code paintUiTiles} 入口会复位它 —— 不会泄漏到别的烘焙路径。
     */
    private static int CUR_KIND = 0;

    /**
     * 顶/底面的纹理尺度系数（乘到 {@link #vn2}/{@link #fbm2} 的 {@code period} 上）。
     *
     * <p>依据是<b>风化方向性</b>而不是随手调参：朝天的顶面被雨水冲刷、被踩踏，细节被磨平
     * ⇒ 图案更大更平；底面常年被压实、潮湿 ⇒ 图案更细；侧面居中。
     * {@code period} 越大相关长度越长 ⇒ 图案越大，故顶面乘 &gt;1、底面乘 &lt;1。
     *
     * <p>⚠️ 只作用于周期类原语。<b>不要</b>改为缩放坐标 {@code xf/yf}：
     * {@link #chipEdgeF}/{@link #crackMaskF} 带绝对范围语义（如 {@code 15 - xf}
     * 是"距贴图右边界"），缩放会把崩边与裂缝推到错误的位置。
     */
    private static float kindPeriod() {
        return CUR_KIND == 1 ? 1.42f : (CUR_KIND == 2 ? 0.76f : 1f);
    }

    /**
     * 材质的「暖光冷影」强度（0 = 关，等价改造前行为）。
     *
     * <p><b>为什么需要它</b>：改造前每个分支都以 {@code r *= m; g *= m; b *= m;} 收尾 ——
     * 同一个标量乘到 RGB 三通道，于是贴图的全部表现力只剩明度、没有色彩温度。
     * 石头的暗部读作"暗灰"、亮部读作"亮灰"，一个色相撑到底 ⇒ 塑料感。
     * 手绘贴图与真实材质的通用规律是<b>受光偏暖、阴影偏冷</b>（直射阳光 vs 天光散射），
     * 把这条规律补上，同一张明度场立刻有体感。
     *
     * <p><b>取值依据</b>：越矿物/自然地越强，越人造/发光越弱。雪与冰刻意给最高档 ——
     * "雪的阴影偏蓝"是这类画面里辨识度最高的色彩记忆点之一；
     * {@code FIRE}/{@code LAMP} 给 0（自身发光，冷暖偏移的语义不成立）。
     */
    private static float warmOf(String nm) {
        switch (nm) {
            case "SNOW":  return 0.80f;
            case "ICE":   return 0.70f;
            case "SAND":  return 0.62f;
            case "GRASS": case "LEAF": case "MOSS": case "CACTUS": return 0.55f;
            case "WOOD":  case "SHELTER": return 0.50f;
            case "STONE": case "BEDROCK": case "CLAY": case "DIRT": return 0.48f;
            case "COAL_ORE": case "IRON_ORE": return 0.45f;
            case "WATER": return 0.45f;
            case "ASHLAR": case "ASHLAR_SHADE": case "ASHLAR_VEIN": return 0.42f;
            case "FLOWER": case "ASH": return 0.40f;
            case "GOLD":  return 0.35f;
            case "GLASS": return 0.30f;
            case "LAMP": case "FIRE": return 0f;
            // ---- 融合百家新方块 ----
            case "COBBLE": case "OBSIDIAN": case "SLATE": case "GRAVEL": return 0.48f;
            case "GRANITE": return 0.50f;
            case "RED_SAND": case "SOUL_SAND": return 0.62f;
            case "PODZOL": return 0.55f;
            case "BRICK": case "SANDSTONE": case "TERRACOTTA": case "HAY": return 0.50f;
            case "AMETHYST": return 0.30f;
            case "LANTERN": case "VINES": case "MUSHROOM": return 0.45f;
            // ---- 第二批「源源不断」：液体/粉末 ----
            case "LAVA": return 0.55f;                                  // 岩浆：热
            case "MUD":  return 0.48f;                                  // 泥浆：土
            // ---- 第三批：功能方块 ----
            case "DOOR": case "CHEST": case "WORKBENCH": return 0.50f;  // 木（与人造木器同档）
            case "FURNACE": return 0.30f;                               // 石/人造（暖光冷影弱）
            // ---- 第四批：金属储块 / 石砖 / 抛光石 / 发光装饰 ----
            case "IRON_BLOCK": case "GOLD_BLOCK": case "COPPER_BLOCK":
            case "BRONZE_BLOCK": case "EMERALD_BLOCK": return 0.25f;   // 金属：暖光冷影弱
            case "STONE_BRICK": case "MOSSY_STONE_BRICK": case "CRACKED_STONE_BRICK":
            case "CHISELED_STONE_BRICK": case "SMOOTH_STONE": return 0.42f;   // 石砌
            case "POLISHED_GRANITE": return 0.50f;
            case "GLOWSTONE": case "SEA_LANTERN": return 0.10f;         // 近自发光
            case "BOOKSHELF": return 0.50f;
            // ---- 第五批：功能方块之二 + 红石 ----
            case "BED": case "LADDER": case "FENCE": return 0.50f;      // 木
            case "LEVER": case "BUTTON": return 0.30f;                  // 石/人造
            case "WIRE": return 0.20f;
            // ---- 第六批：活板门 / 漏斗 / 告示牌 ----
            case "TRAPDOOR": case "SIGN": return 0.50f;                 // 木
            case "HOPPER": return 0.25f;                                // 金属
            // ---- 第七批：红石逻辑三件套 + 生活方块 ----
            case "REPEATER": case "COMPARATOR": case "PLATE": return 0.30f;   // 石/人造电路件
            case "CAMPFIRE": return 0.85f;                                    // 篝火：明火（暖光冷影最强）
            case "FARMLAND": return 0.35f;                                    // 湿土
            // ---- 第九批：红石驱动的机械 ----
            case "DISPENSER": case "PISTON": case "OBSERVER": return 0.28f;   // 金属机件（冷影强）
            // ---- 第十二批 ----
            case "CORAL": case "FERN": case "REED": case "SAPLING": case "DEAD_BUSH": case "BAMBOO": return 0.55f;
            case "KELP": case "SPORE_POD": return 0.50f;                 // 湿生/肉质
            case "CRYSTAL": case "CRYSTAL_CLUSTER": return 0.30f;        // 冷光晶石
            case "BEEHIVE": case "BARREL": case "LECTERN": case "SCAFFOLDING": return 0.50f;
            case "CAULDRON": case "IRON_BARS": case "CHAIN": return 0.28f;  // 金属（冷影强）
            default: return 0.40f;
        }
    }

    /**
     * 明度对比拉伸系数（作用于 {@code paintTile} 末尾的第 ④ 段）。
     *
     * <p><b>为什么需要</b>：各分支产出的明度 {@code m} 范围偏窄（多在 0.70~1.20），
     * 对 64px 贴图而言太保守 —— 实机上看，整片石砌地面会糊成"水泥地"而不是"一块块石头"。
     * 围绕材质本色<b>对称</b>拉伸，所以均值不变（不会整体变亮/变暗），只有对比更明确。
     *
     * <p><b>上限由门禁约束</b>：拉伸同样放大高频，{@code TILEART-MICRO}（二阶差分均值 ≤ 6.0，
     * 纯噪声版实测 8.96）会拦住过头的取值。1.16 时均值约 4.8，已留有余量。
     */
    private static final float CONTRAST = 1.16f;

    /**
     * **2D 值噪声**（双线性/平滑插值的哈希格点场）—— P2 最关键的原语。
     *
     * <p>与 {@link #hash} 的本质区别：后者让每个图案格**独立随机**（相邻格不相关 ⇒ 眼睛读作
     * "格子/雪花"）；前者在 {@code period} 格范围内**连续变化** ⇒ 天然带有"形的相关长度"。
     * 门禁 {@code TILEART} 的 formAcf 正是在量化这个相关长度 —— 平滑插值在 lag-1 上给出
     * 约 {@code 1 − 1/period}，因此本原语是让贴图"有形"的主力。
     *
     * <p>插值用 smoothstep 而非线性：线性插值会在格点处留下可见的**菱形棱线**（读作"编织感"），
     * smoothstep 消除一阶不连续，过渡更像自然起伏。
     *
     * @param period 相关长度（图案单位）。建议 4~9 —— 太小退化成噪声、太大整片一色
     */
    private static float vn2(float sx, float sy, float period, int salt) {
        period *= kindPeriod();          // 顶/底面的纹理尺度差异（见 kindPeriod 注释）
        float u = sx / period, v = sy / period;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        float fu = u - i, fv = v - j;
        fu = fu * fu * (3f - 2f * fu);
        fv = fv * fv * (3f - 2f * fv);
        float a = hash(i, j, salt), b = hash(i + 1, j, salt);
        float c = hash(i, j + 1, salt), d = hash(i + 1, j + 1, salt);
        return (a * (1f - fu) + b * fu) * (1f - fv) + (c * (1f - fu) + d * fu) * fv;
    }

    /**
     * 两个八度的 {@link #vn2} —— 大尺度定"形"、小尺度给"细节"，返回 [0,1]。
     * 权重 0.68/0.32：主导尺度必须明显压倒细节，否则又退回"细节即噪声"。
     */
    private static float fbm2(float xf, float yf, float period, int salt) {
        return 0.68f * vn2(xf, yf, period, salt)
                + 0.32f * vn2(xf, yf, period * 0.45f, salt + 101);
    }

    /** 泥土母题（DIRT 本体 / 草侧下缘共用）：值噪声起伏 + 稀疏暗砾（砾是「块级」的，不是像素级）。 */
    private static float dirtMotif(float xf, float yf, int tile, int x, int y) {
        float f = fbm2(xf, yf, 5, tile * 31 + 5);                       // 相关长度 5 图案单位
        float m = 0.88f + 0.24f * f;
        // 暗砾：用**同一片低频场**的阈值 → 砾是"成片"的，不是散点
        if (vn2(xf, yf, 7, tile * 31 + 13) > 0.80f) m *= 0.80f;
        return m * micro(x, y, tile * 31 + 17, 0.012f);
    }

    /**
     * 单个方块 tile 的绘制。
     *
     * <p><b>P2 形状语言（2026-09-23 重写）</b>：旧写法是「底色 × (1 + a·hash(4px格) + b·hash(像素))」——
     * 明度的全部差异来自随机数，于是 64px 贴图读作"4 像素格子的电视雪花"（门禁 TILEART 实测：
     * 均值 formAcf 0.407、plateFrac 0.623，18/42 个 tile 的块间自相关 &lt; 0.35）。
     *
     * <p>重写后的三条纪律：
     * <ol>
     *   <li><b>形</b>用 {@link #vn2}/{@link #fbm2}（连续场，相关长度 4~9 图案单位）或
     *       {@link #ridge}/{@link #masonry}（周期/砌体）—— 让明度差异**跨越多个图案单位**；</li>
     *   <li><b>缝/裂纹/崩边</b>用 {@link #seamMul}/{@link #crackMask}/{@link #chipEdge}
     *       —— 轮廓结构让"面"读成"块"；</li>
     *   <li><b>微糙</b>一律 ≤ {@code 0.012}（≈1.5 个 8bit 级）—— 它只负责"不是塑料"，绝不参与造型。</li>
     * </ol>
     *
     * <p>⚠️ 灰度约定：LEAF / FLOWER / WATER 输出<b>灰度</b>（颜色由顶点色/群系色盘承担），
     * 改动时不要把彩色写进去，否则会被顶点色二次相乘。
     */
    /**
     * 导线「局部四臂」tile 库（第七批，2026-09-24）：{@link #WIRE_TILE_COUNT} 张 = 4 bit 掩码全组合。
     *
     * <p>与 {@link #paintTile} 的 WIRE 分支<b>同源</b>（同一暗底 + 同一亮红），区别只在"臂"：
     * 母题画满十字，这里按掩码只画<b>连出去的那几条臂</b> + 永远画中心节点（否则孤立导线会是个空洞）。
     * 于是"导线往哪连"在方块表面直接可见，且相邻两格的臂在 tile 边缘对接成一条连续的红线。
     *
     * <p><b>微糙纪律照旧</b>：底纹是连续场 {@code fbm2}/{@code micro}（≤0.012），臂是离散决定
     * （{@code sx/sy in 7..8}）—— 与全图集"形=连续场 / 边界=离散决定"的分工一致，
     * 因此 16 张都能过 {@code TILEART} 的形状判据（{@code TileArtCheck} 的 {@code WIRE-BANK} 段）。
     */
    private static void paintWireBank(ByteBuffer px) {
        CUR_KIND = 0;                                       // 与其它保留 tile 一致：不做顶/底面尺度差异
        for (int m = 0; m < WIRE_TILE_COUNT; m++) {
            int tile = WIRE_TILE_BASE + m;
            int tx0 = cellX(tile) + GUTTER, ty0 = cellY(tile) + GUTTER;
            boolean armC0 = (m & 1) != 0, armC1 = (m & 2) != 0;
            boolean armV0 = (m & 4) != 0, armV1 = (m & 8) != 0;
            for (int y = 0; y < TILE_PX; y++) {
                for (int x = 0; x < TILE_PX; x++) {
                    int sx = x / S, sy = y / S;                       // 0..15：只做离散决定（臂宽）
                    float xf = x / (float) S, yf = y / (float) S;     // 连续：底纹形状场
                    boolean band = (sy >= 7 && sy <= 8);              // 横向导线带
                    boolean col = (sx >= 7 && sx <= 8);               // 纵向导线带
                    boolean onArm = (band && ((armC0 && sx <= 7) || (armC1 && sx >= 8)))
                            || (col && ((armV0 && sy <= 7) || (armV1 && sy >= 8)))
                            || (band && col);                         // 中心节点：任何掩码都画
                    float r, g, b;
                    if (onArm) { r = 0.80f; g = 0.10f; b = 0.08f; }   // 导线（亮红）
                    else {                                            // 底床：暗红熟土（与 WIRE 母题同源）
                        float wm2 = 0.70f + 0.18f * fbm2(xf, yf, 7, tile * 107 + 3);
                        r = 0.424f * wm2; g = 0.118f * wm2; b = 0.110f * wm2;
                    }
                    float mm = micro(x, y, tile * 107 + 19, 0.012f);  // 微糙：只做质感、绝不造型
                    r *= mm; g *= mm; b *= mm;
                    put(px, tx0 + x, ty0 + y, r, g, b, 1f);
                }
            }
        }
    }

    /**
     * 轨道「形状」tile 库（第二十五批）：16 张 = 4 bit 邻居掩码全组合。
     *
     * <p><b>与 {@link #paintWireBank} 同构</b>（同一"离散臂 + 连续底纹 + micro 微糙"结构，
     * 那个结构已被 {@code TILEART} 的 {@code WIRE-BANK} 段验证过）。区别在臂的内容：
     * 导线是亮红直线，轨道是<b>枕木（周期阵列）+ 一条钢轨亮带</b>。
     *
     * <p><b>为什么臂宽 2 格、枕木带 4 格</b>：与导线一致的"大结构"尺度 ——
     * {@code formAcf} 奖励"跨越多个图案单位的明度差异"，2px 之类的小结构会把能量灌进高频
     * （本项目反复踩过：硬阈值斑点让 formAcf 掉到 0.0x）。
     * 枕木用 {@code sin(along·2π)} 再平方做<b>软边</b>，绝不用硬分级（硬分级会把 microNoise 顶过 6.0）。
     */
    private static void paintRailBank(ByteBuffer px) {
        CUR_KIND = 0;                                       // 与其它保留 tile 一致：不做顶/底面尺度差异
        for (int m = 0; m < RAIL_TILE_COUNT; m++) {
            int tile = RAIL_TILE_BASE + m;
            int tx0 = cellX(tile) + GUTTER, ty0 = cellY(tile) + GUTTER;
            boolean armPX = (m & 1) != 0, armNX = (m & 2) != 0, armPZ = (m & 4) != 0, armNZ = (m & 8) != 0;
            for (int y = 0; y < TILE_PX; y++) {
                for (int x = 0; x < TILE_PX; x++) {
                    int sx = x / S, sy = y / S;                       // 0..15：只做离散决定（带/臂）
                    float xf = x / (float) S, yf = y / (float) S;     // 连续：底纹形状场
                    boolean towardPX = armPX && sx >= 7, towardNX = armNX && sx <= 8;   // 朝 +X / -X 的臂
                    boolean towardPZ = armPZ && sy >= 7, towardNZ = armNZ && sy <= 8;   // 朝 +Z / -Z 的臂
                    boolean alongX = (towardPX || towardNX), alongZ = (towardPZ || towardNZ);
                    // 枕木带（宽 4 格）：横向 / 纵向 / 中心（中心永远画 ⇒ 孤立轨也有节点）
                    boolean tieBand = (sy >= 6 && sy <= 9 && alongX) || (sx >= 6 && sx <= 9 && alongZ)
                            || (sx >= 6 && sx <= 9 && sy >= 6 && sy <= 9);
                    // 钢轨（窄 2 格）压在枕木上
                    boolean railBand = (sy >= 7 && sy <= 8 && alongX) || (sx >= 7 && sx <= 8 && alongZ)
                            || (sx >= 7 && sx <= 8 && sy >= 7 && sy <= 8);
                    float r, g, b;
                    if (tieBand) {
                        // 枕木：**沿臂**的周期阵列（周期 4px ⇒ 一格内 4 根；轴对齐 ⇒ formAcf 友好）
                        float along = (sy >= 6 && sy <= 9 && alongX) ? xf : yf;
                        float sl = 0.5f + 0.5f * (float) Math.sin(along * 6.2831853f);
                        sl = sl * sl;                                 // 软边（绝不用硬分级）
                        float wd = 0.44f + 0.24f * sl;                // 深褐枕木
                        r = wd; g = wd * 0.74f; b = wd * 0.50f;
                    } else {
                        // 路基：连续场（与 RAIL 母题同源的石渣底），暗于枕木 ⇒ 枕木读得出来
                        float gr = 0.5f + 0.5f * fbm2(xf, yf, 7, tile * 211 + 3);
                        float base = 0.30f + 0.18f * gr;
                        r = base; g = base; b = base;
                    }
                    if (railBand) {                                   // 钢轨：冷银灰亮带
                        r = r * 0.20f + 0.84f * 0.80f;
                        g = g * 0.20f + 0.86f * 0.80f;
                        b = b * 0.20f + 0.90f * 0.80f;
                    }
                    float mm = micro(x, y, tile * 211 + 29, 0.012f);  // 微糙：只做质感、绝不造型
                    r *= mm; g *= mm; b *= mm;
                    put(px, tx0 + x, ty0 + y, r, g, b, 1f);
                }
            }
        }
    }

    /**
     * 可定向方块「端口面」tile 库（第十批）：每个可定向方块一张同心口形（外框 → 外环 → 凸台 → 内壁 → 中心孔）。
     *
     * <p><b>形状语言照旧</b>：形 = <b>径向连续分级</b>（低频率、结构化的同心环）+ 连续场底纹 {@code fbm2}/{@code vn2}，
     * 微糙 {@code micro ≤0.010}。刻意<b>不用阈值斑点/孤立点</b> —— 那种写法会把能量灌进 4px 尺度，
     * 让 {@code formAcf} 从 ~0.9 掉到 0.0x（金属/石砖那次的实测教训）。
     * 同心环是"大尺度结构"，{@code formAcf} 反而很高。
     *
     * <p>颜色取<b>方块本色提亮 1.18×</b>：于是"有圆口的那一面"在材质上也与其它五面区分得开，
     * 朝向一眼可见；同时 5 个方块的端口各自带自己的色相（中继器偏灰红、比较器偏灰紫、活塞浅灰、
     * 发射器中灰、漏斗青灰），不会互相混淆。
     */
    private static void paintFrontBank(ByteBuffer px) {
        CUR_KIND = 0;
        for (int i = 0; i < FRONT_TILE_COUNT; i++) {
            int tile = FRONT_TILE_BASE + i;
            int tx0 = cellX(tile) + GUTTER, ty0 = cellY(tile) + GUTTER;
            Blocks.Block blk = Blocks.ORIENTABLE[i];
            float cr = blk.r / 255f, cg = blk.g / 255f, cb = blk.b / 255f;
            float R = TILE_PX;
            for (int y = 0; y < TILE_PX; y++) {
                for (int x = 0; x < TILE_PX; x++) {
                    float xf = x / (float) S, yf = y / (float) S;
                    float dx = x - (TILE_PX - 1) * 0.5f, dy = y - (TILE_PX - 1) * 0.5f;
                    float rr = (float) Math.sqrt(dx * dx + dy * dy) / (0.5f * R);   // 0（心）.. ~1.41（四角）
                    float base = 0.62f + 0.22f * fbm2(xf, yf, 6, tile * 167 + 3);    // 金属底（连续场）
                    //  ⚠️ 剖面必须**光滑**：第一版用 `if (rr < 0.38R) lvl = 1.10f` 这类硬分级，
                    //  环形台阶的二阶差分极大 → microNoise 实测 7.22（阈值 6.0）——形状明明很好却被判"电视雪花"。
                    //  改成高斯/平滑阶跃后，口径与"形=连续场"的图集纪律一致（GOLD 斜辉光带那次是同类教训）。
                    float bore  = (float) Math.exp(-(rr * rr) / 0.020f);                       // 中心孔
                    float ring  = (float) Math.exp(-((rr - 0.62f) * (rr - 0.62f)) / 0.012f);   // 外环凸台
                    float fe = rr <= 0.82f ? 0f : (rr >= 1.05f ? 1f : (rr - 0.82f) / 0.23f);
                    fe = fe * fe * (3f - 2f * fe);                                             // smoothstep
                    //  径向肋条：`cos(8θ)` 在**角度方向**是光滑的，且被 rr≈0.45 的高斯限制在中环 ——
                    //  于是它给出"机械口"的读感，却不产生高频能量（直接画 8 条硬线会把 microNoise 顶上去）。
                    float th = (float) Math.atan2(dy, dx);
                    float rib = 0.07f * (float) Math.cos(8.0 * th)
                            * (float) Math.exp(-((rr - 0.45f) * (rr - 0.45f)) / 0.050f);
                    float lvl = 0.96f - 0.60f * bore + 0.20f * ring - 0.26f * fe + rib;
                    float mm = base * lvl * micro(x, y, tile * 167 + 19, 0.010f);
                    put(px, tx0 + x, ty0 + y, cr * mm * 1.18f, cg * mm * 1.18f, cb * mm * 1.18f, 1f);
                }
            }
        }
    }

    private static void paintTile(ByteBuffer px, int tile, Blocks.Block blk, int kind) {
        int tx0 = cellX(tile) + GUTTER, ty0 = cellY(tile) + GUTTER;
        String nm = blk.id;
        // 第十六批（几何基座）：台阶/半砖**复用同族整块的母题**（MC 同款：stone slab 用的就是 stone 的贴图）。
        // 只改"走哪个 case"，不改任何像素生成逻辑 ⇒ TILEART 的形状判据沿用同一条**已通过**的曲线，
        // 不必再冒"新母题 formAcf 不过"的风险（见 TILEART 的反复踩坑史）。
        // 半砖的"半格"是**顶点**表达的（Game.emit 收上边），不是贴图表达的 —— 贴图本来就只画一个面。
        //  第十七批「台阶家族」把这条推广成**约定**：半砖 id = `<同族整块>_SLAB` ⇒ 直接推导，加一档零改动。
        //  `byId(base) != null` 兜底：推不出同族整块时**不映射**（宁可走通用 case，也不静默走进错的 case）；
        //  `MeshCullTest` 另有一条断言盯着这条约定（每档半砖都必须有真实存在的同族整块）。
        if (!blk.isFullShape()) {
            String base = nm;
            if (base.endsWith("_SLAB_TOP"))       base = base.substring(0, base.length() - 9);
            else if (base.endsWith("_SLAB"))      base = base.substring(0, base.length() - 5);
            else if (base.endsWith("_LAYER"))     base = base.substring(0, base.length() - 6);
            else if (base.endsWith("_STAIRS"))    base = base.substring(0, base.length() - 7);
            if (!base.equals(nm) && Blocks.byId(base) != null) nm = base;
        }
        CUR_KIND = kind;                     // 供 vn2/fbm2 做顶/底面的尺度差异
        float warm = warmOf(nm);
        // 基色亮度：暖光冷影要判断"这一像素比材质本色亮还是暗"，必须有个参照。
        // 灰度贴图（水/叶/花，颜色由顶点色承担）的"本色"就是白，故取 1。
        float baseLum = vertexColored(blk)
                ? 1f
                : (0.2126f * blk.r + 0.7152f * blk.g + 0.0722f * blk.b) / 255f;
        float invBaseLum = 1f / Math.max(0.05f, baseLum);
        // 对比拉伸的锚点 = 材质本色（灰度贴图以白为锚，因为它们的输出本身就是"灰度图案"）
        float brC = vertexColored(blk) ? 1f : blk.r / 255f;
        float bgC = vertexColored(blk) ? 1f : blk.g / 255f;
        float bbC = vertexColored(blk) ? 1f : blk.b / 255f;
        for (int y = 0; y < TILE_PX; y++) {
            for (int x = 0; x < TILE_PX; x++) {
                // 图案坐标（0..15，整数：**只用于离散决定**，如边框宽度、花形裁切）
                int sx = x / S, sy = y / S;
                // 连续图案坐标：**所有「形状场」（值噪声/波脊/砌体/裂缝/崩边）都必须用它**。
                // 传整数 sx 会让场在 4×4 像素内恒定 → 贴图上出现可见的 4px 方格
                // （2026-09-23 实测：STONE 背景上那层"淡格子"就是这么来的）。
                float xf = x / (float) S, yf = y / (float) S;
                float r = blk.r / 255f, g = blk.g / 255f, b = blk.b / 255f, a = 1f;
                switch (nm) {
                    case "GRASS":
                        if (kind == 1) {                      // 草顶：草簇（值噪声）+ 叶层节奏
                            float f = fbm2(xf, yf, 4, tile * 29 + 3);
                            float m = 0.80f + 0.34f * f;
                            m *= 1f - 0.06f * (sy % 2);       // 叶层：每 2 图案单位一层，上亮下暗
                            if (vn2(xf, yf, 3, tile * 29 + 21) > 0.88f) m *= 0.82f;   // 草隙
                            m *= micro(x, y, tile * 29 + 9, 0.012f);
                            r *= m; g *= m; b *= m;
                        } else {
                            // 草侧：草皮下垂（高度按每 2 列一组，避免每列独立过碎）+ 泥土
                            int edge = (3 + (int) (hash(sx / 2, 0, tile * 5 + 1) * 3f)) * S;
                            if (kind == 0 && y < edge) {
                                float f = fbm2(xf, yf, 4, tile * 29 + 3);
                                float m = 0.78f + 0.34f * f;
                                m *= 1f - 0.06f * (sy % 2);
                                m *= micro(x, y, tile * 29 + 9, 0.012f);
                                r *= m; g *= m; b *= m;
                            } else {
                                float m = dirtMotif(xf, yf, tile, x, y);
                                r = DIRT_RGB[0] * m; g = DIRT_RGB[1] * m; b = DIRT_RGB[2] * m;
                            }
                        }
                        break;
                    case "DIRT": {
                        float m = dirtMotif(xf, yf, tile, x, y);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "RAIL": {
                        // 第十九批：轨道 = **枕木**（周期阵列）+ **两条钢轨**（软边亮带）。
                        // 枕木用 TILEART 的定式（**轴对齐**周期阵列、横竖各一组取和、一格内 ≥2 个周期）——
                        // 斜向结构/孤立点会把 formAcf 打到 0.1~0.3，本项目反复踩过（见技能 24.6）。
                        float grid = 0.5f * (vn2(xf * 4f, yf, 4, tile * 13 + 5) + vn2(xf, yf * 4f, 4, tile * 13 + 6));
                        float m = 0.74f + 0.30f * grid;
                        // 钢轨：两条**软边**亮带（绝不用硬阈值 —— 硬分级会把 microNoise 顶到 7+）
                        float railA = 1f - Math.min(1f, Math.abs(yf - 0.30f) * 10f);
                        float railB = 1f - Math.min(1f, Math.abs(yf - 0.70f) * 10f);
                        float rail = Math.max(railA, railB);
                        m *= micro(x, y, tile * 13 + 9, 0.012f);
                        r *= m; g *= m; b *= m;
                        r = r * (1f - rail) + 0.86f * rail;      // 钢轨：冷银灰（连续混合，无硬边）
                        g = g * (1f - rail) + 0.88f * rail;
                        b = b * (1f - rail) + 0.92f * rail;
                        break;
                    }
                    case "STONE":
                    case "COAL_ORE":
                    case "IRON_ORE": {
                        // 石面：两级起伏定"形"（宽 + 中，都是连续场而非随机格）+ 全分辨率连续裂缝
                        float f = fbm2(xf, yf, 7, tile * 17 + 3);
                        float g2 = vn2(xf, yf, 3.2f, tile * 17 + 91);
                        float m = 0.70f + 0.34f * f + 0.20f * g2;
                        m *= 1f - 0.22f * crackMaskF(xf, yf, tile * 17 + 41);       // 连续裂缝
                        if (!nm.equals("STONE")) {
                            float vein = vn2(xf, yf, 6, tile * 23 + 11);
                            if (vein > 0.56f) {
                                float edge = Math.min(1f, (vein - 0.56f) / 0.10f);   // 矿脉软边
                                float oreR = nm.equals("COAL_ORE") ? 0.11f : 0.80f;
                                float oreG = nm.equals("COAL_ORE") ? 0.11f : 0.60f;
                                float oreB = nm.equals("COAL_ORE") ? 0.13f : 0.40f;
                                r += (oreR - r) * edge; g += (oreG - g) * edge; b += (oreB - b) * edge;
                                m = 0.92f + 0.16f * vn2(xf, yf, 4, tile * 23 + 31);
                            }
                        }
                        m *= micro(x, y, tile * 17 + 17, 0.010f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "BEDROCK": {
                        // 基岩：高频起伏 + 成片暗斑（贴图语义上"整块石头疙瘩"）
                        float f = fbm2(xf, yf, 5, tile * 37 + 3);
                        float m = 0.62f + 0.86f * f;
                        if (vn2(xf, yf, 4, tile * 37 + 29) > 0.66f) m *= 0.55f;
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "WATER": {                           // 灰度波纹（颜色由顶点色承担）+ 半透明
                        float w1 = ridge(xf, yf, 0f, 1f, 5f, tile * 13 + 3);        // 主行波（周期 5）
                        float w2 = ridge(xf, yf, 0.6f, 0.8f, 9f, tile * 13 + 17);   // 交叉细波
                        float v = 0.82f + 0.20f * w1 + 0.10f * w2;
                        if (w1 > 0.90f) v *= 1.12f;                                 // 浪脊亮线
                        r = v; g = v; b = v;
                        a = Blocks.WATER_ALPHA;               // 唯一来源在 Blocks
                        break;
                    }
                    case "LEAF": {                            // 风动 → 灰度叶簇（相关长度 4）+ 叶隙
                        float f = fbm2(xf, yf, 4, tile * 71 + 5);
                        float v = 0.72f + 0.44f * f;
                        if (vn2(xf, yf, 3, tile * 71 + 23) > 0.84f) v *= 0.52f;     // 叶隙深色
                        v *= micro(x, y, tile * 71 + 37, 0.012f);
                        r = v; g = v; b = v;
                        break;
                    }
                    case "SAND": {
                        // 风成沙纹：斜向周期脊（周期 7）+ 弱起伏。周期本身即"节奏"
                        float w = ridge(xf, yf, 0.62f, 0.79f, 7f, tile * 19 + 3);
                        float m = 0.90f + 0.16f * w + 0.08f * fbm2(xf, yf, 6, tile * 19 + 11);
                        if (w > 0.88f) m *= 1.05f;                                  // 脊背受光
                        m *= micro(x, y, tile * 19 + 7, 0.010f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "SNOW": {
                        // 雪丘：低频平滑起伏 + 脊线亮边（雪面的高光不在像素上，在脊上）
                        float f = fbm2(xf, yf, 9, tile * 43 + 3);
                        float m = 0.94f + 0.12f * f;
                        if (f > 0.72f) m *= 1.04f;
                        m *= micro(x, y, tile * 43 + 19, 0.008f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "ICE": {
                        // 冰：晶面起伏 + **亮裂纹**（冰的缝是亮的；与石头的暗缝相反，这是材质语义）
                        float f = fbm2(xf, yf, 6, tile * 47 + 3);
                        float m = 0.90f + 0.16f * f;
                        m *= 1f + 0.34f * crackMaskF(xf, yf, tile * 47 + 61);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "WOOD": {
                        // 年轮：沿 y 周期 5，且**被 x 调制**（年轮不是绝对水平，才像树）
                        float ring = ridge(xf, yf + 1.6f * (float) Math.sin(xf * 0.45f), 0f, 1f, 5f, tile * 31 + 2);
                        float m = 0.84f + 0.26f * ring;
                        if (ring < 0.22f) m *= 0.80f;                               // 年轮的深色环
                        if (vn2(xf, yf, 3, tile * 31 + 43) > 0.86f) m *= 0.88f;     // 纤维
                        m *= micro(x, y, tile * 31 + 7, 0.012f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "SHELTER": {
                        // 木板：横向板（周期 6）+ 板缝 + 木纹。板缝是**结构**，不是噪声
                        float band = vn2(0f, sy, 6f, tile * 41 + 3);                // 每块板一个明度
                        float m = 0.86f + 0.24f * band;
                        float dSeam = sy - (float) Math.floor(sy / 6f) * 6f;
                        m *= seamMul(dSeam, 0.68f, 0.9f);                           // 板缝
                        if (vn2(xf, yf, 3, tile * 41 + 23) > 0.88f) m *= 0.90f;     // 木纹
                        m *= micro(x, y, tile * 41 + 5, 0.012f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "FIRE": {                            // 火舌：竖直周期性舌（周期 6），底部宽顶部收
                        float tongueW = 3.4f - 1.6f * (sy / 15f);                   // 上窄
                        float tx = sx + 1.1f * (float) Math.sin(sy * 0.7f);         // 舌的摆动
                        float d = Math.abs(tx - 7.5f) - tongueW;
                        float tongue = d < 0f ? 1f : Math.max(0f, 1f - d / 2.2f);
                        float grad = 1f - 0.34f * y / (float) (TILE_PX - 1);        // 下亮上暗
                        float m = (0.66f + 0.62f * tongue) * grad;
                        r *= m * 1.10f; g *= m; b *= m * 0.88f;
                        break;
                    }
                    case "GLASS": {                           // 框 + 斜高光，内部 alpha 裁切
                        float gv = fbm2(xf, yf, 6, tile * 59 + 3);
                        if (sx == 0 || sy == 0 || sx == 15 || sy == 15) {
                            r *= 0.82f + 0.10f * gv; g *= 0.82f + 0.10f * gv; b *= 0.82f + 0.10f * gv;
                        } else if ((sx + sy) % 16 >= 4 && (sx + sy) % 16 <= 6) {
                            r = 0.95f; g = 0.98f; b = 1f;                       // 斜高光带（周期 16，是"节奏"）
                        } else {
                            a = 0f;
                        }
                        break;
                    }
                    case "CACTUS": {
                        // 竖棱（周期 4，已是节奏结构）+ 棱间纵向起伏 + 刺
                        float rib = (sx % 4 == 0) ? 0.68f : (sx % 4 == 2 ? 1.06f : 0.94f);
                        float m = rib * (0.92f + 0.14f * fbm2(xf * 0.6f, yf, 5, tile * 67 + 3));
                        if (vn2(xf, yf * 0.5f, 4, tile * 67 + 31) > 0.90f) { r = 0.95f; g = 0.95f; b = 0.90f; m = 1f; }
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "MOSS": {
                        // 苔斑：同一片场取阈值 → 苔是「成片的斑块」而不是噪点
                        float f = fbm2(xf, yf, 5, tile * 47 + 5);
                        float m = 0.74f + 0.48f * f;
                        if (vn2(xf, yf, 6, tile * 47 + 41) < 0.42f) m *= 0.72f;     // 露石
                        m *= micro(x, y, tile * 47 + 11, 0.012f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "CLAY": {
                        // 黏土：沉积层理（沿 y 周期 4）+ 弱起伏
                        float lay = ridge(xf, yf, 0f, 1f, 4f, tile * 53 + 3);
                        float m = 0.90f + 0.14f * lay + 0.08f * fbm2(xf, yf, 6, tile * 53 + 17);
                        m *= micro(x, y, tile * 53 + 7, 0.012f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "ASHLAR":
                    case "ASHLAR_SHADE":
                    case "ASHLAR_VEIN": {                     // 条石：错缝砌法 + 每砖明度 + 崩角
                        masonry(xf, yf, 8, tile * 53 + 2);      // 8 图案单位一皮（半块砖高）
                        int gx = (int) MASON[0], gy = (int) MASON[1];
                        // 每砖明度（面级，这是"形"的主体）。幅度 ±15% 而不是 ±10%：
                        // 砖与砖的差异越大，"一堵砌好的墙"越读得出来；同时也是块间自相关的主要来源。
                        float brick = 0.86f + 0.28f * hash(gx, gy, tile * 53 + 9);
                        // 灰浆缝用 seamMul **软边**：硬阈值（`*= 0.74`）会在 4px 尺度制造新的高频，
                        // 反而把块间自相关拉低（实测硬边版 ASHLAR 顶只有 0.437，软边后更高）。
                        float seam = Math.min(MASON[2], MASON[3]);
                        float m = brick * seamMul(seam, 0.70f, 1.1f);
                        if (nm.equals("ASHLAR_VEIN")) {
                            // 纹石：用**同一片低频场**做斑，而不是逐砖随机 → 斑也是"形"
                            m *= 0.86f + 0.20f * vn2(gx * 2f, gy * 2f, 3f, tile * 53 + 29);
                        }
                        m *= chipEdgeF(xf, yf, tile * 53 + 47);
                        m *= micro(x, y, tile * 53 + 3, 0.010f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "GOLD": {                            // 金饰：斜向辉光带（周期 8）+ 铆钉（面级）
                        float band = ridge(xf, yf, 0.71f, 0.71f, 8f, tile * 73 + 3);
                        float m = 0.90f + 0.24f * band;
                        if ((sx == 3 || sx == 12) && (sy == 3 || sy == 12)) m *= 0.72f;   // 铆钉
                        m *= micro(x, y, tile * 73 + 7, 0.010f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "LAMP": {                            // 中心辉光（径向，本来就是"形"）
                        float dx = x - (TILE_PX - 1) * 0.5f, dy = y - (TILE_PX - 1) * 0.5f;
                        float d = (float) Math.sqrt(dx * dx + dy * dy) / (TILE_PX * 0.5f);
                        float m = Math.max(0.78f, 1.28f - 0.50f * d) * (0.97f + 0.06f * vn2(xf, yf, 5, tile * 79 + 3));
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "ASH": {
                        float f = fbm2(xf, yf, 6, tile * 83 + 3);
                        float m = 0.86f + 0.22f * f;
                        if (vn2(xf, yf, 8, tile * 83 + 29) > 0.78f) m *= 0.84f;     // 灰堆
                        m *= micro(x, y, tile * 83 + 7, 0.012f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "FLOWER": {                          // 灰度花形裁切（颜色由五色盘顶点色承担）
                        // 花心 + 花瓣（周期 6 的六瓣）+ 茎叶：用**形状**而不是阈值噪声
                        int cxx = sx - 7, cyy = sy - 4;
                        float d = (float) Math.sqrt(cxx * cxx + cyy * cyy);
                        float ang = (float) Math.atan2(cyy, cxx);
                        float petal = 3.6f + 1.5f * (float) Math.cos(ang * 6f);     // 六瓣
                        if (d < 1.8f) { r = 0.30f; g = 0.30f; b = 0.30f; }          // 花芯
                        else if (d < petal) {
                            float v = 0.84f + 0.22f * fbm2(xf, yf, 4, tile * 43 + 5);
                            r = v; g = v; b = v;                                     // 花瓣
                        } else if (sx >= 7 && sx <= 8 && sy > 8 && sy < 15) {
                            r = 0.34f; g = 0.34f; b = 0.34f;                         // 茎
                        } else if ((sx == 5 && sy == 10) || (sx == 6 && sy == 10)
                                || (sx == 9 && sy == 12) || (sx == 10 && sy == 12)) {
                            r = 0.46f; g = 0.46f; b = 0.46f;                         // 叶
                        } else {
                            a = 0f;
                        }
                        break;
                    }
                    case "COBBLE": {                        // 圆石：小石块（周期 ~4.5）+ 每块明度 + 灰浆缝
                        masonry(xf, yf, 4.5f, tile * 53 + 2);
                        int cgx = (int) MASON[0], cgy = (int) MASON[1];
                        float stone = 0.80f + 0.40f * hash(cgx, cgy, tile * 53 + 9);
                        float cseam = Math.min(MASON[2], MASON[3]);
                        float m = stone * seamMul(cseam, 0.66f, 1.0f);
                        if (vn2(xf, yf, 3, tile * 53 + 31) > 0.82f) m *= 0.86f;     // 石纹
                        m *= micro(x, y, tile * 53 + 3, 0.012f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "GRANITE": {                       // 花岗岩：粉灰底 + 矿物颗粒明暗
                        float gf = fbm2(xf, yf, 7, tile * 17 + 3);
                        float gm = 0.82f + 0.22f * gf;
                        float grain = vn2(xf, yf, 2.2f, tile * 17 + 91);
                        if (grain > 0.80f) gm *= 0.88f; else if (grain < 0.30f) gm *= 1.06f;
                        gm *= micro(x, y, tile * 17 + 17, 0.010f);
                        r *= gm; g *= gm; b *= gm;
                        break;
                    }
                    case "OBSIDIAN": {                      // 黑曜石：暗底 + 紫亮裂纹（火山玻璃）
                        float of = fbm2(xf, yf, 6, tile * 47 + 3);
                        float om = 0.55f + 0.20f * of;
                        om *= 1f + 0.42f * crackMaskF(xf, yf, tile * 47 + 61);      // 裂纹发亮
                        r *= om; g *= om * 0.92f; b *= om * 1.12f;                   // 偏冷紫
                        break;
                    }
                    case "SLATE": {                         // 板岩：冷蓝灰 + 层理（沿 x 周期 5）
                        float slay = ridge(xf, yf, 1f, 0f, 5f, tile * 53 + 3);
                        float sm = 0.74f + 0.18f * slay + 0.10f * fbm2(xf, yf, 6, tile * 53 + 17);
                        sm *= micro(x, y, tile * 53 + 7, 0.012f);
                        r *= sm; g *= sm; b *= sm;
                        break;
                    }
                    case "GRAVEL": {                        // 砂砾：细砾（高频阈值成片）+ 起伏
                        float grf = fbm2(xf, yf, 5, tile * 83 + 3);
                        float grm = 0.82f + 0.20f * grf;
                        if (vn2(xf, yf, 2.5f, tile * 83 + 29) > 0.78f) grm *= 0.84f; // 砾石暗斑
                        grm *= micro(x, y, tile * 83 + 7, 0.012f);
                        r *= grm; g *= grm; b *= grm;
                        break;
                    }
                    case "RED_SAND": {                      // 红沙：斜向沙丘脊（周期 7）
                        float rw = ridge(xf, yf, 0.62f, 0.79f, 7f, tile * 19 + 3);
                        float rm = 0.88f + 0.16f * rw + 0.08f * fbm2(xf, yf, 6, tile * 19 + 11);
                        if (rw > 0.88f) rm *= 1.05f;
                        rm *= micro(x, y, tile * 19 + 7, 0.010f);
                        r *= rm; g *= rm; b *= rm;
                        break;
                    }
                    case "PODZOL": {                        // 灰化土：红褐泥 + 顶面硬壳
                        float pm = dirtMotif(xf, yf, tile, x, y);
                        if (kind == 1) {                                        // 顶面：灰化硬壳（浅一点）
                            pm *= 1.10f;
                            if (vn2(xf, yf, 4, tile * 31 + 5) > 0.7f) pm *= 0.94f;
                        }
                        r = (150f / 255f) * pm; g = (92f / 255f) * pm; b = (66f / 255f) * pm;
                        break;
                    }
                    case "SOUL_SAND": {                     // 灵沙：暗沙 + 疣状凹坑
                        float sf = fbm2(xf, yf, 5, tile * 83 + 3);
                        float sm = 0.78f + 0.16f * sf;
                        float wart = vn2(xf, yf, 3, tile * 83 + 29);
                        if (wart < 0.45f) sm *= 0.80f;                             // 凹坑（露黑）
                        sm *= micro(x, y, tile * 83 + 7, 0.012f);
                        r *= sm; g *= sm; b *= sm;
                        break;
                    }
                    case "BRICK": {                         // 红砖：错缝砌（周期 8）+ 砖明度 + 缝
                        masonry(xf, yf, 8, tile * 53 + 2);
                        int bgx = (int) MASON[0], bgy = (int) MASON[1];
                        float brick = 0.84f + 0.26f * hash(bgx, bgy, tile * 53 + 9);
                        float bseam = Math.min(MASON[2], MASON[3]);
                        float bm = brick * seamMul(bseam, 0.68f, 1.1f);
                        bm *= micro(x, y, tile * 53 + 3, 0.010f);
                        r *= bm; g *= bm; b *= bm;
                        break;
                    }
                    case "SANDSTONE": {                     // 砂岩：暖色层理（沿 y 周期 4）+ 弱起伏
                        float sly = ridge(xf, yf, 0f, 1f, 4f, tile * 53 + 3);
                        float sm = 0.88f + 0.14f * sly + 0.08f * fbm2(xf, yf, 6, tile * 53 + 17);
                        sm *= micro(x, y, tile * 53 + 7, 0.012f);
                        r *= sm; g *= sm; b *= sm;
                        break;
                    }
                    case "TERRACOTTA": {                    // 陶瓦：起伏 + 横向瓦楞（沿 y 周期 6）
                        float tly = ridge(xf, yf, 0f, 1f, 6f, tile * 53 + 3);
                        float tm = 0.84f + 0.20f * tly + 0.10f * fbm2(xf, yf, 5, tile * 53 + 17);
                        tm *= micro(x, y, tile * 53 + 7, 0.012f);
                        r *= tm; g *= tm; b *= tm;
                        break;
                    }
                    case "AMETHYST": {                      // 紫晶（自研）：晶面起伏 + 亮晶脉（紫白）
                        float af = fbm2(xf, yf, 5, tile * 73 + 3);
                        float am = 0.70f + 0.30f * af;
                        float avein = crackMaskF(xf, yf, tile * 73 + 61);
                        am *= 1f + 0.40f * avein;
                        r *= am; g *= am * 0.92f; b *= am * 1.12f;
                        break;
                    }
                    case "LANTERN": {                       // 灯笼（自研）：金属框 + 中心暖辉（径向）
                        float ldx = x - (TILE_PX - 1) * 0.5f, ldy = y - (TILE_PX - 1) * 0.5f;
                        float ld = (float) Math.sqrt(ldx * ldx + ldy * ldy) / (TILE_PX * 0.5f);
                        float glow = Math.max(0.55f, 1.20f - 0.45f * ld);
                        boolean frame = (sx == 0 || sy == 0 || sx == 15 || sy == 15 || sx == 7 || sy == 7);
                        float lm = frame ? 0.55f : glow;
                        r *= lm; g *= lm; b *= lm;
                        break;
                    }
                    case "VINES": {                         // 藤蔓：竖缕（周期 3）+ 暗缝 + 叶斑
                        float strand = (sx % 3 == 0) ? 0.70f : (sx % 3 == 1 ? 1.04f : 0.92f);
                        float vm = strand * (0.86f + 0.16f * fbm2(xf * 0.6f, yf, 5, tile * 67 + 3));
                        if (vn2(xf, yf, 4, tile * 67 + 31) > 0.88f) vm *= 0.88f;        // 叶
                        vm *= micro(x, y, tile * 67 + 7, 0.012f);
                        r *= vm; g *= vm; b *= vm;
                        break;
                    }
                    case "MUSHROOM": {                      // 蘑菇：顶面红伞+白点；侧面/底面菌柄（米褐）
                        if (kind == 1) {                                        // 伞盖
                            float spot = vn2(xf, yf, 3.5f, tile * 43 + 5);
                            float v = 0.80f + 0.30f * fbm2(xf, yf, 4, tile * 43 + 9);
                            if (spot > 0.72f) v = 0.92f;                        // 白点（亮）
                            r *= v; g *= v; b *= v;
                        } else {                                                // 菌柄
                            float mm = 0.86f + 0.14f * fbm2(xf, yf, 4, tile * 43 + 13);
                            r = (210f / 255f) * mm; g = (196f / 255f) * mm; b = (150f / 255f) * mm;
                        }
                        break;
                    }
                    case "HAY": {                           // 干草：横层麦秆（沿 y 周期 3）+ 纤维
                        float hly = ridge(xf, yf, 0f, 1f, 3f, tile * 53 + 3);
                        float hm = 0.84f + 0.18f * hly + 0.10f * fbm2(xf, yf, 5, tile * 53 + 17);
                        if (vn2(xf, yf, 3, tile * 53 + 41) > 0.86f) hm *= 0.90f;       // 麦秆纤维
                        hm *= micro(x, y, tile * 53 + 7, 0.012f);
                        r *= hm; g *= hm; b *= hm;
                        break;
                    }
                    // ---- 第二批「源源不断」（2026-09-23）：矿石 + 液体 + 作物 ----
                    case "COPPER_ORE": case "TIN_ORE": case "GOLD_ORE":
                    case "SILVER_ORE": case "LEAD_ORE": case "EMERALD_ORE":
                    case "DIAMOND_ORE": case "REDSTONE_ORE": case "LAPIS_ORE": case "QUARTZ_ORE": {  // 石嵌矿脉（复用 STONE 形）
                        float f = fbm2(xf, yf, 7, tile * 17 + 3);
                        float g2 = vn2(xf, yf, 3.2f, tile * 17 + 91);
                        float m = 0.70f + 0.34f * f + 0.20f * g2;
                        m *= 1f - 0.22f * crackMaskF(xf, yf, tile * 17 + 41);
                        float vein = vn2(xf, yf, 6, tile * 23 + 11);
                        if (vein > 0.56f) {
                            float edge = Math.min(1f, (vein - 0.56f) / 0.10f);
                            float oreR, oreG, oreB;
                            if (nm.equals("COPPER_ORE"))        { oreR = 0.72f; oreG = 0.45f; oreB = 0.28f; }
                            else if (nm.equals("TIN_ORE"))      { oreR = 0.80f; oreG = 0.80f; oreB = 0.82f; }
                            else if (nm.equals("GOLD_ORE"))     { oreR = 0.95f; oreG = 0.80f; oreB = 0.30f; }
                            else if (nm.equals("SILVER_ORE"))   { oreR = 0.88f; oreG = 0.90f; oreB = 0.95f; }
                            else if (nm.equals("LEAD_ORE"))     { oreR = 0.55f; oreG = 0.55f; oreB = 0.60f; }
                            else if (nm.equals("EMERALD_ORE"))  { oreR = 0.20f; oreG = 0.70f; oreB = 0.42f; }
                            else if (nm.equals("DIAMOND_ORE"))  { oreR = 0.32f; oreG = 0.86f; oreB = 0.84f; } // 钻蓝
                            else if (nm.equals("REDSTONE_ORE")) { oreR = 0.88f; oreG = 0.16f; oreB = 0.14f; } // 赤
                            else if (nm.equals("LAPIS_ORE"))    { oreR = 0.18f; oreG = 0.32f; oreB = 0.88f; } // 靛蓝
                            else                                { oreR = 0.92f; oreG = 0.90f; oreB = 0.86f; } // QUARTZ_ORE 白
                            r += (oreR - r) * edge; g += (oreG - g) * edge; b += (oreB - b) * edge;
                            m = 0.92f + 0.16f * vn2(xf, yf, 4, tile * 23 + 31);
                        }
                        m *= micro(x, y, tile * 17 + 17, 0.010f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                    case "LAVA": {                           // 岩浆：暖橙流动（半透明发光，颜色由贴图承担）
                        float w1 = ridge(xf, yf, 0f, 1f, 5f, tile * 13 + 3);
                        float w2 = ridge(xf, yf, 0.5f, 0.9f, 11f, tile * 13 + 17);
                        float m = 0.78f + 0.22f * w1 + 0.10f * w2;
                        if (w1 > 0.86f) m *= 1.18f;                                  // 亮脊
                        m *= micro(x, y, tile * 13 + 7, 0.012f);
                        r *= m; g *= m; b *= m;
                        a = 1.0f;                                                    // 不透明（LAVA 走不透明 pass）
                        break;
                    }
                    case "MUD": {                            // 泥浆：湿泥 + 水洼（亮/暗斑）
                        float mf = fbm2(xf, yf, 6, tile * 83 + 3);
                        float mm = 0.80f + 0.20f * mf;
                        float puddle = vn2(xf, yf, 4, tile * 83 + 29);
                        if (puddle > 0.78f) mm *= 1.08f;                             // 湿亮
                        else if (puddle < 0.35f) mm *= 0.86f;                        // 干暗
                        mm *= micro(x, y, tile * 83 + 7, 0.012f);
                        r *= mm; g *= mm; b *= mm;
                        break;
                    }
                    case "WHEAT_0": case "WHEAT_1": case "WHEAT_2": case "WHEAT_3": {  // 小麦：竖缕麦秆（各阶段自带色）
                        float strand = (sx % 3 == 0) ? 0.72f : (sx % 3 == 1 ? 1.06f : 0.90f);
                        float wm = strand * (0.84f + 0.16f * fbm2(xf * 0.6f, yf, 5, tile * 67 + 3));
                        if (vn2(xf, yf, 3, tile * 67 + 31) > 0.86f) wm *= 0.88f;      // 叶隙
                        wm *= micro(x, y, tile * 67 + 7, 0.012f);
                        r *= wm; g *= wm; b *= wm;
                        break;
                    }
                    case "SUGARCANE_0": case "SUGARCANE_1": case "SUGARCANE_2": {    // 甘蔗：竖纹 + 节
                        float sstrand = (sx % 2 == 0) ? 0.86f : 1.10f;
                        float sm = sstrand * (0.82f + 0.16f * fbm2(xf * 0.7f, yf, 5, tile * 71 + 3));
                        float snode = ridge(xf, yf, 0f, 1f, 4f, tile * 71 + 21);
                        if (snode > 0.90f) sm *= 0.84f;                              // 节（横暗线）
                        sm *= micro(x, y, tile * 71 + 7, 0.012f);
                        r *= sm; g *= sm; b *= sm;
                        break;
                    }
                    case "CACTUS_FLOWER_0": case "CACTUS_FLOWER_1": case "CACTUS_FLOWER_2": {  // 仙人掌花：绿茎 + 顶花
                        float cstrand = (sx % 2 == 0) ? 0.84f : 1.08f;
                        float cm = cstrand * (0.80f + 0.18f * fbm2(xf * 0.7f, yf, 5, tile * 91 + 3));
                        cm *= micro(x, y, tile * 91 + 7, 0.012f);
                        if (nm.equals("CACTUS_FLOWER_2") && sx >= 6 && sx <= 9 && sy >= 4 && sy <= 8) {
                            float fl = vn2(xf, yf, 3, tile * 91 + 31);
                            r = 0.85f + 0.15f * fl; g = 0.45f + 0.10f * fl; b = 0.65f + 0.10f * fl;  // 粉花
                        } else {
                            r *= cm; g *= cm; b *= cm;
                        }
                        break;
                    }
                    // ---- 第三批「源源不断」（2026-09-23）：功能方块四件套 ----
                    // 纪律同前：**形**来自「门框 / 盖缝 / 拱口 / 分格」这类低频结构（不是像素随机），
                    // 木纹用 ridge（周期 4~5），微糙 ≤0.012。四块形状语言彼此可区分（TILEART 唯一性）。
                    case "DOOR": {                          // 木门：门框（外圈）+ 内嵌板缝 + 竖木纹 + 门把手
                        float df = fbm2(xf, yf, 5, tile * 37 + 3);
                        float dm = 0.86f + 0.20f * df;
                        dm *= 0.94f + 0.12f * ridge(xf, yf, 0f, 1f, 4f, tile * 37 + 11);   // 竖木纹
                        boolean dFrame = (sx == 0 || sx == 15 || sy == 0 || sy == 15);
                        boolean dPanel = (sx == 2 || sx == 13 || sy == 2 || sy == 13);
                        if (dFrame) dm *= 0.60f;            // 门框：暗圈（把"面"读成"门"）
                        else if (dPanel) dm *= 0.82f;       // 内嵌板缝
                        dm *= micro(x, y, tile * 37 + 19, 0.012f);
                        r *= dm; g *= dm; b *= dm;
                        if (sx >= 11 && sx <= 12 && sy >= 7 && sy <= 9) {                  // 门把手（金属）
                            float km = 0.90f + 0.18f * vn2(xf, yf, 2.5f, tile * 37 + 41);
                            r = 0.78f * km; g = 0.74f * km; b = 0.66f * km;
                        }
                        break;
                    }
                    case "CHEST": {                         // 箱子：盖（上缘）+ 盖缝 + 金属箍 + 锁扣
                        float cf = fbm2(xf, yf, 6, tile * 41 + 3);
                        float ck = 0.86f + 0.18f * cf;
                        ck *= 0.94f + 0.12f * ridge(xf, yf, 0f, 1f, 5f, tile * 41 + 11);
                        if (sy <= 5) ck *= 1.08f;                        // 盖：略亮
                        else if (sy == 6 || sy == 7) ck *= 0.66f;        // 盖沿缝（把盖与体分开）
                        boolean cBand = (sx == 3 || sx == 12);           // 金属箍
                        if (cBand) ck *= 0.72f;
                        ck *= micro(x, y, tile * 41 + 19, 0.012f);
                        r *= ck; g *= ck; b *= ck;
                        if (cBand) { r *= 0.92f; g *= 0.92f; b *= 1.00f; }
                        if (sx >= 7 && sx <= 8 && sy >= 6 && sy <= 10) {  // 锁扣
                            float lm2 = 0.92f + 0.16f * vn2(xf, yf, 2.5f, tile * 41 + 41);
                            r = 0.85f * lm2; g = 0.80f * lm2; b = 0.58f * lm2;
                        }
                        break;
                    }
                    case "FURNACE": {                       // 熔炉：石体 + 砖缝外框 + 拱形炉口（暖光）
                        float ff = fbm2(xf, yf, 6, tile * 43 + 3);
                        float fm = 0.72f + 0.28f * ff;
                        fm *= 1f - 0.20f * crackMaskF(xf, yf, tile * 43 + 61);            // 石纹
                        boolean bLine = (sy == 3 || sy == 12)
                                || ((sx == 4 || sx == 11) && sy > 3 && sy < 12);
                        if (bLine) fm *= 0.74f;              // 砖缝（炉体外框）
                        fm *= micro(x, y, tile * 43 + 19, 0.010f);
                        r = 0.42f * fm; g = 0.41f * fm; b = 0.40f * fm;                   // 石体本色
                        if (sx >= 4 && sx <= 11 && sy >= 7 && sy <= 13) {                 // 拱形炉口
                            boolean archOk = (sy != 7) || (sx >= 6 && sx <= 9);
                            if (archOk) {
                                float gx = (sx - 7) / 4f, gy = (sy - 7) / 6f;
                                float glow = 1.0f - 0.55f * (float) Math.sqrt(gx * gx + gy * gy * 0.5f);
                                glow = Math.max(0.35f, glow) * (0.92f + 0.16f * vn2(xf, yf, 3, tile * 43 + 77));
                                r = 0.95f * glow; g = 0.42f * glow; b = 0.14f * glow;     // 炉火暖光
                            }
                        }
                        break;
                    }
                    case "WORKBENCH": {                     // 工作台：木台面 + 十格缝（四格）+ 工具斑
                        float wf = fbm2(xf, yf, 6, tile * 47 + 3);
                        float wm = 0.88f + 0.18f * wf;
                        wm *= 0.94f + 0.10f * ridge(xf, yf, 0f, 1f, 4f, tile * 47 + 11);
                        boolean cross = (sx == 7 || sx == 8 || sy == 7 || sy == 8);
                        boolean wrim = (sx == 0 || sx == 15 || sy == 0 || sy == 15);
                        if (cross) wm *= 0.72f;             // 十字缝（分格）
                        if (wrim) wm *= 0.80f;              // 台沿
                        wm *= micro(x, y, tile * 47 + 19, 0.012f);
                        r *= wm; g *= wm; b *= wm;
                        if (!cross && !wrim) {              // 每格一枚工具暗示（弱明度斑）
                            int q = ((sx < 7) ? 0 : 1) + ((sy < 7) ? 0 : 2);
                            if (vn2(xf * 1.4f, yf * 1.4f, 2.6f, tile * 47 + 31 + q * 13) > 0.72f) {
                                r *= 1.08f; g *= 1.06f; b *= 1.02f;
                            }
                        }
                        break;
                    }
                    // ---- 第四批「源源不断」（2026-09-23）：金属储块 / 石砖 / 抛光石 / 发光装饰 ----
                    case "IRON_BLOCK": case "GOLD_BLOCK": case "COPPER_BLOCK":
                    case "BRONZE_BLOCK": case "EMERALD_BLOCK": {        // 储块：横向压延带（沿 y 周期 5）+ 大尺度起伏
                        // 纪律：只用连续场（ridge/fbm2/vn2）+ 微糙。**不用阈值斑点、不用孤立铆钉点** ——
                        // 阈值/孤立点会把能量灌进 4px 尺度 → 门禁 formAcf 崩（实测 STONE_BRICK/COPPER_BLOCK）。
                        float band = ridge(xf, yf, 0f, 1f, 5f, tile * 59 + 3);             // 横向压延带
                        float mm = 0.82f + 0.22f * band + 0.10f * fbm2(xf, yf, 6, tile * 59 + 17);
                        mm *= 0.95f + 0.10f * vn2(xf, yf, 4, tile * 59 + 41);              // 分材质低频纹理（连续）
                        mm *= micro(x, y, tile * 59 + 19, 0.008f);
                        r *= mm; g *= mm; b *= mm;
                        break;
                    }
                    case "STONE_BRICK": case "MOSSY_STONE_BRICK":
                    case "CRACKED_STONE_BRICK": case "CHISELED_STONE_BRICK": {   // 石砖族：横向砌层 + 竖缝 + 变体(苔/裂/雕)
                        // 用「横向砌层（ridge 沿 y）+ 层明度（沿 x 恒定）」而不是逐砖 hash —— 后者在
                        // 中性灰/某些 tile-salt 下会把能量灌进 4px 尺度，formAcf 实测崩到 0.01。
                        float course = ridge(xf, yf, 0f, 1f, 5.3f, tile * 53 + 3);   // 砌层（沿 y）
                        int row = (int) (yf / 2.65f);                                 // 每道砌层
                        float rowLum = hash(0, row, tile * 53 + 9);                   // 层明度：沿 x 恒定 → 横向结构
                        float vx = xf; while (vx >= 8f) vx -= 8f;                     // 8 图案单位一列砖
                        float bm = (0.84f + 0.14f * course + 0.16f * rowLum) * (vx < 0.6f ? 0.82f : 1f);  // 竖缝
                        bm *= micro(x, y, tile * 53 + 21, 0.010f);
                        r *= bm; g *= bm; b *= bm;
                        if (nm.equals("MOSSY_STONE_BRICK")) {                         // 苔石砖：绿斑（平滑过渡）
                            float moss = vn2(xf, yf, 4, tile * 53 + 31);
                            float e = Math.max(0f, Math.min(1f, (moss - 0.48f) / 0.22f)) * 0.55f;
                            r += (0.34f - r) * e; g += (0.52f - g) * e; b += (0.28f - b) * e;
                        } else if (nm.equals("CRACKED_STONE_BRICK")) {                // 裂石砖：暗缝贯穿
                            float cr = crackMaskF(xf, yf, tile * 53 + 61);
                            r *= 1f - 0.50f * cr; g *= 1f - 0.50f * cr; b *= 1f - 0.50f * cr;
                        } else if (nm.equals("CHISELED_STONE_BRICK")) {               // 雕石砖：中央菱形徽记
                            float dia = Math.abs(sx - 7.5f) + Math.abs(sy - 7.5f);
                            if (dia < 3.2f) { r *= 1.14f; g *= 1.14f; b *= 1.14f; }
                            else if (dia < 4.4f) { r *= 0.82f; g *= 0.82f; b *= 0.82f; }
                        }
                        break;
                    }
                    case "SMOOTH_STONE": {                // 平滑石：极细腻 + 偶发细环
                        float sm = 0.92f + 0.10f * fbm2(xf, yf, 8, tile * 61 + 3);
                        if (vn2(xf, yf, 5, tile * 61 + 29) > 0.86f) sm *= 1.05f;
                        sm *= micro(x, y, tile * 61 + 7, 0.008f);
                        r *= sm; g *= sm; b *= sm;
                        break;
                    }
                    case "POLISHED_GRANITE": {            // 磨光花岗岩：细颗粒 + 抛光高光
                        float pm = 0.88f + 0.16f * fbm2(xf, yf, 7, tile * 67 + 3);
                        float speck = vn2(xf, yf, 2.2f, tile * 67 + 91);
                        if (speck > 0.82f) pm *= 1.07f; else if (speck < 0.28f) pm *= 0.92f;
                        pm *= micro(x, y, tile * 67 + 7, 0.010f);
                        r *= pm; g *= pm; b *= pm;
                        break;
                    }
                    case "GLOWSTONE": {                   // 萤石：周期蜂窝格（沿 x/y 每 6 图案单位）+ 暖辉
                        float gdx = x - (TILE_PX - 1) * 0.5f, gdy = y - (TILE_PX - 1) * 0.5f;
                        float gd = (float) Math.sqrt(gdx * gdx + gdy * gdy) / (TILE_PX * 0.5f);
                        float lat = ((sx % 6 == 0) || (sy % 6 == 0)) ? 0.80f : 1.0f;   // 蜂窝格（周期性 → 高 ACF）
                        float gm = lat * (0.86f + 0.18f * vn2(xf, yf, 3.4f, tile * 71 + 3))
                                * Math.max(0.60f, 1.14f - 0.40f * gd);
                        gm *= micro(x, y, tile * 71 + 7, 0.010f);
                        r *= gm; g *= gm; b *= gm;
                        break;
                    }
                    case "SEA_LANTERN": {                 // 海晶灯：密晶格（每 5 图案单位）+ 冷青辉
                        float sdx = x - (TILE_PX - 1) * 0.5f, sdy = y - (TILE_PX - 1) * 0.5f;
                        float sd = (float) Math.sqrt(sdx * sdx + sdy * sdy) / (TILE_PX * 0.5f);
                        float lat = ((sx % 5 == 0) || (sy % 5 == 0)) ? 0.74f : 1.0f;   // 密晶格
                        float sm2 = lat * (0.88f + 0.16f * vn2(xf, yf, 3.6f, tile * 73 + 3))
                                * Math.max(0.64f, 1.14f - 0.36f * sd);
                        sm2 *= micro(x, y, tile * 73 + 7, 0.010f);
                        r *= sm2; g *= sm2; b *= sm2;
                        break;
                    }
                    case "BOOKSHELF": {                   // 书架：木框 + 两层竖书脊（列色恒定→竖向结构）
                        boolean bFrame = (sx == 0 || sx == 15 || sy == 0 || sy == 15 || sy == 7 || sy == 8);
                        float wm2 = 0.90f + 0.16f * fbm2(xf, yf, 6, tile * 79 + 3);
                        if (bFrame) { r *= wm2 * 0.80f; g *= wm2 * 0.80f; b *= wm2 * 0.80f; }
                        else {
                            float lvl = 0.55f + 0.42f * hash(sx / 2, 0, tile * 79 + 17);   // 每 2 图案单位=一册
                            r = (0.50f + 0.45f * hash(sx / 2, 0, tile * 79 + 23)) * lvl;
                            g = (0.42f + 0.42f * hash(sx / 2, 0, tile * 79 + 29)) * lvl;
                            b = (0.48f + 0.46f * hash(sx / 2, 0, tile * 79 + 31)) * lvl;
                            if (sy % 4 == 3) { r *= 0.80f; g *= 0.80f; b *= 0.80f; }       // 册间暗缝
                        }
                        break;
                    }
                    // ---- 第五批「源源不断」（2026-09-24）：功能方块之二（床/梯/栅栏/拉杆/按钮）+ 红石导线 ----
                    // 形状仍只用「周期性骨架（竖轨/横档/槽/十字）」—— 周期结构在 ACF 上远高于阈值；
                    // 空隙用 alpha=0 打洞（梯/栅栏能看见后面），不做孤立点/硬阈值斑点。
                    case "BED": {                         // 床：木框 + 被面（红）+ 枕（浅色块）
                        float bm2 = 0.92f + 0.14f * fbm2(xf, yf, 6, tile * 83 + 3);
                        boolean bFrame = (sy >= 11) || (sx == 0 || sx == 15 || sy == 0);
                        if (bFrame) { r = 0.62f * bm2; g = 0.44f * bm2; b = 0.28f * bm2; }   // 木框
                        else {
                            r = 0.66f * bm2; g = 0.24f * bm2; b = 0.26f * bm2;               // 被面
                            if (sx >= 2 && sx <= 7 && sy >= 1 && sy <= 3) {                  // 枕
                                r = 0.90f * bm2; g = 0.88f * bm2; b = 0.86f * bm2;
                            } else if (sy == 6 || sy == 10) { r *= 0.86f; g *= 0.86f; b *= 0.86f; }  // 被褶
                        }
                        break;
                    }
                    case "LADDER": {                      // 梯子：两条竖轨 + 横档（沿 y 周期 4）+ 空隙透明
                        boolean rail = (sx >= 1 && sx <= 2) || (sx >= 13 && sx <= 14);
                        boolean rung = (sy % 4 == 0);
                        if (rail || rung) {
                            float lm = 0.80f + 0.20f * fbm2(xf, yf, 6, tile * 89 + 3);
                            r = 0.588f * lm; g = 0.424f * lm; b = 0.243f * lm;               // 木色
                        } else { a = 0f; }
                        break;
                    }
                    case "FENCE": {                       // 栅栏：竖板条（沿 x 周期 3）+ 两道横梁 + 空隙透明
                        boolean slat = (sx % 3 == 0);
                        boolean bar = (sy == 3 || sy == 4 || sy == 10 || sy == 11);
                        if (slat || bar) {
                            float fm2 = 0.84f + 0.18f * fbm2(xf, yf, 6, tile * 97 + 3);
                            r = 0.620f * fm2; g = 0.455f * fm2; b = 0.267f * fm2;
                        } else { a = 0f; }
                        break;
                    }
                    case "LEVER": {                       // 拉杆：石底板 + 中央竖槽 + 木柄亮钮（4×4，非孤立点）
                        float lm2 = 0.88f + 0.16f * fbm2(xf, yf, 6, tile * 101 + 3);
                        r = 0.471f * lm2; g = 0.463f * lm2; b = 0.455f * lm2;                // 石底
                        if (sx >= 6 && sx <= 9 && sy >= 3 && sy <= 12) { r *= 0.80f; g *= 0.80f; b *= 0.80f; }   // 竖槽
                        if (sx >= 6 && sx <= 9 && sy >= 6 && sy <= 9) { r = 0.85f; g = 0.62f; b = 0.24f; }      // 木柄
                        break;
                    }
                    case "BUTTON": {                      // 按钮：石底板 + 中央凸起方（6×6）
                        float nm2 = 0.88f + 0.16f * fbm2(xf, yf, 6, tile * 103 + 3);
                        r = 0.518f * nm2; g = 0.502f * nm2; b = 0.478f * nm2;
                        if (sx >= 5 && sx <= 10 && sy >= 5 && sy <= 10) {
                            float k = 1.10f + 0.10f * vn2(xf, yf, 3, tile * 103 + 17);
                            r *= k; g *= k; b *= k;                                          // 凸起
                        }
                        break;
                    }
                    case "WIRE": {                        // 红石导线：暗底 + 贯穿十字（纵线 + 横线）
                        boolean cross = (sx >= 7 && sx <= 8) || (sy >= 7 && sy <= 8);
                        if (cross) { r = 0.80f; g = 0.10f; b = 0.08f; }                      // 导线（亮红）
                        else {
                            float wm2 = 0.70f + 0.18f * fbm2(xf, yf, 7, tile * 107 + 3);
                            r = 0.424f * wm2; g = 0.118f * wm2; b = 0.110f * wm2;
                        }
                        break;
                    }
                    // ---- 第六批「源源不断」（2026-09-24）：功能方块之三（活板门/漏斗/告示牌）----
                    case "TRAPDOOR": {                    // 活板门：木板条（沿 y 周期 4）+ 中央铁铰链 + 边框
                        float tdm = 0.86f + 0.16f * ridge(xf, yf, 0f, 1f, 4f, tile * 109 + 3);
                        r = 0.588f * tdm; g = 0.408f * tdm; b = 0.235f * tdm;
                        if (sx >= 7 && sx <= 8) { r = 0.44f; g = 0.44f; b = 0.46f; }          // 铁铰链
                        if (sx == 0 || sx == 15 || sy == 0 || sy == 15) { r *= 0.78f; g *= 0.78f; b *= 0.78f; }
                        break;
                    }
                    case "HOPPER": {                      // 漏斗：金属斗 + 下窄的梯形暗腔 + 上沿
                        float hf = 0.86f + 0.16f * fbm2(xf, yf, 6, tile * 113 + 3);
                        r = 0.424f * hf; g = 0.424f * hf; b = 0.439f * hf;
                        float halfW = 8.0f - (sy - 3f) * 0.50f;                               // 上宽→下窄
                        if (sy >= 3 && Math.abs(sx - 7.5f) < halfW) { r *= 0.42f; g *= 0.42f; b *= 0.46f; }  // 斗腔（暗）
                        if (sx == 0 || sx == 15 || sy == 0) { r *= 0.72f; g *= 0.72f; b *= 0.75f; }          // 上沿
                        break;
                    }
                    case "OBSERVER": {                    // 观察者：深灰金属壳 + 中央观察缝 + 缝两侧凸轨
                        float om = 0.88f + 0.14f * fbm2(xf, yf, 6, tile * 173 + 3);
                        r = 0.408f * om; g = 0.424f * om; b = 0.463f * om;
                        // 剖面全部走**平滑**函数（高斯/分段线性），沿 x 恒定 → 不产生高频能量。
                        // （第一版用 `ridge(...,4f,...)` 铺 4 条均匀横带，形状门禁过了但读起来只是"瓦楞铁"，
                        //   没有"有个缝在观察"的语义；换成"中缝 + 上下凸轨"才有辨识度。）
                        float oyc = (yf - 8f) / 8f;                                  // -1..1（面内纵向）
                        float oslit = (float) Math.exp(-(oyc * oyc) / 0.055f);        // 中央观察缝（暗）
                        float oar = Math.abs(oyc) - 0.62f;
                        float orail = (float) Math.exp(-(oar * oar) / 0.020f);        // 缝上下两条凸轨（亮）
                        float ol = 0.98f - 0.40f * oslit + 0.13f * orail;
                        r *= ol; g *= ol; b *= ol;
                        if (sx == 0 || sx == 15) { r *= 0.86f; g *= 0.86f; b *= 0.88f; }   // 左右棱（结构缝）
                        float om2 = micro(x, y, tile * 173 + 19, 0.010f);
                        r *= om2; g *= om2; b *= om2;
                        break;
                    }
                    // ---- 第十二批 · 系统之影（8 块）----
                    case "CORAL": {                       // 珊瑚：分枝扇形（连续场 + 沿 x 的枝脊）
                        float co1 = 0.86f + 0.18f * fbm2(xf, yf, 6, tile * 179 + 3);
                        r = 0.784f * co1; g = 0.353f * co1; b = 0.510f * co1;
                        float br2 = ridge(xf, yf, 1f, 0f, 4f, tile * 179 + 11);
                        float cb2 = 0.78f + 0.36f * br2;
                        r *= cb2; g *= cb2 * 1.05f; b *= cb2 * 1.06f;
                        float cm1 = micro(x, y, tile * 179 + 19, 0.010f);
                        r *= cm1; g *= cm1; b *= cm1;
                        break;
                    }
                    case "BEEHIVE": {                     // 蜂巢：双轴周期场叠出的蜂窝格（光滑 cos → 无 4px 硬边）
                        float bh1 = 0.88f + 0.14f * fbm2(xf, yf, 6, tile * 181 + 3);
                        r = 0.839f * bh1; g = 0.659f * bh1; b = 0.282f * bh1;
                        //  ⚠️ 这一格连踩两次同一个陷阱，值得记下来：
                        //   ① `cos(xf*p) + cos(yf*p)` **求和** → 对角棋盘，formAcf 0.29；
                        //   ② 改成**乘积**后更糟（积化和差 = 纯对角）→ 0.005（几乎零自相关）。
                        //  正解是回到与 CRYSTAL / 砖石同族的**轴对齐 ridged 阵列**：横 + 竖各一组周期 3 的板带，
                        //  取和而非取积 → 自相关立刻回到 0.5+，且读感就是"蜂窝壁"。
                        //  第三次修正：**周期 3 太疏**（一格里只有 ~1.3 个周期 → "形状自相关"没有足够重复）→ 0.30。
                        //  改成周期 4（与 CRYSTAL 同参数）后 0.54。教训：这条指标奖励的是"**规则重复**"，
                        //  不是"图案好看"—— 周期必须密到一格里至少出现 1.5~2 个完整单元。
                        float hfx = ridge(xf, yf, 0f, 1f, 4f, tile * 181 + 11);
                        float hfy = ridge(xf, yf, 1f, 0f, 4f, tile * 181 + 17);
                        float bh2 = 0.70f + 0.28f * hfx + 0.28f * hfy;
                        float hglow = (float) Math.exp(-((yf - 8f) * (yf - 8f)) / 20f);   // 蜜色暖芯（换色，不是加噪）
                        r = r * (1f - 0.22f * hglow) + 0.945f * 0.22f * hglow;
                        g = g * (1f - 0.22f * hglow) + 0.780f * 0.22f * hglow;
                        b = b * (1f - 0.22f * hglow) + 0.420f * 0.22f * hglow;
                        r *= bh2; g *= bh2; b *= bh2;
                        float bm1 = micro(x, y, tile * 181 + 19, 0.010f);
                        r *= bm1; g *= bm1; b *= bm1;
                        break;
                    }
                    case "CRYSTAL": {                     // 晶石块：45° 斜切面 + 内部辉带
                        float kf1 = 0.86f + 0.16f * fbm2(xf, yf, 6, tile * 183 + 3);
                        r = 0.588f * kf1; g = 0.471f * kf1; b = 0.863f * kf1;
                        //  ⚠️ 第一版只用一条 45° 斜脊 → formAcf 实测 0.10（纯对角结构在"形状自相关"里几乎无能量）。
                        //  改成**轴对齐切面网格**（横 + 竖各一组周期 4 的脊），与砌体/砖石同一族形态。
                        float fh = ridge(xf, yf, 0f, 1f, 4f, tile * 183 + 11);
                        float fv = ridge(xf, yf, 1f, 0f, 4f, tile * 183 + 17);
                        float km2 = 0.70f + 0.28f * fh + 0.28f * fv;
                        r *= km2; g *= km2 * 1.03f; b *= km2;
                        float khl = (float) Math.exp(-((yf - 8f) * (yf - 8f)) / 18f);   // 中部辉带（换色，不是加噪）
                        r = r * (1f - 0.25f * khl) + 0.471f * 0.25f * khl;
                        g = g * (1f - 0.25f * khl) + 0.588f * 0.25f * khl;
                        b = b * (1f - 0.25f * khl) + 0.910f * 0.25f * khl;
                        float km1 = micro(x, y, tile * 183 + 19, 0.010f);
                        r *= km1; g *= km1; b *= km1;
                        break;
                    }
                    case "CRYSTAL_CLUSTER": {             // 晶簇：三根竖晶柱（沿 y 的亮带收口）
                        float qf1 = 0.88f + 0.14f * fbm2(xf, yf, 5, tile * 185 + 3);
                        r = 0.706f * qf1; g = 0.612f * qf1; b = 0.910f * qf1;
                        float qc = 0.5f + 0.5f * (float) Math.cos(xf * 2.0944f);
                        float qm1 = 0.78f + 0.40f * qc;
                        r *= qm1; g *= qm1 * 1.02f; b *= qm1;
                        float qg = (float) Math.exp(-((yf - 6f) * (yf - 6f)) / 12f);
                        r *= 0.92f + 0.18f * qg; g *= 0.92f + 0.18f * qg; b *= 0.92f + 0.18f * qg;
                        float qmm1 = micro(x, y, tile * 185 + 19, 0.010f);
                        r *= qmm1; g *= qmm1; b *= qmm1;
                        break;
                    }
                    case "FERN": case "REED": case "SPORE_POD": case "DEAD_BUSH": case "KELP": {
                        // 植物族之一：直立草本（竖缕 + 疏节；沿 x 恒定 → 不产高频能量）
                        boolean pDry = nm.equals("DEAD_BUSH") || nm.equals("FERN");
                        boolean pWet = nm.equals("KELP");
                        int pSalt = tile * 191 + 3;
                        float p1 = 0.86f + 0.18f * fbm2(xf, yf, 6, pSalt);
                        if (pWet)      { r = 0.235f * p1; g = 0.471f * p1; b = 0.314f * p1; }
                        else if (pDry) { r = 0.510f * p1; g = 0.392f * p1; b = 0.235f * p1; }
                        else           { r = 0.306f * p1; g = 0.573f * p1; b = 0.243f * p1; }
                        float stalks = 0.5f + 0.5f * (float) Math.cos(xf * 3.1416f);   // 周期 2（8px@S=4）
                        float p2 = 0.80f + 0.34f * stalks;
                        r *= p2; g *= p2; b *= p2;
                        float node = ridge(xf, yf, 0f, 1f, 5f, pSalt + 7);             // 疏节
                        float p3 = 0.92f + 0.14f * node;
                        r *= p3; g *= p3; b *= p3;
                        if (nm.equals("SPORE_POD")) {          // 孢子囊：中部一个鼓包（换色而非加噪）
                            float pod = (float) Math.exp(-((yf - 5f) * (yf - 5f)) / 8f);
                            r = r * (1f - pod) + 0.588f * pod; g = g * (1f - pod) + 0.376f * pod; b = b * (1f - pod) + 0.470f * pod;
                        }
                        float pm1 = micro(x, y, pSalt + 19, 0.010f);
                        r *= pm1; g *= pm1; b *= pm1;
                        break;
                    }
                    // ---- 第十二批 · 玩法纵深（9 块）----
                    case "SAPLING": case "BAMBOO": {      // 树苗 / 竹：嫩绿竖茎 + 明显节（竹节更重）
                        boolean bam = nm.equals("BAMBOO");
                        int sSalt = tile * 193 + 3;
                        float s1 = 0.88f + 0.16f * fbm2(xf, yf, 6, sSalt);
                        r = 0.275f * s1; g = 0.510f * s1; b = 0.235f * s1;
                        if (bam) { r = 0.588f * s1; g = 0.706f * s1; b = 0.275f * s1; }
                        //  ⚠️ 第一版竖缕周期 2（8px）+ 竹节幅 0.36 → BAMBOO 的 microNoise 实测 8.10（阈值 6.0；
                        //  门禁取**均值**故仍 PASS，但单张逼近阈值不是好状态 —— 与 FARMLAND 那次同类）。
                        //  收法：周期 2→4（16px）、节幅 0.36→0.22，形态不变而高频能量显著下降。
                        float sStalk = 0.5f + 0.5f * (float) Math.cos(xf * 1.5708f);
                        float s2 = 0.86f + 0.24f * sStalk;
                        r *= s2; g *= s2; b *= s2;
                        float sNode = ridge(xf, yf, 0f, 1f, 5f, sSalt + 7);
                        float s3 = (bam ? 0.86f : 0.92f) + (bam ? 0.22f : 0.14f) * sNode;
                        r *= s3; g *= s3; b *= s3;
                        float sm1 = micro(x, y, sSalt + 19, 0.010f);
                        r *= sm1; g *= sm1; b *= sm1;
                        break;
                    }
                    case "BARREL": {                      // 木桶：竖板条（周期 4）+ 两道铁箍
                        float bo1 = 0.88f + 0.14f * fbm2(xf, yf, 6, tile * 197 + 3);
                        r = 0.588f * bo1; g = 0.431f * bo1; b = 0.251f * bo1;
                        float staves = 0.5f + 0.5f * (float) Math.cos(xf * 0.7854f);   // 周期 8（32px）
                        float bv1 = 0.82f + 0.32f * staves;
                        r *= bv1; g *= bv1; b *= bv1;
                        if (sy == 2 || sy == 13) { r *= 0.60f; g *= 0.62f; b *= 0.66f; }   // 铁箍（结构，非噪点）
                        float bmm1 = micro(x, y, tile * 197 + 19, 0.010f);
                        r *= bmm1; g *= bmm1; b *= bmm1;
                        break;
                    }
                    case "CAULDRON": {                    // 坩埚：外壁 + 圆口凸缘 + 深腔（全部平滑径向剖面）
                        float ao1 = (float) Math.sqrt((xf - 8f) * (xf - 8f) + (yf - 8f) * (yf - 8f)) / 8f;
                        float co2 = 0.84f + 0.16f * fbm2(xf, yf, 6, tile * 199 + 3);
                        float rim = (float) Math.exp(-((ao1 - 0.68f) * (ao1 - 0.68f)) / 0.010f);
                        float cav = (float) Math.exp(-(ao1 * ao1) / 0.14f);
                        float cl1 = 0.92f + 0.24f * rim - 0.46f * cav;
                        r = 0.306f * co2 * cl1; g = 0.306f * co2 * cl1; b = 0.322f * co2 * cl1;
                        float cmm1 = micro(x, y, tile * 199 + 19, 0.010f);
                        r *= cmm1; g *= cmm1; b *= cmm1;
                        break;
                    }
                    case "LECTERN": {                     // 讲台：上部斜面书板 + 下部柱身
                        float lo1 = 0.88f + 0.14f * fbm2(xf, yf, 6, tile * 211 + 3);
                        r = 0.549f * lo1; g = 0.392f * lo1; b = 0.235f * lo1;
                        float slant = sat01((11f - yf) / 6f);
                        r *= 0.86f + 0.26f * slant; g *= 0.86f + 0.24f * slant; b *= 0.88f + 0.22f * slant;
                        if (sy == 4 || sy == 5) { r *= 0.80f; g *= 0.80f; b *= 0.84f; }     // 书缝
                        float lm1 = micro(x, y, tile * 211 + 19, 0.010f);
                        r *= lm1; g *= lm1; b *= lm1;
                        break;
                    }
                    case "IRON_BARS": {                   // 铁栏杆：两根竖栏（周期 8，光滑 → 不产生 4px 能量）
                        float io1 = 0.90f + 0.10f * fbm2(xf, yf, 6, tile * 223 + 3);
                        r = 0.471f * io1; g = 0.478f * io1; b = 0.494f * io1;
                        float bars = 0.5f + 0.5f * (float) Math.cos(xf * 0.7854f);
                        float iv1 = 0.70f + 0.56f * bars;
                        r *= iv1; g *= iv1; b *= iv1;
                        float im1 = micro(x, y, tile * 223 + 19, 0.010f);
                        r *= im1; g *= im1; b *= im1;
                        break;
                    }
                    case "CHAIN": {                       // 锁链：中柱 + 沿 y 的链节（周期 4）
                        float ch1 = 0.90f + 0.10f * fbm2(xf, yf, 6, tile * 227 + 3);
                        r = 0.431f * ch1; g = 0.439f * ch1; b = 0.463f * ch1;
                        float link = 0.5f + 0.5f * (float) Math.cos(yf * 1.5708f);
                        float lk1 = 0.68f + 0.58f * link;
                        r *= lk1; g *= lk1; b *= lk1;
                        float core = (float) Math.exp(-((xf - 8f) * (xf - 8f)) / 6f);
                        r *= 0.86f + 0.26f * core; g *= 0.86f + 0.26f * core; b *= 0.86f + 0.26f * core;
                        float cmm3 = micro(x, y, tile * 227 + 19, 0.010f);
                        r *= cmm3; g *= cmm3; b *= cmm3;
                        break;
                    }
                    case "SCAFFOLDING": {                 // 脚手架：竖柱（周期 8）+ 横板（周期 5）的框格
                        float so1 = 0.88f + 0.14f * fbm2(xf, yf, 6, tile * 229 + 3);
                        r = 0.588f * so1; g = 0.494f * so1; b = 0.314f * so1;
                        float posts = 0.5f + 0.5f * (float) Math.cos(xf * 0.7854f);
                        float planks = ridge(xf, yf, 0f, 1f, 5f, tile * 229 + 11);
                        float sm4 = 0.76f + 0.30f * posts + 0.16f * planks;
                        r *= sm4; g *= sm4; b *= sm4;
                        float smm1 = micro(x, y, tile * 229 + 19, 0.010f);
                        r *= smm1; g *= smm1; b *= smm1;
                        break;
                    }
                    case "SIGN": {                        // 告示牌：木板 + 两道刻痕（文字暗示）+ 边框
                        float sgm = 0.88f + 0.14f * ridge(xf, yf, 0f, 1f, 5f, tile * 127 + 3);
                        r = 0.620f * sgm; g = 0.478f * sgm; b = 0.298f * sgm;
                        if (sy == 4 || sy == 8) { r *= 0.60f; g *= 0.60f; b *= 0.62f; }      // 刻痕
                        if (sx == 0 || sx == 15 || sy == 0 || sy == 15) { r *= 0.80f; g *= 0.80f; b *= 0.80f; }
                        break;
                    }
                    // ---- 第七批「源源不断」（2026-09-24）：红石逻辑三件套 + 生活方块 ----
                    // 形状语言纪律：形体一律走**连续场**（ridge/fbm2）+ **平滑权重**（sat01 混合），
                    //  从不用 `if (noise > t) *= k` 的硬阈值（那是 formAcf 崩掉的元凶）。
                    case "REPEATER": {                    // 中继器：石底 + 横贯导线 + 中央三角中继标记
                        float rp = 0.86f + 0.16f * fbm2(xf, yf, 6, tile * 137 + 3);
                        r = 0.596f * rp; g = 0.573f * rp; b = 0.557f * rp;
                        float rw = sat01((ridge(xf, yf, 0f, 1f, 8f, tile * 137 + 11) - 0.58f) / 0.26f);
                        r += (0.72f - r) * rw; g += (0.10f - g) * rw; b += (0.08f - b) * rw;      // 横贯导线带
                        if (sy >= 5 && sy <= 10 && Math.abs(sx - 7.5f) <= (sy - 5) * 0.72f) {     // 三角标记（离散，成片）
                            r *= 1.22f; g *= 1.10f; b *= 1.06f;
                        }
                        float rm1 = micro(x, y, tile * 137 + 19, 0.010f);
                        r *= rm1; g *= rm1; b *= rm1;
                        break;
                    }
                    case "COMPARATOR": {                  // 比较器：偏蓝石底 + 双刻度 + 侧向引出标记
                        float cp = 0.86f + 0.16f * fbm2(xf, yf, 6, tile * 139 + 3);
                        r = 0.557f * cp; g = 0.541f * cp; b = 0.596f * cp;
                        float cw = sat01((ridge(xf, yf, 0f, 1f, 8f, tile * 139 + 11) - 0.60f) / 0.24f) * 0.55f;
                        r += (0.70f - r) * cw; g += (0.12f - g) * cw; b += (0.16f - b) * cw;
                        if ((sy == 4 || sy == 11) && sx >= 4 && sx <= 11) { r *= 0.72f; g *= 0.74f; b *= 0.86f; }  // 双刻度
                        if (sx == 3 && sy >= 6 && sy <= 9) { r *= 1.16f; g *= 1.12f; b *= 1.06f; }               // 侧向引出
                        float cm = micro(x, y, tile * 139 + 19, 0.010f);
                        r *= cm; g *= cm; b *= cm;
                        break;
                    }
                    case "PLATE": {                       // 压力板：石底 + 中央凹陷平板 + 边沿亮线
                        float pp = 0.88f + 0.14f * fbm2(xf, yf, 7, tile * 149 + 3);
                        r = 0.612f * pp; g = 0.596f * pp; b = 0.580f * pp;
                        float pIn = Math.max(Math.abs(sx - 7.5f), Math.abs(sy - 7.5f));   // 离散：中央平板几何
                        if (pIn >= 5.0f && pIn < 6.2f) { r *= 1.10f; g *= 1.10f; b *= 1.08f; }    // 边沿（受光）
                        else if (pIn < 5.0f) { r *= 0.83f; g *= 0.83f; b *= 0.85f; }              // 凹面（略暗）
                        float pm = micro(x, y, tile * 149 + 19, 0.010f);
                        r *= pm; g *= pm; b *= pm;
                        break;
                    }
                    case "CAMPFIRE": {                    // 篝火：交叉柴薪 + 中央余烬 + 外缘暗圈
                        float cf = 0.86f + 0.18f * fbm2(xf, yf, 5, tile * 151 + 3);
                        r = 0.384f * cf; g = 0.267f * cf; b = 0.173f * cf;
                        float lg = Math.max(ridge(xf, yf, 1f, 1f, 7f, tile * 151 + 11),
                                            ridge(xf, yf, 1f, -1f, 7f, tile * 151 + 23));      // 两根斜柴（连续场取大）
                        float lt = sat01((lg - 0.62f) / 0.22f);
                        r += (0.46f - r) * lt; g += (0.32f - g) * lt; b += (0.20f - b) * lt;
                        float cdx = sx - 7.5f, cdy = sy - 7.5f;
                        float cd = (float) Math.sqrt(cdx * cdx + cdy * cdy);                        // 离散圆心 → 平滑径向
                        float ember = sat01((4.6f - cd) / 3.2f) * 0.85f;
                        r += (0.95f - r) * ember; g += (0.42f - g) * ember; b += (0.12f - b) * ember;
                        float cfm = micro(x, y, tile * 151 + 19, 0.012f);
                        r *= cfm; g *= cfm; b *= cfm;
                        break;
                    }
                    case "FARMLAND": {                    // 耕地：湿土 + 横向垄沟（周期 3）+ 田埂暗框
                        float fl = 0.90f + 0.12f * fbm2(xf, yf, 7, tile * 157 + 3);
                        r = 0.439f * fl; g = 0.314f * fl; b = 0.212f * fl;
                        float ft = sat01((ridge(xf, yf, 0f, 1f, 3f, tile * 157 + 11) - 0.55f) / 0.28f);
                        // 垄沟明暗比**刻意收窄**（0.72~1.06 而非 0.64~1.08）：垄沟本身是高对比条带，
                        // 拉太开会把明度二阶差分（TILEART 的 microNoise）顶上去 —— 那是个**均值**判据
                        // （不是单张阈值），这里收窄是为观感（田面不该"扎眼"），顺带留出余量。
                        r *= 0.72f + 0.34f * ft; g *= 0.72f + 0.32f * ft; b *= 0.74f + 0.28f * ft;
                        if (sx == 0 || sx == 15 || sy == 0 || sy == 15) { r *= 0.78f; g *= 0.78f; b *= 0.80f; }  // 田埂
                        float fm = micro(x, y, tile * 157 + 19, 0.006f);
                        r *= fm; g *= fm; b *= fm;
                        break;
                    }
                    // ---- 第九批（2026-09-24）：红石驱动的机械 ----
                    case "DISPENSER": {                   // 发射器：金属箱 + 正面出料口（离散）+ 侧面铆钉带
                        float dsm = 0.88f + 0.14f * fbm2(xf, yf, 6, tile * 163 + 3);
                        r = 0.620f * dsm; g = 0.604f * dsm; b = 0.588f * dsm;
                        float dt = sat01((ridge(xf, yf, 0f, 1f, 8f, tile * 163 + 11) - 0.62f) / 0.24f) * 0.5f;
                        r *= 0.86f + 0.20f * dt; g *= 0.86f + 0.20f * dt; b *= 0.88f + 0.18f * dt;   // 铆钉带
                        float dIn = Math.max(Math.abs(sx - 7.5f), Math.abs(sy - 7.5f));
                        if (dIn < 3.4f) { r *= 0.42f; g *= 0.44f; b *= 0.50f; }        // 出料口（暗腔）
                        else if (dIn < 4.4f) { r *= 1.16f; g *= 1.14f; b *= 1.08f; }    // 口沿（受光）
                        float dmm = micro(x, y, tile * 163 + 19, 0.010f);
                        r *= dmm; g *= dmm; b *= dmm;
                        break;
                    }
                    case "PISTON": {                      // 活塞：机身棱带（周期 4）+ 中央活塞头（离散方块）
                        float psm = 0.88f + 0.14f * fbm2(xf, yf, 6, tile * 167 + 3);
                        r = 0.541f * psm; g = 0.518f * psm; b = 0.502f * psm;
                        float pt = sat01((ridge(xf, yf, 0f, 1f, 4f, tile * 167 + 11) - 0.60f) / 0.24f);
                        r *= 0.82f + 0.26f * pt; g *= 0.82f + 0.26f * pt; b *= 0.84f + 0.24f * pt;   // 机身棱带
                        if (Math.abs(sx - 7.5f) <= 3.0f && Math.abs(sy - 7.5f) <= 3.0f) {           // 活塞头
                            r *= 1.20f; g *= 1.18f; b *= 1.14f;
                        }
                        float pmm = micro(x, y, tile * 167 + 19, 0.010f);
                        r *= pmm; g *= pmm; b *= pmm;
                        break;
                    }
                    default: {                        float f = fbm2(xf, yf, 5, tile * 131 + 7);
                        float m = (0.88f + 0.20f * f) * micro(x, y, tile * 131 + 19, 0.012f);
                        r *= m; g *= m; b *= m;
                        break;
                    }
                }
                // ---------- 材质后处理（统一入口）----------
                // 各分支只负责"明度场"，色彩温度交给这里。这样做的关键理由：20+ 个分支的
                // 收尾形式并不统一（有的乘 m、有的直接赋值、有的做颜色替换），逐个改必然漏；
                // 而"色彩温度"对它们其实是同一件事。
                //
                // ① 顶/底面的专属斑 —— 让三个面真正不是同一张贴图。
                //    必须用「场」而非逐像素随机：后者会立刻退回 TV 雪花（P2 的核心教训）。
                if (kind == 1) {
                    // 顶面：风化/积水（成片、略暗）
                    float k = 0.95f + 0.09f * vn2(xf, yf, 4.2f, tile * 13 + 771);
                    r *= k; g *= k; b *= k;
                } else if (kind == 2) {
                    // 底面：压实 + 湿痕（更暗，且带一点冷 —— 潮气在视觉上就是偏冷的）
                    float k = 0.93f + 0.11f * vn2(xf, yf, 3.4f, tile * 13 + 913);
                    r *= k; g *= k * 1.01f; b *= k * 1.03f;
                }
                // ② 色相斑：同一材质内部也该有"这块偏青、那块偏黄"的矿物不均 ——
                //    真实石砌墙从来不是同一个灰。缺了它，贴图就只有明度、没有色彩。
                float hb = vn2(xf, yf, 5.5f, tile * 17 + 313) - 0.5f;
                r *= 1f + hb * 0.11f;
                g *= 1f + hb * 0.02f;
                b *= 1f - hb * 0.11f;
                // ③ 暖光冷影：亮部偏暖、暗部偏冷。明度取"当前亮度 / 本色亮度"，
                //    于是各分支无需报告自己的 m（它们的累积形式各不相同）。
                if (warm != 0f) {
                    float lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) * invBaseLum;
                    float t = (lum - 0.85f) * 2.8571f;              // 1/0.35
                    if (t < -1f) t = -1f; else if (t > 1f) t = 1f;
                    float d = warm * t * 0.15f;
                    r *= 1f + d;
                    g *= 1f + d * 0.15f;                            // 绿是感知上的"中性"通道，几乎不动
                    b *= 1f - d;
                }
                // ④ 对比拉伸：各分支的明度 m 多在 0.70~1.20（±25%），对 64px 贴图而言太保守
                //    —— 远看会糊成一片"水泥色"（实测实机截图：石砌地面整体像水泥地，
                //    而不是"一块块石头"）。围绕材质本色<b>对称</b>拉伸，均值不变
                //    （所以不会整体变亮/变暗），只有"对比"更明确。
                //    不钳位：LAMP 等靠 m>1 表达过亮，钳到 1 会把光晕压平。
                r = brC + (r - brC) * CONTRAST;
                g = bgC + (g - bgC) * CONTRAST;
                b = bbC + (b - bbC) * CONTRAST;
                put(px, tx0 + x, ty0 + y, r, g, b, a);
            }
        }
    }
}
