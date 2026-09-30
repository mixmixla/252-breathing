package core.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 材料规格（{@code assets/content/materials/<BLOCK_ID>.json}）—— 内容平台第 13 类。
 *
 * <p><b>为什么需要它（2026-09-18）</b>：此前方块的「仿真属性」散落在代码里 ——
 * 挖矿硬度是 {@code Game.digHardness()} 里一串 {@code id.contains("STONE")}、
 * 挖掘碎屑是写死的 {@code "dust"}、材料类别根本不存在。
 * 于是每加一种材料都要回去改代码分支，忘了就静默掉进兜底值。
 * 本类把<b>材料的物理规格</b>从代码搬进数据表：一张表说清每种材料是什么。
 *
 * <p>参考 Noita 的 {@code data/materials.xml}（见 {@code docs/NOITA_STUDY.md}）的核心洞察：
 * <b>用极少的物理量撑起全部分层</b> —— {@code cellType}（4+1 类）+ {@code density}（一个标量）。
 * 本表只取这两条与「破坏手感」相关的量，<b>不引入逐像素求解器</b>。
 *
 * <p>schema（全部可选，缺省即兜底值）：
 * <pre>
 * {
 *   "name":        "Stone",        // 显示名（默认 id）
 *   "cellType":    "solid",        // solid / liquid / gas / plant / powder（默认 solid）
 *   "hardness":    1.05,           // 空手基准挖掘秒数（&gt;0；默认 0.70）
 *   "density":     26,             // 密度（空气=1 基准，&gt;=1；默认 20）
 *   "digParticle": "dust",         // 挖掘/破坏时溅的粒子 id（默认 dust）
 *   "lightDecay":  0.56,           // 光穿过该材料的衰减系数（0,1]；缺省按 cellType 推导）
 *   "tags":        ["stone"]       // 标签（必须属于 {@link #TAGS}）
 * }
 * </pre>
 *
 * <p><b>零漂移</b>：本类纯数据解析，无 RNG、不碰网格。当前消费者全部在渲染/输入层
 * （硬度 → {@code Game.digHardness}；粒子 → {@code Game.digTick/breakTarget}）
 * 与加载期校验（{@code ContentTest.MATERIAL}）—— <b>不进 {@code hashState}，不改任何仿真指纹</b>。
 *
 * <p><b>density 与 tags 的诚实边界</b>：这两个字段当前<b>没有仿真层生产消费者</b>；
 * 它们的消费者是 ① 加载期校验（值域 / 类别自洽 / 排序不变量）② 门禁里的跨表断言
 * （{@code tags} 含 {@code ore} ⇒ 该方块必须能掉出物品）。
 * 它们是<b>为下一步「密度驱动统一下落」准备的数据</b>（见 docs/NOITA_LESSONS.md 第二件改造），
 * 届时才需要重锁基线。在此之前它们只被校验、不改变任何行为。
 */
public final class MaterialDef {

    /**
     * 材料物理类别 —— Noita {@code cell_type} 的精简映射（4 类 + 1 个「松散层」）。
     *
     * <p>为什么是这 5 个：它们刚好覆盖本项目的全部方块，且每一类都能与
     * {@link core.world.Blocks.Block} 的 {@code solid/liquid} 标记做<b>自洽校验</b>
     * （见 {@code MaterialBook} 的门禁断言），不会出现"表里说固体、代码里能穿过"。
     */
    public static final String[] CELL_TYPES = { "solid", "liquid", "gas", "plant", "powder" };
    private static final Set<String> CELL_TYPE_SET = new HashSet<String>(Arrays.asList(CELL_TYPES));

    /**
     * 已知标签集。标签的价值是「<b>一条规则覆盖一整类材料</b>」——
     * 这是 Noita 用 77 个标签让 328 条反应覆盖全组合的全部秘密（见 docs/NOITA_STUDY.md §3）。
     * 未知标签 = 加载期报错（铁律 3「未知即拒绝」），否则拼错一个标签会静默变成死标签。
     */
    public static final String[] TAGS = {
            "stone", "soil", "sand", "wood", "plant", "metal", "ore",
            "glass", "ice", "liquid", "fire", "light",
            "dry"                       // 2026-09-18 批 C：可燃的干枯植被（草/花/仙人掌）
                                        // —— 反应表用 [dry] 一条规则覆盖三种材料，见 ReactionBook
    };
    private static final Set<String> TAG_SET = new HashSet<String>(Arrays.asList(TAGS));

    /** 缺省规格（无材料表 / 表里没这个方块时用）：与历史兜底值一致。 */
    public static final float DEFAULT_HARDNESS = 0.70f;
    public static final int DEFAULT_DENSITY = 20;
    public static final String DEFAULT_DIG_PARTICLE = "dust";

    // ---------- 光照衰减（Terraria 两扫光照的 per-material 旋钮）----------
    /**
     * 空气衰减系数 —— 学 Terraria {@code LightDecayThroughAir = 0.91f}
     * （{@code LightingEngine.cs} / {@code LightMap.cs}）。
     *
     * <p>含义：光每穿过一个<b>透明</b>格（空气/气体），强度乘以该系数。
     * 0.91 意味着约 30 格衰减到 6%（对数衰减），这正是 Terraria 里火把"照出一片柔和渐变"
     * 而非我们旧的"每格减 1，14 格硬截断"的观感来源。
     */
    public static final float LIGHT_DECAY_AIR = 0.91f;
    /** 不透明实体衰减系数 —— 学 Terraria {@code LightDecayThroughSolid = 0.56f}。 */
    public static final float LIGHT_DECAY_SOLID = 0.56f;
    /**
     * 液体的默认衰减（缺省）。Terraria 每个 waterStyle 有一套 <b>(r,g,b) 三通道</b>衰减
     * （如水 {@code (0.88f, 0.96f, 1.015f) * 0.91f}）—— 本项目的实现是：<b>标量衰减 + 一个色调</b>
     * （见 {@link #LIGHT_TINT_WATER}），因为我们的世界只有水一种液体，
     * 数值上等价于三通道衰减，但省掉了每格三次浮点乘。
     * <p>取 Terraria 水三通道的几何均值 0.88*0.96*1.015 ≈ 0.857，再乘 0.91 ≈ 0.78。
     */
    public static final float LIGHT_DECAY_LIQUID = 0.78f;
    /** 植物的默认衰减：介于空气与实体之间（叶隙透光，但不全透）。 */
    public static final float LIGHT_DECAY_PLANT = 0.82f;


    public final String id;           // = Blocks 的方块 id（大写），加载期交叉校验
    public final String name;
    public final String cellType;
    public final float hardness;      // 空手基准挖掘秒数
    public final int density;         // 空气 = 1 基准
    public final String digParticle;
    /** 光穿过该材料的衰减系数 (0,1]。缺省按 {@link #cellType} 推导（见 {@link #defaultLightDecay}）。 */
    public final float lightDecay;
    private final List<String> tags;

    private MaterialDef(String id, String name, String cellType, float hardness,
                        int density, String digParticle, float lightDecay, List<String> tags) {
        this.id = id; this.name = name; this.cellType = cellType;
        this.hardness = hardness; this.density = density;
        this.digParticle = digParticle;
        this.lightDecay = lightDecay;
        this.tags = tags;
    }

    /**
     * {@code cellType} → 默认光衰减。这是"table 只写例外"的体现：绝大多数材料不必写 {@code lightDecay}，
     * 只有特殊材料（如水/冰/玻璃）才需要覆写。
     */
    public static float defaultLightDecay(String cellType) {
        if ("liquid".equals(cellType)) return LIGHT_DECAY_LIQUID;
        if ("plant".equals(cellType)) return LIGHT_DECAY_PLANT;
        if ("gas".equals(cellType)) return LIGHT_DECAY_AIR;
        if ("powder".equals(cellType)) return LIGHT_DECAY_SOLID;   // 松散但挡光，与实体同档
        return LIGHT_DECAY_SOLID;                                  // solid 及未知
    }

    public List<String> tags() { return tags; }
    public boolean hasTag(String t) { return tags.contains(t); }

    /**
     * 解析一条材料规格；任何字段非法 → 记错误并返回 {@code null}（该材料不进书）。
     *
     * <p>字段级校验在这里；<b>引用级校验</b>（id 是不是真方块、digParticle 是不是真粒子）
     * 在 {@link ContentRegistry} 里做 —— 因为那需要看到其它内容表。
     */
    public static MaterialDef parse(String id, JsonObject o, List<String> errors) {
        if (o == null) { errors.add("material " + id + ": null definition"); return null; }

        String name = str(o, "name", id);

        String cellType = str(o, "cellType", "solid");
        if (!CELL_TYPE_SET.contains(cellType)) {
            errors.add("material " + id + ": unknown cellType '" + cellType
                    + "' (expected one of " + CELL_TYPE_SET + ")");
            return null;
        }

        float hardness = num(o, "hardness", DEFAULT_HARDNESS);
        if (!(hardness > 0f) || Float.isNaN(hardness) || Float.isInfinite(hardness)) {
            errors.add("material " + id + ": hardness must be > 0 (got " + hardness + ")");
            return null;
        }

        int density = (int) num(o, "density", DEFAULT_DENSITY);
        if (density < 1) {
            errors.add("material " + id + ": density must be >= 1 (got " + density + ")");
            return null;
        }

        String digParticle = str(o, "digParticle", DEFAULT_DIG_PARTICLE);
        if (digParticle.isEmpty()) {
            errors.add("material " + id + ": digParticle must not be empty");
            return null;
        }

        // lightDecay：缺省按 cellType 推导；显式给出则必须落在 (0,1]。
        float lightDecay;
        JsonElement ld = o.get("lightDecay");
        if (ld != null && ld.isJsonPrimitive()) {
            lightDecay = ld.getAsFloat();
            if (!(lightDecay > 0f) || lightDecay > 1f
                    || Float.isNaN(lightDecay) || Float.isInfinite(lightDecay)) {
                errors.add("material " + id + ": lightDecay must be in (0,1] (got " + lightDecay + ")");
                return null;
            }
        } else {
            lightDecay = defaultLightDecay(cellType);
        }

        List<String> tags = new ArrayList<String>();
        JsonElement te = o.get("tags");
        if (te != null && te.isJsonArray()) {
            JsonArray arr = te.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                if (e == null || !e.isJsonPrimitive()) continue;
                String t = e.getAsString();
                if (!TAG_SET.contains(t)) {
                    errors.add("material " + id + ": unknown tag '" + t + "' (known: " + TAG_SET + ")");
                    return null;
                }
                if (!tags.contains(t)) tags.add(t);
            }
        } else if (te != null && !te.isJsonNull()) {
            errors.add("material " + id + ": tags must be an array of strings");
            return null;
        }

        return new MaterialDef(id, name, cellType, hardness, density, digParticle,
                lightDecay, Collections.unmodifiableList(tags));
    }

    public String summary() {
        return name + " [" + cellType + "] hard=" + hardness + "s dens=" + density
                + " dig=" + digParticle + " light=" + lightDecay
                + (tags.isEmpty() ? "" : " tags=" + tags);
    }

    // ---- 小工具（与 ItemDef / Effect / Rule 的解析风格一致）----

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }
    private static float num(JsonObject o, String k, float def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsFloat() : def;
    }
}
