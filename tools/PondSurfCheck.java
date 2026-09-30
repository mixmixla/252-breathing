import core.world.World;
import core.world.Blocks;

import java.util.Set;

/**
 * 直接对比 Pond 两种"每列最顶实心"构建法在 DETERMINISM 同参数世界(64x40x64/123456789)逐 tick 下是否恒等：
 *   A 法（旧）：遍历 nonAirCells，取同列末尾 solid → surfA
 *   B 法（新）：遍历 surfaceCells，向下扫描到首个 solid → surfB
 * 任一 tick 出现 surfA != surfB 即说明 Pond 行为会分叉（DETERMINISM 会翻红）。
 */
public class PondSurfCheck {
    public static void main(String[] a) {
        int SX = 64, SY = 40, SZ = 64, TICKS = 240;
        long SEED = 123456789L;
        World w = new World(SEED, SX, SY, SZ);
        int diffTick = -1, diffCount = 0;
        for (int t = 0; t < TICKS; t++) {
            w.tick();
            int[][] surfA = new int[SX][SZ];
            int[][] surfB = new int[SX][SZ];
            for (int x = 0; x < SX; x++) for (int z = 0; z < SZ; z++) { surfA[x][z] = -1; surfB[x][z] = -1; }
            // A: nonAirCells
            for (int[] c : w.nonAirCells()) {
                if (Blocks.byIndex(w.mat[c[0]][c[1]][c[2]]).solid) surfA[c[0]][c[2]] = c[1];
            }
            // B: surfaceCells
            for (long key : w.surfaceCells) {
                int x = (int) ((key >> 20) & 0x3FFL);
                int y = (int) ((key >> 10) & 0x3FFL);
                int z = (int) (key & 0x3FFL);
                while (y >= 0 && !Blocks.byIndex(w.mat[x][y][z]).solid) y--;
                if (y >= 0 && y > surfB[x][z]) surfB[x][z] = y;
            }
            int dc = 0;
            for (int x = 0; x < SX; x++) for (int z = 0; z < SZ; z++)
                if (surfA[x][z] != surfB[x][z]) dc++;
            if (dc > 0) { diffCount++; if (diffTick < 0) diffTick = t; }
        }
        System.out.println("PondSurfCheck: ticksWithDiff=" + diffCount
                + (diffTick >= 0 ? " firstDiffTick=" + diffTick : "")
                + " -> " + (diffCount == 0 ? "SURF A==B (pond behavior identical)" : "SURF DIVERGED"));
    }
}
