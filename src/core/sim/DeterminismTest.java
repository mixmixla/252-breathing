package core.sim;

import core.world.Player;

/**
 * 确定性门禁（对应 Python tests/test_same_seed_same_result / test_player_loop）：
 * 同种子 + 同 intent 序列 → world.hashState() 逐字节一致。
 *
 * 运行：java -cp out core.sim.DeterminismTest
 */
public final class DeterminismTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 240;
        final long SEED = 123456789L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);

        for (int t = 0; t < T; t++) {
            // 完全相同的脚本化输入
            if (t % 7 == 0) { a.world.player.setIntent(Player.Intent.repel());
                              b.world.player.setIntent(Player.Intent.repel()); }
            if (t % 3 == 0) { a.world.player.setIntent(Player.Intent.move(1, 0));
                              b.world.player.setIntent(Player.Intent.move(1, 0)); }
            if (t % 11 == 0) { a.world.player.setIntent(Player.Intent.move(0, 1));
                               b.world.player.setIntent(Player.Intent.move(0, 1)); }
            a.world.tick();
            b.world.tick();
        }

        long ha = a.world.hashState();
        long hb = b.world.hashState();

        boolean pass = ha == hb
                && a.world.prosperity == b.world.prosperity
                && a.world.skills.equals(b.world.skills)
                && a.world.villageMemory.equals(b.world.villageMemory);

        System.out.printf("DETERMINISM  hashA=%016x hashB=%016x  prosperity=%d  skills=%s%n",
                ha, hb, a.world.prosperity, a.world.skills);
        System.out.println(pass ? "DETERMINISM PASS" : "DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }
}
