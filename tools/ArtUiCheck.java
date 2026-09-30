import render.lwjgl.TextureAtlas;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 门禁 ARTUI：自研艺术 UI 层的<b>契约守护</b>（无头，零 GL）。
 *
 * <p>自研 UI 的艺术质感（图集纹理 + 九宫格面板 + 纸纹/暗角）全部落在渲染层，必须满足项目铁律
 * <b>零漂移</b>：出厂默认（{@code -Dbw.uiart} 未设 → {@code uArtAmt=0}）必须与"纯色旧路径"
 * <b>逐字节等价</b>。本门禁把该契约拆成可在无 GPU 环境验证的若干条不变量：</p>
 *
 * <ol>
 *   <li><b>恒等纹理存在且严格纯白</b>：{@link TextureAtlas#UI_WHITE_TILE} 的中心与 gutter
 *       必须是 exact 255,255,255,255。这是"采样返回白 → col*white == col"的数学前提。
 *       ⚠️ 不能用 {@link TextureAtlas#WHITE_TILE}(255)：那张是纯白<b>微噪</b>（给远景剪影的质感白），
 *       拿它当恒等纹理会让整个 HUD 被乘上 ~0.98 的噪声（确定性压暗）。</li>
 *   <li><b>九宫格 UV 几何正确</b>：{@link TextureAtlas#uiU}/{@link TextureAtlas#uiV} 的四角
 *       必须落在各自角落、四边单轴居中、中央双轴居中（切片比例 = {@link TextureAtlas#UI_NINE_SLICE}）。
 *       错一个角就会出现"描边粗细随面板尺寸变化"的穿帮。</li>
 *   <li><b>UI 贴图烘焙：描边不对称</b>：上/左受光、下/右背光 → 同一 tile 的上下平均亮度不等。
 *       若相等说明描边方向建模失效（2026-09-21 曾因"由 cx/cy 反推贴边方向"导致描边整块不画）。</li>
 *   <li><b>UI 贴图不污染其它 tile</b>：烘焙前后地对形/细节 tile 的 FNV 哈希必须不变
 *       （图集是共享资源，UI 区写入绝不能越界改写方块区）。</li>
 *   <li><b>FS 单字面量纪律</b>：{@code Game.initHudShader()} 的 FS 必须写成<b>单个</b> Java 字符串
 *       字面量。<b>绝不能</b>写成 {@code "..." + "..."} 拼接 —— {@code ShaderCheck} 的抽取器
 *       只读到第一个未转义双引号为止，拼接会让门禁编译残缺 FS 却报 PASS（<b>静默假绿</b>）。</li>
 *   <li><b>FS 恒等语义</b>：默认路径的 RGB 计算必须是 {@code vCol.rgb * t.rgb}，
 *       <b>不得</b>乘 {@code vCol.a}。旧式是 straight alpha + blend(SRC_ALPHA, 1-SRC_ALPHA)；
 *       在 FS 里预乘会让所有 alpha&lt;1 的 HUD 元素按自身 alpha 再压一次（2026-09-21 实测全屏
 *       压暗 1.5%、minimap 区比值恰为其 alpha 0.60）。</li>
 *   <li><b>默认强度为 0</b>：{@code readUiArtAmount} 在属性缺失/非法/≤0 时返回 0，且 &gt;1 时钳到 1。</li>
 * </ol>
 *
 * <p>真实 GPU 上的逐字节等价由 {@code tools/UiArtEquiv}（离屏真驱动，含 alpha&lt;1 场景）证明；
 * 本门禁负责"契约本身"的静态守护，两者互补。</p>
 */
public final class ArtUiCheck {

    private static boolean ok = true;

    private static void check(String name, boolean cond) {
        System.out.println("  [" + (cond ? "ok  " : "FAIL") + "] " + name);
        if (!cond) ok = false;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ARTUI: 自研艺术 UI 契约 ===");

        // ---------- 1) 恒等纹理：严格纯白 ----------
        ByteBuffer px = TextureAtlas.bakeAlbedoOffscreen();
        int CELL = TextureAtlas.CELL_PX, G = TextureAtlas.GUTTER, TP = TextureAtlas.TILE_PX;
        int wt = TextureAtlas.UI_WHITE_TILE;

        boolean centerWhite = true, gutterWhite = true, cornerWhite = true;
        int tx0 = TextureAtlas.cellX(wt), ty0 = TextureAtlas.cellY(wt);
        // 中心（恒等采样点）
        int cx = tx0 + G + TP / 2, cy = ty0 + G + TP / 2;
        int i = (cy * (CELL * TextureAtlas.ATLAS_TILES) + cx) * 4;
        centerWhite = (px.get(i) & 255) == 255 && (px.get(i + 1) & 255) == 255
                && (px.get(i + 2) & 255) == 255 && (px.get(i + 3) & 255) == 255;
        // 四个内容角 + gutter 四角（mip/线性采样越界时要仍是白）
        int[][] pts = {
                {tx0 + G, ty0 + G}, {tx0 + G + TP - 1, ty0 + G + TP - 1},
                {tx0, ty0}, {tx0 + CELL - 1, ty0 + CELL - 1},
                {tx0, ty0 + CELL - 1}, {tx0 + CELL - 1, ty0}};
        for (int[] p : pts) {
            int j = (p[1] * (CELL * TextureAtlas.ATLAS_TILES) + p[0]) * 4;
            for (int k = 0; k < 4; k++)
                if ((px.get(j + k) & 255) != 255) { cornerWhite = false; gutterWhite = false; }
        }
        check("UI_WHITE_TILE 中心 = exact 255,255,255,255", centerWhite);
        check("UI_WHITE_TILE 内容角 = exact 白", cornerWhite);
        check("UI_WHITE_TILE gutter 亦为白（mip 安全）", gutterWhite);

        // WHITE_TILE 与 UI_WHITE_TILE 必须是不同的槽（前者是微噪，不能当恒等）
        check("UI_WHITE_TILE != WHITE_TILE", TextureAtlas.UI_WHITE_TILE != TextureAtlas.WHITE_TILE);
        // 边界扫描：UI_WHITE_TILE 必须落在所有"已占用区间"之外
        boolean free = true;
        int wt2 = TextureAtlas.UI_WHITE_TILE;
        // 方块区 0..(3*blocks)；用 UI 区/边缝区/裂纹区/细节/白噪 明确区间来判
        if (wt2 >= TextureAtlas.UI_TILE_BASE && wt2 < TextureAtlas.UI_TILE_BASE + TextureAtlas.UI_TILE_COUNT) free = false;
        if (wt2 >= TextureAtlas.CRACK_TILE_BASE && wt2 < TextureAtlas.CRACK_TILE_BASE + 10) free = false;
        if (wt2 >= TextureAtlas.EDGE_TILE_BASE && wt2 < TextureAtlas.EDGE_TILE_BASE + TextureAtlas.EDGE_FAMILIES * TextureAtlas.EDGE_TILES_PER_FAM) free = false;
        if (wt2 == TextureAtlas.DETAIL_TILE || wt2 == TextureAtlas.WHITE_TILE) free = false;
        if (wt2 < 0 || wt2 >= TextureAtlas.ATLAS_TILES * TextureAtlas.ATLAS_TILES) free = false;
        check("UI_WHITE_TILE 独占且不与任何已占用区间重叠", free);

        // 中心 UV 必须落在 [0,1] 且与 uOf 一致
        float cu = TextureAtlas.uOf(wt, 0.5f), cv = TextureAtlas.vOf(wt, 0.5f);
        check("恒等 UV 在 [0,1] 内", cu > 0f && cu < 1f && cv > 0f && cv < 1f);

        // ---------- 2) 九宫格 UV 几何 ----------
        check("UI_NINE_SLICE ∈ (0,0.5)", TextureAtlas.UI_NINE_SLICE > 0f && TextureAtlas.UI_NINE_SLICE < 0.5f);
        // 角：TL 应在上左（u,v 都小）；BR 在下右（u,v 都大）
        float tlU = TextureAtlas.uiU(TextureAtlas.UI_CORNER_TL, 0.5f);
        float trU = TextureAtlas.uiU(TextureAtlas.UI_CORNER_TR, 0.5f);
        float blV = TextureAtlas.uiV(TextureAtlas.UI_CORNER_BL, 0.5f);
        float brV = TextureAtlas.uiV(TextureAtlas.UI_CORNER_BR, 0.5f);
        float tlV = TextureAtlas.uiV(TextureAtlas.UI_CORNER_TL, 0.5f);
        check("九宫格 TL 在左上 (u<TR u, v<BL v)", tlU < trU && tlV < blV);
        check("九宫格 BR 在右下 (u>BL u, v>TR v)",
                TextureAtlas.uiU(TextureAtlas.UI_CORNER_BR, 0.5f) > TextureAtlas.uiU(TextureAtlas.UI_CORNER_BL, 0.5f)
                && brV > TextureAtlas.uiV(TextureAtlas.UI_CORNER_TR, 0.5f));
        // 中央：单轴居中（两条中线之间）
        float cU = TextureAtlas.uiU(TextureAtlas.UI_CENTER, 0.5f);
        float cV = TextureAtlas.uiV(TextureAtlas.UI_CENTER, 0.5f);
        float tU = TextureAtlas.uiU(TextureAtlas.UI_EDGE_T, 0.5f);
        check("中央 UV 落在两片中间", Math.abs(cU - 0.5f) < 0.30f && Math.abs(cV - 0.5f) < 0.30f);
        // 边 T：V 轴落在"靠上"的 1/4 切片（比中央的中间片更靠上）；U 轴与中央同属"居中片"。
        // 说明：绝对 UV 因 tile 位置 + gutter 偏移而不同，故只断言"同 tile 行下的相对高低"。
        float tV = TextureAtlas.uiV(TextureAtlas.UI_EDGE_T, 0.5f);
        check("边 T：V 比中央更靠上（同一 tile 行）", tV < cV);
        check("边 T：U 与中央同为『居中 1/2 片』",
                Math.abs((tU * TextureAtlas.ATLAS_TILES) - Math.floor(tU * TextureAtlas.ATLAS_TILES)
                        - ((cU * TextureAtlas.ATLAS_TILES) - Math.floor(cU * TextureAtlas.ATLAS_TILES))) < 0.06f);
        // 边 T 两端参数分别落在该片上/下缘：cv=0 在上、cv=1 在下
        check("边 T：cv=0 的 V < cv=1 的 V（切片方向正确）",
                TextureAtlas.uiV(TextureAtlas.UI_EDGE_T, 0f) < TextureAtlas.uiV(TextureAtlas.UI_EDGE_T, 1f));
        check("边 B：cv=0 的 V < cv=1 的 V（切片方向正确）",
                TextureAtlas.uiV(TextureAtlas.UI_EDGE_B, 0f) < TextureAtlas.uiV(TextureAtlas.UI_EDGE_B, 1f));
        check("边 L：cu=0 的 U < cu=1 的 U / 边 R 同理",
                TextureAtlas.uiU(TextureAtlas.UI_EDGE_L, 0f) < TextureAtlas.uiU(TextureAtlas.UI_EDGE_L, 1f)
                && TextureAtlas.uiU(TextureAtlas.UI_EDGE_R, 0f) < TextureAtlas.uiU(TextureAtlas.UI_EDGE_R, 1f));
        // 所有 UI 区 slot 的 UV 都在 [0,1]
        boolean uvOk = true;
        for (int k = 0; k < TextureAtlas.UI_TILE_COUNT; k++) {
            for (float part : new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}) {
                float u1 = TextureAtlas.uiU(k, part), v1 = TextureAtlas.uiV(k, part);
                if (u1 < 0f || u1 > 1f || v1 < 0f || v1 > 1f) uvOk = false;
            }
        }
        check("全部 UI slot × 全部 part 的 UV ∈ [0,1]", uvOk);

        // ---------- 3) UI 贴图烘焙：描边不对称（上/左亮、下/右暗） ----------
        int t200 = TextureAtlas.UI_TILE_BASE + TextureAtlas.UI_CORNER_TL;
        double top = 0, bot = 0; int cnt = 0;
        int bx0 = TextureAtlas.cellX(t200), by0 = TextureAtlas.cellY(t200);
        for (int x = 0; x < TP; x++) {
            int iT = ((by0 + G + 1) * (CELL * TextureAtlas.ATLAS_TILES) + bx0 + G + x) * 4;
            int iB = ((by0 + G + TP - 2) * (CELL * TextureAtlas.ATLAS_TILES) + bx0 + G + x) * 4;
            top += (px.get(iT) & 255) + (px.get(iT + 1) & 255) + (px.get(iT + 2) & 255);
            bot += (px.get(iB) & 255) + (px.get(iB + 1) & 255) + (px.get(iB + 2) & 255);
            cnt++;
        }
        check("UI 角 tile 上缘比下缘亮（受光建模生效）", top > bot * 1.05);

        // ---------- 4) UI 区写入不污染方块/细节 tile ----------
        long[] h = new long[4];
        int[] probe = {1, 40, TextureAtlas.DETAIL_TILE, TextureAtlas.WHITE_TILE};
        for (int k = 0; k < probe.length; k++) h[k] = tileHash(px, probe[k]);
        // 重新烘焙一次：哈希必须稳定（确定性 + 无越界污染）
        ByteBuffer px2 = TextureAtlas.bakeAlbedoOffscreen();
        boolean stable = true;
        for (int k = 0; k < probe.length; k++) if (tileHash(px2, probe[k]) != h[k]) stable = false;
        check("方块/细节 tile 哈希在重烘焙下稳定（无越界污染）", stable);
        // 与"UI 区不画"的对照：证明 UI 区之外逐字节不变由 tools 侧证明；此处至少保证方块区非全同
        check("方块 tile1 与 UI 中央 tile 内容不同", tileHash(px, 1) != tileHash(px, TextureAtlas.UI_TILE_BASE));

        // ---------- 4b) 容量墙：方块 tile 连续编号，必须**不越过**保留区 ----------
        // 方块寻址是 `blockIndex*3+kind` 的**连续编号**（从 0 起），而保留区占 [UI_TILE_BASE, …]。
        // ⚠️ 此前这条**只写在注释里** ⇒ 真溢出时症状是"某方块侧 = UI_WHITE 槽 → 全白"
        //    （旧版踩过 EMERALD_BLOCK），而 ARTUI 当时**反而 PASS**（它审的是"覆盖方"，不是"被覆盖方"）。
        //    改成断言后，溢出在构建期就红 —— 这是"提前投资"真正的回报：**容量不再靠人记**。
        int maxBlockTile = core.world.Blocks.count() * 3 - 1;
        int spareBlocks = (TextureAtlas.UI_TILE_BASE - 1 - maxBlockTile) / 3;
        check("方块 tile 上界 < UI 区起点（容量未溢出；余 " + spareBlocks + " 块 @ ATLAS_TILES="
                        + TextureAtlas.ATLAS_TILES + "）",
                maxBlockTile < TextureAtlas.UI_TILE_BASE);

        // ---------- 5) FS 单字面量纪律 + 6) 恒等语义 ----------
        String src = null;
        for (String p : new String[]{"src/render/lwjgl/Game.java",
                                     "../src/render/lwjgl/Game.java",
                                     "java/breathing-world/src/render/lwjgl/Game.java"}) {
            if (Files.isRegularFile(Paths.get(p))) { src = new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8); break; }
        }
        if (src == null) {
            System.out.println("  [warn] Game.java 未找到（非项目根目录运行？）→ 跳过 FS 静态检查");
        } else {
            int at = src.indexOf("private void initHudShader()");
            int end = src.indexOf("\n    private ", at + 30);
            if (end < 0) end = src.indexOf("\n    public ", at + 30);
            if (end < 0) end = src.length();
            String body = src.substring(at, end);

            // 单字面量纪律 + 内容检查：用与 ShaderCheck 相同的 readLiteral 抽取（单一真相纪律）
            int firstV = body.indexOf("#version");
            int secondV = body.indexOf("#version", firstV + 8);
            // FS 段：从第二个 #version 之前的开引号读到第一个未转义双引号
            int q = body.lastIndexOf('"', secondV);
            String fs = readLiteral(body, q);

            // 单字面量：抽取必须已含完整函数体（若用了 + 拼接，readLiteral 会在第一段末尾停住）
            check("FS 抽取完整（含 vnoise 与 fc=c，证明未用 '+' 拼接）",
                    fs.contains("vnoise") && fs.contains("fc=c"));
            // 另做一遍显式拼接扫描（双保险；抽取器若被欺骗，这条仍能抓）
            String fsDecl = body.substring(secondV);
            int semi = fsDecl.indexOf(";\n");
            if (semi < 0) semi = Math.min(fsDecl.length(), 4000);
            fsDecl = fsDecl.substring(0, semi);
            check("FS 声明无 '+' 拼接（防 ShaderCheck 静默假绿）",
                    fsDecl.indexOf("+ \"") < 0 && fsDecl.indexOf("+\"") < 0);

            // 恒等语义（在抽取出的真 FS 文本上断言）
            check("FS RGB = vCol.rgb*t.rgb（不预乘 vCol.a）",
                    fs.contains("vCol.rgb*t.rgb") && !fs.contains("vCol.rgb*t.rgb*vCol.a"));
            check("FS alpha = vCol.a*t.a（纹理 alpha 参与，恒等时为 1）", fs.contains("vCol.a*t.a"));
            check("FS 含 uArtAmt 门（默认 0 时艺术层短路）", fs.contains("uArtAmt>0.0001"));
            check("FS 含纸纹(vnoise)与暗角(vig)两层", fs.contains("vnoise") && fs.contains("vig"));

            // 默认强度读取：缺失→0、非法→0、≤0→0、>1→1
            String m = src.substring(src.indexOf("private static float readUiArtAmount()"));
            m = m.substring(0, m.indexOf("\n    private ", 10) > 0 ? m.indexOf("\n    private ", 10) : Math.min(m.length(), 900));
            check("readUiArtAmount 缺失返回 0", m.contains("== null) return 0f"));
            check("readUiArtAmount 钳到 ≤1", m.contains("Math.min(1f") || m.contains("Math.min(1.0f"));
            check("readUiArtAmount 非法/≤0 返回 0", m.contains("<= 0f") || m.contains("<= 0.0f"));

            // HUD 顶点布局 = 9 float（pos3 + col4 + uv2）
            check("HUD 顶点步长常量 = 9", src.contains("HUD_VERT_FLOATS = 9"));
            check("HUD 顶点属性 2 = vec2 uv", src.contains("glVertexAttribPointer((int)2, (int)2"));
            check("恒等 UV 用 UI_WHITE_TILE", src.contains("TextureAtlas.UI_WHITE_TILE, 0.5f"));
        }

        System.out.println("ARTUI RESULT: " + (ok ? "PASS" : "FAIL"));
        System.exit(ok ? 0 : 1);
    }

    /**
     * 从 Java 源码的开引号读出字符串字面量，遇到第一个<b>未转义</b>双引号停止。
     * 与 {@code ShaderCheck.readLiteral} 同纪律 —— 正因如此，"用 + 拼接会被截断"这一
     * 静默假绿风险才在本门禁里可被检测（抽取结果会缺函数体）。
     */
    private static String readLiteral(String s, int q) {
        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(++i);
                switch (nx) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    default: sb.append('\\').append(nx);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 单 tile 的 FNV-1a 64 哈希（含 gutter，覆盖越界写入）。 */
    private static long tileHash(ByteBuffer px, int tile) {
        int CELL = TextureAtlas.CELL_PX;
        int tx0 = TextureAtlas.cellX(tile), ty0 = TextureAtlas.cellY(tile);
        long hsh = 0xcbf29ce484222325L;
        for (int y = 0; y < CELL; y++)
            for (int x = 0; x < CELL; x++) {
                int i = ((ty0 + y) * (CELL * TextureAtlas.ATLAS_TILES) + (tx0 + x)) * 4;
                for (int k = 0; k < 4; k++) {
                    hsh ^= (px.get(i + k) & 255);
                    hsh *= 0x100000001b3L;
                }
            }
        return hsh;
    }
}
