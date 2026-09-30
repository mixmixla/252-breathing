package core.content;

import core.world.QuestLog;
import core.world.World;
import java.util.Collections;
import java.util.List;

/**
 * 任务引擎 —— 把 {@link QuestDef} 跑起来（P1「内容层接线」的第二半）。
 *
 * <p>与 {@link RuleEngine} <b>同构、同期</b>：两者都订阅 {@link World#events}、都持有
 * <b>自己的水印</b>（绝不写 {@code World.eventsProcessed} —— 那个水印属于
 * {@code ProsperitySystem}，共享会互相吃掉对方的事件）。
 *
 * <p><b>驱动位置</b>：与 {@code RuleEngine}/{@code TechTree}/{@code EffectQueue} 并列，
 * 由 {@code render/lwjgl/Game} 的固定步长在 {@code world.tick()} 之后驱动
 * （无头门禁不驱动 → 任务引擎缺席时世界逐字节不变）。这也是 {@code TickBody} 文档里
 * 「真游戏实现 = world.tick() + 内容层」的那份清单：本类是第 4 个引擎。
 *
 * <p><b>零 RNG</b>：任务推进只看事件与计数，不取任何随机 —— 内容层不可能扰动仿真 RNG 流。
 *
 * <p><b>效果出口</b>：节点达成时把 {@link Effect} 直接交给 {@link EffectSink}
 * （任务 JSON 里没有 {@code delay}，不需要延迟队列；要延迟的规则请走
 * {@link RuleEngine} + {@link EffectQueue}）。于是 {@code GRANT_ITEM} 落到同一处
 * （Game 的背包），不产生第二套"发物品"逻辑。
 */
public final class QuestEngine {

    private final List<QuestDef> quests;
    private EffectSink sink = EffectSink.NONE;
    private int watermark = 0;       // 自有水印（不碰 world.eventsProcessed）
    private int advanced = 0;        // 累计跨过的节点数（诊断用，不进指纹）
    private boolean registered = false;

    public QuestEngine(List<QuestDef> quests) {
        this.quests = Collections.unmodifiableList(quests == null
                ? java.util.Collections.<QuestDef>emptyList() : quests);
    }

    /** 上层（Game）注入表现层出口；无头环境保持 {@link EffectSink#NONE}。 */
    public void setSink(EffectSink s) { this.sink = (s == null) ? EffectSink.NONE : s; }

    public List<QuestDef> quests() { return quests; }
    public int watermark() { return watermark; }
    public int advancedTotal() { return advanced; }

    /**
     * 推进一 tick：登记（仅首次）→ 吃掉"无 on 的前置节点" → 扫描新 kill 事件推进匹配任务。
     *
     * @return 本 tick 跨过的节点数（诊断用，不进指纹）
     */
    public int tick(World w, long ignoredTick) {
        if (w == null || quests.isEmpty()) return 0;
        QuestLog log = w.quests;

        int hits = 0;
        if (!registered) {
            registered = true;
            for (int i = 0; i < quests.size(); i++) {
                QuestDef q = quests.get(i);
                if (log.of(q.id) == null) {
                    QuestLog.Progress p = new QuestLog.Progress(q.id);
                    log.list.add(p);
                    hits += settleChain(q, p);        // 首节点若"无 on"，一进入即达成
                }
            }
        }

        List<World.Event> evs = w.events;
        int size = evs.size();
        if (watermark < 0 || watermark > size) watermark = 0;   // 事件表被裁剪 → 安全重置

        for (int i = watermark; i < size; i++) {
            World.Event ev = evs.get(i);
            if (ev == null) continue;
            if (!"combat".equals(ev.system) || !"kill".equals(ev.action)) continue;
            String beastId = (ev.params == null) ? "" : ev.params;
            for (int qi = 0; qi < quests.size(); qi++) {
                QuestDef q = quests.get(qi);
                QuestLog.Progress p = log.of(q.id);
                if (p != null) hits += advance(q, p, beastId);
            }
        }
        watermark = size;
        advanced += hits;
        return hits;
    }

    /** 当前节点（越界/已完成 → null）。 */
    private QuestDef.Node cur(QuestDef q, QuestLog.Progress p) {
        if (p.completed || p.nodeIndex < 0 || p.nodeIndex >= q.nodes.size()) return null;
        return q.nodes.get(p.nodeIndex);
    }

    /**
     * kill 事件推进：命中当前节点目标兽则计数；达标则执行效果并前进。
     * <p>前进时<b>链式吃掉</b>后续"无 on"的节点（如"交任务"节点），否则它会一直等到下一次击杀才生效。
     *
     * @return 跨过的节点数
     */
    private int advance(QuestDef q, QuestLog.Progress p, String beastId) {
        QuestDef.Node n = cur(q, p);
        if (n == null) return 0;
        if (n.onCount <= 0) return settleChain(q, p);          // 当前节点不需要事件（首节点即交任务）
        if (!QuestDef.ON_KILL.equals(n.onType)) return 0;
        if (!n.onBeast.isEmpty() && !n.onBeast.equals(beastId)) return 0;   // "" = 任意兽
        p.killCount++;
        if (p.killCount < n.onCount) return 0;
        // 达标：先执行本节点效果并前进，再链式吃掉后续"无 on"的节点（交任务节点）。
        // 注意别只调 settleChain —— 它的循环条件是 onCount<=0，会跳过"刚刚达标"的节点本身，
        // 表现为"计数满了但奖励不发、也不算完成"（门禁 QUEST-RUN 抓到过一次）。
        return finishNode(q, p, n) + settleChain(q, p);
    }

    /** 执行节点效果并切到 {@code next}；返回 1（跨过一个节点）。 */
    private int finishNode(QuestDef q, QuestLog.Progress p, QuestDef.Node n) {
        runEffects(n);
        if (n.next == null || n.next.isEmpty()) { p.completed = true; return 1; }
        QuestDef.Node nx = q.node(n.next);
        if (nx == null) { p.completed = true; return 1; }
        p.nodeIndex = nx.index;
        p.killCount = 0;
        return 1;
    }

    /**
     * 从当前节点起反复完成"无需事件"的节点（如"交任务"节点）。
     * <p>迭代上界取 {@code nodes.size()+1}：加载期只校验了 {@code next} 存在，
     * 没查环 —— 一串互相指向的"无 on"节点会把 tick 卡死。宁可少推进一步，也不能挂住主循环。
     */
    private int settleChain(QuestDef q, QuestLog.Progress p) {
        int crossed = 0;
        int guard = q.nodes.size() + 1;
        QuestDef.Node n = cur(q, p);
        while (n != null && n.onCount <= 0 && guard-- > 0) {
            crossed += finishNode(q, p, n);
            n = cur(q, p);
        }
        return crossed;
    }

    private void runEffects(QuestDef.Node n) {
        for (int i = 0; i < n.effects.size(); i++) {
            Effect e = n.effects.get(i);
            if (e == null) continue;
            sink.simulate(e.type, e.id, e.amount, 0f, 0f, 0f);
        }
    }

    // ---------------------------------------------------------------- HUD
    /**
     * HUD 行（ASCII 版）：{@code QUEST <id>  <prog>}。
     * <p>只返回<b>第一条未完成</b>任务 —— 同时列十条会变成噪声；顺序即内容文件字典序。
     */
    public String hudLineAscii(World w) {
        QuestDef q = activeQuest(w);
        if (q == null) return "";
        QuestLog.Progress p = w.quests.of(q.id);
        int need = needOf(q, p);
        if (need <= 0) return "QUEST " + q.id;
        return "QUEST " + q.id + "  " + (p == null ? 0 : p.killCount) + "/" + need;
    }

    /** HUD 行（中文版，配 {@code HudText.cjk}）。 */
    public String hudLineCjk(World w) {
        QuestDef q = activeQuest(w);
        if (q == null) return "";
        QuestLog.Progress p = w.quests.of(q.id);
        int need = needOf(q, p);
        if (need <= 0) return "任务  " + q.title;
        return "任务  " + q.title + "   " + (p == null ? 0 : p.killCount) + "/" + need;
    }

    /** 当前节点剩余目标（0 = 该节点不需要事件）。 */
    private int needOf(QuestDef q, QuestLog.Progress p) {
        if (p == null) return 0;
        QuestDef.Node n = cur(q, p);
        return (n == null) ? 0 : n.onCount;
    }

    /** 当前追踪的任务（第一条未完成；全完成返回 {@code null}）。 */
    public QuestDef activeQuest(World w) {
        if (w == null) return null;
        for (int i = 0; i < quests.size(); i++) {
            QuestLog.Progress p = w.quests.of(quests.get(i).id);
            if (p == null || !p.completed) return quests.get(i);
        }
        return null;
    }
}
