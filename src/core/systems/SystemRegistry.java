package core.systems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统注册表 —— 「各个系统还能新增很多系统」的那张桌子。
 *
 * <p>它解决一个具体问题：原来 92 个系统靠 {@code Simulation} 里 92 行手工
 * {@code addSystem} 注册，<b>顺序敏感却不可见</b>，也无法按玩法开关。
 *
 * <p>注册表提供四件事：
 * <ol>
 *   <li><b>顺序单一真相</b>：{@link #ordered()} 就是执行顺序，且永远等于注册顺序
 *       —— 顺序即指纹，绝不重排；</li>
 *   <li><b>职责标签</b>：每个系统带一个 {@link Phase}（纯标签，不影响排序）；</li>
 *   <li><b>黄金序列</b>：{@link #golden()} 输出 {@code name|PHASE} 全序列，
 *       门禁据此断言"顺序没被动过"；</li>
 *   <li><b>按域开关</b>：{@link #disable(String)} / {@link #disablePhase(Phase)}，
 *       让玩法预设能关掉一整类系统 —— 默认全开，故零漂移。</li>
 * </ol>
 *
 * <p><b>零漂移纪律</b>：无 disabled 时 {@link #isDisabled} 恒 false 且立即返回
 * （内部先判空集），因此生产路径与旧实现<b>逐字节等价</b>。
 */
public final class SystemRegistry {

    private final List<System> ordered = new ArrayList<System>();
    private final Map<String, System> byName = new LinkedHashMap<String, System>();
    private final Map<String, Phase> phaseOf = new LinkedHashMap<String, Phase>();
    private final Set<String> disabled = new LinkedHashSet<String>();

    /** 注册（默认标签 {@link Phase#META}）。返回 this 便于链式。 */
    public SystemRegistry add(System s) { return add(s, Phase.META); }

    /** 注册并打上职责标签。重名直接抛异常（静默覆盖是事故之源）。 */
    public SystemRegistry add(System s, Phase p) {
        if (s == null) throw new IllegalArgumentException("null system");
        String n = s.name();
        if (byName.containsKey(n)) throw new IllegalStateException("duplicate system name: " + n);
        byName.put(n, s);
        phaseOf.put(n, p == null ? Phase.META : p);
        ordered.add(s);
        return this;
    }

    /** 执行顺序（不可变视图）。 */
    public List<System> ordered() { return Collections.unmodifiableList(ordered); }

    public int size() { return ordered.size(); }

    public List<String> names() {
        List<String> out = new ArrayList<String>(ordered.size());
        for (System s : ordered) out.add(s.name());
        return out;
    }

    public Phase phaseOf(String name) {
        Phase p = phaseOf.get(name);
        return p == null ? Phase.META : p;
    }

    public System get(String name) { return byName.get(name); }

    /** 按域分组视图（报告 / F3 用，不影响执行顺序）。 */
    public Map<Phase, List<String>> plan() {
        Map<Phase, List<String>> out = new EnumMap<Phase, List<String>>(Phase.class);
        for (Phase p : Phase.values()) out.put(p, new ArrayList<String>());
        for (System s : ordered) out.get(phaseOf(s.name())).add(s.name());
        return out;
    }

    // ---------------- 按域 / 按系统开关 ----------------

    public boolean isDisabled(String name) {
        return !disabled.isEmpty() && disabled.contains(name);   // 空集快路径 → 零开销
    }

    public boolean disable(String name) {
        if (!byName.containsKey(name)) return false;
        return disabled.add(name);
    }

    public boolean enable(String name) { return disabled.remove(name); }

    /** 按域批量开关。返回被影响的系统数。 */
    public int disablePhase(Phase p) {
        int n = 0;
        for (System s : ordered) if (phaseOf.get(s.name()) == p && disabled.add(s.name())) n++;
        return n;
    }

    public int enableAll() {
        int n = disabled.size();
        disabled.clear();
        return n;
    }

    public boolean hasDisabled() { return !disabled.isEmpty(); }
    public int disabledCount()   { return disabled.size(); }

    /** 生效的执行序列（过滤掉被禁用的，保持注册序）。 */
    public List<String> activeNames() {
        List<String> out = new ArrayList<String>();
        for (System s : ordered) if (!isDisabled(s.name())) out.add(s.name());
        return out;
    }

    // ---------------- 审计 ----------------

    /**
     * 黄金序列：{@code name|phase} 按执行顺序拼接。
     * 门禁断言它等于硬编码基线 —— 任何人改动注册顺序都会立刻被抓住。
     */
    public String golden() {
        StringBuilder sb = new StringBuilder();
        for (System s : ordered) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s.name()).append('|').append(phaseOf(s.name()).label());
        }
        return sb.toString();
    }

    /** 人类可读报告：按域列出系统 + 开关状态。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("systems=").append(ordered.size())
          .append(" disabled=").append(disabled.size()).append('\n');
        Map<Phase, List<String>> plan = plan();
        for (Phase p : Phase.values()) {
            List<String> list = plan.get(p);
            if (list.isEmpty()) continue;
            sb.append("  ").append(p.label()).append(" (").append(list.size()).append("): ");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(list.get(i).replace("System", ""));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
