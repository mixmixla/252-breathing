package core.world;

/**
 * 六向朝向表（第十批，2026-09-24）：<b>方向次序与向量表的唯一来源</b>。
 *
 * <p><b>它解决什么</b>：前九批的元件（比较器 / 活塞 / 漏斗 / 发射器）都<b>没有朝向</b> ——
 * 于是比较器只能"取邻域最高强度"（不是 MC 的 A/B 两路比较）、活塞只能垂直顶推、
 * 漏斗只能从上往下搬。要做出"真·逻辑"，最先缺的不是算法而是<b>朝向</b>这个状态。
 *
 * <p><b>为什么要有这个类</b>：朝向的语义全靠"方向下标 ↔ 世界向量"的对应关系，而这份对应
 * 原本在 {@code FaceCull}、{@code WireSystem}、{@code RedstoneLogicSystem} 各写了一份。
 * 一旦有人只改其中一份，"背面/正面"就会错位 —— 这类 bug 单看任何一张贴图都完全正常，
 * 只在"A 的信号该不该进 B"这种地方悄悄出错。故收敛到本类，其余各处一律引用它。
 *
 * <p><b>次序</b>（与 {@code FaceCull.DX/DY/DZ}、{@code TextureAtlas.tileFor} 的 dir 完全一致）：
 * <pre>
 *   0 = +X   1 = -X   2 = +Y(上)   3 = -Y(下)   4 = +Z   5 = -Z
 * </pre>
 * 相邻成对（0↔1 / 2↔3 / 4↔5）→ {@code opposite(d) == d ^ 1}，这是"背面"判定的全部依据。
 */
public final class Facing {

    private Facing() { }

    /** 方向数（六向）。 */
    public static final int COUNT = 6;

    /** 缺省朝向（+Y 上）。<b>为什么默认是它</b>：第九批的活塞就是"顶推正上方"、漏斗就是"从上方抽"。
     *  于是"没有朝向信息的方块"（旧存档 / 门禁世界 / 别的系统放下的）行为与第九批完全一致 —— 零回归。 */
    public static final int DEFAULT = 2;

    public static final int[] DX = { 1, -1, 0, 0, 0, 0 };
    public static final int[] DY = { 0, 0, 1, -1, 0, 0 };
    public static final int[] DZ = { 0, 0, 0, 0, 1, -1 };

    /** 反向：0↔1 / 2↔3 / 4↔5。{@code opposite(opposite(d)) == d}（幂等）。 */
    public static int opposite(int dir) { return dir ^ 1; }

    /** 由向量分量求方向下标；全零返回 {@link #DEFAULT}。同轴时按 X→Y→Z 固定优先序（确定）。 */
    public static int indexOf(int dx, int dy, int dz) {
        if (dx > 0) return 0;
        if (dx < 0) return 1;
        if (dy > 0) return 2;
        if (dy < 0) return 3;
        if (dz > 0) return 4;
        if (dz < 0) return 5;
        return DEFAULT;
    }

    /**
     * 由视线向量求"最近的主轴方向"（放置朝向用）：取三分量里绝对值最大的那个轴与符号。
     *
     * <p><b>为什么用"主轴"而不是"水平朝向"</b>：本作放置是<b>按格</b>的（放进准星指的那一格），
     * 所以"朝我看的方向"是最直观的规则：平视 → 水平推出去；抬头 → 朝上；低头 → 朝下。
     * 平局时按 Y → X → Z 固定优先（确定，不依赖浮点误差的偶然）。
     */
    public static int fromLook(float lx, float ly, float lz) {
        float ax = Math.abs(lx), ay = Math.abs(ly), az = Math.abs(lz);
        if (ay >= ax && ay >= az) return ly >= 0f ? 2 : 3;
        if (ax >= az) return lx >= 0f ? 0 : 1;
        return lz >= 0f ? 4 : 5;
    }

    /** 方向名（HUD / 探针输出用；用代码里惯用的轴写法，避免"东/上"这类含糊词）。 */
    /**
     * 把任意方向映射到 4 个**水平**方向之一（竖直方向落到 {@code +X}）。
     *
     * <p><b>为什么需要</b>（第十七批）：台阶这类"L 形"方块的朝向只可能是水平的（上半格往哪边偏），
     * 而玩家放置时视线常常是俯视/仰视（{@link #fromLook} 会给出 ±Y）⇒ 必须有一个**收敛点**，
     * 否则台阶会拿到竖直朝向，渲染与碰撞都拿它没辙（症状：放下去形状随机）。
     *
     * <p>放在 {@code Facing} 里（而不是渲染层各写一个 {@code if}）是为了让"什么算水平"只有一处定义 ——
     * 与 {@link #opposite} 同一个纪律。
     */
    public static int horizontal(int dir) {
        if (dir == 0 || dir == 1 || dir == 4 || dir == 5) return dir;   // 已经是水平
        return 0;                                                        // ±Y → +X
    }

    public static String name(int dir) {
        switch (dir) {
            case 0: return "+X";
            case 1: return "-X";
            case 2: return "+Y";
            case 3: return "-Y";
            case 4: return "+Z";
            case 5: return "-Z";
            default: return "?" + dir;
        }
    }
}
