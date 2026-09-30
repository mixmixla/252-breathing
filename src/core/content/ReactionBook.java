package core.content;

import core.world.Blocks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 反应表（由 {@link ReactionDef} 展开、按方块索引建成的<b>查表结构</b>）—— 求解器的唯一输入。
 *
 * <p><b>为什么是查表而不是遍历规则列表</b>：反应判定发生在仿真热路径上（每个候选格 × 6 邻居）。
 * 若对每个组合遍历规则列表，成本随规则数线性增长；这里把「材料对 → 规则」压成一张
 * {@code n*n} 的 {@code int[]} —— 运行期 <b>O(1)</b> 且零分配、零字符串比较。
 *
 * <p><b>标签展开（加载期做一次）</b>：{@code "[ice]"} 会被展开成所有带 {@code ice} 标签的方块
 * （按方块索引升序 → <b>确定性</b>，与文件系统枚举顺序无关）。
 * 这正是 Noita「一条规则覆盖一整类材料」的落地方式：
 * {@code melt.json} 的 {@code "[ice]"} 一句同时覆盖 SNOW 与 ICE 两种材料。
 *
 * <p><b>无序对</b>：规则定义的是无序材料对。查表时先按 {@code (a,b)} 找，找不到再按
 * {@code (b,a)} 找并记下 {@code flipped} —— 求解器据此决定"哪一格用 out1、哪一格用 out2"。
 * 这样表里写 {@code FIRE + WATER} 还是 {@code WATER + FIRE} 都一样，避免顺序陷阱。
 *
 * <p><b>零漂移</b>：本类只在加载期构建，纯函数式展开，无 RNG、不碰网格。
 * 是否真正执行反应由 {@code WorldConfig.reactionTable} 决定。
 */
public final class ReactionBook {

    private final int n;              // 方块总数
    private final int[] ruleOf;       // [a*n+b] -> 规则下标；-1 = 无规则
    private final byte[] flipped;     // [a*n+b] = 1 表示查到的是 (in2,in1) 顺序
    private final int[] outA;         // 规则 -> 「匹配 in1 的那一格」的产物；-1 = 不变
    private final int[] outB;         // 规则 -> 「匹配 in2 的那一格」的产物；-1 = 不变
    private final float[] prob;
    private final String[] rate;      // 规则 -> 概率再乘的预设参数名；null = 不缩放
    private final boolean[] reactive; // 该材料是否出现在任一规则的输入侧
    private final ReactionDef[] defs;
    private final List<String> dropped;

    private ReactionBook(int n, int[] ruleOf, byte[] flipped, int[] outA, int[] outB,
                         float[] prob, String[] rate, boolean[] reactive,
                         ReactionDef[] defs, List<String> dropped) {
        this.n = n; this.ruleOf = ruleOf; this.flipped = flipped;
        this.outA = outA; this.outB = outB; this.prob = prob; this.rate = rate;
        this.reactive = reactive; this.defs = defs; this.dropped = dropped;
    }

    /** 空表（无内容 / 未挂载时）：任何查询都返回"无规则"，求解器首行即返回。 */
    public static ReactionBook empty(int blockCount) {
        int[] ruleOf = new int[blockCount * blockCount];
        // **必须填 -1**：int[] 默认值是 0，若不填则空表会谎称"每个材料对都命中规则 #0"。
        // 求解器虽有 isEmpty() 前置返回挡着，但这个谎言一旦被别的消费者采信就是静默错行为 ——
        // 是 REACTION-BARE-SAFE 断言把它抓出来的（2026-09-18）。
        Arrays.fill(ruleOf, -1);
        return new ReactionBook(blockCount, ruleOf,
                new byte[blockCount * blockCount], new int[0], new int[0], new float[0],
                new String[0], new boolean[blockCount], new ReactionDef[0],
                Collections.<String>emptyList());
    }

    /**
     * 展开规则表。
     *
     * @param list        已通过字段级校验的规则（顺序 = 文件名字典序，确定性）
     * @param mats        材料书（用于按标签查有哪些方块）
     * @param blockCount  方块总数
     * @param errors      引用级错误收集器（未知方块 / 未知标签 / 无匹配材料的标签）
     */
    public static ReactionBook from(List<ReactionDef> list, MaterialBook mats,
                                    int blockCount, List<String> errors) {
        int n = blockCount;
        int[] ruleOf = new int[n * n];
        Arrays.fill(ruleOf, -1);
        byte[] flipped = new byte[n * n];
        boolean[] reactive = new boolean[n];
        List<String> dropped = new ArrayList<String>();

        List<ReactionDef> ok = new ArrayList<ReactionDef>();
        if (list != null) {
            for (int k = 0; k < list.size(); k++) {
                ReactionDef r = list.get(k);
                if (r == null) continue;
                int[] a = expand(r.in1, mats, n, r, errors);
                int[] b = expand(r.in2, mats, n, r, errors);
                int o1 = resolve(r.out1, r, errors);
                int o2 = r.out2 == null ? -1 : resolve(r.out2, r, errors);
                if (a == null || b == null || o1 < 0 || (r.out2 != null && o2 < 0)) {
                    dropped.add(r.id);
                    continue;
                }
                ok.add(r);
                int idx = ok.size() - 1;
                for (int i = 0; i < a.length; i++) {
                    for (int j = 0; j < b.length; j++) {
                        reactive[a[i]] = true;
                        reactive[b[j]] = true;
                        put(ruleOf, flipped, n, a[i], b[j], idx, (byte) 0);
                        put(ruleOf, flipped, n, b[j], a[i], idx, (byte) 1);
                    }
                }
            }
        }

        int m = ok.size();
        int[] outA = new int[m];
        int[] outB = new int[m];
        float[] prob = new float[m];
        String[] rate = new String[m];
        ReactionDef[] defs = new ReactionDef[m];
        for (int i = 0; i < m; i++) {
            ReactionDef r = ok.get(i);
            outA[i] = resolveBlock(r.out1);
            outB[i] = r.out2 == null ? -1 : resolveBlock(r.out2);
            prob[i] = r.probability;
            rate[i] = r.rate;
            defs[i] = r;
        }
        return new ReactionBook(n, ruleOf, flipped, outA, outB, prob, rate, reactive, defs, dropped);
    }

    /** 写表：先到先得（同一材料对只保留第一条规则 —— 顺序=文件名序，确定性）。 */
    private static void put(int[] ruleOf, byte[] flipped, int n, int a, int b, int idx, byte flip) {
        int k = a * n + b;
        if (ruleOf[k] == -1) { ruleOf[k] = idx; flipped[k] = flip; }
    }

    /** 把一个输入（方块 id 或 "[tag]"）展开成方块索引数组；失败返回 {@code null}。 */
    private static int[] expand(String spec, MaterialBook mats, int n,
                                ReactionDef r, List<String> errors) {
        String tag = ReactionDef.tagOf(spec);
        if (tag != null) {
            if (!Arrays.asList(MaterialDef.TAGS).contains(tag)) {
                errors.add("reaction " + r.id + ": unknown tag '[" + tag + "] (known: "
                        + Arrays.asList(MaterialDef.TAGS) + ")");
                return null;
            }
            int[] tmp = new int[n];
            int c = 0;
            for (int i = 0; i < n; i++) {            // 按索引升序 → 确定性
                MaterialDef d = (mats == null) ? null : mats.get(i);
                if (d != null && d.hasTag(tag)) tmp[c++] = i;
            }
            if (c == 0) {
                errors.add("reaction " + r.id + ": tag '[" + tag + "] matches no material (dead rule)");
                return null;
            }
            return Arrays.copyOf(tmp, c);
        }
        int idx = resolveBlock(spec);
        if (idx < 0) {
            errors.add("reaction " + r.id + ": unknown block id '" + spec + "'");
            return null;
        }
        return new int[] { idx };
    }

    private static int resolve(String blockId, ReactionDef r, List<String> errors) {
        int idx = resolveBlock(blockId);
        if (idx < 0) {
            errors.add("reaction " + r.id + ": unknown output block id '" + blockId + "'");
            return -1;
        }
        return idx;
    }

    /**
     * 方块 id → 索引；未知返回 {@code -1}。
     *
     * <p><b>为什么不能用 {@code Blocks.index(id)}</b>：它对未知 id 是<b>抛异常</b>
     * （{@code IllegalArgumentException}）而不是返回 -1 —— 内容层要用"收集错误"的方式
     * 报告坏内容，抛异常会把门禁直接打崩（REACTION-LOUD 第一版就踩了这个坑）。
     */
    private static int resolveBlock(String id) {
        if (id == null || id.isEmpty()) return -1;
        if (!Blocks.has(id)) return -1;
        Blocks.Block b = Blocks.byId(id);
        return (b == null) ? -1 : b.index;
    }

    /** 规则条数。 */
    public int size() { return defs.length; }
    public boolean isEmpty() { return defs.length == 0; }

    /** 因引用错误被丢弃的规则 id（门禁要断言它为空）。 */
    public List<String> dropped() { return dropped; }

    /** 该材料是否参与任何反应（求解器用它做快速跳过）。 */
    public boolean reactive(int mat) {
        return mat >= 0 && mat < n && reactive[mat];
    }

    /** 查规则；返回下标或 -1。 */
    public int ruleFor(int a, int b) {
        if (a < 0 || b < 0 || a >= n || b >= n) return -1;
        return ruleOf[a * n + b];
    }

    /** 该次查表是否命中了「反序」配对（决定 out1/out2 各写哪一格）。 */
    public boolean flippedFor(int a, int b) {
        if (a < 0 || b < 0 || a >= n || b >= n) return false;
        return flipped[a * n + b] == 1;
    }

    /** 规则命中时，「in1 侧」那格应变成的方块；-1 = 不变。 */
    public int outFirst(int rule) { return outA[rule]; }
    /** 规则命中时，「in2 侧」那格应变成的方块；-1 = 不变。 */
    public int outSecond(int rule) { return outB[rule]; }
    public float probability(int rule) { return prob[rule]; }
    /** 规则命中时概率要再乘的预设参数名；null = 不缩放。 */
    public String rateKey(int rule) { return rate[rule]; }
    public ReactionDef def(int rule) { return defs[rule]; }

    /** 全部规则摘要（门禁 / 诊断输出用）。 */
    public List<String> summaries() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < defs.length; i++) out.add(defs[i].summary());
        return out;
    }
}
