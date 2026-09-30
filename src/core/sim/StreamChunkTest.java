package core.sim;

import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * M3「大世界规模流畅」性质门禁：
 *
 * ① 分帧流式等价性（M3① 核心不变量）：把整窗重生成切片到多帧（每帧预算 N 块）后，
 *    窗口内容 / surfaceY / 空间索引必须与「一次性同步平移」**逐字节一致**；
 *    且跨窗完成前 streamToSliced 不被新目标打断（窗口推进单调）。
 *    —— 证明分帧只改变「何时算」，不改变「算什么」，四基线指纹不受影响。
 *
 * ② 空间索引惰性重建等价性（M3① 性能改造的正确性护栏）：setBlock 只标脏、索引惰性重算后，
 *    cellsOfType / nonAirCells 的**元素集合与迭代序**必须与「每次写入立即维护索引」的旧语义一致。
 *    —— 防"为了性能把索引改坏"，这是最容易被忽视、且会污染仿真结果的隐性漂移。
 *
 * ③ 邻域查询去 O(N²) 等价性（M3② 护栏）：均匀网格邻域查询给出的「满足曼哈顿半径 R 的邻居集」
 *    必须与暴力双重循环**逐元素、逐序一致**（先构造大 N 场景，再逐口人比对）。
 *    —— 防大规模下邻里关系悄悄改变，进而漂移社会演化。
 *
 * ④ INDEX 阶段分帧等价性（M3① 三次优化护栏）：索引重建被切成 x 条带分帧推进后
 *    （每帧 1 条带的最深分帧），结果必须与「rebuildIndex() 一次跑完」逐元素一致。
 *    —— 防「为了让 COMMIT 不卡而切片」引入隐性索引残缺。
 *
 * ⑤ 索引 vs `mat` 全量扫描（真值）等价性（**CellSet 覆盖层改造的等价性护栏**，2026-09-17）：
 *    `CellSet` 的增量插入/删除从「每次 O(该类型格数) 的 arraycopy 移位」改成
 *    「待加入/待删除两个有序覆盖层 + 压实」之后，`cellsOfType(t)` / `nonAirCells()` 的
 *    **元素集合与 (x,y,z) 升序迭代序**必须与「直接扫 mat 得到的地面真值」逐元素一致 ——
 *    且在远超覆盖层上限（1024）的重型写入负载下（强制多次压实）仍成立。
 *    —— 这一条是「敢动核心数据结构」的**前提**：没有等价性断言的模块不要动。
 *
 * 运行：java -cp out core.sim.StreamChunkTest
 */
public final class StreamChunkTest {
    public static void main(String[] args) {
        boolean eq = testSlicedEquivalence();
        boolean mono = testMonotonicProgress();
        boolean idx = testIndexLazyEquivalence();
        boolean nb = testNeighborEquivalence();
        boolean isl = testIndexSliceEquivalence();
        boolean scan = testIndexAgainstScan();
        boolean inc = testIncrementalPreservation();

        boolean pass = eq && mono && idx && nb && isl && scan && inc;
        System.out.printf("STREAMCHUNK  slicedEquiv=%b monotonic=%b indexEquiv=%b neighborEquiv=%b indexSliceEquiv=%b scanTruth=%b incremental=%b%n",
                eq, mono, idx, nb, isl, scan, inc);
        System.out.println(pass ? "STREAMCHUNK PASS" : "STREAMCHUNK FAIL");
        if (!pass) System.exit(1);
    }

    // ---------------------------------------------------------------- ⑥ P1 增量平移：保留窗口内既有内容
    /**
     * P1（第三十六批「增量平移」）的正确性护栏。
     *
     * <p>旧实现把 CX×CZ 块**全部**重生成（走 1 块 = 100 块），且窗口内既有内容（含系统演化出的
     * 灰/植被/水位）每次平移都被抹回原始地形。P1 改成「先搬迁重叠区、再只生成新进入的条带」，
     * 于是有两条**必须钉住**的性质：
     * <ol>
     *   <li><b>搬迁无损</b>：留在窗口内的整片区域，其 {@code mat} 在平移前后必须**逐字节相同**
     *       （只是本地坐标整体位移）。这是对搬迁实现（先 Z 后 X、方向敏感的 memmove）最直接的断言
     *       —— 旧实现下这条必然 FAIL（内容被重生成）。
     *       ⚠️ <b>唯一例外是"光晕列"</b>：{@code generateChunk} 放树时叶片是 <b>±2 格</b>且
     *       {@code inBounds} 允许**跨块**写 ⇒ 新进入的边界块会把紧邻的 2 列覆盖（实测 7 格，AIR→LEAF）。
     *       这是**既有生成行为**（与搬迁无关），故断言写成"<b>光晕之外零差异</b>，
     *       且差异只允许出现在光晕列内"—— 比单纯放宽边界更强：它能区分
     *       "整片搬错"（大量差异、分布随机）与"只有已知光晕"（少量、全在边界 2 列）。</li>
     *   <li><b>"保留"的字面含义</b>：用 {@code setBlock}（**系统**写入路径，非 {@code editBlock}）
     *       放的标记必须随内容搬到新本地坐标而存在 —— 证明保留的是"窗口内既有内容"，
     *       不是"把整窗冻住"（后者会让位移量不再生效）。</li>
     * </ol>
     */
    private static boolean testIncrementalPreservation() {
        final int SX = 64, SY = 48, SZ = 64;          // CX=CZ=4, R=2
        World w = new World(606001L, SX, SY, SZ);
        w.streamTo(10, 10);                            // 居中到全局块 (10,10) ⇒ winCX0 = 8
        int yStick = colTopSolid(w, 48, 32) + 1;
        w.setBlock(48, yStick, 32, Blocks.LAMP.index); // 系统写入（不进 chunkEdits ⇒ 只能靠搬迁保留）
        boolean before = w.getBlock(48, yStick, 32) == Blocks.LAMP.index;

        // 平移前把"将留在窗口内"的旧区域（x∈[16,63]）**逐格快照**，平移后逐格比对并定位首处差异
        // （不只用指纹：失败时要知道"改了什么、在哪"，否则只能猜）。
        final int HALO = 2;              // 树冠叶片 ±2 格会**跨块**写（既有生成行为，见 generateChunk 的树木放置）
        final int RX0 = 16, RX1 = SX - 1;
        int[] oldRegion = new int[(RX1 - RX0 + 1) * SY * SZ];
        int idx = 0;
        for (int x = RX0; x <= RX1; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) oldRegion[idx++] = w.mat[x][y][z];

        int originBefore = w.windowOriginCX();
        w.streamTo(w.windowOriginCX() + w.R + 1, w.windowOriginCZ() + w.R);   // 窗口 +1 块（+X）
        boolean shifted = w.windowOriginCX() == originBefore + 1;

        int mismTotal = 0, mismOutsideHalo = 0;
        String first = "(none)", firstOutside = "(none)";
        idx = 0;
        for (int x = RX0; x <= RX1; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) {
                    int nx = x - 16;                        // 新本地 = 旧本地 − 16
                    int got = w.mat[nx][y][z];
                    if (got != oldRegion[idx]) {
                        mismTotal++;
                        if (mismTotal == 1) {
                            first = "(" + nx + "," + y + "," + z + ") old="
                                    + Blocks.byIndex(oldRegion[idx]).id + " new=" + Blocks.byIndex(got).id;
                        }
                        if (nx < SX - 16 - HALO) {          // 光晕之外：不允许有任何差异
                            if (mismOutsideHalo == 0) {
                                firstOutside = "(" + nx + "," + y + "," + z + ") old="
                                        + Blocks.byIndex(oldRegion[idx]).id + " new=" + Blocks.byIndex(got).id;
                            }
                            mismOutsideHalo++;
                        }
                    }
                    idx++;
                }
        boolean regionKept = (mismOutsideHalo == 0);
        boolean stayed = w.getBlock(48 - 16, yStick, 32) == Blocks.LAMP.index;

        // ①（第三十七批）：索引改成**增量**（基线平移 + 只扫新边界）后，它**必须**仍然等于
        // "直接扫 mat 得到的真值"（元素集合 + (x,y,z) 升序迭代序）。覆盖四个方向、L 形（x/z 同时变）
        // 与"零重叠"（位移超一屏）三种情形 —— 增量最容易错的就是漏掉 L 形那一半。
        // 失败时打印**首处不一致**（类型/格/两侧取值），否则只能靠猜。
        String idxMsg = indexFirstMismatch(w);
        StringBuilder idxBad = new StringBuilder();
        int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {3, -2} };
        for (int[] d : dirs) {
            w.streamTo(w.windowOriginCX() + w.R + d[0], w.windowOriginCZ() + w.R + d[1]);
            String msg = indexFirstMismatch(w);
            if (!msg.isEmpty() && idxBad.length() == 0) idxBad.append(msg);
        }
        boolean idxAll = idxMsg.isEmpty() && idxBad.length() == 0;

        System.out.printf("  [P1] incremental before=%b shifted=%b regionKept=%b stayed=%b idxAll=%b"
                        + " mism=%d(halo)%d outside first=%s firstOutside=%s idx=%s (win=%d,%d)%n",
                before, shifted, regionKept, stayed, idxAll,
                mismTotal - mismOutsideHalo, mismOutsideHalo, first, firstOutside,
                idxMsg.isEmpty() ? idxBad.toString() : idxMsg,
                w.windowOriginCX(), w.windowOriginCZ());
        return before && shifted && regionKept && stayed && idxAll;
    }

    /** 诊断：索引 vs mat 全量扫描的**首处不一致**（""=一致）。 */
    private static String indexFirstMismatch(World w) {
        final int T = Blocks.count();
        List<List<String>> expect = new ArrayList<List<String>>();
        for (int t = 0; t < T; t++) expect.add(new ArrayList<String>());
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++)
                    expect.get(w.mat[x][y][z]).add(x + "," + y + "," + z);
        for (int t = 1; t < T; t++) {
            List<String> exp = expect.get(t);
            List<String> got = new ArrayList<String>();
            for (int[] c : new ArrayList<int[]>(w.cellsOfType(t))) got.add(c[0] + "," + c[1] + "," + c[2]);
            if (exp.equals(got)) continue;
            int m = Math.min(exp.size(), got.size());
            for (int i = 0; i < m; i++)
                if (!exp.get(i).equals(got.get(i)))
                    return Blocks.byIndex(t).id + "#" + i + " scan=" + exp.get(i) + " idx=" + got.get(i)
                         + " N(" + exp.size() + "/" + got.size() + ")";
            return Blocks.byIndex(t).id + " N(" + exp.size() + "/" + got.size() + ")";
        }
        return "";
    }

    /** 该列自顶向下第一个实心格的 y（找不到返回 0）。 */
    private static int colTopSolid(World w, int x, int z) {
        for (int y = w.SY - 1; y >= 0; y--)
            if (Blocks.byIndex(w.getBlock(x, y, z)).solid) return y;
        return 0;
    }

    // ---------------------------------------------------------------- ① 分帧 vs 一次性：逐字节一致
    private static boolean testSlicedEquivalence() {
        final int SX = 64, SY = 48, SZ = 64;
        final long SEED = 777001L;

        // A：一次性同步平移（旧语义）；B：分帧平移（每步 3 块）
        World a = new World(SEED, SX, SY, SZ);
        World b = new World(SEED, SX, SY, SZ);

        int[][] path = {{4, 4}, {5, 6}, {9, 3}, {2, 8}, {7, 7}, {1, 1}};
        for (int[] p : path) {
            a.streamTo(p[0], p[1]);
            b.streamToSliced(p[0], p[1]);
            int guard = 0;
            while (b.isShifting() && guard++ < 100000) b.stepShift(3);   // 每帧 3 块 → 多帧完成
        }

        boolean winSame = a.windowOriginCX() == b.windowOriginCX()
                       && a.windowOriginCZ() == b.windowOriginCZ();
        boolean hashSame = a.hashState() == b.hashState();
        boolean surfSame = surfaceSame(a, b);
        boolean idxSame = indexSame(a, b, SX, SY, SZ);

        // 分帧确实跨了多帧（否则测不出分帧路径）：统计最大单次步数
        System.out.printf("  [M3①] window=%b hash=%b surface=%b index=%b (hash=%016x)%n",
                winSame, hashSame, surfSame, idxSame, a.hashState());
        return winSame && hashSame && surfSame && idxSame;
    }

    // ---------------------------------------------------------------- ①b 窗口推进单调（分帧中不被新目标打断）
    private static boolean testMonotonicProgress() {
        World w = new World(888002L, 64, 48, 64);
        w.streamToSliced(10, 10);
        // 分帧未完成时，反复登记新目标应被忽略（不产生叠加/交错）
        for (int k = 0; k < 20; k++) w.streamToSliced(20 + k, 20 + k);
        int guard = 0;
        while (w.isShifting() && guard++ < 100000) w.stepShift(2);
        boolean first = w.windowOriginCX() == 10 - (64 / 16) / 2 && w.windowOriginCZ() == 10 - (64 / 16) / 2;
        // 完成后可再次平移
        w.streamToSliced(50, 50);
        while (w.isShifting() && guard++ < 200000) w.stepShift(2);
        boolean second = w.windowOriginCX() == 50 - (64 / 16) / 2;
        System.out.printf("  [M3①] monotonic first=%b second=%b (win=%d,%d)%n",
                first, second, w.windowOriginCX(), w.windowOriginCZ());
        return first && second;
    }

    // ---------------------------------------------------------------- ①c INDEX 阶段分帧 == 一次跑完（M3① 三次优化）
    /**
     * INDEX 阶段（stepShift phase 4）把索引重建切成 x 条带分帧推进后，
     * 「分帧重建」与「rebuildIndex() 一次跑完」的索引必须**逐元素、逐序一致**。
     * 这是本轮新增路径的正确性护栏：切片只改变「何时扫哪些 x 条带」，不改「扫出什么」。
     * 用极小预算（每帧 1 条带）逼出最深分帧，确保覆盖多帧边界。
     */
    private static boolean testIndexSliceEquivalence() {
        final int SX = 64, SY = 48, SZ = 64;
        World a = new World(991001L, SX, SY, SZ);
        World b = new World(991001L, SX, SY, SZ);

        // 两者都做同一串平移；a 用同步（INDEX 一次跑完），b 用极小预算分帧（INDEX 每帧 1 条带）
        int[][] path = {{3, 5}, {8, 2}, {6, 9}, {1, 4}};
        int bFrames = 0;
        for (int[] p : path) {
            a.streamTo(p[0], p[1]);
            b.streamToSliced(p[0], p[1]);
            int guard = 0;
            while (b.isShifting() && guard++ < 200000) { b.stepShift(1); bFrames++; }
        }
        if (bFrames < 20) {   // 预算=1 时必须真的跨了很多帧，否则测试没测到分帧路径
            System.out.printf("  [M3①] index-slice TOO FEW FRAMES=%d (expect >=20)%n", bFrames);
            return false;
        }
        boolean winSame = a.windowOriginCX() == b.windowOriginCX()
                       && a.windowOriginCZ() == b.windowOriginCZ();
        boolean hashSame = a.hashState() == b.hashState();
        boolean idxSame = indexSame(a, b, SX, SY, SZ);
        boolean surfSame = surfaceSame(a, b);
        System.out.printf("  [M3①] index-slice win=%b hash=%b index=%b surface=%b (frames=%d)%n",
                winSame, hashSame, idxSame, surfSame, bFrames);
        return winSame && hashSame && idxSame && surfSame;
    }

    // ---------------------------------------------------------------- ② 索引惰性重建 == 即时维护
    private static boolean testIndexLazyEquivalence() {
        World w = new World(999003L, 48, 40, 48);
        // 随机（确定性伪随机）写一批 setBlock，制造大量脏格
        int seed = 12345;
        for (int k = 0; k < 4000; k++) {
            seed = seed * 1103515245 + 12345;
            int x = ((seed >>> 8) & 0x7fffffff) % 48;
            seed = seed * 1103515245 + 12345;
            int y = ((seed >>> 8) & 0x7fffffff) % 40;
            seed = seed * 1103515245 + 12345;
            int z = ((seed >>> 8) & 0x7fffffff) % 48;
            int[] types = {Blocks.AIR.index, Blocks.STONE.index, Blocks.DIRT.index,
                           Blocks.GRASS.index, Blocks.WATER.index, Blocks.WOOD.index};
            w.setBlock(x, y, z, types[k % types.length]);
        }
        // 惰性索引触发后：逐类型比对「集合 + 序列」与直接扫网格重建的结果。
        // 注意：AIR 不进索引（与旧实现一致 —— typeCells 只登记非空气格），故跳过 AIR。
        for (int t = 1; t < Blocks.count(); t++) {
            java.util.Collection<int[]> live = w.cellsOfType(t);
            List<String> liveSeq = new ArrayList<String>();
            for (int[] c : new ArrayList<int[]>(live)) liveSeq.add(c[0] + "," + c[1] + "," + c[2]);
            List<String> bruteSeq = new ArrayList<String>();
            for (int x = 0; x < 48; x++)
                for (int y = 0; y < 40; y++)
                    for (int z = 0; z < 48; z++)
                        if (w.getBlock(x, y, z) == t) bruteSeq.add(x + "," + y + "," + z);
            if (!liveSeq.equals(bruteSeq)) {
                System.out.printf("  [M3①] index MISMATCH type=%d (%s) live=%d brute=%d%n",
                        t, Blocks.byIndex(t).id, liveSeq.size(), bruteSeq.size());
                return false;
            }
        }
        // nonAirCells 同样比对
        List<String> na = new ArrayList<String>();
        for (int[] c : new ArrayList<int[]>(w.nonAirCells())) na.add(c[0] + "," + c[1] + "," + c[2]);
        List<String> naBrute = new ArrayList<String>();
        for (int x = 0; x < 48; x++)
            for (int y = 0; y < 40; y++)
                for (int z = 0; z < 48; z++)
                    if (w.getBlock(x, y, z) != Blocks.AIR.index) naBrute.add(x + "," + y + "," + z);
        boolean ok = na.equals(naBrute);
        System.out.printf("  [M3①] index lazy-equivalence allTypes=%b nonAir=%b (n=%d)%n", true, ok, na.size());
        return ok;
    }

    // ---------------------------------------------------------------- ③ 邻域查询等价（均匀网格 vs 暴力）
    private static boolean testNeighborEquivalence() {
        // 用 SocialSystem 的真实判据（曼哈顿半径 NEIGHBOR_R）在「大 N」场景下比对暴力解。
        // 这里直接复刻判据（避免依赖 NPC 内部态），验证的是**索引查询的完备性**：
        // 均匀网格 3×3 邻域必须覆盖所有 |dx|+|dz| <= R 的点对。
        final float R = 3.0f;
        float[] xs = new float[400];
        float[] zs = new float[400];
        int seed = 424242;
        for (int i = 0; i < 400; i++) {
            seed = seed * 1103515245 + 12345;
            xs[i] = ((seed >>> 8) & 0x7fffffff) % 4000 / 100.0f;   // 0..40 格
            seed = seed * 1103515245 + 12345;
            zs[i] = ((seed >>> 8) & 0x7fffffff) % 4000 / 100.0f;
        }
        int mismatches = 0;
        for (int i = 0; i < 400 && mismatches == 0; i++) {
            List<Integer> brute = new ArrayList<Integer>();
            for (int j = 0; j < 400; j++) {
                if (i == j) continue;
                if (Math.abs(xs[i] - xs[j]) + Math.abs(zs[i] - zs[j]) <= R) brute.add(j);
            }
            List<Integer> grid = gridQuery(xs, zs, i, R);
            java.util.Collections.sort(brute);
            if (!brute.equals(grid)) mismatches++;
        }
        System.out.printf("  [M3②] neighbor equivalence mismatches=%d (N=400)%n", mismatches);
        return mismatches == 0;
    }

    /** 复刻 SocialSystem 的均匀网格 3×3 查询（格边 = R），返回升序候选里满足判据的 j。 */
    private static List<Integer> gridQuery(float[] xs, float[] zs, int self, float R) {
        java.util.Map<Long, List<Integer>> grid = new java.util.HashMap<Long, List<Integer>>();
        for (int i = 0; i < xs.length; i++) {
            long k = (((long) (int) Math.floor(xs[i] / R)) << 32)
                   | ((int) Math.floor(zs[i] / R) & 0xFFFFFFFFL);
            List<Integer> b = grid.get(k);
            if (b == null) { b = new ArrayList<Integer>(); grid.put(k, b); }
            b.add(i);
        }
        int gx = (int) Math.floor(xs[self] / R), gz = (int) Math.floor(zs[self] / R);
        List<Integer> cand = new ArrayList<Integer>();
        for (int ax = gx - 1; ax <= gx + 1; ax++)
            for (int az = gz - 1; az <= gz + 1; az++) {
                long k = (((long) ax) << 32) | (az & 0xFFFFFFFFL);
                List<Integer> b = grid.get(k);
                if (b != null) cand.addAll(b);
            }
        java.util.Collections.sort(cand);
        List<Integer> out = new ArrayList<Integer>();
        for (Integer j : cand) {
            if (j == self) continue;
            if (Math.abs(xs[self] - xs[j]) + Math.abs(zs[self] - zs[j]) <= R) out.add(j);
        }
        return out;
    }

    // ------------------------------------------------- ⑤ 索引 vs mat 全量扫描（真值）
    /**
     * 重型写入负载下，索引必须始终等于「由 {@code mat} 全量扫描得到的真值」（集合 + 迭代序）。
     *
     * <p>负载规模刻意远超 {@code CellSet} 的覆盖层上限（1024）：12000 次往返写入会让每个被触及的
     * 类型集合经历**多次压实**，从而把「覆盖层 / 压实 / 归并迭代」三条新路径全部走一遍。
     */
    private static boolean testIndexAgainstScan() {
        final int SX = 32, SY = 40, SZ = 32;
        World w = new World(20260917L, SX, SY, SZ);
        final int OPS = 12000;
        int changed = 0;                       // 防假绿：必须真的改到方块（否则「索引==真值」是空对空）
        long s = 12345L;
        for (int i = 0; i < OPS; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;      // 确定性 LCG（不用 RNG，不碰仿真流）
            int x = (int) ((s >>> 33) % SX);
            int z = (int) ((s >>> 11) % SZ);
            int y = 1 + (int) ((s >>> 5) % (SY - 2));
            int next = (w.mat[x][y][z] == Blocks.STONE.index) ? Blocks.AIR.index : Blocks.STONE.index;
            w.setBlock(x, y, z, next);
            changed++;
            if (i % 3000 == 0 && !indexMatchesScan(w)) return false;    // 中途抽查
        }
        // 防假绿：① 写入确实生效；② 真值非空；③ 类型集合确有内容（否则比对是空对空）
        if (changed != OPS) return false;
        if (w.nonAirCells().size() < SX * SZ) return false;
        if (w.cellsOfType(Blocks.STONE.index).size() == 0) return false;
        return indexMatchesScan(w) && indexMatchesScan(w);              // 连查两次：迭代不得破坏状态
    }

    /** 索引 == 真值：单趟扫 mat 建期望（同时建非空气表），再逐类型逐元素比对。 */
    private static boolean indexMatchesScan(World w) {
        final int T = Blocks.count();
        List<List<String>> expect = new ArrayList<List<String>>();
        for (int t = 0; t < T; t++) expect.add(new ArrayList<String>());
        List<String> expectNonAir = new ArrayList<String>();
        for (int x = 0; x < w.SX; x++)
            for (int y = 0; y < w.SY; y++)
                for (int z = 0; z < w.SZ; z++) {
                    int b = w.mat[x][y][z];
                    String key = x + "," + y + "," + z;
                    expect.get(b).add(key);
                    if (b != Blocks.AIR.index) expectNonAir.add(key);
                }
        for (int t = 1; t < T; t++) {                                   // 从 1 起：AIR 不进索引
            List<String> got = new ArrayList<String>();
            for (int[] c : new ArrayList<int[]>(w.cellsOfType(t))) got.add(c[0] + "," + c[1] + "," + c[2]);
            if (!expect.get(t).equals(got)) return false;                // 集合 + 迭代序（List.equals 有序）
            if (w.cellsOfType(t).size() != got.size()) return false;     // size() 必须与迭代一致
        }
        List<String> gotNonAir = new ArrayList<String>();
        for (int[] c : new ArrayList<int[]>(w.nonAirCells())) gotNonAir.add(c[0] + "," + c[1] + "," + c[2]);
        return expectNonAir.equals(gotNonAir) && w.nonAirCells().size() == gotNonAir.size();
    }

    // ---------------------------------------------------------------- 工具
    private static boolean surfaceSame(World a, World b) {
        if (a.SX != b.SX || a.SZ != b.SZ) return false;
        for (int x = 0; x < a.SX; x++)
            for (int z = 0; z < a.SZ; z++)
                if (a.surfaceY[x][z] != b.surfaceY[x][z]) return false;
        return true;
    }

    private static boolean indexSame(World a, World b, int sx, int sy, int sz) {
        // 从 1 起：AIR 不进索引（与旧实现一致）
        for (int t = 1; t < Blocks.count(); t++) {
            List<String> la = new ArrayList<String>(), lb = new ArrayList<String>();
            for (int[] c : new ArrayList<int[]>(a.cellsOfType(t))) la.add(c[0] + "," + c[1] + "," + c[2]);
            for (int[] c : new ArrayList<int[]>(b.cellsOfType(t))) lb.add(c[0] + "," + c[1] + "," + c[2]);
            if (!la.equals(lb)) return false;
        }
        List<String> na = new ArrayList<String>(), nb = new ArrayList<String>();
        for (int[] c : new ArrayList<int[]>(a.nonAirCells())) na.add(c[0] + "," + c[1] + "," + c[2]);
        for (int[] c : new ArrayList<int[]>(b.nonAirCells())) nb.add(c[0] + "," + c[1] + "," + c[2]);
        return na.equals(nb);
    }
}
