import core.world.Blocks;
import core.world.FaceCull;
import core.world.World;
import render.lwjgl.Chunk;

/**
 * 几何成本探针（无头）：给「优化」提供数据，而不是凭感觉。
 *
 * <p>回答两个问题：</p>
 * <ol>
 *   <li><b>透明剔除规则带来了多少额外面</b>？（旧规则只看邻居==AIR；新规则让水/玻璃不遮挡 → 水下地面等被补上）</li>
 *   <li><b>贪婪合并（greedy meshing）还能省多少顶点</b>？（它已实现且被 {code GreedyCheck} 证明逐面等价，但默认关闭）</li>
 * </ol>
 *
 * 运行：java -cp "out;libs/..." FaceCostProbe [seed]
 */
public class FaceCostProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260909L;
        final int SX = 160, SY = 112, SZ = 160;   // 真实游戏窗口尺寸
        World w = new World(seed, SX, SY, SZ);

        long oldF = 0, newF = 0, opaqueFaces = 0, transFaces = 0, seeThroughGround = 0;
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++) {
                    int bi = w.mat[x][y][z];
                    if (bi == Blocks.AIR.index) continue;
                    boolean selfTrans = Blocks.byIndex(bi).translucent;
                    for (int d = 0; d < 6; d++) {
                        int nx = x + FaceCull.DX[d], ny = y + FaceCull.DY[d], nz = z + FaceCull.DZ[d];
                        int n = FaceCull.blockAt(w, nx, ny, nz);
                        if (n == Blocks.AIR.index) oldF++;                       // 旧规则：只见空气才算暴露
                        if (!FaceCull.visible(w, bi, nx, ny, nz)) continue;
                        newF++;
                        if (selfTrans) transFaces++;
                        else {
                            opaqueFaces++;
                            if (Blocks.byIndex(n).translucent) seeThroughGround++;   // 水下地面/玻璃后内壁（新增）
                        }
                    }
                }

        long greedyQuads = 0;
        for (int cx = 0; cx < SX / 16; cx++)
            for (int cz = 0; cz < SZ / 16; cz++) greedyQuads += Chunk.quadCount(w, cx, cz);

        System.out.println("=== FaceCostProbe (seed=" + seed + ", world " + SX + "x" + SY + "x" + SZ + ") ===");
        System.out.println("旧规则(邻居==AIR) 单位面      : " + oldF);
        System.out.println("新规则(FaceCull) 单位面      : " + newF
                + "   增量 " + plus(newF, oldF) + "%");
        System.out.println("  其中 不透明面              : " + opaqueFaces);
        System.out.println("  其中 半透明面(水/玻璃)     : " + transFaces);
        System.out.println("  其中 看穿补面(水下地面等)  : " + seeThroughGround
                + "   占新增 " + plus(seeThroughGround, newF - oldF) + "%");
        System.out.println("逐面路径 顶点数 (单位面*6)   : " + (newF * 6L));
        System.out.println("贪婪合并 四边形数            : " + greedyQuads);
        System.out.println("贪婪路径 顶点数 (四边形*6)   : " + (greedyQuads * 6L)
                + "   可省 " + saving(newF, greedyQuads) + "%");
    }

    /** a 相对 b 的增量百分比。 */
    private static String plus(long a, long b) {
        return b == 0 ? "n/a" : String.format("%+.1f", (a - b) * 100.0 / b);
    }

    /** 用 a 换掉 b 能省下的百分比。 */
    private static String saving(long a, long b) {
        return a == 0 ? "n/a" : String.format("%.1f", (1.0 - (double) b / a) * 100.0);
    }
}
