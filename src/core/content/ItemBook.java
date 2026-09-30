package core.content;

/**
 * 物品查询接口（E 批 2026-09-13）—— {@link Inventory} 与物品定义之间的唯一桥。
 *
 * <p>让背包模型保持零依赖：它不认识 {@link ContentRegistry}，只通过本接口查
 * 「堆叠上限 / 最大耐久」。实现方：
 * <ul>
 *   <li>{@code ContentRegistry.itemBook()} —— 从 {@code assets/content/items/} 解析；</li>
 *   <li>门禁内的内联实现 —— 断言语义时不受磁盘内容影响。</li>
 * </ul>
 *
 * <p>约定：未知物品返回保守默认（stack=64、durability=0），<b>绝不抛异常</b>——
 * 一个 mod 打错物品名不该让背包崩溃。
 */
public interface ItemBook {

    /** 堆叠上限（>=1）。 */
    int stack(String id);

    /** 最大耐久（0 = 无耐久概念）。 */
    int maxDurability(String id);
}
