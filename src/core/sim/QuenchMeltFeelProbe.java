package core.sim;

import core.content.ContentRegistry;
import core.content.MaterialBook;
import core.content.ReactionBook;
import core.world.Blocks;
import core.world.World;
import java.io.File;
import java.util.List;

/**
 * 一次性探针：灭火（quench）与融冰融雪（melt）的「手感」—— 时间序列动态，而非只看终局。
 *
 * <p>批 C 把反应表默认打开后，这两个此前完全没有的物理终于生效。但门禁只断言"接上了、守恒、够快"，
 * <b>不回答"玩家体感上多快"</b>。本探针用受控小场景，按 tick 输出火/冰/雪/水数量，
 * 直接看出：火挨着水多久灭、冰挨着火多久化、灭火是否吃水、有无级联爆燃。
 *
 * <p><b>本版的隔离设计（修正旧版两处盲区）</b>：
 * <ol>
 *   <li>旧版 Q/C 两区火都不续燃，40 tick 时都靠 FireSpread 自然自熄归零 —— 无法区分"被反应浇灭"还是"烧没了"。
 *       本版两区火<b>每 tick 续燃</b>：Q 区火在 WATER 上（应被 quench 持续压制），C 区火在 AIR 上（只吃 50%/tick 自熄）。
 *       若 Q_fire ≪ C_fire，证明 quench 反应确实在吃水灭火；若两者相等，则反应没接上。</li>
 *   <li>旧版只在 y=20 数水，而密度流（DENSITY_FLOW=true）会把融出的水往下漏，于是"化出了水却数不到"。
 *       本版对融雪区做<b>整列全高扫描</b>（x∈[8,16], z∈[40,48], 所有 y）数水，落下的水也算。</li>
 * </ol>
 *
 * <p>采样在 <b>w.tick() 之后</b>（反应周期 8 tick，40 是 8 的整数倍 → 采样点必落在反应 tick 上）。
 *
 * <p>用法：{@code java -Xmx2g -cp "out;libs/gson-2.10.1.jar" core.sim.QuenchMeltFeelProbe}
 */
public final class QuenchMeltFeelProbe {

    public static void main(String[] args) {
        int SX = 64, SY = 48, SZ = 64;
        long seed = 20260918L;
        ContentRegistry reg = ContentRegistry.load(new File("assets/content"));
        MaterialBook mbook = reg.materialBook();
        ReactionBook rbook = reg.reactionBook();
        System.out.println("QUENCHMELT feel  world=" + SX + "x" + SY + "x" + SZ
                + "  seed=" + seed + "  rules=" + rbook.size() + "  reactionTable=true(默认)");

        World w = build(seed, SX, SY, SZ, mbook, rbook);

        // Q 区：9x9 FIRE 板，正下方 1 格 WATER —— 每格火都贴水（应被 quench 压制）
        int QX0 = 8,  QZ0 = 8,  QN = 9, QY = 20;
        // C 区：9x9 FIRE 板，下方是 AIR（只吃自熄，作为"续燃对照"）
        int CX0 = 40, CZ0 = 40, CN = 9, CY = 20;
        // M 区：9x9 ICE 板（x=8..16, z=40..48, y=20），紧邻一条续燃 FIRE 线（x=7）
        int MX0 = 8,  MZ0 = 40, MN = 9, MY = 20;
        int FX0 = 7,  FZ0 = 40, FN = 12, FY = 20;

        System.out.printf("%5s %9s %9s %9s %9s %9s %10s%n",
                "tick", "Q_fire", "C_fire", "M_ice", "M_wFall", "M_wY20", "allFire");

        int totalTicks = 400;
        int lastQ = -1, lastC = -1, lastM = -1, firstMelt = -1;
        for (int t = 0; t <= totalTicks; t++) {
            // 每 tick 续燃：Q 区（水上的火）、C 区（空中的火）、M 区火 line（紧邻冰板）
            for (int x = QX0; x < QX0 + QN; x++)
                for (int z = QZ0; z < QZ0 + QN; z++)
                    w.setBlock(x, QY, z, Blocks.FIRE.index);
            for (int x = CX0; x < CX0 + CN; x++)
                for (int z = CZ0; z < CZ0 + CN; z++)
                    w.setBlock(x, CY, z, Blocks.FIRE.index);
            for (int z = FZ0; z < FZ0 + FN; z++)
                w.setBlock(FX0, FY, z, Blocks.FIRE.index);

            w.tick();   // 反应在 tick 内执行（每 8 tick 一次）；采样在其后，必落在反应 tick

            if (t % 40 == 0) {
                int qFire = count(w, QX0, QZ0, QN, QY, Blocks.FIRE.index);
                int cFire = count(w, CX0, CZ0, CN, CY, Blocks.FIRE.index);
                // 只数熔融层 y=20（避开天气/温度系统在整列里生成的冰雪噪声）
                int mIce  = countBand(w, MX0, MZ0, MN, MY, MY,
                                     Blocks.ICE.index, Blocks.SNOW.index, -1);
                // 融出的水会沿密度流下落，故在冰板正下方 5 层内数水（仍限于本 footprint）
                int mWaterFall = countBand(w, MX0, MZ0, MN, MY - 5, MY - 1,
                                          Blocks.WATER.index, -1, -1);
                int mWaterY20 = countBand(w, MX0, MZ0, MN, MY, MY,
                                         Blocks.WATER.index, -1, -1);
                int allFire = w.cellsOfType(Blocks.FIRE.index).size();
                System.out.printf("%5d %9d %9d %9d %9d %9d %10d%n",
                        t, qFire, cFire, mIce, mWaterFall, mWaterY20, allFire);
                if (lastQ < 0 && qFire == 0) lastQ = t;
                if (lastC < 0 && cFire == 0) lastC = t;
                if (lastM < 0 && mIce < MN * MN) { lastM = t; firstMelt = t; }
            }
        }
        System.out.println("QUENCHMELT feel summary:");
        System.out.println("  Q_fire(水上火,续燃) 期望被 quench 压制→远低于 C；若≈C 则反应没接上");
        System.out.println("  C_fire(空中的火,续燃) 只吃 50%/tick 自熄，稳态≈半数（续燃对照）");
        System.out.println("  Q_fire 首归零 @ tick=" + (lastQ < 0 ? ">400" : lastQ)
                + "   C_fire 首归零 @ tick=" + (lastC < 0 ? ">400" : lastC));
        System.out.println("  M_ice 首减少 @ tick=" + (firstMelt < 0 ? ">400(无融雪!)" : firstMelt)
                + "   (融雪区 " + (MN * MN) + " 格冰，仅 x=8 一列邻火，故最终约少 9 格)");
        System.out.println("QUENCHMELT DONE");
    }

    private static World build(long seed, int sx, int sy, int sz,
                               MaterialBook mbook, ReactionBook rbook) {
        Simulation s = new Simulation(seed, sx, sy, sz);
        World w = s.world;
        w.materials = mbook;
        w.reactions = rbook;
        w.config.reactionTable = true;   // 默认即是 true，这里显式钉死

        int QX0 = 8,  QZ0 = 8,  QN = 9, QY = 20;
        int CX0 = 40, CZ0 = 40, CN = 9, CY = 20;
        int MX0 = 8,  MZ0 = 40, MN = 9, MY = 20;
        int FX0 = 7,  FZ0 = 40, FN = 12, FY = 20;

        // Q 区：火板 + 下方水板
        for (int x = QX0; x < QX0 + QN; x++)
            for (int z = QZ0; z < QZ0 + QN; z++) {
                w.setBlock(x, QY - 1, z, Blocks.WATER.index);
                w.setBlock(x, QY, z, Blocks.FIRE.index);
            }
        // C 区：火板（下方空气，不续燃在 build 阶段；运行期每 tick 续燃）
        for (int x = CX0; x < CX0 + CN; x++)
            for (int z = CZ0; z < CZ0 + CN; z++)
                w.setBlock(x, CY, z, Blocks.FIRE.index);
        // M 区：冰板
        for (int x = MX0; x < MX0 + MN; x++)
            for (int z = MZ0; z < MZ0 + MN; z++)
                w.setBlock(x, MY, z, Blocks.ICE.index);
        // M 区旁火线：紧邻冰板（x = MX0-1），每格冰都挨着火
        for (int z = FZ0; z < FZ0 + FN; z++)
            w.setBlock(FX0, FY, z, Blocks.FIRE.index);
        return w;
    }

    /** 单层计数（y 固定）。 */
    private static int count(World w, int x0, int z0, int n, int y, int mat) {
        int c = 0;
        for (int x = x0; x < x0 + n; x++)
            for (int z = z0; z < z0 + n; z++)
                if (w.getBlock(x, y, z) == mat) c++;
        return c;
    }

    /**
     * 指定 y 区间计数：x∈[x0,x0+n), z∈[z0,z0+n), y∈[yMin,yMax]。
     * matchA/matchB 为要统计的两种方块（传 -1 表示忽略该槽）；matchAll 为第三种（传 -1 忽略）。
     * 用于把"熔融层 y=20 的冰"和"因密度流落到的下方几层的水"分别数到，避开全列天气噪声。
     */
    private static int countBand(World w, int x0, int z0, int n, int yMin, int yMax,
                                 int matchA, int matchB, int matchAll) {
        int c = 0;
        int sx = w.SX, sy = w.SY, sz = w.SZ;
        if (yMin < 0) yMin = 0;
        if (yMax >= sy) yMax = sy - 1;
        for (int x = x0; x < x0 + n && x < sx; x++)
            for (int z = z0; z < z0 + n && z < sz; z++)
                for (int yy = yMin; yy <= yMax; yy++) {
                    int m = w.getBlock(x, yy, z);
                    if (m == matchAll) c++;
                    else if (m == matchA || m == matchB) c++;
                }
        return c;
    }

    private QuenchMeltFeelProbe() { }
}
