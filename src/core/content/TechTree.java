package core.content;

import core.world.World;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 科技树运行时 —— 按节奏检查「谁能解锁了」，并把解锁结果写回世界。
 *
 * <p><b>为什么不是 {@code System} 实现</b>：把它做成 System 会让 {@code systems} 列表
 * 多一项 → 黄金序列哈希失配 → 既有门禁基线被动。所以它和 {@code ContentSystem} 一样
 * 是<b>可选启用</b>的：由渲染层（Game）每 tick 主动调用，门禁环境根本不启用。
 * 于是「新增能力」与「基线稳定」两件事不冲突。
 *
 * <p><b>零 RNG</b>：解锁判定只看 {@code World.skills} 与 {@code prosperity}，纯条件函数。
 *
 * <p><b>节奏</b>：每 {@link #CHECK_INTERVAL} tick 检查一次（1 秒），而不是每 tick 全扫
 * —— 科技图再大也不会拖累 tick。
 */
public final class TechTree {

    /** 检查间隔（tick）。20 tick/s → 每秒一次。 */
    public static final int CHECK_INTERVAL = 20;

    private final List<TechDef> techs;
    private final ContentRegistry reg;   // 物品查表（cost/unlocks 中的 id 是否真实物品）
    private final List<String> journal = new ArrayList<String>();   // 解锁流水（诊断）
    /**
     * 配方自检发现的问题（诊断）—— 空列表 = 内容层与 {@link RecipeBook} 的代码契约一致。
     *
     * <p>存在的理由：旧实现把 {@code cost}/{@code unlocks} 当**不透明字符串**处理，
     * 键名写错/物品不存在都没有任何提示（静默跳过）。这正是审计 C8 把
     * {@code coal}/{@code ore}/{@code iron_bar} 记成「死内容键」的根因 ——
     * 运行时确实读了它们，但源码里连一次字面引用都没有，没人能从代码看出语义。
     */
    private final List<String> recipeIssues = new ArrayList<String>();
    private int checks = 0;
    private int unlockedTotal = 0;

    public TechTree(ContentRegistry reg) {
        this.techs = (reg == null) ? Collections.<TechDef>emptyList() : reg.techs();
        this.reg = reg;
    }

    /**
     * 推进一次（内部按 {@link #CHECK_INTERVAL} 节流）。
     *
     * @return 本次解锁的科技数
     */
    public int tick(World w, EffectQueue queue, long nowTick) {
        return tick(w, queue, nowTick, null);
    }

    /**
     * 推进一次（内部按 {@link #CHECK_INTERVAL} 节流）。
     *
     * @param inv 玩家背包（可 null）—— 非 null 时按 tech 的 {@code cost} 真扣资源、按 {@code unlocks}
     *            中的真实物品产出入袋（死键 §2.2：coal/ore/iron_bar 现被真正驱动）。门禁环境不传背包，
     *            故不影响确定性基线。
     * @return 本次解锁的科技数
     */
    public int tick(World w, EffectQueue queue, long nowTick, Inventory inv) {
        if (techs.isEmpty() || w == null) return 0;
        if (nowTick % CHECK_INTERVAL != 0L) return 0;
        checks++;
        int n = 0;
        for (int i = 0; i < techs.size(); i++) {
            TechDef t = techs.get(i);
            if (w.hasSkill(t.id)) continue;          // 已解锁（skills 是幂等标记集）
            if (!canUnlock(t, w)) continue;
            unlock(w, t, queue, nowTick, inv);
            n++;
        }
        return n;
    }

    /** 前置是否全部满足（tech 依赖 + 世界标记 + 繁荣门槛）。 */
    public boolean canUnlock(TechDef t, World w) {
        if (t == null || w == null) return false;
        if (w.prosperity < t.minProsperity) return false;
        for (int i = 0; i < t.requires.size(); i++) {
            if (!w.hasSkill(t.requires.get(i))) return false;
        }
        return true;
    }

    /** 执行解锁：写标记 + 派发效果。返回是否真的发生了新解锁。 */
    public boolean unlock(World w, TechDef t, EffectQueue queue, long nowTick) {
        return unlock(w, t, queue, nowTick, null);
    }

    /**
     * 执行解锁：写标记 + 派发效果。
     *
     * <p>死键落地（§2.2：coal/ore/iron_bar）：若 {@code inv} 非 null，先按 {@code cost} 从背包
     * <b>真扣</b>资源（仅对真实存在的物品、且数量足够时才扣，不足也不锁死科技树），再把
     * {@code unlocks} 中属真实物品的（如 {@code iron_bar}）<b>真产出入袋</b>。技能标记照旧写入
     * {@code World.skills}。全程无 RNG，顺序由 TechDef.cost 的 LinkedHashMap 保证确定。
     *
     * @return 是否真的发生了新解锁
     */
    public boolean unlock(World w, TechDef t, EffectQueue queue, long nowTick, Inventory inv) {
        if (w == null || t == null || w.hasSkill(t.id)) return false;
        // 死键落地（§2.2）：tech 的 cost（ore/coal/iron_bar 等资源）现从背包真扣除。
        // 配方链的**显式契约校验**（RecipeBook）：把 cost/unlocks 的键名从"不透明字符串"
        // 升级为「有语义、必须对应真实物品」的契约。内容写歪 → 进 recipeIssues（响亮可见），
        // 绝不静默跳过 —— 与 StateCodec.SKIP 同一条纪律。
        RecipeBook.Recipe rcp = RecipeBook.byId(t.id);
        if (rcp != null && reg != null) {
            for (java.util.Map.Entry<String, Integer> e : rcp.inputs.entrySet()) {
                String key = e.getKey();
                int declared = (int) Math.floor(t.cost.containsKey(key) ? t.cost.get(key).floatValue() : 0f);
                if (declared != e.getValue().intValue()) {
                    recipeIssues.add(t.id + ":" + key + " json=" + declared + " code=" + e.getValue());
                }
                if (!RecipeBook.isResource(key)) recipeIssues.add(t.id + ":not-a-resource " + key);
            }
            if (!t.unlocks.contains(rcp.output)) recipeIssues.add(t.id + ":missing-output " + rcp.output);
        }
        if (inv != null && reg != null) {
            for (java.util.Map.Entry<String, Float> e : t.cost.entrySet()) {
                String rid = e.getKey();
                int amt = (int) Math.floor(e.getValue());
                if (amt <= 0) continue;
                if (reg.item(rid) == null) {
                    // 响亮记录：配方里出现了内容层不存在的资源键（旧实现是静默 continue）。
                    recipeIssues.add(t.id + ":unknown-item " + rid);
                    continue;
                }
                if (inv.countOf(rid) >= amt) inv.remove(rid, amt);   // 足以支付才扣（不足则照常解锁）
            }
        }
        w.addSkill(t.id);
        for (int i = 0; i < t.unlocks.size(); i++) {
            String u = t.unlocks.get(i);
            w.addSkill(u);
            if (inv != null && reg != null && reg.item(u) != null) {
                // 产出数量由**配方契约**决定（RecipeBook.outputCount），不再硬编码 1 ——
                // 否则"配方说产几个"与"实际给几个"是两份真相，迟早分叉。
                int amt = (rcp != null && u.equals(rcp.output)) ? rcp.outputCount : 1;
                inv.add(u, amt);
            }
        }
        if (queue != null) queue.submitAll(t.effects, 0f, 0f, 0f, nowTick);
        journal.add(nowTick + ":" + t.id);
        while (journal.size() > 64) journal.remove(0);
        unlockedTotal++;
        return true;
    }

    // ------------------------------------------------------------------
    // 静态分析（加载期用）
    // ------------------------------------------------------------------

    /**
     * 科技图环检测（委托 {@link GraphCheck} —— 与技能树共用同一份实现）。
     *
     * @param outCycle 非 null 时写入一条可读的环路径
     */
    public static boolean hasCycle(List<TechDef> techs, List<String> outCycle) {
        List<String> ids = new ArrayList<String>();
        Map<String, List<String>> edges = new HashMap<String, List<String>>();
        for (int i = 0; i < techs.size(); i++) {
            TechDef t = techs.get(i);
            ids.add(t.id);
            edges.put(t.id, t.requires);
        }
        return GraphCheck.hasCycle(ids, edges, outCycle);   // 与技能树共用同一份环检测
    }

    // ------------------------------------------------------------------
    // 观测
    // ------------------------------------------------------------------

    public List<TechDef> techs()      { return techs; }
    public int size()                 { return techs.size(); }
    public int unlockedTotal()        { return unlockedTotal; }
    public int checks()               { return checks; }
    public List<String> journal()     { return Collections.unmodifiableList(journal); }

    /** 配方自检问题（空 = 内容层与 RecipeBook 契约一致）。 */
    public List<String> recipeIssues() { return Collections.unmodifiableList(recipeIssues); }

    /** 尚未解锁的科技 id（按注册序）—— 供 UI 显示"下一步能做什么"。 */
    public List<String> lockedIds(World w) {
        List<String> out = new ArrayList<String>();
        if (w == null) return out;
        for (int i = 0; i < techs.size(); i++) {
            TechDef t = techs.get(i);
            if (!w.hasSkill(t.id)) out.add(t.id);
        }
        return out;
    }

    /** 当前<b>可解锁</b>的科技 id（前置满足但还没解）—— UI 的"现在能做"。 */
    public List<String> availableIds(World w) {
        List<String> out = new ArrayList<String>();
        if (w == null) return out;
        for (int i = 0; i < techs.size(); i++) {
            TechDef t = techs.get(i);
            if (!w.hasSkill(t.id) && canUnlock(t, w)) out.add(t.id);
        }
        return out;
    }
}
