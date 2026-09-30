package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 熔炉系统（功能方块，第三批「源源不断」2026-09-23）：矿石 → 锭 的**确定性**冶炼。
 *
 * <p><b>它解决什么</b>：第三批引入了首批"有内部状态"的方块。门的开关是瞬时动作（走玩家 USE），
 * 但<b>"冶炼"本质上是跨 tick 的过程</b> —— 它属于仿真演化，故必须落在系统层，而不是渲染层按键里。
 * 本系统就是这条过程：熔炉每 {@link #SMELT_TICKS} tick 把相邻的**矿石 + 燃料**各消耗一格，
 * 产出对应的**锭**记入 {@code furnaceStore[cell]}（玩家用 USE 取走）—— 复用既有冶炼链的产物
 * （{@code copper_ore → copper_bar} 等，见 {@code RecipeBook} / {@code items/*.json}）。
 *
 * <p><b>规则</b>（完全计数驱动，零 RNG）：
 * <ol>
 *   <li>熔炉格六邻域内需同时有<b>矿石</b>与<b>燃料</b>；缺任一个 → 熄火（进度清零，不写网格）；</li>
 *   <li>两者齐备 → 进度 +1（存 {@code meta[cell]}）；</li>
 *   <li>进度达 {@link #SMELT_TICKS} → 消耗相邻 1 矿石 + 1 燃料（置 AIR，走 setBlock）→ 出 1 锭 → 进度归零。</li>
 * </ol>
 * 锭的类型由**完成时相邻矿石的类型**决定（矿被消耗前一直在场，故无需跨 tick 记住类型）。
 *
 * <p><b>确定性纪律</b>：只迭代 {@link World#cellsOfType} 的**升序**快照（与 CropSystem 同纪律）；
 * 邻域按固定偏移序扫描（首个命中即用）→ 选择确定；进度用整数计数，**不消费任何 RNG**；
 * 只用 {@code setBlock}/{@code setMeta}/{@code ensureFurnace} 写世界。旧世界（含门禁世界）
 * **不含任何熔炉** → 首行收集为空即返回 → 对初始 simHash 与四道仿真指纹**零影响**。
 */
public final class FurnaceSystem implements System {

    /** 冶炼周期（tick）：20 tick/s → 2 秒一锭。 */
    public static final int SMELT_TICKS = 40;

    /** 六邻域固定偏移（顺序即优先级：先水平，再竖直）。 */
    private static final int[] OFF = {0, 0, 1, 0, 0, -1, 1, 0, 0, -1, 0, 0, 0, -1, 0, 0, 1, 0};

    /** 可冶炼矿石（方块索引）→ 产物锭（物品 id）。EMERALD_ORE 出宝石而非锭。 */
    private static final int[] ORE_BLOCK = {
            Blocks.COPPER_ORE.index, Blocks.TIN_ORE.index, Blocks.GOLD_ORE.index,
            Blocks.SILVER_ORE.index, Blocks.LEAD_ORE.index, Blocks.IRON_ORE.index,
            Blocks.EMERALD_ORE.index,
    };
    private static final String[] ORE_ITEM = {
            "copper_bar", "tin_bar", "gold_bar", "silver_bar", "lead_bar", "iron_bar", "emerald",
    };

    /** 燃料（每出一锭消耗一格）。 */
    private static final int[] FUELS = { Blocks.WOOD.index, Blocks.COAL_ORE.index };

    @Override public String name() { return "furnace"; }

    @Override
    public void update(World w, SeededRNG rng) {
        List<int[]> furnaces = new ArrayList<int[]>(w.cellsOfType(Blocks.FURNACE.index));
        if (furnaces.isEmpty()) return;                    // 无熔炉 → 首行返回（零漂移，且零 RNG 消费）
        for (int[] c : furnaces) {
            int x = c[0], y = c[1], z = c[2];
            int oreAt = -1, fuelAt = -1;
            for (int k = 0; k < OFF.length; k += 3) {
                int b = w.getBlock(x + OFF[k], y + OFF[k + 1], z + OFF[k + 2]);
                if (oreAt < 0 && oreItem(b) != null) oreAt = k;
                if (fuelAt < 0 && isFuel(b)) fuelAt = k;
            }
            if (oreAt < 0 || fuelAt < 0) { w.setMeta(x, y, z, 0); continue; }   // 缺矿或缺燃料 → 熄火
            int prog = w.getMeta(x, y, z) + 1;
            if (prog < SMELT_TICKS) { w.setMeta(x, y, z, prog); continue; }      // 过程中
            // 一个周期到：完成时按相邻矿石类型定产物
            int oreB = w.getBlock(x + OFF[oreAt], y + OFF[oreAt + 1], z + OFF[oreAt + 2]);
            String bar = oreItem(oreB);
            w.setBlock(x + OFF[oreAt], y + OFF[oreAt + 1], z + OFF[oreAt + 2], Blocks.AIR.index);      // 消耗矿石
            w.setBlock(x + OFF[fuelAt], y + OFF[fuelAt + 1], z + OFF[fuelAt + 2], Blocks.AIR.index);   // 消耗燃料
            LinkedHashMap<String, Integer> out = w.ensureFurnace(World.cellKey(x, y, z));             // 出锭（待玩家 USE 取走）
            Integer cur = out.get(bar);
            out.put(bar, Integer.valueOf((cur == null ? 0 : cur.intValue()) + 1));
            w.setMeta(x, y, z, 0);
        }
    }

    /** 矿石方块 → 产物物品 id（非矿石返回 null）。 */
    private static String oreItem(int b) {
        for (int i = 0; i < ORE_BLOCK.length; i++) if (ORE_BLOCK[i] == b) return ORE_ITEM[i];
        return null;
    }
    private static boolean isFuel(int b) { for (int f : FUELS) if (f == b) return true; return false; }
}
