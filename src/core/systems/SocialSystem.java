package core.systems;

import core.agent.Decision;
import core.agent.Mind;
import core.agent.Npc;
import core.agent.Social;
import core.agent.VillageSocial;
import core.agent.Body;
import core.rng.SeededRNG;
import core.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 社会核心驱动系统（批次 1 · NPC-SOC-PORT 批次1）：把家族/情绪/规范接入 World.tick 主循环。
 *
 * 忠实移植 Python 三系统并驱动批次 0 的 NPC 实体：
 *  - family.py  ：邻里日久生情 -> 结为连理 -> 生儿育女（后代继承父方职业/特质 + 血缘亲近）
 *  - emotion.py ：村庄情绪景观（气质基线 + 压力 + 联结 + 心情起伏 + 情绪崩塌上升沿）
 *  - norms.py   ：道德涌现（合作累积 - 违约冲击 -> 信任；稳定互惠 -> 社会规范 + 声誉惩戒）
 *  另：为 social 层补上邻里升温与情绪状态机（批次 0 未驱动，故 affinity 长期为 0）。
 *
 * 零漂移铁律（与 NpcSystem 同）：
 *  - 只改 NPC 实体内部态 + {@link VillageSocial} 开放标量 + events（均不进 hashState）；
 *  - 绝不写 mat/mass/prosperity/skills/villageMemory；绝不读 rng 主状态或 fxRng；
 *  - 本系统不使用任何随机源（婚育/情绪/规范均确定性推导），故指纹与“无本系统”基线逐字节一致。
 */
public final class SocialSystem implements System {

    // ---- 家族参数（镜像 Python family.py 常量）----
    public static final float MARRIAGE_TH = 0.44f;     // 双方互亲疏门槛
    public static final float MARRIAGE_DIST = 2.5f;    // 结亲需相近（x,z 曼哈顿）
    public static final int REPRO_COOLDOWN = 30;       // 两次生育最小间隔(tick)
    public static final int MAX_CHILD = 3;             // 每对夫妻最多后代
    public static final float KINSHIP = 0.55f;         // 后代-父母初始亲疏
    public static final float KIN_CAP = 0.8f;          // 血缘亲疏上限
    /** 聚落容量上限（生态承载力）：活口达到此值即停止结亲/生育，令村庄稳定而非滚雪球。
     *  与 emotion() 中 connected 的人口归一化口径 (/24) 一致；纯确定性常量，无 rng。 */
    public static final int MAX_POPULATION = 24;

    // ---- 邻里/节奏参数 ----
    public static final float NEIGHBOR_R = 3.0f;       // 邻里半径（x,z 曼哈顿）
    /** 子代出生环带的内半径（格）：出生点落在村心周围 [BIRTH_MIN_R, NpcSystem.VILLAGE_RADIUS] 环内，
     *  既不挤在村心，又不生出聚落外。P1：锚定村心，消除「子代位置随父母实时站位漂移」的站位依赖。 */
    public static final float BIRTH_MIN_R = 6.0f;
    private static final int EMO_INTERVAL = 8;         // 情绪推进间隔(tick)
    private static final int NORM_INTERVAL = 10;       // 规范推进间隔(tick)

    @Override
    public String name() { return "SocialSystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        VillageSocial vs = w.social;
        adjacencyAndMood(w);
        if (w.tick % EMO_INTERVAL == 0) emotion(w, vs);
        if (w.tick % NORM_INTERVAL == 0) norms(w, vs);
        family(w, vs);
    }

    // ---------------------------------------------------------------- 邻里升温 + 情绪状态机

    /**
     * M3② 实体查询去 O(N²)：邻里关系从「双重循环全配对」改为「均匀网格邻域查询」。
     *
     * 旧实现对每口人 i 都扫一遍全部 j（N² 次曼哈顿距离）。当 N 被人口上限锁在 24 时代价可忽略，
     * 但**无缝大世界**必然放宽人口上限（多个聚落、迁徙、商队），N 上到数百即成为每 tick 热点。
     * 本实现把 N 口人按格边 = NEIGHBOR_R 的均匀网格分桶，每口人只查自身格 + 8 邻格（曼哈顿
     * 半径 R 的查询只需 3×3 邻格即可覆盖），复杂度 O(N + Σ k)，k 为每格平均人数（远小于 N）。
     *
     * 确定性保真（零漂移关键）：
     *  - 判据不变：仍用曼哈顿距离 {@code |dx|+|dz| <= NEIGHBOR_R}（本方法不引入新度量）；
     *  - 结果不变：3×3 格查询是曼哈顿半径 R 的超集覆盖（格边=R 时，距离 ≤R 的两点必落在相邻格内），
     *    故候选集包含且仅包含满足判据的 j，最终 nearIds / nb 内容与旧双重循环逐元素一致；
     *  - 顺序不变：nearIds 仍经 {@link Collections#sort} 归一（与旧实现同），nb 的顺序按 j 的**升序索引**
     *    生成（与旧实现的内层 j 循环序一致），故 updateMood/affinity 累积结果逐字节一致。
     */
    private void adjacencyAndMood(World w) {
        int n = w.npcs.size();
        if (n == 0) return;

        // ---- 1) 分桶：格边 = NEIGHBOR_R，键为 (floor(x/R), floor(z/R))；桶内保存 npcs 的**升序索引** ----
        Map<Long, IntList> grid = new HashMap<Long, IntList>();
        for (int i = 0; i < n; i++) {
            Npc p = w.npcs.get(i);
            if (p.dead()) continue;
            long key = gridKey((int) Math.floor(p.x / NEIGHBOR_R), (int) Math.floor(p.z / NEIGHBOR_R));
            IntList bucket = grid.get(key);
            if (bucket == null) { bucket = new IntList(); grid.put(key, bucket); }
            bucket.add(i);
        }

        // ---- 2) 每口人只查自身格 + 8 邻格（曼哈顿半径 R 的完整覆盖）----
        for (int i = 0; i < n; i++) {
            Npc p = w.npcs.get(i);
            if (p.dead()) continue;
            int gx = (int) Math.floor(p.x / NEIGHBOR_R);
            int gz = (int) Math.floor(p.z / NEIGHBOR_R);

            // 收集候选 j 的索引后排序，确保与旧实现的「内层 j 升序」完全一致
            IntList cand = new IntList();
            for (int ax = gx - 1; ax <= gx + 1; ax++)
                for (int az = gz - 1; az <= gz + 1; az++) {
                    IntList bucket = grid.get(gridKey(ax, az));
                    if (bucket == null) continue;
                    for (int b = 0; b < bucket.size; b++) cand.add(bucket.a[b]);
                }
            cand.sort();

            List<String> nearIds = new ArrayList<String>();
            List<Social.Neighbor> nb = new ArrayList<Social.Neighbor>();
            for (int c = 0; c < cand.size; c++) {
                int j = cand.a[c];
                if (i == j) continue;
                Npc o = w.npcs.get(j);
                if (o.dead()) continue;
                float d = Math.abs(p.x - o.x) + Math.abs(p.z - o.z);   // 判据与旧实现逐字一致
                if (d <= NEIGHBOR_R) {
                    nearIds.add(o.id);
                    nb.add(new Social.Neighbor(o.id, p.social.affinityTo(o.id)));
                }
            }
            Collections.sort(nearIds);   // 确定性顺序（increment 与顺序无关，排序仅为稳健）
            p.social.setAdjacency(nearIds);
            float fear = emo(p, "fear"), anger = emo(p, "anger"), trust = emo(p, "trust");
            p.social.updateMood(fear, anger, trust, nb);
        }
    }

    /** 网格键：把 (gx,gz) 打包成 long（与 World.chunkKeyGlobal 同构）。 */
    private static long gridKey(int gx, int gz) {
        return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
    }

    /** 极简 int 动态数组（避免 Integer 装箱：M3② 热点路径零对象分配）。 */
    private static final class IntList {
        int[] a = new int[4];
        int size = 0;
        void add(int v) {
            if (size == a.length) {
                int[] b = new int[a.length * 2];
                java.lang.System.arraycopy(a, 0, b, 0, size);
                a = b;
            }
            a[size++] = v;
        }
        /** 插入排序：候选集通常极小（≤ 邻域人数），无需通用排序。 */
        void sort() {
            for (int i = 1; i < size; i++) {
                int v = a[i], j = i - 1;
                while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j--; }
                a[j + 1] = v;
            }
        }
    }

    private static float emo(Npc n, String k) {
        Float v = n.mind.emotion.get(k);
        return v != null ? v : 0f;
    }

    // ---------------------------------------------------------------- 情绪（M41 emotion）
    private void emotion(World w, VillageSocial vs) {
        // 压力源：稀缺(繁荣低) + 来犯之敌(相当于疫病/冲突压力)
        float scarcity = clamp01(1f - w.prosperity * 0.12f);
        float conflict = w.beasts.isEmpty() ? 0f : 0.3f;
        vs.stress = round3(clamp01(0.25f + 0.35f * scarcity + conflict));
        // 联结：邻里亲疏均值 + 聚落规模（Java 无 Python 的 trade/learning/diplomacy，改由社会层信号合成）
        float avg = avgAffinity(w);
        vs.connected = round3(clamp(0.1f, 1.0f,
                0.3f + avg * 0.25f + Math.min(1f, aliveCount(w) / 24f) * 0.2f));
        // 心情：节奏起伏 + 联结支撑 - 压力回落（权重经重调，使村庄情绪落在有生气的带内，不顶极值）
        float wave = 0.05f * (float) StrictMath.sin(2.0 * Math.PI * w.tick / 30.0);
        float mood = clamp(0.05f, 0.95f, 0.35f + vs.connected * 0.5f - 0.3f * vs.stress + wave);
        vs.mood = round3(mood);
        // 崩塌：心情跌破阈值 -> 一次集体情绪低点（上升沿），之后随联结回升而回稳
        if (mood < 0.22f) {
            if (!vs.low) {
                vs.low = true;
                vs.crash++;
                vs.trauma++;
                w.log("emotion", "despair", "mood=" + vs.mood + ",stress=" + vs.stress,
                        "压力联手打击，群体陷入集体情绪低点，创伤累积");
            }
        } else {
            vs.low = false;
        }
    }

    // ---------------------------------------------------------------- 规范（M43 norms）
    private void norms(World w, VillageSocial vs) {
        float avg = avgAffinity(w);
        float cooperation = clamp01(0.3f + avg * 0.6f + w.prosperity * 0.02f);
        vs.cooperation = round3(cooperation);
        float defect = 0.05f + 0.06f * Math.min(1f, w.beasts.size() / 2f);   // Java 无 wars，取来犯之敌为违约压力近似
        float trust = vs.trust + cooperation * 0.06f - defect * 0.4f;
        trust = trust + (0.4f - trust) * 0.05f;         // 温和回拉到基线
        vs.trust = round3(clamp01(trust));
        if (defect > 0.1f) vs.violations++;
        boolean was = vs.norm;
        if (cooperation > 0.55f && !was) {
            vs.norm = true;
            w.log("norms", "norm", "trust=" + vs.trust + ",coop=" + vs.cooperation,
                    "稳定互惠让社会自组织出规范：从'我善意'变成'大家默认该如此'");
        } else if (cooperation <= 0.4f && was) {
            vs.norm = false;
        }
        if (vs.norm && defect > 0.12f) {
            vs.sanctions++;
            if (vs.sanctions == 1) {
                w.log("norms", "sanction", "trust=" + vs.trust, "背约者被声誉系统惩戒，违约成本上升");
            }
        }
    }

    // ---------------------------------------------------------------- 家族（M8 family）
    private void family(World w, VillageSocial vs) {
        if (aliveCount(w) >= MAX_POPULATION) return;   // 聚落达容量上限：停止婚育，村庄进入稳定态（防无界滚雪球）
        tryMarry(w, vs);
        tryReproduce(w, vs);
    }

    private static boolean eligible(World w, VillageSocial vs, Npc a, Npc b) {
        if (a.dead() || b.dead()) return false;
        if (vs.familyOf(a.id) != null || vs.familyOf(b.id) != null) return false;
        if (a.social.affinityTo(b.id) < MARRIAGE_TH) return false;
        if (b.social.affinityTo(a.id) < MARRIAGE_TH) return false;
        return Math.abs(a.x - b.x) + Math.abs(a.z - b.z) <= MARRIAGE_DIST;
    }

    private void tryMarry(World w, VillageSocial vs) {
        List<Npc> singles = aliveSingles(w, vs);
        int i = 0;
        while (i < singles.size()) {
            Npc a = singles.get(i);
            Npc mate = null;
            for (int j = i + 1; j < singles.size(); j++) {
                if (eligible(w, vs, a, singles.get(j))) { mate = singles.get(j); break; }
            }
            if (mate == null) { i++; continue; }
            String fid = "fam_" + a.id + "_" + mate.id;
            vs.families.add(new VillageSocial.Family(fid, a.id, mate.id, w.tick + REPRO_COOLDOWN));
            w.log("npc", "marry", a.id, "{actor=" + a.name + ",spouse=" + mate.name + "}");
            singles = aliveSingles(w, vs);   // 重扫（可能腾出新相邻候选）
            i = 0;
        }
    }

    private List<Npc> aliveSingles(World w, VillageSocial vs) {
        List<Npc> out = new ArrayList<Npc>();
        for (Npc n : w.npcs) {
            if (!n.dead() && vs.familyOf(n.id) == null) out.add(n);
        }
        Collections.sort(out, (x, y) -> x.id.compareTo(y.id));   // 确定顺序
        return out;
    }

    private void tryReproduce(World w, VillageSocial vs) {
        for (VillageSocial.Family fam : vs.families) {
            if (aliveCount(w) >= MAX_POPULATION) return;   // 每添一口即复检，防一 tick 内多户齐添冲破上限
            if (fam.children.size() >= MAX_CHILD) continue;
            if (w.tick < fam.nextBirthTick) continue;
            Npc a = findAlive(w, fam.a);
            Npc b = findAlive(w, fam.b);
            if (a == null || b == null) continue;
            // P1：出生点锚定村心 + 由 simStream 派生的确定性环偏移，**不再依赖父母实时站位**，
            // 消除「子代位置随 NPC 走动漂移」的站位依赖（每次生育用唯一 npcSeq 进流名，确定性）
            float[] spot = birthSpot(w, vs);
            if (spot == null) continue;

            vs.npcSeq++;
            String cid = "n_" + vs.npcSeq;
            Map<String, Float> traits = new java.util.HashMap<String, Float>();
            java.util.Set<String> keys = new java.util.HashSet<String>();
            keys.addAll(a.traits.keySet());
            keys.addAll(b.traits.keySet());
            List<String> ks = new ArrayList<String>(keys);
            Collections.sort(ks);
            for (String k : ks) {
                float va = a.traits.containsKey(k) ? a.traits.get(k) : 0.5f;
                float vb = b.traits.containsKey(k) ? b.traits.get(k) : 0.5f;
                traits.put(k, Math.round((va + vb) / 2f * 100f) / 100f);
            }
            Npc child = new Npc(cid, spot[0], spot[1], spot[2],
                    new Body(), new Mind(), new Social(), new Decision(),
                    a.profession, a.race, traits);
            child.name = a.name + b.name + "子" + vs.npcSeq;   // 中文名（日志/未来 CJK UI）
            // 血缘亲近：后代↔父母 互设初始亲疏
            child.social.affinity.put(a.id, Math.min(KINSHIP, KIN_CAP));
            child.social.affinity.put(b.id, Math.min(KINSHIP, KIN_CAP));
            a.social.affinity.put(cid, Math.min(KINSHIP, KIN_CAP));
            b.social.affinity.put(cid, Math.min(KINSHIP, KIN_CAP));
            w.spawnNpc(child);
            fam.children.add(cid);
            fam.nextBirthTick = w.tick + REPRO_COOLDOWN;
            w.log("npc", "birth", cid,
                    "{name=" + child.name + ",parent=" + a.name + ",parent2=" + b.name + "}");
        }
    }

    private static Npc findAlive(World w, String id) {
        for (Npc n : w.npcs) if (n.id.equals(id) && !n.dead()) return n;
        return null;
    }

    /** P1：出生点锚定村心（世界中心，与 NpcSystem 同源）周围的 [BIRTH_MIN_R, VILLAGE_RADIUS] 环带，
     *  由 simStream("social:birth:" + npcSeq) 确定性取方位与半径，找一块无他人占位的立足点。
     *  不再扫父母实时站位 → 子代位置稳定、不随 NPC 走动漂移。 */
    private static float[] birthSpot(World w, VillageSocial vs) {
        SeededRNG r = w.simStream("social:birth:" + vs.npcSeq);
        float cx = w.SX / 2f, cz = w.SZ / 2f;
        float ang = r.nextFloat() * (float) (2.0 * Math.PI);
        float dist = BIRTH_MIN_R + r.nextFloat() * (NpcSystem.VILLAGE_RADIUS - BIRTH_MIN_R);
        int ix = (int) Math.floor(cx + (float) StrictMath.cos(ang) * dist);
        int iz = (int) Math.floor(cz + (float) StrictMath.sin(ang) * dist);
        if (!w.inBounds(ix, 0, iz)) return null;
        if (occupied(w, ix, iz)) return null;
        float sy = w.surfaceY[ix][iz] + 1f;
        return new float[]{ix + 0.5f, sy, iz + 0.5f};
    }

    private static boolean occupied(World w, int ix, int iz) {
        for (Npc n : w.npcs) {
            if (n.dead()) continue;
            if ((int) Math.floor(n.x) == ix && (int) Math.floor(n.z) == iz) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 工具
    private static int aliveCount(World w) {
        int c = 0;
        for (Npc n : w.npcs) if (!n.dead()) c++;
        return c;
    }

    /** 邻里亲疏均值（按 id 排序求和，规避浮点加法顺序噪声）。 */
    private static float avgAffinity(World w) {
        float sum = 0f; int cnt = 0;
        for (Npc n : w.npcs) {
            List<String> ks = new ArrayList<String>(n.social.affinity.keySet());
            Collections.sort(ks);
            for (String k : ks) { sum += n.social.affinity.get(k); cnt++; }
        }
        return cnt == 0 ? 0f : sum / cnt;
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float clamp(float lo, float hi, float v) { return Math.max(lo, Math.min(hi, v)); }
    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
}
