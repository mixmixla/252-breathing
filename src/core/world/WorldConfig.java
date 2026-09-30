package core.world;

/**
 * 玩法子系统可调参数（内容层 → 仿真层的接线点）。
 *
 * <p>预设 {@code params} 里的 7 个「子系统参数」在此落位；各 {@code System} 读取对应字段，
 * 在<b>出厂默认值</b>下全部 no-op（或保持与改动前逐字节一致的旧行为），因此默认世界演化不变。
 *
 * <p><b>零漂移</b>：本对象挂在 {@link World#config}，且被 {@code StateCodec.SKIP} 排除
 * （不进快照 / 不进 netHash）—— 它是「配置」不是「演化量」，两端各自从同一预设加载即可一致。
 *
 * <p><b>铁律</b>：任何非默认参数都会改变世界演化，那是<b>意图</b>（预设显式指定）而非事故；
 * 默认 = 与本项目历史版本逐字节一致。
 */
public final class WorldConfig {

    // ---- 出厂默认值（= 改动前的世界行为；改这里 = 有意改玩法）----
    public static final float EROSION_RATE_DFLT = 1.0f;        // 1.0 → 侵蚀反应概率 0.08*1.0（与改动前一致；批 D 收编进反应表）
    public static final float ASCENSION_THRESHOLD_DFLT = 1e9f; // 不可达 → 修仙不触发
    public static final float HAUL_RATE_DFLT = 0.0f;           // 0 → 不搬运
    public static final float HUNGER_RATE_DFLT = 0.0f;         // 0 → 不饥饿
    public static final int   ORB_ITEM_COST_DFLT = 1;          // 精灵球消耗（默认 1）
    public static final float HP_THRESHOLD_DFLT = 1.0f;        // 驯服血量阈值（占最大血量比）
    public static final float WIRE_RANGE_DFLT = 0.0f;          // 0 → 导线不可连

    public float erosionRate = EROSION_RATE_DFLT;
    public float ascensionThreshold = ASCENSION_THRESHOLD_DFLT;
    public float haulRate = HAUL_RATE_DFLT;
    public float hungerRate = HUNGER_RATE_DFLT;
    public int   orbItemCost = ORB_ITEM_COST_DFLT;
    public float hpThreshold = HP_THRESHOLD_DFLT;
    public float wireRange = WIRE_RANGE_DFLT;

    // ---- 材料物理（2026-09-18 密度驱动）----

    /**
     * 密度驱动的出厂值。
     *
     * <p><b>2026-09-18 批 B：true（已打开）。</b>打开前先跑过 A/B 门禁（{@code core.sim.DensityFlowProbe}，
     * 1200 tick / 96³ / 手铺"水池+沙堆"场景）：
     * 第 1 tick 即有 107 格变化、终局 6.16% 格不同、成本 **−0.054 ms/tick**（红线 ≤ +0.5）。
     *
     * <p>⚠️ <b>为什么打开它不必重锁既有仿真基线</b>：{@code Simulation} 构造函数<b>不加载内容</b> ——
     * 门禁里裸 {@code new} 的世界 {@code World.materials} 是**空书**（{@code sinksInto} 恒 false），
     * 于是"打开开关"对它们逐字节无影响。只有**注入了材料表**的世界（真实游戏、探针）才会变。
     * 该论证已固化为门禁断言 {@code GameplayTest.MATERIAL-BARE-SAFE}。
     *
     * <p>旧值 {@code false} 的语义保留在注释里：关闭时 {@link core.systems.SandFallSystem} 只在下方是
     * <b>空气</b>时下落 —— 沙停在水面之上（历史行为，也是 {@code QuicksandSystem} 曾经存在的理由）。
     */
    public static final boolean DENSITY_FLOW_DFLT = true;

    /**
     * 是否启用「密度驱动下沉 / 浮起」。
     *
     * <p><b>{@code false}</b>：{@link core.systems.SandFallSystem} 只在下方是<b>空气</b>时下落
     * —— 沙停在水面之上（本批次之前的历史行为）。
     *
     * <p><b>{@code true}（现行出厂）</b>：下落判据改为「下方材料<b>更轻且可位移</b>（液体 / 粉末 / 气体）」——
     * 沙（density 20）会沉入水（10）底，水被顶上来。这就是 Noita 的核心技巧：
     * <b>比一个标量 density 判换位，零专门系统</b>（见 {@code docs/NOITA_STUDY.md}、{@code docs/NOITA_LESSONS.md} §5②）。
     *
     * <p>本字段<b>不受</b> {@link #reset()} 影响：它由世界设定（而非玩法预设）决定，
     * 切换预设不该悄悄改变世界的物理法则。
     */
    public boolean densityFlow = DENSITY_FLOW_DFLT;

    // ---- 材料反应表（2026-09-18 批 C）----

    /**
     * 反应表的出厂值：<b>打开</b>（2026-09-18 批 C）。
     *
     * <p>打开的依据（{@code ReactionFlowProbe}，1200 tick / 96³ 同种子 A/B）：
     * <ul>
     *   <li>开关<b>接上了</b>：第 1 tick 就产生差异；终局 19,923 格 = 3.38% 不同；</li>
     *   <li>变化<b>是想要的</b>：开组火更少（118 → 88，被水浇灭）、冰雪更少（−11，融化）；</li>
     *   <li><b>水量守恒</b>：296 → 308（+4%）—— 灭火不吃水；</li>
     *   <li><b>成本 +0.117 ms/tick</b>（红线 ≤ +0.5）。</li>
     * </ul>
     *
     * <p>因为改变了世界演化 → <b>需要重锁 DETERMINISM / ZERO-DRIFT 基线</b>（与批 B 同一手续）。
     */
    public static final boolean REACTION_TABLE_DFLT = true;

    /**
     * 是否启用「材料反应表」求解器（{@code core.systems.ReactionSystem}）。
     *
     * <p><b>{@code false}（出厂）</b>：求解器首行即返回 —— 与历史版本逐字节一致。
     * 这样"数据表 + 求解器 + 门禁"可以先落地并接受检验，而<b>不惊动任何既有基线</b>。
     *
     * <p><b>{@code true}</b>：按 {@code assets/content/reactions/*.json} 判定 ——
     * 「A 挨着 B → 变 C（按概率）」。初始三条规则（灭火 / 融冰融雪 / 引燃干枯植物）
     * 都是此前<b>完全没有实现</b>的物理（灭火靠概率、雪与冰永不融化）。
     *
     * <p>与 {@link #densityFlow} 同一条纪律：本字段<b>不受</b> {@link #reset()} 影响 ——
     * 它由世界设定决定，切换玩法预设不该悄悄改掉世界的化学法则。
     */
    public boolean reactionTable = REACTION_TABLE_DFLT;

    /** 复位到出厂值（{@code Simulation.applyPreset} 每次先复位，避免参数跨预设泄漏）。 */
    public void reset() {
        erosionRate = EROSION_RATE_DFLT;
        ascensionThreshold = ASCENSION_THRESHOLD_DFLT;
        haulRate = HAUL_RATE_DFLT;
        hungerRate = HUNGER_RATE_DFLT;
        orbItemCost = ORB_ITEM_COST_DFLT;
        hpThreshold = HP_THRESHOLD_DFLT;
        wireRange = WIRE_RANGE_DFLT;
    }
}
