package core.net;

import core.world.Player;

/**
 * N1：{@link Player.Intent} ⇄ **6 字节** 的确定性编解码 —— 联机锁步的最小输入包。
 *
 * <p><b>为什么是 6 字节</b>：锁步只同步"意图"，不同步世界。一个意图的全部语义就是
 * 类型 + 方向 + 朝向 + 方块号，所以能压到 6 字节。20 tick/s 下 ≈ 120 B/s/玩家，
 * 冗余重发 K 帧也仍是可忽略的带宽。
 *
 * <p><b>字节布局（大端，显式定序 —— 杜绝平台差异）</b>：
 * <pre>
 *   off+0  type       uint8   0=IDLE 1=MOVE 2=REPEL 3=ATTACK 4=BUILD 5=PARRY 6=EXECUTE
 *   off+1  dx         int8
 *   off+2  dz         int8
 *   off+3  yaw 高字节  uint8
 *   off+4  yaw 低字节  uint8   (yaw = 量化 uint16，0..65535 ↔ 0..360°)
 *   off+5  blockIdx   uint8
 * </pre>
 *
 * <p><b>确定性纪律</b>：本类**纯计算**——不碰 {@code World}、不碰任何 RNG、不读墙钟，
 * 因此对 {@code hashState()} 与全部门禁零影响（门禁 NETCODEC 有"惰性证明"断言）。
 *
 * <p><b>契约即断言</b>：越界值**不静默截断**，而是抛 {@link IllegalArgumentException}。
 * 静默截断会把"输入层 bug"变成"两端悄悄分叉"，那是最贵的一类 bug。
 */
public final class IntentCodec {

    /** 一个意图的编码长度。 */
    public static final int SIZE = 6;

    public static final int T_IDLE = 0;
    public static final int T_MOVE = 1;
    public static final int T_REPEL = 2;
    public static final int T_ATTACK = 3;
    public static final int T_BUILD = 4;
    public static final int T_PARRY = 5;
    public static final int T_EXECUTE = 6;

    /** dx / dz 的可编码范围（int8）。 */
    public static final int D_MIN = -128, D_MAX = 127;
    /** blockIdx 的可编码范围（uint8）。 */
    public static final int BLK_MIN = 0, BLK_MAX = 255;
    /** yaw 的可编码范围（uint16 量化）。 */
    public static final int YAW_MIN = 0, YAW_MAX = 65535;

    private IntentCodec() {}

    /** 意图类型 → 线路码。显式 switch（不依赖枚举 ordinal，防重排即改协议）。 */
    public static int codeOf(Player.Intent.Type t) {
        if (t == Player.Intent.Type.IDLE)   return T_IDLE;
        if (t == Player.Intent.Type.MOVE)   return T_MOVE;
        if (t == Player.Intent.Type.REPEL)  return T_REPEL;
        if (t == Player.Intent.Type.ATTACK) return T_ATTACK;
        if (t == Player.Intent.Type.BUILD)  return T_BUILD;
        if (t == Player.Intent.Type.PARRY)  return T_PARRY;
        if (t == Player.Intent.Type.EXECUTE) return T_EXECUTE;
        throw new IllegalArgumentException("unknown intent type: " + t);
    }

    /** 线路码 → 意图类型。未知码**必须**抛错（防"新版本混入旧客户端"静默变 IDLE）。 */
    public static Player.Intent.Type typeOf(int code) {
        switch (code) {
            case T_IDLE:   return Player.Intent.Type.IDLE;
            case T_MOVE:   return Player.Intent.Type.MOVE;
            case T_REPEL:  return Player.Intent.Type.REPEL;
            case T_ATTACK: return Player.Intent.Type.ATTACK;
            case T_BUILD:  return Player.Intent.Type.BUILD;
            case T_PARRY:  return Player.Intent.Type.PARRY;
            case T_EXECUTE: return Player.Intent.Type.EXECUTE;
            default: throw new IllegalArgumentException("bad intent type code: " + code);
        }
    }

    /** 把意图写进 {@code out[off .. off+SIZE)}。越界字段抛 IllegalArgumentException。 */
    public static void encode(Player.Intent in, byte[] out, int off) {
        validate(in);
        out[off]     = (byte) codeOf(in.type);
        out[off + 1] = (byte) in.dx;
        out[off + 2] = (byte) in.dz;
        out[off + 3] = (byte) ((in.yaw >>> 8) & 0xFF);
        out[off + 4] = (byte) (in.yaw & 0xFF);
        out[off + 5] = (byte) in.blockIdx;
    }

    /** 从 {@code in[off .. off+SIZE)} 读出一个意图。缓冲区不足抛 IllegalArgumentException。 */
    public static Player.Intent decode(byte[] in, int off) {
        require(in, off, SIZE);
        int code = in[off] & 0xFF;
        Player.Intent it = Player.Intent.of(typeOf(code));
        it.dx      = in[off + 1];                                   // int8（有符号）
        it.dz      = in[off + 2];
        it.yaw     = ((in[off + 3] & 0xFF) << 8) | (in[off + 4] & 0xFF);
        it.blockIdx = in[off + 5] & 0xFF;
        return it;
    }

    /**
     * 缓冲区边界检查（包内可见，{@link InputFrame} 复用）。
     *
     * <p><b>为什么显式检查</b>：靠 {@code ArrayIndexOutOfBoundsException} 兜底也是一种"能跑"，
     * 但它不是契约的一部分——调用方无法区分"包坏了"与"我代码写错了"。显式抛
     * {@link IllegalArgumentException} 才能让上层明确按"坏包"处理（丢帧/断开/重同步）。
     */
    static void require(byte[] buf, int off, int len) {
        if (buf == null) throw new IllegalArgumentException("buffer is null");
        if (off < 0 || len < 0 || off + len > buf.length) {
            throw new IllegalArgumentException("buffer too short: need " + len + " at " + off
                    + ", have " + buf.length);
        }
    }

    /** 越界检查（编码前）。把契约失败变成显式异常，而不是静默截断。 */
    public static void validate(Player.Intent in) {
        if (in == null) throw new IllegalArgumentException("intent is null");
        if (in.dx < D_MIN || in.dx > D_MAX) throw new IllegalArgumentException("dx out of range: " + in.dx);
        if (in.dz < D_MIN || in.dz > D_MAX) throw new IllegalArgumentException("dz out of range: " + in.dz);
        if (in.yaw < YAW_MIN || in.yaw > YAW_MAX) throw new IllegalArgumentException("yaw out of range: " + in.yaw);
        if (in.blockIdx < BLK_MIN || in.blockIdx > BLK_MAX) throw new IllegalArgumentException("blockIdx out of range: " + in.blockIdx);
        codeOf(in.type);   // 未知类型同样要爆
    }
}
