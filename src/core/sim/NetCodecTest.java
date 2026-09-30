package core.sim;

import core.net.InputFrame;
import core.net.IntentCodec;
import core.world.Player;

/**
 * NETCODEC 门禁（N1：联机输入编解码）。
 *
 * <p><b>为什么需要它</b>：锁步联机只同步"意图"，所以**输入编解码是整条链路的地基**——
 * 一旦它悄悄丢精度或改顺序，两端就会在几秒后分叉，而症状（某个怪卡住）离根因很远。
 * 四道基线门禁只跑 {@code World.tick()}，看不见编解码层。
 *
 * <p><b>断言的性质</b>（不是"能跑"）：
 * <ol>
 *   <li><b>协议稳定</b>：类型码是**显式契约**（IDLE=0..BUILD=4），不随枚举重排而变。</li>
 *   <li><b>无损往返</b>：全部类型 + 全部字段边界值 encode→decode **逐字段相等**；
 *       且写出**不越界**（前后哨兵字节不被改写）。</li>
 *   <li><b>拒绝而非截断</b>：越界值 / 未知类型码 / 重复玩家 id / 降序 id / 长度不符
 *       **必须抛异常**。静默截断会把"输入层 bug"变成"两端悄悄分叉"。</li>
 *   <li><b>逐字节确定性</b>：同一逻辑帧 pack 两次 → 字节完全相同。</li>
 *   <li><b>惰性证明</b>：跑 2 万次编解码后 {@code world.hashState()} **逐字节不变**
 *       —— 证明编解码不碰世界/不碰 RNG。</li>
 *   <li><b>防假绿</b>：正样本对照（人为造一个坏缓冲，检测必须真的报错）+ 工作计数器非零
 *       （防"循环被优化掉 = 什么都没测"）。</li>
 * </ol>
 */
public final class NetCodecTest {

    private static int props = 0;
    private static boolean fail = false;

    private static void ok(String name, boolean cond) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name);
        if (cond) props++; else fail = true;
    }

    private interface Probe { void run(); }

    /** 必须抛 IllegalArgumentException：抛别的或静默通过都算 FAIL。 */
    private static boolean throwsIae(Probe p) {
        try { p.run(); return false; }
        catch (IllegalArgumentException e) { return true; }
        catch (RuntimeException e) { return false; }
    }

    private static boolean rt(Player.Intent in) {
        byte[] b = new byte[IntentCodec.SIZE];
        IntentCodec.encode(in, b, 0);
        Player.Intent out = IntentCodec.decode(b, 0);
        return out.type == in.type && out.dx == in.dx && out.dz == in.dz
                && out.yaw == in.yaw && out.blockIdx == in.blockIdx;
    }

    public static void main(String[] args) {
        Player.Intent.Type[] TYPES = Player.Intent.Type.values();

        // ---------- 1. 协议稳定 ----------
        ok("TYPE_CODE_STABLE",
                IntentCodec.codeOf(Player.Intent.Type.IDLE) == 0
             && IntentCodec.codeOf(Player.Intent.Type.MOVE) == 1
             && IntentCodec.codeOf(Player.Intent.Type.REPEL) == 2
             && IntentCodec.codeOf(Player.Intent.Type.ATTACK) == 3
             && IntentCodec.codeOf(Player.Intent.Type.BUILD) == 4
             && IntentCodec.codeOf(Player.Intent.Type.PARRY) == 5
             && IntentCodec.codeOf(Player.Intent.Type.EXECUTE) == 6
             && IntentCodec.T_IDLE == 0 && IntentCodec.T_BUILD == 4);

        boolean mapOk = true;
        for (Player.Intent.Type t : TYPES) mapOk &= IntentCodec.typeOf(IntentCodec.codeOf(t)) == t;
        ok("TYPE_MAP_ROUNDTRIP", mapOk && TYPES.length == 7);

        // ---------- 2. 无损往返 + 不越界 ----------
        boolean allOk = true;
        for (Player.Intent.Type t : TYPES) {
            Player.Intent i = Player.Intent.of(t);
            i.dx = 1; i.dz = -1; i.blockIdx = 7; i.yaw = 40000;
            allOk &= rt(i);
        }
        ok("ROUNDTRIP_ALL_TYPES", allOk);

        // {dx, dz, blockIdx, yaw} —— 全部字段的两端值组合
        int[][] ex = {
            {IntentCodec.D_MIN, IntentCodec.D_MIN, IntentCodec.BLK_MIN, IntentCodec.YAW_MIN},
            {IntentCodec.D_MAX, IntentCodec.D_MAX, IntentCodec.BLK_MAX, IntentCodec.YAW_MAX},
            {0, 0, 0, 0x8000},
            {-1, 1, 255, 1},
        };
        boolean extOk = true;
        for (int[] e : ex) {
            Player.Intent i = Player.Intent.of(Player.Intent.Type.MOVE);
            i.dx = e[0]; i.dz = e[1]; i.blockIdx = e[2]; i.yaw = e[3];
            extOk &= rt(i);
        }
        ok("ROUNDTRIP_EXTREMES", extOk);

        byte[] buf = new byte[IntentCodec.SIZE + 4];
        for (int k = 0; k < buf.length; k++) buf[k] = 0x7F;
        Player.Intent probe = Player.Intent.move(1, -1);
        probe.yawDeg(123.4f);
        IntentCodec.encode(probe, buf, 2);
        boolean sentinel = true;
        for (int k = 0; k < 2; k++) if (buf[k] != 0x7F) sentinel = false;
        for (int k = 2 + IntentCodec.SIZE; k < buf.length; k++) if (buf[k] != 0x7F) sentinel = false;
        ok("SIZE_6_NO_OVERWRITE", IntentCodec.SIZE == 6 && sentinel);

        Player.Intent y = Player.Intent.idle().yawDeg(-90f);
        ok("YAW_DEG_QUANTIZE", y.yaw == 49152 && Math.abs(y.yawDegrees() - 270f) < 0.01f);

        // ---------- 3. 拒绝而非截断 ----------
        final Player.Intent bad1 = Player.Intent.move(128, 0);
        final Player.Intent bad2 = Player.Intent.move(0, -129);
        final Player.Intent bad3 = Player.Intent.idle();       // yaw 越界
        bad3.yaw = 65536;
        final Player.Intent bad4 = Player.Intent.build(256);
        ok("REJECT_OUT_OF_RANGE",
                throwsIae(new Probe() { public void run() { IntentCodec.validate(bad1); } })
             && throwsIae(new Probe() { public void run() { IntentCodec.validate(bad2); } })
             && throwsIae(new Probe() { public void run() { IntentCodec.validate(bad3); } })
             && throwsIae(new Probe() { public void run() { IntentCodec.validate(bad4); } }));

        final byte[] badType = new byte[IntentCodec.SIZE];
        badType[0] = 9;                                        // 未知类型码
        ok("REJECT_UNKNOWN_TYPE_CODE",
                throwsIae(new Probe() { public void run() { IntentCodec.decode(badType, 0); } })
             && throwsIae(new Probe() { public void run() { IntentCodec.typeOf(9); } }));

        final byte[] shortBuf = new byte[IntentCodec.SIZE - 1];
        final byte[] shortFrame = new byte[InputFrame.HEADER];           // 帧头称 3 人，实际无载荷
        shortFrame[5] = 3;
        final byte[] tinyFrame = new byte[2];
        ok("REJECT_TRUNCATED",
                throwsIae(new Probe() { public void run() { IntentCodec.decode(shortBuf, 0); } })
             && throwsIae(new Probe() { public void run() { IntentCodec.validate(null); } })
             && throwsIae(new Probe() { public void run() { InputFrame.unpack(shortFrame, 0); } })
             && throwsIae(new Probe() { public void run() { InputFrame.unpack(tinyFrame, 0); } }));

        // ---------- 4. 整帧往返 + 逐字节确定性 ----------
        int[] ids = {3, 17, 512};
        Player.Intent[] its = {
            Player.Intent.move(1, 0).yawDeg(10f),
            Player.Intent.attack().yawDeg(200f),
            Player.Intent.build(5),
        };
        InputFrame f = new InputFrame(123456, ids, its);
        byte[] fb = new byte[f.byteSize()];
        f.pack(fb, 0);
        InputFrame g = InputFrame.unpack(fb, 0);

        boolean frameOk = g.tick == f.tick && g.playerIds.length == 3;
        for (int i = 0; i < 3 && frameOk; i++) {
            Player.Intent a = f.intents[i], b = g.intents[i];
            frameOk &= g.playerIds[i] == ids[i]
                    && a.type == b.type && a.dx == b.dx && a.dz == b.dz
                    && a.yaw == b.yaw && a.blockIdx == b.blockIdx;
        }
        ok("FRAME_ROUNDTRIP_3P", frameOk && f.byteSize() == 6 + 8 * 3);

        byte[] fb2 = new byte[f.byteSize()];
        f.pack(fb2, 0);
        byte[] fb3 = new byte[f.byteSize()];
        new InputFrame(123456, new int[]{3, 17, 512}, new Player.Intent[]{
            Player.Intent.move(1, 0).yawDeg(10f),
            Player.Intent.attack().yawDeg(200f),
            Player.Intent.build(5)}).pack(fb3, 0);
        boolean bytesEq = true;
        for (int i = 0; i < fb.length; i++) bytesEq &= fb[i] == fb2[i] && fb[i] == fb3[i];
        ok("FRAME_BYTE_DETERMINISTIC", bytesEq && IntentCodec.SIZE == 6);

        ok("FRAME_INTENT_OF", g.intentOf(17) != null && g.intentOf(17).type == Player.Intent.Type.ATTACK
                && g.intentOf(99) == null);

        // ---------- 5. 整帧拒绝 ----------
        final int[] dupIds = {5, 5};
        final int[] descIds = {9, 3};
        final Player.Intent[] two = {Player.Intent.idle(), Player.Intent.idle()};
        final Player.Intent[] one = {Player.Intent.idle()};
        ok("FRAME_REJECT_ORDER_AND_LEN",
                throwsIae(new Probe() { public void run() { new InputFrame(0, dupIds, two); } })
             && throwsIae(new Probe() { public void run() { new InputFrame(0, descIds, two); } })
             && throwsIae(new Probe() { public void run() { new InputFrame(0, dupIds, one); } })
             && throwsIae(new Probe() { public void run() { new InputFrame(-1, dupIds, two); } })
             && InputFrame.byteSize(0) == 6 && InputFrame.byteSize(3) == 30);

        // ---------- 6. 惰性证明（不碰世界/RNG）----------
        Simulation sim = new Simulation(20260914L, 64, 40, 64);
        for (int t = 0; t < 60; t++) sim.world.tick();
        long h0 = sim.world.hashState();

        int work = 0;
        byte[] wb = new byte[InputFrame.byteSize(3)];
        for (int i = 0; i < 20000; i++) {
            Player.Intent it = Player.Intent.of(Player.Intent.Type.values()[i % TYPES.length]);
            it.dx = (i % 255) - 127;
            it.dz = (i % 201) - 100;
            it.yaw = (i * 7919) & 0xFFFF;
            it.blockIdx = i % 256;
            IntentCodec.encode(it, wb, 0);
            Player.Intent back = IntentCodec.decode(wb, 0);
            work += back.dx + back.dz + back.yaw + back.blockIdx;
            Player.Intent[] arr = {back, it, Player.Intent.idle()};
            InputFrame fr = new InputFrame(i, new int[]{0, 1, 2}, arr);
            byte[] fb4 = new byte[fr.byteSize()];
            fr.pack(fb4, 0);
            work += InputFrame.unpack(fb4, 0).intentOf(1).dx;
        }
        long h1 = sim.world.hashState();
        ok("INERT_NO_DRIFT", h0 == h1 && work != 0);

        System.out.println((fail ? "NETCODEC FAIL (" : "NETCODEC PASS (") + props + " properties)");
        if (fail) System.exit(1);
    }
}
