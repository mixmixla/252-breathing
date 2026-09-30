package core.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 村庄级社会状态（批次 1 · 社会核心）：家族注册表 + 村庄情绪 + 社会规范。
 *
 * 忠实移植 Python 三系统：
 *  - systems/family.py   -> families（夫妻 + 后代 id 列表）+ npcSeq（后代 id 计数）
 *  - systems/emotion.py  -> mood/stress/connected/trauma/crash（开放标量情绪景观）
 *  - systems/norms.py    -> trust/cooperation/violations/norm/sanctions（规范涌现）
 *
 * 零漂移纪律（与 npcs/beasts/shrines 同）：本类是“实体级开放标量状态”，
 * 绝不进 {@link core.world.World#hashState()}，也不写 mat/mass/prosperity/skills/villageMemory；
 * 纯确定性推导（不含 rng），故对四道零漂移门禁指纹零影响——指纹仍为 ad9e7b31ed47ec45。
 */
public final class VillageSocial {

    // ---------------------------------------------------------------- 家族（M8）
    /** 一户人家：夫妻 a/b + 已登记后代 id。确定性结构，可序列化（未来存档用）。 */
    public static final class Family {
        public final String id;
        public final String a, b;                       // 夫妻 NPC id
        public final List<String> children = new ArrayList<String>();
        public int nextBirthTick;                       // 下次可生育的最早 tick（冷却）
        public Family(String id, String a, String b, int nextBirthTick) {
            this.id = id; this.a = a; this.b = b; this.nextBirthTick = nextBirthTick;
        }
    }

    /** 家族注册表（确定性顺序：按结为连理时的 id 排序登记）。 */
    public final List<Family> families = new ArrayList<Family>();
    /** 后代 id 计数（n_1, n_2 ...），对应 Python world._npc_seq。 */
    public int npcSeq = 0;

    // ---------------------------------------------------------------- 情绪（M41）
    public float mood = 0.5f;            // 平均心情（0..1）
    public float stress = 0f;            // 压力（0..1）
    public float connected = 0.5f;       // 社会联结（0..1）
    public int trauma = 0;               // 创伤累积（情绪崩塌次数）
    public int crash = 0;                // 情绪低点计数
    public boolean low = false;          // 低迷期上升沿标记（内部用）

    // ---------------------------------------------------------------- 规范（M43）
    public float trust = 0.4f;           // 信任（0..1）
    public float cooperation = 0f;       // 合作度（0..1）
    public int violations = 0;           // 违约计数
    public int sanctions = 0;            // 声誉惩戒次数
    public boolean norm = false;         // 规范是否已涌现

    /** 查询某 NPC 所属人家（无则 null），对应 Python family_of()。 */
    public Family familyOf(String npcId) {
        for (Family f : families) {
            if (f.a.equals(npcId) || f.b.equals(npcId)) return f;
        }
        return null;
    }

    /** 可读快照（供门禁/日志；纯派生，不修改状态）。 */
    public String snapshot() {
        return "fam=" + families.size() + ",seq=" + npcSeq
                + ",mood=" + round3(mood) + ",stress=" + round3(stress)
                + ",conn=" + round3(connected) + ",crash=" + crash
                + ",trust=" + round3(trust) + ",coop=" + round3(cooperation)
                + ",norm=" + norm + ",sanc=" + sanctions;
    }

    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
}
