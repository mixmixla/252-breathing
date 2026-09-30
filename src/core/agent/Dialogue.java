package core.agent;

import core.world.World;

/**
 * 角色人格对话层（批次 1 · 社会核心）：让村民“带着性格说话”。
 *
 * 忠实移植 Python systems/dialogue.py 的确定性规则路径（persona/tone/规则回话/话题感知/忆往）：
 *  - persona：由人格特质(industrious/cautious/greedy)推导性格标签；
 *  - tone：性格 × 情绪状态机(mood) × 与交谈者亲疏(affinity)；
 *  - reply：按亲疏分档 + 情绪覆盖 + 话题感知 + (id|tick|mood) 稳定轮转，措辞不再“只会一句”；
 *  - talk：一次交谈 → 生成回话 + 亲疏微调 + 记录 dialogue 事件（供村志/记忆）。
 *
 * 确定性铁律（对齐整套世界）：
 *  - 全部模板固定、不依赖 rng；变体轮转只用稳定哈希(id|tick|mood)，跨进程同结果；
 *  - 只读/单向：不改身体/网格；只改 social.affinity 并记录事件（events 不进指纹）；
 *  - 本类绝不读 world.rng 主状态或 fxRng，故对四道零漂移门禁指纹零影响。
 *
 * 注：回话文本为中文，当前 GL 字体仅含 ASCII，故中文回话先落日志/事件（未来接入 CJK 字体后上屏）；
 * 屏幕上的性格/情绪标签由 {@link #asciiPersona}/{@link #asciiMood} 提供 ASCII 版。
 */
public final class Dialogue {

    private Dialogue() { }

    // ---------------------------------------------------------------- persona（性格标签）
    private static float t(Npc n, String k) {
        Float v = n.traits.get(k);
        return v != null ? v : 0f;
    }

    /** 由三条特质推导性格标签（确定性、可解释），移植 _PERSONA_RULES。 */
    public static String persona(Npc n) {
        float ind = t(n, "industrious"), cau = t(n, "cautious"), gre = t(n, "greedy");
        String best = "稳重";
        float bs = -1f;
        float s;
        s = ind * 0.9f + gre * 0.2f; if (s > bs) { bs = s; best = "辛劳"; }
        s = cau * 0.9f + ind * 0.3f; if (s > bs) { bs = s; best = "机敏"; }
        s = gre * 0.9f + cau * 0.2f; if (s > bs) { bs = s; best = "精明"; }
        s = ind * 0.7f;              if (s > bs) { bs = s; best = "勤勉"; }
        s = cau * 0.6f;              if (s > bs) { bs = s; best = "谨慎"; }
        s = ind * 0.4f + gre * 0.4f; if (s > bs) { bs = s; best = "稳重"; }
        return best;
    }

    /** 情绪状态机 -> 语气主调（供对话措辞），移植 _TONE_MOOD。 */
    private static String moodTone(String mood) {
        switch (mood) {
            case "calm":    return "平静";
            case "wary":    return "警惕";
            case "scared":  return "惊惶";
            case "furious": return "恼怒";
            case "fond":    return "亲切";
            case "hostile": return "敌意";
            default:        return "平静";
        }
    }

    /** 综合 性格 + 情绪 + 亲疏 -> 对话语气（确定性），移植 tone()。 */
    public static String tone(Npc n, float affinity) {
        String mt = moodTone(n.social.mood);
        if (affinity >= 0.45f) mt = "熟络";
        else if (affinity <= -0.45f) mt = "疏离";
        return persona(n) + mt;
    }

    // ---------------------------------------------------------------- 稳定哈希（跨进程一致）
    /** FNV-1a 32bit（& 0x7fffffff），对齐 Python _stable_hash（不用内置 hash，避免随机化）。 */
    static int stableHash(String s) {
        long h = 2166136261L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h = (h * 16777619L) & 0x7fffffffL;
        }
        return (int) h;
    }

    // ---------------------------------------------------------------- 话题感知应答
    private static boolean has(String nt, String... kws) {
        for (String k : kws) if (nt.contains(k)) return true;
        return false;
    }

    static String topicReply(Npc n, String who, String topic) {
        String nt = topic != null ? topic : "";
        String m = n.social.mood;
        if (has(nt, "饿", "饭", "吃", "口粮", "饥")) {
            return ("calm".equals(m) || "fond".equals(m))
                    ? "正好也饿了，" + who + "要是不嫌弃，一道去河沟边碰碰运气。"
                    : "提吃的最戳我心窝子，可这会儿真没得吃的。";
        }
        if (has(nt, "修道", "修炼", "修仙", "打坐", "灵气", "突破", "修为")) {
            return (!"furious".equals(m) && !"hostile".equals(m))
                    ? "修仙？山那边风气清正，倒是打坐的好地方，可我这人不耐坐。"
                    : "修不修仙的先放一边，眼前这日子都顾不过来。";
        }
        if (has(nt, "天气", "雨", "晴", "冷", "热", "风")) {
            return "这天气说不准，午后说不定就变脸，出门都揣件衣裳。";
        }
        if (has(nt, "价", "集市", "卖", "买", "行情", "货")) {
            return (t(n, "greedy") >= 0.6f)
                    ? "集市上的行情一天一个样，我看是越来越闹腾了。"
                    : "行情啥的我不太懂，能换到口粮就成。";
        }
        if (has(nt, "危险", "怕", "怪物", "丧尸", "地震", "山火", "洪水", "敌人", "不安")) {
            return (!"fond".equals(m))
                    ? "外头不太平，" + who + "半夜可别走单。"
                    : "有你在身边，我倒不那么慌了。";
        }
        return "";
    }

    // ---------------------------------------------------------------- 关键事件回话（情绪/饥/累/常态）
    static String basicReply(Npc n, String who, int rotate) {
        boolean hungry = n.body.hunger >= 60f;
        boolean tired = n.body.stamina <= 20f;
        String m = n.social.mood;
        String[] pool;
        if ("furious".equals(m)) {
            pool = !hungry
                    ? new String[]{"哼，" + who + "这会儿别来烦我，正烦着呢。",
                                   "火气正上头，" + who + "先离我远些！",
                                   "气还没消，别来撞我的火头。"}
                    : new String[]{"饿着肚子本就窝火，" + who + "还来添乱！",
                                   "又饿又火，今天谁也不许惹我。"};
            return pool[rotate % pool.length];
        }
        if ("scared".equals(m)) {
            pool = new String[]{"外头不安全……" + who + "别走远，我看天色不对劲。",
                                "总觉得背后凉飕飕的，" + who + "别落单。"};
            return pool[rotate % pool.length];
        }
        if (hungry) {
            pool = t(n, "greedy") < 0.6f
                    ? new String[]{"唉，灶上还没吃的，" + who + "要是有口粮，倒匀我些。",
                                   "饿得前胸贴后背，" + who + "行行好匀口吃的。"}
                    : new String[]{"要是" + who + "有口吃的，我拿两件活计换。",
                                   "有粮的换我两趟活，" + who + "看着给。"};
            return pool[rotate % pool.length];
        }
        if (tired) {
            pool = new String[]{"干了一天，浑身骨头都散了，" + who + "容我歇歇。",
                                "腿都软了，" + who + "先去忙你的，我缓口气。"};
            return pool[rotate % pool.length];
        }
        pool = t(n, "industrious") < 0.6f
                ? new String[]{"如今收成尚可，多亏大家搭把手。" + who + "也歇会吧。",
                               "日子越过越有盼头，" + who + "你看这天色多好。"}
                : new String[]{"还得再犁两垄地，" + who + "要搭把手便一起。",
                               "趁地气没散，我再赶两垄，" + who + "别管我。"};
        return pool[rotate % pool.length];
    }

    static String friendlyReply(Npc n, String who, int rotate) {
        String[] pool = !"wary".equals(n.social.mood)
                ? new String[]{who + "来了，坐！正好聊聊今日的光景。",
                               "稀客啊" + who + "，快坐下喝口水。",
                               who + "来得正好，我正想找人说道说道。"}
                : new String[]{"是你啊" + who + "，我这会儿心不在焉，别见怪。",
                               "嗯…" + who + "，我正想着心事，你先坐。"};
        return pool[rotate % pool.length];
    }

    static String hostileReply(Npc n, String who) {
        return "离我远些，" + who + "！你我没什么好说的。";
    }

    /** 确定性规则回话：话题感知 -> 亲疏分档 + 情绪覆盖 + (id|tick|mood) 稳定轮转。 */
    public static String reply(Npc n, String who, float affinity, int tick, String topic) {
        if (topic != null && !topic.isEmpty()) {
            String themed = topicReply(n, who, topic);
            if (!themed.isEmpty()) return themed;
        }
        int rotate = stableHash(n.id + "|" + tick + "|" + n.social.mood);
        if (affinity <= -0.45f || "furious".equals(n.social.mood)
                || "hostile".equals(n.social.mood) || "scared".equals(n.social.mood)) {
            if (affinity <= -0.45f || "hostile".equals(n.social.mood)) return hostileReply(n, who);
            return basicReply(n, who, rotate);
        }
        if (affinity >= 0.45f) return friendlyReply(n, who, rotate);
        return basicReply(n, who, rotate);
    }

    // ---------------------------------------------------------------- 主入口：一次交谈
    /**
     * speaker（玩家或 NPC）与 target 攀谈一句，返回 target 的回话。
     * 副作用：亲疏微调（投缘则靠近）+ 记录 dialogue 事件（供村志/记忆读取）。
     * useMemory：把世界回响（villageMemory）里最近一段旧事织进回话——让玩家感到“我的守护被村人记得”
     * （直接对齐 CD-PILLARS P1「世界因你而变被看见」）。
     */
    public static String talk(World w, String speakerId, String speakerName,
                              Npc target, String topic, boolean useMemory) {
        if (target == null) return "";
        if (target.dead()) return "……鸦雀无声，人已经不在了。";
        String who = speakerName != null ? speakerName : "旅人";
        float affinity = target.social.affinityTo(speakerId);
        String text = reply(target, who, affinity, w.tick, topic);

        if (useMemory) {
            // 优先引用村志最新档案（批次1 余项 storyteller：结构化史实），否则回落世界回响短句
            String echo = (w.chronicle != null) ? w.chronicle.latestEcho() : null;
            if (echo == null && !w.villageMemory.isEmpty()) {
                echo = w.villageMemory.get(w.villageMemory.size() - 1);
            }
            if (echo != null) {
                text = text + "（" + target.name + "神情一肃）说起旧事——" + echo;
            }
        }
        // 亲疏微调：回应投缘与否（确定性增量，对齐 Python talk()）
        if ("fond".equals(target.social.mood) || "calm".equals(target.social.mood)) {
            Float d = Social.DELTA.get("coexist");
            target.social.drift(speakerId, d != null ? d : 0.006f, "aid",
                    "与" + who + "谈得来", w.tick);
        }
        // 记录对话事件（给村志/记忆）：带性格与语气
        w.log("npc", "dialogue", target.id,
                "{speaker=" + who + ",npc=" + target.name
                        + ",persona=" + persona(target) + ",tone=" + tone(target, affinity)
                        + ",text=" + text + "}");
        return text;
    }

    // ---------------------------------------------------------------- ASCII 标签（GL 字体仅 ASCII）
    public static String asciiPersona(Npc n) {
        switch (persona(n)) {
            case "辛劳": return "DILIGENT";
            case "机敏": return "SHREWD";
            case "精明": return "CUNNING";
            case "勤勉": return "INDUSTRY";
            case "谨慎": return "CAUTIOUS";
            default:     return "STEADY";
        }
    }

    public static String asciiMood(Npc n) {
        switch (n.social.mood) {
            case "calm":    return "CALM";
            case "wary":    return "WARY";
            case "scared":  return "SCARED";
            case "furious": return "FURIOUS";
            case "fond":    return "FOND";
            case "hostile": return "HOSTILE";
            default:        return "CALM";
        }
    }
}
