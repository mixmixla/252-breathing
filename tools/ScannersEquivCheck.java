import core.rng.SeededRNG;
import core.sim.Simulation;
import core.world.Blocks;
import core.world.World;

/**
 * 列/体积扫描系统逐 tick 行为等价门禁（批⑨）：断言 biodiversity / snowcap / cactus 的
 * "新实现"与"原实现（逐字拷贝）"在同种子、同初始世界下**逐 tick 逐字节**一致。
 *
 * <p>做法同 FlowerVineEquivCheck：A 只保留被测系统（经 `tick()`），B 全关系统但 `tick()` 推进
 * tick + 手动调用本文件内的原实现逐字拷贝（同 tick 同 simStream）。每 tick 全量比对 mat。
 *
 * 用法：{@code javac -cp out -d out tools/ScannersEquivCheck.java && java -cp out ScannersEquivCheck [SX SY SZ TICKS SEED]}
 */
public class ScannersEquivCheck {
    public static void main(String[] a) {
        int SX = a.length > 0 ? Integer.parseInt(a[0]) : 96;
        int SY = a.length > 1 ? Integer.parseInt(a[1]) : 48;
        int SZ = a.length > 2 ? Integer.parseInt(a[2]) : 96;
        int TICKS = a.length > 3 ? Integer.parseInt(a[3]) : 400;
        long SEED = a.length > 4 ? Long.parseLong(a[4]) : 20260909L;

        boolean ok = compare("biodiversity", SX, SY, SZ, TICKS, SEED, 0)
                   & compare("snowcap", SX, SY, SZ, TICKS, SEED, 1)
                   & compare("cactus", SX, SY, SZ, TICKS, SEED, 2)
                   & compare("moss", SX, SY, SZ, TICKS, SEED, 3)
                   & compare("ash", SX, SY, SZ, TICKS, SEED, 4);
        System.out.println("SCANNERS-EQUIV -> " + (ok ? "EQUIVALENT (per-tick byte-identical)" : "FAILED"));
        if (!ok) System.exit(1);
    }

    private static boolean compare(String which, int SX, int SY, int SZ, int TICKS, long SEED, int kind) {
        World wa = new Simulation(SEED, SX, SY, SZ).world;
        World wb = new Simulation(SEED, SX, SY, SZ).world;
        for (String nm : wa.registry.names()) wa.registry.disable(nm);
        for (String nm : wb.registry.names()) wb.registry.disable(nm);
        wa.registry.enable(which);

        int mismatches = 0;
        for (int t = 0; t < TICKS; t++) {
            wa.tick();
            wb.tick();
            SeededRNG rng = wb.simStream(which);
            if (kind == 0) referenceBiodiversity(wb, rng);
            else if (kind == 1) referenceSnowcap(wb, rng);
            else if (kind == 2) referenceCactus(wb, rng);
            else if (kind == 3) referenceMoss(wb, rng);
            else referenceAsh(wb, rng);
            int bad = 0;
            long firstKey = -1;
            for (int x = 0; x < SX; x++)
                for (int y = 0; y < SY; y++)
                    for (int z = 0; z < SZ; z++)
                        if (wa.mat[x][y][z] != wb.mat[x][y][z]) {
                            bad++;
                            if (firstKey < 0) firstKey = World.cellKey(x, y, z);
                            if (bad > 16) { x = SX; y = SY; z = SZ; }
                        }
            if (bad != 0) {
                if (mismatches < 5)
                    System.out.println("  MISMATCH " + which + " t=" + t + " cells=" + bad + " first=" + firstKey);
                mismatches++;
            }
        }
        boolean ok = mismatches == 0;
        System.out.println("  " + which + "  worlds=" + SX + "x" + SY + "x" + SZ + " ticks=" + TICKS
                + "  mismatches=" + mismatches + "  " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    // ---------------- 原实现逐字拷贝 ----------------

    static void referenceBiodiversity(World w, SeededRNG rng) {
        int leafWoodCount = 0;
        final int step = 3;
        for (int x = 0; x < w.SX; x += step)
            for (int y = 0; y < w.SY; y += step)
                for (int z = 0; z < w.SZ; z += step) {
                    int b = w.mat[x][y][z];
                    if (b == Blocks.LEAF.index || b == Blocks.WOOD.index) leafWoodCount++;
                }
        int samples = 12;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = w.surfaceY[x][z];
            if (top < 0) continue;
            if (w.mat[x][top][z] != Blocks.GRASS.index) continue;
            if (top + 5 >= w.SY) continue;
            double p = leafWoodCount < 40 ? 0.02 : 0.002;
            if (rng.nextDouble() < p) growTree(w, x, top, z);
        }
    }

    static void growTree(World w, int x, int base, int z) {
        for (int t = 1; t <= 4; t++) w.setBlock(x, base + t, z, Blocks.WOOD.index);
        int ty = base + 5;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    int lx = x + dx, ly = ty + dy, lz = z + dz;
                    if (w.inBounds(lx, ly, lz) && w.mat[lx][ly][lz] == Blocks.AIR.index
                            && (Math.abs(dx) + Math.abs(dz) + Math.abs(dy)) <= 2)
                        w.setBlock(lx, ly, lz, Blocks.LEAF.index);
                }
    }

    static void referenceSnowcap(World w, SeededRNG rng) {
        int snowLine = (int) (w.SY * 0.68);
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int top = w.surfaceY[x][z];
                if (top < 0) continue;
                int surface = w.getBlock(x, top, z);
                if ((surface == Blocks.GRASS.index || surface == Blocks.DIRT.index
                        || surface == Blocks.STONE.index)
                        && top >= snowLine
                        && w.getBlock(x, top + 1, z) == Blocks.AIR.index
                        && rng.nextDouble() < 0.02) {
                    w.setBlock(x, top + 1, z, Blocks.SNOW.index);
                }
            }
    }

    /** 原 MossSystem（批⑩之前：getBlock + 无平铺快照；含批⑤的 remain 逻辑）。 */
    static void referenceMoss(World w, SeededRNG rng) {
        int remain = w.spreadHeadroom(Blocks.MOSS.index);
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int top = w.surfaceY[x][z];
                if (top < 0) continue;
                if (w.getBlock(x, top, z) == Blocks.STONE.index
                        && w.getBlock(x, top + 1, z) == Blocks.AIR.index
                        && rng.nextDouble() < 0.03 && remain > 0) {
                    w.setBlock(x, top + 1, z, Blocks.MOSS.index);
                    remain--;
                }
            }
    }

    /** 原 AshFallSystem（批⑩之前：surfaceY helper + 未扁平化；ASH_MAX_LAYERS=1）。 */
    static void referenceAsh(World w, SeededRNG rng) {
        final int ASH_MAX_LAYERS = 1;
        int remain = w.spreadHeadroom(Blocks.ASH.index);
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int top = w.surfaceY[x][z];
                if (top < 0) continue;
                if (top + 1 >= w.SY) continue;
                if (w.mat[x][top + 1][z] != Blocks.AIR.index) continue;
                if (rng.nextDouble() < 0.004) {
                    int n = 0;
                    for (int y = top; y >= 0 && n < ASH_MAX_LAYERS; y--) {
                        if (w.mat[x][y][z] != Blocks.ASH.index) break;
                        n++;
                    }
                    if (n < ASH_MAX_LAYERS && remain > 0) {
                        w.setBlock(x, top + 1, z, Blocks.ASH.index);
                        remain--;
                    }
                }
            }
    }

    static void referenceCactus(World w, SeededRNG rng) {
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) {
                int top = w.surfaceY[x][z];
                if (top < 0) continue;
                if (w.getBlock(x, top, z) != Blocks.SAND.index) continue;
                if (w.getBlock(x - 1, top, z) != Blocks.AIR.index
                        || w.getBlock(x + 1, top, z) != Blocks.AIR.index
                        || w.getBlock(x, top, z - 1) != Blocks.AIR.index
                        || w.getBlock(x, top, z + 1) != Blocks.AIR.index) continue;
                if (rng.nextDouble() < 0.01) {
                    int h = 1 + rng.nextInt(3);
                    for (int t = 1; t <= h; t++) {
                        int yy = top + t;
                        if (yy < w.SY && w.getBlock(x, yy, z) == Blocks.AIR.index)
                            w.setBlock(x, yy, z, Blocks.CACTUS.index);
                    }
                }
            }
    }
}
