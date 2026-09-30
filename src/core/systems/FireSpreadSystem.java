package core.systems;

import core.rng.SeededRNG;
import core.world.Beast;
import core.world.Blocks;
import core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 火蔓延（涌现系统样板，移植自 systems/ 的火灾类）。
 * 仅当有 FIRE 方块时激活；向相邻可燃方块（WOOD/LEAF）按 simStream 概率蔓延，自身按概率熄灭。
 * 演示“涌现系统的随机必须走 simStream 子流”这一零漂移纪律。默认无火源 → 惰性，不影响世界。
 *
 * <p><b>P2-2 续：火有牙（双向）。</b>站在火里的<b>敌兵</b>每 tick 受伤（走 {@link core.world.Player#hitBeast}
 * 「唯一一处伤害规则」）；<b>玩家</b>同样会被烧伤，但走 {@link core.world.Player#hurtByHazard}
 * —— 同一条"按伤害来源分流"的规则：火是持续判定、不是敌人出招，不能被 i 帧吞成"完美闪避"。
 * 两段都<b>不消耗 rng</b>（扣血/升级/掉落皆确定性整数运算），故 simStream 序列不变 → 既有基线零漂移；
 * 敌兵/玩家皆实体层，不进 hashState。
 */
public final class FireSpreadSystem implements System {
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};
    /** 火每 tick 对身处火中的敌兵造成的伤害（20Hz → 40 dps；GRUNT 34HP 约 0.85s 烧穿）。 */
    private static final int FIRE_DMG_PER_TICK = 2;

    @Override public String name() { return "fire"; }

    @Override
    public void update(World w, SeededRNG rng) {
        // PERF-SIM：共享空间索引直接取 FIRE 体素（按 x,y,z 升序，等价于全量扫描）；
        // 快照后遍历，本 tick 新点燃的火不计入（与原全扫收集语义一致）。
        List<int[]> sources = new ArrayList<>(w.cellsOfType(Blocks.FIRE.index));
        if (sources.isEmpty()) return;

        for (int[] s : sources) {
            int x = s[0], y = s[1], z = s[2];
            for (int k = 0; k < 6; k++) {
                int nx = x + DX[k], ny = y + DY[k], nz = z + DZ[k];
                if (!w.inBounds(nx, ny, nz)) continue;
                int b = w.mat[nx][ny][nz];
                if ((b == Blocks.WOOD.index || b == Blocks.LEAF.index) && rng.nextDouble() < 0.25) {
                    w.setBlock(nx, ny, nz, Blocks.FIRE.index);   // 蔓延
                }
            }
            if (rng.nextDouble() < 0.5) w.setBlock(x, y, z, Blocks.AIR.index); // 熄灭
        }

        // 火有牙：身处火中的敌兵每 tick 受伤（先快照再结算，避免边遍历边从 w.beasts 移除）。
        if (w.player != null) {
            List<Beast> burning = new ArrayList<>();
            for (Beast b : w.beasts) {
                int bx = (int) Math.floor(b.x), by = (int) Math.floor(b.y), bz = (int) Math.floor(b.z);
                if (w.getBlock(bx, by, bz) == Blocks.FIRE.index
                        || w.getBlock(bx, by + 1, bz) == Blocks.FIRE.index
                        || w.getBlock(bx, by - 1, bz) == Blocks.CAMPFIRE.index) {   // 第七批：踩在篝火上
                    burning.add(b);
                }
            }
            for (Beast b : burning) w.player.hitBeast(w, b, FIRE_DMG_PER_TICK, "burn");

            // 玩家也怕火（双向危险 = 真杠杆，而不是只烧对方的单向武器）。
            // 走 hurtByHazard：i 帧免伤，但**不**误发"完美闪避"奖励（火是持续判定，非读招）。
            int ppx = (int) Math.floor(w.player.x);
            int ppy = (int) Math.floor(w.player.y);
            int ppz = (int) Math.floor(w.player.z);
            // 第七批：篝火是**实心可站**的（不像火那样可穿过），所以"站在篝火上"是脚底<b>下方</b>那格；
            // 站进/站在其上方（被顶到）也一并算 —— 与 FIRE 同一判定口径，只是多一个方向。
            // 门禁世界不含 CAMPFIRE → 新增分支恒 false → 既有伤害行为逐字节不变。
            if (w.getBlock(ppx, ppy, ppz) == Blocks.FIRE.index
                    || w.getBlock(ppx, ppy + 1, ppz) == Blocks.FIRE.index
                    || w.getBlock(ppx, ppy - 1, ppz) == Blocks.CAMPFIRE.index) {
                w.player.hurtByHazard(FIRE_DMG_PER_TICK, w);
            }
        }
    }
}
