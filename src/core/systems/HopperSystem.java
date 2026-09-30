package core.systems;

import core.rng.SeededRNG;
import core.world.Blocks;
import core.world.Facing;
import core.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 漏斗自动抽取系统（功能方块之四，2026-09-24）：<b>跨 tick 的确定性搬运</b>。
 *
 * <p><b>它解决什么</b>：第六批的漏斗只有"玩家右键手动抽一次"（{@code HOPPER PULL}，渲染层瞬时动作）。
 * 但漏斗的本质是<b>自动</b>——它应该在没人点时也持续把上方容器的物项往下搬，否则只是个"戴了斗笠的箱子"。
 * 「持续」＝跨 tick 过程 ⇒ 按本项目纪律必须落系统层（与 {@link FurnaceSystem} 同一分类依据），
 * 不能在渲染层按键里做。
 *
 * <p><b>规则</b>（完全计数驱动，<b>零 RNG</b>）：
 * <ol>
 *   <li>每 {@link #HOPPER_TICKS} tick 触发一次（{@code w.tick % HOPPER_TICKS == 0}）；</li>
 *   <li>对每个漏斗，读它<b>正上方</b>那一格的容器（箱子内容 {@code chestStore}
 *       <b>或</b>熔炉产出 {@code furnaceStore}；漏斗自己的内容也住 {@code chestStore}）→ 可串联成链；</li>
 *   <li>按<b>插入序第一个键</b>取一种物项，抽 {@link #HOPPER_BATCH} 个进漏斗自己的 {@code chestStore}；</li>
 *   <li>源容器被抽空 → 清掉它的稀疏表项（与渲染层"取空即清"同一纪律，避免稀疏表膨胀）；</li>
 *   <li><b>抽完就送</b>（第十批）：沿朝向把 1 件送给正前方的容器 → 漏斗可以<b>串联成传送带</b>。
 *       稳态下一个漏斗每周期搬 1 件。⚠️ "送"是<b>独立动作</b>，不依赖"这一 tick 刚抽到" ——
 *       传送带中间那个漏斗的入料来自侧面（上游的前方），它自己上方是空的。</li>
 * </ol>
 *
 * <p><b>确定性纪律</b>：只迭代 {@link World#cellsOfType} 的**升序**快照（系统间统一纪律）；
 * 物项选择取 {@code LinkedHashMap} 的<b>插入序</b>（而非 hash 序）→ 顺序确定；
 * 纯整数计数、不消费任何 RNG；只在容器表里搬数（<b>不写 {@code mat}</b>）。
 *
 * <p><b>零漂移</b>：门禁世界（含四道仿真指纹基线世界）里<b>没有任何漏斗</b> →
 * 首行的 {@code tick % 20} 之外再加一道 {@code cellsOfType} 为空即返回 ⇒ 零 RNG、零写入、
 * 对 {@code simHash} 与全部仿真指纹<b>逐字节零影响</b>。
 *
 * <p><b>为什么内容表不进 hashState</b>：容器内容属"玩家/机械造成的物项搬运"，与
 * {@code chestStore}/{@code furnaceStore} 同性质 —— 非 SKIP ⇒ 随 {@code StateCodec} 自动持久化/回滚，
 * 但不进窄哈希 ⇒ 不扰动指纹。这是本批"状态住 sim World、但不进指纹"的既有范式。
 */
public final class HopperSystem implements System {

    /** 抽取周期（tick）：20 tick/s → 1 秒搬一次。 */
    public static final int HOPPER_TICKS = 20;

    /** 每周期搬运的件数（同一物项）。 */
    public static final int HOPPER_BATCH = 1;

    @Override public String name() { return "hopper"; }

    @Override
    public void update(World w, SeededRNG rng) {
        if (w.tick % HOPPER_TICKS != 0) return;                 // 节拍未到 → 零开销
        List<int[]> hoppers = new ArrayList<int[]>(w.cellsOfType(Blocks.HOPPER.index));
        if (hoppers.isEmpty()) return;                          // 无漏斗 → 首行返回（零漂移、零 RNG 消费）
        for (int[] c : hoppers) {
            int x = c[0], y = c[1], z = c[2];
            pullFromAbove(w, x, y, z);
            // ⚠️ 送出去**不能**依赖"刚从上面抽到了"：串成传送带时，中间那个漏斗的入料来自**侧面**
            //   （上游的前方），而它自己上方是空的 —— 第一版把 pushOut 放在"抽到东西"的分支里，
            //   于是传送带在第二个漏斗处断掉（探针实测：src=1 h1=0 h2=3 dst=0，货全卡在中间）。
            pushOut(w, x, y, z, World.cellKey(x, y + 1, z));
        }
    }

    /**
     * 从<b>正上方</b>那一格抽 1 件（箱子内容 {@code chestStore} 或熔炉产出 {@code furnaceStore}）；
     * 上方不是容器 / 已空 → 静默返回（本方法不做任何别的动作）。
     *
     * <p>与 {@link #pushOut} <b>刻意解耦</b>：抽是"从上方进来"，送是"朝前出去"，两者互不依赖 ——
     * 串联成传送带时，中间那个漏斗的入料来自侧面，它上方是空的，若把"送"挂在"抽到东西"上，
     * 传送带会在第二个漏斗处断掉。
     */
    private static void pullFromAbove(World w, int x, int y, int z) {
        long srcCell = World.cellKey(x, y + 1, z);              // 正上方那一格

        LinkedHashMap<String, Integer> src = w.chestOf(srcCell);
        boolean srcIsChest = (src != null);
        if (src == null || src.isEmpty()) {                     // 上方不是箱子/漏斗 → 试熔炉产出
            src = w.furnaceStoreOf(srcCell);
            srcIsChest = false;
        }
        if (src == null || src.isEmpty()) return;               // 上方没东西可抽

        String key = null;                                      // 插入序第一个键（确定序，非 hash 序）
        for (String k : src.keySet()) { key = k; break; }
        if (key == null) return;
        Integer have = src.get(key);
        if (have == null || have.intValue() <= 0) { src.remove(key); return; }

        int moved = Math.min(HOPPER_BATCH, have.intValue());
        LinkedHashMap<String, Integer> dst = w.ensureChest(World.cellKey(x, y, z));
        Integer cur = dst.get(key);
        dst.put(key, Integer.valueOf((cur == null ? 0 : cur.intValue()) + moved));

        int left = have.intValue() - moved;
        if (left <= 0) src.remove(key); else src.put(key, Integer.valueOf(left));
        if (src.isEmpty()) {                                    // 抽空源容器 → 清稀疏表项
            if (srcIsChest) w.chestStore.remove(srcCell);
            else w.furnaceStore.remove(srcCell);
        }
    }

    /**
     * 沿<b>正面</b>把 1 件送给前方的容器（第十批：漏斗从"下抽"升级成"能搭传送带"）。
     *
     * <p><b>先抽后送，同一 tick 完成</b> → 一个漏斗的"吞吐量"是每周期 1 件（不是 2 件）：
     * 抽进来的那一件在同一 tick 就被送走，稳态下漏斗自身是空的。这条时序是本系统的语义，
     * 门禁 {@code HOPPER_PUSH} 用"40 tick 后 源−2 / 漏斗 0 / 目标 +2"把它钉住。
     *
     * <p><b>两条护栏</b>：
     * <ol>
     *   <li>不许把物品推回<b>刚刚抽进来的那一格</b>（{@code srcCell}）—— 否则默认朝向（+Y）的漏斗
     *       会"抽上来又推回去"，看起来像卡住；</li>
     *   <li>正前方不是容器就<b>什么也不做</b>（物品留在漏斗里，等玩家改朝向/接上容器）——
     *       与发射器不同，漏斗不是发射器，不该把物品弹到地上。</li>
     * </ol>
     */
    private static void pushOut(World w, int x, int y, int z, long srcCell) {
        long mine = World.cellKey(x, y, z);
        LinkedHashMap<String, Integer> bag = w.chestOf(mine);
        if (bag == null || bag.isEmpty()) return;
        int f = w.getFacing(x, y, z);
        int nx = x + Facing.DX[f], ny = y + Facing.DY[f], nz = z + Facing.DZ[f];
        if (!w.inBounds(nx, ny, nz)) return;
        if (World.cellKey(nx, ny, nz) == srcCell) return;       // 别推回刚抽的那一格

        int nb = w.getBlock(nx, ny, nz);
        LinkedHashMap<String, Integer> dst;
        if (nb == Blocks.CHEST.index || nb == Blocks.HOPPER.index || nb == Blocks.DISPENSER.index) {
            dst = w.ensureChest(World.cellKey(nx, ny, nz));
        } else if (nb == Blocks.FURNACE.index) {
            dst = w.ensureFurnace(World.cellKey(nx, ny, nz));
        } else {
            return;
        }

        String key = null;
        for (String k : bag.keySet()) { key = k; break; }       // 插入序首键（确定序）
        if (key == null) return;
        Integer have = bag.get(key);
        if (have == null || have.intValue() <= 0) { bag.remove(key); return; }

        Integer cur = dst.get(key);
        dst.put(key, Integer.valueOf((cur == null ? 0 : cur.intValue()) + 1));
        int left = have.intValue() - 1;
        if (left <= 0) bag.remove(key); else bag.put(key, Integer.valueOf(left));
        if (bag.isEmpty()) w.chestStore.remove(mine);           // 送空 → 清表（保稀疏）
    }

    // ---------------- 观测（渲染层 HUD 用） ----------------

    /** 距离下次抽取还有多少 tick（HUD 倒计时用；纯只读）。 */
    public static int ticksToNext(long tick) {
        long m = tick % HOPPER_TICKS;
        return (int) (HOPPER_TICKS - m);
    }
}
