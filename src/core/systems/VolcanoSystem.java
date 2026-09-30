package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 火山喷发（81 · 地质/深地）：顶层低频 rng 触发喷发——在地形高处落灰（SNOW 占位落 STONE/SAND 顶）并进入
 * 短暂冷却倒计时（实例字段幂等、确定性递减），记 "volcano"."erupt" event。
 * 与 AshFallSystem（空中火山灰被动沉降：逐列极低概率在任何暴露地表积灰）区分：本系统是“主动喷发源 + 冷却期”，
 * 且只落于高处峰顶一带；与 ClimateSystem 的“火山”（仅气候偏置 + 任意地表落雪）区分：本系统直接落灰块、带自有冷却期与 volcano 事件。
 * 零漂移：随机仅走入参 rng；用 setBlock 演化；冷却倒计时用实例字段随 tick 确定性递减（不读墙钟/静态计数）。
 */
public final class VolcanoSystem implements System {
    private int cooldown = 0;   // 冷却期（幂等，确定性递减；不进 hashState）

    @Override public String name() { return "volcano"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (cooldown > 0) { cooldown--; return; }          // 冷却中，幂等抑制
        if (rng.nextDouble() >= 0.01) return;              // 低频喷发

        // 找最高实心列（确定性全列扫描），确定峰顶带
        int high = -1;
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++)
                for (int y = w.SY - 1; y >= 0; y--)
                    if (Blocks.byIndex(w.mat[x][y][z]).solid) {
                        if (y > high) high = y;
                        break;
                    }

        int fallen = 0;
        if (high >= 0 && high + 1 < w.SY) {
            for (int x = 0; x < w.SX; x++)
                for (int z = 0; z < w.SZ; z++) {
                    int top = surfaceY(w, x, z);
                    if (top < 0) continue;
                    int b = w.mat[x][top][z];
                    if ((b == Blocks.STONE.index || b == Blocks.SAND.index)
                            && top >= high - 1
                            && top + 1 < w.SY
                            && w.mat[x][top + 1][z] == Blocks.AIR.index
                            && rng.nextDouble() < 0.3) {
                        w.setBlock(x, top + 1, z, Blocks.SNOW.index);   // 灰=SNOW 占位落顶
                        fallen++;
                    }
                }
        }
        cooldown = 300;   // 进入冷却幂等期
        w.log("volcano", "erupt", "ash=" + fallen + ",cool=" + cooldown, "erupt");
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
