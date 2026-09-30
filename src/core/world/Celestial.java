package core.world;

/**
 * 天体模型（日 / 月 / 食）—— <b>纯确定性派生：零状态 / 零 RNG / 零世界写入</b>。
 *
 * <p>与 {@link DayCycle} 的分工：
 * <ul>
 *   <li>{@link DayCycle}：昼夜<b>调色板与光照基准</b>（天顶/地平线色、环境光、光色、雾、"夜"定义）。
 *       它只有 {@code phase} 一个自变量。</li>
 *   <li>{@code Celestial}：<b>天体在天球上的真实位置</b>（需要 {@code tick}，因为月球轨道以"日"为单位演化）
 *       与<b>食</b>的判定。天球坐标 → 世界方向是一条纯公式，可 headless 断言。</li>
 * </ul>
 *
 * <p><b>为什么放在 core 而不是渲染层</b>：与 {@code DayCycle} 同理 —— 它要被渲染层读（画日月、定光影方向），
 * 而且"什么算月食"必须全项目只有一个定义。它不写 mat/mass/prosperity、不吃 RNG，
 * 所以对四道零漂移门禁<b>逐字节零影响</b>（{@code DayNightTest} 的 CELESTIAL 段实证）。
 *
 * <h3>天球模型</h3>
 * 观测者纬度 {@link #OBSERVER_LATITUDE_DEG}（默认 30°）；{@code +Y} 为天顶、{@code +X} 为北、{@code +Z} 为东：
 * <pre>
 *   H(时角) = 2π·phase − π         // phase 0=午夜（时角 −π，天底）→ 0.5=正午（时角 0，天顶）
 *   dir = (cos(phi)sin(delta) - sin(phi)cos(delta)cosH,  sin(phi)sin(delta) + cos(phi)cos(delta)cosH,  cos(delta)sinH)
 * </pre>
 * 太阳取 delta=0，于是 {@code dir.y = cos(phi)·cosH = cos(phi)·(−cos 2π·phase) = cos(phi)·}{@link DayCycle#sunHeight(float)}
 * —— <b>与既有昼夜模型同号同定义</b>（{@code >0} 昼、{@code <0} 夜），只是被观测者纬度压低：
 * 取 {@link #OBSERVER_LATITUDE_DEG} = 30° 时太阳最高 60°，全天在天上有一条看得见的弧，
 * 正午仍有方向感与投影（取 0° 会过天顶 —— 见该常量注释）。
 *
 * <p>月球在太阳的黄经上落后一个<b>距角</b> {@code E}，且带<b>黄纬</b> β（黄白交角）：
 * <pre>
 *   E(t) = 2π·t/SYNODIC + π        // π = 满月（开局满月）
 *   H_m  = H − E                   // 月球时角：每恒星日推进 (1/日 − 1/朔望月)，故月中天每天晚 ~49 分钟（真实）
 *   β(t) = asin( sin(i)·sin(2π·t/LATITUDE + U0) )
 *   dir_m = (cosφ·sinβ − sinφ·cosβ·cos H_m,  sinφ·sinβ + cosφ·cosβ·cos H_m,  cosβ·sin H_m)
 * </pre>
 * 于是月球有<b>自己的轨迹</b>（不是"太阳的镜像"）：每天晚升 ~49 分钟、相位按真实朔望月演化。
 *
 * <h3>食</h3>
 * 判定纯几何：把月球方向与"反日点"（月食）/ "日面"（日食）的<b>角距离</b>与地球本影/半影视半径比较。
 * 因为黄纬 β 的存在，只有"朔望 + 月球靠近交点"才可能成食 —— 这正是真实的机理。
 *
 * <p><b>为可观测性做的两处刻意压缩</b>（都在常量里注明，想更真实改回去即可）：
 * <ol>
 *   <li>{@link Rate}（黄白交角）：真实 {@link #INCLINATION_REAL_DEG} 5.145°。真实值下"月球躲开地影"
 *       太有效 → 平均 ~250 游戏日才一次月食，几乎看不到；默认档 {@link Rate#NORMAL} 取 2.0°，
 *       约 30% 的满月成食（平均 ~98 游戏日一次）。三档都保留"只有朔望附近才可能成食"的机理。</li>
 *   <li>{@link #LATITUDE_DAYS}：真实由"交点月 27.2 日 + 食年 346.6 日"共同决定（等价节点周期 ~6798 日），
 *       此处直接给黄纬一个 3.7 朔望月的振荡周期。</li>
 * </ol>
 * 朔望月、恒星日月升节奏、相位、本影/半影半径、角距离判据<b>全部保持真实值</b>。
 */
public final class Celestial {

    private Celestial() {}

    // ================= 周期常量（单位：游戏日） =================

    /** 朔望月（新月→新月）：<b>真实值 29.530588 日</b>。相位与月升节奏都按真实节奏走。 */
    public static final float SYNODIC_DAYS = 29.530588f;

    /** 真实黄白交角（度）—— 仅作文档基准：真实值下平均 ~250 游戏日才有一次月食，看不到。 */
    public static final float INCLINATION_REAL_DEG = 5.145f;

    /**
     * 食的频率档位。<b>数值 = 黄白交角（度）</b>：交角越大，月球越容易从地影上方/下方溜过 → 成食越少。
     *
     * <p>注意它仍 &gt; 0，所以"只有朔望且靠近交点才成食"的机理<b>没有被抹掉</b> —— 是几何，不是开关；
     * 每个满月的食分仍随黄纬从"深全食"变到"浅偏食"，也会出现"只进半影、月亮只是发暗"的浅食。
     *
     * <p>实测（4000 游戏日，逐满月判定）：
     * <pre>
     *   RARE   3.5° → 20% 的满月成食（本影月食平均 154 游戏日一次），日食平均 364 日一次
     *   NORMAL 2.0° → 30%（平均  98 日一次），日食平均 154 日一次   ← 默认
     *   OFTEN  1.2° → 56%（平均  51 日一次），日食平均 100 日一次
     * </pre>
     * 真实世界约 12% 的满月会成食（一年 2 次上下），故默认档已偏"游戏化"，
     * 但<b>不再</b>是"每个满月都食"（旧值 0.70° &lt; 本影+月视半径 0.959°，必然成食）。
     */
    public enum Rate {
        RARE(3.5f), NORMAL(2.0f), OFTEN(1.2f);

        /** 该档的黄白交角（度）。 */
        public final float incDeg;

        Rate(float incDeg) { this.incDeg = incDeg; }
    }

    /**
     * 当前档位（表现参数），默认 {@link Rate#NORMAL}。
     *
     * <p>它只影响"月亮在天上偏多少 / 会不会进地影"这类<b>渲染与展示</b>派生量，
     * 不写 mat/mass/任何世界状态、不吃 RNG → 对四道零漂移门禁与联机快照<b>逐字节无影响</b>。
     * 设置页 ECLIPSE 行直接切换它。
     */
    public static Rate rate = Rate.NORMAL;

    /** 切换食的频率档位（null 忽略）。 */
    public static void setRate(Rate r) { if (r != null) rate = r; }

    /** 当前黄白交角（度）。 */
    public static float inclinationDeg() { return rate.incDeg; }

    /** 月球黄纬振荡周期（日）：3.7 朔望月（真实等价节点周期 ~6798 日，见类注释）。 */
    public static final float LATITUDE_DAYS = 3.7f * SYNODIC_DAYS;

    /** 黄纬相位初值（弧度）：0 → 开局正在交点 → <b>第一夜即全食</b>。 */
    public static final float LATITUDE_PHASE0 = 0.0f;

    /** 开局月相：0.5 = 满月（配合"第一夜可见月食"）。 */
    public static final float PHASE0 = 0.5f;

    // ================= 视半径 / 影半径（度，全部真实值） =================

    /** 太阳视半径（度）。 */
    public static final float SUN_RADIUS_DEG = 0.265f;
    /** 月球视半径（度）。 */
    public static final float MOON_RADIUS_DEG = 0.259f;
    /** 地球本影在月球处的视半径（度）。 */
    public static final float UMBRA_RADIUS_DEG = 0.70f;
    /** 地球半影在月球处的视半径（度）。 */
    public static final float PENUMBRA_RADIUS_DEG = 1.25f;

    /** 光源方向的 y 分量下限：沿用既有"光源永不贴地"设计意图（防整面压黑）。 */
    public static final float MIN_LIGHT_Y = 0.25f;

    /**
     * 观测者纬度（度）。<b>这是"太阳能在天上看得到"的关键</b>：取 0°（赤道）时太阳正午会从
     * <b>天顶</b>经过 —— 要抬头才看得见、正午垂直面完全没有直射光（画面发平）、正午也没有影子。
     * 取 30° 后太阳最高约 60°，全天都在天上划一条看得见的弧，正午仍有方向感与投影。
     */
    public static final float OBSERVER_LATITUDE_DEG = 30.0f;

    /** 食的种类。 */
    public enum Eclipse {
        NONE, PENUMBRAL_LUNAR, PARTIAL_LUNAR, TOTAL_LUNAR, PARTIAL_SOLAR, TOTAL_SOLAR
    }

    // ================= 时间基 =================

    /** 朔望月的 tick 数。 */
    public static float synodicTicks() { return SYNODIC_DAYS * DayCycle.DAY_LEN; }

    /** 黄纬振荡周期的 tick 数。 */
    public static float latitudeTicks() { return LATITUDE_DAYS * DayCycle.DAY_LEN; }

    /** 取小数部分，结果恒在 [0,1)，对负数安全。 */
    public static float frac(double v) {
        double f = v - StrictMath.floor(v);
        return (float) f;
    }

    /**
     * 月相（月龄）：0 = 新月，0.25 = 上弦，0.5 = 满月，0.75 = 下弦。
     * <p>以真实朔望月推进，开局为 {@link #PHASE0}（满月）。
     */
    public static float moonPhase(long tick) {
        double st = synodicTicks();
        if (st <= 0.0) return PHASE0;
        return frac((double) PHASE0 + (double) tick / st);
    }

    /** 距角（弧度）：0 = 新月（日地月一线、月在中间），π = 满月。 */
    public static float elongation(long tick) {
        return (float) (moonPhase(tick) * 2.0 * StrictMath.PI);
    }

    /** 被照亮比例：0（新月）→ 1（满月）。 */
    public static float moonLit(long tick) {
        return (float) ((1.0 - StrictMath.cos(elongation(tick))) * 0.5);
    }

    /** 月球黄纬（度）：偏离黄道的角度，决定能否成食。 */
    public static float latitudeDeg(long tick) {
        double lt = latitudeTicks();
        if (lt <= 0.0) return 0f;
        double u = 2.0 * StrictMath.PI * ((double) tick / lt) + LATITUDE_PHASE0;
        return (float) StrictMath.toDegrees(
                StrictMath.asin(StrictMath.sin(StrictMath.toRadians(inclinationDeg())) * StrictMath.sin(u)));
    }

    // ================= 天体方向（单位向量，世界坐标） =================

    /** 太阳时角：phase 0（午夜）= −π（天底），0.5（正午）= 0（天顶）。 */
    private static double sunHourAngle(float phase) {
        return phase * 2.0 * StrictMath.PI - StrictMath.PI;
    }

    /**
     * 太阳方向（真实日弧）：日出在地平线一侧、正午过天顶、日落在对侧、夜里落到地平线以下。
     * <p>{@code out3[1] == }{@link DayCycle#sunHeight(float)} —— 与既有昼夜模型同一个定义。
     */
    public static void sunDir(float phase, float[] out3) {
        double h = sunHourAngle(phase);
        double phi = StrictMath.toRadians(OBSERVER_LATITUDE_DEG);
        double sp = StrictMath.sin(phi), cp = StrictMath.cos(phi);
        double ch = StrictMath.cos(h), sh = StrictMath.sin(h);
        // 太阳赤纬取 0（一年中最"正"那天）。+X 北 / +Y 天顶 / +Z 东。
        // dir.y = cos(phi)*cos H = cos(phi)*sunHeight —— 与昼夜模型同号同定义，
        // 只是被纬度压低：正午在偏南 60 度，而不是 90 度天顶。
        out3[0] = (float) (-sp * ch);
        out3[1] = (float) (cp * ch);
        out3[2] = (float) sh;
    }

    /** 太阳方向（按 tick）。 */
    public static void sunDir(long tick, float[] out3) { sunDir(DayCycle.phase(tick), out3); }

    /**
     * 月球方向：自己的轨迹（每天晚升 ~49 分钟）+ 黄纬偏移。
     * <p>月球时角 {@code H_m = H − E}：每恒星日推进 (1/日 − 1/朔望月)，
     * 所以月中天逐日推迟（真实 24h50m）—— 不是"太阳的镜像"。
     */
    public static void moonDir(long tick, float[] out3) {
        double hm = sunHourAngle(DayCycle.phase(tick)) - elongation(tick);
        double beta = StrictMath.toRadians(latitudeDeg(tick));
        double phi = StrictMath.toRadians(OBSERVER_LATITUDE_DEG);
        double sp = StrictMath.sin(phi), cp = StrictMath.cos(phi);
        double sd = StrictMath.sin(beta), cd = StrictMath.cos(beta);
        double ch = StrictMath.cos(hm), sh = StrictMath.sin(hm);
        // 与太阳同一套天球公式（+X 北 / +Y 天顶 / +Z 东），delta = 黄纬 beta：
        //   高度角 = asin(sin(phi)sin(delta) + cos(phi)cos(delta)cos H) → |H|<90 度在地平线上
        //   黄纬只把月亮从太阳轨道面上抬/压一点 -> 这正是它躲开地影、只在朔望偶尔成食的原因
        out3[0] = (float) (cp * sd - sp * cd * ch);        // 北向
        out3[1] = (float) (sp * sd + cp * cd * ch);        // 天顶（高度角）
        out3[2] = (float) (cd * sh);                       // 东向
    }

    /** 方向是否在地平线以上（单位向量，y &gt; 0）。 */
    public static boolean aboveHorizon(float[] dir) { return dir[1] > 0f; }

    /** 月球此刻是否在地平线以上。 */
    public static boolean moonUp(long tick) {
        float[] d = new float[3];
        moonDir(tick, d);
        return d[1] > 0f;
    }

    /** 两个单位方向的夹角（度）。 */
    public static float separationDeg(float[] a, float[] b) {
        double d = (double) a[0] * b[0] + (double) a[1] * b[1] + (double) a[2] * b[2];
        if (d > 1.0) d = 1.0;
        if (d < -1.0) d = -1.0;
        return (float) StrictMath.toDegrees(StrictMath.acos(d));
    }

    // ================= 食 =================

    /**
     * 月食食分（0 = 无，1 = 月球刚被本影吞没，&gt;1 = 全食越深）。
     * <p>判据：月球 → 反日点（= 地影轴）的角距离与 (本影半径 + 月球视半径) 比较。
     */
    public static float lunarMagnitude(float[] moonDir, float[] sunDir) {
        float[] anti = { -sunDir[0], -sunDir[1], -sunDir[2] };
        float sep = separationDeg(moonDir, anti);
        float m = (UMBRA_RADIUS_DEG + MOON_RADIUS_DEG - sep) / (2f * MOON_RADIUS_DEG);
        return m < 0f ? 0f : (m > 1.2f ? 1.2f : m);
    }

    /** 半影月食食分（用于"月亮只是发暗"的浅食）。 */
    public static float penumbralMagnitude(float[] moonDir, float[] sunDir) {
        float[] anti = { -sunDir[0], -sunDir[1], -sunDir[2] };
        float sep = separationDeg(moonDir, anti);
        float m = (PENUMBRA_RADIUS_DEG + MOON_RADIUS_DEG - sep) / (2f * MOON_RADIUS_DEG);
        return m < 0f ? 0f : (m > 1.2f ? 1.2f : m);
    }

    /** 日食食分（0 = 无，1 = 全食）。判据：月球 → 日面中心角距离。 */
    public static float solarMagnitude(float[] moonDir, float[] sunDir) {
        float sep = separationDeg(moonDir, sunDir);
        float m = (SUN_RADIUS_DEG + MOON_RADIUS_DEG - sep) / (2f * SUN_RADIUS_DEG);
        return m < 0f ? 0f : (m > 1f ? 1f : m);
    }

    /** 月食食分（按 tick，内部自行算方向；便于门禁/工具调用）。 */
    public static float lunarMagnitude(long tick) {
        float[] m = new float[3], s = new float[3];
        moonDir(tick, m);
        sunDir(DayCycle.phase(tick), s);
        return lunarMagnitude(m, s);
    }

    /** 日食食分（按 tick）。 */
    public static float solarMagnitude(long tick) {
        float[] m = new float[3], s = new float[3];
        moonDir(tick, m);
        sunDir(DayCycle.phase(tick), s);
        return solarMagnitude(m, s);
    }

    /**
     * 综合食类型：日食优先（发生在白天、更醒目），其次月食。
     * <p>两种食都只在朔望附近可能出现 —— 这是由几何（黄纬 β）自然导出的，不是额外开关。
     */
    public static Eclipse eclipse(float[] moonDir, float[] sunDir) {
        float sm = solarMagnitude(moonDir, sunDir);
        if (sm >= 1f) return Eclipse.TOTAL_SOLAR;
        if (sm > 0f) return Eclipse.PARTIAL_SOLAR;
        float lm = lunarMagnitude(moonDir, sunDir);
        if (lm >= 1f) return Eclipse.TOTAL_LUNAR;
        if (lm > 0f) return Eclipse.PARTIAL_LUNAR;
        if (penumbralMagnitude(moonDir, sunDir) > 0f) return Eclipse.PENUMBRAL_LUNAR;
        return Eclipse.NONE;
    }

    /** 综合食类型（按 tick）。 */
    public static Eclipse eclipse(long tick) {
        float[] m = new float[3], s = new float[3];
        moonDir(tick, m);
        sunDir(DayCycle.phase(tick), s);
        return eclipse(m, s);
    }

    /** 是否正在发生"够看"的食（半影月食不算）。 */
    public static boolean notableEclipse(Eclipse e) {
        return e == Eclipse.PARTIAL_LUNAR || e == Eclipse.TOTAL_LUNAR
                || e == Eclipse.PARTIAL_SOLAR || e == Eclipse.TOTAL_SOLAR;
    }

    // ================= 光影方向 =================

    /**
     * 有效光源方向（world shader 的 {@code uLightDir} / 阴影方向）：
     * <b>白天=太阳，夜里=月亮</b>，晨昏之间按 {@link DayCycle#nightFactor(float)} 平滑混合，
     * 且把 y 抬到 {@link #MIN_LIGHT_Y} 以上（沿用"光源永不贴地"的设计意图）。
     *
     * <p>夜间权重还会被"月相"调制：新月夜没有月光 → 权重趋近 0，回到太阳侧的通用方向，
     * 于是"满月夜亮、新月夜暗"是自然结果，而不是额外写死的。
     */
    public static void lightDir(long tick, float[] out3) {
        float phase = DayCycle.phase(tick);
        float[] s = new float[3], m = new float[3];
        sunDir(phase, s);
        moonDir(tick, m);
        lift(s, MIN_LIGHT_Y);
        lift(m, MIN_LIGHT_Y);
        float night = DayCycle.nightFactor(phase);
        float lit = moonLit(tick);
        float w = night * (0.35f + 0.65f * lit);      // 满月 → 完全跟月亮；新月 → 基本不跟
        float x = s[0] * (1f - w) + m[0] * w;
        float y = s[1] * (1f - w) + m[1] * w;
        float z = s[2] * (1f - w) + m[2] * w;
        float l = (float) StrictMath.sqrt((double) (x * x + y * y + z * z));
        if (l < 1e-6f) { out3[0] = 0f; out3[1] = 1f; out3[2] = 0f; return; }
        out3[0] = x / l; out3[1] = y / l; out3[2] = z / l;
    }

    /** 把方向抬到 y &ge; minY（保留方位角），用于避免光源贴地。 */
    private static void lift(float[] d, float minY) {
        if (d[1] >= minY) return;
        float hl = (float) StrictMath.sqrt((double) (d[0] * d[0] + d[2] * d[2]));
        if (hl < 1e-5f) { d[0] = 0f; d[1] = 1f; d[2] = 0f; return; }
        float hWant = (float) StrictMath.sqrt((double) Math.max(0f, 1f - minY * minY));
        d[0] = d[0] / hl * hWant;
        d[2] = d[2] / hl * hWant;
        d[1] = minY;
    }

    // ================= 文本（HUD / 门禁） =================

    /** 月相名（ASCII，保证 {@code Font} 能画）。 */
    public static String phaseName(long tick) {
        float p = moonPhase(tick);
        if (p < 0.03f || p >= 0.97f) return "NEW";
        if (p < 0.22f) return "WAXING CRESCENT";
        if (p < 0.28f) return "FIRST QUARTER";
        if (p < 0.47f) return "WAXING GIBBOUS";
        if (p < 0.53f) return "FULL";
        if (p < 0.72f) return "WANING GIBBOUS";
        if (p < 0.78f) return "LAST QUARTER";
        return "WANING CRESCENT";
    }

    /** 食名（ASCII 短标签）。 */
    public static String eclipseName(Eclipse e) {
        switch (e) {
            case TOTAL_LUNAR: return "TOTAL LUNAR ECLIPSE";
            case PARTIAL_LUNAR: return "PARTIAL LUNAR ECLIPSE";
            case PENUMBRAL_LUNAR: return "PENUMBRAL LUNAR ECLIPSE";
            case TOTAL_SOLAR: return "TOTAL SOLAR ECLIPSE";
            case PARTIAL_SOLAR: return "PARTIAL SOLAR ECLIPSE";
            default: return "";
        }
    }

    // ================= 预报（HUD 用；纯读，不影响仿真） =================

    /**
     * 向前搜索下一次"够看的月食"，返回其 tick；找不到返回 -1。
     *
     * <p>步长 {@code coarse} tick 先粗扫（用于定位），再在附近逐 tick 精修到食分峰值。
     * 只读 tick，不写任何状态 —— 因此可安全放在渲染层周期性调用。
     */
    public static long nextLunarEclipse(long fromTick, int maxDays, int coarse) {
        long span = (long) maxDays * DayCycle.DAY_LEN;
        long step = Math.max(1, coarse);
        for (long t = fromTick; t < fromTick + span; t += step) {
            if (!moonUp(t)) continue;
            if (lunarMagnitude(t) > 0f) return refinePeak(t, -step, step, true);
        }
        return -1L;
    }

    /** 向前搜索下一次日食（不影响仿真的纯读）。 */
    public static long nextSolarEclipse(long fromTick, int maxDays, int coarse) {
        long span = (long) maxDays * DayCycle.DAY_LEN;
        long step = Math.max(1, coarse);
        for (long t = fromTick; t < fromTick + span; t += step) {
            if (solarMagnitude(t) > 0f) return refinePeak(t, -step, step, false);
        }
        return -1L;
    }

    private static long refinePeak(long around, long lo, long hi, boolean lunar) {
        long best = around;
        float bestM = lunar ? lunarMagnitude(around) : solarMagnitude(around);
        long step = Math.max(1L, (hi - lo) / 4L);
        for (long t = around - step * 4; t <= around + step * 4; t++) {
            if (t < 0) continue;
            float m = lunar ? lunarMagnitude(t) : solarMagnitude(t);
            if (m > bestM) { bestM = m; best = t; }
        }
        return best;
    }
}
