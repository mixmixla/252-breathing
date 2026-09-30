package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 藤蔓系统（生物）：WOOD/LEAF 正下方为空气时以低概率向下垂生 LEAF（藤蔓向下爬），直到落地。
 * 确定性：体素全扫描、固定迭代序、随机走 simStream 子流。
 *
 * <p><b>批⑧ 优化</b>：原实现把 WOOD、LEAF 两个（各自已升序的）索引拼成一个 {@code ArrayList} 再
 * {@code Collections.sort} —— O(n log n) 且每格 {@code new int[3]}。改为：两个索引先做**零分配**平铺快照
 * （{@code countOf/copyOf}），再**线性归并**（O(n)）。归并序 = (x,y,z) 升序 = 原排序序；
 * 逐格取值与 rng 抽取点不变 ⇒ 行为逐字节等价。
 * （本文不改动任何格的状态依赖：写 (x,y-1,z) 只落进"快照中本为 AIR 的格"，不会被本次遍历再读到。）
 */
public final class VineSystem implements System {
    @Override public String name() { return "vine"; }

    @Override
    public void update(World w, SeededRNG rng) {
        final int WOOD = Blocks.WOOD.index, LEAF = Blocks.LEAF.index, AIR = Blocks.AIR.index;
        final int SY = w.SY;
        final int nw = w.countOf(WOOD), nl = w.countOf(LEAF);
        int[] bw = new int[nw * 3]; w.copyOf(WOOD, bw);
        int[] bl = new int[nl * 3]; w.copyOf(LEAF, bl);
        // 线性归并两个已升序序列 → cells（(x,y,z) 升序，即原 Collections.sort 的结果）。
        final int n = nw + nl;
        int[] cells = new int[n * 3];
        int i = 0, j = 0, o = 0;
        while (i < nw && j < nl) {
            int a = i * 3, b = j * 3;
            if (cmp3(bw, a, bl, b) <= 0) { cells[o]=bw[a]; cells[o+1]=bw[a+1]; cells[o+2]=bw[a+2]; i++; }
            else                          { cells[o]=bl[b]; cells[o+1]=bl[b+1]; cells[o+2]=bl[b+2]; j++; }
            o += 3;
        }
        while (i < nw) { int a = i * 3; cells[o]=bw[a]; cells[o+1]=bw[a+1]; cells[o+2]=bw[a+2]; i++; o += 3; }
        while (j < nl) { int b = j * 3; cells[o]=bl[b]; cells[o+1]=bl[b+1]; cells[o+2]=bl[b+2]; j++; o += 3; }

        for (int k = 0; k < n; k++) {
            int b3 = k * 3;
            int x = cells[b3], y = cells[b3 + 1], z = cells[b3 + 2];
            // y<1 无下方格（原 getBlock(x,-1,z)=BEDROCK 不写、且不抽 rng）→ 合并进此判据，抽取序列不变。
            if (y < 1 || y >= SY - 1) continue;
            int b = w.mat[x][y][z];
            if (b != WOOD && b != LEAF) continue;
            if (w.mat[x][y - 1][z] == AIR && rng.nextDouble() < 0.02) {
                w.setBlock(x, y - 1, z, LEAF);
            }
        }
    }

    /** (x,y,z) 字典序比较：a[ai..ai+2] vs b[bi..bi+2]。 */
    private static int cmp3(int[] a, int ai, int[] b, int bi) {
        if (a[ai] != b[bi]) return a[ai] < b[bi] ? -1 : 1;
        if (a[ai + 1] != b[bi + 1]) return a[ai + 1] < b[bi + 1] ? -1 : 1;
        if (a[ai + 2] != b[bi + 2]) return a[ai + 2] < b[bi + 2] ? -1 : 1;
        return 0;
    }
}
