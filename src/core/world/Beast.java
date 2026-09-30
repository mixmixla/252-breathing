package core.world;

/**
 * 敌兵实体（遭遇闭环 + 敌兵种类/AI 差异 + <b>可读招式</b>）。
 *
 * <p>关键纪律：Beast 是“实体”，绝不写 mat/mass 网格，也不进 {@link World#hashState()} 确定性指纹，
 * 故无论敌兵如何生成/移动/死亡，都不会改变仿真指纹（四道零漂移门禁不会翻红）。
 * 一切随机只走 {@link World#simStream(String)} 派生的确定性子流。
 *
 * <h3>四种原型（TT 表）</h3>
 * <pre>
 *   0 GRUNT   均衡近战（样本基线）
 *   1 STALKER 高速脆皮，贴脸追击（0.17/tick，是 BRUTE 的 3.8 倍速）
 *   2 BRUTE   高血慢速，重击（110 HP，一下 12 点）
 *   3 SPITTER 远程，保持距离吐息（3~11 格）
 * </pre>
 *
 * <h3>攻击状态机（2026-09-16 · 参考 DS/ER 的“读招—闪避—惩罚”三段式）</h3>
 * <pre>
 *   IDLE ──进入接敌距离──▶ WINDUP(前摇 windupT tick) ──▶ STRIKE(判定 1 tick) ──▶ RECOVER(后摇 recoverT) ──▶ IDLE
 * </pre>
 * 三条设计后果（缺一，战斗就退化成“接触即结算”）：
 * <ol>
 *   <li><b>伤害离散</b>：只在 STRIKE 那一 tick 判定一次，而不是每 tick 掉血 —— 于是“被打中”是一个可读事件；</li>
 *   <li><b>招式有方向</b>：WINDUP 开始时把出手方向冻结成 ({@link #lockX},{@link #lockZ})，前摇/后摇期间
 *       <b>不再转向</b>；STRIKE 只打“锁定方向的半球”。→ 侧移/后撤真的能躲（ER 的“招式有承诺”）；</li>
 *   <li><b>有可惩罚窗口</b>：{@link #punishable()} 为真时（前摇/后摇）被命中 → 打断出招 + 额外伤害（破势）。</li>
 * </ol>
 * 三者合起来才第一次让“走位”“翻滚 i 帧”“完美闪避反击”有存在意义：此前敌兵从不“出招”，只有接触伤害，
 * 翻滚躲的是一团持续伤害而不是一次攻击。
 */
public final class Beast {

    // ---- 原型静态表（索引 = type）----
    /**
     * <b>随机刷怪</b>用的原型数 = 4（GRUNT / STALKER / BRUTE / SPITTER）。
     *
     * <p>守卫 Boss（索引 {@link #TYPE_BOSS}）<b>不参与随机刷怪</b>：它只在世界之心揭晓时由
     * {@code TrialSystem} 显式生成。所以 {@code BeastSystem} 里的
     * {@code (int)(r.nextFloat() * N_TYPES)} <b>必须继续等于 4</b> ——
     * 改成 5 会让所有既有种子的刷怪序列变化（同种子不再复现历史世界）。
     */
    public static final int N_TYPES = 4;
    /** 世界之心守卫（唯一 Boss）的类型索引。 */
    public static final int TYPE_BOSS = 4;
    /** 全部原型数（含 Boss）—— 仅供遍历静态表。 */
    public static final int N_ALL = 5;

    //          type:   0      1       2      3       4 = BOSS
    private static final float[] SPEED   = {0.08f, 0.17f, 0.045f, 0.075f, 0.055f}; // 每 tick 逼近步长
    private static final int[]   HP      = {34, 22, 110, 26, 420};                 // atk=10 → 4/3/11/3 击；守卫 42 击
    private static final int[]   DMG     = {6, 4, 12, 7, 20};                      // 守卫 2 下打死满血玩家 → 药瓶是必需品
    private static final float[] CONTACT = {1.2f, 1.2f, 1.6f, 1.0f, 2.4f};         // 近战接触半径（守卫长臂）
    private static final int[]   WINDUP  = {14, 8, 22, 18, 30};                    // 前摇（读招窗口）：守卫 1.5 s，全项目最长
    private static final int[]   RECOVER = {18, 12, 30, 24, 44};                   // 后摇 + 冷却（可惩罚窗口）：守卫 2.2 s
    private static final String[] NAME   = {"GRUNT", "STALKER", "BRUTE", "SPITTER", "WARDEN"};

    /**
     * 身体盒半宽 / 净空高度（格），按 type 索引。<b>这是全项目唯一的实体尺寸表</b>：
     * {@code BeastSystem.BODY_HW/BODY_H} 是它的别名（{@code = HW[0]}），
     * {@code audit_invariants.py} 的 C3 直接读这张表来验证「渲染盒 ⊆ 碰撞盒」。
     *
     * <p>守卫取 (1.00, 2.90)：必须装得下它的渲染（躯干 ±0.75 / 肩甲 ±0.80 / 双眼 ±0.78 /
     * 冠刺顶 2.85 / 前摇前肢伸出 0.96）。取小了就会出现"模型被方块裁掉一角"
     * （= LD-2026-09-16 那条穿模根因）。
     */
    public static final float[] HW = {0.35f, 0.35f, 0.35f, 0.35f, 1.00f};
    public static final float[] HH = {1.35f, 1.35f, 1.35f, 1.35f, 2.90f};

    /** Boss 二阶段阈值：血量降到该比例以下 → 前摇缩短、伤害提高。 */
    public static final float BOSS_ENRAGE_PCT = 0.35f;
    /** Boss 三阶段阈值：濒死狂暴。 */
    public static final float BOSS_FINAL_PCT = 0.12f;
    public static final int BOSS_ENRAGE_WINDUP_NUM = 3, BOSS_ENRAGE_WINDUP_DEN = 5;
    public static final int BOSS_ENRAGE_DMG_NUM = 3, BOSS_ENRAGE_DMG_DEN = 2;
    public static final int BOSS_FINAL_WINDUP_NUM = 2, BOSS_FINAL_WINDUP_DEN = 5;
    public static final int BOSS_FINAL_DMG_NUM = 2, BOSS_FINAL_DMG_DEN = 1;
    public static final int BOSS_FINAL_SPEED_NUM = 3, BOSS_FINAL_SPEED_DEN = 2;

    /** 身体盒半宽（格），按 type —— 未登记的 type 退回 0 号原型。 */
    public static float hw(int t) { return HW[(t >= 0 && t < N_ALL) ? t : 0]; }
    /** 净空高度（格），按 type。 */
    public static float h(int t) { return HH[(t >= 0 && t < N_ALL) ? t : 0]; }
    /** 该原型是否为守卫 Boss。 */
    public static boolean isBoss(int t) { return t == TYPE_BOSS; }

    // ---- 攻击状态 ----
    public static final int PHASE_IDLE    = 0;
    public static final int PHASE_WINDUP  = 1;
    public static final int PHASE_STRIKE  = 2;
    public static final int PHASE_RECOVER = 3;
    /** STRIKE 的命中角度门限：与锁定方向的点积需 &gt; 此值（≈78° 半角）。 */
    public static final float STRIKE_ARC_DOT = 0.2f;
    /** 进入前摇的额外距离（近战：contact + 此值）。 */
    public static final float ENGAGE_PAD = 0.6f;
    /** 远程原型的有效射程（与 BeastSystem.RANGE 同义，收敛到实体侧避免两处定义）。 */
    public static final float RANGED_MIN = 3.0f, RANGED_MAX = 11.0f;

    public float x, y, z;          // 体素坐标（y 为脚底，贴地）
    public int hp, maxHp;
    public int type;               // 原型索引
    // 行为参数（生成时从原型表写入，渲染/AI 直接读）
    public float speed;
    public int dmg;
    public float contact;
    public int windupT, recoverT;   // 本体的前摇/后摇长度（tick）
    public int atkPhase = PHASE_IDLE;
    public int atkTimer = 0;        // 当前相位剩余 tick
    public float lockX = 0f, lockZ = 1f;   // 出手方向（WINDUP 起手时冻结）
    public int stagger = 0;         // 硬直剩余 tick（被命中 / 被破势）
    public float hitFlash = 0f;     // 受击闪白（渲染计时，不进指纹）

    // ---- P0 运动四件套②：假击退（渲染层位移，绝不参与 BeastSystem.move）----
    // 来由（2026-09-21）：旧实现命中只有 hitFlash（原地闪白），敌人"缩一下继续走路" →
    // 打击感 = 打在棉花上。真击退若写进 BeastSystem.move 会改世界演化轨迹 → **必然进指纹**。
    // 因此这里是**纯渲染层**位移：BeastSystem.move 完全不读它，只有 drawEntities 读取并偏移绘制位置。
    // 视觉上"打得动"已成立，且对 simHash 逐字节零影响。
    /** 击退进度 [0,1]：1 = 刚被击中（偏移最大），0 = 已归位。由 Game 每帧衰减（fxDt，QA 冻结）。 */
    public float kbProg = 0f;
    /** 击退方向（单位向量，世界空间 XZ）：沿"玩家→敌"或"命中法术方向"。 */
    public float kbDirX = 0f, kbDirZ = 0f;
    /** 击退最大位移（格）：普通敌兵 1.2，Boss 0.30（体重）——由 hitBeast 写入。 */
    public float kbDist = 0f;
    /**
     * 移速倍率 —— 状态类效果（{@code APPLY_BUFF} 的 {@code modifiers.speed}）的载体。
     *
     * <p>{@link core.systems.BuffSystem} 每 tick 先把它复位为 1，再按目标身上生效的状态相乘；
     * {@link BeastSystem} 移动时乘它。这样两件事各归其位：BuffSystem 管"身上有什么"，
     * BeastSystem 管"怎么走"，彼此不必互相认识（避免系统间循环依赖）。
     *
     * <p>默认 1 → 对既有种子<b>逐字节零影响</b>（{@code speed * 1.0f == speed} 在 IEEE754 下精确相等）。
     */
    public float slowMul = 1f;

    /**
     * 是否已被玩家收编（捕捉驯养子系统 CaptureSystem 写入）。
     *
     * <p>被驯服后 {@link core.systems.BeastSystem} 不再把它当敌对单位驱动（不追、不攻击玩家），
     * 改由 {@code CaptureSystem} 驱动跟随 + 协战。
     *
     * <p><b>零漂移</b>：默认 false → BeastSystem 的行为与改动前逐字节一致；
     * 且 Beast 属实体层，不进 {@code hashState()}。
     */
    public boolean tamed = false;
    /**
     * P1：内容兽 id（{@code beasts/*.json} 的文件名）。纯原型为 {@code null}。
     * <p>用途：① 任务目标链按它计数（{@code QuestDef.on.beast}）② HUD/日志显示真名。
     * 它是"身份"不是"数值"—— 数值在生成时从 {@link core.content.BeastDef} 拷进现有字段，
     * 所以 AI 逻辑不需要认识内容层。
     */
    public String defId = null;
    /**
     * 警戒距离（格）：超过它就不进入前摇。纯原型 = {@link #AGGRO_NONE}（无约束，与旧行为逐字节相同）；
     * 内容兽由 {@code behavior.aggroRange} 覆盖 —— 这是"加了内容却完全没影响玩法"的那种死字段的反面。
     */
    public float aggroRange = AGGRO_NONE;
    /** "无警戒限制"哨兵：比世界对角线还大。 */
    public static final float AGGRO_NONE = 999f;

    public Beast(float x, float y, float z, int type) {
        this.x = x; this.y = y; this.z = z;
        this.type = type;
        this.maxHp = HP[type]; this.hp = HP[type];
        this.speed = SPEED[type];
        this.dmg = DMG[type];
        this.contact = CONTACT[type];
        this.windupT = WINDUP[type];
        this.recoverT = RECOVER[type];
    }

    /** 按原型确定性生成一只敌兵（坐标由调用方给定）。 */
    public static Beast make(int type, float x, float y, float z) {
        return new Beast(x, y, z, type);
    }

    public static String typeName(int t) {
        return (t >= 0 && t < N_ALL) ? NAME[t] : "BEAST";
    }

    /** 该原型是否远程（保持距离吐息）。 */
    public static boolean isRanged(int t) { return t == 3; }

    /** 前摇进度 0..1（未在起手时为 0）—— 渲染层拿它做“读招”提示（体色转暖 + 抬爪）。 */
    public float warn() {
        if (atkPhase != PHASE_WINDUP || windupT <= 0) return 0f;
        float w = 1f - (float) atkTimer / (float) windupT;
        return w < 0f ? 0f : (w > 1f ? 1f : w);
    }

    /** 是否处于可被“破势”的窗口（前摇 / 后摇）。 */
    public boolean punishable() { return atkPhase == PHASE_WINDUP || atkPhase == PHASE_RECOVER; }

    /** 是否正在出招（供渲染层做姿态/音效）。 */
    public boolean attacking() { return atkPhase != PHASE_IDLE; }

    /** 本体是否为守卫 Boss。 */
    public boolean isBoss() { return type == TYPE_BOSS; }

    /**
     * Boss 二阶段（激怒）：血量 ≤ {@link #BOSS_ENRAGE_PCT} → 前摇缩短、伤害提高。
     * <p>为什么要二阶段：守卫血量是全项目最高（420），若全程一个节奏，后半段就是"磨"。
     * 激怒把「读招窗口 1.5 s」压到 0.9 s、伤害从 20 抬到 30 —— 前 65% 是教学，后 35% 是考试。
     */
    /** Boss 战斗阶段：1=教学、2=激怒、3=濒死狂暴；普通敌兵恒为 1。 */
    public int phase() {
        if (!isBoss() || maxHp <= 0) return 1;
        float p = (float) hp / (float) maxHp;
        return p <= BOSS_FINAL_PCT ? 3 : (p <= BOSS_ENRAGE_PCT ? 2 : 1);
    }
    public boolean enraged() { return phase() >= 2; }
    public String phaseName() { return phase() == 3 ? "FINAL" : (phase() == 2 ? "ENRAGED" : "GUARDIAN"); }
    public int effectiveWindup() {
        int ph = phase();
        if (ph == 1) return windupT;
        if (ph == 2) return Math.max(1, windupT * BOSS_ENRAGE_WINDUP_NUM / BOSS_ENRAGE_WINDUP_DEN);
        return Math.max(1, windupT * BOSS_FINAL_WINDUP_NUM / BOSS_FINAL_WINDUP_DEN);
    }
    public int effectiveDmg() {
        int ph = phase();
        if (ph == 1) return dmg;
        if (ph == 2) return dmg * BOSS_ENRAGE_DMG_NUM / BOSS_ENRAGE_DMG_DEN;
        return dmg * BOSS_FINAL_DMG_NUM / BOSS_FINAL_DMG_DEN;
    }
    public float effectiveSpeed() {
        return phase() == 3 ? speed * BOSS_FINAL_SPEED_NUM / (float) BOSS_FINAL_SPEED_DEN : speed;
    }
}
