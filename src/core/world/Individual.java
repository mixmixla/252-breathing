package core.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 个体成长层（批次 3 · NPC-SOC-PORT 批次3）：把 Python 的 18 个「个体/超验/异种」系统
 * 的**决策与数字层**移植为一个开放标量聚合容器。
 *
 * 忠实移植（Python -> Java 本类字段）：
 *  - systems/learning.py    -> libraries/students/knowledge/generation/literacy/regressed/reawakened
 *  - systems/behavior.py    -> bold/sociable/activity/aggro/scent/behaviorConflicts
 *  - systems/persona.py     -> personasAssigned/murmurLines（无本地 LLM，降级为“村口碎语”计数）
 *  - systems/genetics.py    -> mutRate/yield{grass,bug,beast,wheat,berry}/adapt
 *  - systems/evolution.py   -> genePool(K×4)+gene 统计+adapted/regressed/speciated/allele
 *  - systems/medicine.py    -> sick/immunity/recovered/feverPressure（聚合 SIR 口径）
 *  - systems/robotics.py    -> robots/robotMass/robotAwakeTotal/robotAwaken/robotConflicts/robotRights
 *  - systems/firearms.py    -> blueprints/parts/gunsBuilt/customized/armPower/armAccuracy/armCaliber
 *  - systems/fishing.py     -> fishStock/catches/bestCatch/anglers/lakeLevel
 *  - systems/taming.py      -> captured/tamed/loyalty/mounts/herd
 *  - systems/xeno.py        -> virus/strains/zombies/giants/vampires/aliens/outbreaks/panic
 *  - systems/ascension.py   -> mechanized/fleshSuffered/cyborgs/transcended/sacrifice/aether
 *  - systems/magic.py       -> mana/spellsKnown/casts（材料执行层由 IndividualSystem.MATERIAL_WORKS 守护）
 *  - systems/cultivation.py -> meditators/qi/realmIdx/breakthroughs（村庄聚合口径）
 *  - systems/myth.py        -> legends/heroes/epics/oracles/mythValue
 *  - systems/dreamscape.py  -> lucid/nightmares/inspiration/dreamEcho/dreaminess
 *  - systems/chemistry.py   -> elements/compounds/discoveries（只读网格普查 + 开放标量）
 *  - systems/roleplay.py    -> deeds/questsDone（Java 玩家已有 LV/HP，此处只记成长里程碑）
 *
 * 零漂移纪律（与 npcs/beasts/shrines/social/chronicle/civ 同）：
 *  - 本类是**实体级开放标量状态**，绝不进 {@link World#hashState()}，也不写
 *    mat/mass/prosperity/skills/villageMemory/builtMass；纯确定性推导，
 *    随机只走 {@link World#simStream(String)} 派生子流（不动主 rng 状态）。
 */
public final class Individual {

    // ---- 教育/知识（learning.py M35）----
    public int libraries, students, generation = 1;
    public float knowledge, literacy, peak;
    public int regressed, reawakened, peakAlive;
    public boolean regressing, reawakenedEvt;

    // ---- 行为本能（behavior.py M39）----
    public float bold, sociable, activity, aggro, scent;
    public int behaviorConflicts;

    // ---- 角色引擎（persona.py M76；无本地 Ollama → 降级为“村口碎语”计数）----
    public int personasAssigned, murmurLines;

    // ---- 宏观基因（genetics.py M27）----
    public static final String[] SPECIES = {"grass", "bug", "beast", "wheat", "berry"};
    public float mutRate = 0.2f, adapt;
    public final LinkedHashMap<String, Float> yield = new LinkedHashMap<String, Float>();

    // ---- 分子进化（evolution.py M37）：独立基因库（不触碰 NPC/生态）----
    public static final String[] GENES = {"heat", "spend", "hard", "diet"};
    public static final int EVO_K = 60;
    public int evoGeneration = 1, adapted, evoRegressed, speciated, grazer, browser;
    public float meanFitness = 0.001f, peakFitness, adaptation;
    public boolean atPeak, inBimodal, evoInitialized;
    public final float[][] genePool = new float[EVO_K][GENES.length];
    public final LinkedHashMap<String, Float> allele = new LinkedHashMap<String, Float>();

    // ---- 疫病/医学（medicine.py M22，聚合 SIR 口径）----
    public int sick, recovered;
    public float immunity, feverPressure;

    // ---- 机器人（robotics.py M29）----
    public int robots, robotAwaken, robotConflicts;
    public float robotMass, robotAwakeTotal;
    public boolean robotRights;

    // ---- 枪械（firearms.py M67）----
    public int blueprints, parts, gunsBuilt, customized;
    public float armPower, armAccuracy, armCaliber;

    // ---- 渔猎（fishing.py M65）----
    public int catches, anglers;
    public float fishStock, bestCatch, lakeLevel;

    // ---- 驯养（taming.py M75）----
    public int captured, tamed, mounts, herd;
    public float loyalty;

    // ---- 异种（xeno.py M62）----
    public float virus, panic;
    public int strains, zombies, giants, vampires, aliens, outbreaks;

    // ---- 超验飞升（ascension.py M64）----
    public int mechanized, fleshSuffered, cyborgs, transcended;
    public float sacrifice, aether;

    // ---- 秘法（magic.py L3；材料执行层由开关守护）----
    public float mana = 100f, manaMax = 100f, manaRegen = 0.5f;
    public int spellsKnown, casts;

    // ---- 修行（cultivation.py L3；村庄聚合口径）----
    public static final String[] REALMS = {"凡人", "练气", "筑基", "金丹", "元婴", "化神", "大乘", "渡劫"};
    public int meditators, realmIdx, breakthroughs;
    public float qi;

    // ---- 神话（myth.py M73）----
    public int legends, heroes, epics, oracles;
    public float mythValue;

    // ---- 梦境（dreamscape.py M72）----
    public int lucid, nightmares, dreamEcho;
    public float inspiration, dreaminess;

    // ---- 化学元素周期（chemistry.py M44；只读网格 + 开放标量）----
    public final LinkedHashMap<String, Float> elements = new LinkedHashMap<String, Float>();
    public final List<String> compounds = new ArrayList<String>();
    public int discoveries;
    public float chemPeriod;

    // ---- 主角成长（roleplay.py M81；Java 玩家已有 LV/HP → 只记成长里程碑）----
    public int deeds, questsDone;

    // ---- 个体层研究点（批次 3 自持池）----
    // 说明：Python 的 world._research 是全局池，被 tech/firearms/myth/dreamscape 共享；
    // 直接照搬会让「个体层消费」抽干「文明科技层」的研究点（实测 civ.research 175→6.4、科技永久停解锁）。
    // 故此处为个体层单列一个研究池（由 learning/dreamscape/myth 供养、firearms 消费），
    // 与 civ.research 物理隔离 → 两层各自演化、互不饥饿。
    public float research;

    public Individual() {
        for (String s : SPECIES) yield.put(s, 1.0f);
        for (String g : GENES) allele.put(g, 0.5f);
    }

    /** 可读快照（供门禁/日志；纯派生，不修改状态）。 */
    public String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("know=").append(round2(knowledge)).append(",lit=").append(round3(literacy))
          .append(",lib=").append(libraries).append(",gen=").append(generation)
          .append(",regr=").append(regressed).append(",reaw=").append(reawakened).append(';');
        sb.append("bold=").append(round3(bold)).append(",act=").append(round3(activity))
          .append(",aggro=").append(round3(aggro)).append(",scent=").append(round3(scent))
          .append(",bconf=").append(behaviorConflicts).append(';');
        sb.append("voices=").append(personasAssigned).append(",murmur=").append(murmurLines).append(';');
        sb.append("mut=").append(round3(mutRate)).append(",adapt=").append(round2(adapt)).append(",yield=");
        for (String s : SPECIES) sb.append(s).append(':').append(round2(yield.get(s))).append(' ');
        sb.append(';');
        sb.append("evoGen=").append(evoGeneration).append(",mf=").append(round4(meanFitness))
          .append(",peakF=").append(round4(peakFitness)).append(",adapt2=").append(round3(adaptation))
          .append(",eAdapt=").append(adapted).append(",eRegr=").append(evoRegressed)
          .append(",spec=").append(speciated).append(",grazer=").append(grazer)
          .append(",browser=").append(browser).append(",allele=");
        for (String g : GENES) sb.append(g).append(':').append(round3(allele.get(g))).append(' ');
        sb.append(';');
        sb.append("sick=").append(sick).append(",rec=").append(recovered).append(",imm=").append(round2(immunity))
          .append(",fever=").append(round3(feverPressure)).append(';');
        sb.append("robots=").append(robots).append(",rMass=").append(round1(robotMass))
          .append(",rAwake=").append(robotAwaken).append(",avgAware=").append(round2(avgAware()))
          .append(",rConf=").append(robotConflicts).append(",rRights=").append(robotRights).append(';');
        sb.append("bp=").append(blueprints).append(",parts=").append(parts).append(",guns=").append(gunsBuilt)
          .append(",diy=").append(customized).append(",pw=").append(round2(armPower))
          .append(",acc=").append(round2(armAccuracy)).append(",cal=").append(round2(armCaliber)).append(';');
        sb.append("fish=").append(round2(fishStock)).append(",catch=").append(catches)
          .append(",best=").append(round2(bestCatch)).append(",anglers=").append(anglers)
          .append(",lake=").append(round2(lakeLevel)).append(';');
        sb.append("cap=").append(captured).append(",tamed=").append(tamed).append(",loy=").append(round3(loyalty))
          .append(",mounts=").append(mounts).append(",herd=").append(herd).append(';');
        sb.append("virus=").append(round2(virus)).append(",strains=").append(strains)
          .append(",zomb=").append(zombies).append(",giants=").append(giants)
          .append(",vamp=").append(vampires).append(",aliens=").append(aliens)
          .append(",outbr=").append(outbreaks).append(",panic=").append(round2(panic)).append(';');
        sb.append("mech=").append(mechanized).append(",flesh=").append(fleshSuffered)
          .append(",cyb=").append(cyborgs).append(",trans=").append(transcended)
          .append(",sacr=").append(round2(sacrifice)).append(",aether=").append(round2(aether)).append(';');
        sb.append("mana=").append(round1(mana)).append(",spells=").append(spellsKnown)
          .append(",casts=").append(casts).append(';');
        sb.append("med=").append(meditators).append(",qi=").append(round2(qi))
          .append(",realm=").append(realmIdx).append(",bt=").append(breakthroughs).append(';');
        sb.append("myth=").append(round3(mythValue)).append(",leg=").append(legends)
          .append(",hero=").append(heroes).append(",epic=").append(epics).append(",oracle=").append(oracles).append(';');
        sb.append("lucid=").append(lucid).append(",night=").append(nightmares)
          .append(",insp=").append(round3(inspiration)).append(",echo=").append(dreamEcho)
          .append(",dreamy=").append(round3(dreaminess)).append(';');
        sb.append("elems=");
        for (String k : elements.keySet()) sb.append(k).append(':').append(round1(elements.get(k))).append(' ');
        sb.append(",cmpd=").append(compounds.size()).append(",disc=").append(discoveries).append(';');
        sb.append("deeds=").append(deeds).append(",quests=").append(questsDone)
          .append(",resrch=").append(round2(research));
        return sb.toString();
    }

    /** 平均意识（供 HUD/门禁）。 */
    public float avgAware() {
        return robots <= 0 ? 0f : round2(robotAwakeTotal / (float) robots);
    }

    private static float round1(float v) { return Math.round(v * 10f) / 10f; }
    private static float round2(float v) { return Math.round(v * 100f) / 100f; }
    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
    private static float round4(float v) { return Math.round(v * 10000f) / 10000f; }
}
