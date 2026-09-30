package core.systems;

import core.rng.SeededRNG;
import core.world.World;

/**
 * 世界回响 / 繁荣链（移植自 P0-2：combat.repel → prosperity 过阈解锁蓝图 → village_memory）。
 * 签名系统：验证“玩家改变世界”在 Java 版依然成立且确定。
 *
 * 阈值（对齐 skills.json 的 _blueprint_palisade/watchtower）：
 *   prosperity >= 1 → 解锁木栅；>= 3 → 解锁望楼。幂等。
 */
public final class ProsperitySystem implements System {
    @Override public String name() { return "prosperity"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // 按水印取本 tick 新事件（不含 ts，不破坏指纹）
        for (int i = w.eventsProcessed; i < w.events.size(); i++) {
            World.Event e = w.events.get(i);
            if (e.system.equals("combat") && e.action.equals("repel")) {
                w.prosperity++;
            }
        }
        w.eventsProcessed = w.events.size();

        if (w.prosperity >= 1 && !w.hasSkill("_blueprint_palisade")) {
            w.addSkill("_blueprint_palisade");
            w.recordMemory("繁荣+：木栅蓝图已解锁");
        }
        if (w.prosperity >= 3 && !w.hasSkill("_blueprint_watchtower")) {
            w.addSkill("_blueprint_watchtower");
            w.recordMemory("繁荣++：望楼蓝图已解锁");
        }
    }
}
