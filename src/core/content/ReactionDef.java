package core.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 材料反应规则（{@code assets/content/reactions/<ID>.json}）—— 内容平台第 14 类。
 *
 * <p><b>为什么需要它（2026-09-18 批 C）</b>：学到 Noita 的两个架构秘密之后（见
 * {@code docs/NOITA_STUDY.md} §3、{@code docs/NOITA_LESSONS.md}），我们已有
 * 「材料规格表」（第 13 类：每种材料的 {@code cellType/density/tags}），
 * 但<b>材料之间的相互作用仍散落在 90+ 个系统里各写一遍</b> —— 例如灭火、融雪
 * 这类"A 挨着 B 就变 C"的逻辑，要么没有实现，要么写死在某个系统的概率分支里。
 *
 * <p>本类把<b>材料反应</b>搬进数据表：<b>一条规则 = 一对输入 + 产物 + 概率</b>，
 * 求解器只有一份（{@code core.systems.ReactionSystem}）。
 * 于是「新增一种反应」= 加一个 JSON，而不是再写一个 System。
 *
 * <p><b>标签查询（核心技巧）</b>：输入可以是 {@code "[tag]"} 形式，加载期展开成
 * 所有带该标签的材料。这就是 Noita「用 77 个标签让 328 条反应覆盖全组合」的秘密 ——
 * 本项目里 {@code melt.json} 一句 {@code "[ice]"} 就同时覆盖了 SNOW 与 ICE。
 *
 * <p>schema：
 * <pre>
 * {
 *   "name":        "Quench",      // 显示名（默认 id）
 *   "in1":         "[fire]",      // 输入 1：方块 id 或 "[tag]"
 *   "in2":         "[liquid]",    // 输入 2：同上（与 in1 构成<b>无序</b>对）
 *   "out1":        "AIR",         // 命中时"匹配 in1 的那一格"变成什么（必填）
 *   "out2":        "WATER",       // 可选：匹配 in2 的那一格变成什么；省略 = 不变
 *   "probability": 0.9            // 每 tick 命中时的触发概率（0 &lt; p &lt;= 1，默认 1.0）
 *   "rate":        "erosionRate"  // 可选：概率再乘「某个预设参数」（仅已知 knob 生效）
 * }
 * </pre>
 *
 * <p><b>rate（参数旋钮，2026-09-18 批 D）</b>：把反应概率再乘以一个<b>预设参数</b>
 * 的值。例如侵蚀规则写 {@code "rate":"erosionRate"} —— 出厂默认 1.0 → 概率不变；
 * 预设调低 → 侵蚀变慢；调到 0 → 该反应彻底不发生。这样"被收编进反应表的系统"仍能保留
 * 它原先由预设参数驱动的手感（见 {@code SubsystemTest.EROSION_RATE}）。未知 knob 名 → 不缩放
 * （内容写错也不致反应崩坏，只是退化为固定概率）。
 *
 * <p><b>零漂移</b>：本类纯数据解析，无 RNG、不碰网格。
 * 求解器的随机只走 {@code World.simStream("reaction")} 派生流（不消耗主 rng），
 * 且由 {@code WorldConfig.reactionTable} 开关守护 —— 出厂值变更时才需重锁基线。
 */
public final class ReactionDef {

    /** 缺省触发概率。 */
    public static final float DEFAULT_PROBABILITY = 1.0f;

    public final String id;        // = 文件名
    public final String name;
    public final String in1;       // 方块 id 或 "[tag]"
    public final String in2;
    public final String out1;      // in1 侧产物（必填）
    public final String out2;      // in2 侧产物；null = 不变
    public final float probability;
    /** 可选：概率再乘的预设参数名（见类注释）；null = 不缩放。 */
    public final String rate;

    private ReactionDef(String id, String name, String in1, String in2,
                        String out1, String out2, float probability, String rate) {
        this.id = id; this.name = name; this.in1 = in1; this.in2 = in2;
        this.out1 = out1; this.out2 = out2; this.probability = probability;
        this.rate = rate;
    }

    /** 该输入是不是标签形式（{@code "[xxx]"}）。 */
    public static boolean isTag(String s) {
        return s != null && s.length() > 2 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']';
    }

    /** 取标签名（去方括号）；非标签形式返回 {@code null}。 */
    public static String tagOf(String s) {
        return isTag(s) ? s.substring(1, s.length() - 1) : null;
    }

    /**
     * 解析一条反应规则；任何字段非法 → 记错误并返回 {@code null}（该规则不进书）。
     *
     * <p>字段级校验在这里；<b>引用级校验</b>（方块 id 是不是真方块、标签是不是已知标签）
     * 在 {@link ContentRegistry} 里做 —— 因为那需要看到其它内容表。
     */
    public static ReactionDef parse(String id, JsonObject o, List<String> errors) {
        if (o == null) { errors.add("reaction " + id + ": null definition"); return null; }

        String name = str(o, "name", id);

        String in1 = str(o, "in1", null);
        String in2 = str(o, "in2", null);
        if (in1 == null || in1.isEmpty()) { errors.add("reaction " + id + ": missing in1"); return null; }
        if (in2 == null || in2.isEmpty()) { errors.add("reaction " + id + ": missing in2"); return null; }

        String out1 = str(o, "out1", null);
        String out2 = str(o, "out2", null);
        if (out1 == null || out1.isEmpty()) {
            errors.add("reaction " + id + ": at least out1 is required");
            return null;
        }
        // 两侧都不变 = 死规则（看起来接线了其实什么都没做）→ 响亮拒绝，别留着骗人。
        if (out2 != null && out2.isEmpty()) out2 = null;

        float prob = num(o, "probability", DEFAULT_PROBABILITY);
        if (!(prob > 0f) || prob > 1f || Float.isNaN(prob) || Float.isInfinite(prob)) {
            errors.add("reaction " + id + ": probability must be in (0,1] (got " + prob + ")");
            return null;
        }

        // 可选 rate 旋钮：仅当是已知 knob 名才接受（拼写错会被拒绝，而非悄悄退化）。
        String rate = str(o, "rate", null);
        if (rate != null && rate.isEmpty()) rate = null;
        if (rate != null && !KNOWN_RATES.contains(rate)) {
            errors.add("reaction " + id + ": unknown rate knob '" + rate
                    + "' (known: " + KNOWN_RATES + ")");
            return null;
        }

        return new ReactionDef(id, name, in1, in2, out1, out2, prob, rate);
    }

    /** 已知的 rate 旋钮（与 {@code WorldConfig} 的字段一一对应）。 */
    public static final java.util.Set<String> KNOWN_RATES =
            java.util.Collections.singleton("erosionRate");

    /** 人类可读摘要（门禁报告 / 覆盖清单用）。 */
    public String summary() {
        return name + ": " + in1 + " + " + in2 + " -> "
                + out1 + (out2 == null ? "" : " + " + out2)
                + "  p=" + probability + (rate == null ? "" : " x" + rate);
    }

    /** 这条规则涉及的所有"待解析引用"（方块 id 与标签），供注册表做交叉校验。 */
    public List<String> references() {
        List<String> out = new ArrayList<String>();
        if (!isTag(in1)) out.add(in1);
        if (!isTag(in2)) out.add(in2);
        out.add(out1);
        if (out2 != null) out.add(out2);
        return out;
    }

    // ---- 小工具（与 MaterialDef / ItemDef 的解析风格一致）----

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }
    private static float num(JsonObject o, String k, float def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsFloat() : def;
    }
}
