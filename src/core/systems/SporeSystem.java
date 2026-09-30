package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 孢子散播（58 · spore，生物/种群）：MUSHROOM/MOSS（真菌/苔藓占位均为 LEAF 或 MOSS）上方为空腔时，
 * 低概率(rng)向空中散播孢子、在上方空腔长新的 LEAF（真菌占位），表现菌类繁殖扩散。
 * 与 MushroomSystem 区分：MushroomSystem 仅在“封闭暗穴(STONE≥5 面夹住)"初次长真菌；
 * 本系统在已有真菌/苔藓上方、开放空腔中继续向上散播，是“同源但独立触发”的扩散阶段，触发条件互补。
 * 随机严格走 simStream 入参 rng。
 */
public final class SporeSystem implements System {
    @Override public String name() { return "spore"; }

    @Override
    public void update(World w, SeededRNG rng) {
        int samples = 16;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            int b = w.mat[x][y][z];
            //  第十二批：真菌的实体是 MUSHROOM（MushroomSystem 同期改用它），孢子囊是 SPORE_POD。
            if (b != Blocks.MUSHROOM.index && b != Blocks.MOSS.index
                    && b != Blocks.SPORE_POD.index) continue;
            if (y + 1 >= w.SY) continue;
            if (w.mat[x][y + 1][z] != Blocks.AIR.index) continue;  // 向空中散播
            if (rng.nextDouble() < 0.03) {
                w.setBlock(x, y + 1, z, Blocks.SPORE_POD.index);   // 孢子落下长孢子囊（第十二批）
                w.log("spore", "disperse", "x=" + x + ",z=" + z, "air");
            }
        }
    }
}
