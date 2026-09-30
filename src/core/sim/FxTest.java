package core.sim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import core.content.EffectQueue;
import core.content.ParticleSim;
import core.content.MapField;
import core.content.RainField;
import core.world.Blocks;
import core.world.World;

/**
 * 粒子门禁（第 30 个出口）—— 断言「粒子可配置」的十条性质。
 *
 * <p>粒子的物理模型被特意放在 <b>core</b>（{@link ParticleSim}，零 GL 依赖），
 * 所以它能在无头环境里被完整断言 —— 渲染器只负责把索引读出来画成方块。
 *
 * <p>核心断言：<b>SPAWN</b>（按配置出量）、<b>DET</b>（同种子同配置逐粒子一致 ——
 * 这是「内容层零 RNG」的直接验证）、<b>GRAVITY/DRAG</b>（力场真的作用）、
 * <b>CAP</b>（超量不越界，绝不崩）、<b>RAMP</b>（尺寸/颜色沿生命进度插值）。
 *
 * <p>2026-09-17 加 RAIN 段：雨幕的**几何模型**（{@link RainField}）也放在 core，
 * 于是"雨丝是否全在界内 / 是否铺满屏宽 / alpha 是否随雨量线性"这些性质
 * 都能在无头环境断言 —— 否则它只有有 GL 的机器才看得见，写错了没人知道。
 */
public final class FxTest {

    private static int fails = 0;

    private static void ck(String tag, boolean cond, String detail) {
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    private static JsonObject j(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    private static final String SPARK = "{\"shape\":\"point\",\"count\":18,\"life\":[0.3,0.9],"
            + "\"speed\":[1.5,3.5],\"gravity\":-5.0,\"drag\":0.6,\"size\":[0.05,0.12],"
            + "\"color0\":[1.0,0.9,0.4,1.0],\"color1\":[1.0,0.3,0.05,0.0]}";

    public static void main(String[] args) {

        // ---------------- SPAWN：按配置出量 ----------------
        ParticleSim p = new ParticleSim(20260913L);
        JsonObject spark = j(SPARK);
        int made = p.spawn(spark, 0f, 10f, 0f);
        ck("SPAWN", made == 18 && p.count() == 18, "made=" + made + " count=" + p.count());

        // ---------------- DET：同种子同配置 → 逐粒子一致（零 RNG 纪律的验证） ----------------
        ParticleSim q = new ParticleSim(20260913L);
        q.spawn(spark, 0f, 10f, 0f);
        boolean det = p.count() == q.count();
        for (int i = 0; det && i < p.count(); i++) {
            if (p.lifeLeft(i) != q.lifeLeft(i) || p.size(i) != q.size(i)
                    || p.vx(i) != q.vx(i) || p.vy(i) != q.vy(i)) det = false;
        }
        ck("DET", det, "counts=" + p.count() + "/" + q.count() + " identical=" + det);

        // ---------------- GRAVITY：重力真的拉速度 ----------------
        ParticleSim g = new ParticleSim(7L);
        g.spawn(j("{\"shape\":\"point\",\"count\":1,\"life\":[5,5],\"speed\":[0,0],\"gravity\":-6.0}"), 0f, 5f, 0f);
        float v0 = g.vy(0);
        g.update(0.5f);
        float v1 = g.vy(0);
        ck("GRAVITY", v1 < v0 && Math.abs(v1 - (v0 - 3.0f)) < 0.01f,
                String.format("vy %.3f -> %.3f (expect %.3f)", v0, v1, v0 - 3.0f));

        // ---------------- DRAG：阻尼真的衰减速度 ----------------
        ParticleSim d = new ParticleSim(9L);
        d.spawn(j("{\"shape\":\"point\",\"count\":1,\"life\":[5,5],\"speed\":[4,4],\"gravity\":0,\"drag\":2.0}"), 0f, 5f, 0f);
        float s0 = Math.abs(d.vx(0));
        d.update(0.25f);
        float s1 = Math.abs(d.vx(0));
        ck("DRAG", s1 < s0 && s1 > 0f, String.format("|vx| %.3f -> %.3f (drag 2.0 @0.25s)", s0, s1));

        // ---------------- LIFE：寿命耗尽自动回收 ----------------
        ParticleSim l = new ParticleSim(11L);
        l.spawn(j("{\"shape\":\"point\",\"count\":12,\"life\":[0.1,0.1],\"speed\":[1,1]}"), 0f, 5f, 0f);
        int before = l.count();
        l.update(0.2f);
        ck("LIFE", before == 12 && l.count() == 0, "before=" + before + " after=" + l.count());

        // ---------------- RAMP：尺寸/颜色沿生命进度插值 ----------------
        ParticleSim r = new ParticleSim(13L);
        r.spawn(j("{\"shape\":\"point\",\"count\":1,\"life\":[2,2],\"speed\":[0,0],\"gravity\":0,"
                + "\"size\":[0.2,0.2],\"sizeEnd\":[0.4,0.4],\"color0\":[1,0,0,1],\"color1\":[0,0,1,0]}"), 0f, 5f, 0f);
        float t0 = r.t(0), sz0 = r.size(0), rd0 = r.red(0);
        r.update(1.0f);
        float t1 = r.t(0), sz1 = r.size(0), rd1 = r.red(0), bl1 = r.blue(0);
        boolean ramp = Math.abs(t0) < 0.01f && Math.abs(t1 - 0.5f) < 0.02f
                && Math.abs(sz1 - 0.3f) < 0.01f                  // 0.2→0.4 在中点
                && Math.abs(rd1 - 0.5f) < 0.02f && Math.abs(bl1 - 0.5f) < 0.02f;
        ck("RAMP", ramp, String.format("t %.2f->%.2f size %.3f->%.3f r %.2f->%.2f b->%.2f",
                t0, t1, sz0, sz1, rd0, rd1, bl1));

        // ---------------- CAP：超量不越界（丢弃而非崩溃） ----------------
        ParticleSim c = new ParticleSim(17L);
        int big = c.spawn(j("{\"shape\":\"point\",\"count\":900,\"life\":[5,5],\"speed\":[1,1]}"), 0f, 5f, 0f);
        int again = c.spawn(j("{\"shape\":\"point\",\"count\":50,\"life\":[5,5],\"speed\":[1,1]}"), 0f, 5f, 0f);
        ck("CAP", big == ParticleSim.CAP && c.count() == ParticleSim.CAP
                        && again == 0 && c.droppedTotal() >= 50,
                "made=" + big + " cap=" + ParticleSim.CAP + " again=" + again + " dropped=" + c.droppedTotal());

        // ---------------- SHAPE：球状 vs 点状分布不同 ----------------
        ParticleSim sh = new ParticleSim(19L);
        sh.spawn(j("{\"shape\":\"sphere\",\"count\":40,\"life\":[5,5],\"speed\":[2,2],\"gravity\":0}"), 0f, 5f, 0f);
        int up = 0, down = 0;
        for (int i = 0; i < sh.count(); i++) { if (sh.vy(i) > 0.01f) up++; else if (sh.vy(i) < -0.01f) down++; }
        ParticleSim sh2 = new ParticleSim(19L);
        sh2.spawn(j("{\"shape\":\"point\",\"count\":40,\"life\":[5,5],\"speed\":[2,2],\"gravity\":0}"), 0f, 5f, 0f);
        int up2 = 0;
        for (int i = 0; i < sh2.count(); i++) if (sh2.vy(i) > 0.01f) up2++;
        ck("SHAPE", up > 0 && down > 0 && up2 == sh2.count(),
                "sphere up/down=" + up + "/" + down + " point up=" + up2 + "/" + sh2.count());

        // ---------------- CLEAR ----------------
        c.clear();
        ck("CLEAR", c.count() == 0, "count=" + c.count());

        // ---------------- HASH：hash01 分布可用于散布（落入 [0,1) 且非退化） ----------------
        float mn = 2f, mx = -1f;
        for (int i = 0; i < 1000; i++) {
            float v = EffectQueue.hash01(123L, i, 7);
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        ck("HASH", mn >= 0f && mx < 1f && mx - mn > 0.9f,
                String.format("min=%.4f max=%.4f spread=%.4f", mn, mx, mx - mn));

        // ---------------- RAIN：雨幕几何模型（纯函数 / 有界 / 随雨量线性 / 铺满屏宽） ----------------
        // 雨幕层此前零验证：只在有 GL 的机器上看得到，软件渲染器不含叠加层。
        // 抽到 core.content.RainField 之后，这些性质就能无头断言。
        final int W = 1280, H = 800;
        final float T = 37.5f;
        float[] a = new float[RainField.FIELDS], b = new float[RainField.FIELDS];
        RainField.streak(7, W, H, T, 1.0f, a);
        RainField.streak(7, W, H, T, 1.0f, b);
        boolean rainDet = true;
        for (int k = 0; k < RainField.FIELDS; k++) if (a[k] != b[k]) rainDet = false;
        ck("RAIN_DET", rainDet, "同入参两次 → 逐字段一致=" + rainDet);

        boolean rainBounds = true;
        float mnX = Float.MAX_VALUE, mxX = -Float.MAX_VALUE;
        for (int i = 0; i < RainField.STREAKS; i++) {
            RainField.streak(i, W, H, T, 1.0f, a);
            if (a[0] < -RainField.X_PAD || a[0] > W + RainField.X_PAD) rainBounds = false;
            if (a[1] < -RainField.LEN_MAX || a[1] > RainField.spanY(H)) rainBounds = false;
            if (a[2] < RainField.LEN_MIN || a[2] > RainField.LEN_MAX) rainBounds = false;
            if (a[3] < RainField.SLANT_MIN || a[3] > RainField.SLANT_MAX) rainBounds = false;
            if (a[4] < 0f || a[4] > RainField.ALPHA_MAIN) rainBounds = false;
            if (Float.isNaN(a[1]) || Float.isInfinite(a[1])) rainBounds = false;
            if (a[0] < mnX) mnX = a[0];
            if (a[0] > mxX) mxX = a[0];
        }
        ck("RAIN_BOUNDS", rainBounds,
                String.format("240 条全在界内=%b  x∈[%.0f,%.0f] y/len/slant/alpha 均在域内", rainBounds, mnX, mxX));

        // 铺满屏宽：最左/最右至少覆盖 80% 屏宽 —— 否则雨丝会挤成一坨（写错了看不出来）
        boolean rainSpread = (mxX - mnX) > W * 0.8f;
        ck("RAIN_SPREAD", rainSpread, String.format("x 跨度 %.0f px / 屏宽 %d", mxX - mnX, W));

        // 随雨量线性 + 雨量 0 → 完全不可见（与 Game 的 <=0.01 提前返回一致）
        RainField.streak(3, W, H, T, 0.5f, a);
        RainField.streak(3, W, H, T, 0.0f, b);
        boolean rainAlpha = Math.abs(a[4] - RainField.ALPHA_MAIN * 0.5f) < 1e-6f
                && Math.abs(a[5] - RainField.ALPHA_SECOND * 0.5f) < 1e-6f
                && b[4] == 0f && b[5] == 0f;
        ck("RAIN_ALPHA", rainAlpha, String.format("0.5→%.3f/%.3f  0→%.1f/%.1f",
                a[4], a[5], b[4], b[5]));

        // 顶点预算：雨幕层相对 HUD 上限（1.6M float）占比 —— 防"某天雨丝数被调爆"
        int verts = RainField.vertexCount();
        boolean rainBudget = verts * 7 < 1600000 / 10;
        ck("RAIN_BUDGET", rainBudget, "verts=" + verts + " (float=" + (verts * 7) + " < HUD 上限的 1/10)");

        // ---------------- MAP：小地图采样模型（纯读 / 确定 / 越界安全 / 水色 / 高度明暗） ----------------
        World mw = new World(20260917L, 32, 40, 32);
        int px = 16, pz = 16;
        int[] mc = new int[MapField.CELLS * MapField.CELLS];
        int[] mc2 = new int[MapField.CELLS * MapField.CELLS];
        MapField.sample(mw, px, pz, mc);
        MapField.sample(mw, px, pz, mc2);
        boolean mapDet = true;
        for (int i = 0; i < mc.length; i++) if (mc[i] != mc2[i]) mapDet = false;
        ck("MAP_DET", mapDet, "同入参两次 → 逐格一致=" + mapDet);

        // 中心格必须对应玩家所在列（采样对齐没偏）
        int centerIdx = (MapField.CELLS / 2) * MapField.CELLS + MapField.CELLS / 2;
        int mid = MapField.BLOCKS_PER_CELL / 2;
        int want = MapField.colorAt(mw, px - MapField.SPAN / 2 + MapField.CELLS / 2 * MapField.BLOCKS_PER_CELL + mid,
                                    pz - MapField.SPAN / 2 + MapField.CELLS / 2 * MapField.BLOCKS_PER_CELL + mid);
        ck("MAP_CENTER", mc[centerIdx] == want,
                String.format("center=0x%06X 期望=0x%06X", mc[centerIdx], want));

        // 水色：人工把一列顶上铺水 → 该格必须按水色画（否则湖会在图上消失）
        int wx = px, wz = pz;
        int wy = mw.surfaceY[wx][wz];
        mw.setBlock(wx, wy + 1, wz, Blocks.WATER.index);
        int waterCol = MapField.colorAt(mw, wx, wz);
        Blocks.Block wb = Blocks.byIndex(Blocks.WATER.index);
        float shW = MapField.heightShade(wy, mw.SY);
        int expW = (Math.min(255, (int) (wb.r * shW)) << 16)
                 | (Math.min(255, (int) (wb.g * shW)) << 8)
                 | Math.min(255, (int) (wb.b * shW));
        ck("MAP_WATER", waterCol == expW, String.format("water=0x%06X 期望=0x%06X", waterCol, expW));

        // 越界安全：贴着世界角落采样，越界格必须是 OUT_OF_WORLD（不崩、不环绕）
        MapField.sample(mw, 0, 0, mc);
        int oow = 0;
        for (int i = 0; i < mc.length; i++) if (mc[i] == MapField.OUT_OF_WORLD) oow++;
        boolean mapEdge = oow > 0 && oow < mc.length;      // 角落应既有界内也有界外
        ck("MAP_EDGE", mapEdge, "角落采样：界外格=" + oow + "/" + mc.length);

        // 高度明暗单调：越高越亮，且在 [0.55, 1.15]
        // 注意用容差：0.55f + 0.60f 在 float 下 ≈ 1.15000004 > 1.15f（写死比较会假失败）
        boolean mapShade = MapField.heightShade(0, 40) < MapField.heightShade(39, 40)
                && MapField.heightShade(0, 40) >= 0.55f - 1e-6f
                && MapField.heightShade(39, 40) <= 1.15f + 1e-6f;
        ck("MAP_SHADE", mapShade, String.format("y0=%.3f y39=%.3f", MapField.heightShade(0, 40), MapField.heightShade(39, 40)));

        // ---- 分帧切片环（Terraria LightingEngine.EngineState 泛化，2026-09-21）----
        // 守护三条：① N 帧内每槽恰好各轮到一次（无饥饿/无重复）② 顺序可复现 ③ 单槽时恒返回 0。
        core.content.FrameSlicer fs = new core.content.FrameSlicer(4);
        int[] hit = new int[4];
        int[] order = new int[16];
        for (int i = 0; i < 16; i++) { order[i] = fs.next(); hit[order[i]]++; }
        boolean fair = true;
        for (int i = 0; i < 4; i++) if (hit[i] != 4) fair = false;
        ck("SLICE_FAIR", fair, "16 帧内每槽次数=" + hit[0] + "/" + hit[1] + "/" + hit[2] + "/" + hit[3]);
        boolean seqOk = true;
        for (int i = 0; i < 16; i++) if (order[i] != i % 4) seqOk = false;
        ck("SLICE_ORDER", seqOk, "顺序 = 0,1,2,3,0,1,...");
        ck("SLICE_ROUNDS", fs.rounds() == 4, "16 帧 / 4 槽 → rounds=" + fs.rounds());
        // 顺序可复现：两个新环逐帧相同
        core.content.FrameSlicer fa = new core.content.FrameSlicer(4);
        core.content.FrameSlicer fb = new core.content.FrameSlicer(4);
        boolean detOk = true;
        for (int i = 0; i < 32; i++) if (fa.next() != fb.next()) detOk = false;
        ck("SLICE_DET", detOk, "同槽位数两次推进逐帧一致");
        // isDue / framesUntil 与游标一致（只读，不推进）
        core.content.FrameSlicer fc = new core.content.FrameSlicer(4);
        ck("SLICE_DUE", fc.isDue(0) && fc.framesUntil(0) == 0 && fc.framesUntil(2) == 2,
                "cursor=0: due(0)=true until(2)=2");
        int c0 = fc.cursor();
        fc.isDue(0); fc.framesUntil(1);
        ck("SLICE_READONLY", fc.cursor() == c0, "isDue/framesUntil 不推进游标");
        // 单槽退化：恒 0（"每帧都做"）
        core.content.FrameSlicer f1 = new core.content.FrameSlicer(1);
        boolean oneOk = true;
        for (int i = 0; i < 8; i++) if (f1.next() != 0) oneOk = false;
        ck("SLICE_SINGLE", oneOk, "槽位=1 时恒返回 0");

        System.out.println("FX " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (27 properties)");
        if (fails > 0) System.exit(1);
    }
}
