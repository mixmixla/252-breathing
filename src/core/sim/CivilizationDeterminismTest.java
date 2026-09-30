package core.sim;

import core.world.Civilization;
import core.world.World;

/**
 * 文明深度确定性门禁（批次 2 · NPC-SOC-PORT 批次2）：
 * 接入 CivilizationSystem（culture/religion/tech/industry/urban/diplomacy/warfare）后，
 * 同种子两遍运行 → world.hashState() 逐字节一致（证明文明层不写指纹字段），
 * 且文明状态（研究/信仰/区划/外交/军政/文化）逐字节一致。
 *
 * 同时印出文明活动证据（研究点/神殿/信徒/区划/道路/外交关系/政权），证明系统真的在跑。
 *
 * 运行：java -cp out core.sim.CivilizationDeterminismTest
 */
public final class CivilizationDeterminismTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 1200;
        final long SEED = 987654321L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);
        for (int t = 0; t < T; t++) { a.world.tick(); b.world.tick(); }

        long ha = a.world.hashState(), hb = b.world.hashState();
        boolean hashOk = ha == hb;
        boolean civOk = sig(a.world).equals(sig(b.world));
        boolean pass = hashOk && civOk;

        Civilization c = a.world.civ;
        System.out.printf("CIVILIZATION-DETERMINISM  hashA=%016x hashB=%016x  npcs=%d%n",
                ha, hb, a.world.npcs.size());
        System.out.println("CIV A = " + c.snapshot());
        System.out.println("culture: festivals=" + c.festivals + " taboos=" + c.taboos.size()
                + " legends=" + c.legends.size() + " nicknames=" + c.nicknames.size());
        System.out.println(pass ? "CIVILIZATION-DETERMINISM PASS" : "CIVILIZATION-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }

    /** 文明状态签名：全部开放标量 + 区划覆盖层校验和（逐字节可比的确定性代表）。 */
    private static String sig(World w) {
        Civilization c = w.civ;
        StringBuilder sb = new StringBuilder();
        sb.append("research=").append(c.research).append(",unlocked=").append(c.unlockedNames())
          .append(",gm=").append(c.techGrowthMult).append(",wm=").append(c.techWaterMult)
          .append(",bp=").append(c.blueprints.size()).append(';');
        sb.append("temples=").append(c.temples).append(",bel=").append(c.believers)
          .append(",faith=").append(c.faith).append(",mir=").append(c.miracles).append(",sects=").append(c.sects).append(';');
        sb.append("power=").append(c.power).append(",ore=").append(c.ore).append(",iron=").append(c.iron)
          .append(",gear=").append(c.gear).append(",grid=").append(c.gridDegree)
          .append(",extractable=").append(c.extractable).append(';');
        sb.append("res=").append(c.resArea).append(",ind=").append(c.indArea).append(",farm=").append(c.farmArea)
          .append(",roads=").append(c.roads).append(",residents=").append(c.residents)
          .append(",liv=").append(c.livability).append(",land=").append(c.landValue)
          .append(",cx=").append(c.centerX).append(",cz=").append(c.centerZ).append(';');
        sb.append("zoneChecksum=").append(zoneSum(c)).append(';');
        for (Civilization.Faction f : c.factions) {
            sb.append('[').append(f.id).append(":rel=").append(c.relations.get(f.id))
              .append(",pow=").append(f.power).append(",bd=").append(f.border)
              .append(",po=").append(f.posture).append(']');
        }
        sb.append(",treaties=").append(c.treaties).append(",conflicts=").append(c.conflicts)
          .append(",wars=").append(c.wars).append(",war=").append(c.war).append(';');
        sb.append("regimes=").append(c.regimes).append(",armies=").append(c.armies)
          .append(",unrest=").append(c.unrest).append(",milP=").append(c.milPower)
          .append(",wfWars=").append(c.wfWars).append(",revolt=").append(c.revolutions).append(';');
        sb.append("fest=").append(c.festivals.size()).append(",nik=").append(c.nicknames.size())
          .append(",tab=").append(c.taboos.size()).append(",leg=").append(c.legends.size());
        return sb.toString();
    }

    private static long zoneSum(Civilization c) {
        if (c.zone == null) return 0;
        long s = 0;
        for (int x = 0; x < c.zone.length; x++)
            for (int z = 0; z < c.zone[x].length; z++) s = s * 31 + c.zone[x][z];
        return s;
    }
}
