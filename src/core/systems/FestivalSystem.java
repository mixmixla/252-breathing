package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 节庆（65·festival，文明/社会）：繁荣过阈（>=3）后按低概率周期记 "festival"."held" 事件，
 * 并在村庄中心附近若干确定性位置点亮 LAMP，表现节庆灯火。不破守恒（只点亮空腔为 LAMP）。
 * 与 BeaconSystem（>=6 仅中心单盏 LAMP）区分：本系统点一圈偏移灯（半径 2）且语义为节庆；
 * 与 MonumentSystem/WallSystem（实体建造）区分：本系统只“点灯”。
 * 确定性：随机严格走 simStream 入参 rng。
 */
public final class FestivalSystem implements System {
    @Override public String name() { return "festival"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.prosperity < 3) return;
        if (rng.nextDouble() >= 1.0 / 240.0) return;   // 低频周期
        int cx = w.SX / 2, cz = w.SZ / 2;
        int[][] pts = {{cx - 2, cz}, {cx + 2, cz}, {cx, cz - 2}, {cx, cz + 2}};
        for (int[] p : pts) {
            int top = AshFallSystem.surfaceY(w, p[0], p[1]);
            if (top < 0 || top + 1 >= w.SY) continue;
            if (w.mat[p[0]][top + 1][p[1]] == Blocks.AIR.index)
                w.setBlock(p[0], top + 1, p[1], Blocks.LAMP.index);
        }
        w.log("festival", "held", "cx=" + cx + ",cz=" + cz, "prosperity=" + w.prosperity);
        w.recordMemory("节庆灯火：村庄中心点起 " + pts.length + " 盏灯，欢庆繁荣=" + w.prosperity);
    }
}
