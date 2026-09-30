package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 深岩晶簇（77 · 地质/深地）：深岩（y 较低，水面以下）STONE 邻腔（AIR 邻格）时低概率析出发光矿，
 * 把该邻腔 AIR 置 LAMP 微光，记 "crystal"."grow" event，表现深层晶簇。
 * 与 GeodeSystem（封闭空腔填 ORE）区分：本系统是“岩壁（STONE）朝外析出（LAMP）发光矿”，不要求封闭空腔；
 * 与 LAMP 作暖炉/灯塔（RuinsSystem/BeaconSystem）区分：本系统由地质过程自发析出，非玩家繁荣社会产物。
 * 零漂移：随机仅走入参 rng；用 setBlock 演化。
 */
public final class CrystalSystem implements System {
    private static final int[][] D = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Override public String name() { return "crystal"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int deep = w.SY / 3;                  // 深岩层（水面以下）
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(deep), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.STONE.index) continue;
            for (int[] o : D) {               // 岩壁邻腔则析出发光晶簇（第十二批：CRYSTAL_CLUSTER，自带冷辉）
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                if (!w.inBounds(nx, ny, nz)) continue;
                if (w.mat[nx][ny][nz] == Blocks.AIR.index) {
                    if (rng.nextDouble() < 0.03) {
                        w.setBlock(nx, ny, nz, Blocks.CRYSTAL_CLUSTER.index);   // 第十二批：改用晶簇（原为 LAMP 占位）
                        w.log("crystal", "grow", "at=" + nx + "," + ny + "," + nz, "glow");
                    }
                    break;
                }
            }
        }
    }
}
