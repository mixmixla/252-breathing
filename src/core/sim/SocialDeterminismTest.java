package core.sim;

import core.agent.Npc;
import core.agent.VillageSocial;
import core.world.World;

/**
 * 社会核心确定性门禁（批次 1 · NPC-SOC-PORT 批次1）：
 * 接入 SocialSystem（家族/情绪/规范）后，同种子两遍运行 → world.hashState() 逐字节一致，
 * NPC 内部态逐字节一致，且村庄社会态（家族/情绪/规范）逐字节一致。
 *
 * 同时印出社会活动证据（结亲户数 / 生育数 / 情绪 / 信任 / 规范），证明系统真的在跑。
 *
 * 运行：java -cp out core.sim.SocialDeterminismTest
 */
public final class SocialDeterminismTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 800;
        final long SEED = 424242L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);
        for (int t = 0; t < T; t++) { a.world.tick(); b.world.tick(); }

        boolean hashOk = a.world.hashState() == b.world.hashState();
        boolean npcOk = npcSig(a.world).equals(npcSig(b.world));
        boolean socOk = socSig(a.world).equals(socSig(b.world));
        boolean pass = hashOk && npcOk && socOk;

        VillageSocial vs = a.world.social;
        int births = 0;
        for (VillageSocial.Family f : vs.families) births += f.children.size();

        System.out.printf("SOCIAL-DETERMINISM  hashA=%016x hashB=%016x  npcs=%d%n",
                a.world.hashState(), b.world.hashState(), a.world.npcs.size());
        System.out.println("families=" + vs.families.size() + " births=" + births
                + " mood=" + vs.mood + " stress=" + vs.stress + " trust=" + vs.trust
                + " coop=" + vs.cooperation + " norm=" + vs.norm + " crash=" + vs.crash);
        System.out.println("social signature A = " + socSig(a.world));
        System.out.println(pass ? "SOCIAL-DETERMINISM PASS" : "SOCIAL-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }

    private static String npcSig(World w) {
        StringBuilder sb = new StringBuilder();
        sb.append("n=").append(w.npcs.size()).append(";");
        for (Npc npc : w.npcs) {
            sb.append('[').append(npc.id)
              .append(",h=").append(fp(npc.body.hunger))
              .append(",th=").append(fp(npc.body.thirst))
              .append(",mood=").append(npc.social.mood)
              .append(",x=").append(fp(npc.x))
              .append(",z=").append(fp(npc.z))
              .append(']');
        }
        return sb.toString();
    }

    private static String socSig(World w) {
        VillageSocial v = w.social;
        StringBuilder sb = new StringBuilder();
        sb.append("seq=").append(v.npcSeq)
          .append(",mood=").append(v.mood)
          .append(",stress=").append(v.stress)
          .append(",conn=").append(v.connected)
          .append(",trust=").append(v.trust)
          .append(",coop=").append(v.cooperation)
          .append(",viol=").append(v.violations)
          .append(",sanc=").append(v.sanctions)
          .append(",norm=").append(v.norm)
          .append(",crash=").append(v.crash).append(';');
        for (VillageSocial.Family f : v.families) {
            sb.append('[').append(f.id).append(":kids=");
            for (String c : f.children) sb.append(c).append(',');
            sb.append("]");
        }
        return sb.toString();
    }

    private static float fp(float v) { return Math.round(v * 100f) / 100f; }
}
