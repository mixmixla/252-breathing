package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 繁荣灯塔（社会/世界回响）：繁荣≥6 时村庄中心低概率亮起 LAMP（发光地标），
 * 把“村庄繁荣”翻译成可见的夜间光点。沿用 RuinsSystem 的村庄中心模式。确定性。
 */
public final class BeaconSystem implements System {
    @Override public String name() { return "beacon"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.prosperity < 6) return;
        if (rng.nextDouble() >= 0.05) return;
        int cx = w.SX / 2, cz = w.SZ / 2;
        int top = surfaceY(w, cx, cz);
        if (top < 0 || top + 1 >= w.SY) return;   // 树木顶冠可达 SY-1；与 Festival/Wall/Road 等保持一致的边界守卫
        if (w.mat[cx][top + 1][cz] == Blocks.AIR.index) {
            w.setBlock(cx, top + 1, cz, Blocks.LAMP.index);
            w.markDirty(cx, top + 1, cz);
            w.recordMemory("繁荣灯塔：村庄中心亮起 LAMP");
        }
        // D 批：把村庄中心记录为可导航地标。幂等——仅首盏登记一次（beacons 非空即跳过），
        // 与中心格 LAMP 是否持久解耦（窗口外 setBlock 可能被忽略，不能靠“格变 LAMP”去重）。
        // 实体级开放状态，不写网格、不进 hashState，且 rng 行为（前两条 if 的抽数）完全不变 → 零漂移成立。
        if (w.beacons.isEmpty()) {
            w.beacons.add(new World.Beacon(cx + 0.5f, top + 2.0f, cz + 0.5f, "繁荣灯塔"));
        }
    }

    private static int surfaceY(World w, int x, int z) {
        return w.surfaceY[x][z];
    }
}
