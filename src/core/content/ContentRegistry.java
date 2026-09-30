package core.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 内容注册表——「万物皆配置」的总入口。
 *
 * <p>职责：<b>扫描 → 解析 → 校验 → 注册</b>。全部在加载期完成（铁律 1），
 * 运行时只剩 map 查表。注册结果是<b>不可变</b>的（铁律：内容不会在 tick 中变形）。
 *
 * <p>ID 与顺序的确定性：文件按<b>路径字典序</b>处理，与操作系统目录枚举顺序无关；
 * ID 取文件名（去 {@code .json}）。因此同一份内容目录在任何机器上都得到同一张表。
 *
 * <p>校验四件套（铁律 3「未知即拒绝」）：
 * <ol>
 *   <li>内容类型必须在白名单；ID 不得重复</li>
 *   <li>效果指令必须在 {@link Effect#WHITELIST}</li>
 *   <li>引用完整性：技能引用的粒子 / 特效 / 状态…必须存在（悬空引用 = 错误）</li>
 *   <li>循环引用：{@code fx → fx → fx} 成环 = 错误（不栈溢出，只报告）</li>
 * </ol>
 *
 * <p><b>零漂移</b>：本类与 RNG / 网格零接触，纯解析。内容变更不影响任何仿真指纹。
 */
public final class ContentRegistry {

    /** 内容类型（目录名 = 类型名）。顺序固定，用于确定性快照。 */
    public static final String[] TYPES = {
            "particles", "fx", "skills", "buffs", "items", "quests", "beasts",
            "rules", "presets", "modules", "techs", "characters",
            "materials",         // 2026-09-18 第 13 类：材料规格（方块 → 物理量），见 MaterialDef
            "reactions"          // 2026-09-18 第 14 类：材料反应规则（A 挨 B → C，标签查询），见 ReactionDef
    };
    private static final Set<String> TYPE_SET = new HashSet<String>(Arrays.asList(TYPES));

    private final Map<String, Map<String, JsonObject>> byType;
    private final List<String> errors;
    private final List<String> warnings;
    private final boolean fromDisk;
    private final List<Rule> parsedRules;
    private final List<Preset> parsedPresets;
    private final Map<String, Preset.ModuleDef> parsedModules;
    private final List<TechDef> parsedTechs;
    /** P1：内容兽 / 任务（此前只有 schema、没有运行时消费者）。 */
    private final List<BeastDef> parsedBeasts;
    private final List<QuestDef> parsedQuests;
    /** 2026-09-18：材料规格（方块 → 硬度 / 密度 / 类别 / 挖掘粒子 / 标签）。 */
    private final List<MaterialDef> parsedMaterials;
    /** 材料规格按方块索引展开的查表结构（渲染层 O(1) 消费）。 */
    private final MaterialBook materialBook;
    /** 2026-09-18 批 C：材料反应规则（A 挨着 B → C，支持标签查询）。 */
    private final List<ReactionDef> parsedReactions;
    /** 反应规则按「材料对」展开的查表结构（求解器 O(1) 消费）。 */
    private final ReactionBook reactionBook;
    /** 加载期发生的覆盖记录（来源名 -> 来源名），MOD 支持的关键证据。 */
    private final List<String> overrides;

    private ContentRegistry(Map<String, Map<String, JsonObject>> byType,
                            List<String> errors, List<String> warnings, boolean fromDisk,
                            List<Rule> parsedRules,
                            List<Preset> parsedPresets,
                            Map<String, Preset.ModuleDef> parsedModules,
                            List<TechDef> parsedTechs,
                            List<BeastDef> parsedBeasts,
                            List<QuestDef> parsedQuests,
                            List<MaterialDef> parsedMaterials,
                            MaterialBook materialBook,
                            List<ReactionDef> parsedReactions,
                            ReactionBook reactionBook,
                            List<String> overrides) {
        this.byType = byType;
        this.errors = errors;
        this.warnings = warnings;
        this.fromDisk = fromDisk;
        this.parsedRules = parsedRules;
        this.parsedPresets = parsedPresets;
        this.parsedModules = parsedModules;
        this.parsedTechs = parsedTechs;
        this.parsedBeasts = parsedBeasts;
        this.parsedQuests = parsedQuests;
        this.parsedMaterials = parsedMaterials;
        this.materialBook = materialBook;
        this.parsedReactions = parsedReactions;
        this.reactionBook = (reactionBook == null)
                ? ReactionBook.empty(core.world.Blocks.count()) : reactionBook;
        this.overrides = (overrides == null) ? Collections.<String>emptyList() : overrides;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /** 从内存文件表构建（门禁与内置内容共用同一路径 → 校验逻辑只有一份）。 */
    public static ContentRegistry build(Map<String, String> files) {
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        Map<String, Map<String, JsonObject>> byType = new LinkedHashMap<String, Map<String, JsonObject>>();
        for (String t : TYPES) byType.put(t, new TreeMap<String, JsonObject>());

        List<String> keys = new ArrayList<String>(files.keySet());
        Collections.sort(keys);                                    // 字典序 → 确定性

        for (String key : keys) {
            String norm = key.replace('\\', '/');
            int slash = norm.indexOf('/');
            if (slash < 0) { errors.add("bad path: " + key); continue; }
            String type = norm.substring(0, slash);
            String file = norm.substring(slash + 1);
            if (file.indexOf('/') >= 0) file = file.substring(file.lastIndexOf('/') + 1);
            if (!TYPE_SET.contains(type)) { errors.add("unknown content type: " + type + "  (" + key + ")"); continue; }
            String id = file.endsWith(".json") ? file.substring(0, file.length() - 5) : file;
            if (id.isEmpty()) { errors.add("empty id: " + key); continue; }
            JsonObject def;
            try {
                def = JsonParser.parseString(files.get(key)).getAsJsonObject();
            } catch (RuntimeException ex) {
                errors.add("bad json: " + key + "  (" + ex.getMessage() + ")"); continue;
            }
            if (byType.get(type).containsKey(id)) { errors.add("duplicate id: " + type + "/" + id); continue; }
            byType.get(type).put(id, def);
        }

        Set<String> referenced = new HashSet<String>();
        validateSkills(byType, errors, referenced);
        validateFx(byType, errors, referenced);
        detectFxCycles(byType, errors);
        List<Rule> rules = parseRules(byType, errors, referenced);
        List<Preset> presets = parsePresets(byType, errors);
        Map<String, Preset.ModuleDef> modules = parseModules(byType);
        List<TechDef> techs = parseTechs(byType, errors);
        // P1 内容层接线：beasts / quests 从"只有 schema"变成"有运行时消费者"。
        // 这两张表此前是**死配置**（beastCap 之外，怪不会刷、任务接不到 —— EVAL-3 实测）。
        List<BeastDef> beasts = parseBeasts(byType, errors);
        List<QuestDef> quests = parseQuests(byType, errors, beasts);
        List<MaterialDef> materials = parseMaterials(byType, errors);
        MaterialBook book = MaterialBook.from(materials, core.world.Blocks.count());
        List<ReactionDef> reactions = parseReactions(byType, errors);
        ReactionBook rbook = ReactionBook.from(reactions, book, core.world.Blocks.count(), errors);
        // 覆盖度报告：缺规格的方块用兜底值（行为与历史一致），但必须**可见**——
        // 静默缺表会让"以为改了材料属性、其实走的是兜底"这种情况查不出来。
        if (!book.missing().isEmpty()) {
            warnings.add("materials: " + book.missing().size() + " block(s) without spec (fallback used): "
                    + book.missing());
        }
        orphanReport(byType, referenced, warnings);

        return new ContentRegistry(byType, errors, warnings, true, rules, presets, modules, techs,
                beasts, quests, materials, book, reactions, rbook, Collections.<String>emptyList());
    }

    /**
     * 多来源加载（MOD 支持）—— 官方内容 + 若干 mod 内容<b>合并</b>。
     *
     * <p>合并规则：按 {@link ContentSource#priority} 从低到高放置，<b>后放者覆盖先放者的同名 ID</b>；
     * 同优先级按来源名字典序（与文件系统枚举顺序无关 → 确定性）。覆盖会被记录在
     * {@link #overrides()} 里 —— 这就是"mod 改了哪些官方内容"的清单。
     *
     * <p><b>为什么这样设计</b>：合并发生在**文件表**层，之后走的是同一个 {@link #build}
     * —— 于是校验、环检测、快照、解析<b>全部复用，一行都不用改</b>。
     * 新增一个「来源」概念，换来整套 mod 能力。
     */
    public static ContentRegistry loadAll(List<ContentSource> sources) {
        if (sources == null || sources.isEmpty()) return builtin();
        List<ContentSource> ordered = new ArrayList<ContentSource>();
        for (int i = 0; i < sources.size(); i++) {
            ContentSource sc = sources.get(i);
            if (sc != null && sc.exists()) ordered.add(sc);
        }
        if (ordered.isEmpty()) return builtin();
        Collections.sort(ordered, new java.util.Comparator<ContentSource>() {
            @Override public int compare(ContentSource a, ContentSource b) {
                if (a.priority != b.priority) return a.priority - b.priority;   // 低优先先放（后被覆盖）
                return a.name.compareTo(b.name);
            }
        });

        List<String> overrides = new ArrayList<String>();
        Map<String, String> files = new LinkedHashMap<String, String>();
        Map<String, String> origin = new HashMap<String, String>();
        for (int i = 0; i < ordered.size(); i++) collect(ordered.get(i), files, origin, overrides);
        if (files.isEmpty()) return builtin();

        ContentRegistry r = build(files);
        return new ContentRegistry(r.byType, r.errors, r.warnings, true,
                r.parsedRules, r.parsedPresets, r.parsedModules, r.parsedTechs,
                r.parsedBeasts, r.parsedQuests, r.parsedMaterials, r.materialBook,
                r.parsedReactions, r.reactionBook, overrides);
    }

    /**
     * 扫描 {@code mods/} 目录：目录 mod（{@code mods/x/content/...}）与压缩包 mod
     * （{@code mods/x.zip} 内 {@code content/...}）。按名字典序返回（确定性）。
     *
     * <p>约定：mod 的内容根是它的 {@code content/} 子目录；若没有该子目录，就把 mod 目录本身当内容根。
     */
    public static List<ContentSource> discoverMods(java.io.File modsDir) {
        List<ContentSource> out = new ArrayList<ContentSource>();
        if (modsDir == null || !modsDir.isDirectory()) return out;
        java.io.File[] list = modsDir.listFiles();
        if (list == null) return out;
        List<java.io.File> sorted = new ArrayList<java.io.File>(java.util.Arrays.asList(list));
        Collections.sort(sorted, new java.util.Comparator<java.io.File>() {
            @Override public int compare(java.io.File a, java.io.File b) { return a.getName().compareTo(b.getName()); }
        });
        for (int i = 0; i < sorted.size(); i++) {
            java.io.File f = sorted.get(i);
            String n = f.getName();
            if (f.isDirectory()) {
                java.io.File content = new java.io.File(f, "content");
                out.add(ContentSource.dir(n, content.isDirectory() ? content : f, 10));
            } else if (n.toLowerCase().endsWith(".zip")) {
                out.add(ContentSource.zip(n.substring(0, n.length() - 4), f, "content/", 10));
            }
        }
        return out;
    }

    /** 把一个来源的文件并入 files 表；同名 path 由后来的覆盖并记入 overrides。 */
    private static void collect(ContentSource src, Map<String, String> files,
                                Map<String, String> origin, List<String> overrides) {
        if (src.isZip()) {
            java.util.zip.ZipFile zf = null;
            try {
                zf = new java.util.zip.ZipFile(src.zip);
                List<String> names = new ArrayList<String>();
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String n = e.getName().replace('\\', '/');
                    if (!n.startsWith(src.prefix) || !n.endsWith(".json")) continue;
                    names.add(n.substring(src.prefix.length()));
                }
                Collections.sort(names);                       // zip 条目顺序不定 → 字典序保证确定性
                for (int i = 0; i < names.size(); i++) {
                    String rel = names.get(i);
                    put(files, origin, overrides, src, rel, readZip(zf, zf.getEntry(src.prefix + rel)));
                }
            } catch (Exception ex) {
                overrides.add("ERROR " + src.name + ": " + ex.getClass().getSimpleName());
            } finally {
                try { if (zf != null) zf.close(); } catch (Exception ignored) { }
            }
        } else {
            for (String type : TYPES) {
                java.io.File d = new java.io.File(src.dir, type);
                java.io.File[] list = d.listFiles();
                if (list == null) continue;
                List<String> names = new ArrayList<String>();
                for (int i = 0; i < list.length; i++) {
                    if (list[i].isFile() && list[i].getName().endsWith(".json")) names.add(list[i].getName());
                }
                Collections.sort(names);
                for (int i = 0; i < names.size(); i++) {
                    String fn = names.get(i);
                    try { put(files, origin, overrides, src, type + "/" + fn, readAll(new java.io.File(d, fn))); }
                    catch (Exception ex) { overrides.add("ERROR " + src.name + ": " + fn); }
                }
            }
        }
    }

    private static void put(Map<String, String> files, Map<String, String> origin,
                            List<String> overrides, ContentSource src, String key, String text) {
        String prev = origin.get(key);
        if (prev != null && !prev.equals(src.name)) {
            overrides.add("override " + key + ": " + prev + " -> " + src.name);
        }
        files.put(key, text);
        origin.put(key, src.name);
    }

    private static String readZip(java.util.zip.ZipFile zf, java.util.zip.ZipEntry e) throws Exception {
        java.io.InputStream in = zf.getInputStream(e);
        try {
            java.io.InputStreamReader rd = new java.io.InputStreamReader(in, Charset.forName("UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = rd.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } finally { in.close(); }
    }

    /** 从磁盘加载 {@code assets/content/**}{@code /*.json}。目录不存在 → 返回内置内容。 */
    public static ContentRegistry load(File root) {
        Map<String, String> files = new HashMap<String, String>();
        if (root != null && root.isDirectory()) {
            for (String t : TYPES) {
                File dir = new File(root, t);
                File[] list = dir.listFiles();
                if (list == null) continue;
                for (File f : list) {
                    if (!f.isFile() || !f.getName().endsWith(".json")) continue;
                    try {
                        String json = readAll(f);
                        files.put(t + "/" + f.getName(), json);
                    } catch (Exception ex) {
                        // 读失败的单个文件不该拖垮整个加载，但必须留痕（铁律 3 的精神）
                        files.put(t + "/" + f.getName(), "{\"__readError\":\"" + ex.getClass().getSimpleName() + "\"}");
                    }
                }
            }
        }
        if (files.isEmpty()) return builtin();
        return build(files);
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            InputStreamReader rd = new InputStreamReader(in, Charset.forName("UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = rd.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } finally { in.close(); }
    }

    // ------------------------------------------------------------------
    // 内置内容（无 assets 目录时的可用默认；与磁盘内容走同一份校验）
    // ------------------------------------------------------------------

    private static final String[][] BUILTIN = {
        {"particles/spark.json", "{\"shape\":\"point\",\"count\":18,\"life\":[0.3,0.9],\"speed\":[1.5,3.5],\"gravity\":-5.0,\"drag\":0.6,\"size\":[0.05,0.12],\"color0\":[1.0,0.9,0.4,1.0],\"color1\":[1.0,0.3,0.05,0.0],\"fade\":true}"},
        {"particles/smoke.json", "{\"shape\":\"sphere\",\"count\":12,\"life\":[0.8,1.8],\"speed\":[0.4,1.2],\"gravity\":0.6,\"drag\":1.2,\"size\":[0.15,0.4],\"color0\":[0.35,0.35,0.38,0.7],\"color1\":[0.6,0.6,0.62,0.0],\"fade\":true}"},
        {"particles/leaf.json", "{\"shape\":\"box\",\"count\":10,\"life\":[1.2,2.4],\"speed\":[0.5,1.5],\"gravity\":-1.2,\"drag\":1.6,\"size\":[0.06,0.1],\"color0\":[0.4,0.75,0.25,1.0],\"color1\":[0.55,0.45,0.15,0.0],\"fade\":true}"},
        {"fx/flame_burst.json", "{\"emitters\":[{\"particle\":\"spark\",\"at\":[0,0,0],\"delay\":0.0},{\"particle\":\"smoke\",\"at\":[0,0.6,0],\"delay\":0.1}],\"sfx\":\"charge\",\"shake\":0.35,\"duration\":0.8}"},
        {"buffs/burning.json", "{\"name\":\"Burning\",\"duration\":4.0,\"tickEvery\":0.5,\"modifiers\":{\"speed\":-0.15},\"effects\":[{\"type\":\"DAMAGE\",\"amount\":3,\"target\":\"SELF\"}]}"},
        {"skills/flame_burst.json", "{\"name\":\"Flame Burst\",\"anim\":\"attack\",\"cost\":22,\"cooldown\":1.4,\"targeting\":{\"shape\":\"sphere\",\"radius\":4.0,\"maxTargets\":6},\"effects\":[{\"type\":\"PLAY_SFX\",\"id\":\"charge\"},{\"type\":\"DAMAGE\",\"amount\":26,\"target\":\"TARGET\"},{\"type\":\"SPAWN_FX\",\"id\":\"flame_burst\",\"at\":\"TARGET\",\"delay\":0.12},{\"type\":\"APPLY_BUFF\",\"id\":\"burning\",\"duration\":4.0}]}"},
        {"skills/gale_step.json", "{\"name\":\"Gale Step\",\"anim\":\"roll\",\"cost\":14,\"cooldown\":0.9,\"targeting\":{\"shape\":\"point\",\"radius\":1.0,\"maxTargets\":1},\"effects\":[{\"type\":\"PLAY_SFX\",\"id\":\"dash\"},{\"type\":\"TELEPORT\",\"amount\":6.0},{\"type\":\"SPAWN_PARTICLE\",\"id\":\"leaf\",\"delay\":0.05},{\"type\":\"SCREEN_SHAKE\",\"power\":0.15}]}"},
    };

    private static ContentRegistry builtin() {
        Map<String, String> files = new LinkedHashMap<String, String>();
        for (String[] kv : BUILTIN) files.put(kv[0], kv[1]);
        return build(files);
    }

    /** 供门禁构造「故意坏掉」的内容用（同名 type 目录下的自定义文件）。 */
    public static ContentRegistry of(Map<String, String> files) { return build(files); }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private static void validateSkills(Map<String, Map<String, JsonObject>> byType,
                                       List<String> errors, Set<String> referenced) {
        for (Map.Entry<String, JsonObject> en : byType.get("skills").entrySet()) {
            String id = en.getKey();
            JsonObject def = en.getValue();
            JsonElement effEl = def.get("effects");
            if (effEl == null || !effEl.isJsonArray()) {
                errors.add("skill " + id + ": missing effects[]");
                continue;
            }
            JsonArray arr = effEl.getAsJsonArray();
            if (arr.size() == 0) errors.add("skill " + id + ": empty effects[]");
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                if (!e.isJsonObject()) { errors.add("skill " + id + ": effect[" + i + "] not an object"); continue; }
                JsonObject eo = e.getAsJsonObject();
                String t = eo.has("type") ? eo.get("type").getAsString() : "";
                if (!Effect.WHITELIST.contains(t)) {
                    errors.add("skill " + id + ": unknown effect type '" + t + "' at [" + i + "]");
                    continue;
                }
                String table = Effect.refTable(t);
                if (table == null) continue;
                String rid = eo.has("id") ? eo.get("id").getAsString() : "";
                if (rid.isEmpty()) { errors.add("skill " + id + ": effect " + t + " missing id"); continue; }
                referenced.add(table + "/" + rid);
                if (!byType.get(table).containsKey(rid)) {
                    errors.add("skill " + id + ": dangling ref -> " + table + "/" + rid);
                }
            }
        }

        // 技能图环检测（requires 指向同表内技能才构成边；指向外部标记则忽略）
        List<String> ids = new ArrayList<String>(byType.get("skills").keySet());
        Map<String, List<String>> edges = new HashMap<String, List<String>>();
        for (Map.Entry<String, JsonObject> en : byType.get("skills").entrySet()) {
            List<String> req = new ArrayList<String>();
            JsonElement re = en.getValue().get("requires");
            if (re != null && re.isJsonArray()) {
                for (JsonElement x : re.getAsJsonArray()) if (!x.isJsonNull()) req.add(x.getAsString());
            }
            edges.put(en.getKey(), req);
        }
        List<String> cyc = new ArrayList<String>();
        if (GraphCheck.hasCycle(ids, edges, cyc)) {
            for (String c2 : cyc) errors.add("skill " + c2);
        }
    }

    /**
     * 解析 {@code beasts/*.json} 成 {@link BeastDef}。
     *
     * <p>引用完整性：{@code drop[].item} 必须存在于 {@code items}（悬空掉落 = 加载期报错，
     * 否则玩家会捡到一个不存在的物品 id）。{@code archetype} 的上界由实体层给出
     * （{@code Beast.N_TYPES-1}）—— 本类不硬编码 4，实体加了原型这里自动跟上。
     */
    private static List<BeastDef> parseBeasts(Map<String, Map<String, JsonObject>> byType,
                                              List<String> errors) {
        List<BeastDef> out = new ArrayList<BeastDef>();
        int maxArch = core.world.Beast.N_TYPES - 1;
        for (Map.Entry<String, JsonObject> en : byType.get("beasts").entrySet()) {
            BeastDef d = BeastDef.parse(en.getKey(), en.getValue(), maxArch, errors);
            if (d == null) continue;
            if (d.drops() && !byType.get("items").containsKey(d.dropItem)) {
                errors.add("beast " + en.getKey() + ": dangling drop item -> items/" + d.dropItem);
                continue;
            }
            out.add(d);
        }
        return out;
    }

    /**
     * 解析 {@code quests/*.json} 成 {@link QuestDef}。
     *
     * <p>引用完整性：节点的 {@code on.beast} 必须是已注册的内容兽 id（悬空 = 加载期报错，
     * 否则任务的目标链永远不可能达成 —— 正是 EVAL-3 里那条"接了也完不成"的死尾）。
     */
    private static List<QuestDef> parseQuests(Map<String, Map<String, JsonObject>> byType,
                                              List<String> errors, List<BeastDef> beasts) {
        Set<String> known = new HashSet<String>();
        for (int i = 0; i < beasts.size(); i++) known.add(beasts.get(i).id);
        List<QuestDef> out = new ArrayList<QuestDef>();
        for (Map.Entry<String, JsonObject> en : byType.get("quests").entrySet()) {
            QuestDef q = QuestDef.parse(en.getKey(), en.getValue(), known, errors);
            if (q != null) out.add(q);
        }
        return out;
    }

    /**
     * 解析 {@code materials/*.json} 成 {@link MaterialDef}（材料规格，2026-09-18 第 13 类）。
     *
     * <p>两条<b>加载期</b>引用完整性校验（铁律 3「未知即拒绝」）：
     * <ol>
     *   <li>id 必须是 {@link core.world.Blocks} 里真实存在的方块 —— 拼错一个方块名
     *       （{@code "STONEE"}）否则会静默变成一条永远不生效的规格；</li>
     *   <li>{@code digParticle} 必须存在于 {@code particles/} —— 悬空粒子 = 挖它时
     *       什么都不会溅（和 {@code fx → particle} 同一条纪律）。</li>
     * </ol>
     * 字段级校验（cellType / hardness / density / tags）在 {@link MaterialDef#parse}。
     */
    private static List<MaterialDef> parseMaterials(Map<String, Map<String, JsonObject>> byType,
                                                    List<String> errors) {
        List<MaterialDef> out = new ArrayList<MaterialDef>();
        for (Map.Entry<String, JsonObject> en : byType.get("materials").entrySet()) {
            String id = en.getKey();
            if (core.world.Blocks.byId(id) == null) {
                errors.add("material " + id + ": unknown block id (not registered in Blocks)");
                continue;
            }
            MaterialDef m = MaterialDef.parse(id, en.getValue(), errors);
            if (m == null) continue;
            if (!byType.get("particles").containsKey(m.digParticle)) {
                errors.add("material " + id + ": dangling digParticle -> particles/" + m.digParticle);
                continue;
            }
            out.add(m);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 材料反应规则 —— 字段级校验在 {@link ReactionDef#parse}，
     * 引用级校验（方块 id / 标签 / 空标签）在 {@link ReactionBook#from}（需要材料书才能展开标签）。
     */
    private static List<ReactionDef> parseReactions(Map<String, Map<String, JsonObject>> byType,
                                                    List<String> errors) {
        List<ReactionDef> out = new ArrayList<ReactionDef>();
        for (Map.Entry<String, JsonObject> en : byType.get("reactions").entrySet()) {
            ReactionDef r = ReactionDef.parse(en.getKey(), en.getValue(), errors);
            if (r == null) continue;
            out.add(r);
        }
        return Collections.unmodifiableList(out);
    }

    private static void validateFx(Map<String, Map<String, JsonObject>> byType,
                                   List<String> errors, Set<String> referenced) {
        for (Map.Entry<String, JsonObject> en : byType.get("fx").entrySet()) {
            String id = en.getKey();
            JsonElement emEl = en.getValue().get("emitters");
            if (emEl == null || !emEl.isJsonArray()) {
                errors.add("fx " + id + ": missing emitters[]");
                continue;
            }
            JsonArray arr = emEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                if (!e.isJsonObject()) continue;
                JsonObject eo = e.getAsJsonObject();
                if (eo.has("particle")) {
                    String pid = eo.get("particle").getAsString();
                    referenced.add("particles/" + pid);
                    if (!byType.get("particles").containsKey(pid)) {
                        errors.add("fx " + id + ": dangling particle -> " + pid);
                    }
                }
                if (eo.has("fx")) {
                    String fid = eo.get("fx").getAsString();
                    referenced.add("fx/" + fid);
                    if (!byType.get("fx").containsKey(fid)) {
                        errors.add("fx " + id + ": dangling fx -> " + fid);
                    }
                }
            }
        }
    }

    /** fx → fx 引用图找环（DFS 三色标记；只报告，不抛栈溢出）。 */
    private static void detectFxCycles(Map<String, Map<String, JsonObject>> byType, List<String> errors) {
        Map<String, List<String>> edges = new HashMap<String, List<String>>();
        for (Map.Entry<String, JsonObject> en : byType.get("fx").entrySet()) {
            List<String> out = new ArrayList<String>();
            JsonElement emEl = en.getValue().get("emitters");
            if (emEl != null && emEl.isJsonArray()) {
                for (JsonElement e : emEl.getAsJsonArray()) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("fx")) {
                        out.add(e.getAsJsonObject().get("fx").getAsString());
                    }
                }
            }
            edges.put(en.getKey(), out);
        }
        Map<String, Integer> color = new HashMap<String, Integer>();   // 0=white 1=gray 2=black
        for (String n : edges.keySet()) color.put(n, 0);
        List<String> seen = new ArrayList<String>();
        for (String n : edges.keySet()) {
            if (color.get(n) == 0) dfsCycle(n, edges, color, seen, errors);
        }
    }

    private static void dfsCycle(String n, Map<String, List<String>> edges,
                                 Map<String, Integer> color, List<String> stack, List<String> errors) {
        color.put(n, 1);
        stack.add(n);
        List<String> out = edges.get(n);
        if (out != null) {
            for (String m : out) {
                if (!edges.containsKey(m)) continue;
                if (color.get(m) == 1) {
                    StringBuilder sb = new StringBuilder("cycle: ");
                    int from = stack.indexOf(m);
                    for (int i = (from < 0 ? 0 : from); i < stack.size(); i++) sb.append(stack.get(i)).append(" -> ");
                    sb.append(m);
                    String msg = sb.toString();
                    if (!errors.contains(msg)) errors.add(msg);
                } else if (color.get(m) == 0) {
                    dfsCycle(m, edges, color, stack, errors);
                }
            }
        }
        stack.remove(stack.size() - 1);
        color.put(n, 2);
    }

    /** 孤儿统计：内容存在但没有任何技能/特效引用它（警告，不是错误——手册里可能先写内容）。 */
    private static void orphanReport(Map<String, Map<String, JsonObject>> byType,
                                     Set<String> referenced, List<String> warnings) {
        String[] checked = {"buffs", "items", "beasts", "particles", "fx"};
        int orphan = 0;
        for (String t : checked) {
            for (String id : byType.get(t).keySet()) {
                if (!referenced.contains(t + "/" + id)) orphan++;
            }
        }
        if (orphan > 0) warnings.add("orphans: " + orphan + " content defs referenced by nobody (ok if WIP)");
    }

    // ------------------------------------------------------------------
    // 玩法层解析（规则 / 预设 / 模块）
    // ------------------------------------------------------------------

    private static List<Rule> parseRules(Map<String, Map<String, JsonObject>> byType,
                                         List<String> errors, Set<String> referenced) {
        List<Rule> out = new ArrayList<Rule>();
        for (Map.Entry<String, JsonObject> en : byType.get("rules").entrySet()) {
            Rule r = Rule.parse(en.getKey(), en.getValue(), errors);
            if (r == null) continue;
            out.add(r);
            for (Effect e : r.effects) {                       // 规则引用的内容也不算孤儿
                String t = e.refTable();
                if (t == null || e.id == null || e.id.isEmpty()) continue;
                referenced.add(t + "/" + e.id);
                if (!byType.get(t).containsKey(e.id)) {        // 悬空引用 = 错误（与技能同纪律）
                    errors.add("rule " + en.getKey() + ": dangling ref -> " + t + "/" + e.id);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Preset> parsePresets(Map<String, Map<String, JsonObject>> byType,
                                             List<String> errors) {
        List<Preset> out = new ArrayList<Preset>();
        for (Map.Entry<String, JsonObject> en : byType.get("presets").entrySet()) {
            Preset p = Preset.parse(en.getKey(), en.getValue());
            if (p == null) { errors.add("preset " + en.getKey() + ": unparseable"); continue; }
            for (String m : p.modules) {
                if (!byType.get("modules").containsKey(m)) {
                    errors.add("preset " + en.getKey() + ": unknown module -> " + m);
                }
            }
            for (String r : p.rules) {
                if ("*".equals(r) || r.startsWith("module:")) continue;
                if (!byType.get("rules").containsKey(r)) {
                    errors.add("preset " + en.getKey() + ": unknown rule -> " + r);
                }
            }
            // 投递路径：把「启用模块」各自的 params 注入预设（模块默认值；预设自身 params 覆盖它们）。
            // 不做这一步，模块 params 就只是**文档**而不是配置 —— 见 Preset.moduleParams() 的说明。
            Map<String, Float> modParams = new LinkedHashMap<String, Float>();
            for (String m : p.modules) {
                JsonObject mo = byType.get("modules").get(m);
                if (mo == null) continue;                       // 未知模块已在上方报错
                JsonElement pe = mo.get("params");
                if (pe == null || !pe.isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> me : pe.getAsJsonObject().entrySet()) {
                    try { modParams.put(me.getKey(), me.getValue().getAsFloat()); }
                    catch (RuntimeException ignored) { }
                }
            }
            p.attachModuleParams(modParams);
            out.add(p);
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, Preset.ModuleDef> parseModules(Map<String, Map<String, JsonObject>> byType) {
        Map<String, Preset.ModuleDef> out = new LinkedHashMap<String, Preset.ModuleDef>();
        for (Map.Entry<String, JsonObject> en : byType.get("modules").entrySet()) {
            Preset.ModuleDef m = Preset.ModuleDef.parse(en.getKey(), en.getValue());
            if (m != null) out.put(en.getKey(), m);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 解析科技图（配方式有向图）。
     *
     * <p>加载期做两件事：① 逐条解析（非法效果被拒）；② <b>环检测</b>
     * —— 科技图成环意味着"谁也解锁不了谁都等对方"的死锁，必须在加载期抓出。
     *
     * <p>{@code requires} 里的名字若<b>不是</b>另一个 tech，就当作外部标记
     * （{@code World.skills} 里的世界既有机制，如 {@code _blueprint_palisade}）——
     * 那是运行时状态，加载期不校验。
     */
    private static List<TechDef> parseTechs(Map<String, Map<String, JsonObject>> byType,
                                            List<String> errors) {
        List<TechDef> out = new ArrayList<TechDef>();
        int order = 0;
        for (Map.Entry<String, JsonObject> en : byType.get("techs").entrySet()) {
            TechDef t = TechDef.parse(en.getKey(), en.getValue(), errors, order++);
            if (t != null) out.add(t);
        }
        List<String> cyc = new ArrayList<String>();
        if (TechTree.hasCycle(out, cyc)) {
            for (String c : cyc) errors.add("tech " + c);
        }
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 方块 → 挖它掉什么物品（惰性构建；见 {@link #itemForBlock}）。 */
    private Map<String, String> blockDropIndex = null;

    /**
     * 挖掉某个方块应该掉什么物品 —— **反向索引**：由 item 的 {@code block} 字段说了算。
     *
     * <p><b>为什么需要它（2026-09-17 修）</b>：原实现用 {@code blockId.toLowerCase()} 猜物品 id，
     * 于是挖 {@code COAL_ORE} 掉 {@code coal_ore}、挖 {@code IRON_ORE} 掉 {@code iron_ore}；
     * 而 {@code techs/smelting.json} 的成本要的是 {@code ore}/{@code coal} ——
     * 这两个物品**没有 block 字段、永远拿不到** → **整条冶炼/钢铁链的成本从来没被支付过**，
     * 配方只是装饰。根因是"靠命名巧合对齐"：一旦命名不一致就静默失配，没有任何东西会报错。
     *
     * <p>回退：没有 item 声明该方块时，退回「id == blockId.toLowerCase() 且该物品存在」，
     * 保持既有内容（stone/dirt/sand/wood/…）行为逐字不变。没有对应物品则返回 {@code null}（不拾取）。
     */
    public String itemForBlock(String blockId) {
        if (blockId == null) return null;
        if (blockDropIndex == null) {
            Map<String, String> m = new HashMap<String, String>();
            Map<String, JsonObject> items = of("items");
            // 第 1 遍：**主绑定**（`item.block`）—— 优先级最高，先占先得。
            for (Map.Entry<String, JsonObject> e : items.entrySet()) {
                JsonElement b = e.getValue().get("block");
                if (b == null || b.isJsonNull() || !b.isJsonPrimitive()) continue;
                String bid = b.getAsString();
                if (bid == null || bid.isEmpty() || m.containsKey(bid)) continue;   // 一物一主（先到先得）
                m.put(bid, e.getKey());
            }
            // 第 2 遍：**别名**（`item.alsoDropsFrom`）—— 让"一个物品覆盖同一作物的多个阶段"成为可能
            //  （2026-09-25）：`wheat_seeds` 声明 alsoDropsFrom=[WHEAT_1,WHEAT_2] ⇒ 打掉没长熟的小麦也能收回种子。
            //  放在第 2 遍是刻意的：**主绑定永远赢**，别名只在没人认领时补位 —— 否则一个写了宽泛别名的物品
            //  会悄悄抢走别人的掉落（这正是"靠命名巧合对齐"那类静默失配的翻版）。
            for (Map.Entry<String, JsonObject> e : items.entrySet()) {
                JsonElement a = e.getValue().get("alsoDropsFrom");
                if (a == null || !a.isJsonArray()) continue;
                for (JsonElement x : a.getAsJsonArray()) {
                    if (x == null || !x.isJsonPrimitive()) continue;
                    String bid = x.getAsString();
                    if (bid == null || bid.isEmpty() || m.containsKey(bid)) continue;
                    m.put(bid, e.getKey());
                }
            }
            blockDropIndex = m;
        }
        String hit = blockDropIndex.get(blockId);
        if (hit != null) return hit;
        String lower = blockId.toLowerCase();
        return of("items").containsKey(lower) ? lower : null;
    }

    public Map<String, JsonObject> of(String type) {
        Map<String, JsonObject> m = byType.get(type);
        return m == null ? Collections.<String, JsonObject>emptyMap() : Collections.unmodifiableMap(m);
    }

    public JsonObject get(String type, String id) {
        Map<String, JsonObject> m = byType.get(type);
        return m == null ? null : m.get(id);
    }

    public int size(String type) {
        Map<String, JsonObject> m = byType.get(type);
        return m == null ? 0 : m.size();
    }

    /** 玩法规则（按 ID 字典序 = 确定性顺序）。 */
    public List<Rule> rules() { return parsedRules; }

    /** 预设列表（按 ID 字典序）。 */
    public List<Preset> presets() { return parsedPresets; }

    /** 按 id 取预设；不存在返回 null。 */
    public Preset preset(String id) {
        if (id == null) return null;
        for (Preset p : parsedPresets) if (p.id.equals(id)) return p;
        return null;
    }

    /** 玩法模块元数据表。 */
    public Map<String, Preset.ModuleDef> modules() { return parsedModules; }

    /** 科技图（按注册序 = 字典序，确定性）。 */
    public List<TechDef> techs() { return parsedTechs; }

    /** 内容兽定义（按文件字典序 = 确定顺序；推进时按同一下标索引）。 */
    public List<BeastDef> beastDefs() { return parsedBeasts; }
    /** 任务定义（同上）。 */
    public List<QuestDef> questDefs() { return parsedQuests; }

    /** 材料规格（按方块 id 字典序 = 确定性顺序）。 */
    public List<MaterialDef> materials() { return parsedMaterials; }

    /**
     * 材料规格书（按方块索引 O(1) 查表）。
     *
     * <p><b>消费者</b>：{@code render/lwjgl/Game.digHardness}（挖掘硬度）与
     * {@code Game.digTick}/{@code breakTarget}（挖掘粒子）。均为渲染/输入层 → 零仿真漂移。
     */
    public MaterialBook materialBook() { return materialBook; }

    /** 材料反应规则（按文件名 id 字典序 = 确定性顺序）。 */
    public List<ReactionDef> reactions() { return parsedReactions; }

    /**
     * 反应表（按「材料对」O(1) 查表）。
     *
     * <p><b>消费者</b>：{@code core.systems.ReactionSystem}（唯一求解器）。
     * 是否真正执行由 {@code WorldConfig.reactionTable} 决定。
     */
    public ReactionBook reactionBook() { return reactionBook; }

    /** 按 id 取内容兽；不存在返回 {@code null}。 */
    public BeastDef beastDef(String id) {
        if (id == null) return null;
        for (int i = 0; i < parsedBeasts.size(); i++) {
            if (parsedBeasts.get(i).id.equals(id)) return parsedBeasts.get(i);
        }
        return null;
    }

    /** 角色预设原始表（F 批捏脸；id = 文件名，字典序 = 确定性）。 */
    public Map<String, JsonObject> characters() { return of("characters"); }

    /** 按 id 取科技；不存在返回 null。 */
    public TechDef tech(String id) {
        if (id == null) return null;
        for (int i = 0; i < parsedTechs.size(); i++) {
            if (parsedTechs.get(i).id.equals(id)) return parsedTechs.get(i);
        }
        return null;
    }

    /** 按 id 取物品定义（E 批 2026-09-13）；不存在返回 null。 */
    public ItemDef item(String id) {
        return id == null ? null : ItemDef.from(id, get("items", id));
    }

    /**
     * 物品查询桥（背包用）：未知物品返回保守默认（stack=64、无耐久），绝不抛异常 ——
     * 一个 mod 打错物品名不该让背包崩溃（见 {@link ItemBook} 约定）。
     */
    public ItemBook itemBook() {
        final ContentRegistry self = this;
        return new ItemBook() {
            @Override public int stack(String it) {
                ItemDef d = self.item(it);
                return d != null ? d.stack : 64;
            }
            @Override public int maxDurability(String it) {
                ItemDef d = self.item(it);
                return d != null ? d.durability : 0;
            }
        };
    }

    public int total() {
        int n = 0;
        for (String t : TYPES) n += size(t);
        return n;
    }

    /** 覆盖记录（如 {@code override skills/x.json: base -> mymod}）—— "mod 改了哪些官方内容"。 */
    public List<String> overrides() { return Collections.unmodifiableList(overrides); }

    public List<String> errors()   { return Collections.unmodifiableList(errors); }
    public List<String> warnings() { return Collections.unmodifiableList(warnings); }
    public boolean ok()            { return errors.isEmpty(); }
    public boolean fromDisk()      { return fromDisk; }

    /** 确定性快照：类型顺序固定 + ID 排序 → 同内容必得同字符串（门禁 DET 用）。 */
    public String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String t : TYPES) {
            sb.append('[').append(t).append(':').append(size(t)).append("]\n");
            for (Map.Entry<String, JsonObject> en : byType.get(t).entrySet()) {
                sb.append(en.getKey()).append('=').append(en.getValue().toString()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 人类可读加载报告（启动时写日志）。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("content: ").append(fromDisk ? "disk" : "builtin").append(", total=").append(total());
        for (String t : TYPES) sb.append(' ').append(t).append('=').append(size(t));
        sb.append('\n');
        for (String e : errors)   sb.append("  ERROR   ").append(e).append('\n');
        for (String w : warnings) sb.append("  WARN    ").append(w).append('\n');
        return sb.toString();
    }
}
