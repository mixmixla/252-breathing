import core.world.World;
import render.lwjgl.Chunk;
import render.lwjgl.MeshBuilder;

import java.nio.FloatBuffer;

/**
 * 无头门禁（<b>不需要 GPU / OpenGL 上下文</b>）：<b>异步网格构建 == 同步网格构建</b>（第三十二批 A2）。
 *
 * <p>为什么必须有它：A2 把 {@code Chunk.buildMesh}（纯计算）搬到了 worker 线程池。这是本项目第一次
 * 引入"渲染层的并发"，而"优化"最容易的翻车方式是<b>悄悄改变输出</b> —— 画面看着差不多，但顶点少了一个、
 * 三角形顺序换了、边界格的面被裁掉了。这类 bug 门禁看不见、软件预览器也可能看不见，
 * 只能在某个角度偶发地"少一块地形"。</p>
 *
 * <p>所以本门禁把不变量钉成<b>逐字节比对</b>：同世界、同块，"经 MeshBuilder 的 worker 产出"与
 * "主线程直接 buildMesh"的三个 pass（不透明 / 半透明 / 发光）必须<b>完全相同</b>。
 * 顺带把提交/认领/丢弃三条簿记也钉住（缓冲是 {@code memAlloc} 的非 GC 内存，漏释放不会崩，
 * 只会慢慢吃内存 —— 正是最该有牙的那类问题）。</p>
 *
 * <p>运行：{@code java -cp "out;libs/..." MeshAsyncCheck}</p>
 */
public class MeshAsyncCheck {

    private static boolean ok = true;

    private static void check(String name, boolean cond, String detail) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name
                + (detail.isEmpty() ? "" : "  " + detail));
        if (!cond) ok = false;
    }

    /** 逐字节比对两个 direct 缓冲的<b>有效区间</b>（buildMesh 结尾已 flip ⇒ 区间就是写出的内容）。 */
    private static boolean sameBuf(FloatBuffer a, FloatBuffer b) {
        if (a == null || b == null) return a == b;
        return a.equals(b);      // FloatBuffer.equals = 比较 remaining 的全部元素（不是引用相等）
    }

    private static int remaining(FloatBuffer f) { return f == null ? -1 : f.remaining(); }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260928L;
        final int SX = 48, SY = 24, SZ = 48;
        World w = new World(seed, SX, SY, SZ);
        System.out.println("=== MeshAsyncCheck (seed=" + seed + ") ===");

        // ---------- ① 出队序策略（纯函数，确定性可断言）----------
        check("ORDER-NEAR-FIRST  近者先出队（距离² 小者优先）",
                MeshBuilder.compareOrder(0f, 9L, 100f, 1L) < 0
                && MeshBuilder.compareOrder(100f, 1L, 0f, 9L) > 0,
                "compareOrder(近,远)<0 且反对称");
        check("ORDER-TIE-KEY    同距按块键升序（与脏集合遍历序无关）",
                MeshBuilder.compareOrder(4f, 2L, 4f, 7L) < 0
                && MeshBuilder.compareOrder(4f, 7L, 4f, 2L) > 0
                && MeshBuilder.compareOrder(4f, 7L, 4f, 7L) == 0,
                "同距 4 时 key2 < key7");

        // ---------- ② 异步产出 == 同步产出（本批核心不变量）----------
        MeshBuilder mb = new MeshBuilder(2);
        int[][] coords = { {0, 0}, {1, 0}, {0, 1}, {2, 2} };
        boolean eq = true, countsEq = true, maxYEq = true;
        int compared = 0;                 // ⚠️ 没有它，"超时后一条都没比"会静默空跑成 PASS（本批亲历）
        StringBuilder detail = new StringBuilder();
        for (int[] c : coords) {
            Chunk chunk = new Chunk(c[0], c[1]);
            long key = World.chunkKey(c[0] * 16, c[1] * 16);
            if (!mb.submit(chunk, w, key, 0f, 8)) { eq = false; detail.append("submit 被拒 "); break; }
            MeshBuilder.Completed got = waitFor(mb, w);
            if (got == null) { eq = false; detail.append("超时/被丢弃 "); break; }
            compared++;
            Chunk.MeshData asy = got.mesh;
            Chunk.MeshData syn = chunk.buildMesh(w);      // 同一块、同一世界，主线程直接算
            boolean same = sameBuf(asy.opaque, syn.opaque)
                    && sameBuf(asy.trans, syn.trans)
                    && sameBuf(asy.emissive, syn.emissive);
            boolean sameCounts = asy.quadsO == syn.quadsO && asy.quadsT == syn.quadsT
                    && asy.quadsE == syn.quadsE;
            if (!same) { eq = false; detail.append("块(").append(c[0]).append(',').append(c[1]).append(")缓冲不同 "); }
            if (!sameCounts) { countsEq = false; detail.append("块(").append(c[0]).append(',').append(c[1]).append(")面数不同 "); }
            if (asy.maxY != syn.maxY) { maxYEq = false; }
            if (c[0] == 0 && c[1] == 0) {
                detail.append("块(0,0) quads O/T/E=").append(syn.quadsO).append('/')
                      .append(syn.quadsT).append('/').append(syn.quadsE)
                      .append(" verts=").append(remaining(syn.opaque) / Chunk.VERT_FLOATS).append(' ');
            }
            asy.release();
            syn.release();
        }
        check("ASYNC-EQ-SYNC    异步三个 pass 逐字节 == 同步（本批核心不变量）",
                eq && compared == coords.length, detail.toString().trim());
        check("ASYNC-QUADS      三个 pass 的四边形数一致（且确实比过 " + coords.length + " 块）",
                countsEq && compared == coords.length, "compared=" + compared);
        check("ASYNC-MAXY       列顶包络（遮挡剔除用）一致",
                maxYEq && compared == coords.length, "compared=" + compared);

        // ---------- ③ 在飞上限 ----------
        MeshBuilder mb2 = new MeshBuilder(1);
        Chunk c3 = new Chunk(0, 0);
        boolean first = mb2.submit(c3, w, World.chunkKey(0, 0), 0f, 1);
        boolean second = mb2.submit(c3, w, World.chunkKey(16, 0), 1f, 1);   // 上限 = 1 ⇒ 必被拒
        check("INFLIGHT-CAP     在飞上限生效（达到上限时 submit 返回 false）",
                first && !second && mb2.inflight() == 1,
                "first=" + first + " second=" + second + " inflight=" + mb2.inflight());

        // ---------- ④ epoch：过期结果丢弃 + 块键回滚重标脏 ----------
        long keyE = World.chunkKey(0, 0);
        w.dirtyChunks.remove(keyE);
        long droppedBefore = mb2.droppedTotal();
        mb2.bumpEpoch();                                        // 模拟"窗口平移 / 换世界"
        MeshBuilder.Completed stale = waitDrain(mb2, w, 4000L);
        boolean rolledBack = w.dirtyChunks.contains(keyE);
        check("EPOCH-DROP       bump 后旧结果被丢弃（poll 返回 null 而非旧网格）", stale == null, "");
        check("EPOCH-REDIRTY   丢弃的块被重新标脏（否则那块地形永久缺失）", rolledBack,
                "dirtyChunks 含回滚键=" + rolledBack);
        check("EPOCH-COUNT      丢弃计数 +1（可诊断）",
                mb2.droppedTotal() == droppedBefore + 1,
                "dropped=" + mb2.droppedTotal());

        // ---------- ⑤ 簿记：全部消费后归零（每个结果都被 release 或 apply 过）----------
        MeshBuilder mb3 = new MeshBuilder(2);
        // ⚠️ 块下标必须落在世界内：SX=SZ=48 ⇒ 只有 3×3 个块。
        // （本门禁第一版写成 `new Chunk(i,0)` i=0..5 → chunk 5 覆盖 x=80..96 → worker 里
        //   ArrayIndexOutOfBoundsException，而症状是"任务永远取不回"—— 也正是这次让我给
        //   MeshBuilder 补上了 worker 的 Throwable 兜底。）
        for (int i = 0; i < 6; i++) {
            int cx = i % 3, cz = i / 3;
            mb3.submit(new Chunk(cx, cz), w, World.chunkKey(cx * 16, cz * 16), (float) i, 64);
        }
        int seen = 0;
        long t0 = System.nanoTime();
        while (seen < 6 && (System.nanoTime() - t0) / 1000000L < 10000L) {
            MeshBuilder.Completed g = mb3.pollCompleted(w);
            if (g == null) { Thread.sleep(1L); continue; }
            g.mesh.release();
            seen++;
        }
        check("DRAIN-ALL        全部任务都能取回（顺序无关）", seen == 6, "seen=" + seen + "/6");
        check("DRAIN-ZERO       取完后 in-flight 与 ready 归零（无悬挂缓冲）",
                mb3.inflight() == 0 && mb3.ready() == 0,
                "inflight=" + mb3.inflight() + " ready=" + mb3.ready());

        // ---------- ⑥ shutdown 收尾 ----------
        mb.shutdown(); mb2.shutdown();
        check("WORKER-NO-FAIL  worker 侧零异常（非零即『某块地形永远建不出来』）",
                mb.failedTotal() == 0 && mb2.failedTotal() == 0 && mb3.failedTotal() == 0,
                "failed=" + mb.failedTotal() + "/" + mb2.failedTotal() + "/" + mb3.failedTotal());
        mb3.submit(new Chunk(0, 0), w, World.chunkKey(0, 0), 0f, 64);   // shutdown 后再压一个
        mb3.shutdown();
        check("SHUTDOWN-CLEAN  shutdown 后 in-flight 归零（队列里的缓冲被释放）",
                mb3.inflight() == 0, "inflight=" + mb3.inflight());

        System.out.println("MESHASYNC RESULT: " + (ok ? "PASS" : "FAIL"));
        if (!ok) System.exit(1);
    }

    /** 轮询等一个结果（内部按 pollCompleted 语义消化过期/拒收，故可能永不返回 ⇒ 有超时）。 */
    private static MeshBuilder.Completed waitFor(MeshBuilder mb, World w) throws Exception {
        long t0 = System.nanoTime();
        while ((System.nanoTime() - t0) / 1000000L < 10000L) {
            MeshBuilder.Completed c = mb.pollCompleted(w);
            if (c != null) return c;
            if (mb.inflight() == 0) return null;      // 已被丢弃 ⇒ 不会再有
            Thread.sleep(1L);
        }
        return null;
    }

    /** 空转到"队列无进展"为止（用于 epoch 丢弃后确认 poll 不再返回任何东西）。 */
    private static MeshBuilder.Completed waitDrain(MeshBuilder mb, World w, long ms) throws Exception {
        long t0 = System.nanoTime();
        while ((System.nanoTime() - t0) / 1000000L < ms) {
            MeshBuilder.Completed c = mb.pollCompleted(w);
            if (c != null) { c.mesh.release(); return c; }   // 不该发生：epoch 不符 ⇒ 必被丢弃
            if (mb.inflight() == 0) return null;
            Thread.sleep(1L);
        }
        return null;
    }
}
