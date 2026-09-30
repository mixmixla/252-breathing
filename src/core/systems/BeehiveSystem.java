package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 蜂巢迹象（生物）：野花密集处（FLOWER 邻花）偶尔生成“蜂巢”迹象，复用 LEAF 占位（花上小蜂巢），
 * 与 PollinateSystem（野花散播）形成“花↔蜂”耦合。随机走 simStream。
 */
public final class BeehiveSystem implements System {
    @Override public String name() { return "beehive"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), z = rng.nextInt(w.SZ);
            int top = AshFallSystem.surfaceY(w, x, z);
            if (top < 0 || top + 1 >= w.SY) continue;
            // 花位于草面之上一格（FlowerSystem 在 top+1 落花），故在 top+1 读源花
            if (w.mat[x][top + 1][z] != Blocks.FLOWER.index) continue;
            int f = 0;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    //  ⚠️ 第十二批修掉第 2 个潜伏 bug：原先是 `getBlock(..., top, ...)` —— 但花长在 **top+1**
                    //  （FlowerSystem 落花在 top+1），读地面层只会读到草 ⇒ `f>=3` **永远不成立** ⇒
                    //  与"放置条件互斥"叠加，这个系统双重不可能产出。改为读 top+1（与源花同一层）。
                    if (w.getBlock(x + dx, top + 1, z + dz) == Blocks.FLOWER.index) f++;
                }
            if (f >= 3 && rng.nextDouble() < 0.02) {
                //  ⚠️ 第十二批顺手修掉一个**本来就存在**的潜伏 bug（不是本批引入的）：
                //   原先这里是 "if (top+1 是 AIR) 落块 else 记记忆"，可**前置判定**要求 top+1 必须是 FLOWER
                //   —— 两个条件**互斥** ⇒ 永远走 else 分支 ⇒ 这个系统自建立起**从来没真正长出过蜂巢**
                //   （只写了记忆与日志，方块一个没落）。这类 bug 的隐蔽性在于：日志/记忆都在，看不出异常。
                //   修正：蜂巢落在**花的上方一格**（top+2），与类文档"花上小蜂巢"一致。
                if (top + 2 < w.SY && w.mat[x][top + 2][z] == Blocks.AIR.index)
                    w.setBlock(x, top + 2, z, Blocks.BEEHIVE.index); // 花上蜂巢（第十二批：改用 BEEHIVE 方块）
                else
                    w.recordMemory("蜂群迹象: x=" + x + ",z=" + z);
                w.log("beehive", "sign", "x=" + x + ",z=" + z, "flowers=" + f);
            }
        }
    }
}
