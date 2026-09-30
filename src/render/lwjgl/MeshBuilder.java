package render.lwjgl;

import core.world.World;

import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 区块网格的<b>异步构建器</b>（第三十二批 A2"worker 线程池构建网格"）。
 *
 * <p><b>照抄 MC 的 {@code ChunkRenderDispatcher} 分工</b>（字段名就是它的架构答案）：
 * <pre>
 *   MC 的字段                             本项目落点
 *   executor（worker 线程池）         →   worker 只跑 {@link Chunk#buildMesh}（纯计算、不碰 GL）
 *   toBatchHighPriority / LowPriority →   {@link #queue}：按"到相机距离²"升序的出队序
 *   toUpload（主线程只做这个）         →   {@link #done}，主线程在 {@link #applyReady} 里做 glBufferData
 *   highPriorityQuota（每帧上传配额）  →   调用方传入的 {@code maxApply}
 * </pre>
 *
 * <p><b>为什么必须这么改</b>：{@code Chunk.rebuild()} 是一次 5~15ms 的<b>整块</b>网格重建，而它此前
 * 跑在<b>主线程</b>。只要它在主线程，"每帧少做一点"只能把卡顿摊匀，不能让它消失 ——
 * 这正是用户报的"走路一卡一卡 / 跟地形交互卡顿"的根因。MC 的做法是<b>根本不在主线程做</b>。</p>
 *
 * <h3>并发安全论证（本批的<b>唯一</b>真风险点，已逐条核实）</h3>
 * <ol>
 *   <li><b>worker 绝不写世界</b>：{@link Chunk#buildMesh} 只读 {@code w.mat}、{@code w.getBlock}
 *       （纯数组读，见 World 实现）、{@code FaceCull} 与 {@code EdgeAtlas}/{@code Autotile} 的纯函数。
 *       实测确认 {@code getBlock} <b>不触发</b>惰性索引重建 ⇒ 不存在"worker 顺手改了世界"的路径。</li>
 *   <li><b>基本类型数组读原子</b>：JMM 保证 {@code int[]} 元素读不撕裂 ⇒ 最坏读到"上一 tick 的
 *       地形"（一个格子的旧值），下一个脏标记就会修正。网格是渲染层派生量，不进 {@code hashState}。</li>
 *   <li><b>稀疏状态表（{@code meta} / {@code blockState}，LinkedHashMap）</b>：主线程可能同时写它们，
 *       故 worker 侧最坏读到 null ⇒ 表现是"某一帧某个台阶朝向画错 / 某根导线不亮"，下一帧重建自愈。
 *       ⚠️ 这是本批<b>刻意接受</b>的近似（Java 8+ 的 {@code get} 并发下只会返回错值，不会死循环、不会抛异常）。
 *       要彻底消除它得把这两个表在提交时深拷贝并让整条 emit 链改走快照接口 —— 那是比 A2 更大的一批改动，
 *       收益只是"消掉一个自愈的一帧观感瑕疵"，因此本批不做。</li>
 *   <li><b>可变静态量</b>：emit 链上唯一被读的可变量是 {@code EdgeAtlas.STRENGTH}，它只在<b>启动期</b>
 *       （QA 参数）被写一次 —— 线程启动本身构成 happens-before，故安全。</li>
 *   <li><b>{@code world.dirtyChunks}（HashSet）只被主线程碰</b>：提交与重标脏都在主线程，
 *       worker 完全不接触它。</li>
 * </ol>
 *
 * <h3>过期结果（epoch）</h3>
 * 世界窗口平移（{@code stepShift}）或换世界时，在飞的任务是用<b>另一个坐标系</b>算的（平移还会把块对象
 * 旋转到新槽），故每次这类变化由调用方 {@link #bumpEpoch()} 一次；应用时 epoch 不符 ⇒
 * <b>丢弃 + 释放缓冲 + 重标脏</b>（重标脏取块对象当前槽，见 {@link #redirty}）。
 * 缓冲是 {@code memAlloc} 的<b>非 GC 内存</b>，丢弃路径必须 release，否则泄漏。
 *
 * <p>⚠️ 注意"丢弃"并不等于"整窗重建"：被丢弃的只是**恰好在那几帧里在飞**的少数块
 * （≤ {@code maxInflight}），其余的块网格在平移时是被<b>复用</b>的（{@link Chunk#meshOffX}）。
 */
public final class MeshBuilder {

    /** 一块网格的构建任务。{@code result} 由 worker 写、主线程读（经阻塞队列的 happens-before 传递）。 */
    private static final class Task {
        final Chunk chunk;
        final World world;
        final long epoch;
        final long key;        // 提交时的窗口相对脏块键：**只用于出队序的二级键**（重标脏见 redirty）
        final float dist2;     // 到相机中心距离²（出队序 = 近者先做）
        volatile Chunk.MeshData result;
        /** worker 构建时抛异常 ⇒ 由主线程记账并<b>不</b>回滚重标脏（否则会无限重试同一个坏块）。 */
        volatile boolean failed;

        Task(Chunk chunk, World world, long epoch, long key, float dist2) {
            this.chunk = chunk; this.world = world; this.epoch = epoch;
            this.key = key; this.dist2 = dist2;
        }
    }

    /**
     * 出队序<b>策略</b>（纯函数）：距离² 升序；同距按块键升序。
     *
     * <p>暴露成纯函数是为了让门禁能直接断言"策略"本身（排序是对外不可见的优化，靠行为很难确定性验证）。
     * {@link #ORDER} 就是它的唯一生产调用方。
     *
     * <p>⚠️ 二级键用 <b>块键而不是提交序号</b>：脏块集合是 {@code HashSet}，遍历序不确定，
     * 用序号会让"哪块先建"随哈希序漂移；用块键则与遍历序无关（本层是渲染层、不影响指纹，但确定性无价）。
     */
    public static int compareOrder(float d1, long k1, float d2, long k2) {
        if (d1 != d2) return d1 < d2 ? -1 : 1;
        return k1 < k2 ? -1 : (k1 == k2 ? 0 : 1);
    }

    private static final Comparator<Task> ORDER = new Comparator<Task>() {
        @Override public int compare(Task a, Task b) {
            return compareOrder(a.dist2, a.key, b.dist2, b.key);
        }
    };

    private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<Task>(64, ORDER);
    private final ConcurrentLinkedQueue<Task> done = new ConcurrentLinkedQueue<Task>();
    /** 已提交、尚未被应用（或丢弃）的任务数 —— 内存闸门（每块缓冲可到 MB 级）。 */
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicLong builtCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    private final Thread[] workers;
    private volatile boolean running = true;
    /** 世界版本号：提交时记进任务，应用时比对 —— 不符即丢弃（窗口平移/换世界）。 */
    private volatile long epoch = 0L;

    public MeshBuilder(int threads) {
        int n = Math.max(1, threads);
        this.workers = new Thread[n];
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() { work(); }
            }, "BW-Mesh-" + i);
            t.setDaemon(true);      // 关窗即随进程退出；正常运行由 shutdown() 明确收尾
            t.start();
            this.workers[i] = t;
        }
    }

    /** worker 主循环：取任务 → 纯计算 → 交给主线程。 */
    private void work() {
        while (this.running) {
            Task t;
            try {
                t = this.queue.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;                                     // shutdown() 用 interrupt 唤醒
            }
            if (t == null) continue;
            if (t.epoch != this.epoch) { this.done.add(t); continue; }   // 已过期：不白算，交主线程释放
            try {
                t.result = t.chunk.buildMesh(t.world);                   // ← 唯一的重活，在 worker 上
                this.builtCount.incrementAndGet();
            } catch (Throwable e) {
                // ⚠️ 绝不能让它把线程"静默带走"：worker 一旦死掉，后续所有任务永久卡在队列里，
                // 而症状是"地形局部永远不出现"（极难归因）。响亮报错 + 记账 + 交主线程决定。
                t.failed = true;
                this.failedCount.incrementAndGet();
                System.err.println("[MESH] worker 构建失败 chunk(" + t.chunk.cx + "," + t.chunk.cz
                        + ") key=" + t.key + " : " + e);
            }
            this.done.add(t);
        }
    }

    /** 版本号 +1（窗口平移 / 换世界时调用一次）：在飞任务的产物此后一律丢弃。 */
    public void bumpEpoch() { this.epoch++; }

    /** 已提交但未应用的任务数（内存闸门读数）。 */
    public int inflight() { return this.inflight.get(); }

    /** 已生成、待主线程应用的块数。 */
    public int ready() { return this.done.size(); }

    /** 已完成的构建总数（诊断用）。 */
    public long builtTotal() { return this.builtCount.get(); }

    /** 被丢弃（过期）的构建总数（诊断用；非零说明世界在移动，属正常）。 */
    public long droppedTotal() { return this.droppedCount.get(); }

    /** worker 侧构建抛异常的次数（诊断用）。<b>非零即故障</b>：那一块地形不会出现。 */
    public long failedTotal() { return this.failedCount.get(); }

    /**
     * 提交一块。返回 {@code false} 表示已达在飞上限（本帧不再提交，把内存与主线程上传压力压住）。
     *
     * <p>调用方必须已从 {@code world.dirtyChunks} 移除该键 —— 与旧的同步实现同一语义
     * （"提交即认领"）；若之后该块又被编辑，{@code markDirty} 会重新加回去 ⇒ 自然重建。
     */
    public boolean submit(Chunk chunk, World world, long key, float dist2, int maxInflight) {
        if (this.inflight.get() >= maxInflight) return false;
        this.inflight.incrementAndGet();
        this.queue.add(new Task(chunk, world, this.epoch, key, dist2));
        return true;
    }

    /**
     * 主线程：取出下一个已完成的结果（没有则 {@code null}）。<b>调用方负责 {@code mesh.release()}</b>。
     *
     * <p>过期结果（{@code result == null} 或 epoch 不符）在这里就地消化：丢弃 / 释放缓冲 /
     * 把块键重新标脏 —— 所以返回的<b>必定</b>是"当前版本"的产物，调用方无需再判 epoch。
     *
     * <p>它是 {@link #applyReady} 的必经路径（生产调用方），同时让离线门禁能在<b>无 GL</b> 下
     * 直接拿到网格做逐字节比对 —— 否则"异步结果 == 同步结果"这条不变量就只能靠肉眼。
     */
    public Completed pollCompleted(World world) {
        for (;;) {
            Task t = this.done.poll();
            if (t == null) return null;
            this.inflight.decrementAndGet();
            if (t.failed) {                                     // 已在 worker 侧响亮报错；不回滚（避免无限重试）
                this.droppedCount.incrementAndGet();
                continue;
            }
            Chunk.MeshData m = t.result;
            if (m == null) {                                    // worker 侧就发现过期（没白算）
                redirty(world, t);
                this.droppedCount.incrementAndGet();
                continue;
            }
            if (t.epoch != this.epoch) {                        // 算完才过期
                m.release();
                redirty(world, t);
                this.droppedCount.incrementAndGet();
                continue;
            }
            return new Completed(t.chunk, m);
        }
    }

    /**
     * 丢弃过期结果时把该块重新标脏（否则"玩家在平移前后编辑的那一格"永远不会被重建）。
     *
     * <p>⚠️ 第三十八批：<b>键必须取块对象**当前**的 cx/cz，而不是任务里记的 {@code key}</b>。
     * 窗口平移时块对象会被旋转到新槽（{@link Chunk#relocateArray}），旧 key 指向的是**邻居块** ——
     * 用旧键会把重建错派给别人，而真正带编辑的那块一直不重建（地形停在旧形状）。
     * 旋转之后 {@code cx/cz} 仍然等于"该内容现在所在的槽"（这是它的定义），所以取它是对的。
     */
    private static void redirty(World world, Task t) {
        world.dirtyChunks.add(World.chunkKey(t.chunk.cx * 16, t.chunk.cz * 16));
    }

    /** 一个"块 + 它的新网格"配对（{@link #pollCompleted} 的返回值）。 */
    public static final class Completed {
        public final Chunk chunk;
        public final Chunk.MeshData mesh;
        Completed(Chunk chunk, Chunk.MeshData mesh) { this.chunk = chunk; this.mesh = mesh; }
    }

    /**
     * 主线程：把已完成的网格上传到 GL（<b>唯一碰 GL 的一步</b>）。
     *
     * @param world    取 {@code dirtyChunks} 用于"过期结果回滚重标脏"
     * @param maxApply 每帧最多上传几块（MC 的每帧上传配额）
     * @param budgetMs 每帧最多在这上面花多少毫秒（0 = 不限）
     * @return 真正应用（上传）了几块
     */
    public int applyReady(World world, int maxApply, float budgetMs) {
        long t0 = java.lang.System.nanoTime();
        int applied = 0;
        while (applied < maxApply) {
            Completed c = this.pollCompleted(world);
            if (c == null) break;
            c.chunk.applyMesh(c.mesh);                          // 内含 release()
            applied++;
            if (budgetMs > 0f
                    && (java.lang.System.nanoTime() - t0) / 1000000.0f >= budgetMs) break;
        }
        return applied;
    }

    /** 收尾：停 worker、丢弃在飞结果（缓冲是非 GC 内存，必须显式释放）。 */
    public void shutdown() {
        this.running = false;
        for (Thread t : this.workers) t.interrupt();
        Task t;
        while ((t = this.queue.poll()) != null) {
            if (t.result != null) t.result.release();
        }
        while ((t = this.done.poll()) != null) {
            if (t.result != null) t.result.release();
        }
        this.inflight.set(0);
    }
}
