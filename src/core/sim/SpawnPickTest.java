package core.sim;

import core.world.Blocks;
import core.world.World;

/**
 * 开局选址器性质测试（QA 2026-09-12 · TEST_STRATEGY P0）。
 *
 * <p>断言的是<b>性质</b>而非"能跑"：
 * ① 确定性——同种子两次构造，出生坐标逐字节一致；
 * ② 范围——落点必在村心（世界中心）±24 格内（选址器环形扫描域）；
 * ③ 平坦性——落点 3×3 邻列地表高差 ≤1（无 2 格墙围困）、本列上方 2 格净空（不嵌树冠）；
 * ④ 边界——小世界（inBounds 收紧）构造不越界、不抛异常，且坐标落在世界内。
 *
 * <p>平坦性判据在测试内<b>独立实现</b>（不复用 Simulation.spawnFlatClear）——交叉验证，
 * 防止被测代码与断言共享同一处错误。
 */
public final class SpawnPickTest {
    public static void main(String[] args) {
        int fails = 0;

        // ---- 1. 确定性 + 范围 + 平坦性（主世界尺寸）----
        Simulation a = new Simulation(20260912L, 96, 112, 96);
        Simulation b = new Simulation(20260912L, 96, 112, 96);
        boolean detOk = a.player.x == b.player.x && a.player.z == b.player.z;
        System.out.println("DET   " + (detOk ? "PASS" : "FAIL") + "  x=" + a.player.x + " z=" + a.player.z);
        if (!detOk) fails++;

        int ix = Math.round(a.player.x - 0.5f), iz = Math.round(a.player.z - 0.5f);
        int cx = 96 / 2, cz = 96 / 2;
        boolean inRange = Math.max(Math.abs(ix - cx), Math.abs(iz - cz)) <= 24;
        System.out.println("RANGE " + (inRange ? "PASS" : "FAIL") + "  ix=" + ix + " iz=" + iz + " (village center " + cx + "," + cz + ")");
        if (!inRange) fails++;

        World w = a.world;
        int h0 = w.surfaceY[ix][iz];
        boolean flat = true;
        for (int dx = -1; dx <= 1 && flat; dx++)
            for (int dz = -1; dz <= 1 && flat; dz++) {
                int nx = ix + dx, nz = iz + dz;
                if (!w.inBounds(nx, 0, nz)) { flat = false; break; }
                if (Math.abs(w.surfaceY[nx][nz] - h0) > 1) flat = false;
            }
        boolean clear = w.getBlock(ix, h0 + 1, iz) == Blocks.AIR.index
                && w.getBlock(ix, h0 + 2, iz) == Blocks.AIR.index;
        System.out.println("FLAT  " + (flat ? "PASS" : "FAIL") + "  3x3 heightDiff<=1");
        System.out.println("CLEAR " + (clear ? "PASS" : "FAIL") + "  +1/+2 AIR over head");
        if (!flat) fails++;
        if (!clear) fails++;

        // ---- 2. 小世界边界：不越界、不抛异常、坐标在世界内 ----
        boolean smallOk;
        try {
            Simulation s = new Simulation(99L, 48, 40, 48);
            float px = s.player.x, pz = s.player.z;
            smallOk = px >= 0 && px < 48 && pz >= 0 && pz < 48
                    && s.world.inBounds((int) Math.floor(px), 0, (int) Math.floor(pz));
        } catch (Throwable t) {
            smallOk = false;
            System.out.println("SMALL threw: " + t);
        }
        System.out.println("SMALL " + (smallOk ? "PASS" : "FAIL") + "  48x40x48 edge world");
        if (!smallOk) fails++;

        System.out.println(fails == 0 ? "SPAWNPICK PASS" : ("SPAWNPICK FAIL (" + fails + ")"));
        if (fails > 0) System.exit(1);
    }
}
