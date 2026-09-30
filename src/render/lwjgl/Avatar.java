package render.lwjgl;

/**
 * 角色外观系统（AVATAR · 渲染层纯映射，无状态）。
 *
 * 职责：把「养成 / 职业 / 捏脸」翻译成可看见的形体参数——装备决定衣着与手持武器，
 * 职业决定村民帽式，捏脸决定脸型 / 身高 / 五官 / 胡须 / 纹身。供 Game.drawEntities 与
 * drawFacePreview 消费。
 *
 * 零漂移纪律：全部静态只读映射，不读 sim 状态、不碰 RNG/网格 → 不进 hashState，
 * 对四道基线门禁指纹零影响。颜色基准对齐 ART_BIBLE 低饱和体素风。
 *
 * 设计原则（CD-PILLARS P2「养成被看见」）：玩家拿到更好的护甲/武器时，
 * 角色外观必须立刻变化——数值成长要有形体回响。
 */
public final class Avatar {

    private Avatar() {}

    // ---- 玩家护甲 → 衣着主色（躯干）。索引对齐 Player.ARMORS：0 Rags / 1 Leather / 2 Plate / 3 Ward Cloak ----
    public static float[] bodyColor(int armor) {
        switch (armor) {
            case 1:  return new float[]{0.48f, 0.34f, 0.20f};   // Leather 皮革棕
            case 2:  return new float[]{0.78f, 0.80f, 0.85f};   // Plate 银白板甲
            case 3:  return new float[]{0.36f, 0.24f, 0.52f};   // Ward 守护紫袍
            default: return new float[]{0.55f, 0.48f, 0.38f};   // Rags 粗布灰褐
        }
    }

    /** 玩家腿部颜色（衣着次色：主色压暗）。 */
    public static float[] legColor(int armor) {
        float[] c = bodyColor(armor);
        return new float[]{c[0] * 0.58f, c[1] * 0.58f, c[2] * 0.64f};
    }

    // ---- 玩家武器 → 手持武器视觉（贴右手，分段盒近似长条）。----
    // 索引对齐 Weapons.LIST：0 Fist / 1 Iron Sword / 2 War Axe / 3 Hunting Bow / 4 Rune Edge。
    // 返回 {segLen, thick, headLen, r, g, b}：segLen=单节长、thick=截面、headLen=刃/斧头加粗段长；Fist 返回 null。
    public static float[] weapon(int weaponIdx) {
        switch (weaponIdx) {
            case 1:  return new float[]{0.20f, 0.05f, 0.30f, 0.80f, 0.83f, 0.88f}; // Iron Sword 细长灰白
            case 2:  return new float[]{0.16f, 0.07f, 0.16f, 0.55f, 0.57f, 0.62f}; // War Axe 短粗+宽头
            case 3:  return new float[]{0.24f, 0.04f, 0.00f, 0.58f, 0.42f, 0.26f}; // Hunting Bow 长弓棕
            case 4:  return new float[]{0.22f, 0.06f, 0.34f, 0.45f, 0.75f, 0.85f}; // Rune Edge 符文青刃
            default: return null;                                                   // Fist 空手
        }
    }

    /** 玩家等级 → 肩章色（level≥2 出现；数值成长的轻量形体回响）。返回 null=无肩章。 */
    public static float[] rankColor(int level) {
        if (level >= 5) return new float[]{0.85f, 0.30f, 0.25f};   // 红·老练
        if (level >= 4) return new float[]{0.95f, 0.75f, 0.30f};   // 金·精锐
        if (level >= 3) return new float[]{0.45f, 0.85f, 0.80f};   // 青·见习骑士
        if (level >= 2) return new float[]{0.55f, 0.62f, 0.70f};   // 灰·初阶
        return null;
    }

    // ---- 村民职业 → 帽式。返回 {r, g, b, hatType}；hatType：0 无 / 1 宽扁草帽 / 2 高帽 / 3 兜帽 / 4 平盔 ----
    public static float[] villagerHat(String profession) {
        if (profession == null) return new float[]{0.55f, 0.50f, 0.45f, 0f};
        switch (profession) {
            case "farmer":    return new float[]{0.82f, 0.72f, 0.30f, 1f};   // 草帽·宽扁黄
            case "crafter":   return new float[]{0.45f, 0.32f, 0.20f, 3f};   // 工装头巾·兜帽棕
            case "trader":    return new float[]{0.88f, 0.68f, 0.20f, 2f};   // 商帽·高顶金
            case "herbalist": return new float[]{0.26f, 0.55f, 0.38f, 3f};   // 兜帽·药草绿
            case "guard":     return new float[]{0.55f, 0.58f, 0.66f, 4f};   // 制式盔·钢灰
            default:          return new float[]{0.55f, 0.50f, 0.45f, 0f};
        }
    }

    // =========================================================================
    // 阶段1 · 捏脸渲染映射（纯函数，零 RNG，不进 hashState）
    // =========================================================================

    /** 脸型 → 头部半宽/高（基准 hw=0.18, h=0.30）。0 圆 / 1 方 / 2 尖 / 3 长。 */
    public static float[] faceDims(int faceShape) {
        switch (faceShape) {
            case 0: return new float[]{0.20f, 0.28f};   // 圆脸：宽而短
            case 2: return new float[]{0.16f, 0.30f};   // 尖脸：窄
            case 3: return new float[]{0.17f, 0.36f};   // 长脸：高
            default: return new float[]{0.18f, 0.30f};  // 方脸：标准
        }
    }

    /** 身高档 → 整体纵向缩放（脚底锚定，缩放不改脚底 y）。0 矮 0.92 / 1 标准 1.0 / 2 高 1.08。 */
    public static float heightScale(int height) {
        if (height <= 0) return 0.92f;
        if (height >= 2) return 1.08f;
        return 1.0f;
    }

    /** 年龄 → 肤色乘子（老年偏灰暗，模拟风霜/皱纹感）。返回 {mr, mg, mb}。 */
    public static float[] ageSkinTint(int age) {
        if (age <= 0) return new float[]{1.0f, 1.0f, 1.0f};        // 青年
        if (age == 1) return new float[]{0.95f, 0.92f, 0.90f};     // 中年
        return new float[]{0.86f, 0.83f, 0.82f};                   // 老年
    }

    /** 是否有胡须（>0）。 */
    public static boolean hasBeard(int beard) { return beard > 0; }

    /** 胡须类型：1 短髭 / 2 络腮 / 3 山羊胡。 */
    public static int beardType(int beard) { return beard < 0 ? 0 : (beard > 3 ? 3 : beard); }

    /** 是否有纹身 / 疤痕（>0）。 */
    public static boolean hasTattoo(int tattoo) { return tattoo > 0; }

    /** 纹身位置：1 左脸疤 / 2 右臂纹 / 3 额痕。 */
    public static int tattooType(int tattoo) { return tattoo < 0 ? 0 : (tattoo > 3 ? 3 : tattoo); }

    /** 发型类型：0 短 / 1 中 / 2 长 / 3 光 / 4 辫 / 5 丸子。 */
    public static int hairType(int hairStyle) { return hairStyle < 0 ? 0 : (hairStyle > 5 ? 5 : hairStyle); }

    /** 0xRRGGBB → 归一化 {r, g, b}（0..1），供渲染层消费。 */
    public static float[] rgb(int hex) {
        return new float[]{((hex >> 16) & 255) / 255.0f, ((hex >> 8) & 255) / 255.0f, (hex & 255) / 255.0f};
    }

    // =========================================================================
    // 阶段 B · 服装层渲染映射（纯函数，零 RNG，不进 hashState）
    // 与 gameplay player.armor（bodyColor/legColor，驱动躯干配色）正交：这里是
    // 捏脸可选的覆盖几何（胸甲 / 头盔 / 披风）之颜色，0=无覆盖。
    // =========================================================================

    /** 胸甲颜色（覆盖几何，非躯干底色）。0 无 / 1 皮 / 2 锁 / 3 板。null=不画。 */
    public static float[] chestplateColor(int t) {
        switch (t) {
            case 1: return new float[]{0.50f, 0.34f, 0.20f};
            case 2: return new float[]{0.68f, 0.70f, 0.74f};
            case 3: return new float[]{0.80f, 0.82f, 0.88f};
            default: return null;
        }
    }

    /** 头盔颜色。0 无 / 1 皮 / 2 铁 / 3 角。null=不画。 */
    public static float[] helmetColor(int t) {
        switch (t) {
            case 1: return new float[]{0.45f, 0.32f, 0.18f};
            case 2: return new float[]{0.66f, 0.68f, 0.72f};
            case 3: return new float[]{0.72f, 0.60f, 0.30f};
            default: return null;
        }
    }

    /** 披风颜色。0 无 / 1 短(红) / 2 长(蓝)。null=不画。 */
    public static float[] cloakColor(int t) {
        switch (t) {
            case 1: return new float[]{0.50f, 0.22f, 0.24f};
            case 2: return new float[]{0.28f, 0.30f, 0.56f};
            default: return null;
        }
    }
}
