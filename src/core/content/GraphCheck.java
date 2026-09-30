package core.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用有向图环检测 —— 科技链（{@link TechTree}）与技能树（{@link SkillTree}）共用。
 *
 * <p>为什么值得单独抽出来：**任何"前置依赖图"都怕成环**。
 * 环意味着「A 等 B、B 等 A」的死锁 —— 玩家永远解锁不了，而且这种错误在数据里
 * 极难靠肉眼发现（图一大就看不出来）。放在加载期自动抓，成本几乎为零。
 *
 * <p>只有**两端都在同一张表里**的引用才构成边；指向外部标记（世界既有技能）
 * 的引用被忽略 —— 那不是图的一部分。
 */
public final class GraphCheck {

    private GraphCheck() { }

    /**
     * 三色 DFS 找环。
     *
     * @param ids      图内的全部节点 id
     * @param edges    邻接表：id → 它依赖的 id 列表
     * @param outCycle 非 null 时写入一条可读环路径（如 {@code a -> b -> a}）
     * @return 是否存在环
     */
    public static boolean hasCycle(List<String> ids, Map<String, List<String>> edges, List<String> outCycle) {
        if (ids == null || ids.isEmpty()) return false;
        Map<String, Integer> color = new HashMap<String, Integer>();   // 0=白(未访) 1=灰(栈上) 2=黑(完成)
        for (int i = 0; i < ids.size(); i++) color.put(ids.get(i), 0);
        List<String> stack = new ArrayList<String>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (color.get(id) == 0 && dfs(id, edges, color, stack, outCycle)) return true;
        }
        return false;
    }

    private static boolean dfs(String id, Map<String, List<String>> edges,
                               Map<String, Integer> color, List<String> stack, List<String> outCycle) {
        color.put(id, 1);
        stack.add(id);
        List<String> next = edges.get(id);
        if (next != null) {
            for (int i = 0; i < next.size(); i++) {
                String n = next.get(i);
                if (!color.containsKey(n)) continue;          // 外部节点：不构成边
                int c = color.get(n);
                if (c == 1) {                                  // 回到栈上 → 成环
                    if (outCycle != null) outCycle.add(pathOf(stack, n));
                    return true;
                }
                if (c == 0 && dfs(n, edges, color, stack, outCycle)) return true;
            }
        }
        stack.remove(stack.size() - 1);
        color.put(id, 2);
        return false;
    }

    private static String pathOf(List<String> stack, String back) {
        StringBuilder sb = new StringBuilder("cycle: ");
        int from = stack.indexOf(back);
        for (int i = (from < 0 ? 0 : from); i < stack.size(); i++) sb.append(stack.get(i)).append(" -> ");
        return sb.append(back).toString();
    }
}
