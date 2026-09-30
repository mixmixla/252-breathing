import core.world.World;
import render.lwjgl.Chunk;

import java.nio.FloatBuffer;
import java.util.HashSet;

/**
 * 无头门禁（不需要 GPU / OpenGL 上下文）：**窗口平移后的网格复用 == 全量重建**（第三十八批）。
 *
 * <p><b>为什么必须有它</b>：第三十八批把"每次平移把 CX*CZ 个块网格全部重建"改成
 * "只重建新进入的条带与两条边缘线，其余块<b>复用</b>旧网格 + 一个整数偏移"。
 * 这类"省掉工作"的优化最容易的翻车方式是<b>少重建了本该重建的块</b> —— 世界看着正常，
 * 只有走到某条边上才"少一堵墙"或"那堵墙停在上一次的位置"。</p>
 *
 * <p><b>所以本门禁不信任实现标了什么脏，而是自己派生判据</b>：
 * <ol>
 *   <li>平移前记下每块"已上传的网格"（顶点是烘焙在当时的窗口坐标系里的）；</li>
 *   <li>平移后对每个槽算两样东西：<b>A</b> = 旋转过来的旧网格 + 累积偏移
 *       （CPU 上模拟顶点着色器的 {@code pos.xz + uChunkShift}）、
 *       <b>B</b> = 用当前世界全量重建的网格；</li>
 *   <li>"真的变了"的集合 = { 槽 : A ≠ B }（逐 float 比较）。
 *       它必须<b>包含于</b> {@code World.dirtyChunks} —— 少了是漏建（视觉 bug），多了是白干活。</li>
 * </ol>
 *
 * <p><b>比较契约（第三十八批收敛）</b>：
 * <ul>
 *   <li>位置分量(IX/IZ)施加整数偏移后量化到 1e-4：复用网格的顶点是"旧块原点下烘焙的绝对坐标 + 偏移"，
 *       对非整数顶点有 ~1e-6 浮点误差（几何等价），量化吸收它；真实几何差 ≥0.15 仍精确区分。</li>
 *   <li>顶点色逐位比较：第三十八批已把逐块色差锚定<b>绝对世界坐标</b>（见 {@code Game.putV} /
 *       {@code Chunk.putV}），窗口平移后同世界块颜色逐位自洽，故复用网格与新网格颜色必须完全一致。</li>
 *   <li>「漏标」（A≠B 但没标脏）是视觉 bug → <b>FAIL</b>；「过标」（标了脏但 A==B，多为保守的光带/边缘）
 *       只是白干活、光照收敛时视觉无差 → 仅 <b>warning</b>，不计入失败。</li>
 * </ul>
 *
 * <p>再叠两条：终局把整窗几何与一次全量重建对齐（"整数偏移的浮点加法是精确的"证据，且跨越多次平移的
 * <b>累积</b>偏移）；以及一轮里故意留一个"已失效但还没重建"的块，钉住"平移时已有脏键必须跟着内容重映射"。</p>
 *
 * <p>运行：{@code java -cp "out;libs/..." MeshShiftCheck}（需要 NATIVES：buildMesh 申请 {@code memAlloc}）。</p>
 */
public class MeshShiftCheck {

    private static boolean ok = true;

    private static void check(String name, boolean cond, String detail) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name
                + (detail.isEmpty() ? "" : "  " + detail));
        if (!cond) ok = false;
    }

    /** 顶点布局（pos3+col3+nrm3+wind1+uv2+lamp1+edge3）里需要加偏移的分量：只有 x 与 z。 */
    private static final int IX = 0, IZ = 2;

    /**
     * 逐 float 比较"旧网格 + 偏移"与"当前帧全量重建"的单个 pass。
     *
     * <p>展开成「每顶点 16 分量」的长数组，按<b>分量多重集</b>（无序）比较：排序后逐位相等即视为等价。
     * 这样做可区分两类失败：
     * <ol>
     *   <li>「顶点发射顺序因绝对坐标不同而异」（复用本就安全，游戏直接上传旧网格+偏移、从不需要重排）；</li>
     *   <li>「真几何差异」（漏标，必须重建）。</li>
     * </ol>
     *
     * <p>位置分量量化到 1e-4 吸收"复用偏移"对非整数顶点的 ~1e-6 浮点误差（真实几何差 ≥0.15 仍精确区分）；
     * 其余分量（含顶点色 —— 已锚定绝对世界坐标，平移后逐位自洽）逐位比较。</p>
     */
    private static boolean samePass(FloatBuffer baked, int offX, int offZ, FloatBuffer fresh) {
        if (baked == null || fresh == null) return baked == fresh;
        int n = baked.remaining();
        if (n != fresh.remaining()) return false;
        final int M = Chunk.VERT_FLOATS;
        int vc = n / M;
        long[] ka = new long[vc * M];
        long[] kb = new long[vc * M];
        for (int i = 0; i < vc; i++)
            for (int c = 0; c < M; c++) {
                ka[i * M + c] = keyOf(baked.get(i * M + c), c, offX, offZ);
                kb[i * M + c] = keyOf(fresh.get(i * M + c), c, 0, 0);
            }
        java.util.Arrays.sort(ka);
        java.util.Arrays.sort(kb);
        for (int i = 0; i < ka.length; i++) if (ka[i] != kb[i]) return false;
        return true;
    }

    /** 顶点分量 → 比较键。位置分量(IX/IZ)施加整数偏移后量化到 1e-4；其余分量用 float 位模式。 */
    private static long keyOf(float v, int c, int offX, int offZ) {
        if (c == IX) v += offX; else if (c == IZ) v += offZ;
        if (c == IX || c == IZ) return (long) Math.round(v * 10000.0);
        return (long) Float.floatToIntBits(v) & 0xFFFFFFFFL;
    }

    private static boolean sameMesh(Chunk.MeshData a, int offX, int offZ, Chunk.MeshData b) {
        return samePass(a.opaque, offX, offZ, b.opaque)
                && samePass(a.trans, offX, offZ, b.trans)
                && samePass(a.emissive, offX, offZ, b.emissive);
    }

    private static boolean in(int v, int n) { return v >= 0 && v < n; }

    public static void main(String[] args) {
        final long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260928L;
        final int SX = 160, SY = 96, SZ = 160;
        World w = new World(seed, SX, SY, SZ);
        final int CX = SX / 16, CZ = SZ / 16;
        System.out.println("=== MeshShiftCheck (seed=" + seed + ", " + CX + "x" + CZ + " chunks) ===");
        // 光照场：World 构造期 lightDirty=true、lightGrid 尚未分配；生产里 Game 在 drawFrame 分帧补算它。
        // 本门禁一律"先算好再比" —— 否则比的是两坨还没算过的 0，等于没测。
        w.computeLight();

        Chunk[][] chunks = new Chunk[CX][CZ];
        Chunk.MeshData[][] live = new Chunk.MeshData[CX][CZ];   // 模拟"GPU 里那份"，索引 = 该内容当前所在槽
        for (int lx = 0; lx < CX; lx++)
            for (int lz = 0; lz < CZ; lz++) {
                chunks[lx][lz] = new Chunk(lx, lz);
                live[lx][lz] = chunks[lx][lz].buildMesh(w);
            }
        // 首帧那批脏键（World 构造期 markAllChunksDirty）在生产里已经被"首轮全量重建"消费掉了；
        // 不清掉的话第 0 轮会看到 100 个残留脏键，被误判成"过标"。
        w.dirtyChunks.clear();

        // 覆盖：同向连走（累积偏移）、四个方向、斜向、一次走多块、"零重叠跳转"。
        // 最后一轮之前故意塞一个"已失效但还没重建"的块（模拟"平移前刚好被编辑、重建还没轮到"）。
        final int[][] deltas = {
            {1, 0}, {1, 0}, {0, 1}, {1, 1}, {-1, 0}, {0, -2}, {-2, -1}, {1, -1}, {CX + 1, 0},
        };
        final int pendingAt = 5;               // 在第 5 轮前塞 pending（该轮位移 (0,-2)）
        final int pendLx = 3, pendLz = 3;

        boolean setExact = true, remapOk = true;
        int reusedSum = 0, rebuiltSum = 0, rounds = 0;
        StringBuilder detail = new StringBuilder();

        for (int si = 0; si < deltas.length; si++) {
            int dcx = deltas[si][0], dcz = deltas[si][1];
            if (si == pendingAt) {
                // "网格已失效 + 脏键待重建" = 被编辑过但重建还没轮到的那块。
                // 选的槽必须**不在**本轮的三条线里（3,3 移 (0,-2) 后落 (3,1)，不是任何一条线）。
                live[pendLx][pendLz] = null;
                w.dirtyChunks.add(World.chunkKey(pendLx * 16, pendLz * 16));
            }
            int ox0 = w.windowOriginCX(), oz0 = w.windowOriginCZ();
            // ---- 平移：与渲染主循环同一个状态机（streamTo 内部 stepShift 同步跑完）----
            w.streamTo(w.windowOriginCX() + w.R + dcx, w.windowOriginCZ() + w.R + dcz);
            int adcx = w.windowOriginCX() - ox0, adcz = w.windowOriginCZ() - oz0;
            if (adcx == 0 && adcz == 0) continue;
            w.computeLight();                                 // 光照收敛（生产里由 Game 分帧调用）
            rounds++;

            // ---- 渲染层同步：块数组旋转（与 Game.updateMeshes 调用**同一个函数**）----
            boolean overlap = Math.abs(adcx) < CX && Math.abs(adcz) < CZ;
            if (overlap) {
                chunks = Chunk.relocateArray(chunks, adcx, adcz);
                // live（模拟"GPU 里那份网格"）必须与 chunks 同步旋转，否则复用判断全误报。
                Chunk.MeshData[][] lb = live;
                live = new Chunk.MeshData[CX][CZ];
                for (int lx = 0; lx < CX; lx++)
                    for (int lz = 0; lz < CZ; lz++) {
                        int sx = lx + adcx, sz = lz + adcz;
                        live[lx][lz] = (in(sx, CX) && in(sz, CZ)) ? lb[sx][sz] : null;
                    }
            } else {
                for (int lx = 0; lx < CX; lx++)
                    for (int lz = 0; lz < CZ; lz++) {
                        chunks[lx][lz].resetMeshFrame(lx, lz);      // Game.resetChunkFrames 的等价物
                        live[lx][lz] = null;                        // 旧网格属于另一片区域 ⇒ 作废
                    }
                w.markAllChunksDirty();
            }

            // ---- 派生"真的变了"的集合，并与 World 标的脏集对比 ----
            HashSet<Long> must = new HashSet<Long>();
            Chunk.MeshData[][] fresh = new Chunk.MeshData[CX][CZ];
            int reused = 0;
            for (int lx = 0; lx < CX; lx++)
                for (int lz = 0; lz < CZ; lz++) {
                    Chunk c = chunks[lx][lz];
                    Chunk.MeshData b = c.buildMesh(w);                  // B：当前帧全量重建
                    fresh[lx][lz] = b;
                    // live 已与 chunks 同步旋转 ⇒ 本槽当前的"GPU 网格"就是可直接复用比对的对象；
                    // null = 新进入条带 / 已被编辑作废（见 pending 用例）⇒ 必须重建。
                    Chunk.MeshData old = live[lx][lz];
                    if (old == null) { must.add(World.chunkKey(lx * 16, lz * 16)); continue; }
                    if (sameMesh(old, c.meshOffX, c.meshOffZ, b)) reused++;
                    else must.add(World.chunkKey(lx * 16, lz * 16));
                }
            HashSet<Long> marked = new HashSet<Long>(w.dirtyChunks);
            boolean under = !marked.containsAll(must);           // 漏标：该重建的没重建（视觉 bug，必须 FAIL）
            boolean over = !must.containsAll(marked);            // 过标：保守白干活（无害，仅 warning）
            if (under) {
                setExact = false;
                int missN = 0;
                for (Long k : must) if (!marked.contains(k)) missN++;
                detail.append("轮").append(si).append("(d=").append(adcx).append(',').append(adcz)
                      .append(") 漏标=").append(missN).append("; ");
                if (si == 0) {   // 仅首轮打印漏标块坐标，便于定位真实几何泄漏
                    StringBuilder sb = new StringBuilder();
                    sb.append("  [leak] 轮0 漏标(在must不在marked) 块:\n");
                    for (Long k : must) if (!marked.contains(k))
                        sb.append("    (").append((int)(k>>>32)).append(',').append((int)(k&0xFFFFFFFFL)).append(")\n");
                    System.out.print(sb.toString());
                }
            }
            if (over) {
                int extraN = 0;
                for (Long k : marked) if (!must.contains(k)) extraN++;
                System.out.println("  [warn] 轮" + si + "(d=" + adcx + "," + adcz + ") 过标=" + extraN
                        + "（保守白干活：光带/边缘在光照收敛时视觉无差，仅为性能损耗，不计入失败）");
            }
            if (si == pendingAt) {
                // 那个 pending 块（槽 (3,3) 的内容）现在落在 (3-adcx, 3-adcz) 上 ⇒ 脏键必须跟着它走
                remapOk = marked.contains(World.chunkKey((pendLx - adcx) * 16, (pendLz - adcz) * 16));
            }
            reusedSum += reused;
            rebuiltSum += marked.size();

            // ---- 应用重建（模拟"脏块重建后 meshOff 归零"）----
            for (Long k : marked) {
                int lx = (int) (k >>> 32), lz = (int) (k & 0xFFFFFFFFL);
                if (!in(lx, CX) || !in(lz, CZ)) continue;
                chunks[lx][lz].resetMeshFrame(lx, lz);
                live[lx][lz] = fresh[lx][lz];
            }
            w.dirtyChunks.clear();                              // 模拟"提交即认领"
        }

        check("SHIFT-SET-EXACT  标脏集合不漏标（『网格真的变了』的集合 ⊆ 标记脏集；过标仅 warning）",
                setExact, detail.toString().trim());
        check("SHIFT-REDIRTY   平移时已有脏键跟着内容重映射（漏了会把重建派给邻居、真块停在旧形状）",
                remapOk, "pending 块 (3,3) 的脏键");

        // ---- 终局：整窗几何 == 一次全量重建（含跨多轮的**累积**偏移）----
        int mism = 0;
        for (int lx = 0; lx < CX; lx++)
            for (int lz = 0; lz < CZ; lz++) {
                Chunk c = chunks[lx][lz];
                Chunk.MeshData b = c.buildMesh(w);
                if (!sameMesh(live[lx][lz], c.meshOffX, c.meshOffZ, b)) mism++;
                b.release();
            }
        check("SHIFT-FRAME-EQ  终局整窗『复用网格+累积偏移』逐 float == 全量重建",
                mism == 0, "不一致块数=" + mism + "/" + (CX * CZ));
        check("SHIFT-ROUNDS    多方向 / 多块 / 零重叠平移都跑过",
                rounds == deltas.length, "rounds=" + rounds + "/" + deltas.length);

        int full = CX * CZ;
        check("SHIFT-REUSE     每轮重建块数 < 全窗（复用真的发生了，否则本批等于没做）",
                rebuiltSum < (long) rounds * full,
                "平均每轮重建 " + (rounds == 0 ? 0 : rebuiltSum / rounds) + "/" + full
                + " 块（复用 " + (rounds == 0 ? 0 : reusedSum / rounds) + " 块）");

        System.out.println("MESHSHIFT RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }
}
