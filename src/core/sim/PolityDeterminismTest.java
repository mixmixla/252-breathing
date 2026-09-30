package core.sim;

import core.world.Calamity;
import core.world.Polity;
import core.world.World;

/**
 * 民政经济 + 灾害 确定性门禁（批次 4 · NPC-SOC-PORT 批次4）：
 * 接入 PolitySystem（城镇等级/集市定价/犯罪仲裁 + 货币/商队/通胀/供应链）与
 * CalamitySystem（山火/洪涝/干旱/地震 触发 + 预警 + 撤离）后，
 * 同种子两遍运行 → world.hashState() 逐字节一致（证明两层不写指纹字段），
 * 且民政经济状态 / 灾害状态 逐字节一致。
 *
 * 同时印出活动证据（城镇等级/价格/犯罪/仲裁/货币/通胀/商队/灾害计数），证明系统真的在跑。
 *
 * 运行：java -cp out core.sim.PolityDeterminismTest
 */
public final class PolityDeterminismTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 1200;
        final long SEED = 987654321L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);
        for (int t = 0; t < T; t++) { a.world.tick(); b.world.tick(); }

        long ha = a.world.hashState(), hb = b.world.hashState();
        boolean hashOk = ha == hb;
        boolean polityOk = sig(a.world).equals(sig(b.world));
        boolean tradeOk = tradeSig(a.world).equals(tradeSig(b.world));
        boolean calamityOk = a.world.calamity.snapshot().equals(b.world.calamity.snapshot());
        boolean pass = hashOk && polityOk && tradeOk && calamityOk;

        Polity p = a.world.polity;
        Calamity c = a.world.calamity;
        System.out.printf("POLITY-DETERMINISM  hashA=%016x hashB=%016x  npcs=%d%n",
                ha, hb, a.world.npcs.size());
        System.out.println("POLITY A = " + p.snapshot());
        System.out.println("TRADE  A = " + p.tradeSnapshot());
        System.out.println("CALAM  A = " + c.snapshot());
        System.out.println("HUD = " + p.asciiSummary() + "   |   " + c.asciiSummary());
        System.out.println(pass ? "POLITY-DETERMINISM PASS" : "POLITY-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }

    /** 民政状态签名（城镇档案 + 集市定价 + 犯罪/仲裁计数）。 */
    private static String sig(World w) {
        return w.polity.snapshot();
    }

    /** 贸易账本签名（货币/通胀/商队/供应链）。 */
    private static String tradeSig(World w) {
        return w.polity.tradeSnapshot();
    }
}
