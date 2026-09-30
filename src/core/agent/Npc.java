package core.agent;

import core.rng.SeededRNG;
import core.world.World;

/**
 * L4 NPC 装配体（身体 + 心智 + 社会 + 决策 + 位置）（移植自 agents/npc.py）。
 *
 * 零漂移纪律（与 Beast/Shrine 一致）：
 *  - NPC 是“实体”，绝不写 mat/mass 网格，也不进 {@link World#hashState()} 确定性指纹，
 *    故无论 NPC 如何生成/移动/死亡，都不会改变仿真指纹（四道零漂移门禁不会翻红）；
 *  - 一切随机只走调用方传入的 SeededRNG（来自 world.simStream 派生的确定性子流），
 *    绝不读 world.rng 主状态或 fxRng。
 *
 * 已接入主循环：由 {@code core.systems.NpcSystem}（注册于 {@code Simulation} 的 ENTITY 相位）在首 tick
 * 确定性生成聚落，此后每 tick 驱动 body/mind/social/decision（见 {@code NpcSystem#update} → {@link #decide}）。
 * 亦可作为纯数据容器被 Calamity/Civilization/Individual/Matter/Polity 等系统读取。
 */
public final class Npc {

    public final String id;
    public String name;
    public String profession;
    public String race;
    public final java.util.Map<String, Float> traits;

    public float x, y, z;                  // 体素坐标（3D；y 向上）
    public float prevX, prevY, prevZ;      // 渲染插值：上一 tick 位置（不进 hashState）

    // ---- 村民行走（2026-09-16）：目的地 + 恒定步速 ----
    // 旧实现在 WANDER 分支里每 tick 取一次随机方向（±0.3 格）并叠加回家引力 → 峰值 8.4 格/s 的乱抖，
    // 比玩家 4.5 格/s 还快，看起来是“闪现 / 飞着走”。改为先选目的地、再匀速走过去。
    /** 当前漫步目的地（自家锚点附近；到达后重选）。确定性内部态。 */
    public float wanderX, wanderZ;
    public boolean hasWander = false;

    public final Body body;
    public final Mind mind;
    public final Social social;
    public final java.util.Map<String, Float> inventory = new java.util.HashMap<String, Float>();

    public Decision decision;             // 决策引用（GOAP-lite），可为 null

    public Npc(String id, float x, float y, float z, Body body, Mind mind, Social social) {
        this(id, x, y, z, body, mind, social, null, "villager", "human",
                new java.util.HashMap<String, Float>());
    }

    public Npc(String id, float x, float y, float z, Body body, Mind mind, Social social,
               Decision decision, String profession, String race,
               java.util.Map<String, Float> traits) {
        this.id = id;
        this.x = x; this.y = y; this.z = z;
        this.prevX = x; this.prevY = y; this.prevZ = z;
        this.body = body;
        this.mind = mind;
        this.social = social;
        this.decision = decision;
        this.profession = profession;
        this.race = race;
        this.name = id;
        this.traits = new java.util.HashMap<String, Float>(traits != null ? traits
                : new java.util.HashMap<String, Float>());
    }

    public boolean dead() { return body.dead(); }

    /**
     * 委托决策：返回动作标签（确定性；如需随机只走传入的 rng）。
     * 调用方应传入 world.simStream("npc:" + id + ":" + world.tick) 派生的子流。
     */
    public String decide(World w, SeededRNG rng) {
        return decision != null ? decision.choose(this, w, rng) : Decision.IDLE;
    }
}
