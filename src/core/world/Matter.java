package core.world;

/**
 * 物质·能量域状态容器（批次 5 · 抽象系统）：把 Python 4 个“抽象系统”的
 * <b>可落地世界状态字段</b>集中为一份可观测的开放标量状态。
 *
 * <p>忠实移植对应关系（Python → 本类字段；全部是“读数”，不是网格/账本搬运）：
 * <ul>
 *   <li>{@code systems/phases.py}  → {@link #phase}/{@link #transitions}/{@link #alloy}
 *       （物相：固/液/气 + 相变次数 + 合金成熟度）</li>
 *   <li>{@code systems/entropy.py} → {@link #supply}/{@link #work}/{@link #entropy}/{@link #exergy}
 *       （能量梯级：供给/做功/熵增/可用能）</li>
 *   <li>{@code systems/fusion.py}  → {@link #reactors}/{@link #fusionOut}/{@link #radiation}
 *       （聚变点火台数 + 产能 + 中子辐射标量）</li>
 *   <li>{@code systems/deepsea.py} → {@link #subs}/{@link #pressure}/{@link #mineral}/{@link #pollution}/{@link #colonies}
 *       （深海：潜航器/静水压强/海底矿脉/海洋污染/海底殖民）</li>
 * </ul>
 *
 * <p><b>明确不移植</b>（非“世界状态字段”，已在 {@code docs/OPEN_ITEMS.md} 逐条记账）：
 * <ul>
 *   <li>{@code systems/link.py}：鸭子类型“跨系统耦合胶水层”（{@code couple(world,target,**fields)} 靠
 *       {@code getattr} + 反射式字段名 → 加法写入）。Java 各系统以静态引用直接调用、字段类型编译期校验，
 *       再引入这样一层通用写入只会丢掉类型安全、把编译期错误推迟到运行期 —— 属 Python 特有的实现细节，
 *       <b>不产生任何世界状态</b>，故不移植。</li>
 *   <li>{@code systems/portal.py}：CLI/Web 管理端的“功能启停 + LLM 开关”配置中枢
 *       （{@code GROUPS/resolve/apply/read_profile/write_profile}）。纯运维工具，同样<b>不产生世界状态</b>，
 *       与体素动作游戏无关，故不移植。</li>
 * </ul>
 *
 * <p><b>零漂移纪律</b>（与 npcs/social/chronicle/civ/individual/polity/calamity/trials 同）：
 * 本类是开放标量，<b>绝不进</b> {@link World#hashState()}，也绝不写 mat/mass/prosperity/skills/villageMemory。
 * 空间执行层（辐射场扩散 / 作物变异 / NPC 受辐射掉血 / 洋面掩码 / 潜航器实体）由
 * {@link core.systems.MatterSystem#MATERIAL_WORKS} 开关守护，默认关 → 四道零漂移门禁指纹逐字节不变。
 *
 * <p><b>为什么这些字段值不值得进指纹</b>：它们都可由 (tick, 方块计数, {@code civ} 标量) 纯函数重现，
 * 属“派生观测”——与 DayCycle 同理。进指纹只会平白扩大指纹面、且一旦调参就要重定基线。
 */
public final class Matter {

    // ================================================================ 物相（phases）
    /** 物相：0=固相（{@link #SOLID}）／1=液相（{@link #LIQUID}）／2=气相（{@link #GAS}）。 */
    public static final int SOLID = 0, LIQUID = 1, GAS = 2;
    public int phase = SOLID;
    /** 相变次数（每次跨过熔点/沸点 +1；观测“火候/冶炼/冰川”的活跃度）。 */
    public int transitions = 0;
    /** 合金成熟度 [0,1]：由生铁供给与“温和冶炼温度窗”共同决定（越高代表材料工艺越好）。 */
    public float alloy = 0f;

    // ================================================================ 能量梯级（entropy）
    public float supply = 0f;    // 可用能供给（电网功率 + 日照）
    public float work = 0f;      // 做功（人口 + 工业 + 抖动）
    /** 熵：单向累积（第二定律），只增不减。 */
    public float entropy = 0f;
    /** 可用能（exergy）：供给按效率折旧、并被熵侵蚀 → 长期越用越废。 */
    public float exergy = 0f;

    // ================================================================ 聚变（fusion）
    public int reactors = 0;       // 已点火的聚变堆数
    public float fusionOut = 0f;   // 聚变产能（注入电网的开放标量）
    /** 中子辐射标量（空间辐射场的标量等价；网格版扩散由 MATERIAL_WORKS 守护）。 */
    public float radiation = 0f;

    // ================================================================ 深海（deepsea）
    public int subs = 0;            // 潜航器数
    public float pressure = 0f;     // 静水压强（∝ 深海覆盖格数）
    public float mineral = 0f;      // 海底矿脉（开放资源，不进固体账本）
    public float pollution = 0f;    // 海洋污染（工业排放 − 藻类自净）
    public float colonies = 0f;     // 海底殖民地数（增长量，浮点累积）

    /** 物相名（HUD/快照用；GL 字体仅 ASCII）。 */
    public static String phaseName(int p) {
        return p == GAS ? "GAS" : p == LIQUID ? "LIQ" : "SOL";
    }

    /** HUD 第一行：物质·能量域（ASCII 摘要）。 */
    public String asciiSummary() {
        return "MATTER  PH " + phaseName(phase) + "  TR " + transitions
                + "  ALLOY " + f2(alloy) + "  EXE " + f2(exergy) + "  ENT " + f1(entropy);
    }

    /** HUD 第二行：聚变 + 深海（ASCII 摘要）。 */
    public String deepSummary() {
        return "DEEPSEA  SUB " + subs + "  PRES " + f1(pressure) + "  MIN " + f0(mineral)
                + "  POLL " + f1(pollution) + "  COL " + f1(colonies)
                + "  FUS " + reactors + "  RAD " + f1(radiation);
    }

    /** 可读快照（供门禁/日志；纯派生，不修改状态）。 */
    public String snapshot() {
        return "phase=" + phaseName(phase) + ",trans=" + transitions + ",alloy=" + f3(alloy)
                + " | supply=" + f3(supply) + ",work=" + f3(work)
                + ",entropy=" + f3(entropy) + ",exergy=" + f3(exergy)
                + " | reactors=" + reactors + ",fusionOut=" + f3(fusionOut) + ",rad=" + f3(radiation)
                + " | subs=" + subs + ",pressure=" + f3(pressure) + ",mineral=" + f3(mineral)
                + ",pollution=" + f3(pollution) + ",colonies=" + f3(colonies);
    }

    private static String f0(float v) { return String.format(java.util.Locale.US, "%.0f", v); }
    private static String f1(float v) { return String.format(java.util.Locale.US, "%.1f", v); }
    private static String f2(float v) { return String.format(java.util.Locale.US, "%.2f", v); }
    private static String f3(float v) { return String.format(java.util.Locale.US, "%.3f", v); }
}
