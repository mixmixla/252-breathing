package core.systems;

import core.agent.Npc;
import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.Individual;
import core.world.World;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 个体成长驱动系统（批次 3 · NPC-SOC-PORT 批次3）：把 Python 18 个「个体/超验/异种」系统
 * 接入 World.tick 主循环（注册于 registerDefaultSystems 末位，固定顺序保证确定性）。
 *
 * 固定子步序（interval 镜像 Python config 缺省）：
 *   learning(10) → behavior(8) → persona(20) → genetics(20) → evolution(10) → medicine(5)
 *   → robotics(10) → firearms(10) → fishing(8) → taming(8) → xeno(10) → ascension(12)
 *   → magic(12) → cultivation(20) → myth(10) → dreamscape(8) → chemistry(12) → roleplay(20)
 *
 * 零漂移铁律（与 NpcSystem/SocialSystem/StorytellerSystem/CivilizationSystem 同）：
 *  - 只改 {@link Individual} 开放标量 + {@link core.world.Civilization} 开放标量 + events，
 *    均不进 hashState；绝不写 mat/mass/prosperity/skills/villageMemory/builtMass；
 *  - 随机只走 {@link World#simStream(String)}（主种子派生子流，不推进主 rng.state），绝不读 fxRng；
 *  - **材料执行层**由 {@link #MATERIAL_WORKS} 开关守护，默认关：
 *      · magic 的真实施法（写 temp / set_cell）
 *      · chemistry 的反应层（燃烧/氧化/熔炼/光合，写 mat/mass/temp）
 *    关闭时只跑「决策/数字层」与「只读网格的元素普查」→ 网格一字不改，指纹不变。
 *
 * 信号源替换（Java 无 Python 的辐射场/生态草量/深空信号/本地 LLM → 用等效来源合成，已在各处注明）。
 */
public final class IndividualSystem implements System {

    /** 材料执行层总开关：默认关（守四门禁指纹基线）。开启后 magic/chemistry 才会写 temp/mat，
     *  属批次 4「文明/个体实体化」的职责。 */
    // 2026-09-16：去掉 final —— 与 CalamitySystem/CivilizationSystem/MatterSystem 的同名开关同质。
    // final 会让 `if (MATERIAL_WORKS)` 被编译期消除（材料层永远不可能被执行），而另外三处可以运行时打开；
    // audit_invariants.py 的 C10 会断言这四处同名概念必须同值且都可翻转。
    public static boolean MATERIAL_WORKS = false;

    // ---- 医学参数（对齐 Python systems/medicine.py 缺省值）----
    private static final int   MED_INFECTIOUS_WINDOW = 12;    // until_infect：仅病程前 12 才具传染性
    private static final float MED_SPREAD_RATE = 0.08f;       // spread_rate：逐对传播概率
    private static final int   MED_DURATION = 30;             // duration：病程时长（tick）
    private static final float MED_DECAY = 5f / 200f;         // 免疫随时间的衰减率（immunity=200）

    private static final float PI2 = (float) (2.0 * Math.PI);

    /** 村口碎语池（persona 降级口径：GL 未含 CJK，仅写事件日志）。 */
    private static final String[] BARKS = {
            "今儿日头正好，晒一晒田里的庄稼。",
            "井口的水又涨了三分，真是喜事。",
            "听说邻村来了一队货商，不知道捎了什么好物件。",
            "风里有股青草味，是要落雨的前兆。",
            "巷口那只猫今天又叼走了我的鱼干。",
            "柴火备足了，入冬心里就不慌。",
    };

    @Override
    public String name() { return "IndividualSystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        Individual ind = w.individual;
        if (!ind.evoInitialized) initGenePool(w, ind);

        if (w.tick % 10 == 0) learning(w, ind);
        if (w.tick % 8 == 0)  behavior(w, ind);
        if (w.tick % 20 == 0) persona(w, ind);
        if (w.tick % 20 == 0) genetics(w, ind);
        if (w.tick % 10 == 0) evolution(w, ind);
        if (w.tick % 5 == 0)  medicine(w, ind);
        if (w.tick % 10 == 0) robotics(w, ind);
        if (w.tick % 10 == 0) firearms(w, ind);
        if (w.tick % 8 == 0)  fishing(w, ind);
        if (w.tick % 8 == 0)  taming(w, ind);
        if (w.tick % 10 == 0) xeno(w, ind);
        if (w.tick % 12 == 0) ascension(w, ind);
        if (w.tick % 12 == 0) magic(w, ind);
        if (w.tick % 20 == 0) cultivation(w, ind);
        if (w.tick % 10 == 0) myth(w, ind);
        if (w.tick % 8 == 0)  dreamscape(w, ind);
        if (w.tick % 12 == 0) chemistry(w, ind);
        if (w.tick % 20 == 0) roleplay(w, ind);
    }

    // ================================================================ 教育/知识（M35）
    private void learning(World w, Individual ind) {
        int alive = alive(w);
        float wealth = w.prosperity;                                  // 替代 Python trade.coins
        float lv = w.civ != null ? w.civ.landValue : 0f;
        int want = Math.min(6, Math.max(1, alive / 4 + (int) (wealth / 120f) + (int) (lv * 2f)));
        ind.libraries = Math.max(ind.libraries, want);
        ind.students = (int) (alive * Math.min(1f, 0.5f + ind.libraries * 0.08f));
        ind.literacy = round3(Math.min(1f, 0.1f + ind.students / (float) Math.max(1, alive) * 0.6f));

        float gain = ind.literacy * ind.libraries * 2f + ind.generation * 0.5f;
        ind.knowledge = Math.min(100000f, ind.knowledge + gain);
        if (!ind.regressing) ind.peak = Math.max(ind.peak, ind.knowledge);
        ind.peakAlive = Math.max(ind.peakAlive, alive);
        if (w.tick % 240 == 0) {
            ind.generation++;
            w.log("learning", "generation", "{gen=" + ind.generation + "}", "新代登上舞台，知识薪火相传");
        }

        boolean collapse = ind.peakAlive > 0 && alive < ind.peakAlive * 0.6f;
        if (collapse && !ind.regressing) {
            ind.regressing = true;
            ind.regressed++;
            ind.knowledge = Math.max(0f, ind.knowledge * 0.7f);
            w.log("learning", "regress", "{knowledge=" + round1(ind.knowledge) + "}", "人口凋零，知识按比例断代（黑暗期）");
        } else if (!collapse && ind.regressing) {
            ind.regressing = false;
            w.log("learning", "recover", "{}", "人口回升，黑暗期结束，知识重新开始积累");
        }
        if (ind.regressed >= 1 && !ind.regressing && ind.knowledge > ind.peak && !ind.reawakenedEvt) {
            ind.reawakened++;
            ind.reawakenedEvt = true;
            w.log("learning", "reawaken", "{knowledge=" + round1(ind.knowledge) + "}", "知识重越历史峰值，文明迎来再启蒙");
        }
        // 教育供养**个体层**研究池（供 firearms/magic 消费）；不与 civ.research（文明科技池）共享，
        // 避免个体层消费把文明池抽干（实测 civ.research 会被清零致 medicine 永久不解锁）。
        ind.research += ind.literacy * ind.libraries * 0.10f;
    }

    // ================================================================ 行为本能（M39）
    private void behavior(World w, Individual ind) {
        int t = w.tick;
        int n = w.npcs.size();
        float dayNorm = 0.5f + 0.5f * (float) StrictMath.cos(PI2 * t / 24.0);
        ind.activity = round3(0.35f + 0.55f * dayNorm);

        SeededRNG r = w.simStream("behavior:" + t);
        ind.bold = round3(clamp(0f, 1f, 0.5f + gauss(r) * 0.1f));
        ind.sociable = round3(clamp(0f, 1f, 0.5f + gauss(r) * 0.08f));

        float scarcity = clamp(0f, 1f, 1f - w.prosperity / 100f);      // 替代 Python 生态草量
        float crowding = n / 40f;
        float aggro = Math.min(1f, 0.2f + 0.55f * scarcity + 0.15f * crowding + ind.bold * 0.1f);
        ind.aggro = round3(aggro);
        ind.scent = round3(0.5f + 0.3f * (aggro > 0.5f ? aggro : 1f - aggro));
        if (aggro > 0.62f) {
            ind.behaviorConflicts++;
            if (ind.behaviorConflicts % 20 == 1) {
                w.log("behavior", "conflict", "{aggro=" + round2(aggro) + ",scarcity=" + round2(scarcity) + "}",
                        "食物稀缺/人口拥挤，群体攻击性越线，冲突升级");
            }
        }
        if (ind.scent > 0.9f) ind.behaviorConflicts++;   // 忠实保留（scent≤0.8，实为死分支）
        if (ind.behaviorConflicts > 60) ind.behaviorConflicts = 60;
    }

    // ================================================================ 角色引擎（M76；无本地 LLM）
    private void persona(World w, Individual ind) {
        int alive = alive(w);
        if (alive > ind.personasAssigned) ind.personasAssigned = alive;   // 角色档案随人口补齐（计数口径）
        SeededRNG r = w.simStream("persona:" + w.tick);
        if (alive > 0 && r.nextFloat() < 0.6f) {
            int idx = r.nextInt(w.npcs.size());
            Npc npc = w.npcs.get(idx);
            if (npc != null && !npc.dead()) {
                String bark = BARKS[r.nextInt(BARKS.length)];
                ind.murmurLines++;
                w.log("persona", "murmur", "{who=" + npc.name + "}", bark);
            }
        }
    }

    // ================================================================ 宏观基因（M27）
    private void genetics(World w, Individual ind) {
        SeededRNG r = w.simStream("genetics:" + w.tick);
        ind.mutRate = round3(0.2f);                                  // Java 无辐射场 → 取基线
        float seasonal = (float) StrictMath.cos(PI2 * w.tick / 240.0);     // 替代 climate.temp_bias
        float pressure = Math.abs(seasonal) * 0.03f;
        for (int k = 0; k < 2; k++) {
            String sp = Individual.SPECIES[r.nextInt(Individual.SPECIES.length)];
            float drift = 0.04f * (0.5f + pressure * 8f);
            float d = (float) r.uniform(-drift, drift);
            float cur = ind.yield.get(sp);
            ind.yield.put(sp, Math.min(1.6f, Math.max(0.5f, cur + d)));
            ind.adapt += Math.abs(d);
        }
        ind.adapt = round2(ind.adapt);
    }

    // ================================================================ 分子进化（M37）
    private void initGenePool(World w, Individual ind) {
        SeededRNG r = w.simStream("evolution:init");                 // 固定名 → 同种子同初值
        for (int i = 0; i < Individual.EVO_K; i++)
            for (int g = 0; g < Individual.GENES.length; g++)
                ind.genePool[i][g] = r.nextFloat();
        ind.evoInitialized = true;
    }

    private void evolution(World w, Individual ind) {
        int K = Individual.EVO_K;
        float ph = PI2 * w.tick / 150f;
        float warm = (float) StrictMath.cos(ph);
        float scarc = (float) ((StrictMath.sin(ph) + 1.0) / 2.0);
        float bimo = (float) ((StrictMath.cos(2.0 * ph) + 1.0) / 2.0);
        float optHeat = clamp(0.02f, 0.98f, 0.5f + 0.4f * warm);
        float optSpend = clamp(0.05f, 0.95f, 0.85f - 0.78f * scarc);
        float optHard = 0.1f;                                        // rad_n=0（无辐射场）

        float[] f = new float[K];
        for (int i = 0; i < K; i++) f[i] = fitness(ind.genePool[i], optHeat, optSpend, optHard, bimo);

        // 自然选择：截断保留适应度前 keep 名（并列按下标升序，确定性）
        int keep = Math.max(1, (int) (K * 0.4f));
        Integer[] idx = new Integer[K];
        for (int i = 0; i < K; i++) idx[i] = i;
        final float[] ff = f;
        java.util.Arrays.sort(idx, (a, b) -> ff[a] != ff[b] ? Float.compare(ff[b], ff[a]) : Integer.compare(a, b));
        int[] survivors = new int[keep];
        for (int i = 0; i < keep; i++) survivors[i] = idx[i];

        // 遗传：幸存者两两重组 + 突变，填满种群
        SeededRNG r = w.simStream("evolution:" + w.tick);
        float sigma = 0.05f;
        float[][] ng = new float[K][Individual.GENES.length];
        for (int k = 0; k < K; k++) {
            float[] pa = ind.genePool[survivors[r.nextInt(keep)]];
            float[] pb = ind.genePool[survivors[r.nextInt(keep)]];
            for (int g = 0; g < Individual.GENES.length; g++) {
                float base = r.nextFloat() < 0.5f ? pa[g] : pb[g];
                ng[k][g] = clamp(0f, 1f, base + gauss(r) * sigma);
            }
        }
        for (int k = 0; k < K; k++) java.lang.System.arraycopy(ng[k], 0, ind.genePool[k], 0, Individual.GENES.length);
        ind.evoGeneration++;

        // 观测
        float mf = 0f;
        for (int i = 0; i < K; i++) mf += fitness(ind.genePool[i], optHeat, optSpend, optHard, bimo);
        mf /= K;
        ind.adaptation = round3(ind.adaptation + Math.abs(mf - ind.meanFitness));
        ind.meanFitness = mf;
        int grazer = 0;
        for (int i = 0; i < K; i++) if (ind.genePool[i][3] < 0.5f) grazer++;
        ind.grazer = grazer; ind.browser = K - grazer;
        for (int g = 0; g < Individual.GENES.length; g++) {
            float s = 0f;
            for (int i = 0; i < K; i++) s += ind.genePool[i][g];
            ind.allele.put(Individual.GENES[g], round3(s / K));
        }

        // 退化 / 进化（上升沿）
        float peak = ind.peakFitness;
        if (peak > 0 && mf < peak * 0.55f) {
            if (ind.atPeak) {
                ind.atPeak = false; ind.evoRegressed++;
                w.log("evolution", "regress", "{fitness=" + round4(mf) + "}", "环境翻面，旧优势基因反成劣势，种群适应度滑坡（退化）");
            }
        }
        if (mf > peak * 0.90f) {
            if (mf > peak) ind.peakFitness = mf;
            if (!ind.atPeak) {
                ind.atPeak = true; ind.adapted++;
                w.log("evolution", "adapt", "{fitness=" + round4(mf) + "}", "种群向新环境最优靠拢，适应度回弹（进化）");
            }
        }
        // 物种分化：双峰选择且两群均持续存在
        if (bimo > 0.55f) {
            int specMin = K / 6;
            float meanDiet = 0f;
            for (int i = 0; i < K; i++) meanDiet += ind.genePool[i][3];
            meanDiet /= K;
            if (Math.min(grazer, K - grazer) >= specMin && Math.abs(meanDiet - 0.5f) >= 0.15f) {
                if (!ind.inBimodal) {
                    ind.inBimodal = true; ind.speciated++;
                    w.log("evolution", "speciate", "{grazer=" + grazer + ",browser=" + (K - grazer) + "}",
                            "双峰食物选择把种群劈成两个生态位群（物种分化）");
                }
            }
        } else {
            ind.inBimodal = false;
        }
    }

    private static float fitness(float[] g, float oh, float ops, float ohd, float bimo) {
        float heat = gaussK(g[0], oh, 0.32f);
        float spend = gaussK(g[1], ops, 0.30f);
        float hard = gaussK(g[2], ohd, 0.35f);
        float diet = bimo > 0.55f
                ? Math.max(gaussK(g[3], 0.20f, 0.14f), gaussK(g[3], 0.80f, 0.14f))
                : gaussK(g[3], 0.5f, 0.45f);
        return Math.max(1e-12f, heat * spend * hard * diet);
    }

    private static float gaussK(float off, float opt, float w) {
        float d = (off - opt) / Math.max(1e-9f, w);
        return (float) StrictMath.exp(-(double) (d * d));
    }

    // ================================================================ 疫病/医学（M22，聚合 SIR）
    private void medicine(World w, Individual ind) {
        int alive = alive(w);
        boolean hasMedicine = w.civ != null && w.civ.unlockedNames() != null
                && w.civ.unlockedNames().contains("medicine");
        SeededRNG r = w.simStream("medicine:" + w.tick);

        if (ind.sick == 0 && alive > 0 && r.nextFloat() < 0.02f) {
            ind.sick = 1;
            w.log("medicine", "infect", "{seed=1}", "村中出现了零号病人");
        }
        if (ind.sick > 0) {
            int susceptible = Math.max(0, alive - ind.sick - (int) ind.immunity);
            // 逐对口径（对齐 Python medicine._spread）：每个具传染性的病人在接触半径内对每个易感者
            // 按 spread_rate 独立掷骰。村庄尺度下近似 = 具传染者数 × 易感者数 × 概率 × 密度因子。
            int carriers = Math.min(ind.sick, MED_INFECTIOUS_WINDOW);
            float density = Math.min(1f, alive / 24f);                // 满员（容量 24）= 1
            int newInf = Math.round(carriers * susceptible * MED_SPREAD_RATE * density);
            if (newInf > susceptible) newInf = susceptible;
            ind.sick += newInf;
            int rec = Math.round(ind.sick * (5f / MED_DURATION));     // 病程按 interval 推进
            if (ind.sick > 0 && rec == 0) rec = 1;
            rec = Math.min(rec, ind.sick);
            ind.sick -= rec;
            ind.immunity += rec;
            ind.recovered += rec;
            if (hasMedicine && ind.sick > 0) {
                int cured = Math.max(1, ind.sick / 2);
                ind.sick -= cured;
                ind.immunity += cured;
                ind.recovered += cured;
                w.log("medicine", "recover", "{via=medicine}", "草药见效，病患加速痊愈");
            }
            ind.immunity = Math.max(0f, ind.immunity * (1f - MED_DECAY));   // 免疫随时间衰减
            ind.feverPressure = round3(Math.min(1f, ind.sick / (float) Math.max(1, alive)));
            if (ind.sick == 0) w.log("medicine", "recover", "{via=immunity}", "疫情告一段落，村人获得免疫");
        } else {
            ind.feverPressure = round3(Math.max(0f, ind.feverPressure - 0.1f));
        }
    }

    // ================================================================ 机器人（M29）
    private void robotics(World w, Individual ind) {
        // 制造：从工业齿轮抽料造机器人（开放标量转移，不碰网格质量）
        if (w.civ != null && ind.robots < 24 && w.civ.gear > 0f) {
            float cost = 30f;
            float rate = (0.5f + 0.2f * ind.robots) * (0.5f + Math.min(1f, w.civ.power));
            float take = Math.min(w.civ.gear, rate * cost);
            w.civ.gear -= take;
            ind.robotMass += take;
            while (ind.robotMass >= cost * (ind.robots + 1) && ind.robots < 24) {
                ind.robots++;
                w.log("robotics", "manufacture", "{robots=" + ind.robots + "}", "工厂落成一台机器人工位");
            }
        }
        // 意识累积（聚合口径：总意识池 / 单机阈值）
        if (ind.robots > 0 && w.civ != null) {
            float th = 12f;
            float inc = 0.15f * (0.5f + 0.3f * alive(w)) * (0.5f + Math.min(1f, w.civ.power) * 0.5f);
            ind.robotAwakeTotal += inc * ind.robots;
            while (ind.robotAwakeTotal >= th && ind.robotAwaken < ind.robots) {
                ind.robotAwaken++;
                ind.robotAwakeTotal -= th;
                w.log("robotics", "awaken", "{robot=" + ind.robotAwaken + "}", "某台机器觉醒了");
            }
        }
        // 三大定律冲突（危害指令源：Java 取战争/民怨）
        if (ind.robots > 0 && w.civ != null && w.civ.war) ind.robotConflicts++;
        // AI 权利：觉醒数跨阈值 → 阶级涌现
        if (!ind.robotRights && ind.robotAwaken >= 1) {
            ind.robotRights = true;
            for (Npc npc : w.npcs) {
                if (npc.dead()) continue;
                for (Map.Entry<String, Float> e : npc.social.affinity.entrySet()) {
                    e.setValue(Math.max(-1f, Math.min(1f, e.getValue() - 0.02f)));
                }
            }
            w.log("robotics", "rights", "{}", "机器人权利被承认，AI 与人类进入社会对齐阶段");
        }
    }

    // ================================================================ 枪械工坊（M67）
    private void firearms(World w, Individual ind) {
        SeededRNG r = w.simStream("firearms:" + w.tick);
        // 消费**个体层**研究池（不夺 civ.research）：Python world._research 是全局池，
        // Java 拆分为独立池避免与文明科技层互相饥饿。
        if (ind.research >= 8f && r.nextFloat() < 0.3f) {
            ind.research -= 8f;
            ind.blueprints++;
            if (ind.blueprints <= 3) w.log("firearms", "blueprint", "{blueprints=" + ind.blueprints + "}", "一张全新的枪械蓝图出图");
        }
        if (ind.blueprints > ind.parts && r.nextFloat() < 0.4f) {
            ind.parts++;
            if (ind.parts <= 3) w.log("firearms", "parts", "{parts=" + ind.parts + "}", "枪管、机匣、握把逐件下线");
        }
        if (ind.parts >= 3 && r.nextFloat() < 0.3f) {
            ind.parts -= 3;
            ind.gunsBuilt++;
            if (ind.gunsBuilt <= 3) w.log("firearms", "build", "{built=" + ind.gunsBuilt + "}", "一支新枪走下装配台");
        }
        if (ind.gunsBuilt > ind.customized && r.nextFloat() < 0.5f) {
            ind.customized++;
            ind.armPower = round2(Math.min(5f, ind.armPower + 0.3f * r.nextFloat()));
            ind.armAccuracy = round2(Math.min(1f, ind.armAccuracy + 0.1f * r.nextFloat()));
            ind.armCaliber = round1(Math.min(20f, ind.armCaliber + 0.5f + 0.5f * r.nextFloat()));
            if (ind.customized <= 3) w.log("firearms", "diy", "{power=" + ind.armPower + "}", "魔改装配完毕，这支枪独一无二");
        }
        ind.armAccuracy = round3(Math.max(0f, ind.armAccuracy - 0.005f));
    }

    // ================================================================ 渔猎（M65）
    private void fishing(World w, Individual ind) {
        SeededRNG r = w.simStream("fishing:" + w.tick);
        int nWet = w.waterSurfaceCells.size();                      // 替代 Python water_level 覆盖格数
        float cap = 30f * (0.3f + 0.7f * Math.min(1f, nWet / 60f));
        ind.fishStock = round2(Math.min(cap, ind.fishStock + 0.5f));
        ind.lakeLevel = round2(Math.min(1f, nWet / 60f));
        if (nWet >= 1 && ind.fishStock >= 1f && r.nextFloat() < 0.4f) {
            ind.anglers++;
            float size = round2(ind.fishStock * 0.6f * (0.7f + 0.6f * r.nextFloat()));
            float take = round2(0.5f + 1.2f * r.nextFloat());
            ind.fishStock = round2(Math.max(0f, ind.fishStock - take));
            ind.catches++;
            ind.bestCatch = Math.max(ind.bestCatch, size);
            if (ind.catches <= 3) w.log("fishing", "catch", "{weight=" + take + "}", "渔人甩竿入水，拉起一尾沉甸甸的鱼");
        }
        if (ind.catches >= 8) ind.fishStock = round2(Math.max(0f, ind.fishStock - 0.3f));   // 可持续压力
    }

    // ================================================================ 百兽驯养（M75）
    private void taming(World w, Individual ind) {
        SeededRNG r = w.simStream("taming:" + w.tick);
        int wild = w.beasts.size() + alive(w);
        if (wild < 1 && ind.captured >= 1) return;
        if (wild > 0 && r.nextFloat() < 0.4f) {
            ind.captured++;
            if (ind.captured <= 4) w.log("taming", "capture", "{captured=" + ind.captured + "}", "猎人满载而归");
        }
        if (ind.captured > ind.tamed && r.nextFloat() < 0.5f) {
            ind.tamed++;
            ind.loyalty = round4(Math.min(1f, ind.loyalty + 0.15f));
        }
        if (ind.tamed > 0) ind.loyalty = round4(Math.min(1f, ind.loyalty + ind.tamed * 0.004f - 0.001f));
        if (ind.tamed >= 1 && ind.loyalty >= 0.5f && ind.mounts < ind.tamed) {
            ind.mounts = Math.min(ind.tamed, ind.mounts + 1);
            if (w.civ != null) w.civ.unrest = Math.max(0f, w.civ.unrest - 0.004f);   // 坐骑壮胆，压民怨
            if (ind.mounts <= 3) w.log("taming", "mount", "{loyalty=" + ind.loyalty + "}", "一声轻啸，野兽伏首，成了村民的坐骑伙伴");
        }
        ind.herd = ind.tamed + ind.captured;
    }

    // ================================================================ 异种纪元（M62）
    private void xeno(World w, Individual ind) {
        SeededRNG r = w.simStream("xeno:" + w.tick);
        int sick = ind.sick;
        boolean signal = ind.knowledge >= 600f;                        // 替代 Python 深空信号：知识积累到可解码深空回响
        float virRate = 0.18f + 0.05f * sick;
        if (r.nextFloat() < Math.min(0.9f, virRate)) {
            ind.strains++;
            ind.virus = round3(Math.min(1.5f, ind.virus + 0.1f));
            if (ind.strains <= 3) w.log("xeno", "strain", "{strains=" + ind.strains + "}", "病毒变异出新毒株，病原库扩张");
        }
        if (ind.virus >= 0.5f && r.nextFloat() < 0.12f) {
            ind.zombies += 2; ind.outbreaks++;
            ind.panic = round3(Math.min(1f, ind.panic + 0.2f));
            if (ind.outbreaks <= 3) w.log("xeno", "zombie", "{zombies=" + ind.zombies + "}", "毒株失控，疫区站起一队丧失心智的丧尸");
        }
        if (r.nextFloat() < 0.06f) {
            ind.giants++;
            ind.panic = round3(Math.min(1f, ind.panic + 0.1f));
            if (ind.giants <= 2) w.log("xeno", "giant", "{giants=" + ind.giants + "}", "远古巨人的血脉悄然觉醒");
        }
        if (r.nextFloat() < 0.05f) {
            ind.vampires++;
            ind.panic = round3(Math.min(1f, ind.panic + 0.12f));
            if (ind.vampires <= 2) w.log("xeno", "vampire", "{vampires=" + ind.vampires + "}", "吸血鬼的诅咒悄然蔓延");
        }
        if (signal && r.nextFloat() < 0.07f) {
            ind.aliens++;
            ind.panic = round3(Math.min(1f, ind.panic + 0.15f));
            if (ind.aliens <= 2) w.log("xeno", "alien", "{aliens=" + ind.aliens + "}", "深空回响之后，外星访客降临");
        }
        ind.panic = round3(Math.max(0f, ind.panic - 0.01f));
    }

    // ================================================================ 超验飞升（M64）
    private void ascension(World w, Individual ind) {
        SeededRNG r = w.simStream("ascension:" + w.tick);
        int robots = ind.robots;
        float research = ind.research;                                 // 个体层研究池
        float mechRate = 0.16f + 0.04f * Math.min(8, robots) + 0.001f * Math.min(200f, research);
        if (r.nextFloat() < Math.min(0.9f, mechRate)) {
            ind.mechanized++; ind.cyborgs++;
            ind.aether = round3(ind.aether + 0.15f);
            if (ind.mechanized <= 3) w.log("ascension", "mechanize", "{cyborgs=" + ind.cyborgs + "}", "血肉换作机械躯壳，踏上机械飞升之路");
        }
        if (r.nextFloat() < 0.14f) {
            ind.fleshSuffered++;
            ind.sacrifice = round3(Math.min(2f, ind.sacrifice + 0.12f));
            ind.aether = round3(ind.aether + 0.12f);
            if (ind.fleshSuffered <= 3) w.log("ascension", "flesh", "{sacrifice=" + ind.sacrifice + "}", "血肉之躯被逼至极限");
        }
        if (ind.aether >= 0.9f && r.nextFloat() < 0.2f) {
            ind.transcended++;
            ind.aether = round3(Math.max(0f, ind.aether - 0.9f));
            if (ind.transcended <= 2) w.log("ascension", "transcend", "{transcended=" + ind.transcended + "}", "有人完成超验飞升，脱离血肉凡躯");
        }
        if (ind.mechanized >= 3) ind.sacrifice = round3(Math.max(0f, ind.sacrifice - 0.02f));   // 两路相斥
    }

    // ================================================================ 秘法（L3；材料执行层守护）
    private void magic(World w, Individual ind) {
        ind.mana = Math.min(ind.manaMax, ind.mana + ind.manaRegen);
        if (ind.research >= 10f * (ind.spellsKnown + 1) && ind.spellsKnown < 6) {   // 个体层研究池
            ind.research -= 10f;
            ind.spellsKnown++;
            w.log("magic", "learn", "{spells=" + ind.spellsKnown + "}", "学者参详法则，习得一门新法术");
        }
        SeededRNG r = w.simStream("magic:" + w.tick);
        if (ind.spellsKnown > 0 && ind.mana >= 5f && r.nextFloat() < 0.2f) {
            ind.mana -= 5f;
            ind.casts++;
            if (ind.casts <= 3) w.log("magic", "cast", "{spell=" + ind.spellsKnown + "}", "施法者诵念真言，法则为之一改");
            if (MATERIAL_WORKS) {
                // 材料执行层（默认关）：此处才会真正写 temp / set_cell —— 归批次 4 文明实体化。
            }
        }
    }

    // ================================================================ 修行（L3；村庄聚合口径）
    private void cultivation(World w, Individual ind) {
        int temples = w.civ != null ? w.civ.temples : 0;
        float faith = w.civ != null ? w.civ.faith : 0f;
        ind.meditators = Math.min(alive(w), temples);
        ind.qi = round3(ind.qi + ind.meditators * 0.5f + faith * 2f);
        float th = 8f * (ind.realmIdx + 1);
        if (ind.realmIdx + 1 < Individual.REALMS.length && ind.qi >= th) {
            ind.realmIdx++;
            ind.breakthroughs++;
            ind.qi = 0f;
            w.log("cultivation", "breakthrough", "{realm=" + Individual.REALMS[ind.realmIdx] + "}", "村中修行者突破至新的境界");
        }
    }

    // ================================================================ 神话纪元（M73）
    private void myth(World w, Individual ind) {
        SeededRNG r = w.simStream("myth:" + w.tick);
        int alive = alive(w);
        ind.mythValue = round4(Math.min(1f, ind.mythValue + alive * 0.008f - 0.004f));
        ind.legends = (int) (ind.mythValue * 20f);
        if (ind.legends > ind.heroes * 6f && r.nextFloat() < 0.2f) {
            ind.heroes++;
            ind.legends = (int) (ind.legends * 0.6f);
            if (ind.heroes <= 3) w.log("myth", "hero", "{heroes=" + ind.heroes + "}", "又一位英雄在火堆旁诞生，被孩童们反复传唱");
        }
        if (ind.heroes >= 3 && ind.heroes > ind.epics) {
            ind.epics = ind.heroes;
            if (ind.epics <= 3) w.log("myth", "epic", "{epics=" + ind.epics + "}", "英雄的功业被谱成史诗，镌进长卷");
        }
        if (ind.mythValue >= 0.4f && r.nextFloat() < 0.15f) {
            ind.oracles++;
            ind.mythValue = round4(Math.max(0f, ind.mythValue - 0.2f));
            if (w.civ != null) {
                ind.research += 1.2f;                                 // 神谕供养个体层研究池
                w.civ.faith = Math.min(1f, w.civ.faith + 0.01f);      // 神谕点燃宗教虔诚（跨系统联动，纯供养不夺）
            }
            if (ind.oracles <= 3) w.log("myth", "oracle", "{}", "神谕自云外垂落，祭司恍然");
        }
    }

    // ================================================================ 梦生化境（M72）
    private void dreamscape(World w, Individual ind) {
        SeededRNG r = w.simStream("dreamscape:" + w.tick);
        int alive = alive(w);
        ind.dreaminess = round4(Math.min(1f, ind.dreaminess + alive * 0.004f - 0.002f));
        if (r.nextFloat() < 0.3f && ind.dreaminess >= 0.05f) {
            ind.lucid++;
            ind.inspiration = round3(Math.min(1f, ind.inspiration + 0.12f));
            ind.research += 0.6f;                                     // 灵感供养个体层研究池
            if (ind.lucid <= 4) w.log("dreamscape", "lucid", "{inspiration=" + ind.inspiration + "}", "有人在梦中睁眼，灵感如泉涌");
        }
        if (r.nextFloat() < 0.18f && ind.dreaminess >= 0.08f) {
            ind.nightmares++;
            if (ind.nightmares <= 4) w.log("dreamscape", "nightmare", "{}", "梦境深处涌起黑色的潮，有人惊醒");
        }
        if (ind.inspiration >= 0.6f && r.nextFloat() < 0.3f) {
            ind.dreamEcho++;
            ind.inspiration = round3(Math.max(0f, ind.inspiration - 0.35f));
        }
        if (ind.inspiration >= 0.25f) ind.mythValue = round4(Math.min(1f, ind.mythValue + 0.005f));   // 梦境孕育传说
    }

    // ================================================================ 化学元素周期（M44；只读网格）
    private void chemistry(World w, Individual ind) {
        int wood = 0, leaf = 0, stone = 0, sand = 0, water = 0, ironOre = 0;
        long vol = (long) w.SX * w.SY * w.SZ;
        if (vol <= 2_000_000L) {
            for (int x = 0; x < w.SX; x++)
                for (int y = 0; y < w.SY; y++)
                    for (int z = 0; z < w.SZ; z++) {
                        int b = w.mat[x][y][z];
                        if (b == Blocks.WOOD.index) wood++;
                        else if (b == Blocks.LEAF.index) leaf++;
                        else if (b == Blocks.STONE.index) stone++;
                        else if (b == Blocks.SAND.index) sand++;
                        else if (b == Blocks.WATER.index) water++;
                        else if (b == Blocks.IRON_ORE.index) ironOre++;
                    }
        }
        float carbon = 2f * wood + leaf;
        float oxygen = 0.5f * stone + 0.4f * water;
        float iron = ironOre + (w.civ != null ? w.civ.iron : 0f);
        float silicon = 0.6f * stone + sand;
        float calcium = 0.3f * stone;
        ind.elements.put("碳", round1(Math.max(0f, carbon) / 10f));
        ind.elements.put("氧", round1(Math.max(0f, oxygen) / 10f));
        ind.elements.put("铁", round1(Math.max(0f, iron) / 10f));
        ind.elements.put("硅", round1(Math.max(0f, silicon) / 10f));
        ind.elements.put("钙", round1(Math.max(0f, calcium) / 10f));

        addCompound(w, ind, "青铜合金", (w.civ != null ? w.civ.gear : 0f) + ind.elements.get("碳"));
        addCompound(w, ind, "硅酸盐玻璃", ind.elements.get("硅") + ind.elements.get("钙"));
        addCompound(w, ind, "熟铁", ind.elements.get("铁") + ind.elements.get("碳"));
        ind.chemPeriod = round3(1f - (float) (StrictMath.cos(PI2 * w.tick / 100.0) / 2.0));
        if (MATERIAL_WORKS) {
            // 材料反应层（默认关）：燃烧/氧化/熔炼/光合会写 mat/mass/temp —— 归批次 4。
        }
    }

    private void addCompound(World w, Individual ind, String name, float val) {
        if (val > 0f && !ind.compounds.contains(name)) {
            ind.compounds.add(name);
            ind.discoveries++;
            if (ind.discoveries <= 3) {
                w.log("chemistry", "discover", "{compound=" + name + "}", "工艺推进，化学家炼出了「" + name + "」");
            }
        }
    }

    // ================================================================ 主角成长（M81；只记里程碑）
    private void roleplay(World w, Individual ind) {
        int alive = alive(w);
        int temples = w.civ != null ? w.civ.temples : 0;
        int done = 0;
        if (alive >= 8) done++;
        if (temples >= 1) done++;
        if (w.civ != null && !w.civ.unlockedNames().isEmpty()) done++;
        if (ind.heroes >= 1) done++;
        if (ind.mounts >= 1) done++;
        if (done > ind.questsDone) {
            ind.deeds += (done - ind.questsDone);
            ind.questsDone = done;
        }
    }

    // ================================================================ 工具
    private static int alive(World w) {
        int c = 0;
        for (Npc n : w.npcs) if (!n.dead()) c++;
        return c;
    }

    /** 标准正态（Box-Muller；SeededRNG 无 nextGaussian）。 */
    private static float gauss(SeededRNG r) {
        double u1 = Math.max(1e-9, r.nextDouble());
        double u2 = r.nextDouble();
        return (float) (Math.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * Math.PI * u2));
    }

    private static float clamp(float lo, float hi, float v) { return Math.max(lo, Math.min(hi, v)); }
    private static float round1(float v) { return Math.round(v * 10f) / 10f; }
    private static float round2(float v) { return Math.round(v * 100f) / 100f; }
    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
    private static float round4(float v) { return Math.round(v * 10000f) / 10000f; }

    /** 供 HUD：文明式 ASCII 摘要（语言无关）。 */
    public static String asciiSummary(Individual ind) {
        return "LIFE KNOW " + (int) ind.knowledge + "  MUT " + ind.mutRate
                + "  EVO " + ind.evoGeneration + "  SICK " + ind.sick
                + "  HERO " + ind.heroes + "  DREAM " + ind.lucid;
    }

    /** 供调试：未使用（保留以便扩展）。 */
    static LinkedHashMap<String, Float> emptyMap() { return new LinkedHashMap<String, Float>(); }
}
