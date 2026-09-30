package core.sim;

import core.world.Individual;
import core.world.World;

/**
 * 个体成长确定性门禁（批次 3 · NPC-SOC-PORT 批次3）：
 * 接入 IndividualSystem（learning/behavior/genetics/evolution/medicine/robotics/firearms/
 * fishing/taming/xeno/ascension/magic/cultivation/myth/dreamscape/chemistry/roleplay）后，
 * 同种子两遍运行 → world.hashState() 逐字节一致（证明个体层不写指纹字段），
 * 且个体成长状态（知识/基因/进化/疫病/机器人/神话/梦境…）逐字节一致。
 *
 * 同时印出个体层活动证据（知识/进化世代/患病/英雄/机器人/元素），证明系统真的在跑。
 *
 * 运行：java -cp out core.sim.IndividualDeterminismTest
 */
public final class IndividualDeterminismTest {
    public static void main(String[] args) {
        final int SX = 64, SY = 40, SZ = 64, T = 1200;
        final long SEED = 987654321L;

        Simulation a = new Simulation(SEED, SX, SY, SZ);
        Simulation b = new Simulation(SEED, SX, SY, SZ);
        for (int t = 0; t < T; t++) { a.world.tick(); b.world.tick(); }

        long ha = a.world.hashState(), hb = b.world.hashState();
        boolean hashOk = ha == hb;
        boolean indOk = a.world.individual.snapshot().equals(b.world.individual.snapshot());
        boolean pass = hashOk && indOk;

        Individual in = a.world.individual;
        System.out.printf("INDIVIDUAL-DETERMINISM  hashA=%016x hashB=%016x  npcs=%d%n",
                ha, hb, a.world.npcs.size());
        System.out.println("LIFE A = " + in.snapshot());
        System.out.println("HUD  = " + core.systems.IndividualSystem.asciiSummary(in));
        System.out.println("IND signature A = " + sig(a.world));
        System.out.println("IND signature B = " + sig(b.world));
        System.out.println(pass ? "INDIVIDUAL-DETERMINISM PASS" : "INDIVIDUAL-DETERMINISM FAIL");
        if (!pass) System.exit(1);
    }

    /** 个体成长状态签名：全部开放标量（逐字节可比的确定性代表）。 */
    private static String sig(World w) {
        Individual in = w.individual;
        StringBuilder sb = new StringBuilder();
        sb.append("know=").append(in.knowledge).append(",lit=").append(in.literacy)
          .append(",lib=").append(in.libraries).append(",gen=").append(in.generation)
          .append(",regr=").append(in.regressed).append(",reaw=").append(in.reawakened).append(';');
        sb.append("bold=").append(in.bold).append(",act=").append(in.activity)
          .append(",aggro=").append(in.aggro).append(",scent=").append(in.scent)
          .append(",bconf=").append(in.behaviorConflicts).append(';');
        sb.append("mut=").append(in.mutRate).append(",adapt=").append(in.adapt).append(",yield=");
        for (String s : Individual.SPECIES) sb.append(s).append(':').append(in.yield.get(s)).append(' ');
        sb.append(';');
        sb.append("evoGen=").append(in.evoGeneration).append(",mf=").append(in.meanFitness)
          .append(",peakF=").append(in.peakFitness).append(",adapt2=").append(in.adaptation)
          .append(",eAdapt=").append(in.adapted).append(",eRegr=").append(in.evoRegressed)
          .append(",spec=").append(in.speciated).append(",grazer=").append(in.grazer)
          .append(",browser=").append(in.browser).append(",allele=");
        for (String g : Individual.GENES) sb.append(g).append(':').append(in.allele.get(g)).append(' ');
        sb.append(';');
        sb.append("sick=").append(in.sick).append(",rec=").append(in.recovered)
          .append(",imm=").append(in.immunity).append(",fever=").append(in.feverPressure).append(';');
        sb.append("robots=").append(in.robots).append(",rMass=").append(in.robotMass)
          .append(",rAwake=").append(in.robotAwaken).append(",rConf=").append(in.robotConflicts)
          .append(",rRights=").append(in.robotRights).append(';');
        sb.append("bp=").append(in.blueprints).append(",parts=").append(in.parts)
          .append(",guns=").append(in.gunsBuilt).append(",diy=").append(in.customized)
          .append(",pw=").append(in.armPower).append(",acc=").append(in.armAccuracy)
          .append(",cal=").append(in.armCaliber).append(';');
        sb.append("fish=").append(in.fishStock).append(",catch=").append(in.catches)
          .append(",best=").append(in.bestCatch).append(",anglers=").append(in.anglers)
          .append(",lake=").append(in.lakeLevel).append(';');
        sb.append("cap=").append(in.captured).append(",tamed=").append(in.tamed)
          .append(",loy=").append(in.loyalty).append(",mounts=").append(in.mounts)
          .append(",herd=").append(in.herd).append(';');
        sb.append("virus=").append(in.virus).append(",strains=").append(in.strains)
          .append(",zomb=").append(in.zombies).append(",giants=").append(in.giants)
          .append(",vamp=").append(in.vampires).append(",aliens=").append(in.aliens)
          .append(",outbr=").append(in.outbreaks).append(",panic=").append(in.panic).append(';');
        sb.append("mech=").append(in.mechanized).append(",flesh=").append(in.fleshSuffered)
          .append(",cyb=").append(in.cyborgs).append(",trans=").append(in.transcended)
          .append(",sacr=").append(in.sacrifice).append(",aether=").append(in.aether).append(';');
        sb.append("mana=").append(in.mana).append(",spells=").append(in.spellsKnown)
          .append(",casts=").append(in.casts).append(';');
        sb.append("med=").append(in.meditators).append(",qi=").append(in.qi)
          .append(",realm=").append(in.realmIdx).append(",bt=").append(in.breakthroughs).append(';');
        sb.append("myth=").append(in.mythValue).append(",leg=").append(in.legends)
          .append(",hero=").append(in.heroes).append(",epic=").append(in.epics)
          .append(",oracle=").append(in.oracles).append(';');
        sb.append("lucid=").append(in.lucid).append(",night=").append(in.nightmares)
          .append(",insp=").append(in.inspiration).append(",echo=").append(in.dreamEcho)
          .append(",dreamy=").append(in.dreaminess).append(';');
        sb.append("elems=");
        for (String k : in.elements.keySet()) sb.append(k).append(':').append(in.elements.get(k)).append(' ');
        sb.append(",cmpd=").append(in.compounds.size()).append(",disc=").append(in.discoveries).append(';');
        sb.append("deeds=").append(in.deeds).append(",quests=").append(in.questsDone);
        return sb.toString();
    }
}
