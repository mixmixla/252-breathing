package core.world;

/**
 * 玩家外观（捏脸系统 · F 批，阶段1扩展）。
 *
 * <p>纯数据、零 RNG、零 GL。<b>身份数据，不是世界状态</b>——因此<b>不进</b>
 * {@link World#hashState()} 确定性指纹（与 {@code Player} 的养成/装备同纪律），
 * 对四道零漂移门禁指纹零影响。改外观不会让存档/回放/多人同步错位。
 *
 * <p>字段语义（全部整数 / 字符串，便于存档与确定性；保持 MenuModel「可比较整数」纪律）：
 * <ul>
 *   <li>{@code presetId}：所选内容预设 id（{@code assets/content/characters/*.json} 的文件名）；
 *       空 / 找不到时退回默认 {@code wanderer}。</li>
 *   <li>{@code name}：角色名（中文，UI 展示；不参与仿真）。</li>
 *   <li>{@code skin/hair/accent/eye}：颜色（0xRRGGBB），纯视觉。</li>
 *   <li>{@code hairStyle}：0..5（短/中/长/光/辫/丸子）；{@code build}：0..2（瘦/标准/壮）。</li>
 *   <li>{@code faceShape}：0..3（圆/方/尖/长）；{@code height}：0..2（矮0.92/标准1.0/高1.08）。</li>
 *   <li>{@code eyeShape/browStyle/noseStyle/mouthStyle}：各 0..2（五官基础形态）。</li>
 *   <li>{@code age}：0..2（青年/中年/老年）；{@code tattoo}：0..3（无/左脸疤/右臂纹/额痕）。</li>
 *   <li>{@code beard}：0..3（无/短/络腮/山羊）。</li>
 *   <li>{@code armor/helmet/cloak}：服装层（0..3 / 0..3 / 0..2），与 gameplay {@code player.armor} 正交的纯外观覆盖几何。</li>
 * </ul>
 */
public final class Appearance {

    public String presetId = "wanderer";
    public String name = "\u6444\u6d3d\u8005";          // 漂泊者

    // ---- 颜色（0xRRGGBB，纯视觉）----
    public int skin = 0xE0AC69;
    public int hair = 0x3B2A1A;
    public int accent = 0x6FA8DC;
    public int eye = 0x4B3621;

    // ---- 发型 / 体型（档位）----
    public int hairStyle = 0;   // 0..5 短/中/长/光/辫/丸子
    public int build = 1;       // 0..2 瘦/标准/壮

    // ---- 阶段1 新增：脸型 / 五官 / 年龄 / 身高 / 纹身 / 胡须（全部整数档）----
    public int faceShape = 1;   // 0..3 圆/方/尖/长
    public int eyeShape = 0;    // 0..2 圆/细长/上扬
    public int browStyle = 0;   // 0..2 平/浓/挑
    public int noseStyle = 0;   // 0..2 无/小/挺
    public int mouthStyle = 0;  // 0..2 平/笑/抿
    public int age = 0;         // 0..2 青年/中年/老年
    public int height = 1;      // 0..2 矮(0.92)/标准(1.0)/高(1.08)
    public int tattoo = 0;      // 0..3 无/左脸疤/右臂纹/额痕
    public int beard = 0;       // 0..3 无/短/络腮/山羊
    // ---- 阶段 B 新增：服装层（纯外观覆盖几何，与 gameplay 护甲数值正交） ----
    public int armor = 0;       // 0..3 无/皮甲/锁甲/板甲
    public int helmet = 0;      // 0..3 无/皮盔/铁盔/角盔
    public int cloak = 0;       // 0..2 无/短披风/长披风

    public void copyFrom(Appearance o) {
        if (o == null) return;
        this.presetId = o.presetId;
        this.name = o.name;
        this.skin = o.skin;
        this.hair = o.hair;
        this.hairStyle = o.hairStyle;
        this.build = o.build;
        this.accent = o.accent;
        this.eye = o.eye;
        this.faceShape = o.faceShape;
        this.eyeShape = o.eyeShape;
        this.browStyle = o.browStyle;
        this.noseStyle = o.noseStyle;
        this.mouthStyle = o.mouthStyle;
        this.age = o.age;
        this.height = o.height;
        this.tattoo = o.tattoo;
        this.beard = o.beard;
        this.armor = o.armor;
        this.helmet = o.helmet;
        this.cloak = o.cloak;
    }

    public Appearance copy() {
        Appearance a = new Appearance();
        a.copyFrom(this);
        return a;
    }

    /** 把内容预设 {@link core.content.CharacterDef} 套用到本外观（保留 presetId/name 取自预设）。 */
    public void applyDef(core.content.CharacterDef d) {
        if (d == null) return;
        this.presetId = d.id;
        this.name = d.name;
        this.skin = d.skin;
        this.hair = d.hair;
        this.hairStyle = d.hairStyle;
        this.build = d.build;
        this.accent = d.accent;
        this.eye = d.eye;
        this.faceShape = d.faceShape;
        this.eyeShape = d.eyeShape;
        this.browStyle = d.browStyle;
        this.noseStyle = d.noseStyle;
        this.mouthStyle = d.mouthStyle;
        this.age = d.age;
        this.height = d.height;
        this.tattoo = d.tattoo;
        this.beard = d.beard;
        this.armor = d.armor;
        this.helmet = d.helmet;
        this.cloak = d.cloak;
    }
}
