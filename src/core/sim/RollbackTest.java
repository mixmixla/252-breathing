package core.sim;

import core.world.World;
import core.world.Player;

import java.io.ByteArrayOutputStream;

/**
 * ROLLBACK 门禁（N2-2 原地恢复 + N2-3 回滚黄金判据）。
 *
 * <p><b>这是整套回滚机制唯一的正确性证明。</b> 它一次性覆盖三类故障：
 * ① 快照不完整（读回后状态少了东西）；② 原地恢复没把旧状态清干净（"叠写"而非"覆盖"）；
 * ③ 重演不等价（tick 不可重入 / 系统实例状态缺失 / 输入定序错误）。
 *
 * <p><b>黄金判据（原文）</b>：
 * 「跑 N → 回滚到 RB → 用**修正输入**跑到 N」 == 「一开始就用修正输入跑到 N」。
 *
 * <p>本门禁的实现方式比字面判据更狠一层：回滚世界在快照之后**先故意用错误输入跑偏**，
 * 再原地恢复 —— 所以它同时证明"恢复能把已经走歪的世界**拽回**快照状态"。
 *
 * <p><b>断言的性质</b>：
 * <ol>
 *   <li>{@code IN_PLACE_RESTORE_EXACT}：原地恢复后 hash 与快照时**逐字节一致**（且 tick 回退到位）。</li>
 *   <li>{@code RESTORE_UNDOES_DIVERGENCE}：恢复前 hash **确实**已经跑偏（防假绿：否则本测试什么都没证明）。</li>
 *   <li>{@code ROLLBACK_EQUALS_DIRECT}：回滚重演结果 == 从头就用修正输入跑的结果（**黄金判据**）。</li>
 *   <li>{@code REJECT_MISMATCHED_SNAPSHOT}：seed/尺寸不符的快照必须**响亮拒绝**，不得静默接受。</li>
 *   <li>{@code INERT_RESTORE}：快照/恢复动作本身不改动仿真状态（无副作用）。</li>
 * </ol>
 */
public final class RollbackTest {

    private static final int SX = 160, SY = 112, SZ = 160;
    private static final int N = 70;      // 总 tick 数
    private static final int RB = 60;     // 回滚点

    private static int props = 0;
    private static boolean fail = false;

    private static void ok(String name, boolean cond) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name);
        if (cond) props++; else fail = true;
    }

    /** 确定性输入：同一 tick 的输入只由 tick 与"是否修正"决定（与到达顺序无关，便于重演）。 */
    private static void setInput(World w, int tick, boolean corrected) {
        int dx = ((tick / 3) % 3) - 1;
        int dz = ((tick / 5) % 3) - 1;
        if (corrected && tick > RB) { dx = -dx; dz = (dz == 0) ? 1 : -dz; }
        w.player.setIntent(Player.Intent.move(dx, dz));
    }

    private static byte[] snapOf(World w) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        w.save(bo);
        return bo.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        final long SEED = 0x5A17C0DEL;

        // ---------- 参照：从头跑 1..RB（未修正）→ 再跑 RB+1..N（修正）----------
        World direct = new Simulation(SEED, SX, SY, SZ).world;
        for (int k = 1; k <= RB; k++) { setInput(direct, k, false); direct.tick(); }
        for (int k = RB + 1; k <= N; k++) { setInput(direct, k, true); direct.tick(); }
        long hDirect = direct.hashState();

        // ---------- 回滚世界：跑 1..RB → 快照 → 故意跑偏 → 原地恢复 → 重演修正输入 ----------
        World roll = new Simulation(SEED, SX, SY, SZ).world;
        for (int k = 1; k <= RB; k++) { setInput(roll, k, false); roll.tick(); }
        long hAtSnapshot = roll.hashState();
        int tickAtSnapshot = roll.tick;

        // ③ 惰性：取快照本身不得改动仿真状态
        byte[] snap = snapOf(roll);
        ok("INERT_RESTORE", roll.hashState() == hAtSnapshot && roll.tick == tickAtSnapshot);

        // 故意用「错误输入」继续跑，让世界走偏
        for (int k = RB + 1; k <= N; k++) { setInput(roll, k, false); roll.tick(); }
        long hDiverged = roll.hashState();
        ok("RESTORE_UNDOES_DIVERGENCE", hDiverged != hAtSnapshot);

        // ---- 原地恢复（N2-2）----
        String mismatchErr = null;
        try {
            // ④ seed 不符必须响亮拒绝（用一个别 seed 的快照去打）
            byte[] foreign = snapOf(new Simulation(SEED ^ 0x9E37L, SX, SY, SZ).world);
            roll.restoreInPlace(foreign);
        } catch (Throwable t) {
            mismatchErr = String.valueOf(t.getMessage());
        }
        ok("REJECT_MISMATCHED_SNAPSHOT", mismatchErr != null);

        roll.restoreInPlace(snap);
        long hRestored = roll.hashState();
        boolean exact = hRestored == hAtSnapshot && roll.tick == tickAtSnapshot;
        ok("IN_PLACE_RESTORE_EXACT", exact);

        // ---- 重演修正输入（N2-3）----
        for (int k = RB + 1; k <= N; k++) { setInput(roll, k, true); roll.tick(); }
        long hRoll = roll.hashState();
        ok("ROLLBACK_EQUALS_DIRECT", hRoll == hDirect);

        System.out.println("  info  seed=" + Long.toHexString(SEED) + "  " + SX + "x" + SY + "x" + SZ
                + "  N=" + N + " RB=" + RB + "  systems=" + roll.systemCount());
        System.out.println("  info  snapshot   = " + snap.length + " B (" + (snap.length / 1024) + " KB)");
        System.out.println("  info  h@snapshot = " + Long.toHexString(hAtSnapshot));
        System.out.println("  info  h@diverged = " + Long.toHexString(hDiverged)
                + "   （恢复前确实已走偏）");
        System.out.println("  info  h@restored = " + Long.toHexString(hRestored)
                + "   （应 == h@snapshot）");
        System.out.println("  info  h@rollback = " + Long.toHexString(hRoll));
        System.out.println("  info  h@direct   = " + Long.toHexString(hDirect));
        if (mismatchErr != null)
            System.out.println("  info  拒绝信息   = " + mismatchErr);

        System.out.println((fail ? "ROLLBACK FAIL (" : "ROLLBACK PASS (") + props + " properties)");
        if (fail) System.exit(1);
    }
}
