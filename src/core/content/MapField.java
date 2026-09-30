package core.content;

import core.world.Blocks;
import core.world.World;

/**
 * 小地图的**纯采样模型**（零 GL 依赖）—— 与 {@link ParticleSim} / {@link RainField} 同一套理由：
 * 渲染表现里"是数学的那部分"放 core，才能在无头环境里被门禁完整断言。
 *
 * <p><b>做什么</b>：把玩家周围一片区域的地表俯视图采样成 {@code CELLS × CELLS} 个颜色，
 * 渲染层只管把它们画成小方块。颜色直接用 {@link Blocks.Block} 自带的 {@code r,g,b}
 * （世界本色，不另维护一套调色板 —— 两套必然分叉），再乘一个高度明暗系数让起伏读得出来。
 *
 * <p><b>纯读</b>：只读 {@code mat} / {@code surfaceY}，不写任何状态、不消耗 RNG → 零漂移。
 *
 * <p><b>湖面</b>：列顶若是水（{@code surfaceY+1} 为 WATER），按水色画 ——
 * 否则水面会被画成水底的地色，湖在图上消失。
 */
public final class MapField {

    /** 每边格数。 */
    public static final int CELLS = 24;
    /** 每格像素（渲染层用）。 */
    public static final int CELL_PX = 4;
    /** 每格采样的方块数（2 → 视野 48×48 格）。 */
    public static final int BLOCKS_PER_CELL = 2;
    /** 采样跨度（格）。 */
    public static final int SPAN = CELLS * BLOCKS_PER_CELL;
    /** 渲染边长（像素）。 */
    public static final int PIXELS = CELLS * CELL_PX;
    /** 世界外 / 无地表列的颜色。 */
    public static final int OUT_OF_WORLD = 0x0E1216;

    private MapField() { }

    /**
     * 以 ({@code px},{@code pz}) 为中心采样地表俯视图，写进 {@code out}（长度须 ≥ {@link #CELLS}²）。
     *
     * @param out 每格一个 0xRRGGBB，行序 = 北→南（z 递增），列序 = 西→东（x 递增）
     */
    public static void sample(World w, int px, int pz, int[] out) {
        int half = SPAN / 2, mid = BLOCKS_PER_CELL / 2;
        for (int cz = 0; cz < CELLS; cz++) {
            for (int cx = 0; cx < CELLS; cx++) {
                int bx = px - half + cx * BLOCKS_PER_CELL + mid;
                int bz = pz - half + cz * BLOCKS_PER_CELL + mid;
                out[cz * CELLS + cx] = colorAt(w, bx, bz);
            }
        }
    }

    /** 单格取色：列顶实心方块的本色 × 高度明暗；头顶是水则按水色。越界 → {@link #OUT_OF_WORLD}。 */
    public static int colorAt(World w, int bx, int bz) {
        if (bx < 0 || bz < 0 || bx >= w.SX || bz >= w.SZ) return OUT_OF_WORLD;
        int y = w.surfaceY[bx][bz];
        if (y < 0 || y >= w.SY) return OUT_OF_WORLD;
        int idx = w.mat[bx][y][bz];
        if (y + 1 < w.SY && w.mat[bx][y + 1][bz] == Blocks.WATER.index) idx = Blocks.WATER.index;
        Blocks.Block b = Blocks.byIndex(idx);
        float sh = heightShade(y, w.SY);
        return (clamp((int) (b.r * sh)) << 16) | (clamp((int) (b.g * sh)) << 8) | clamp((int) (b.b * sh));
    }

    /**
     * 高度明暗系数 ∈ [0.55, 1.15]：低处暗、高处亮。
     *
     * <p>为什么需要：只用方块本色的话，同一种石头铺满整张图 → 地形起伏完全看不出来，
     * 小地图就退化成一块色斑。
     */
    public static float heightShade(int y, int sy) {
        float t = (sy <= 1) ? 0f : (float) y / (float) (sy - 1);
        return 0.55f + 0.60f * t;
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
