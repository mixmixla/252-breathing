// 写入成本探针（非门禁，人工运行）—— 回答「一次真实 setBlock 到底多贵、贵在哪」。
//
// 用法:
//   javac -encoding UTF-8 -cp out -d out tools/WriteCostProbe.java
//   java  -cp out WriteCostProbe [SX SY SZ WARM TICKS SEED]
//
// 背景（2026-09-17 消融实测）：关掉 `ash` 一个系统，整 tick 从 17.9ms 掉到 4.2ms（省 76.6%），
// 而 SystemAudit 统计 `ash` 每 tick 只写 ~27 格 => **单次写入的边际成本极高**。
//
// 要验证的机制：World 的派生索引 `CellSet` 是"升序平铺 int[]"，
// 增量 insert/remove 走 arraycopy 移位 = **O(该类型的格数)**；
// 而本世界 nonAir=100 万格 / STONE=85 万格 —— 一次插入 = 兆字节级 memmove。
//
// 关键：**每次测量必须产生一次真实的插入/删除**。第一版探针把同一批列反复写同一个值，
// 第二次起 `mat == AIR` 条件不成立 → 变成空操作，量出来的是"判断"而不是"写入"（0.2µs 的假数据）。
// 现在改成同一列在 AIR <-> 目标方块之间**往返切换**，每次调用都是一次真实的增或删。
import core.sim.Simulation;
import core.world.Blocks;
import core.world.World;

public final class WriteCostProbe {

    public static void main(String[] args) {
        int SX = args.length > 0 ? Integer.parseInt(args[0]) : 160;
        int SY = args.length > 1 ? Integer.parseInt(args[1]) : 112;
        int SZ = args.length > 2 ? Integer.parseInt(args[2]) : 160;
        int WARM = args.length > 3 ? Integer.parseInt(args[3]) : 100;
        int TICKS = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        long SEED = args.length > 5 ? Long.parseLong(args[5]) : 20260917L;

        World w = new Simulation(SEED, SX, SY, SZ).world;
        for (int i = 0; i < WARM; i++) w.tick();

        java.lang.System.out.printf("WRITECOST world=%dx%dx%d  seed=%d%n", SX, SZ, SEED, 0);
        java.lang.System.out.printf("  集合规模：nonAir=%d  STONE=%d  DIRT=%d  GRASS=%d  MOSS=%d  LEAF=%d  FLOWER=%d%n",
                w.nonAirCells().size(), w.cellsOfType(Blocks.STONE.index).size(),
                w.cellsOfType(Blocks.DIRT.index).size(), w.cellsOfType(Blocks.GRASS.index).size(),
                w.cellsOfType(Blocks.MOSS.index).size(), w.cellsOfType(Blocks.LEAF.index).size(),
                w.cellsOfType(Blocks.FLOWER.index).size());
        java.lang.System.out.printf("  tick 基准：%.2f ms%n", benchTick(w, TICKS));

        // 三类写入，全部在**同一批列上往返切换**，保证每次都是一次真实增/删
        java.lang.System.out.printf("  ① AIR <-> STONE   （大集 85 万 + 跨 AIR 边界）%8.1f µs/次%n",
                toggle(w, Blocks.STONE.index, -8) * 1000);
        java.lang.System.out.printf("  ② AIR <-> FLOWER  （小集 1.4 万 + 跨 AIR 边界）%8.1f µs/次%n",
                toggle(w, Blocks.FLOWER.index, -12) * 1000);
        java.lang.System.out.printf("  ③ STONE <-> DIRT  （大集 + 不跨边界，只动 typeCells）%8.1f µs/次%n",
                swapInPlace(w, Blocks.STONE.index, Blocks.DIRT.index, -16) * 1000);
    }

    /** 在 yOff（相对地表）处对 400 个不同列做 AIR <-> 目标方块的往返切换。 */
    private static double toggle(World w, int blockIdx, int yOff) {
        final int N = 400;
        int[][] cells = pick(w, N, yOff);
        for (int[] c : cells) w.setBlock(c[0], c[1], c[2], Blocks.AIR.index);       // 归零
        for (int r = 0; r < 200; r++) w.setBlock(cells[r % N][0], cells[r % N][1], cells[r % N][2],
                (r % 2 == 0) ? blockIdx : Blocks.AIR.index);                        // 预热
        long t0 = java.lang.System.nanoTime();
        for (int i = 0; i < N; i++) {
            int[] c = cells[i];
            boolean air = w.mat[c[0]][c[1]][c[2]] == Blocks.AIR.index;
            w.setBlock(c[0], c[1], c[2], air ? blockIdx : Blocks.AIR.index);
        }
        return (java.lang.System.nanoTime() - t0) / 1e6 / N;
    }

    /** 不跨 AIR 边界：STONE <-> DIRT 往返（只动 typeCells[STONE]/[DIRT]，不动 nonAirCells）。 */
    private static double swapInPlace(World w, int a, int b, int yOff) {
        final int N = 400;
        int[][] cells = pick(w, N, yOff);
        for (int[] c : cells) w.setBlock(c[0], c[1], c[2], a);
        long t0 = java.lang.System.nanoTime();
        for (int i = 0; i < N; i++) {
            int[] c = cells[i];
            boolean isA = w.mat[c[0]][c[1]][c[2]] == a;
            w.setBlock(c[0], c[1], c[2], isA ? b : a);
        }
        return (java.lang.System.nanoTime() - t0) / 1e6 / N;
    }

    private static int[][] pick(World w, int n, int yOff) {
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            int x = 8 + (i % 20), z = 8 + (i / 20);
            int y = w.surfaceY[x][z] + yOff;
            if (y < 1) y = 1;
            if (y > w.SY - 2) y = w.SY - 2;
            out[i] = new int[]{x, y, z};
        }
        return out;
    }

    private static double benchTick(World w, int ticks) {
        for (int i = 0; i < 50; i++) w.tick();
        long t0 = java.lang.System.nanoTime();
        for (int i = 0; i < ticks; i++) w.tick();
        return (java.lang.System.nanoTime() - t0) / 1e6 / ticks;
    }
}
