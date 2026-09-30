package core.systems;

import core.rng.SeededRNG;
import core.world.Chronicle;
import core.world.World;

/**
 * 说书人系统（批次 1 余项 · NPC-SOC-PORT 批次1 收口）：把村庄的转折事件酿成“当日村志”。
 *
 * 忠实移植 Python systems/storyteller.py 的确定性路径：由事件统计 + 记忆回响 + 家族/关系，
 * 拼出“村中说书人回顾这一日见闻”的村志（模板规则、无随机、可断言）。归档与检索逻辑
 * 落在 {@link Chronicle}（对应 systems/village_memory.py 的 VillageMemory）。
 *
 * 定位与零漂移铁律：
 *  - 本系统**只读** events/npcs/social，把结果写进 {@link World#chronicle}（不进 hashState）；
 *  - 绝不写 mat/mass/prosperity/skills/villageMemory，绝不读 world.rng 主状态或 fxRng；
 *  - 不使用任何随机源（全部确定性推导），故四道零漂移门禁指纹与“无本系统”基线逐字节一致。
 *  - 本系统不调用 w.log（避免“归档事件→再产生事件”的回馈环）。
 *
 * 消费端：Game 渲染层读 Chronicle 出 ASCII 摘要上屏 + 写 chronicle.log（中文全文）；
 * NPC 对话（Dialogue.talk）引用最新档案——让“世界因你而变被看见”（CD-PILLARS P1）。
 */
public final class StorytellerSystem implements System {

    /** 出村志的间隔（tick）；每个间隔算“一日”。首 tick 即出一版。 */
    public static final int STORY_INTERVAL = 24;
    /** 说书人署名。 */
    public static final String NARRATOR = "说书人";

    @Override
    public String name() { return "StorytellerSystem"; }

    @Override
    public void update(World w, SeededRNG rng) {
        Chronicle c = w.chronicle;
        if (c == null) return;
        // 每 tick 归档新转折事件（单调水印，通常 0–2 条，廉价）
        c.absorb(w.events, w.tick);
        // 周期（或首 tick）出“当日村志”
        if (w.tick == 1 || w.tick % STORY_INTERVAL == 0) {
            c.days++;
            c.story = c.generateStory(w, NARRATOR);
            c.storyTick = w.tick;
        }
    }
}
