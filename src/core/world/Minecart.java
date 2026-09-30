package core.world;

/**
 * 矿车（第二十一批）—— 沿轨道滑行的**载具实体**。
 *
 * <p><b>为什么是实体而不是方块</b>：它的位置是**连续**的（每 tick 在格内推进），
 * 而方块的语义是"占据整格"。硬做成方块会得到"一格一格跳"的荒谬运动。
 *
 * <p><b>确定性</b>：与 {@link Beast} / {@code Npc} 同层 —— 实体层<b>不进</b> {@link World#hashState()}
 * （窄哈希只盖 mat/mass/tick/rng/skills/memory/builtMass），所以对四道零漂移指纹门禁零影响；
 * 但会被 {@code StateCodec} 反射式自动快照 ⇒ 存档 / 回滚照常恢复。
 * 运动本身零 RNG（见 {@code World.updateCarts}）⇒ 联机两端同输入必得同轨迹。
 *
 * <p><b>回读约定</b>：必须有<b>无参构造器</b>（反射式快照/回读要求，与其它实体一致）。
 */
public final class Minecart {

    /** 体素坐标（{@code y} = 底面，贴着轨道）。 */
    public float x, y, z;
    /** 水平速度（格 / tick）。轨道上摩擦极小、脱轨时迅速衰减到 0。 */
    public float vx, vz;
    /** 上一 tick 是否踩在轨道上（诊断/渲染用；不进窄哈希）。 */
    public boolean onRail;

    /**
     * 载物（第二十三批）：物品 id → 数量。**与箱子同一套容器表示**
     * （{@code World.chestStore} 的值类型），但键不是坐标 —— 方块的身份是坐标，
     * 而矿车的身份是**这个对象本身**，所以容器直接挂在实体上，不需要（也没有）稳定坐标键。
     *
     * <p>⚠️ **不加 {@code final}**：{@code StateCodec} 的纪律是"写出全部非 static、非 SKIP 字段"
     * （不靠 final 判断是否状态），加 final 也能被反射写回；这里保持与同类字段一致的写法，避免踩
     * 那条"final 集合 ⇒ 实体层被整层跳过"的历史坑（见 {@code StateCodec} 注释里的两次实测教训）。
     */
    public java.util.LinkedHashMap<String, Integer> cargo = new java.util.LinkedHashMap<String, Integer>();

    /** 反射式快照的回读入口（约定：实体类必须有它）。 */
    public Minecart() { }

    public Minecart(float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
    }

    /** 该车是否已经停稳（速度可忽略）—— 门禁断言"脱轨会停"时用它，免得比较浮点误差。 */
    public boolean atRest() {
        return vx == 0f && vz == 0f;
    }

    /** 是否装着货（渲染/摩擦/门禁读它；纯状态读取，不抽 RNG ⇒ 零漂移）。 */
    public boolean hasCargo() { return !cargo.isEmpty(); }

    /** 载物总件数（HUD / 诊断用）。 */
    public int cargoCount() {
        int t = 0;
        for (Integer n : cargo.values()) if (n != null) t += n.intValue();
        return t;
    }
}
