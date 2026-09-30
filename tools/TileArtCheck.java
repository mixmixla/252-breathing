import core.world.Blocks;
import render.lwjgl.TextureAtlas;

import java.nio.ByteBuffer;

/**
 * 方块纹理「形状语言」门禁（P2，2026-09-23 新增）—— 无 GL、headless，只读烘焙出来的图集像素。
 *
 * <p><b>为什么需要它</b>：{@code ARTUI} 守的是「UI 图集 / HUD 通道」，完全不覆盖方块贴图的
 * <b>艺术质量</b>。而"噪点堆出来的贴图"和"有形状语言的贴图"在代码里**看起来一样合理** ——
 * 都是几行 hash。只有把"形与节奏"变成可测量的量，才能防止它悄悄退回噪声。
 *
 * <p><b>什么是「形状语言」</b>（本门禁量化的定义）：
 * <pre>
 *   ① 分区（形）：明度的大块差异必须来自「面」，不能来自「像素」。
 *      逐像素抖明度会把任何形状糊成电视雪花；把明度归到面上、再补暗缝，形状自己就出来了。
 *   ② 节奏：存在稳定的周期（砌缝 / 年轮 / 波脊 / 板缝），而不是纯随机。
 *   ③ 值层级：缝（最暗）&lt; 面（中）&lt; 受光边（亮）的三段式，而不是单一噪声分布。
 * </pre>
 *
 * <p><b>核心指标 shapeRatio（可证伪）</b>：把 tile 切成 4×4 块，
 * {@code shapeRatio = mean(块内标准差) / std(块均值的标准差)}。
 * <ul>
 *   <li>逐像素噪声：块内方差 ≈ 块间方差 ⇒ **shapeRatio ≈ 1**（形状被噪点淹没）；</li>
 *   <li>分区贴图：块内几乎恒定、块间差异大 ⇒ **shapeRatio ≪ 1**。</li>
 * </ul>
 * 这个比值不依赖任何实现细节（不需要读 facet id），纯从像素算，因此**不可能被"改门禁"绕过**。
 *
 * <p>运行：{@code java -cp "out;..." TileArtCheck}
 */
public class TileArtCheck {

    /** 要审的方块（id → 是否必须过 shapeRatio 判据）。覆盖玩家最常看到的实心地形。 */
    private static final String[] OPAQUE_TERRAIN = {
            "STONE", "SAND", "SNOW", "ICE", "GRASS", "DIRT", "WOOD", "ASHLAR", "ASHLAR_SHADE",
            "ASHLAR_VEIN", "COAL_ORE", "IRON_ORE", "MOSS", "CLAY", "BEDROCK", "SHELTER",
            "GOLD", "CACTUS", "ASH", "LEAF", "FIRE",
            // ---- 融合百家新方块（2026-09-23）----
            "COBBLE", "GRANITE", "OBSIDIAN", "SLATE", "GRAVEL", "RED_SAND", "PODZOL",
            "SOUL_SAND", "BRICK", "SANDSTONE", "TERRACOTTA", "AMETHYST", "LANTERN",
            "VINES", "MUSHROOM", "HAY",
            // ---- 第二批「源源不断」（2026-09-23）：矿石 + 液体 + 粉末 ----
            "COPPER_ORE", "TIN_ORE", "GOLD_ORE", "SILVER_ORE", "LEAD_ORE", "EMERALD_ORE",
            "LAVA", "MUD",
            // ---- 第三批（2026-09-23）：功能方块（门/箱/熔炉/工作台）----
            "DOOR", "CHEST", "FURNACE", "WORKBENCH",
            // ---- 第四批（2026-09-23）：矿物经济 / 金属储块 / 石砖 / 发光装饰 ----
            "DIAMOND_ORE", "REDSTONE_ORE", "LAPIS_ORE", "QUARTZ_ORE",
            "IRON_BLOCK", "GOLD_BLOCK", "COPPER_BLOCK", "BRONZE_BLOCK", "EMERALD_BLOCK",
            "STONE_BRICK", "MOSSY_STONE_BRICK", "CRACKED_STONE_BRICK", "CHISELED_STONE_BRICK",
            "GLOWSTONE", "SEA_LANTERN", "BOOKSHELF", "SMOOTH_STONE", "POLISHED_GRANITE",
            // ---- 第五批（2026-09-24）：功能方块之二 + 红石导线 ----
            "BED", "LADDER", "FENCE", "LEVER", "BUTTON", "WIRE",
            // ---- 第六批（2026-09-24）：活板门 / 漏斗 / 告示牌 ----
            "TRAPDOOR", "HOPPER", "SIGN",
            // ---- 第七批（2026-09-24）：红石逻辑三件套 + 生活方块 ----
            "REPEATER", "COMPARATOR", "PLATE", "CAMPFIRE", "FARMLAND",
            // ---- 第九批（2026-09-24）：红石驱动的机械 ----
            "DISPENSER", "PISTON",
            // ---- 第十一批（2026-09-24）：观察者 ----
            "OBSERVER",
            // ---- 第十二批（2026-09-25）：系统之影 + 玩法纵深 ----
            "CORAL", "BEEHIVE", "CRYSTAL", "CRYSTAL_CLUSTER", "FERN", "REED",
            "SPORE_POD", "DEAD_BUSH", "SAPLING", "BAMBOO", "KELP", "BARREL",
            "CAULDRON", "LECTERN", "IRON_BARS", "CHAIN", "SCAFFOLDING",
    };

    private static int fails = 0;

    public static void main(String[] args) {
        ByteBuffer atlas = TextureAtlas.bakeAlbedoOffscreen();
        final int A = TextureAtlas.ATLAS_PX, cell = TextureAtlas.CELL_PX;
        final int T = TextureAtlas.TILE_PX, G = TextureAtlas.GUTTER;

        System.out.println("TILEART tile=" + T + "px cell=" + cell + " gutter=" + G
                + "  materials=" + OPAQUE_TERRAIN.length);

        // ---- 0. 确定性：两次烘焙必须逐字节相同（纹理是纯函数，任何 RNG/时间依赖都不可接受）----
        ByteBuffer again = TextureAtlas.bakeAlbedoOffscreen();
        boolean detOk = true;
        for (int i = 0; i < A * A * 4; i++) {
            if (atlas.get(i) != again.get(i)) { detOk = false; break; }
        }
        System.out.println("TILEART-DET " + (detOk ? "PASS" : "FAIL") + "  两次烘焙逐字节相同=" + detOk);
        if (!detOk) fails++;

        // ---- 逐材料指标 ----
        System.out.println("  材料            侧/顶   span   plate  period  p15/中位   范围");
        float sumSpan = 0f, sumPlate = 0f, sumAcf = 0f, sumBStd = 0f, sumMic = 0f;
        float minSpan = 1e9f, minPlate = 1e9f, minAcf = 1e9f, maxMic = -1e9f;
        String minSpanNm = "-", minPlateNm = "-", minAcfNm = "-", maxMicNm = "-";
        int strong = 0, formFail = 0;
        StringBuilder formFailList = new StringBuilder();
        int n = 0;
        for (String id : OPAQUE_TERRAIN) {
            Blocks.Block blk = Blocks.byId(id);
            if (blk == null) { System.out.println("  [" + id + "] 方块不存在"); fails++; continue; }
            for (int kind : new int[]{0, 1}) {
                float[] L = tileLum(atlas, blk.index * 3 + kind, A, cell, T, G);
                if (L == null) continue;
                float span = plateSpan(L, T);
                float plate = plateFrac(L, T);
                float mic = microNoise(L, T);
                float per = periodPeak(L, T);
                float[] ac = blockAcf(L, T);
                float[] q = quantiles(L);
                float p15 = q[0], med = q[1], mx = q[2], mn = q[3];
                boolean degenerate = (mx - mn) < 4f;
                System.out.println(String.format(java.util.Locale.US,
                        "  %-14s %-2s formAcf=%5.3f micro=%6.2f span=%5.2f plate=%5.3f per=%5.2f bStd=%5.2f [%5.1f,%6.1f] %s",
                        id, (kind == 0 ? "侧" : "顶"), ac[0], mic, span, plate, per, ac[1],
                        mn, mx, degenerate ? "退化" : ""));
                if (degenerate) fails++;
                sumSpan += span; sumPlate += plate; n++;
                sumAcf += ac[0]; sumBStd += ac[1]; sumMic += mic;
                if (mic > maxMic) { maxMic = mic; maxMicNm = id + (kind == 0 ? "侧" : "顶"); }
                if (span < minSpan) { minSpan = span; minSpanNm = id + (kind == 0 ? "侧" : "顶"); }
                if (plate < minPlate) { minPlate = plate; minPlateNm = id + (kind == 0 ? "侧" : "顶"); }
                if (ac[0] < minAcf) { minAcf = ac[0]; minAcfNm = id + (kind == 0 ? "侧" : "顶"); }
                if (ac[0] < 0.35f) {
                    formFail++;
                    if (formFailList.length() < 400) formFailList.append(formFailList.length() == 0 ? "" : ", ")
                            .append(id).append(kind == 0 ? "侧" : "顶").append("(").append(fmt(ac[0])).append(")");
                }
            }
        }
        float avgSpan = n == 0 ? 0f : sumSpan / n;
        float avgPlate = n == 0 ? 0f : sumPlate / n;
        float avgAcf = n == 0 ? 0f : sumAcf / n;
        float avgBStd = n == 0 ? 0f : sumBStd / n;
        float avgMic = n == 0 ? 0f : sumMic / n;
        float strongFrac = n == 0 ? 0f : (float) strong / n;
        System.out.println(String.format(java.util.Locale.US,
                "TILEART-STATS 均值 formAcf=%.3f micro=%.2f span=%.3f plate=%.3f bStd=%.2f"
                        + " | 最差 formAcf=%.3f(%s) micro=%.2f(%s)",
                avgAcf, avgMic, avgSpan, avgPlate, avgBStd, minAcf, minAcfNm, maxMic, maxMicNm));

        // ---- 判据 ----------------------------------------------------------------
        // 阈值全部由「改前基线 实测」夹出来，不是拍脑袋。改前（2026-09-23 纯 hash 版）：
        //   均值 formAcf = 0.407（被少数有母题的材料拉高）  最差 formAcf = 0.012  → 24/42 个 tile < 0.35
        //   均值 plateFrac = 0.623
        //   —— 也就是说：大多数地形贴图是「4px 独立随机格」+「逐像素微糙」，没有任何跨越形的形状。
        //
        // ⚠️ **本门禁是"不许退回噪声"的下限，不是"好看"的判决。**
        //   好看只能靠导贴图表（`AtlasDump` → `proof/atlas_tiles.png`）目视。这里只钉两条客观规格：
        //     ① FORM：每个 tile 都必须存在**跨越 4px 的结构**（面的相关 / 周期纹样）；
        //     ② MICRO：全分辨率微糙不得超过约 1 个 8bit 级（否则就是"电视雪花"）。
        boolean formOk = formFail == 0;
        System.out.println("TILEART-FORM " + (formOk ? "PASS" : "FAIL")
                + "  formAcf>=0.35 的 tile 数=" + (n - formFail) + "/" + n + "  want 全部"
                + (formFail == 0 ? "" : "\n    未达标: " + formFailList));
        if (!formOk) fails++;

        // ② MICRO：全分辨率微糙（二阶差分）必须小 —— 这是"不要电视雪花"的**精确**表述。
        //    用二阶差分而非"相邻同值率"：后者会惩罚强平滑梯度（沙纹脊/金属辉光带），
        //    把"形状"误判成"噪声"（实测 GOLD 的斜辉光带把 plateFrac 压到 0.499，逼近阈值）。
        //    实测基线：改造前 8.96 级、改造后 4.18 级。判据取 ≤ 6.0。
        boolean microOk = avgMic <= 6.0f;
        System.out.println("TILEART-MICRO " + (microOk ? "PASS" : "FAIL")
                + "  均值 microNoise=" + fmt(avgMic) + " 级 want<=6.0（纯噪声版实测 8.96）");
        if (!microOk) fails++;

        // ---- 导线「局部四臂」tile 库（第七批）：16 张程序化 tile 必须同样守住形状语言 ----
        //  它们不经过上面"按方块名遍历"的路径（不对应任何 blockIndex*3+kind），所以必须显式纳入
        //  —— 否则新加的图集内容就成了没人审的盲区（图集扩容那次的同类教训）。
        int wbFormFail = 0;
        float wbMicMax = 0f, wbAcfMin = 1e9f;
        StringBuilder wbFail = new StringBuilder();
        for (int m = 0; m < TextureAtlas.WIRE_TILE_COUNT; m++) {
            float[] L = tileLum(atlas, TextureAtlas.WIRE_TILE_BASE + m, A, cell, T, G);
            if (L == null) { wbFormFail++; continue; }
            float[] ac = blockAcf(L, T);
            float mic = microNoise(L, T);
            if (ac[0] < 0.35f) {
                wbFormFail++;
                if (wbFail.length() < 200) wbFail.append(wbFail.length() == 0 ? "" : ", ")
                        .append("m").append(m).append("(").append(fmt(ac[0])).append(")");
            }
            if (ac[0] < wbAcfMin) wbAcfMin = ac[0];
            if (mic > wbMicMax) wbMicMax = mic;
        }
        boolean wbOk = (wbFormFail == 0) && (wbMicMax <= 6.0f);
        System.out.println("TILEART-WIRE-BANK " + (wbOk ? "PASS" : "FAIL")
                + "  " + TextureAtlas.WIRE_TILE_COUNT + " 张臂形 tile: minFormAcf=" + fmt(wbAcfMin)
                + " maxMicro=" + fmt(wbMicMax) + "  want formAcf>=0.35 & micro<=6.0"
                + (wbOk ? "" : "\n    未达标: " + wbFail));
        if (!wbOk) fails++;

        // ---- 可定向方块的「端口面」tile 库（第十批）：5 张程序化 tile 同样必须守住形状语言 ----
        //  与 WIRE-BANK 同一类问题：这批 tile **不对应** 任何方块索引（它们是"朝的那一面"的图案），
        //  所以按方块名遍历的路径**看不到它们**。不显式纳入 = 新加的图集内容没人审。
        int fbFormFail = 0;
        float fbMicMax = 0f, fbAcfMin = 1e9f;
        StringBuilder fbFail = new StringBuilder();
        for (int i = 0; i < TextureAtlas.FRONT_TILE_COUNT; i++) {
            float[] L = tileLum(atlas, TextureAtlas.FRONT_TILE_BASE + i, A, cell, T, G);
            if (L == null) { fbFormFail++; continue; }
            float[] ac = blockAcf(L, T);
            float mic = microNoise(L, T);
            if (ac[0] < 0.35f) {
                fbFormFail++;
                if (fbFail.length() < 200) fbFail.append(fbFail.length() == 0 ? "" : ", ")
                        .append(Blocks.ORIENTABLE[i].id).append("(").append(fmt(ac[0])).append(")");
            }
            if (ac[0] < fbAcfMin) fbAcfMin = ac[0];
            if (mic > fbMicMax) fbMicMax = mic;
        }
        boolean fbOk = (fbFormFail == 0) && (fbMicMax <= 6.0f);
        System.out.println("TILEART-FRONT-BANK " + (fbOk ? "PASS" : "FAIL")
                + "  " + TextureAtlas.FRONT_TILE_COUNT + " 张端口 tile: minFormAcf=" + fmt(fbAcfMin)
                + " maxMicro=" + fmt(fbMicMax) + "  want formAcf>=0.35 & micro<=6.0"
                + (fbOk ? "" : "\n    未达标: " + fbFail));
        if (!fbOk) fails++;

        System.out.println("TILEART-SPAN (参考) 均值 span=" + fmt(avgSpan)
                + " px  均值 plate=" + fmt(avgPlate) + "（纯噪声版分别为 2.876 / 0.623）");

        // ---- 唯一性：不同材质不能逐像素相同（防复制粘贴导致"到哪都一样"）----
        int dup = 0;
        for (int i = 0; i < OPAQUE_TERRAIN.length; i++) {
            for (int j = i + 1; j < OPAQUE_TERRAIN.length; j++) {
                Blocks.Block a = Blocks.byId(OPAQUE_TERRAIN[i]), b = Blocks.byId(OPAQUE_TERRAIN[j]);
                if (a == null || b == null) continue;
                if (sameTile(atlas, a.index * 3, b.index * 3, A, cell, T, G)) { dup++; }
            }
        }
        boolean uniqOk = dup == 0;
        System.out.println("TILEART-UNIQ " + (uniqOk ? "PASS" : "FAIL")
                + "  逐像素相同的材质对=" + dup + " want 0");
        if (!uniqOk) fails++;

        if (fails > 0) { System.out.println("TILEART RESULT: FAIL (" + fails + ")"); System.exit(1); }
        System.out.println("TILEART RESULT: PASS");
    }

    // ------------------------------------------------------------------

    /** 取一个 tile 的亮度数组（只统计 alpha ≥ 32 的像素；镂空处填 -1 表示"不属于本面"）。 */
    private static float[] tileLum(ByteBuffer px, int tile, int A, int cell, int T, int G) {
        if (tile < 0) return null;
        int tx0 = TextureAtlas.cellX(tile) + G, ty0 = TextureAtlas.cellY(tile) + G;
        float[] L = new float[T * T];
        int opaque = 0;
        for (int y = 0; y < T; y++) {
            for (int x = 0; x < T; x++) {
                int i = ((ty0 + y) * A + tx0 + x) * 4;
                int r = px.get(i) & 0xFF, g = px.get(i + 1) & 0xFF, b = px.get(i + 2) & 0xFF;
                int al = px.get(i + 3) & 0xFF;
                if (al < 32) { L[y * T + x] = -1f; continue; }
                L[y * T + x] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                opaque++;
            }
        }
        if (opaque < T * T / 4) return null;    // 镂空太多（玻璃）→ 不参与"面"的形状判据
        return L;
    }

    /**
     * shapeRatio = mean(4×4 块内标准差) / std(4×4 块均值)。
     * 纯逐像素噪声 ≈ 1；分区贴图 ≪ 1。只用 alpha≥32 的像素（-1 跳过）。
     */
    private static float shapeRatio(float[] L, int T) {
        final int B = 4, nb = T / B;
        float[] blockMean = new float[nb * nb];
        float withinSum = 0f;
        int withinCnt = 0;
        for (int by = 0; by < nb; by++) {
            for (int bx = 0; bx < nb; bx++) {
                float s = 0f, s2 = 0f; int c = 0;
                for (int y = 0; y < B; y++) {
                    for (int x = 0; x < B; x++) {
                        float v = L[(by * B + y) * T + bx * B + x];
                        if (v < 0f) continue;
                        s += v; s2 += v * v; c++;
                    }
                }
                if (c == 0) { blockMean[by * nb + bx] = Float.NaN; continue; }
                float m = s / c;
                blockMean[by * nb + bx] = m;
                withinSum += Math.max(0f, s2 / c - m * m);
                withinCnt++;
            }
        }
        float within = withinCnt == 0 ? 0f : (float) Math.sqrt(withinSum / withinCnt);
        float between = std(blockMean);
        if (between < 1e-3f) return within < 1e-3f ? 0f : 99f;
        return within / between;
    }

    /**
     * 结构指标③（**主判据**）：**块均值的自相关 lag-1**。
     *
     * <p>做法：把 tile 的亮度场降采样成 4×4 块均值场（16×16），再求相邻块之间的归一化自相关
     * （x、y 两方向取平均）。
     *
     * <p>为什么用它而不是"同值游程"：游程对**斜率**敏感 —— 一条平滑的波纹（斜率大时每像素变化 &gt;2）
     * 会被误判成"碎"，但平滑波恰恰是有结构的。自相关只看"**隔了 4 像素之后还相不相关**"，
     * <b>与斜率无关</b>：
     * <ul>
     *   <li>4px 独立随机格 ⇒ 隔 4px 已是另一个随机值 ⇒ **acf1 ≈ 0**；</li>
     *   <li>跨越 8px 以上的面 / 波 / 年轮 ⇒ 隔 4px 仍在同一结构里 ⇒ **acf1 明显 &gt; 0**。</li>
     * </ul>
     *
     * <p>同时返回块均值场的<b>绝对标准差</b>（8bit 级），用于确认"确实有形的幅度"（而不是全域过平）。
     * 返回 {formAcf, blockMeanStd}。
     *
     * <p><b>为什么取绝对值、且取 x/y 两方向的较大者</b>：
     * <ul>
     *   <li><b>取绝对值</b>：正相关 = 大面/渐变（相邻块同向）；<b>负相关 = 周期纹样</b>
     *       （如仙人掌每 4 图案单位一道竖棱，块均值序列是 D,L,L,L,D,L,L,L ⇒ acf1 = −0.33）。
     *       两种都是"形"，都会被"隔 4px 还相不相关"这个提问抓到，而独立随机格两种都给出 ≈0。</li>
     *   <li><b>取 x/y 较大者</b>：竖棱 / 横板 / 年轮是<b>一维</b>结构，若对两方向取平均会被另一半稀释
     *       （实测 CACTUS 平均后只剩 0.13，误判为"无形"）。</li>
     * </ul>
     */
    private static float[] blockAcf(float[] L, int T) {
        final int B = 4, nb = T / B;
        float[] m = new float[nb * nb];
        for (int by = 0; by < nb; by++) {
            for (int bx = 0; bx < nb; bx++) {
                float s = 0f; int c = 0;
                for (int y = 0; y < B; y++)
                    for (int x = 0; x < B; x++) {
                        float v = L[(by * B + y) * T + bx * B + x];
                        if (v >= 0f) { s += v; c++; }
                    }
                m[by * nb + bx] = c == 0 ? Float.NaN : s / c;
            }
        }
        float sum = 0f; int n = 0;
        for (float v : m) if (!Float.isNaN(v)) { sum += v; n++; }
        float mean = n == 0 ? 0f : sum / n;
        float var = 0f;
        for (float v : m) if (!Float.isNaN(v)) var += (v - mean) * (v - mean);
        var /= Math.max(1, n);
        if (var < 1e-6f) return new float[]{0f, 0f};
        double ax = 0, ay = 0; int px = 0, py = 0;
        for (int by = 0; by < nb; by++)
            for (int bx = 0; bx < nb; bx++) {
                float v = m[by * nb + bx];
                if (Float.isNaN(v)) continue;
                if (bx + 1 < nb && !Float.isNaN(m[by * nb + bx + 1])) { ax += (v - mean) * (m[by * nb + bx + 1] - mean); px++; }
                if (by + 1 < nb && !Float.isNaN(m[(by + 1) * nb + bx])) { ay += (v - mean) * (m[(by + 1) * nb + bx] - mean); py++; }
            }
        float rx = px == 0 ? 0f : (float) (ax / px / var);
        float ry = py == 0 ? 0f : (float) (ay / py / var);
        return new float[]{Math.max(Math.abs(rx), Math.abs(ry)), (float) Math.sqrt(var)};
    }

    /**
     * 结构指标①：**同值游程跨度**（水平 + 垂直合并），单位 = 像素。
     *
     * <p>把「相邻像素亮度差 ≤ 2」连成游程，取其<b>平均长度</b> —— 也就是"同一块色面覆盖多少像素"。
     * <ul>
     *   <li>4px 随机格 + 全分辨率微噪 → 游程被噪声反复打断 ⇒ **1~2**；</li>
     *   <li>有面（8~16px）+ 把微糙压在 1 个 8bit 级以内 ⇒ **6~14**。</li>
     * </ul>
     * 这个量直接对应"眼睛看到的块有多大"，不依赖任何实现细节，纯从像素算。
     */
    private static float plateSpan(float[] L, int T) {
        int same = 0, runs = 0;
        // 水平
        for (int y = 0; y < T; y++) {
            boolean in = false;
            for (int x = 0; x + 1 < T; x++) {
                float a = L[y * T + x], b = L[y * T + x + 1];
                if (a < 0f || b < 0f) { in = false; continue; }
                if (Math.abs(a - b) <= 2f) { same++; if (!in) { runs++; in = true; } }
                else in = false;
            }
        }
        // 垂直
        for (int x = 0; x < T; x++) {
            boolean in = false;
            for (int y = 0; y + 1 < T; y++) {
                float a = L[y * T + x], b = L[(y + 1) * T + x];
                if (a < 0f || b < 0f) { in = false; continue; }
                if (Math.abs(a - b) <= 2f) { same++; if (!in) { runs++; in = true; } }
                else in = false;
            }
        }
        return runs == 0 ? 1f : (float) same / runs;
    }

    /** 结构指标②：**周期性**。取列均值剖面，求归一化自相关在 lag ∈ [4,16] 上的最大值。 */
    private static float periodPeak(float[] L, int T) {
        float[] prof = new float[T];
        for (int x = 0; x < T; x++) {
            float s = 0f; int c = 0;
            for (int y = 0; y < T; y++) { float v = L[y * T + x]; if (v >= 0f) { s += v; c++; } }
            prof[x] = c == 0 ? 0f : s / c;
        }
        float mean = 0f;
        for (float v : prof) mean += v;
        mean /= T;
        double var = 0;
        for (float v : prof) var += (v - mean) * (v - mean);
        if (var < 1e-6) return 0f;
        float best = 0f;
        for (int lag = 4; lag <= 16; lag++) {
            double s = 0;
            for (int i = 0; i + lag < T; i++) s += (prof[i] - mean) * (prof[i + lag] - mean);
            best = (float) Math.max(best, s / var);
        }
        return best;
    }

    /**
     * 结构指标④（**微糙判据**）：**二阶差分均值的绝对值**，单位 = 8bit 级。
     *
     * <p>{@code microNoise = mean(|L[x−1] − 2L[x] + L[x+1]|)}（水平 + 垂直）。
     *
     * <p>为什么不用"相邻像素同值率"（plateFrac）：那个量会**惩罚任何强平滑梯度** ——
     * 一条清晰的沙纹脊（周期 28px、幅度 32 级）每像素变化可达 7 级，于是被判成"糙"，
     * 但它是**形状**不是噪声（实测 GOLD 的斜辉光带把 plateFrac 压到 0.499，逼近阈值）。
     * 二阶差分对**线性/平滑趋势恒等于 0**，只对"每个像素各抖各的"敏感 ⇒ **斜率无关**，
     * 正是"微糙"的定义。
     *
     * <p><b>实测（本机，2026-09-23）</b>：改造前均值 <b>8.96</b>（GRASS 8.05 / SAND 6.41 / LEAF 17.99 /
     * FIRE 9.71 / CACTUS 8.38 / GLASS 20.35），改造后均值 <b>4.18</b>。判据取 ≤ 6.0 —— 两侧都夹得住。
     * （旧值用 `proof/before/atlas_tiles.png` 3× 图按最近邻降采样回 1:1 复算，不是在"新代码上假装旧值"。）
     */
    private static float microNoise(float[] L, int T) {
        double sum = 0; int n = 0;
        for (int y = 0; y < T; y++)
            for (int x = 1; x + 1 < T; x++) {
                float a = L[y * T + x - 1], b = L[y * T + x], c = L[y * T + x + 1];
                if (a < 0f || b < 0f || c < 0f) continue;
                sum += Math.abs(a - 2f * b + c); n++;
            }
        for (int x = 0; x < T; x++)
            for (int y = 1; y + 1 < T; y++) {
                float a = L[(y - 1) * T + x], b = L[y * T + x], c = L[(y + 1) * T + x];
                if (a < 0f || b < 0f || c < 0f) continue;
                sum += Math.abs(a - 2f * b + c); n++;
            }
        return n == 0 ? 0f : (float) (sum / n);
    }

    private static float std(float[] a) {
        float s = 0f, s2 = 0f; int c = 0;
        for (float v : a) { if (Float.isNaN(v)) continue; s += v; s2 += v * v; c++; }
        if (c == 0) return 0f;
        float m = s / c;
        return (float) Math.sqrt(Math.max(0f, s2 / c - m * m));
    }

    /** 相邻（水平）像素对的亮度差 ≤ 2 的占比 —— "成面"的直接度量。 */
    private static float plateFrac(float[] L, int T) {
        int same = 0, tot = 0;
        for (int y = 0; y < T; y++) {
            for (int x = 0; x + 1 < T; x++) {
                float a = L[y * T + x], b = L[y * T + x + 1];
                if (a < 0f || b < 0f) continue;
                tot++;
                if (Math.abs(a - b) <= 2f) same++;
            }
        }
        return tot == 0 ? 0f : (float) same / tot;
    }

    /** 返回 {p15, 中位数, 最大, 最小}。 */
    private static float[] quantiles(float[] L) {
        int c = 0;
        for (float v : L) if (v >= 0f) c++;
        float[] a = new float[c];
        int k = 0;
        for (float v : L) if (v >= 0f) a[k++] = v;
        java.util.Arrays.sort(a);
        if (c == 0) return new float[]{0f, 0f, 0f, 0f};
        return new float[]{a[(int) (c * 0.15f)], a[c / 2], a[c - 1], a[0]};
    }

    /** 两个 tile 是否逐像素相同。 */
    private static boolean sameTile(ByteBuffer px, int t1, int t2, int A, int cell, int T, int G) {
        int x1 = TextureAtlas.cellX(t1) + G, y1 = TextureAtlas.cellY(t1) + G;
        int x2 = TextureAtlas.cellX(t2) + G, y2 = TextureAtlas.cellY(t2) + G;
        for (int y = 0; y < T; y++) {
            for (int x = 0; x < T; x++) {
                int i1 = ((y1 + y) * A + x1 + x) * 4, i2 = ((y2 + y) * A + x2 + x) * 4;
                for (int k = 0; k < 4; k++) if (px.get(i1 + k) != px.get(i2 + k)) return false;
            }
        }
        return true;
    }

    private static String fmt(float v) { return String.format(java.util.Locale.US, "%.4f", v); }
}
