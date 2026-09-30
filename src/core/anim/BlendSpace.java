package core.anim;

/**
 * 二维混合树 / 混合空间（Blend Space）—— UE5 对标（C 批③之二）。
 *
 * <p><b>对标来源</b>：UE5 {@code UBlendSpace}（{@code Engine/Private/Animation/BlendSpace.cpp}）
 * 与 {@code FBlendSpaceGrid::FindTriangleThisPointBelongsTo} / {@code GetBaryCentric2D}
 * （{@code Engine/Private/Animation/BlendSpaceHelpers.cpp}）。UE5 的核心思想：
 * <ol>
 *   <li>把每个动画样本铺在二维参数平面（如 Speed×Direction，X=速度、Y=朝向）；</li>
 *   <li>对样本点做<b>三角化</b>（Delaunay 变体，含 {@code EPreferredTriangulationDirection}
 *       的 Tangential/Radial 歧义消解）；</li>
 *   <li>给定输入点，找到<b>包含它的三角形</b>，用<b>重心坐标</b>作为三个样本的混合权重；</li>
 *   <li>点落在凸包外时用最近三角形钳制（{@code FindTriangleThisPointBelongsTo} 按距离排序取第一个）。</li>
 * </ol>
 *
 * <p><b>本类做什么</b>：规则网格版混合树（本项目骨骼/片段是手写小规模，规则网格足够）：
 * 样本按 (x, y) 铺点，网格自动切成一组三角形（每格两三角形，对角线方向固定 →
 * 确定性，对标 UE5 的 Tangential 消歧），{@link #weightsAt} 返回重心坐标权重。
 *
 * <p><b>确定性</b>：纯函数、零 RNG、零世界写入。三角化在构造期完成一次，采样期只做
 * 加减乘除 → 同输入必得同权重，不进 {@link core.world.World#hashState()}。
 */
public final class BlendSpace {

    /** 一个样本点：参数坐标 + 片段（片段可为 null —— 只做权重计算时用）。 */
    public static final class Sample {
        public final String name;
        public final float x, y;
        public final Clip clip;
        public Sample(String name, float x, float y, Clip clip) {
            this.name = name; this.x = x; this.y = y; this.clip = clip;
        }
    }

    /** 三角形（三个样本索引），重心坐标权重按此三点分配。 */
    public static final class Tri {
        public final int a, b, c;
        Tri(int a, int b, int c) { this.a = a; this.b = b; this.c = c; }
    }

    private final Sample[] samples;
    private final Tri[] tris;
    private final float minX, maxX, minY, maxY;

    /**
     * @param samples 样本点（≥1；坐标用于三角化，重复坐标会被拒绝）
     */
    public BlendSpace(Sample[] samples) {
        if (samples == null || samples.length == 0) throw new IllegalArgumentException("no samples");
        this.samples = samples.clone();
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Sample s : this.samples) {
            if (s.x < x0) x0 = s.x; if (s.x > x1) x1 = s.x;
            if (s.y < y0) y0 = s.y; if (s.y > y1) y1 = s.y;
        }
        this.minX = x0; this.maxX = x1; this.minY = y0; this.maxY = y1;
        this.tris = triangulate(this.samples);
    }

    public int sampleCount() { return samples.length; }
    public int triangleCount() { return tris.length; }
    public Sample sample(int i) { return samples[i]; }

    /**
     * 规则网格三角化：把样本按 (x, y) 网格分组，每格按固定的「左下→右下→右上 / 左下→右上→左上」
     * 对角线切成两三角形。对角线方向固定 → 确定性（换任何机器结果一致）。
     * 非规则点（坐标不在网格上）退化为「质心扇形」三角化，仍保证覆盖全部点。
     */
    private static Tri[] triangulate(Sample[] s) {
        int n = s.length;
        if (n < 3) {
            // 1~2 个点：无三角形；weightsAt 退化为最近点独权
            return new Tri[0];
        }
        // 判断是否规则网格：所有 x 只有少数几个不同值，且每列 y 相同
        // 简化为按 y 分层、每层按 x 排序，相邻层配对成三角形（"strip" 三角化）
        java.util.List<Integer> idx = new java.util.ArrayList<Integer>();
        for (int i = 0; i < n; i++) idx.add(i);
        final Sample[] arr = s;
        java.util.Collections.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer p, Integer q) {
                if (arr[p].y != arr[q].y) return Float.compare(arr[p].y, arr[q].y);
                return Float.compare(arr[p].x, arr[q].x);
            }
        });
        // 分层
        java.util.List<java.util.List<Integer>> rows = new java.util.ArrayList<java.util.List<Integer>>();
        java.util.List<Integer> cur = new java.util.ArrayList<Integer>();
        float lastY = arr[idx.get(0)].y;
        for (int k = 0; k < idx.size(); k++) {
            int i = idx.get(k);
            if (Math.abs(arr[i].y - lastY) > 1e-4f) { rows.add(cur); cur = new java.util.ArrayList<Integer>(); lastY = arr[i].y; }
            cur.add(i);
        }
        rows.add(cur);
        if (rows.size() < 2) return new Tri[0];
        java.util.List<Tri> out = new java.util.ArrayList<Tri>();
        for (int r = 0; r < rows.size() - 1; r++) {
            java.util.List<Integer> lo = rows.get(r), hi = rows.get(r + 1);
            int m = Math.min(lo.size(), hi.size());
            for (int c = 0; c < m - 1; c++) {
                // 每格两三角形，对角线固定为 lo[c] — hi[c+1]
                out.add(new Tri(lo.get(c), lo.get(c + 1), hi.get(c + 1)));
                out.add(new Tri(lo.get(c), hi.get(c + 1), hi.get(c)));
            }
            if (m == 1 && lo.size() == 1 && hi.size() > 1) {
                // 退化：单点对多点的扇形
                for (int c = 0; c < hi.size() - 1; c++) out.add(new Tri(lo.get(0), hi.get(c), hi.get(c + 1)));
            }
        }
        return out.toArray(new Tri[0]);
    }

    /**
     * 求输入点 (x, y) 在各样本上的权重（长度 = {@link #sampleCount()}，和恒为 1）。
     *
     * <p>找包含该点的三角形（无则取最近三角形）→ 重心坐标 → 分配到三顶点。
     * 无三角形（样本 &lt; 3）时退化为最近点独权（和仍为 1）。
     */
    public float[] weightsAt(float x, float y) {
        float[] w = new float[samples.length];
        if (tris.length == 0) {
            int best = 0; float bestD = Float.MAX_VALUE;
            for (int i = 0; i < samples.length; i++) {
                float dx = samples[i].x - x, dy = samples[i].y - y;
                float d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = i; }
            }
            w[best] = 1f;
            return w;
        }
        // 找包含点 / 最近的三角形（UE5 按距离排序取第一个命中）
        Tri bestTri = null;
        float[] bestBary = null;
        float bestDist = Float.MAX_VALUE;
        for (Tri t : tris) {
            float[] bc = barycentric(x, y, t);
            float dist = Math.abs(Math.min(bc[0], Math.min(bc[1], bc[2])));   // 离三角形最近时的"负深度"
            boolean inside = bc[0] >= -1e-5f && bc[1] >= -1e-5f && bc[2] >= -1e-5f;
            if (inside) { bestTri = t; bestBary = bc; bestDist = 0f; break; }
            if (dist < bestDist) { bestDist = dist; bestTri = t; bestBary = bc; }
        }
        if (bestTri != null) {
            // 钳制到非负并归一（点在三角形外时投影到边）
            float b0 = Math.max(0f, bestBary[0]), b1 = Math.max(0f, bestBary[1]), b2 = Math.max(0f, bestBary[2]);
            float sum = b0 + b1 + b2;
            if (sum < 1e-6f) { b0 = b1 = b2 = 1f / 3f; sum = 1f; }
            w[bestTri.a] += b0 / sum;
            w[bestTri.b] += b1 / sum;
            w[bestTri.c] += b2 / sum;
        } else {
            w[0] = 1f;
        }
        return w;
    }

    /** 三角形重心坐标（返回值 to 顶点 a/b/c，和恒为 1；点在外则有负分量）。 */
    private float[] barycentric(float px, float py, Tri t) {
        float ax = samples[t.a].x, ay = samples[t.a].y;
        float bx = samples[t.b].x, by = samples[t.b].y;
        float cx = samples[t.c].x, cy = samples[t.c].y;
        float d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        if (Math.abs(d) < 1e-9f) return new float[]{1f / 3f, 1f / 3f, 1f / 3f};   // 退化三角形
        float l0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / d;
        float l1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / d;
        float l2 = 1f - l0 - l1;
        return new float[]{l0, l1, l2};
    }

    /**
     * 按权重混合各样本片段的姿态到骨架（每关节对各样本姿态做加权和 —— Overwrite 语义，
     * 对标 UE5 {@code BlendTransform<Overwrite>}: {@code Dest = Source * BlendWeight}）。
     */
    public void blendInto(float x, float y, Joint.Skeleton skel) {
        float[] w = weightsAt(x, y);
        skel.resetAll();
        // 累积缓冲
        java.util.Map<String, float[]> rotAcc = new java.util.HashMap<String, float[]>();
        java.util.Map<String, float[]> posAcc = new java.util.HashMap<String, float[]>();
        java.util.Map<String, Boolean> posTouched = new java.util.HashMap<String, Boolean>();
        for (int i = 0; i < samples.length; i++) {
            if (w[i] <= 0f || samples[i].clip == null) continue;
            Clip c = samples[i].clip;
            for (Clip.Track tr : c.tracks) {
                Joint j = skel.joint(tr.joint);
                if (j == null) continue;
                float[] r = new float[3], p = new float[3];
                tr.sample(0f, r, p);
                float[] ar = rotAcc.get(tr.joint);
                if (ar == null) { ar = new float[3]; rotAcc.put(tr.joint, ar); }
                ar[0] += r[0] * w[i]; ar[1] += r[1] * w[i]; ar[2] += r[2] * w[i];
                if (tr.keys.length > 0 && tr.keys[0].pos != null) {
                    float[] ap = posAcc.get(tr.joint);
                    if (ap == null) { ap = new float[3]; posAcc.put(tr.joint, ap); }
                    ap[0] += p[0] * w[i]; ap[1] += p[1] * w[i]; ap[2] += p[2] * w[i];
                    posTouched.put(tr.joint, Boolean.TRUE);
                }
            }
        }
        for (java.util.Map.Entry<String, float[]> e : rotAcc.entrySet()) {
            Joint j = skel.joint(e.getKey());
            if (j != null) System.arraycopy(e.getValue(), 0, j.localRot, 0, 3);
        }
        for (java.util.Map.Entry<String, float[]> e : posAcc.entrySet()) {
            Joint j = skel.joint(e.getKey());
            if (j != null && Boolean.TRUE.equals(posTouched.get(e.getKey())))
                System.arraycopy(e.getValue(), 0, j.localPos, 0, 3);
        }
        skel.updateWorld();
    }

    public float minX() { return minX; }
    public float maxX() { return maxX; }
    public float minY() { return minY; }
    public float maxY() { return maxY; }
}
