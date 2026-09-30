package core.sim;

import core.net.InputFrame;
import core.net.PredictiveSession;
import core.world.Player;
import core.world.World;

/**
 * PREDROLLBACK 门禁（N4：预测回滚）。
 *
 * <p><b>这是 N4 唯一的正确性证明。</b> 它把「客户端预测 + 权威帧延迟到达 + 回滚重演」跑通，
 * 并断言其终态与「同种子 + 同序权威帧 + 纯锁步」逐字节一致。
 *
 * <p><b>黄金判据</b>：预测回滚跑出的 {@code netHash} == 纯锁步（D=0，无预测）跑出的 {@code netHash}。
 * 预测/回滚只改变「输入到达的顺序」，不改「已确认历史的演化结果」。
 *
 * <p><b>断言的性质</b>（不是"能跑"，而是"机制真实发生且正确"）：
 * <ol>
 *   <li>{@code FINAL_EQUALS_REFERENCE}：延迟 D=4 的预测客户端终态 == D=0 的纯锁步参照（核心判据）。</li>
 *   <li>{@code ROLLBACK_HAPPENED}：预测真错 → 真回滚（rollbackCount &gt; 0；防假绿：否则本测试什么都没证明）。</li>
 *   <li>{@code NO_ROLLBACK_STILL_CONVERGES}：D=0 时 rollbackCount == 0，终态仍等于预测客户端（机制不污染无回滚情形）。</li>
 *   <li>{@code DETERMINISTIC_REPLAY}：同输入跑两遍预测客户端 → 终态逐字节一致（会话确定性）。</li>
 *   <li>{@code BASELINE_CACHED}：每 tick 取快照但原始地形基线只重生成极少次（N4 前置：缓存基线生效）。</li>
 * </ol>
 */
public final class PredRollbackTest {

    private static final long SEED = 0x9A77C0DEL;
    private static final int SX = 96, SY = 112, SZ = 96;
    private static final int N = 40;          // 总 tick 数
    private static final int D = 4;           // 预测延迟（tick 数）
    private static final int[] PLAYER_IDS = { 0, 1 };
    private static final int LOCAL_ID = 1;    // 本地玩家（即时可知）；远端玩家 = 0（被预测、驱动世界）

    private static int props = 0;
    private static boolean fail = false;

    private static void ok(String name, boolean cond) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name);
        if (cond) props++; else fail = true;
    }

    /** 远端（被预测）玩家在 tick t 的"真实"意图：刻意做成非周期、使重复上次预测必然频繁失准。 */
    private static Player.Intent remoteIntent(int t) {
        return Player.Intent.move(((t * 5) % 3) - 1, ((t * 7) % 3) - 1);
    }
    /** 本地玩家意图（即时可知；本门禁中不影响世界演化，仅占位）。 */
    private static Player.Intent localIntent(int t) {
        return Player.Intent.idle();
    }

    /** 构造权威帧序列：帧 t 含 [远端真实意图, 本地意图]，playerIds 升序。 */
    private static InputFrame[] authority() {
        InputFrame[] f = new InputFrame[N + 1];
        for (int t = 1; t <= N; t++)
            f[t] = new InputFrame(t, PLAYER_IDS,
                    new Player.Intent[]{ remoteIntent(t), localIntent(t) });
        return f;
    }

    /** 驱动一个预测会话直到所有权威帧确认（confirmedTick == N）。delayD=0 即纯锁步参照。 */
    private static PredictiveSession run(long seed, InputFrame[] auth, int delayD) {
        World w = new Simulation(seed, SX, SY, SZ).world;
        // 远端（索引 0）意图驱动世界 → 预测错误必须能造成世界分叉，回滚才有意义
        PredictiveSession.InputSink sink = new PredictiveSession.InputSink() {
            @Override public void apply(int tick, Player.Intent[] by) { w.player.setIntent(by[0]); }
        };
        PredictiveSession.LocalIntentSource localSrc = new PredictiveSession.LocalIntentSource() {
            @Override public Player.Intent forTick(int t) { return localIntent(t); }
        };
        PredictiveSession s = new PredictiveSession(w, LOCAL_ID, PLAYER_IDS, sink, localSrc);
        int nextDeliver = 1;
        int guard = 0;
        while (s.confirmedTick() < N) {
            if (nextDeliver <= N && s.predictedTick() - nextDeliver + 1 >= delayD) {
                s.receiveFrame(auth[nextDeliver]);
                nextDeliver++;
            } else if (s.predictedTick() < N) {
                s.advance();
            } else {
                if (nextDeliver <= N) { s.receiveFrame(auth[nextDeliver]); nextDeliver++; }
                else break;
            }
            // 最坏情形：远端意图序列无相邻相等（本门禁刻意如此）→ 每帧预测必错、必回滚重演，
            // 迭代数 ≈ N*(D+1)。给足余量同时仍能抓真·死循环。
            if (++guard > (N + D + 5) * 8)
                throw new IllegalStateException("PREDROLLBACK 驱动未收敛（delayD=" + delayD + "）");
        }
        return s;
    }

    public static void main(String[] args) {
        InputFrame[] auth = authority();

        // ---------- ① 参照：纯锁步（D=0，无预测）----------
        PredictiveSession reference = run(SEED, auth, 0);
        long hRef = reference.worldRef().netHash();

        // ---------- ② 预测客户端（D=4，远端意图延迟到达 → 必须预测 + 回滚）----------
        PredictiveSession predicted = run(SEED, auth, D);
        long hPred = predicted.worldRef().netHash();

        // ---------- ③ 确定性重演：同输入再跑一遍预测客户端 ----------
        PredictiveSession replay = run(SEED, auth, D);
        long hReplay = replay.worldRef().netHash();

        ok("FINAL_EQUALS_REFERENCE", hPred == hRef);
        ok("ROLLBACK_HAPPENED", predicted.rollbackCount() > 0);
        ok("NO_ROLLBACK_STILL_CONVERGES", reference.rollbackCount() == 0 && hRef == hPred);
        ok("DETERMINISTIC_REPLAY", hReplay == hPred);
        ok("BASELINE_CACHED", predicted.worldRef().baselineRegenCount <= 3);

        System.out.println("  info  seed=" + Long.toHexString(SEED) + "  " + SX + "x" + SY + "x" + SZ
                + "  N=" + N + "  D=" + D);
        System.out.println("  info  h@reference(D=0) = " + Long.toHexString(hRef)
                + "   rollbackCount=" + reference.rollbackCount());
        System.out.println("  info  h@predicted(D=" + D + ") = " + Long.toHexString(hPred)
                + "   rollbackCount=" + predicted.rollbackCount());
        System.out.println("  info  h@replay          = " + Long.toHexString(hReplay));
        System.out.println("  info  baselineRegenCount(predicted) = " + predicted.worldRef().baselineRegenCount
                + "   （N=" + N + " tick 取快照，证明基线已缓存）");

        System.out.println((fail ? "PREDROLLBACK FAIL (" : "PREDROLLBACK PASS (") + props + " properties)");
        if (fail) System.exit(1);
    }
}
