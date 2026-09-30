import render.lwjgl.VoxelVariety;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * 门禁 <b>VARIETY</b>：方块级自发差异（"同材质的不同方块不该逐像素相同"）。
 *
 * <p>这道门禁的存在理由：本特性只把顶点色乘了一个 ±6% / ±5% 的偏移 —— <b>幅度刻意很小</b>
 * （大了会盖过 AO 的四档过渡）。小幅度意味着**肉眼几乎无法判断它到底有没有生效**，
 * 而这类"看起来没问题"的功能最典型的结局是：调用被删/被短路，而所有门禁依然全绿。
 * 所以这里既要验数学性质，也要验<b>接线</b>。
 *
 * <p>断言：
 * <ol>
 *   <li>{@code DET}     —— 确定性：同坐标恒同值（本项目逐字节复现的立身之本）；</li>
 *   <li>{@code RANGE}   —— 幅度落在设计区间（明度 ±6%、色相 ±5%）；</li>
 *   <li>{@code UNIFORM} —— 均匀性：16 桶直方图不得有明显偏斜（防哈希退化）；</li>
 *   <li>{@code DECORR}  —— 相邻方块<b>不相关</b>（有相关性 = 画面会出现规律斑纹）；</li>
 *   <li>{@code NEIGH}   —— 相邻方块"确实不同"（这才是特性的本体）；</li>
 *   <li>{@code WIRED}   —— 两个顶点发射路径 {@code Game.putV} / {@code Chunk.putV}
 *       的方法体里确实调用了它（防"没接上"）。</li>
 * </ol>
 */
public class VarietyCheck {

    private static int fails = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("VARIETY 方块级自发差异门禁");

        // ---- 1. DET 确定性 ----
        boolean det = true;
        for (int i = 0; i < 400; i++) {
            int x = i * 13 - 500, y = i * 7 + 3, z = -i * 11;
            if (VoxelVariety.of(x, y, z) != VoxelVariety.of(x, y, z)) det = false;
            if (VoxelVariety.bright(x, y, z) != VoxelVariety.bright(x, y, z)) det = false;
            if (VoxelVariety.hue(-x, y, z) != VoxelVariety.hue(-x, y, z)) det = false;
        }
        // 非平凡：不能退化成常数
        boolean trivial = VoxelVariety.of(0, 0, 0) == VoxelVariety.of(1, 0, 0)
                && VoxelVariety.of(0, 0, 0) == VoxelVariety.of(0, 0, 1);
        boolean detOk = det && !trivial;
        System.out.println("VARIETY-DET     " + (detOk ? "PASS" : "FAIL")
                + "  同坐标恒同值=" + det + "  非常数=" + !trivial);
        if (!detOk) fails++;

        // ---- 2. RANGE 幅度 ----
        float bLo = 9f, bHi = -9f, hLo = 9f, hHi = -9f;
        for (int x = -30; x < 30; x++)
            for (int y = -30; y < 30; y++)
                for (int z = -30; z < 30; z++) {
                    float b = VoxelVariety.bright(x, y, z);
                    float h = VoxelVariety.hue(x, y, z);
                    if (b < bLo) bLo = b;
                    if (b > bHi) bHi = b;
                    if (h < hLo) hLo = h;
                    if (h > hHi) hHi = h;
                }
        boolean rngOk = bLo >= 0.9395f && bHi <= 1.0605f && hLo >= -0.0505f && hHi <= 0.0505f
                && bHi - bLo > 0.10f && hHi - hLo > 0.09f;   // 同时必须"用满"区间，不能缩在中间
        System.out.printf(java.util.Locale.US,
                "VARIETY-RANGE   %s  明度=[%.4f, %.4f] 色相=[%.4f, %.4f]  want 明度∈[0.94,1.06] 色相∈[-0.05,0.05]%n",
                rngOk ? "PASS" : "FAIL", bLo, bHi, hLo, hHi);
        if (!rngOk) fails++;

        // ---- 3. UNIFORM 均匀性 ----
        final int NB = 16, SIDE = 40;
        int[] hist = new int[NB];
        int total = 0;
        for (int x = -SIDE / 2; x < SIDE / 2; x++)
            for (int y = -SIDE / 2; y < SIDE / 2; y++)
                for (int z = -SIDE / 2; z < SIDE / 2; z++) {
                    float v = VoxelVariety.of(x, y, z);
                    int b = (int) (v * NB);
                    if (b >= NB) b = NB - 1;
                    if (b < 0) b = 0;
                    hist[b]++;
                    total++;
                }
        float expect = total / (float) NB;
        float devMax = 0f;
        StringBuilder hs = new StringBuilder();
        for (int i = 0; i < NB; i++) {
            float dev = Math.abs(hist[i] - expect) / expect;
            if (dev > devMax) devMax = dev;
            hs.append(hist[i]).append(i == NB - 1 ? "" : " ");
        }
        boolean uniOk = devMax <= 0.25f;
        System.out.printf(java.util.Locale.US,
                "VARIETY-UNIFORM %s  最大桶偏差=%.1f%% want<=25%%  直方图=[%s]%n",
                uniOk ? "PASS" : "FAIL", devMax * 100f, hs.toString());
        if (!uniOk) fails++;

        // ---- 4. DECORR 相邻不相关 ----
        double sa = 0, sb = 0, sab = 0, saa = 0, sbb = 0;
        int n = 0;
        for (int x = 0; x < 320; x++)
            for (int y = 0; y < 24; y++) {
                float a = VoxelVariety.of(x, y, 0);
                float b = VoxelVariety.of(x + 1, y, 0);
                sa += a; sb += b; sab += a * b; saa += a * a; sbb += b * b; n++;
            }
        double ma = sa / n, mb = sb / n;
        double cov = sab / n - ma * mb;
        double da = Math.sqrt(Math.max(0, saa / n - ma * ma));
        double db = Math.sqrt(Math.max(0, sbb / n - mb * mb));
        double corr = (da < 1e-9 || db < 1e-9) ? 1.0 : cov / (da * db);
        boolean decOk = Math.abs(corr) < 0.12;
        System.out.printf(java.util.Locale.US,
                "VARIETY-DECORR  %s  相邻方块相关系数=%.4f  |corr|<0.12（有相关会在画面上形成规律斑纹）%n",
                decOk ? "PASS" : "FAIL", corr);
        if (!decOk) fails++;

        // ---- 5. NEIGH 相邻方块"确实不同"（特性本体）----
        int differing = 0, pairs = 0;
        float maxD = 0f;
        for (int x = 0; x < 400; x++)
            for (int y = 0; y < 8; y++) {
                float d = Math.abs(VoxelVariety.bright(x, y, 5) - VoxelVariety.bright(x, y + 1, 5));
                if (d > 1e-7f) differing++;
                if (d > maxD) maxD = d;
                pairs++;
            }
        float frac = differing / (float) pairs;
        boolean nbOk = frac >= 0.99f && maxD > 0.02f;
        System.out.printf(java.util.Locale.US,
                "VARIETY-NEIGH   %s  相邻方块明度不同的比例=%.4f 最大差=%.4f  want>=0.99 且最大差>0.02%n",
                nbOk ? "PASS" : "FAIL", frac, maxD);
        if (!nbOk) fails++;

        // ---- 6. WIRED 接线（防"没接上"）----
        int wired = 0;
        String[] files = {"src/render/lwjgl/Game.java", "src/render/lwjgl/Chunk.java"};
        String[] sigs = {"private static void putV(", "private static void putV("};
        for (int i = 0; i < files.length; i++) {
            File f = new File(files[i]);
            if (!f.isFile()) { System.out.println("  找不到 " + files[i]); continue; }
            String src = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
            String body = methodBody(src, sigs[i]);
            boolean ok = body != null
                    && body.contains("VoxelVariety.bright(")
                    && body.contains("VoxelVariety.hue(");
            if (ok) wired++;
            System.out.println("  " + files[i] + " 的 putV 体内调用 VoxelVariety = " + ok);
        }
        boolean wiredOk = wired == files.length;
        System.out.println("VARIETY-WIRED   " + (wiredOk ? "PASS" : "FAIL")
                + "  " + wired + "/" + files.length + " 条发射路径已接线"
                + "（未接线 = 特性静默失效，而其余断言仍会全绿）");
        if (!wiredOk) fails++;

        if (fails > 0) {
            System.out.println("VARIETY RESULT: FAIL (" + fails + ")");
            System.exit(1);
        }
        System.out.println("VARIETY RESULT: PASS");
    }

    /**
     * 截取方法体（花括号配对）。取<b>最后一次</b>匹配，因为 {@code putV} 还有调用点，
     * 而调用点的形如 {@code Game.putV(...)} 不含 {@code private static void} 前缀 —— 但也有可能命中别处。
     */
    private static String methodBody(String src, String signature) {
        int idx = src.lastIndexOf(signature);
        if (idx < 0) return null;
        int open = src.indexOf('{', idx);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, i + 1);
            }
        }
        return null;
    }
}
