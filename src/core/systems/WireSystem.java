package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.Facing;
import core.world.World;
import java.util.Collection;
import java.util.HashMap;

/**
 * 电路 / 红石子系统（第 96 个系统）—— 消费预设参数 {@code wireRange}，算出<b>信号强度</b>。
 *
 * <p><b>它解决什么</b>：{@code automation} 模块写了 {@code wireRange}，但此前背后没有系统（审计 C8
 * 的 7 个「空转参数」之一）。本系统把 {@code WIRE}/{@code LAMP} 当导体，算出全网每格的信号强度，
 * 写回该格 {@code meta}（渲染层据此点亮、HUD 据此刻度、比较器据此做阈值判断）。
 *
 * <p><b>为什么电源不是火</b>：第一版把电源定义成「紧邻 FIRE 的灯具」，实测<b>当场失败</b> ——
 * {@code FireSpreadSystem} 每 tick 有 50% 概率把火熄灭（"自身按概率熄灭"是它的既有语义），
 * 火源平均活不过 2 tick → 电路随机断电。改用煤矿：它是稳定方块、语义也正好是"燃料/能源"。
 * （这条正是「先跑一遍再下结论」的价值：纸面上"火=能源"很自然，跑起来才知道火是瞬态的。）
 *
 * <p><b>模型（第九批重写为「沿导线传导 + 逐格衰减」）</b>：
 * <ol>
 *   <li><b>导体</b>：{@link Blocks#WIRE} ∪ {@link Blocks#LAMP}（炉灯兼作导线，向后兼容）。</li>
 *   <li><b>电源</b>：导体<b>六邻</b>中的元件按「<b>它朝我发射的强度</b>」注入
 *       （{@link RedstoneLogicSystem#strengthToward}，第十批起方向敏感：中继/比较器只从正面出，
 *       拉杆/按钮/压力板全向）；另有旧模型「邻 {@code COAL_ORE} 的 LAMP」= 满强度。</li>
 *   <li><b>传导</b>：沿导体之间的<b>六邻邻接</b>传播，每走一格强度 <b>−1</b>；最多走 {@code floor(wireRange)} 格。</li>
 *   <li><b>写入</b>：每格 {@code meta} = 该格强度（0..{@link #MAX_SIGNAL}）。</li>
 * </ol>
 *
 * <p><b>⚠️ 与旧模型的差异（有意演进，不是事故漂移）</b>：第八批及以前是「与任一电源的
 * <b>切比雪夫距离 ≤ wireRange</b>」—— 那意味着信号能<b>穿过空气/石头</b>跳到远处的灯（方环，不是导线），
 * 而且只有 0/1 两态。现在改成「只能沿导体走、每格衰减」+ 15 级强度：
 * <ul>
 *   <li>更接近红石语义（信号沿导线走，不穿墙）；</li>
 *   <li>为第九批的比较器（阈值门）与后续"模拟电路"提供了必需的量；</li>
 *   <li>代价：原先"方环"可达的<b>隔空/对角</b>节点不再通电。门禁 {@code SUBSYS} 的 WIRE_RANGE 用例是
 *       <b>连续 LAMP 链</b>，两种模型下结果一致（故该用例的判据未变），并在 {@code SubsystemTest}
 *       新增 {@code WIRE_STRENGTH} 段<b>显式钉住新模型</b>（衰减斜率 / 中继再生 / 超距衰减到 0），
 *       把"模型"本身变成有牙判据 —— 否则这次改模型就等于"没人审"。</li>
 * </ul>
 *
 * <p><b>为什么电源判据不在本类</b>：「什么算电源、朝哪个方向算」= {@link RedstoneLogicSystem#strengthToward}
 * （唯一来源），否则每加一个元件就要同步两份名单（第八批的教训）。
 * 本类只负责「沿导体传导 + 逐格衰减 + 取最大值」这一段，**不含任何元件名单**。
 *
 * <p><b>有牙</b>：通电节点是 {@link HaulSystem} 的前置条件 —— 自动化搬运需要电力。
 *
 * <p><b>可视化</b>：强度写回 {@code meta}（0..15）并在变化时 {@code World.markDirty} → 区块网格重建；
 * 渲染层读 meta：<b>亮度随强度变化</b>（{@code Chunk.isRedstoneLit} / {@code Game.putV}）+ 通电元件进发光网格（红辉）。
 * （曾写过一个 {@code isPowered(x,y,z)} 访问器但<b>零调用</b>，2026-09-17 机械自查后删掉：
 * 没有消费者的查询口就是死代码 —— 本项目已三次栽在"声称有消费者、其实没有"上。）
 *
 * <p><b>零漂移</b>：出厂默认 {@code wireRange = 0} → 首行返回，不消费 RNG、不写世界。
 * 网络本身每 tick 由地形重算，只存在系统实例里；写入只落 {@code meta}（不进 {@code hashState}），
 * {@code markDirty} 仅在强度<b>变化</b>时调用（{@code dirtyChunks} 是 SKIP 的渲染脏标记）。
 *
 * <p><b>有界</b>：节点上限 {@link #MAX_NODES}、松弛轮次上限 {@link #MAX_ROUNDS} → 电路再大也不会让 tick 失控。
 */
public final class WireSystem implements System {

    /** 信号强度上限（MC 口径）：源 = 15，沿导线每走一格 −1。 */
    public static final int MAX_SIGNAL = 15;

    /** 导体节点上限（超出则忽略，绝不越界）。 */
    public static final int MAX_NODES = 256;

    /** 松弛轮次上限（防病态地形下无界循环）。 */
    public static final int MAX_ROUNDS = 32;


    /** 全部导体坐标（x,y,z 平铺；{@code cellsOfType} 给出的升序确定序）。 */
    private final int[] nodes = new int[MAX_NODES * 3];
    /** 每节点信号强度（下标与 {@link #nodes} 对齐：节点 i 的坐标在 {@code nodes[i*3..i*3+2]}）。 */
    private final int[] strength = new int[MAX_NODES];
    private int nodeCount = 0;
    /** 强度 > 0 的节点数（HaulSystem 的前置条件）。 */
    private int poweredCount = 0;
    /** 直接邻接电源的导体数（= 松弛种子数，即"真实激励点"）。 */
    private int sourceCount = 0;

    /** {@code cellKey → 节点下标}（每 tick 重建；复用同一实例避免逐 tick 分配）。 */
    private final HashMap<Long, Integer> cellToNode = new HashMap<Long, Integer>(512);

    @Override public String name() { return "wire"; }

    @Override
    public void update(World w, SeededRNG rng) {
        nodeCount = 0; poweredCount = 0; sourceCount = 0;
        float range = w.config.wireRange;
        if (range <= 0f) return;                  // 出厂默认 → 零写入

        // ---- 1) 收集导体：WIRE（红石导线）∪ LAMP（炉灯兼作导线，向后兼容）----
        //   各自 cellsOfType 保证 (x,y,z) 升序 → 合并后仍是确定序（先 WIRE 后 LAMP）。
        collect(w, Blocks.WIRE.index);
        collect(w, Blocks.LAMP.index);
        if (nodeCount == 0) return;
        int maxSteps = (int) Math.floor(range);
        if (maxSteps < 1) maxSteps = 1;

        // ---- 2) 建索引 + 种电源 ----
        cellToNode.clear();
        for (int i = 0; i < nodeCount; i++) {
            strength[i] = 0;
            cellToNode.put(Long.valueOf(World.cellKey(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2])),
                    Integer.valueOf(i));
        }
        for (int i = 0; i < nodeCount; i++) {
            int x = nodes[i * 3], y = nodes[i * 3 + 1], z = nodes[i * 3 + 2];
            int s = 0;
            // 元件注入：**方向敏感** —— 邻居在 k 方向，只有它「朝我发射」才算数。
            // 邻居朝我的方向 = 从我指向它的方向的反向 = opposite(k)（k^1）。这一步是第十批
            // 「比较器/中继器只从背面进、只朝正面出」的落点；LEVER/BUTTON/PLATE 是<b>全向</b>发射，
            // 故对它们而言这一改动等价于旧行为（既有门禁与实机手感不变）。
            for (int k = 0; k < Facing.COUNT; k++) {
                //  ⚠️ 这里必须用 **sourceStrengthToward**（只认"外部电源"），**不能**用 strengthToward。
                //  后者对导体返回它自己的 meta —— 那会让"邻居的当前强度"变成种子，而种子**不减 1**，
                //  于是每 tick 整条链自己把自己抬高一格（实测把 15 格处从 1 抬到 2、断链后残值不减）。
                //  衰减由下面的松弛负责（邻接 −1），种子只该来自真正的激励源。
                int nb = RedstoneLogicSystem.sourceStrengthToward(w, x + Facing.DX[k], y + Facing.DY[k], z + Facing.DZ[k],
                        Facing.opposite(k));
                if (nb > s) s = nb;
            }
            if (s == 0 && touchesSource(w, x, y, z)) s = MAX_SIGNAL;   // 旧模型：邻煤矿的炉灯 = 满强度
            if (s > 0) { strength[i] = s; sourceCount++; }
        }

        // ---- 3) 逐格衰减：固定轮数迭代松弛 ----
        //  为什么不用"队列 BFS"：各电源注入强度**可以不同**（比较器是模拟输出）。
        //  用"取最大值"的松弛（Bellman-Ford 式）天然支持异强源，且**每轮的更新与遍历顺序无关**
        //  （只有 `best > strength[i]` 才写）→ 语义比手写优先队列短且不容易写错。
        //  轮数 = 最长可能路径（≤ maxSteps）即收敛；每轮内部仍按升序遍历，保证同种子同输入逐字节可复现。
        int rounds = Math.min(maxSteps, MAX_ROUNDS);
        for (int round = 0; round < rounds; round++) {
            boolean changed = false;
            for (int i = 0; i < nodeCount; i++) {
                int x = nodes[i * 3], y = nodes[i * 3 + 1], z = nodes[i * 3 + 2];
                int best = strength[i];
                for (int k = 0; k < 6; k++) {
                    Integer j = cellToNode.get(Long.valueOf(
                            World.cellKey(x + Facing.DX[k], y + Facing.DY[k], z + Facing.DZ[k])));
                    if (j == null) continue;                        // 邻居不是导体 → 信号到此为止（不穿墙）
                    int cand = strength[j.intValue()] - 1;
                    if (cand > best) best = cand;
                }
                if (best > strength[i]) { strength[i] = best; changed = true; }
            }
            if (!changed) break;                                    // 收敛
        }

        // ---- 4) 强度写回节点格 meta（0..15）+ 变化时标脏 ----
        //  meta 不进 hashState（方块状态）→ 零漂移；markDirty 只触发区块网格重建（dirtyChunks 亦不进指纹）。
        for (int i = 0; i < nodeCount; i++) {
            int v = strength[i] > 0 ? strength[i] : 0;
            if (v > 0) poweredCount++;
            int x = nodes[i * 3], y = nodes[i * 3 + 1], z = nodes[i * 3 + 2];
            if (w.getMeta(x, y, z) != v) { w.setMeta(x, y, z, v); w.markDirty(x, y, z); }
        }
    }

    /** 追加收集某类导体（有界：达 {@link #MAX_NODES} 即停）。 */
    private void collect(World w, int blockIdx) {
        Collection<int[]> cells = w.cellsOfType(blockIdx);
        for (int[] c : cells) {
            if (nodeCount >= MAX_NODES) return;
            int o = nodeCount * 3;
            nodes[o] = c[0]; nodes[o + 1] = c[1]; nodes[o + 2] = c[2];
            nodeCount++;
        }
    }

    /** 六邻里是否有煤矿（= 旧模型里"该灯具接了发电炉"）。 */
    private boolean touchesSource(World w, int x, int y, int z) {
        int c = Blocks.COAL_ORE.index;
        return w.getBlock(x + 1, y, z) == c || w.getBlock(x - 1, y, z) == c
                || w.getBlock(x, y + 1, z) == c || w.getBlock(x, y - 1, z) == c
                || w.getBlock(x, y, z + 1) == c || w.getBlock(x, y, z - 1) == c;
    }

    // ---------------- 观测（渲染层 / 门禁 / HaulSystem 消费） ----------------

    /** 导体总数。 */
    public int nodeCount() { return nodeCount; }

    /** 强度 > 0 的节点数。 */
    public int poweredCount() { return poweredCount; }

    /** 直接邻接电源的导体数（松弛种子数 = "真实激励点"）。 */
    public int sourceCount() { return sourceCount; }

    /**
     * 指定格的<b>信号强度</b>（0..{@link #MAX_SIGNAL}；非导体或不在网内 → 0）。
     * 纯只读、线性扫节点表（≤ {@link #MAX_NODES}）→ 不写世界、不消费 RNG。
     *
     * <p><b>为什么有它</b>：HUD 要显示"这一格是几级信号"（第九批把导线从"亮/灭"升级成"刻度"）。
     * 第九批同时<b>删掉了旧的 {@code poweredAt}</b> —— 它已被本方法取代（留着就是死代码）。
     */
    public int strengthAt(int x, int y, int z) {
        for (int i = 0; i < nodeCount; i++) {
            if (nodes[i * 3] == x && nodes[i * 3 + 1] == y && nodes[i * 3 + 2] == z) return strength[i];
        }
        return 0;
    }
}
