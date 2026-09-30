package core.systems;

import core.agent.Body;
import core.agent.Decision;
import core.agent.Mind;
import core.agent.Npc;
import core.agent.Social;
import core.rng.SeededRNG;
import core.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * NPC 社会层驱动系统（批次 0 · NPC-SOC-WIRE）：把 agents/ 社会认知层接入 World.tick 主循环。
 *
 * 零漂移铁律（与 BeastSystem/ShrineSystem 一致）：
 *  - 只经 {@link World#simStream(String)} 取随机（确定性子流，基于主种子，绝不进 rng 主状态）；
 *  - 绝不动 mat/mass 网格（NPC 是实体，不写方块）；
 *  - 不读 fxRng（演出专用，绝不进仿真）；
 *  - NPC 内部状态（hunger/thirst/mood/affinity/位置）不进 {@link World#hashState()}，故指纹零影响。
 *  - 关键：simStream 由主种子派生子流，不推进主 rng.state()（见 SeededRNG.deriveStream），
 *    因此即便本系统自动生成并驱动 NPC，四门禁指纹与“无 NPC”基线逐字节一致。
 *
 * 行为（最小可驱动闭环，对应 PORTING_GAP 批次 0）：
 *  1) 生成：首 tick（tick==1 且 npcs 空）确定性生成一座小聚落（村民围绕世界中心），
 *           坐标/职业/特质由 simStream("npc:village") 派生，y 贴所在列地表；
 *  2) 驱动：每个存活 NPC 每 tick 执行 body.advance（生理推进）→ mind.update（情感衰减）
 *           → social.decay（亲疏淡忘）→ decision.choose（意图选择）→ 执行意图（进食/饮水/漫步，纯内部态）；
 *  3) 死亡：body 归零的 NPC 从列表移除（聚落可自然消亡；本批次不含重生链）。
 */
public final class NpcSystem implements System {

    /** 聚落规模（确定性常量；首 tick 生成一次）。 */
    public static final int VILLAGE_SIZE = 5;

    /**
     * 村庄半径：村民各自的"宅基地"锚点分布在此半径的环带内（村民不离村）。
     * LD-2026-09-11 修复：原值 8f 且圆心=世界中心=玩家出生点 → 全村 24 口挤在
     * 玩家脚边围成人墙。改为 24f 并给每口人独立锚点（见 {@link #homeAnchor}），
     * 村民散开各归各家、白天"出来干活"，玩家出生广场保留净空。
     * NPC 位置不进 hashState → 本改动对四道基线指纹零影响。
     */
    public static final float VILLAGE_RADIUS = 24f;

    /** 宅基地锚点离村心的最小距离（= 玩家出生广场净空半径）。 */
    public static final float PLAZA_CLEAR = 5f;

    /** 每口人的活动半径：漫步被约束在自家锚点 ± 此值内（散而不乱，仍在村庄半径内）。 */
    public static final float HOME_RADIUS = 3f;

    /**
     * 村民散步步速（每 tick 格数）：0.09 × 20 Hz = **1.8 格/s**。
     * <p>刻度参照：玩家走路 4.5 格/s、冲刺 6.1 格/s、野兽 0.9~3.4 格/s。
     * 村民必须明显慢于玩家 —— 旧版每 tick 随机 ±0.3 格（均值约 4.8 格/s、峰值 8.4 格/s）比玩家还快，
     * 观感是“闪现 / 飞着走”而不是走路。
     */
    public static final float NPC_WALK = 0.09f;

    /** 目的地到达半径（格）：到这个距离就换下一个目的地。 */
    public static final float ARRIVE_R = 0.35f;

    /** 下坡最大下降速率（每 tick 格数）：0.12 × 20 = 2.4 格/s —— 走下坡而不是瞬移贴地。 */
    public static final float NPC_FALL = 0.12f;

    /** 村民身体盒半宽（格）：渲染模型身体 ±0.26、含肩甲/斗篷 ±0.28 → 取 0.30 留边。 */
    public static final float NPC_HW = 0.30f;

    /**
     * 村民净空判定高度（格）：取躯干高度而非模型总高（头顶 2.2）——
     * 否则头顶上方 2 格的树冠会被当成"墙"，把村民整片林子地困住。
     */
    public static final float NPC_H = 1.75f;

    /** 自动上台阶高度：直接别名 {@link World#STEP_HEIGHT}（全项目唯一来源）。 */
    public static final float NPC_STEP = World.STEP_HEIGHT;

    /** 出生落点：优先按"中心列 +1 格台阶"取足迹地面；若身体仍不净空（窄槽 / 仙人掌），退回足迹最高地面。 */
    private float spawnY(World w, float x, float z, float centerY) {
        float y = w.floorY(x, z, NPC_HW, centerY + NPC_STEP);
        if (w.solidBox(x, y + 0.02f, z, NPC_HW, NPC_H)) y = w.floorY(x, z, NPC_HW, (float) w.SY);
        return y;
    }

    /** 位置 (x,y,z) 处村民身体盒是否净空，且在世界可用区内。 */
    private boolean clear(World w, float x, float y, float z) {
        if (x < 2f || x > w.SX - 3f || z < 2f || z > w.SZ - 3f) return false;
        return !w.solidBox(x, y, z, NPC_HW, NPC_H);
    }

    /**
     * 尝试位移 (dx, dz)：整段 → 抬升 1 格台阶重试 → 分轴沿墙滑 → 抬升后分轴。
     * 返回是否至少移动了一点；全部失败 = 真被墙挡住（调用方换目的地）。
     */
    private boolean tryMove(World w, Npc npc, float dx, float dz) {
        if (clear(w, npc.x + dx, npc.y, npc.z + dz)) { npc.x += dx; npc.z += dz; return true; }
        if (clear(w, npc.x + dx, npc.y + NPC_STEP, npc.z + dz)) {
            npc.x += dx; npc.z += dz; npc.y += NPC_STEP; return true;
        }
        boolean moved = false;
        if (dx != 0f && clear(w, npc.x + dx, npc.y, npc.z)) { npc.x += dx; moved = true; }
        if (dz != 0f && clear(w, npc.x, npc.y, npc.z + dz)) { npc.z += dz; moved = true; }
        if (moved) return true;
        if (dx != 0f && clear(w, npc.x + dx, npc.y + NPC_STEP, npc.z)) { npc.x += dx; npc.y += NPC_STEP; return true; }
        if (dz != 0f && clear(w, npc.x, npc.y + NPC_STEP, npc.z + dz)) { npc.z += dz; npc.y += NPC_STEP; return true; }
        return false;
    }

    /**
     * 收尾落回地面：按<b>整片足迹</b>取地面高度。上台阶后 ≤0.05 的余量直接落定；
     * 下坡限速 {@link #NPC_FALL}（不下瞬移，避免"飞着走"）。
     * {@code fromY = npc.y + 0.05} 保证 settle 永远只会"往下补"（抬升只由 tryMove 的台阶重试负责）。
     */
    private void settle(World w, Npc npc) {
        float gy = w.floorY(npc.x, npc.z, NPC_HW, npc.y + 0.05f);
        npc.y = gy >= npc.y ? gy : Math.max(gy, npc.y - NPC_FALL);
        // 防活埋（LD-2026-09-16）：聚落施工 / 地形被改 / 世界滑窗重建，都可能在村民脚下或身上
        // 长出方块。若身体此时真的嵌在 solid 里，就抬到"足迹最高地面"上去 —— 宁可站在顶上，也不埋进去。
        if (w.solidBox(npc.x, npc.y + 0.02f, npc.z, NPC_HW, NPC_H)) {
            npc.y = w.floorY(npc.x, npc.z, NPC_HW, (float) w.SY);
        }
    }


    @Override
    public String name() { return "NpcSystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        spawnVillage(w);                 // 首 tick 确定性生成聚落（幂等：npcs 非空则跳过）
        drive(w);                        // 驱动每个存活 NPC
    }

    /** 生成：仅首 tick 且 npcs 为空时确定性生成一座小聚落（围绕世界中心，贴地表）。 */
    private void spawnVillage(World w) {
        if (!w.npcs.isEmpty()) return;
        if (w.tick != 1) return;         // 仅在首 tick 生成一次，避免流式平移后误重生
        SeededRNG r = w.simStream("npc:village");   // 仅此处取随机，基于主种子，零漂移
        float cx = w.SX / 2f, cz = w.SZ / 2f;
        String[] profs = {"farmer", "crafter", "trader", "herbalist", "guard"};
        for (int i = 0; i < VILLAGE_SIZE; i++) {
            float ang = (float) (r.nextDouble() * (2.0 * Math.PI));
            // 初始落点撒在 PLAZA_CLEAR..VILLAGE_RADIUS 环带上（广场净空，出生点不被围堵）
            float dist = PLAZA_CLEAR + (float) (r.nextDouble() * (VILLAGE_RADIUS - PLAZA_CLEAR));
            float nx = Math.max(2f, Math.min(w.SX - 3f, cx + (float) StrictMath.cos(ang) * dist));
            float nz = Math.max(2f, Math.min(w.SZ - 3f, cz + (float) StrictMath.sin(ang) * dist));
            int ix = (int) Math.floor(nx), iz = (int) Math.floor(nz);
            // QA 2026-09-16：出生落点也按**整片足迹**取地面（旧实现只取中心列 → 一出生就有半个身子埋在坡里）
            float centerY = (w.inBounds(ix, 0, iz) ? w.surfaceY[ix][iz] : 0) + 1f;
            float ny = this.spawnY(w, nx, nz, centerY);
            Body b = new Body();
            Mind m = new Mind();
            Social s = new Social();
            Decision d = new Decision();
            Map<String, Float> traits = new HashMap<String, Float>();
            traits.put("cautious", 0.3f + r.nextFloat() * 0.4f);   // 谨慎度影响恐惧（确定性）
            Npc npc = new Npc("villager" + i, nx, ny, nz, b, m, s, d,
                    profs[i % profs.length], "human", traits);
            npc.prevX = nx; npc.prevY = ny; npc.prevZ = nz;   // 渲染插值初值
            w.spawnNpc(npc);
        }
    }

    /**
     * 每口人的确定性"宅基地"锚点：由 npc.id 派生独立子流（每 tick 重算结果恒定），
     * 落点保证离村心 ≥ PLAZA_CLEAR（玩家出生广场净空）且 ≤ VILLAGE_RADIUS（不离村）。
     * 纯读派生流，不消费 wander 子流、不进 hashState。
     */
    private float[] homeAnchor(World w, Npc npc) {
        SeededRNG r = w.simStream("npc:home:" + npc.id);
        float ang = (float) (r.nextDouble() * (2.0 * Math.PI));
        float dist = PLAZA_CLEAR + (float) (r.nextDouble() * (VILLAGE_RADIUS - PLAZA_CLEAR));
        float cx = w.SX / 2f, cz = w.SZ / 2f;
        float hx = Math.max(2f, Math.min(w.SX - 3f, cx + (float) StrictMath.cos(ang) * dist));
        float hz = Math.max(2f, Math.min(w.SZ - 3f, cz + (float) StrictMath.sin(ang) * dist));
        return new float[]{hx, hz};
    }

    /** 驱动：每个存活 NPC 推进身体/心智/社会 + 选意图 + 执行（纯内部态，不写网格）。 */
    private void drive(World w) {
        // 倒序遍历，便于安全移除死亡 NPC
        for (int i = w.npcs.size() - 1; i >= 0; i--) {
            Npc npc = w.npcs.get(i);
            if (npc.dead()) { w.npcs.remove(i); continue; }
            // 记录上一 tick 位置供渲染插值（纯视觉，不进 hashState）
            npc.prevX = npc.x; npc.prevY = npc.y; npc.prevZ = npc.z;
            Body body = npc.body;
            Mind mind = npc.mind;
            Social social = npc.social;

            // 生理 / 心智 / 社会 确定性推进（均无随机源）
            body.advance(1.0f);
            mind.update();
            social.decay();

            // 意图选择：随机只走 simStream 派生的确定性子流（每 NPC 每 tick 独立）
            SeededRNG r = w.simStream("npc:" + npc.id + ":" + w.tick);
            String act = npc.decide(w, r);

            // 执行意图（仅改 NPC 内部状态，绝不写 mat/mass）
            switch (act) {
                case Decision.EAT:
                    body.eat(40f);
                    mind.remember("ate");
                    break;
                case Decision.DRINK:
                    body.drink(40f);
                    mind.remember("drank");
                    break;
                case Decision.WANDER: {
                    // 村民行走（2026-09-16 重做）：先选一个“自家锚点附近”的目的地，再以恒定步速走过去。
                    //   · 方向只在选目的地那一刻取随机（仍走同一 simStream 子流 → 确定性不变）；
                    //   · 每 tick 位移恒为 NPC_WALK → 观感是散步，且必然慢于玩家；
                    //   · 活动范围 = 自家锚点 ± HOME_RADIUS（村民不离村）。
                    float[] home = homeAnchor(w, npc);
                    if (!npc.hasWander) {
                        double ang = r.nextDouble() * 2.0 * Math.PI;
                        double dist = 1.0 + r.nextDouble() * (HOME_RADIUS - 1.0);
                        npc.wanderX = home[0] + (float) (StrictMath.cos(ang) * dist);
                        npc.wanderZ = home[1] + (float) (StrictMath.sin(ang) * dist);
                        npc.hasWander = true;
                    }
                    float wx = npc.wanderX - npc.x, wz = npc.wanderZ - npc.z;
                    float wl = (float) StrictMath.hypot(wx, wz);
                    if (wl <= ARRIVE_R) {
                        npc.hasWander = false;                              // 到了 → 下一 tick 重选目的地
                    } else {
                        float sw = Math.min(NPC_WALK, wl);                  // 匀速；收尾一步不超过剩余距离
                        // QA 2026-09-16：改成"带身体盒碰撞的位移"。旧实现直接改 x/z（只夹世界边界）、
                        // 高度只取中心列地表 → 身体一侧会埋进相邻的更高方块（截图里"模型一半没了 / 进墙里"），
                        // 并且会径直走进 2 格崖壁。现在抬不动就沿墙滑，全被挡就换目的地。
                        if (!this.tryMove(w, npc, wx / wl * sw, wz / wl * sw)) {
                            npc.hasWander = false;                          // 被墙彻底挡住 → 换个目的地
                        }
                    }
                    this.settle(w, npc);                                    // 按整片足迹落回地面
                    break;
                }
                default:
                    // IDLE / FLEE / TRADE / CRAFT ... 本批次仅维持内部态（FLEE 的恐惧已由 mind 记录）
                    break;
            }
        }
    }
}
