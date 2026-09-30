package core.systems;

import core.agent.Npc;
import core.rng.SeededRNG;
import core.world.Beast;
import core.world.Blocks;
import core.world.Facing;
import core.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 红石逻辑系统（第 99 个系统，SOCIETY）：红石元件的<b>执行者</b>与「信号强度」的<b>唯一定义处</b>。
 *
 * <p><b>它管六类元件</b>：
 * <ul>
 *   <li>{@link Blocks#PLATE 压力板}：任何实体脚底落在本格 → 带电（<b>首个非玩家按键驱动的电源</b>，全向发射）。</li>
 *   <li>{@link Blocks#REPEATER 中继器}：每 {@link #REPEATER_PERIOD} tick <b>采样背面输入并保持</b>，
 *       朝<b>正面</b>以满强度输出 → 延时 / 去抖 / <b>信号再生</b>（把衰减链重新拉满，于是信号能传得更远）。
 *       ⚠️ 它<b>不能拐弯</b>：输入口（背面）与输出口（正面）永远相反 —— MC 的中继器同理。</li>
 *   <li>{@link Blocks#COMPARATOR 比较器}：<b>真 A/B 比较器</b>（第十批）——A = 背面输入，B = 两侧输入，
 *       compare 模式输出 max(A,B)、subtract 模式输出 max(0, A−B)，结果作为<b>模拟强度</b>从正面发出。
 *       <b>第十一批补上"容器满度"</b>：A/B 若对着容器（箱/漏斗/发射器/熔炉）则读它的<b>满度 1..15</b>
 *       （MC 语义）→ 于是"箱子快满了就通知"这类自动化成立。</li>
 *   <li>{@link Blocks#OBSERVER 观察者}（第十一批）：<b>看背面</b>那一格，"身份 + 状态位"变了就朝<b>正面</b>
 *       发 1 tick 脉冲。它是本作第一个有<b>记忆</b>的元件（记忆住 {@code meta} 的高位、脉冲住低位）。</li>
 *   <li>{@link Blocks#DISPENSER 发射器}：有弹匣（复用 {@code chestStore}）；<b>通电上升沿</b>朝<b>正面</b>
 *       送 1 件给容器，正前方是空气则<b>弹射成掉落物</b>（MC 语义）。</li>
 *   <li>{@link Blocks#PISTON 活塞}：通电上升沿把<b>正面</b>那格方块推到再往前一格。</li>
 *   <li>{@link Blocks#LEVER}/{@link Blocks#BUTTON}：玩家输入（开关态由渲染层写 {@code meta}，本系统只读）。</li>
 * </ul>
 *
 * <p><b>第十批的关键升级：朝向（{@link World#getFacing}）</b>
 * <ol>
 *   <li><b>比较器变成真的了</b>：第九批它只能"取邻域最高强度 + 过阈值转发"（因为不知道哪一面是输入、
 *       哪一面是输出）；现在 A/B 由朝向切分，于是 compare/subtract 两种模式才<b>有区别</b>。
 *       右键可切模式（渲染层），与 MC 一致。</li>
 *   <li><b>活塞能横推了</b>：第九批是"把正上方顶高一格"（无朝向的唯一可能），现在是"沿正面推一格"——
 *       朝上看=顶升（与第九批行为一致），平视=水平推、朝下=下压。</li>
 *   <li><b>发射器/漏斗能定向了</b>：只往正面送，于是可以搭"传送带"而不是"到处乱撒"。</li>
 * </ol>
 * 朝向的默认值是 {@link Facing#DEFAULT}（+Y）—— 于是"没有朝向信息"的方块（旧存档 / 门禁世界 /
 * 别的系统放下的）行为与第九批<b>逐字节一致</b>，本批对既有世界是纯增量。
 *
 * <p><b>⚠️ 仍然诚实的边界</b>：本作的导体网络是<b>无向</b>的（一根导线把信号朝两端带，不区分方向），
 * 所以"导线本身"没有朝向概念；朝向只作用在<b>元件</b>上。MC 的"准星朝向影响比较器的 A/B 选择"、
 * 中继器的"锁存（locking）"、比较器的"容器满度读取"这些更细的语义都没有做。
 *
 * <p><b>上升沿检测只用一个 meta 位</b>：发射器/活塞的 {@code meta} = "上一 tick 是否带电"，
 * {@code 本 tick 带电 && 上一 tick 不带电} 即上升沿 → "推一次/发一次"是<b>事件</b>而非每 tick 重复。
 * 同一个 {@code meta} 也用于渲染提亮，故不需要第二个状态位。
 *
 * <p><b>执行顺序（重要）</b>：注册在 {@link WireSystem} <b>之后</b> → 读到的是本 tick 刚算出的导线强度；
 * 它写的元件带电态要等<b>下一 tick</b> 的 WireSystem 才被当电源消费
 * → 天然形成"<b>每经过一个元件，信号延迟 1 tick</b>"的确定流水线。改注册顺序 = 改行为。
 *
 * <p><b>零漂移</b>：门禁世界不含这些方块 → 各收集列表全为空 → 各自首行返回、不消费任何 RNG；
 * 写入只落 {@code meta}/{@code chestStore}/{@code blockState}（不进 {@code hashState}，
 * 随 StateCodec 自动持久化/回滚）；{@code markDirty} 仅在状态变化时调用（{@code dirtyChunks} 是 SKIP 的渲染脏标记）。
 *
 * <p><b>有界</b>：压力板对实体只做 O(方块数 + 实体数) 的一次遍历（不是"每块板扫一遍实体"）；活塞/发射器都先查边界。
 */
public final class RedstoneLogicSystem implements System {

    /** 中继器采样周期（tick）：20 tick/s → 4 tick = 0.2 秒。既是延时上限，也是"去抖窗口"。 */
    public static final int REPEATER_PERIOD = 4;

    /** 比较器 compare 模式下"B 路必须不弱于 A 路"的阈值残留：本作是 max(A,B)，
     *  保留该常量只为 HUD 提示"这一级会按哪一路走"，不参与判定。 */
    public static final int COMPARATOR_THRESHOLD = 8;

    @Override public String name() { return "redstone"; }

    @Override
    public void update(World w, SeededRNG rng) {
        pressurePlates(w);
        observers(w);                                      // 先算脉冲 → 本 tick 的边沿元件就能看到它
        if (w.tick % REPEATER_PERIOD == 0) repeaters(w);   // 采样相位由 tick 决定（零状态实现"保持"）
        comparators(w);
        edgeTriggered(w, Blocks.DISPENSER.index);
        edgeTriggered(w, Blocks.PISTON.index);
    }

    // ---------------- 观察者：看背面一格的变化 → 朝前面发 1 tick 脉冲 ----------------

    /** 观察记忆装在 meta 的低位之上（见 {@link #observedCode}）。 */
    private static final int OBS_META_CAP = 31;

    /**
     * 观察者：<b>看背面</b>（朝向的反向）那一格，"身份 + 状态位"发生变化就朝<b>正面</b>发 1 tick 脉冲。
     *
     * <p><b>状态怎么装</b>：{@code meta = (上次观察到的编码 << 1) | 本 tick 脉冲}。
     * 一个 int 装两件事，是因为它们永远是"同一格同一个观察者"的属性，且**同一次写入**就要一起更新 ——
     * 分成两个槽反而会引入"更新了一半"的中间态。{@code 0} = 还没观察过。
     *
     * <p><b>为什么首次不报</b>：{@code prev == 0} 表示"刚放下、还没有基线"，此时记录但不发脉冲 ——
     * 否则玩家每放一个观察者都会莫名其妙地闪一下（MC 也不是这么做的）。
     *
     * <p><b>观察的是什么</b>：{@code blockIndex + meta 低位}。于是它能看见"方块被放/被挖 / 门开关 /
     * 拉杆按钮 / 中继比较器的输出变化 / 另一个观察者的脉冲"。<b>看不见容器内容的变化</b>
     * （容器内容不在 meta 里）—— 这条边界写在 {@link #observedCode} 上。
     *
     * <p><b>脉冲宽度 = 1 tick</b>：每 tick 都按"当前是否变化"重写这一位 → 变化那 tick 置 1、下一 tick 自然归 0。
     * 而 {@code WireSystem} 在本系统<b>之前</b>跑 → 它恰好能在下一 tick 读到这一位（一次，然后被清）。
     * 这与第九批"上升沿只用一个 meta 位"是同一套手法。
     */
    private void observers(World w) {
        List<int[]> list = new ArrayList<int[]>(w.cellsOfType(Blocks.OBSERVER.index));
        if (list.isEmpty()) return;
        for (int[] c : list) {
            int x = c[0], y = c[1], z = c[2];
            int d = Facing.opposite(w.getFacing(x, y, z));          // 观察方向 = 背面
            int seen = observedCode(w, x + Facing.DX[d], y + Facing.DY[d], z + Facing.DZ[d]);
            int prev = w.getMeta(x, y, z);
            int pulse = (((prev >>> 1) != 0) && seen != (prev >>> 1)) ? 1 : 0;
            int next = (seen << 1) | pulse;
            if (next != prev) { w.setMeta(x, y, z, next); w.markDirty(x, y, z); }
        }
    }

    /**
     * 把"某一格的可见状态"压成一个正数（{@code 0} 不会出现，便于用 {@code 0} 表示"还没观察过"）。
     * {@code code = 1 + blockIndex * 32 + min(meta, 31)}。
     */
    public static int observedCode(World w, int x, int y, int z) {
        int b = w.getBlock(x, y, z);
        int m = w.getMeta(x, y, z);
        if (m > OBS_META_CAP) m = OBS_META_CAP;
        return 1 + b * (OBS_META_CAP + 1) + m;
    }

    // ---------------- 压力板：实体踩踏 ----------------

    private void pressurePlates(World w) {
        List<int[]> plates = new ArrayList<int[]>(w.cellsOfType(Blocks.PLATE.index));
        if (plates.isEmpty()) return;                        // 无压力板 → 零开销、零写入
        HashSet<Long> cells = new HashSet<Long>();
        for (int[] c : plates) cells.add(Long.valueOf(World.cellKey(c[0], c[1], c[2])));

        // 反向遍历（实体 → 格）而不是"每块板扫所有实体"：复杂度 O(板 + 实体) 而非 O(板 × 实体)。
        HashSet<Long> pressed = new HashSet<Long>();
        if (w.player != null) markStanding(pressed, cells, w.player.x, w.player.y, w.player.z);
        for (Beast b : w.beasts) markStanding(pressed, cells, b.x, b.y, b.z);
        for (Npc n : w.npcs) markStanding(pressed, cells, n.x, n.y, n.z);

        for (int[] c : plates) {
            int x = c[0], y = c[1], z = c[2];
            int want = pressed.contains(Long.valueOf(World.cellKey(x, y, z))) ? 1 : 0;
            if (w.getMeta(x, y, z) != want) { w.setMeta(x, y, z, want); w.markDirty(x, y, z); }
        }
    }

    /** 实体脚底格恰好落在某块压力板上 → 记入按下集合。 */
    private static void markStanding(HashSet<Long> out, HashSet<Long> cells, float ex, float ey, float ez) {
        int cx = (int) Math.floor(ex), cy = (int) Math.floor(ey + 1e-4f), cz = (int) Math.floor(ez);
        long k = World.cellKey(cx, cy, cz);
        if (cells.contains(Long.valueOf(k))) out.add(Long.valueOf(k));
    }

    // ---------------- 中继器：采样背面输入 → 正面满强度输出 ----------------

    private void repeaters(World w) {
        List<int[]> list = new ArrayList<int[]>(w.cellsOfType(Blocks.REPEATER.index));
        if (list.isEmpty()) return;
        for (int[] c : list) {
            int x = c[0], y = c[1], z = c[2];
            int on = rearStrength(w, x, y, z, w.getFacing(x, y, z)) > 0 ? 1 : 0;
            if (w.getMeta(x, y, z) != on) { w.setMeta(x, y, z, on); w.markDirty(x, y, z); }
        }
    }

    // ---------------- 比较器：真 A/B（compare / subtract）----------------

    private void comparators(World w) {
        List<int[]> list = new ArrayList<int[]>(w.cellsOfType(Blocks.COMPARATOR.index));
        if (list.isEmpty()) return;
        for (int[] c : list) {
            int x = c[0], y = c[1], z = c[2];
            int f = w.getFacing(x, y, z);
            int a = rearAnalog(w, x, y, z, f);          // A：背面主输入（容器 → 满度）
            int b = sideAnalog(w, x, y, z, f);          // B：两侧副输入（取较强的一侧）
            int out;
            if (w.isSubtractMode(x, y, z)) {
                out = a - b;                            // subtract：A − B（下限 0）
                if (out < 0) out = 0;
            } else {
                out = a > b ? a : b;                    // compare：max(A, B)
            }
            if (w.getMeta(x, y, z) != out) { w.setMeta(x, y, z, out); w.markDirty(x, y, z); }
        }
    }

    // ---------------- 上升沿事件元件：发射器 / 活塞 ----------------

    /** 统一处理"通电上升沿执行一次"的元件（{@code meta} 兼作"上一 tick 是否带电"与渲染提亮依据）。 */
    private void edgeTriggered(World w, int blockIdx) {
        List<int[]> list = new ArrayList<int[]>(w.cellsOfType(blockIdx));
        if (list.isEmpty()) return;
        for (int[] c : list) {
            int x = c[0], y = c[1], z = c[2];
            boolean powered = anyIncoming(w, x, y, z);
            int prev = w.getMeta(x, y, z);
            int now = powered ? 1 : 0;
            if (powered && prev == 0) {                       // 上升沿 → 动作一次
                int f = w.getFacing(x, y, z);
                if (blockIdx == Blocks.DISPENSER.index) dispenseOne(w, x, y, z, f);
                else pushOne(w, x, y, z, f);
            }
            if (prev != now) { w.setMeta(x, y, z, now); w.markDirty(x, y, z); }
        }
    }

    /**
     * 发射器：从自身弹匣取 1 件，朝<b>正面</b>送出去。
     * <ol>
     *   <li>正前方是容器（箱/漏斗/发射器/熔炉）→ 送进去；</li>
     *   <li>正前方是空气 → <b>弹射成掉落物</b>（掉在正前方格心附近，玩家走过去就能捡）；</li>
     *   <li>正前方是实心非容器 → <b>不发</b>（避免物品被塞进石头里静默丢失）。</li>
     * </ol>
     * 三种结局都只扣 1 件，扣完弹匣空则清表（保稀疏）。
     */
    private static void dispenseOne(World w, int x, int y, int z, int f) {
        long cell = World.cellKey(x, y, z);
        LinkedHashMap<String, Integer> mag = w.chestOf(cell);
        if (mag == null || mag.isEmpty()) return;
        String key = null;
        for (String k : mag.keySet()) { key = k; break; }     // 插入序首键（确定序）
        if (key == null) return;
        Integer have = mag.get(key);
        if (have == null || have.intValue() <= 0) { mag.remove(key); return; }

        int nx = x + Facing.DX[f], ny = y + Facing.DY[f], nz = z + Facing.DZ[f];
        int nb = w.getBlock(nx, ny, nz);
        LinkedHashMap<String, Integer> dst = null;
        if (nb == Blocks.CHEST.index || nb == Blocks.HOPPER.index || nb == Blocks.DISPENSER.index) {
            dst = w.ensureChest(World.cellKey(nx, ny, nz));
        } else if (nb == Blocks.FURNACE.index) {
            dst = w.ensureFurnace(World.cellKey(nx, ny, nz));
        } else if (nb != Blocks.AIR.index) {
            return;                                          // 实心非容器：宁可不发，也不把物品塞进方块里
        }

        if (dst != null) {
            Integer cur = dst.get(key);
            dst.put(key, Integer.valueOf((cur == null ? 0 : cur.intValue()) + 1));
        } else {
            // 朝正面弹射：落点 = 前格中心再往外 0.3 格（看得出"从口里喷出来"）
            w.spawnDropAt(nx + 0.5f + Facing.DX[f] * 0.3f,
                          ny + 0.5f + Facing.DY[f] * 0.3f,
                          nz + 0.5f + Facing.DZ[f] * 0.3f, key, 1);
        }
        int left = have.intValue() - 1;
        if (left <= 0) mag.remove(key); else mag.put(key, Integer.valueOf(left));
        if (mag.isEmpty()) w.chestStore.remove(cell);         // 弹匣空 → 清表（保稀疏）
    }

    /**
     * 活塞：把<b>正面</b>那格方块推到再往前一格（朝上看 = 顶升，与第九批行为一致）。
     *
     * <p>四条护栏（都是"拒绝即中止"，绝不半途改世界）：
     * <ol>
     *   <li>源格/目标格必须都在界内；</li>
     *   <li>源格要有可推的东西（非空气、非基岩）；</li>
     *   <li>目标格必须是空气；</li>
     *   <li>源格<b>不能带不可无痛搬走的状态</b>（{@link World#hasUnyieldableState}）—— 否则
     *       {@code setBlock} 会把状态留在原格，造成"箱子里的东西凭空消失"这类静默数据丢失。</li>
     * </ol>
     * 推动时把<b>朝向与模式</b>一并搬过去（否则"活塞推活塞"会让被推的那个悄悄转回默认朝向）。
     */
    private static void pushOne(World w, int x, int y, int z, int f) {
        int sx = x + Facing.DX[f], sy = y + Facing.DY[f], sz = z + Facing.DZ[f];
        int tx = sx + Facing.DX[f], ty = sy + Facing.DY[f], tz = sz + Facing.DZ[f];
        if (!w.inBounds(sx, sy, sz) || !w.inBounds(tx, ty, tz)) return;
        int moving = w.getBlock(sx, sy, sz);
        if (moving == Blocks.AIR.index || moving == Blocks.BEDROCK.index) return;
        if (w.getBlock(tx, ty, tz) != Blocks.AIR.index) return;
        if (w.hasUnyieldableState(sx, sy, sz)) return;

        int srcFacing = w.getFacing(sx, sy, sz);
        boolean srcSubtract = w.isSubtractMode(sx, sy, sz);

        w.setBlock(tx, ty, tz, moving);
        w.setBlock(sx, sy, sz, Blocks.AIR.index);
        if (Blocks.orientableOrdinal(moving) >= 0) {          // 朝向随方块一起走
            w.setFacing(tx, ty, tz, srcFacing);
            if (srcSubtract) w.setSubtractMode(tx, ty, tz, true);
        }
    }

    // ---------------- 「带电 / 电源强度」判定：全项目唯一来源 ----------------

    /**
     * 元件<b>作为电源</b>的强度（<b>不分方向</b>，0 = 不供电）。
     *
     * <p>用途：渲染提亮（{@code Chunk.redstoneBrightness}）与 HUD 刻度 —— 它们问的是
     * "这一格亮不亮"，与方向无关。而<b>网络计算</b>问的是"朝我发射多少"，走
     * {@link #sourceStrengthToward}。两个问题分开两个函数，别用一个含糊的万能谓词糊过去。
     */
    public static int sourceStrength(World w, int x, int y, int z) {
        int b = w.getBlock(x, y, z);
        int m = w.getMeta(x, y, z);
        if (b == Blocks.LEVER.index || b == Blocks.BUTTON.index || b == Blocks.PLATE.index
                || b == Blocks.REPEATER.index) {
            return m != 0 ? WireSystem.MAX_SIGNAL : 0;
        }
        if (b == Blocks.COMPARATOR.index) {
            if (m <= 0) return 0;
            return m > WireSystem.MAX_SIGNAL ? WireSystem.MAX_SIGNAL : m;
        }
        if (b == Blocks.OBSERVER.index) return (m & 1) != 0 ? WireSystem.MAX_SIGNAL : 0;   // 低位 = 本 tick 脉冲
        return 0;
    }

    /**
     * 元件朝 {@code outDir} 方向发射的强度（方向敏感的电源判定）。
     *
     * <ul>
     *   <li>拉杆 / 按钮 / 压力板：<b>全向</b> —— 它们是"裸电源"，接哪面都算；</li>
     *   <li>中继器：只在<b>正面</b>发满强度（背面是输入口）；</li>
     *   <li>比较器：只在<b>正面</b>发自身输出强度（模拟量，过阈值的强度能继续往下传）；</li>
     *   <li>发射器 / 活塞：<b>不发</b>（它们是消费者；本作不模拟"机械元件对外供电"）。</li>
     * </ul>
     */
    public static int sourceStrengthToward(World w, int x, int y, int z, int outDir) {
        int b = w.getBlock(x, y, z);
        int m = w.getMeta(x, y, z);
        if (b == Blocks.LEVER.index || b == Blocks.BUTTON.index || b == Blocks.PLATE.index) {
            return m != 0 ? WireSystem.MAX_SIGNAL : 0;
        }
        if (b == Blocks.REPEATER.index) {
            return (m != 0 && outDir == w.getFacing(x, y, z)) ? WireSystem.MAX_SIGNAL : 0;
        }
        if (b == Blocks.COMPARATOR.index) {
            if (m <= 0 || outDir != w.getFacing(x, y, z)) return 0;
            return m > WireSystem.MAX_SIGNAL ? WireSystem.MAX_SIGNAL : m;
        }
        if (b == Blocks.OBSERVER.index) {                          // 只朝正面发（背面是"眼睛"）
            return ((m & 1) != 0 && outDir == w.getFacing(x, y, z)) ? WireSystem.MAX_SIGNAL : 0;
        }
        return 0;
    }

    /**
     * 「一个格朝某方向发射多少」的<b>唯一入口</b>：导体（WIRE/LAMP）读它自己的强度，元件问
     * {@link #sourceStrengthToward}。
     *
     * <p>导体为什么不分方向：本作的导线模型是<b>无向</b>的（一根导线把信号朝两端带）。
     * 给导线加方向会变成"有向图 + 需要记住每条边的来向"，是另一个量级的改动；
     * 而"元件有向、导线无向"已经足够表达中继/比较/拐弯这三件事。
     */
    public static int strengthToward(World w, int x, int y, int z, int outDir) {
        int b = w.getBlock(x, y, z);
        if (b == Blocks.WIRE.index || b == Blocks.LAMP.index) {
            int m = w.getMeta(x, y, z);
            return m > 0 ? m : 0;
        }
        return sourceStrengthToward(w, x, y, z, outDir);
    }

    /** 该格<b>不分方向</b>的有效强度（导体读自身，元件读电源强度）。渲染/HUD 用。 */
    public static int strengthAt(World w, int x, int y, int z) {
        int b = w.getBlock(x, y, z);
        if (b == Blocks.WIRE.index || b == Blocks.LAMP.index) {
            int m = w.getMeta(x, y, z);
            return m > 0 ? m : 0;
        }
        return sourceStrength(w, x, y, z);
    }

    /**
     * 比较器 <b>A 路</b>：正后方（朝向的反向）那格朝我发射的强度。
     *
     * <p>方向记账：背面邻居在 {@code pos + D[opp(f)]}，从它指向我的方向 = {@code opp(opp(f)) = f}。
     * 这类"从我指向它的方向的相反数"是本批最容易写反的一步，故探针用<b>相对性质</b>断言（见 SubsystemTest）。
     */
    public static int rearStrength(World w, int x, int y, int z, int f) {
        int d = Facing.opposite(f);
        return strengthToward(w, x + Facing.DX[d], y + Facing.DY[d], z + Facing.DZ[d], f);
    }

    /** 比较器 <b>B 路</b>：与朝向垂直的 4 个邻居里，朝我发射的最强强度（取较强的一侧，与 MC 一致）。 */
    public static int sideStrength(World w, int x, int y, int z, int f) {
        int back = Facing.opposite(f);
        int best = 0;
        for (int k = 0; k < Facing.COUNT; k++) {
            if (k == f || k == back) continue;              // 正面是输出口、背面是 A 路 → 都不算 B
            int s = strengthToward(w, x + Facing.DX[k], y + Facing.DY[k], z + Facing.DZ[k],
                    Facing.opposite(k));
            if (s > best) best = s;
        }
        return best;
    }

    // ---------------- 容器满度（比较器的"模拟输入"）----------------

    /** 容器参照容量（MC 单箱 27 格 × 64 = 1728）。本作容器<b>没有槽位概念</b>（`chestStore` 只记 id→数量），
     *  故用 MC 的箱容量当"满度"的刻度参照 —— 这在文档里必须写清，别让命名承诺超过实现。 */
    public static final int CONTAINER_CAPACITY = 27 * 64;

    /** 该格容器的总件数；不是容器 → -1（用 -1 而不是 0，才能把"非容器"与"空容器"分开）。 */
    private static int containerItems(World w, int x, int y, int z) {
        int b = w.getBlock(x, y, z);
        long k = World.cellKey(x, y, z);
        LinkedHashMap<String, Integer> bag;
        if (b == Blocks.CHEST.index || b == Blocks.HOPPER.index || b == Blocks.DISPENSER.index) {
            bag = w.chestOf(k);
        } else if (b == Blocks.FURNACE.index) {
            bag = w.furnaceStoreOf(k);
        } else {
            return -1;
        }
        if (bag == null) return 0;
        int t = 0;
        for (Integer v : bag.values()) t += v.intValue();
        return t;
    }

    /** 容器满度 <b>1..15</b>（空容器 / 非容器 → 0）。纯整数、单调、确定（不消费 RNG）。 */
    public static int containerFullness(World w, int x, int y, int z) {
        int total = containerItems(w, x, y, z);
        if (total <= 0) return 0;
        int lvl = 1 + (int) ((long) total * 14L / CONTAINER_CAPACITY);
        return lvl > WireSystem.MAX_SIGNAL ? WireSystem.MAX_SIGNAL : lvl;
    }

    /**
     * 读某格作为"输入"的强度。
     *
     * <p>{@code analog=false}（<b>数字</b>读：中继器输入 / 活塞通电 / 导线网络种子）：只认红石电源
     * —— <b>容器不算</b>（MC 里装东西的箱子不会点亮中继器或活塞）。
     * <p>{@code analog=true}（<b>模拟</b>读：比较器）：容器读<b>满度</b> 1..15，其余与数字读相同。
     *
     * <p>两个粒度分开，就是为了不重蹈 21.7 的覆辙：一个含糊的"算什么读"会让"箱子满 → 中继器亮"
     * 这种显然错的语义悄悄溜进来。
     */
    private static int readInput(World w, int x, int y, int z, int dirTowardMe, boolean analog) {
        if (analog) {
            int full = containerFullness(w, x, y, z);
            if (full > 0) return full;
        }
        return strengthToward(w, x, y, z, dirTowardMe);
    }

    /** 比较器 A 路的<b>模拟</b>读（背面容器 → 满度）。 */
    public static int rearAnalog(World w, int x, int y, int z, int f) {
        int d = Facing.opposite(f);
        return readInput(w, x + Facing.DX[d], y + Facing.DY[d], z + Facing.DZ[d], f, true);
    }

    /** 比较器 B 路的<b>模拟</b>读（与朝向垂直的 4 邻取最强；容器同样按满度算）。 */
    public static int sideAnalog(World w, int x, int y, int z, int f) {
        int back = Facing.opposite(f);
        int best = 0;
        for (int k = 0; k < Facing.COUNT; k++) {
            if (k == f || k == back) continue;
            int s = readInput(w, x + Facing.DX[k], y + Facing.DY[k], z + Facing.DZ[k],
                    Facing.opposite(k), true);
            if (s > best) best = s;
        }
        return best;
    }

    /** 六邻中「朝我发射」的最强强度（元件读输入的唯一直入口）。 */
    public static int maxIncoming(World w, int x, int y, int z) {
        int best = 0;
        for (int k = 0; k < Facing.COUNT; k++) {
            int s = strengthToward(w, x + Facing.DX[k], y + Facing.DY[k], z + Facing.DZ[k],
                    Facing.opposite(k));
            if (s > best) best = s;
        }
        return best;
    }

    /** 是否有任一邻居朝我发射（发射器/活塞的"通电"判据）。 */
    public static boolean anyIncoming(World w, int x, int y, int z) {
        return maxIncoming(w, x, y, z) > 0;
    }
}
