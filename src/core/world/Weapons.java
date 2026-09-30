package core.world;

/**
 * 武器 / 战技原型表（C3 · 多武器 · 多战技）。
 *
 * <p>此前装备槽虽已落地，但战技只有一种「突刺」，5 把武器打起来手感完全相同。
 * 本类把「武器」与「战技形态」解耦成一张原型表：每把武器归属一个
 * {@link Art} 原型，决定它的战技是<b>近身突刺</b>、<b>原地回旋斩（AoE）</b>还是
 * <b>远距射击</b>、<b>蓄力重击</b>；范围 / 倍率 / 耗体 / 冷却随之差异化，形成可感知的 build 分歧。
 *
 * <p><b>零漂移纪律</b>：本类与 {@link Player} 的一切战技状态都属「玩家实体」层，
 * 不写 mat / mass / prosperity / skills / villageMemory，也不进
 * {@link World#hashState()} 确定性指纹 —— 因此对四道零漂移门禁零影响。
 * 战技只读 {@code world.beasts}（实体列表）并追加 {@code events}（不入指纹）。
 */
public final class Weapons {

    private Weapons() {}

    /** 战技原型：决定战技形态。 */
    public enum Art {
        /** 近身突刺：朝朝向猛冲一小段 + 重击最近目标（单体、有位移）。 */
        LUNGE,
        /** 回旋斩：原地上撩横扫，命中半径内<b>全部</b>目标并径向击退（AoE、无位移）。 */
        CLEAVE,
        /** 远距射击：对射程内最近目标造成一次远距离重击（单体、超长射程、无位移）。 */
        SHOT,
        /**
         * 蓄力重击（D 批）：原地蓄势横扫，范围与击退都强于回旋斩 —— 定位是「控制」而非「清场」。
         * 代价写进武器表：耗体次贵、冷却长。于是它和 CLEAVE 不是上下位关系：
         * CLEAVE 更便宜更快，CHARGE 更重更远。
         */
        CHARGE
    }

    /** 一把武器的定义（不可变）。 */
    public static final class Def {
        public final String name;    // 武器名（掉落/HUD）
        public final int atk;        // 攻击加成（并入 Player.atk）
        public final Art art;        // 该武器自带的战技原型
        public final int cost;       // 战技体力消耗
        public final float range;    // 战技命中半径（SHOT 即射程）
        public final float mult;     // 伤害倍率（× atk）
        public final float cd;       // 战技冷却（秒）

        Def(String name, int atk, Art art, int cost, float range, float mult, float cd) {
            this.name = name; this.atk = atk; this.art = art;
            this.cost = cost; this.range = range; this.mult = mult; this.cd = cd;
        }

        /** 该武器的一次战技总伤害（至少 1，避免倍率取整成 0）。 */
        public int artDamage(int atkTotal) { return Math.max(1, Math.round(atkTotal * mult)); }
    }

    /**
     * 武器表（索引 = {@link Player#weapon}）。
     *
     * <p><b>设计规则：互相不可支配（no strict dominance）</b>。EVAL-3 实测旧表是<b>纯线性上位替代</b>——
     * 符文刃在 atk / 倍率 / 冷却 / 射程上全面优于战斧，于是"要不要换武器"根本不是一个问题，
     * 掉落自动晋级即可，玩家零决策。现在每把武器都在至少一个维度上不可替代，代价与收益成对出现：
     *
     * <pre>
     *   Fist        最低耗体(14) + 最短冷却(0.55)  ← 连击流；代价：射程最短、伤害最低
     *   Iron Sword  均衡：射程/伤害/CD 都不垫底    ← 通用；代价：没有尖峰
     *   War Axe     高倍率(2.10) 群战              ← 代价：射程短(2.9)、CD 偏长
     *   Hunting Bow 最远射程(16) + 低耗体(20)      ← 拉距消耗；代价：倍率最低(1.35)
     *   Rune Edge   最高伤害(atk14/倍率2.70)       ← 代价：耗体最贵(46)、CD 最长(2.30)
     *   Great Maul  范围/击退控制(倍率2.40/范围3.2) ← 代价：耗体次贵(40)、CD 长(1.90)
     * </pre>
     *
     * <p>该性质由门禁 {@code WeaponArtTest} 的 {@code NO_DOMINANCE} 逐对断言（10 对全查），
     * 任何人把某把武器改成"全面更强"都会被门禁拦下。武器与战技原型正交：
     * {@link Def#art} 只是它自带的战技（拿到即解锁），实际使用哪个原型由 {@link Player#currentArt} 决定。
     */
    public static final Def[] LIST = {
            new Def("Fist",        0,  Art.LUNGE,  14,  2.0f, 1.10f, 0.55f),
            new Def("Iron Sword",  4,  Art.LUNGE,  22,  3.4f, 1.85f, 1.00f),
            new Def("War Axe",     8,  Art.CLEAVE, 30,  2.9f, 2.10f, 1.45f),
            new Def("Hunting Bow", 11, Art.SHOT,   20, 16.0f, 1.35f, 1.60f),
            new Def("Rune Edge",   14, Art.CLEAVE, 46,  3.8f, 2.70f, 2.30f),
            new Def("Great Maul",  12, Art.CHARGE, 40,  3.2f, 2.40f, 1.90f),
    };

    public static final int COUNT = LIST.length;

    /** 越界安全取值（战技/掉落共用）。 */
    public static Def def(int i) {
        return LIST[i < 0 ? 0 : (i >= COUNT ? COUNT - 1 : i)];
    }

    /** 战技原型名（HUD / 日志用 ASCII）。 */
    public static String artName(Art a) {
        switch (a) {
            case CLEAVE: return "CLEAVE";
            case SHOT:   return "SHOT";
            case CHARGE: return "CHARGE";
            default:     return "LUNGE";
        }
    }

    /** 战技原型中文名（CJK 横幅/回话用）。 */
    public static String artNameCn(Art a) {
        switch (a) {
            case CLEAVE: return "回旋斩";
            case SHOT:   return "远射";
            case CHARGE: return "蓄力重击";
            default:     return "突刺";
        }
    }

    /** 战技形态的一句话说明（CJK 提示用）。 */
    public static String artDescCn(Art a) {
        switch (a) {
            case CLEAVE: return "原地横扫，命中身边全体并击退";
            case SHOT:   return "超远射程单体重击，无需贴身";
            case CHARGE: return "蓄势横扫，范围与击退都更强（代价：耗体与冷却）";
            default:     return "朝面向突进并重击近身目标";
        }
    }
}
