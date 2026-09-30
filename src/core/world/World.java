package core.world;

import core.agent.Npc;
import core.rng.SeededRNG;
import core.systems.Phase;
import core.systems.System;
import core.systems.SystemRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 体素世界核心状态（移植自 world/world.py 的 World 的“状态 + tick”骨架，升维到 3D）。
 *
 * 零漂移纪律：
 *  - {@link #rng} 是仿真主 RNG，一切仿真随机走它（经 deriveStream 派生）；
 *  - {@link #fxRng} 仅给渲染/演出用，仿真逻辑严禁读取它——见 {@link #hashState()} 不纳入 fxRng；
 *  - 同种子 → 地形、系统演化、解锁全部逐字节一致（DeterminismTest 验证）。
 *
 * 守恒：mass 数组记录每体素质量，build 操作扣料与 builtMass 加料相抵（与 Python mass_ledger 同构）。
 */
public final class World {

    public final int SX, SY, SZ;
    /** 区块边长（XZ 方向），SX/SZ 必须是它的整数倍。 */
    public static final int CHUNK = 16;
    public final long seed;

    // ---- 无限世界流式（滑动窗口）----
    // 当前“已加载窗口”覆盖的全局块坐标范围：[winCX0, winCX0+CX) × [winCZ0, winCZ0+CZ)。
    // 整个仿真/渲染都活在窗口内（mat 按窗口本地坐标索引，系统零改动）；
    // 地形由全局坐标的确定性函数生成，玩家跨块时窗口平移并重生成，编辑按块持久化。
    public final int CX, CZ, R;          // 窗口块数（XZ）与中心半宽（R = CX/2）
    private int winCX0 = 0, winCZ0 = 0;  // 窗口最小角块坐标（全局）
    private final java.util.Map<Long, Override> chunkEdits = new java.util.HashMap<>(); // 卸载块的编辑覆盖（持久化建造）
    // 仅记录“当前已加载且被改过”的全局块，卸载时只快照这些块——未编辑的块由确定性地形重生成，
    // 不放进 chunkEdits，从而保证无论玩家走多远、chunkEdits 只随“被编辑的块数”增长（RAM 有界）。
    private final java.util.Set<Long> editedChunks = new java.util.HashSet<>();

    public final int[][][] mat;     // 体素方块索引（mat[x][y][z]）
    public final float[][][] mass;  // 每体素质量（守恒演示）
    public int tick = 0;

    // ---- PERF-SIM 共享逐 tick 空间索引（M3① 重写：平铺有序数组，去 TreeSet 对象税）----
    // 语义契约（对外不变）：cellsOfType(idx) 返回该类型全部体素的“按 (x,y,z) 升序、坐标唯一”视图；
    // nonAirCells() 同理返回全部非空气体素。消费者只需「有序迭代 + size()」（21 处调用点均如此，
    // 且都在遍历前自行快照），故内部表示可自由替换 —— 只要迭代序与旧 TreeSet 逐字节一致。
    //
    // 旧实现病根（实测 160 窗口，_M3Probe 拆解）：
    //   纯扫描 2.87M 格 = 8ms；+ new int[3]×97万 = 20ms；+ 逐个 TreeSet.add = **321ms**。
    //   → 比较器 + 红黑树插入税 301ms（94%），是 rebuildIndex 583ms 的绝对大头。
    //
    // M3① 两级优化：
    //   ① setBlock 热路径不再逐格插入索引，只把 (旧类型→新类型, 坐标) 追加进紧凑 delta 缓冲
    //      （零分配、O(1)），消费前由 ensureIndex() 应用这一小批变更；
    //   ② 索引内部表示从 TreeSet<int[]> 换成**升序平铺 int[]**（3 int/格，无对象、无比较器）：
    //      全量重建 = 扫描自然升序 → 顺序 append（零移位、零分配）；
    //      增量变更 = 两分查找 + arraycopy 移位（变更量小，成本可控）。
    //   ⚠️ 教训：最初写成「一次 setBlock 就标脏全域、下次读取全量重建」，结果 70 个写系统 + 15 个
    //   读系统交替时退化为**每 tick 多次全域重建**（INDD 门禁 120s 超时）。增量补丁才是正解。
    // 索引是纯派生状态（不进 hashState），故本次改动**零指纹风险**（四基线逐字节不变）。
    private final CellSet[] typeCells = new CellSet[Blocks.count()];
    private final CellSet nonAirCells = new CellSet();
    {
        for (int i = 0; i < typeCells.length; i++) typeCells[i] = new CellSet();
    }
    /**
     * 升序平铺的体素坐标集合（M3①）：内部 {@code int[] data} 每 3 个 int 存一个 (x,y,z)，
     * 按 (x,y,z) 字典序严格升序、坐标唯一。仅实现消费者真正需要的两件事：{@link #size()}（O(1)）
     * 与 {@link #iterator()}（按升序惰性产出 int[3]，不持有百万级对象）。
     *
     * 之所以继承 {@code AbstractCollection<int[]>}：消费者大量使用
     * {@code new ArrayList<>(cells)} / {@code new TreeSet<>(cells)}（构造器要求 Collection），
     * 且 21 处调用点里只有 1 处显式声明 TreeSet 类型（已改为 Collection），其余零改动。
     */
    public static final class CellSet extends java.util.AbstractCollection<int[]> {
        /**
         * 覆盖层上限：待加入 + 待删除合计超过它就**压实**（归并回基线数组）。
         *
         * <p>取值权衡：越大 → 单次增删越便宜（O(覆盖层)），但压实的单次代价是 O(基线)；
         * 越小 → 压实越频繁。**1024 是实测扫出来的**（PerfProbe，160x112x160）：
         * 256 / 512 / 1024 / 2048 → tick median 2.17 / 2.08 / 2.01 / 1.95 ms、p95 7.64 / 4.90 / 4.43 / 4.33 ms。
         * 1024 在「中位数」与「p95」之间取到拐点（p95 是帧节奏的主导项，2048 只再省 0.1ms 却让压实更粗）。
         */
        private static final int OVERLAY_LIMIT = 1024;

        int[] data;      // 基线：升序 (x,y,z) 平铺，3 int/格
        int n;           // 基线格数（data 有效长度 = 3n）
        int[] addData;   // 待加入（升序；与 data 不相交）
        int addN;
        int[] remData;   // 待删除（升序；必须是 data 的子集）
        int remN;

        CellSet() { data = new int[3 * 64]; }

        /** 逻辑格数 = 基线 - 待删除 + 待加入。 */
        @java.lang.Override public int size() { return (n - remN) + addN; }

        // ---------------- 比较 / 查找 ----------------

        /** (x,y,z) 字典序比较。 */
        private static int cmp(int ax, int ay, int az, int bx, int by, int bz) {
            if (ax != bx) return ax < bx ? -1 : 1;
            if (ay != by) return ay < by ? -1 : 1;
            if (az != bz) return az < bz ? -1 : 1;
            return 0;
        }

        /** 比较 buf 的第 i 格与 buf2 的第 j 格。 */
        private static int cmpAt(int[] buf, int i, int[] buf2, int j) {
            int a = i * 3, b = j * 3;
            return cmp(buf[a], buf[a + 1], buf[a + 2], buf2[b], buf2[b + 1], buf2[b + 2]);
        }

        /** 两分查找：返回 key 在 buf 前 cnt 格中的下标（存在）或 -(插入点+1)（不存在）。 */
        private static int find(int[] buf, int cnt, int x, int y, int z) {
            int lo = 0, hi = cnt - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1, b = mid * 3;
                int c = cmp(buf[b], buf[b + 1], buf[b + 2], x, y, z);
                if (c == 0) return mid;
                if (c < 0) lo = mid + 1; else hi = mid - 1;
            }
            return -(lo + 1);
        }

        /** 该格是否在集合里（基线 ∪ 待加入，减去待删除）。 */
        boolean containsCell(int x, int y, int z) {
            if (addN > 0 && find(addData, addN, x, y, z) >= 0) return true;
            if (n == 0) return false;
            if (find(data, n, x, y, z) < 0) return false;
            return remN == 0 || find(remData, remN, x, y, z) < 0;
        }

        // ---------------- 增删（走覆盖层） ----------------

        /** 升序插入；已存在则空操作。 */
        void addCell(int x, int y, int z) {
            if (remN > 0) {
                int rp = find(remData, remN, x, y, z);
                if (rp >= 0) { removeAt(remData, remN, rp); remN--; return; }   // 撤掉一次待删除（它本就在基线里）
            }
            if (n > 0 && find(data, n, x, y, z) >= 0) return;                  // 已在基线
            if (addN > 0 && find(addData, addN, x, y, z) >= 0) return;          // 已在待加入
            ensureOverlay();
            int p = -(find(addData, addN, x, y, z) + 1);
            insertAt(addData, addN, p, x, y, z);
            addN++;
            if (addN + remN > OVERLAY_LIMIT) compact();
        }

        /** 删除；不存在则空操作。 */
        void removeCell(int x, int y, int z) {
            if (addN > 0) {
                int ap = find(addData, addN, x, y, z);
                if (ap >= 0) { removeAt(addData, addN, ap); addN--; return; }   // 还没落进基线 → 直接撤掉
            }
            if (n == 0 || find(data, n, x, y, z) < 0) return;                   // 基线里也没有
            if (remN > 0 && find(remData, remN, x, y, z) >= 0) return;          // 已标记删除
            ensureOverlay();
            int p = -(find(remData, remN, x, y, z) + 1);
            insertAt(remData, remN, p, x, y, z);
            remN++;
            if (addN + remN > OVERLAY_LIMIT) compact();
        }

        // ---------------- 迭代（归并 基线-待删除 与 待加入） ----------------

        @java.lang.Override public java.util.Iterator<int[]> iterator() {
            return new java.util.Iterator<int[]>() {
                int bi = 0, ri = 0, ai = 0;
                int[] nxt = null;
                { advance(); }

                private void advance() {
                    while (bi < n && ri < remN) {              // 跳过基线里被标记删除的格
                        int c = cmpAt(data, bi, remData, ri);
                        if (c == 0) { bi++; ri++; continue; }
                        if (c > 0) { ri++; continue; }
                        break;
                    }
                    boolean hasB = bi < n, hasA = ai < addN;
                    if (hasB && (!hasA || cmpAt(data, bi, addData, ai) < 0)) {
                        int b = bi++ * 3;
                        nxt = new int[]{data[b], data[b + 1], data[b + 2]};
                    } else if (hasA) {
                        int b = ai++ * 3;
                        nxt = new int[]{addData[b], addData[b + 1], addData[b + 2]};
                    } else {
                        nxt = null;
                    }
                }

                @java.lang.Override public boolean hasNext() { return nxt != null; }

                @java.lang.Override public int[] next() {
                    if (nxt == null) throw new java.util.NoSuchElementException();
                    int[] r = nxt;
                    advance();
                    return r;
                }
            };
        }

        /**
         * 零每格分配：把当前体素按 (x,y,z) 升序写入 {@code out}（3 int/格），返回写入格数（≤ out.length/3）。
         * 与 {@link #iterator()} **同一套**"（基线 − 待删除）∪ 待加入"归并 ⇒ 逐元素同序同值；
         * 用于热路径规避迭代器"每格 new int[3]"的开销。`out` 由调用方按 {@link #size()} 定容。
         */
        int copyTriples(int[] out) {
            int cap = out.length / 3;
            int m = 0, bi = 0, ri = 0, ai = 0;
            while (m < cap) {
                while (bi < n && ri < remN) {              // 跳过基线里被标记删除的格
                    int c = cmpAt(data, bi, remData, ri);
                    if (c == 0) { bi++; ri++; continue; }
                    if (c > 0) { ri++; continue; }
                    break;
                }
                boolean hasB = bi < n, hasA = ai < addN;
                if (hasB && (!hasA || cmpAt(data, bi, addData, ai) < 0)) {
                    int b = bi++ * 3;
                    out[m * 3] = data[b]; out[m * 3 + 1] = data[b + 1]; out[m * 3 + 2] = data[b + 2];
                } else if (hasA) {
                    int b = ai++ * 3;
                    out[m * 3] = addData[b]; out[m * 3 + 1] = addData[b + 1]; out[m * 3 + 2] = addData[b + 2];
                } else {
                    break;
                }
                m++;
            }
            return m;
        }

        // ---------------- 重建路径（顺序追加） ----------------

        /**
         * 顺序追加（仅「按升序调用」时使用，如全量重建的网格序扫描）——零移位快路径。
         *
         * <p>防御：追加要求「基线是唯一真相」，故覆盖层非空时先压实（正常路径上
         * {@code rebuildIndexBegin} 已 clearCells，覆盖层本就是空的）。
         */
        void appendSorted(int x, int y, int z) {
            if (addN > 0 || remN > 0) compact();
            if (n == capacityChunks()) grow();
            int b = n * 3;
            data[b] = x; data[b + 1] = y; data[b + 2] = z;
            n++;
        }

        /** 预留至少 chunks 个格子容量（全量重建前一次性预分配，避免多次 Arrays.copyOf）。 */
        void reserveChunks(int chunks) {
            int need = 3 * chunks;
            if (data.length < need) {
                int cap = data.length;
                while (cap < need) cap *= 2;
                data = java.util.Arrays.copyOf(data, cap);
            }
        }

        void clearCells() { n = 0; addN = 0; remN = 0; }

        // ---------------- P1 增量索引（第三十七批）----------------

        /**
         * 把基线整体平移 {@code (x-sx, y, z-sz)}、**丢弃越界格**，并额外丢弃落在"[rx0,rx1) 或 [rz0,rz1)"
         * 这片**重扫区**内的格（那片区域会由 {@code World.scanNewCols} 重新登记，见 relocateIndex）。
         *
         * <p><b>为什么可以原地做</b>：字典序下"减去同一个常量"是**保序**的 ⇒ 过滤后数组仍升序，
         * 无需重排、无需临时数组（写指针恒 ≤ 读指针）。这是把"全窗重建索引"降成 O(重扫区) 的关键。
         */
        void relocateInPlace(int sx, int sz, int limX, int limZ,
                             int rx0, int rx1, int rz0, int rz1) {
            if (addN > 0 || remN > 0) compact();      // 先让基线成为唯一真相
            int w = 0;
            for (int i = 0; i < n; i++) {
                int b = i * 3;
                int nx = data[b] - sx, nz = data[b + 2] - sz;
                if (nx < 0 || nz < 0 || nx >= limX || nz >= limZ) continue;             // 越界 = 该块已卸载
                if ((nx >= rx0 && nx < rx1) || (nz >= rz0 && nz < rz1)) continue;        // 重扫区 = 交给重扫
                data[w] = nx; data[w + 1] = data[b + 1]; data[w + 2] = nz;
                w += 3;
            }
            n = w / 3;
            addN = 0; remN = 0;
        }

        /**
         * 把另一个**升序**集合并入本集合（原地**后向**归并：目标尾部预留容量，从后往前写）。
         *
         * <p>要求两者**不相交**（本项目的调用点满足：被并入的是"新进入窗口"的格，与平移后的基线无交集）。
         * 后向归并保证写入位置恒在未读源之后 ⇒ 无需临时数组。
         */
        void mergeSorted(CellSet other) {
            if (other == null || other.n == 0) return;
            if (n == 0) {
                reserveChunks(other.n);
                java.lang.System.arraycopy(other.data, 0, data, 0, 3 * other.n);
                n = other.n;
                return;
            }
            reserveChunks(n + other.n);
            int i = n - 1, j = other.n - 1, k = n + other.n - 1;
            while (j >= 0) {
                if (i >= 0 && cmpAt(data, i, other.data, j) > 0) {
                    int s = i-- * 3, d = k-- * 3;
                    data[d] = data[s]; data[d + 1] = data[s + 1]; data[d + 2] = data[s + 2];
                } else {
                    int s = j-- * 3, d = k-- * 3;
                    data[d] = other.data[s]; data[d + 1] = other.data[s + 1]; data[d + 2] = other.data[s + 2];
                }
            }
            n += other.n;
        }

        // ---------------- 内部 ----------------

        private void ensureOverlay() {
            if (addData == null) {
                addData = new int[3 * (OVERLAY_LIMIT + 4)];
                remData = new int[3 * (OVERLAY_LIMIT + 4)];
            }
        }

        /** 把覆盖层归并回基线（一次 O(基线 + 覆盖层)）。 */
        private void compact() {
            int[] out = new int[3 * Math.max(n + addN + 1, 64)];
            int o = 0, bi = 0, ri = 0, ai = 0;
            while (bi < n || ai < addN) {
                while (bi < n && ri < remN) {              // 跳过基线里被标记删除的格（两数组均升序 → 双指针）
                    int c = cmpAt(data, bi, remData, ri);
                    if (c == 0) { bi++; ri++; continue; }
                    if (c > 0) { ri++; continue; }
                    break;
                }
                boolean hasB = bi < n, hasA = ai < addN;
                if (!hasB && !hasA) break;
                if (hasB && (!hasA || cmpAt(data, bi, addData, ai) < 0)) {
                    int b = bi++ * 3;
                    out[o++] = data[b]; out[o++] = data[b + 1]; out[o++] = data[b + 2];
                } else {
                    int b = ai++ * 3;
                    out[o++] = addData[b]; out[o++] = addData[b + 1]; out[o++] = addData[b + 2];
                }
            }
            data = out; n = o / 3; addN = 0; remN = 0;
        }

        private int capacityChunks() { return data.length / 3; }

        private void grow() {
            int need = 3 * (n + 1);
            int cap = data.length;
            while (cap < need) cap *= 2;
            data = java.util.Arrays.copyOf(data, cap);
        }

        private static void insertAt(int[] buf, int cnt, int p, int x, int y, int z) {
            int b = p * 3;
            if (p < cnt) java.lang.System.arraycopy(buf, b, buf, b + 3, 3 * (cnt - p));
            buf[b] = x; buf[b + 1] = y; buf[b + 2] = z;
        }

        private static void removeAt(int[] buf, int cnt, int p) {
            int b = p * 3;
            if (p < cnt - 1) java.lang.System.arraycopy(buf, b + 3, buf, b, 3 * (cnt - p - 1));
        }
    }

    // ---- M3① 索引增量补丁缓冲：每 3 个 int 记一条 (oldBlockIdx, newBlockIdx, packedXYZ) ----
    // 用 int 三元组平铺（零对象分配）。历史上限 = SX*SY*SZ 的 3 倍足够单 tick 变更量；
    // 溢出则退化为一次全量重建（正确性优先，极端情况才会触发）。
    private int[] indexDelta = new int[3 * 4096];
    private int indexDeltaLen = 0;           // 已用槽位数（3 的倍数）

    /** 索引是否已与实际 mat 脱节（有待应用的增量补丁）。消费前由 ensureIndex() 应用。 */
    private boolean indexStale = false;

    /**
     * {@link #nonAirCells} 是否已过期（需要惰性重建）。**纯派生状态的脏标记**，与 {@code indexStale} 同类。
     *
     * <p><b>为什么要有它（2026-09-17 性能修复）</b>：{@code nonAirCells} 是**全窗最大**的索引
     * （实测 160x112x160 下 1,006,024 格），而 {@code CellSet} 的增量 insert/remove 是
     * {@code arraycopy} 移位 = **O(该集合格数)**。于是**任何跨 AIR 边界的写入**（如 `ash` 往地表堆一格
     * STONE）都要付一次兆字节级 memmove —— 消融实测：仅 `ash` 一个系统就占整 tick 的 **76.6%**，
     * 而它每 tick 只写 ~27 格。
     *
     * <p>而这个集合**生产代码从不读**（全仓唯一读者是 {@link #nonAirCells()}，只有门禁/工具调用）。
     * 故改为：跨边界写入只标脏，真正需要时按网格序重建（与 {@code rebuildIndex} 同源同序）。
     */
    private boolean nonAirStale = false;

    public final SeededRNG rng;     // 仿真主 RNG
    public final SeededRNG fxRng;    // 渲染/演出专用（绝不进仿真）

    // ---- 风场状态（WindSystem 维护，PollenSystem 读取偏置；轻量状态，不进 hashState，确定性派生）----
    public float windX = 0f, windZ = 0f;

    // ---- 世界回响 / 繁荣链状态（P0-2 签名系统）----
    public int prosperity = 0;
    public final Set<String> skills = new LinkedHashSet<>();         // 已解锁蓝图
    public final List<String> villageMemory = new ArrayList<>();     // 村庄记忆（无时间戳）

    // ---- 守恒账本 ----
    public float builtMass = 0f;

    // ---- 玩家建造覆盖集（镜像 Python agentStructures）----
    // key=体素坐标打包(x<<20|y<<10|z)，value=蓝图 id。建成体素不计入"松质量"(mass 数组)，
    // 由本集合标记，供渲染/蓝图 UI 判定哪些格是玩家已建成（未来 ghost/blueprint UI 用）。
    // 不进 hashState（属渲染/演出派生），但写入受 editBlock 持久化路径约束（同块内提取支撑质量一并快照）。
    public final java.util.Map<Long, String> builtCells = new java.util.LinkedHashMap<>();
    public boolean isPlayerBuilt(int x, int y, int z) { return builtCells.containsKey(cellKey(x, y, z)); }
    public String builtBlueprint(int x, int y, int z) { return builtCells.get(cellKey(x, y, z)); }
    /** 体素坐标打包（x/y/z 各 10 位，SX/SZ<=64 绰绰有余），供 builtCells 索引。 */
    public static long cellKey(int x, int y, int z) {
        return ((long)(x & 0x3FF) << 20) | ((long)(y & 0x3FF) << 10) | (z & 0x3FFL);
    }

    // ---- 功能方块 per-block 状态层（2026-09-23 第三批「源源不断」）----
    // 门/箱/熔炉/工作台是首批"有内部状态"的方块。状态按格（{@link #cellKey}）**稀疏**存储：
    //   meta[cell]        ：通用开关/进度位（DOOR：0=关 1=开；FURNACE：当前冶炼进度 tick 计数）
    //   chestStore[cell]  ：箱内容物（物品 id → 数量）
    //   furnaceStore[cell]：熔炉已产出、待玩家取走的锭（物品 id → 数量）
    // 三条纪律：① 全部**非 SKIP** → 随 StateCodec 反射持久化/回滚（LinkedHashMap 直写 → 逐字节可复现）；
    //   ② **不进 hashState()**（窄哈希只盖 mat/mass/…）→ 玩家开关门/存取物不改仿真指纹 → 四道零漂移门禁不变；
    //   ③ 稀疏（只记真有状态的格）→ 不会像整片 byte[] 那样把每份快照撑大（见 SNAPSTATE 的膨胀教训）。
    public final java.util.Map<Long, Integer> meta = new java.util.LinkedHashMap<>();
    public final java.util.Map<Long, java.util.LinkedHashMap<String, Integer>> chestStore =
            new java.util.LinkedHashMap<>();
    public final java.util.Map<Long, java.util.LinkedHashMap<String, Integer>> furnaceStore =
            new java.util.LinkedHashMap<>();
    /** 告示牌铭文（cellKey → 文本）。与 meta/chestStore 同层：非 SKIP → 自动持久化；不进 hashState。 */
    public final java.util.Map<Long, String> signText = new java.util.LinkedHashMap<>();
    /**
     * per-block <b>朝向 / 模式位</b>（第十批，2026-09-24）：稀疏，与 meta/chestStore 同层
     * （非 SKIP ⇒ 自动持久化/回滚；**不进 hashState** ⇒ 玩家转方块不改仿真指纹）。
     *
     * <p><b>打包</b>（一个 int 装两件事，因为两者永远是"同一格同一元件"的属性）：
     * <pre>
     *   bits 0..2 : 朝向 = 值 − 1（1..6 → 方向 0..5，见 {@link Facing}）；0 = 未设置 → 取 {@link Facing#DEFAULT}
     *   bit  3    : 比较器工作模式（0 = compare / 1 = subtract）
     * </pre>
     * <b>为什么用 "+1" 编码</b>：方向 0（+X）是合法值，若直接存 0 就无法区分"朝 +X"与"没设过"。
     *
     * <p><b>为什么朝向是"真状态"而不是渲染参数</b>：它决定比较器的 A/B 是哪两路、活塞往哪推、
     * 漏斗往哪送 —— 是仿真语义的一部分，所以必须随快照回滚（`非 SKIP`）。同时它只由玩家放置/右键驱动，
     * 与 {@code mat}/{@code mass} 无关 → 不必进窄哈希（与 meta 同一论证）。
     */
    public final java.util.Map<Long, Integer> blockState = new java.util.LinkedHashMap<>();

    /** 朝向位掩码（低 3 位）。 */
    public static final int FACING_MASK = 7;
    /** 比较器模式位（第 4 位）。 */
    public static final int MODE_BIT = 8;

    /** 读某格状态位（缺省 0）。纯读，无副作用。 */
    public int getMeta(int x, int y, int z) {
        Integer v = meta.get(cellKey(x, y, z));
        return v == null ? 0 : v.intValue();
    }
    /** 写某格状态位（写 0 视为清除，保持稀疏 → 快照不膨胀）。 */
    public void setMeta(int x, int y, int z, int v) {
        long k = cellKey(x, y, z);
        if (v == 0) meta.remove(k);
        else meta.put(k, Integer.valueOf(v));
    }
    /** 箱内容物：不存在时返回 {@code null}（纯读，绝不因读而建表 → 保稀疏与插入序确定）。 */
    public java.util.LinkedHashMap<String, Integer> chestOf(long cell) { return chestStore.get(cell); }
    /** 取（必要时建）某格箱内容物 —— 仅写入路径调用。 */
    public java.util.LinkedHashMap<String, Integer> ensureChest(long cell) {
        java.util.LinkedHashMap<String, Integer> c = chestStore.get(cell);
        if (c == null) { c = new java.util.LinkedHashMap<>(); chestStore.put(cell, c); }
        return c;
    }
    /** 熔炉产出（待取，物品 id → 数量）：不存在时返回 {@code null}（纯读，保稀疏）。 */
    public java.util.LinkedHashMap<String, Integer> furnaceStoreOf(long cell) { return furnaceStore.get(cell); }
    /** 取（必要时建）某格熔炉产出袋 —— 仅 FurnaceSystem 写入路径调用。 */
    public java.util.LinkedHashMap<String, Integer> ensureFurnace(long cell) {
        java.util.LinkedHashMap<String, Integer> c = furnaceStore.get(cell);
        if (c == null) { c = new java.util.LinkedHashMap<>(); furnaceStore.put(cell, c); }
        return c;
    }
    /** 告示牌铭文（不存在返回 null）。纯读。 */
    public String signTextOf(long cell) { return signText.get(cell); }
    /** 设置某格告示牌铭文（空串 = 清除，保稀疏）。 */
    public void setSignText(int x, int y, int z, String s) {
        long k = cellKey(x, y, z);
        if (s == null || s.isEmpty()) signText.remove(k); else signText.put(k, s);
    }

    // ---- per-block 朝向 / 模式（第十批）——读写都要"保留另一半"，否则会互相覆盖 ----

    /**
     * 读朝向。未设置过 → {@link Facing#DEFAULT}（+Y）。
     * <b>返回的永远是合法方向</b>（调用方不必判 null）——"没设过"与"朝默认方向"在本作里行为等价。
     */
    public int getFacing(int x, int y, int z) {
        Integer v = blockState.get(cellKey(x, y, z));
        if (v == null) return Facing.DEFAULT;
        int f = (v.intValue() & FACING_MASK) - 1;
        return f < 0 ? Facing.DEFAULT : f;
    }

    /** 写朝向（保留模式位）。朝向是有效信息 → 即使其它位都是 0 也保留表项（不删）。 */
    public void setFacing(int x, int y, int z, int dir) {
        long k = cellKey(x, y, z);
        Integer cur = blockState.get(k);
        int keep = (cur == null ? 0 : cur.intValue()) & ~FACING_MASK;
        blockState.put(k, Integer.valueOf(keep | (((dir & FACING_MASK) + 1) & FACING_MASK)));
    }

    /** 比较器是否处于 subtract（减法）模式。 */
    public boolean isSubtractMode(int x, int y, int z) {
        Integer v = blockState.get(cellKey(x, y, z));
        return v != null && (v.intValue() & MODE_BIT) != 0;
    }

    /** 写比较器模式（保留朝向位；若朝向位为空则补默认朝向，保证 "+1 编码" 不为 0）。 */
    public void setSubtractMode(int x, int y, int z, boolean on) {
        long k = cellKey(x, y, z);
        Integer cur = blockState.get(k);
        int fBits = (cur == null) ? 0 : (cur.intValue() & FACING_MASK);
        if (fBits == 0) fBits = Facing.DEFAULT + 1;                    // 未设朝向 → 补默认（不写 0，否则读回来是"未设置"）
        int v = fBits | (on ? MODE_BIT : 0);
        blockState.put(k, Integer.valueOf(v));
    }

    /**
     * 该格是否携带<b>不可无痛搬走</b>的状态 —— 移动方块的元件（活塞）必须先问它。
     *
     * <p>判据只认"内容/意图"：容器内容、熔炉产出、铭文；以及门/活板门<b>玩家设定</b>的开态。
     * <b>不含</b> {@code meta} 里那些每 tick 由系统重算的派生位（压力板踩踏、中继/比较器输出、
     * 发射器/活塞的边沿位）—— 那些搬走后下一 tick 会自己算回来，误把它们当"不可搬"会让
     * 活塞拒绝推动任何正在通电的元件（第九批就是这么写的，过严）。
     *
     * <p>⚠️ 判据刻意<b>不写成"可搬运方块白名单"</b>：那种名单一定会随内容增长而漏，而漏的后果是
     * {@code setBlock} 把状态留在原格 → "箱子里的东西凭空消失"这类静默数据丢失。
     */
    public boolean hasUnyieldableState(int x, int y, int z) {
        long k = cellKey(x, y, z);
        if (!chestStore.isEmpty() && chestStore.containsKey(Long.valueOf(k))) return true;
        if (!furnaceStore.isEmpty() && furnaceStore.containsKey(Long.valueOf(k))) return true;
        if (!signText.isEmpty() && signText.containsKey(Long.valueOf(k))) return true;
        int b = getBlock(x, y, z);
        if ((b == Blocks.DOOR.index || b == Blocks.TRAPDOOR.index) && getMeta(x, y, z) != 0) return true;
        return false;
    }

    /** 清掉某格的全部功能状态（方块被挖走/替换时调用，避免状态悬挂到后放的新方块）。 */
    private void clearFunctionalState(int x, int y, int z) {
        long k = cellKey(x, y, z);
        if (!meta.isEmpty()) meta.remove(k);
        if (!chestStore.isEmpty()) chestStore.remove(k);
        if (!furnaceStore.isEmpty()) furnaceStore.remove(k);
        if (!signText.isEmpty()) signText.remove(k);
        if (!blockState.isEmpty()) blockState.remove(k);
    }

    /**
     * 实体碰撞语义的「是否实心」—— 在 {@link Blocks.Block#solid} 之上叠加功能方块状态。
     *
     * <p>目前只有 DOOR：{@code meta==1}（开）→ 不再阻挡（玩家能穿过闭合的判定盒）。
     * 无可交互方块时逐格等价于 {@code Blocks.byIndex(getBlock).solid} —— 故旧世界/门禁世界行为不变。
     * 读 meta（玩家动作驱动）不影响 hashState（窄哈希不读 meta）→ 零漂移。
     */
    public boolean solidForEntity(int x, int y, int z) {
        int b = getBlock(x, y, z);
        if (!Blocks.byIndex(b).solid) return false;
        if (b == Blocks.DOOR.index && getMeta(x, y, z) == 1) return false;   // 开着的门可通行
        if (b == Blocks.TRAPDOOR.index && getMeta(x, y, z) == 1) return false; // 开着的活板门可通行
        // 「材料实心但实体可穿过」：梯子（要爬进去）、拉杆/按钮/红石导线（贴墙/贴地的薄件）。
        if (b == Blocks.LADDER.index || b == Blocks.LEVER.index
                || b == Blocks.BUTTON.index || b == Blocks.WIRE.index) return false;
        // 第七批：红石逻辑三件套同样是"贴地薄件"（压力板要踩上去、中继/比较器是薄电路件）。
        if (b == Blocks.REPEATER.index || b == Blocks.COMPARATOR.index
                || b == Blocks.PLATE.index) return false;
        // 第九批：发射器是薄件（贴地可站上去）；活塞**可站**（实心，用于顶升，不该让实体陷进去）。
        if (b == Blocks.DISPENSER.index) return false;
        // 第十二批：锁链/脚手架也是"薄件/可穿"——材料意义上实心（能贴墙放置、能挡光），
        // 但实体必须能钻进去才能攀爬（与梯子同一范式：碰撞的唯一真相是 solidForEntity）。
        if (b == Blocks.CHAIN.index || b == Blocks.SCAFFOLDING.index) return false;
        return true;
    }

    /**
     * 指定格是否可以攀爬（{@link Player} 物理的攀爬判定）。
     *
     * <p>第十二批把可攀爬集合从 {LADDER} 扩到 {LADDER, CHAIN, SCAFFOLDING} —— 这一步**零成本**
     * 地让两件新道具获得完整的攀爬手感（跳跃上爬 / 潜行下爬 / 松手缓滑），
     * 因为攀爬逻辑在 {@code Player.physicsStep} 里是**按谓词**驱动的，不认具体方块。
     */
    public boolean isLadder(int x, int y, int z) {
        int b = getBlock(x, y, z);
        return b == Blocks.LADDER.index || b == Blocks.CHAIN.index || b == Blocks.SCAFFOLDING.index;
    }

    // ---- 事件总线（仅调试用，不进确定性指纹）----
    public static final class Event {
        public final String system, action, params, result;
        Event(String s, String a, String p, String r) { system = s; action = a; params = p; result = r; }
    }
    public final List<Event> events = new ArrayList<>();
    public int eventsProcessed = 0;   // 系统扫描水印（世界回响按水印取本 tick 事件）

    // ---- 审计 instrumentation（仅 SystemAudit 经 tickAudited() 启用；不进 hashState，生产 tick() 永不触发）----
    // 设计纪律：这些字段与 events 一样纯调试派生，绝不参与仿真或确定性指纹；
    // 生产路径 World.tick() 不设置 auditSys，故 setBlock 内的审计块恒为 no-op，零行为/性能副作用。
    public final java.util.Map<String, Integer> sysMut = new java.util.HashMap<>();   // 每系统 setBlock 改写次数
    public String auditSys = null;                                                   // 当前正在 update 的系统名
    public final java.util.Map<String, Integer> dbgConflict = new java.util.HashMap<>(); // 冲突对（异系统短期反复改写同格）计数
    private final java.util.Map<Long, String> dbgCellWriter = new java.util.HashMap<>();
    private final java.util.Map<Long, Integer> dbgCellTick = new java.util.HashMap<>();

    public Player player;             // 玩家回环由 World.tick 始终驱动（对齐 Python P0-1 修复）

    /**
     * C5 暂停开关（渲染层菜单持有；不进 {@link #hashState()}）。
     *
     * <p>暂停语义被刻意定义为「<b>丢弃 tick</b>」而非「冻结时钟」：暂停期间 {@link #tick()} 是纯
     * no-op —— 既不推进 tick 计数、也不消耗 RNG、不改任何网格。因此「中途暂停任意次再恢复」与
     * 「从未暂停」跑同样多次 tick 后的世界状态<b>逐字节一致</b>（由 {@code core.sim.MenuTest} 断言）。
     * 这是暂停能被安全引入确定性内核的前提：暂停不改变<em>已推进的</em> tick 序列。
     */
    public boolean paused = false;

    // ---- 敌兵实体（IMPL-COMBAT 真实战斗遭遇闭环）----
    // 实体“不写网格”，且不进 hashState()，故对四道零漂移门禁指纹零影响。
    // 仿真的所有随机只走 simStream，绝不动 fxRng 或 rng 主状态。
    public static final int BEAST_CAP = 4;                  // 同屏敌兵数量上限（出厂值）

    /**
     * 本世界<b>实际生效</b>的同屏敌兵上限：玩法预设 {@code params.beastCap} 覆盖，默认 = {@link #BEAST_CAP}。
     *
     * <p>为什么不直接用静态常量：预设是<b>运行时可换</b>的玩法层（{@code Simulation.applyPreset}），
     * 而静态可变常量会被同 JVM 里的多个世界互相污染（联机门禁常态就是多世界并存）。
     * 本字段是实例状态 → 进快照（联机两端必须一致，这是对的），但<b>不进 {@code hashState()}</b>
     * （它是配置而非演化量）→ 四基线指纹不受影响。
     */
    public int beastCap = BEAST_CAP;

    /**
     * 玩法子系统可调参数容器（内容层 → 仿真层接线点）。
     *
     * <p>7 个「子系统参数」（erosionRate/ascensionThreshold/haulRate/hungerRate/orbItemCost/
     * hpThreshold/wireRange）在此落位；各 System 读取对应字段。出厂默认下全部 no-op（或保持
     * 与改动前逐字节一致的旧行为）→ 默认世界演化不变。
     *
     * <p><b>不进快照 / 不进 hashState</b>：由 {@code StateCodec.SKIP} 排除 —— 它是「配置」不是
     * 「演化量」；两端各自从同一预设加载即可一致（与 {@code beastDefs} 同纪律，但 beastCap 例外，
     * 那种"两端必须同值"的配置才进快照）。
     */
    public WorldConfig config = new WorldConfig();
    public final List<Beast> beasts = new ArrayList<>();   // 敌兵列表（构造时初始化）
    /**
     * 矿车列表（第二十一批）。**实体层**：不进 {@link #hashState()}（窄哈希只盖 mat/mass/…）
     * ⇒ 对四道零漂移指纹门禁零影响；但会被 {@code StateCodec} 反射式自动快照（与 {@code beasts} 同机制），
     * 联机回滚 / 读档照常恢复。
     */
    public final List<Minecart> carts = new ArrayList<>();
    /** P1-2：敌兵死亡后留在原地的「魂滴」（DaS 风尸体回收）。实体层：<b>不进 {@link #hashState()}</b>（窄哈希只盖 mat/mass/...），
     *  故对四道零漂移门禁指纹零影响；但会被 {@code StateCodec} 反射式自动快照（与 {@code beasts} 同机制），联机回滚/读档照常恢复。 */
    public final List<Corpse> corpses = new ArrayList<>();
    /**
     * P1：内容兽定义表（{@code assets/content/beasts/*.json}）。
     *
     * <p>是<b>加载期资产</b>而不是世界状态：由渲染层在装配内容时注入，两端各自加载同一份内容即可一致。
     * 因此它是实例字段（避免同 JVM 多世界互相污染 —— 静态可变量的老坑）但被
     * {@code StateCodec.SKIP} 排除（不进快照：它是"配方"，不是"演化量"）。
     */
    public java.util.List<core.content.BeastDef> beastDefs = java.util.Collections.emptyList();

    /**
     * 材料规格书（内容层第 13 类）—— 与 {@code beastDefs} 同纪律的<b>加载期资产</b>：
     * 由渲染层在装配内容时注入，被 {@code StateCodec.SKIP} 排除（不进快照 / 不进 netHash）。
     *
     * <p><b>为什么挂在 World 上</b>：仿真系统只能拿到 {@code World}，而密度判据需要读材料表
     * （{@code SandFallSystem} 用 {@link core.content.MaterialBook#sinksInto} 决定能否沉入下方材料）。
     * 这是"数据表 → 求解器"的唯一通路。
     *
     * <p><b>默认是空书</b>（所有方块走兜底值 = 与历史行为一致）：门禁里裸 {@code new} 的 Simulation
     * 不受影响，{@code sinksInto} 恒返回 false —— 于是即使误开了 {@code densityFlow} 也不会乱动世界。
     */
    public core.content.MaterialBook materials = core.content.MaterialBook.empty(Blocks.count());

    /**
     * 材料反应表（内容层第 14 类）—— 与 {@code materials} 同纪律的<b>加载期资产</b>：
     * 由渲染层注入，被 {@code StateCodec.SKIP} 排除（不进快照 / 不进 netHash）。
     *
     * <p><b>为什么挂在 World 上</b>：反应求解器 {@code ReactionSystem} 只能拿到 {@code World}。
     *
     * <p><b>默认是空表</b>（任何材料对都查不到规则）：门禁里裸 {@code new} 的 Simulation
     * 不受影响；即使误开 {@code reactionTable} 也因为"查不到规则"而零写入 —— 与
     * {@code materials} 的空书兜底是同一条防线（见 {@code MATERIAL-BARE-SAFE} 断言）。
     */
    public core.content.ReactionBook reactions = core.content.ReactionBook.empty(Blocks.count());

    /** P1：任务进度（实体级开放状态；不进 {@link #hashState()}，但进存档与联机快照）。 */
    public final QuestLog quests = new QuestLog();

    // ---- 祭坛实体（P2-B 塞尔达能力-探索闭环）：确定性放置，邻近授予能力，不写网格，不进 hashState ----
    // 实体“不写网格”，故对四道零漂移门禁指纹零影响；一切随机只走 simStream。
    public static final class Shrine {
        public float x, y, z;
        public final String ability;     // GLIDE / DASH / BOMB
        public final String name;
        public boolean claimed = false;
        public Shrine(float x, float y, float z, String ability, String name) { this.x = x; this.y = y; this.z = z; this.ability = ability; this.name = name; }
    }
    public final List<Shrine> shrines = new ArrayList<>();

    // ---- 繁荣灯塔实体（D 批 · 地标入罗盘）：BeaconSystem 点亮中心 LAMP 时记录为可导航地标 ----
    // 实体级开放状态，与 shrines/beasts 同纪律：不写 mat/mass/prosperity/skills/villageMemory，不进 hashState，
    // 故对四道零漂移门禁指纹零影响；存档由立项 F 扩展段 v3 序列化（旧档 ext<3 自然为空）。
    public static final class Beacon {
        public float x, y, z;
        public final String name;
        public Beacon(float x, float y, float z, String name) { this.x = x; this.y = y; this.z = z; this.name = name; }
    }
    public final List<Beacon> beacons = new ArrayList<>();

    /**
     * 内容扩张：可打开的容器箱（实体层，不写 mat、不进窄 hashState；StateCodec 自动持久化）。
     *
     * <p>{@link #tier} 品质分档：0=COMMON（地表随处可见）· 1=RARE（更少、loot 更好）·
     * 2=HIDDEN（隐藏宝箱：藏在洞穴空气袋里、loot 最稀有）。渲染层按 tier 上色。
     */
    public static final class Chest {
        public float x, y, z;
        public String itemId;
        public int count;
        public int tier;                        // 0=COMMON 1=RARE 2=HIDDEN
        public boolean opened;
        public static final int TIER_COMMON = 0, TIER_RARE = 1, TIER_HIDDEN = 2;
        public Chest() { }
        public Chest(float x, float y, float z, String itemId, int count) {
            this(x, y, z, itemId, count, TIER_COMMON);
        }
        public Chest(float x, float y, float z, String itemId, int count, int tier) {
            this.x=x; this.y=y; this.z=z; this.itemId=itemId; this.count=count;
            this.tier=tier; this.opened=false;
        }
    }
    public final List<Chest> chests = new ArrayList<Chest>();

    /**
     * MC 手感（路线图 §1 P0 #2）：挖掉方块后掉在地上的「小方块」。
     *
     * <p>实体层：不写 mat/mass/prosperity/skills/villageMemory → <b>不进窄 {@code hashState}</b>，
     * 对四道基线指纹零影响；但进 StateCodec 快照（联机两端一致）。
     *
     * <p>落点即<b>静止点</b>（该列最顶实心之上）—— 不做逐 tick 物理 → 零仿真开销、零 RNG、完全确定；
     * 「走近自动吸入背包」由渲染/输入层完成（与 {@link Chest} 同一种分工：数据在 sim，背包转移在渲染层）。
     */
    public static final class Drop {
        public float x, y, z;
        public String itemId;
        public int count;
        public Drop() { }
        public Drop(float x, float y, float z, String itemId, int count) {
            this.x=x; this.y=y; this.z=z; this.itemId=itemId; this.count=count;
        }
    }
    public final List<Drop> drops = new ArrayList<Drop>();
    /** 掉落物上限：超出即移除最旧的 → 有界（防长时间游玩把快照/内存撑大）。确定性与 RNG 无关。 */
    public static final int MAX_DROPS = 96;

    /**
     * 落一个掉落物（方块已被挖掉后调用）。位置 = 该列最顶实心之上 1 格（无实心则留在原高度）。
     * 纯确定性：不消费 RNG、不写网格。返回落点（供门禁断言）。
     */
    public Drop spawnDrop(int bx, int by, int bz, String itemId, int count) {
        int top = surfaceY[bx][bz];
        float dy = (top < 0 ? (float) by : (float) top) + 1f;
        return spawnDropAt(bx + 0.5f, dy, bz + 0.5f, itemId, count);
    }

    /**
     * 在<b>指定坐标</b>落一个掉落物（第十批：发射器"弹射"用）。
     *
     * <p>上界（{@link #MAX_DROPS}）的裁剪只在这里做一处 —— 两个入口共用同一条有界纪律，
     * 免得"新增一个入口就忘了限流"。纯确定性：不消费 RNG、不写网格。
     */
    public Drop spawnDropAt(float fx, float fy, float fz, String itemId, int count) {
        Drop d = new Drop(fx, fy, fz, itemId, count);
        drops.add(d);
        while (drops.size() > MAX_DROPS) drops.remove(0);      // 最旧先出队（有界）
        return d;
    }

    // ---- NPC 实体（agents/ 社会认知层；由 NpcSystem/SocialSystem 驱动，已接入 tick 主循环）----
    // 实体“不写网格”，且不进 hashState()，故对四道零漂移门禁指纹零影响（与 beasts/shrines 同纪律）。
    // 仿真的所有随机只走 simStream，绝不动 fxRng 或 rng 主状态。
    public final java.util.List<core.agent.Npc> npcs = new java.util.ArrayList<>();

    // ---- 村庄级社会状态（批次1 · 家族/情绪/规范；实体级开放标量，不进 hashState，纯确定性）----
    // 与 npcs/beasts/shrines 同纪律：不写 mat/mass/prosperity/skills/villageMemory，故指纹零影响。
    public final core.agent.VillageSocial social = new core.agent.VillageSocial();

    // ---- 村庄编年史（批次1 余项 · storyteller/村志消费端；不进 hashState，纯确定性派生）----
    // 由 StorytellerSystem 驱动：只读 events/npcs/social 生成档案与叙述，不写 mat/mass/prosperity/
    // skills/villageMemory，故对四道零漂移门禁指纹零影响（让“世界因你而变”被看见而零漂移）。
    public final core.world.Chronicle chronicle = new core.world.Chronicle();

    // ---- 文明状态（批次 2 · 文明深度：culture/religion/tech/industry/urban/diplomacy/warfare）----
    // 由 CivilizationSystem 驱动：开放标量 + 区划覆盖层 + 意图队列，不进 hashState，
    // 不写 mat/mass/prosperity/skills/villageMemory（材料执行层由 MATERIAL_WORKS 开关守护，默认关），
    // 故对四道零漂移门禁指纹零影响（与 npcs/beasts/shrines/social/chronicle 同纪律）。
    public final core.world.Civilization civ = new core.world.Civilization();

    // ---- 个体成长状态（批次 3 · 个体成长：learning/behavior/genetics/evolution/medicine/
    //      robotics/firearms/fishing/taming/xeno/ascension/magic/cultivation/myth/dreamscape/
    //      chemistry/roleplay；开放标量聚合，不进 hashState，纯确定性）----
    // 由 IndividualSystem 驱动：只改开放标量与 events，不写 mat/mass/prosperity/skills/villageMemory
    // （材料执行层由 IndividualSystem.MATERIAL_WORKS 开关守护，默认关），故四道零漂移门禁指纹零影响。
    public final core.world.Individual individual = new core.world.Individual();

    // ---- 民政与经济状态（批次 4 · 城镇等级/集市定价/犯罪仲裁 + 货币/商队/通胀/供应链）----
    // 由 PolitySystem 驱动：开放标量 + 实体级账本 + events，不进 hashState，
    // 不写 mat/mass/prosperity/skills/villageMemory（犯罪只改 NPC 实体态，实体不进指纹），
    // 故对四道零漂移门禁指纹零影响（与 npcs/social/chronicle/civ/individual 同纪律）。
    public final core.world.Polity polity = new core.world.Polity();

    // ---- 灾害状态（批次 4 · 山火/洪涝/干旱/地震 触发 + 预警 + 撤离）----
    // 由 CalamitySystem 驱动：干燥/连雨/周期追踪 + 预警事件 + NPC 撤离信号；
    // 网格实体化由 CalamitySystem.MATERIAL_WORKS 开关守护（默认关），
    // 故对四道零漂移门禁指纹零影响。
    public final core.world.Calamity calamity = new core.world.Calamity();

    // ---- 试炼/遗物/世界之心（C2 · 塞尔达循环后半段：能力→解谜点→回报→终局）----
    // 由 TrialSystem 驱动：3 试炼点（能力门）+ 4 补给箱 + 世界之心（峰顶，集齐遗物后显现）。
    // 实体级开放状态，不进 hashState，且认取时不写 mat/mass/prosperity/skills/villageMemory，
    // 故对四道零漂移门禁指纹零影响（与 npcs/shrines/social/chronicle/civ/individual/polity/calamity 同纪律）。
    public final core.world.Trials trials = new core.world.Trials();

    // ---- 物质·能量域状态（批次 5 · 抽象系统：物相 phases / 能量梯级 entropy / 聚变 fusion / 深海 deepsea）----
    // 由 MatterSystem 驱动：开放标量“读数”，不进 hashState，不写 mat/mass/prosperity/skills/villageMemory；
    // 空间执行层（辐射场扩散/作物变异/NPC 受辐射/洋面掩码/潜航器实体）由 MatterSystem.MATERIAL_WORKS 守护
    // （默认关），故对四道零漂移门禁指纹零影响（与 npcs/social/chronicle/civ/individual/polity/calamity/trials 同纪律）。
    public final core.world.Matter matter = new core.world.Matter();

    // ---- 天气镜像（WeatherSystem 每 tick 写入；供 CalamitySystem 读取；派生状态，不进 hashState）----
    public boolean raining = false;
    public float humidity = 0.5f;

    /** 生成一个 NPC 实体并加入 npcs（供系统/婚育/调试调用；实体不进指纹，故不影响四门禁）。 */
    public void spawnNpc(core.agent.Npc npc) {
        if (npc != null) npcs.add(npc);
    }

    // ---- 渲染脏区标记（仅用于分块增量重建，绝不进 hashState，不参与仿真）----
    public final Set<Long> dirtyChunks = new java.util.HashSet<>();

    // ---- 每列最顶实心格缓存（surfaceY[x][z]；派生状态，不进 hashState；setBlock 增量维护 + rebuildIndex 全量重建）----
    public int[][] surfaceY;
    /** rebuildIndex 一趟扫描的暂存：每列当前最高 solid y（-1 = 无）；扫描后回填 surfaceY。仅重建期间使用。 */
    private int[][] surfaceTopY;

    // ---- 地表索引（surfaceCells：每列最顶非空气格；派生状态，不进 hashState）----
    // 定义：cell (x,y,z) ∈ surfaceCells 当且仅当 mat[x][y][z] != AIR 且 (y+1 >= SY 或 mat[x][y+1][z] == AIR)。
    // 即"每列最顶非空气格"（其正上方要么是空气、要么已越界/顶层）。它等价于"nonAirCells 中头顶为空气的格"，
    // 唯一区别是：当某列非空气一直顶到最顶层（y=SY-1，头顶越界）时，该顶格仍计入——否则会漏掉树冠顶到顶层的列，
    // 使 Pond 把该列误判为"无表层"而跳过积水，破坏 DETERMINISM（已实测指纹会翻红，故必须包含顶层）。
    // 用途：PondSystem 直接遍历它（规模≈SX*SZ，远小于百万级 nonAirCells），向下扫描到首个 solid 即得每列最顶实心，
    // 从而把 Pond 热点从遍历全部地下实心格降到仅表层。维护：setBlock 增量 + rebuildIndex 全量重建（双保险）。
    // 纯派生：hashState() 不读它，演化结果与"遍历 nonAirCells 找表层"逐字节一致（DETERMINISM 指纹不变）。
    public final java.util.Set<Long> surfaceCells = new java.util.HashSet<>();

    // ---- 水面索引（waterSurfaceCells：每列最顶 WATER 且头顶为空气/越界；派生状态，不进 hashState）----
    // 定义：cell (x,y,z) ∈ waterSurfaceCells 当且仅当 mat[x][y][z] == WATER 且 (y+1 >= SY 或 mat[x][y+1][z] == AIR)。
    // 即"每列最顶水且头顶为空气"的那一格——这正是 IceFormSystem 原 topWaterY 扫描（从 y=0 扫到 y<SY 取最顶 WATER）所命中、
    // 且其上方为空气时才会结冰的那一格。由于每 tick WaterFlowSystem 已把水柱竖直沉降为连续块，水柱内最多只会有一个
    // "头顶为空气"的水格（即最顶水），故 waterSurfaceCells 的每列元素个数恒为 0 或 1，且与"原 topWaterY 返回的最顶 WATER"
    // 逐列一一对应（若最顶水被实心/ICE 盖住则两者都不结冰，结果一致）。
    // 用途：IceFormSystem 直接桶排它到 int[SX][SZ]（按 x,z 列序，保持 rng 调用顺序零漂移），把原 44 万次 getBlock/tick 降到 O(SX*SZ)。
    // 维护：setBlock 增量 + rebuildIndex 全量重建（双保险）；纯派生，hashState() 不读它，演化和"遍历全水柱"逐字节一致。
    public final java.util.Set<Long> waterSurfaceCells = new java.util.HashSet<>();

    private final List<System> systems = new ArrayList<>();
    /** N2-0：读档时暂存的「系统实例状态」字节（此刻系统尚未注册），由 {@link #applyPendingSystemStates()} 落位。 */
    private byte[] pendingSystemState;
    // N4 前置：原始地形基线缓存（消除每 tick 快照的 204ms 全窗重生成）。baseMat/baseMass 引用一个
    // 一次性生成的 pristine 世界之数组（该世界不再被改动 → 引用安全，不复制省内存/时间）；
    // 窗口原点变化时失效重算。
    private int[][][] baseMat = null;
    private float[][][] baseMass = null;
    private int baseWinCX0 = 0, baseWinCZ0 = 0;
    private boolean baselineValid = false;
    /** 仅供门禁观测：原始基线重生成次数（应远小于快照次数 —— 证明缓存生效）。 */
    public int baselineRegenCount = 0;
    /**
     * 系统注册表（平台化 2026-09-13）：执行顺序的单一真相 + 职责标签 + 按域开关。
     * <b>phase 只是标签，绝不参与排序</b> —— 顺序决定演化，重排即指纹漂移。
     */
    public final SystemRegistry registry = new SystemRegistry();
    /** 开局选址器找到的「安全出生点」；读档后仍用，避免回世界中心再陷凹地/树冠。 */
    public int safeSpawnX = -1, safeSpawnZ = -1;

    public World(long seed, int sx, int sy, int sz) { this(seed, sx, sy, sz, 0, 0); }

    /**
     * N2-1：以**指定窗口原点**构造一个"原始世界"（无系统、无编辑）—— 用作稀疏差分的**基线源**。
     *
     * <p>与 {@code World.load} 的 {@code new World(...)} + {@code restoreWindowOrigin(...)} 路径
     * **逐字节同源**，故 {@code baseline + diff} 必然复现存档状态。这是稀疏快照正确性的根。
     */
    private World(long seed, int sx, int sy, int sz, int originCX, int originCZ) {
        if (sx % CHUNK != 0 || sz % CHUNK != 0)
            throw new IllegalArgumentException("SX/SZ must be multiples of CHUNK");
        this.seed = seed;
        this.SX = sx; this.SY = sy; this.SZ = sz;
        this.CX = sx / CHUNK; this.CZ = sz / CHUNK; this.R = CX / 2;
        this.mat = new int[sx][sy][sz];
        this.mass = new float[sx][sy][sz];
        this.surfaceY = new int[sx][sz];
        this.surfaceTopY = new int[sx][sz];
        this.rng = new SeededRNG(seed);
        this.fxRng = new SeededRNG(seed ^ 0xBADC0FFEE0DDF00DL); // 独立种子，物理隔离演出随机
        this.winCX0 = originCX; this.winCZ0 = originCZ;
        generateWindowTerrain();
        markAllChunksDirty();
    }

    // ---------- N2-1：稀疏窗口差分（快照压缩的底座）----------

    /**
     * 把当前窗口相对「原始地形基线」的差异抽成**稀疏块差分**列表（只含差异数 &gt; 0 的块）。
     *
     * <p>基线由 {@link #ensureBaseline} 在**同一窗口原点**全窗生成并缓存复用 —— 与读档时的重生成同源，
     * 但只在窗口原点首次变化时算一次（N4 前置：消除每 tick 取快照的 204ms 全窗重生成）。
     * 实测（160×112×160 / 92 系统 / 120 tick）：差异 28,699 格（1.0009%），
     * 序列化 21.9 MB → ~259 KB（约 84x）。
     */
    /**
     * N4 前置：把当前窗口相对「缓存的原始地形基线」的差异抽成稀疏差分。
     * 基线只在窗口原点首次变化时生成一次（{@link #ensureBaseline}），之后复用 →
     * 每 tick 取快照不再重生成全窗地形（旧实现 {@code new World()} 约 204ms@160³）。
     */
    public java.util.List<core.net.ChunkDiff> captureWindowDiffs() {
        ensureBaseline();
        return diffAgainstBaseline();
    }

    /** 确保 {@link #baseMat}/{@link #baseMass} 是当前窗口原点的原始地形（惰性生成 + 缓存复用）。 */
    private void ensureBaseline() {
        if (baselineValid && baseMat != null && baseWinCX0 == winCX0 && baseWinCZ0 == winCZ0) return;
        World pristine = new World(seed, SX, SY, SZ, winCX0, winCZ0);   // 与旧 pristineWindow 同源
        baseMat = pristine.mat;        // 引用（pristine 不再被改动 → 安全，不复制省内存/时间）
        baseMass = pristine.mass;
        baseWinCX0 = winCX0; baseWinCZ0 = winCZ0;
        baselineValid = true;
        baselineRegenCount++;
    }

    /** 窗口原点变化（平移/读档到异窗）时失效，下次 captureWindowDiffs/restoreInPlace 重算。 */
    private void invalidateBaseline() { baselineValid = false; baseMat = null; baseMass = null; }

    /** 把当前窗口地形/质量整体重置为缓存基线（原地恢复用，替代 generateWindowTerrain 的整窗重生成）。 */
    private void copyBaseToMat() {
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) { mat[x][y][z] = baseMat[x][y][z]; mass[x][y][z] = baseMass[x][y][z]; }
    }

    /** 相对缓存基线的稀疏差分（仅窗口内）。 */
    private java.util.List<core.net.ChunkDiff> diffAgainstBaseline() {
        java.util.List<core.net.ChunkDiff> out = new java.util.ArrayList<core.net.ChunkDiff>();
        for (int cx = 0; cx < CX; cx++)
            for (int cz = 0; cz < CZ; cz++) {
                int bx = cx * CHUNK, bz = cz * CHUNK;
                int n = 0;
                for (int lx = 0; lx < CHUNK; lx++)
                    for (int y = 0; y < SY; y++)
                        for (int lz = 0; lz < CHUNK; lz++) {
                            int gx = bx + lx, gz = bz + lz;
                            if (mat[gx][y][gz] != baseMat[gx][y][gz]
                                    || Float.compare(mass[gx][y][gz], baseMass[gx][y][gz]) != 0) n++;
                        }
                if (n == 0) continue;
                int[] idx = new int[n]; int[] mt = new int[n]; float[] ms = new float[n];
                int k = 0;
                for (int lx = 0; lx < CHUNK; lx++)
                    for (int y = 0; y < SY; y++)
                        for (int lz = 0; lz < CHUNK; lz++) {
                            int gx = bx + lx, gz = bz + lz;
                            if (mat[gx][y][gz] != baseMat[gx][y][gz]
                                    || Float.compare(mass[gx][y][gz], baseMass[gx][y][gz]) != 0) {
                                idx[k] = (lx * SY + y) * CHUNK + lz;
                                mt[k] = mat[gx][y][gz];
                                ms[k] = mass[gx][y][gz];
                                k++;
                            }
                        }
                out.add(new core.net.ChunkDiff(chunkKeyGlobal(winCX0 + cx, winCZ0 + cz), n, idx, mt, ms));
            }
        return out;
    }

    /** 逐块比对本世界与 {@code base}（仅窗口内），产出稀疏差分。 */
    public java.util.List<core.net.ChunkDiff> diffAgainst(World base) {
        java.util.List<core.net.ChunkDiff> out = new java.util.ArrayList<core.net.ChunkDiff>();
        for (int cx = 0; cx < CX; cx++)
            for (int cz = 0; cz < CZ; cz++) {
                int bx = cx * CHUNK, bz = cz * CHUNK;
                int n = 0;
                for (int lx = 0; lx < CHUNK; lx++)
                    for (int y = 0; y < SY; y++)
                        for (int lz = 0; lz < CHUNK; lz++) {
                            int gx = bx + lx, gz = bz + lz;
                            if (mat[gx][y][gz] != base.mat[gx][y][gz]
                                    || Float.compare(mass[gx][y][gz], base.mass[gx][y][gz]) != 0) n++;
                        }
                if (n == 0) continue;                       // 未改动的块不进快照
                int[] idx = new int[n]; int[] mt = new int[n]; float[] ms = new float[n];
                int k = 0;
                for (int lx = 0; lx < CHUNK; lx++)
                    for (int y = 0; y < SY; y++)
                        for (int lz = 0; lz < CHUNK; lz++) {
                            int gx = bx + lx, gz = bz + lz;
                            if (mat[gx][y][gz] != base.mat[gx][y][gz]
                                    || Float.compare(mass[gx][y][gz], base.mass[gx][y][gz]) != 0) {
                                idx[k] = (lx * SY + y) * CHUNK + lz;   // 升序（lx→y→lz）
                                mt[k] = mat[gx][y][gz];
                                ms[k] = mass[gx][y][gz];
                                k++;
                            }
                        }
                out.add(new core.net.ChunkDiff(chunkKeyGlobal(winCX0 + cx, winCZ0 + cz), n, idx, mt, ms));
            }
        return out;
    }

    /**
     * 把稀疏窗口差分写回 {@code mat}/{@code mass}。**前提**：窗口已由同源 `generateWindowTerrain()`
     * 重生为原始基线（读档路径已保证）。索引由随后的 {@code rebuildIndex()} 统一重建。
     */
    public void applyWindowDiffs(java.util.List<core.net.ChunkDiff> diffs) {
        if (diffs == null) return;
        for (core.net.ChunkDiff d : diffs) {
            int gcx = (int) (d.key >>> 32), gcz = (int) (d.key & 0xFFFFFFFFL);
            int cx = gcx - winCX0, cz = gcz - winCZ0;
            if (cx < 0 || cx >= CX || cz < 0 || cz >= CZ)
                throw new IllegalStateException("窗口差分块不在窗口内: (" + gcx + "," + gcz + ")");
            int bx = cx * CHUNK, bz = cz * CHUNK;
            for (int i = 0; i < d.n; i++) {
                int v = d.idx[i];
                int lz = v % CHUNK; v /= CHUNK;
                int y = v % SY; int lx = v / SY;
                mat[bx + lx][y][bz + lz] = d.mat[i];
                mass[bx + lx][y][bz + lz] = d.mass[i];
            }
        }
    }

    /** 玩家/系统编辑的按块覆盖（卸载后重载仍保留——MC 建造感）。 */
    private static final class Override {
        final int[][][] m; final float[][][] ms;
        Override(int[][][] m, float[][][] ms) { this.m = m; this.ms = ms; }
    }

    /** 把所有区块标记为脏（首帧全量重建，或地形/外部整体变更后调用）。 */
    public void markAllChunksDirty() {
        for (int x = 0; x < SX; x += 16)
            for (int z = 0; z < SZ; z += 16)
                dirtyChunks.add(chunkKey(x, z));
    }

    // ---------- 第三十八批：平移后**只标必要块**（"整窗重建风暴"的解药）----------
    /**
     * 网格的<b>邻居依赖半径</b>（单位：块）。
     *
     * <p>一个块的网格会被"相邻一圈"的方块影响 —— {@code FaceCull.visible} 要读邻格才知道这一面
     * 画不画，{@code Game.emit} 还要读邻格算交界过渡。⇒ 窗口平移后，**只有"邻居集合发生了变化"
     * 的那些块**需要重建网格；其余块的网格在整块搬走之后依然逐字节有效（可由整数偏移补回坐标系）。
     *
     * <p>⚠️ 与 {@link #GEN_HALO} 同性质：它是**与实现耦合的常量**。若将来面剔除开始读 2 圈邻居，
     * 这里和 {@code MeshShiftCheck} 的推导都要跟着改。
     */
    private static final int MESH_NEIGHBOR_HALO = 1;

    /**
     * 窗口平移后把脏块集合**重定向**到"真正需要重建"的那些块。分两步，缺一不可：
     *
     * <ol>
     *   <li><b>重映射已有脏键</b>：脏键指的是<b>内容</b>，而内容跟着块一起搬了（新本地 = 旧本地 − d）。
     *       不重映射的后果有两个方向 —— 去重建了邻居的块（白做），以及**真正被编辑过的那块没被重建**
     *       （地形停在旧形状）。移出窗口的键直接丢弃：那块内容的编辑已随快照离开窗口。</li>
     *   <li><b>补上"因窗口边界变化而必须重建"的块</b>（{@link #markDirtyAxis}）。</li>
     * </ol>
     *
     * <p>⚠️ 这是**渲染提示**（{@code dirtyChunks} 是派生量：不进 {@code hashState}、不进快照），
     * 所以这里可以自由地"少标"；它的正确边界由门禁 {@code MeshShiftCheck} 以
     * "逐块比较『旧网格 + 偏移』与『全量重建』"的方式**派生**验证，而不是写死一份清单。
     */
    private void retargetDirtyOnShift(int dcx, int dcz) {
        if (dcx == 0 && dcz == 0) return;
        // 零重叠（一次跳得比一屏还远：初次定位 / 长距离传送）：没有"重叠区"可复用 ⇒ 全窗皆新块。
        if (Math.abs(dcx) >= CX || Math.abs(dcz) >= CZ) { markAllChunksDirty(); return; }
        if (!dirtyChunks.isEmpty()) {
            java.util.HashSet<Long> neu = new java.util.HashSet<Long>(dirtyChunks.size() * 2);
            for (Long k : dirtyChunks) {
                int cx = (int) (k >>> 32) - dcx, cz = (int) (k & 0xFFFFFFFFL) - dcz;
                if (cx >= 0 && cx < CX && cz >= 0 && cz < CZ)
                    neu.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
            }
            dirtyChunks.clear();
            dirtyChunks.addAll(neu);
        }
        markDirtyAxis(dcx, CX, true);
        markDirtyAxis(dcz, CZ, false);
        // 第三十八批：光照影响带（Lambert 0.91^n < CUTOFF(0.0185) ⇒ n≈42 格 ⇒ 约 3 块）。
        // 新条带是"真·新内容"，其光场会沿 ±42 格渗进窗内 ⇒ 凡在影响带内的块烘焙光都变了，
        // 必须重建（否则复用块带着旧光照，与全量重建逐 float 不一致，MeshShiftCheck 必 FAIL）。
        // 几何面剔除只需 1 圈邻居（markDirtyAxis 已覆盖），光照带是额外的一层。
        markLightBand(dcx, CX, true);
        markLightBand(dcz, CZ, false);
    }

    /** 沿一个轴标出"受新条带光照渗透"的带（向内 {@code LIGHT_BAND_CHUNKS} 块），其余块光照由旋转自洽。 */
    private void markLightBand(int d, int n, boolean axisX) {
        if (d == 0) return;
        int lo, hi;
        if (d > 0) { lo = n - d - LIGHT_BAND_CHUNKS; hi = n; }   // 进入边在 n-d，向内（朝 0）扩带
        else       { lo = 0;                       hi = -d + LIGHT_BAND_CHUNKS; } // 进入边在 0，向内（朝 n-1）扩带
        for (int l = lo; l < hi; l++) {
            if (l < 0 || l >= n) continue;
            markDirtyLine(axisX, l);
        }
    }

    /**
     * 沿一个轴标出"平移后网格会变"的**三条线**（其余块的网格逐字节不变）。以 d&gt;0（窗口沿 +x 走
     * d 块）为例，新本地 l 块的内容来自旧本地 l+d：
     *
     * <ul>
     *   <li><b>[n−d, n)</b>：新进入的条带 —— 网格从未建过（或建的是别的内容）。</li>
     *   <li><b>n−d−1</b>：它紧挨新条带。建它的网格时右侧邻居<b>不存在</b>（那时它是窗口边缘 ⇒
     *       右面一律画）；现在右侧邻居出现了 ⇒ 那一面要按新邻居的方块重新裁定。</li>
     *   <li><b>0</b>：反向的窗口边缘。建它的网格时左侧邻居<b>存在</b>（左面被邻居裁掉了）；
     *       现在它是窗口边缘 ⇒ 左面必须画出来（否则窗口边缘会缺一堵"看板墙"）。</li>
     * </ul>
     *
     * d&lt;0 是同式镜像：[0, −d) / −d / n−1。相邻那两条线只差 {@link #MESH_NEIGHBOR_HALO}=1 圈 ——
     * 若面剔除改成读 2 圈邻居，只需把这两条线各外扩一圈，本方法的其余逻辑不动。
     */
    private void markDirtyAxis(int d, int n, boolean axisX) {
        if (d == 0) return;
        int enterLo, enterHi, adjacent, trailing;
        if (d > 0) { enterLo = n - d; enterHi = n; adjacent = n - d - 1; trailing = 0; }
        else       { enterLo = 0;     enterHi = -d; adjacent = -d;      trailing = n - 1; }
        for (int l = enterLo; l < enterHi; l++) markDirtyLine(axisX, l);
        markDirtyLine(axisX, adjacent);
        markDirtyLine(axisX, trailing);
    }

    /** 标脏整条线（{@code axisX} 时 l 是块 x 下标，否则是块 z 下标）。 */
    private void markDirtyLine(boolean axisX, int l) {
        if (l < 0) return;
        if (axisX) { for (int z = 0; z < SZ; z += CHUNK) dirtyChunks.add(chunkKey(l * CHUNK, z)); }
        else       { for (int x = 0; x < SX; x += CHUNK) dirtyChunks.add(chunkKey(x, l * CHUNK)); }
    }

    /** 注册系统（固定顺序，决定确定性演化顺序）。 */
    /** 注册系统（固定顺序，决定确定性演化顺序）。标签默认 {@link Phase#META}。 */
    public void addSystem(System s) { addSystem(s, Phase.META); }

    /** 注册系统并打上职责标签。标签不参与排序，仅供分组 / 开关 / 审计。 */
    public void addSystem(System s, Phase p) { registry.add(s, p); systems.add(s); }

    /**
     * N2-0：把读档时暂存的**系统实例状态**按系统名逐一对位写入。
     *
     * <p><b>为什么需要它</b>：状态不只在 {@code World} 上 —— 系统对象自身也持有可变状态
     * （实测：{@code WindSystem} 有 {@code private int t} 与 {@code gustX/gustZ}，{@code t} 决定基础风向角；
     * 读档后 {@code t} 从 0 重来 → 风向立刻不同 → 生态/天气全偏）。
     * 而 {@code World.load} 执行时系统**尚未注册**（注册由 {@code Simulation(World)} 负责），
     * 故快照把系统状态存成字节暂留，注册完成后由本方法落位。
     *
     * <p>按**系统名**对位（而非顺序）→ 即使系统列表顺序或数量变化也不会错位。
     */
    public void applyPendingSystemStates() {
        if (pendingSystemState == null) return;
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(pendingSystemState));
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String nm = in.readUTF();
                int len = in.readInt();
                byte[] b = new byte[len];
                in.readFully(b);
                System tgt = null;
                for (System s : systems) if (s.name().equals(nm)) { tgt = s; break; }
                if (tgt != null) core.net.StateCodec.decodeInto(tgt, b);
            }
            pendingSystemState = null;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("applyPendingSystemStates failed", e);
        }
    }

    public int systemCount() { return systems.size(); }

    // ---------- 几何体素访问 ----------
    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < SX && y < SY && z < SZ;
    }
    public int getBlock(int x, int y, int z) {
        if (!inBounds(x, y, z)) return Blocks.BEDROCK.index;
        return mat[x][y][z];
    }

    /**
     * 某一列（x,z）最高 solid 方块的 y；该列整列无 solid 时返回 -1。
     *
     * <p>给"虚空兜底"选落点用：{@link Player#spawnY} 是"扫到最高 solid 之上"，但它在
     * 整列无 solid 时<b>静默返回 1f</b> —— 落点悬在虚空里，于是玩家会立刻再次下落、
     * 被兜底逻辑反复拉起（视觉上疯狂抖动）。所以救援前必须能判"这一列到底有没有地"。
     * 纯读函数，不进任何指纹。
     */
    public int solidColumnTop(int x, int z) {
        if (x < 0 || z < 0 || x >= SX || z >= SZ) return -1;
        for (int y = SY - 1; y >= 0; y--) {
            if (Blocks.byIndex(mat[x][y][z]).solid) return y;
        }
        return -1;
    }
    /**
     * 仿真层体素写入（系统演化用）：更新 mat + 实时空间索引 + 渲染脏标记。
     *
     * <p><b>P0（第三十六批）：它**不再**把所在块标进 {@code editedChunks}</b> —— 只有
     * {@link #editBlock}（玩家建造路径）才标记。三条理由互相印证：
     * <ol>
     *   <li>本类头部声明"chunkEdits 只随被编辑块数增长（<b>RAM 有界</b>）"，
     *       {@code CraftsmanSystem} 的类注释也写着"用 setBlock（仿真写入，<b>不污染 editedChunks</b>）。
     *       工艺体<b>不需要</b>跨卸载持久化" —— 而"存档前修"那次补丁把两者都推翻了；</li>
     *   <li>实测（{@code tools/WindowStreamProbe}）：系统写入会让<b>窗口内 100/100 块全被标记</b>
     *       ⇒ 每走 1 块，离开窗口的那一列（CZ=10 块）全部按<b>整列</b>（229 KB/块）落进 {@code chunkEdits}
     *       ⇒ <b>2.17 MB / 走 1 块</b>（走 1000 块 ≈ 2.1 GB），与本类声明的"RAM 有界"相反；</li>
     *   <li>P1 增量平移落地后，窗口内既有内容（含系统演化）**在窗口内本来就会保留**，
     *       不需要靠"持久化"来保它。</li>
     * </ol>
     * ⇒ "跨卸载持久化"现在只覆盖<b>玩家建造</b>（及显式调用 {@code editBlock} 的写入者）：
     * 走远后远方的系统演化内容会被程序化地形覆盖 —— 这是<b>有意</b>的语义
     * （"附近是活的、远方是程序化的"），也是内存有界的前提。
     *
     * <p>防御：idx 越界（理论不该发生）时仅记日志并跳过，避免整局崩溃（零漂移不受影响，合法 idx 行为不变）。 */
    public void setBlock(int x, int y, int z, int idx) {
        if (!inBounds(x, y, z)) return;
        if (idx < 0 || idx >= Blocks.count()) {
            java.lang.System.err.println("WARN setBlock out-of-range idx=" + idx + " at (" + x + "," + y + "," + z + ")");
            return;
        }
        int old = mat[x][y][z];
        mat[x][y][z] = idx;
        if (old != idx) {
            clearFunctionalState(x, y, z);   // 功能方块状态层：格上换了方块 → 旧状态（门开/箱内容/炉进度）作废
            lightDirty = true;   // 立项 C：方块变更可能改变 LAMP 光的传播路径（墙挡光）→ 光场标脏
            lightDirtyShift = false;   // 第三十九批：编辑改变任意格 → 增量带重算不足以覆盖 ⇒ 退回全量
            // P0（第三十六批）：**不**在这里标 editedChunks —— 仿真演化出的地形由全局坐标确定性
            // 重生成，无需跨卸载持久化（见本方法注释的三条理由）。持久化只留给 editBlock（玩家建造）。
            // M3①：索引改为增量补丁 —— 仅追加一条 (old→new, 坐标) 变更记录（零分配、O(1)），
            // 不再在此逐格 new int[3] + TreeSet 插入/删除。消费侧经 cellsOfType()/nonAirCells()
            // 触发 applyIndexDeltas() 把这一小批变更应用进索引（通常每 tick 几十~几百格）。
            recordIndexDelta(old, idx, x, y, z);
            // ---- 审计：仅当审计驱动（auditSys != null）时记录，生产路径恒跳过 ----
            if (auditSys != null) {
                Integer mc = sysMut.get(auditSys);
                sysMut.put(auditSys, (mc == null ? 0 : mc) + 1);
                long ck = cellKey(x, y, z);
                String pw = dbgCellWriter.get(ck);
                Integer pt = dbgCellTick.get(ck);
                if (pw != null && !pw.equals(auditSys) && pt != null && (tick - pt) <= 6) {
                    String key = pw.compareTo(auditSys) < 0 ? pw + "|" + auditSys : auditSys + "|" + pw;
                    Integer cc = dbgConflict.get(key);
                    dbgConflict.put(key, (cc == null ? 0 : cc) + 1);
                }
                dbgCellWriter.put(ck, auditSys);
                dbgCellTick.put(ck, tick);
            }
        }
        bumpSurface(x, z, y, old, idx);
        // ---- 增量维护地表索引 surfaceCells（纯派生，不进 hashState）----
        // 本格 (x,y,z) 变化会影响：(1) 自身 membership（依赖自己与头顶 (x,y+1,z)）；
        // (2) 正下方格 (x,y-1,z) 的 membership（其头顶即本格，本格由空气变实心/实心变空气会改变它"头顶是否空气"）。
        // 其它格的 membership 取决于各自的头，不受本格影响。故只需重判这两格。
        updateSurfaceMember(x, y, z);
        if (y - 1 >= 0) updateSurfaceMember(x, y - 1, z);
        // ---- 增量维护水面索引 waterSurfaceCells（纯派生，不进 hashState）----
        // 与 surfaceCells 完全镜像：(x,y,z) 自身 membership 依赖自己与头顶 (x,y+1,z)；
        // 正下方格 (x,y-1,z) 的 membership 依赖其头顶即本格；其余格不受影响。故只需重判这两格。
        updateWaterSurfaceMember(x, y, z);
        if (y - 1 >= 0) updateWaterSurfaceMember(x, y - 1, z);
        markDirty(x, y, z);
    }

    /** 玩家/用户编辑写入（建造/挖掘用）：等价于 setBlock 且标记该块“被编辑”，
     *  窗口平移卸载时只有这类块才快照进 chunkEdits 持久化（MC 建造感）。仿真系统请勿调用。 */
    /**
     * 玩家编辑写路径（挖 / 放 / 炸 / 引水 / 引火 / 刻地形）。
     *
     * <p><b>不可破坏方块在此统一拦下</b>：{@link Blocks#BEDROCK} 是世界底层外壳。
     * 此前只有个别调用点做了判断（如 {@code Player.bomb} 的"非基岩"过滤），而**挖方块路径没有** ——
     * 实测可以从地底一路挖穿掉出世界：玩家永远下落、四周再没有任何方块可看（俗称"里世界"）。
     * 收敛到本方法是因为<b>所有玩家写路径都经过它</b>：一处定义胜过逐点判断。
     * 世界生成走 {@link #setBlock}，不受本约束（生成期必须能写基岩）。
     *
     * @return 是否真的改动了世界（{@code false} = 目标不可破坏，调用方应给玩家反馈而不是静默失败）
     */
    public boolean editBlock(int x, int y, int z, int idx) {
        if (inBounds(x, y, z) && mat[x][y][z] == Blocks.BEDROCK.index) return false;
        setBlock(x, y, z, idx);
        editedChunks.add(chunkKeyGlobal(winCX0 + (x >> 4), winCZ0 + (z >> 4)));
        return true;
    }

    // ---------- PERF-SIM 空间索引（升序平铺，纯派生状态，不进 hashState）----------
    /**
     * 返回某类型方块的全部体素坐标（按 x,y,z 升序，live 集合）。调用方须快照后遍历。
     *
     * 返回类型为 {@code Collection<int[]>}（M3① 由 TreeSet 改）：消费者只用
     * {@code size()} / for-each / {@code new ArrayList<>(c)} / {@code new TreeSet<>(c)}，
     * 这些接口 {@link CellSet} 全兼容；内部已是升序平铺数组，迭代序与旧 TreeSet 逐字节一致。
     */
    public java.util.Collection<int[]> cellsOfType(int idx) { ensureIndex(); return typeCells[idx]; }

    /** 该类型体素数的 O(1) 计数（= {@link #cellsOfType} 的 {@code size()}）。 */
    public int countOf(int idx) { ensureIndex(); return typeCells[idx].size(); }

    /**
     * 把该类型全部体素以 (x,y,z) 升序写入 {@code out}（3 int/格），**零每格分配**；返回写入格数。
     * 迭代序与 {@code new ArrayList<>(cellsOfType(idx))} 逐元素一致（同一套归并）——
     * 用于热路径规避 {@link CellSet} 迭代器"每格 new int[3]"。调用方按 {@link #countOf} 定容。
     */
    public int copyOf(int idx, int[] out) { ensureIndex(); return typeCells[idx].copyTriples(out); }
    /** 所有非空气体素（按 x,y,z 升序，live 集合）。调用方须快照后遍历。 */
    public java.util.Collection<int[]> nonAirCells() {
        ensureIndex();
        if (nonAirStale) rebuildNonAir();
        return nonAirCells;
    }

    // ---------- 散布者家族全局上限（第四十一批 / 批⑤，2026-09-29）----------
    // 背景：LEAF/MOSS/ICE/FLOWER 等"散布者"只增不减（唯一移除者 DecaySystem 仅清"六邻皆空气"的悬空块，
    // 移除率 << 生成率）⇒ 长时域下世界被逐步填满（实测 96×48×96 跑 40000 tick：非空气 +17.3% 体积），
    // 属**收敛性缺陷**（世界变满 ⇒ 所有系统共同变贵 + 单色退化）。修 ASH（ASH_MAX_LAYERS=1）只解决灰一个。
    //
    // 修法：给每个散布者一个**派生式**全局上限 = 覆盖分数 × 地表面积(SX×SZ)。
    //   · "派生"：上限不是写死的绝对体素数，而随世界尺寸派生（96² 与 160² 得到不同上限）——
    //     与"总量不许写死"同精神；覆盖分数是**唯一**可调数（同 ASH_MAX_LAYERS 的"改一个数"）。
    //   · 覆盖分数含义 = 该类型体素数 / 地表面积；1.0 ≈ 恰好一层满覆盖。
    //   · 消费侧纪律（AshFallSystem 教训）：上限判据必须放在 rng 抽取**之后** —— 消费侧先取 headroom，
    //     在"已抽取"分支内比较（`host && rng< p && remain>0`），抽取序列不变 ⇒ 不位移本系统随机流。
    //   · 上限只约束**仿真散布**路径（系统 update）；世界生成 / 读档 / 联机 applyWindowDiffs 走 setBlock，
    //     **不受限**（生成期必须能写任意方块，否则地形会被截断）。
    /**
     * 散布者覆盖分数（该类型体素数上限 / 地表面积）。0 = 不设限。
     *
     * <p><b>取值依据（2026-09-29 实测，96×48×96 跑到 40000 tick）</b>：
     * <ul>
     *   <li>LEAF / FLOWER <b>随栖息地自然饱和</b>（LEAF 收敛到 ≈2.1× 地表面积、FLOWER ≈0.94× ≈ 草地数），
     *       故设<b>安全网</b>（2.5× / 1.5×）——留出余量、正常不触发，只防病理种子下的失控。</li>
     *   <li>MOSS / ICE <b>线性增长不收敛</b>（ICE 40k 时已达 1.33× 且仍 +0.33/tick），
     *       故设<b>强制上限</b>（1.0× = 一层满覆盖）——它们的<b>全部</b>写入者均接本上限（见各系统）。</li>
     * </ul>
     */
    public static final float SPREAD_CAP_LEAF   = 2.5f;   // 树冠(~1×)+林下/洞穴(~1×)+ 安全余量
    public static final float SPREAD_CAP_FLOWER = 1.5f;   // 地表装饰（自然 ≈0.94×）+ 余量
    public static final float SPREAD_CAP_MOSS   = 1.0f;   // 岩壁覆苔：一层（强制阈值）
    public static final float SPREAD_CAP_ICE    = 1.0f;   // 水面结冰：一层（强制阈值）
    public static final float SPREAD_CAP_ASH    = 2.0f;   // 火山灰：powder 经 densityFlow 填洼堆积（自然 ≈2.4×）

    /** 某类型的散布上限（体素数）；未设限返回 -1。上限 = 覆盖分数 × 地表面积（世界尺寸派生）。 */
    public int spreadCap(int idx) {
        float f = spreadCapFraction(idx);
        if (f <= 0f) return -1;
        long cap = (long) (f * (float) ((long) SX * SZ));
        return cap > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cap;
    }

    private static float spreadCapFraction(int idx) {
        if (idx == Blocks.LEAF.index)   return SPREAD_CAP_LEAF;
        if (idx == Blocks.FLOWER.index) return SPREAD_CAP_FLOWER;
        if (idx == Blocks.MOSS.index)   return SPREAD_CAP_MOSS;
        if (idx == Blocks.ICE.index)    return SPREAD_CAP_ICE;
        if (idx == Blocks.ASH.index)    return SPREAD_CAP_ASH;
        return 0f;
    }

    /**
     * 距散布上限的剩余可写格数（未设限返回 {@link Integer#MAX_VALUE}；已达上限返回 0）。
     * 消费侧每 tick 取一次、循环内递减即可（避免逐格 size()）；纯派生读取（走 cellsOfType 增量索引），
     * 不写世界、不进 hashState。
     */
    public int spreadHeadroom(int idx) {
        int cap = spreadCap(idx);
        if (cap < 0) return Integer.MAX_VALUE;
        return Math.max(0, cap - cellsOfType(idx).size());
    }

    /**
     * 惰性重建 {@link #nonAirCells}（网格序 x→y→z 扫描 = 天然 (x,y,z) 升序）。
     *
     * <p>与 {@code rebuildIndexStep} 里的 nonAir 填充**同源同序** → 元素与迭代序逐元素一致，
     * 故对唯一读者（{@code nonAirCells()}）而言行为完全不变。纯派生状态，不进 {@code hashState}。
     */
    private void rebuildNonAir() {
        nonAirCells.clearCells();
        nonAirCells.reserveChunks(SX * SY * SZ / 3 + 16);
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++)
                    if (mat[x][y][z] != Blocks.AIR.index) nonAirCells.appendSorted(x, y, z);
        nonAirStale = false;
    }

    /**
     * M3① 索引增量同步：把 setBlock 期间累积的变更补丁应用进索引（消费前调用）。
     * 复杂度 O(变更数 · log n)，与全网格大小无关 —— 这是相对「惰性全量重建」的关键区别。
     * 补丁按追加序（= setBlock 调用序）逐条应用；由于每条都精确执行「旧类型删、新类型加」，
     * 最终索引与「按最终 mat 全量重建」逐元素一致（迭代序由 TreeSet 全序保证不变）。
     * 补丁内容只来自已成功执行的 setBlock（mat 已先行更新），故不会与 mat 不一致。
     */
    private void ensureIndex() {
        if (!indexStale) return;
        int n = indexDeltaLen / 3;
        for (int k = 0; k < n; k++) {
            int oldB = indexDelta[k * 3];
            int newB = indexDelta[k * 3 + 1];
            int packed = indexDelta[k * 3 + 2];
            int x = (packed >>> 20) & 0x3FF;
            int y = (packed >>> 10) & 0x3FF;
            int z = packed & 0x3FF;
            if (oldB != Blocks.AIR.index) typeCells[oldB].removeCell(x, y, z);
            if (newB != Blocks.AIR.index) typeCells[newB].addCell(x, y, z);
            // nonAirCells 改为**惰性重建**（见 nonAirCells()）：跨 AIR 边界只标脏，
            // 不再在此做 O(|nonAirCells|) 的 arraycopy 插入/删除（实测这是整 tick 的最大单项）。
            if ((oldB == Blocks.AIR.index) != (newB == Blocks.AIR.index)) nonAirStale = true;
        }
        indexDeltaLen = 0;
        indexStale = false;
    }

    /** 追加一条索引变更补丁（M3①）；缓冲不足则退化为一次全量重建（正确性优先的极端兜底）。 */
    private void recordIndexDelta(int oldB, int newB, int x, int y, int z) {
        if (indexDeltaLen + 3 > indexDelta.length) {
            // 兜底：先把已有补丁应用掉，再尝试扩容；仍不足则全量重建。
            ensureIndex();
            if (indexDeltaLen + 3 > indexDelta.length) {
                if (indexDelta.length < 3 * 65536) {
                    int[] bigger = new int[indexDelta.length * 2];
                    java.lang.System.arraycopy(indexDelta, 0, bigger, 0, indexDeltaLen);
                    indexDelta = bigger;
                } else {
                    rebuildIndex();   // 极端：单 tick 变更超过 65536 格 → 全量重建一次
                    return;
                }
            }
        }
        // 坐标打包（x,y,z 各 10 bit；SX/SY/SZ ≤ 1024 时无损 —— 本项目 160/112/160 满足）
        int packed = ((x & 0x3FF) << 20) | ((y & 0x3FF) << 10) | (z & 0x3FF);
        indexDelta[indexDeltaLen++] = oldB;
        indexDelta[indexDeltaLen++] = newB;
        indexDelta[indexDeltaLen++] = packed;
        indexStale = true;
    }

    /**
     * 全量重建空间索引（地形重生成 / 窗口平移后调用；M3① 亦为惰性同步入口）。
     *
     * 关键点（M3① 改）：按网格序 x→y→z 扫描时该序**本身即 (x,y,z) 字典序升序**，故直接
     * {@code appendSorted} 顺序追加到平铺数组 —— 零比较器、零对象分配、零移位（快路径），
     * 与旧 TreeSet 的迭代序逐字节一致（对外契约不变）。实测把 583ms 的灌入降到 ~26ms 量级。
     *
     * M3① 二次优化（一趟扫描合并）：把原先「主扫描 + surfaceCells 二次遍历 97 万格 +
     * waterSurfaceCells 三次遍历」三趟合一 —— 在主扫描中同步维护每列最高 solid/非空气/水面
     * （y 递增天然覆盖），再仅对 SX*SZ 列做确定性填充。三趟降为一趟。
     *
     * M3① 三次优化（可切片）：{@link #rebuildIndexBegin} / {@link #rebuildIndexStep} 把
     * 这条 x 循环切成条带，供 {@code stepShift} 的 INDEX 阶段分帧推进（消除 59ms COMMIT 尖峰）。
     * 本方法是其「一次跑完」的等价包装，语义与旧实现逐字节一致。
     * 切片期间索引不完整，故**分帧期禁止 tick**（由 {@code isShifting()} 门控，见 stepShift 注释）。
     *
     * 注意：内部一律用字段 {@code typeCells[...]} / {@code nonAirCells}（**不要**用
     * {@link #cellsOfType} / {@link #nonAirCells()}，那两个会回调 ensureIndex → 无限递归）。
     */
    public void rebuildIndex() {   // 存档读入编辑后须重建空间索引（立项 F）
        rebuildIndexBegin();
        rebuildIndexStep(Integer.MAX_VALUE);
    }

    /** 分帧重建的扫描游标（INDEX 阶段使用；0 = 未开始）。 */
    private int rbiCursorX = 0;

    /** 开始一次（可分帧的）全量索引重建：清空索引 + 预分配。 */
    private void rebuildIndexBegin() {
        indexStale = false;        // 先清标志：本方法内部的读取不得再次触发 ensureIndex
        indexDeltaLen = 0;         // 全量重建已覆盖全部变更 → 待应用补丁作废
        nonAirStale = true;        // 重建期间 nonAir 不完整；若此时被读 → 触发惰性全量重建（比返回半成品安全）
        for (CellSet t : typeCells) t.clearCells();
        nonAirCells.clearCells();
        nonAirCells.reserveChunks(SX * SY * SZ / 3 + 16);   // 预分配上限（非空气 ≤ 全格），一次到位免多次拷贝
        typeCells[Blocks.WATER.index].reserveChunks(SX * SZ * 4);
        surfaceCells.clear();
        waterSurfaceCells.clear();
        rbiCursorX = 0;
    }

    /**
     * 推进（可分帧的）索引重建：处理最多 budgetCols 个 x 条带。返回是否已完成。
     * 必须由 {@link #rebuildIndexBegin} 启动后调用；中途不得 tick（索引不完整）。
     */
    private boolean rebuildIndexStep(int budgetCols) {
        final int waterIdx = Blocks.WATER.index;
        int remaining = budgetCols;
        while (remaining > 0 && rbiCursorX < SX) {
            int x = rbiCursorX;
            int[] topCol = surfaceTopY[x];
            for (int z = 0; z < SZ; z++) topCol[z] = -1;
            for (int y = 0; y < SY; y++) {
                for (int z = 0; z < SZ; z++) {
                    int b = mat[x][y][z];
                    if (b == Blocks.AIR.index) continue;
                    emitCell(b, x, y, z);                       // 网格序 = 天然升序，零移位追加
                    // 每列最高 solid：y 递增扫描，后写覆盖前写 → 最终即最高 solid（与 recomputeColumn 等价）
                    if (Blocks.byIndex(b).solid) topCol[z] = y;
                    // 头顶为空气/越界 → 本格是该列「该实心层顶」(surfaceCells) 或「最顶水」(waterSurfaceCells)
                    if (y + 1 >= SY || mat[x][y + 1][z] == Blocks.AIR.index) {
                        surfaceCells.add(cellKey(x, y, z));
                        if (b == waterIdx) waterSurfaceCells.add(cellKey(x, y, z));
                    }
                }
            }
            // 该 x 条带扫完：回填 surfaceY（每列最顶 solid）
            java.lang.System.arraycopy(topCol, 0, surfaceY[x], 0, SZ);
            rbiCursorX++;
            remaining--;
        }
        boolean done = rbiCursorX >= SX;
        if (done) nonAirStale = false;   // 本次重建已把 nonAirCells 填全
        return done;
    }

    /** 把一格登记进类型索引与 nonAir（必须按 x→y→z 升序调用 → 平铺数组顺序追加，零移位）。 */
    private void emitCell(int blockIdx, int x, int y, int z) {
        typeCells[blockIdx].appendSorted(x, y, z);
        nonAirCells.appendSorted(x, y, z);
    }

    // ---------- 地表缓存（surfaceY[x][z] = 每列最顶实心格 y；派生状态，不进 hashState）----------
    private void recomputeColumn(int x, int z) {
        int top = -1;
        for (int y = SY - 1; y >= 0; y--)
            if (Blocks.byIndex(mat[x][y][z]).solid) { top = y; break; }
        surfaceY[x][z] = top;
    }
    private void bumpSurface(int x, int z, int y, int old, int idx) {
        int cur = surfaceY[x][z];
        boolean os = old != Blocks.AIR.index && Blocks.byIndex(old).solid;
        boolean ns = idx != Blocks.AIR.index && Blocks.byIndex(idx).solid;
        if (ns && y > cur) { surfaceY[x][z] = y; return; }
        if (os && y == cur) { recomputeColumn(x, z); return; }
    }

    /** 增量维护 surfaceCells：(x,y,z) 是否"非空气且其上方为空气/越界（即本列最顶非空气）"。mat 已在 setBlock 中更新为最新值，故直接读取判定。 */
    private void updateSurfaceMember(int x, int y, int z) {
        boolean in = (mat[x][y][z] != Blocks.AIR.index) && (y + 1 >= SY || mat[x][y + 1][z] == Blocks.AIR.index);
        long key = cellKey(x, y, z);
        if (in) surfaceCells.add(key); else surfaceCells.remove(key);
    }

    /** 增量维护 waterSurfaceCells：(x,y,z) 是否"WATER 且其上方为空气/越界（即本列最顶水）"。mat 已在 setBlock 中更新为最新值，故直接读取判定。 */
    private void updateWaterSurfaceMember(int x, int y, int z) {
        boolean in = (mat[x][y][z] == Blocks.WATER.index) && (y + 1 >= SY || mat[x][y + 1][z] == Blocks.AIR.index);
        long key = cellKey(x, y, z);
        if (in) waterSurfaceCells.add(key); else waterSurfaceCells.remove(key);
    }

    /** 计算分块坐标（块大小 16，XZ 方向分块；Y 全高）。不修改任何仿真状态。 */
    public static long chunkKey(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** 标记某体素所在区块为脏（仅渲染用，进 hashState 的字段不受影响）。 */
    public void markDirty(int x, int y, int z) {
        dirtyChunks.add(chunkKey(x, z));
    }

    /** 仿真逐 tick 子流（对应 Python spawn(f"eco:{tick}")）。 */
    public SeededRNG simStream(String name) {
        return rng.deriveStream(name + ":" + tick);
    }

    /** 事件记录（不进指纹，避免 ts 漂移）。 */
    public void log(String system, String action, String params, String result) {
        events.add(new Event(system, action, params, result));
    }

    // ---------- 世界回响接口 ----------
    public void addSkill(String id) { skills.add(id); }
    public boolean hasSkill(String id) { return skills.contains(id); }
    public void recordMemory(String s) { villageMemory.add(s); }

    /**
     * 世界回响（只读派生）：根据当前世界状态拼出一段"村庄记忆续写"叙述，供 UI/村志/NPC 忆往展示。
     * 纯派生——只读 {@link #prosperity} / {@link #skills} / {@link #villageMemory}，
     * 绝不修改 mat/mass/rng/任何仿真状态，不影响 {@link #hashState()} 确定性指纹。
     */
    public String echoNarrative() {
        StringBuilder sb = new StringBuilder();
        int n = villageMemory.size();
        if (n == 0) {
            sb.append("村庄尚在沉睡，尚无回响落进记忆。");
            return sb.toString();
        }
        sb.append("村史已记 ").append(n).append(" 段回响");
        if (prosperity >= 1) {
            sb.append("（你击退来犯之敌 ").append(prosperity).append(" 次，村民记得你的守护）");
        }
        sb.append("。近事：").append(villageMemory.get(n - 1));
        return sb.toString();
    }

    // ---------- 固定步长 tick ----------
    public void tick() {
        if (paused) return;                      // C5：暂停 = 丢 tick（不推进 tick、不耗 RNG、不改网格）
        tick++;
        if (player != null) player.tick(this);   // 玩家回环：始终驱动，死亡也重生（不跳过）
        for (System s : systems) {
            if (registry.isDisabled(s.name())) continue;   // 按域/按名开关（默认全开 → 零开销）
            // 逐系统耗时统计（第三十三批诊断）：`-Dbw.simdiag=1` 时开启，默认关 ⇒ 只多一次布尔判断。
            // 为什么需要：单 tick 实测 median 7.6ms（60fps 一帧仅 16.67ms），不拆到系统级就只能猜谁贵。
            // ⚠️ 两个分支必须调**同一份** `simStream(s.name())` —— 否则诊断模式会改变随机子流。
            if (core.systems.SimProfiler.ON) {
                long t0 = java.lang.System.nanoTime();
                s.update(this, simStream(s.name()));
                core.systems.SimProfiler.add(s.name(), java.lang.System.nanoTime() - t0);
            } else {
                s.update(this, simStream(s.name()));
            }
        }
        updateCarts();   // 第二十一批：矿车推进（见方法注释里"为什么不建系统"）
    }

    /** 轨道上的摩擦（每 tick 速度保留率）：滑得远，但不无限加速。 */
    private static final float RAIL_FRICTION = 0.98f;
    /** 载物时的轨道摩擦（第二十三批）：比空车大 ⇒ 重车滑得近。空车仍用 {@link #RAIL_FRICTION}（既有断言不变）。 */
    private static final float RAIL_FRICTION_CARGO = 0.94f;
    /** 脱轨后的摩擦：迅速停住。 */
    private static final float GROUND_FRICTION = 0.5f;

    /**
     * 矿车推进（第二十一批）—— 每 tick 在**系统循环之后**执行。
     *
     * <p><b>为什么不建系统</b>：矿车推进<b>不抽 RNG</b>、不需要独立的随机子流、也不需要 phase 分组
     * （它只读 {@code mat} / per-block 朝向，并写自己的速度）。而新增系统要同步 `SystemRegistry` 的
     * `COUNT` / `REPORT` / <b>`GOLDEN_HASH`（本项目唯一的真存储常量）</b> —— 代价与收益不成比例。
     * 执行顺序由本方法在 {@link #tick()} 里的<b>代码位置</b>固定（与"追加在注册表末尾"同样确定）。
     *
     * <p><b>零漂移论证</b>：按 {@code carts} 逐车处理、<b>不抽任何 RNG</b>；门禁世界里 {@code carts}
     * 为空 ⇒ 循环体一次都不执行 ⇒ 对既有世界<b>逐字节零影响</b>。
     * （与 {@code MATERIAL-BARE-SAFE} / {@code REACTION-BARE-SAFE} 同一套"空集合兜底"论证。）
     */
    private void updateCarts() {
        for (int i = 0; i < carts.size(); i++) {
            Minecart c = carts.get(i);
            // ⚠️ 这里**不能**写 `floor(c.y - ε)`：矿车的 y 是"底面贴着轨道"⇒ 常常正好是整数，
            //    减 ε 会掉进**下一格**（floor(23 - 0.01) = 22）⇒ 永远判"脱轨"。
            //    （第二十一批实测：矿车只滑了 0.2 格就衰减停住，根因就是这一行。）
            int gx = (int) Math.floor(c.x), gy = (int) Math.floor(c.y), gz = (int) Math.floor(c.z);
            boolean rail = inBounds(gx, gy, gz) && getBlock(gx, gy, gz) == Blocks.RAIL.index;
            c.onRail = rail;
            if (rail) {
                // 走向不再读 per-block 朝向，而由**四邻掩码**惰性推导（第二十五批）——
                // 于是"直线/弯/十字"零新状态，拆邻居也自动正确（判据见 RailShape 类注释）。
                // ⚠️ 这里**必须**只读状态、不抽 RNG：抽一次就会给既有世界重排随机序列。
                int mask = railMask(gx, gy, gz);
                int inDir = dirOfSign(c.vx, c.vz);
                int outDir = (inDir < 0) ? -1 : RailShape.exitDir(mask, inDir);
                if (outDir < 0) {
                    c.vx = 0f; c.vz = 0f;                        // 无出口（终点 / 孤立轨 / 车静止）⇒ 停
                } else {
                    float sp = Math.abs(c.vx) + Math.abs(c.vz);  // 轨道上每 tick 只余一轴 ⇒ 这就是速率
                    c.vx = Facing.DX[outDir] * sp;
                    c.vz = Facing.DZ[outDir] * sp;
                }
                // 载物让车更"重"：摩擦变大 ⇒ 滑得近（"载物"的可见反馈，不是装饰）。
                // 空车仍用 RAIL_FRICTION ⇒ 既有 CART-RUN 断言（滑行 4.56 格）逐字不变。
                float fr = c.cargo.isEmpty() ? RAIL_FRICTION : RAIL_FRICTION_CARGO;
                c.vx *= fr; c.vz *= fr;
                // 下一格不是轨道 ⇒ 到站停车（而不是冲出去变成"会飞的箱子"）
                int nx = (int) Math.floor(c.x + c.vx), nz = (int) Math.floor(c.z + c.vz);
                if (!inBounds(nx, gy, nz) || getBlock(nx, gy, nz) != Blocks.RAIL.index) { c.vx = 0f; c.vz = 0f; }
            } else {
                c.vx *= GROUND_FRICTION; c.vz *= GROUND_FRICTION;
            }
            if (Math.abs(c.vx) < 1e-4f) c.vx = 0f;
            if (Math.abs(c.vz) < 1e-4f) c.vz = 0f;
            // 第二十三批（碰撞）：推进前**统一**检查目标格是否可通行（空气 或 轨道）。
            // ⚠️ 补的是一个**真缺口**：脱轨分支此前只衰减速度、位置照推进 ⇒ 矿车会滑进墙里。
            //   轨道本身 solid=true，所以判据**不能**只写 `!solidForEntity`（那会让矿车骑不上轨道）。
            //   沿轨道分支的"下一格非轨道即停"（到站停车）保持不动 —— 语义不同：那是到站，不是撞墙。
            if (c.vx != 0f || c.vz != 0f) {
                int tx = (int) Math.floor(c.x + c.vx), tz = (int) Math.floor(c.z + c.vz);
                boolean passable = inBounds(tx, gy, tz)
                        && (!solidForEntity(tx, gy, tz) || getBlock(tx, gy, tz) == Blocks.RAIL.index);
                if (!passable) { c.vx = 0f; c.vz = 0f; }
            }
            c.x += c.vx; c.z += c.vz;
        }
    }

    /**
     * 该格轨道的<b>连接掩码</b>（水平四邻是否也是轨道）—— <b>惰性推导、不存储</b>。
     *
     * <p>这是"弯轨/直轨/十字"的<b>唯一真相</b>：形状不落任何状态，所以拆轨/放轨后自动正确，
     * 也不会出现"记忆里还是旧形状"。判据本体（掩码 → 出口方向）是 {@link RailShape} 里的<b>纯函数</b>，
     * 可被门禁穷举；本方法只负责"把世界采样成 4 个布尔值"（与 {@code SowEnv} 的折叠法同一范式）。
     */
    public int railMask(int x, int y, int z) {
        return RailShape.ofNeighbors(
                isRailAt(x + 1, y, z), isRailAt(x - 1, y, z),
                isRailAt(x, y, z + 1), isRailAt(x, y, z - 1));
    }

    private boolean isRailAt(int x, int y, int z) {
        return inBounds(x, y, z) && getBlock(x, y, z) == Blocks.RAIL.index;
    }

    /** 速度 → 水平运动方向（{@link Facing} 的 0/1/4/5）；静止 → {@code -1}。 */
    private static int dirOfSign(float vx, float vz) {
        if (Math.abs(vx) >= Math.abs(vz)) {
            if (vx > 0f) return 0;
            if (vx < 0f) return 1;
        } else {
            if (vz > 0f) return 4;
            if (vz < 0f) return 5;
        }
        return -1;
    }

    /** 该格上的矿车（渲染 / 交互查询用）；没有则 {@code null}。 */
    public Minecart cartAt(int gx, int gy, int gz) {
        for (int i = 0; i < carts.size(); i++) {
            Minecart c = carts.get(i);
            if ((int) Math.floor(c.x) == gx && (int) Math.floor(c.y) == gy       // 与 updateCarts 同一判据
                    && (int) Math.floor(c.z) == gz) return c;
        }
        return null;
    }

    /**
     * 睡眠跳时：把世界时钟<b>前推</b>到 {@code target}（床「跳过夜晚」的落地点，见
     * {@link DayCycle#nextDawn(long)}）。<b>只允许前进</b> —— 倒退时间会破坏"tick 单调递增"
     * 这条上层普遍假设（叙事/统计/采样都按 tick 递增写）。
     *
     * <p><b>为什么它能被安全引入确定性内核</b>：{@code tick} 虽是 {@link #hashState()} 的一部分，
     * 但它<b>只由玩家输入驱动</b>（右键床），而「同 tick + 同输入 → 同结果」正是确定性的定义。
     * 门禁世界从不右键床 ⇒ 时间线逐字节不变 ⇒ 四道零漂移门禁（同种子两跑一致）不动。
     * （注：这里原先写着一个**具体的指纹常量**；第十二批改了仿真内容后它就不再成立 ——
     *  故改为陈述**可证伪的性质**而不是数值，避免下次再被写死。）
     * 又因为时间在本项目是 {@code tick} 的<b>纯函数</b>（{@link DayCycle}），跳时无需重放任何 tick：
     * 不消费 RNG、不跑系统、不改网格 —— 也让这次跳时本身<b>不可能</b>引入漂移。
     *
     * <p>光源场（{@code lightGrid}）不依赖 tick（见 {@link #computeLight()} 的确定性说明）→ 无需标脏。
     */
    public void skipToTick(long target) {
        if (target > (long) tick) tick = (int) Math.min(Integer.MAX_VALUE, target);
    }

    /**
     * 审计专用 tick：与 {@link #tick()} 逻辑完全一致，仅在系统 update 前把 {@link #auditSys} 设为
     * 该系统名，使 setBlock 内的审计块能按系统归因改写次数与冲突。生产代码绝不调用，故不影响确定性。
     */
    public void tickAudited() {
        tick++;
        if (player != null) player.tick(this);
        for (System s : systems) {
            if (registry.isDisabled(s.name())) continue;
            auditSys = s.name();
            s.update(this, simStream(s.name()));
        }
        auditSys = null;
    }

    // ---------- 确定性指纹（排除 fxRng 与 events，守零漂移）----------
    /**
     * 自动上台阶高度（格）：**全项目唯一来源**。玩家（{@code Player.STEP_H}）、村民
     * （{@code NpcSystem.NPC_STEP}）、野兽（{@code BeastSystem.BODY_STEP}）都别名到它。
     *
     * <p>LD-2026-09-16：这三处原本各写一份 {@code 1.05f}，`audit_invariants.py` 的 C2
     * （同一概念同值）把它标成"同一概念散落 3 个文件"—— 只改一处就会出现
     * "NPC 能上的台阶玩家上不去"这类极难查的不一致。收成单一常量后，改一处即全域生效。
     */
    public static final float STEP_HEIGHT = 1.05f;

    // ================== 实体行走的只读地形查询（供 NpcSystem / BeastSystem） ==================
    // 纯读派生状态（surfaceY / mat），不写任何东西、不消费 RNG、不进 hashState —— 因此对确定性零影响。
    // 与 Player.collides 同一判据（只挡 solid，water 可入）；放在 World 里是为了让 NPC 与野兽共用
    // **同一个定义**（"同一概念全项目一个定义"），而不是各自抄一份。

    /**
     * 实体足底足迹（中心 x,z、半宽 hw）能站的**脚底 y**：对足迹覆盖的每一列，从 {@code fromY}
     * 往下扫**真实方块**，取遇到的第一块 solid 的顶面 +1，再对整片足迹取最大值。
     * 传 {@code fromY = SY} 即"整片足迹的最高地面"。找不到地面时返回 1（世界底）。
     *
     * <p><b>为什么按整片足迹取最大</b>：只按中心列取高时，身体一侧会探进相邻的更高方块里，
     * 被深度测试裁掉 —— 观感就是"模型一半没了 / 进墙里了"。
     *
     * <p><b>为什么往下扫方块、而不是用派生的 `surfaceY`</b>（LD-2026-09-16 实测踩坑）：
     * `surfaceY` 只记"该列最顶的 solid"。一旦有东西被放到实体<b>上方</b>（聚落施工、世界滑窗后重建、
     * 树冠），`surfaceY` 就跑到脚底以上，此时"最顶 solid 不高于脚底"的过滤会<b>筛掉整列</b> →
     * 返回兜底值 1 → 实体瞬间掉到世界底部，且因为脚底=1 而永久卡死（实测 302/800 采样）。
     * 往下扫真实方块则天然只看脚下、无视头顶的悬空物。
     */
    public float floorY(float x, float z, float hw, float fromY) {
        int x0 = (int) Math.floor(x - hw), x1 = (int) Math.floor(x + hw - 1e-4f);
        int z0 = (int) Math.floor(z - hw), z1 = (int) Math.floor(z + hw - 1e-4f);
        int top = (int) Math.floor(Math.min(fromY, (float) (SY - 1)));
        if (top > SY - 1) top = SY - 1;
        float best = 1f;
        for (int gx = x0; gx <= x1; gx++) {
            if (gx < 0 || gx >= SX) continue;
            for (int gz = z0; gz <= z1; gz++) {
                if (gz < 0 || gz >= SZ) continue;
                for (int gy = top; gy >= 0; gy--) {
                    Blocks.Block b = Blocks.byIndex(mat[gx][gy][gz]);
                    if (b.solid) {
                        // 第十六批（几何基座）：地面高度 = 该方块的**实心高度** —— 半砖踩在 y+0.5，
                        // 不是 y+1（否则玩家会"站在半砖上方半格的空气里"）。
                        // 满格 b.shapeHeight == 1f ⇒ 与改动前逐字节一致（零回归）。
                        float t = gy + b.shapeBase + b.shapeHeight;
                        if (t > best) best = t;
                        break;
                    }
                }
            }
        }
        return best;
    }

    /**
     * 实体 AABB（脚底中心 x,y,z、半宽 hw、高 h）是否与任何 solid 方块相交。
     * 越界按 BEDROCK 处理（{@link #getBlock} 的既有语义）→ 世界外一律算撞墙。
     */
    public boolean solidBox(float x, float y, float z, float hw, float h) {
        int x0 = (int) Math.floor(x - hw), x1 = (int) Math.floor(x + hw - 1e-4f);
        int y0 = (int) Math.floor(y),     y1 = (int) Math.floor(y + h - 1e-4f);
        int z0 = (int) Math.floor(z - hw), z1 = (int) Math.floor(z + hw - 1e-4f);
        for (int gx = x0; gx <= x1; gx++)
            for (int gy = y0; gy <= y1; gy++)
                for (int gz = z0; gz <= z1; gz++) {
                    if (!solidForEntity(gx, gy, gz)) continue;   // 叠加功能方块状态：开着的门可穿过
                    // 第十六/十七批：非满形状方块占 [gy+base, gy+base+height]，与 AABB **区间相交**才算撞。
                    // 于是"站在半砖上 / 从上半砖下方走过"都成立；贴着走过去时脚底落在实心区间内 ⇒ 判撞 →
                    // 由 STEP_HEIGHT(=1.05) 自动抬腿，手感与整格一致。
                    // 整格 (0,1) ⇒ 区间恒覆盖整格 ⇒ 与改动前逐字节等价（零回归）。
                    int bi = mat[gx][gy][gz];
                    float lo = gy + Blocks.shapeBase(bi), hi = lo + Blocks.shapeHeight(bi);
                    if (Blocks.isStairs(bi)) {
                        // 台阶是 L 形：下半格总在，上半格只占"朝向那一半" ⇒ 按 AABB 中心落在哪一半
                        // 定实心高度。比逐盒求交简单得多，手感却一致（MC 同款简化）。
                        lo = gy;
                        hi = gy + (stairsHighHalf(gx, gy, gz, x, z) ? 1f : 0.5f);
                    }
                    if (y < hi && y + h > lo) return true;
                }
        return false;
    }

    /**
     * 台阶格内"高半边"是否覆盖点 (px,pz) —— 台阶碰撞的**唯一判据**。
     *
     * <p>{@link #solidBox} 与 {@code Player.collides} 都必须调它：两处若各写一份，会立刻出现
     * "站得住但走不进 / 走得进却掉下去"这类**只有实机能发现**的症状（本项目反复吃过这个亏）。
     * 朝向取 per-block {@code blockState}（渲染层写、不进指纹、不进哈希）。
     */
    public boolean stairsHighHalf(int gx, int gy, int gz, float px, float pz) {
        int d = Facing.horizontal(getFacing(gx, gy, gz));
        float cx = gx + 0.5f, cz = gz + 0.5f;
        if (d == 0) return px >= cx;    // +X：高半边在 +x 侧
        if (d == 1) return px <  cx;    // -X
        if (d == 4) return pz >= cz;    // +Z
        return pz < cz;                 // -Z
    }

    public long hashState() {
        long h = 0xcbf29ce484222325L; // FNV-1a 64
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) {
                    h ^= mat[x][y][z]; h *= 0x100000001b3L;
                    h ^= Float.floatToRawIntBits(mass[x][y][z]); h *= 0x100000001b3L;
                }
        h ^= prosperity; h *= 0x100000001b3L;
        h ^= tick; h *= 0x100000001b3L;
        h ^= rng.state(); h *= 0x100000001b3L;        // 仿真 RNG 终态（严格）
        // skills 顺序无关
        List<String> sk = new ArrayList<>(skills); Collections.sort(sk);
        for (String s : sk) { for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 0x100000001b3L; } }
        for (String m : villageMemory) { for (int i = 0; i < m.length(); i++) { h ^= m.charAt(i); h *= 0x100000001b3L; } }
        long bm = Double.doubleToRawLongBits(builtMass); h ^= (bm >>> 32); h ^= bm; h *= 0x100000001b3L;
        return h;
    }

    /**
     * N3：**desync 检测用的宽哈希** —— 覆盖全部被持久化的状态（含实体层、8 个系统对象的全部标量、
     * Player 全字段、系统实例私有状态），而 {@link #hashState()} 是**故意做窄**的（只盖
     * mat/mass/tick/rng/skills/memory/builtMass，为了零漂移门禁的稳定）。
     *
     * <p><b>为什么这样实现</b>：直接**复用既有机制** —— {@code hashState()}（含最贵的 2.87M 格
     * mat/mass 扫描）+ 反射式 {@code StateCodec.encode}（与快照**同源**：编码覆盖什么，
     * 这里就检测什么，**新增字段自动纳入，不存在第二份名单**）。偷懒写"再挑几个字段哈希一下"
     * 必然重蹈 {@code hashState} 做窄的覆辙。
     *
     * <p><b>成本</b>：≈ hashState 的全格扫描（~56ms）+ 反射编码（~155KB）。**不可每 tick 调用**，
     * 每 20~40 tick 校验一次即可（desync 晚 1~2 秒发现完全可接受）。
     *
     * <p><b>不覆盖</b>：{@code intents}（待注入意图队列 = **外部输入**而非世界状态；锁步下
     * 输入由 InputFrame 定序后统一注入，两端本就该一致）。
     */
    public long netHash() {
        long h = hashState();
        try {
            h = fold(h, core.net.StateCodec.encode(this));
            for (System s : systems) {
                h ^= s.name().hashCode(); h *= 0x100000001b3L;
                h = fold(h, core.net.StateCodec.encode(s));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("netHash: 状态编码失败（不该发生 —— 快照能编码它就能）", e);
        }
        return h;
    }

    /** FNV-1a 折叠一段字节（netHash 的辅助）。 */
    private static long fold(long h, byte[] b) {
        for (int i = 0; i < b.length; i++) { h ^= (b[i] & 0xFF); h *= 0x100000001b3L; }
        return h;
    }

    // ---------- 存档 / 读档（叠加在 chunkEdits 流式持久化之上；正交、不影响仿真与四个 gate）----------
    // 格式：简单二进制（DataOutput）。地形由 seed+全局坐标确定性重生成，故只持久化“被编辑块”覆盖；
    // 仿真 RNG 终态通过 setState 精确恢复，保证读档后逐字节继续确定性演化。
    private static final String SAVE_MAGIC = "BWORLD2";   // v2（N2-1）：窗口内编辑改「稀疏差分」；BWORLD1 旧档仍可读
    private static final String SAVE_MAGIC_V1 = "BWORLD1";
    private static final int SAVE_EXT_VERSION = 10;  // v10（P1）追加 quests（任务进度）   // v8（P0-3）追加 player.weaponOwned（已获得武器位掩码）   // 立项 F：扩展段；v4 玩家外观；v5 反射全状态快照；v6（阶段1）追加 9 个捏脸新字段；**v7（阶段 B）追加 3 个服装字段（armor/helmet/cloak）；旧档 ext<7 跳过 → 默认无服装**

    /**
     * 当前窗口内已被编辑的块数（诊断用）。
     *
     * <p>N2-1 起窗口内编辑**不再**经 {@code chunkEdits} 整块落盘（改走 {@link #captureWindowDiffs()}
     * 的稀疏差分），所以原先的 {@code snapshotInWindowEdits()} 已删除 —— 保留本方法只为让门禁能
     * 观测「窗口里到底改了多少块」。
     */
    public int windowEditedChunkCount() {
        int c = 0;
        for (Long gk : editedChunks) {
            int gcx = (int) (gk >>> 32);
            int gcz = (int) (gk & 0xFFFFFFFFL);
            if (gcx >= winCX0 && gcx < winCX0 + CX && gcz >= winCZ0 && gcz < winCZ0 + CZ) c++;
        }
        return c;
    }

    /** 把窗口原点平移到给定全局块坐标并确定性重生成地形（不污染仿真指纹）。 */
    private void restoreWindowOrigin(int cx, int cz) {
        if (cx == winCX0 && cz == winCZ0) return;
        invalidateBaseline();
        winCX0 = cx; winCZ0 = cz;
        generateWindowTerrain();
    }

    /** 把 chunkEdits 中所有落在当前窗口内的覆盖重新施加到 mat/mass（随窗口平移/读档调用）。 */
    private void applyInWindowOverrides() {
        for (java.util.Map.Entry<Long, Override> e : chunkEdits.entrySet()) {
            long k = e.getKey();
            int gcx = (int) (k >>> 32);
            int gcz = (int) (k & 0xFFFFFFFFL);
            if (gcx >= winCX0 && gcx < winCX0 + CX && gcz >= winCZ0 && gcz < winCZ0 + CZ)
                applyOverride(gcx - winCX0, gcz - winCZ0, e.getValue());
        }
        rebuildIndex();
    }

    /** 序列化世界状态到流（文本/二进制由 DataOutput 控制）。不修改任何仿真状态字段。 */
    public void save(java.io.OutputStream raw) throws java.io.IOException {
        java.io.DataOutputStream d = new java.io.DataOutputStream(raw);
        d.writeUTF(SAVE_MAGIC);
        d.writeLong(seed);
        d.writeInt(SX); d.writeInt(SY); d.writeInt(SZ);
        d.writeInt(winCX0); d.writeInt(winCZ0);
        d.writeInt(tick);
        d.writeLong(rng.state());
        d.writeInt(prosperity);
        d.writeFloat(builtMass);
        d.writeInt(skills.size());
        for (String s : skills) d.writeUTF(s);
        d.writeInt(villageMemory.size());
        for (String s : villageMemory) d.writeUTF(s);
        if (player != null) {
            d.writeBoolean(true);
            d.writeFloat(player.x); d.writeFloat(player.y); d.writeFloat(player.z);
        } else d.writeBoolean(false);
        // ---- N2-1：窗口内编辑 = **稀疏差分**（相对原始地形基线）----
        // 实测 21.9 MB → ~259 KB（约 84x）。基线由 captureWindowDiffs() 于同一窗口原点全窗生成，
        // 与读档路径的 new World(...) + restoreWindowOrigin(...) 逐字节同源 → baseline + diff == 存档状态。
        java.util.List<core.net.ChunkDiff> wd = captureWindowDiffs();
        d.writeInt(wd.size());
        for (core.net.ChunkDiff cd : wd) core.net.ChunkDiff.write(cd, d);
        // ---- 窗口**外**的编辑块：仍整块持久化 ----
        // 原因（重要）：Megalith 选址/地面高度会读**邻列** mat，故"原始地形"只在整个窗口按固定序
        // 全量生成时才可复现。异窗上下文里 generateChunk 得到的是**另一个**基线 → 稀疏差分在
        // 该场景下不安全。此处的块数由"玩家实际走过的路径"决定（非全窗），故是可接受的尾项。
        d.writeInt(chunkEdits.size());
        for (java.util.Map.Entry<Long, Override> e : chunkEdits.entrySet()) {
            d.writeLong(e.getKey());
            Override ov = e.getValue();
            for (int lx = 0; lx < CHUNK; lx++)
                for (int y = 0; y < SY; y++)
                    for (int lz = 0; lz < CHUNK; lz++) d.writeInt(ov.m[lx][y][lz]);
            for (int lx = 0; lx < CHUNK; lx++)
                for (int y = 0; y < SY; y++)
                    for (int lz = 0; lz < CHUNK; lz++) d.writeFloat(ov.ms[lx][y][lz]);
        }
        // ---- 扩展段 v2（立项 F：player 完整状态 + 系统状态标量；旧读档器读到即知有扩展）----
        d.writeInt(SAVE_EXT_VERSION);
        if (player != null) {
            d.writeBoolean(true);
            d.writeInt(player.hp); d.writeInt(player.maxHp);
            d.writeInt(player.souls); d.writeInt(player.level); d.writeInt(player.str); d.writeInt(player.weapon);
            d.writeInt(player.abilities.size());
            for (String a : player.abilities) d.writeUTF(a);
            // F 批：玩家外观（捏脸；身份数据，不进 hashState）
            d.writeUTF(player.appearance.presetId); d.writeUTF(player.appearance.name);
            d.writeInt(player.appearance.skin); d.writeInt(player.appearance.hair);
            d.writeInt(player.appearance.hairStyle); d.writeInt(player.appearance.build);
            d.writeInt(player.appearance.accent); d.writeInt(player.appearance.eye);
            d.writeInt(player.appearance.faceShape); d.writeInt(player.appearance.eyeShape);
            d.writeInt(player.appearance.browStyle); d.writeInt(player.appearance.noseStyle);
            d.writeInt(player.appearance.mouthStyle); d.writeInt(player.appearance.age);
            d.writeInt(player.appearance.height); d.writeInt(player.appearance.tattoo);
            d.writeInt(player.appearance.beard);
            // 阶段 B：服装层（v7 起；旧读档器 ext<7 跳过 → 默认无服装）
            d.writeInt(player.appearance.armor);
            d.writeInt(player.appearance.helmet);
            d.writeInt(player.appearance.cloak);
            // P0-3 v8：已获得武器位掩码（掉落不再自动换装 → 不存就会读档丢武器）
            d.writeInt(player.weaponOwned);
        } else d.writeBoolean(false);
        // 系统：civ
        d.writeFloat(civ.research); d.writeFloat(civ.ore); d.writeFloat(civ.iron); d.writeFloat(civ.power);
        d.writeInt(civ.temples); d.writeFloat(civ.believers); d.writeFloat(civ.faith);
        d.writeInt(civ.miracles); d.writeInt(civ.sects);
        d.writeInt(civ.unlocked.size()); for (String s : civ.unlocked) d.writeUTF(s);
        d.writeInt(civ.blueprints.size()); for (String s : civ.blueprints) d.writeUTF(s);
        d.writeInt(civ.festivals.size()); for (String s : civ.festivals) d.writeUTF(s);
        d.writeInt(civ.taboos.size()); for (String s : civ.taboos) d.writeUTF(s);
        d.writeInt(civ.legends.size()); for (String s : civ.legends) d.writeUTF(s);
        // 系统：polity
        d.writeInt(polity.townLevel); d.writeInt(polity.townAlive); d.writeFloat(polity.townStock);
        d.writeInt(polity.townUps); d.writeInt(polity.crimes); d.writeInt(polity.judgedEvents);
        d.writeInt(polity.recidivists); d.writeFloat(polity.coins); d.writeFloat(polity.inflation);
        d.writeInt(polity.caravans); d.writeFloat(polity.caravanFrac); d.writeBoolean(polity.inflating);
        d.writeInt(polity.ledgers);
        d.writeInt(polity.crimeLog.size()); for (String s : polity.crimeLog) d.writeUTF(s);
        // 系统：villageSocial
        d.writeInt(social.npcSeq); d.writeFloat(social.mood); d.writeFloat(social.stress);
        d.writeFloat(social.connected); d.writeInt(social.trauma); d.writeInt(social.crash);
        d.writeBoolean(social.low); d.writeFloat(social.trust); d.writeFloat(social.cooperation);
        d.writeInt(social.violations); d.writeInt(social.sanctions); d.writeBoolean(social.norm);
        // 系统：matter（15 标量）
        d.writeInt(matter.phase); d.writeInt(matter.transitions); d.writeFloat(matter.alloy);
        d.writeFloat(matter.supply); d.writeFloat(matter.work); d.writeFloat(matter.entropy);
        d.writeFloat(matter.exergy); d.writeInt(matter.reactors); d.writeFloat(matter.fusionOut);
        d.writeFloat(matter.radiation); d.writeInt(matter.subs); d.writeFloat(matter.pressure);
        d.writeFloat(matter.mineral); d.writeFloat(matter.pollution); d.writeFloat(matter.colonies);
        // 系统：trials
        d.writeBoolean(trials.heartRevealed); d.writeBoolean(trials.heartClaimed); d.writeInt(trials.relics);
        d.writeBoolean(trials.bossSpawned); d.writeBoolean(trials.bossSlain);   // v9
        d.writeInt(trials.sites.size()); for (Trials.Site s : trials.sites) d.writeBoolean(s.claimed);
        d.writeInt(trials.caches.size()); for (Trials.Cache c : trials.caches) d.writeBoolean(c.claimed);
        // 系统：quests（v10）
        d.writeInt(quests.list.size());
        for (QuestLog.Progress qp : quests.list) {
            d.writeUTF(qp.questId); d.writeInt(qp.nodeIndex); d.writeInt(qp.killCount); d.writeBoolean(qp.completed);
        }
        // 系统：calamity
        d.writeInt(calamity.dryTicks); d.writeInt(calamity.rainStreak);
        d.writeUTF(calamity.flag == null ? "" : calamity.flag);
        d.writeInt(calamity.alerts); d.writeInt(calamity.wildfires);
        // 系统：individual（关键标量）
        d.writeInt(individual.evoGeneration); d.writeFloat(individual.meanFitness);
        d.writeFloat(individual.loyalty); d.writeFloat(individual.mana);
        d.writeFloat(individual.qi); d.writeFloat(individual.mythValue);
        d.writeInt(individual.discoveries); d.writeBoolean(individual.robotRights);
        // 系统：beacons（地标罗盘标记；实体级，不进 hashState）
        d.writeInt(beacons.size());
        for (Beacon b : beacons) { d.writeFloat(b.x); d.writeFloat(b.y); d.writeFloat(b.z); d.writeUTF(b.name); }
        // ---- 扩展段 v5（N2-0 完备性）：反射式全状态快照 ----
        // 覆盖 v2 段没写的东西：实体层（npcs / beasts / shrines / chronicle / events）、
        // 8 个系统对象的**全部**标量（Individual 一个类就约 100 个字段）、Player 的全部字段，
        // 以及 World 的零散状态（fxRng / windX,Z / raining / humidity / paused / builtCells / shift* …）。
        // 用反射而非手写：手写必然漏，而"漏"不会有任何症状 —— 见 core/net/StateCodec 的类注释。
        core.net.StateCodec.write(this, d);
        // ---- v5 附加：**系统实例自身的可变状态** ----
        // 重大发现（N2-0 实测）：状态并不只在 World 上！例如 WindSystem 持有 private int t
        // （t 决定基础风向角）与 gustX/gustZ。读档后 t 从 0 重来 → 风向立刻不同 → 生态/天气全偏。
        // 这打破了「World 的字段 = 全部状态」的假设，故系统实例状态也必须纳入快照。
        // 用**反射**遍历系统字段：任何系统新增私有字段都自动被覆盖（与 World 同策略）。
        java.io.ByteArrayOutputStream sb = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream sd = new java.io.DataOutputStream(sb);
        sd.writeInt(systems.size());
        for (System s : systems) {
            sd.writeUTF(s.name());
            byte[] b = core.net.StateCodec.encode(s);
            sd.writeInt(b.length);
            sd.write(b);
        }
        sd.flush();
        byte[] blob = sb.toByteArray();
        d.writeInt(blob.length);
        d.write(blob);
        d.flush();
    }

    /**
     * 立项 F 修复：以规范的 tick=1 子流重建试炼点/补给箱/世界之心。
     *
     * <p>{@code World.load} 只恢复状态、不跑 tick，而布点发生在 {@code TrialSystem} 的
     * {@code update()} 里且被 {@code w.tick == 1} 门控——所以读档路径必须自己触发。
     * 子流名含 tick，故必须把 tick 暂时置 1 才能逐位复现（{@code deriveStream} 只依赖 seed 与名字，与 rng.state 无关）。
     */
    private static void placeTrialsForLoad(World w) {
        int keep = w.tick;
        w.tick = 1;
        core.systems.TrialSystem.placeOnLoad(w);
        w.tick = keep;
    }

    /** 从流重建世界：重生成确定性地形 → 恢复 RNG 终态 → 施加编辑覆盖 → 恢复繁荣/技能/记忆/玩家。 */
    public static World load(java.io.InputStream raw) throws java.io.IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(raw);
        boolean sparse = readMagic(in);
        return readInto(in, sparse, null);
    }

    /**
     * N2-2：**原地恢复** —— 把快照写回**本实例**（不 new World、不重新注册系统）。
     *
     * <p><b>为什么回滚需要它</b>：{@link #load} 走的是「new World + 由 Simulation 重新注册 92 个
     * 系统」，那会丢掉索引热态与系统实例引用（渲染层 / 其他对象可能持有它们）。原地恢复只把
     * **状态**写回去 —— 这是客户端预测回滚（N2-3）能在每帧反复调用的前提。
     *
     * <p><b>基线契约（关键）</b>：必须先把窗口地形**强制重生成**为原始基线，再叠加稀疏差分 ——
     * 否则差分会被叠在"已被系统改过的"数组上。**不能复用** {@code restoreWindowOrigin}：
     * 它在窗口原点相同时会提前返回、不重生成。
     *
     * <p>seed / 尺寸不符即抛 —— 静默接受不匹配的快照是"沉默走偏"的温床。
     */
    public void restoreInPlace(byte[] snapshot) throws java.io.IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(snapshot));
        boolean sparse = readMagic(in);
        readInto(in, sparse, this);
    }

    /** N4：把当前世界状态序列化为一份内存快照字节（供 {@link #restoreInPlace} 原地恢复）。不改动仿真状态。 */
    public byte[] snapshot() {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            save(bo);
            return bo.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("snapshot 失败（不该发生）", e);
        }
    }

    private static boolean readMagic(java.io.DataInputStream in) throws java.io.IOException {
        String magic = in.readUTF();
        boolean sparse = SAVE_MAGIC.equals(magic);                  // BWORLD2（N2-1 稀疏窗口差分）
        if (!sparse && !SAVE_MAGIC_V1.equals(magic))
            throw new java.io.IOException("bad save magic: " + magic);
        return sparse;
    }

    /** 解析并写回。{@code target == null} → 新建世界；否则**原地**写回（seed/尺寸必须一致）。 */
    private static World readInto(java.io.DataInputStream in, boolean sparse, World target)
            throws java.io.IOException {
        long seed = in.readLong();
        int SX = in.readInt(), SY = in.readInt(), SZ = in.readInt();
        if (target != null && (target.seed != seed || target.SX != SX || target.SY != SY || target.SZ != SZ))
            throw new java.io.IOException("快照与世界不匹配：seed/尺寸不同（"
                    + seed + "/" + SX + "x" + SY + "x" + SZ + " vs "
                    + target.seed + "/" + target.SX + "x" + target.SY + "x" + target.SZ + "）");
        int winCX0 = in.readInt(), winCZ0 = in.readInt();
        int tick = in.readInt();
        long rngState = in.readLong();
        int prosperity = in.readInt();
        float builtMass = in.readFloat();
        int nSkills = in.readInt();
        java.util.List<String> skills = new java.util.ArrayList<>();
        for (int i = 0; i < nSkills; i++) skills.add(in.readUTF());
        int nMem = in.readInt();
        java.util.List<String> mem = new java.util.ArrayList<>();
        for (int i = 0; i < nMem; i++) mem.add(in.readUTF());
        boolean hasPlayer = in.readBoolean();
        float px = 0, py = 0, pz = 0;
        if (hasPlayer) { px = in.readFloat(); py = in.readFloat(); pz = in.readFloat(); }
        // ---- N2-1：窗口内编辑（稀疏差分；BWORLD1 旧档没有这一段）----
        java.util.List<core.net.ChunkDiff> windowDiffs = new java.util.ArrayList<>();
        if (sparse) {
            int nw = in.readInt();
            for (int i = 0; i < nw; i++) windowDiffs.add(core.net.ChunkDiff.read(in));
        }
        int nEdits = in.readInt();
        java.util.List<Long> editKeys = new java.util.ArrayList<>();
        java.util.List<int[][][]> editM = new java.util.ArrayList<>();
        java.util.List<float[][][]> editMS = new java.util.ArrayList<>();
        for (int e = 0; e < nEdits; e++) {
            long key = in.readLong();
            int[][][] m = new int[CHUNK][SY][CHUNK];
            float[][][] ms = new float[CHUNK][SY][CHUNK];
            for (int lx = 0; lx < CHUNK; lx++)
                for (int y = 0; y < SY; y++)
                    for (int lz = 0; lz < CHUNK; lz++) m[lx][y][lz] = in.readInt();
            for (int lx = 0; lx < CHUNK; lx++)
                for (int y = 0; y < SY; y++)
                    for (int lz = 0; lz < CHUNK; lz++) ms[lx][y][lz] = in.readFloat();
            editKeys.add(key); editM.add(m); editMS.add(ms);
        }
        // ---- 重建（新世界）/ 原地写回（N2-2）----
        World w = target;
        if (w == null) w = new World(seed, SX, SY, SZ);
        w.rng.setState(rngState);                 // 精确恢复仿真 RNG 终态（确定性继续）
        if (target == null) {
            w.restoreWindowOrigin(winCX0, winCZ0);    // 平移到存档窗口原点并确定性重生成地形
        } else {
            // 原地：复用缓存基线（N4：消除 generateWindowTerrain 的 204ms 全窗重生成）
            w.chunkEdits.clear();
            w.editedChunks.clear();
            w.winCX0 = winCX0; w.winCZ0 = winCZ0;
            w.ensureBaseline();                       // 当前原点原始基线（命中缓存则零重生成）
            w.copyBaseToMat();                        // mat/mass ← 原始基线
            w.markAllChunksDirty();                   // 渲染层须整体重建（dirtyChunks 是派生、不进快照）
            w.lightDirty = true;                      // LAMP 光场同样须重算
            w.lightDirtyShift = false;                // 第三十九批：整体恢复 ⇒ 全量重算（非平移增量）
        }
        w.tick = tick;
        w.prosperity = prosperity;
        w.builtMass = builtMass;
        w.skills.clear(); w.skills.addAll(skills);
        w.villageMemory.clear(); w.villageMemory.addAll(mem);
        if (hasPlayer) {
            if (w.player == null) w.player = new Player();   // 原地恢复复用既有 Player 实例
            w.player.x = px; w.player.y = py; w.player.z = pz;
        }
        for (int e = 0; e < editKeys.size(); e++) {
            long key = editKeys.get(e);
            w.chunkEdits.put(key, new Override(editM.get(e), editMS.get(e)));
            w.editedChunks.add(key);
        }
        w.applyWindowDiffs(windowDiffs);          // N2-1：先把稀疏窗口差分写回（基线已由 restoreWindowOrigin 生成）
        w.applyInWindowOverrides();               // 再施加整块编辑覆盖（异窗块），并统一 rebuildIndex
        // ---- 扩展段 v2（立项 F）：player 完整状态 + 系统状态标量 ----
        int ext = in.readInt();
        if (ext >= 2) {
            boolean hasP = in.readBoolean();
            if (hasP && w.player != null) {
                w.player.hp = in.readInt(); w.player.maxHp = in.readInt();
                w.player.souls = in.readInt(); w.player.level = in.readInt();
                w.player.str = in.readInt(); w.player.weapon = in.readInt();
                int na = in.readInt();
                for (int i = 0; i < na; i++) w.player.abilities.add(in.readUTF());
                // F 批：玩家外观（v4 起；旧档 ext<4 跳过 → 默认外观，不破坏立项 F）
                if (ext >= 4) {
                    w.player.appearance.presetId = in.readUTF();
                    w.player.appearance.name = in.readUTF();
                    w.player.appearance.skin = in.readInt();
                    w.player.appearance.hair = in.readInt();
                    w.player.appearance.hairStyle = in.readInt();
                    w.player.appearance.build = in.readInt();
                    w.player.appearance.accent = in.readInt();
                    w.player.appearance.eye = in.readInt();
                    // 阶段1：9 个捏脸新字段（v6 起；旧档 ext<6 跳过 → 默认外观）
                    if (ext >= 6) {
                        w.player.appearance.faceShape = in.readInt();
                        w.player.appearance.eyeShape = in.readInt();
                        w.player.appearance.browStyle = in.readInt();
                        w.player.appearance.noseStyle = in.readInt();
                        w.player.appearance.mouthStyle = in.readInt();
                        w.player.appearance.age = in.readInt();
                        w.player.appearance.height = in.readInt();
                        w.player.appearance.tattoo = in.readInt();
                        w.player.appearance.beard = in.readInt();
                        // 阶段 B：服装层（v7 起；旧档 ext<7 跳过）
                        if (ext >= 7) {
                            w.player.appearance.armor = in.readInt();
                            w.player.appearance.helmet = in.readInt();
                            w.player.appearance.cloak = in.readInt();
                            // P0-3 v8：旧档（ext<8）没有该字段 → 视为「只持有当前装备的那把」
                            w.player.weaponOwned = (ext >= 8) ? in.readInt() : (1 << w.player.weapon);
                        }
                    }
                }
            } else if (hasP) {
                throw new java.io.IOException("save has player ext but world has no player");
            }
            w.civ.research = in.readFloat(); w.civ.ore = in.readFloat(); w.civ.iron = in.readFloat(); w.civ.power = in.readFloat();
            w.civ.temples = in.readInt(); w.civ.believers = in.readFloat(); w.civ.faith = in.readFloat();
            w.civ.miracles = in.readInt(); w.civ.sects = in.readInt();
            int ns = in.readInt(); for (int i = 0; i < ns; i++) w.civ.unlocked.add(in.readUTF());
            ns = in.readInt(); for (int i = 0; i < ns; i++) w.civ.blueprints.add(in.readUTF());
            ns = in.readInt(); for (int i = 0; i < ns; i++) w.civ.festivals.add(in.readUTF());
            ns = in.readInt(); for (int i = 0; i < ns; i++) w.civ.taboos.add(in.readUTF());
            ns = in.readInt(); for (int i = 0; i < ns; i++) w.civ.legends.add(in.readUTF());
            w.polity.townLevel = in.readInt(); w.polity.townAlive = in.readInt(); w.polity.townStock = in.readFloat();
            w.polity.townUps = in.readInt(); w.polity.crimes = in.readInt(); w.polity.judgedEvents = in.readInt();
            w.polity.recidivists = in.readInt(); w.polity.coins = in.readFloat(); w.polity.inflation = in.readFloat();
            w.polity.caravans = in.readInt(); w.polity.caravanFrac = in.readFloat(); w.polity.inflating = in.readBoolean();
            w.polity.ledgers = in.readInt();
            ns = in.readInt(); for (int i = 0; i < ns; i++) w.polity.crimeLog.add(in.readUTF());
            w.social.npcSeq = in.readInt(); w.social.mood = in.readFloat(); w.social.stress = in.readFloat();
            w.social.connected = in.readFloat(); w.social.trauma = in.readInt(); w.social.crash = in.readInt();
            w.social.low = in.readBoolean(); w.social.trust = in.readFloat(); w.social.cooperation = in.readFloat();
            w.social.violations = in.readInt(); w.social.sanctions = in.readInt(); w.social.norm = in.readBoolean();
            w.matter.phase = in.readInt(); w.matter.transitions = in.readInt(); w.matter.alloy = in.readFloat();
            w.matter.supply = in.readFloat(); w.matter.work = in.readFloat(); w.matter.entropy = in.readFloat();
            w.matter.exergy = in.readFloat(); w.matter.reactors = in.readInt(); w.matter.fusionOut = in.readFloat();
            w.matter.radiation = in.readFloat(); w.matter.subs = in.readInt(); w.matter.pressure = in.readFloat();
            w.matter.mineral = in.readFloat(); w.matter.pollution = in.readFloat(); w.matter.colonies = in.readFloat();
            w.trials.heartRevealed = in.readBoolean(); w.trials.heartClaimed = in.readBoolean(); w.trials.relics = in.readInt();
            // v9：旧档（ext<9）没有守卫字段 → 视为"守卫从未苏醒"（旧档若已认取世界之心，
            // 则 bossSlain 置真 —— 否则读旧档会重新长出守卫却已拿过心，语义矛盾）。
            w.trials.bossSpawned = (ext >= 9) ? in.readBoolean() : false;
            w.trials.bossSlain = (ext >= 9) ? in.readBoolean() : w.trials.heartClaimed;
            // 立项 F 修复（N2a 前置）：TrialSystem 只在 tick==1 用子流 "trial:place" 布点，
            // 而 load 只恢复状态、不跑 tick。若存档里已有布点（ns>0）必须在此显式重建，
            // 否则 sites.get(i) 越界崩溃（**真实世界存读档必崩**），且读档后世界里没有试炼点。
            // 子流 = SHA256(seed + "|trial:place:1")，只依赖 (seed,name,tick)、与 rng.state 无关 → 可精确复现。
            ns = in.readInt();
            if (ns > 0 && w.trials.sites.isEmpty()) placeTrialsForLoad(w);
            for (int i = 0; i < ns; i++) w.trials.sites.get(i).claimed = in.readBoolean();
            ns = in.readInt();
            for (int i = 0; i < ns; i++) w.trials.caches.get(i).claimed = in.readBoolean();
            // v10：任务进度（旧档 ext<10 没有该段 → 留空，QuestEngine 首次 tick 会重新登记为"未开始"）
            if (ext >= 10) {
                int nq = in.readInt();
                w.quests.list.clear();
                for (int i = 0; i < nq; i++) {
                    QuestLog.Progress qp = new QuestLog.Progress(in.readUTF());
                    qp.nodeIndex = in.readInt(); qp.killCount = in.readInt(); qp.completed = in.readBoolean();
                    w.quests.list.add(qp);
                }
            }
            w.calamity.dryTicks = in.readInt(); w.calamity.rainStreak = in.readInt();
            String flag = in.readUTF(); w.calamity.flag = flag.isEmpty() ? null : flag;
            w.calamity.alerts = in.readInt(); w.calamity.wildfires = in.readInt();
            w.individual.evoGeneration = in.readInt(); w.individual.meanFitness = in.readFloat();
            w.individual.loyalty = in.readFloat(); w.individual.mana = in.readFloat();
            w.individual.qi = in.readFloat(); w.individual.mythValue = in.readFloat();
            w.individual.discoveries = in.readInt(); w.individual.robotRights = in.readBoolean();
            if (ext >= 3) {
                int nb = in.readInt();
                for (int i = 0; i < nb; i++) {
                    float bx = in.readFloat(), by = in.readFloat(), bz = in.readFloat();
                    String bn = in.readUTF();
                    w.beacons.add(new Beacon(bx, by, bz, bn));
                }
            }
            // ---- 扩展段 v5（N2-0 完备性）：反射式全状态快照（原地写回）----
            // 原地语义：不 new 世界，只把值写回已存在的实例；列表元素优先复用原位对象。
            if (ext >= 5) {
                core.net.StateCodec.read(w, in);
                w.rebuildIndex();      // surfaceY / 派生索引按恢复后的 mat 与 surfaceY 重算
                // 系统实例状态：新世界路径下 systems 尚未注册（由 Simulation(World) 负责），故先存原样
                // 字节，待注册完成后由 applyPendingSystemStates() 按系统名逐一对位写入。
                int bl = in.readInt();
                byte[] blob = new byte[bl];
                in.readFully(blob);
                w.pendingSystemState = blob;
                // N2-2：原地恢复时系统**已注册** → 立刻落位，不必等 Simulation。
                if (target != null) w.applyPendingSystemStates();
            }
        }
        return w;
    }

    // ---------- 种子地形生成（确定性，按块流式）----------
    /** 生成整个已加载窗口（初始或窗口平移后调用）。 */
    private void generateWindowTerrain() {
        for (int cx = 0; cx < CX; cx++)
            for (int cz = 0; cz < CZ; cz++) generateChunk(cx, cz);
        rebuildIndex();
    }

    /**
     * 按全局坐标确定性生成单个窗口本地块（cx,cz 为窗口内局部块号）。
     * 基岩/石/泥/草/沙/水由全局值噪声决定（与位置无关，顺序无关）；
     * 树用「按全局块坐标派生」的随机流，保证加载顺序不影响结果（流式零漂移关键）。
     */
    private void generateChunk(int cx, int cz) { generateChunk(cx, cz, winCX0, winCZ0); }

    /**
     * 按全局坐标确定性生成单个窗口本地块（cx,cz 为窗口内局部块号），原点显式给出。
     *
     * M3① 关键：分帧平移期间 {@code winCX0/winCZ0} **尚未提交**（仍是旧窗原点），
     * 而正在生成的块属于新窗 → 必须用**目标原点**计算 baseX/baseZ 与 treeRng 派生键，
     * 否则 treeRng 与 Megalith.fillChunk 都会用错全局坐标（曾实测导致神殿不材化、MEGA 门禁红）。
     * 一次性路径（generateWindowTerrain）传当前 winCX0/winCZ0，语义与旧实现逐字一致。
     */
    private void generateChunk(int cx, int cz, int originCX, int originCZ) {
        long t0 = java.lang.System.nanoTime();
        int waterLevel = SY / 3;                 // 水面在 1/3 高度 → 山谷成湖、海岸沙滩
        int baseX = (originCX + cx) * CHUNK;
        int baseZ = (originCZ + cz) * CHUNK;
        SeededRNG treeRng = rng.deriveStream("tree:" + (originCX + cx) + ":" + (originCZ + cz));
        for (int lx = 0; lx < CHUNK; lx++)
            for (int lz = 0; lz < CHUNK; lz++) {
                int gx = baseX + lx, gz = baseZ + lz;       // 全局坐标（顺序无关）
                int x = cx * CHUNK + lx, z = cz * CHUNK + lz; // 窗口本地坐标
                // 相干地形高度场（粗网格平滑 + 细节 octave）：产生真实丘陵/河谷，
                // 不再是逐列独立哈希造成的“尖刺盆景”，玩家也不再贴着世界天花板出生。
		double n = terrainHeightField(gx, gz);
		int maxH = SY - 6;                            // 顶部留 6 格空气，相机不顶到天花板
		// 修复1（历史）：以水位为中枢、向下偏移 0.25*range → 约 1/4 列下探到水位以下成湖、海岸成沙。
		// 修复2（2026-09-11 本机验收）：SY 48→112 后振幅 (maxH-waterLevel) 达 69 → 坡度 >2.5 格/格，
		// 玩家落在冲沟底时跳不出 1.25 格跳跃高度（"卡住动不了"）→ 振幅减半，并放宽噪声尺度
		// （terrainHeightField：12/4 → 18/6、权重 0.75/0.25 → 0.8/0.2）让丘陵更宽缓可攀。
		// 全局坐标确定性不变；基线指纹为记录值，随本次有意演进（门禁为自洽断言，仍全绿）。
		int amp = Math.max(4, (maxH - waterLevel) / 2);
		int h = Math.max(2, waterLevel + (int) ((n - 0.25) * amp));
                int biome = biomeAt(gx, gz);   // 立项 B：温带/雪原/沙海/花海（小世界 SY=40 恒温带=门禁零漂移）
                for (int y = 0; y < SY; y++) {
                    int idx;
                    if (y == 0) idx = Blocks.BEDROCK.index;
                    else if (y < h - 4) idx = Blocks.STONE.index;
                    else if (y < h) idx = Blocks.DIRT.index;
                    else if (y == h && h <= waterLevel) idx = Blocks.SAND.index;
                    else if (y == h) idx = biome == BIOME_SNOWY ? Blocks.SNOW.index
                                       : biome == BIOME_ARID ? Blocks.SAND.index
                                       : Blocks.GRASS.index;
                    else if (y == h - 1 && biome == BIOME_ARID) idx = Blocks.SAND.index;   // 沙海加厚一层
                    else if (y <= waterLevel) idx = (biome == BIOME_SNOWY && y == waterLevel)
                                                       ? Blocks.ICE.index : Blocks.WATER.index;   // 雪原冻湖
                    else idx = Blocks.AIR.index;
                    mat[x][y][z] = idx;
                    mass[x][y][z] = idx == Blocks.AIR.index ? 0f : 1f;
                }
                // 确定性洞穴雕刻（仅地下 STONE/DIRT；不碰基岩 y=0、不破地表草/沙、不碰水中）。
                // 产生封闭/连通空腔，激活休眠的地下系统（Lava/Geode/Crystal/MagmaChamber/Sinkhole）。
                // 下探到 y=1（基岩 y=0 正上方留空腔），让 Lava/MagmaChamber 的“近基岩空腔”条件成立。
                // 纯全局坐标函数 → 流式顺序无关、同种子逐字节一致。
                for (int y = 1; y <= h - 2 && y < SY - 1; y++) {
                    int b = mat[x][y][z];
                    if (b == Blocks.STONE.index || b == Blocks.DIRT.index) {
                        if (caveAt(baseX + lx, y, baseZ + lz) > 0.66) {
                            mat[x][y][z] = Blocks.AIR.index;
                            mass[x][y][z] = 0f;
                        }
                    }
                }
                // 稀疏长树（确定性，仅陆地草原；树随机流按全局块坐标派生）
                if (mat[x][h][z] == Blocks.GRASS.index && h > waterLevel + 1
                        && treeRng.nextInt(SCATTER_TREE_DENOM) < 1 && h + 5 < SY) {
                    for (int t = 1; t <= 4; t++) mat[x][h + t][z] = Blocks.WOOD.index;
                    int ty = h + 5;
                    for (int dx = -2; dx <= 2; dx++)
                        for (int dz = -2; dz <= 2; dz++)
                            for (int dy = -1; dy <= 1; dy++) {
                                int lxx = x + dx, lyy = ty + dy, lzz = z + dz;
                                if (inBounds(lxx, lyy, lzz) && mat[lxx][lyy][lzz] == Blocks.AIR.index
                                        && (Math.abs(dx) + Math.abs(dz) + Math.abs(dy)) <= 2)
                                    mat[lxx][lyy][lzz] = Blocks.LEAF.index;
                            }
                }
                // 立项 B：群系装饰（确定性；额外 nextInt 只发生在群系列 → 同种子仍逐字节一致）
                if (biome == BIOME_SNOWY && h > waterLevel && h + 5 < SY && treeRng.nextInt(SCATTER_SNOW_TREE_DENOM) < 1) {
                    for (int t = 1; t <= 4; t++) mat[x][h + t][z] = Blocks.WOOD.index;      // 雪原稀树（云杉样）
                    int ty = h + 5;
                    for (int dx = -2; dx <= 2; dx++)
                        for (int dz = -2; dz <= 2; dz++)
                            for (int dy = -1; dy <= 1; dy++) {
                                int lxx = x + dx, lyy = ty + dy, lzz = z + dz;
                                if (inBounds(lxx, lyy, lzz) && mat[lxx][lyy][lzz] == Blocks.AIR.index
                                        && (Math.abs(dx) + Math.abs(dz) + Math.abs(dy)) <= 2)
                                    mat[lxx][lyy][lzz] = Blocks.LEAF.index;
                            }
                } else if (biome == BIOME_ARID && h > waterLevel && h + 3 < SY && treeRng.nextInt(SCATTER_CACTUS_DENOM) < 1) {
                    int ch = 2 + (int) (cellHash(gx, gz, seed ^ SCATTER_CACTUS_SALT) * 2f);             // 2-3 格仙人掌
                    for (int t = 1; t <= ch; t++) mat[x][h + t][z] = Blocks.CACTUS.index;
                } else if (biome == BIOME_BLOOM && h > waterLevel && h + 1 < SY
                        && mat[x][h + 1][z] == Blocks.AIR.index
                        && cellHash(gx, gz, seed ^ SCATTER_BLOOM_SALT) < SCATTER_BLOOM_FLOWER_P) {
                    mat[x][h + 1][z] = Blocks.FLOWER.index;                                 // 花海：16% 密度撒花
                }
            }
        // ---- C6 巨构（Phase 2）：神殿材化进 mat。仅高世界（SY ≥ MIN_SY）触发 → 门禁小世界（SY=40）零漂移 ----
        if (SY >= Megalith.MIN_SY) Megalith.fillChunk(this, baseX, baseZ, originCX, originCZ);
        lightDirty = true;   // 立项 C：新块可能含 LAMP（神殿祭坛灯）→ 光场标脏（Game 渲染惰性重算）
        lightDirtyShift = false;   // 第三十九批：新块生成（含首次/巨构）一律全量；平移增量由 COMMIT 统一置位
        profGenNs += java.lang.System.nanoTime() - t0;
        profGenChunks++;
    }

    // ---------- 立项 B：生物群系表层（MC_BENCHMARK_AUDIT 复审翻案项 2）----------

    /** 群系常量：温带（默认，与引入前完全一致）/ 雪原 / 干旱沙海 / 花海。 */
    public static final int BIOME_TEMPERATE = 0, BIOME_SNOWY = 1, BIOME_ARID = 2, BIOME_BLOOM = 3;

    // ---------- AP-PCG：群系撒布参数表（PCG 化第一层：密度/盐值/规格数据化）----------
    // 纪律：treeRng 是<b>顺序流</b> → 撒布执行骨架（短路次序、每格对 treeRng 的调用次数）
    // 必须与本表引入前逐字节一致；调参只许改这里的数值，不许动生成代码的结构
    // （改结构 = 全世界演化序列漂移 = 四道基线指纹作废）。PCG 化第二层（执行序数据化）
    // 收益低、漂移风险高，明确不做。
    /** 温带草原：树的稀疏密度（每草格 1/N 概率，短路后才消耗 treeRng）。 */
    static final int SCATTER_TREE_DENOM = 250;
    /** 雪原：云杉样稀树密度（每格 1/N）。 */
    static final int SCATTER_SNOW_TREE_DENOM = 600;
    /** 干旱沙海：仙人掌密度（每格 1/N）。 */
    static final int SCATTER_CACTUS_DENOM = 180;
    /** 仙人掌高度盐（cellHash → 2..3 格）。 */
    static final long SCATTER_CACTUS_SALT = 0xCAC7L;
    /** 花海：野花密度（cellHash &lt; p 每格撒一株）。 */
    static final double SCATTER_BLOOM_FLOWER_P = 0.16;
    /** 野花盐。 */
    static final long SCATTER_BLOOM_SALT = 0xF10AB1EL;

    /** F3 调试显示用群系名。 */
    public static String biomeName(int b) {
        switch (b) {
            case BIOME_SNOWY:  return "SNOWY";
            case BIOME_ARID:   return "ARID";
            case BIOME_BLOOM:  return "BLOOM";
            default:           return "TEMPERATE";
        }
    }

    /**
     * 生物群系判定（纯全局坐标 + 种子函数，顺序无关 → 流式零漂移）。
     * SurfaceRules 思想：**高度公式完全不动**，只换表层方块——温/湿两条大尺度（64 格）
     * 确定性噪声查表：雪原（寒）/ 干旱沙海（热且干）/ 花海（湿且温）/ 温带（默认）。
     * <p>门控：仅 {@code SY >= Megalith.MIN_SY} 的高世界启用（同巨构先例）——门禁小世界
     * （SY=40）恒返回温带 → <b>17 道门禁与四道基线指纹逐字节不变（零重基线）</b>。</p>
     */
    public int biomeAt(int gx, int gz) {
        if (SY < Megalith.MIN_SY) return BIOME_TEMPERATE;
        double temp  = valueNoise(gx, gz, seed ^ 0x5EED10L, 64);
        double humid = valueNoise(gx, gz, seed ^ 0x51AB5L, 64);
        if (temp < 0.30) return BIOME_SNOWY;                          // 寒 → 雪原
        if (temp > 0.70 && humid < 0.42) return BIOME_ARID;           // 热 且 干 → 沙海
        if (humid > 0.62 && temp >= 0.30 && temp <= 0.70) return BIOME_BLOOM; // 湿 且 温 → 花海
        return BIOME_TEMPERATE;
    }

    // ---------- 立项 C：LAMP 块光 BFS（MC 复审翻案项 3）----------

    /**
     * 光场量化满级 = 源格强度。同时决定 {@link #lightGrid} 这个 {@code byte[]} 的**分辨率**。
     *
     * <p><b>2026-09-21 从 14 提到 64（两扫光照重写的一部分）</b>：旧 BFS 是"每格减 1"，
     * 14 级刚好够表达 14 格硬截断的光斑；但两扫光照是**指数长尾**（0.91^n，有效半径 ~42 格），
     * 14 级量化会产生肉眼可见的**色阶环**（实测 0.5 连续出现两次、相邻格跳变 7%）。
     * 提到 64 后单格量化步长 ≈ 1.6%，在 8bit 输出上不再成环。
     * <p>为什么不是 128/255：{@code lightGrid} 是 <b>signed</b> {@code byte}（上限 127），
     * 64 留足余量且够精细。要更高精度须改存 {@code short[]}（成本 ×2 内存，暂不需要）。
     */
    public static final int LIGHT_R = 64;
    /**
     * 火光**强度占比**（0..1，乘 {@link #LIGHT_R} 得量化级）—— 火是**局部暖光**而非照明灯，
     * 夜里火堆/燃烧的森林应是一团跳动的小暖光，而不是把四周照成白昼。
     * <p>为什么是"占比"而不是旧版那样的"半径（格）"：两扫光照的衰减**与半径无关**
     * （光的有效范围由 {@link core.content.MaterialDef#LIGHT_DECAY_AIR} 的指数长尾决定，
     * 不是由源强度决定），所以这里唯一有意义的量就是"起点多亮"。
     * <p>为什么让 FIRE 发光：与 P2-2 火杠杆配套 —— 玩家放的火/雷点的树若"不亮"，
     * 夜里就是一团无声的色块；发光后火才有存在感（且纯渲染派生，零漂移）。
     * <p>0.62 ≈ 旧值 9/14（观感对齐），但不再暗示"半径 9 格"。
     */
    public static final float FIRE_LIGHT_FRACTION = 0.62f;

    /** LAMP 块光场 [x*SY+y)*SZ+z → 0..LIGHT_R。<b>渲染派生缓存</b>：不进 hashState、World.tick 永不触碰（Game 渲染惰性重算）→ 门禁零漂移。 */
    public byte[] lightGrid;
    /** 光场脏标记：setBlock / generateChunk 置位，Game 渲染消费并清除。 */
    public boolean lightDirty = true;
    /** 增量光照标志（第三十九批）：窗口平移后，光场已随 {@code mat} 旋转、只需重算新条带（见 {@link #computeLight} 分支）。 */
    public boolean lightDirtyShift = false;
    /** 增量光照用的平移量（块坐标，与 {@link #relocateWindow} 同语义）；仅 {@link #lightDirtyShift} 为 true 时有效。 */
    private int lightShiftSX = 0, lightShiftSZ = 0;
    /** 增量重算带区：本轴"离开条带"是否有光源退出（退出 ⇒ interior 丢光，须含离开边）。 */
    private boolean lightLeaveX = false, lightLeaveZ = false;
    /** 光场是否已在 {@link #relocateWindow} 中随 mat 旋转（零重叠平移跳过旋转 → 必须走全量重算）。 */
    private boolean lightRotated = false;

    // ---------- 第四十批（平移墙钟测量）诊断计时 ----------
    // ⚠️ 仅用 java.lang.System.nanoTime() 累加耗时，**完全不参与仿真 RNG**（仿真只走 simStream）→ 零漂移不受影响。
    //    这些是只读性能探针，供 tools/ShiftTiming.java 读取；生产逻辑对它们无依赖。
    /** relocateWindow 内 mat/mass/surfaceY/surfaceTopY 全量搬运累计耗时（ns）。 */
    public long profRelocNs = 0;
    /** 平移期间 generateChunk（新条带生成）累计耗时（ns）。 */
    public long profGenNs = 0;
    /** 平移期间 generateChunk 调用次数（= 新进入条带块数）。 */
    public int profGenChunks = 0;
    /** COMMIT 阶段 retargetDirtyOnShift + relocateIndex 累计耗时（ns）。 */
    public long profCommitNs = 0;
    /** stepShift 整体累计耗时（ns，含 reloc+gen+commit 分帧聚合）。 */
    public long profShiftNs = 0;
    /** computeLight 走<b>增量</b>分支（带区重算）累计耗时（ns）与次数。 */
    public long profComputeIncNs = 0;
    public int profComputeIncN = 0;
    /** computeLight 走<b>全量</b>分支（含光源退出回退）累计耗时（ns）与次数。 */
    public long profComputeFullNs = 0;
    public int profComputeFullN = 0;
    /** 清零所有平移计时（测量前调用）。 */
    public void resetShiftProfile() {
        profRelocNs = profGenNs = profCommitNs = profShiftNs = 0; profGenChunks = 0;
        profComputeIncNs = profComputeFullNs = 0; profComputeIncN = profComputeFullN = 0;
    }

    // ---------- Terraria 式两扫光照（2026-09-21，FOUR_SOURCE_PICK §2）----------
    //
    // 为什么放弃逐源 BFS：BFS 的成本随光源数线性增长，且只有一个"不透明格挡光"的布尔 →
    // 结果**硬边无渐变**（14 格内每格减 1/14，14 格外突兀归零）。而 Terraria 的做法是
    // 「**与源数无关**的固定两轮扫描」：把整个世界网格扫两遍（每遍行列各一次），
    // 每格按自身材料的 lightDecay 衰减、取邻格历史最大值。
    //   · 成本 = 2 遍 × 全网格（常数，与灯数无关）→ 天然确定性（无队列、无松弛、无顺序依赖）
    //   · 观感 = 指数衰减的长尾渐变（0.91^n），火把照出一片柔和光晕而非"灯泡硬边"
    //   · 语义变化（**有意演进**）：不再是"每格减 1/14"，而是"每格乘 lightDecay"。
    // 证据：`_study/terraria/tModLoader_proj/Terraria.Graphics.Light/LightMap.cs`
    //   `Blur()`(131-136) = `BlurPass(); BlurPass();`（**正好 2 次**）；`BlurPass`(138-160) 一次发 4 条扫；
    //   `LightDecayThroughAir=0.91` / `Solid=0.56`（`LightingEngine.cs`）；低于 `0.0185f` 停（`BlurLine` 173）。

    /** 光照迭代轮数 —— 硬编码 2（Terraria `LightMap.Blur()` 就是两次 `BlurPass()`）。门禁断言它的值。 */
    public static final int LIGHT_PASSES = 2;
    /**
     * 低于此强度即视为"无光"（Terraria `LightMap.BlurLine` 的 {@code 0.0185f} 截断）。
     *
     * <p>它同时是<b>性能与观感的双赢旋钮</b>：0.91^n &lt; 0.0185 时 n≈42，即光的有效半径约 42 格
     * （对比旧的硬编码 14 格）。再远的部分人眼已不可辨，扫它是纯浪费。
     */
    public static final float LIGHT_CUTOFF = 0.0185f;

    /** 光照有效半径（块）：0.91^n < CUTOFF ⇒ n≈42。窗口平移时受新条带光照渗透的带宽（块）= ⌈42/CHUNK⌉。 */
    public static final int LIGHT_BAND_CHUNKS = (int) Math.ceil(42.0 / CHUNK);

    /** 采样 (x,y,z) 块光亮度 [0,1]（越界/未初始化 → 0）。 */
    public float lightAt(int x, int y, int z) {
        if (lightGrid == null || x < 0 || y < 0 || z < 0 || x >= SX || y >= SY || z >= SZ) return 0f;
        return lightGrid[(x * SY + y) * SZ + z] / (float) LIGHT_R;
    }

    /**
     * 全量重建块光场（LAMP + FIRE）：<b>Terraria 式固定两轮扫描</b>（每轮向 6 个轴向各扫一次）。
     *
     * <p>算法（学 {@code LightMap.BlurPass}）：
     * <ol>
     *   <li>清场 → 把每个 LAMP/FIRE 源格写入自身强度（LAMP=1.0 / FIRE=FIRE_LIGHT_R/LIGHT_R）</li>
     *   <li>重复 {@link #LIGHT_PASSES} 次：沿 ±x / ±y / ±z 各扫一趟，每格
     *       {@code self = max(self, neighbor * decay(neighbor))}，低于 {@link #LIGHT_CUTOFF} 跳过</li>
     * </ol>
     * 为什么"正扫 + 反扫"都要：只正扫光只能向 +方向传（图像会偏）；Terraria 一次发 4 条（上下左右），
     * 我们 3D 故为 6 条（±x/±y/±z）。两次往返让光在拐角/凹槽里也能绕过去。
     *
     * <p><b>确定性</b>：固定迭代次数、固定遍历顺序、无队列无松弛、无 RNG、不读 tick →
     * 同种子同 mat 逐字节一致（门禁 `LIGHT` 断言）。<b>仅渲染线程调用</b>，与 tick 同线程。
     */
    public void computeLight() {
        long t0 = java.lang.System.nanoTime();
        ensureIndex();   // 必须先应用索引增量：本函数直接读 typeCells[LAMP/FIRE]，若索引 stale 会漏掉刚放的灯/火
        // 第三十九批：平移增量路径 —— 光场已随 mat 旋转、只需重算新条带（见 recomputeLightBand）。
        //   仅在「增量标志已置位 + 光场已旋转 + 非平移中（mat 已定稿）+ 无光源退出窗口」时走此分支，
        //   否则退回全量重算。
        //   ⚠️ 光源退出：退出光源原本照亮 interior 的光被旋转带入新 interior → 残留 stale 亮度；
        //     受限带区扫描读 interior 邻格（仍含 stale 光）→ 离开边得到错误亮度。光是全局不动点，
        //     interior 被污染区域可达整窗 ⇒ 带区隔离无效。故「有光源退出」直接走全量（见 lightLeaveX/Z）。
        if (lightDirtyShift && lightGrid != null && lightWork != null && !shifting
                && !lightLeaveX && !lightLeaveZ) {
            recomputeLightBand();
            lightDirtyShift = false;
            profComputeIncNs += java.lang.System.nanoTime() - t0;
            profComputeIncN++;
            return;
        }
        lightDirtyShift = false;   // 走全量 ⇒ 增量标志失效（无论因何未走增量分支）
        if (lightGrid == null) {
            lightGrid = new byte[SX * SY * SZ];
        } else {
            java.util.Arrays.fill(lightGrid, (byte) 0);
        }
        // 浮点工作缓冲：**扫描全程用 float，最后一次性量化成 byte**。
        // 为什么不能用 byte 当工作缓冲：光每走一格就要读上一格的值再乘 decay，
        // 若中途就 round 到 1/14 的整数格，误差会**逐格复利放大**
        // （实测 0.56 链：14→8→4→2→1，比真值 14→7.84→4.39→2.46 快得多，光"提前死掉"）。
        // Terraria 的 LightMap 内部也是 float[]，只在对外 API 边界量化 —— 这里照抄这个边界划分。
        if (lightWork == null || lightWork.length != SX * SY * SZ) lightWork = new float[SX * SY * SZ];
        else java.util.Arrays.fill(lightWork, 0f);
        seedLight(Blocks.LAMP.index, 1.0f);                        // 暖炉/灯塔：满强度
        seedLight(Blocks.FIRE.index, FIRE_LIGHT_FRACTION);         // 火：较弱暖光（夜里火堆/燃烧的森林发光）
        // 第十二批：晶簇/晶石接进光场（它们替代了原来当"深岩微光"的 LAMP）。
        //  门禁世界里没有任何 CRYSTAL ⇒ seedLight 不进任何种子 ⇒ 光场（不进 hashState）与既有世界等价。
        seedLight(Blocks.CRYSTAL_CLUSTER.index, 0.55f);            // 晶簇：烛光级冷辉
        seedLight(Blocks.CRYSTAL.index, 0.35f);                    // 晶石块：更弱（洞壁余辉）
        // 第七批：篝火 —— 常燃热源，与 FIRE 同档强度（"人点的火堆"本就该照亮周围）。
        // 零漂移：旧世界（含全部门禁世界）不含 CAMPFIRE → typeCells[CAMPFIRE] 为空 → 循环体不执行 → 光场逐字节不变。
        seedLight(Blocks.CAMPFIRE.index, FIRE_LIGHT_FRACTION);
        // 固定两轮扫描（每轮 6 个方向各一趟）—— 光沿所有轴向双向传播 + 拐角绕行。
        for (int pass = 0; pass < LIGHT_PASSES; pass++) {
            sweepAxis(0, true);  sweepAxis(0, false);   // ±x
            sweepAxis(1, true);  sweepAxis(1, false);   // ±y
            sweepAxis(2, true);  sweepAxis(2, false);   // ±z
        }
        // 量化：float 工作缓冲 [0,1] → byte 光场 [0,LIGHT_R]（对外 API 的唯一表示）。
        // 注意 `Math.round` 之前的 `* LIGHT_R`：lightWork 存的是 [0,1] 归一化强度，
        // lightGrid 存的是 0..LIGHT_R 的量化级（{@link #lightAt} 再除回 LIGHT_R）。
        for (int i = 0; i < lightWork.length; i++) {
            float v = lightWork[i];
            if (v <= 0f) continue;
            int q = Math.round(v * LIGHT_R);
            if (q > LIGHT_R) q = LIGHT_R;
            else if (q < 0) q = 0;
            lightGrid[i] = (byte) q;
        }
        // 【运行时不变式】量化后源格必须仍为满强度（若被 clamp/round 吃掉，渲染会整体偏暗而无提示）。
        if (!lightInvariantChecked) {
            lightInvariantChecked = true;
            for (int[] src : typeCells[Blocks.LAMP.index]) {
                int si = (src[0] * SY + src[1]) * SZ + src[2];
                if (lightGrid[si] != (byte) LIGHT_R) {
                    java.lang.System.out.println("[LIGHT] FAIL 源格量化后非满强度: "
                            + src[0] + "," + src[1] + "," + src[2] + " = " + lightGrid[si]);
                    lightInvariantOk = false;
                    break;
                }
            }
        }
        profComputeFullNs += java.lang.System.nanoTime() - t0;
        profComputeFullN++;
    }

    /** 浮点光工作缓冲（扫描全程使用；对外只暴露量化后的 {@link #lightGrid}）。非仿真状态，不进 hashState。 */
    private float[] lightWork;
    /** 运行时不变式只检查一次（首个 LAMP 源格的量化结果），避免每帧全网格遍历的开销。 */
    private boolean lightInvariantChecked = false;
    /** 不变式是否通过（供门禁/诊断读取）。 */
    public boolean lightInvariantOk = true;

    /**
     * 沿某一轴扫一趟：把光从"上游"格传播到"下游"格（全程浮点，不量化）。
     *
     * <p>批四十 起，增量路径已独立为 {@link #sweepBand(int, boolean)}（只扫进入条带），本方法
     * 只做<b>全量</b>扫描。早期版本带一个 {@code restricted} 参数做"带区受限"，但自 sweepBand
     * 落地后已无任何调用者传 {@code true} ⇒ 该参数与其死分支已删除（行为逐字节不变）。
     *
     * @param axis    0=x, 1=y, 2=z
     * @param forward true=正方向（0→max），false=反方向
     */
    private void sweepAxis(int axis, boolean forward) {
        final int dx = axis == 0 ? (forward ? 1 : -1) : 0;
        final int dy = axis == 1 ? (forward ? 1 : -1) : 0;
        final int dz = axis == 2 ? (forward ? 1 : -1) : 0;

        for (int x = 0; x < SX; x++) {
            if (axis == 0 && ((forward && x == 0) || (!forward && x == SX - 1))) continue;
            for (int y = 0; y < SY; y++) {
                if (axis == 1 && ((forward && y == 0) || (!forward && y == SY - 1))) continue;
                for (int z = 0; z < SZ; z++) {
                    if (axis == 2 && ((forward && z == 0) || (!forward && z == SZ - 1))) continue;

                    // 上游格（自身沿反方向一格）—— 光源在这里
                    int ui = ((x - dx) * SY + (y - dy)) * SZ + (z - dz);
                    float src = lightWork[ui];
                    if (src < LIGHT_CUTOFF) continue;

                    // 光进入本格时被**本格材料**吸收（Terraria 语义：衰减算在"被照亮的格"上）。
                    int m = mat[x][y][z];
                    float reach = src * materials.lightDecay(m);
                    if (reach < LIGHT_CUTOFF) continue;

                    int i = (x * SY + y) * SZ + z;
                    if (reach > lightWork[i]) lightWork[i] = reach;
                }
            }
        }
    }

    /**
     * 把某类方块的每个格写成自发光源格（强度 = radius），供后续 {@link #sweepAxis} 扫描传播。
     *
     * <p>与旧版的关键差异：**不再在这里做 BFS**。旧版是"每源一次 BFS"，成本随源数增长且硬边；
     * 现在这里只"点亮种子"，传播统一交给固定两轮扫描（成本与源数无关）。同格取更强值（LAMP 压 FIRE）。
     */
    private void seedLight(int blockIdx, float v) {
        for (int[] src : typeCells[blockIdx]) {
            int si = (src[0] * SY + src[1]) * SZ + src[2];
            if (lightWork[si] >= v) continue;         // 已被更强源覆盖
            lightWork[si] = v;                        // 源格自发光（LAMP 自身不透明，光从邻格传出）
        }
    }

    // ---------- 无限世界流式（滑动窗口）----------
    /**
     * 玩家当前所在全局块坐标 → 平移已加载窗口使其居中（R 为半宽）。
     *
     * **语义保持同步**（等价 M3① 之前的阻塞行为）：调用返回时窗口已完全就位。
     * 这正是全部门禁/存档/无头步进所依赖的语义，故此处不做分帧。
     * 渲染主循环若需要「不阻塞的分帧流式」，改用 {@link #streamToSliced} + 每帧 {@link #stepShift}。
     */
    public void streamTo(int playerGlobalChunkX, int playerGlobalChunkZ) {
        streamToImmediate(playerGlobalChunkX, playerGlobalChunkZ);
    }

    /** 一次性同步平移（阻塞至完成）。语义与 M3① 之前完全一致，供门禁/无头步进/兼容调用方使用。 */
    public void streamToImmediate(int playerGlobalChunkX, int playerGlobalChunkZ) {
        if (shifting) {                          // 有未完成的分帧平移：先把它跑完，再处理新目标
            while (shifting) stepShift(Integer.MAX_VALUE);
        }
        int desiredOx = playerGlobalChunkX - R;
        int desiredOz = playerGlobalChunkZ - R;
        if (desiredOx == winCX0 && desiredOz == winCZ0) return;
        beginShift(desiredOx - winCX0, desiredOz - winCZ0);
        while (shifting) stepShift(Integer.MAX_VALUE);
    }

    /**
     * 分帧平移：只登记目标，实际重生成由渲染层每帧 {@link #stepShift} 按预算推进（M3①，零卡顿）。
     * 与 {@link #streamTo} 结果逐字节一致，仅「何时算」不同。
     */
    public void streamToSliced(int playerGlobalChunkX, int playerGlobalChunkZ) {
        if (shifting) return;                    // 上一次分帧平移未完成：本帧忽略新目标（窗口单调推进）
        int desiredOx = playerGlobalChunkX - R;
        int desiredOz = playerGlobalChunkZ - R;
        if (desiredOx == winCX0 && desiredOz == winCZ0) return;
        beginShift(desiredOx - winCX0, desiredOz - winCZ0);
    }

    /** 窗口平移 dcx/dcz 个块：先快照离开块、再重生成全窗口（基岩+编辑覆盖）、同步玩家本地坐标。 */
    private void shiftWindow(int dcx, int dcz) {
        beginShift(dcx, dcz);
        // 一次性模式（门禁/无渲染步进）：单次调用内跑完全部分片，行为与旧的整窗平移逐字节一致。
        while (shifting) stepShift(Integer.MAX_VALUE);
    }

    // ---------- M3① 跨图流式分帧：把整窗重生成切成多帧预算，消除跨窗停帧 ----------
    /**
     * 分帧平移的状态机。旧实现把「快照离开块 + 重生成 CX*CZ 块 + 全量重建索引」一次做完
     * （160 窗口实测 346ms/次 → 每秒多次平移即秒级卡顿）。这里拆成同结果的四阶段：
     *   SNAPSHOT(1) → 遍历离开块并存快照（与旧实现逐块等价）
     *   GEN(2)      → 按**与旧实现完全相同的 (cx,cz) 双层循环序**逐块 generateChunk + applyOverride
     *   COMMIT(3)   → 换窗原点、平移玩家本地坐标、标脏、**启动**索引重建
     *   INDEX(4)    → 分帧推进索引重建（M3① 三次优化：把 59ms 的 rebuildIndex 切成条带）
     * 关键保真点：分片只改变「何时算」，不改变「算什么、按什么次序算什么」——
     * generateChunk 每次都用全局块坐标派生 treeRng，与调用时机无关；
     * 且 GEN 的分片游标严格复现旧实现的 (cx 外层, cz 内层) 顺序，故 treeRng 消费序不变 →
     * 生成结果与旧实现**逐字节一致**（四基线指纹不变）。
     * 一致性：分片期间窗口原点尚未提交，mat 仍是旧窗内容（逐步被新内容覆盖），
     * 玩家坐标未平移 → 语义上等价于「旧窗 + 部分新块」的保守中间态，不会越界；
     * 未完成前 streamTo 不再发起新平移（由 inProgress 挡住）。
     *
     * ⚠️ INDEX 阶段（4）索引不完整 —— 渲染层必须在 {@link #isShifting()} 期间暂停 tick，
     *    否则系统会读到残缺的 cellsOfType/nonAirCells 视图而使演化失真。
     *    门禁/无头步进走 {@link #streamTo}（budget=Integer.MAX_VALUE）→ 单次调用内跑完全部阶段，
     *    返回时索引已完整，语义与旧的整窗同步平移逐字节一致。
     */
    private boolean shifting = false;
    private int shiftDCX, shiftDCZ, shiftNOx, shiftNOz;
    private int shiftCX, shiftCZ;          // GEN 阶段游标（复现 (cx,cz) 双层序）
    private int shiftPhase = 0;            // 0=空闲 1=SNAPSHOT 2=RELOCATE+GEN 3=COMMIT 4=INDEX
    /** P1：本帧是否已做过重叠区搬迁（阶段 2a 只做一次）。 */
    private boolean shiftRelocated = false;
    /**
     * 生成一块时可能写到**本块之外**的最大半径。
     *
     * <p>来源：{@code generateChunk} 放树时叶片取 ±2 格，而 {@code inBounds} 允许它**跨块**写
     * ⇒ 新生成的边界块会改写紧邻重叠列的方块。
     * <p><b>用途</b>：增量索引（{@link #relocateIndex}）把新条带外扩这么多列纳入"重扫区"，
     * 否则光晕列的索引条目会停留在旧内容（实测：不外扩时六个平移方向全部索引/真值不一致）。
     * <p>⚠️ <b>它与 generateChunk 里树冠的 ±2 是耦合的：改那边必须改这里。</b>
     * （这也是 {@code StreamChunkTest} 的 `incremental` 性质里"光晕例外"的那 2 列。）
     */
    private static final int GEN_HALO = 2;

    /** 每帧生成块数预算（M3① 分帧粒度）：越小越平滑、跨窗完成越慢。8 块 ≈ 每帧数 ms。 */
    public static final int SHIFT_CHUNKS_PER_STEP = 8;

    /** 是否正处在分帧平移中间态（渲染层可据此显示加载提示 / 抑制瞬移）。 */
    public boolean isShifting() { return shifting; }
    //
    // 注（第三十七批）：原 `isIndexIncomplete()`（判 `shiftPhase == 4`）已**删除** ——
    //   ① 它全项目**零调用**（"平移期暂停 tick"实际由 `isShifting()` 保证，非它）；
    //   ② 索引改增量后已无 INDEX 阶段，它恒为 false ⇒ 留着就是死代码 + 误导。
    //   契约仍成立：`isShifting()` 期间不得 tick（搬迁/生成进行中，索引尚未更新完）。

    /** 当前分帧进度 0..1（纯观测用，不进指纹）。 */
    public float shiftProgress() {
        if (!shifting) return 1f;
        int total = Math.max(1, CX * CZ);
        return Math.min(1f, (shiftCX * CZ + shiftCZ) / (float) total);
    }

    /** 启动一次分帧平移（等价 shiftWindow 的阶段 0：只登记目标，不做实际工作）。 */
    private void beginShift(int dcx, int dcz) {
        if (shifting) return;                    // 上一次未完成：忽略（保持窗口推进单调）
        shiftDCX = dcx; shiftDCZ = dcz;
        shiftNOx = winCX0 + dcx; shiftNOz = winCZ0 + dcz;
        shiftCX = 0; shiftCZ = 0; shiftPhase = 1;
        shiftRelocated = false;
        shifting = true;
    }

    /**
     * 推进一步分帧平移；{@code budgetChunks} 为本步允许生成的块数上限（SNAPSHOT/COMMIT 步不计入）。
     * 返回是否**已完成**本次平移。Game 每帧调用一次（budgetChunks=SHIFT_CHUNKS_PER_STEP）。
     */
    public boolean stepShift(int budgetChunks) {
        if (!shifting) return true;
        long t0 = java.lang.System.nanoTime();
        if (shiftPhase == 1) {
            // ---- 阶段 1：快照离开新窗口范围且曾被编辑的块（与旧 shiftWindow 第 1 步同序）----
            for (int cx = 0; cx < CX; cx++)
                for (int cz = 0; cz < CZ; cz++) {
                    int gcx = winCX0 + cx, gcz = winCZ0 + cz;
                    boolean leaving = gcx < shiftNOx || gcx >= shiftNOx + CX
                                   || gcz < shiftNOz || gcz >= shiftNOz + CZ;
                    long gk = chunkKeyGlobal(gcx, gcz);
                    if (leaving && editedChunks.contains(gk)) {
                        snapshotChunk(cx, cz, gk);
                        editedChunks.remove(gk);
                    }
                }
            shiftPhase = 2;
        }
        if (shiftPhase == 2) {
            if (!shiftRelocated) {
                // ---- 阶段 2a（P1，第三十六批）：把"仍在窗口内"的块**就地搬迁**到新本地坐标 ----
                // 旧实现此阶段把 CX×CZ 块**全部**重生成；现在改成「搬迁重叠区 + 只生成新进入的条带」。
                // 收益：走 1 块 100 块 → ~10 块（实测 99.7ms → 见 WindowStreamProbe），
                // 且**窗口内既有内容（含系统演化出的灰/植被/水位）得以保留**（旧实现每次平移都抹掉）。
                // ⚠️ 搬迁后 `mat` 与空间索引不再自洽（索引仍按旧本地坐标登记）⇒ 与旧实现同一契约：
                //   **isShifting() 期间不得 tick**，索引统一在 COMMIT 由 rebuildIndexBegin 重建。
                relocateWindow(shiftDCX, shiftDCZ);
                shiftRelocated = true;
            }
            // ---- 阶段 2b：只生成**新进入**的块（按 (cx 外层, cz 内层) 序分片，保持确定性）----
            int remaining = budgetChunks;
            while (remaining > 0 && shiftCX < CX) {
                int gcx = shiftNOx + shiftCX, gcz = shiftNOz + shiftCZ;
                boolean inOld = gcx >= winCX0 && gcx < winCX0 + CX
                             && gcz >= winCZ0 && gcz < winCZ0 + CZ;
                if (!inOld) {                                        // 窗口内既有的块：搬迁已就位，跳过重生成
                    generateChunk(shiftCX, shiftCZ, shiftNOx, shiftNOz);   // ← 必须传目标原点（winCX0 尚未提交）
                    Override ov = chunkEdits.get(chunkKeyGlobal(gcx, gcz));
                    if (ov != null) applyOverride(shiftCX, shiftCZ, ov);
                }
                remaining--;
                if (++shiftCZ >= CZ) { shiftCZ = 0; shiftCX++; }
            }
            // 注（第三十八批）：此处原为 `markAllChunksDirty()` —— 它在**每一帧** GEN 期间重复标全窗。
            //   标脏改到 COMMIT 一次做完（那时窗口原点已提交，语义才完整），且只标真正需要重建的块。
            if (shiftCX < CX) { profShiftNs += java.lang.System.nanoTime() - t0; return false; }   // 本帧预算用尽，下一帧继续
            shiftPhase = 3;
        }
        if (shiftPhase == 3) {
            // ---- 阶段 3：提交（换窗原点 + 平移玩家本地坐标 + **增量**更新空间索引）----
            winCX0 = shiftNOx; winCZ0 = shiftNOz;
            invalidateBaseline();                     // N4：窗口原点已变 → 基线失效，下次快照重算
            if (player != null) { player.x -= shiftDCX * CHUNK; player.z -= shiftDCZ * CHUNK; }
            // 第三十八批：**只标真正需要重建的块**（新条带 + 两条边缘线），并重映射已有脏键。
            //   旧实现在这里 markAllChunksDirty()（CX*CZ=100 块）⇒ 每次平移后一整窗网格重建 +
            //   一整窗上传，而留在窗内的块**内容一个字节都没变**，网格完全可以复用（渲染层给它们
            //   记一个整数偏移即可，见 Chunk.meshOffX）。块对象数组的旋转在渲染层同步做
            //   （Chunk.relocateArray），两处用的是同一个位移。
            long tc = java.lang.System.nanoTime();
            retargetDirtyOnShift(shiftDCX, shiftDCZ);
            // 第三十九批：光场已随 mat 旋转（见 relocateWindow）→ 标记增量重算新条带。
            //   零重叠平移 rotateLight 返回 false ⇒ lightRotated=false ⇒ 走全量重算（正确）。
            lightDirtyShift = lightRotated;
            // P1（第三十七批）：索引从"全窗重建（分帧）"改为**增量**（基线平移 + 只扫新边界，见
            // relocateIndex 的说明）⇒ 一次做完、代价 O(新边界) ⇒ **阶段 4（INDEX 分帧）已删除**。
            // 后果：`shifting` 结束后索引即完整（不再有"索引不完整中间态"）。
            relocateIndex(shiftDCX * CHUNK, shiftDCZ * CHUNK);
            profCommitNs += java.lang.System.nanoTime() - tc;
            shiftPhase = 0; shifting = false;
            profShiftNs += java.lang.System.nanoTime() - t0;
            return true;
        }
        profShiftNs += java.lang.System.nanoTime() - t0;
        return false;
    }

    private long chunkKeyGlobal(int gcx, int gcz) {
        return ((long) gcx << 32) | (gcz & 0xFFFFFFFFL);
    }

    // ---------- P1（第三十六批）：增量平移 —— 搬迁重叠区 + 只生成新进入的块 ----------
    /**
     * 把「仍在窗口内」的块**就地搬迁**到它们在新窗口中的本地位置（新本地 = 旧本地 − (sx,sz)）。
     *
     * <p><b>为什么需要它</b>：{@code mat} 按**窗口本地**坐标索引，窗口一平移，所有块的本地坐标都变。
     * 旧实现因此只能「整窗重生成」（+ 重放 {@code chunkEdits}），代价是走 1 块要重生成 CX×CZ=100 块
     * （实测 99.7ms/次，见 {@code tools/WindowStreamProbe}）；**并且**留在窗口内的块会被重生成成
     * 原始地形 ⇒ 它们身上由系统演化出的内容（灰/植被/水位）**每次平移都被抹掉**（这正是
     * {@code chunkEdits} 里存着 2.17MB/块却几乎全是"基线附近"内容的原因）。
     *
     * <p>搬迁之后，只有**新进入**的条带需要 {@code generateChunk}（走 1 块 ⇒ 10 块）⇒ 快 ~10×，
     * 且窗口内既有内容（含系统演化）**得以保留**。
     *
     * <p><b>正确性要点</b>：
     * <ol>
     *   <li>顺序必须**先 Z 后 X**：Z 在行内做（{@code java.lang.System.arraycopy} 对重叠语义安全），
     *       X 在行间做（每行是独立数组，但源/目标行区间重叠 ⇒ 必须按"不覆盖未读源"的方向迭代，见下）；</li>
     *   <li>完全不重叠（{@code |sx|>=SX 或 |sz|>=SZ}）时**直接返回** —— 那种情况下所有块都是新块，
     *       由 GEN 阶段全部重生成，搬迁无意义；</li>
     *   <li>稀疏状态层（{@code meta/chestStore/furnaceStore/signText/blockState/builtCells}）的键是
     *       {@link #cellKey}（**本地**坐标）⇒ 必须整体重映射，否则箱内容/门开关会留在旧格上；</li>
     *   <li>搬迁直写 {@code mat}（不经 {@code setBlock}）⇒ 不触发 {@code indexStale}，
     *       也**不得**在 {@code isShifting()} 期间 tick（索引与 mat 在搬迁后不再自洽，
     *       到 COMMIT 的 {@code rebuildIndexBegin} 才重建）。</li>
     * </ol>
     */
    private void relocateWindow(int dcx, int dcz) {
        long t0 = java.lang.System.nanoTime();
        int sx = dcx * CHUNK, sz = dcz * CHUNK;
        if (sx == 0 && sz == 0) return;
        if (Math.abs(sx) >= SX || Math.abs(sz) >= SZ) return;   // 零重叠 ⇒ 全窗皆新块，无需搬迁
        // ⚠️⚠️ **搬迁之前必须先把"待应用的索引补丁"落进索引**（`remapLocalState` 末尾会清空补丁缓冲）：
        //  补丁里带的是**旧**本地坐标；一旦搬迁，那些坐标就不再指向原格 ⇒ 之后无法补。
        //  （实测症状：`setBlock` 放的标记块，索引里 count=0 而 mat 里有 1 个 ⇒ 六个平移方向全红。
        //    注意"全量重建"路径不会踩到它 —— 它从 mat 重扫，丢弃补丁无害；增量路径才必须补。）
        ensureIndex();
        // 第三十九批：检测本轴"离开条带"是否有光源退出（退出 ⇒ interior 丢光，computeLight 须走全量）。
        //   在旋转 mat 之前用旧本地坐标判断（ensureIndex 之后，typeCells 已最新）：
        //   sx<0 时窗口左移、远边退出；sx>0 时近边退出。本轴未平移则无从退出 ⇒ 显式清零（防上一轮残留）。
        if (sx != 0) lightLeaveX = sourceInStrip(sx < 0 ? SX - CHUNK : 0, sx < 0 ? SX - 1 : CHUNK - 1, true);
        else         lightLeaveX = false;
        if (sz != 0) lightLeaveZ = sourceInStrip(sz < 0 ? SZ - CHUNK : 0, sz < 0 ? SZ - 1 : CHUNK - 1, false);
        else         lightLeaveZ = false;
        // ---- ① Z 方向：行内 memmove ----
        if (sz != 0) {
            int src = sz > 0 ? sz : 0, dst = sz > 0 ? 0 : -sz, len = SZ - Math.abs(sz);
            for (int x = 0; x < SX; x++) {
                for (int y = 0; y < SY; y++) {
                    java.lang.System.arraycopy(mat[x][y], src, mat[x][y], dst, len);
                    java.lang.System.arraycopy(mass[x][y], src, mass[x][y], dst, len);
                }
                java.lang.System.arraycopy(surfaceY[x], src, surfaceY[x], dst, len);
                java.lang.System.arraycopy(surfaceTopY[x], src, surfaceTopY[x], dst, len);
            }
        }
        // ---- ② X 方向：整行搬运（方向敏感）----
        if (sx != 0) {
            if (sx > 0) {
                // dst=x, src=x+sx > x ⇒ 升序安全（写入的 dst 只会覆盖"已经处理过"的源）
                for (int x = 0; x + sx < SX; x++) copyRow(x + sx, x);
            } else {
                // dst=x, src=x+sx < x ⇒ 必须降序
                for (int x = SX - 1; x + sx >= 0; x--) copyRow(x + sx, x);
            }
        }
        // ---- ③ 稀疏状态层重映射 + 光场同步旋转 ----
        // ⚠️ 第三十九批：光场**随 mat 同步旋转**（光是 mat 的纯函数 ⇒ interior 旋转后逐字节正确，
        //   只有新条带需重算）。旧实现注释曾断言"光不随平移旋转"——那是因为当时平移后**整窗重算**光场，
        //   旋转纯属会被覆盖的死工作；现在平移改为**增量重算**新条带（见 computeLight 分支 +
        //   retargetDirtyOnShift 的 markLightBand），旋转 interior 才有意义。零重叠时 rotateLight 直接返回，
        //   由全量重算接管（lightRotated=false ⇒ COMMIT 不置 lightDirtyShift）。
        rotateLight(sx, sz);
        lightShiftSX = sx; lightShiftSZ = sz;
        // 搬迁后待应用的索引补丁（indexDelta）里带的是**旧**本地坐标 ⇒ 作废（COMMIT 会全量重建索引）。
        remapLocalState(sx, sz);
        profRelocNs += java.lang.System.nanoTime() - t0;
    }

    // ---------- 第三十九批：增量光照 —— 旋转光场 + 只重算新条带 ----------
    /**
     * 把 {@link #lightGrid}/{@link #lightWork} 随窗口同步旋转（与 mat 同样的 Z/X 变换）。
     * 光是 mat 的纯函数 ⇒ 旋转后 interior 光值正确，只有<b>新进入的条带</b>需要重算
     * （其邻域从"旧条带内容"变成"新条带内容"）。零重叠（平移距离 ≥ 窗宽）时跳过：
     * 那种情况整窗都是新块，旋转无意义、且会被覆盖，由全量重算接管。
     */
    private void rotateLight(int sx, int sz) {
        lightRotated = false;
        if (lightGrid == null || lightWork == null) return;     // 尚未算过 ⇒ 增量无意义，全量接管
        int ax = Math.abs(sx), az = Math.abs(sz);
        if (ax >= SX || az >= SZ) return;                       // 零重叠 ⇒ 整窗新块
        // ---- ① Z 方向（与 mat 同）----
        if (sz != 0) {
            int src = sz > 0 ? sz : 0, dst = sz > 0 ? 0 : -sz, len = SZ - Math.abs(sz);
            for (int x = 0; x < SX; x++)
                for (int y = 0; y < SY; y++) {
                    java.lang.System.arraycopy(lightGrid, (x * SY + y) * SZ + src, lightGrid, (x * SY + y) * SZ + dst, len);
                    java.lang.System.arraycopy(lightWork, (x * SY + y) * SZ + src, lightWork, (x * SY + y) * SZ + dst, len);
                }
        }
        // ---- ② X 方向（与 mat 同，方向敏感）----
        if (sx != 0) {
            if (sx > 0) for (int x = 0; x + sx < SX; x++) copyLightRow(x + sx, x);
            else        for (int x = SX - 1; x + sx >= 0; x--) copyLightRow(x + sx, x);
        }
        lightRotated = true;
    }

    /** 把一整列 x=srcX 的光场（lightGrid + lightWork）搬到 x=dstX（与 {@link #copyRow} 同语义）。 */
    private void copyLightRow(int srcX, int dstX) {
        for (int y = 0; y < SY; y++) {
            int so = (srcX * SY + y) * SZ, ddo = (dstX * SY + y) * SZ;
            java.lang.System.arraycopy(lightGrid, so, lightGrid, ddo, SZ);
            java.lang.System.arraycopy(lightWork, so, lightWork, ddo, SZ);
        }
    }

    /** 该块是否在增量重算的<b>进入条带</b>内（覆盖所有 y）。
     * <p>光是 mat 的纯函数 ⇒ 旋转后 interior 光值正确，只有<b>新进入的条带</b>（邻域从旧内容变新内容）
     * 需重算；带宽 = {@link #LIGHT_BAND_CHUNKS}*CHUNK 块（≈42 格光程，覆盖全部可能渗透）。
     * <p>⚠️ 若本平移有光源退出窗口（{@link #lightLeaveX}/{@link #lightLeaveZ}），
     * {@link #computeLight} 会直接走全量重算、<b>根本不调用本函数</b> —— 退出光源污染的是整窗 interior
     * （全局不动点），带区隔离无效。故这里只需覆盖进入条带，离开条带由全量重算统一处理。
     */
     private boolean inR(int x, int z) {
        final int REACH = LIGHT_BAND_CHUNKS * CHUNK;
        if (lightShiftSX != 0) {
            boolean enter = (lightShiftSX < 0) ? (x <= CHUNK + REACH) : (x >= SX - CHUNK - REACH);
            if (enter) return true;
        }
        if (lightShiftSZ != 0) {
            boolean enter = (lightShiftSZ < 0) ? (z <= CHUNK + REACH) : (z >= SZ - CHUNK - REACH);
            if (enter) return true;
        }
        return false;
    }

    /** 五种光源于 [lo,hi]（useX 取 x 坐标，否则取 z）离开条带内是否存在。 */
    private boolean sourceInStrip(int lo, int hi, boolean useX) {
        int[] srcs = { Blocks.LAMP.index, Blocks.FIRE.index,
                       Blocks.CRYSTAL_CLUSTER.index, Blocks.CRYSTAL.index, Blocks.CAMPFIRE.index };
        for (int idx : srcs) {
            for (int[] c : typeCells[idx]) {
                int coord = useX ? c[0] : c[2];
                if (coord >= lo && coord <= hi) return true;
            }
        }
        return false;
    }

    /**
     * 增量重算新条带光照：interior 光（已由 {@link #rotateLight} 旋转到位）保持不动，
     * 只对带区内格清零、重播光源、做<b>受限</b>扫描（只写带区格，读任意邻格含 interior 边界 → 正确），
     * 末了只量化带区。要求本窗口平移时 {@link #rotateLight} 已执行过（否则结果为旋转前的旧光场）。
     */
    private void recomputeLightBand() {
        // 清零带区内光工作缓冲与光场（interior 保留旋转后的正确值）
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) {
                    if (!inR(x, z)) continue;
                    int i = (x * SY + y) * SZ + z;
                    lightWork[i] = 0f; lightGrid[i] = 0;
                }
        // 只在带区内重播光源（interior 光源的影响已由旋转后的边界格带入，无需重播）
        seedLightR(Blocks.LAMP.index, 1.0f);
        seedLightR(Blocks.FIRE.index, FIRE_LIGHT_FRACTION);
        seedLightR(Blocks.CRYSTAL_CLUSTER.index, 0.55f);
        seedLightR(Blocks.CRYSTAL.index, 0.35f);
        seedLightR(Blocks.CAMPFIRE.index, FIRE_LIGHT_FRACTION);
        // 带区扫描：真正只遍历带区格（范围与 inR 同几何），读任意邻格（含 interior 旋转后的正确值）
        // ⇒ 带区结果与全量扫描逐字节一致，但遍历量从 O(全窗) 降到 O(带区)（第四十批修复伪增量）。
        for (int pass = 0; pass < LIGHT_PASSES; pass++) {
            sweepBand(0, true);  sweepBand(0, false);
            sweepBand(1, true);  sweepBand(1, false);
            sweepBand(2, true);  sweepBand(2, false);
        }
        // 量化只写带区
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) {
                    if (!inR(x, z)) continue;
                    int i = (x * SY + y) * SZ + z;
                    float v = lightWork[i];
                    if (v <= 0f) continue;
                    int q = Math.round(v * LIGHT_R);
                    if (q > LIGHT_R) q = LIGHT_R; else if (q < 0) q = 0;
                    lightGrid[i] = (byte) q;
                }
    }

    // ---------- 第四十批：真正带区受限扫描（修复伪增量：旧 sweepAxis restricted 仍遍历全窗）----------
    /**
     * 沿某轴扫一趟，但只遍历<b>带区格</b>（范围与 {@link #inR} 同几何），不遍历 interior。
     * 传播体读任意邻格（含 interior 旋转后的正确值）→ 带区结果与全量扫描逐字节一致。
     * 遍历序与 {@link #sweepAxis} 完全一致（x/y/z 均升序），保证与全量扫描在带区格上收敛到同一值。
     */
    private void sweepBand(int axis, boolean forward) {
        final int dx = axis == 0 ? (forward ? 1 : -1) : 0;
        final int dy = axis == 1 ? (forward ? 1 : -1) : 0;
        final int dz = axis == 2 ? (forward ? 1 : -1) : 0;
        boolean hasX = lightShiftSX != 0, hasZ = lightShiftSZ != 0;
        int xLo = 0, xHi = SX - 1, zLo = 0, zHi = SZ - 1;
        final int REACH = LIGHT_BAND_CHUNKS * CHUNK;
        if (hasX) {
            if (lightShiftSX < 0) { xLo = 0; xHi = CHUNK + REACH; }
            else { xLo = SX - CHUNK - REACH; xHi = SX - 1; }
        }
        if (hasZ) {
            if (lightShiftSZ < 0) { zLo = 0; zHi = CHUNK + REACH; }
            else { zLo = SZ - CHUNK - REACH; zHi = SZ - 1; }
        }
        if (axis == 0) {
            // x 外层：x∈xBand 全 (y,z)；否则仅 z∈zBand（若 hasZ）
            for (int x = 0; x < SX; x++) {
                if (hasX && x >= xLo && x <= xHi)
                    for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) propagate(x, y, z, dx, dy, dz);
                else if (hasZ)
                    for (int y = 0; y < SY; y++) for (int z = zLo; z <= zHi; z++) propagate(x, y, z, dx, dy, dz);
            }
        } else if (axis == 1) {
            // (x,y) 外层、z 内层连续：x∈xBand 全 z；否则仅 z∈zBand（若 hasZ）
            for (int x = 0; x < SX; x++) {
                boolean xIn = hasX && x >= xLo && x <= xHi;
                for (int y = 0; y < SY; y++) {
                    if (xIn) for (int z = 0; z < SZ; z++) propagate(x, y, z, dx, dy, dz);
                    else if (hasZ) for (int z = zLo; z <= zHi; z++) propagate(x, y, z, dx, dy, dz);
                }
            }
        } else { // axis == 2：同 (x,y,z) 结构，z 内层连续 → cache 友好（修复 z 外层跨步跳跃）
            for (int x = 0; x < SX; x++) {
                boolean xIn = hasX && x >= xLo && x <= xHi;
                for (int y = 0; y < SY; y++) {
                    if (xIn) for (int z = 0; z < SZ; z++) propagate(x, y, z, dx, dy, dz);
                    else if (hasZ) for (int z = zLo; z <= zHi; z++) propagate(x, y, z, dx, dy, dz);
                }
            }
        }
    }

    /** 单格光传播体（与 {@link #sweepAxis} 的循环体同语义）：读上游邻格衰减后取 max。 */
    private void propagate(int x, int y, int z, int dx, int dy, int dz) {
        int ux = x - dx, uy = y - dy, uz = z - dz;
        if (ux < 0 || ux >= SX || uy < 0 || uy >= SY || uz < 0 || uz >= SZ) return;  // 轴边界：无上游光
        int ui = (ux * SY + uy) * SZ + uz;
        float src = lightWork[ui];
        if (src < LIGHT_CUTOFF) return;
        int m = mat[x][y][z];
        float reach = src * materials.lightDecay(m);
        if (reach < LIGHT_CUTOFF) return;
        int i = (x * SY + y) * SZ + z;
        if (reach > lightWork[i]) lightWork[i] = reach;
    }

    /** 只在带区重播光源（见 {@link #seedLight}）。 */
    private void seedLightR(int blockIdx, float v) {
        for (int[] src : typeCells[blockIdx]) {
            if (!inR(src[0], src[2])) continue;
            int si = (src[0] * SY + src[1]) * SZ + src[2];
            if (lightWork[si] >= v) continue;
            lightWork[si] = v;
        }
    }

    /** 把整行 x=srcX 的内容（含 mass / surfaceY / surfaceTopY 的对应行）搬到 x=dstX。 */
    private void copyRow(int srcX, int dstX) {
        for (int y = 0; y < SY; y++) {
            java.lang.System.arraycopy(mat[srcX][y], 0, mat[dstX][y], 0, SZ);
            java.lang.System.arraycopy(mass[srcX][y], 0, mass[dstX][y], 0, SZ);
        }
        java.lang.System.arraycopy(surfaceY[srcX], 0, surfaceY[dstX], 0, SZ);
        java.lang.System.arraycopy(surfaceTopY[srcX], 0, surfaceTopY[dstX], 0, SZ);
    }

    /** 把全部"按本地 {@link #cellKey} 索引"的稀疏状态层重映射到新本地坐标（越界的丢弃 = 已卸载）。 */
    private void remapLocalState(int sx, int sz) {
        remapLocalKeys(meta, sx, sz);
        remapLocalKeys(chestStore, sx, sz);
        remapLocalKeys(furnaceStore, sx, sz);
        remapLocalKeys(signText, sx, sz);
        remapLocalKeys(blockState, sx, sz);
        remapLocalKeys(builtCells, sx, sz);
        indexDeltaLen = 0;   // 旧坐标的待应用补丁作废（见方法注释③）
    }

    /** 按 (sx,sz) 重映射一个 cellKey 键的稀疏层；**保持插入序**（LinkedHashMap：StateCodec 逐字节持久化依赖它）。 */
    private <V> void remapLocalKeys(java.util.Map<Long, V> m, int sx, int sz) {
        if (m.isEmpty()) return;
        java.util.LinkedHashMap<Long, V> out = new java.util.LinkedHashMap<Long, V>();
        for (java.util.Map.Entry<Long, V> e : m.entrySet()) {
            long k = e.getKey();
            int x = (int) ((k >>> 20) & 0x3FFL), y = (int) ((k >>> 10) & 0x3FFL), z = (int) (k & 0x3FFL);
            int nx = x - sx, nz = z - sz;
            if (nx < 0 || nz < 0 || nx >= SX || nz >= SZ) continue;   // 移出窗口 ⇒ 丢弃
            out.put(cellKey(nx, y, nz), e.getValue());
        }
        m.clear();
        m.putAll(out);
    }

    /** 同 {@link #remapLocalKeys}，但作用于 Set（surfaceCells / waterSurfaceCells）；重扫区内的成员交给重扫重新登记。 */
    private void remapLocalKeySet(java.util.Set<Long> s, int sx, int sz,
                                  int rx0, int rx1, int rz0, int rz1) {
        if (s.isEmpty()) return;
        java.util.HashSet<Long> out = new java.util.HashSet<Long>(s.size() * 2);
        for (Long k : s) {
            int x = (int) ((k >>> 20) & 0x3FFL), y = (int) ((k >>> 10) & 0x3FFL), z = (int) (k & 0x3FFL);
            int nx = x - sx, nz = z - sz;
            if (nx < 0 || nz < 0 || nx >= SX || nz >= SZ) continue;
            if ((nx >= rx0 && nx < rx1) || (nz >= rz0 && nz < rz1)) continue;
            out.add(cellKey(nx, y, nz));
        }
        s.clear();
        s.addAll(out);
    }

    // ---------- P1（第三十七批）：**增量索引** —— 基线平移 + 只扫新边界 ----------
    //
    // 旧路径（第三十六批及之前）在 COMMIT 调 rebuildIndexBegin() + 分帧 rebuildIndexStep()：
    // **清空**全部索引再**全窗重扫** 2.87M 格 —— 这是 P1 落地后平移成本的大头
    // （WindowStreamProbe：平移 24.96ms，其中索引占相当一块；且它必须分帧，让 isShifting 期变长）。
    //
    // 增量做法（三条性质使它能成立）：
    //   ① 索引基线的键是**本地**坐标、且按 (x,y,z) **升序**平铺 ⇒ "减同一个常量"**保序**：
    //      平移即可原地过滤完成，O(n) 且不需要重排/临时数组（见 CellSet.relocateInPlace）；
    //   ② 只有**新进入**的 L 形条带（x 板 ∪ z 板）需要重新登记 —— 其余格的位置与类型都没变；
    //   ③ `surfaceY`/`surfaceCells`/`waterSurfaceCells` 同样平移即可（它们只依赖"本列"信息）。
    // ⇒ 索引更新从 O(全窗) 降为 O(新边界)，且**一次做完**（不必再分帧）⇒ 删掉 INDEX 阶段。

    /** 增量索引用的暂存集合（按块类型 + nonAir；懒分配，避免与 typeCells 的初始化顺序耦合）。 */
    private CellSet[] idxScratch = null;
    private CellSet nonAirScratch = null;

    private void ensureIdxScratch() {
        if (idxScratch == null) {
            idxScratch = new CellSet[Blocks.count()];
            for (int i = 0; i < idxScratch.length; i++) idxScratch[i] = new CellSet();
            nonAirScratch = new CellSet();
        }
    }

    /**
     * 把空间索引增量更新到"窗口已平移 (sx,sz)、且新边界已生成"的状态（替代全窗重建）。
     * 必须在 GEN 阶段**之后**调用（新边界的方块必须已写完）。
     */
    private void relocateIndex(int sx, int sz) {
        // ⚠️⚠️ **必须先把待应用的索引补丁落到索引里**（ensureIndex），再搬迁。
        //  全量重建路径可以直接 `indexDeltaLen = 0` 丢弃补丁 —— 因为它从 `mat` 重新扫出全部真值；
        //  而增量路径是"旧索引 + 搬迁 + 重扫边界"，**丢弃补丁 = 那批改动永久消失**
        //  （实测症状：测试用 setBlock 放的 LAMP，索引里 count=0 而 mat 里有 1 个）。
        ensureIndex();
        // ⚠️ 零重叠（窗口一次跳得比一屏还远，如初次定位/长距离传送）：**没有"重叠区"可搬迁**
        // （relocateWindow 也已直接返回）⇒ 新区域 = **整个窗口**，L 形推导不再成立（会算出负的 x 范围）。
        // 这种情况退回全量重建（正确性优先；正常行走是 ±1 块，永远走不到这里）。
        if (Math.abs(sx) >= SX || Math.abs(sz) >= SZ) {
            rebuildIndexBegin();
            rebuildIndexStep(Integer.MAX_VALUE);
            return;
        }
        // ---- 需要**重扫**的区域（本地坐标，含生成光晕 GEN_HALO）----
        // 新条带本体：x 板 [xN0,xN1) 与 z 板 [zN0,zN1)（二者之一可能为空）
        int xN0 = sx > 0 ? SX - sx : 0, xN1 = sx > 0 ? SX : (sx < 0 ? -sx : 0);
        int zN0 = sz > 0 ? SZ - sz : 0, zN1 = sz > 0 ? SZ : (sz < 0 ? -sz : 0);
        // ⚠️ 必须外扩 GEN_HALO：generateChunk 放树时叶片 ±2 格**跨块**写 ⇒ 新边界块会改写紧邻的
        //    重叠列；若不把这一圈纳入重扫，那些格的索引条目会停留在旧内容（实测：六个方向全红）。
        int rx0 = (xN1 > xN0) ? Math.max(0, xN0 - GEN_HALO) : 0;
        int rx1 = (xN1 > xN0) ? Math.min(SX, xN1 + GEN_HALO) : 0;
        int rz0 = (zN1 > zN0) ? Math.max(0, zN0 - GEN_HALO) : 0;
        int rz1 = (zN1 > zN0) ? Math.min(SZ, zN1 + GEN_HALO) : 0;
        // ---- ① 基线平移 + 丢弃越界与重扫区 ----
        for (CellSet t : typeCells) t.relocateInPlace(sx, sz, SX, SZ, rx0, rx1, rz0, rz1);
        nonAirCells.relocateInPlace(sx, sz, SX, SZ, rx0, rx1, rz0, rz1);
        // ---- ② 地表/水面索引：键是本地 cellKey ⇒ 重映射（同样丢弃重扫区）----
        remapLocalKeySet(surfaceCells, sx, sz, rx0, rx1, rz0, rz1);
        remapLocalKeySet(waterSurfaceCells, sx, sz, rx0, rx1, rz0, rz1);
        // ---- ③ 重扫：两个板块必须**按 x 升序**依次扫（收集顺序须整体按 (x,y,z) 升序，见 mergeSorted）----
        //   · sx > 0：x 板在高 x 侧（后缀）⇒ 先扫低 x 的 z 板
        //   · sx < 0：x 板在低 x 侧（前缀）⇒ 先扫 x 板
        //   · sx == 0：只有 z 板
        ensureIdxScratch();
        for (CellSet c : idxScratch) c.clearCells();
        nonAirScratch.clearCells();
        if (rx1 > rx0) {
            if (sx < 0) {
                scanNewCols(rx0, rx1, 0, SZ);
                if (rz1 > rz0) scanNewCols(rx1, SX, rz0, rz1);
            } else {
                if (rz1 > rz0) scanNewCols(0, rx0, rz0, rz1);
                scanNewCols(rx0, rx1, 0, SZ);
            }
        } else if (rz1 > rz0) {
            scanNewCols(0, SX, rz0, rz1);
        }
        // ---- ④ 并入（原地后向归并；两者不相交：重扫区已从基线里丢弃）----
        for (int t = 0; t < typeCells.length; t++) typeCells[t].mergeSorted(idxScratch[t]);
        nonAirCells.mergeSorted(nonAirScratch);
        indexStale = false;
        indexDeltaLen = 0;
        nonAirStale = false;
    }

    /** 扫 [x0,x1) × [z0,z1) 的列：按 (x,y,z) 升序收集到暂存集合，并回填 surfaceY / 地表索引。 */
    private void scanNewCols(int x0, int x1, int z0, int z1) {
        final int waterIdx = Blocks.WATER.index;
        for (int x = x0; x < x1; x++) {
            int[] topCol = surfaceTopY[x];
            for (int z = z0; z < z1; z++) topCol[z] = -1;
            for (int y = 0; y < SY; y++)
                for (int z = z0; z < z1; z++) {
                    int b = mat[x][y][z];
                    if (b == Blocks.AIR.index) continue;
                    idxScratch[b].appendSorted(x, y, z);
                    nonAirScratch.appendSorted(x, y, z);
                    if (Blocks.byIndex(b).solid) topCol[z] = y;     // y 递增 ⇒ 末次写入即最高 solid
                    if (y + 1 >= SY || mat[x][y + 1][z] == Blocks.AIR.index) {
                        surfaceCells.add(cellKey(x, y, z));
                        if (b == waterIdx) waterSurfaceCells.add(cellKey(x, y, z));
                    }
                }
            java.lang.System.arraycopy(topCol, z0, surfaceY[x], z0, z1 - z0);
        }
    }

    private void snapshotChunk(int cx, int cz, long gk) {
        int[][][] m = new int[CHUNK][SY][CHUNK];
        float[][][] ms = new float[CHUNK][SY][CHUNK];
        int bx = cx * CHUNK, bz = cz * CHUNK;
        for (int lx = 0; lx < CHUNK; lx++)
            for (int y = 0; y < SY; y++)
                for (int lz = 0; lz < CHUNK; lz++) {
                    m[lx][y][lz] = mat[bx + lx][y][bz + lz];
                    ms[lx][y][lz] = mass[bx + lx][y][bz + lz];
                }
        chunkEdits.put(gk, new Override(m, ms));
    }

    private void applyOverride(int cx, int cz, Override ov) {
        int bx = cx * CHUNK, bz = cz * CHUNK;
        for (int lx = 0; lx < CHUNK; lx++)
            for (int y = 0; y < SY; y++)
                for (int lz = 0; lz < CHUNK; lz++) {
                    mat[bx + lx][y][bz + lz] = ov.m[lx][y][lz];
                    mass[bx + lx][y][bz + lz] = ov.ms[lx][y][lz];
                }
    }

    /** 当前窗口最小角块坐标（全局）——渲染层跟随玩家流式用。 */
    public int windowOriginCX() { return winCX0; }
    public int windowOriginCZ() { return winCZ0; }

    /**
     * 相干地形高度场 [0,1)（与状态流无关，纯全局坐标+种子函数，顺序无关 → 流式零漂移）。
     * 粗网格（scale=18）取随机值后 smoothstep 双线性插值得到平滑主起伏，叠加一层更细的
     * 细节 octave（scale=6）增加丘陵质感；两者皆由 cellHash 确定性派生，故同种子逐字节一致。
     * （2026-09-11：12/4 → 18/6、0.75/0.25 → 0.8/0.2 —— 配合振幅减半，高位世界坡度可攀。）
     */
    double terrainHeightField(int gx, int gz) {   // 包内可见：Megalith 神殿锚定复用（单一代码源）
        double base = valueNoise(gx, gz, seed, 18) * 0.8
                    + valueNoise(gx, gz, seed ^ 0x9E3779B97F4A7C15L, 6) * 0.2;
        return base < 0 ? 0 : base > 0.999999 ? 0.999999 : base;
    }

    /** 单层值噪声 [0,1)：粗网格 (scale) 上随机，块内 smoothstep 插值 → 连续平滑。（包内可见：Megalith 复用） */
    static double valueNoise(int x, int z, long seed, int scale) {
        double fx = (double) x / scale, fz = (double) z / scale;
        int gx = (int) Math.floor(fx), gz = (int) Math.floor(fz);
        double tx = fx - gx, tz = fz - gz;
        double u = smooth(tx), v = smooth(tz);
        double n00 = cellHash(gx,     gz,     seed);
        double n10 = cellHash(gx + 1, gz,     seed);
        double n01 = cellHash(gx,     gz + 1, seed);
        double n11 = cellHash(gx + 1, gz + 1, seed);
        double a = n00 + (n10 - n00) * u;
        double b = n01 + (n11 - n01) * u;
        return a + (b - a) * v;
    }

    /** smoothstep：t*t*(3-2t)，消除块间线性插值的接缝。（包内可见：Megalith 复用） */
    static double smooth(double t) { return t * t * (3 - 2 * t); }

    /** 确定性单元格随机值 [0,1)（整数网格点专用，2D 地形高度场用）。（包内可见：Megalith 复用） */
    static double cellHash(int gx, int gz, long seed) {
        long h = (long) gx * 374761393L + (long) gz * 668265263L + seed * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h = h ^ (h >>> 16);
        return ((h >>> 11) & 0xFFFFFF) / (double) 0x1000000;
    }

    /** 确定性 3D 单元格随机值 [0,1)（洞穴雕刻用）。 */
    private static double cellHash3(int gx, int gy, int gz, long seed) {
        long h = (long) gx * 374761393L + (long) gy * 668265263L + (long) gz * 2654435761L
                + seed * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h = h ^ (h >>> 16);
        return ((h >>> 11) & 0xFFFFFF) / (double) 0x1000000;
    }

    /** 单层 3D 值噪声 [0,1)：粗网格 (scale) 随机，块内 smoothstep 三线性插值 → 连续平滑隧道。 */
    private static double valueNoise3(int x, int y, int z, long seed, int scale) {
        double fx = (double) x / scale, fy = (double) y / scale, fz = (double) z / scale;
        int gx = (int) Math.floor(fx), gy = (int) Math.floor(fy), gz = (int) Math.floor(fz);
        double tx = fx - gx, ty = fy - gy, tz = fz - gz;
        double u = smooth(tx), v = smooth(ty), w = smooth(tz);
        double c000 = cellHash3(gx,     gy,     gz,     seed);
        double c100 = cellHash3(gx + 1, gy,     gz,     seed);
        double c010 = cellHash3(gx,     gy + 1, gz,     seed);
        double c110 = cellHash3(gx + 1, gy + 1, gz,     seed);
        double c001 = cellHash3(gx,     gy,     gz + 1, seed);
        double c101 = cellHash3(gx + 1, gy,     gz + 1, seed);
        double c011 = cellHash3(gx,     gy + 1, gz + 1, seed);
        double c111 = cellHash3(gx + 1, gy + 1, gz + 1, seed);
        double x00 = c000 + (c100 - c000) * u;
        double x10 = c010 + (c110 - c010) * u;
        double x01 = c001 + (c101 - c001) * u;
        double x11 = c011 + (c111 - c011) * u;
        double y0 = x00 + (x10 - x00) * v;
        double y1 = x01 + (x11 - x01) * v;
        return y0 + (y1 - y0) * w;
    }

    /** 洞穴采样 [0,1)：scale=5 的 3D 值噪声，决定某地下体素是否空腔。 */
    private double caveAt(int gx, int gy, int gz) {
        return valueNoise3(gx, gy, gz, seed ^ 0x1B873593L, 5);
    }
}
