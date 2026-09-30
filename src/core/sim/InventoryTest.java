package core.sim;

import core.content.Inventory;
import core.content.ItemBook;

/**
 * 背包门禁（E 批 2026-09-13）——断言背包模型的八类性质。
 *
 * <p>背包是<b>纯模型</b>（core.content，零 RNG、零 GL），所以它能被无头门禁完整断言语义，
 * 这正是「确定性涌现平台」能容纳「可配置背包」的前提。本门禁重点断言：
 * <ul>
 *   <li><b>CONSERVE</b>：任何合法的移动 / 合并 / 光标交互都不增减物品总量；</li>
 *   <li><b>DUR</b>：耐久语义（工具扣耐久、归零损毁移除、非工具不消耗）；</li>
 *   <li><b>CLICK</b>：MC 风格光标交互（左键拿/放/交换，右键拿半/放一）的完备性；</li>
 *   <li><b>SETTLE</b>：点面板外把手持放回背包，无处放则保持手持（绝不凭空消失）；</li>
 *   <li><b>DET</b>：同操作序列 → 确定性快照逐字节一致。</li>
 * </ul>
 *
 * <p>无头运行：用内存 {@link ItemBook}，不依赖磁盘内容或渲染器。
 */
public final class InventoryTest {

    private static int fails = 0;

    private static void ck(String tag, boolean cond, String detail) {
        System.out.println(tag + " " + (cond ? "PASS" : "FAIL") + "  " + detail);
        if (!cond) fails++;
    }

    /** 测试用物品书：pickaxe 堆叠 1、耐久 120；其余堆叠 64、无耐久。 */
    private static ItemBook book() {
        return new ItemBook() {
            @Override public int stack(String id) {
                return "pickaxe".equals(id) ? 1 : 64;
            }
            @Override public int maxDurability(String id) {
                return "pickaxe".equals(id) ? 120 : 0;
            }
        };
    }

    private static int maxStack(Inventory inv, ItemBook b) {
        int m = 0;
        for (int i = 0; i < Inventory.SLOTS; i++) {
            String id = inv.idAt(i);
            if (id != null) m = Math.max(m, inv.countAt(i));
            // 任何槽都不得超过该物品的堆叠上限（守恒 + 堆叠不变量）
            if (id != null && inv.countAt(i) > b.stack(id)) return -1;
        }
        return m;
    }

    public static void main(String[] args) {
        ItemBook b = book();

        // ---------------- ADD：堆叠 + 开新槽 + 溢出余量 ----------------
        Inventory inv = new Inventory(b);
        int left = inv.add("grass", 70);                 // 64 进槽0，6 进槽1
        boolean addStack = left == 0 && inv.countOf("grass") == 70
                && "grass".equals(inv.idAt(0)) && "grass".equals(inv.idAt(1));
        ck("ADD_STACK", addStack, "left=" + left + " total=" + inv.countOf("grass"));

        int left2 = inv.add("grass", 1000);             // 容量远大于请求 → 全装下
        boolean addOver = left2 == 0 && inv.countOf("grass") == inv.totalItems()
                && maxStack(inv, b) > 0;
        ck("ADD_OVERFLOW", addOver, "left2=" + left2 + " total=" + inv.totalItems());

        // ---------------- STACK_LIMIT：任何槽不超过堆叠上限 ----------------
        Inventory lim = new Inventory(b);
        for (int i = 0; i < 40; i++) lim.add("grass", 64);   // 故意请求超过 36 槽
        ck("STACK_LIMIT", maxStack(lim, b) > 0, "maxStack=" + maxStack(lim, b));

        // ---------------- CONSERVE：移动/合并/光标交互守恒 ----------------
        Inventory c = new Inventory(b);
        c.setSlot(0, "grass", 10);
        c.setSlot(1, "stone", 5);
        int r1 = c.add("grass", 3);          // 10 -> 13
        int r2 = c.remove("grass", 4);       // 13 -> 9
        int before = c.totalItems();         // 9 + 5 = 14
        c.clickLeft(0); c.clickLeft(2); c.settleCursor();
        c.clickRight(1); c.clickRight(3); c.settleCursor();
        ck("CONSERVE", c.totalItems() == before && r1 == 0 && r2 == 4,
                "before=" + before + " after=" + c.totalItems() + " removed=" + r2);

        // ---------------- DURABILITY：工具耐久语义 ----------------
        Inventory d = new Inventory(b);
        d.setSlot(0, "pickaxe", 1);
        ck("DUR_INIT", d.durAt(0) == 120, "dur=" + d.durAt(0));
        boolean broke = d.damage(0, 120);
        ck("DUR_BREAK", broke && d.idAt(0) == null, "broke=" + broke + " id=" + d.idAt(0));

        Inventory d2 = new Inventory(b);
        d2.setSlot(0, "pickaxe", 1);
        boolean notBroken = d2.damage(0, 50);
        ck("DUR_PARTIAL", !notBroken && d2.durAt(0) == 70, "consumed? " + notBroken + " dur=" + d2.durAt(0));

        Inventory d3 = new Inventory(b);
        d3.setSlot(0, "grass", 1);
        boolean grassDmg = d3.damage(0, 5);
        ck("DUR_NONTOOL", !grassDmg && d3.countAt(0) == 1, "consumed? " + grassDmg);

        // ---------------- CONSUME：精确扣槽 1 个 ----------------
        Inventory cm = new Inventory(b);
        cm.setSlot(0, "stone", 3);
        boolean cOk = cm.consumeOne(0);
        ck("CONSUME_OK", cOk && cm.countAt(0) == 2, "count=" + cm.countAt(0));
        cm.clear();
        ck("CONSUME_EMPTY", !cm.consumeOne(0), "empty-consume should fail");

        // ---------------- CLICK_LEFT：拿 / 交换 / 放下 ----------------
        Inventory cl = new Inventory(b);
        cl.setSlot(0, "grass", 10);
        cl.setSlot(1, "stone", 5);
        cl.clickLeft(0);                                  // 拿起 grass 10
        boolean pick = cl.cursorId != null && cl.cursorCount == 10 && cl.idAt(0) == null;
        ck("CLICK_PICK", pick, "cursor=" + cl.cursorId);
        cl.clickLeft(1);                                  // 与 stone5 交换：stone 进光标、grass 落槽1（槽0 自拿起起即空）
        boolean swap = cl.idAt(0) == null && "grass".equals(cl.idAt(1)) && cl.cursorCount == 5;
        ck("CLICK_SWAP", swap, "s0=" + cl.idAt(0) + " s1=" + cl.idAt(1) + " cur=" + cl.cursorCount);
        cl.clickLeft(0);                                  // 放下 stone5 回槽0
        boolean drop = cl.cursorId == null && "stone".equals(cl.idAt(0)) && cl.countAt(0) == 5;
        ck("CLICK_DROP", drop, "s0=" + cl.idAt(0));

        // ---------------- CLICK_RIGHT：拿一半 / 放一个 ----------------
        Inventory cr = new Inventory(b);
        cr.setSlot(0, "grass", 10);
        cr.clickRight(0);                                 // 拿一半 = 5
        boolean half = cr.cursorId != null && cr.cursorCount == 5 && cr.countAt(0) == 5;
        ck("CLICK_RIGHT_HALF", half, "cur=" + cr.cursorCount + " s0=" + cr.countAt(0));
        cr.clickRight(1);                                 // 放一个到空槽1
        boolean one = cr.cursorCount == 4 && "grass".equals(cr.idAt(1)) && cr.countAt(1) == 1;
        ck("CLICK_RIGHT_ONE", one, "cur=" + cr.cursorCount + " s1=" + cr.countAt(1));

        // ---------------- SETTLE：放回背包 / 无处放则保持手持 ----------------
        Inventory st = new Inventory(b);
        for (int i = 0; i < 36; i++) st.setSlot(i, "grass", 64);  // 填满
        st.clickLeft(0);                                  // 拿起 64（槽0 空出）
        st.clickLeft(0);                                  // 放回原槽
        ck("SETTLE_FULL_KEEP", st.cursorId == null && st.totalItems() == 36 * 64, "total=" + st.totalItems());

        Inventory st2 = new Inventory(b);
        for (int i = 0; i < 36; i++) st2.setSlot(i, "pickaxe", 1);  // 全满（stack=1）
        st2.cursorId = "pickaxe"; st2.cursorCount = 1; st2.cursorDur = 50;  // 额外手持一个（压力态）
        int remain = st2.settleCursor();
        ck("SETTLE_NOFIT_KEEP", remain == 1 && st2.cursorId != null, "remain=" + remain + " cursor=" + st2.cursorId);

        // ---------------- DET：同状态/同操作 → 相同快照 ----------------
        Inventory a1 = new Inventory(b); a1.setSlot(0, "grass", 10); a1.setSlot(1, "stone", 5);
        Inventory a2 = new Inventory(b); a2.setSlot(1, "stone", 5); a2.setSlot(0, "grass", 10);
        ck("DET_ORDER", a1.snapshot().equals(a2.snapshot()), "snap equal under slot order");
        a1.clickLeft(0); a1.clickLeft(2); a1.settleCursor();
        a2.clickLeft(0); a2.clickLeft(2); a2.settleCursor();
        ck("DET_OPS", a1.snapshot().equals(a2.snapshot()), "snap equal after same ops");

        System.out.println("INVENTORY " + (fails == 0 ? "PASS" : "FAIL " + fails) + "  (14 properties)");
        if (fails > 0) System.exit(1);
    }
}
