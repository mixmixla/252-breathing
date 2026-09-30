package core.content;

/**
 * 背包（MC 风格 36 格：0-8 hotbar + 9-35 背包）—— <b>纯模型</b>，零 RNG、零 GL 依赖。
 *
 * <p>放在 core 层（而非渲染层）的原因：背包的<b>性质</b>必须能被无头门禁完整断言 ——
 * 守恒律（任何操作不增减物品总量）、耐久损毁语义、光标交互的完备性、确定性快照。
 *
 * <p><b>守恒律</b>（INVENTORY 门禁断言）：装入 / 取出 / 移动 / 合并 / 光标交互，
 * 全部不增减 {@link #totalItems()}。点击「面板外」= 把手持放回背包，找不到空位则
 * <b>保持手持</b> —— 绝不凭空消失。
 *
 * <p><b>耐久语义</b>：{@code dur[slot]} 只对定义了 {@code durability > 0} 的物品有意义；
 * {@link #damage} 扣到 0 → 物品损毁并从槽位移除（返回 true）。无耐久物品消耗不生效。
 *
 * <p><b>光标手持</b>（MC 背包交互模型）：左键 = 整堆拿起 / 放下 / 合并 / 交换；
 * 右键 = 拿一半（向上取整）/ 放一个。
 */
public final class Inventory {

    // 物品查询桥统一用 core.content.ItemBook（与 ContentRegistry.itemBook() 同类型，避免双接口漂移）
    public static final int SLOTS = 36;
    public static final int HOTBAR = 9;    // 0-8 = hotbar，9-35 = 背包

    private static final ItemBook DEFAULT_BOOK = new ItemBook() {
        @Override public int stack(String id) { return 64; }
        @Override public int maxDurability(String id) { return 0; }
    };

    private final ItemBook book;
    private final String[] id = new String[SLOTS];
    private final int[] count = new int[SLOTS];
    private final int[] dur = new int[SLOTS];

    // 光标手持（MC：拿起后跟鼠标走）
    public String cursorId;
    public int cursorCount;
    public int cursorDur;

    public Inventory(ItemBook book) { this.book = book != null ? book : DEFAULT_BOOK; }

    private int limit(String itemId) { return Math.max(1, book.stack(itemId)); }
    private int maxDur(String itemId) { return Math.max(0, book.maxDurability(itemId)); }

    // ---------- 存取 ----------

    /** 装入 n 个（先叠已有堆，再开空槽）。返回装不下的余量（0 = 全部装入）。 */
    public int add(String itemId, int n) {
        if (itemId == null || itemId.isEmpty() || n <= 0) return Math.max(0, n);
        int lim = limit(itemId);
        for (int i = 0; i < SLOTS && n > 0; i++) {
            if (itemId.equals(id[i]) && count[i] < lim) {
                int take = Math.min(lim - count[i], n);
                count[i] += take; n -= take;
            }
        }
        for (int i = 0; i < SLOTS && n > 0; i++) {
            if (id[i] == null) {
                int take = Math.min(lim, n);
                id[i] = itemId; count[i] = take; dur[i] = maxDur(itemId); n -= take;
            }
        }
        return n;
    }

    /** 取出 n 个（从高位槽往回拿，优先保住 hotbar）。返回实际取出数。 */
    public int remove(String itemId, int n) {
        if (itemId == null || n <= 0) return 0;
        int got = 0;
        for (int i = SLOTS - 1; i >= 0 && got < n; i--) {
            if (itemId.equals(id[i])) {
                int take = Math.min(count[i], n - got);
                count[i] -= take; got += take;
                if (count[i] <= 0) clearSlot(i);
            }
        }
        return got;
    }

    public int countOf(String itemId) {
        if (itemId == null) return 0;
        int t = 0;
        for (int i = 0; i < SLOTS; i++) if (itemId.equals(id[i])) t += count[i];
        return t;
    }

    /** 精确扣某槽 1 个（放方块用）。返回是否成功。 */
    public boolean consumeOne(int slot) {
        if (slot < 0 || slot >= SLOTS || id[slot] == null || count[slot] <= 0) return false;
        count[slot]--;
        if (count[slot] <= 0) clearSlot(slot);
        return true;
    }

    /**
     * 扣耐久。返回 true = 已损毁并被移除。
     * 无耐久物品（{@code maxDurability == 0}）不消耗 —— 耐久只属于声明了它的物品。
     */
    public boolean damage(int slot, int n) {
        if (slot < 0 || slot >= SLOTS || id[slot] == null || n <= 0) return false;
        if (maxDur(id[slot]) <= 0) return false;
        dur[slot] -= n;
        if (dur[slot] <= 0) { clearSlot(slot); return true; }
        return false;
    }

    /** 直接设置槽位（初始背包用）；耐久自动取物品定义的最大值。 */
    public void setSlot(int slot, String itemId, int cnt) {
        if (slot < 0 || slot >= SLOTS) return;
        if (itemId == null || cnt <= 0) { clearSlot(slot); return; }
        id[slot] = itemId; count[slot] = cnt; dur[slot] = maxDur(itemId);
    }

    public void clear() {
        for (int i = 0; i < SLOTS; i++) clearSlot(i);
        cursorId = null; cursorCount = 0; cursorDur = 0;
    }

    private void clearSlot(int i) { id[i] = null; count[i] = 0; dur[i] = 0; }

    // ---------- 只读 ----------

    public String idAt(int slot) { return (slot >= 0 && slot < SLOTS) ? id[slot] : null; }
    public int countAt(int slot) { return (slot >= 0 && slot < SLOTS) ? count[slot] : 0; }
    public int durAt(int slot) { return (slot >= 0 && slot < SLOTS) ? dur[slot] : 0; }
    public int maxDurAt(int slot) { return id[slot] != null ? maxDur(id[slot]) : 0; }
    public boolean isEmpty(int slot) { return id[slot] == null; }

    /** 守恒量：所有槽位 + 光标手持的物品总数。 */
    public int totalItems() {
        int t = cursorCount;
        for (int i = 0; i < SLOTS; i++) t += count[i];
        return t;
    }

    // ---------- 光标交互（MC 背包模型）----------

    /** 左键：空手整堆拿起；手持则放下 / 同 id 合并 / 异 id 交换。 */
    public void clickLeft(int slot) {
        if (slot < 0 || slot >= SLOTS) return;
        if (cursorId == null) {
            if (id[slot] == null) return;
            cursorId = id[slot]; cursorCount = count[slot]; cursorDur = dur[slot];
            clearSlot(slot);
        } else if (id[slot] == null) {
            id[slot] = cursorId; count[slot] = cursorCount; dur[slot] = cursorDur;
            cursorId = null; cursorCount = 0; cursorDur = 0;
        } else if (id[slot].equals(cursorId)) {
            int lim = limit(cursorId);
            int take = Math.min(lim - count[slot], cursorCount);
            count[slot] += take; cursorCount -= take;
            if (cursorCount <= 0) { cursorId = null; cursorCount = 0; cursorDur = 0; }
        } else {
            String ti = id[slot]; int tc = count[slot]; int td = dur[slot];
            id[slot] = cursorId; count[slot] = cursorCount; dur[slot] = cursorDur;
            cursorId = ti; cursorCount = tc; cursorDur = td;
        }
    }

    /** 右键：空手拿一半（向上取整）；手持放一个；异 id / 已满 → 交换。 */
    public void clickRight(int slot) {
        if (slot < 0 || slot >= SLOTS) return;
        if (cursorId == null) {
            if (id[slot] == null) return;
            int half = (count[slot] + 1) / 2;
            if (half >= count[slot]) {                       // 单个：整堆拿起
                cursorId = id[slot]; cursorCount = count[slot]; cursorDur = dur[slot];
                clearSlot(slot);
            } else {
                cursorId = id[slot]; cursorDur = dur[slot];
                cursorCount = half; count[slot] -= half;
            }
        } else if (id[slot] == null) {
            id[slot] = cursorId; count[slot] = 1; dur[slot] = cursorDur;
            cursorCount--;
            if (cursorCount <= 0) { cursorId = null; cursorCount = 0; cursorDur = 0; }
        } else if (id[slot].equals(cursorId) && count[slot] < limit(cursorId)) {
            count[slot]++; cursorCount--;
            if (cursorCount <= 0) { cursorId = null; cursorCount = 0; cursorDur = 0; }
        } else {
            clickLeft(slot);                                 // 交换（保守路径）
        }
    }

    /**
     * 「点击面板外」：把手持放回背包（找不到空位则<b>保持手持</b>，绝不凭空消失）。
     * 返回仍未安置的数量（0 = 全部回包）。
     */
    public int settleCursor() {
        if (cursorId == null || cursorCount <= 0) {
            cursorId = null; cursorCount = 0; cursorDur = 0;
            return 0;
        }
        if (cursorDur > 0) {
            // 带耐久物品（通常 stack=1）：优先放回空槽（保住已损耗耐久，不与别堆混）；
            // 仅当目标同 id 槽未达堆叠上限时才合并，避免凭空突破堆叠上限。
            for (int i = 0; i < SLOTS; i++) {
                if (id[i] == null) {
                    id[i] = cursorId; count[i] = cursorCount; dur[i] = cursorDur;
                    cursorId = null; cursorCount = 0; cursorDur = 0;
                    return 0;
                }
            }
            for (int i = 0; i < SLOTS; i++) {
                if (cursorId.equals(id[i]) && count[i] < limit(cursorId)) {
                    count[i] += cursorCount; cursorId = null; cursorCount = 0; cursorDur = 0; return 0;
                }
            }
            return cursorCount;                              // 背包满 / 无法合规放置 → 保持手持
        }
        int leftover = add(cursorId, cursorCount);
        cursorCount = leftover;
        if (leftover == 0) { cursorId = null; cursorDur = 0; }
        return leftover;
    }

    // ---------- 确定性 ----------

    /** 确定性快照（门禁 DET 用：同操作序列 → 逐字节一致）。 */
    public String snapshot() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < SLOTS; i++) {
            sb.append(i).append(':');
            if (id[i] != null) sb.append(id[i]).append('x').append(count[i]).append('/').append(dur[i]);
            sb.append(';');
        }
        if (cursorId != null) sb.append("|cursor=").append(cursorId).append('x').append(cursorCount).append('/').append(cursorDur);
        return sb.toString();
    }
}
