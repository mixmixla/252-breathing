package core.sim;

import core.world.Player;

/**
 * 零漂移门禁（对应 Python tests/test_zero_drift_spawn）：
 * 渲染/演出乱用 fxRng 绝不可改变仿真指纹 —— 证明 sim 与 render 彻底隔离。
 *
 * 运行：java -cp out core.sim.ZeroDriftTest
 */
public final class ZeroDriftTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 200;
        final long SEED = 987654321L;

        Simulation sim = new Simulation(SEED, SX, SY, SZ);
        Simulation ref = new Simulation(SEED, SX, SY, SZ);

        for (int t = 0; t < T; t++) {
            if (t % 5 == 0) { sim.world.player.setIntent(Player.Intent.repel());
                              ref.world.player.setIntent(Player.Intent.repel()); }
            // 渲染层疯狂消耗演出 RNG（模拟粒子/屏震/颜色抖动）
            sim.world.fxRng.nextDouble();
            sim.world.fxRng.nextInt(100000);
            sim.world.fxRng.uniform(-1, 1);
            sim.world.tick();
            ref.world.tick();
        }

        long hs = sim.world.hashState();
        long hr = ref.world.hashState();
        boolean pass = hs == hr;

        System.out.printf("ZERO-DRIFT  simHash=%016x refHash=%016x%n", hs, hr);
        System.out.println(pass ? "ZERO-DRIFT PASS" : "ZERO-DRIFT FAIL");
        if (!pass) System.exit(1);
    }
}
