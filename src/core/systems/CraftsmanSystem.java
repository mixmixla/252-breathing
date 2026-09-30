package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

import java.util.HashSet;
import java.util.Set;

/**
 * 智能工匠 NPC（Agent 反哺游戏 · 会呼吸的世界 Java 版）。
 *
 * 闭环：每 tick 观察世界状态（繁荣 / 玩家建造 / village_memory），当繁荣越过一个
 * 它尚未动手的阈值时，工匠「合成」一座小型工艺结构体并落到确定性位置（优先贴着玩家
 * 已建格 builtCells，否则落在村心附近），再 recordMemory 一条续写叙述。
 *
 * 确定性纪律（硬约束）：
 *  - 任何随机（选工艺种类）只走 world.simStream("craftsman:" + prosperity)；
 *    该流 = rng.deriveStream(...)，与主 RNG/渲染 fxRng 物理隔离，逐字节可复现。
 *  - 阈值追踪用确定性字段（acted 集合 + lastCraftedProsperity）。繁荣只升不降，
 *    故同一阈值只动手一次，且两次同种子运行必然一致。
 *  - 绝不使用 Math.random() / fxRng / 墙钟 / System.nanoTime()。
 *
 * 放置策略：用 setBlock（仿真写入，不污染 editedChunks）。工艺体不需要跨卸载持久化——
 * 它会在「阈值重新成立」时被确定性重派。详见末尾 caveat。
 */
public final class CraftsmanSystem implements System {

    /** 已动手过的繁荣阈值（繁荣只升不降 → 幂等，确定性）。 */
    private final Set<Integer> acted = new HashSet<>();
    /** 上一次动手时的繁荣值，作为兜底水印。 */
    private int lastCraftedProsperity = 0;

    @Override
    public String name() { return "craftsman"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int p = w.prosperity;
        // 阈值越过且尚未动手 → 触发一次工匠合成
        if (p <= lastCraftedProsperity) return;
        if (acted.contains(p)) return;

        // 选取工艺种类（唯一随机源：仿真子流，绑定当前繁荣值 → 确定性）
        SeededRNG craft = w.simStream("craftsman:" + p);
        int variant = craft.nextInt(3);   // 0 工坊 / 1 锻炉 / 2 摊棚

        // 决定落点：优先贴玩家已建格，否则村心附近确定性偏移（按繁荣散开成小聚落）
        int bx, bz;
        if (!w.builtCells.isEmpty()) {
            // 取第一个玩家已建格（LinkedHashMap 有序 → 确定）
            long key = w.builtCells.keySet().iterator().next();
            bx = (int) ((key >> 20) & 0x3FF);
            bz = (int) (key & 0x3FF);
            bx = bx + 1; bz = bz + 1;     // 紧邻其旁
        } else {
            int cx = w.SX / 2, cz = w.SZ / 2;
            bx = cx + ((p * 3) % 14) - 7; // 村心四周确定性铺开
            bz = cz + ((p * 5) % 14) - 7;
        }
        // 避让 BeaconSystem 占用的村心正中央格（避免把它推过数组上界导致越界）
        int ccx = w.SX / 2, ccz = w.SZ / 2;
        if (bx == ccx && bz == ccz) { bx = ccx + 1; bz = ccz; }
        if (!w.inBounds(bx, 1, bz) || !w.inBounds(bx + 1, 1, bz + 1)) return;

        int top = SnowCapSystem.surfaceY(w, bx, bz);
        if (top < 0 || top + 3 >= w.SY) return;   // 留足余量：最高只放到 top+2 <= SY-2
        int base = top + 1;

        // 合成小型工艺结构体（少量方块，有界）
        String line;
        switch (variant) {
            case 0: // 工坊：木基 + 中心暖灯 + 立柱
                w.setBlock(bx,     base, bz,     Blocks.WOOD.index);
                w.setBlock(bx + 1, base, bz,     Blocks.WOOD.index);
                w.setBlock(bx,     base, bz + 1, Blocks.WOOD.index);
                w.setBlock(bx + 1, base, bz + 1, Blocks.LAMP.index);
                w.setBlock(bx,     base + 1, bz, Blocks.WOOD.index);
                line = "工匠依你击退之威，于村心立起一座工坊";
                break;
            case 1: // 锻炉：石基 + 暖炉 + 石柱
                w.setBlock(bx,     base, bz,     Blocks.STONE.index);
                w.setBlock(bx + 1, base, bz,     Blocks.STONE.index);
                w.setBlock(bx,     base, bz + 1, Blocks.STONE.index);
                w.setBlock(bx + 1, base, bz + 1, Blocks.LAMP.index);
                w.setBlock(bx + 1, base + 1, bz + 1, Blocks.STONE.index);
                line = "工匠采石为基，在村心搭起一座锻炉";
                break;
            default: // 摊棚：木架 + 叶顶 + 暖灯
                w.setBlock(bx,     base, bz,     Blocks.WOOD.index);
                w.setBlock(bx + 1, base, bz,     Blocks.WOOD.index);
                w.setBlock(bx,     base, bz + 1, Blocks.LEAF.index);
                w.setBlock(bx + 1, base, bz + 1, Blocks.LEAF.index);
                w.setBlock(bx,     base + 1, bz, Blocks.LAMP.index);
                line = "工匠伐木成坊，于村心筑起一座摊棚";
                break;
        }

        w.recordMemory(line + "（繁荣 " + p + "）");

        // 标记已动手，保证幂等（繁荣只升 → 同阈值永不再触发）
        acted.add(p);
        lastCraftedProsperity = p;
    }
}
