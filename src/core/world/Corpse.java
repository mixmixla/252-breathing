package core.world;

/**
 * 敌兵死亡后留在原地的「魂滴」（DaS 风尸体回收，P1-2）。
 *
 * <p>实体层状态：<b>不进 {@link World#hashState()}</b>（窄哈希只盖 mat/mass/...），故对四道零漂移门禁
 * 指纹零影响；但会被 {@link core.net.StateCodec} 反射式自动快照（与 {@code World.beasts} 同机制），
 * 联机回滚/读档照常恢复。
 *
 * <p>魂滴让「击杀有重量」：野兽死在原地留下可拾取魂，玩家须走回回收（同玩家血渍半径），
 * 而非击杀瞬间自动入账——探索因此带风险与回报。
 */
public final class Corpse {
    public float x, y, z;
    public int souls;          // 回收给玩家的魂（确定性数值，无随机）
    public String id;          // 兽身份（内容兽 id 或原型名），仅日志/未来 HUD 用

    public Corpse(float x, float y, float z, int souls, String id) {
        this.x = x; this.y = y; this.z = z; this.souls = souls; this.id = id;
    }

    /** 无参构造：供 {@code StateCodec} 反射重建（让其 {@code inst()} 走干净的 newInstance，不依赖 Unsafe 退化路径）。 */
    public Corpse() {}
}
