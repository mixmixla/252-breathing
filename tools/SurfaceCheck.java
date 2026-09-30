import core.world.World;
import core.world.Blocks;

/**
 * 等价探针：跑代表性仿真 N tick，周期性对比 World.surfaceY 缓存与 live 逐列下扫（最顶 solid）。
 * 若每列 cached == live 恒成立 → surfaceY 缓存与 live 查询行为完全等价，可安全替换所有 surfaceY 调用。
 *
 * <p>⚠️ 批⑯修正：原用**裸 `new World(...)`** ⇒ 世界无系统（`World.systems` 空，仅 `Simulation.reg()` 填充）
 * ⇒ "600 tick" 期间**世界一格没变**，本探针实际只测了"生成后一瞬"。改为真 `Simulation` 驱动，并补
 * 失败退出码（原来检出不一致也只打印、`exit 0` ⇒ 若接成门禁就是假绿）。
 */
public class SurfaceCheck {
    public static void main(String[] a) {
        World w = new core.sim.Simulation(20260909L, 96, 48, 96).world;
        int bad = 0, checked = 0;
        for (int t = 0; t < 600; t++) {
            w.tick();
            if (t % 20 == 0) {
                for (int x = 0; x < w.SX; x++)
                    for (int z = 0; z < w.SZ; z++) {
                        checked++;
                        int live = liveTop(w, x, z);
                        if (w.surfaceY[x][z] != live) {
                            if (bad < 30) System.out.println("MISMATCH t=" + t + " x=" + x + " z=" + z
                                    + " cached=" + w.surfaceY[x][z] + " live=" + live);
                            bad++;
                        }
                    }
            }
        }
        System.out.println("checked=" + checked + " mismatches=" + bad
                + " -> " + (bad == 0 ? "SURFACE CACHE EQUIVALENT" : "SURFACE CACHE FAILED"));
        if (bad != 0) System.exit(1);
    }

    static int liveTop(World w, int x, int z) {
        for (int y = w.SY - 1; y >= 0; y--)
            if (Blocks.byIndex(w.mat[x][y][z]).solid) return y;
        return -1;
    }
}
