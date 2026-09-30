package core.systems;

import core.rng.SeededRNG;
import core.world.Beast;
import core.world.Player;
import core.world.World;

/**
 * 敌兵系统（IMPL-COMBAT 真实战斗遭遇最小闭环）。
 *
 * 零漂移铁律：
 *  - 只经 {@link World#simStream(String)} 取随机（确定性子流，基于主种子+name+tick，绝不进 rng 主状态）；
 *  - 绝不动 mat/mass 网格（敌兵是实体，不写方块）；
 *  - 不读 fxRng（演出专用，绝不进仿真）。
 * 因此本系统对 {@link World#hashState()} 指纹零影响——四道门禁翻不了红。
 *
 * 行为（最小可玩闭环）：
 *  1) 生成：beasts 未满上限时，按 tick 确定性节奏在玩家附近生成一只敌兵，
 *           坐标偏移由 simStream("beast:spawn") 派生，y 贴该列地表；
 *  2) 移动：每 tick 朝玩家以固定步长逼近，保持贴地（y 用所在列地表高度）；
 *  3) 攻击：与玩家距离 < 接触半径则固定值扣血，归零则置玩家 alive=false（下 tick 由 player.tick 自动重生）。
 * 敌兵的死亡（移除 + 触发繁荣链）由 Player 的 ATTACK 意图负责（见 Player.tick）。
 */
public final class BeastSystem implements System {

    private static final float SPAWN_PERIOD = 24f;   // 每 24 tick 至多补一只（确定性节奏）
    /** 出生宽限（LD-2026-09-11「开局两兽堵门」）：前 120 tick（≈6s）不刷敌——落地即被围殴=新手体验死局。 */
    private static final int GRACE_TICKS = 120;
    private static final float SPAWN_MIN_DIST = 10f; // 生成离玩家最近距离（旧 4f：贴脸刷，玩家还没看清就接敌）
    private static final float SPAWN_MAX_DIST = 18f; // 生成离玩家最远距离（旧 8f）
    private static final float STANDOFF = 5.5f;      // 远程原型保持的距离
    private static final float RANGE = 11f;          // 远程原型有效射程（= Beast.RANGED_MAX）

    /**
     * 普通敌兵身体盒半宽（格）：<b>别名</b> {@link Beast#HW}{@code [0]}（唯一来源在实体侧的尺寸表）。
     *
     * <p>数值必须 ⊇ 渲染最宽处（本体 ±0.30、剪影暗盒 ±0.34、角/横杆 ±0.35）。
     * LD-2026-09-16：原取 0.34，被 {@code audit_invariants.py} 的 C3（渲染盒 ⊆ 碰撞盒）判为不合格 ——
     * 角尖比碰撞盒宽 0.01 格。这类"模型比碰撞盒大"正是"模型一部分没了"的根因。
     *
     * <p>2026-09-16（P0-4）：尺寸表整体搬到 {@link Beast#HW}/{@link Beast#HH} 按 type 索引，
     * 否则守卫 Boss 的体型无处安放。这里保留名字是为了让既有调用点（含门禁）继续可用。
     */
    public static final float BODY_HW = Beast.HW[0];
    /** 普通敌兵净空高度（格）：别名 {@link Beast#HH}{@code [0]}（1.35，不把树冠当墙）。 */
    public static final float BODY_H = Beast.HH[0];
    /** 自动上台阶高度：直接别名 {@link World#STEP_HEIGHT}（全项目唯一来源）。 */
    public static final float BODY_STEP = World.STEP_HEIGHT;

    @Override
    public String name() { return "BeastSystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.player == null) return;                // 无玩家则无战斗目标，安全跳过
        spawn(w);
        move(w);
        attack(w);
        // 受击闪白计时衰减（渲染用，不进指纹）
        for (Beast b : w.beasts) if (b.hitFlash > 0f) b.hitFlash = Math.max(0f, b.hitFlash - 1f / 20f);
    }

    /** 生成：确定性节奏 + simStream 派生的偏移与类型，贴玩家附近地表。
     *  LD-2026-09-12 村庄庇护所：选址器把玩家出生地搬进了村庄环带 → 兽群跟着玩家刷 = 刷进村里屠村。
     *  刷点落在村庄半径（NpcSystem.VILLAGE_RADIUS）内 → 重掷方位/距离（至多 3 次），仍不成就放弃本次。 */
    private void spawn(World w) {
        if (w.beasts.size() >= w.beastCap) return;   // 用世界实例上的上限（预设可覆盖），不再读静态常量
        if (w.tick < GRACE_TICKS) return;                // 出生宽限：给玩家看清世界的时间
        if (w.tick % (int) SPAWN_PERIOD != 0) return;   // 确定性节奏（同 tick 同结果）
        Player p = w.player;
        SeededRNG r = w.simStream("beast:spawn");        // 仅此处取随机，且基于主种子
        int type = (int) (r.nextFloat() * Beast.N_TYPES); // 第 1 次：原型
        // P1：给这个原型挑一只"有身份的内容兽"（beasts/*.json）。
        // 关键：**没有任何内容兽时不抽随机** —— 否则既有种子的刷怪序列会整体位移
        // （内容是可选资产；装了内容才改变世界，这是"内容驱动"能保持零漂移的根据）。
        core.content.BeastDef def = null;
        java.util.List<core.content.BeastDef> defs = w.beastDefs;
        if (defs != null && !defs.isEmpty()) {
            int n = 0;
            for (int i = 0; i < defs.size(); i++) if (defs.get(i).archetype == type) n++;
            if (n > 0) {
                int pick = (int) (r.nextFloat() * n);
                for (int i = 0; i < defs.size(); i++) {
                    if (defs.get(i).archetype != type) continue;
                    if (pick-- == 0) { def = defs.get(i); break; }
                }
            }
        }
        float bx = 0, bz = 0;
        boolean ok = false;
        for (int tries = 0; tries < 3; tries++) {
            float ang = r.nextFloat() * (float) (2.0 * Math.PI);
            float dist = SPAWN_MIN_DIST + r.nextFloat() * (SPAWN_MAX_DIST - SPAWN_MIN_DIST);
            bx = p.x + (float) StrictMath.cos(ang) * dist;
            bz = p.z + (float) StrictMath.sin(ang) * dist;
            // 夹到世界内（留 2 格边距），避免越界
            bx = Math.max(2f, Math.min(w.SX - 3f, bx));
            bz = Math.max(2f, Math.min(w.SZ - 3f, bz));
            float vx = bx - w.SX / 2f, vz = bz - w.SZ / 2f;   // 村心 = 世界中心（NpcSystem 同源）
            if (vx * vx + vz * vz > NpcSystem.VILLAGE_RADIUS * NpcSystem.VILLAGE_RADIUS) { ok = true; break; }
        }
        if (!ok) return;                                 // 三次都在村内 → 放弃本次（下个周期再试）
        int ix = (int) Math.floor(bx), iz = (int) Math.floor(bz);
        // QA 2026-09-16：出生落点按**整片足迹**取地面（旧实现只取中心列 → 一出生就有半个身子埋在坡里）
        float by = this.spawnY(w, type, bx, bz, w.surfaceY[ix][iz] + 1f);
        Beast b = Beast.make(type, bx, by, bz);
        if (def != null) {
            // 内容只覆盖**数值与身份**，不覆盖 AI 形状（形状由 archetype 决定）。
            b.defId = def.id;
            b.maxHp = def.hp; b.hp = def.hp;
            b.dmg = def.atk;
            b.speed = def.speedPerTick;
            b.aggroRange = def.aggroRange;
        }
        w.beasts.add(b);
    }

    /** 移动：按原型速度朝玩家逼近；远程原型保持 Standoff 距离。贴地。 */
    /**
     * 移动：按原型速度朝玩家逼近；远程原型保持 Standoff 距离。
     *
     * <p>QA 2026-09-16：位移改为<b>带身体盒碰撞</b>（抬升 1 格台阶 / 沿墙滑），高度改为按
     * <b>整片足迹</b>取地面。旧实现直接改 x/z、高度只取中心列地表 → 身体一侧埋进相邻高方块
     * （"模型一半没了 / 进墙里"），并且会穿墙式逼近。
     */
    /** P1-3：距离分档（格，离村心）：[0,40) ×1.0 / [40,80) ×1.4 / [80,∞) ×1.9。
     *  只缩放 HP 与伤害（保持可玩、避免卡墙）；speed/aggro 不变。确定性（仅依赖玩家坐标）。
     *  仅在「有内容兽」时调用（内容是可选资产；无内容则默认兽不受影响 → 测试基线零漂移）。 */
    private static void applyDistanceTier(World w, Player p, Beast b) {
        float d = (float) StrictMath.hypot(p.x - w.SX / 2f, p.z - w.SZ / 2f);
        float mult = d < 40f ? 1.0f : (d < 80f ? 1.4f : 1.9f);
        b.maxHp = (int) Math.round(b.maxHp * mult);
        b.hp = b.maxHp;
        b.dmg = (int) Math.round(b.dmg * mult);
    }

    private void move(World w) {
        Player p = w.player;
        for (Beast b : w.beasts) {
            if (b.tamed) continue;                  // 已收编：不追玩家（由 CaptureSystem 驱动跟随）
            float dx = p.x - b.x;
            float dz = p.z - b.z;
            float len = (float) StrictMath.hypot(dx, dz);
            float bhw = Beast.hw(b.type), bhh = Beast.h(b.type);
            // 状态类效果（APPLY_BUFF 的 modifiers.speed）经 Beast.slowMul 生效。
            // 默认 1 → 与旧行为**逐字节相同**（speed*1.0f 在 IEEE754 下精确等于 speed）。
            float sp = b.effectiveSpeed() * b.slowMul;
            if (len > 1e-4f) {
                if (Beast.isRanged(b.type)) {
                    // 远程：太近后退，太远前进，否则原地（保持距离吐息）
                    if (len < STANDOFF - 1f) this.step(w, b, -dx / len * sp, -dz / len * sp);
                    else if (len > STANDOFF + 1f) this.step(w, b, dx / len * sp, dz / len * sp);
                } else {
                    // 近战：逼近到接触半径的 80% 即停——不再走进玩家身体（旧版叠身=「两个东西堵着动不了」的体感来源）
                    if (len > b.contact * 0.8f) this.step(w, b, dx / len * sp, dz / len * sp);
                }
            }
            // 贴地：按整片足迹取地面高度（只会"往下补"，抬升由 step 的台阶重试负责）
            b.y = w.floorY(b.x, b.z, bhw, b.y + 0.05f);
            // 防活埋（LD-2026-09-16）：身体嵌进方块时抬到"足迹最高地面"上（同 NpcSystem.settle）。
            if (w.solidBox(b.x, b.y + 0.02f, b.z, bhw, bhh)) {
                b.y = w.floorY(b.x, b.z, bhw, (float) w.SY);
            }
        }
    }

    /** 位置 (x,y,z) 处野兽身体盒是否净空，且在世界可用区内（尺寸按 type 取）。 */
    private boolean clear(World w, Beast b, float x, float y, float z) {
        if (x < 2f || x > w.SX - 3f || z < 2f || z > w.SZ - 3f) return false;
        return !w.solidBox(x, y, z, Beast.hw(b.type), Beast.h(b.type));
    }

    /** 位移（格）：整段 → 抬升 1 格台阶 → 分轴沿墙滑；全被挡即原地（不再"穿墙式逼近"）。 */
    private void step(World w, Beast b, float dx, float dz) {
        if (clear(w, b, b.x + dx, b.y, b.z + dz)) { b.x += dx; b.z += dz; return; }
        if (clear(w, b, b.x + dx, b.y + BODY_STEP, b.z + dz)) {
            b.x += dx; b.z += dz; b.y += BODY_STEP; return;
        }
        if (dx != 0f && clear(w, b, b.x + dx, b.y, b.z)) b.x += dx;
        if (dz != 0f && clear(w, b, b.x, b.y, b.z + dz)) b.z += dz;
    }

    /** 出生落点：优先"中心列 +1 格台阶"取足迹地面；身体仍不净空（窄槽 / 仙人掌）则退回足迹最高地面。 */
    private float spawnY(World w, int type, float x, float z, float centerY) {
        float bhw = Beast.hw(type), bhh = Beast.h(type);
        float y = w.floorY(x, z, bhw, centerY + BODY_STEP);
        if (w.solidBox(x, y + 0.02f, z, bhw, bhh)) y = w.floorY(x, z, bhw, (float) w.SY);
        return y;
    }

    /**
     * P0-4：在给定水平位置确定性生成<b>世界之心守卫</b>并加入世界。
     *
     * <p>与随机刷怪完全分离：不走 {@code simStream}、不吃 {@code beastCap} 的"随机"语义
     * （守卫是剧情实体）。落点按整片足迹取地面并做一次防活埋，跟普通敌兵同一套规则。
     *
     * <p>{@code refY} 是「参考高度」：守卫落在该高度<b>附近</b>的地面上。
     * 世界之心在峰顶 → 传 {@code w.SY}（取整列最高地面）；F8 调试召唤 → 传玩家当前 y
     * （否则从峰顶扫下来会落到山顶而不是玩家脚下）。
     *
     * @return 生成的守卫（已 add 进 {@code w.beasts}）
     */
    public static Beast spawnWarden(World w, float x, float z, float refY) {
        float bx = Math.max(3f, Math.min(w.SX - 4f, x));
        float bz = Math.max(3f, Math.min(w.SZ - 4f, z));
        float hw = Beast.hw(Beast.TYPE_BOSS), hh = Beast.h(Beast.TYPE_BOSS);
        float by = w.floorY(bx, bz, hw, refY + BODY_STEP);
        if (w.solidBox(bx, by + 0.02f, bz, hw, hh)) by = w.floorY(bx, bz, hw, (float) w.SY) + 1f;
        Beast b = Beast.make(Beast.TYPE_BOSS, bx + 0.5f, by, bz + 0.5f);
        w.beasts.add(b);
        return b;
    }

    /**
     * 攻击玩家：<b>攻击状态机</b>（IDLE → WINDUP → STRIKE → RECOVER），伤害只在 STRIKE 那一 tick
     * 结算一次（见 {@link Beast} 类注释）。
     *
     * <p>与旧实现的差别（旧实现：接触即每 tick 扣血，一级被贴身 0.5 s 必死）：
     * <ul>
     *   <li>伤害离散 → “被打中”成了可读事件，翻滚 i 帧躲的是<b>一次攻击</b>而不是一团持续伤害；</li>
     *   <li>前摇/后摇<b>不转向</b>（方向在起手时冻结）→ 侧移/后撤能真的躲开；</li>
     *   <li>命中判定要求玩家仍在锁定方向的半球内（点积 &gt; {@link Beast#STRIKE_ARC_DOT}）。</li>
     * </ul>
     * 硬直（{@link Beast#stagger}）期间不推进攻击相位，但移动照常（见 {@link #move}，因此
     * “不叠身”距离断言不受影响）：于是“打中 → 敌人缩一下”的手感成立。
     */
    private void attack(World w) {
        Player p = w.player;
        for (Beast b : w.beasts) {
            if (b.tamed) continue;                               // 已收编：不再攻击玩家
            if (b.stagger > 0) { b.stagger--; continue; }        // 硬直：本 tick 不推进出招
            float adx = p.x - b.x, ady = p.y - b.y, adz = p.z - b.z;
            float d = (float) Math.sqrt(adx * adx + ady * ady + adz * adz);
            boolean ranged = Beast.isRanged(b.type);
            switch (b.atkPhase) {
                case Beast.PHASE_IDLE: {
                    boolean engage = ranged
                            ? (d >= Beast.RANGED_MIN && d <= Beast.RANGED_MAX)
                            : (d < b.contact + Beast.ENGAGE_PAD);
                    if (engage && d > b.aggroRange) engage = false;   // P1：内容兽的警戒范围
                    if (!engage) break;
                    // 起手：冻结“出手方向”。此后到出手为止不再转向 → 这是玩家侧移能躲开的根据。
                    float l = (float) StrictMath.hypot(adx, adz);
                    if (l < 1e-4f) { b.lockX = 0f; b.lockZ = 1f; } else { b.lockX = adx / l; b.lockZ = adz / l; }
                    b.atkPhase = Beast.PHASE_WINDUP;
                    // P0-4：守卫二阶段（激怒）把前摇从 30 tick 压到 18 tick —— 读招窗口收紧，
                    // 但仍有 0.9 s，不会变成"必中"。
                    b.atkTimer = b.effectiveWindup();
                    break;
                }
                case Beast.PHASE_WINDUP: {
                    // 前摇 = 给玩家的读招窗口：这里刻意什么都不做（不追向、不修正方向）
                    if (--b.atkTimer <= 0) b.atkPhase = Beast.PHASE_STRIKE;
                    break;
                }
                case Beast.PHASE_STRIKE: {
                    float dot = adx * b.lockX + adz * b.lockZ;
                    boolean inArc = ranged || dot > Beast.STRIKE_ARC_DOT;
                    float reach = ranged ? Beast.RANGED_MAX : b.contact + 0.4f;
                    if (inArc && d < reach) p.hitByBeast(b.effectiveDmg(), w);
                    b.atkPhase = Beast.PHASE_RECOVER;
                    b.atkTimer = b.recoverT;
                    break;
                }
                case Beast.PHASE_RECOVER: {
                    if (--b.atkTimer <= 0) b.atkPhase = Beast.PHASE_IDLE;
                    break;
                }
                default: b.atkPhase = Beast.PHASE_IDLE; break;
            }
        }
    }
}
