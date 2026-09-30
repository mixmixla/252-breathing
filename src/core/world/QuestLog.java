package core.world;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务进度（实体级开放状态，P1「内容层接线」）。
 *
 * <p><b>纪律（与 npcs/beasts/shrines/trials 同）</b>：
 * <ul>
 *   <li><b>不进</b> {@link World#hashState()} —— 窄哈希只管 mat/mass/rng/prosperity/skills/memory，
 *       所以「加一整套任务系统」不会移动四基线指纹；</li>
 *   <li>但<b>要进存档与联机快照</b> —— 它是玩家进度，不是派生量（{@code StateCodec} 反射全字段
 *       自动覆盖；存档走 ext v10 显式段）。</li>
 * </ul>
 *
 * <p>为什么进度放在 {@code World} 而不是场景层：任务推进由 {@code combat.kill} 事件驱动，
 * 而事件总线在 {@code World} —— 放在这里才能与回滚/快照同生共死。
 */
public final class QuestLog {

    /** 一条任务的进度（纯数据类，字段全部会被反射进快照）。 */
    public static final class Progress {
        public final String questId;
        /** 当前节点下标（在 {@link core.content.QuestDef#nodes} 里的位置）。 */
        public int nodeIndex = 0;
        /** 当前节点"击杀目标兽"的累计（换节点时归零）。 */
        public int killCount = 0;
        /** 是否已走到终点（next 为空且该节点达成）。 */
        public boolean completed = false;

        public Progress(String questId) { this.questId = questId; }
        @Override public String toString() { return questId + "#" + nodeIndex
                + (completed ? "=done" : "/" + killCount); }
    }

    /** 全部任务进度（顺序 = 注册顺序 = 内容文件字典序，确定性）。 */
    public final List<Progress> list = new ArrayList<Progress>();

    /** 按 id 取进度；不存在返回 {@code null}。 */
    public Progress of(String questId) {
        if (questId == null) return null;
        for (int i = 0; i < list.size(); i++) if (list.get(i).questId.equals(questId)) return list.get(i);
        return null;
    }

    /** 已登记的进度条数。 */
    public int size() { return list.size(); }

    /** 已完成的条数。 */
    public int completedCount() {
        int n = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).completed) n++;
        return n;
    }

    /** 进度摘要（ASCII，HUD / 门禁用）。 */
    public String asciiSummary() {
        StringBuilder sb = new StringBuilder("QUESTS ").append(completedCount()).append('/').append(list.size());
        for (int i = 0; i < list.size(); i++) {
            Progress p = list.get(i);
            sb.append(' ').append(p.questId).append(':');
            if (p.completed) sb.append("DONE");
            else sb.append("n").append(p.nodeIndex).append('+').append(p.killCount);
        }
        return sb.toString();
    }

    /** 确定性签名（门禁断言"同种子两遍逐字节一致"用；不进指纹）。 */
    public String snapshot() { return asciiSummary(); }
}
