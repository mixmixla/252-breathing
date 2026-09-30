package core.sim;

import core.world.DayCycle;
import core.world.World;

/**
 * 昼夜/天空模型 门禁（D3，第 14 道）。
 *
 * <p>为什么昼夜也值得一道门禁：天色是 {@link core.world.DayCycle} 的纯函数输出，
 * 而它同时被<b>渲染层</b>（天空/日照/雾色）和<b>仿真层</b>（{@code AuroraSystem} 判“夜”）读取。
 * 把它抽成纯模型后，四类以前只能肉眼验收的性质都能 headless 断言：
 *
 * <ol>
 *   <li><b>周期与回绕</b>：{@code phase(t) == phase(t+DAY_LEN)}；{@code phase(0)=0}、{@code phase(HALF)=0.5}；
 *       负数 tick 安全（回绕而非取模出负）。</li>
 *   <li><b>连续性（防“天色跳变”）</b>：以 1 tick 为步长扫完整周期，断言天顶色/地平线色/光色/环境光/
 *       光源方向/星空强度的<b>逐 tick 变化量有上界</b>。若有人把调色板写成“按档位硬切”，这条会红。</li>
 *   <li><b>方向锚定（防“天空贴在屏幕上”）</b>：{@link DayCycle.SkyBasis} 是屏幕像素 → 视线方向的纯数学。
 *       断言屏幕中心射线 == 相机前向、屏幕上方 → 射线向上仰（且 fov=90° 时 {@code y=sin45°} 精确值）、
 *       沿屏幕 Y 扫描时视线高度<b>单调递增</b>。这正是老版天空用屏幕 Y 取色（抬头天色不动）的修复点。</li>
 *   <li><b>纯函数 / 零漂移</b>：把渲染层每帧要算的一整套量狂算几千遍，
 *       {@link World#hashState()} 与 {@code rng.state()} <b>逐字节不变</b> —— 证明昼夜是 tick 的纯读，
 *       不吃 RNG、不写状态，故对四道基线门禁零影响。</li>
 * </ol>
 *
 * <p>另加一条<b>防死分支</b>断言：{@code AuroraSystem} 的“夜”已改为共享的 {@code DayCycle.isNight}，
 * 必须证明它<b>仍然会触发</b>（对齐时若把判据写反，极光会永远不出现——静默变成死代码）。
 * 做法：逐 tick 推进并观察事件增量，给每条极光事件标注它<b>出现时</b>的 tick，断言全部落在夜里。
 *
 * <p>运行：java -cp out core.sim.DayNightTest
 */
public final class DayNightTest {

    private static final int SX = 64, SY = 40, SZ = 64;
    private static final long SEED = 20260910L;
    private static final int T = 300;
    private static final int AURORA_TICKS = 1024;

    public static void main(String[] args) {
        StringBuilder ev = new StringBuilder();
        float[] a = new float[3], b = new float[3];

        // ---------- 1) 周期 / 回绕 / 日序 ----------
        boolean period = true, wrapNeg = true;
        for (long t = -DAY_SAFE; t <= DAY_SAFE; t += 7) {
            if (DayCycle.phase(t) != DayCycle.phase(t + DayCycle.DAY_LEN)) period = false;
            if (Math.abs(DayCycle.phase(t) - DayCycle.phase(t - 3L * DayCycle.DAY_LEN)) > 1e-6f) wrapNeg = false;
        }
        boolean anchors = DayCycle.phase(0) == 0f
                && Math.abs(DayCycle.phase(DayCycle.HALF) - 0.5f) < 1e-6f
                && DayCycle.dayNumber(0) == 1 && DayCycle.dayNumber(DayCycle.DAY_LEN) == 2;
        boolean negPhaseOk = true;
        for (long t = -1200; t < 0; t++) {
            float p = DayCycle.phase(t);
            if (p < 0f || p >= 1f) negPhaseOk = false;
        }
        boolean cycleOk = period && wrapNeg && anchors && negPhaseOk;

        // ---------- 2) 连续性：逐 tick 扫全周期，变化量有上界 ----------
        final float EPS = 0.06f;                      // 1 tick 的相位步长 = 1/512，天色不应有肉眼级跳变
        float maxTop = 0f, maxHor = 0f, maxTint = 0f, maxAmb = 0f, maxDir = 0f, maxStar = 0f;
        DayCycle.skyTop(0f, a); DayCycle.skyHorizon(0f, b);
        float[] pTop = a.clone(), pHor = b.clone();
        float[] pTint = new float[3]; DayCycle.lightTint(DayCycle.phase(0), pTint);
        float pAmb = DayCycle.ambient(DayCycle.phase(0));
        float pStar = DayCycle.starAlpha(DayCycle.phase(0));
        float[] pDir = new float[3]; DayCycle.lightDir(DayCycle.phase(0), pDir);
        float[] cur = new float[3];
        for (int t = 1; t <= DayCycle.DAY_LEN * 2; t++) {
            float ph = DayCycle.phase(t);
            DayCycle.skyTop(ph, cur);    maxTop = Math.max(maxTop, maxAbsDiff(cur, pTop));    System.arraycopy(cur, 0, pTop, 0, 3);
            DayCycle.skyHorizon(ph, cur); maxHor = Math.max(maxHor, maxAbsDiff(cur, pHor));   System.arraycopy(cur, 0, pHor, 0, 3);
            DayCycle.lightTint(ph, cur);  maxTint = Math.max(maxTint, maxAbsDiff(cur, pTint)); System.arraycopy(cur, 0, pTint, 0, 3);
            float amb = DayCycle.ambient(ph);
            maxAmb = Math.max(maxAmb, Math.abs(amb - pAmb)); pAmb = amb;
            float st = DayCycle.starAlpha(ph);
            maxStar = Math.max(maxStar, Math.abs(st - pStar)); pStar = st;
            DayCycle.lightDir(ph, cur);
            maxDir = Math.max(maxDir, maxAbsDiff(cur, pDir)); System.arraycopy(cur, 0, pDir, 0, 3);
        }
        boolean continuous = maxTop <= EPS && maxHor <= EPS && maxTint <= EPS
                && maxAmb <= EPS && maxDir <= EPS && maxStar <= EPS;

        // ---------- 3) 取值域 / 昼夜单调 ----------
        boolean inRange = true;
        for (int i = 0; i < 512; i++) {
            float ph = i / 512f;
            float sh = DayCycle.sunHeight(ph);
            if (sh < -1.0001f || sh > 1.0001f) inRange = false;
            float amb = DayCycle.ambient(ph);
            if (amb < 0.199f || amb > 0.621f) inRange = false;
            float st = DayCycle.starAlpha(ph);
            if (st < 0f || st > 1f) inRange = false;
            DayCycle.skyTop(ph, cur);
            DayCycle.skyHorizon(ph, b);
            DayCycle.lightTint(ph, a);
            for (int c = 0; c < 3; c++)
                if (cur[c] < 0f || cur[c] > 1f || b[c] < 0f || b[c] > 1f
                        || a[c] < 0f || a[c] > 1.001f) inRange = false;
            DayCycle.lightDir(ph, cur);
            if (cur[1] <= 0f) inRange = false;                 // 光源永不贴地（防整面全黑）
        }
        float ambNoon = DayCycle.ambient(0.5f), ambMid = DayCycle.ambient(0f), ambDawn = DayCycle.ambient(0.25f);
        float fogNoon = DayCycle.fogDensity(0.5f), fogDawn = DayCycle.fogDensity(0.25f);
        boolean sunOk = DayCycle.sunHeight(0.5f) > 0.999f && DayCycle.sunHeight(0f) < -0.999f
                && ambNoon > ambMid + 0.3f && ambNoon > ambDawn
                && fogNoon < fogDawn;                              // 晨昏有雾、正午通透
        boolean rngOk = inRange && sunOk;

        // ---------- 4) “夜”唯一性 ----------
        boolean nightOk = DayCycle.isNight(0) && !DayCycle.isNight(DayCycle.HALF);
        for (int i = 0; i < 512; i++) {
            long t = i * 3L;
            if (DayCycle.isNight(t) != (DayCycle.sunHeight(DayCycle.phase(t)) < 0f)) nightOk = false;
        }
        boolean labelOk = "NIGHT".equals(DayCycle.label(0f)) && "NOON".equals(DayCycle.label(0.5f))
                && "DAWN".equals(DayCycle.label(0.25f)) && "DUSK".equals(DayCycle.label(0.75f))
                && "00:00".equals(DayCycle.clock(0f)) && "12:00".equals(DayCycle.clock(0.5f))
                && "06:00".equals(DayCycle.clock(0.25f)) && "18:00".equals(DayCycle.clock(0.75f));
        boolean semanticOk = nightOk && labelOk;

        // ---------- 4b) 睡眠跳夜：nextDawn / sleepTarget 契约（床「跳过夜晚」的纯函数底座）----------
        //  断言的是"能证伪"的性质，而不是"实现细节"：严格前进 / 落点必为昼 / 最多一天 /
        //  落点是**最早**的白天（区间 (t,d) 内不得再出现白天）/ 白天睡觉原样不动（不倒退时间）。
        //  注意"最早"必须靠扫描区间证明，不能写成 `isNight(d-1)` —— 相位 0.25 是浮点边界
        //  （sunHeight(0.25) = -6.1e-17 仍算夜），"d-1 是夜"在这种边界上会给出错误的前提。
        boolean dawnOk = true;
        long worstJump = 0L;
        int dawnCases = 0;
        for (int i = 0; i < 60; i++) {
            long t = i * 997L - 1234L;                        // 覆盖负 tick；步长与 DAY_LEN 互质 → 打散相位
            long d = DayCycle.nextDawn(t);
            if (!(d > t)) dawnOk = false;                     // 严格前进
            long jump = d - t;
            if (jump <= 0L || jump > DayCycle.DAY_LEN) dawnOk = false;   // 有界（≤ 一整天）
            if (DayCycle.isNight(d)) dawnOk = false;          // 落点必为白天
            boolean night = DayCycle.isNight(t);
            if (night) {                                      // 从"夜"出发才谈"最早"：
                for (long u = t + 1; u < d; u++)              // 区间 (t,d) 内不得再出现白天
                    if (!DayCycle.isNight(u)) { dawnOk = false; break; }
            }
            long st = DayCycle.sleepTarget(t);
            if (night) { if (st != d) dawnOk = false; }
            else if (st != t) dawnOk = false;                 // 白天睡觉 = 原地（不倒退）
            if (jump > worstJump) worstJump = jump;
            dawnCases++;
        }
        semanticOk = semanticOk && dawnOk;

        // ---------- 5) 天空射线基：屏幕像素 → 视线方向（老版“贴屏幕”天空的修复点）----------
        float[] fwd = {0f, 0f, -1f}, right = {1f, 0f, 0f}, up = {0f, 1f, 0f};
        float[] d = new float[3];
        DayCycle.SkyBasis sb = new DayCycle.SkyBasis(fwd, right, up, 90f, 16f / 9f);
        sb.ray(0f, 0f, d);
        boolean centerIsFwd = Math.abs(d[0]) < 1e-6f && Math.abs(d[1]) < 1e-6f && Math.abs(d[2] + 1f) < 1e-6f;
        // fovY=90° → tanY=1；屏幕上边缘（ndcY=+1）视线应恰好上仰 45°
        sb.ray(0f, 1f, d);
        boolean upEdgeTilt = Math.abs(d[1] - 0.70710678f) < 1e-4f && Math.abs(d[2] + 0.70710678f) < 1e-4f;
        sb.ray(0f, -1f, d);
        boolean downEdgeTilt = d[1] < -0.7f;
        sb.ray(1f, 0f, d);
        boolean rightEdge = d[0] > 0.8f && Math.abs(d[1]) < 1e-6f;
        // 沿屏幕 Y 扫描：视线高度严格单调递增（=> 天色随抬头升高，而不是贴在屏幕上）
        float prevY = -2f;
        boolean monotone = true, unitLen = true;
        for (float ny = -1f; ny <= 1.0001f; ny += 0.05f) {
            sb.ray(0f, ny, d);
            if (d[1] <= prevY) monotone = false;
            prevY = d[1];
            float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            if (Math.abs(l - 1f) > 1e-5f) unitLen = false;
        }
        // 非对称宽高比：水平半角应大于垂直（tanX = tanY*aspect）
        boolean aspectOk = sb.tanX > sb.tanY && Math.abs(sb.tanY - 1f) < 1e-6f;
        boolean rayOk = centerIsFwd && upEdgeTilt && downEdgeTilt && rightEdge && monotone && unitLen && aspectOk;

        ev.append("CYCLE period=").append(period).append(" negWrap=").append(wrapNeg && negPhaseOk)
          .append(" anchors=").append(anchors);
        ev.append(" | CONTINUOUS eps=").append(EPS).append(" top=").append(fmt(maxTop))
          .append(" hor=").append(fmt(maxHor)).append(" tint=").append(fmt(maxTint))
          .append(" amb=").append(fmt(maxAmb)).append(" dir=").append(fmt(maxDir))
          .append(" star=").append(fmt(maxStar));
        ev.append(" | SUN noon=").append(fmt(ambNoon)).append(" mid=").append(fmt(ambMid))
          .append(" dawn=").append(fmt(ambDawn)).append(" fogNoon=").append(fmt(fogNoon))
          .append(" fogDawn=").append(fmt(fogDawn));
        ev.append(" | NIGHT isNight(0)=").append(DayCycle.isNight(0))
          .append(" isNight(HALF)=").append(DayCycle.isNight(DayCycle.HALF));
        ev.append(" | RAY center=fwd ").append(centerIsFwd).append(" up45=").append(upEdgeTilt)
          .append(" monotone=").append(monotone).append(" unit=").append(unitLen)
          .append(" tanX/tanY=").append(fmt(sb.tanX)).append("/").append(fmt(sb.tanY));

        // ---------- 6) 纯函数：狂算整套昼夜量，指纹与 RNG 逐字节不变 ----------
        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        for (int i = 0; i < T; i++) sim.world.tick();
        long h1 = sim.world.hashState();
        long r1 = sim.world.rng.state();
        float[] o3 = new float[3];
        for (int i = 0; i < T * 8; i++) {
            long tk = sim.world.tick + i;
            float ph = DayCycle.phase(tk);
            DayCycle.skyTop(ph, o3); DayCycle.skyHorizon(ph, o3); DayCycle.lightTint(ph, o3);
            DayCycle.lightDir(ph, o3); DayCycle.ambient(ph); DayCycle.starAlpha(ph);
            DayCycle.fogDensity(ph); DayCycle.isNight(tk); DayCycle.dayNumber(tk);
            DayCycle.label(ph); DayCycle.clock(ph);
            sb.ray(i % 3 - 1, i % 5 - 2, o3);
        }
        long h2 = sim.world.hashState();
        long r2 = sim.world.rng.state();
        boolean pureNoDrift = h1 == h2 && r1 == r2;
        ev.append(" | PURE hashEq=").append(h1 == h2).append(" rngEq=").append(r1 == r2)
          .append(" h=").append(Long.toHexString(h1));

        // ---------- 7) 防死分支：极光仍会触发，且只出现在“夜” ----------
        Simulation aur = new Simulation(SEED, SX, SY, SZ);
        int seen = 0, aurora = 0, atNight = 0, atDay = 0;
        for (int i = 0; i < AURORA_TICKS; i++) {
            aur.world.tick();
            for (int j = seen; j < aur.world.events.size(); j++) {
                World.Event e = aur.world.events.get(j);
                if ("aurora".equals(e.system)) {
                    aurora++;
                    if (DayCycle.isNight(aur.world.tick)) atNight++; else atDay++;
                }
            }
            seen = aur.world.events.size();
        }
        boolean auroraAlive = aurora > 0 && atDay == 0;
        ev.append(" | AURORA events=").append(aurora).append(" atNight=").append(atNight)
          .append(" atDay=").append(atDay);

        // ---------- 8) 天体（Celestial）：真实日弧 / 月球独立轨道 / 相位 / 食 ----------
        // 固定用默认档：rate 是全局表现参数（设置页可改），门禁必须自锁，否则跑在别的门禁之后就漂了
        core.world.Celestial.setRate(core.world.Celestial.Rate.NORMAL);
        float[] cs = new float[3], cm = new float[3], cl = new float[3];
        // 8a 日弧：正午过天顶、日出日落贴地平线、午夜天底；且 y 分量 == sunHeight（与昼夜模型同一个定义）
        float cosPhi = (float) Math.cos(Math.toRadians(core.world.Celestial.OBSERVER_LATITUDE_DEG));
        core.world.Celestial.sunDir(0.5f, cs);
        boolean noonTop = Math.abs(cs[1] - cosPhi) < 1e-4f;
        // 回归护栏：正午太阳不许过天顶（过天顶 → 天上的太阳只有抬头才看得见、正午没有直射光与影子）
        boolean sunNotZenith = cs[1] < 0.95f && cs[1] > 0.5f;
        core.world.Celestial.sunDir(0f, cs);   boolean midNadir = Math.abs(cs[1] + cosPhi) < 1e-4f;
        core.world.Celestial.sunDir(0.25f, cs); boolean dawnHorizon = Math.abs(cs[1]) < 1e-5f;
        core.world.Celestial.sunDir(0.75f, cs); boolean duskHorizon = Math.abs(cs[1]) < 1e-5f;
        boolean sunArc = true;
        for (int i = 0; i < 128; i++) {
            float ph = i / 128f;
            core.world.Celestial.sunDir(ph, cs);
            float l = (float) Math.sqrt(cs[0] * cs[0] + cs[1] * cs[1] + cs[2] * cs[2]);
            if (Math.abs(l - 1f) > 1e-5f) sunArc = false;
            // 与昼夜模型同号同定义（只是被纬度压低）：dir.y == cos(phi) * sunHeight
            if (Math.abs(cs[1] - cosPhi * DayCycle.sunHeight(ph)) > 1e-4f) sunArc = false;
        }
        // 8b 月球：自己的轨道 —— 每恒星日推进 (1/日 − 1/朔望月)，中天周期必须 > 1 天（即"每天晚升"）
        double transitDays = 1.0 / (1.0 / DayCycle.DAY_LEN - 1.0 / core.world.Celestial.synodicTicks());
        double transitDay = transitDays / DayCycle.DAY_LEN;   // tick 换算成"日"
        boolean transitOk = transitDay > 1.02 && transitDay < 1.06;         // 真实 1.0348
        boolean moonUnit = true;
        for (int i = 0; i < 256; i++) {
            core.world.Celestial.moonDir(i * 131L, cm);
            float l = (float) Math.sqrt(cm[0] * cm[0] + cm[1] * cm[1] + cm[2] * cm[2]);
            if (Math.abs(l - 1f) > 1e-5f) moonUnit = false;
        }
        boolean moonUpAtStart = true;
        core.world.Celestial.moonDir(0L, cm);
        moonUpAtStart = cm[1] > 0f;
        int crossings = 0;
        boolean prevUp = cm[1] > 0f;
        for (long tt = 1; tt < (long) (core.world.Celestial.synodicTicks() * 1.2f); tt += 5) {
            core.world.Celestial.moonDir(tt, cm);
            boolean mUp = cm[1] > 0f;
            if (mUp != prevUp) crossings++;
            prevUp = mUp;
        }
        boolean moonRises = crossings >= 2;
        // 8c 月相：开局满月；半个朔望月后是新月
        float litFull = core.world.Celestial.moonLit(0L);
        float litNew = core.world.Celestial.moonLit((long) (core.world.Celestial.synodicTicks() * 0.5f));
        boolean litOk = litFull > 0.999f && litNew < 0.001f;
        boolean phaseNameOk = "FULL".equals(core.world.Celestial.phaseName(0L));
        // 8d 光影方向：单位长、恒在地平线以上、逐 tick 连续；深夜必须跟随月亮（真实月照方向）
        boolean lightUnit = true, lightUp = true;
        float maxDL = 0f;
        core.world.Celestial.lightDir(0L, cl);
        float[] prevL = cl.clone();
        for (long tt = 1; tt <= 2L * DayCycle.DAY_LEN; tt++) {
            core.world.Celestial.lightDir(tt, cl);
            float l = (float) Math.sqrt(cl[0] * cl[0] + cl[1] * cl[1] + cl[2] * cl[2]);
            if (Math.abs(l - 1f) > 1e-4f) lightUnit = false;
            if (cl[1] <= 0f) lightUp = false;
            maxDL = Math.max(maxDL, maxAbsDiff(cl, prevL));
            System.arraycopy(cl, 0, prevL, 0, 3);
        }
        core.world.Celestial.lightDir(0L, cl);
        core.world.Celestial.moonDir(0L, cm);
        boolean lightFollowsMoon = core.world.Celestial.separationDeg(cl, cm) < 25f;
        boolean lightOk = lightUnit && lightUp && maxDL <= 0.06f && lightFollowsMoon;
        // 8e 食：开局第一夜＝月全食；月食/日食都可复现；且只可能发生在朔望附近
        boolean firstNight = core.world.Celestial.eclipse(0L) == core.world.Celestial.Eclipse.TOTAL_LUNAR;
        long nl = core.world.Celestial.nextLunarEclipse(0L, 90, 600);
        long ns = core.world.Celestial.nextSolarEclipse(0L, 400, 600);   // NORMAL 档首次日食 ~162 日
        boolean eclipseReachable = nl >= 0L && ns >= 0L;
        boolean syzygyOnly = true;
        int lunarEvents = 0, solarEvents = 0;
        for (long tt = 0; tt < 400L * DayCycle.DAY_LEN; tt += 97) {   // 窗口要盖住首次日食（~162 日）
            float litHere = core.world.Celestial.moonLit(tt);
            if (core.world.Celestial.lunarMagnitude(tt) > 0f) {
                lunarEvents++;
                if (litHere < 0.85f) syzygyOnly = false;            // 月食只可能发生在满月
            }
            if (core.world.Celestial.solarMagnitude(tt) > 0f) {
                solarEvents++;
                if (litHere > 0.15f) syzygyOnly = false;            // 日食只可能发生在新月
            }
        }
        boolean eclipseAlive = eclipseReachable && syzygyOnly && lunarEvents > 0 && solarEvents > 0;
        // 8g 频率回归护栏（2026-09-16）：满月成食必须是"偶尔"而不是"每次"。
        //     旧值黄白交角 0.70° < 本影+月视半径 0.959° → 100% 的满月都成食（用户反馈"怎么老月食"）。
        //     默认档 2.0° 实测 ~30%；真实世界 ~12%。区间 (5%, 60%) 同时挡住"永不食"与"每满月食"。
        int fullMoons = 80, fullMoonEcl = 0;
        for (int k = 0; k < fullMoons; k++) {
            long tf = (long) (k * core.world.Celestial.synodicTicks());
            if (core.world.Celestial.lunarMagnitude(tf) > 0f) fullMoonEcl++;
        }
        float eclipseRate = (float) fullMoonEcl / fullMoons;
        boolean eclipseRateOk = eclipseRate > 0.05f && eclipseRate < 0.60f;
        // 8f 逆映射：dir → NDC → ray 必须回到原方向；相机背后的方向必须返回 false
        float[] n2 = new float[2];
        boolean ndcRound = true;
        for (int i = 0; i < 128; i++) {
            core.world.Celestial.sunDir(i / 128f, cs);
            if (!sb.ndc(cs, n2)) continue;
            sb.ray(n2[0], n2[1], d);
            if (Math.abs(d[0] - cs[0]) > 1e-4f || Math.abs(d[1] - cs[1]) > 1e-4f
                    || Math.abs(d[2] - cs[2]) > 1e-4f) ndcRound = false;
        }
        float[] behind = {0f, 0f, 1f};
        boolean ndcBehind = !sb.ndc(behind, n2);
        boolean celestialOk = sunArc && noonTop && sunNotZenith && midNadir && dawnHorizon && duskHorizon
                && transitOk && moonUnit && moonUpAtStart && moonRises && litOk && phaseNameOk
                && lightOk && firstNight && eclipseAlive && eclipseRateOk && ndcRound && ndcBehind;
        ev.append(" | CELESTIAL arc=").append(sunArc).append(" noonTop=").append(noonTop)
          .append(" midNadir=").append(midNadir).append(" notZenith=").append(sunNotZenith).append(" dawnHoriz=").append(dawnHorizon).append("/").append(duskHorizon)
          .append(" transit=").append(fmt((float) transitDay)).append("d")
          .append(" moonUnit=").append(moonUnit).append(" moonRises=").append(moonRises)
          .append(" lit=").append(fmt(litFull)).append("/").append(fmt(litNew))
          .append(" light=").append(lightUnit).append("/").append(lightUp).append("/").append(fmt(maxDL))
          .append(" followMoon=").append(lightFollowsMoon)
          .append(" firstNightTotal=").append(firstNight)
          .append(" nextLunar=").append(nl).append(" nextSolar=").append(ns)
          .append(" syzygyOnly=").append(syzygyOnly)
          .append(" lun/sol hits=").append(lunarEvents).append("/").append(solarEvents)
          .append(" fullMoonEcl=").append(fullMoonEcl).append("/").append(fullMoons)
          .append(" rate=").append(fmt(eclipseRate)).append(" rateOk=").append(eclipseRateOk)
          .append(" ndcRound=").append(ndcRound).append(" ndcBehind=").append(ndcBehind)
          .append(" nextDawnOk=").append(dawnOk).append(" worstJump=").append(worstJump)
          .append("/").append(DayCycle.DAY_LEN).append(" cases=").append(dawnCases);

        boolean pass = cycleOk && continuous && rngOk && semanticOk && rayOk && pureNoDrift && auroraAlive && celestialOk;

        System.out.println("DAYNIGHT  " + ev);
        System.out.println("SKY  horizonLockedRay=true  dayLen=" + DayCycle.DAY_LEN
                + "ticks (" + fmt(DayCycle.DAY_LEN / 20f) + "s)  phase0=00:00  phase0.5=12:00");
        System.out.println(pass ? "DAYNIGHT PASS" : "DAYNIGHT FAIL");
        if (!pass) System.exit(1);
    }

    private static final long DAY_SAFE = 4000L;

    private static float maxAbsDiff(float[] x, float[] y) {
        float m = 0f;
        for (int i = 0; i < x.length; i++) m = Math.max(m, Math.abs(x[i] - y[i]));
        return m;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }
}
