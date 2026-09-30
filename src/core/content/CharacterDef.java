package core.content;

import com.google.gson.JsonObject;

/**
 * 角色预设定义（内容层 · F 批捏脸）。
 *
 * <p>与 {@link ItemDef} 同构：纯数据、由 {@code assets/content/characters/*.json} 配置，
 * 经 {@link ContentRegistry} 加载期解析。本类与 RNG / 网格零接触，纯解析。
 *
 * <p>字段（全部可选，缺省走兜底值）：
 * <ul>
 *   <li>{@code name}：展示名（中文）。</li>
 *   <li>{@code skin/hair/accent/eye}：颜色，支持 {@code "#RRGGBB"} 字符串或十进制整数。</li>
 *   <li>{@code hairStyle}：0..3；{@code build}：0..2。</li>
 *   <li>{@code bio}：风味文本（UI 展示，不参与仿真）。</li>
 * </ul>
 */
public final class CharacterDef {

    public String id;
    public String name = "";
    public int skin = 0xE0AC69;
    public int hair = 0x3B2A1A;
    public int hairStyle = 0;
    public int build = 1;
    public int accent = 0x6FA8DC;
    public int eye = 0x4B3621;
    public int faceShape = 1;
    public int eyeShape = 0;
    public int browStyle = 0;
    public int noseStyle = 0;
    public int mouthStyle = 0;
    public int age = 0;
    public int height = 1;
    public int tattoo = 0;
    public int beard = 0;
    public int armor = 0;
    public int helmet = 0;
    public int cloak = 0;
    public String bio = "";

    public static CharacterDef fromJson(String id, JsonObject o) {
        CharacterDef d = new CharacterDef();
        d.id = id;
        if (o == null) return d;
        d.name = o.has("name") ? o.get("name").getAsString() : id;
        d.skin = color(o, "skin", d.skin);
        d.hair = color(o, "hair", d.hair);
        d.accent = color(o, "accent", d.accent);
        d.eye = color(o, "eye", d.eye);
        if (o.has("hairStyle")) d.hairStyle = clampInt(o.get("hairStyle").getAsInt(), 0, 5);
        if (o.has("build")) d.build = clampInt(o.get("build").getAsInt(), 0, 2);
        if (o.has("faceShape")) d.faceShape = clampInt(o.get("faceShape").getAsInt(), 0, 3);
        if (o.has("eyeShape")) d.eyeShape = clampInt(o.get("eyeShape").getAsInt(), 0, 2);
        if (o.has("browStyle")) d.browStyle = clampInt(o.get("browStyle").getAsInt(), 0, 2);
        if (o.has("noseStyle")) d.noseStyle = clampInt(o.get("noseStyle").getAsInt(), 0, 2);
        if (o.has("mouthStyle")) d.mouthStyle = clampInt(o.get("mouthStyle").getAsInt(), 0, 2);
        if (o.has("age")) d.age = clampInt(o.get("age").getAsInt(), 0, 2);
        if (o.has("height")) d.height = clampInt(o.get("height").getAsInt(), 0, 2);
        if (o.has("tattoo")) d.tattoo = clampInt(o.get("tattoo").getAsInt(), 0, 3);
        if (o.has("beard")) d.beard = clampInt(o.get("beard").getAsInt(), 0, 3);
        if (o.has("armor")) d.armor = clampInt(o.get("armor").getAsInt(), 0, 3);
        if (o.has("helmet")) d.helmet = clampInt(o.get("helmet").getAsInt(), 0, 3);
        if (o.has("cloak")) d.cloak = clampInt(o.get("cloak").getAsInt(), 0, 2);
        if (o.has("bio")) d.bio = o.get("bio").getAsString();
        return d;
    }

    private static int color(JsonObject o, String k, int fallback) {
        if (!o.has(k)) return fallback;
        try {
            String v = o.get(k).getAsString().trim();
            if (v.startsWith("#")) return (int) Long.parseLong(v.substring(1), 16) & 0xFFFFFF;
            return Integer.parseInt(v) & 0xFFFFFF;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
