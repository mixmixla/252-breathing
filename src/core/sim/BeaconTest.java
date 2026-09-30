package core.sim;

import core.rng.SeededRNG;
import core.systems.BeaconSystem;
import core.world.World;

/**
 * D 批 · 地标入罗盘门禁。
 *
 * <p>锁两件事：
 * <ol>
 *   <li><b>恰好一次</b>：BeaconSystem 在繁荣≥6 时点亮中心 LAMP，并<b>仅一次</b>把地标写入
 *       {@link World#beacons}（中心格点过一次后变为 LAMP，if 分支永不再成立 → 幂等、零额外 RNG 消耗）。</li>
 *   <li><b>确定性</b>：同种子两遍运行 → 地标数量与坐标逐字节一致（证明零漂移；beacons 不进
 *       {@link World#hashState()}，故不影响四道零漂移指纹与立项 F 存读档门禁）。</li>
 * </ol>
 *
 * 运行：java -cp out core.sim.BeaconTest
 */
public final class BeaconTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 48, SZ = 64;
        final long SEED = 20260913L;

        World a = run(SEED, SX, SY, SZ);
        World b = run(SEED, SX, SY, SZ);

        boolean one = a.beacons.size() == 1 && b.beacons.size() == 1;
        World.Beacon ba = a.beacons.get(0), bb = b.beacons.get(0);
        boolean posOk = Math.abs(ba.x - (SX / 2 + 0.5f)) < 0.01f
                    && Math.abs(ba.z - (SZ / 2 + 0.5f)) < 0.01f
                    && Math.abs(bb.x - ba.x) < 1e-6f && Math.abs(bb.z - ba.z) < 1e-6f;
        boolean named = "繁荣灯塔".equals(ba.name);
        boolean pass = one && posOk && named;

        System.out.printf("BEACON  countA=%d countB=%d  x=%.2f z=%.2f name=%s%n",
                a.beacons.size(), b.beacons.size(), ba.x, ba.z, ba.name);
        System.out.println(pass ? "BEACON PASS" : "BEACON FAIL");
        if (!pass) System.exit(1);
    }

    /** 一趟确定性运行：直接驱动 BeaconSystem 400 tick（繁荣已置 6，5%/tick 必点亮）。 */
    private static World run(long seed, int sx, int sy, int sz) {
        World w = new World(seed, sx, sy, sz);
        w.prosperity = 6;                       // 越过 BeaconSystem 的繁荣门槛
        BeaconSystem bs = new BeaconSystem();
        SeededRNG rng = new SeededRNG(12345L);
        for (int i = 0; i < 400; i++) bs.update(w, rng);
        return w;
    }
}
