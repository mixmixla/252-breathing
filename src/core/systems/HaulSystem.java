package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.World;

/**
 * 工业搬运子系统（第 97 个系统）—— 消费预设参数 {@code haulRate}。
 *
 * <p><b>它解决什么</b>：{@code automation} 模块与 {@code palworld_like} 预设写了
 * {@code haulRate}，但此前**背后没有系统**（审计 C8 的 7 个「空转参数」之一）。
 *
 * <p><b>玩法</b>：自动化的第一环 —— 无人搬运。以 {@link #TICKS_PER_HAUL} 为基准节拍，
 * {@code haulRate} 决定每秒搬运次数：随机采样找到一格矿石
 * （{@link Blocks#COAL_ORE} / {@link Blocks#IRON_ORE}），把它搬到村庄中心外的
 * <b>仓储环</b>（半径 5~8 的方环顶面空位），并累加 {@code World.builtMass}。
 *
 * <p><b>有前置</b>：搬运需要电力 —— {@link WireSystem} 必须至少有一个通电节点
 * （即 {@code wireRange > 0} 且灯具连到火源）。这是 {@code automation} 模块把
 * {@code wireRange} 与 {@code haulRate} 放在同一份内容里的原因。
 *
 * <p><b>零漂移</b>：出厂默认 {@code haulRate = 0} → 首行返回，不消费 RNG、不写世界。
 * 启用后写入 {@code mat} / {@code builtMass}（都在 {@code hashState} 内）—— 那是<b>意图</b>，
 * 不是事故；门禁环境永远是默认值，故四道指纹不动。
 */
public final class HaulSystem implements System {

    /** rate=1.0 时的搬运节拍（tick）：20 tick/s → 每秒一次。 */
    public static final int TICKS_PER_HAUL = 20;

    /** 每次搬运的随机采样格数（找不到货源就放弃本次）。 */
    public static final int SAMPLES = 24;

    /** 单 tick 最多搬运次数（防预设把 rate 调得离谱时 tick 失控）。 */
    public static final int MAX_HAULS_PER_TICK = 8;

    /** 定点缩放（避免 float 累加漂移）。 */
    private static final int SCALE = 100;

    /** 累计搬运格数（诊断 / HUD）。 */
    private int carried = 0;
    /** 确定性节奏累加器（定点整数）。 */
    private int budget = 0;

    @Override public String name() { return "haul"; }

    @Override
    public void update(World w, SeededRNG rng) {
        float rate = w.config.haulRate;
        if (rate <= 0f) return;                       // 出厂默认 → 零写入
        if (poweredNodes(w) <= 0) return;             // 无电力 → 不搬运

        budget += Math.round(rate * SCALE);
        int need = TICKS_PER_HAUL * SCALE;
        int cap = need * MAX_HAULS_PER_TICK;
        if (budget > cap) budget = cap;               // 有界：货源断供时不无限累积
        int done = 0;
        while (budget >= need && done < MAX_HAULS_PER_TICK) {
            budget -= need;
            if (!haulOnce(w, rng)) break;
            done++;
        }
    }

    /** 读 WireSystem 的实时通电节点数（同 tick 内 WireSystem 已先跑 —— 注册序保证）。 */
    private int poweredNodes(World w) {
        System s = w.registry.get("wire");
        return (s instanceof WireSystem) ? ((WireSystem) s).poweredCount() : 0;
    }

    /**
     * 搬一格矿石到村心仓储环。返回是否真的搬了。
     *
     * <p>全程走 {@code rng}（simStream("haul")）—— 同种子同 tick 逐字节可复现。
     */
    private boolean haulOnce(World w, SeededRNG rng) {
        int sx = -1, sy = -1, sz = -1;
        for (int i = 0; i < SAMPLES; i++) {
            int x = rng.nextInt(w.SX), y = rng.nextInt(w.SY), z = rng.nextInt(w.SZ);
            int m = w.mat[x][y][z];
            if (m == Blocks.COAL_ORE.index || m == Blocks.IRON_ORE.index) {
                sx = x; sy = y; sz = z;
                break;
            }
        }
        if (sx < 0) return false;                     // 本轮无货源
        int cx = w.SX / 2, cz = w.SZ / 2;
        for (int r = 5; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // 仅方环上格
                    int x = cx + dx, z = cz + dz;
                    if (!w.inBounds(x, 1, z)) continue;
                    // 注意：这是**放方块**的目标高度（列顶 +1），不是实体高度 ——
                    // 实体高度一律走 World.floorY（整片足迹），见审计 C1 的判据。
                    int placeY = w.surfaceY[x][z] + 1;
                    if (placeY <= 0 || placeY >= w.SY) continue;
                    if (w.mat[x][placeY][z] != Blocks.AIR.index) continue;
                    int mat = w.mat[sx][sy][sz];
                    w.setBlock(x, placeY, z, mat);    // 入仓
                    w.setBlock(sx, sy, sz, Blocks.AIR.index);   // 出矿（原地留空腔）
                    w.builtMass += 1f;
                    carried++;
                    if ((carried % 10) == 1) {        // 稀疏记账：事件表不被搬运刷屏
                        w.log("haul", "carried", sx + "," + sy + "," + sz,
                                x + "," + placeY + "," + z + " n=" + carried);
                    }
                    return true;
                }
            }
        }
        return false;                                 // 仓储环满 → 本轮放弃
    }

    public int carried() { return carried; }
}
