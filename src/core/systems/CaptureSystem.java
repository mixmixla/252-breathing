package core.systems;

import core.content.Inventory;
import core.rng.SeededRNG;
import core.world.Beast;
import core.world.Player;
import core.world.World;
import java.util.ArrayList;
import java.util.List;

/**
 * 捕捉驯养子系统（第 98 个系统）—— 消费预设参数 {@code hpThreshold} 与 {@code orbItemCost}。
 *
 * <p><b>它解决什么</b>：{@code capture} 模块与 {@code palworld_like} 预设写了这两个键，
 * 但此前**背后没有系统**（审计 C8 的 7 个「空转参数」中的两个）。
 *
 * <p><b>玩法（Palworld 式两段判定）</b>：
 * <ol>
 *   <li><b>削弱</b>：目标野兽血量比 ≤ {@code hpThreshold}（{@code palworld_like} = 0.35）；</li>
 *   <li><b>投球</b>：背包里有 {@code orbItemCost} 个捕捉球（物品 {@link #ORB_ITEM}）。</li>
 * </ol>
 * 两条都满足 → 野兽被收编（{@link Beast#tamed}）：不再攻击玩家（见 {@link BeastSystem}），
 * 改为跟随玩家并周期性协战（伤害走 {@link Player#hitBeast} —— 全项目唯一伤害规则）。
 *
 * <p><b>为什么捕捉入口不在 {@code update} 里</b>：投球是<b>玩家动作</b>（按键触发），
 * 不是每 tick 演化。故 {@link #tryCapture} 是给渲染层调用的公开入口 —— 与
 * {@code TechTree}/{@code ContentSystem} 同一套「可选启用」纪律。
 *
 * <p><b>零漂移</b>：没有任何已驯服野兽时 {@link #update} 不消费 RNG、不写世界；
 * 且门禁环境不会调用 {@link #tryCapture} → 四道指纹不受影响。
 */
public final class CaptureSystem implements System {

    /** 捕捉球物品 id（内容层 {@code items/orb.json}）。 */
    public static final String ORB_ITEM = "orb";

    /** 投球射程（格）。 */
    public static final float CAPTURE_RANGE = 4.5f;

    /** 跟随距离：超过即朝玩家靠拢。 */
    public static final float FOLLOW_DIST = 3.0f;

    /** 协战节拍（tick）：1.5 秒一次。 */
    public static final int TAMED_ASSIST_PERIOD = 30;

    /** 协战索敌半径（格）。 */
    public static final float ASSIST_RANGE = 4.0f;

    /** 协战单次伤害。 */
    public static final int TAMED_ASSIST_DMG = 4;

    private int captured = 0;
    private int tamedAlive = 0;

    @Override public String name() { return "capture"; }

    @Override
    public void update(World w, SeededRNG rng) {
        Player p = w.player;
        tamedAlive = 0;
        if (p == null) return;
        for (int i = 0; i < w.beasts.size(); i++) {
            Beast b = w.beasts.get(i);
            if (!b.tamed) continue;
            tamedAlive++;
            follow(w, p, b);
        }
        if (tamedAlive > 0 && (w.tick % TAMED_ASSIST_PERIOD) == 0L) assist(w, p);
    }

    /** 已驯服野兽跟随玩家（贴地；不攻击玩家）。 */
    private void follow(World w, Player p, Beast b) {
        float dx = p.x - b.x, dz = p.z - b.z;
        float len = (float) StrictMath.hypot(dx, dz);
        if (len > FOLLOW_DIST) {
            float sp = b.speed * b.slowMul;
            float nx = dx / len * sp, nz = dz / len * sp;
            if (len - sp < FOLLOW_DIST * 0.5f) {          // 别冲过头（绕着玩家抖）
                nx = dx * 0.5f / len; nz = dz * 0.5f / len;
            }
            b.x += nx; b.z += nz;
        }
        float bhw = Beast.hw(b.type), bhh = Beast.h(b.type);
        b.y = w.floorY(b.x, b.z, bhw, b.y + 0.05f);
        if (w.solidBox(b.x, b.y + 0.02f, b.z, bhw, bhh)) b.y = w.floorY(b.x, b.z, bhw, (float) w.SY);
    }

    /** 协战：每只已驯服野兽打最近的敌对野兽一下（伤害走唯一伤害规则）。 */
    private void assist(World w, Player p) {
        List<Beast> snap = new ArrayList<Beast>(w.beasts);
        for (int i = 0; i < snap.size(); i++) {
            Beast b = snap.get(i);
            if (!b.tamed) continue;
            Beast t = nearest(w, b.x, b.z, ASSIST_RANGE, true);
            if (t != null) p.hitBeast(w, t, TAMED_ASSIST_DMG, "tamed");
        }
    }

    /**
     * 投球收编（渲染层按键调用）。
     *
     * @return 是否成功收编
     */
    public boolean tryCapture(World w, Inventory inv) {
        Player p = w.player;
        if (p == null) return false;
        Beast target = nearest(w, p.x, p.z, CAPTURE_RANGE, true);
        if (target == null) return false;
        float frac = target.maxHp <= 0 ? 1f : (float) target.hp / (float) target.maxHp;
        if (frac > w.config.hpThreshold) return false;                 // ① 血量未削够
        int cost = Math.max(1, w.config.orbItemCost);
        if (inv == null || inv.countOf(ORB_ITEM) < cost) return false;  // ② 球不够
        inv.remove(ORB_ITEM, cost);
        target.tamed = true;
        target.atkPhase = Beast.PHASE_IDLE;
        target.atkTimer = 0;
        target.stagger = 0;
        captured++;
        String who = target.defId != null ? target.defId : Beast.typeName(target.type);
        w.log("capture", "tamed", who, "cost=" + cost + ",hp=" + target.hp + "/" + target.maxHp);
        w.recordMemory("收编：" + who + " 归心（耗球 " + cost + "，余血 " + target.hp + "）");
        return true;
    }

    /** 半径内最近的敌对野兽（不含已驯服与守卫）。 */
    private Beast nearest(World w, float x, float z, float range, boolean hostileOnly) {
        Beast best = null;
        float bestD = range * range;
        for (int i = 0; i < w.beasts.size(); i++) {
            Beast b = w.beasts.get(i);
            if (hostileOnly && b.tamed) continue;
            if (Beast.isBoss(b.type)) continue;            // 守卫不可收编
            float dx = b.x - x, dz = b.z - z;
            float d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = b; }
        }
        return best;
    }

    public int captured()   { return captured; }
    public int tamedAlive() { return tamedAlive; }
}
