package core.sim;

import core.rng.SeededRNG;
import core.systems.CanopySystem;
import core.systems.FlowerSystem;
import core.systems.IceFormSystem;
import core.systems.MossSpreadSystem;
import core.world.Blocks;
import core.world.World;

/**
 * 收敛性门禁（第四十一批 / 批⑤，2026-09-29）：散布者家族全局上限。
 *
 * <p>背景：LEAF/MOSS/ICE/FLOWER 等"散布者"只增不减（唯一移除者 {@code DecaySystem} 仅清
 * "六邻皆空气"的悬空块）⇒ 长时域下世界被逐步填满（实测 96×48×96 跑 40000 tick：非空气 +17.3% 体积）。
 * 本批给每个类型一个<b>派生式</b>全局上限 = 覆盖分数 × 地表面积（见 {@code World.SPREAD_CAP_*}），
 * 并在各类型<b>全部写入点</b>加"上限判据置于 rng 抽取之后"的守卫。
 *
 * <p>本门禁断言：
 * <ol>
 *   <li><b>CAP_DERIVED</b>：上限随世界尺寸派生（96² 与 64² 得到不同上限），不是写死绝对数；</li>
 *   <li><b>CAP_LAYER1 / CAP_UNSET</b>：各分数与"未设限=-1"符合设计；</li>
 *   <li><b>{TYPE}_RESPECTS_CAP</b>：把类型填到 cap-50，跑系统 → <b>会增长</b>（写入路径真的在跑）
 *       但<b>绝不超过 cap</b>（守卫生效），且恰好停在上限；</li>
 *   <li><b>HEADROOM</b>：spreadHeadroom 语义（满=0，未设限=MAX）；</li>
 *   <li><b>SIM_BOUNDED</b>：真实仿真 4000 tick 后四个类型的计数均 ≤ 上限（端到端回归护栏）。</li>
 * </ol>
 *
 * <p>运行：{@code java -cp out;LIBS core.sim.DisperserCapTest}
 */
public final class DisperserCapTest {
    private static int fails = 0;

    private static void ck(String name, boolean ok, String msg) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + name + (msg.isEmpty() ? "" : "  " + msg));
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        // ---------- 1) 上限派生 + 未设限 ----------
        World w64 = new World(1L, 64, 40, 64);
        World w96 = new World(1L, 96, 40, 96);
        int capLeaf64 = w64.spreadCap(Blocks.LEAF.index);
        int capLeaf96 = w96.spreadCap(Blocks.LEAF.index);
        ck("CAP_DERIVED", capLeaf64 == (int)(2.5f * 64 * 64) && capLeaf96 == (int)(2.5f * 96 * 96),
                "leafCap 64²=" + capLeaf64 + " 96²=" + capLeaf96 + "（随尺寸派生，非写死）");
        ck("CAP_LAYER1", w64.spreadCap(Blocks.MOSS.index) == 64 * 64
                && w64.spreadCap(Blocks.ICE.index) == 64 * 64
                && w64.spreadCap(Blocks.FLOWER.index) == (int)(1.5f * 64 * 64)
                && w64.spreadCap(Blocks.ASH.index) == 2 * 64 * 64,
                "moss/ice=1× flower=1.5× ash=2× leaf=2.5× 地表面积");
        ck("CAP_UNSET", w64.spreadCap(Blocks.STONE.index) == -1 && w64.spreadCap(Blocks.WOOD.index) == -1,
                "未设限类型返回 -1（不干预）");

        // ---------- 2) headroom 语义 ----------
        World h = new World(2L, 64, 40, 64);
        int capH = h.spreadCap(Blocks.LEAF.index);
        fillAir(h, Blocks.LEAF.index, capH, 0);
        ck("HEADROOM_AT_CAP", h.spreadHeadroom(Blocks.LEAF.index) == 0,
                "LEAF=" + h.cellsOfType(Blocks.LEAF.index).size() + " cap=" + capH);
        ck("HEADROOM_UNSET", h.spreadHeadroom(Blocks.STONE.index) == Integer.MAX_VALUE, "未设限=MAX");

        // ---------- 3) 四个类型的写入路径：会增长但绝不超过上限 ----------
        ck("LEAF_RESPECTS_CAP",   microLeaf(), "");
        ck("FLOWER_RESPECTS_CAP", microFlower(), "");
        ck("ICE_RESPECTS_CAP",    microIce(), "");
        ck("MOSS_RESPECTS_CAP",   microMoss(), "");
        ck("ASH_RESPECTS_CAP",    microAsh(), "");

        // ---------- 4) 真实仿真端到端：计数恒 ≤ 上限 ----------
        Simulation sim = new Simulation(20260929L, 64, 40, 64);
        World sw = sim.world;
        for (int t = 0; t < 4000; t++) sw.tick();
        int[] T = { Blocks.LEAF.index, Blocks.FLOWER.index, Blocks.ICE.index, Blocks.MOSS.index, Blocks.ASH.index };
        String[] N = { "LEAF", "FLOWER", "ICE", "MOSS", "ASH" };
        boolean bounded = true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < T.length; i++) {
            int c = sw.cellsOfType(T[i]).size(), cap = sw.spreadCap(T[i]);
            sb.append(N[i]).append('=').append(c).append('/').append(cap).append(' ');
            if (c > cap) bounded = false;
        }
        ck("SIM_BOUNDED", bounded, sb.toString().trim());

        System.out.println(fails == 0 ? "DISPCAP PASS" : "DISPCAP FAIL (" + fails + ")");
        if (fails > 0) System.exit(1);
    }

    // ---------------- 微场景 ----------------
    // 每个：把目标类型填到 cap-50（宿主充足），跑系统若干 update →
    //   期望：计数 > 起始（写入路径在跑）且 ≤ cap（守卫生效）。

    private static boolean microLeaf() {
        World w = new World(7L, 64, 40, 64);
        // 棋盘 y：偶层 WOOD（宿主）、奇层 AIR（可上方长叶）→ 宿主丰富
        for (int y = 0; y < w.SY; y++)
            for (int x = 0; x < w.SX; x++)
                for (int z = 0; z < w.SZ; z++)
                    w.setBlock(x, y, z, (y & 1) == 0 ? Blocks.WOOD.index : Blocks.AIR.index);
        int cap = w.spreadCap(Blocks.LEAF.index);
        fillAir(w, Blocks.LEAF.index, cap - 50, 34);          // 上带铺 LEAF（宿主区 y<34 保留）
        int before = w.cellsOfType(Blocks.LEAF.index).size();
        CanopySystem sys = new CanopySystem();
        for (int t = 0; t < 600; t++) sys.update(w, new SeededRNG(1000L + t));
        int after = w.cellsOfType(Blocks.LEAF.index).size();
        System.out.println("       leaf  before=" + before + " after=" + after + " cap=" + cap);
        return before == cap - 50 && after > before && after <= cap;
    }

    private static boolean microFlower() {
        World w = new World(8L, 64, 40, 64);
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) w.setBlock(x, 1, z, Blocks.GRASS.index);   // 宿主草地
        int cap = w.spreadCap(Blocks.FLOWER.index);
        fillAir(w, Blocks.FLOWER.index, cap - 50, 20);
        int before = w.cellsOfType(Blocks.FLOWER.index).size();
        FlowerSystem sys = new FlowerSystem();
        for (int t = 0; t < 600; t++) sys.update(w, new SeededRNG(2000L + t));
        int after = w.cellsOfType(Blocks.FLOWER.index).size();
        System.out.println("       flower before=" + before + " after=" + after + " cap=" + cap);
        return before == cap - 50 && after > before && after <= cap;
    }

    private static boolean microIce() {
        World w = new World(9L, 64, 40, 64);
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) w.setBlock(x, 1, z, Blocks.WATER.index);   // 水面
        int cap = w.spreadCap(Blocks.ICE.index);
        fillAir(w, Blocks.ICE.index, cap - 50, 20);
        int before = w.cellsOfType(Blocks.ICE.index).size();
        IceFormSystem sys = new IceFormSystem();
        for (int t = 0; t < 600; t++) sys.update(w, new SeededRNG(3000L + t));
        int after = w.cellsOfType(Blocks.ICE.index).size();
        System.out.println("       ice   before=" + before + " after=" + after + " cap=" + cap);
        return before == cap - 50 && after > before && after <= cap;
    }

    private static boolean microMoss() {
        World w = new World(10L, 64, 40, 64);
        for (int y = 0; y < w.SY; y++)
            for (int x = 0; x < w.SX; x++)
                for (int z = 0; z < w.SZ; z++) w.setBlock(x, y, z, Blocks.STONE.index);
        int cap = w.spreadCap(Blocks.MOSS.index);
        // 散布式放 MOSS（彼此隔离 ⇒ 每个都有 STONE 邻 → MossSpread 有活干）
        int c = 0;
        for (int y = 0; y < w.SY && c < cap - 50; y++)
            for (int x = 0; x < w.SX && c < cap - 50; x++)
                for (int z = 0; z < w.SZ && c < cap - 50; z++)
                    if (((x + y * 7 + z * 13) % 40) == 0) { w.setBlock(x, y, z, Blocks.MOSS.index); c++; }
        int before = w.cellsOfType(Blocks.MOSS.index).size();
        MossSpreadSystem sys = new MossSpreadSystem();
        for (int t = 0; t < 8000; t++) sys.update(w, new SeededRNG(4000L + t));
        int after = w.cellsOfType(Blocks.MOSS.index).size();
        System.out.println("       moss  before=" + before + " after=" + after + " cap=" + cap);
        return before == cap - 50 && after > before && after <= cap;
    }

    private static boolean microAsh() {
        World w = new World(11L, 64, 40, 64);
        for (int x = 0; x < w.SX; x++)
            for (int z = 0; z < w.SZ; z++) w.setBlock(x, 0, z, Blocks.STONE.index);   // 平地：石底 + 上方空
        int cap = w.spreadCap(Blocks.ASH.index);
        // 只在 x<16 区域铺灰到 cap-50，留 x>=16 的空列给 AshFallSystem 长（否则每列都被"一层"钉住）
        int c = 0;
        for (int y = 20; y < w.SY && c < cap - 50; y++)
            for (int x = 0; x < 16 && c < cap - 50; x++)
                for (int z = 0; z < w.SZ && c < cap - 50; z++) { w.setBlock(x, y, z, Blocks.ASH.index); c++; }
        int before = w.cellsOfType(Blocks.ASH.index).size();
        core.systems.AshFallSystem sys = new core.systems.AshFallSystem();
        for (int t = 0; t < 200; t++) sys.update(w, new SeededRNG(5000L + t));
        int after = w.cellsOfType(Blocks.ASH.index).size();
        System.out.println("       ash   before=" + before + " after=" + after + " cap=" + cap);
        return before == cap - 50 && after > before && after <= cap;
    }

    /** 从 y0 起逐层把 AIR 格填成 idx，至多 n 个（优先取上层，避免破坏低层宿主）。 */
    private static void fillAir(World w, int idx, int n, int y0) {
        int c = 0;
        for (int y = y0; y < w.SY && c < n; y++)
            for (int x = 0; x < w.SX && c < n; x++)
                for (int z = 0; z < w.SZ && c < n; z++)
                    if (w.mat[x][y][z] == Blocks.AIR.index) { w.setBlock(x, y, z, idx); c++; }
    }
}
