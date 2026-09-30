package core.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import core.anim.AnimController;
import core.anim.Clip;

/**
 * 玩家（移植自 agents/player.py 的“玩家回环” + 击退意图触发世界回响）。
 *
 * 确定性：玩家 intents 由外部（脚本/输入）逐 tick 注入，World.tick 始终驱动 player.tick；
 * 同种子 + 同 intent 序列 → 同结果（DeterminismTest 验证）。
 *
 * P2-A 养成 build/配装 + P2-B 能力门控：
 *  - 属性 STR/VIT/END/DEX 派生 maxHp/atk/maxStamina/artCd；
 *  - 装备（武器/护甲/护符）提供额外加成，击杀确定性掉落并自动晋级更好的装备；
 *  - souls 既是 ER 风货币，也是“魂锻”属性消费出口（investSoul）；
 *  - abilities 集合承载塞尔达式能力（GLIDE 缓降 / DASH 长冲 / BOMB 炸开方块），由祭坛授予。
 *
 * C3 多武器 · 多战技（2026-09-10）：
 *  - 武器与战技形态解耦为 {@link Weapons} 原型表（突刺 LUNGE / 回旋斩 CLEAVE / 远射 SHOT），
 *    范围·倍率·耗体·冷却随原型差异化；
 *  - 拿到某武器即永久解锁其战技原型（arts），可用 {@link #cycleArt()}（G 键）在已解锁原型间切换；
 *  - 战技只读 {@code world.beasts} 并追加 {@code events}，不写网格/不碰主 rng。
 * 以上全部是“玩家实体”状态，不进 {@link World#hashState()} 确定性指纹，对四道零漂移门禁零影响。
 */
public final class Player {

    public float x, y, z;            // 体素坐标（y 向上，y 为脚底）
    /**
     * 正在乘坐的矿车在 {@code World.carts} 里的下标（{@code -1} = 没坐）。
     *
     * <p><b>第二十二批「真载具」</b>：这就是"载具"的全部实现 —— 坐下时玩家的位置由车决定
     * （见 {@code physicsStep} 的分支），输入不再转成**自身位移**，而是转成**车的推力**。
     *
     * <p>用**下标**而不是对象引用：反射式 {@code StateCodec} 遇到"对象引用"字段会<b>抛异常</b>
     * （本项目约定"不支持的字段类型响亮报错、绝不静默跳过"）⇒ 存 int 才是 codec 友好的。
     * 用之前必须校验下标仍在范围内（车被清理时自动下车）。
     * 实体层字段 ⇒ 不进 {@code hashState} ✓
     */
    public int ridingCartIdx = -1;
    /** 坐姿：脚底相对车底面的高度（视觉上"人坐在斗里"）。 */
    private static final float CART_RIDE_OFFSET = 0.45f;
    /** 坐在车上按前进键时，每个物理子步给车的推力（格/tick）。 */
    private static final float CART_RIDE_PUSH = 0.05f;
    public boolean onGround = false; // 物理状态（渲染/输入层维护，不进 sim 指纹）
    public float vy = 0f;             // 垂直速度
    public float fallPeak = 0f;       // 本次滞空的最高点（摔落伤害结算；玩家实体状态，不进 hashState）
    public boolean fellGlide = false; // 本次下落中是否滑翔过（滑翔落地免伤，塞尔达风）
    public int hp = 30, maxHp = 30;
    public int stamina = 100, maxStamina = 100;
    public int souls = 0;            // 魂（ER 风货币 + 魂锻消费出口）

    /** 饱食度上限 —— 全项目唯一来源（HungerSystem 与药瓶补给都取这里）。 */
    public static final float HUNGER_FULL = 100f;
    /**
     * 饱食度（0..{@link #HUNGER_FULL}）。由 {@code HungerSystem} 按预设参数 {@code hungerRate} 递减，
     * 归零后周期性扣血；药瓶补给回满。
     *
     * <p><b>零漂移</b>：属实体层 —— {@code hashState()} 只盖 mat/mass/prosperity/tick/rng/
     * skills/villageMemory/builtMass，<b>不含 Player 任何字段</b>。默认满值 → 饥饿系统首行即返回。
     */
    public float hunger = HUNGER_FULL;
    public boolean alive = true;

    // ---- ER/DaS 风「药瓶 + 复活点 + 血渍」三件套（全是玩家实体状态，不进 hashState）----
    /** 药瓶余量（在祭坛/重生时补满）。 */
    public int flask = FLASK_MAX;
    /** 药瓶容量。 */
    public static final int FLASK_MAX = 3;
    /** 每口回血量（≈ 半管）。 */
    public static final int FLASK_HEAL = 20;
    /** 复活点（默认出生点；触碰祭坛后更新为祭坛处）。 */
    public float cpX, cpY, cpZ;
    public boolean cpSet = false;
    /** 血渍：死亡时魂落在原地；走回去取回（取代旧的“直接减半”）。 */
    public int lostSouls = 0;
    public float lostX, lostY, lostZ;
    public boolean hasLost = false;

    // ---- 塞尔达式「以能力破印」：最近一次真正用到该能力的 sim tick ----
    /** 最近一次处于滑翔中的 tick（GLIDE）。 */
    public long lastGlideTick = -1000000L;
    /** 最近一次翻滚/冲刺的 tick（DASH）。 */
    public long lastDashTick = -1000000L;
    /** 最近一次炸开方块的 tick（BOMB）。 */
    public long lastBombTick = -1000000L;
    /** 最近一次引火的 tick（FLINT）。 */
    public long lastIgniteTick = -1000000L;
    /** 最近一次引水灌田的 tick（AQUA）。 */
    public long lastAquaTick = -1000000L;
    /** 最近一次引雷的 tick（THUNDER）。 */
    public long lastThunderTick = -1000000L;

    // ---- 角色养成 / 战技状态（渲染/输入层消费；不进 hashState，对四门禁零影响）----
    public int level = 1, xp = 0;    // 等级 / 经验（击杀敌兵累积）
    public int atk = 10;             // 攻击力（由属性+装备派生）
    public float artCd = 0f, rollCd = 0f, atkCd = 0f, invuln = 0f; // 各类冷却 / 无敌帧
    /**
     * 技能冷却剩余（秒）—— 与 {@link #artCd} 对称的「技能」通道。
     * {@link core.systems.ContentSystem#cast} 释放成功时置为该技能的 {@code cooldown}，
     * 每 tick 在此递减。实体层状态，不进 hashState。
     */
    public float skillCd = 0f;

    // ---- C 批（动作打磨）：攻击状态机（建在 AnimController 之上）----
    // 玩家实体状态，hashState() 不覆盖 → 零漂移；AnimController 零 RNG、纯函数。
    private AnimController atkAnim = null;
    private static final float ATK_LEN = 0.5f;       // 攻击片段时长（秒）
    private static final float ATK_STARTUP = 0.12f;  // 前摇结束 / 判定窗起点
    private static final float ATK_HIT = 0.16f;      // 判定帧（事件帧）位置 ∈ [STARTUP, CANCEL)
    private static final float ATK_CANCEL = 0.38f;   // 取消窗起点（后摇可取消段）
    private static final Clip[] ATTACK_COMBO = buildAttackCombo();

    private static Clip[] buildAttackCombo() {
        Clip a = new Clip("swing", ATK_LEN, false, new Clip.Track("arm", new Clip.Key[]{
                new Clip.Key(0f, 0f, 0f, 0f), new Clip.Key(ATK_LEN, 90f, 0f, 0f)}));
        a.events(new float[]{ATK_HIT}, new int[]{1});
        Clip b = new Clip("swing2", ATK_LEN, false, new Clip.Track("arm", new Clip.Key[]{
                new Clip.Key(0f, 90f, 0f, 0f), new Clip.Key(ATK_LEN, -90f, 0f, 0f)}));
        b.events(new float[]{ATK_HIT}, new int[]{1});
        return new Clip[]{a, b};
    }

    // ---- 完美闪避 / 反击（塞尔达式，2026-09-13）----
    // 这些全是【玩家实体状态】，hashState() 不覆盖 → 零漂移。
    /** 完美闪避成功次数（诊断 / UI）。 */
    public int perfectDodges = 0;
    /** 反击窗口剩余秒数：完美闪避后，下一次命中享受反击加成。 */
    public float riposteTimer = 0f;
    public float parryTimer = 0f, parryCd = 0f;
    public static final float PARRY_WINDOW = 0.22f, PARRY_COOLDOWN = 0.65f;
    public static final float EXECUTE_PCT = 0.25f, BOSS_EXECUTE_PCT = 0.10f;
    /** 反击窗口长度（秒）。 */
    public static final float RIPOSTE_WINDOW = 0.7f;
    /** 反击伤害倍率。 */
    public static final int RIPOSTE_MULT = 2;
    /** 破势伤害倍率（%）：打在敌人前摇/后摇上时的加成。 */
    public static final int STANCE_BREAK_PCT = 150;
    /** 破势造成的硬直（tick，约 0.8 s）。 */
    public static final int STANCE_BREAK_STAGGER = 16;
    public float artCdMax = 1.2f;    // 战技冷却基准（由 DEX 派生；实际 CD 见 artCdFor()）
    public final float ATK_RANGE = 2.6f;   // 轻攻击距离
    public static final int ROLL_COST = 20;          // 翻滚体力消耗（战技消耗改为按武器，见 Weapons）
    private static final float STAM_REGEN = 22f;     // 每秒体力回复

    // ---- P2-A 属性 / 装备 / 养成（build 深度；souls 消费出口）----
    public int str = 5, dex = 5, vit = 5, end = 5;  // 属性点
    public int weapon = 0, armor = 0, charm = 0;     // 装备槽（索引见下方目录）

    /**
     * 已获得的武器（位掩码：bit i = {@code Weapons.LIST[i]}）。bit0（拳）永久持有。
     *
     * <p>P0-3：掉落**只把武器放进背包**，不再自动换装 —— 「要哪把」成为玩家的决策
     * （{@link #cycleWeapon()}，M 键）。护甲/饰品仍是自动晋级：它们只改纯数值，
     * 没有打法差异，手动换装不产生决策价值（要给它们也做出取舍，属于下一批）。
     */
    public int weaponOwned = 1;
    public int killCount = 0;                         // 用于确定性掉落
    public int pointsSpent = 0;                       // 魂锻累计消费（成本递增）
    public final List<String> lootLog = new ArrayList<>(); // 最近掉落（HUD 展示）

    // 装备目录（索引 = 槽位值）。加成在 recompute() 中合成到派生属性。
    // 武器表已抽到 {@link Weapons}（C3：武器 ↔ 战技原型解耦），此处仅保留护甲/护符。
    public static final String[] ARMORS = {"Rags", "Leather", "Plate", "Ward Cloak"};
    public static final int[]   ARMOR_HP = {0, 12, 26, 42};
    public static final String[] CHARMS = {"None", "Stamina Charm", "Power Charm"};
    public static final int[]   CHARM_STAM = {0, 25, 0};
    public static final int[]   CHARM_ATK = {0, 0, 4};

    // ---- P2-B 能力（塞尔达式：能力→探索→回报；由祭坛授予，不进 hashState）----
    public final Set<String> abilities = new LinkedHashSet<>();
    public boolean godMode = false;            // 调试沙盒：无敌（实体层，不进 hashState；默认关 → 零漂移）

    // ---- C3 多武器 · 多战技：战技原型的“已解锁集合” + 当前选定 ----
    // 拿到某武器的同时永久解锁其战技原型（不因后续换装而丢失），可用 cycleArt()（G 键）自由切换；
    // 全部是玩家实体层状态，不进 hashState，对四道零漂移门禁零影响。
    public final Set<Weapons.Art> arts = new LinkedHashSet<>();
    public Weapons.Art currentArt = Weapons.Art.LUNGE;

    // ---- F 批（捏脸）：玩家外观（身份数据；不进 hashState()，对四道零漂移门禁零影响）----
    public final Appearance appearance = new Appearance();

    private final Deque<Intent> intents = new ArrayDeque<>();

    public Player() { arts.add(Weapons.Art.LUNGE); recompute(); hp = maxHp; }   // 开局满血（原先开局只有 20/45）

    /** 注入一个意图（CLI/输入/测试脚本复用同一接口）。 */
    public void setIntent(Intent it) { intents.addLast(it); }

    public Intent pollIntent() {
        return intents.isEmpty() ? Intent.idle() : intents.pollFirst();
    }

    /** 玩家回环：每 tick 由 World.tick 驱动（修复旧版死亡玩家被跳过永久卡死）。 */
    public void tick(World w) {
        if (!alive) { respawn(w); return; }
        // 冷却递减 + 体力回复（不进指纹，纯战斗手感状态）
        float dt = 1f / 20f;
        if (artCd > 0) artCd = Math.max(0, artCd - dt);
        if (rollCd > 0) rollCd = Math.max(0, rollCd - dt);
        if (atkCd > 0) atkCd = Math.max(0, atkCd - dt);
        if (skillCd > 0) skillCd = Math.max(0, skillCd - dt);
        if (invuln > 0) invuln = Math.max(0, invuln - dt);
        if (riposteTimer > 0f) riposteTimer = Math.max(0f, riposteTimer - dt);   // 反击窗口衰减
        if (parryTimer > 0f) parryTimer = Math.max(0f, parryTimer - dt);
        if (parryCd > 0f) parryCd = Math.max(0f, parryCd - dt);
        stamina = Math.min(maxStamina, stamina + (int) (STAM_REGEN * dt));
        Intent it = pollIntent();
        switch (it.type) {
            case MOVE:   tryMove(w, it.dx, it.dz); break;
            case REPEL:  // 玩家击退来犯之敌 → 触发世界回响
                w.log("combat", "repel", String.format("%.0f,%.0f,%.0f", x, y, z), "ok");
                break;
            case ATTACK: tryAttack(w); break;   // IMPL-COMBAT：攻击最近敌兵（REPEL 分支原样保留）
            case PARRY: tryParry(w); break;
            case EXECUTE: executeNearest(w); break;
            case BUILD:  tryBuild(w, it.blockIdx); break;
            case IDLE:   break;
        }
        // 血渍回收（DaS 尸体跑）：走回死亡点即取回掉落的魂
        if (hasLost) {
            float dx = x - lostX, dy = y - lostY, dz = z - lostZ;
            if (dx * dx + dy * dy + dz * dz <= 2.25f) {
                souls += lostSouls;
                w.log("player", "souls_recover", String.valueOf(lostSouls), "ok");
                lostSouls = 0;
                hasLost = false;
            }
        }
        // C：敌兵魂滴回收（P1-2）：走回尸体即取回魂（同血渍半径 1.5 格）
        for (int ci = w.corpses.size() - 1; ci >= 0; ci--) {
            Corpse c = w.corpses.get(ci);
            float dx = x - c.x, dy = y - c.y, dz = z - c.z;
            if (dx * dx + dy * dy + dz * dz <= 2.25f) {
                souls += c.souls;
                w.log("player", "corpse_recover", String.valueOf(c.souls), c.id);
                w.corpses.remove(ci);
            }
        }
        // C 批：推进攻击状态机（判定帧发伤害）；atkAnim 是玩家实体状态，不进 hashState，对指纹零影响
        tickAttack(w, dt);
    }

    /** 由属性+装备合成派生属性（maxHp/atk/maxStamina/artCd）。纯函数，不触网格/rng。 */
    public void recompute() {
        int hpBonus = ARMOR_HP[armor] + 0;
        int atkBonus = Weapons.def(weapon).atk + CHARM_ATK[charm];
        int stamBonus = CHARM_STAM[charm];
        maxHp = 30 + vit * 5 + (level - 1) * 5 + hpBonus;
        atk = 8 + str * 2 + (level - 1) * 1 + atkBonus;
        maxStamina = 80 + end * 10 + stamBonus;
        artCdMax = Math.max(0.5f, 1.2f - dex * 0.03f);
        if (hp > maxHp) hp = maxHp;
        if (stamina > maxStamina) stamina = maxStamina;
    }

    /** 当前武器名（HUD 用）。 */
    public String weaponName() { return Weapons.def(weapon).name; }

    /** 当前战技原型的实际冷却：DEX 派生的 artCdMax 与武器自带 CD 取较大者（重武器更慢）。 */
    public float artCdFor(Weapons.Art a) { return Math.max(artCdMax, Weapons.def(weapon).cd); }

    /** C3：按到达顺序轮换已解锁战技（G 键）。返回切换后的原型（无其他解锁则原地不动）。 */
    public Weapons.Art cycleArt() {
        if (arts.size() <= 1) return currentArt;
        List<Weapons.Art> l = new ArrayList<>(arts);
        int i = l.indexOf(currentArt);
        currentArt = l.get((i + 1) % l.size());
        return currentArt;
    }

    /** 是否已获得第 i 把武器（越界安全）。 */
    public boolean ownsWeapon(int i) {
        return i >= 0 && i < Weapons.COUNT && (weaponOwned & (1 << i)) != 0;
    }

    /**
     * 在**已获得**的武器间循环（M 键）。返回是否真的换了。
     * 只有一把时原地不动 —— 门禁断言它永远不会切到"没拿到"的武器上。
     */
    public boolean cycleWeapon() {
        for (int k = 1; k <= Weapons.COUNT; k++) {
            int idx = (weapon + k) % Weapons.COUNT;
            if (ownsWeapon(idx)) { weapon = idx; recompute(); return true; }
        }
        return false;
    }

    /** C3：永久解锁一个战技原型并立即切换（拿到新武器时调用）。返回是否为“首次解锁”。 */
    public boolean unlockArt(Weapons.Art a) {
        if (!arts.add(a)) return false;
        currentArt = a;                 // 新战技入手即切到它，玩家立刻感到“打法变了”
        return true;
    }

    /** 被敌兵命中（BeastSystem 调用）。尊重无敌帧；归零则死亡并掉落一半魂。 */
    public void hitByBeast(int dmg, World w) {
        if (godMode) return;                    // 调试沙盒：无敌
        if (parryTimer > 0f && w != null) {
            parryTimer = 0f; parryCd = PARRY_COOLDOWN;
            for (Beast b : w.beasts) {
                if (b.atkPhase == Beast.PHASE_STRIKE || b.atkPhase == Beast.PHASE_WINDUP) {
                    b.atkPhase = Beast.PHASE_IDLE; b.atkTimer = 0;
                    b.stagger = b.isBoss() ? STANCE_BREAK_STAGGER / 2 : STANCE_BREAK_STAGGER;
                }
            }
            riposteTimer = RIPOSTE_WINDOW;
            w.log("combat", "parry", "perfect", "riposte");
            return;
        }
        if (invuln > 0) {
            // 完美闪避（塞尔达式）：无敌帧内被攻击 = 玩家抓准了时机 → 记成功 + 开反击窗口
            perfectDodges++;
            riposteTimer = RIPOSTE_WINDOW;
            if (w != null) w.log("player", "dodge", "perfect", "riposte");
            return;
        }
        hp -= dmg;
        if (hp <= 0) die(w);
    }

    /**
     * 环境伤害（hazard）路径：火 / 水 / 雷等**非敌人攻击**的伤害源走这里（P2-2 补完）。
     *
     * <p>与 {@link #hitByBeast} 的唯一区别：**不发放"完美闪避"奖励**。无敌帧在两种情况下含义不同——
     * 面对敌人出招，在 i 帧内挨打是"读招成功"（该给反击窗口）；而火是每 tick 都在判定的持续伤害，
     * 若复用 beast 路径，站在火里的几帧会把 {@code perfectDodges} 刷爆并白送反击窗口。
     * 拆成独立入口，才能让「唯一一处伤害规则」按**伤害来源**各自成立，而不是两个语义硬挤一条路。
     *
     * <p>零漂移：纯实体层（hp / invuln），不消耗任何 rng。
     */
    public void hurtByHazard(int dmg, World w) {
        if (godMode) return;                    // 调试沙盒：无敌
        if (invuln > 0) return;      // i 帧免伤，但不给"完美闪避"奖励
        hp -= dmg;
        if (hp <= 0) die(w);
    }

    /**
     * 死亡：血渍落地（魂不清零，改为可回收——旧的 {@code souls /= 2} 是纯粹的无聊惩罚），
     * 下 tick 由 {@link #tick(World)} 调 {@link #respawn(World)} 在复活点重生。
     */
    private void die(World w) {
        hp = 0;
        alive = false;
        if (souls > 0) {
            lostSouls = souls;
            lostX = x; lostY = y; lostZ = z;
            hasLost = true;                       // 走回血渍即取回（DaS 尸体回收）
            if (w != null) w.log("player", "death_drop", String.valueOf(lostSouls), "ok");
        }
        souls = 0;
        if (w != null) w.log("player", "death", "", "ok");
    }

    /** 喝一口药瓶（回血）。返回是否真的喝到（满血/没药时返回 false，避免浪费）。 */
    public boolean useFlask(World w) {
        if (flask <= 0) return false;
        // 饥饿子系统启用时（hunger 被消耗到低于满值），补给也值得 —— 否则"满血但饿死"无解。
        // 默认 hunger == HUNGER_FULL → 该条件恒真 → 与旧行为逐字节一致（旧的 `hp >= maxHp` 短路）。
        if (hp >= maxHp && hunger >= HUNGER_FULL) return false;
        flask--;
        hp = Math.min(maxHp, hp + FLASK_HEAL);
        hunger = HUNGER_FULL;
        if (w != null) w.log("player", "flask", String.valueOf(flask), "ok");
        return true;
    }

    /** 补满药瓶（祭坛 / 重生）。 */
    public void refillFlask() { flask = FLASK_MAX; }

    /** 记录复活点（祭坛）= 同时补满药瓶。 */
    public void setCheckpoint(float x, float y, float z) {
        cpX = x; cpY = y; cpZ = z; cpSet = true;
        refillFlask();
    }

    /** 击杀结算：魂 + 经验 → 升级（满血+属性派生刷新）；确定性掉落自动晋级装备。 */
    public void onKill(World w) {
        souls += 8;
        xp += 12;
        killCount++;
        while (xp >= level * 24) {
            xp -= level * 24;
            level++;
            recompute();
            hp = maxHp;                    // 升级满血
            if (level == 2) w.log("player", "unlock", "weapon_art", "lunge");
            if (level == 3) w.log("player", "unlock", "dodge_roll", "iframes");
        }
        // 确定性掉落（节奏不变）：等级/击杀数越高，越可能掉更高阶装备。
        // P0-3：武器**不再自动换装**，只放进背包（位掩码）→ 「要哪把」由玩家用 M 键决定。
        int wT = Math.min(Weapons.COUNT - 1, 1 + level / 3 + (killCount % 3 == 0 ? 1 : 0));
        int aT = Math.min(ARMORS.length - 1, 1 + level / 3 + (killCount % 5 == 0 ? 1 : 0));
        int cT = (killCount % 7 == 0) ? Math.min(CHARMS.length - 1, 1 + level / 5) : 0;
        if (!ownsWeapon(wT)) {
            weaponOwned |= (1 << wT);
            Weapons.Def wd = Weapons.def(wT);
            lootLog.add(0, wd.name);
            w.log("loot", "weapon", wd.name, "obtained");     // 旧档日志写 up（自动换装）；现语义是“进背包”
            // C3：拿到新武器即永久解锁其战技原型（突刺→回旋斩→远射），并记一条入档事件供 HUD/横幅消费
            if (unlockArt(wd.art)) w.log("player", "unlock_art", Weapons.artName(wd.art), wd.name);
        }
        if (aT > armor)  { armor = aT;  lootLog.add(0, ARMORS[armor]);   w.log("loot", "armor", ARMORS[armor], "up"); }
        if (cT > charm)  { charm = cT;  lootLog.add(0, CHARMS[charm]);   w.log("loot", "charm", CHARMS[charm], "up"); }
        recompute();
    }

    /** 魂锻：消费 souls 提升一项属性（成本随累计消费递增，给 souls 真实出口）。返回是否成功。 */
    public boolean investSoul(String attr) {
        int cost = 30 + pointsSpent * 10;
        if (souls < cost) return false;
        souls -= cost; pointsSpent++;
        if (attr.equals("STR")) str++;
        else if (attr.equals("VIT")) vit++;
        else if (attr.equals("END")) end++;
        else if (attr.equals("DEX")) dex++;
        else return false;
        recompute();
        return true;
    }

    /** 授予能力（祭坛调用）。 */
    public void grantAbility(String id) { abilities.add(id); }

    /** 在 range 内找最近的敌兵（攻击/战技共用）。 */
    public Beast nearestBeast(World w, float range) {
        Beast best = null; float bd = range;
        for (Beast b : w.beasts) {
            float dx = x - b.x, dy = y - b.y, dz = z - b.z;
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    /**
     * 战技（C3 多武器·多战技）：按<b>当前选定原型</b>分派，参数取自当前武器。
     * 返回是否真正施放（供渲染层决定要不要放特效/给失败提示）。
     *
     * <p>三形态差异：
     * <ul>
     *   <li>LUNGE　突刺：朝朝向冲 2.4 格 + 单体重击（近战位移）</li>
     *   <li>CLEAVE 回旋斩：原地横扫半径内<b>全体</b>并径向击退（AoE 清场）</li>
     *   <li>SHOT　 远射：射程内最近目标单体重击，射程 16 格（可越沟跨崖）</li>
     *   <li>CHARGE 蓄力：原地蓄势横扫，范围内全体并<b>更强击退</b>（控制型）</li>
     * </ul>
     * 归一化：无目标时不施放、不耗体力、不进 CD（远程不该空放）。
     */
    public boolean weaponArt(World w, float dx, float dz) {
        if (level < 2 || artCd > 0) return false;
        Weapons.Art art = currentArt;            // 先冻结：命中可能触发掉落 → unlockArt 会改写 currentArt
        Weapons.Def d = Weapons.def(weapon);
        if (stamina < d.cost) return false;
        float len = (float) StrictMath.hypot(dx, dz);
        if (len > 1e-4f) { dx /= len; dz /= len; } else { dx = 0; dz = 1; }

        switch (art) {
            case CLEAVE: {
                List<Beast> hit = beastsWithin(w, d.range);
                if (hit.isEmpty()) return false;
                artCd = artCdFor(art); stamina -= d.cost;
                for (Beast b : hit) {
                    // 径向击退（纯实体位移，不碰网格；被墙挡住也不会穿墙，因 beast 不参与方块碰撞）
                    float ux = b.x - x, uz = b.z - z;
                    float l = (float) StrictMath.hypot(ux, uz);
                    if (l > 1e-3f) { ux /= l; uz /= l; } else { ux = dx; uz = dz; }
                    b.x += ux * 1.2f; b.z += uz * 1.2f;
                    hitBeast(w, b, d.artDamage(atk), "art");
                }
                return true;
            }
            case SHOT: {
                Beast t = nearestBeast(w, d.range);
                if (t == null) return false;
                artCd = artCdFor(art); stamina -= d.cost;
                hitBeast(w, t, d.artDamage(atk), "art");
                return true;
            }
            case CHARGE: {
                // 蓄力重击（D 批）：同 CLEAVE 的「原地 AoE」骨架，但击退更远（2.0 vs 1.2 格）。
                // 差异化的代价在武器表（耗体 40 / CD 1.90）—— 所以两者不是上下位，而是'控制'与'清场'。
                List<Beast> hit = beastsWithin(w, d.range);
                if (hit.isEmpty()) return false;
                artCd = artCdFor(art); stamina -= d.cost;
                for (Beast b : hit) {
                    float ux = b.x - x, uz = b.z - z;
                    float l = (float) StrictMath.hypot(ux, uz);
                    if (l > 1e-3f) { ux /= l; uz /= l; } else { ux = dx; uz = dz; }
                    b.x += ux * 2.0f; b.z += uz * 2.0f;
                    hitBeast(w, b, d.artDamage(atk), "art");
                }
                return true;
            }
            default: {
                sweepStep(w, dx * 2.4f, 0, dz * 2.4f);
                artCd = artCdFor(art); stamina -= d.cost;
                hitBeast(w, nearestBeast(w, d.range), d.artDamage(atk), "art");
                return true;
            }
        }
    }

    /** 半径内（水平距离 ≤ r 且高度相近）的全部敌兵快照（先收集再结算，避免边遍历边移除）。 */
    private List<Beast> beastsWithin(World w, float r) {
        List<Beast> out = new ArrayList<>();
        for (Beast b : w.beasts) {
            float ex = b.x - x, ez = b.z - z, ey = b.y - y;
            if (ex * ex + ez * ez > r * r) continue;
            if (Math.abs(ey) > 2f) continue;
            out.add(b);
        }
        return out;
    }

    /**
     * 统一命中结算：受击闪白 → 扣血 → 归零则移除 + 记 combat.repel（繁荣链）+ 击杀结算。
     *
     * <p><b>public 供内容层调用</b>（平台化 2026-09-13）：{@code EffectSink.simulate("DAMAGE", …)}
     * 需要走<b>同一套</b>结算逻辑，否则内容驱动的伤害会与武器伤害产生语义漂移。
     * 把它开成 public 而不是在渲染层重写一遍，就是为了让"唯一一处伤害规则"保持成立。
     */
    public void hitBeast(World w, Beast t, int dmg, String result) {
        if (t == null || !w.beasts.contains(t)) return;
        t.hitFlash = 0.15f;
        if (riposteTimer > 0f) {                 // 完美闪避后的反击：伤害翻倍并消耗窗口
            dmg *= RIPOSTE_MULT;
            riposteTimer = 0f;
            result = "riposte";
        } else if (t.punishable()) {             // DS/ER 式「破势」：打在敌人前摇/后摇上
            dmg = dmg * STANCE_BREAK_PCT / 100;  // → 加成 + 打断出招 + 长硬直（这是“读招”的回报）
            t.atkPhase = Beast.PHASE_IDLE;
            t.atkTimer = 0;
            // P0-4：守卫的破势僵直减半 —— 否则"每一击都打在前摇上"可以把它永久锁住，
            // 守卫就不成其为守卫了。普通敌兵不受影响（门禁 PUNISH 仍断言完整僵直）。
            t.stagger = t.isBoss() ? STANCE_BREAK_STAGGER / 2 : STANCE_BREAK_STAGGER;
            result = "break";
        } else {
            t.stagger = Math.max(t.stagger, 6);  // 普通命中也让敌人缩一下（poise）
        }
        // ---- P0②：假击退（纯渲染层，绝不进 BeastSystem.move）----
        // 方向 = 玩家→敌（退开玩家），长度 = 体重分档。仅写 kbProg/kbDir/kbDist 三个渲染字段，
        // 由 Game.drawEntities 读并偏移绘制位置、每帧衰减。t.x/y/z 本体不被触碰 → 零漂移。
        float kdx = t.x - x, kdz = t.z - z;
        float kl = (float) StrictMath.hypot(kdx, kdz);
        if (kl < 1e-4f) { kdx = 0f; kdz = 1f; } else { kdx /= kl; kdz /= kl; }
        t.kbDirX = kdx; t.kbDirZ = kdz;
        t.kbDist = t.isBoss() ? 0.30f : 1.20f;
        t.kbProg = 1f;
        t.hp -= dmg;
        if (t.hp <= 0) {
            w.beasts.remove(t);
            // P1-2：敌兵死亡在原地留魂滴（走回拾取；不进 hashState，联机快照自动编）
            w.corpses.add(new Corpse(t.x, t.y, t.z, 6, (t.defId == null ? Beast.typeName(t.type) : t.defId)));
            // 这条 log 正是 ProsperitySystem 扫描的繁荣链输入（与 REPEL 同源）
            w.log("combat", "repel", String.format("%.0f,%.0f,%.0f", t.x, t.y, t.z), result);
            // P1：另发一条 kill 事件，params 带**兽的身份**（内容兽 id，纯原型退回原型名）。
            // 为什么不复用 repel 的 params：ProsperitySystem 正在解析那一条（繁荣链），
            // 往里塞字段等于让两个消费者的格式耦合在一起。
            w.log("combat", "kill", (t.defId == null ? Beast.typeName(t.type) : t.defId), result);
            onKill(w);
        }
    }

    /** 主动招架：短窗口内敌兵出招被打断，并打开一次反击窗口。 */
    public boolean tryParry(World w) {
        if (parryCd > 0f || parryTimer > 0f || stamina < 8) return false;
        stamina -= 8; parryTimer = PARRY_WINDOW;
        if (w != null) w.log("combat", "parry_start", "window", "ok");
        return true;
    }
    /** 处决最近的硬直低血敌兵；返回是否真的结算。 */
    public boolean executeNearest(World w) {
        Beast t = nearestBeast(w, 2.8f);
        if (t == null || t.stagger <= 0) return false;
        float pct = t.isBoss() ? BOSS_EXECUTE_PCT : EXECUTE_PCT;
        if ((float) t.hp / Math.max(1f, t.maxHp) > pct) return false;
        t.hp = 0; w.beasts.remove(t);
        // 死因标签与击杀路径（hitBeast）保持一致：有内容 id 用 id，否则退回原型名。
        String drop = (t.defId == null ? Beast.typeName(t.type) : t.defId);
        w.corpses.add(new Corpse(t.x, t.y, t.z, t.isBoss() ? 20 : 6, drop));
        w.log("combat", "execute", drop, "ok"); onKill(w); return true;
    }

    /** 翻滚闪避（Zelda/Souls 风）：朝移动方向猛冲并获得无敌帧。若拥有 DASH 能力则更长更短 CD。渲染/输入层触发。 */
    public void dodgeRoll(World w, float dx, float dz) {
        if (level < 3 || rollCd > 0 || stamina < ROLL_COST) return;
        float len = (float) StrictMath.hypot(dx, dz);
        if (len > 1e-4f) { dx /= len; dz /= len; } else { dx = 0; dz = 0; }
        boolean dash = abilities.contains("DASH");
        float dist = dash ? 4.6f : 3.0f;
        sweepStep(w, dx * dist, 0, dz * dist);
        rollCd = dash ? 0.55f : 0.7f;
        invuln = dash ? 0.55f : 0.45f;
        stamina -= ROLL_COST;
        lastDashTick = w.tick;         // 记“刚用过 DASH”（试炼封印判定用）
    }

    /** BOMB 能力：炸开正前方最近的实心方块（含 1 格半径坑），打开被封死的路径。渲染/输入层触发。 */
    public void bomb(World w, float fx, float fz) {
        if (!abilities.contains("BOMB")) return;
        float dx = fx, dz = fz; float len = (float) StrictMath.hypot(dx, dz);
        if (len > 1e-4f) { dx /= len; dz /= len; } else { dx = 0; dz = 1; }
        for (int s = 1; s <= 3; s++) {
            int bx = (int) Math.floor(x + dx * s), by = (int) Math.floor(y + 0.6f), bz = (int) Math.floor(z + dz * s);
            int b = w.getBlock(bx, by, bz);
            if (b != Blocks.AIR.index && b != Blocks.BEDROCK.index) {
                for (int ox = -1; ox <= 1; ox++)
                    for (int oy = -1; oy <= 1; oy++)
                        for (int oz = -1; oz <= 1; oz++) {
                            int cx = bx + ox, cy = by + oy, cz = bz + oz;
                            int cb = w.getBlock(cx, cy, cz);
                            if (cb != Blocks.BEDROCK.index && cb != Blocks.WATER.index)
                                w.editBlock(cx, cy, cz, Blocks.AIR.index);
                        }
                lastBombTick = w.tick;     // 记“刚炸过”（试炼封印判定用）
                w.log("player", "bomb", "crater", "ok");
                return;
            }
        }
    }

    /**
     * FLINT 能力（P2-2 涌现杠杆·火种）：在正前方 1~3 格内放第一簇 FIRE，
     * 之后由已注册的 FireSpreadSystem / WildfireSystem 沿确定性子流（simStream）自主蔓延
     * —— 玩家把“火”从被动灾害变成可主动驱动的涌现杠杆。
     * <p>零漂移：与 bomb 同走 World.setBlock 写路径（实体层输入驱动，不推进主 rng）；
     * 写 mat 会移 DET/ZD 等基线，属有意演进（已重锁）。
     */
    public void ignite(World w, float fx, float fz) {
        if (!abilities.contains("FLINT")) return;
        float dx = fx, dz = fz; float len = (float) StrictMath.hypot(dx, dz);
        if (len > 1e-4f) { dx /= len; dz /= len; } else { dx = 0; dz = 1; }
        for (int s = 1; s <= 3; s++) {
            int bx = (int) Math.floor(x + dx * s), by = (int) Math.floor(y + 0.6f), bz = (int) Math.floor(z + dz * s);
            int b = w.getBlock(bx, by, bz);
            // 不点基岩/水；空地或可燃方块都可在其位置点燃（火自己会找蔓延目标）
            if (b != Blocks.BEDROCK.index && b != Blocks.WATER.index) {
                w.editBlock(bx, by, bz, Blocks.FIRE.index);
                lastIgniteTick = w.tick;   // 记“刚引过火”（未来试炼封印可用）
                w.log("player", "ignite", "spark", "ok");
                return;
            }
        }
    }

    // ---- P2-2 元素杠杆参数（水 / 雷）----
    private static final float THUNDER_R = 3.5f;         // 引雷电击半径（格）
    private static final int   THUNDER_DMG = 22;         // 单次电击伤害（≈ 2/3 只 GRUNT）
    private static final long  THUNDER_COOLDOWN = 40;    // 引雷冷却（tick，≈2s）
    private static final int   CONDUCT_MAX = 64;         // 雷电沿水域传导的最大格数（有界 BFS）
    private static final int[] DX6 = {1, -1, 0, 0, 0, 0};
    private static final int[] DY6 = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ6 = {0, 0, 0, 0, 1, -1};

    /**
     * AQUA 能力（P2-2 涌现杠杆·水，引水灌田）：正前方放一格 WATER，随后**确定性**灌溉相邻 DIRT ——
     * 邻水 DIRT 若上方是空地，立即在其上长出 LEAF 作物（判据与 {@code FarmSystem} 完全一致）。
     *
     * <p>为什么不"只放水、等 FarmSystem 自己发现"：Irrigation/Farm 每 tick 只**随机采样 24 列**
     * （世界有数千列），玩家刚浇的地要等很久才被抽中 → 反馈太弱、不成杠杆。这里把同一判据在
     * **玩家动作**上立即跑一遍；那两个系统仍按自身节奏在别处涌现 → 即时反馈 + 世界慢涌现并存。
     * 零漂移：与 ignite 同走写路径（输入驱动、不推进主 rng）。
     */
    public void divert(World w, float fx, float fz) {
        if (!abilities.contains("AQUA")) return;
        float dx = fx, dz = fz; float len = (float) StrictMath.hypot(dx, dz);
        if (len > 1e-4f) { dx /= len; dz /= len; } else { dx = 0; dz = 1; }
        for (int s = 1; s <= 3; s++) {
            int bx = (int) Math.floor(x + dx * s), by = (int) Math.floor(y + 0.6f), bz = (int) Math.floor(z + dz * s);
            if (w.getBlock(bx, by, bz) == Blocks.BEDROCK.index) continue;   // 不灌进基岩
            w.editBlock(bx, by, bz, Blocks.WATER.index);                    // 引水：放下水源
            irrigateAround(w, bx, by, bz);                                  // 立即灌溉相邻田（确定性）
            lastAquaTick = w.tick;
            w.log("player", "divert", "water", "ok");
            return;
        }
    }

    /** 确定性灌溉：水源 3x3x3 邻域内「邻 WATER 且上方 AIR」的 DIRT → 长出 LEAF 作物。 */
    private static void irrigateAround(World w, int wx, int wy, int wz) {
        for (int ox = -1; ox <= 1; ox++)
            for (int oy = -1; oy <= 1; oy++)
                for (int oz = -1; oz <= 1; oz++) {
                    int cx = wx + ox, cy = wy + oy, cz = wz + oz;
                    if (!w.inBounds(cx, cy + 1, cz)) continue;
                    if (w.mat[cx][cy][cz] != Blocks.DIRT.index) continue;        // 田 = DIRT
                    if (w.mat[cx][cy + 1][cz] != Blocks.AIR.index) continue;     // 上方要空地
                    if (!waterAdjacent(w, cx, cy, cz)) continue;                 // 必须邻水
                    w.setBlock(cx, cy + 1, cz, Blocks.LEAF.index);               // 长出作物
                    w.log("farm", "harvest", "x=" + cx + ",z=" + cz, "crop");
                }
    }

    /** 同层四邻或下方一格是否为 WATER（与 FarmSystem 同判据）。 */
    private static boolean waterAdjacent(World w, int x, int y, int z) {
        return w.getBlock(x - 1, y, z) == Blocks.WATER.index
            || w.getBlock(x + 1, y, z) == Blocks.WATER.index
            || w.getBlock(x, y, z - 1) == Blocks.WATER.index
            || w.getBlock(x, y, z + 1) == Blocks.WATER.index
            || w.getBlock(x, y - 1, z) == Blocks.WATER.index;
    }

    /**
     * THUNDER 能力（P2-2 涌现杠杆·雷，雷击导电）：正前方落一道雷。命中可燃方块即点燃
     * （与 {@code LightningSystem} 同判据），并电击落点半径内敌兵；**若落点邻接 WATER，
     * 电流沿连通水域传导**（有界 BFS ≤ {@link #CONDUCT_MAX} 格），把电击覆盖铺到整片水面
     * —— 把敌人引到水边/水里再引雷，一击放倒一片，这就是"导电"的玩法含义。
     *
     * <p>本能力是"玩家主动引雷"，与 {@code LightningSystem} 的自然落雷**并存、互不干扰**。
     * 零漂移：纯 setBlock/editBlock + hitBeast，不消耗 rng；带 tick 冷却防连点。
     */
    public void thunder(World w, float fx, float fz) {
        if (!abilities.contains("THUNDER")) return;
        if (w.tick - lastThunderTick < THUNDER_COOLDOWN) return;   // 冷却
        float dx = fx, dz = fz; float len = (float) StrictMath.hypot(dx, dz);
        if (len > 1e-4f) { dx /= len; dz /= len; } else { dx = 0; dz = 1; }

        int ty = (int) Math.floor(y + 0.6f);
        int tx = (int) Math.floor(x + dx), tz = (int) Math.floor(z + dz);
        for (int s = 1; s <= 4; s++) {                              // 命中正前方第一个非空气格
            int cx = (int) Math.floor(x + dx * s), cz = (int) Math.floor(z + dz * s);
            if (!w.inBounds(cx, ty, cz)) break;
            tx = cx; tz = cz;
            if (w.getBlock(cx, ty, cz) != Blocks.AIR.index) break;
        }

        int b0 = w.getBlock(tx, ty, tz);                            // 点燃可燃（同 LightningSystem 判据）
        if (b0 == Blocks.WOOD.index || b0 == Blocks.LEAF.index) w.setBlock(tx, ty, tz, Blocks.FIRE.index);

        // 电击目标格集合：落点 +（邻水时）沿连通水域传导到的整片水面
        List<int[]> cells = new ArrayList<int[]>();
        cells.add(new int[]{tx, ty, tz});
        if (w.getBlock(tx, ty, tz) == Blocks.WATER.index || waterAdjacent(w, tx, ty, tz)) {
            conductWater(w, tx, ty, tz, cells);
        }
        LinkedHashSet<Beast> hit = new LinkedHashSet<Beast>();      // 去重（有序）→ 每只敌兵只结算一次
        for (int[] c : cells) {
            for (Beast b : w.beasts) {
                float ddx = b.x - (c[0] + 0.5f), ddz = b.z - (c[2] + 0.5f);
                if (ddx * ddx + ddz * ddz <= THUNDER_R * THUNDER_R && Math.abs(b.y - c[1]) <= 3f) hit.add(b);
            }
        }
        for (Beast b : hit) hitBeast(w, b, THUNDER_DMG, "thunder");

        lastThunderTick = w.tick;
        w.log("player", "thunder", "strike", "ok");
    }

    /** 从落点沿连通 WATER 做有界 BFS；把途经水格追加进 cells（供电击铺开）。 */
    private static void conductWater(World w, int sx, int sy, int sz, List<int[]> cells) {
        Deque<int[]> q = new ArrayDeque<int[]>();
        LinkedHashSet<Long> seen = new LinkedHashSet<Long>();
        q.add(new int[]{sx, sy, sz});
        seen.add(World.cellKey(sx, sy, sz));
        int added = 0;
        while (!q.isEmpty() && added < CONDUCT_MAX) {
            int[] c = q.poll();
            cells.add(c);
            added++;
            for (int k = 0; k < 6; k++) {
                int nx = c[0] + DX6[k], ny = c[1] + DY6[k], nz = c[2] + DZ6[k];
                if (!w.inBounds(nx, ny, nz)) continue;
                if (w.getBlock(nx, ny, nz) != Blocks.WATER.index) continue;
                if (!seen.add(World.cellKey(nx, ny, nz))) continue;
                q.add(new int[]{nx, ny, nz});
            }
        }
    }

    private void tryMove(World w, int dx, int dz) {
        int nx = (int) Math.floor(x) + dx;
        int nz = (int) Math.floor(z) + dz;
        int cy = (int) Math.floor(y);
        int target = w.getBlock(nx, cy, nz);
        Blocks.Block blk = Blocks.byIndex(target);
        if (blk.solid) return;                     // 撞墙
        if (blk.liquid) { this.hp -= 1; }          // 入水掉血（演示）
        x += dx; z += dz;
    }

    private void tryBuild(World w, int blockIdx) {
        // 仅当蓝图已解锁（世界回响的结果）才允许建造
        Blocks.Block blk = Blocks.byIndex(blockIdx);
        String skill = "_blueprint_" + blk.id.toLowerCase();
        if (!w.hasSkill(skill)) { w.log("build", "denied", blk.id, "no_skill"); return; }
        int bx = (int) Math.floor(x), by = (int) Math.floor(y) + 1, bz = (int) Math.floor(z);
        if (!w.inBounds(bx, by, bz)) return;
        if (w.getBlock(bx, by, bz) != Blocks.AIR.index) return;
        // 材料守恒（镜像 Python mass_ledger：扣料=加料，零注入）：
        //  从建成格正下方向下扫描，提取首个有松质量的体素（玩家脚下地面）的单位质量 → 世界松质量 -cost；
        //  落成实体后该体素质量置 0（不计入松质量，结构质量另计）；builtMass +cost → 总量守恒。
        final float cost = 1f;
        int sx = bx, sz = bz, sy = by - 1;
        boolean extracted = false;
        while (w.inBounds(sx, sy, sz)) {
            if (w.mass[sx][sy][sz] >= cost) { w.mass[sx][sy][sz] -= cost; extracted = true; break; }
            sy--;
        }
        if (!extracted) {
            w.log("build", "denied", blk.id, "no_material"); return;   // 料不足则失败，绝不凭空造质量
        }
        w.editBlock(bx, by, bz, blockIdx);           // 落成实体（user edit，按块持久化）
        w.mass[bx][by][bz] = 0f;                     // 建成体素不计入松质量（结构质量另计）
        w.builtMass += cost;                         // 加回：结构质量 +cost → 总质量守恒
        w.builtCells.put(World.cellKey(bx, by, bz), blk.id);  // 建造覆盖集（渲染/蓝图 UI）
        int n = w.builtCells.size();
        // village_memory 续篇：把建造织进世界回响叙事（仅建造事件写记忆；无建造仿真路径的确定性不受影响）
        w.recordMemory(n == 1
                ? "村民首次以" + blk.id + "筑成居所，回响落进记忆"
                : "村民又添一座" + blk.id + "（已筑" + n + "座），世界回响延续");
        w.log("build", "ok", blk.id, "placed");
    }

    /**
     * C 批（动作打磨）：攻击意图 → 攻击状态机。
     * <ul>
     *   <li>起手：无攻击中且冷却就绪 → 启动 AnimController（前摇→判定窗→取消窗→后摇）；</li>
     *   <li>连段：取消窗内再次输入 → 切连招表下段（AnimController.input），下段判定帧会再发一次伤；</li>
     *   <li>前摇/判定窗/后摇硬直内输入 → 忽略（卡手，不重复发伤害）。</li>
     * </ul>
     * 实际伤害由 {@link #tickAttack} 每 tick 推进 atkAnim，跨过<b>判定帧</b>时才发一次（HIT_ONCE_PLAYER 门禁锁死）
     * —— 即"判定帧驱动"，而非瞬间命中。AnimController 零 RNG、atkAnim 不进 hashState → 零漂移。
     */
    private void tryAttack(World w) {
        if (atkAnim == null || atkAnim.finished()) {
            if (atkCd > 0) return;                       // 无攻击中时冷却才生效（防连点）
            atkAnim = new AnimController(ATTACK_COMBO, ATK_STARTUP, ATK_CANCEL);
            atkCd = ATK_LEN;
        } else if (atkAnim.phase() == AnimController.PHASE_CANCEL) {
            atkAnim.input();                             // 取消窗内 → 连段（下段判定帧再发一次伤）
            atkCd = ATK_LEN;
        }
        // 前摇/判定窗/后摇硬直内 → 忽略（卡手）
    }

    /** 每 tick 推进攻击状态机；跨过判定帧 → 唯一一次伤害。纹理/音频零副作用，纯实体结算。 */
    private void tickAttack(World w, float dt) {
        if (atkAnim == null) return;
        if (atkAnim.finished()) { atkAnim = null; return; }
        int ev = atkAnim.update(dt);
        if (ev != AnimController.NO_HIT) {
            hitBeast(w, nearestBeast(w, ATK_RANGE), atk, "ok");
        }
    }

    private void respawn(World w) {
        // 复活点优先（触碰过的祭坛）；未设则回开局选址的安全出生点（持久化，避免回世界中心再陷凹地）
        if (cpSet) { x = cpX; y = cpY; z = cpZ; }
        else {
            int sx = (w.safeSpawnX >= 0) ? w.safeSpawnX : w.SX / 2;
            int sz = (w.safeSpawnZ >= 0) ? w.safeSpawnZ : w.SZ / 2;
            x = sx + 0.5f; z = sz + 0.5f; y = spawnY(w, sx, sz);
        }
        hp = maxHp;
        refillFlask();
        hunger = HUNGER_FULL;         // 重生补给：饥饿不跨命累计（默认满值 → 无行为差异）
        alive = true;                 // 修复：旧实现漏了这一步 —— alive 永不复位 = 死后每 tick 被拉回出生点
        w.log("player", "respawn", cpSet ? "checkpoint" : "spawn", "ok");
    }

    /** 渲染层用：当前攻击进度 [0,1]，未攻击返回 -1。纯读取，不改任何状态 → 零漂移。 */
    public float attackSwing() {
        if (atkAnim == null || atkAnim.finished()) return -1f;
        float len = atkAnim.totalLen();
        if (len <= 0f) return -1f;
        return Math.max(0f, Math.min(1f, atkAnim.animator().time / len));
    }

    /** 渲染层用：是否正在攻击（驱动手持物挥砍）。 */
    public boolean isAttacking() { return atkAnim != null && !atkAnim.finished(); }

    public static float spawnY(World w) {
        return spawnY(w, w.SX / 2, w.SZ / 2);
    }

    /** 指定列的出生高度（最高 solid 之上 1 格）；开局选址器（Simulation.findSpawn）用。 */
    public static float spawnY(World w, int sx, int sz) {
        for (int y = w.SY - 1; y >= 0; y--) {
            int b = w.getBlock(sx, y, sz);
            // 第十六批（几何基座）：出生在"最高实心块的**顶面**"—— 半砖顶面在 y+0.5，不是 y+1
            //（否则会出生在半砖上方半格的空气里、落地时再掉一下）。
            // 满格 shapeHeight == 1f ⇒ 与改动前逐字节一致。
            if (Blocks.byIndex(b).solid) return y + Blocks.shapeBase(b) + Blocks.shapeHeight(b);
        }
        return 1f;
    }

    /**
     * AABB 碰撞检测（渲染/输入层用，不进 sim 确定性指纹）。
     * 玩家盒：以 (x,y,z) 为脚底中心，半宽 0.3、高 1.8。只与 solid 方块碰撞（water 可入）。
     */
    public boolean collides(World w, float nx, float ny, float nz) {
        final float hw = 0.3f;
        int x0 = (int) Math.floor(nx - hw), x1 = (int) Math.floor(nx + hw - 1e-4f);
        int y0 = (int) Math.floor(ny),         y1 = (int) Math.floor(ny + 1.8f - 1e-4f);
        int z0 = (int) Math.floor(nz - hw), z1 = (int) Math.floor(nz + hw - 1e-4f);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    if (!w.solidForEntity(x, y, z)) continue;   // 叠加功能方块状态：开着的门可穿过
                    // 第十六/十七批：非满形状方块只占 [y+base, y+base+height]，AABB 与它**区间相交**才算撞。
                    // 必须与 World.solidBox 用**同一条判据**，否则会出现"站得住但走不进 / 走得进但掉下去"。
                    // 整格 (0,1) ⇒ 条件恒真 ⇒ 与改动前逐字节等价（零回归）。
                    int bi = w.mat[x][y][z];
                    float lo = y + Blocks.shapeBase(bi), hi = lo + Blocks.shapeHeight(bi);
                    if (Blocks.isStairs(bi)) {                  // L 形：下半总在，上半看朝向那半
                        lo = y;
                        hi = y + (w.stairsHighHalf(x, y, z, nx, nz) ? 1f : 0.5f);
                    }
                    if (ny < hi && ny + 1.8f > lo) return true;
                }
        return false;
    }

    // ---- 玩家行走物理（渲染/输入层调用，不进 sim 确定性指纹）----
    private static final float GRAV = 28f, JUMP = 8.6f, WALK = 4.5f;
    /**
     * 站在**轨道**上的速度倍率（第十九批）。这是"轨道"的最小玩法闭环 ——
     * 不必等矿车，轨道本身就有意义（否则它只是"地上一条线"，那正是我拒绝单独做 RAIL 的理由）。
     * 只改实体层的目标速度 ⇒ 不进 {@code hashState}、零漂移。
     */
    private static final float RAIL_SPEED_MUL = 1.5f;
    /** 梯子：上爬/下爬速度、松手时下滑速度上限（第五批功能方块）。 */
    private static final float CLIMB_SPEED = 3.4f, CLIMB_SLIDE = 1.8f;

    // ---- P0 运动四件套①：移动手感（加速度 / 摩擦 / 空中控制）----
    // 来由（2026-09-21）：旧实现 `dx = moveX/len * WALK * dt` 把输入**瞬时**变成满速位移，
    // 于是「起步肉（输入层 83ms 低通）+ 刹车硬（松手当帧归零）+ 空中与地面手感完全一样」。
    // 这里补上真正的速度积分层：v 是**实体层速度**（hashState 不覆盖 → 零漂移），
    // 位移由 v 积分而来，加速/减速各自有独立时间常数。
    /** 地面加速度（m/s²）：0→WALK(4.5) 用时 ≈0.107 s，配合输入层平滑整体 ≈0.21 s 到满速。 */
    private static final float GROUND_ACC = 42f;
    /** 地面减速度（m/s²）：松手后滑行 WALK²/(2·DEC) ≈ 0.34 m。有"顿"但不飘。 */
    private static final float GROUND_DEC = 30f;
    /** 空中加速度系数：空中控制 ≈35%（保住"能修正落点"，但不再"空中横移如平地"）。 */
    private static final float AIR_ACC_MUL = 0.35f;
    /** 空中减速度系数：≈8%（起跳惯性保持 → 跳跃弧线才有形状）。 */
    private static final float AIR_DEC_MUL = 0.08f;
    /** 水平速度（世界空间）。玩家坐标 x/z 是实体层，不进 hashState()。 */
    private float vx = 0f, vz = 0f;
    /** 起跳预输入缓冲（秒）：落地前 120 ms 内按跳 → 落地即起跳（消除"明明按了没跳"）。 */
    private static final float JUMP_BUFFER = 0.12f;
    private float jumpBuf = 0f;

    /**
     * 分轴扫掠碰撞：沿单轴把位移拆成 ≤0.1 小步，碰 solid 即停。
     * 返回<b>是否被方块挡住</b>（碰撞发生：横移撞墙 / 下落着陆 / 上升撞头统称）。
     * LD-2026-09-11 语义修复：旧版返回 {@code dy < 0}（"下落着陆"语义），横移调用时 dy=0 →
     * 撞墙也返回 false → physicsTick 的 blocked 恒为 false → auto-step（tryStepUp）成了死代码，
     * 1 格台阶全部挡路（雪原阶梯地形"步步皆墙"的直接根因）。
     */
    public boolean sweepStep(World w, float dx, float dy, float dz) {
        if (dx == 0 && dy == 0 && dz == 0) return false;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist / 0.1f));
        float sx = dx / steps, sy = dy / steps, sz = dz / steps;
        for (int i = 0; i < steps; i++) {
            if (collides(w, x + sx, y + sy, z + sz)) return true;
            x += sx; y += sy; z += sz;
        }
        return false;
    }

    /**
     * auto-step 台阶高度（MC stepHeight 思想）：能自动登上 1 格台阶，2 格墙仍需跳。
     * 值来自 {@link World#STEP_HEIGHT}（全项目唯一来源，村民/野兽共用同一格数）。
     */
    private static final float STEP_H = World.STEP_HEIGHT;   // Java 无"同包即可见"，跨类必须加类限定符

    /** 潜行防坠边：新位置玩家盒（半宽 0.3）覆盖的脚下列中任一列脚下 solid 即算有支撑。 */
    private static boolean hasGroundUnder(World w, float fx, float fy, float fz) {
        int by = (int) Math.floor(fy - 0.05f);
        int x0 = (int) Math.floor(fx - 0.3f), x1 = (int) Math.floor(fx + 0.3f - 1e-4f);
        int z0 = (int) Math.floor(fz - 0.3f), z1 = (int) Math.floor(fz + 0.3f - 1e-4f);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                if (w.inBounds(x, by, z) && Blocks.byIndex(w.getBlock(x, by, z)).solid) return true;
        return false;
    }
    /** 摔落伤害免伤高度（格）：超过 3.5 格的坠落按 (fall-3)*6 扣血。 */
    private static final float FALL_SAFE = 3.5f;

    /**
     * auto-step：横移被台阶挡住且在地面时，从位移起点抬升 {@link #STEP_H} 重试同段位移——
     * 抬升后能走得同样远（或更远）则登上台阶（垂直重力 sweep 自然落顶），否则回退原位（是墙不是台阶）。
     */
    /**
     * auto-step：横移被台阶挡住且在地面时，从位移起点<b>真的抬升</b> {@link #STEP_H} 后重试同段位移——
     * 抬升处净空且能走得同样远（或更远）则登上台阶（本 tick 末尾的垂直重力 sweep 自然落到台阶顶），
     * 否则整体回退原位（是墙不是台阶）。
     *
     * <p>LD-2026-09-11 修复：旧版只做了 {@code collides(w, x, y+STEP_H, z)} 净空<b>检查</b>，
     * 重走 {@code sweepStep} 时 y 仍停在原地 → 第一步就撞同一堵墙，auto-step 从未生效
     * （雪原阶梯地形"步步皆墙、不知不觉卡住"的根因）。PhysicsTest 已补 STEP 断言锁定此性质。
     */
    private void tryStepUp(World w, float dx, float dz, float ox, float oy, float oz) {
        float rx = x, ry = y, rz = z;
        x = ox; y = oy + STEP_H; z = oz;                                 // 真抬升再试走
        if (collides(w, x, y, z)) { x = rx; y = ry; z = rz; return; }    // 抬升处头顶不净空 → 墙
        boolean blocked = sweepStep(w, dx, 0, dz);
        boolean worse = blocked
                && StrictMath.hypot(x - ox, z - oz) < StrictMath.hypot(rx - ox, rz - oz) - 1e-4f;
        if (worse) { x = rx; y = ry; z = rz; }   // 抬升后走得更少 → 不是台阶，整体回退（含 y）
    }

    /** 一帧玩家物理：行走 + 重力 + 跳跃 + AABB 碰撞。glide=空中按住跳键缓降（需 GLIDE 能力）。 */
    public void physicsTick(World w, float moveX, float moveZ, boolean jump, boolean glide, float dt) {
        physicsTick(w, moveX, moveZ, jump, glide, false, dt);
    }

    /** 一帧玩家物理（带潜行）。sneak=true：移动 ×0.45 + MC 式防坠边（移出支撑格的轴分量取消）。 */
    public void physicsTick(World w, float moveX, float moveZ, boolean jump, boolean glide, boolean sneak, float dt) {
        // 时间基纪律（2026-09-16）：调用方可能传入“已被 GAME SPEED 缩放的帧时长”（如 4x 下的 0.064 s）。
        // 旧实现直接把它夹到 0.05 → 玩家被限速，于是“世界跑得快、玩家跑得慢”
        // （用户报的“其他角色还是太快了、跟我的动作不一样的速度”就是这一条 + 村民乱抖）。
        // 改为按 0.05 s 切片推进：既保住单步碰撞安全（不长步穿墙），又让总位移与仿真 tick 数严格一致。
        float total = Math.min(dt, 0.25f);                       // 上限：极端卡顿一帧也不超过 5 个 tick
        int steps = Math.max(1, (int) Math.ceil(total / 0.05f - 1e-6f));
        float step = total / steps;
        for (int i = 0; i < steps; i++) physicsStep(w, moveX, moveZ, jump, glide, sneak, step);
    }

    /** 把当前值以最大步长 {@code maxStep} 逼近目标（不越过）。P0① 速度积分的唯一原语。 */
    private static float approach(float cur, float target, float maxStep) {
        float d = target - cur;
        if (d > maxStep) return cur + maxStep;
        if (d < -maxStep) return cur - maxStep;
        return target;
    }

    /** 单个物理子步（≤0.05 s）：由 {@link #physicsTick} 切片调用。 */
    private void physicsStep(World w, float moveX, float moveZ, boolean jump, boolean glide, boolean sneak, float dt) {
        // 第二十二批：**坐在矿车上** —— 位置由车决定，玩家不跑自己的物理。
        // 这就是"载具"的全部：输入不再转成自身位移，而是转成**车的推力**（沿轨道走向，不抽 RNG）。
        // 顺序上必须在最前（否则重力/碰撞会先把玩家拽走，表现为"坐上去就掉下来"）。
        if (ridingCartIdx >= 0 && ridingCartIdx < w.carts.size()) {
            Minecart rc = w.carts.get(ridingCartIdx);
            float lenR = (float) StrictMath.hypot(moveX, moveZ);
            if (lenR > 1e-4f) {
                int rd = Facing.horizontal(w.getFacing((int) Math.floor(rc.x),
                        (int) Math.floor(rc.y), (int) Math.floor(rc.z)));
                float push = CART_RIDE_PUSH * lenR;
                if (rd == 0) rc.vx += push;
                else if (rd == 1) rc.vx -= push;
                else if (rd == 4) rc.vz += push;
                else rc.vz -= push;
            }
            x = rc.x; y = rc.y + CART_RIDE_OFFSET; z = rc.z;   // 位置强制跟随
            vy = 0f; onGround = true; fallPeak = y;
            return;                                            // 跳过常规物理（不受重力/碰撞/步进影响）
        }
        if (ridingCartIdx >= 0) ridingCartIdx = -1;            // 车被清理了 → 自动下车（防下标越界）
        float len = (float) StrictMath.hypot(moveX, moveZ);
        float wishX = 0f, wishZ = 0f;
        if (len > 1e-4f) {
            // 期望速度（世界空间）：输入方向 × 最大步速
            // 第十九批：站在**轨道**上加速 —— 轨道的最小玩法闭环（不必等矿车）。
            // 判据：脚底所在格是轨道（轨道只有 1/16 高 ⇒ floor(y - ε) 就落在它那一格里）。
            boolean onRail = w.getBlock((int) Math.floor(x), (int) Math.floor(y - 0.01f),
                    (int) Math.floor(z)) == Blocks.RAIL.index;
            float spd = WALK * (sneak && onGround ? 0.45f : 1.0f) * (onRail ? RAIL_SPEED_MUL : 1f);
            wishX = moveX / len * spd;
            wishZ = moveZ / len * spd;
        }
        // ---- P0①：速度积分（加速/减速各自时间常数；空中控制衰减）----
        // 加速：向 wish 靠拢，速率 = 地面/空中加速度。减速：无输入时按摩擦系数衰减。
        float acc = GROUND_ACC * (onGround ? 1f : AIR_ACC_MUL);
        float dec = GROUND_DEC * (onGround ? 1f : AIR_DEC_MUL);
        float nvx = approach(vx, wishX, (len > 1e-4f ? acc : dec) * dt);
        float nvz = approach(vz, wishZ, (len > 1e-4f ? acc : dec) * dt);
        vx = nvx; vz = nvz;
        float dx = vx * dt, dz = vz * dt;
        // MC 潜行（SneakBehaviour）：不走出支撑边缘（onGround 才生效）
        if (sneak && onGround) {
            if (dx != 0 && !hasGroundUnder(w, x + dx, y, z)) { dx = 0; vx = 0; }   // 前方无支撑 → 取消 X 轴
            if (dz != 0 && !hasGroundUnder(w, x, y, z + dz)) { dz = 0; vz = 0; }   // 前方无支撑 → 取消 Z 轴
        }
        // auto-step（MC stepHeight）：横移被挡且在地面 → 抬升重试（本机验收：小台阶不再挡路）
        float px0 = x, py0 = y, pz0 = z;
        boolean blocked = sweepStep(w, dx, 0, 0);
        if (blocked && onGround && dx != 0) tryStepUp(w, dx, 0, px0, py0, pz0);
        // P0①：撞墙（且没能登台阶）→ 该轴速度归零，避免"贴墙蓄速"导致松手后弹出
        if (blocked && x == px0) vx = 0f;
        px0 = x; pz0 = z;
        blocked = sweepStep(w, 0, 0, dz);
        if (blocked && onGround && dz != 0) tryStepUp(w, 0, dz, px0, py0, pz0);
        if (blocked && z == pz0) vz = 0f;
        if (jump && onGround) { vy = JUMP; onGround = false; jumpBuf = 0f; }
        else if (jump) { jumpBuf = JUMP_BUFFER; }             // P0①：起跳预输入（空中/前摇也能"记住"这一跳）
        if (!jump && jumpBuf > 0f && onGround) {              // 落地瞬间兑现缓冲
            vy = JUMP; onGround = false; jumpBuf = 0f;
        }
        if (jumpBuf > 0f) jumpBuf = Math.max(0f, jumpBuf - dt);
        // ---- 第五批：梯子攀爬（实体/物理层，不进 hashState → 零漂移）----
        // 处于梯子格（脚或头所在格）：跳跃=上爬、潜行=下爬、松手=缓慢下滑；横移阻尼（贴梯不甩出）。
        boolean onLadder = w.isLadder((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                || w.isLadder((int) Math.floor(x), (int) Math.floor(y + 1f), (int) Math.floor(z));
        if (onLadder) {
            if (jump) { vy = CLIMB_SPEED; onGround = false; }
            else if (sneak) { vy = -CLIMB_SPEED; onGround = false; }
            else if (vy < -CLIMB_SLIDE) vy = -CLIMB_SLIDE;   // 松手：缓慢下滑（不自由落体）
            vx *= 0.55f; vz *= 0.55f;
        } else {
            vy -= GRAV * dt;
        }
        // GLIDE 缓降：空中按住跳键 → 限制下落速度并减弱重力（塞尔达风）
        if (glide && abilities.contains("GLIDE") && !onGround && vy < 0) {
            vy = Math.max(vy, -2.2f);
            vy -= GRAV * 0.12f * dt;
            lastGlideTick = w.tick;    // 记“正在滑翔”（试炼封印判定用）
        }
        boolean hit = sweepStep(w, 0, vy * dt, 0);
        if (hit) {
            onGround = vy < 0f;   // 下落碰地=着陆；上升撞头≠着地（vy 同样清零，防贴头滑行）
            float fall = fallPeak - y;
            vy = 0;
            // 摔落伤害（P2 stakes）：超过免伤高度的坠落按距离扣血；本次下落滑翔过 → 免伤
            if (onGround && fall > FALL_SAFE && !fellGlide) {
                hp -= (int) ((fall - 3f) * 6f);
                if (hp <= 0) die(w);
            }
            fallPeak = y; fellGlide = false;
        } else {
            if (vy < 0) onGround = false;
            fallPeak = Math.max(fallPeak, y);
            if (glide && abilities.contains("GLIDE") && vy < 0) fellGlide = true;
        }
        rescueFromVoid(w);
    }

    // ---- 掉出世界兜底（"里世界"）----

    /**
     * 虚空兜底触发线（格）。合法世界里最低可站面是 y=1（y=0 层是 BEDROCK 外壳），
     * 所以 y ≤ 0 就已经"脚踩在底层之下"了。
     *
     * <p><b>为什么不是旧值 {@code -1}</b>：那是"已经掉出去一格格才开始救"。
     * 玩家在 y∈(-1,0] 之间时会看到<b>整屏天空盒填充</b> —— 因为此时眼位低于所有区块的
     * 底面，四周没有任何地形几何，渲染结果就是"被天空/雾/方向辉光糊满整屏"（用户原话
     * "强光糊脸"）。旧线还要求玩家先越过它，等于让玩家先看一眼"里世界"再被拉回来。
     * 抬到 0 之后，**任何绕过 editBlock 的挖穿路径都会在同一物理子步内被兜住**。
     */
    private static final float VOID_FLOOR = 0f;

    /**
     * 掉出世界兜底（"里世界"）：把越界者拉回安全出生点。
     *
     * <p>来由是一次真实事故 —— 世界底层外壳被挖穿后，玩家永远下落、四周没有任何方块。
     * {@link World#editBlock} 统一拦住了"挖穿底层"，但兜底仍然必要：爆炸 / 落雷 /
     * 将来地形生成出空隙 / 联机端越界编辑都可能再产生路径。这里留**最终保险**：
     * 不论怎么出去的，都能回来。
     *
     * <p><b>为什么必须校验落点</b>：{@link #respawn(World)} 与 {@link #spawnY}
     * 都假设出生点那一列有 solid 地面。若出生点自身被挖空，spawnY 返回兜底值 1f，
     * 玩家会立刻再次下落 → 每物理步被"救"一次 → 视觉上疯狂闪烁抖动。
     * 所以这里显式扫一条<b>一定会落地</b>的落点：优先安全出生点列，不行就向外螺旋找。
     *
     * <p><b>零漂移</b>：只在 {@code y < 0} 时触发；合法演化的玩家到不了那里
     * （门禁与正常玩法都不会）→ 对既有指纹零影响。
     */
    private void rescueFromVoid(World w) {
        if (y >= VOID_FLOOR) return;
        int sx = (w.safeSpawnX >= 0) ? w.safeSpawnX : w.SX / 2;
        int sz = (w.safeSpawnZ >= 0) ? w.safeSpawnZ : w.SZ / 2;
        // 落点必须真的踩得到地：出生点列若被挖空则向外螺旋找（半径 ≤12，够覆盖任何人为挖掘区）
        int rx = sx, rz = sz;
        if (w.solidColumnTop(sx, sz) < 0) {
            boolean found = false;
            outer:
            for (int r = 1; r <= 12; r++) {
                for (int i = -r; i <= r; i++) {
                    int[][] cand = {{sx + i, sz - r}, {sx + i, sz + r}, {sx - r, sz + i}, {sx + r, sz + i}};
                    for (int[] c : cand) {
                        if (w.solidColumnTop(c[0], c[1]) >= 0) { rx = c[0]; rz = c[1]; found = true; break outer; }
                    }
                }
            }
            if (!found) rx = -1;   // 整片被挖空：退回原语（spawnY 的 1f 兜底 + 底层 BEDROCK 承托）
        }
        if (rx >= 0) { x = rx + 0.5f; z = rz + 0.5f; }
        else { x = sx + 0.5f; z = sz + 0.5f; }
        y = spawnY(w, (int) Math.floor(x), (int) Math.floor(z));
        vy = 0f; onGround = true; fallPeak = y;
        w.log("player", "void_rescue", "fell_below_world", "ok");
    }

    // ---------- Intent ----------
    /**
     * 玩家意图 = 联机锁步的**唯一输入单元**（N1）。
     *
     * <p><b>为什么加 {@link #yaw}</b>：朝向（鼠标视角）本来就属于"输入"，锁步的输入包格式
     * 一旦上线就不该再改，所以现在就加。它是**线路字段**——当前仿真逻辑一律不读它
     * （攻击走"最近敌兵"、建造落在玩家自身格，见 {@code tryBuild}/{@code tryAttack}），
     * 故对 {@link World#hashState()} 与全部门禁**零影响**。
     *
     * <p>取值是**量化 uint16**（0..65535 ↔ 0..360°）→ 线路无损（浮点不线路化）；
     * 量化在输入层完成，用 {@link #yawDeg(float)} / {@link #yawDegrees()} 做角度换算。
     */
    public static final class Intent {
        public enum Type { MOVE, REPEL, ATTACK, PARRY, EXECUTE, BUILD, IDLE }
        public Type type; public int dx, dz, blockIdx;
        /** 朝向：量化 uint16（0..65535 ↔ 0..360°）。线路字段，仿真不读。 */
        public int yaw;
        private Intent() {}
        public static Intent move(int dx, int dz) { Intent i = new Intent(); i.type = Type.MOVE; i.dx = dx; i.dz = dz; return i; }
        public static Intent repel() { Intent i = new Intent(); i.type = Type.REPEL; return i; }
        public static Intent attack() { Intent i = new Intent(); i.type = Type.ATTACK; return i; }
        public static Intent parry() { Intent i = new Intent(); i.type = Type.PARRY; return i; }
        public static Intent execute() { Intent i = new Intent(); i.type = Type.EXECUTE; return i; }
        public static Intent build(int b) { Intent i = new Intent(); i.type = Type.BUILD; i.blockIdx = b; return i; }
        public static Intent idle() { Intent i = new Intent(); i.type = Type.IDLE; return i; }

        /** N1：按类型构造空意图（编解码器用；其余字段由调用方填）。 */
        public static Intent of(Type t) { Intent i = new Intent(); i.type = t; return i; }

        /** 便捷：以角度（度）设置朝向（内部量化为 uint16）。可链式调用。 */
        public Intent yawDeg(float deg) {
            float d = deg % 360f;
            if (d < 0f) d += 360f;
            yaw = ((int) (d / 360f * 65536f)) & 0xFFFF;
            return this;
        }

        /** 便捷：读回量化朝向对应的角度（度，0..360）。 */
        public float yawDegrees() { return yaw * 360f / 65536f; }
    }
}
