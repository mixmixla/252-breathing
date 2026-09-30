package core.systems;

import core.content.ReactionBook;
import core.rng.SeededRNG;
import core.world.World;
import java.util.ArrayList;
import java.util.List;

/**
 * 材料反应求解器 —— <b>唯一的反应执行者</b>，规则全部来自数据表
 * （{@code assets/content/reactions/*.json} → {@link ReactionBook}）。
 *
 * <p><b>为什么需要它（2026-09-18 批 C）</b>：此前"A 挨着 B 就变 C"这类逻辑要么没有实现
 * （灭火、融雪），要么硬编码在某个系统的概率分支里（火蔓延只认 WOOD/LEAF 两种方块）。
 * 于是每加一种材料交互就要回去改代码。现在：<b>新增一种反应 = 加一个 JSON</b>。
 *
 * <p>这是从 Noita 学来的架构（见 {@code docs/NOITA_STUDY.md} §3、
 * {@code docs/NOITA_LESSONS.md}）：<b>一份求解器 + 一张表</b>，而不是"一种材料一套系统"。
 * 表里的 {@code "[tag]"} 输入在加载期展开成具体方块 —— 一份规则覆盖一整类材料
 * （本项目 {@code melt.json} 的 {@code "[ice]"} 同时覆盖 SNOW 与 ICE）。
 *
 * <p><b>确定性</b>：
 * <ul>
 *   <li>候选集是 {@code World.surfaceCells}（元素集合与迭代序由 {@code STREAMCHUNK} 门禁钉死）；</li>
 *   <li>邻居遍历顺序固定（+x,-x,+y,-y,+z,-z）；</li>
 *   <li>随机只来自 {@code rng} —— 即 {@code World.simStream("reaction")} 派生流，
 *       <b>不消耗主 rng</b>，因此不影响其它系统的随机序列；</li>
 *   <li><b>先收集后写入</b>（不在遍历中改集合 → 不会 ConcurrentModification，
 *       也避免"同 tick 内刚写入的格子又被当候选"造成的顺序依赖）。</li>
 * </ul>
 *
 * <p><b>成本</b>：每 {@value #PERIOD} tick 跑一次；先用 {@code book.reactive(mat)}
 * 快速跳过绝大多数格子（一次布尔数组读），未命中规则的格子零写入。
 *
 * <p><b>零漂移</b>：出厂 {@code WorldConfig.reactionTable = false} → {@code update} 首行返回。
 */
public final class ReactionSystem implements System {

    /**
     * 每 N tick 跑一次（反应是慢过程，无需 20Hz）。
     *
     * <p><b>为什么是 8</b>：实测（{@code ReactionFlowProbe}）PERIOD=4 时成本 +0.5ms/tick
     * <b>压在红线上</b> —— 根因不是规则判定（未命中规则的格子只花一次布尔数组读），
     * 而是候选集 {@code World.surfaceCells} 是 {@code HashSet<Long>}：<b>装箱 + 指针跳转</b>，
     * 缓存极不友好，遍历它本身就是主要开销。放宽到 8 tick（0.4s 一次判定）后成本减半，
     * 而"水浇灭火"这类反应在 0.4s 内完成完全够用。
     */
    private static final int PERIOD = 8;

    private static final int[] DX = { 1, -1, 0, 0, 0, 0 };
    private static final int[] DY = { 0, 0, 1, -1, 0, 0 };
    private static final int[] DZ = { 0, 0, 0, 0, 1, -1 };

    /**
     * 把"参数旋钮名"映射到它的乘子（2026-09-18 批 D）。未知 knob → 1（内容写错也不崩）。
     * 已知旋钮与 {@code ReactionDef.KNOWN_RATES} 对应。
     */
    private static float rateFactor(World w, String key) {
        if ("erosionRate".equals(key)) return w.config.erosionRate;
        return 1f;
    }

    private int step = 0;

    @Override public String name() { return "reaction"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 开关 + 空表双重防线：开关关 → 与历史逐字节一致；开关开但空表 → 也零写入。
        // （注：reactionTable **出厂默认为开**，见 WorldConfig.REACTION_TABLE_DFLT；注释原写"出厂关闭"是原型期设定，已按实际更正。）
        if (w.config == null || !w.config.reactionTable) return;
        ReactionBook book = w.reactions;
        if (book == null || book.isEmpty()) return;
        if (step++ % PERIOD != 0) return;

        // 命中收集（先收集后写入，见类注释）
        List<int[]> hits = null;
        for (long key : w.surfaceCells) {
            int x = (int) ((key >> 20) & 0x3FFL);
            int y = (int) ((key >> 10) & 0x3FFL);
            int z = (int) (key & 0x3FFL);
            if (!w.inBounds(x, y, z)) continue;
            int cur = w.mat[x][y][z];
            if (!book.reactive(cur)) continue;                 // 快速跳过：绝大多数格子到此为止
            for (int k = 0; k < 6; k++) {
                int nx = x + DX[k], ny = y + DY[k], nz = z + DZ[k];
                if (!w.inBounds(nx, ny, nz)) continue;
                int nb = w.mat[nx][ny][nz];
                if (!book.reactive(nb)) continue;
                int rule = book.ruleFor(cur, nb);
                if (rule < 0) continue;
                float p = book.probability(rule);
                String rk = book.rateKey(rule);
                if (rk != null) p *= rateFactor(w, rk);   // 参数旋钮（如 erosionRate）缩放概率
                if (p <= 0f) continue;                     // rate=0 → 该反应彻底不发生（零 RNG 调用）
                if (rng.nextDouble() >= p) continue;
                if (hits == null) hits = new ArrayList<int[]>();
                hits.add(new int[] { x, y, z, nx, ny, nz, rule, book.flippedFor(cur, nb) ? 1 : 0 });
                break;                                          // 每格每轮只触发一条规则
            }
        }
        if (hits == null) return;

        for (int i = 0; i < hits.size(); i++) {
            int[] h = hits.get(i);
            int x = h[0], y = h[1], z = h[2], nx = h[3], ny = h[4], nz = h[5];
            int rule = h[6];
            boolean flip = h[7] == 1;
            // outFirst 写在「匹配 in1 的那一格」上；flip 表示这一格是邻居而不是自身。
            int outSelf  = flip ? book.outSecond(rule) : book.outFirst(rule);
            int outNeigh = flip ? book.outFirst(rule) : book.outSecond(rule);
            if (outSelf >= 0) w.setBlock(x, y, z, outSelf);
            if (outNeigh >= 0) w.setBlock(nx, ny, nz, outNeigh);
        }
    }
}
