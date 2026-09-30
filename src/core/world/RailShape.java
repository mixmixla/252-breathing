package core.world;

/**
 * 轨道的<b>形状</b>与<b>出口方向</b>（第二十五批）—— 由四邻轨道的连接掩码<b>惰性推导</b>。
 *
 * <p><b>为什么不像 MC 那样把形状存起来</b>：MC 把形状存成 block state（10 种），放置时求解一次；
 * 拆掉邻居后形状就<b>过时</b>了，只能靠"邻居变化时去更新自己"来修。本项目<b>不存</b>：
 * 每次需要时用 {@link #exitDir(int, int)} 从掩码现算。于是
 * ① <b>零新状态</b>、② 拆轨/放轨<b>自动正确</b>（不会留下"记忆中的旧形状"）、
 * ③ 判据是<b>纯函数</b> ⇒ 门禁可以穷举全部 16 个掩码 × 4 个入射方向（4×16=64 种情形）。
 *
 * <p><b>与 MC 的三处有意偏离</b>（都是"MC 那条规则在这里不需要存在"，不是省略功能）：
 * <ol>
 *   <li>MC 的"从弯的<b>背面</b>进入则直穿" —— 它对应 MC 自己的连接模型（直线轨可能"够到"弯轨
 *       却没真正连上）。本项目的连接<b>就是</b>掩码：背面没有轨道 ⇒ 矿车不可能从背面进来；</li>
 *   <li>MC 的 "<b>south-east 规则</b>"（多个出口时按固定东南偏好转向）—— 本项目在<b>十字</b>口
 *       直接<b>直穿</b>（保持原方向），语义更直观；</li>
 *   <li>MC 的 "<b>下坡优先规则</b>"（路口总是走能下坡的那条）—— 那是配合 MC 的坡轨与
 *       "路口 = 一格多出口"模型；本项目坡轨尚未落地（见类注释末尾），十字直穿已足够确定。</li>
 * </ol>
 *
 * <p><b>坡轨（未落地）</b>：掩码里已经预留了"上下层"的位置（{@link #ofNeighbors} 只收水平四邻），
 * 但坡轨的<b>渲染是几何改动</b>（斜薄片 ⇒ 需要新的"倾斜 quad 发射器"），与"改逻辑/贴图"不同粒度，
 * 故单独一批。矿车上下坡的运动钩子将落在 {@link #exitDir} 的调用点（{@code World.updateCarts}）。
 */
public final class RailShape {

    /** 掩码位：bit0=+X  bit1=-X  bit2=+Z  bit3=-Z（与 {@link Facing} 的 0 / 1 / 4 / 5 一一对应）。 */
    public static final int BIT_PX = 1, BIT_NX = 2, BIT_PZ = 4, BIT_NZ = 8;

    /** 水平 {@link Facing} 方向 → 它对应的掩码位；非水平方向 → 0。 */
    public static int bitFor(int facing) {
        switch (facing) {
            case 0: return BIT_PX;
            case 1: return BIT_NX;
            case 4: return BIT_PZ;
            case 5: return BIT_NZ;
            default: return 0;
        }
    }

    /** 四邻是否是轨道 → 掩码（纯函数；采样世界的代码在 {@code World.railMask}）。 */
    public static int ofNeighbors(boolean px, boolean nx, boolean pz, boolean nz) {
        int m = 0;
        if (px) m |= BIT_PX;
        if (nx) m |= BIT_NX;
        if (pz) m |= BIT_PZ;
        if (nz) m |= BIT_NZ;
        return m;
    }

    /** 掩码是否是"<b>弯</b>"：X 轴恰好一个方向 + Z 轴恰好一个方向（垂直两腿）。 */
    public static boolean isCurve(int mask) {
        int xm = mask & 3, zm = mask & 12;
        return (xm == 1 || xm == 2) && (zm == 4 || zm == 8);
    }

    /**
     * 矿车以水平方向 {@code dir} 驶入掩码为 {@code mask} 的轨道格，返回<b>离开方向</b>（水平 facing）；
     * {@code -1} = <b>无出口</b>（该格是终点，车应停下）。
     *
     * <p><b>规则（按优先级）</b>：
     * <ol>
     *   <li><b>弯</b>（垂直两腿）⇒ 车<b>必转向另一轴</b>：沿 X 进入 → 出该弯的唯一 Z 腿，反之亦然。
     *       这就是 MC 弯轨的语义（车沿 L 形路径走到格中再拐）；</li>
     *   <li>否则若<b>前方有轨道</b>（掩码含 {@code dir} 位）⇒ <b>直穿</b>（保持 dir）。
     *       直线、十字、T 字都走这条 ⇒ 语义直观、且与"矿车一根筋往前走"一致；</li>
     *   <li>否则取掩码里的任一位（<b>确定序</b>：+X → -X → +Z → -Z）⇒ T 字从"没有前路"的一侧进入时，
     *       按固定偏好选 +X 那一侧（MC 也有固定偏好，只是顺序不同）；</li>
     *   <li>掩码为 0（孤立轨）⇒ {@code -1}。</li>
     * </ol>
     */
    public static int exitDir(int mask, int dir) {
        if (mask == 0) return -1;
        if (isCurve(mask)) {
            int xm = mask & 3, zm = mask & 12;
            if (dir == 0 || dir == 1) return (zm == BIT_PZ) ? 4 : 5;   // 沿 X 进 → 出唯一 Z 腿
            return (xm == BIT_PX) ? 0 : 1;                              // 沿 Z 进 → 出唯一 X 腿
        }
        if ((mask & bitFor(dir)) != 0) return dir;                      // 前方有轨道 → 直穿
        // ⚠️ 必须**排除来路**：末端的直线轨掩码里只剩"来路那一位"（车从那儿来），
        //    若不排除就会算出"出口 = 来路" ⇒ 矿车到站**掉头**（既有 CART-RUN 断言的"到站停车"会破）。
        //    排除后 cand == 0 ⇒ 返回 -1 ⇒ 车停 —— 与改动前的"下一格不是轨道即停"逐字等价。
        int cand = mask & ~bitFor(Facing.opposite(dir));
        if (cand == 0) return -1;
        if ((cand & BIT_PX) != 0) return 0;
        if ((cand & BIT_NX) != 0) return 1;
        if ((cand & BIT_PZ) != 0) return 4;
        if ((cand & BIT_NZ) != 0) return 5;
        return -1;
    }

    /**
     * 掩码的<b>规范朝向索引</b>（0..15）= 掩码本身。
     *
     * <p>之所以不做"归一化到更少的形状"：本项目的轨道贴图走 {@code RAIL_TILE_BASE + mask} 的
     * <b>16 张臂形库</b>（与红石导线臂形库同一范式，见 {@code TextureAtlas.wireTile}）——
     * 16 张换来"零归一化逻辑"，且每张只画"中心 + 朝掩码各位的臂"。
     */
    public static int tileIndex(int mask) { return mask & 0xF; }

    /** 诊断/门禁用：掩码的可读名（如 {@code "+X-Z"}、{@code "CURVE(+X,+Z)"}）。 */
    public static String name(int mask) {
        if (mask == 0) return "ISOLATED";
        StringBuilder sb = new StringBuilder();
        if (isCurve(mask)) sb.append("CURVE(");
        if ((mask & BIT_PX) != 0) sb.append("+X");
        if ((mask & BIT_NX) != 0) sb.append("-X");
        if ((mask & BIT_PZ) != 0) sb.append("+Z");
        if ((mask & BIT_NZ) != 0) sb.append("-Z");
        if (isCurve(mask)) sb.append(")");
        return sb.toString();
    }

    private RailShape() { }
}
