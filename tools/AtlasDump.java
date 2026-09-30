import core.world.Blocks;
import render.lwjgl.TextureAtlas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * 无头导出纹理图集及其 <b>mip 链</b>（不触碰 GL，走 {@link TextureAtlas#bakeAlbedoOffscreen()} /
 * {@link TextureAtlas#bakeMipChainOffscreen} —— 与 GL 上传同一套烘焙代码）。
 *
 * <p>产物：</p>
 * <ul>
 *   <li>{@code proof/atlas_used.png}：已用区域 1:1 全览（列数 = {@code ATLAS_TILES}，行数覆盖到最后保留区 tile）；</li>
 *   <li>{@code proof/atlas_tiles.png}：重点方块 3 倍放大表（每行一个方块，三列 = 侧 / 顶 / 底）；</li>
 *   <li>{@code proof/atlas_cracks.png}：10 级破坏裂纹；{@code proof/atlas_edges.png}：交界过渡族；
 *       {@code proof/wire_bank.png}：16 张导线臂形 tile（第七批，掩码 → 出臂方向的可视化验收）；</li>
 *   <li>{@code proof/atlas_mip0..N.png}：<b>逐级 mip</b> 的重点方块表（每 tile 固定放大到 64px 显示，
 *       带 alpha，镂空区真透明）——用来看"远处各向异性/缩小取样时纹理长什么样"；</li>
 *   <li>stdout：逐级数值指标 + <b>"GL 整图盒滤波"对照</b>（同口径重算一遍 naive 链），用来证明
 *       现役 mip 链在三件事上优于 {@code glGenerateMipmap}：跨 tile 串色 / 透明黑边 / sRGB 平均偏暗。</li>
 * </ul>
 *
 * <p>运行：java -Djava.awt.headless=true -cp "out;libs/..." AtlasDump</p>
 */
public class AtlasDump {

    public static void main(String[] args) throws Exception {
        ByteBuffer px = TextureAtlas.bakeAlbedoOffscreen();
        final int A = TextureAtlas.ATLAS_PX, cell = TextureAtlas.CELL_PX,
                  T = TextureAtlas.TILE_PX, G = TextureAtlas.GUTTER, NL = TextureAtlas.MIP_LEVELS;

        // 1) 已用区域 1:1 全览（**全宽 × 覆盖到最后保留区 tile 的行数**）
        // ⚠️ 2026-09-26：列数原先硬编码 16、行数硬编码 6 ⇒ 只覆盖 tile 0..95 ——
        //    既漏掉大部分方块 tile，更**完全看不到保留区**（UI 在 ATLAS_SLOTS/2 附近）。
        //    改成由 TextureAtlas 常量推导 ⇒ 图集尺寸 / 保留区位置一变，验收图自动跟随。
        int cols = TextureAtlas.ATLAS_TILES;
        int lastUsed = TextureAtlas.FRONT_TILE_BASE + TextureAtlas.FRONT_TILE_COUNT - 1;
        int rows = lastUsed / cols + 1;
        BufferedImage full = new BufferedImage(cell * cols, cell * rows, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < cell * rows; y++)
            for (int x = 0; x < cell * cols; x++) {
                // cell 网格线（1px 浅灰）：图集有**白色底**，所以"纯白 tile / 未覆盖槽位"在图上不可见
                // （试过"透明处补棋盘"—— **无效**，因为未覆盖区不是透明、而是不透明白）。
                // 网格线至少让"每个 tile 槽位在哪"可读 —— 这正是布局验收（如保留区迁移）需要的信息。
                if (x % cell == 0 || y % cell == 0) { full.setRGB(x, y, 0xFFB8B8B8); continue; }
                full.setRGB(x, y, argb(px, A, x, y));
            }
        ImageIO.write(full, "png", new File("proof/atlas_used.png"));
        System.out.println("  atlas_used.png = " + cols + " 列 × " + rows + " 行 cell"
                + "（覆盖 tile 0.." + lastUsed + "；UI_TILE_BASE=" + TextureAtlas.UI_TILE_BASE
                + " ATLAS_SLOTS=" + TextureAtlas.ATLAS_SLOTS + "）");

        String[] names = {"GRASS", "DIRT", "STONE", "COBBLE", "GRANITE", "OBSIDIAN", "SLATE", "GRAVEL",
                "SAND", "RED_SAND", "PODZOL", "SOUL_SAND", "WOOD", "SHELTER", "BRICK", "SANDSTONE",
                "TERRACOTTA", "CLAY", "ASHLAR", "MOSS", "SNOW", "ICE", "WATER", "GLASS", "VINES",
                "MUSHROOM", "HAY", "CACTUS", "FLOWER", "LEAF", "GOLD", "AMETHYST", "LANTERN",
                "LAMP", "FIRE", "COAL_ORE", "IRON_ORE", "BEDROCK", "ASH",
                // 第七批：红石逻辑三件套 + 生活方块（新增方块的母题必须进这张可复现的目视验收表）
                "REPEATER", "COMPARATOR", "PLATE", "CAMPFIRE", "FARMLAND",
                "DISPENSER", "PISTON", "OBSERVER",
                "CORAL", "BEEHIVE", "CRYSTAL", "FERN", "REED", "SAPLING",
                "BAMBOO", "BARREL", "CAULDRON", "LECTERN", "IRON_BARS", "CHAIN"};

        // 2) 重点方块 3× 放大表（level 0）：每行一个方块，三列 = 侧 / 顶 / 底
        int scale = 3, S = T * scale;
        BufferedImage sheet = new BufferedImage(S * 3, S * names.length, BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < names.length; r++) {
            Blocks.Block b = Blocks.byId(names[r]);
            for (int k = 0; k < 3; k++) {
                int tile = b.index * 3 + k;
                int sx = TextureAtlas.cellX(tile) + G, sy = TextureAtlas.cellY(tile) + G;
                for (int y = 0; y < S; y++)
                    for (int x = 0; x < S; x++)
                        sheet.setRGB(k * S + x, r * S + y, argb(px, A, sx + x / scale, sy + y / scale));
            }
        }
        ImageIO.write(sheet, "png", new File("proof/atlas_tiles.png"));

        // 2b) 破坏阶段裂纹 10 级（学 MC destroy_stage_0..9）：合成到灰底上模拟"叠在方块表面"的样子，
        //     并打印每级的裂纹像素占比 —— 应随级单调增加（这就是"还差多少"的可读性来源）。
        int cs = T * 3, NS = TextureAtlas.CRACK_STAGES;
        BufferedImage crack = new BufferedImage(cs * NS, cs, BufferedImage.TYPE_INT_ARGB);
        System.out.print("  crack stage coverage%:");
        for (int st = 0; st < NS; st++) {
            int tile = TextureAtlas.CRACK_TILE_BASE + st;
            int sx = TextureAtlas.cellX(tile) + G, sy = TextureAtlas.cellY(tile) + G;
            int hit = 0;
            for (int y = 0; y < T; y++)
                for (int x = 0; x < T; x++) {
                    int i = ((sy + y) * A + sx + x) * 4;
                    boolean on = (px.get(i + 3) & 255) > 0;
                    if (on) hit++;
                    int bg = 150;
                    int c = on ? 0x2A2A2E : (bg << 16) | (bg << 8) | bg;
                    for (int oy = 0; oy < 3; oy++)
                        for (int ox = 0; ox < 3; ox++)
                            crack.setRGB(st * cs + x * 3 + ox, y * 3 + oy, 0xFF000000 | c);
                }
            System.out.printf(java.util.Locale.US, " %.1f", 100f * hit / (T * T));
        }
        System.out.println();
        ImageIO.write(crack, "png", new File("proof/atlas_cracks.png"));
        System.out.println("  proof/atlas_cracks.png  " + (cs * NS) + "x" + cs + "（10 级破坏裂纹，灰底 = 方块表面）");

        // 2b-2) 导线「局部四臂」tile 库（第七批）：16 张 = 4 bit 连通掩码全组合，4×4 排布、3× 放大。
        //  目视要点：掩码 m 的二进制位应与其出臂方向一一对应（bit0=局部左 / bit1=右 / bit2=上 / bit3=下，
        //  见 TextureAtlas.wireTile）；所以 m=0 只有中心节点、m=15 是完整十字。
        //  这里**必须能复现**，不能只靠一次性探针 —— 否则以后没人能再生成这张图来验收。
        int ws = T * 3, WC = 4, WR = TextureAtlas.WIRE_TILE_COUNT / WC;
        BufferedImage wire = new BufferedImage(ws * WC, ws * WR, BufferedImage.TYPE_INT_ARGB);
        System.out.print("  wire bank arms:");
        for (int m = 0; m < TextureAtlas.WIRE_TILE_COUNT; m++) {
            int tile = TextureAtlas.WIRE_TILE_BASE + m;
            int sx = TextureAtlas.cellX(tile) + G, sy = TextureAtlas.cellY(tile) + G;
            int ox0 = (m % WC) * ws, oy0 = (m / WC) * ws;
            int arms = 0;
            for (int b = 0; b < 4; b++) if ((m & (1 << b)) != 0) arms++;
            for (int y = 0; y < T; y++)
                for (int x = 0; x < T; x++) {
                    int c = argb(px, A, sx + x, sy + y) & 0xFFFFFF;
                    for (int oy = 0; oy < 3; oy++)
                        for (int ox = 0; ox < 3; ox++)
                            wire.setRGB(ox0 + x * 3 + ox, oy0 + y * 3 + oy, 0xFF000000 | c);
                }
            System.out.print(" " + m + ":" + arms);
        }
        System.out.println();
        ImageIO.write(wire, "png", new File("proof/wire_bank.png"));
        System.out.println("  proof/wire_bank.png  " + (ws * WC) + "x" + (ws * WR)
                + "（16 张导线臂形 tile，掩码 m 越大臂越多；口径见 TextureAtlas.wireTile）");

        // 2b2) 可定向方块的「端口面」tile 库（第十批）：5 张，3× 放大。
        //  验收口径：每张都能看到"外框 → 外环 → 凸台 → 中心孔"的同心结构，且 5 张各有自己的色相。
        int fc = TextureAtlas.FRONT_TILE_COUNT;
        BufferedImage front = new BufferedImage((T * 3 + 4) * fc + 4, T * 3 + 8, BufferedImage.TYPE_INT_ARGB);
        System.out.print("  front bank (facing marker):");
        for (int i = 0; i < fc; i++) {
            int tile = TextureAtlas.FRONT_TILE_BASE + i;
            int sx = TextureAtlas.cellX(tile) + G, sy = TextureAtlas.cellY(tile) + G;
            int ox0 = i * (T * 3 + 4) + 4, oy0 = 4;
            for (int y = 0; y < T; y++)
                for (int x = 0; x < T; x++) {
                    int c = argb(px, A, sx + x, sy + y);
                    for (int oy = 0; oy < 3; oy++)
                        for (int ox = 0; ox < 3; ox++)
                            front.setRGB(ox0 + x * 3 + ox, oy0 + y * 3 + oy, c);
                }
            System.out.print(" " + i + ":" + Blocks.ORIENTABLE[i].id);
        }
        System.out.println();
        ImageIO.write(front, "png", new File("proof/front_bank.png"));
        System.out.println("  proof/front_bank.png  " + front.getWidth() + "x" + front.getHeight()
                + "（" + fc + " 张端口 tile，次序 = Blocks.ORIENTABLE，画在方块朝向的那一面）");

        // 2c) 跨材质交界过渡族（四路步骤 4）：6 张灰度图案，3× 放大。灰度均值应 ≈0.5（乘上去只改质感不改明度）。
        int es = T * 3;
        BufferedImage edges = new BufferedImage(es * TextureAtlas.EDGE_FAMILIES, es, BufferedImage.TYPE_INT_ARGB);
        System.out.print("  edge family mean(gray 0..1):");
        for (int fam = 0; fam < TextureAtlas.EDGE_FAMILIES; fam++) {
            int tile = TextureAtlas.edgeTile(fam);
            int sx = TextureAtlas.cellX(tile) + G, sy = TextureAtlas.cellY(tile) + G;
            long sum = 0;
            for (int y = 0; y < T; y++)
                for (int x = 0; x < T; x++) {
                    int i = ((sy + y) * A + sx + x) * 4;
                    int v = px.get(i) & 255;
                    sum += v;
                    int c = (v << 16) | (v << 8) | v;
                    for (int oy = 0; oy < 3; oy++)
                        for (int ox = 0; ox < 3; ox++)
                            edges.setRGB(fam * es + x * 3 + ox, y * 3 + oy, 0xFF000000 | c);
                }
            System.out.printf(java.util.Locale.US, " %.3f", sum / (255f * T * T));
        }
        System.out.println();
        ImageIO.write(edges, "png", new File("proof/atlas_edges.png"));
        System.out.println("  proof/atlas_edges.png  " + (es * TextureAtlas.EDGE_FAMILIES) + "x" + es
                + "（" + TextureAtlas.EDGE_FAMILIES + " 族跨材质交界图案，0/1=颗粒互嵌 2/3=堆积咬合 4/5=硬拼裂纹）");

        // 3) mip 链（现役：逐 cell 钳制的 CPU 链）
        long t0 = System.nanoTime();
        ByteBuffer[] chain = TextureAtlas.bakeMipChainOffscreen(px);
        long msChain = (System.nanoTime() - t0) / 1_000_000L;
        // 对照链：等价于 glGenerateMipmap 的整图 2×2 盒滤波（不跳过透明、不做 gamma、不钳 cell）。仅用于验证。
        ByteBuffer[] naive = naiveChain(px);
        System.out.println("  mip 链 CPU 生成耗时 = " + msChain + " ms（启动一次，含 " + TextureAtlas.MIP_LEVELS + " 级）");

        for (int L = 0; L <= NL; L++) {
            int size = A >> L;
            if (size < 1 || chain[L] == null) break;
            int tp = Math.max(1, T >> L);            // 该级 tile 内容边长（视角等价：固定显示 64px）
            int sc = Math.max(1, T / tp);
            BufferedImage img = new BufferedImage(T * 3, T * names.length, BufferedImage.TYPE_INT_ARGB);
            for (int r = 0; r < names.length; r++) {
                Blocks.Block b = Blocks.byId(names[r]);
                for (int k = 0; k < 3; k++) {
                    int tile = b.index * 3 + k;
                    int sx = (TextureAtlas.cellX(tile) + G) >> L, sy = (TextureAtlas.cellY(tile) + G) >> L;
                    for (int y = 0; y < T; y++)
                        for (int x = 0; x < T; x++)
                            img.setRGB(k * T + x, r * T + y,
                                    argb(chain[L], size, sx + Math.min(tp - 1, x / sc), sy + Math.min(tp - 1, y / sc)));
                }
            }
            ImageIO.write(img, "png", new File("proof/atlas_mip" + L + ".png"));
        }

        System.out.println("ATLAS DUMP OK");
        System.out.println("  proof/atlas_used.png   " + (cell * TextureAtlas.ATLAS_TILES) + "x" + (cell * rows));
        System.out.println("  proof/atlas_tiles.png  " + (S * 3) + "x" + (S * names.length));
        System.out.println("  proof/atlas_mip0.." + NL + ".png  （逐级 mip，每 tile 固定显示 " + T + "px）");
        System.out.println("  TILE_PX=" + T + " GUTTER=" + G + " CELL_PX=" + cell + " ATLAS_PX=" + A
                + " MIP_LEVELS=" + NL);

        // 4) 逐级数值指标：现役链 vs naive 对照链 —— 三件事一张表说清
        report(px, chain, naive, "GLASS", NL);
        report(px, chain, naive, "FLOWER", NL);
        report(px, chain, naive, "WATER", NL);
        report(px, chain, naive, "GRASS", NL);
    }

    // ---------- 对照：glGenerateMipmap 等价实现（整图 2×2 盒滤波，无 gamma / 不跳透明 / 不钳 cell） ----------

    private static ByteBuffer[] naiveChain(ByteBuffer l0) {
        int A = TextureAtlas.ATLAS_PX, NL = TextureAtlas.MIP_LEVELS;
        ByteBuffer[] lv = new ByteBuffer[NL + 1];
        lv[0] = l0;
        for (int L = 1; L <= NL; L++) {
            int src = A >> (L - 1), dst = A >> L;
            if (dst < 1) break;
            ByteBuffer s = lv[L - 1], d = ByteBuffer.allocate(dst * dst * 4);
            for (int y = 0; y < dst; y++)
                for (int x = 0; x < dst; x++) {
                    int di = (y * dst + x) * 4;
                    for (int c = 0; c < 4; c++) {
                        int sum = 0;
                        for (int j = 0; j < 2; j++)
                            for (int i = 0; i < 2; i++)
                                sum += s.get(((2 * y + j) * src + (2 * x + i)) * 4 + c) & 255;
                        d.put(di + c, (byte) ((sum + 2) / 4));
                    }
                }
            lv[L] = d;
        }
        return lv;
    }

    // ---------- 指标 ----------

    /**
     * 一个 tile（取侧面 kind=0）在各级的指标：
     * <ul>
     *   <li>{@code a}：平均 alpha（0..1）——整体不透明度；</li>
     *   <li>{@code vis}：**可见 texel**（alpha&gt;0）的平均亮度 —— 肉眼看这块纹理有多亮。
     *       naive 在 sRGB 空间盒滤波会系统性偏低（细节一缩就变暗），这是"远处地形发灰发暗"的一个隐藏来源；</li>
     *   <li>{@code edge}：**半透明边缘 texel**（0&lt;alpha&lt;255）的平均亮度 —— 镂空/半透 tile 的**黑边指标**。
     *       naive 会把"透明黑"平均进边缘 RGB，现役链跳过全透明 tap（MC {@code alphaBlend} 那道门）；</li>
     *   <li>{@code ring}：cell 最外圈相对该 cell 自身 level-0 内容均值的绝对偏差 —— **跨 tile 串色指标**。
     *       naive 在 cell 边界会把邻居 tile 平均进来（GL 整图滤波无法避免），现役链逐 cell 钳制取样窗口。</li>
     * </ul>
     */
    private static void report(ByteBuffer l0, ByteBuffer[] chain, ByteBuffer[] naive, String block, int NL) {
        int A = TextureAtlas.ATLAS_PX, cell = TextureAtlas.CELL_PX, G = TextureAtlas.GUTTER;
        Blocks.Block b = Blocks.byId(block);
        if (b == null) return;
        int tile = b.index * 3;                       // 侧面
        int cx0 = TextureAtlas.cellX(tile), cy0 = TextureAtlas.cellY(tile);
        int content = TextureAtlas.TILE_PX;

        // baseline：该 tile 自身 level-0 内容区**可见** texel 的平均亮度（ring-dev 的参照）
        long own = 0; int ownN = 0;
        for (int y = 0; y < content; y++)
            for (int x = 0; x < content; x++) {
                int i = ((cy0 + G + y) * A + (cx0 + G + x)) * 4;
                if ((l0.get(i + 3) & 255) == 0) continue;
                own += lum(l0, i); ownN++;
            }
        float ownMean = ownN > 0 ? own / (float) ownN : 0f;

        System.out.println();
        System.out.println("== " + block + " (side tile " + tile + ")  own0=" + Math.round(ownMean) + " ==");
        System.out.printf("%-5s | %-21s | %-21s | %-13s%n",
                "level", "live  a / vis / edge", "naive a / vis / edge", "ring live/naive");
        for (int L = 0; L <= NL; L++) {
            if (chain[L] == null || naive[L] == null) break;
            int sz = A >> L;
            // cell 在该级是否仍落在 texel 整数格上（CELL % 2^L == 0）；不对齐时 ring 指标退化，记 "-"
            boolean aligned = (cell % (1 << L)) == 0;
            String ring = aligned
                    ? String.format(java.util.Locale.US, "%6.1f /%6.1f",
                        ringDev(chain[L], sz, cx0, cy0, ownMean, L), ringDev(naive[L], sz, cx0, cy0, ownMean, L))
                    : "     - (cell 未对齐)";
            System.out.printf("%-5d | %-21s | %-21s | %s%n", L,
                    stats(chain[L], sz, cx0, cy0, L), stats(naive[L], sz, cx0, cy0, L), ring);
        }
    }

    /** 该 tile 在该级的 a(平均alpha 0..1) / vis(可见 texel 平均亮度) / edge(半透明边缘 texel 平均亮度)。 */
    private static String stats(ByteBuffer lv, int sz, int cx0, int cy0, int L) {
        int G = TextureAtlas.GUTTER, content = TextureAtlas.TILE_PX;
        long aSum = 0; int n = 0;
        long visSum = 0; int visN = 0;
        long edgeSum = 0; int edgeN = 0;
        for (int y = 0; y < content; y++)
            for (int x = 0; x < content; x++) {
                int px2 = (cx0 + G + x) >> L, py2 = (cy0 + G + y) >> L;   // 绝对坐标右移，避免 cx0 非 2^L 倍数时错位
                int i = (py2 * sz + px2) * 4;
                int a = lv.get(i + 3) & 255;
                aSum += a; n++;
                if (a > 0) { visSum += lum(lv, i); visN++; }
                if (a > 0 && a < 255) { edgeSum += lum(lv, i); edgeN++; }
            }
        return String.format(java.util.Locale.US, "%.2f %3s %3s",
                aSum / (255f * n), visN > 0 ? "" + Math.round(visSum / (float) visN) : "-",
                edgeN > 0 ? "" + Math.round(edgeSum / (float) edgeN) : "-");
    }

    /**
     * cell 最外圈（gutter 环）相对该 cell 自身 level-0 内容均值的平均绝对偏差：跨 tile 串色指标。
     * 只统计**可见** texel（alpha&gt;0），否则会把"全透明区 RGB"这种不可见差异算进来。
     */
    private static float ringDev(ByteBuffer lv, int sz, int cx0, int cy0, float ownMean, int L) {
        int cell = TextureAtlas.CELL_PX;
        int lo = cx0 >> L, to = cy0 >> L, hi = (cx0 + cell) >> L, hiy = (cy0 + cell) >> L;
        long sum = 0; int n = 0;
        for (int y = to; y < hiy; y++)
            for (int x = lo; x < hi; x++) {
                if (x != lo && x != hi - 1 && y != to && y != hiy - 1) continue;   // 只取最外圈
                int i = (y * sz + x) * 4;
                if ((lv.get(i + 3) & 255) == 0) continue;                          // 不可见 → 不计
                sum += Math.abs(lum(lv, i) - ownMean);
                n++;
            }
        return n > 0 ? sum / (float) n : 0f;
    }

    private static int lum(ByteBuffer px, int i) {
        int r = px.get(i) & 255, g = px.get(i + 1) & 255, b = px.get(i + 2) & 255;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** 图集 (x,y) → 0xAARRGGBB（保留 alpha，镂空区在 PNG 里真透明）。 */
    private static int argb(ByteBuffer px, int A, int x, int y) {
        int i = (y * A + x) * 4;
        return ((px.get(i + 3) & 255) << 24) | ((px.get(i) & 255) << 16)
                | ((px.get(i + 1) & 255) << 8) | (px.get(i + 2) & 255);
    }
}
