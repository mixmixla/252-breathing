package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 落沙（物理系统）：SAND 下落一格（带步长 stride 扫描，避免每 tick 全扫）。
 * 纯确定性（不消耗 rng），与 WaterFlowSystem 同构但作用在沙上——让崖壁沙堆随时间坍塌。
 *
 * <p><b>2026-09-18 密度驱动原型</b>：判据从「下方是<b>空气</b>」升级为
 * 「下方是空气 <b>或</b> 更轻的可位移材料（液体 / 粉末 / 气体）」——由
 * {@link core.world.WorldConfig#densityFlow} 控制。<b>⚠️ 出厂即开</b>（{@code DENSITY_FLOW_DFLT=true}；
 * 本注释原写"出厂关闭"是**原型期**设定，默认值已由批 B 的 {@code DensityFlowProbe} 门禁后翻转为开，现按实际更正）。
 *
 * <ul>
 *   <li><b>关闭时</b>：短路在第一个条件，写集与调用序列与历史版本<b>逐字节一致</b>
 *       （沙停在水面之上，这正是 {@code QuicksandSystem} 存在的理由）。</li>
 *   <li><b>打开时</b>：沙（density 20）会沉入水（10）底，水被顶到上方 —— 这就是 Noita
 *       用「一个标量 density 判换位」替代成堆专用系统的核心技巧。打开会改地形演化 → 需重锁基线。</li>
 * </ul>
 */
public final class SandFallSystem implements System {
    @Override public String name() { return "sand"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // SIM-PERF-4：原实现是 160*112*160/4 ≈ 71.7 万格/tick 的全量三维读，实测仍是头号热点
        // （直接计时 ≈1.3ms/tick ≈ 整 tick 的 45%；而全窗只有 ~1.8k 格沙 ⇒ 扫描访问了 386× 多余的格子）。
        //
        // ⚠️ 2026-09-29 第五刀（本批）：把"全窗 stride-2 扫描"改为"只遍历 SAND 体素"。
        //   等价性论证（`tools/SandFallEquivCheck` 逐 tick 逐字节钉住）：
        //     ① 原扫描**只访问** (x 偶 && z 偶) 的列、y∈[1,SY) ⇒ 只有偶数坐标的沙会动；
        //        本实现过滤 cellsOfType(SAND) 到 y≥1 && (x 偶 && z 偶)，**候选格集合完全相同**。
        //     ② cellsOfType 返回 (x,y,z) 升序 = 原扫描序（x 外层→y→z）；筛后相对顺序不变
        //        ⇒ setBlock 调用**序列**逐字节一致。
        //     ③ 原 `prev[z]` 恒等于当刻 mat[x][y-1][z]；本实现直接读 mat[x][y-1][z] 亦同值。
        //     ④ 沙只**向下**移动（落到已越过的 y-1）⇒ 不会在"未来迭代位置"凭空出现新沙
        //        ⇒ 快照集合不缺格、不重复处理。
        //   注：快照 `new ArrayList<>(...)` 是必要的 —— 遍历期间 setBlock 会改 mat/索引。
        final int SAND = Blocks.SAND.index;
        final boolean dens = w.config != null && w.config.densityFlow;
        java.util.List<int[]> cells = new java.util.ArrayList<>(w.cellsOfType(SAND));
        for (int i = 0, n = cells.size(); i < n; i++) {
            int[] c = cells.get(i);
            int x = c[0], y = c[1], z = c[2];
            if (y < 1) continue;                             // 原扫描 y 从 1 起
            if ((x & 1) != 0 || (z & 1) != 0) continue;      // 原 stride-2：仅偶数列被访问
            int below = w.mat[x][y - 1][z];
            if (below == Blocks.AIR.index || (dens && w.materials.sinksInto(SAND, below))) {
                w.setBlock(x, y - 1, z, SAND);               // 沙下移
                w.setBlock(x, y, z, below);                  // below 被顶到原位（AIR 时即旧行为）
            }
        }
    }
}
