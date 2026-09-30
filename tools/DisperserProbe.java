import core.world.Blocks;
import core.world.World;
import core.sim.Simulation;

/**
 * 散布者家族收敛性测量探针（headless，只读）。
 *
 * <p>目的：量化 LEAF/MOSS/ICE/FLOWER 等"散布者"类型的方块计数随时间的增长曲线，
 * 确认它们是否<b>无界增长</b>（世界最终被填满 ⇒ 所有系统变贵 + 单色退化），
 * 并为"派生式全局上限"提供实测基线。
 *
 * <p>用法：{@code java -cp out;LIBS DisperserProbe [SX SY SZ T step seed]}
 * 默认 96 48 96 12000 2000 20260929。
 */
public final class DisperserProbe {
    public static void main(String[] args) {
        int SX   = args.length > 0 ? Integer.parseInt(args[0]) : 96;
        int SY   = args.length > 1 ? Integer.parseInt(args[1]) : 48;
        int SZ   = args.length > 2 ? Integer.parseInt(args[2]) : 96;
        int T    = args.length > 3 ? Integer.parseInt(args[3]) : 12000;
        int step = args.length > 4 ? Integer.parseInt(args[4]) : 2000;
        long seed= args.length > 5 ? Long.parseLong(args[5]) : 20260929L;

        int[] TYPES = { Blocks.LEAF.index, Blocks.MOSS.index, Blocks.ICE.index, Blocks.FLOWER.index,
                        Blocks.WOOD.index, Blocks.ASH.index, Blocks.GRASS.index, Blocks.DIRT.index,
                        Blocks.STONE.index, Blocks.WATER.index };
        String[] NAMES = { "LEAF", "MOSS", "ICE", "FLOWER", "WOOD", "ASH", "GRASS", "DIRT", "STONE", "WATER" };

        Simulation sim = new Simulation(seed, SX, SY, SZ);
        World w = sim.world;

        System.out.printf("DisperserProbe SX=%d SY=%d SZ=%d seed=%d T=%d step=%d%n", SX, SY, SZ, seed, T, step);
        System.out.printf("vol=%d  surface(SX*SZ)=%d%n", (long) SX * SY * SZ, (long) SX * SZ);

        StringBuilder h = new StringBuilder("tick  ");
        for (String n : NAMES) h.append(String.format("%9s", n));
        h.append("   nonAir");
        System.out.println(h);

        printRow(0, w, TYPES, NAMES, SX, SY, SZ);
        for (int t = 1; t <= T; t++) {
            w.tick();
            if (t % step == 0) printRow(t, w, TYPES, NAMES, SX, SY, SZ);
        }
    }

    private static void printRow(int t, World w, int[] types, String[] names, int SX, int SY, int SZ) {
        StringBuilder sb = new StringBuilder(String.format("%-6d", t));
        for (int idx : types) sb.append(String.format("%9d", w.cellsOfType(idx).size()));
        int nonAir = 0;
        for (int x = 0; x < SX; x++)
            for (int y = 0; y < SY; y++)
                for (int z = 0; z < SZ; z++)
                    if (w.mat[x][y][z] != Blocks.AIR.index) nonAir++;
        sb.append(String.format("%9d", nonAir));
        System.out.println(sb);
    }
}
