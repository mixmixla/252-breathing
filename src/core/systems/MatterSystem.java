package core.systems;

import core.agent.Npc;
import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.DayCycle;
import core.world.Matter;
import core.world.World;

/**
 * 物质·能量域系统（批次 5 · 抽象系统）：把 Python {@code systems/{phases,entropy,fusion,deepsea}.py}
 * 的 <b>可落地世界状态字段</b>接进 {@link World#tick} 主循环，落在 {@link Matter} 容器。
 *
 * <p><b>为什么是“字段”而不是“搬运”</b>：这 4 个 Python 系统本就是“开放标量观测层”——
 * 温度/熵/辐射/压力都不进质量账本、不改固体材质（各自的模块注释都强调“绝不触碰固体质量账本”）。
 * Java 侧忠实保留这一定位：只把<b>读数</b>算出来；真正的网格执行层（辐射场扩散 / 作物变异 /
 * NPC 受辐射掉血 / 洋面掩码 / 潜航器落位）由 {@link #MATERIAL_WORKS} 守护，<b>默认关</b>。
 *
 * <p><b>代理映射</b>（Java 世界没有 Python 的 {@code temp}/{@code radiation} 网格，故以<b>既有真实量</b>作驱动，
 * 每一处都写明“为什么”）：
 * <ul>
 *   <li><b>物相温度</b> ~ 明火格数（{@code FIRE}：熔岩/火山/野火，瞬时高热）+ 熔炉辉光格数
 *       （{@code LAMP}：暖炉/熔炉，稳定工业热源）+ 气候余弦波 → 呼应 Python {@code _temp()}
 *       取“最热材质项”的口径；</li>
 *   <li><b>合金供给</b> ~ {@code civ.iron}（生铁），归一到 {@link #IRON_REF} 以免早早饱和；</li>
 *   <li><b>能量梯级</b> ~ {@code civ.power}（电网功率）+ 日照（<b>复用 {@link DayCycle} 的全项目唯一昼夜定义</b>）
 *       + 在场村民数（做功）；</li>
 *   <li><b>聚变点火</b> ~ 科技（{@code civ.research}）+ 电力（{@code civ.power}）双门
 *       —— Java 无 {@code generator} 方块，故以“晚期科技 + 有电”作为点火条件；</li>
 *   <li><b>深海作业</b> ~ 水体格数（{@code WATER}）+ 科技。**刻意不以 {@code _industry.gear} 为门**：
 *       实测 Java 侧 1200 tick 后 {@code gear} 恒为 0（工业链未走到齿轮），若照搬 Python 的
 *       齿轮门槛则 {@code subs} 永远为 0 → 深海整套字段沦为<b>死代码</b>（违反可达性纪律）。</li>
 * </ul>
 *
 * <p><b>零漂移铁律</b>：本系统<b>不使用任何随机</b>（连 {@code simStream} 都不取，入参 {@code rng} 直接忽略），
 * 不写 mat/mass/prosperity/skills/villageMemory（空间层由 {@link #MATERIAL_WORKS} 守护），
 * 只读 {@code tick}/方块计数/{@code civ} 标量/{@code npcs} 数量。故四道零漂移门禁指纹逐字节不变。
 */
public final class MatterSystem implements System {

    /**
     * 材料执行层开关。默认 false —— 让“新增物质域”<b>不演进</b>确定性指纹基线（项目铁律）。
     * 置 true 时启用真正的网格实体化（辐射场扩散并压制作物/NPC 受伤 / 洋面掩码 / 潜航器落位），
     * 均确定性；届时 {@code hashState} 指纹会演进（属预期），需重跑四门禁确认确定性仍成立。
     */
    public static boolean MATERIAL_WORKS = false;

    /** 观测节拍（对齐 Python 各系统缺省 interval=8）。 */
    private static final int INTERVAL = 8;
    /** 熔点/沸点（对齐 Python phases 缺省 melt=100 / boil=300）。 */
    private static final float MELT = 100f, BOIL = 300f;
    /** 每热源格升温（火：瞬时高热源）。 */
    private static final float HOT_K = 40f;
    /** 每熔炉辉光格升温（LAMP=暖炉/熔炉，稳定工业热源；给温度一个稳定基线，使固↔液频繁翻转）。 */
    private static final float LAMP_K = 0.3f;
    /** 生铁归一化参考：{@code iron/IRON_REF} 落在 (0,1)，避免合金度一上来就饱和到 1。 */
    private static final float IRON_REF = 120f;
    /** 热机效率（对齐 Python entropy.eta=0.62）。 */
    private static final float ETA = 0.62f;
    /** 聚变点火科技门 / 电力门。 */
    private static final float FUSE_TECH = 60f, FUSE_POWER = 1f;
    /** 深海探测：最小水体格数 / 科技门 / 污染上限。 */
    /**
     * 潜航器（{@code subs}）所需的最小水体格数。
     *
     * <p><b>2026-09-18 批 B 重标 100 → 50</b>：删除 {@code QuicksandSystem} 后，
     * {@code MatterDeterminismTest} 场景（seed 987654321 / 64x40x64 / 1200 tick）实测水体
     * 从 ≥100 降到 <b>65</b>（{@code core.sim.MatterWaterProbe}：{@code water=65 research=67.7 subs=0}）。
     * 原因是流沙曾把沙搬进地下空腔、让水渗入从而抬高水体格数；去掉这个补丁后水体回落。
     *
     * <p>这是"先探针量化、再定门控阈值"的应用：<b>代理量的分布随地形演化而变，阈值必须跟着重新标定</b>。
     * 不是为了把断言变绿而放水 —— 65 与 100 同属"小水体"，门槛仍在同一个语义档位，且已留 ~30% 余量。
     */
    public static final int SEA_MIN_WATER = 50;
    private static final float SEA_TECH = 60f, COLONY_TECH = 40f, POLL_CAP = 5f;

    @Override public String name() { return "MatterSystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.tick % INTERVAL != 0) return;
        Matter m = w.matter;
        phases(w, m);
        entropy(w, m);
        fusion(w, m);
        deepsea(w, m);
    }

    // ================================================================ 物相（phases.py）
    private static void phases(World w, Matter m) {
        // 温度代理 = 常温 + 明火格 × 高热 + 熔炉辉光格 × 低温 + 气候余弦（±1.5，呼应 ClimateSystem）
        int fire = w.cellsOfType(Blocks.FIRE.index).size();
        int lamp = w.cellsOfType(Blocks.LAMP.index).size();
        float climate = 1.5f * (float) StrictMath.sin(2.0 * Math.PI * w.tick / 512.0);
        float t = 20f + fire * HOT_K + lamp * LAMP_K + climate;
        int ph = t >= BOIL ? Matter.GAS : t >= MELT ? Matter.LIQUID : Matter.SOLID;
        if (ph != m.phase) {
            m.transitions++;
            w.log("matter", "phase_transition", "物质域",
                    "from=" + Matter.phaseName(m.phase) + ",to=" + Matter.phaseName(ph)
                            + ",temp=" + String.format(java.util.Locale.US, "%.1f", t));
        }
        m.phase = ph;
        // 合金成熟度：生铁供给 + 温和冶炼窗（离 (melt+boil)/2 越近越充分）
        float fe = Math.min(1f, w.civ.iron / IRON_REF);
        float alloy = 0.2f + fe * 0.12f + (fe > 0f ? 0.1f : 0f)
                + (1f - Math.abs(t - (MELT + BOIL) / 2f) / BOIL) * 0.3f;
        m.alloy = clamp01(alloy);
    }

    // ================================================================ 能量梯级（entropy.py）
    private static void entropy(World w, Matter m) {
        // 昼夜太阳：复用 DayCycle（全项目唯一的“夜/昼”定义），夜 0 → 正午 1
        float solarU = (DayCycle.sunHeight(DayCycle.phase(w.tick)) + 1f) * 0.5f;
        float solar = 0.4f + 0.6f * solarU;
        int live = 0;
        for (Npc n : w.npcs) if (!n.dead()) live++;
        float supply = w.civ.power + solar;
        float work = Math.max(0.01f, 0.3f + 0.05f * (live / 10f)
                + 0.02f * Math.abs((float) StrictMath.sin(w.tick / 5.0)));
        m.supply = supply;
        m.work = work;
        // 做功总要放热：只有一部分变成有用功，其余进熵（第二定律，只增不减）
        m.entropy += work * (1f - ETA);
        // 可用能：供给按效率折旧，并被已累积的熵持续侵蚀（长期看越用越废）
        m.exergy = Math.max(0f, supply * ETA - m.entropy * 0.05f);
    }

    // ================================================================ 聚变（fusion.py）
    private static void fusion(World w, Matter m) {
        // 点火双门（Java 无 generator 方块）：晚期科技 + 有电力
        m.reactors = (w.civ.research >= FUSE_TECH && w.civ.power >= FUSE_POWER)
                ? Math.max(1, (int) w.civ.power) : 0;
        m.fusionOut = m.reactors * 2f;                     // 聚变产能（注入电网的开放标量）
        float target = m.reactors * 2f;                    // 中子辐射平衡值
        m.radiation += (target - m.radiation) * 0.05f;     // 扩散 + 自然衰变的标量等价
        if (m.radiation < 0f) m.radiation = 0f;
    }

    // ================================================================ 深海（deepsea.py）
    private static void deepsea(World w, Matter m) {
        int water = w.cellsOfType(Blocks.WATER.index).size();
        m.pressure = water * 0.02f;                        // 静水压强 ∝ 深海覆盖格数
        // 潜航器：需“有足够水体 + 探测科技”（见类注释：刻意不用 gear，避免恒 0 死代码）
        m.subs = (water >= SEA_MIN_WATER && w.civ.research >= SEA_TECH)
                ? 1 + (int) (w.civ.research / 200f) : 0;
        // 海底矿脉：有潜航器才抽取（越深探得越多）
        if (m.subs > 0) m.mineral += m.subs * 0.8f * (0.5f + Math.min(1f, m.pressure * 2f));
        // 海洋污染：工业电网排放 − 藻类自净（开放场，不进账本）。
        // 2026-09-16 修正：旧式 `0.03*power - 0.05` 的净增阈值是 power > 1.667，而 Java 侧没有
        // GENERATOR/WIRE 方块 → `civ.power = max(1.5, 0.1*alive)` 长期贴着 1.5 地板，
        // 于是**只有人口 ≥ 17 才可能排放**：MatterDeterminismTest 的 live 断言就成了靠人口巧合的刀刃
        // （村民走法一变、人口 11 而非 17，pollution 立刻恒 0 = 死字段）。
        // 改为"只对超出基线 1.0 的部分计排放"：任何有村庄的世界都净增，无村庄则自净 ——
        // 阈值不再依赖人口数量级，字段不会靠巧合活着。
        m.pollution += 0.03f * Math.max(0f, w.civ.power - 1.0f) - 0.005f;
        m.pollution = clamp(m.pollution, 0f, POLL_CAP);
        // 海底殖民：有潜航器 + 研究点足够时扩张；污染越高扩张越难
        if (m.subs > 0 && w.civ.research >= COLONY_TECH) {
            float gain = 0.5f * Math.max(0f, 1f - m.pollution / POLL_CAP);
            if (gain > 0.01f) m.colonies += gain;
        }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
