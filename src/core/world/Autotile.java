package core.world;

/**
 * 泰拉瑞亚缺口①：自动拼贴（autotile）的连通性逻辑（纯 Java，零 GL 依赖，可无头门禁）。
 *
 * <p>思路（轻量、确定性、零漂移）：不烘焙 47 格变体图集，而是<b>在网格层对「不连边的角点」做暗化</b>——
 * 同组相邻方块内部无缝、异组（或空气）边界显缝。这正是 autotile 的视觉本质，且：
 * <ul>
 *   <li>纯函数：掩码只取决于世界状态（邻居的 autotileGroup），无任何 RNG / 无 hashState 写入；</li>
 *   <li>两网格路径（逐面 {@code Game.emit} 与贪婪 {@code Chunk.emitQuad}）都调用 {@link #seam}，结果一致；</li>
 *   <li>非拼贴方块（group=0，如 LAMP/FIRE/GOLD/水/玻璃） seam 恒 1.0，渲染零变化。</li>
 * </ul>
 *
 * <p>掩码位（法线轴 na 的两个共面轴 ua/va 决定）：bit0=+ua 边连接、bit1=-ua、bit2=+va、bit3=-va。
 * 四边形的 4 个角点参数 cu/cv∈{0,1}；不连的那条边上的角点被压暗 {@link #SEAM}。</p>
 */
public final class Autotile {

    private Autotile() { }

    /** 法线轴 na(0=x,1=y,2=z) 的两个共面轴：[ua, va]。 */
    private static final int[][] INPLANE = { {1, 2}, {0, 2}, {0, 1} };

    /** 不连边角点的暗化系数（0.88 → 边界缝可见，但夜晚低环境光下不会把地表压黑）。 */
    public static final float SEAM = 0.88f;

    /** 邻居 (x,y,z) 是否与组 g 连通（同组且非空气且越界按空气）。 */
    public static boolean connects(World w, int x, int y, int z, int g) {
        if (g <= 0) return false;
        if (x < 0 || y < 0 || z < 0 || x >= w.SX || y >= w.SY || z >= w.SZ) return false;
        int bi = w.mat[x][y][z];
        if (bi == Blocks.AIR.index) return false;
        return Blocks.byIndex(bi).autotileGroup == g;
    }

    /** 4 位边掩码：bit0=+ua 1=-ua 2=+va 3=-va。na=法线轴。 */
    public static int edgeMask(World w, int bx, int by, int bz, int na) {
        int g = Blocks.byIndex(w.mat[bx][by][bz]).autotileGroup;
        if (g <= 0) return 0;
        int ua = INPLANE[na][0], va = INPLANE[na][1];
        int m = 0;
        // +ua
        int dx = (ua == 0 ? 1 : 0), dy = (ua == 1 ? 1 : 0), dz = (ua == 2 ? 1 : 0);
        if (connects(w, bx + dx, by + dy, bz + dz, g)) m |= 1;
        // -ua
        if (connects(w, bx - dx, by - dy, bz - dz, g)) m |= 2;
        // +va
        dx = (va == 0 ? 1 : 0); dy = (va == 1 ? 1 : 0); dz = (va == 2 ? 1 : 0);
        if (connects(w, bx + dx, by + dy, bz + dz, g)) m |= 4;
        // -va
        if (connects(w, bx - dx, by - dy, bz - dz, g)) m |= 8;
        return m;
    }

    /** 顶点级缝暗化系数（cu/cv∈{0,1} 的四边形角点参数）。不连边上的角点被压暗。 */
    public static float seam(World w, Blocks.Block blk, int bx, int by, int bz, int na, float cu, float cv) {
        int g = blk.autotileGroup;
        if (g <= 0) return 1.0f;
        int m = edgeMask(w, bx, by, bz, na);
        float s = 1.0f;
        if (cu > 0.5f && (m & 1) == 0) s *= SEAM;     // 右缘(+ua)不连
        if (cu < 0.5f && (m & 2) == 0) s *= SEAM;     // 左缘(-ua)不连
        if (cv > 0.5f && (m & 4) == 0) s *= SEAM;     // 上缘(+va)不连
        if (cv < 0.5f && (m & 8) == 0) s *= SEAM;     // 下缘(-va)不连
        return s;
    }

    /** 由法线向量推导法线轴（na）。nx/ny/nz 为 ±1 方向之一。 */
    public static int normalAxis(float nx, float ny, float nz) {
        if (Math.abs(nx) > 0.5f) return 0;
        if (Math.abs(ny) > 0.5f) return 1;
        return 2;
    }
}
