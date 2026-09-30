package core.sim;

import core.world.Player;
import core.world.World;

/**
 * NETDESYNC 门禁（N3-0：**desync 检测用的宽哈希 `netHash`**）。
 *
 * <p><b>为什么需要它</b>：{@code hashState()} 是**故意做窄**的（只盖 mat/mass/tick/rng/skills/
 * memory/builtMass，为了零漂移门禁的稳定）—— 实体层、8 个系统对象的标量、Player 全字段、
 * 系统实例私有状态都**不在里面**。联机时两端在这些字段上分叉，{@code hashState()} 看不见，
 * 玩家却看得见（"他的 NPC 怎么跟我的不一样"）。
 *
 * <p><b>实现选择：与快照**同源**。</b> {@code netHash = hashState() + FNV(StateCodec.encode(world))
 * + FNV(各系统 encode)}。编码覆盖什么，这里就检测什么 —— **新增字段自动纳入，不存在第二份名单**。
 * 偷懒写"再挑几个字段哈希一下"必然重蹈 hashState 做窄的覆辙。
 *
 * <p><b>断言的性质</b>：
 * <ol>
 *   <li><b>同源一致</b>：同 seed + 同输入的两世界，netHash 逐字节一致。</li>
 *   <li><b>宽于 hashState</b>：只改一个 {@code hashState()} 看不见的字段（如 {@code civ.research}），
 *       netHash 必须变 —— 这条证明它真的更宽，而不是把 hashState 又包了一层。</li>
 *   <li><b>敏感</b>：改一格方块 → netHash 必须变。</li>
 *   <li><b>可区分种子</b>：不同 seed → 不同 netHash。</li>
 *   <li><b>惰性 + 确定性</b>：调用 netHash 不改变 {@code hashState()}（无副作用），重复调用同值。</li>
 *   <li><b>成本预算</b>：单次 ≤ {@link #BUDGET_MS}（它不可每 tick 调用，预算钉住这个事实）。</li>
 * </ol>
 */
public final class NetDesyncTest {

    private static final long SEED = 0xD3511C0DL;   // "DESINC" 谐音的合法十六进制
    private static final int TICKS = 30;
    private static final long BUDGET_MS = 300;

    private static int props = 0;
    private static boolean fail = false;

    private static void ok(String name, boolean cond) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name);
        if (cond) props++; else fail = true;
    }

    private static void drive(Simulation s, int ticks) {
        for (int t = 0; t < ticks; t++) {
            if (t % 5 == 0) s.world.player.setIntent(Player.Intent.move(1, 0));
            s.world.tick();
        }
    }

    public static void main(String[] args) throws Exception {
        Simulation a = new Simulation(SEED, 160, 112, 160);
        Simulation b = new Simulation(SEED, 160, 112, 160);
        drive(a, TICKS); drive(b, TICKS);

        long t0 = System.nanoTime();
        long hA = a.world.netHash();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long hB = b.world.netHash();

        // ① 同源一致
        ok("SAME_SEED_SAME_INPUT_EQUAL", hA == hB);

        // ② 宽于 hashState：改一个 hashState 看不见的字段
        // ⚠️ 必须用**整数**字段做往返：浮点 `x+1f-1f` 不保证回到原值（实测 0.7f → 0.70000005f，
        //    因为 0.7 本就不能被 float 精确表示）—— 那不是 netHash 的 bug，恰恰证明它连 1 ulp 都抓得到。
        b.world.civ.temples += 1;
        boolean narrowSame = a.world.hashState() == b.world.hashState();
        boolean wideDiff = a.world.netHash() != b.world.netHash();
        ok("WIDER_THAN_HASHSTATE", narrowSame && wideDiff);
        b.world.civ.temples -= 1;                         // 还原（后续用例要在同一起点上比较）
        ok("RESTORED", a.world.netHash() == b.world.netHash());

        // ③ 敏感：改一格方块
        int sx = 80, sy = 60, sz = 80;
        b.world.setBlock(sx, sy, sz, core.world.Blocks.STONE.index);
        ok("SENSITIVE_TO_ONE_CELL", a.world.netHash() != b.world.netHash());

        // ④ 可区分种子
        Simulation c = new Simulation(SEED ^ 0x9E37L, 160, 112, 160);
        drive(c, TICKS);
        ok("DISTINGUISHES_SEEDS", a.world.netHash() != c.world.netHash());

        // ⑤ 惰性 + 确定性
        long before = a.world.hashState();
        long h1 = a.world.netHash();
        long h2 = a.world.netHash();
        ok("INERT_AND_DETERMINISTIC", before == a.world.hashState() && h1 == h2 && h1 == hA);

        // ⑥ 成本预算
        ok("COST_BUDGET", ms <= BUDGET_MS);

        System.out.println("  info  netHash(A) = " + String.format("%016x", hA)
                + "   单次耗时 " + ms + " ms（预算 " + BUDGET_MS + "）");
        System.out.println("  info  不可每 tick 调用 —— 每 20~40 tick 校验一次即可"
                + "（desync 晚 1~2 秒发现完全可接受）");

        System.out.println((fail ? "NETDESYNC FAIL (" : "NETDESYNC PASS (") + props + " properties)");
        if (fail) System.exit(1);
    }
}
