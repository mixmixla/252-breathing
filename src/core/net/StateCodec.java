package core.net;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * N2-0：**通用反射式**仿真状态编解码器（快照 / 回滚 / 深拷贝的共同底座）。
 *
 * <p><b>为什么用反射而不是手写序列化</b>：手写 57 个字段 + 8 个系统对象（{@code Individual} 一个类
 * 就约 100 个字段）不但量大，更致命的是**沉默** —— 加了新字段忘了写序列化不会有任何症状，直到某次
 * 回滚后世界悄悄走偏。反射式**默认覆盖全部字段**：加字段自动被覆盖；只有显式列入 {@link #SKIP} 的
 * 才不写，而那是**一次有意识的、被门禁逼出来的决定**。
 *
 * <p><b>定序</b>：字段按**名字排序**写出（不依赖 JVM 声明顺序，那不在规范保证内）。
 * <p><b>自描述类型**：枚举与对象都写出具体类名 → 集合 / 数组元素里的类型也能准确还原。
 * <p><b>原地恢复</b>：{@link #read} 不 new 顶层对象，而是写回**已存在的实例**；集合先 clear 再填充，
 * 且**列表元素优先复用原位实例**（既省构造，又保住对象身份）。这正是回滚需要的语义。
 * <p><b>自动跳过</b>：① {@code final} 字段（构造期常量）；② {@link #SKIP}。
 * <p><b>不支持的字段类型抛异常，绝不静默跳过</b> —— 静默跳过就是"沉默的缺口"，正是本类要消灭的东西。
 *
 * <p><b>存档兼容性（2026-09-30 补）</b>：本格式**按位置**存字段（写出时按名字排序），故**字段集一旦变化
 * 就会错位** —— 读到的值类型与字段声明不符，最终崩在一个不相干的字段上（真实案例：Beast 于 2026-09-21
 * 新增 {@code kbDirX/kbDirZ/kbDist/kbProg} ⇒ 旧存档报 {@code Can not set int field ...recoverT to Boolean}）。
 * 已加**两道防线**，把"随机崩溃"变成"响亮拒绝"：
 * <ol>
 *   <li>{@code anyRead} 的对象段（tag=16）先比对**字段条数**，不符即抛（点名类 + 两侧条数）；</li>
 *   <li>{@code setv} 把反射的 {@code IllegalArgumentException} 包装成"字段 + 实际类型"可读错误。</li>
 * </ol>
 * <p><b>已知局限（诚实交代）</b>：防线①只校**条数** ⇒ "增 1 删 1"的等数改写仍查不出，会退化为位置错位
 * （由防线②接住并点名）。彻底方案是**自描述格式**（每字段写出名字、读时按名匹配、缺失用默认值以保
 * 向后兼容），尚未实现 —— 它要改存档格式（{@code SAVE_EXT_VERSION} 门控 + 旧档迁移）。当前策略是
 * **响亮拒绝而非静默错位**。
 */
public final class StateCodec {

    /**
     * 显式跳过表 —— **类限定名**（形如 {@code World.mat}）。**每一条都必须有理由**
     * （完备性门禁 SNAPSTATE 会核对：未记录的跳过、已失效的条目都会 FAIL）。
     * 其余非 SKIP 字段一律写出（含 {@code final} —— 不可变数据类的常态）。
     *
     * <p>⚠️ <b>为什么按「类.字段」而不是按纯字段名</b>：按纯字段名是**全局**匹配 —— 任何类只要有
     * 同名字段都会被静默跳过。N2-1 实测就撞到了：{@code ContentSystem.registry}（内容注册表，
     * 装配句柄）与 {@code World.registry} 同名而被"顺带"跳过；{@code ChunkDiff.mat/mass}、
     * {@code InputFrame.intents} 同理。那种"因重名而恰好正确"是**偶然**，不是设计 —— 一旦有人
     * 把字段改名，跳过就无声消失。改成类限定后，每个跳过都是**针对某一个类的显式决定**。
     */
    public static final Set<String> SKIP = new LinkedHashSet<String>(Arrays.asList(
            // ---- 巨大数组：由「seed 基线地形 + 稀疏窗口差分 + 异窗整块编辑」重建 ----
            "World.mat", "World.mass",
            "World.chunkEdits", "World.editedChunks",
            // ---- 装配句柄，不是仿真状态 ----
            "World.systems", "World.registry", "ContentSystem.registry",
            // P1：内容兽定义表 = 加载期资产（"配方"），两端各自加载 → 不进快照，否则每个快照都拖一份内容。
            "World.beastDefs",
            // 2026-09-18：材料规格书（内容第 13 类）同理 —— 加载期资产；密度判据（sinksInto）读它。
            // 不进快照的理由与 beastDefs 完全一致：两端各自从同一份 content 加载即一致。
            "World.materials",
            // 2026-09-18 批 C：材料反应表（内容第 14 类）同理 —— 加载期资产；反应求解器读它。
            "World.reactions",
            // 玩法子系统参数（7 个空转参数的落点）：是「配置」不是「演化量」——
            // 两端各自从同一预设加载即一致；且它本身会**改变**演化，写进快照毫无意义。
            "World.config",
            // World 内部暂存位（读档时的系统状态字节），不是仿真状态
            "World.pendingSystemState",
            // ---- N4 基线缓存（ensureBaseline 懒生成的原始地形副本，可重算，非仿真状态）----
            // StateCodec 反射全部非 skip 字段 → 若不跳过会把整份 160^3 原始地形（baseMat/baseMass
            // 各 ~11.5MB）写进每个快照，撑爆 BUDGET_BYTES。载入后由 ensureBaseline 惰性重算。
            "World.baseMat", "World.baseMass",
            "World.baseWinCX0", "World.baseWinCZ0", "World.baselineValid",
            "World.baselineRegenCount",
            // ---- 派生状态：读档后由 rebuildIndex() 全量重算（load 里就在 StateCodec.read 之后立刻调用）----
            // N2-1 实测：这 4 个字段曾被当成"状态"写入，占 v5 段 **69%（916 KB）**，纯属浪费。
            //   surfaceCells 651 KB / surfaceY 130 KB / surfaceTopY 130 KB / waterSurfaceCells 5.5 KB
            "World.typeCells", "World.nonAirCells", "World.indexDelta",
            // 派生索引的**脏标记**：可由 mat 重算，不是仿真状态。
            // ⚠️ 必须 SKIP —— 否则它会被编进 netHash：生产世界里 nonAirStale 几乎恒 true
            // （跨 AIR 边界写入就标脏），而 restoreInPlace/rebuildIndex 后是 false
            // → 回滚对拍会因这个"与行为无关的标记"报 desync（实测 PREDROLLBACK 因此红灯）。
            "World.indexStale", "World.nonAirStale",
            // ---- 第四十七批（真 bug 修复）：批④ 的性能计时字段（`java.lang.System.nanoTime()` 累计）----
            // ⚠️ 它们是**墙钟派生**的：同种子两世界跑同样 tick，这些值也不同。批④ 当时只核对了
            //    "不进 hashState"（窄指纹），**漏了 StateCodec/宽 netHash 这条路** ⇒ 被编进 netHash 后：
            //      · NETDESYNC SAME_SEED_SAME_INPUT_EQUAL / RESTORED 红；
            //      · NETINTEG CROSS_INSTANCE / WRAPPER_FAITHFUL / TICKBODY_INJECTED 红；
            //      · PREDROLLBACK DETERMINISTIC_REPLAY / FINAL_EQUALS_REFERENCE / NO_ROLLBACK_STILL_CONVERGES 红；
            //      · 锁步/UDP 两端 netHash **恒不等** ⇒ 恒报 desync（实测 NETLOCK 子进程 `desync=true`）。
            //    定位工具：`tools/NetHashDiff`（宽哈希三分比对）+ `tools/WorldFieldDiff`（World 字段级比对）。
            //    与上面 nonAirStale 同一条纪律：**hashState 看不见 ≠ netHash 看不见**，一切墙钟/派生字段都必须 SKIP。
            "World.profRelocNs", "World.profGenNs", "World.profGenChunks",
            "World.profCommitNs", "World.profShiftNs",
            "World.profComputeIncNs", "World.profComputeIncN",
            "World.profComputeFullNs", "World.profComputeFullN",
            "World.surfaceCells", "World.waterSurfaceCells",
            "World.surfaceY", "World.surfaceTopY",
            // 渲染脏标记（渲染层提示，非仿真状态；构造期已 markAllChunksDirty）
            "World.dirtyChunks",
            // 派生缓存：由 (SX, SZ, centerX, centerZ) 确定性排序得出；null 时 buildOrder() 自动重建
            "Civilization.urbanOrder",
            // KNOWN GAP：AnimController 无无参构造 → 攻击状态机中间态暂不持久化
            "Player.atkAnim",
            // 本帧待注入的意图队列（Intent 构造器私有 + 属"外部输入"而非世界状态）：
            // 回滚/重演时输入由 InputFrame（N1）重新注入，故不进快照
            "Player.intents"));

    private StateCodec() {}

    /** {@code Class.getSimpleName() + "." + field} —— SKIP 的键格式。 */
    private static String qname(Class<?> c, String field) { return c.getSimpleName() + "." + field; }

    // ================================================================ 顶层 API

    /** 把 {@code o} 的全部可写字段写出。 */
    public static void write(Object o, DataOutput out) throws IOException {
        for (Field f : writable(o.getClass())) anyWrite(getv(f, o), out, 0);
    }

    /** 把字段写回 {@code o}（原地）。容器与同类型对象在内部已改完 → 不回写（final 字段也不可回写）。 */
    public static void read(Object o, DataInput in) throws IOException {
        for (Field f : writable(o.getClass())) {
            Object cur = getv(f, o);
            Object nv = anyRead(cur, in, 0);
            if (nv != cur) setv(f, o, nv);
        }
    }

    /** 该类的「可写字段」：非 static、不在 {@link #SKIP}（**按类限定名匹配**）；**按名字排序**。 */
    public static List<Field> writable(Class<?> c) {
        List<Field> out = new ArrayList<Field>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (SKIP.contains(qname(c, f.getName()))) continue;
            f.setAccessible(true);
            out.add(f);
        }
        Collections.sort(out, new Comparator<Field>() {
            public int compare(Field a, Field b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /** 该类全部非 static 字段名（含常量）—— 供完备性门禁用。 */
    public static List<String> allInstanceFieldNames(Class<?> c) {
        List<String> out = new ArrayList<String>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            out.add(f.getName());
        }
        Collections.sort(out);
        return out;
    }

    /** 工具 API：把任意值按本格式写出（用于逐字段诊断 / 未来的 netHash）。 */
    public static void writeAny(Object v, DataOutput out) throws IOException { anyWrite(v, out, 0); }

    /** 编码为字节数组（系统实例状态 / 工具用）。 */
    public static byte[] encode(Object o) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        write(o, new java.io.DataOutputStream(bo));
        return bo.toByteArray();
    }

    /** 从字节数组**原地**恢复。 */
    public static void decodeInto(Object o, byte[] b) throws IOException {
        read(o, new java.io.DataInputStream(new java.io.ByteArrayInputStream(b)));
    }

    /**
     * 关于 {@code final} 字段的**两次实测教训**（都踩过）：
     * <ol>
     *   <li>最初写「跳过所有 final」→ {@code npcs}/{@code beasts}/{@code shrines} 全是
     *       {@code final List}（引用不可变、内容可变）→ 实体层被整层跳过，读档后世界空心。</li>
     *   <li>改成「只跳过 final 原始类型/String/枚举/数组」→ {@code World.Event} 的 4 个字段、
     *       {@code Trials.Site} 的坐标、{@code Chronicle.Episode} 的文本<b>全是 final</b>
     *       （不可变数据类的常态）→ 数据又被跳过，而且是**静默**的。</li>
     * </ol>
     * 结论：**不能靠 final 判断"是不是状态"**。所以本 codec **写出全部非 static、非 SKIP 字段**，
     * 读回时统一用反射写值（Java 8 下 {@code setAccessible(true)} 后写 final 实例字段是允许的）。
     * 于是"该跳什么"完全由 {@link #SKIP} 里的**显式清单**决定 —— 一望即知、且被门禁盯着。
     */
    /** 该类被跳过的字段名（**简单名**；本类范围内）—— 内部用。 */
    private static Set<String> skippedSimple(Class<?> c) {
        Set<String> out = new HashSet<String>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (SKIP.contains(qname(c, f.getName()))) out.add(f.getName());
        }
        return out;
    }

    /** 被跳过的字段（**类限定名**，如 {@code World.mat}）—— 供完备性门禁用（一眼看出是哪一类）。 */
    public static Set<String> skippedNames(Class<?> c) {
        Set<String> out = new LinkedHashSet<String>();
        for (String s : skippedSimple(c)) out.add(qname(c, s));
        return out;
    }

    /** 会被写出的字段名 —— 供完备性门禁用。 */
    public static Set<String> writtenNames(Class<?> c) {
        Set<String> out = new HashSet<String>(allInstanceFieldNames(c));
        out.removeAll(skippedSimple(c));
        return out;
    }

    // ================================================================ 值层

    private static void anyWrite(Object v, DataOutput out, int depth) throws IOException {
        if (depth > 32) throw new IOException("state nesting too deep");
        if (v == null) { out.writeByte(0); return; }
        Class<?> c = v.getClass();

        if (v instanceof Boolean) { out.writeByte(1); out.writeBoolean((Boolean) v); return; }
        if (v instanceof Integer) { out.writeByte(2); out.writeInt((Integer) v); return; }
        if (v instanceof Float)   { out.writeByte(3); out.writeFloat((Float) v); return; }
        if (v instanceof Long)    { out.writeByte(4); out.writeLong((Long) v); return; }
        if (v instanceof Double)  { out.writeByte(5); out.writeDouble((Double) v); return; }
        if (v instanceof Short)   { out.writeByte(6); out.writeShort((Short) v); return; }
        if (v instanceof Byte)    { out.writeByte(7); out.writeByte((Byte) v); return; }
        if (v instanceof Character) { out.writeByte(8); out.writeChar((Character) v); return; }
        if (v instanceof String)  { out.writeByte(9); out.writeUTF((String) v); return; }

        if (v instanceof core.rng.SeededRNG) {   // 原地类型：只存内部状态（seed 为 final）
            out.writeByte(10);
            out.writeLong(((core.rng.SeededRNG) v).state());
            return;
        }
        if (v instanceof Enum) {
            out.writeByte(11);
            out.writeUTF(c.getName());
            out.writeUTF(((Enum<?>) v).name());
            return;
        }
        if (c.isArray()) {
            out.writeByte(12);
            out.writeUTF(c.getComponentType().getName());
            int n = Array.getLength(v);
            out.writeInt(n);
            for (int i = 0; i < n; i++) anyWrite(Array.get(v, i), out, depth + 1);
            return;
        }
        if (v instanceof List) {
            out.writeByte(13);
            List<?> l = (List<?>) v;
            out.writeInt(l.size());
            for (Object e : l) anyWrite(e, out, depth + 1);
            return;
        }
        if (v instanceof Set) {
            out.writeByte(14);
            Set<?> s = (Set<?>) v;
            out.writeInt(s.size());
            for (Object e : s) anyWrite(e, out, depth + 1);
            return;
        }
        if (v instanceof Map) {
            out.writeByte(15);
            Map<?, ?> m = (Map<?, ?>) v;
            out.writeInt(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                anyWrite(e.getKey(), out, depth + 1);
                anyWrite(e.getValue(), out, depth + 1);
            }
            return;
        }
        if (v instanceof java.util.Collection) {   // Deque / Queue 等（List/Set 已在上面分流）
            out.writeByte(17);
            java.util.Collection<?> col = (java.util.Collection<?>) v;
            out.writeInt(col.size());
            for (Object e : col) anyWrite(e, out, depth + 1);
            return;
        }
        out.writeByte(16);
        out.writeUTF(c.getName());
        List<Field> fs = writable(c);
        out.writeInt(fs.size());
        for (Field f : fs) anyWrite(getv(f, v), out, depth + 1);
    }

    private static Object anyRead(Object cur, DataInput in, int depth) throws IOException {
        if (depth > 32) throw new IOException("state nesting too deep");
        int tag = in.readByte();
        switch (tag) {
            case 0: return null;
            case 1: return in.readBoolean();
            case 2: return in.readInt();
            case 3: return in.readFloat();
            case 4: return in.readLong();
            case 5: return in.readDouble();
            case 6: return in.readShort();
            case 7: return in.readByte();
            case 8: return in.readChar();
            case 9: return in.readUTF();
            case 10: {
                long s = in.readLong();
                if (!(cur instanceof core.rng.SeededRNG))
                    throw new IOException("SeededRNG 位置无现存实例（原地恢复要求字段已初始化）");
                ((core.rng.SeededRNG) cur).setState(s);
                return cur;
            }
            case 11: {
                Class<?> ec = classOf(in.readUTF());
                String en = in.readUTF();
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object e = Enum.valueOf((Class<? extends Enum>) ec.asSubclass(Enum.class), en);
                return e;
            }
            case 12: {
                Class<?> comp = classOf(in.readUTF());
                int n = in.readInt();
                boolean reuse = cur != null && cur.getClass().isArray() && Array.getLength(cur) == n;
                Object arr = reuse ? cur : Array.newInstance(comp, n);
                for (int i = 0; i < n; i++) {
                    Array.set(arr, i, anyRead(reuse ? Array.get(arr, i) : null, in, depth + 1));
                }
                return arr;
            }
            case 13: {
                int n = in.readInt();
                @SuppressWarnings("unchecked")
                List<Object> l = (cur instanceof List) ? (List<Object>) cur : new ArrayList<Object>();
                List<Object> keep = new ArrayList<Object>(l);   // 先留住原位实例供复用
                l.clear();
                for (int i = 0; i < n; i++) {
                    Object old = i < keep.size() ? keep.get(i) : null;
                    l.add(anyRead(old, in, depth + 1));
                }
                return l;
            }
            case 14: {
                int n = in.readInt();
                @SuppressWarnings("unchecked")
                Set<Object> s = (cur instanceof Set) ? (Set<Object>) cur : new LinkedHashSet<Object>();
                List<Object> keep = new ArrayList<Object>(s);
                s.clear();
                for (int i = 0; i < n; i++) {
                    Object e = anyRead(null, in, depth + 1);
                    // 复用语义：若原位集合里存在"同一逻辑元素"（equals），保留原对象身份
                    for (Object o : keep) if (o != null && o.equals(e)) { e = o; break; }
                    s.add(e);
                }
                return s;
            }
            case 15: {
                int n = in.readInt();
                @SuppressWarnings("unchecked")
                Map<Object, Object> m = (cur instanceof Map) ? (Map<Object, Object>) cur : new LinkedHashMap<Object, Object>();
                m.clear();
                for (int i = 0; i < n; i++) {
                    Object k = anyRead(null, in, depth + 1);
                    Object v = anyRead(null, in, depth + 1);
                    m.put(k, v);
                }
                return m;
            }
            case 16: {
                Class<?> oc = classOf(in.readUTF());
                int n = in.readInt();
                List<Field> fs = writable(oc);
                // 🩸 字段集校验（2026-09-30 修）：本格式**按位置**取字段（写出时按名字排序）。
                //   若某类在存档写出之后**增删了字段**，位置就错位 —— 读到的值类型与字段声明不符，
                //   最终崩在一个毫不相干的字段上。实测（真实存档 `save/world.sav`，2026-09-21）：
                //   9/21 之后 Beast 新增 kbDirX/kbDirZ/kbDist/kbProg 四个字段 ⇒ 旧档的 boolean 被塞进
                //   int 字段 ⇒ `Can not set int field core.world.Beast.recoverT to java.lang.Boolean`。
                //   那不是"存档损坏"，是**版本不兼容**。故在此**响亮拒绝**（点名类 + 两侧字段数），
                //   绝不按位置错位解析 —— 宁可拒绝加载，也不要一个"字段串了位"的静默错误世界。
                //   ⚠️ 诚实边界：只校验**条数**（增删相抵的等数改写查不出）；彻底方案是自描述（写字段名），
                //      见类注释「已知局限」。
                if (n != fs.size()) {
                    throw new IOException("状态字段集不匹配（存档与当前代码版本不兼容）："
                            + oc.getName() + " 存档=" + n + " 字段，当前=" + fs.size() + " 字段；"
                            + "本格式按位置解析，条数不同必然错位，故拒绝加载。"
                            + "（旧存档请删除 save/world.sav 后重开；详见 StateCodec 类注释）");
                }
                Object o = (cur != null && cur.getClass() == oc) ? cur : inst(oc);
                for (int i = 0; i < n; i++) {
                    Field f = fs.get(i);
                    setv(f, o, anyRead(getv(f, o), in, depth + 1));
                }
                return o;
            }
            case 17: {
                int n = in.readInt();
                @SuppressWarnings("unchecked")
                java.util.Collection<Object> col = (cur instanceof java.util.Collection)
                        ? (java.util.Collection<Object>) cur : new java.util.ArrayList<Object>();
                List<Object> keep = new ArrayList<Object>(col);
                col.clear();
                for (int i = 0; i < n; i++) {
                    col.add(anyRead(i < keep.size() ? keep.get(i) : null, in, depth + 1));
                }
                return col;
            }
            default:
                throw new IOException("unknown state tag " + tag);
        }
    }

    // ================================================================ 小工具

    private static Object getv(Field f, Object o) throws IOException {
        try { return f.get(o); } catch (IllegalAccessException e) { throw new IOException(e); }
    }

    private static void setv(Field f, Object o, Object v) throws IOException {
        try {
            f.set(o, v);
        } catch (IllegalAccessException e) {
            throw new IOException(e);
        } catch (IllegalArgumentException e) {
            // 第二道防线（2026-09-30）：即便字段条数相同（增删相抵的等数改写），位置错位仍会在此暴露。
            // 把它变成"点名到字段 + 实际类型"的可读错误，而不是一条裸反射消息
            // （裸消息如 `Can not set int field core.world.Beast.recoverT to java.lang.Boolean`
            //  只暴露了**受害**字段，不说明是版本不兼容，极易被误读成"数据损坏"）。
            throw new IOException("状态字段类型不匹配：" + f.getDeclaringClass().getName() + "." + f.getName()
                    + "（声明 " + f.getType().getSimpleName() + "，存档值 "
                    + (v == null ? "null" : v.getClass().getSimpleName()) + "）"
                    + " —— 通常是存档与代码版本不一致导致字段错位（见 StateCodec 类注释）", e);
        }
    }

    private static Class<?> classOf(String name) throws IOException {
        if ("int".equals(name)) return int.class;
        if ("float".equals(name)) return float.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("byte".equals(name)) return byte.class;
        if ("short".equals(name)) return short.class;
        if ("long".equals(name)) return long.class;
        if ("double".equals(name)) return double.class;
        if ("char".equals(name)) return char.class;
        try { return Class.forName(name); }
        catch (ClassNotFoundException e) { throw new IOException("unknown state class " + name, e); }
    }

    /**
     * 构造一个"空白"实例。
     *
     * <p>优先走**无参构造**（干净、可读：{@code Player}/{@code ArrayList} 等）。若类没有无参构造
     * （本项目大量数据类如此：{@code Npc} 需要 Body/Mind/Social 三个子对象、{@code Intent} 构造器私有、
     * {@code Beast}/{@code Site}/{@code Episode} 只有带参构造），则退化为
     * {@code Unsafe.allocateInstance} —— **不调用构造器**，字段随后由本 codec 逐个写回。
     *
     * <p>为什么不用"给每个数据类补空构造"：那要给 10+ 个类写假构造器（还可能级联要求子对象也有），
     * 每加一个新数据类都要记得补，**又会变成一种沉默的缺口**。Unsafe 路径对任何新类自动适用。
     */
    private static Object inst(Class<?> c) throws IOException {
        try {
            return c.newInstance();
        } catch (Exception ignore) {
            // 退化路径
        }
        try {
            Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            java.lang.reflect.Method m = unsafe.getClass().getMethod("allocateInstance", Class.class);
            return m.invoke(unsafe, c);
        } catch (Exception e) {
            throw new IOException("无法构造 " + c.getName()
                    + "（既无无参构造，Unsafe.allocateInstance 也不可用）", e);
        }
    }
}
