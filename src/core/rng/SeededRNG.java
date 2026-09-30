package core.rng;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 确定性种子随机数（移植自 engine/rng.py 的 SeededRNG）。
 *
 * 零漂移铁律在 Java 版的落点：
 *  - 主种子 master seed 控制世界全部仿真随机性；
 *  - 任何系统的逐 tick 随机必须走 {@link #deriveStream(String)} 派生子流，
 *    子流 = SHA256(masterSeed + "|" + streamName) 的前 8 字节作为子种子；
 *  - 渲染/演出用的随机走独立的 fxRng（见 World），绝不可喂进仿真流，
 *    否则会破坏“同种子同输入逐字节复现”。
 *
 * 注：内部 PRNG 用 xorshift64（与 Python random.Random 的序列不同），
 * 但 Java 版自身确定性由本类保证——这是新版本需要守住的基线。
 */
public final class SeededRNG {

    private long state;
    public final long seed;

    public SeededRNG(long seed) {
        this.seed = seed;
        // 用 splitmix64 搅拌主种子得到初始状态，避免 seed=0 退化
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        this.state = z ^ (z >>> 31);
    }

    /** 派生一个确定性的子流 RNG（对应 Python 的 spawn(stream)）。 */
    public SeededRNG deriveStream(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((seed + "|" + name).getBytes(StandardCharsets.UTF_8));
            long child = 0;
            for (int i = 0; i < 8; i++) child = (child << 8) | (dig[i] & 0xFFL);
            return new SeededRNG(child);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private long nextLong() {
        long x = state;
        x ^= (x << 13);
        x ^= (x >>> 7);
        x ^= (x << 17);
        state = x;
        return x;
    }

    /** [0,1) 双精度浮点。 */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** [0,1) 单精度浮点。 */
    public float nextFloat() {
        return (float) nextDouble();
    }

    /** [0, n) 整数（先对非负 long 取模再转 int，避免符号位污染导致负索引）。 */
    public int nextInt(int n) {
        if (n <= 0) throw new IllegalArgumentException("n must be positive");
        return (int) ((nextLong() >>> 32) % n);
    }

    /** [a, b] 闭区间整数（含两端）。 */
    public int nextInt(int a, int b) {
        return a + nextInt(b - a + 1);
    }

    /** [a, b) 浮点。 */
    public double uniform(double a, double b) {
        return a + (b - a) * nextDouble();
    }

    /** 从列表中确定性选一个。 */
    public <T> T choice(List<T> seq) {
        return seq.get(nextInt(seq.size()));
    }

    /** 复位到主种子初始状态（对应 Python reset()）。 */
    public void reset() {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        this.state = z ^ (z >>> 31);
    }

    /** 当前内部状态（仅用于调试/确定性断言）。 */
    public long state() { return state; }

    /** 恢复内部状态（仅用于存档读档，确定性恢复——读取即恢复，无随机源）。 */
    public void setState(long s) { this.state = s; }
}
