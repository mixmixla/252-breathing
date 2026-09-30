package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 火山灰沉降（地质/气象）：空中飘落的火山灰/烟尘以极低概率在暴露地表面积聚成 <b>ASH</b> 层，
 * 让山体与废墟随时间被薄灰覆盖——演示“沉降”这一缓慢涌现。纯确定性（逐列固定迭代序 + simStream 概率）。
 *
 * <p><b>2026-09-18 批 B</b>：此前它只能拿 {@code STONE} 占位，注释自陈
 * 「不复用新方块（硬约束：不碰 Blocks.java）—— 那是材料表贫瘠年代的妥协」。
 * 现在材料规格层已就位（{@code assets/content/materials/*.json}），灰有了自己的材质：
 * {@code cellType=powder}（可被更重的材料压开）+ {@code density=14}（重于水 10 → 灰会沉入水底）
 * + 自己的颜色与挖掘粒子。
 *
 * <p>⚠️ 本改动<b>会改变地形</b>（灰从 STONE 变成 ASH，密度/可位移性都不同）—— 属有意演进，
 * 与 {@code densityFlow} 的开启同批次。
 *
 * <p><b>第三十五批（性能/收敛修复）</b>：加了灰层<b>厚度上限</b> {@link #ASH_MAX_LAYERS}。
 * 原实现无上限 ⇒ 灰会在一列上无限增厚 ⇒ 10 分钟占满世界 22%、单 tick 耗时 ×2.7。
 * 详见该常量的注释（含实测数据与"判据必须放在随机抽取之后"的原因）。
 */
public final class AshFallSystem implements System {
    @Override public String name() { return "ash"; }

    /**
     * 灰层<b>厚度上限</b>（格）。
     *
     * <p><b>为什么必须有它（第三十五批）</b>：原实现没有上限，而 {@code surfaceY} 是"该列最顶实心格"，
     * 灰是 solid ⇒ 灰落上去后 surfaceY 随之抬升 ⇒ <b>同一列下一 tick 还能再落一层</b>，
     * 每 tick 全图约 100 格新灰（{@code SX*SZ=25600} 列 × 0.4%）。实测 12000 tick（世界时间 10 分钟）：
     * 灰从 59,417 涨到 <b>647,650</b>（占世界 22%，吃掉 93% 失去的空气），
     * 而单 tick 中位耗时同步从 3.5ms 涨到 9.4ms —— 因为"<b>世界变满</b>"是<b>所有系统共同变贵</b>的根因
     * （连全图常量扫描的 {@code sand} 都 ×2.0，它涨的是循环体里的 {@code setBlock}）。
     * 按此速率约 30~40 分钟灰会填满世界（2.87M 格）⇒ 这是<b>收敛性缺陷</b>，不是"生机勃勃"。
     *
     * <p>上限取 <b>1</b> = 本类注释本来就写的"<b>薄灰</b>覆盖"（灰沉积成单层薄壳，不再无限增厚）。
     * 想调厚调薄只改这一个数：灰总量上限 ≈ 可落灰列数 × 本值。
     */
    private static final int ASH_MAX_LAYERS = 1;

    @Override
    public void update(World w, SeededRNG rng) {
        // 批⑥：火山灰全局上限（派生式）。判据置于 rng 抽取之后 ⇒ 不位移本系统随机流。
        // 背景：ASH_MAX_LAYERS=1 只限"每列一层"；但 ASH 是 powder+密度 14，经 densityFlow 填洼堆积，
        // 全局量仍线性增长（实测 96² 跑 40000 tick 达 2.37× 地表面积、全图头号增长者）⇒ 补全局上限。
        int remain = w.spreadHeadroom(Blocks.ASH.index);
        // 批⑩：扁平化 surfaceY[x] / mat[x] 行（减少每列字段间接寻址）；条件序 / rng 抽取点不变。
        final int AIRB = Blocks.AIR.index;
        final int SY = w.SY;
        final int[][] sy = w.surfaceY;
        final int[][][] mat = w.mat;
        // 全列固定序扫描；随机仅走 simStream 子流（rng 已是逐 tick 派生流）
        for (int x = 0; x < w.SX; x++) {
            final int[] syx = sy[x];
            final int[][] mx = mat[x];
            for (int z = 0; z < w.SZ; z++) {
                int top = syx[z];
                if (top < 0) continue;
                if (top + 1 >= SY) continue;
                // 仅落在陆地（水面/空中不积灰：surfaceY 取实心，顶上为水则跳过）
                if (mx[top + 1][z] != AIRB) continue;
                if (rng.nextDouble() < 0.004) {
                    // ⚠️ 判据（厚度 + 全局上限）必须放在 rng.nextDouble() **之后**：若放到它前面，
                    // 某些列会"提前 continue" ⇒ 本系统自己的随机数抽取次数改变 ⇒ 全图随机流整体错位，
                    // 改动面就从"灰不再无限增厚"扩大到"整个世界重新演化"。放后面 ⇒ 本系统的抽取序列逐字不变。
                    if (ashLayers(w, x, top, z) < ASH_MAX_LAYERS && remain > 0) {
                        w.setBlock(x, top + 1, z, Blocks.ASH.index);   // 灰层堆积（自己的材质，不再借 STONE）
                        remain--;
                    }
                }
            }
        }
    }

    /** 该列地表顶端<b>连续</b>的 ASH 层数（往下数到上限即止 ⇒ 常数级开销）。 */
    private static int ashLayers(World w, int x, int top, int z) {
        int n = 0;
        for (int y = top; y >= 0 && n < ASH_MAX_LAYERS; y--) {
            if (w.mat[x][y][z] != Blocks.ASH.index) break;
            n++;
        }
        return n;
    }

    static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
