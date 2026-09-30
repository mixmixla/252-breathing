package core.systems;

import core.rng.SeededRNG;
import core.world.Beast;
import core.world.Blocks;
import core.world.Player;
import core.world.Trials;
import core.world.World;

/**
 * 试炼系统（C2 · 塞尔达循环后半段）。
 *
 * <p>职责：
 * <ol>
 *   <li><b>确定性布点</b>（首 tick，仅一次）：在世界中确定地放置 3 座<b>试炼点</b>（各以 GLIDE/DASH/BOMB 为印）、
 *       4 处<b>补给箱</b>，并把<b>世界之心</b>定于全图最高峰。</li>
 *   <li><b>认取判定</b>：试炼点需「邻近 + 已具对应能力」；补给箱仅需邻近；世界之心需「已显现 + 三力齐备 + 登顶」。</li>
 *   <li><b>目标链推进</b>：集齐三遗物 → 世界之心显现（写一条事件，由村志归档）。</li>
 * </ol>
 *
 * <h3>零漂移铁律（同 BeastSystem/ShrineSystem）</h3>
 * <ul>
 *   <li>随机只走 {@link World#simStream(String)}（基于主种子派生子流，<b>不推进</b>主 rng.state）；</li>
 *   <li>试炼点/补给箱/世界之心是<b>实体级状态</b>，存 {@link World#trials}，<b>不进</b> hashState；</li>
 *   <li>认取只改 {@link Trials} 自身 + {@link Player}（souls）+ 追加 {@code events}；
 *       <b>绝不</b>写 prosperity/skills/villageMemory —— 故 DETERMINISM 指纹与接入前逐字节一致；</li>
 *   <li>玩家位置来自确定性输入（同种子同输入 → 同认取序列）。</li>
 * </ul>
 *
 * <h3>F4 · 地形门（2026-09-16，已获准重锁基线）</h3>
 * <ul>
 *   <li>每座试炼点外围刻一道<b>纯几何、零 rng</b>的石环（{@link #carveGate}），入口按能力改写：
 *       BOMB=石墙封口（须 {@code Player.bomb} 炸开）、GLIDE=入口断壑（缓降入窟）、DASH=窄隙长冲通道；</li>
 *   <li>写地形的代价是 DET/ZD/STREAMING/NPC/MEGALITH 五基线哈希整体改变（各自<b>仍自洽</b>），
 *       故门禁全过，但须在 task_board.json / EVAL_REPORT_3.md / 文档<b>重新记录新指纹</b>；</li>
 *   <li><b>逻辑封印优先</b>：{@link Trials#abilityUsed} 仍是硬门（未真用过该能力 → 取不到遗物），
 *       即便地形被绕过也绝不软锁；地形门是「风味 + 真实障碍」，不单独承担正确性。</li>
 * </ul>
 */
public final class TrialSystem implements System {

    private static final String[] ABILITY = {"GLIDE", "DASH", "BOMB"};
    private static final String[] NAME = {
            "Trial of the Updraft", "Trial of the Long Stride", "Trial of the Shattered Vault"};
    private static final float[] SITE_DIST = {48f, 60f, 72f};   // F4：按距离铺开（比祭坛 12~22 远一截，逼出探索梯度）

    private static final int CACHE_N = 4;
    private static final float[] CACHE_DIST = {9f, 16f, 23f, 30f};
    private static final int CACHE_SOUL_MIN = 10, CACHE_SOUL_SPAN = 16;   // 10..25

    private static final float SITE_R2 = 9f;      // 3 格
    private static final float CACHE_R2 = 4f;     // 2 格
    private static final float HEART_R2 = 9f;     // 3 格

    private static final int RELIC_SOULS = 30;
    private static final int HEART_SOULS = 200;
    /** P0-4：击败世界之心守卫的魂奖励（比世界之心本身略低 —— 终局奖励仍给"认取"。 */
    private static final int WARDEN_SOULS = 260;
    /** P0-4：守卫与世界之心的水平间距（格）—— 放在心南侧，玩家登顶时会先撞上它。 */
    private static final float WARDEN_OFFSET = 4.5f;

    @Override
    public String name() { return "TrialSystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        Trials tr = w.trials;
        if (tr.sites.isEmpty() && w.tick == 1) place(w, tr);   // 首 tick 确定性布点（仅一次）

        // 目标链推进：集齐三遗物 → 世界之心显现（写一条事件，入村志；不写指纹字段）
        if (!tr.heartRevealed && tr.allRelics()) {
            tr.heartRevealed = true;
            w.log("trial", "heart_reveal", "summit", "ok");
        }

        // ---- P0-4：世界之心守卫 ----
        // 分支用 if / else-if 而非两个独立 if：生成的那一 tick 绝不进"判死"分支，
        // 否则刚放下的守卫会被当场判定为"不存在 ⇒ 已死"，终局直接白送。
        if (tr.heartRevealed && !tr.heartClaimed && !tr.bossSlain) {
            if (!tr.bossSpawned) {
                core.systems.BeastSystem.spawnWarden(w, tr.heartX, tr.heartZ - WARDEN_OFFSET, (float) w.SY);
                tr.bossSpawned = true;
                w.log("trial", "warden", "awake", "ok");
            } else if (!anyWarden(w)) {
                tr.bossSlain = true;
                w.log("trial", "warden", "slain", "ok");
                if (w.player != null) w.player.souls += WARDEN_SOULS;
            }
        }

        Player p = w.player;
        if (p == null) return;

        // 试炼点认取：邻近 + 具备对应能力 + 刚刚真的用过该能力（塞尔达式封印，2026-09-16）
        for (Trials.Site s : tr.sites) {
            if (s.claimed || !p.abilities.contains(s.ability)) continue;
            if (!Trials.abilityUsed(p, s.ability, w.tick)) continue;   // 封印未破 → 见 Trials.sealHint
            if (near(p, s.x, s.y, s.z, SITE_R2)) {
                s.claimed = true;
                tr.relics++;
                p.souls += RELIC_SOULS;
                w.log("trial", "relic", s.ability, "ok");
            }
        }

        // 补给箱认取：邻近即取（探索的即时回报）
        for (Trials.Cache c : tr.caches) {
            if (c.claimed) continue;
            if (near(p, c.x, c.y, c.z, CACHE_R2)) {
                c.claimed = true;
                p.souls += c.souls;
                w.log("trial", "cache", "souls=" + c.souls, "ok");
            }
        }

        // 世界之心认取：已显现 + <b>守卫已被击败</b> + 三力齐备 + 登顶
        // （P0-4：加了 bossSlain —— 否则"终局"仍然只是走到最高峰站一下。）
        if (tr.heartRevealed && !tr.heartClaimed && tr.bossSlain
                && p.abilities.contains(ABILITY[0]) && p.abilities.contains(ABILITY[1])
                && p.abilities.contains(ABILITY[2])
                && near(p, tr.heartX, tr.heartY, tr.heartZ, HEART_R2)) {
            tr.heartClaimed = true;
            p.souls += HEART_SOULS;
            w.log("trial", "heart", "world_heart", "ok");
        }
    }

    // ---------------------------------------------------------------- 布点
    /**
     * 立项 F 修复（N2a 前置）：读档后重建布点。
     *
     * <p>{@link #place} 是「tick==1 专属」的确定性布点——其子流 {@code simStream("trial:place")}
     * = {@code SHA256(seed + "|trial:place:1")}，<b>只依赖 (seed, name, tick)、与 {@code rng.state} 无关</b>，
     * 故把 tick 置 1 即可逐位复现原始布点。
     *
     * <p>而 {@code World.load} 只恢复状态、不跑 tick。若不显式重建：
     * ① ext 段读 {@code sites.get(i)} 会直接越界崩溃（**真实世界存读档必崩**）；
     * ② 即便侥幸不崩，读档后的世界里也没有任何试炼点/补给箱/世界之心。
     */
    public static void placeOnLoad(World w) {
        Trials tr = w.trials;
        tr.sites.clear();
        tr.caches.clear();
        place(w, tr);
        // P0-4：实体层（beasts）不持久化，所以存档里"守卫还没死"时，读档必须把它放回去 ——
        // 否则下一 tick 的"找不到守卫 ⇒ 已死"分支会白送终局。
        if (tr.heartRevealed && !tr.heartClaimed && tr.bossSpawned && !tr.bossSlain) {
            core.systems.BeastSystem.spawnWarden(w, tr.heartX, tr.heartZ - WARDEN_OFFSET, (float) w.SY);
        }
    }

    /** 场上是否还有守卫活着。 */
    private static boolean anyWarden(World w) {
        for (Beast b : w.beasts) if (b.type == Beast.TYPE_BOSS) return true;
        return false;
    }

    /** 首 tick 确定性布点：3 试炼点 + 4 补给箱（同一子流），世界之心置于全图最高峰。 */
    private static void place(World w, Trials tr) {
        SeededRNG r = w.simStream("trial:place");
        int cx = w.SX / 2, cz = w.SZ / 2;
        for (int i = 0; i < ABILITY.length; i++) {
            float ang = r.nextFloat() * (float) (2.0 * Math.PI);
            int sx = clamp((int) Math.floor(cx + StrictMath.cos(ang) * SITE_DIST[i]), 2, w.SX - 3);
            int sz = clamp((int) Math.floor(cz + StrictMath.sin(ang) * SITE_DIST[i]), 2, w.SZ - 3);
            int sy = w.surfaceY[sx][sz] + 1;
            tr.sites.add(new Trials.Site(sx + 0.5f, sy, sz + 0.5f, ABILITY[i], NAME[i]));
            carveGate(w, sx, sz, ABILITY[i]);   // F4：刻地形门（纯几何、零 rng）
        }
        for (int i = 0; i < CACHE_N; i++) {
            float ang = r.nextFloat() * (float) (2.0 * Math.PI);
            int sx = clamp((int) Math.floor(cx + StrictMath.cos(ang) * CACHE_DIST[i]), 2, w.SX - 3);
            int sz = clamp((int) Math.floor(cz + StrictMath.sin(ang) * CACHE_DIST[i]), 2, w.SZ - 3);
            int sy = w.surfaceY[sx][sz] + 1;
            int souls = CACHE_SOUL_MIN + (int) Math.floor(r.nextFloat() * CACHE_SOUL_SPAN);
            tr.caches.add(new Trials.Cache(sx + 0.5f, sy, sz + 0.5f, souls));
        }
        // 世界之心：全图最高峰（surfaceY 最大；并列取最小 x、再最小 z → 确定性）
        int bx = 2, bz = 2, best = Integer.MIN_VALUE;
        for (int x = 2; x < w.SX - 2; x++)
            for (int z = 2; z < w.SZ - 2; z++) {
                int h = w.surfaceY[x][z];
                if (h > best) { best = h; bx = x; bz = z; }
            }
        tr.heartX = bx + 0.5f;
        tr.heartZ = bz + 0.5f;
        tr.heartY = w.surfaceY[bx][bz] + 1;
    }

    /**
     * F4 地形门：在试炼点 (sx,sz) 外围确定性刻一道「需对应能力」的地形障碍。
     * 纯几何、不使用 rng（位置来自 {@link #place} 已确定的 sx,sz），故零漂移。
     * 入口朝向世界中心（玩家从中心探索而来）。逻辑封印（{@link Trials#abilityUsed}）仍作硬门保底。
     */
    private static void carveGate(World w, int sx, int sz, String ability) {
        int gy = w.surfaceY[sx][sz];            // 试炼点地面高度
        int r = 3;                              // 石环半径（切比雪夫）
        int cx = w.SX / 2, cz = w.SZ / 2;
        int dx = Integer.signum(cx - sx);      // 入口方向（指向中心）
        int dz = Integer.signum(cz - sz);
        int ex = sx + dx * r, ez = sz + dz * r; // 入口基准格

        // 1) 石环：以 (sx,sz) 为中心的空心石墙（2 格高），入口留空
        for (int ax = -r; ax <= r; ax++) {
            for (int az = -r; az <= r; az++) {
                if (Math.max(Math.abs(ax), Math.abs(az)) != r) continue;   // 只取最外圈
                int x = sx + ax, z = sz + az;
                if (x == ex && z == ez) continue;                          // 入口留空
                w.setBlock(x, gy + 1, z, Blocks.STONE.index);
                w.setBlock(x, gy + 2, z, Blocks.STONE.index);
            }
        }
        // 2) 入口按能力改写（地形门本体）
        if ("BOMB".equals(ability)) {
            // 石墙封口：玩家须用 bomb 炸开正前方实心方块
            w.setBlock(ex, gy + 1, ez, Blocks.STONE.index);
            w.setBlock(ex, gy + 2, ez, Blocks.STONE.index);
        } else if ("GLIDE".equals(ability)) {
            // 缓降入阶：入口外侧挖 1 格浅阶（2 格长），须缓降而下、踏步而上（STEP_HEIGHT 内，绝不困人）。
            // 逻辑封印保底：即便绕开也取不到遗物。
            for (int k = 1; k <= 2; k++) {
                int px = ex + dx * k, pz = ez + dz * k;
                w.setBlock(px, gy, pz, Blocks.AIR.index);
            }
        } else if ("DASH".equals(ability)) {
            // 窄隙长冲：入口两侧（法线方向）补石墙，夹出 1 格宽通道，逼迫长冲穿越
            int nx = -dz, nz = dx;
            w.setBlock(ex + nx, gy + 1, ez + nz, Blocks.STONE.index);
            w.setBlock(ex + nx, gy + 2, ez + nz, Blocks.STONE.index);
            w.setBlock(ex - nx, gy + 1, ez - nz, Blocks.STONE.index);
            w.setBlock(ex - nx, gy + 2, ez - nz, Blocks.STONE.index);
        }
    }

    private static boolean near(Player p, float x, float y, float z, float r2) {
        float dx = p.x - x, dy = p.y - y, dz = p.z - z;
        return dx * dx + dy * dy + dz * dz <= r2;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
