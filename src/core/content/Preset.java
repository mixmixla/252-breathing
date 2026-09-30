package core.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩法预设（Preset）—— 一个条目 = 一套玩法组合。你说的「我们也有我们的预设」就是它。
 *
 * <p>预设决定三件事：
 * <ol>
 *   <li>{@link #modules} —— 启用哪些玩法模块（捕捉 / 建造 / 自动化 / 生存…）</li>
 *   <li>{@link #rules} —— 启用哪些规则（{@code "*"} = 全部；{@code "module:capture"} = 该模块全部）</li>
 *   <li>{@link #params} —— 参数覆盖（一天多长、野兽上限、资源丰度…）</li>
 * </ol>
 *
 * <p>因此「换一套玩法」= 换一个 JSON 引用，而不是改代码。这就是「集大成」的正确形态：
 * 机制都在（可插拔），组合由预设决定。
 */
public final class Preset {

    public final String id;             // 文件名
    public final String name;           // 显示名
    public final String description;
    public final List<String> modules;         // 启用的玩法模块 id
    public final List<String> rules;           // 启用的规则（"*" / "module:x" / 具体 id）
    public final List<String> disablePhases;   // 要关掉的系统域（Phase 枚举名）
    public final List<String> disableSystems;  // 要关掉的单个系统（System.name()）
    public final Map<String, Float> params;

    private Preset(String id, String name, String description,
                   List<String> modules, List<String> rules,
                   List<String> disablePhases, List<String> disableSystems,
                   Map<String, Float> params) {
        this.id = id; this.name = name; this.description = description;
        this.modules = Collections.unmodifiableList(modules);
        this.rules = Collections.unmodifiableList(rules);
        this.disablePhases = Collections.unmodifiableList(disablePhases);
        this.disableSystems = Collections.unmodifiableList(disableSystems);
        this.params = Collections.unmodifiableMap(params);
    }

    public static Preset parse(String id, JsonObject o) {
        if (o == null) return null;
        List<String> mods = strList(o.get("modules"));
        List<String> rls = strList(o.get("rules"));
        Map<String, Float> ps = new LinkedHashMap<String, Float>();
        JsonElement pe = o.get("params");
        if (pe != null && pe.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : pe.getAsJsonObject().entrySet()) {
                try { ps.put(en.getKey(), en.getValue().getAsFloat()); } catch (RuntimeException ignored) {}
            }
        }
        return new Preset(id, str(o, "name", id), str(o, "description", ""), mods, rls,
                strList(o.get("disablePhases")), strList(o.get("disableSystems")), ps);
    }

    /** 该预设是否启用某条规则（按 id、模块、或通配符判定）。 */
    public boolean enablesRule(String ruleId, String ruleModule) {
        if (rules.isEmpty()) return true;                       // 未声明 = 全启用（向后兼容）
        for (String r : rules) {
            if ("*".equals(r)) return true;
            if (r.equals(ruleId)) return true;
            if (ruleModule != null && !ruleModule.isEmpty()
                    && ("module:" + ruleModule).equals(r)) return true;
        }
        return false;
    }

    public boolean enablesModule(String moduleId) {
        return modules.contains(moduleId);
    }

    public float param(String key, float dflt) {
        Float v = params.get(key);
        return v == null ? dflt : v;
    }

    /**
     * 本预设**启用的模块**各自的 {@code params}（模块默认值），由 {@link ContentRegistry} 解析后注入。
     *
     * <p><b>为什么需要它</b>（2026-09-17 修的投递缺口）：模块的 {@code params} 此前**只有解析、
     * 没有消费者**（{@code ContentRegistry.modules()} 全仓零生产调用）——于是
     * {@code wireRange} / {@code hungerRate} / {@code orbItemCost} 这些**只在模块里出现**的键
     * 任何预设都送不到 {@code World.config}。表现为"参数接线了但永远送不到"：
     * 例如 {@code palworld_like} 写了 {@code haulRate: 1.5}，但 {@code wireRange} 恒 0
     * → {@code HaulSystem} 永远没电 → 无人搬运从不发生。
     *
     * <p>语义：**模块参数 = 该模块的默认值**，预设自身的 {@code params} **覆盖**它们。
     * 顺序 = {@link #modules} 的声明顺序（后出现的模块覆盖先出现的同名键）。
     */
    public Map<String, Float> moduleParams() { return moduleParams; }

    /** 由内容注册表在解析完 modules 后注入一次（不暴露给外部改写）。 */
    void attachModuleParams(Map<String, Float> mp) {
        this.moduleParams = (mp == null)
                ? Collections.<String, Float>emptyMap() : Collections.unmodifiableMap(mp);
    }

    private Map<String, Float> moduleParams = Collections.emptyMap();

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" [modules=").append(modules.size())
          .append(" rules=").append(rules.isEmpty() ? "all" : String.valueOf(rules.size()))
          .append(" params=").append(params.size())
          .append(" off=").append(disablePhases.size() + disableSystems.size()).append(']');
        return sb.toString();
    }

    private static List<String> strList(JsonElement e) {
        List<String> out = new ArrayList<String>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (!x.isJsonNull()) out.add(x.getAsString());
            }
        }
        return out;
    }

    private static String str(JsonObject o, String k, String dflt) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? dflt : e.getAsString();
    }

    /**
     * 玩法模块元数据。
     *
     * <p><b>诚实说明</b>：模块的<b>实现</b>是代码（比如捕捉需要交互判定、产线需要结算），
     * 这份 JSON 描述的是它的<b>身份与可配置参数</b> —— 让预设能开关它、让加载器能校验依赖。
     * 这就是「机制接口化，参数配置化」。
     */
    public static final class ModuleDef {
        public final String id, name, description;
        public final List<String> requires;   // 依赖的其他模块 / System
        public final Map<String, Float> params;

        private ModuleDef(String id, String name, String description,
                          List<String> requires, Map<String, Float> params) {
            this.id = id; this.name = name; this.description = description;
            this.requires = Collections.unmodifiableList(requires);
            this.params = Collections.unmodifiableMap(params);
        }

        public static ModuleDef parse(String id, JsonObject o) {
            if (o == null) return null;
            Map<String, Float> ps = new LinkedHashMap<String, Float>();
            JsonElement pe = o.get("params");
            if (pe != null && pe.isJsonObject()) {
                for (Map.Entry<String, JsonElement> en : pe.getAsJsonObject().entrySet()) {
                    try { ps.put(en.getKey(), en.getValue().getAsFloat()); } catch (RuntimeException ignored) {}
                }
            }
            return new ModuleDef(id, str(o, "name", id), str(o, "description", ""),
                    strList(o.get("requires")), ps);
        }
    }
}
