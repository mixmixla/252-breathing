package core.systems;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 逐系统仿真耗时统计（诊断；默认关）。
 *
 * <p><b>存在理由</b>：本项目有 99 个系统共用一个 tick 循环，而 {@code World.hashState()} / 门禁都只关心
 * "结果对不对"，从不关心"谁贵"。实测（160×112×160，见 {@code tools/TickCostProbe}）单 tick
 * <b>median 7.6ms</b>，而 60fps 一帧只有 16.67ms —— 即"一帧跑一个 tick 就吃掉 45%"，
 * 且该值随世界年龄 <b>+168%</b>（6000 tick）。**不把 7.6ms 拆到系统级，就只能继续猜。**</p>
 *
 * <p>用法：{@code -Dbw.simdiag=1} 启动，{@code World.tick()} 会为每个系统累计耗时；
 * 由 {@code TickCostProbe} 打印 top-N。关闭时 {@link #ON} 为 false ⇒ 循环里只多一次布尔判断。</p>
 *
 * <p><b>为什么状态是 static 而不是 World 的字段</b>：{@code StateCodec} 会反射写出 World 的
 * <b>全部非 static、非 SKIP 字段</b> —— 把一个诊断容器挂进 World 就等于把它写进存档/快照契约，
 * 还得同步 {@code SnapshotStateTest.SKIP_DOC}，纯属自找麻烦。放静态 ⇒ 天然在所有快照契约之外。</p>
 */
public final class SimProfiler {

    /**
     * 是否启用（启动时读一次属性；默认关 ⇒ 零开销）。
     * ⚠️ 必须写全限定名 {@code java.lang.System} —— **本包内有 {@link System}（仿真系统接口）**，
     * 简单名 `System` 会被它遮蔽（编译期 "找不到符号: getProperty"，很容易看反）。
     */
    public static final boolean ON = "1".equals(java.lang.System.getProperty("bw.simdiag"));

    /** 系统名 → [总纳秒, 调用次数]。 */
    private static final Map<String, long[]> NS = new LinkedHashMap<String, long[]>();

    private SimProfiler() { }

    /** 累加一次调用（由 {@code World.tick} 在启用时调用）。 */
    public static void add(String name, long nanos) {
        long[] v = NS.get(name);
        if (v == null) { v = new long[2]; NS.put(name, v); }
        v[0] += nanos; v[1]++;
    }

    /** 清零（探针在预热后调用，把 JIT 预热排除在统计之外）。 */
    public static void reset() { NS.clear(); }

    /** 参与统计的系统数。 */
    public static int size() { return NS.size(); }

    /** top-N 报告（按总耗时降序）。{@code totalTicks} 用于把总量摊到每 tick。 */
    public static String report(int top, int totalTicks) {
        java.util.List<Map.Entry<String, long[]>> list =
                new java.util.ArrayList<Map.Entry<String, long[]>>(NS.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<Map.Entry<String, long[]>>() {
            @Override public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        long all = 0;
        for (Map.Entry<String, long[]> e : NS.entrySet()) all += e.getValue()[0];
        StringBuilder sb = new StringBuilder();
        int n = Math.min(top, list.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<String, long[]> e = list.get(i);
            double ms = e.getValue()[0] / 1000000.0;
            sb.append(String.format(java.util.Locale.US,
                    "  %-22s total=%9.1fms  perTick=%6.3fms  calls=%d  share=%4.1f%%%n",
                    e.getKey(), ms, ms / Math.max(1, totalTicks), e.getValue()[1],
                    100.0 * e.getValue()[0] / Math.max(1, all)));
        }
        sb.append(String.format(java.util.Locale.US,
                "  ---- \u5408\u8ba1 %.1fms\uff08%d \u4e2a\u7cfb\u7edf\uff1b\u6bcf tick \u5e73\u5747 %.3fms\uff09%n",
                all / 1000000.0, NS.size(), all / 1000000.0 / Math.max(1, totalTicks)));
        return sb.toString();
    }

    /** 拍一份快照（深拷贝）—— 供"首段 vs 末段"对比用（{@link #reset} 会清空原始数据）。 */
    public static Map<String, long[]> snapshot() {
        Map<String, long[]> out = new LinkedHashMap<String, long[]>();
        for (Map.Entry<String, long[]> e : NS.entrySet()) {
            out.put(e.getKey(), new long[]{e.getValue()[0], e.getValue()[1]});
        }
        return out;
    }

    /**
     * 首末段对比：回答「<b>谁随世界年龄变贵</b>」。
     *
     * <p>为什么不能只看"谁最贵"：本项目最贵的系统 {@code sand} 是**全图常量扫描**（每 tick 都扫 71.7 万格），
     * 它的成本**不随年龄变** —— 所以"世界越玩越慢"一定另有其人。排序用<b>每 tick 绝对增量</b>
     * （而不是增长倍数）：倍数会让"从 0.001ms 涨到 0.010ms"的小系统排到最前，而真正拖慢帧的是绝对量。</p>
     *
     * @param first      首段快照（{@link #snapshot}）
     * @param firstTicks 首段的 tick 数
     * @param last       末段快照
     * @param lastTicks  末段的 tick 数
     * @param top        报告前几项
     */
    public static String growthReport(Map<String, long[]> first, int firstTicks,
                                      Map<String, long[]> last, int lastTicks, int top) {
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        names.addAll(first.keySet());
        names.addAll(last.keySet());
        final Map<String, Double> delta = new LinkedHashMap<String, Double>();
        for (String n : names) {
            delta.put(n, perTick(last, n, lastTicks) - perTick(first, n, firstTicks));
        }
        java.util.List<String> list = new java.util.ArrayList<String>(names);
        java.util.Collections.sort(list, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                return Double.compare(delta.get(b), delta.get(a));
            }
        });
        StringBuilder sb = new StringBuilder();
        int n = Math.min(top, list.size());
        for (int i = 0; i < n; i++) {
            String nm = list.get(i);
            double f = perTick(first, nm, firstTicks), l = perTick(last, nm, lastTicks);
            sb.append(String.format(java.util.Locale.US,
                    "  %-22s %6.3fms -> %6.3fms  (%+.3fms%5s)%n",
                    nm, f, l, l - f, f <= 1e-6 ? "" : String.format(java.util.Locale.US, " x%.1f", l / f)));
        }
        double df = 0, dl = 0;
        for (String nm : names) { df += perTick(first, nm, firstTicks); dl += perTick(last, nm, lastTicks); }
        sb.append(String.format(java.util.Locale.US,
                "  ---- \u9996\u6bb5 %.3fms/tick -> \u672b\u6bb5 %.3fms/tick  (%+.3fms, %+.1f%%)%n",
                df, dl, dl - df, 100.0 * (dl - df) / Math.max(1e-9, df)));
        return sb.toString();
    }

    private static double perTick(Map<String, long[]> m, String name, int ticks) {
        long[] v = m.get(name);
        return v == null ? 0.0 : v[0] / 1000000.0 / Math.max(1, ticks);
    }
}
