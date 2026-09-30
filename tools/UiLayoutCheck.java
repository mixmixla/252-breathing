import render.lwjgl.UiLayout;

/**
 * UILAYOUT 门禁（P3，2026-09-23）：HUD 布局引擎的**语义**门禁（纯计算、无 GL、headless）。
 *
 * <p>本门禁要同时证明三件事，缺一不可：
 * <ol>
 *   <li><b>迁移没改坏</b>：{@link UiLayout#solve} 与迁移前的原手写公式<b>逐位等价</b>
 *       （{@code UILAYOUT-ANCHOR}）。这是本次重构的全部理由能被接受的前提 ——
 *       布局换了实现，画面必须一模一样。</li>
 *   <li><b>新能力真的存在</b>：极小窗口下旧式会越界、{@link UiLayout#fit} 不会
 *       （{@code UILAYOUT-OVERFLOW} + {@code UILAYOUT-FIT}）。</li>
 *   <li><b>门禁自己有牙</b>：{@code UILAYOUT-OVERFLOW} 必须在旧式上<b>量到越界</b>。
 *       一条在现状上就通过的"越界检测"是假绿（P2 门禁第一版就是这么废掉的）。</li>
 * </ol>
 *
 * <p><b>关于参照实现</b>：{@link #oldRect} 是迁移前 {@code Game.java} 里那些杂散表达式的
 * 逐字副本（{@code n3 = this.H - 104}、{@code n7 = this.W - 220}、{@code n5 = (this.W - n4) / 2} …），
 * 只作等价性参照，不参与运行时。之所以不直接解析 {@code Game.java}：这些表达式引用了大量
 * 方法内局部变量（{@code f29}/{@code f33}/{@code f32} 等），静态抽取的复杂度远超收益。
 * <b>真正的迁移正确性最终由无头截图逐字节对比证明</b>，本门禁负责在无 GL 环境下把
 * "引擎算错了"这一类错误挡住。
 *
 * <p><b>整数除法陷阱</b>：原式里 {@code int n5 = (this.W - n4) / 2} 与
 * {@code int n8 = (int)((float)this.W / 2.0f - 120.0f)} 是<b>整数截断</b>，
 * 而引擎用浮点。两者在<b>奇数宽度</b>下会差 0.5px。故 {@code UILAYOUT-ANCHOR} 分两档判据：
 * 偶数宽要求逐位相等（{@code < 1e-3}），奇数宽容许亚像素（{@code <= 0.5}）。
 * 无头截图用的分辨率是偶数宽，因此截图为逐字节不变。
 */
public class UiLayoutCheck {

    // ------------------------------------------------------------------
    // 面板表：迁移后的声明式描述 + 迁移前的原式
    // ------------------------------------------------------------------

    /** 一个 HUD 面板的声明式规格。尺寸取「最坏情况」（行数最多、文本最长）。 */
    private static final class Panel {
        final String id;
        final int anchor;
        final float ox;
        final float oy;
        final float w;
        final float h;
        Panel(String id, int anchor, float ox, float oy, float w, float h) {
            this.id = id; this.anchor = anchor; this.ox = ox; this.oy = oy; this.w = w; this.h = h;
        }
    }

    /** 对话面板宽：{@code f29 = Math.min(W - 80, 760)}。 */
    private static float dialogW(float W) { return Math.min(W - 80f, 760f); }
    /** 编年史面板宽：{@code f33 = Math.min(W - 80, 580)}。 */
    private static float storyW(float W) { return Math.min(W - 80f, 580f); }

    private static Panel[] panels(float W, float H) {
        return new Panel[]{
            new Panel("hud-status",    UiLayout.TL, 14f,  14f, 244f,             66f),
            new Panel("hud-chronicle", UiLayout.TL, 14f,  88f, 520f,             176f),
            new Panel("hud-soul",      UiLayout.BL, 14f,  78f, 220f,             26f),
            new Panel("hud-clock",     UiLayout.TC, 0f,   12f, 306f,             42f),
            // ⚠️ TR 的 ox 是「**右**边距」不是「左偏移」：旧式 x = W - 220 且 w = 206
            //    ⇒ 右边距 = W - (W-220) - 206 = 14。（首版写成 220，门禁当场抓到 206px 偏差。）
            new Panel("hud-skills",    UiLayout.TR, 14f,  14f, 206f,             104f),
            new Panel("hud-lockon",    UiLayout.TC, 0f,   66f, 240f,             44f),
            new Panel("hud-boss",      UiLayout.TC, 0f,   88f, 446f,             50f),
            new Panel("hud-tutorial",  UiLayout.BR, 16f,  76f, 214f,             264f),
            new Panel("hud-dialogue",  UiLayout.BC, 0f,  160f, dialogW(W),       110f),
            new Panel("hud-story",     UiLayout.TR, 20f, 210f, storyW(W),        181f),
            new Panel("hud-minimap",   UiLayout.TR, 9f,    9f, 106f,             106f),
        };
    }

    /**
     * 迁移前 {@code Game.java} 里的**原式逐字副本**（返回 {x, y, w, h}）。
     *
     * <p>逐个对照：
     * <ul>
     *   <li>status {@code panel(buf, 14, 14, 244, 66)}</li>
     *   <li>chronicle {@code panel(buf, 14, 88, 520, 176)}</li>
     *   <li>soul {@code int n3 = H - 104; panel(buf, 14, n3, 220, 26)}</li>
     *   <li>clock {@code int n4 = 306; int n5 = (W - n4) / 2; panel(buf, n5, 12, n4, 42)}</li>
     *   <li>skills {@code int n7 = W - 220; panel(buf, n7, 14, 206, 104)}</li>
     *   <li>lockon {@code int n8 = (int)((float)W / 2f - (n/2)); panel(buf, n8, 66, 240, 44)}</li>
     *   <li>boss {@code int xBoss = (W - 430) / 2; panel(buf, xBoss - 8, 88, 430 + 16, 50)}</li>
     *   <li>tutorial {@code f2 = W - 214 - 16; f = H - f32 - 76; panel(buf, f2, f, 214, f32)}
     *       （{@code f32 = 10*2 + 16 + TUT_KEY.length*19 = 264}）</li>
     *   <li>dialogue {@code f32 = (W - f29)/2; f2 = H - 160 - f4; panel(buf, f32, f2, f29, f4)}
     *       （{@code f4 = 30 + 4*20 = 110}）</li>
     *   <li>story {@code f34 = W - f33 - 20; f4 = 210; panel(buf, f34, f4, f33, f)}
     *       （{@code f = 48 + 7*19 = 181}）</li>
     *   <li>minimap {@code x0 = W - 96 - 14; y0 = 14; panel(buf, x0 - 5, y0 - 5, 96 + 10, 96 + 10)}</li>
     * </ul>
     */
    private static float[] oldRect(String id, float W, float H) {
        int iW = (int) W;
        float f29 = dialogW(W);
        float f33 = storyW(W);
        switch (id) {
            case "hud-status":    return new float[]{14f, 14f, 244f, 66f};
            case "hud-chronicle": return new float[]{14f, 88f, 520f, 176f};
            case "hud-soul":      return new float[]{14f, H - 104f, 220f, 26f};
            case "hud-clock":     return new float[]{(float) ((iW - 306) / 2), 12f, 306f, 42f};
            case "hud-skills":    return new float[]{iW - 220f, 14f, 206f, 104f};
            case "hud-lockon":    return new float[]{(float) (int) ((float) iW / 2f - 120f), 66f, 240f, 44f};
            case "hud-boss":      return new float[]{(float) ((iW - 430) / 2 - 8), 88f, 446f, 50f};
            case "hud-tutorial":  return new float[]{iW - 214f - 16f, H - 264f - 76f, 214f, 264f};
            case "hud-dialogue":  return new float[]{(W - f29) / 2f, H - 160f - 110f, f29, 110f};
            case "hud-story":     return new float[]{W - f33 - 20f, 210f, f33, 181f};
            case "hud-minimap":   return new float[]{iW - 110f - 5f, 14f - 5f, 106f, 106f};
            default: throw new IllegalArgumentException(id);
        }
    }

    // ------------------------------------------------------------------

    private static int fails = 0;

    public static void main(String[] args) {
        System.out.println("UILAYOUTCHECK  HUD 布局引擎语义门禁（无 GL / headless）");

        // ---- 1. 锚点等价性：引擎求解 == 迁移前原式 ----
        // 覆盖：常见桌面分辨率 + 奇数宽度（暴露整数截断）+ 超宽 + 极小
        float[][] sizes = {
            {640f, 360f}, {800f, 600f}, {1024f, 768f}, {1280f, 720f},
            {1366f, 768f}, {1600f, 900f}, {1920f, 1080f}, {2560f, 1080f},
            {1281f, 721f},   // 奇数宽：整数截断语义对照
        };
        int worstExact = 0, worstSub = 0, checked = 0;
        String worstSubId = "-";
        float worstSubD = 0f;
        for (float[] sz : sizes) {
            float W = sz[0], H = sz[1];
            boolean even = ((int) W) % 2 == 0;
            for (Panel p : panels(W, H)) {
                UiLayout.Rect r = UiLayout.solve(p.id, p.anchor, p.ox, p.oy, p.w, p.h, W, H);
                float[] o = oldRect(p.id, W, H);
                float d = Math.max(Math.max(Math.abs(r.x - o[0]), Math.abs(r.y - o[1])),
                                   Math.max(Math.abs(r.w - o[2]), Math.abs(r.h - o[3])));
                checked++;
                if (even) {
                    if (d > 1e-3f) { worstExact++; System.out.println("  ✗ 偶数宽不等价 " + p.id + " @" + W + "x" + H + " diff=" + d); }
                } else {
                    if (d > 0.5f) { worstSub++; System.out.println("  ✗ 奇数宽超亚像素 " + p.id + " @" + W + "x" + H + " diff=" + d); }
                    if (d > worstSubD) { worstSubD = d; worstSubId = p.id; }
                }
            }
        }
        boolean anchorOk = (worstExact == 0 && worstSub == 0);
        System.out.println("UILAYOUT-ANCHOR " + (anchorOk ? "PASS" : "FAIL")
                + "  " + checked + " 项（9 分辨率 × 11 面板）偶数宽逐位相等，奇数宽最大偏差 "
                + fmt(worstSubD) + "px (" + worstSubId + ")  want<=0.5");
        if (!anchorOk) fails++;

        // ---- 2. 钳制幂等：正常分辨率下 fit 必须与 solve 逐位相同 ----
        // 这是"迁移不改变画面"的量化形式：钳制只在溢出时才可以生效。
        int idemBad = 0;
        float[][] normal = {{1024f, 768f}, {1280f, 720f}, {1366f, 768f}, {1600f, 900f}, {1920f, 1080f}};
        for (float[] sz : normal) {
            for (Panel p : panels(sz[0], sz[1])) {
                UiLayout.Rect a = UiLayout.solve(p.id, p.anchor, p.ox, p.oy, p.w, p.h, sz[0], sz[1]);
                UiLayout.Rect b = UiLayout.fit(p.id, p.anchor, p.ox, p.oy, p.w, p.h, sz[0], sz[1]);
                if (Math.abs(a.x - b.x) > 1e-4f || Math.abs(a.y - b.y) > 1e-4f
                        || Math.abs(a.w - b.w) > 1e-4f || Math.abs(a.h - b.h) > 1e-4f) {
                    idemBad++;
                    System.out.println("  ✗ 钳制在正常分辨率下改变了布局：" + p.id + " @" + sz[0] + "x" + sz[1]);
                }
            }
        }
        boolean idemOk = idemBad == 0;
        System.out.println("UILAYOUT-IDEMPOTENT " + (idemOk ? "PASS" : "FAIL")
                + "  正常分辨率下 fit==solve（共 " + (normal.length * 11) + " 项，差异 " + idemBad + "）");
        if (!idemOk) fails++;

        // ---- 3. 旧式越界检测：必须有牙（在极小窗口下必须量到越界）----
        // 若这条在旧式上也 PASS，说明窗口还不够小 → 检测是假绿。
        float[][] tiny = {{640f, 360f}, {480f, 320f}, {320f, 240f}};
        int oldViolations = 0, oldViolPanels = 0;
        StringBuilder oldNames = new StringBuilder();
        for (float[] sz : tiny) {
            for (Panel p : panels(sz[0], sz[1])) {
                float[] o = oldRect(p.id, sz[0], sz[1]);
                boolean bad = o[0] < 0f || o[1] < 0f || o[0] + o[2] > sz[0] || o[1] + o[3] > sz[1];
                if (bad) {
                    oldViolations++;
                    oldViolPanels++;
                    if (oldNames.indexOf(p.id) < 0) oldNames.append(p.id).append(' ');
                }
            }
        }
        boolean detectOk = oldViolations >= 3;
        System.out.println("UILAYOUT-OVERFLOW " + (detectOk ? "PASS" : "FAIL")
                + "  旧式在极小窗口下越界 " + oldViolations + " 项 / 涉及 " + oldNames
                + "  (want>=3，否则检测无牙)");
        if (!detectOk) fails++;

        // ---- 4. fit 之后必须全部落在屏幕内（含 1×1 退化画布）----
        float[][] all = new float[tiny.length + normal.length][];
        System.arraycopy(tiny, 0, all, 0, tiny.length);
        System.arraycopy(normal, 0, all, tiny.length, normal.length);
        int fitBad = 0, fitTotal = 0;
        for (float[] sz : all) {
            for (Panel p : panels(sz[0], sz[1])) {
                UiLayout.Rect r = UiLayout.fit(p.id, p.anchor, p.ox, p.oy, p.w, p.h, sz[0], sz[1]);
                fitTotal++;
                if (!r.inside(sz[0], sz[1])) {
                    fitBad++;
                    System.out.println("  ✗ fit 后仍越界 " + r + " 画布=" + sz[0] + "x" + sz[1]);
                }
            }
        }
        // 1×1 退化画布：不应抛异常，且结果为空盒
        UiLayout.Rect degenerate = UiLayout.fit("degenerate", UiLayout.TR, 220f, 14f, 206f, 104f, 1f, 1f);
        boolean degenerateOk = degenerate.w <= 1.0001f && degenerate.h <= 1.0001f
                && degenerate.x >= 0f && degenerate.y >= 0f;
        boolean fitOk = (fitBad == 0) && degenerateOk;
        System.out.println("UILAYOUT-FIT " + (fitOk ? "PASS" : "FAIL")
                + "  钳制后 " + fitTotal + " 项全部在屏内（越界 " + fitBad + "）+ 1×1 退化画布 "
                + (degenerateOk ? "安全收缩" : "异常"));
        if (!fitOk) fails++;

        // ---- 5. 重叠诊断 + 回归上限 ----
        // ⚠️ 这 5 对重叠是**迁移前就存在的存量问题**（P3 只做检测与回归护栏，不在本轮修它们，
        //    因为修法涉及设计决策：Boss 血条该不该从顶部挪到别处、村志面板该不该收窄）。
        //    门禁的作用是：① 把它们量化；② 不许变多。修完这些冲突后应把基线收紧到 0。
        float W = 1280f, H = 720f;
        Panel[] ps = panels(W, H);
        UiLayout.Rect[] rs = new UiLayout.Rect[ps.length];
        for (int i = 0; i < ps.length; i++) {
            rs[i] = UiLayout.solve(ps[i].id, ps[i].anchor, ps[i].ox, ps[i].oy, ps[i].w, ps[i].h, W, H);
        }
        int overlapPairs = 0;
        StringBuilder pairs = new StringBuilder();
        for (int i = 0; i < rs.length; i++) {
            for (int j = i + 1; j < rs.length; j++) {
                float a = rs[i].overlapArea(rs[j]);
                if (a > 0.5f) {          // 忽略亚像素接触
                    overlapPairs++;
                    float ratio = a / Math.min(rs[i].area(), rs[j].area());
                    pairs.append("\n      ").append(rs[i].id).append(" ∩ ").append(rs[j].id)
                         .append(String.format(java.util.Locale.US, "  面积 %.0f px² (占较小者 %.1f%%)", a, ratio * 100f));
                }
            }
        }
        final int OVERLAP_BASELINE = 5;
        boolean overlapOk = overlapPairs <= OVERLAP_BASELINE;
        System.out.println("UILAYOUT-OVERLAP " + (overlapOk ? "PASS" : "FAIL")
                + "  " + (int) W + "x" + (int) H + " 下重叠对 " + overlapPairs + " (存量基线 " + OVERLAP_BASELINE + "，不许变多)"
                + pairs);
        if (!overlapOk) fails++;

        // ---- 5b. 迁移完整性：Game.java 里不许再有裸坐标 panel 调用 ----
        // 直接解析源码（不抄副本）。判据：每个 `this.panel(this.hudBuf, X, ...)` 的 X
        // 必须是一个布局矩形引用（含 ".x"）。漏迁移一处即 FAIL。
        int rawPanelCalls = 0;
        StringBuilder rawList = new StringBuilder();
        try {
            String root = System.getProperty("bw.root", ".");
            String src = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(root, "src/render/lwjgl/Game.java")),
                    java.nio.charset.StandardCharsets.UTF_8);
            String needle = "this.panel(this.hudBuf,";
            int at = 0;
            while ((at = src.indexOf(needle, at)) >= 0) {
                at += needle.length();
                int end = at;
                while (end < src.length() && src.charAt(end) != ',') end++;
                String first = src.substring(at, Math.min(end, at + 40)).trim();
                if (first.indexOf(".x") < 0) {
                    rawPanelCalls++;
                    if (rawList.length() < 300) rawList.append(" [").append(first).append(']');
                }
            }
        } catch (Exception ex) {
            System.out.println("  ✗ 源码解析失败（防假绿，判 FAIL）：" + ex);
            rawPanelCalls = -1;
        }
        boolean migrationOk = rawPanelCalls == 0;
        System.out.println("UILAYOUT-MIGRATION " + (migrationOk ? "PASS" : "FAIL")
                + "  Game.java 内 " + (rawPanelCalls < 0 ? "解析失败" : ("裸坐标 panel 调用 " + rawPanelCalls + " 处"))
                + "  (要求 0；全部须经 UiLayout 求解)" + rawList);
        if (!migrationOk) fails++;

        // ---- 6. Stack 等价性：自动累加 == 手写 y 步进 ----
        UiLayout.Stack st = new UiLayout.Stack(UiLayout.TL, 14f, 14f, 8f, W, H);
        UiLayout.Rect a1 = st.next("hud-status", 244f, 66f);
        UiLayout.Rect a2 = st.next("hud-chronicle", 520f, 176f);
        boolean stackEq = Math.abs(a1.y - 14f) < 1e-4f && Math.abs(a2.y - 88f) < 1e-4f
                && Math.abs(a1.x - 14f) < 1e-4f && Math.abs(a2.x - 14f) < 1e-4f;
        // 改高度后，第二项必须自动下移（原手写版本会漏改）
        UiLayout.Stack st2 = new UiLayout.Stack(UiLayout.TL, 14f, 14f, 8f, W, H);
        st2.next("hud-status", 244f, 80f);              // 高度 66 → 80
        UiLayout.Rect b2 = st2.next("hud-chronicle", 520f, 176f);
        boolean stackAuto = Math.abs(b2.y - 102f) < 1e-4f;   // 14 + 80 + 8 = 102
        boolean stackOk = stackEq && stackAuto;
        System.out.println("UILAYOUT-STACK " + (stackOk ? "PASS" : "FAIL")
                + "  Stack 序列 y=(" + fmt(a1.y) + "," + fmt(a2.y) + ") 对标手写 (14,88)；"
                + "首项加高后第二项自动 y=" + fmt(b2.y) + " (want 102)");
        if (!stackOk) fails++;

        // ---- 7. 命中测试（为后续拖拽/点击留接口，先证语义正确）----
        UiLayout.Rect hit = UiLayout.solve("hit", UiLayout.TL, 14f, 14f, 244f, 66f, W, H);
        boolean hitOk = hit.contains(14f, 14f)          // 左上闭
                && hit.contains(257.9f, 79.9f)          // 右下开口前
                && !hit.contains(258f, 40f)             // 右边界外
                && !hit.contains(100f, 80f)             // 底边界外
                && !hit.contains(13.9f, 40f);           // 左边界外
        System.out.println("UILAYOUT-HIT " + (hitOk ? "PASS" : "FAIL")
                + "  contains() 左闭右开语义（角落 4 例 + 越界 3 例）");
        if (!hitOk) fails++;

        if (fails > 0) {
            System.out.println("UILAYOUTCHECK FAIL  (" + fails + " 项失败)");
            System.exit(1);
        }
        System.out.println("UILAYOUTCHECK PASS");
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }
}
