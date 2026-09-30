package core.world;

import core.agent.Npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 村庄编年史（批次 1 余项 · storyteller + village_memory 消费端）。
 *
 * 忠实移植 Python 两模块的确定性路径为 Java：
 *  - systems/village_memory.py：把世界里的“转折事件”酿成一条条可检索的档案（episode），
 *    检索用字符级 bigram Jaccard（无 embedding 也中文可检索、可解释、可断言）；
 *  - systems/storyteller.py  ：由事件统计 + 记忆回响拼出“当日村志”（模板规则、无随机）。
 *
 * 与 {@link World#villageMemory} 的分工：
 *  - villageMemory（List&lt;String&gt;）是“世界回响短句”，由 ProsperitySystem/Player 写，**进 hashState**；
 *  - Chronicle 是“结构化村志档案 + 叙述生成”，**不进 hashState**（与 beasts/shrines/npcs/social 同纪律）。
 *    它只**读** events / npcs / social 生成叙述，绝不写 mat/mass/prosperity/skills/villageMemory，
 *    故对四道零漂移门禁指纹零影响——这正是让“世界因你而变”被看见（CD-PILLARS P1）而零漂移的关键。
 *
 * 确定性：episode 文本由事件参数直拼（无随机）；归档用单调水印 {@link #absorbed}，跨进程同结果；
 * 长程去重按 {@link #skeleton}（去时辰前缀）判等，同样是文本的纯函数，故不引入任何随机或不稳定排序。
 *
 * <p><b>D2 长程去重</b>：计数（{@link #counts}，每次发生都计）与归档（{@link #episodes}，同义只留一条）
 * 解耦——既让 HUD「REPEL n」随每次击退增长（“世界因你而变”可见），又不让村志被同一句话刷屏。
 */
public final class Chronicle {

    /** 一条村庄档案（可检索的“史实”）。 */
    public static final class Episode {
        public final int tick;
        public final String action;   // marry / birth / repel / awaken / norm / despair
        public final String text;     // 中文档案文本（确定性拼装）
        public final String ascii;    // ASCII 类别标签（HUD 用；GL 字体仅 ASCII）
        Episode(int tick, String action, String text, String ascii) {
            this.tick = tick; this.action = action; this.text = text; this.ascii = ascii;
        }
    }

    /** 档案上限（防止超长跑无界增长；保留最近 N 条，确定性）。 */
    public static final int EPISODE_CAP = 600;

    /** 去重后的村庄档案（同一动作 + 同一骨架只留一条；见 {@link #absorb}）。 */
    public final List<Episode> episodes = new ArrayList<Episode>();
    /** 各类别（ASCII 标签）的<b>发生总次数</b>——含被去重未入库者；HUD/叙述的“已 N 次”用它。 */
    public final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();

    /** 已归档到的事件下标水印（单调递增；只增不减，保证不重复扫描）。 */
    public int absorbed = 0;
    /**
     * 长程去重索引：{@code action -> 已归档文本的“骨架”集合}（骨架 = 去掉「第N时辰：」前缀）。
     *
     * <p>同一动作、同一骨架的事件只入库一次。否则长时游玩后 REPEL / JUDGE / 灾害之类的固定文案
     * 会把村志刷成“复读机”——实测 240 tick 内 60 条档案里就有 <b>35 条</b>是同一句
     * “旅人击退了来犯之敌，村寨得以安宁”，村志的“近日事迹”列表几乎全是它。
     *
     * <p>骨架与发生时刻无关，故这是<b>长程</b>去重（Python 原版按含“第N天”的全文去重，
     * 实际只能挡住同一天内的重复）。参数不同的事件（不同夫妻/不同新生儿/不同罪犯）骨架不同，
     * 因此<b>不会被误并</b>——`CivilizationSystem.incubate` 按档案计数立节日/绰号的可达性得以保全。
     */
    private final Map<String, Set<String>> seenSkeleton = new LinkedHashMap<String, Set<String>>();
    /** 最近一条转折事件的 ASCII 标签（HUD `LAST` 用；去重后仍如实反映“最新发生”而非“最新入库”）。 */
    public String lastAscii = "";
    /** 最近一条转折事件的 tick。 */
    public int lastTick = 0;
    /** 说书人已叙的“日”数（每次生成村志 +1）。 */
    public int days = 0;
    /** 最新一版“当日村志”全文（中文；供 chronicle.log 阅读）。 */
    public String story = "";
    /** 生成该版村志时的 tick。 */
    public int storyTick = 0;

    // 动作 -> 中文检索线索（供 bigram 以真实文本相似度召回，而非对英文动作名做字面匹配）。
    private static final Map<String, String> HINT = new LinkedHashMap<String, String>();
    static {
        HINT.put("marry", "婚 夫妻 连理");
        HINT.put("birth", "出生 孩子 添丁");
        HINT.put("repel", "击退 来犯 守寨");
        HINT.put("awaken", "觉醒 祭坛 能力");
        HINT.put("norm", "规范 互惠 信任");
        HINT.put("despair", "低迷 压力 创伤");
        // ---- 批次 3 · 个体成长：英雄/史诗/神谕/飞升/机器人/再启蒙/物种分化/异种/驯养/炼成 ----
        HINT.put("hero", "英雄 传说 传唱");
        HINT.put("epic", "史诗 功业 长卷");
        HINT.put("oracle", "神谕 天启 祭司");
        HINT.put("transcend", "飞升 超验 脱俗");
        HINT.put("rights", "机器人 权利 对齐");
        HINT.put("reawaken", "再启蒙 重生 知识");
        HINT.put("speciate", "分化 生态位 物种");
        HINT.put("zombie", "丧尸 疫区 毒株");
        HINT.put("mount", "坐骑 驯服 伙伴");
        HINT.put("discover", "化合物 元素 炼成");
        // ---- 批次 4 · 民政经济/灾害：城镇升格、犯罪、仲裁、山火、洪涝、干旱、地震、通胀 ----
        HINT.put("town", "城镇 升格 繁荣");
        HINT.put("crime", "盗窃 偷盗 斗殴");
        HINT.put("judge", "仲裁 惩戒 村纪");
        HINT.put("fire", "山火 点燃 干燥");
        HINT.put("flood", "洪涝 淹没 降雨");
        HINT.put("drought", "干旱 缺水 蒸发");
        HINT.put("quake", "地震 摇晃 残骸");
        HINT.put("inflation", "通胀 货币 市场");
    }

    // ---------------------------------------------------------------- 归档
    /**
     * 把事件日志里自 {@link #absorbed} 起的新“转折事件”落成档案，返回<b>新增档案条数</b>。
     *
     * <p>两件事被刻意<b>解耦</b>（这是 D2 村志去重的核心）：
     * <ol>
     *   <li><b>计数</b>：每一次发生都计（{@link #counts}），故 HUD「REPEL n」随每次击退增长 ——
     *       这是“世界因你而变”的可见证据（A 类验收项），绝不能因去重而停止增长；</li>
     *   <li><b>归档</b>：只有“骨架不重复”的才进 {@link #episodes}，故村志不会被同一句话刷屏。</li>
     * </ol>
     *
     * <p>只收家族/守护/里程碑类动作（日常噪声不入库，避免检索被淹没），对齐 Python {@code _STICKY}。
     */
    public int absorb(List<World.Event> events, int tick) {
        if (events == null) return 0;
        int n0 = episodes.size();
        for (int i = absorbed; i < events.size(); i++) {
            Episode ep = classify(tick, events.get(i));
            if (ep == null) continue;
            // (1) 计数：每一次发生都计（与是否入库无关）
            Integer c = counts.get(ep.ascii);
            counts.put(ep.ascii, (c == null ? 0 : c) + 1);
            lastAscii = ep.ascii;
            lastTick = tick;
            // (2) 归档：同一动作 + 同一骨架只留一条（长程去重）
            Set<String> seen = seenSkeleton.get(ep.action);
            if (seen == null) { seen = new HashSet<String>(); seenSkeleton.put(ep.action, seen); }
            if (seen.add(skeleton(ep.text))) episodes.add(ep);
        }
        absorbed = events.size();
        if (episodes.size() > EPISODE_CAP) {
            episodes.subList(0, episodes.size() - EPISODE_CAP).clear();
        }
        return episodes.size() - n0;
    }

    /** 档案文本的“骨架”：去掉「第N时辰：」前缀 —— 去重按骨架比对，与发生时刻无关（长程去重）。 */
    public static String skeleton(String text) {
        if (text == null) return "";
        int i = text.indexOf('：');
        return (i >= 0 && i + 1 < text.length()) ? text.substring(i + 1) : text;
    }

    /** 单条事件 -> 档案（不属转折动作则返回 null）。 */
    private static Episode classify(int tick, World.Event e) {
        if (e == null) return null;
        String sys = e.system, act = e.action;
        if ("npc".equals(sys) && "marry".equals(act)) {
            String a = between(e.params, "actor"), s = between(e.params, "spouse");
            return new Episode(tick, act, "第" + tick + "时辰：" + a + "与" + s + "结为连理，成了夫妻。", "MARRY");
        }
        if ("npc".equals(sys) && "birth".equals(act)) {
            String nm = between(e.params, "name"), pa = between(e.params, "parent");
            return new Episode(tick, act, "第" + tick + "时辰：" + nm + "出生，是" + pa + "家的孩子。", "BIRTH");
        }
        if ("combat".equals(sys) && "repel".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：旅人击退了来犯之敌，村寨得以安宁。", "REPEL");
        }
        if ("shrine".equals(sys) && "claim".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：旅人于祭坛觉醒了「" + e.params + "」之力。", "AWAKEN");
        }
        if ("norms".equals(sys) && "norm".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：稳定互惠让村庄自组织出规范，人心渐齐。", "NORM");
        }
        if ("emotion".equals(sys) && "despair".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：压力联手打击，村中陷入一阵低迷。", "DESPAIR");
        }
        // ---- 批次 2 · 文明深度：把文明里程碑也酿成村志（让文明进展被看见）----
        if ("tech".equals(sys) && "unlock".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：村中智者钻研有成，解锁了「" + between(e.result, "tech") + "」。", "TECH");
        }
        if ("religion".equals(sys) && "build".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：信徒云集，神殿于晨曦中立起。", "TEMPLE");
        }
        if ("religion".equals(sys) && "miracle".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：天光垂落，神殿上空浮出异象。", "MIRACLE");
        }
        if ("diplomacy".equals(sys) && "treaty".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：村寨与「" + e.params + "」签署贸易协定，两邦互市。", "TREATY");
        }
        if ("diplomacy".equals(sys) && "war".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：多边关系交恶，边境已入冲突时期。", "DIPWAR");
        }
        if ("diplomacy".equals(sys) && "peace".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：贸易协定攒够，文明重回和平轨道。", "PEACE");
        }
        if ("warfare".equals(sys) && "war".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：军旗猎猎，一场征伐在边疆拉开。", "WAR");
        }
        if ("warfare".equals(sys) && "revolution".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：民怨如潮，旧朝倾覆，第" + between(e.result, "regimes") + "代政权立起。", "REVOLT");
        }
        if ("culture".equals(sys) && "incubate".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：村中风俗渐成，立下新的节日与口讳。", "CULTURE");
        }
        // ---- 批次 3 · 个体成长：把个体/神话/异种里程碑也酿成村志 ----
        if ("myth".equals(sys) && "hero".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：又一位英雄在火堆旁诞生，被孩童们反复传唱。", "HERO");
        }
        if ("myth".equals(sys) && "epic".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：英雄的功业被谱成史诗，镌进长卷，万世传颂。", "EPIC");
        }
        if ("myth".equals(sys) && "oracle".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：神谕自云外垂落，祭司恍然，学者也记下几个异文。", "ORACLE");
        }
        if ("ascension".equals(sys) && "transcend".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：一道光柱冲天而起，有人完成超验飞升，脱离血肉凡躯。", "ASCEND");
        }
        if ("robotics".equals(sys) && "rights".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：机器人权利被承认，机巧之物与村民步入社会对齐。", "ROBOT");
        }
        if ("learning".equals(sys) && "reawaken".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：知识重越历史峰值，文明在废墟上迎来再启蒙。", "REAWAKEN");
        }
        if ("evolution".equals(sys) && "speciate".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：双峰食物选择把种群劈成两个生态位群（物种分化）。", "SPECIATE");
        }
        if ("xeno".equals(sys) && "zombie".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：毒株失控，疫区里站起一队丧失心智的丧尸。", "XENO");
        }
        if ("taming".equals(sys) && "mount".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：一声轻啸，野兽伏首，成了村民的坐骑伙伴。", "MOUNT");
        }
        if ("chemistry".equals(sys) && "discover".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：工艺推进，化学家炼出了「" + between(e.params, "compound") + "」。", "MATTER");
        }
        // ---- 批次 4 · 民政经济层：城镇升格 / 犯罪 / 仲裁 ----
        if ("polity".equals(sys) && "town_up".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：村寨渐盛，已晋为「" + between(e.result, "label") + "」。", "TOWN");
        }
        if ("npc".equals(sys) && "crime".equals(act)) {
            String kind = between(e.params, "kind");
            String off = between(e.params, "offender");
            if ("fight".equals(kind))
                return new Episode(tick, act, "第" + tick + "时辰：" + off + "旧怨难消，与人当街动起手来。", "CRIME");
            return new Episode(tick, act, "第" + tick + "时辰：" + off + "困顿难耐，偷走了邻家的存粮。", "CRIME");
        }
        if ("npc".equals(sys) && "judge".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：村中有了是非，众人依民意与亲疏论处。", "JUDGE");
        }
        // ---- 批次 4 · 灾害驱动层：山火/洪涝/干旱/地震/通胀 ----
        if ("disaster".equals(sys) && "wildfire_ignite".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：天干物燥，荒原上腾起第一簇山火。", "FIRE");
        }
        if ("disaster".equals(sys) && "flood".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：连日暴雨，低洼田垄已被洪水漫过。", "FLOOD");
        }
        if ("disaster".equals(sys) && "drought_begin".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：久旱不雨，溪塘见底，庄稼都打了蔫。", "DROUGHT");
        }
        if ("disaster".equals(sys) && "earthquake".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：一阵地动山摇，几处屋舍塌成了木料。", "QUAKE");
        }
        if ("trade".equals(sys) && "inflation".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：钱多货少，集市物价飞涨，村人皆咋舌。", "INFLATION");
        }
        // ---- C2 · 试炼/遗物/世界之心：塞尔达后半段里程碑进村志 ----
        if ("trial".equals(sys) && "relic".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：旅人破开「" + e.params + "」之印，取得一枚遗物。", "RELIC");
        }
        if ("trial".equals(sys) && "heart".equals(act)) {
            return new Episode(tick, act, "第" + tick + "时辰：三力齐备，旅人登临峰顶认取世界之心，山川为之低鸣。", "HEART");
        }
        return null;
    }

    /** 从 "{actor=X,spouse=Y}" 里取 key 的值（到 ',' 或 '}' 止）。 */
    private static String between(String s, String key) {
        if (s == null) return "";
        int i = s.indexOf(key + "=");
        if (i < 0) return "";
        int j = i + key.length() + 1, k = j;
        while (k < s.length()) { char c = s.charAt(k); if (c == ',' || c == '}') break; k++; }
        return s.substring(j, k);
    }

    // ---------------------------------------------------------------- 检索（bigram Jaccard）
    /** 取与近期线索最相关的旧档案文本（供村志“说起从前”衔接），topk 条。 */
    public List<String> continuity(World w, int topk) {
        if (episodes.isEmpty()) return Collections.emptyList();
        // 近期线索：最近 12 条档案的动作 -> 中文提示词
        Set<String> acts = new HashSet<String>();
        int from = Math.max(0, episodes.size() - 12);
        for (int i = from; i < episodes.size(); i++) acts.add(episodes.get(i).action);
        StringBuilder q = new StringBuilder();
        for (String a : acts) { String h = HINT.get(a); q.append(h != null ? h : a).append(' '); }
        List<String> qb = bigrams(q.toString());
        if (qb.isEmpty()) return Collections.emptyList();

        // 打分：只搜较早档案（排除最近 12 条，避免“今天的话今天再说一遍”）
        List<int[]> idx = new ArrayList<int[]>();        // {score*1000, episodeIndex}
        int older = Math.max(0, episodes.size() - 12);
        for (int i = 0; i < older; i++) {
            Episode ep = episodes.get(i);
            float sim = jaccard(qb, bigrams(ep.text));
            if (sim <= 0f) continue;
            idx.add(new int[]{(int) (sim * 1000f), i});
        }
        if (idx.isEmpty()) return Collections.emptyList();
        Collections.sort(idx, (x, y) -> x[0] != y[0] ? y[0] - x[0] : y[1] - x[1]);  // 分高优先，其次更新
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (int[] t : idx) {
            String txt = episodes.get(t[1]).text;
            if (seen.add(txt)) out.add(txt);
            if (out.size() >= topk) break;
        }
        return out;
    }

    /** 字符级 bigram（去空白）。 */
    private static List<String> bigrams(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) t.append(s.charAt(i));
        for (int i = 0; i + 1 < t.length(); i++) out.add(t.substring(i, i + 2));
        return out;
    }

    private static float jaccard(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0f;
        Set<String> sa = new HashSet<String>(a), sb = new HashSet<String>(b);
        int inter = 0;
        for (String x : sa) if (sb.contains(x)) inter++;
        int uni = sa.size() + sb.size() - inter;
        return uni == 0 ? 0f : (float) inter / uni;
    }

    // ---------------------------------------------------------------- 叙述生成（storyteller）
    /** 生成“当日村志”全文（模板规则 + 统计 + 记忆回响 + 家族），无随机、确定性。 */
    public String generateStory(World w, String narrator) {
        int alive = 0;
        for (Npc n : w.npcs) if (!n.dead()) alive++;
        StringBuilder sb = new StringBuilder();
        sb.append("《").append(narrator).append("·村志》　（第 ").append(w.tick).append(" 个时辰）\n\n");

        // 前尘回响开篇（检索出的旧事，让今日有所本）
        for (String ep : continuity(w, 2)) sb.append("　说起从前，").append(ep).append('\n');

        sb.append("　这一日天光放亮，村中便有了动静。\n");
        sb.append("　村中 ").append(w.npcs.size()).append(" 人，现存 ").append(alive).append(" 人。\n");

        int rp = count("REPEL"), mr = count("MARRY"), bi = count("BIRTH");
        if (rp > 0) sb.append("　旅人已 ").append(rp).append(" 次击退来犯之敌，村寨因他而安宁。\n");
        if (mr > 0) sb.append("　村中已 ").append(mr).append(" 户结为连理，两姓之好。\n");
        if (bi > 0) sb.append("　人丁渐旺，已添 ").append(bi).append(" 名新生。\n");

        // 家族烟火（读 w.social，只读不写）
        if (w.social != null && !w.social.families.isEmpty()) {
            sb.append("　如今村中有 ").append(w.social.families.size()).append(" 户人家，互帮互助，日子有了盼头。\n");
        }
        // 情绪景观 + 规范（读 w.social）
        if (w.social != null) {
            sb.append("　村中气象：心情 ").append(round2(w.social.mood))
              .append("，联结 ").append(round2(w.social.connected))
              .append("，信任 ").append(round2(w.social.trust));
            sb.append(w.social.norm ? "，已立下互惠的规矩。" : "，规矩尚未立稳。").append('\n');
        }

        // 近日事迹（可回放的具体条目）
        if (!episodes.isEmpty()) {
            sb.append("\n　—— 据村人口述，近几件值得记下的事：\n");
            int from = Math.max(0, episodes.size() - 6);
            for (int i = from; i < episodes.size(); i++) sb.append("　　· ").append(episodes.get(i).text).append('\n');
        }

        sb.append("\n　夜幕四合，村中 ").append(alive).append(" 人尚在。明日，又是新的一日。");
        return sb.toString();
    }

    // ---------------------------------------------------------------- Getters（供 HUD / 对话消费）
    public int count(String ascii) {
        Integer c = counts.get(ascii);
        return c == null ? 0 : c;
    }

    /** HUD 一行：ASCII 村志摘要（语言无关，GL 字体仅 ASCII 也能上屏）。 */
    public String asciiHeader() {
        return "CHRONICLE D" + days + "   REPEL " + count("REPEL")
                + "  MARRY " + count("MARRY") + "  BIRTH " + count("BIRTH");
    }

    /** HUD 一行：最近一条转折事件（含被去重、未入库者）+ 总发生数 / 去重后档案数。 */
    public String asciiLast() {
        if (lastAscii == null || lastAscii.isEmpty()) return "LAST: (no records yet)";
        return "LAST: " + lastAscii + " @" + lastTick
                + "   EVENTS " + countsTotal() + "  DISTINCT " + episodes.size();
    }

    /** 全部转折事件的<b>发生总次数</b>（各类别计数之和；≥ 档案条数，差值即被去重掉的重复量）。 */
    public int countsTotal() {
        int t = 0;
        for (Integer v : counts.values()) t += (v == null ? 0 : v);
        return t;
    }

    /** 某动作在去重后档案里实际留下的条数（供门禁断言“长程去重生效”）。 */
    public int distinctOf(String action) {
        int n = 0;
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).action.equals(action)) n++;
        return n;
    }

    /** 最新一条档案的原文（供 NPC 回话“忆往”引用）；无则 null。 */
    public String latestEcho() {
        return episodes.isEmpty() ? null : episodes.get(episodes.size() - 1).text;
    }

    private static String round2(float v) { return String.format(java.util.Locale.US, "%.2f", v); }
}
