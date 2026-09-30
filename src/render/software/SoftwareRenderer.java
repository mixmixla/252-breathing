package render.software;

import core.world.Blocks;
import core.world.FaceCull;
import core.world.World;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 零依赖软件体素渲染器（等距投影），用于无 GPU / 无显示器环境出图证明引擎架构。
 * 按"列体素柱"渲染：每列从地表往下 3 格（侧壁分层），往上到最顶非空（树），painter 排序保证遮挡。
 *
 * <p><b>面可见性与 GL 路径同源</b>：直接用 {@link FaceCull#visible}（邻居是空气/越界 → 画；
 * 邻居是半透明且与本方块不同类 → 画，看穿；同种液体相接 → 不画）。因此本渲染器也是那条规则的
 * <b>第二个独立实现</b>——GL 与它给出同样的可见面集合，一处写错另一处会立刻对不上。</p>
 *
 * <p>半透明方块（水/玻璃）按 {@link Blocks#WATER_ALPHA} 做 alpha 混合（painter 顺序 = 先画后方），
 * 于是"水是否真的透明、水底是否看得见"可以<b>出图自检</b>，不必只靠 GPU 现场目视。</p>
 *
 * 运行：java -Djava.awt.headless=true -cp out render.software.SoftwareRenderer [输出png] [lake]
 * <ul>
 *   <li>无第二参数：纯净初始地形（seed 20260908）；</li>
 *   <li>{@code lake}：在中前方挖一个浅湖并灌水，用于预览水体透明。</li>
 * </ul>
 */
public final class SoftwareRenderer {

    static final int TILE_W = 22, TILE_H = 14, BLOCK_H = 22;   // 更俯视的角度，让顶面（草/水/沙）看得见
    static final int W = 1000, H = 780;
    static final int DEPTH_BELOW = 5;   // 地表往下画几格（同时露出 dirt 与 stone）
    static final int AIR = Blocks.AIR.index;
    static final int WATER = Blocks.WATER.index;

    /**
     * 预览用 alpha：默认 = {@link Blocks#WATER_ALPHA}（真实值），可用 {@code -Dwater.alpha=0.45} 覆盖。
     * 覆盖只影响<b>出图比较</b>，不改真实常量；用来在无 GPU 环境里自己选水浓淡。
     */
    static final float PREVIEW_ALPHA = readAlphaOverride();

    private static float readAlphaOverride() {
        try { return Float.parseFloat(System.getProperty("water.alpha", String.valueOf(Blocks.WATER_ALPHA))); }
        catch (Exception e) { return Blocks.WATER_ALPHA; }
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "proof/world_proof.png";
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 20260908L;   // 第三个参数：换种子看别的地形
        World w = new World(seed, 48, 40, 48);
        if (args.length > 1 && "lake".equals(args[1])) carveLake(w);
        if (args.length > 1 && "slab".equals(args[1])) slabScene(w);   // 第十六批：几何基座的目视验收场景
        // 0 tick = 纯净初始地形（地面分层清晰可见）；演化后的世界另存为 world_evolved.png
        // （保持仿真侧与渲染侧严格解耦：渲染只读快照）

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < H; y++) {
            float t = y / (float) H;
            int r = (int) (90 + t * 60), gg = (int) (160 + t * 50), b = (int) (235 + t * 10);
            g.setColor(new Color(Math.min(255, r), Math.min(255, gg), Math.min(255, b)));
            g.drawLine(0, y, W, y);
        }

        int ox = W / 2, oy = (int) (H * 0.62);   // 把等距菱形基准点下移到画面中下，让地面主导
        // 按 (x+z) 升序，列内 y 升序（painter）
        for (int s = 0; s <= w.SX + w.SZ; s++) {
            for (int x = 0; x < w.SX; x++) {
                int z = s - x;
                if (z < 0 || z >= w.SZ) continue;
                int surface = surfaceY(w, x, z);
                if (surface < 0) continue;
                int top = topmostNonAir(w, x, z);   // 包括树上
                int lo = Math.max(0, surface - DEPTH_BELOW);
                for (int y = lo; y <= top; y++) {
                    int bi = w.mat[x][y][z];
                    if (bi == AIR) continue;
                    Blocks.Block blk = Blocks.byIndex(bi);
                    drawBlock(g, w, x, y, z, blk, ox, oy, surface, top);
                }
            }
        }
        g.dispose();

        File f = new File(out);
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println("SOFTWARE RENDER OK -> " + f.getAbsolutePath() + " (" + W + "x" + H + ")");
    }

    /**
     * 在中前方挖一个浅湖并灌水（只改渲染用的世界快照，不触碰任何仿真/门禁）。
     * 砂底 + 黏土内底 + 3 格水，用于预览"水透明后水底/池壁是否看得见"。
     */
    private static void carveLake(World w) {
        int x0 = 18, x1 = 30, z0 = 18, z1 = 30, floor = 7, level = 10;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                boolean edge = (x == x0 || x == x1 || z == z0 || z == z1);
                for (int y = floor; y < w.SY; y++) w.setBlock(x, y, z, AIR);
                w.setBlock(x, floor, z, edge ? Blocks.SAND.index : Blocks.CLAY.index);
                for (int y = floor + 1; y <= level; y++) w.setBlock(x, y, z, WATER);
            }
    }

    private static int surfaceY(World w, int x, int z) {
        for (int y = w.SY - 1; y >= 0; y--)
            if (Blocks.byIndex(w.mat[x][y][z]).solid) return y;
        // 没有固体？水面即地表
        for (int y = w.SY - 1; y >= 0; y--)
            if (w.mat[x][y][z] == WATER) return y;
        return -1;
    }

    private static int topmostNonAir(World w, int x, int z) {
        for (int y = w.SY - 1; y >= 0; y--)
            if (w.mat[x][y][z] != AIR) return y;
        return -1;
    }

    private static int px(double xf, double zf, int ox) { return ox + (int) ((xf - zf) * (TILE_W / 2.0)); }
    private static int py(double xf, double zf, double yf, int oy) { return oy + (int) ((xf + zf) * (TILE_H / 2.0) - yf * BLOCK_H); }

    /**
     * 第十六批（几何基座）的**目视验收场景** —— 无 GPU 也要能"看见"半砖。
     *
     * <p><b>为什么做成 main 的场景分支，而不是一次性探针</b>：一次性探针能证明逻辑，却证明不了
     * "看起来对不对"（半格高度、空腔内壁、共面接缝）。固化成可复现的产物后，
     * 以后任何人跑 {@code SoftwareRenderer proof/slab_proof.png slab} 都能重新生成同一张图来验收
     * —— 与 {@code AtlasDump} 出 wire-bank 图是同一套纪律。</p>
     *
     * <p>四组场景各针对一个已知风险：
     * <ol>
     *   <li><b>高度对比</b>：满格 / 石半砖 / 木半砖 / 空气 交替 → 半砖是否真的只占一半；</li>
     *   <li><b>半砖上压满格</b>：中间那半格<b>空腔的内壁</b>必须画出来（面剔除最容易错的一条 ——
     *       只判"邻居矮"会把半砖自己的顶面剔掉，这里就会看到黑洞）；</li>
     *   <li><b>三层半砖叠</b>：每层之间都有空腔；</li>
     *   <li><b>并排木半砖</b>：贴合处的共面重叠<b>不应有接缝</b>（关掉逐格随机色差的效果）。</li>
     * </ol>
     */
    private static void slabScene(World w) {
        int gy = 18;
        for (int x = 4; x < 44; x++)
            for (int z = 4; z < 44; z++) {
                for (int y = gy; y < w.SY; y++) w.setBlock(x, y, z, AIR);
                for (int y = 0; y < gy; y++) w.setBlock(x, y, z, Blocks.STONE.index);
            }
        // ① 族对比：每族一行，"满格 / 该族半砖"逐个交替 —— 一眼看出半格高度与配色差异
        //   （第十七批「台阶家族」7 档全在这里；母题复用同族整块，颜色差 4~6 便于区分）
        int[][] fam = {
            { Blocks.STONE.index,        Blocks.STONE_SLAB.index },
            { Blocks.COBBLE.index,       Blocks.COBBLE_SLAB.index },
            { Blocks.STONE_BRICK.index,  Blocks.STONE_BRICK_SLAB.index },
            { Blocks.SANDSTONE.index,    Blocks.SANDSTONE_SLAB.index },
            { Blocks.BRICK.index,        Blocks.BRICK_SLAB.index },
            { Blocks.IRON_BLOCK.index,   Blocks.IRON_BLOCK_SLAB.index },
            { Blocks.WOOD.index,         Blocks.WOOD_SLAB.index },
        };
        for (int r = 0; r < fam.length; r++)
            for (int i = 0; i < 10; i++)
                w.setBlock(8 + i, gy, 10 + r * 3, (i % 2 == 0) ? fam[r][0] : fam[r][1]);
        // ② 半砖上压满格（空腔内壁必须可见）+ 右侧一根满格柱做参照
        w.setBlock(22, gy, 14, Blocks.STONE_SLAB.index);
        w.setBlock(22, gy + 1, 14, Blocks.STONE.index);
        w.setBlock(23, gy, 14, Blocks.STONE.index);
        w.setBlock(23, gy + 1, 14, Blocks.STONE.index);
        w.setBlock(23, gy + 2, 14, Blocks.STONE.index);
        // ③ 三层半砖叠（每层之间都有半格空腔）
        for (int k = 0; k < 3; k++)
            for (int z = 32; z <= 36; z++)
                for (int j = 0; j <= k; j++) w.setBlock(10 + k, gy + j, z, Blocks.STONE_SLAB.index);
        // ④ 并排木半砖（共面处不应有接缝）
        for (int i = 0; i < 10; i++) w.setBlock(20 + i, gy, 36, Blocks.WOOD_SLAB.index);
        // ⑤ 形状谱系（第十七批·形状二元化）：雪层(0,1/8) → 下半砖(0,1/2) → 上半砖(1/2,1/2)
        //    → 满格(0,1) → 木上半砖。同一套设施、四种区间，一眼看出"从哪起 / 多高"都能表达。
        w.setBlock(26, gy, 22, Blocks.SNOW_LAYER.index);
        w.setBlock(28, gy, 22, Blocks.STONE_SLAB.index);
        w.setBlock(30, gy, 22, Blocks.STONE_SLAB_TOP.index);
        w.setBlock(32, gy, 22, Blocks.STONE.index);
        w.setBlock(34, gy, 22, Blocks.WOOD_SLAB_TOP.index);
        //    上半砖悬空一次：下方空腔里的底面必须画出来（否则从这个角度会"看穿"）
        w.setBlock(30, gy + 2, 26, Blocks.STONE_SLAB_TOP.index);
        w.setBlock(32, gy + 2, 26, Blocks.STONE_SLAB.index);
        // ⑥ 台阶（第十七批·L 形）：三个朝向并排 —— 一眼看出"高半边"随朝向翻面。
        //    ⚠️ 朝向住在 per-block blockState（游戏里由放置时写入）；预览器必须**显式设一次**，
        //    否则三个台阶会全用默认朝向，这张图就变成了"看起来对、其实没验证朝向"的假验收。
        int[] stairDirs = { 0, 1, 4 };                   // +X / -X / +Z
        for (int i = 0; i < stairDirs.length; i++) {
            int sx = 24 + i * 2;
            w.setBlock(sx, gy, 30, Blocks.STONE_STAIRS.index);
            w.setFacing(sx, gy, 30, stairDirs[i]);
        }
        // ⑦ 台阶家族 + **转角**（第十八批）。
        //    转角**不是新方块**：两个朝向不同的台阶相邻摆放，各自的 L 形拼起来就是转角
        //    （MC 的转角台阶同样由两个方块拼出）—— 这一条没有实现成本，只需要在这里证明它成立。
        Blocks.Block[] famStairs = { Blocks.WOOD_STAIRS, Blocks.BRICK_STAIRS, Blocks.SANDSTONE_STAIRS };
        for (int i = 0; i < famStairs.length; i++) {
            w.setBlock(18 + i * 2, gy, 30, famStairs[i].index);
            w.setFacing(18 + i * 2, gy, 30, 0);
        }
        w.setBlock(30, gy, 30, Blocks.STONE_STAIRS.index); w.setFacing(30, gy, 30, 0);   // 转角左件：朝 +X
        w.setBlock(31, gy, 30, Blocks.STONE_STAIRS.index); w.setFacing(31, gy, 30, 4);   // 转角右件：朝 +Z
        // ⑧ 轨道（第十九批）：两条**走向不同**的轨道并排 —— 走向靠"贴图转 90°"表达（UV 对调），
        //    所以这两行必须在图里长得不一样，否则就说明 UV 对调没生效（这正是要验的那件事）。
        for (int i = 0; i < 7; i++) {
            w.setBlock(18 + i, gy, 22, Blocks.RAIL.index);
            w.setFacing(18 + i, gy, 22, 0);          // 沿 X
            w.setBlock(18 + i, gy, 24, Blocks.RAIL.index);
            w.setFacing(18 + i, gy, 24, 4);          // 沿 Z
        }
    }

    private static void drawBlock(Graphics2D g, World w, int x, int y, int z, Blocks.Block blk,
                                  int ox, int oy, int surface, int top) {
        if (blk.stairs) {
            // 第十七批（三）台阶：L 形 —— 分两个盒各画一次，与 GL 路径（Game.emit 的两次 emitBox）同构。
            // ⚠️ 预览器若只画一个盒，验收图会变成"看起来对、其实没验证形状"的假验收。
            int d = core.world.Facing.horizontal(w.getFacing(x, y, z));
            drawBox(g, w, x, y, z, blk, ox, oy, 0f, 1f, 0f, 0.5f, 0f, 1f);
            float bx0 = 0f, bx1 = 1f, bz0 = 0f, bz1 = 1f;
            if (d == 0) bx0 = 0.5f;          // +X：高半边在 +x
            else if (d == 1) bx1 = 0.5f;     // -X
            else if (d == 4) bz0 = 0.5f;     // +Z
            else bz1 = 0.5f;                 // -Z
            drawBox(g, w, x, y, z, blk, ox, oy, bx0, bx1, 0.5f, 1f, bz0, bz1);
            return;
        }
        // 普通方块 = 一个竖直区间（整格 (0,1) ⇒ 与改动前逐字节等价；半砖/上半砖/薄层只换两个数）。
        drawBox(g, w, x, y, z, blk, ox, oy, 0f, 1f, blk.shapeBase, blk.shapeBase + blk.shapeHeight, 0f, 1f);
    }

    /** 画**一个轴对齐盒**（格内 0..1 的小数边界）—— 与 GL 路径的 {@code Game.emitBox} 同一套形状语言。 */
    private static void drawBox(Graphics2D g, World w, int x, int y, int z, Blocks.Block blk, int ox, int oy,
                                float bx0, float bx1, float by0, float by1, float bz0, float bz1) {
        boolean water = blk.liquid;
        float fx0 = x + bx0, fx1 = x + bx1, fz0 = z + bz0, fz1 = z + bz1;
        double yb = y + by0, yt = y + by1;
        // 顶面：仅当正上方的面可见（同种液体相接 → 不可见；上方是空气/异种半透明 → 可见）
        if (FaceCull.visible(w, blk.index, x, y + 1, z)) {
            int[] tx = { px(fx0, fz0, ox), px(fx1, fz0, ox), px(fx1, fz1, ox), px(fx0, fz1, ox) };
            int[] ty = { py(fx0, fz0, yt, oy), py(fx1, fz0, yt, oy), py(fx1, fz1, yt, oy), py(fx0, fz1, yt, oy) };
            g.setColor(shade(blk, 1.0f));
            g.fillPolygon(tx, ty, 4);
        }
        if (water) return;   // 液体只画顶面（与 GL 侧一致的简化）

        // 四侧壁：邻面可见才画（水不遮挡 → 池壁/水下地面照画）
        if (FaceCull.visible(w, blk.index, x + 1, y, z))
            side(g, blk, fx1, yb, fz0, fx1, yt, fz0, fx1, yt, fz1, fx1, yb, fz1, ox, oy, 0.60f);
        if (FaceCull.visible(w, blk.index, x - 1, y, z))
            side(g, blk, fx0, yb, fz1, fx0, yt, fz1, fx0, yt, fz0, fx0, yb, fz0, ox, oy, 0.72f);
        if (FaceCull.visible(w, blk.index, x, y, z + 1))
            side(g, blk, fx1, yb, fz1, fx1, yt, fz1, fx0, yt, fz1, fx0, yb, fz1, ox, oy, 0.78f);
        if (FaceCull.visible(w, blk.index, x, y, z - 1))
            side(g, blk, fx0, yb, fz0, fx0, yt, fz0, fx1, yt, fz0, fx1, yb, fz0, ox, oy, 0.66f);
    }

    private static void side(Graphics2D g, Blocks.Block blk,
                              double ax, double ay, double az, double bx, double by, double bz,
                              double cx, double cy, double cz, double dx, double dy, double dz,
                              int ox, int oy, float k) {
        int[] sx = { px(ax, az, ox), px(bx, bz, ox), px(cx, cz, ox), px(dx, dz, ox) };
        int[] sy = { py(ax, az, ay, oy), py(bx, bz, by, oy), py(cx, cz, cy, oy), py(dx, dz, dy, oy) };
        g.setColor(shade(blk, k));
        g.fillPolygon(sx, sy, 4);
    }

    /** 方块色 × 明度；半透明方块带 {@link Blocks#WATER_ALPHA} 的 alpha（AWT 会与已画像素混合 → 水透明可见）。 */
    private static Color shade(Blocks.Block blk, float k) {
        int r = Math.min(255, (int) (blk.r * k));
        int g = Math.min(255, (int) (blk.g * k));
        int b = Math.min(255, (int) (blk.b * k));
        int a = blk.translucent ? Math.round(PREVIEW_ALPHA * 255f) : 255;
        return new Color(r, g, b, a);
    }
}
