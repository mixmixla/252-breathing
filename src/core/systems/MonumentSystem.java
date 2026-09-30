package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 纪念碑（66·monument，文明/社会）：当 w.prosperity 达到 >=8，在村庄中心确定性立一座 SHELTER 高塔
 * （沿中心列向上若干格置 SHELTER，叠在已有结构之上），记 "monument"."built" event + recordMemory。
 * 与 RuinsSystem（>=4 在随机草原建 SHELTER 簇 + 中心 LAMP）区分：本系统是“村心确定性高塔”；
 * 与 WallSystem（>=7 外围石环）区分：本系统是“内部中心地标”。一次性建造（繁荣只升，幂等）。
 * 确定性：随机严格走 simStream 入参 rng；建造位置由中心坐标确定性推导。
 */
public final class MonumentSystem implements System {
    private boolean built = false;   // 一次性建造水印（繁荣只升 → 幂等，确定性）

    @Override public String name() { return "monument"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (built || w.prosperity < 8) return;
        int cx = w.SX / 2, cz = w.SZ / 2;
        int top = AshFallSystem.surfaceY(w, cx, cz);
        if (top < 0) return;
        // 从中心列最高非空气方块之上起建（可叠在灯塔 LAMP 等已有结构之上）
        int y = top + 1;
        while (y < w.SY && w.mat[cx][y][cz] != Blocks.AIR.index) y++;
        if (y >= w.SY) return;
        int h = 5;
        for (int i = 0; i < h; i++) {
            int yy = y + i;
            if (yy >= w.SY) break;
            if (w.mat[cx][yy][cz] == Blocks.AIR.index)
                w.setBlock(cx, yy, cz, Blocks.SHELTER.index);
        }
        built = true;
        w.log("monument", "built", "cx=" + cx + ",cz=" + cz + ",h=" + h, "prosperity=" + w.prosperity);
        w.recordMemory("纪念碑落成：村心立起 " + h + " 格高塔，铭记繁荣=" + w.prosperity);
    }
}
