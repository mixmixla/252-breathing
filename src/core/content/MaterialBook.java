package core.content;

import core.world.Blocks;
import java.util.ArrayList;
import java.util.List;

/**
 * 材料规格书 —— {@link MaterialDef} 按<b>方块索引</b>展开成的查表结构（内容平台第 13 类的运行时形态）。
 *
 * <p><b>为什么是数组而不是 Map</b>：挖掘发生在每帧的输入/渲染路径上，查的是
 * {@code World.mat} 里的整数索引。用 {@code float[blockCount]} 直索引做到 O(1) 且零装箱，
 * 与 {@code Blocks.byIndex()} 同一套索引语义（索引一旦分配即固定）。
 *
 * <p><b>消费者（可 grep）</b>：
 * <ul>
 *   <li>{@code render/lwjgl/Game.digHardness(int)} —— 挖掘硬度（替换掉原先的 {@code id.contains(...)} 字符串链）</li>
 *   <li>{@code render/lwjgl/Game.digTick / breakTarget} —— 挖掘与破坏时溅的粒子（按材料区分：土溅灰、叶飞叶、冰溅晶）</li>
 * </ul>
 * 两者都在<b>渲染/输入层</b>，不进仿真、不进 {@code hashState} → 零漂移。
 *
 * <p><b>兜底纪律</b>：没有材料规格的方块<b>绝不报错也不崩</b>，退回
 * {@link MaterialDef#DEFAULT_HARDNESS} / {@code "dust"} —— 与历史行为逐字一致。
 * 缺哪几个方块由 {@link #missing()} 报告（加载期 WARN，便于补齐），而不是静默。
 */
public final class MaterialBook {

    private final int blockCount;
    private final float[] hardness;
    private final String[] digParticle;
    private final int[] density;
    private final String[] cellType;
    /**
     * {@code cellType} 的**预计算布尔视图**（liquid / powder / gas → true）。
     *
     * <p>为什么单独存一份：{@link #displaceable} 跑在 {@code SandFallSystem} 的**热路径**上
     * （每 tick 数十万格扫描），若在那里做 {@code String.equals} 就是"熟路上搬字符串"。
     * 加载期算一次 → 运行期一次数组读。
     */
    private final boolean[] displaceable;
    /**
     * 光衰减系数 {@code [0,1]}，按方块索引 —— Terraria 两扫光照的热路径查表。
     *
     * <p>为什么预展开成数组：{@code World.computeLight} 的两次扫描要遍历<b>整个世界网格</b>
     * （上百万格），在那里做材料表的字符串/对象查表是不可接受的。加载期算一次 → 运行期一次数组读。
     * <p><b>注意</b>：空气也在此表内（{@link MaterialDef#LIGHT_DECAY_AIR}=0.91）——
     * 光穿过空气要衰减，这是两扫光照与旧 BFS 最根本的区别。
     */
    private final float[] lightDecay;
    private final MaterialDef[] defs;      // null = 该方块没有材料规格（用兜底值）
    private final List<String> missing;

    private MaterialBook(int blockCount, float[] hardness, String[] digParticle,
                         int[] density, String[] cellType, boolean[] displaceable,
                         float[] lightDecay,
                         MaterialDef[] defs, List<String> missing) {
        this.blockCount = blockCount;
        this.hardness = hardness; this.digParticle = digParticle;
        this.density = density; this.cellType = cellType;
        this.displaceable = displaceable;
        this.lightDecay = lightDecay;
        this.defs = defs; this.missing = missing;
    }

    /**
     * 构建材料书。{@code defs} 里 id 不在 {@link Blocks} 中的条目由调用方（内容注册表）先剔除。
     *
     * @param list       材料规格列表
     * @param blockCount 方块总数（{@link Blocks#count()}）
     */
    public static MaterialBook from(List<MaterialDef> list, int blockCount) {
        float[] h = new float[blockCount];
        String[] p = new String[blockCount];
        int[] d = new int[blockCount];
        String[] c = new String[blockCount];
        float[] ldec = new float[blockCount];
        MaterialDef[] defs = new MaterialDef[blockCount];
        for (int i = 0; i < blockCount; i++) {                 // 先铺兜底值
            h[i] = MaterialDef.DEFAULT_HARDNESS;
            p[i] = MaterialDef.DEFAULT_DIG_PARTICLE;
            d[i] = MaterialDef.DEFAULT_DENSITY;
            c[i] = "solid";
            ldec[i] = MaterialDef.LIGHT_DECAY_SOLID;
        }
        List<String> missing = new ArrayList<String>();
        if (list != null) {
            for (int k = 0; k < list.size(); k++) {
                MaterialDef m = list.get(k);
                if (m == null) continue;
                Blocks.Block b = Blocks.byId(m.id);
                if (b == null) continue;                        // 未知方块名 → 内容注册表已报错
                int i = b.index;
                if (i < 0 || i >= blockCount) continue;
                defs[i] = m;
                h[i] = m.hardness; p[i] = m.digParticle; d[i] = m.density; c[i] = m.cellType;
                ldec[i] = m.lightDecay;
            }
        }
        for (int i = 0; i < blockCount; i++) {
            if (defs[i] == null) missing.add(Blocks.byIndex(i).id);
        }
        // 预计算"可位移"布尔视图（热路径零字符串操作，见字段注释）
        boolean[] disp = new boolean[blockCount];
        for (int i = 0; i < blockCount; i++) {
            String ct = c[i];
            disp[i] = "liquid".equals(ct) || "powder".equals(ct) || "gas".equals(ct);
        }
        return new MaterialBook(blockCount, h, p, d, c, disp, ldec, defs, missing);
    }

    /** 空书（无内容目录 / 内容为 null 时用）：全部走兜底值 = 历史行为。 */
    public static MaterialBook empty(int blockCount) {
        return from(null, blockCount);
    }

    /** 挖掘硬度（秒，空手基准）。mat 越界 → 兜底值。 */
    public float hardness(int mat) {
        if (mat < 0 || mat >= blockCount) return MaterialDef.DEFAULT_HARDNESS;
        return hardness[mat];
    }

    /** 挖掘/破坏时溅的粒子 id（一定非 null）。 */
    public String digParticle(int mat) {
        if (mat < 0 || mat >= blockCount) return MaterialDef.DEFAULT_DIG_PARTICLE;
        String s = digParticle[mat];
        return s == null ? MaterialDef.DEFAULT_DIG_PARTICLE : s;
    }

    /** 密度（空气 = 1 基准）。 */
    public int density(int mat) {
        if (mat < 0 || mat >= blockCount) return MaterialDef.DEFAULT_DENSITY;
        return density[mat];
    }

    /** 材料物理类别（solid / liquid / gas / plant / powder）。 */
    public String cellType(int mat) {
        if (mat < 0 || mat >= blockCount) return "solid";
        String s = cellType[mat];
        return s == null ? "solid" : s;
    }

    /**
     * 光穿过该材料的衰减系数 {@code (0,1]}（Terraria 两扫光照的 per-material 旋钮）。
     *
     * <p>越界 → {@link MaterialDef#LIGHT_DECAY_SOLID}（保守：当作挡光）。
     * 空气格的材料是 AIR（{@code cellType="gas"}）→ 0.91，这正是"光在空气中也要衰减"的关键。
     */
    public float lightDecay(int mat) {
        if (mat < 0 || mat >= blockCount) return MaterialDef.LIGHT_DECAY_SOLID;
        return lightDecay[mat];
    }

    /**
     * 该材料能否被更重的材料挤开（液体 / 粉末 / 气体）。
     *
     * <p>用于密度分层：{@code solid} 与 {@code plant} 视为固定骨架，其余可位移。
     * 查**加载期预计算的布尔数组** —— 零字符串操作、零分配（它在 {@code SandFallSystem} 的热路径上）。
     */
    public boolean displaceable(int mat) {
        if (mat < 0 || mat >= blockCount) return false;
        return displaceable[mat];
    }

    /**
     * {@code a} 能否沉入 {@code b} —— <b>密度驱动分层的全部秘密</b>。
     *
     * <p>Noita 用这一条替代了成堆的"某某落入某某"专用系统：比一个标量 {@code density} 就够了
     * （见 {@code docs/NOITA_STUDY.md} §2「浮沉只靠一个标量」）。判据：<b>a 更重 且 b 可位移</b>。
     *
     * <p>本项目的实例（当前 25 种材料）：沙(20) 沉入水(10) ✓ · 沙(20) 撞石(26) ✗ ·
     * 冰(9) 浮在水(10) 上（反向调用即得"谁浮起来"）✓ · 金(45) 沉入一切可位移材料 ✓。
     *
     * <p>越界 / 未知材料一律返回 {@code false}（保守：不乱动物理）。
     */
    public boolean sinksInto(int a, int b) {
        if (a == b || a < 0 || b < 0 || a >= blockCount || b >= blockCount) return false;
        if (!displaceable(b)) return false;
        return density(a) > density(b);
    }

    /** 该方块是否有材料规格（false = 用兜底值）。 */
    public boolean has(int mat) {
        return mat >= 0 && mat < blockCount && defs[mat] != null;
    }

    /** 该方块的规格（可能为 null）。 */
    public MaterialDef get(int mat) {
        return (mat < 0 || mat >= blockCount) ? null : defs[mat];
    }

    /** 已登记的规格数。 */
    public int size() {
        int n = 0;
        for (int i = 0; i < blockCount; i++) if (defs[i] != null) n++;
        return n;
    }

    /** 没有材料规格的方块 id（加载期报告用；空列表 = 全覆盖）。 */
    public List<String> missing() { return missing; }
}
