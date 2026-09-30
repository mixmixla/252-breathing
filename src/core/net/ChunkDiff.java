package core.net;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * N2-1：**块级稀疏差分** —— 相对「seed 确定性地形基线」的差集。
 *
 * <p><b>为什么需要它</b>：N2-0 之后快照仍是 22 MB，根因不在反射编解码器（{@code mat}/{@code mass}
 * 已被 {@link StateCodec#SKIP} 排除），而在 {@code save()} 里**显式**把每个被编辑块整块落盘
 * （16×112×16 格 × (int mat + float mass) = 229 KB/块）。
 *
 * <p><b>实测依据</b>（160×112×160，92 系统，120 tick）：与基线不同的格数只有
 * <b>28,699 / 2,867,200 = 1.0009%</b>，且 100 个块**每块都有**差异（18~406 格，无整块大改离群）
 * → 差异高度稀疏且均匀，稀疏差分是最优解。实测压缩 <b>21.9 MB → ~259 KB（约 84x）</b>。
 *
 * <p><b>格式</b>（大端，SoA 排列，便于顺序写）：
 * <pre>
 *   long  key      全局块坐标 (gcx&lt;&lt;32 | gcz)
 *   int   n        差异格数
 *   int   idx[n]   块内线性下标 (lx*SY + y)*CHUNK + lz
 *   byte  mat[n]   方块索引（uint8；Blocks.count() 必须 ≤256，超限**响亮报错**而非静默截断）
 *   float mass[n]  质量
 * </pre>
 * 每格 9 字节。
 *
 * <p><b>基线契约</b>：{@code baseline + diff == 存档时的真实状态}。基线由
 * {@code generateWindowTerrain()} 在**同一窗口原点**上全窗生成（与读档路径
 * {@code new World(...)} + {@code restoreWindowOrigin(...)} 完全同源）。
 *
 * <p>⚠️ **为什么不能"就地为单个块重生成基线"**：{@code Megalith.fillChunk} 的选址/地面高度
 * （{@code groundY}/{@code steep}）会**读取邻列** {@code mat}，所以"原始地形"只在整个窗口
 * **按固定序（cx 外层 / cz 内层）全量生成**时才可复现。单独重生一块时，邻列可能已被系统改写
 * → 得到的是**另一个**基线，会让差异集错漏 → 读档后静默走偏。故基线一律取**全窗**生成结果。
 */
public final class ChunkDiff {

    /** 全局块坐标（低 32 位为 gcz）。 */
    public final long key;
    /** 差异格数。 */
    public final int n;
    /** 块内线性下标（升序）。 */
    public final int[] idx;
    /** 方块索引（与 {@link #idx} 对位）。 */
    public final int[] mat;
    /** 质量（与 {@link #idx} 对位）。 */
    public final float[] mass;

    public ChunkDiff(long key, int n, int[] idx, int[] mat, float[] mass) {
        if (n < 0) throw new IllegalArgumentException("n < 0");
        if (idx.length != n || mat.length != n || mass.length != n)
            throw new IllegalArgumentException("ChunkDiff 数组长度不一致");
        this.key = key; this.n = n; this.idx = idx; this.mat = mat; this.mass = mass;
    }

    /** 序列化载荷字节数（不含 key/count 的 12 字节头部）。 */
    public int payloadBytes() { return n * (4 + 1 + 4); }

    public static void write(ChunkDiff d, DataOutput out) throws IOException {
        out.writeLong(d.key);
        out.writeInt(d.n);
        for (int i = 0; i < d.n; i++) out.writeInt(d.idx[i]);
        for (int i = 0; i < d.n; i++) {
            int m = d.mat[i];
            if (m < 0 || m > 255)
                throw new IOException("ChunkDiff：方块索引 " + m + " 超出 uint8 —— "
                        + "Blocks.count() 已 >256，请把 mat 字段扩为 2 字节（不要静默截断）");
            out.writeByte(m);
        }
        for (int i = 0; i < d.n; i++) out.writeFloat(d.mass[i]);
    }

    public static ChunkDiff read(DataInput in) throws IOException {
        long key = in.readLong();
        int n = in.readInt();
        if (n < 0) throw new IOException("ChunkDiff：负的差异格数 " + n);
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = in.readInt();
        int[] mat = new int[n];
        for (int i = 0; i < n; i++) mat[i] = in.readByte() & 0xFF;
        float[] mass = new float[n];
        for (int i = 0; i < n; i++) mass[i] = in.readFloat();
        return new ChunkDiff(key, n, idx, mat, mass);
    }
}
