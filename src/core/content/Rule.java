package core.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 玩法规则（Rule）—— 把「玩法」表达成 <b>ECA</b>：
 * {@code on}(事件) → {@code if}(条件) → {@code then}(动作)。
 *
 * <p>这是「所有玩法都能配置进来」的通用表达。举例：
 * <ul>
 *   <li>捕捉：{@code on = BEAST_WEAKENED, if = [野兽血量 < 30%], then = [捕获/失败]}</li>
 *   <li>自动化：{@code on = HARVEST_DONE, if = [有箱子], then = [搬运产出]}</li>
 *   <li>科技树：{@code on = BUILD_DONE, if = [已解锁 X], then = [解锁配方]}</li>
 * </ul>
 *
 * <p><b>关键纪律：动作不发明新语言</b> —— {@code then} 里直接写内容层的 13 条
 * {@link Effect} 指令。所以「玩法」和「内容」共用同一套原子，规则引擎不需要懂游戏。
 *
 * <p><b>零 RNG</b>：条件里的 {@code CHANCE} 走 {@link EffectQueue#hash01} 纯哈希，
 * 不碰 {@code simStream}。规则再多也不扰动仿真 RNG 流。
 */
public final class Rule {

    /** 条件类型白名单（5 种）。不在其中的 check 在加载期被拒绝。 */
    public static final Set<String> CHECKS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "HAS_SKILL", "HAS_ITEM", "SCALAR_AT_LEAST", "CHANCE", "EVENT_FIELD")));

    /** 一条条件。字段复用：{@code value} = 阈值/概率，{@code name} = 标量名/字段名。 */
    public static final class Check {
        public final String kind;
        public final String id;      // HAS_SKILL / HAS_ITEM 的目标
        public final String name;    // SCALAR_AT_LEAST 的标量名、EVENT_FIELD 的字段名
        public final String eq;      // EVENT_FIELD 的期望值
        public final float value;    // 阈值 / 概率 / 数量

        private Check(String kind, String id, String name, String eq, float value) {
            this.kind = kind; this.id = id; this.name = name; this.eq = eq; this.value = value;
        }

        public static Check parse(JsonObject o) {
            if (o == null) return null;
            String k = str(o, "check", "");
            if (!CHECKS.contains(k)) return null;
            return new Check(k, str(o, "id", ""), str(o, "name", ""), str(o, "eq", ""),
                    num(o, "value", num(o, "p", num(o, "count", 0f))));
        }

        public String describe() {
            if ("CHANCE".equals(kind)) return kind + "(" + value + ")";
            if ("SCALAR_AT_LEAST".equals(kind)) return kind + "(" + name + ">=" + value + ")";
            if ("EVENT_FIELD".equals(kind)) return kind + "(" + name + "==" + eq + ")";
            return kind + "(" + id + ")";
        }
    }

    public final String id;       // 文件名
    public final String name;     // 显示名
    public final String module;   // 归属玩法模块（可空）
    public final String event;    // 订阅的事件 action
    public final String from;     // 可选：事件来源 system 过滤
    public final String fieldEq;  // 可选：事件 params 必须等于该值
    public final float cooldown;  // 触发冷却（秒）
    public final List<Check> checks;
    public final List<Effect> effects;

    private Rule(String id, String name, String module, String event, String from,
                 String fieldEq, float cooldown, List<Check> checks, List<Effect> effects) {
        this.id = id; this.name = name; this.module = module; this.event = event; this.from = from;
        this.fieldEq = fieldEq; this.cooldown = cooldown;
        this.checks = Collections.unmodifiableList(checks);
        this.effects = Collections.unmodifiableList(effects);
    }

    /**
     * 解析一条规则。任一 check 不在白名单、或 then 里有非法指令 → 记入 {@code errors} 并跳过该条。
     */
    public static Rule parse(String id, JsonObject o, List<String> errors) {
        String name = str(o, "name", id);
        String ev = str(o, "event", "");
        if (ev.isEmpty()) { errors.add("rule " + id + ": missing event"); return null; }

        List<Check> checks = new ArrayList<Check>();
        JsonElement ifEl = o.get("if");
        if (ifEl != null && ifEl.isJsonArray()) {
            int i = 0;
            for (JsonElement e : ifEl.getAsJsonArray()) {
                if (!e.isJsonObject()) { errors.add("rule " + id + ": check[" + i + "] not an object"); i++; continue; }
                Check c = Check.parse(e.getAsJsonObject());
                if (c == null) {
                    errors.add("rule " + id + ": unknown check at [" + i + "]");
                } else checks.add(c);
                i++;
            }
        }

        List<Effect> effects = new ArrayList<Effect>();
        JsonElement thenEl = o.get("then");
        if (thenEl == null || !thenEl.isJsonArray() || thenEl.getAsJsonArray().size() == 0) {
            errors.add("rule " + id + ": missing then[]");
            return null;
        }
        int j = 0;
        for (JsonElement e : thenEl.getAsJsonArray()) {
            if (!e.isJsonObject()) { errors.add("rule " + id + ": then[" + j + "] not an object"); j++; continue; }
            Effect ef = Effect.parse(e.getAsJsonObject());
            if (ef == null) errors.add("rule " + id + ": unknown effect at then[" + j + "]");
            else effects.add(ef);
            j++;
        }
        if (effects.isEmpty()) { errors.add("rule " + id + ": then[] produced no valid effect"); return null; }

        return new Rule(id, name, str(o, "module", ""), ev, str(o, "from", ""),
                str(o, "paramsEq", ""), num(o, "cooldown", 0f), checks, effects);
    }

    /** 事件匹配：action 必须相等；from / paramsEq 若给出则必须相等。 */
    public boolean matches(String system, String action, String params) {
        if (!event.equals(action)) return false;
        if (from != null && !from.isEmpty() && !from.equals(system)) return false;
        if (fieldEq != null && !fieldEq.isEmpty()) {
            return params != null && params.contains(fieldEq);
        }
        return true;
    }

    @Override public String toString() {
        return id + "(" + event + " -> " + effects.size() + "fx)";
    }

    private static String str(JsonObject o, String k, String dflt) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? dflt : e.getAsString();
    }

    private static float num(JsonObject o, String k, float dflt) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return dflt;
        try { return e.getAsFloat(); } catch (RuntimeException ex) { return dflt; }
    }
}
