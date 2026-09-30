// 探针：统计初始地形里 WATER 数量，并统计有多少列地形高度 h < waterLevel（应成湖却没成）。
import core.world.Blocks;
import core.world.World;

public class ProbeWater {
    public static void main(String[] a) {
        int SX = Integer.parseInt(a.length > 0 ? a[0] : "96");
        int SY = Integer.parseInt(a.length > 1 ? a[1] : "48");
        int SZ = Integer.parseInt(a.length > 2 ? a[2] : "96");
        long seed = a.length > 3 ? Long.parseLong(a[3]) : 20260909L;
        World w = new World(seed, SX, SY, SZ);
        int water = 0, below = 0, sandSurf = 0, grassSurf = 0;
        int waterLevel = SY / 3;
        for (int x = 0; x < SX; x++)
            for (int z = 0; z < SZ; z++) {
                int h = -1;
                for (int y = SY - 1; y >= 0; y--)
                    if (Blocks.byIndex(w.mat[x][y][z]).solid) { h = y; break; }
                if (h < 0) continue;
                if (h < waterLevel) below++;
                int top = w.mat[x][h][z];
                if (top == Blocks.SAND.index) sandSurf++;
                if (top == Blocks.GRASS.index) grassSurf++;
                for (int y = 0; y < SY; y++)
                    if (w.mat[x][y][z] == Blocks.WATER.index) water++;
            }
        System.out.println("world=" + SX + "x" + SY + "x" + SZ + " seed=" + seed
                + " waterLevel=" + waterLevel);
        System.out.println("initial WATER blocks = " + water);
        System.out.println("columns with surface h < waterLevel (should be lakes) = " + below);
        System.out.println("surface SAND=" + sandSurf + " GRASS=" + grassSurf);
    }
}
