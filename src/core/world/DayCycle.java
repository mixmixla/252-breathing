package core.world;

/**
 * 昼夜周期（D3 · 纯确定性派生模型）：<b>零状态 / 零 RNG / 零网格写入</b>。
 *
 * <p>唯一时基是 {@link World#tick}（<b>已进 hashState 指纹</b>）。本类只做纯函数映射：
 * 相位 → 太阳高度 / 光源方向 / 环境光 / 光色 / 天空顶色 / 地平线色 / 星空强度 / “夜”判定。
 * 于是：
 * <ul>
 *   <li><b>渲染层</b>（{@code Game}）据此驱动天空渐变、日照方向、环境光、雾色 —— 昼夜推移一眼可见；</li>
 *   <li><b>仿真层</b>（{@link core.systems.AuroraSystem}）也据 {@link #isNight(long)} 判“夜”，
 *       让<b>全项目对“夜”只有一个定义</b>（此前 AuroraSystem 自带 {@code t%128>=64} 私有相位，
 *       与任何其他系统都不一致，属隐性不一致 —— 见 {@code PORTING_PLAYBOOK.md} §Q）。</li>
 * </ul>
 *
 * <p><b>为什么放在 core（而不是渲染层）</b>：它要被仿真系统读；而它又是纯函数，
 * 不写 mat/mass/prosperity/skills/villageMemory/builtMass、不耗任何 RNG，
 * 所以对四道零漂移门禁<b>逐字节零影响</b>（第 14 道门禁 {@code DayNightTest} 实证）。
 *
 * <p><b>为什么不“冻结时钟式”实现</b>：昼夜是 tick 的纯函数，因此暂停（丢 tick）时天色自然静止、
 * 恢复后自然接续 —— 与 C5 的 {@code World.paused} 语义天然一致，无需额外状态。
 *
 * <p>颜色调色板用 5 关键帧（u = 0 午夜 / 0.25 前夜 / 0.5 晨昏 / 0.75 上午 / 1 正午）线性插值 + smoothstep，
 * 保证天色<b>连续渐变不跳变</b>（门禁断言相邻 tick 色差有上界）。
 */
public final class DayCycle {

    private DayCycle() {}

    /** 每分钟 tick 数（仿真 20 tick/s × 60）—— 菜单显示 / 预设换算 / 档位定义的唯一来源。 */
    public static final int TICKS_PER_MINUTE = 1200;

    /** 昼夜长度出厂值（tick）= 10 分钟。菜单与 {@code applyPreset(null)} 的复位锚点。 */
    public static final int DEFAULT_DAY_LEN = 12000;

    /** 分钟 → tick（四舍五入）。菜单 / 预设 / 显示共用，避免各处自算 1200 而口径漂移。 */
    public static int minutesToTicks(float minutes) { return Math.round(minutes * TICKS_PER_MINUTE); }

    /** 一昼夜的 tick 数（可运行时调节）。仿真 20 tick/s → 默认 {@link #DEFAULT_DAY_LEN} = 10 分钟一昼夜
     *  （QA 2026-09-13：原 512 = 25.6 秒太快，"什么都来不及"——MC 为 20 分钟，折中取 10 分钟）。
     *  菜单 DAY LENGTH 与玩法预设 {@code params.dayLengthMinutes} 都经 {@link #setDayLen} 改它。
     *
     *  <p>声明顺序：常量在上、派生量在下 —— Java 禁止初始化器引用**同类中后声明**的静态字段
     *  （illegal forward reference，2026-09-16 实测编译报错）。 */
    public static int DAY_LEN = DEFAULT_DAY_LEN;
    /** 半日（正午与午夜的间隔）——随 {@link #setDayLen} 同步。 */
    public static int HALF = DAY_LEN / 2;

    /** 调节昼夜长度（tick；下限 1500 = 75 秒防误设）；HALF 同步。菜单 DAY LENGTH 项调用。 */
    public static void setDayLen(int ticks) {
        DAY_LEN = Math.max(1500, ticks);
        HALF = DAY_LEN / 2;
    }

    // ---- 调色板关键帧（RGB 顺序，5 帧均匀分布在 u∈[0,1]）----
    /** 天顶色：午夜深蓝 → 前夜 → 晨昏 → 上午 → 正午亮蓝。 */
    private static final float[] SKY_TOP = {
        0.030f, 0.040f, 0.098f,   // u=0.00 午夜（0920 提亮：夜空不该是纯黑，地形/雾才有对比）
        0.055f, 0.070f, 0.190f,   // u=0.25 前夜
        0.180f, 0.260f, 0.470f,   // u=0.50 日出/日落
        0.270f, 0.500f, 0.800f,   // u=0.75 上午
        0.300f, 0.560f, 0.880f,   // u=1.00 正午
    };
    /** 地平线色：午夜暗 → 前夜紫 → 晨昏暖橙 → 白天灰白。 */
    private static final float[] SKY_HORIZON = {
        0.085f, 0.098f, 0.175f,   // AP3/0920：夜色地平线再抬一档（=雾色），月食雨夜远景也不糊成纯黑
        0.260f, 0.180f, 0.240f,
        0.950f, 0.520f, 0.250f,   // 暖橙（日出/日落）
        0.800f, 0.830f, 0.850f,
        0.780f, 0.870f, 0.940f,
    };
    /** 光色：夜间月蓝 → 晨昏暖橙 → 正午近白。 */
    private static final float[] LIGHT_TINT = {
        0.450f, 0.550f, 0.850f,
        0.600f, 0.580f, 0.800f,
        1.000f, 0.780f, 0.550f,
        1.000f, 0.960f, 0.900f,
        1.000f, 0.990f, 0.950f,
    };

    // ---------- 相位 ----------

    /** 归一化日相位：0=午夜，0.25=日出，0.5=正午，0.75=日落。任意（含负数）tick 都安全回绕。 */
    public static float phase(long tick) {
        long m = tick % DAY_LEN;
        if (m < 0) m += DAY_LEN;
        return (float) m / DAY_LEN;
    }

    /** 第几日（从 1 开始；负数 tick 也安全）。 */
    public static int dayNumber(long tick) {
        return (int) Math.floorDiv(tick, (long) DAY_LEN) + 1;
    }

    /** 太阳高度：-1（地平线下·午夜）→ +1（天顶·正午）。日出与日落均为 0（一升一降）。 */
    public static float sunHeight(float phase) {
        return (float) -StrictMath.cos(phase * 2.0 * Math.PI);
    }

    /** 是否为“夜”。夜 = 太阳在地平线下（{@code sunHeight < 0}）。全项目唯一的“夜”定义。 */
    public static boolean isNight(long tick) {
        return sunHeight(phase(tick)) < 0f;
    }

    /**
     * 下一个「黎明」的 tick（相位 ≈ 0.25，太阳由地平线下升到地平线的时刻）—— <b>睡眠跳夜的纯函数</b>。
     *
     * <p>语义与 MC 的 {@code ServerLevel.setDayTime} 一致：睡觉把时钟**前推**到清晨，而不是把夜色快进
     * （快进要逐 tick 跑仿真，一夜 = {@code DAY_LEN/2} 约 6000 tick × 2ms ≈ 12 秒，不可接受）。
     * 时间在本项目里是 {@code tick} 的纯函数，所以「跳到清晨」＝把 {@code tick} 设成那个值。
     *
     * <p>契约（门禁 {@code DAYNIGHT} 的 {@code NEXTDAWN} 逐条断言）：
     * <ul>
     *   <li>{@code nextDawn(t) > t} —— <b>严格前进</b>，绝不倒退时间（含 t 本身恰为黎明 → 给次日）；</li>
     *   <li>{@code !isNight(nextDawn(t))} —— 落点一定是白天；</li>
     *   <li>{@code nextDawn(t) - t <= DAY_LEN} —— 最多跳一整天，不存在无界循环；</li>
     *   <li>相位对 {@code DAY_LEN} 的取模使负 tick / 任意大 tick 同样安全。</li>
     * </ul>
     */
    public static long nextDawn(long tick) {
        // 用**上取整**（不是 /4）：DAY_LEN 不必被 4 整除（菜单可设任意 ≥1500 的值）。
        long q = (DAY_LEN + 3L) / 4L;
        long dayStart = Math.floorDiv(tick, (long) DAY_LEN) * (long) DAY_LEN;
        long dawn = dayStart + q;
        if (dawn <= tick) dawn += DAY_LEN;                     // 已经过了今天的黎明 → 次日
        // ⚠️ 相位 0.25 恰好是浮点边界：`sunHeight(0.25) = -cos(π/2)` 算出来是 **-6.1e-17**（不是 0），
        //    按 {@link #isNight} 的严格定义它仍算"夜"。所以这里必须往后推到第一个<b>真正</b>的白天，
        //    否则"睡到黎明"会落在"还差一个浮点 ULP 才天亮"的瞬间（实测：门禁 NEXTDAWN 当场抓到）。
        //    有界：正常只走 1 步；仅当 DAY_LEN 极大（相位精度被 float 吃掉）时才多走几步。
        for (int guard = 0; guard < 64 && isNight(dawn); guard++) dawn++;
        return dawn;
    }

    /** 睡眠目标 tick：夜里 → 下一黎明；白天 → <b>原地不动</b>（睡觉不该让时间倒退）。 */
    public static long sleepTarget(long tick) {
        return isNight(tick) ? nextDawn(tick) : tick;
    }

    /** 0.0 = 正午（全亮），1.0 = 深夜。用于星空强度与雾密度。 */
    public static float nightFactor(float phase) {
        float u = (sunHeight(phase) + 1f) * 0.5f;    // 0 夜 → 1 正午
        return 1f - smoothstep(0.28f, 0.62f, u);
    }

    // ---------- 光照 ----------

    /**
     * 光源方向（<b>指向光源</b>，与 world shader 的 {@code uLightDir} 同语义）。
     * 白天=太阳、夜里=月亮（近似对侧），随时间横扫。{@code y} 分量恒 &gt; 0：
     * 避免光源贴地导致“整面全黑”或昼夜间亮度突变。
     */
    public static void lightDir(float phase, float[] out3) {
        double a = phase * 2.0 * Math.PI;
        float sh = sunHeight(phase);
        out3[0] = (float) StrictMath.cos(a) * 0.85f;          // 东→西横扫（午夜与正午互反）
        out3[1] = Math.abs(sh) * 0.90f + 0.25f;         // 恒 > 0
        out3[2] = 0.45f;                                // 固定向南倾，画面有立体感
    }

    /**
     * 环境光基准（world shader 的 {@code uAmbient}）：夜约 0.26 → 正午约 0.62。
     * <p>AP3 把夜间下限 0.20 → 0.26：单光源下背光面会“压死”成近黑，抬高下限让暗部仍有形体。
     * 门禁 {@code DayNightTest} 的约束：取值须落在 [0.199, 0.621]，且 {@code ambient(正午) > ambient(午夜) + 0.3}
     * （此处 0.62 − 0.26 = 0.36，仍满足）。
     */
    public static float ambient(float phase) {
        float u = (sunHeight(phase) + 1f) * 0.5f;       // 0 夜 → 1 正午
        return 0.26f + 0.36f * smoothstep(0f, 1f, u);
    }

    /** 光色（夜间偏月蓝、晨昏偏暖橙、正午近白）。 */
    public static void lightTint(float phase, float[] out3) {
        palette(LIGHT_TINT, dayU(phase), out3);
    }

    /**
     * 天空光填充色（world shader 的 {@code uSkyColor}）：作为“环境/天空光”的<b>独立颜色通道</b>，
     * 与方向光颜色 {@code uLightTint} 解耦 —— 即使月全食/深夜方向光趋近 0，地形仍由本通道得到可见的
     * 月光填充（参考 Minecraft 双通道光照：Sky Light 自带冷色调且有夜间下限；UE 用 Sky Light 作 ambient fill）。
     * 夜间=冷月蓝、日间=天蓝偏白；取值恒 &gt; 0，保证地形永不全黑。纯数据函数，不进仿真。
     */
    public static void skyFillColor(float phase, float[] out3) {
        float u = dayU(phase);                 // 0 夜 → 1 正午
        // R 与 G **必须相等**：MC 的夜间光照贴图 R==G（`LightTexture:112` 的 `(f,f,1)` 只抬高蓝），
        // 所以 MC 的夜色是"纯蓝缩放"、不改红绿比例。此前 G 比 R 高 20% → 绿植被被额外提亮、
        // 画面整体偏黄绿。B/R：夜 1.81（MC 实测 1.85）→ 昼 1.10（MC 白天光照贴图 ≈ 中性白）。
        out3[0] = 0.32f + 0.48f * u;           // R：夜 0.32 → 昼 0.80
        out3[1] = 0.32f + 0.48f * u;           // G：夜 0.32 → 昼 0.80（= R）
        out3[2] = 0.58f + 0.30f * u;           // B：夜 0.58 → 昼 0.88
    }

    // ---------- 天空 ----------

    /** 天顶色。 */
    public static void skyTop(float phase, float[] out3) {
        palette(SKY_TOP, dayU(phase), out3);
    }

    /** 地平线色（距离雾色应与它一致，地平线才不“断层”）。 */
    public static void skyHorizon(float phase, float[] out3) {
        palette(SKY_HORIZON, dayU(phase), out3);
    }

    /** 星空强度：夜 1.0 → 白天 0.0（晨昏平滑淡出）。 */
    public static float starAlpha(float phase) {
        return nightFactor(phase);
    }

    /** 基础雾密度：正午最薄、晨昏最浓（晨雾）、夜里中等。降雨由渲染层再叠加。 */
    public static float fogDensity(float phase) {
        float night = nightFactor(phase);
        float horizon = 1f - Math.abs(sunHeight(phase));   // 晨昏附近 = 1
        return 0.009f + 0.005f * night + 0.004f * horizon * horizon;
    }

    // ---------- 文本（HUD 验证用）----------

    /** 时段名：NIGHT / DAWN / MORNING / NOON / AFTERNOON / DUSK。 */
    public static String label(float phase) {
        if (phase < 0.20f) return "NIGHT";
        if (phase < 0.30f) return "DAWN";
        if (phase < 0.45f) return "MORNING";
        if (phase < 0.55f) return "NOON";
        if (phase < 0.70f) return "AFTERNOON";
        if (phase < 0.80f) return "DUSK";
        return "NIGHT";
    }

    /** 24 小时制时钟文本 {@code HH:MM}（相位 0 = 00:00 午夜）。 */
    public static String clock(float phase) {
        float h24 = phase * 24f;
        int hh = (int) h24;
        int mm = (int) ((h24 - hh) * 60f);
        if (hh > 23) hh = 23;
        if (mm > 59) mm = 59;
        if (mm < 0) mm = 0;
        return String.format(java.util.Locale.US, "%02d:%02d", hh, mm);
    }

    // ---------- 内部：u = 0(午夜) → 1(正午) 的插值参数 ----------

    private static float dayU(float phase) {
        return (sunHeight(phase) + 1f) * 0.5f;
    }

    private static void palette(float[] keys, float u, float[] out3) {
        int k = keys.length / 3 - 1;                    // 关键帧段数
        if (k < 1) { out3[0] = out3[1] = out3[2] = 0f; return; }
        float x = clamp01(u) * k;
        int i = (int) x;
        if (i >= k) i = k - 1;
        float f = x - i;
        f = f * f * (3f - 2f * f);                      // smoothstep：过渡更自然、无“顿”
        for (int c = 0; c < 3; c++) {
            float a = keys[i * 3 + c], b = keys[(i + 1) * 3 + c];
            out3[c] = a + (b - a) * f;
        }
    }

    private static float smoothstep(float e0, float e1, float x) {
        if (e1 <= e0) return x < e0 ? 0f : 1f;
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ---------- 天空射线基（屏幕像素 → 视线方向）----------

    /**
     * 相机基 + 半 FOV，用于把屏幕 NDC 反解成世界空间视线方向。
     *
     * <p><b>为什么需要它</b>：老版天空渐变用的是<b>屏幕 Y</b>（{@code in float vY = aP.y}），
     * 于是抬头/低头时天色完全不动 —— 天空是“贴在屏幕上的壁纸”，不是“头顶的天”；
     * 而且没法画太阳/月亮/星空（它们必须锚在世界方向上）。改成按视线方向取色后，
     * 渐变锚定真实地平线、日月能画出盘、星空能固定在天球上。
     *
     * <p><b>为什么是纯数据类</b>：这样才能 headless 断言“屏幕中心射线 == 相机前向”、
     * “屏幕上方 → 射线向上仰”等性质（否则又是只能肉眼验收的 shader 逻辑）。
     * {@code Game} 从 JOML 的 view 矩阵提取基（保证与实际渲染一致），
     * sky shader 用<b>同一条公式</b>逐像素求方向。
     */
    public static final class SkyBasis {
        public final float fx, fy, fz;      // 前向（单位）
        public final float rx, ry, rz;      // 右向（单位）
        public final float ux, uy, uz;      // 上向（单位）
        public final float tanX, tanY;      // tanX = tan(fovY/2)*aspect，tanY = tan(fovY/2)

        public SkyBasis(float[] fwd, float[] right, float[] up, float fovYDeg, float aspect) {
            float fl = len3(fwd); fx = fwd[0] / fl; fy = fwd[1] / fl; fz = fwd[2] / fl;
            float rl = len3(right); rx = right[0] / rl; ry = right[1] / rl; rz = right[2] / rl;
            float ul = len3(up); ux = up[0] / ul; uy = up[1] / ul; uz = up[2] / ul;
            double t = StrictMath.tan(Math.toRadians(fovYDeg) * 0.5);
            tanY = (float) t;
            tanX = (float) (t * aspect);
        }

        /** NDC（x 右+ / y 上+，[-1,1] 内为屏内）→ 单位视线方向写入 out3。与 sky shader 公式逐字对应。 */
        public void ray(float ndcX, float ndcY, float[] out3) {
            float sx = ndcX * tanX, sy = ndcY * tanY;
            float x = fx + rx * sx + ux * sy;
            float y = fy + ry * sx + uy * sy;
            float z = fz + rz * sx + uz * sy;
            float l = len3(x, y, z);
            if (l < 1e-8f) l = 1f;
            out3[0] = x / l; out3[1] = y / l; out3[2] = z / l;
        }

        /**
         * {@link #ray} 的<b>逆映射</b>：世界方向（单位）→ NDC。两者互为逆运算（门禁做往返断言）。
         *
         * <p>天体（太阳/月亮）要画在屏幕上就必须有这条逆映射；把它放在这里而不是渲染层，
         * 是为了让它和 {@code ray} 一样可 headless 断言（避免"位置算错只能肉眼发现"）。
         *
         * @return false = 该方向在相机背后（{@code dot(dir, fwd) <= 0}），不该上屏
         */
        public boolean ndc(float[] dir, float[] out2) {
            float d = fx * dir[0] + fy * dir[1] + fz * dir[2];
            if (d <= 1e-4f) return false;
            float sx = (rx * dir[0] + ry * dir[1] + rz * dir[2]) / d;
            float sy = (ux * dir[0] + uy * dir[1] + uz * dir[2]) / d;
            out2[0] = sx / tanX;
            out2[1] = sy / tanY;
            return true;
        }

        private static float len3(float[] v) { return len3(v[0], v[1], v[2]); }

        private static float len3(float x, float y, float z) {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }
    }

    /**
     * 水下雾色 / 水下叠加色（学 MC {@code FogRenderer} 的 {@code FogType.WATER}）。
     *
     * <p>MC 水下时把雾色换成<b>水体雾色</b>（原版主世界是 {@code 0x050533} —— 极深的蓝），并把雾距换成
     * 水下曲线（{@code start=-8, end=96}，再乘"水下视觉"系数），于是整屏底色与远近衰减都变成水色。
     * 这里取一个比 MC 略亮、贴近本作水体色（{@code Blocks.WATER} 58/123/208）的深青蓝：既明显区别于
     * 空气中的天色，也还留得住水下方向感。</p>
     *
     * <p>纯数据函数，不进仿真（水下是纯渲染判定，不写任何仿真状态）。唯一调色旋钮就在这里。</p>
     */
    public static void waterFogColor(float[] out3) {
        out3[0] = 0.045f;
        out3[1] = 0.115f;
        out3[2] = 0.225f;
    }
}
