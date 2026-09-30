package core.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 体素方块注册表（MC 风材质），对应 Python 侧 world/material.py 的 MaterialRegistry。
 *
 * 设计要点：
 *  - 每个方块 = 字符串 id + 整数索引（mat 数组存索引）+ 渲染属性（颜色/是否实心/是否透明/是否液体）。
 *  - 索引一旦分配即固定（确定性需要：存档与复现依赖索引而非字符串）。
 *  - MC 明亮饱和配色（见 production/art-bible.md v1.1 的 MC 调色板）。
 *  - 当前为内置代表性集合；剩余 81 系统的 matter 可按 PORTING_PLAYBOOK 机械扩充 register()。
 */
public final class Blocks {

    /** 方块静态属性。 */
    public static final class Block {
        public final String id;
        public final int index;
        public final int r, g, b;       // 0-255 基础色（渲染用）
        public final boolean solid;     // 是否阻挡移动/碰撞
        public final boolean opaque;    // 是否不透明（<b>光照传播</b>用：光不进不透明格）
        public final boolean liquid;    // 是否液体
        public final boolean wind;      // 是否植被（渲染层做顶点风动；纯渲染标记，不进仿真）
        /**
         * 是否半透明（水/玻璃）——<b>纯渲染标记</b>不进仿真。
         * 语义有二：① 该方块的网格进独立「透明 pass」（后画 + 混合 + 不写深度）；
         * ② 它<b>不遮挡</b>邻居的面（见 {@link FaceCull}）→ 水下的地面/玻璃后的内壁才有几何可画。
         */
        public final boolean translucent;
        /**
         * 自动拼贴组（泰拉瑞亚缺口①，纯渲染标记，不进仿真/不进指纹）。
         * 同组相邻方块在网格层做「边缝暗化」拼贴——同一材质内部无缝、材质边界显缝。
         * 0 = 不参与拼贴（默认）。赋值见本类末尾静态块。
         */
        public int autotileGroup = 0;
        /**
         * 发光颜色（泰拉瑞亚缺口② 选择性泛光用，纯渲染，不进仿真）。
         * 非 null 即发光源（LAMP/FIRE/GOLD）；值 &gt;1 以保证亮部阈值能捕获泛光。
         * 赋值见本类末尾静态块。
         */
        public float[] emissive = null;
        /**
         * 实心区间：{@code [shapeBase, shapeBase + shapeHeight]}（0..1 的格内竖直区间）。
         *
         * <p><b>第十六批</b>建立"非满格"概念（只有 {@link #shapeHeight}，起点固定贴格底）；
         * <b>第十七批</b>补上 {@link #shapeBase} 把形状升级成<b>二元区间</b>，于是同一套设施同时表达：
         * <ul>
         *   <li>整块 {@code (0, 1)}；下半砖 {@code (0, 0.5)}；<b>上半砖</b> {@code (0.5, 0.5)}；
         *       <b>雪层</b> {@code (0, 0.125)}；</li>
         *   <li>判据 {@code isFullShape()} = {@code base == 0 && height == 1} ⇒ "谁也不特殊"。</li>
         * </ul>
         *
         * <p>它被三处消费，且三处<b>必须走同一对字段</b>（否则会出现"看着是半格、撞上去是满格"）：
         * <ol>
         *   <li><b>渲染</b>：{@code Game.emit} / {@code Chunk.emitQuad} 把面的<b>下边</b>从 {@code y} 抬到
         *       {@code y+base}、<b>上边</b>从 {@code y+1} 收到 {@code y+base+height} —— 各一行，6 个面同时正确；</li>
         *   <li><b>面剔除</b>：{@link FaceCull#visible} —— 任一方非满形状 ⇒ 它遮不住整个面 ⇒ 可见
         *       （否则会露出空腔/空洞）；</li>
         *   <li><b>实体碰撞</b>：{@code World.floorY} / {@code World.solidBox} / {@code Player.collides} /
         *       {@code Player.spawnY} —— AABB 只与 {@code [y+base, y+base+height]} 相交。</li>
         * </ol>
         * <b>光照刻意不用它</b>：形状对光学的影响由材料 json 的 {@code lightDecay} 表达 ⇒ 零代码改动
         * （见 {@code World.sweepAxis} 走的是 {@code materials.lightDecay(m)}）。
         *
         * <p><b>确定性</b>：静态表字段（与 {@code mat} 的取值无关）⇒ 不进 {@code hashState}、不改仿真。
         * <b>零回归论证就写在这里：全表 {@code isFullShape()} 为真时，上述三处判据全部退化为改动前的行为。</b>
         */
        public final float shapeBase;
        /** 见 {@link #shapeBase}（实心区间的高度）。 */
        public final float shapeHeight;

        Block(String id, int index, int r, int g, int b, boolean solid, boolean opaque, boolean liquid,
              boolean wind, boolean translucent, float shapeBase, float shapeHeight) {
            this.id = id; this.index = index;
            this.r = r; this.g = g; this.b = b;
            this.solid = solid; this.opaque = opaque; this.liquid = liquid; this.wind = wind;
            this.translucent = translucent;
            this.shapeBase = shapeBase; this.shapeHeight = shapeHeight;
        }

        /**
         * 是否「台阶」（第十七批）：**L 形** —— 下半格满 + 上半格只占**朝向那一半**。
         *
         * <p>它刻意<b>不</b>用 {@code shapeBase/shapeHeight} 表达（那两个字段只能描述"一个竖直区间"，
         * 而台阶是两个错开的盒）。所以台阶的 {@code (base, height) == (0, 1)} 却仍<b>不是满形状</b> ——
         * 几何上它是 L 形，会让邻居露出空腔。赋值见 {@link #STAIRS}。
         */
        public boolean stairs = false;
        /**
         * 是否「轨道」（第十九批）：贴地薄片 + **朝向决定走向**（沿 X 还是沿 Z）。
         *
         * <p>它不需要单独的 {@code isFullShape} 豁免 —— 轨道高度只有 1/16 格，本来就不是满形状。
         * 但朝向对它是**语义必需**的（贴图要转 90°），所以进 {@link #needsFacing}。
         */
        public boolean rail = false;

        /** 是否**整格实心**（唯一的"普通方块"判据：所有形状判据都从它取反）。 */
        public boolean isFullShape() { return shapeBase == 0f && shapeHeight == 1f && !stairs; }
    }

    private static final List<Block> ORDER = new ArrayList<>();
    private static final Map<String, Block> BY_ID = new LinkedHashMap<>();
    private static int nextIndex = 0;

    // ---- 形状常量 ----------------------------------------------------------
    // ⚠️ **必须声明在方块声明之前**：下面那些方块常量的初始化器会引用它们，而 Java 的
    //    静态初始化严格按**声明顺序**执行 ⇒ 放在后面会触发"非法前向引用"编译错误
    //    （2026-09-26 实测：`SNOW_LAYER = regLayer(..., LAYER_BLOCK)` 就是这么翻车的）。
    /** 满格（默认）实心高度。 */
    private static final float FULL_BLOCK = 1.0f;
    /** 台阶/半砖的实心高度（第十六批；公开给门禁/工具断言用）。 */
    public static final float HALF_BLOCK = 0.5f;
    /** 薄层（雪层）的实心高度（第十七批）：照 MC 取 1/8 格。 */
    public static final float LAYER_BLOCK = 0.125f;
    /** 轨道片的实心高度（第十九批）：1/16 格 —— 薄到几乎不改变地形，却足以让玩家"踩在上面"。 */
    public static final float RAIL_BLOCK = 0.0625f;

    /**
     * 「台阶」方块名单（第十七批）。台阶的几何是 **L 形**（下半格满 + 上半格只占朝向那一半），
     * 无法用单个竖直区间描述 ⇒ 用 {@link Block#stairs} 标记，而本名单是它的<b>唯一赋值处</b>。
     * ⚠️ 必须声明在方块声明**之前**：方块初始化器会往它里面 add（静态初始化按声明顺序执行）。
     */
    private static final List<Block> STAIRS = new ArrayList<Block>();

    /**
     * 水体半透明 alpha —— <b>渲染层唯一来源</b>。GL 路径（{@code TextureAtlas} 的 WATER tile alpha）与
     * 软件预览器（{@code render.software.SoftwareRenderer} 的混合 alpha）都取这里，
     * 杜绝"同一概念两处定义"（审计 C2）。调水浓淡只改这一个数。
     */
    public static final float WATER_ALPHA = 0.60f;

    // ---- 内置代表方块（明亮 MC 风）----
    public static final Block AIR       = reg("AIR",        0,   0,   0, false, false, false);
    public static final Block GRASS     = reg("GRASS",    96, 159,  59, true,  true,  false);
    public static final Block DIRT      = reg("DIRT",    134,  96,  67, true,  true,  false);
    public static final Block STONE     = reg("STONE",   128, 128, 128, true,  true,  false);
    public static final Block WATER     = reg("WATER",    58, 123, 208, false, false, true, false, true);  // 半透明：进透明 pass + 不遮挡邻居
    public static final Block SAND      = reg("SAND",    219, 205, 139, true,  true,  false);
    public static final Block WOOD      = reg("WOOD",    128,  86,  47, true,  true,  false);
    public static final Block LEAF      = reg("LEAF",     76, 168,  59, true,  false, false, true);  // 植被：风动
    public static final Block BEDROCK   = reg("BEDROCK",  38,  38,  38, true,  true,  false);
    public static final Block LAMP      = reg("LAMP",     255, 207, 107, true,  true,  false); // 暖炉辉光
    public static final Block SHELTER   = reg("SHELTER",  192, 138,  78, true,  true,  false); // 聚落暖木
    public static final Block COAL_ORE  = reg("COAL_ORE",  48,  48,  52, true,  true,  false);
    public static final Block IRON_ORE  = reg("IRON_ORE", 154, 140, 122, true,  true,  false);
    public static final Block FIRE      = reg("FIRE",     255, 122,  40, false, false, false);
    public static final Block GLASS     = reg("GLASS",    170, 220, 236, true,  false, false, false, true);  // 半透明：只画框+高光（内部 alpha=0 镂空），但不遮挡其后几何
    // ---- P2 续批新增方块（索引追加，渲染层自动取色）----
    public static final Block SNOW      = reg("SNOW",     236, 240, 245, false, false, false); // 积雪层（非实心）
    public static final Block ICE       = reg("ICE",      150, 210, 235, true,  true,  false); // 冰面（实心）
    public static final Block CACTUS    = reg("CACTUS",    60, 150,  60, true,  true,  false); // 仙人掌（实心）
    public static final Block FLOWER    = reg("FLOWER",   222, 120, 140, false, false, false, true); // 野花（装饰非实心，风动）；AP3：由 Chunk.vegTint 按体素取花田色盘
    public static final Block MOSS      = reg("MOSS",      86, 140,  70, true,  true,  false); // 苔藓/地衣（覆石）
    public static final Block CLAY      = reg("CLAY",     196, 160, 118, true,  true,  false); // 黏土（河边泥岸）
    // ---- C6 巨构建材（ART_BIBLE §9.4：索引追加；苍白条石家族 + 金饰，渲染层自动取色）----
    public static final Block ASHLAR       = reg("ASHLAR",        212, 206, 192, true, true, false); // 苍白条石（墙体/柱身；明度0.82 与地形绿褐分离）
    public static final Block ASHLAR_SHADE = reg("ASHLAR_SHADE",  146, 140, 128, true, true, false); // 阴面条石（基座/收分/顶盖）
    public static final Block ASHLAR_VEIN  = reg("ASHLAR_VEIN",   188, 182, 170, true, true, false); // 纹石（12% 点缀打散白墙）
    public static final Block GOLD         = reg("GOLD",          232, 190,  96, true, true, false); // 金饰（全场景唯一高饱和暖色）
    // ---- 批 B（2026-09-18）：火山灰专属材质 ----
    // 此前 AshFallSystem 只能拿 STONE 占位（它的注释自陈"硬约束：不碰 Blocks.java"）——
    // 那是材料表贫瘠年代的妥协。现在有了材料规格层，灰可以有自己的一切：
    // cellType=powder（可被更重的材料压开）+ density 14（重于水 10 → 灰会沉入水底）。
    public static final Block ASH          = reg("ASH",            96,  92,  88, true, true, false); // 火山灰沉积层

    // ---- 融合百家（2026-09-23）：参考 MC / NOITA / 泰拉瑞亚 的方块类型扩充调色板 ----
    // 全部索引追加（不重排既有索引，存档/复现零影响）；渲染层自动取色、自动烘焙 tile。
    // 末尾两枚 AMETHYST / LANTERN 为自研新类型（发光晶体 / 自研灯具）。
    public static final Block COBBLE     = reg("COBBLE",     130, 128, 126, true, true, false); // 圆石
    public static final Block GRANITE    = reg("GRANITE",    160, 138, 120, true, true, false); // 花岗岩
    public static final Block OBSIDIAN   = reg("OBSIDIAN",    28,  24,  34, true, true, false); // 黑曜石（火山）
    public static final Block SLATE      = reg("SLATE",       72,  80,  94, true, true, false); // 板岩（冷蓝灰）
    public static final Block GRAVEL     = reg("GRAVEL",     138, 132, 124, true, true, false); // 砂砾
    public static final Block RED_SAND   = reg("RED_SAND",   201, 110,  72, true, true, false); // 红沙（MC 沙漠）
    public static final Block PODZOL     = reg("PODZOL",     112,  74,  52, true, true, false); // 灰化土（MC 针叶林）
    public static final Block SOUL_SAND  = reg("SOUL_SAND",   92,  82,  74, true, true, false); // 灵沙（地狱）
    public static final Block BRICK      = reg("BRICK",      166,  72,  56, true, true, false); // 红砖
    public static final Block SANDSTONE  = reg("SANDSTONE",  202, 186, 142, true, true, false); // 砂岩
    public static final Block TERRACOTTA = reg("TERRACOTTA", 171, 102,  72, true, true, false); // 陶瓦
    public static final Block AMETHYST   = reg("AMETHYST",   150,  92, 201, true, true, false); // 紫晶（自研·发光）
    public static final Block LANTERN    = reg("LANTERN",    222, 182,  96, true, true, false); // 灯笼（自研·发光）
    public static final Block VINES      = reg("VINES",       64, 122,  56, true, true, false); // 藤蔓
    public static final Block MUSHROOM   = reg("MUSHROOM",   184,  52,  52, true, true, false); // 蘑菇（红伞）
    public static final Block HAY        = reg("HAY",        206, 186,  92, true, true, false); // 干草

    // ---- 经济与液体扩展（2026-09-23 第二批「源源不断」）：矿石 + 冶炼链 / 液体变体 / 作物 ----
    // 索引自动追加；全部走现有 mat/cellType/itemForBlock，不引入新状态层，零漂移安全。
    // 矿石嵌石族（autotile 组 2），挖之掉矿石物品 → 冶炼成锭（见 RecipeBook + items/*.json）。
    public static final Block COPPER_ORE = reg("COPPER_ORE", 122, 104,  86, true, true, false); // 铜矿（岩+绿铜斑）
    public static final Block TIN_ORE    = reg("TIN_ORE",   150, 148, 150, true, true, false); // 锡矿（岩+浅斑）
    public static final Block GOLD_ORE   = reg("GOLD_ORE",  150, 135,  80, true, true, false); // 金矿（岩+金斑）
    public static final Block SILVER_ORE = reg("SILVER_ORE",150, 150, 155, true, true, false); // 银矿（岩+银白斑）
    public static final Block LEAD_ORE   = reg("LEAD_ORE",  110, 110, 115, true, true, false); // 铅矿（岩+暗灰斑）
    public static final Block EMERALD_ORE= reg("EMERALD_ORE", 90, 130, 100, true, true, false); // 绿宝石矿（岩+翠斑）
    // 液体变体：岩浆（liquid，发光，蔓延+点燃）/ 泥浆（powder，重于水，沉底）
    public static final Block LAVA       = reg("LAVA",       200,  80,  25, false, false, true, false, false); // 岩浆（发光流体；不透明 pass，不穿透邻居面）
    public static final Block MUD        = reg("MUD",         95,  75,  50, true, true, false); // 泥浆（松散，可被更重材料压开）
    // 作物：每生长阶段=一个方块索引（plant cellType，非实心不挡光），由 CropSystem 做确定性生长 tick。
    public static final Block WHEAT_0    = reg("WHEAT_0",    176, 158,  78, false, false, false); // 小麦苗
    public static final Block WHEAT_1    = reg("WHEAT_1",    188, 168,  74, false, false, false); // 小麦抽穗
    public static final Block WHEAT_2    = reg("WHEAT_2",    200, 178,  70, false, false, false); // 小麦灌浆
    public static final Block WHEAT_3    = reg("WHEAT_3",    214, 190,  64, false, false, false); // 小麦成熟（可收获→wheat）
    public static final Block SUGARCANE_0= reg("SUGARCANE_0",120, 168,  92, false, false, false); // 甘蔗幼
    public static final Block SUGARCANE_1= reg("SUGARCANE_1",118, 174,  86, false, false, false); // 甘蔗中
    public static final Block SUGARCANE_2= reg("SUGARCANE_2",112, 180,  80, false, false, false); // 甘蔗高（可收获→sugarcane）
    public static final Block CACTUS_FLOWER_0 = reg("CACTUS_FLOWER_0", 92, 162,  92, false, false, false); // 仙人掌花蕾
    public static final Block CACTUS_FLOWER_1 = reg("CACTUS_FLOWER_1", 88, 168,  88, false, false, false); // 仙人掌花苞
    public static final Block CACTUS_FLOWER_2 = reg("CACTUS_FLOWER_2",120, 200, 110, false, false, false); // 仙人掌花放（可收获→cactus_flower）

    // ---- 功能方块（2026-09-23 第三批「源源不断」）：门/箱/熔炉/工作台 ----
    // 这四块**首次引入 per-block 状态**（见 World.meta/chestStore/furnace*）——
    // 但它们本身仍走既有 mat/cellType/itemForBlock：索引追加、不重排、存档零影响。
    // 状态全部由玩家动作（USE）与 FurnaceSystem 驱动，不进 hashState → 零漂移门禁不变。
    // 独立物件，不参与自动拼贴（autotileGroup 保持 0）。
    public static final Block DOOR       = reg("DOOR",      150, 104,  60, true, true, false); // 木门（开/关；开态可通行）
    public static final Block CHEST      = reg("CHEST",     152, 108,  58, true, true, false); // 箱子（存取物品）
    public static final Block FURNACE    = reg("FURNACE",   108, 106, 104, true, true, false); // 熔炉（矿石→锭）
    public static final Block WORKBENCH  = reg("WORKBENCH", 160, 118,  72, true, true, false); // 工作台（合成功能方块）

    // ---- 第四批（2026-09-23「源源不断」）：矿物经济 + 金属储块 + 石砖建材 + 发光装饰 ----
    // 索引追加（不重排既有索引，存档/复现零影响）；纯方块层，不引新状态、不动仿真。
    // 矿石（嵌石族 autotile 2，挖之掉宝石/矿料物品；diamond/redstone/lapis/quartz 直接掉落，不冶炼）。
    public static final Block DIAMOND_ORE = reg("DIAMOND_ORE",118, 140, 150, true, true, false); // 钻石矿（岩+钻蓝斑）
    public static final Block REDSTONE_ORE= reg("REDSTONE_ORE",120,  90,  90, true, true, false); // 红石矿（岩+赤斑）
    public static final Block LAPIS_ORE   = reg("LAPIS_ORE",  106, 116, 138, true, true, false); // 青金石矿（岩+靛斑）
    public static final Block QUARTZ_ORE  = reg("QUARTZ_ORE", 140, 136, 132, true, true, false); // 石英矿（岩+白斑）
    // 金属/宝石储块（9 锭→1 块；建材兼仓储）。
    public static final Block IRON_BLOCK  = reg("IRON_BLOCK", 216, 216, 216, true, true, false); // 铁块
    public static final Block GOLD_BLOCK  = reg("GOLD_BLOCK", 232, 190,  96, true, true, false); // 金块
    public static final Block COPPER_BLOCK= reg("COPPER_BLOCK",196, 120,  80, true, true, false); // 铜块
    public static final Block BRONZE_BLOCK= reg("BRONZE_BLOCK",200, 140,  80, true, true, false); // 青铜块（合金）
    public static final Block EMERALD_BLOCK=reg("EMERALD_BLOCK", 46, 200, 120, true, true, false); // 绿宝石块
    // 石砖建材族（砌体；变体：苔/裂/雕）。
    public static final Block STONE_BRICK       = reg("STONE_BRICK",        128, 128, 128, true, true, false); // 石砖
    public static final Block MOSSY_STONE_BRICK = reg("MOSSY_STONE_BRICK",  110, 124, 104, true, true, false); // 苔石砖
    public static final Block CRACKED_STONE_BRICK=reg("CRACKED_STONE_BRICK",120, 120, 120, true, true, false); // 裂石砖
    public static final Block CHISELED_STONE_BRICK=reg("CHISELED_STONE_BRICK",150,148,144,true, true, false); // 雕石砖
    // 装饰 / 发光。
    public static final Block GLOWSTONE   = reg("GLOWSTONE",  246, 214, 120, true, true, false); // 萤石（发光）
    public static final Block SEA_LANTERN = reg("SEA_LANTERN",190, 230, 220, true, true, false); // 海晶灯（发光）
    public static final Block BOOKSHELF   = reg("BOOKSHELF",  150, 110,  66, true, true, false); // 书架
    public static final Block SMOOTH_STONE= reg("SMOOTH_STONE",156,156, 156, true, true, false); // 平滑石
    public static final Block POLISHED_GRANITE=reg("POLISHED_GRANITE",176,124,102,true,true,false); // 磨光花岗岩

    // ---- 第五批（2026-09-24「源源不断」）：功能方块之二（床/梯/栅栏/拉杆/按钮）+ 红石导线 ----
    // 索引追加；床/拉杆/按钮/导线的"开关态"复用第三批的 per-block 状态层（World.meta，不进 hashState）。
    // 梯子是可攀爬非实心（Player 物理读 World.isLadder）；栅栏是实心非不透明（能看见后面）。
    // 注意：LADDER/LEVER/BUTTON/WIRE 在此标 {@code solid=true}（材料意义上"实心"，使 material 规格合法、
    //   也避免被"更重材料压开"的位移逻辑盯上）；但**实体可穿过** —— 由 {@link World#solidForEntity}
    //   对它们返回 false 实现（与 DOOR 开态"solid=true 却可通行"同一范式）。故"实体碰撞的唯一真相"
    //   始终是 solidForEntity，而非本字段。
    public static final Block BED    = reg("BED",    168,  60,  64, true,  true,  false); // 床（设重生点）
    public static final Block LADDER = reg("LADDER", 150, 108,  62, true,  false, false); // 梯子（实体可穿过 + 可攀爬）
    public static final Block FENCE  = reg("FENCE",  158, 116,  68, true,  false, false); // 栅栏（挡路、可透视）
    public static final Block LEVER  = reg("LEVER",  120, 118, 116, true,  false, false); // 拉杆（红石开关）
    public static final Block BUTTON = reg("BUTTON", 132, 128, 122, true,  false, false); // 按钮（红石脉冲）
    public static final Block WIRE   = reg("WIRE",   108,  30,  28, true,  false, false); // 红石导线（导体）

    // ---- 第六批（2026-09-24）：功能方块之三（活板门 / 漏斗 / 告示牌）----
    public static final Block TRAPDOOR = reg("TRAPDOOR", 150, 104, 60, true, true,  false); // 活板门（开态可穿过）
    public static final Block HOPPER   = reg("HOPPER",   108, 108, 112, true, false, false); // 漏斗（容器：从上方容器抽取）
    public static final Block SIGN     = reg("SIGN",     158, 122,  76, true, false, false); // 告示牌（刻字）

    // ---- 第七批（2026-09-24）：红石逻辑三件套 + 生活方块 ----
    // 三件套把已有红石从「能亮」升级成「能搭逻辑」：三者都以 World.meta（0/1）表示自身"带电"，
    // 并作为 WireSystem 的**电源**参与网络 —— 与 LEVER/BUTTON 完全同一范式（开关态住 meta，不进 hashState）。
    // 均为"材料实心但实体可穿过"的薄件（实体碰撞由 World.solidForEntity 放行）。
    // 语义诚实声明：本作导线网络是**布尔**模型（通电/断电，无信号强度），故
    //   REPEATER = 采样保持中继（脉冲整形 / 信号再生），COMPARATOR = 多路符合门（≥2 路输入才输出）。
    //   真·模拟信号比较需要先给导线网络引入"信号强度"（独立一步，未做）。
    public static final Block REPEATER  = reg("REPEATER",  152, 146, 142, true, false, false); // 中继器（采样保持）
    public static final Block COMPARATOR= reg("COMPARATOR",142, 138, 152, true, false, false); // 比较器（≥2 路符合门）
    public static final Block PLATE     = reg("PLATE",     156, 152, 148, true, false, false); // 压力板（踩踏）
    // 生活方块：篝火常燃（光源 + 发光 + 踩上去烧伤），耕地给作物生长加成。
    public static final Block CAMPFIRE  = reg("CAMPFIRE",   98,  68,  44, true, true,  false); // 篝火（常燃光源）
    public static final Block FARMLAND  = reg("FARMLAND",  112,  80,  54, true, true,  false); // 耕地（作物加成）

    // ---- 第九批（2026-09-24）：红石驱动的机械（有内部状态 / 沿触发）----
    // 两者的动作是"通电上升沿执行一次"（发射器推 1 件 / 活塞顶高一格），状态位住 World.meta；
    // 发射器的弹匣复用 World.chestStore（与箱/漏斗同一容器范式）。实体可穿过（薄件）。
    public static final Block DISPENSER = reg("DISPENSER", 158, 152, 146, true, false, false); // 发射器（弹匣→相邻容器）
    public static final Block PISTON    = reg("PISTON",    138, 132, 128, true, true,  false); // 活塞（垂直顶推）

    // ---- 第十一批（2026-09-24）：观察者（有"记忆"的元件）----
    // ⚠️ **必须声明在块列表的最末尾**：`reg()` 按**声明顺序**递增分配 index，而 index 直接进 `mat`
    //   → 插在中间会让其后所有方块的 index 平移 → `hashState` 变 → 零漂移当场破。
    // 语义：**看背面**（朝的反向）那一格，它的"身份/状态"变了就朝前面发 1 tick 脉冲。
    // 状态：`meta = (上次观察到的编码 << 1) | 本 tick 脉冲`（0 = 还没观察过 → 首次不报）。
    public static final Block OBSERVER  = reg("OBSERVER",  104, 108, 118, true, true,  false); // 观察者（变化→脉冲）

    // ---- 第十二批（2026-09-25）「系统之影」：让已有涌现变得**看得见**（8 块）----
    //  这些方块是 8 个既有系统的"主题实体"：此前那些系统只能拿 LEAF/SAND/LAMP 当替身，玩家看不出它在做什么。
    public static final Block CORAL           = reg("CORAL",           200,  90, 130, true,  true,  false); // 珊瑚块（CoralSystem 的实体）
    public static final Block BEEHIVE         = reg("BEEHIVE",         214, 168,  72, true,  true,  false); // 蜂巢（BeehiveSystem）
    public static final Block CRYSTAL         = reg("CRYSTAL",         150, 120, 220, true,  true,  false); // 晶石块（CrystalSystem/GeodeSystem）
    public static final Block CRYSTAL_CLUSTER = reg("CRYSTAL_CLUSTER", 180, 156, 232, false, false, false); // 晶簇（CrystalSystem 析出的那个；第十二批改为不随风摆 + 自发光）
    public static final Block FERN            = reg("FERN",             78, 140,  66, false, false, false, true); // 蕨（FernSystem）
    public static final Block REED            = reg("REED",            150, 170,  90, false, false, false, true); // 芦苇（ReedSystem）
    public static final Block SPORE_POD       = reg("SPORE_POD",       150, 120, 140, false, false, false, true); // 孢子囊（SporeSystem）
    public static final Block DEAD_BUSH       = reg("DEAD_BUSH",       130, 100,  60, false, false, false, true); // 枯木（旱地可采集物）

    // ---- 第十二批 · 玩法纵深：让玩家能主动做一件新事（9 块）----
    public static final Block SAPLING     = reg("SAPLING",     70, 130,  60, false, false, false, true); // 树苗（种树闭环：SAPLING → WOOD+LEAF）
    public static final Block BAMBOO      = reg("BAMBOO",     150, 180,  70, true,  true,  false); // 竹（群系可采集物）
    public static final Block KELP        = reg("KELP",        60, 120,  80, false, false, false, true); // 海带（水下可采集）
    public static final Block BARREL      = reg("BARREL",     150, 110,  64, true,  true,  false); // 木桶（小容器，复用 chestStore）
    public static final Block CAULDRON    = reg("CAULDRON",    78,  78,  82, true,  true,  false); // 坩埚（装水：水位住 meta 0..3）
    public static final Block LECTERN     = reg("LECTERN",    140, 100,  60, true,  true,  false); // 讲台（复用 signText 的文本编辑）
    public static final Block IRON_BARS   = reg("IRON_BARS",  120, 122, 126, true,  false, false); // 铁栏杆（挡路可透视，同 FENCE 范式）
    public static final Block CHAIN       = reg("CHAIN",      110, 112, 118, true,  false, false); // 锁链（可穿过 + 可攀爬，实体碰撞交 solidForEntity）
    public static final Block SCAFFOLDING = reg("SCAFFOLDING",150, 126,  80, true,  false, false); // 脚手架（同上）

    // ---- 第十六批（2026-09-25）「几何基座」：首个**非满格**方块（台阶/半砖，shapeHeight=0.5）----
    // 这是本项目第一次让"一个方块不是整格"：渲染上边收窄 + 面剔除按 shape 放行 + 碰撞只挡半格。
    // 颜色刻意取得比满格原方块**略亮**（切面受光更足），也让 SoftwareRenderer 的预览能区分两者。
    // ⚠️ 索引追加在**最末尾**（SCAFFOLDING 之后）—— reg() 按声明顺序分配 index，插入中间会移动
    //    既有方块的 mat 取值（= 改存档语义）。
    public static final Block STONE_SLAB  = regHalf("STONE_SLAB", 132, 132, 130, true, true); // 石台阶（半格）
    public static final Block WOOD_SLAB   = regHalf("WOOD_SLAB",  132,  92,  52, true, true); // 木台阶（半格）

    // ---- 第十七批（2026-09-26）「台阶家族」：给已有方块族各配一档半砖 ----
    // ⚠️ **命名约定是契约**：半砖的 id 一律为 `<同族整块 id>_SLAB` ——
    //    贴图母题复用（{@code TextureAtlas.paintTile}）与门禁断言都从这条约定推导，
    //    且 {@code MeshCullTest} 会断言"每个非满格方块都有对应的同族整块"（推不出就 FAIL，
    //    不会变成"某档台阶静默用错贴图"）。
    //    色值 = 同族整块 + 4~6（切面受光更足）：既让软件预览能区分，也保证 TILEART-UNIQ
    //    （"逐像素相同的材质对 = 0"）仍然成立 —— 母题复用不等于贴图相同。
    public static final Block COBBLE_SLAB      = regHalf("COBBLE_SLAB",      134, 132, 130, true, true); // 圆石台阶
    public static final Block STONE_BRICK_SLAB = regHalf("STONE_BRICK_SLAB", 134, 132, 132, true, true); // 石砖台阶
    public static final Block SANDSTONE_SLAB   = regHalf("SANDSTONE_SLAB",   208, 192, 148, true, true); // 砂岩台阶
    public static final Block BRICK_SLAB       = regHalf("BRICK_SLAB",       172,  78,  62, true, true); // 红砖台阶
    public static final Block IRON_BLOCK_SLAB  = regHalf("IRON_BLOCK_SLAB",  222, 222, 224, true, true); // 铁块台阶

    // ---- 第十七批（一）「形状二元化」：上半砖 + 薄层 ----
    // `shapeBase` 一加进来，同一套设施就同时表达"从哪起、多高"，**判据与渲染都无需任何分支**：
    //   下半砖 (0, 0.5) / **上半砖 (0.5, 0.5)** / **雪层 (0, 0.125)**。
    // ⚠️ 命名约定（贴图母题推导靠它）：`<同族整块>_SLAB` / `<同族整块>_SLAB_TOP` / `<同族整块>_LAYER`。
    //     `SNOW_LAYER` 的"同族整块"是 SNOW（薄层用雪的母题，只是高度不同）—— 这正是母题复用该有的样子。
    public static final Block STONE_SLAB_TOP = regTopHalf("STONE_SLAB_TOP", 136, 136, 134, true, true); // 石上半砖
    public static final Block WOOD_SLAB_TOP  = regTopHalf("WOOD_SLAB_TOP",  136,  96,  56, true, true); // 木上半砖
    public static final Block SNOW_LAYER     = regLayer("SNOW_LAYER", 236, 240, 245, true, false, LAYER_BLOCK); // 积雪薄层（1/8 格，可踩）

    // ---- 第十七批（三）「台阶」：L 形方块（下半格满 + 上半格占朝向那半）----
    // 与前两批的关系：半砖/薄层是"**一个竖直区间**"（(base, height) 就够），台阶是**两个错开的盒**，
    // 所以它必须单独标记（`stairs`）—— 几何上它会让邻居露出空腔，因此（0,1）也仍**不是**满形状。
    // ⚠️ 朝向存在 per-block `blockState`（与红石元件同一套设施），放置时按玩家视线**水平化**；
    //    ⚠️ 它**不**进 `ORIENTABLE` —— 那份名单的语义是"哪一面画端口图案"，台阶不需要端口。
    public static final Block STONE_STAIRS = regStairs("STONE_STAIRS", 136, 136, 134, true, true); // 石台阶（L 形）

    // ---- 第十八批（2026-09-26）「台阶填表」：把台阶家族补齐 ----
    // 与半砖族**同一条命名约定**（`<同族整块>_STAIRS`）⇒ 贴图母题推导零改动、门禁白名单照登记。
    // 颜色 = 同族整块 + 4~6（与半砖族同一规则）⇒ TILEART-UNIQ（逐像素相同的材质对 = 0）仍成立。
    // 关于「转角台阶」：**不需要新方块也不需要新代码** —— 两个朝向不同的台阶**相邻摆放**，
    // 各自的 L 形拼起来就是转角（MC 的转角台阶同样由两个方块拼出）。这条没有实现成本，
    // 只缺一张验收图（见 SoftwareRenderer 的 slab 场景 ⑥）。
    public static final Block WOOD_STAIRS      = regStairs("WOOD_STAIRS",      132,  92,  52, true, true); // 木台阶
    public static final Block BRICK_STAIRS     = regStairs("BRICK_STAIRS",     172,  78,  62, true, true); // 红砖台阶
    public static final Block SANDSTONE_STAIRS = regStairs("SANDSTONE_STAIRS", 208, 192, 148, true, true); // 砂岩台阶

    // ---- 第十九批（2026-09-26）「轨道」：贴地薄片 + 朝向决定走向 ----
    // 形状复用第十七批的薄层设施（1/16 格），朝向复用红石元件那套 per-block `blockState`（不进指纹）。
    // 本批给它一个**不依赖矿车的闭环**：走在轨道上加速 —— 否则轨道只是"地上一条线"，
    // 那正是我上一批拒绝单独做 RAIL 的理由。MINECART 是**另一个量级**（新实体类型 + 物理 + 上下车），
    // 单开一批做，别把"加一个方块"和"加一类实体"混在一起。
    public static final Block RAIL = regRail("RAIL", 150, 134, 110); // 轨道（1/16 格薄片，踩上去加速）

    // ---- 泰拉瑞亚缺口①：自动拼贴组（纯渲染，不进仿真）。同组相邻→无缝，异组→显缝。----
    static {
        int g = 1;
        for (Block b : new Block[]{GRASS, DIRT}) b.autotileGroup = g;          // 1 泥土族（草/泥连续）
        g = 2;
        for (Block b : new Block[]{STONE, COAL_ORE, IRON_ORE, COPPER_ORE, TIN_ORE,
                GOLD_ORE, SILVER_ORE, LEAD_ORE, EMERALD_ORE,
                DIAMOND_ORE, REDSTONE_ORE, LAPIS_ORE, QUARTZ_ORE}) b.autotileGroup = g; // 2 石族（矿嵌石+新矿）
        g = 3; SAND.autotileGroup = g;                                          // 3 沙
        g = 4; MOSS.autotileGroup = g;                                          // 4 苔
        g = 5; CLAY.autotileGroup = g;                                          // 5 黏土
        g = 6; SNOW.autotileGroup = g;                                          // 6 雪
        g = 7; ASH.autotileGroup = g;                                           // 7 火山灰
        g = 8;
        for (Block b : new Block[]{ASHLAR, ASHLAR_SHADE, ASHLAR_VEIN}) b.autotileGroup = g; // 8 条石家族
        g = 9;
        for (Block b : new Block[]{WOOD, SHELTER}) b.autotileGroup = g;        // 9 木家族
        g = 10; CACTUS.autotileGroup = g;                                       // 10 仙人掌
        // ---- 融合百家新方块：各自独立拼贴组（11..22），相邻异材质显缝、同材质无缝 ----
        g = 11; COBBLE.autotileGroup = g;
        g = 12; GRANITE.autotileGroup = g;
        g = 13; OBSIDIAN.autotileGroup = g;
        g = 14; SLATE.autotileGroup = g;
        g = 15; GRAVEL.autotileGroup = g;
        g = 16; RED_SAND.autotileGroup = g;
        g = 17; PODZOL.autotileGroup = g;
        g = 18; SOUL_SAND.autotileGroup = g;
        g = 19; BRICK.autotileGroup = g;
        g = 20; SANDSTONE.autotileGroup = g;
        g = 21; TERRACOTTA.autotileGroup = g;
        g = 22; HAY.autotileGroup = g;
        // ---- 第四批新方块：石砖族 / 石 / 金属族各自拼贴（23..26）----
        g = 23;
        for (Block b : new Block[]{STONE_BRICK, MOSSY_STONE_BRICK,
                CRACKED_STONE_BRICK, CHISELED_STONE_BRICK}) b.autotileGroup = g; // 23 石砖族
        g = 24; SMOOTH_STONE.autotileGroup = g;                                  // 24 平滑石
        g = 25; POLISHED_GRANITE.autotileGroup = g;                              // 25 磨光花岗岩
        g = 26;
        for (Block b : new Block[]{IRON_BLOCK, GOLD_BLOCK, COPPER_BLOCK,
                BRONZE_BLOCK, EMERALD_BLOCK}) b.autotileGroup = g;               // 26 金属/宝石储块族
        g = 27; FENCE.autotileGroup = g;                                         // 27 栅栏
        g = 28; CORAL.autotileGroup = g;                                         // 28 珊瑚（成片生长，要无缝拼贴）
        g = 29; CRYSTAL.autotileGroup = g;                                       // 29 晶石块
        g = 30; BEEHIVE.autotileGroup = g;                                       // 30 蜂巢
        g = 31; BAMBOO.autotileGroup = g;                                        // 31 竹
        g = 32; SCAFFOLDING.autotileGroup = g;                                   // 32 脚手架
        // 第五批的 BED/LADDER/LEVER/BUTTON/WIRE 保持 0：独立物件，不参与拼贴
        // GLOWSTONE/SEA_LANTERN/BOOKSHELF 保持 0：独立物件，不参与拼贴
        // VINES/MUSHROOM/AMETHYST/LANTERN 保持 0：独立物件，不参与拼贴
        // 其余（AIR/WATER/GLASS/ICE/LEAF/FLOWER/LAMP/FIRE/BEDROCK/GOLD）保持 0：不参与拼贴

        // ---- 泰拉瑞亚缺口②：选择性泛光发光源（值>1 以过亮部阈值）----
        LAMP.emissive     = new float[]{1.00f * 1.5f, 0.81f * 1.5f, 0.42f * 1.5f};   // 暖炉：暖白
        FIRE.emissive     = new float[]{1.00f * 1.4f, 0.48f * 1.4f, 0.16f * 1.4f};   // 火：橙红
        GOLD.emissive     = new float[]{0.91f * 1.7f, 0.74f * 1.7f, 0.38f * 1.7f};   // 金饰：高饱和暖
        // ---- 自研新类型（融合百家之后拓展的发光方块）----
        AMETHYST.emissive = new float[]{0.59f * 1.5f, 0.36f * 1.5f, 0.79f * 1.5f};   // 紫晶：冷紫辉光
        LANTERN.emissive  = new float[]{0.87f * 1.5f, 0.71f * 1.5f, 0.38f * 1.5f};   // 灯笼：暖白辉光
        LAVA.emissive      = new float[]{1.00f * 1.6f, 0.42f * 1.6f, 0.12f * 1.6f};   // 岩浆：热橙辉光（液体发光源）
        // ---- 第四批：发光装饰方块 ----
        GLOWSTONE.emissive   = new float[]{1.00f * 1.5f, 0.85f * 1.5f, 0.48f * 1.5f}; // 萤石：暖黄辉光
        SEA_LANTERN.emissive = new float[]{0.72f * 1.5f, 0.92f * 1.5f, 0.90f * 1.5f}; // 海晶灯：冷青白辉光
        // 第十二批：晶簇/晶石自带冷辉 —— 它们**替代了 CrystalSystem 原来放的 LAMP**，
        //  所以必须自己发光，否则"深岩晶簇"会变成"深岩里几块不亮的石头"（那等于把效果改差了）。
        CRYSTAL_CLUSTER.emissive = new float[]{0.62f * 1.5f, 0.52f * 1.5f, 1.00f * 1.5f}; // 紫晶簇：冷紫辉光
        CRYSTAL.emissive         = new float[]{0.50f * 1.5f, 0.42f * 1.5f, 0.80f * 1.5f}; // 晶石块：较弱冷辉
        // ---- 第七批：篝火（常燃热源；光场由 World.computeLight 的 seedLight(CAMPFIRE, …) 供，见该处）----
        CAMPFIRE.emissive    = new float[]{1.00f * 1.5f, 0.55f * 1.5f, 0.20f * 1.5f}; // 篝火：暖橙辉光

    }

    private static Block reg(String id, int r, int g, int b, boolean solid, boolean opaque, boolean liquid) {
        return reg(id, r, g, b, solid, opaque, liquid, false, false);
    }
    private static Block reg(String id, int r, int g, int b, boolean solid, boolean opaque, boolean liquid, boolean wind) {
        return reg(id, r, g, b, solid, opaque, liquid, wind, false);
    }
    private static Block reg(String id, int r, int g, int b, boolean solid, boolean opaque, boolean liquid,
                            boolean wind, boolean translucent) {
        return reg(id, r, g, b, solid, opaque, liquid, wind, translucent, 0f, FULL_BLOCK);
    }
    /** 非满格方块（台阶/半砖）：只占**下半格**（{@link #HALF_BLOCK}）。 */
    private static Block regHalf(String id, int r, int g, int b, boolean solid, boolean opaque) {
        return reg(id, r, g, b, solid, opaque, false, false, false, 0f, HALF_BLOCK);
    }
    /**
     * **上半砖**（第十七批）：占格子的上半格 {@code [0.5, 1]} —— 与下半砖共用同一套形状设施，
     * 区别只是 {@code shapeBase != 0}（这正是把形状从"一个高度"升级成"一个区间"的直接收益）。
     */
    private static Block regTopHalf(String id, int r, int g, int b, boolean solid, boolean opaque) {
        return reg(id, r, g, b, solid, opaque, false, false, false, HALF_BLOCK, HALF_BLOCK);
    }
    /** **薄层**（第十七批，雪层）：占格子最底的一层，高度由 {@link #LAYER_BLOCK} 给。 */
    private static Block regLayer(String id, int r, int g, int b, boolean solid, boolean opaque, float height) {
        return reg(id, r, g, b, solid, opaque, false, false, false, 0f, height);
    }
    /**
     * **台阶**（第十七批）：L 形（下半格满 + 上半格占朝向那半）。
     * 形状上记 {@code (0, 1)}，但 {@link Block#stairs} 为真 ⇒ {@link Block#isFullShape()} 仍为 false
     * （几何上它会与邻居之间留下空腔）。
     */
    private static Block regStairs(String id, int r, int g, int b, boolean solid, boolean opaque) {
        Block blk = reg(id, r, g, b, solid, opaque, false, false, false, 0f, FULL_BLOCK);
        blk.stairs = true;
        STAIRS.add(blk);
        return blk;
    }

    /** 是否台阶（L 形方块）—— 渲染 / 碰撞 / 门禁的**唯一判据**。 */
    public static boolean isStairs(int idx) {
        if (idx < 0 || idx >= ORDER.size()) return false;
        return ORDER.get(idx).stairs;
    }

    /** **轨道**（第十九批）：贴地薄片（1/16 格）+ 朝向决定走向。solid=true ⇒ 踩得上去。 */
    private static Block regRail(String id, int r, int g, int b) {
        Block blk = reg(id, r, g, b, true, false, false, false, false, 0f, RAIL_BLOCK);
        blk.rail = true;
        return blk;
    }

    /** 是否轨道。 */
    public static boolean isRail(int idx) {
        if (idx < 0 || idx >= ORDER.size()) return false;
        return ORDER.get(idx).rail;
    }

    /**
     * 该方块是否**需要朝向**（放置时把玩家视线方向写进 per-block {@code blockState}）。
     *
     * <p>判据 = 可定向（有端口图案，决定"哪一面画圆口"）**或** 形状/贴图随朝向变（台阶、轨道）。
     * <b>收敛成一个方法</b>是为了让 {@code Game.doPlace} 不出现第三个 {@code ||} ——
     * 那正是"同一概念多处分判"的开端（本项目反复踩过的坑）。
     */
    public static boolean needsFacing(int idx) {
        return orientableOrdinal(idx) >= 0 || isStairs(idx) || isRail(idx);
    }

    private static Block reg(String id, int r, int g, int b, boolean solid, boolean opaque, boolean liquid,
                            boolean wind, boolean translucent, float shapeBase, float shapeHeight) {
        Block block = new Block(id, nextIndex++, r, g, b, solid, opaque, liquid, wind, translucent, shapeBase, shapeHeight);
        ORDER.add(block); BY_ID.put(id, block);
        return block;
    }

    /** 实心区间下界（0..1）；越界索引按整格（保守：外世界视为实心，与 {@code getBlock} 的既有语义一致）。 */
    public static float shapeBase(int idx) {
        if (idx < 0 || idx >= ORDER.size()) return 0f;
        return ORDER.get(idx).shapeBase;
    }
    /** 实心区间高度（0..1）；越界索引按整格。 */
    public static float shapeHeight(int idx) {
        if (idx < 0 || idx >= ORDER.size()) return FULL_BLOCK;
        return ORDER.get(idx).shapeHeight;
    }
    /**
     * 该方块是否**整格实心** —— 所有"形状是否特殊"的判据都从它取反（越界按整格处理）。
     * 用一个方法而不是让各处自己写 {@code base == 0 && height == 1}，是为了让"什么算满格"只有一处定义。
     */
    public static boolean isFullShape(int idx) {
        if (idx < 0 || idx >= ORDER.size()) return true;
        return ORDER.get(idx).isFullShape();
    }

    public static int count() { return ORDER.size(); }
    public static Block byIndex(int idx) { return ORDER.get(idx); }
    public static Block byId(String id) { return BY_ID.get(id); }

    /**
     * <b>可定向方块</b>（第十批，2026-09-24）：这些方块有"正面"——
     * 放置时朝向 = 玩家视线主轴（{@link Facing#fromLook}），渲染时在朝的那一面画端口图案
     * （{@code TextureAtlas.frontTileFor}），语义上朝向决定"信号/物品从哪进出"。
     *
     * <p><b>为什么不给每个方块加一个 {@code facing} 布尔字段</b>：那会多一份"谁可定向"的名单，
     * 而这份名单必须与渲染端的端口 tile 次序<b>逐项对齐</b>。用<b>本数组同时充当注册表与 tile 次序</b>
     * —— {@code TextureAtlas.frontTileFor} 直接取 {@link #orientableOrdinal} —— 就没有第二份名单。
     *
     * <p>⚠️ <b>顺序即 front tile 编号</b>：加新方块请<b>追加在末尾</b>，否则会移动既有 tile 编号
     * （表现是"中继器的正面忽然变成活塞的头"）。
     */
    public static final Block[] ORIENTABLE = { REPEATER, COMPARATOR, PISTON, DISPENSER, HOPPER, OBSERVER };

    /** 可定向方块在本表中的序号（-1 = 不可定向）。渲染端 tile 编号与之一一对应。 */
    public static int orientableOrdinal(int idx) {
        for (int i = 0; i < ORIENTABLE.length; i++) if (ORIENTABLE[i].index == idx) return i;
        return -1;
    }
    public static int index(String id) {
        Block b = BY_ID.get(id);
        if (b == null) throw new IllegalArgumentException("unknown block: " + id);
        return b.index;
    }
    public static boolean has(String id) { return BY_ID.containsKey(id); }
    /** 是否发光源（泰拉瑞亚缺口② 选择性泛光用）：LAMP/FIRE/GOLD 等 emissive 非 null。 */
    public static boolean isEmissive(int bi) {
        if (bi < 0 || bi >= ORDER.size()) return false;
        return ORDER.get(bi).emissive != null;
    }
    public static List<Block> all() { return new ArrayList<>(ORDER); }
}
