package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 陷坑塌落（76 · 地质/深地）：地下空腔（AIR）正上方为 SAND，且该空腔是“隧道”（有水平邻空）时，
 * 低概率塌落——把 SAND 搬到下方空腔（setBlock 搬运，质量守恒），记 "sinkhole"."collapse" event。
 * 与 SandFallSystem（SAND 下方直接是 AIR 就落）区分：本系统要求“下方是隧道式空腔”（有水平连通的空），
 * 而非单格落空；表现地下洞穴让地表沙塌陷形成陷坑。
 * 零漂移：随机仅走入参 rng；用 setBlock 搬运。
 */
public final class SinkholeSystem implements System {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    @Override public String name() { return "sinkhole"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 14;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            if (w.mat[x][y][z] != Blocks.AIR.index) continue;       // 地下空腔
            if (y + 1 >= w.SY) continue;
            if (w.mat[x][y + 1][z] != Blocks.SAND.index) continue;   // 正上方为 SAND
            boolean tunnel = false;                                 // 须为隧道（水平邻空），区别于单格落空
            for (int k = 0; k < 4; k++) {
                int bx = x + DX[k], bz = z + DZ[k];
                if (w.inBounds(bx, y, bz) && w.mat[bx][y][bz] == Blocks.AIR.index) { tunnel = true; break; }
            }
            if (!tunnel) continue;
            if (rng.nextDouble() < 0.2) {
                w.setBlock(x, y,     z, Blocks.SAND.index);          // 质量守恒搬运
                w.setBlock(x, y + 1, z, Blocks.AIR.index);
                w.log("sinkhole", "collapse", "at=" + x + "," + y + "," + z, "fall");
            }
        }
    }
}
