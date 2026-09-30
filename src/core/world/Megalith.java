package core.world;

/**
 * 巨构（ART_BIBLE.md §9 · 巨物主义 Phase 2）：近场可进入神殿 —— 全局坐标确定性材化进 mat。
 *
 * <p>分层混合 D 方案的近场层：玩家能真正走进去的中庭 / 环墙 / 门洞 / 中央祭坛（「完整但空旷：
 * 神已离去，殿宇仍新」——靠尺度而非破损制造压迫感）。远景 120–220 格的剪影属 Phase 1
 * （render 层 {@code Silhouettes}），两者共用本类放置函数，保证「剪影 ↔ 真身」一一对应。
 *
 * <p><b>零漂移契约（必须保持）</b>：
 * <ul>
 *   <li>只有 {@code SY >= MIN_SY(96)} 的世界才材化 —— 门禁小世界（SY=40）走不到这里，
 *       四道基线指纹逐字节不变（由 {@code MegalithDeterminismTest} 第 17 道门禁断言豁免）；</li>
 *   <li>放置 / 选址 / 几何全部是 (seed, sy, 全局坐标) 的纯函数，<b>不消费任何 RNG 流</b>
 *       （随机性全部来自全局坐标整数哈希 {@link #mh}）；</li>
 *   <li>地形锚定直接调用 {@link World#terrainHeightField}（单一代码源，杜绝双维护；
 *       该方法与 valueNoise/cellHash/smooth 已放宽为包内可见，行为不变）；</li>
 *   <li>玩家编辑照常走 chunkEdits 覆盖（神殿可被挖掘 / 建造，随窗口平移持久化）。</li>
 * </ul>
 *
 * <p><b>选址带推导</b>：水面 = SY/3，地形 h = waterLevel + (n-0.25)·(maxH-waterLevel)；
 * TOWER 神殿总高 48 格（§9.3 近场 44–56 档）→ 要求 gy ≤ SY-58；带非空 ⟺ SY ≥ 90，
 * 取 MIN_SY=96。SY=112 时带 = [39, 54]（约 1/4 陆地合格，再叠加坡度否决）。
 */
public final class Megalith {

    /** 神殿材化的最低世界高度（低于此值只出远景剪影、无真身）。 */
    public static final int MIN_SY = 96;
    /** 剪影让位半径：材化世界中相机距巨构中心近于此值时剪影停画（真身由 chunk 网格接管）。 */
    public static final float SKIP_R = 128f;

    /** 巨构格间距（渲染层剪影装载范围换算用）。 */
    public static int cellSize() { return CELL; }

    private static final int CELL = 384;            // 巨构格间距（§9.6，与 Phase 1 一致）
    private static final float EXIST_P = 0.62f;     // 存在率
    private static final int SLOPE_MAX = 12;        // 坡度否决（footprint 内高差）
    private static final int FOOT_HALF = 40;        // 坡度检测半径（≥ plinth 半宽 36 + 余量）

    /** 出生锚点候选（全局坐标，全部距原点 ≤ 250 格）：按序扫描，第一个合格者胜出（P3「远方有未知」）。 */
    private static final int[][] ORIGIN_CANDIDS = {
        {170, 170}, {120, 200}, {200, 120}, {100, 100}, {80, 170}, {170, 80},
        {60, 140}, {140, 60}, {100, 220}, {220, 100}, {40, 100}, {100, 40}
    };

    /** 巨构描述（全局坐标确定，逐字节可复现）。 */
    public static final class Desc {
        public final int gxc, gzc;      // 中心全局坐标
        public final int variant;       // 0=TOWER 神殿 / 1=GATE 断拱门
        public final int rot;           // 0..3（90° 增量；门朝本地 +X 随 rot 旋转）
        Desc(int gxc, int gzc, int variant, int rot) {
            this.gxc = gxc; this.gzc = gzc; this.variant = variant; this.rot = rot;
        }
    }

    // ---------------------------------------------------------------- 放置（纯函数，剪影/真身共用）

    /**
     * 巨构格 (ccx,ccz) 的巨构描述；不存在 / 选址否决返回 null。
     * @param materialized true=真身材化世界（含天花板选址带）；false=仅剪影世界（无天花板约束）
     */
    public static Desc descAt(World w, int ccx, int ccz, boolean materialized) {
        if (ccx == 0 && ccz == 0) {
            for (int[] c : ORIGIN_CANDIDS) {
                Desc d = tryPlace(w, c[0], c[1], materialized, true);
                if (d != null) return d;
            }
            return null;
        }
        if (mh(ccx, ccz, 0x4D4FL) >= EXIST_P) return null;
        int gxc = ccx * CELL + 64 + (int) (mh(ccx, ccz, 0x4F58L) * (CELL - 128));
        int gzc = ccz * CELL + 64 + (int) (mh(ccx, ccz, 0x4F5AL) * (CELL - 128));
        return tryPlace(w, gxc, gzc, materialized, false);
    }

    private static Desc tryPlace(World w, int gxc, int gzc, boolean materialized, boolean relaxSlope) {
        int sy = w.SY;
        int gy = groundY(w, gxc, gzc);
        if (gy <= sy / 3 + 1) return null;                       // 不落水下 / 滩涂
        if (materialized && gy > sy - 58) return null;           // 天花板带（TOWER 总高 48 + 余量）
        if (!relaxSlope && steep(w, gxc, gzc)) return null;      // 坡度否决（出生锚点豁免）
        int variant = mh(gxc, gzc, 0x5641L) < 0.5f ? 0 : 1;
        int rot = (int) (mh(gxc, gzc, 0x524FL) * 4f) & 3;
        return new Desc(gxc, gzc, variant, rot);
    }

    /** 与 World.generateChunk 的列高公式一致（直接复用 World 地形场 —— 单一代码源）。 */
    public static int groundY(World w, int gx, int gz) {
        double n = w.terrainHeightField(gx, gz);
        int waterLevel = w.SY / 3, maxH = w.SY - 6;
        return Math.max(2, waterLevel + (int) ((n - 0.25) * (maxH - waterLevel)));
    }

    private static boolean steep(World w, int gx, int gz) {
        int c = groundY(w, gx, gz);
        int mn = c, mx = c;
        int[] dd = {-FOOT_HALF, FOOT_HALF};
        for (int dx : dd) {
            int v = groundY(w, gx + dx, gz);
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        for (int dz : dd) {
            int v = groundY(w, gx, gz + dz);
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        return mx - mn > SLOPE_MAX;
    }

    /** 全局坐标整数哈希 → [0,1)（与 Chunk.hash01 同族；纯位置函数，不碰任何 RNG 流）。 */
    private static float mh(int x, int y, long salt) {
        int h = (int) (x * 374761393L + y * 668265263L + salt * 1274126177L);
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return (h & 0x7fffffff) / (float) 0x7fffffff;
    }

    // ---------------------------------------------------------------- 材化（generateChunk 尾部调用）

    /** 把与本 chunk（全局 [baseX,baseX+16) × [baseZ,baseZ+16)）相交的神殿写进 w.mat/mass。
     *  M3①：窗口原点**显式传入** —— 分帧平移期间 {@code winCX0} 尚未提交（仍是旧窗原点），
     *  而正在生成的是新窗块；若沿用 {@code windowOriginCX()} 会算错窗口本地坐标并越界（曾实测 AIOOBE）。 */
    static void fillChunk(World w, int baseX, int baseZ, int originCX, int originCZ) {
        final int R = 56;   // 最大 footprint 半宽（plinth 36 + 碎块带）
        int c0x = Math.floorDiv(baseX - R, CELL), c1x = Math.floorDiv(baseX + World.CHUNK - 1 + R, CELL);
        int c0z = Math.floorDiv(baseZ - R, CELL), c1z = Math.floorDiv(baseZ + World.CHUNK - 1 + R, CELL);
        int winX0 = originCX * World.CHUNK, winZ0 = originCZ * World.CHUNK;
        for (int ccx = c0x; ccx <= c1x; ccx++)
            for (int ccz = c0z; ccz <= c1z; ccz++) {
                Desc d = descAt(w, ccx, ccz, true);
                if (d == null) continue;
                int x0 = Math.max(baseX, d.gxc - R), x1 = Math.min(baseX + World.CHUNK, d.gxc + R + 1);
                int z0 = Math.max(baseZ, d.gzc - R), z1 = Math.min(baseZ + World.CHUNK, d.gzc + R + 1);
                for (int gx = x0; gx < x1; gx++)
                    for (int gz = z0; gz < z1; gz++)
                        writeColumn(w, d, gx - winX0, gz - winZ0, gx - d.gxc, gz - d.gzc);
            }
    }

    /** 单列写入：tlx/tlz=巨构本地坐标（窗口无关），lx/lz=窗口本地坐标（写 mat 用）。 */
    private static void writeColumn(World w, Desc d, int lx, int lz, int tlx, int tlz) {
        int tx = tlx, tz = tlz;
        for (int i = 0; i < d.rot; i++) { int t = tx; tx = tz; tz = -t; }   // rot90: (x,z)→(z,-x)
        int gy = groundY(w, d.gxc, d.gzc) + 1;                              // 地表顶面上一格
        if (d.variant == 0) writeTower(w, d, lx, lz, tx, tz, gy);
        else writeGate(w, d, lx, lz, tx, tz, gy);
        weather(w, d, lx, lz, tx, tz, gy);   // Phase 3：风化层（苔藓侵蚀低处条石）
    }

    /** TOWER · 完整神殿（总高 48）：基座 → 环墙(含门) + 中庭空腔 + 祭坛长明灯 → 顶盖金冠 → 中央塔楼。 */
    private static void writeTower(World w, Desc d, int lx, int lz, int tx, int tz, int gy) {
        int m = Math.max(Math.abs(tx), Math.abs(tz));   // Chebyshev 距离（方形环）
        // 基座三阶（半埋 → 被吞噬暗示，§9.2-5）
        fill(w, lx, lz, gy - 8, gy - 1, Blocks.ASHLAR_SHADE.index);
        if (m < 31) fill(w, lx, lz, gy, gy + 3, Blocks.ASHLAR.index);
        if (m < 25) fill(w, lx, lz, gy + 4, gy + 7, Blocks.ASHLAR_SHADE.index);
        if (m < 21) {
            put(w, lx, gy + 8, lz, Blocks.ASHLAR_SHADE.index);          // 中庭地坪
            if (m < 20) {
                clear(w, lx, lz, gy + 9, gy + 23);                       // 中庭空腔（可进入）
                if (m < 3) {                                             // 中央祭坛 + 长明灯（夜导航）
                    fill(w, lx, lz, gy + 9, gy + 12, Blocks.ASHLAR.index);
                    put(w, lx, gy + 13, lz, Blocks.LAMP.index);
                }
            }
        }
        // 环墙 20..23（11×15 门洞朝本地 +X —— 门洞远大于玩家，§9.2-4 尺度对比）
        if (m >= 20 && m < 24) {
            boolean door = tx >= 20 && tx < 24 && Math.abs(tz) <= 5;
            if (!door) fillSpeckle(w, d, lx, lz, tx, tz, gy + 9, gy + 23, Blocks.ASHLAR.index);
        }
        // 柱列（墙外一圈，模 7 网格 → 重复产生体量，§9.2-2）
        if (m >= 24 && m < 27) {
            if (mod7(tx) == 0 || mod7(tz) == 0)
                fill(w, lx, lz, gy + 9, gy + 23, Blocks.ASHLAR.index);
        }
        // 顶盖 + 金冠带（全场景唯一高饱和暖色，§9.4）
        if (m < 27) {
            fill(w, lx, lz, gy + 24, gy + 26, m < 24 ? Blocks.ASHLAR_SHADE.index : Blocks.ASHLAR.index);
            if (m >= 24) {
                fill(w, lx, lz, gy + 27, gy + 28, Blocks.GOLD.index);
            } else if (m < 11) {
                // 中央塔楼（收分三阶 + 金饰两道）
                fillSpeckle(w, d, lx, lz, tx, tz, gy + 29, gy + 40, Blocks.ASHLAR.index);
                fill(w, lx, lz, gy + 41, gy + 42, Blocks.GOLD.index);
                if (m < 9) fill(w, lx, lz, gy + 43, gy + 44, Blocks.ASHLAR.index);
                if (m < 7) fill(w, lx, lz, gy + 45, gy + 46, Blocks.ASHLAR_SHADE.index);
                if (m < 3) fill(w, lx, lz, gy + 47, gy + 48, Blocks.GOLD.index);
            }
        }
        // 外圈半埋碎块（稀疏点缀）
        if (m >= 38 && m < 50 && mh(d.gxc + tx, gy * 31 + d.gzc + tz, 0x5257L) < 0.10f)
            fill(w, lx, lz, gy - 5, gy - 1, Blocks.ASHLAR_VEIN.index);
        // 倒伏柱段（Phase 3「断裂的跨」的地面回声）：正交两条半埋躺柱，哈希缺口 → 断口参差
        boolean segX = tx >= 40 && tx < 52 && Math.abs(tz) <= 3;
        boolean segZ = tz >= 40 && tz < 52 && Math.abs(tx) <= 3;
        if ((segX || segZ) && mh(d.gxc + tx, gy * 17 + d.gzc + tz, 0x46414CL) < 0.85f)
            fill(w, lx, lz, gy - 3, gy + 2, Blocks.ASHLAR.index);
    }

    /** GATE · 断拱门（总高 ~40）：双柱 + 相向悬臂（中段缺口永留）+ 半埋断龙骨。 */
    private static void writeGate(World w, Desc d, int lx, int lz, int tx, int tz, int gy) {
        int gap = 20 + (int) (mh(d.gxc, d.gzc, 0x4741L) * 20f);          // 缺口 20..39
        int segs = Math.max(1, Math.round(gap * 0.42f / 7f));            // 悬臂段数（保证缺口永存）
        for (int side = -1; side <= 1; side += 2) {
            int pc = side * (gap / 2 + 9);                                // 柱心（本地 x）
            int dxp = tx - pc;
            if (Math.abs(dxp) < 10 && Math.abs(tz) < 10) {
                fillSpeckle(w, d, lx, lz, tx, tz, gy - 8, gy + 18, Blocks.ASHLAR.index);
                if (Math.abs(dxp) < 8 && Math.abs(tz) < 8) fill(w, lx, lz, gy + 19, gy + 30, Blocks.ASHLAR.index);
                if (Math.abs(dxp) < 9 && Math.abs(tz) < 9) fill(w, lx, lz, gy + 31, gy + 33, Blocks.ASHLAR_SHADE.index);
                if (Math.abs(dxp) < 7 && Math.abs(tz) < 7) fill(w, lx, lz, gy + 34, gy + 35, Blocks.GOLD.index);
                if (Math.abs(dxp) < 4 && Math.abs(tz) < 4) fill(w, lx, lz, gy + 36, gy + 39, Blocks.ASHLAR.index);
            }
            // 相向悬臂（断裂的跨，§9.2-3）
            for (int i = 0; i < segs; i++) {
                int r0 = Math.max(3, gap / 2 - (i + 1) * 7), r1 = gap / 2 - i * 7;
                if (Math.abs(tx) >= r0 && Math.abs(tx) < r1 && Math.abs(tz) <= 5)
                    fill(w, lx, lz, gy + 30 + i * 3, gy + 33 + i * 3, Blocks.ASHLAR.index);
            }
            int re = Math.max(3, gap / 2 - segs * 7);
            if (Math.abs(tx) >= re - 2 && Math.abs(tx) < re && Math.abs(tz) <= 4)
                fill(w, lx, lz, gy + 30 + segs * 3 - 4, gy + 30 + segs * 3, Blocks.GOLD.index);
            // 柱脚碎块
            if (Math.abs(dxp - side * 20) < 3 && Math.abs(tz - side * 12) < 4)
                fill(w, lx, lz, gy - 5, gy - 1, Blocks.ASHLAR_VEIN.index);
        }
        // 半埋断龙骨（掉落的拱心石 →「曾经横跨过」的物证）
        if (Math.abs(tx) < 5 && Math.abs(tz) < 5)
            fill(w, lx, lz, gy - 6, gy - 1, Blocks.ASHLAR_VEIN.index);
    }

    // ---------------------------------------------------------------- 写入原语

    private static void put(World w, int lx, int y, int lz, int idx) {
        if (y < 0 || y >= w.SY) return;
        w.mat[lx][y][lz] = idx;
        w.mass[lx][y][lz] = 1f;
    }

    private static void fill(World w, int lx, int lz, int y0, int y1, int idx) {
        for (int y = Math.max(0, y0); y <= Math.min(w.SY - 1, y1); y++) {
            w.mat[lx][y][lz] = idx;
            w.mass[lx][y][lz] = 1f;
        }
    }

    private static void clear(World w, int lx, int lz, int y0, int y1) {
        for (int y = Math.max(0, y0); y <= Math.min(w.SY - 1, y1); y++) {
            w.mat[lx][y][lz] = Blocks.AIR.index;
            w.mass[lx][y][lz] = 0f;
        }
    }

    /** 条石 + 12% 纹石点缀（§9.4 ASHLAR_VEIN 打散白墙；按全局块坐标哈希，窗口无关、确定性）。 */
    private static void fillSpeckle(World w, Desc d, int lx, int lz, int tx, int tz, int y0, int y1, int idx) {
        for (int y = Math.max(0, y0); y <= Math.min(w.SY - 1, y1); y++) {
            boolean vein = mh(d.gxc + tx, y * 131 + d.gzc + tz, 0x5650L) < 0.12f;
            int i = vein ? Blocks.ASHLAR_VEIN.index : idx;
            w.mat[lx][y][lz] = i;
            w.mass[lx][y][lz] = 1f;
        }
    }

    private static int mod7(int v) { return ((v % 7) + 7) % 7; }

    /**
     * Phase 3 · 风化层（§9.2-5「被吞噬」）：低处条石被苔藓侵蚀——愈近地表愈密（5%→25%）。
     * 只侵蚀 ASHLAR 家族（不碰 GOLD/LAMP/AIR）；哈希按全局块坐标 → 确定性、窗口无关。
     */
    private static void weather(World w, Desc d, int lx, int lz, int tx, int tz, int gy) {
        int top = Math.min(w.SY - 1, gy + 8);
        for (int y = Math.max(0, gy - 8); y <= top; y++) {
            int b = w.mat[lx][y][lz];
            if (b != Blocks.ASHLAR.index && b != Blocks.ASHLAR_SHADE.index && b != Blocks.ASHLAR_VEIN.index) continue;
            int depth = gy + 8 - y;
            if (depth <= 0) continue;
            float p = 0.05f + 0.20f * (depth / 16f);
            if (mh(d.gxc + tx, y * 131 + d.gzc + tz, 0x4D4F53L) < p)
                w.mat[lx][y][lz] = Blocks.MOSS.index;
        }
    }
}
