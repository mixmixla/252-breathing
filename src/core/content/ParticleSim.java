package core.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import core.world.World;

/**
 * 配置驱动的粒子<b>纯模型</b> —— 零 GL 依赖，因此可以在无头环境里被门禁断言。
 *
 * <p>这是「粒子系统可配置」的落地：粒子不再写死在渲染器里（旧版是 {@code Game.java}
 * 的一个内部类，重力 6.0 写死、上限 90、颜色固定），而是按
 * {@code assets/content/particles/*.json} 里的发射器参数生成与演化。
 *
 * <p>存储用<b>平铺数组</b>（SoA）而非对象列表：512 个粒子零对象分配，
 * 渲染层只按索引读取，不产生 GC 抖动。
 *
 * <p><b>零 RNG 纪律</b>：所有随机（散布方向、初速、寿命、尺寸）走
 * {@link EffectQueue#hash01} 纯哈希派生，<b>不碰 {@code fxRng} 也不碰 {@code simStream}</b>。
 *
 * <p>字段语义见 {@code docs/CONTENT_PLATFORM.md} §3.1。
 */
public final class ParticleSim {

    /** 粒子池容量（超出则丢弃新粒子，绝不越界）。 */
    public static final int CAP = 512;

    private final float[] px = new float[CAP], py = new float[CAP], pz = new float[CAP];
    private final float[] vx = new float[CAP], vy = new float[CAP], vz = new float[CAP];
    private final float[] life = new float[CAP], maxLife = new float[CAP];
    private final float[] sz0 = new float[CAP], sz1 = new float[CAP];
    private final float[] grav = new float[CAP], drag = new float[CAP];
    private final float[] r0 = new float[CAP], g0 = new float[CAP], b0 = new float[CAP], a0 = new float[CAP];
    private final float[] r1 = new float[CAP], g1 = new float[CAP], b1 = new float[CAP], a1 = new float[CAP];
    // 死键落地（内容平台 §2.1）：每个粒子记录其发射器样式，渲染层据此表现。
    // 缺省值 = 旧行为（点团 / 普通 alpha 混合 / 线性淡出），故键缺失时像素逐字节不变（零漂移）。
    private final byte[]  fmt   = new byte[CAP];   // 0=point 1=streak 2=soft
    private final boolean[] glow  = new boolean[CAP]; // material: "glow"/"add" → 加色发光
    private final boolean[] collide = new boolean[CAP]; // 是否做地形碰撞停驻（需要 World 地面）
    private final float[] fadeIn  = new float[CAP];  // 生命前 fadeIn 比例（0=不淡入）
    private final float[] fadeOut = new float[CAP];  // 生命后 fadeOut 比例（0=不淡出）
    private final boolean[] burst = new boolean[CAP]; // 是否「爆发」：去除上抛偏置，纯径向炸开

    private int n = 0;
    private long seed;
    private int salt = 0;
    private World world;   // 可选：供 collide 做地形碰撞停驻（无头测试不注入）
    private int spawned = 0;
    private int dropped = 0;

    public ParticleSim(long seed) { this.seed = seed; }

    public void setSeed(long s) { this.seed = s; }

    // ------------------------------------------------------------------
    // 发射
    // ------------------------------------------------------------------

    /** 按定义发射（数量取 def 的 count）。 */
    public int spawn(JsonObject def, float x, float y, float z) { return spawn(def, x, y, z, 0, false); }

    /** 按定义发射，并指定是否「爆发」（emitter 的 burst 标记位 → 纯径向炸开、去上抛偏置）。 */
    public int spawn(JsonObject def, float x, float y, float z, boolean burst) { return spawn(def, x, y, z, 0, burst); }

    /**
     * 按定义发射一团粒子。
     *
     * @param def           发射器定义（见 CONTENT_PLATFORM §3.1）；{@code null} → 不发射
     * @param countOverride {@code >0} 时覆盖 def 的 {@code count}（同一材质不同用量的场景）
     * @return 实际生成的粒子数（受 {@link #CAP} 限制）
     */
    public int spawn(JsonObject def, float x, float y, float z, int countOverride) { return spawn(def, x, y, z, countOverride, false); }

    /**
     * 按定义发射一团粒子（真正实现）。
     *
     * @param def           发射器定义（见 CONTENT_PLATFORM §3.1）；{@code null} → 不发射
     * @param countOverride {@code >0} 时覆盖 def 的 {@code count}（同一材质不同用量的场景）
     * @param burst         是否「爆发」：去除默认上抛偏置，改为纯径向炸开
     * @return 实际生成的粒子数（受 {@link #CAP} 限制）
     */
    private int spawn(JsonObject def, float x, float y, float z, int countOverride, boolean isBurst) {
        if (def == null) return 0;
        int cnt = countOverride > 0 ? countOverride : (int) f(def, "count", 8f);
        float[] lifeR = range(def, "life", 0.4f, 1.0f);
        float[] spdR  = range(def, "speed", 1.0f, 3.0f);
        float g       = f(def, "gravity", -6.0f);
        float dg      = f(def, "drag", 0.0f);
        float[] szR   = range(def, "size", 0.06f, 0.14f);
        float[] szE   = range(def, "sizeEnd", Float.NaN, Float.NaN);   // 可选的终末尺寸
        boolean hasEnd = !Float.isNaN(szE[0]);
        float[] c0    = color(def, "color0", 1f, 1f, 1f, 1f);
        float[] c1    = color(def, "color1", 1f, 1f, 1f, 0f);
        String shape  = str(def, "shape", "point");
        // 死键落地（§2.1）：发射器样式，缺省 = 旧行为。
        String material = str(def, "material", "alpha");     // alpha(普通) / glow(加色发光)
        String format   = str(def, "format", "point");       // point / streak / soft
        // 死键落地（§2.1）：offset 是「三元向量」[dx,dy,dz]，不是二元区间！
        // 旧实现误用 range()（只返回 2 元）后取 off[2] → 每次 spawn 必抛
        // ArrayIndexOutOfBoundsException: 2（FX 门禁红灯 + 游戏内粒子全不可用）。
        float[] off     = vec3(def, "offset");
        boolean doColl  = def.has("collide") && def.get("collide").getAsBoolean();
        float[] fade    = range(def, "fade", 0f, 0f);        // [淡入比例, 淡出比例]（0=线性旧行为）

        int made = 0;
        for (int k = 0; k < cnt; k++) {
            if (n >= CAP) { dropped += (cnt - k); break; }   // 剩余全计入丢弃（不静默少算）
            int s = salt++;
            float s01 = EffectQueue.hash01(seed, s, 11);
            float s02 = EffectQueue.hash01(seed, s, 12);
            float s03 = EffectQueue.hash01(seed, s, 13);
            float s04 = EffectQueue.hash01(seed, s, 14);
            float spd = spdR[0] + (spdR[1] - spdR[0]) * s02;
            float ang = s01 * 6.2831855f;

            float dx, dy, dz;
            if ("sphere".equals(shape)) {                       // 球面均匀
                float cosT = s03 * 2f - 1f;
                float sinT = (float) Math.sqrt(Math.max(0f, 1f - cosT * cosT));
                dx = (float) StrictMath.cos(ang) * sinT;
                dy = cosT;
                dz = (float) StrictMath.sin(ang) * sinT;
            } else if ("ring".equals(shape)) {                   // 水平环
                dx = (float) StrictMath.cos(ang);
                dy = 0.15f;
                dz = (float) StrictMath.sin(ang);
            } else if ("box".equals(shape)) {                    // 盒内随机
                dx = s01 * 2f - 1f;
                dy = s03 * 1.5f;
                dz = s02 * 2f - 1f;
            } else {                                            // point：水平四散 + 上抛
                dx = (float) StrictMath.cos(ang);
                dy = isBurst ? 0f : (1f + s03 * 0.8f);          // 爆发：去上抛偏置 → 纯径向炸开
                dz = (float) StrictMath.sin(ang);
            }

            int i = n++;
            px[i] = x + off[0]; py[i] = y + off[1]; pz[i] = z + off[2];
            vx[i] = dx * spd; vy[i] = dy * spd; vz[i] = dz * spd;
            maxLife[i] = lifeR[0] + (lifeR[1] - lifeR[0]) * s04;
            life[i] = maxLife[i];
            sz0[i] = szR[0] + (szR[1] - szR[0]) * s04;
            sz1[i] = hasEnd ? (szE[0] + (szE[1] - szE[0]) * s04) : sz0[i];   // 终末尺寸（默认不变）
            grav[i] = g; drag[i] = dg;
            r0[i] = c0[0]; g0[i] = c0[1]; b0[i] = c0[2]; a0[i] = c0[3];
            r1[i] = c1[0]; g1[i] = c1[1]; b1[i] = c1[2]; a1[i] = c1[3];
            // 死键落地：每个粒子记录发射器样式（缺省值 = 旧行为，故未配置时像素逐字节不变）
            fmt[i]   = (byte) ("streak".equals(format) ? 1 : "soft".equals(format) ? 2 : 0);
            glow[i]  = "glow".equals(material) || "add".equals(material) || "additive".equals(material);
            collide[i] = doColl;
            burst[i]  = isBurst;
            fadeIn[i]  = fade[0]; fadeOut[i] = fade[1];
            spawned++;
            made++;
        }
        return made;
    }

    // ------------------------------------------------------------------
    // 演化
    // ------------------------------------------------------------------

    /** 注入世界引用（供 collide 做地形碰撞停驻）。无头测试可不调用 → world=null → 不做碰撞。 */
    public void setWorld(World w) { this.world = w; }

    /** 推进一帧（无世界 → 不做地形碰撞，保持无头门禁行为）。 */
    public void update(float dt) { update(dt, world); }

    /**
     * 推进一帧。寿命耗尽的粒子用 swap-remove 回收（O(1)，顺序无关）。
     * 若 w 非空且粒子带 collide 标记，落到地面顶面即停驻（去竖直速度 + 水平摩擦）。
     */
    public void update(float dt, World w) {
        for (int i = n - 1; i >= 0; i--) {
            vy[i] += grav[i] * dt;
            float damp = Math.max(0f, 1f - drag[i] * dt);
            vx[i] *= damp; vy[i] *= damp; vz[i] *= damp;
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt;
            if (w != null && collide[i]) {
                float fy = w.floorY(px[i], pz[i], 0.1f, py[i] + 0.2f);
                if (py[i] < fy) { py[i] = fy; vy[i] = 0f; vx[i] *= 0.5f; vz[i] *= 0.5f; }
            }
            life[i] -= dt;
            if (life[i] <= 0f) {
                int last = --n;
                if (i != last) move(last, i);
            }
        }
    }

    private void move(int from, int to) {
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from];
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from];
        life[to] = life[from]; maxLife[to] = maxLife[from];
        sz0[to] = sz0[from]; sz1[to] = sz1[from];
        grav[to] = grav[from]; drag[to] = drag[from];
        r0[to] = r0[from]; g0[to] = g0[from]; b0[to] = b0[from]; a0[to] = a0[from];
        r1[to] = r1[from]; g1[to] = g1[from]; b1[to] = b1[from]; a1[to] = a1[from];
        fmt[to] = fmt[from]; glow[to] = glow[from]; collide[to] = collide[from];
        fadeIn[to] = fadeIn[from]; fadeOut[to] = fadeOut[from]; burst[to] = burst[from];
    }

    // ------------------------------------------------------------------
    // 渲染读取（按索引；进度 t = 1 - life/maxLife）
    // ------------------------------------------------------------------

    public int count()    { return n; }
    public int capacity() { return CAP; }
    public int spawnedTotal() { return spawned; }
    public int droppedTotal() { return dropped; }
    public void clear()   { n = 0; }

    public float x(int i) { return px[i]; }
    public float y(int i) { return py[i]; }
    public float z(int i) { return pz[i]; }
    public float vx(int i) { return vx[i]; }
    public float vy(int i) { return vy[i]; }
    public float vz(int i) { return vz[i]; }
    public float lifeLeft(int i) { return life[i]; }
    public float lifeMax(int i) { return maxLife[i]; }

    /** 生命进度 0→1。 */
    public float t(int i) {
        float ml = maxLife[i];
        return ml <= 0f ? 1f : 1f - life[i] / ml;
    }

    /** 当前尺寸（sz0 → sz1 线性插值）。 */
    public float size(int i) { return sz0[i] + (sz1[i] - sz0[i]) * t(i); }
    public float red(int i)   { return r0[i] + (r1[i] - r0[i]) * t(i); }
    public float green(int i) { return g0[i] + (g1[i] - g0[i]) * t(i); }
    public float blue(int i)  { return b0[i] + (b1[i] - b0[i]) * t(i); }
    /**
     * 当前不透明度。默认（fadeIn=fadeOut=0）即旧行为 a0→a1 线性；
     * 配置了 fade 才在生命前/后段做淡入/淡出（乘子，不影响未配置时的像素）。
     */
    public float alpha(int i) {
        float a = a0[i] + (a1[i] - a0[i]) * t(i);
        float tt = t(i);
        float fi = fadeIn[i], fo = fadeOut[i];
        if (fi > 0f && tt < fi)  a *= Math.min(1f, tt / fi);          // 淡入
        if (fo > 0f && tt > 1f - fo) a *= Math.min(1f, (1f - tt) / fo); // 淡出
        return a;
    }
    // 死键读取（渲染层消费）
    public int  format(int i)   { return fmt[i]; }
    public boolean glow(int i)  { return glow[i]; }
    public boolean collide(int i){ return collide[i]; }
    public boolean burst(int i)  { return burst[i]; }

    // ------------------------------------------------------------------
    // JSON 取值助手
    // ------------------------------------------------------------------

    private static float f(JsonObject o, String k, float dflt) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return dflt;
        try { return e.getAsFloat(); } catch (RuntimeException ex) { return dflt; }
    }

    private static String str(JsonObject o, String k, String dflt) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? dflt : e.getAsString();
    }

    /** 读 {@code [min,max]} 区间；缺省或非法 → {@code [d0,d1]}。 */
    private static float[] range(JsonObject o, String k, float d0, float d1) {
        JsonElement e = o.get(k);
        if (e != null && e.isJsonArray() && e.getAsJsonArray().size() >= 2) {
            try {
                return new float[]{ e.getAsJsonArray().get(0).getAsFloat(),
                                    e.getAsJsonArray().get(1).getAsFloat() };
            } catch (RuntimeException ignored) { }
        }
        return new float[]{ d0, d1 };
    }

    /**
     * 读三元向量（如 offset）。缺省 / 长度不足 / 非数值 → {0,0,0}。
     *
     * <p><b>为何不能用 {@link #range}</b>：range 只读前两个元素并返回长度 2 的数组，
     * 而向量消费方会读 [0][1][2] —— 这正是 2026-09-17 定位到的真 bug
     * （offset 越界致使用者每次 spawn 崩溃）。方向量与区间是两种语义，必须分开。
     */
    private static float[] vec3(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e != null && e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
            try {
                return new float[]{ e.getAsJsonArray().get(0).getAsFloat(),
                                    e.getAsJsonArray().get(1).getAsFloat(),
                                    e.getAsJsonArray().get(2).getAsFloat() };
            } catch (RuntimeException ignored) { }
        }
        return new float[]{ 0f, 0f, 0f };
    }

    /** 读 4 分量颜色；缺省 → 默认值。 */
    private static float[] color(JsonObject o, String k, float r, float g, float b, float a) {
        JsonElement e = o.get(k);
        if (e != null && e.isJsonArray() && e.getAsJsonArray().size() >= 4) {
            try {
                return new float[]{ e.getAsJsonArray().get(0).getAsFloat(),
                                    e.getAsJsonArray().get(1).getAsFloat(),
                                    e.getAsJsonArray().get(2).getAsFloat(),
                                    e.getAsJsonArray().get(3).getAsFloat() };
            } catch (RuntimeException ignored) { }
        }
        return new float[]{ r, g, b, a };
    }
}
