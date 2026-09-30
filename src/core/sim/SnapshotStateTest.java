package core.sim;

import core.net.StateCodec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SNAPSTATE 门禁（N2-0/N2-1：快照完备性 + 紧凑性分类）。
 *
 * <p><b>为什么需要它</b>：回滚 = 回到过去某 tick 的快照 → 用修正输入重演，要求快照**完整**。
 * 而"完整"最怕的不是难写，是**沉默** —— 加一个字段忘了序列化不会有任何症状，直到某次回滚后
 * 世界悄悄走偏（而 {@code hashState()} 又因故意做窄而看不见）。
 *
 * <p><b>反过来，"不完备"之外还有第二种沉默：快照悄悄膨胀。</b> 派生的大字段被当成状态写进去，
 * 不会让任何门禁变红，只会让快照从 573 KB 涨回 22 MB。N2-1 实测就踩到了 ——
 * {@code surfaceCells}/{@code surfaceY}/{@code surfaceTopY} 三个派生字段曾占 v5 段的 91%。
 * 故本门禁同时断言 {@link #MUST_BE_SKIPPED}。
 *
 * <p><b>设计要点：与 codec 同源，不各自维护一份名单。</b>
 * {@code PERSISTED} 直接由 {@link StateCodec#writtenNames} 算出；本门禁只维护
 * <b>{@link #SKIP_DOC} —— 「跳过的字段 + 理由」</b>。于是：
 * <ul>
 *   <li>新增字段 → 自动进入 PERSISTED → **零维护、零缺口**（这是反射式 codec 的核心红利）。</li>
 *   <li>有人把字段加进 {@code StateCodec.SKIP} → 本门禁立刻 FAIL，直到他**在这里写下理由**。</li>
 * </ul>
 *
 * <p><b>断言的性质</b>：
 * ① 跳过名单与 {@link #SKIP_DOC} 完全一致（不漏、不多）；
 * ② **无幽灵条目**（每条 SKIP 都必须是该类的真实实例字段 —— 字段改名后条目不会永远留着）；
 * ③ 每个实例字段要么被持久化、要么有理由；
 * ④ 防假绿（枚举字段数 ≥ 下限）；
 * ⑤ 跳过数等于记录值；
 * ⑥ **派生字段必须在 SKIP 里**（{@link #MUST_BE_SKIPPED}）。
 */
public final class SnapshotStateTest {

    private static final int WORLD_FLOOR = 70;
    private static final int PLAYER_FLOOR = 36;

    /** 参与快照、且被 SKIP 关心的类（简单名 → 全限定名）—— 用于"无幽灵条目"校验。 */
    private static final Map<String, String> CLASSES = new LinkedHashMap<String, String>();
    static {
        CLASSES.put("World", "core.world.World");
        CLASSES.put("Player", "core.world.Player");
        CLASSES.put("Civilization", "core.world.Civilization");
        CLASSES.put("ContentSystem", "core.systems.ContentSystem");
    }

    /**
     * 「跳过的字段（类限定名）→ 理由」。**每一条都必须是"不可持久化"或"可确定性重建"或"已知缺口"**。
     */
    private static final Map<String, String> SKIP_DOC = new LinkedHashMap<String, String>();
    static {
        SKIP_DOC.put("World.mat", "巨大数组；由 seed 基线地形 + 稀疏窗口差分 + 异窗整块编辑重建");
        SKIP_DOC.put("World.mass", "同上");
        SKIP_DOC.put("World.chunkEdits", "巨大；作为 header 段独立序列化");
        SKIP_DOC.put("World.editedChunks", "由 chunkEdits 的键集推导");
        SKIP_DOC.put("World.typeCells", "派生索引（M3 CellSet），rebuildIndex 可重建");
        SKIP_DOC.put("World.nonAirCells", "派生索引，同上");
        SKIP_DOC.put("World.indexDelta", "派生增量缓冲，同上");
        SKIP_DOC.put("World.indexStale", "派生索引脏标记（可重算，非仿真状态）；且必须 SKIP —— 见 StateCodec 里 nonAirStale 的理由（编进 netHash 会误报 desync）");
        SKIP_DOC.put("World.nonAirStale", "派生索引脏标记：nonAirCells 是否需惰性重建（可重算，非仿真状态）；同上");
        SKIP_DOC.put("World.surfaceCells", "派生索引，rebuildIndex 可重建（N2-1 实测占 651 KB，纯浪费）");
        SKIP_DOC.put("World.waterSurfaceCells", "派生索引，同上");
        SKIP_DOC.put("World.surfaceY", "派生索引（每列地表高度），rebuildIndex 可重建");
        SKIP_DOC.put("World.surfaceTopY", "派生索引（rebuildIndex 的临时暂存缓冲），同上");
        SKIP_DOC.put("World.dirtyChunks", "渲染脏标记（渲染层提示，非仿真状态；构造期已全标脏）");
        SKIP_DOC.put("World.systems", "装配句柄；其**内部状态**由 v5 的 systems 段单独快照");
        SKIP_DOC.put("World.registry", "装配句柄；由 registerDefaultSystems 重建");
        SKIP_DOC.put("World.pendingSystemState", "读档暂存位（系统状态字节），非仿真状态");
        // ---- N4 基线缓存（ensureBaseline 懒生成的原始地形副本/标记，可重算，非仿真状态）----
        SKIP_DOC.put("World.baseMat", "N4 基线缓存：原始地形 mat 副本；载入后由 ensureBaseline 惰性重算（不进快照，否则整份 160^3 地形被写进每个快照）");
        SKIP_DOC.put("World.baseMass", "N4 基线缓存：原始地形 mass 副本；同上");
        SKIP_DOC.put("World.baseWinCX0", "N4 基线缓存：基线窗口原点；由 ensureBaseline 按当前窗口重算");
        SKIP_DOC.put("World.baseWinCZ0", "N4 基线缓存：基线窗口原点；同上");
        SKIP_DOC.put("World.baselineValid", "N4 基线缓存：缓存有效性标记；载入后默认 false → 触发重算");
        SKIP_DOC.put("World.baselineRegenCount", "N4 基线缓存：仅门禁观测用的重生成计数，非仿真状态");
        SKIP_DOC.put("World.beastDefs", "P1 内容资产（beasts/*.json 解析结果）—— 是「配方」不是「演化量」："
                + "两端各自从同一份内容加载即可一致；写进快照等于每份快照都拖一份内容表。"
                + "对比：World.beastCap 是配置但要进快照（两端必须同值），beastDefs 是加载期常量，故不同待遇");
        SKIP_DOC.put("World.materials", "2026-09-18 材料规格书（materials/*.json 解析结果）—— 与 beastDefs 同纪律的加载期资产："
                + "是「配方」不是「演化量」，两端各自加载同一份内容即一致；写进快照等于每份快照都拖一份材料表。"
                + "它是密度判据（MaterialBook.sinksInto）的数据来源，被 SandFallSystem 读取");
        SKIP_DOC.put("World.reactions", "2026-09-18 批 C 材料反应表（reactions/*.json 解析结果）—— 与 World.materials 同纪律的加载期资产："
                + "是「规则」不是「演化量」，两端各自加载同一份内容即一致；写进快照等于每份快照都拖一份反应表。"
                + "它是反应求解器（ReactionSystem）的唯一输入；空表时任何材料对都查不到规则 → 零写入");
        SKIP_DOC.put("World.config", "玩法子系统参数容器（WorldConfig）：是「配置」不是「演化量」——"
                + "两端各自从同一预设加载即一致；且非默认值会改变演化，写进快照毫无意义。"
                + "与 World.beastCap 的差别：beastCap 影响每 tick 刷怪且必须两端同值（故进快照），"
                + "config 是预设派生物，按 id 重放即可");
        SKIP_DOC.put("Civilization.urbanOrder", "派生缓存；由 (SX,SZ,centerX,centerZ) 确定性排序得出，null 时 buildOrder 自动重建");
        SKIP_DOC.put("ContentSystem.registry", "装配句柄（内容注册表，来自磁盘内容文件），非仿真状态");
        SKIP_DOC.put("Player.atkAnim", "KNOWN GAP：AnimController 无无参构造，攻击状态机中间态暂不持久化");
        SKIP_DOC.put("Player.intents", "待注入意图队列；属外部输入（N1 InputFrame），回滚时重新注入");
        // ---- 第四十七批：批④ 的性能计时字段（nanoTime 累计）----
        // ⚠️ **墙钟派生**：同种子两世界跑同样 tick，这些值也不同 ⇒ 编进宽 netHash 会让
        // NETDESYNC/NETINTEG/PREDROLLBACK/NETLOCK/UDPLOCK 全红（实测）。批④ 只核对了"不进 hashState"
        // 的窄指纹，漏了 StateCodec 这条路 ⇒ 补 SKIP。理由细节见 StateCodec 里该组注释。
        SKIP_DOC.put("World.profRelocNs", "性能计时（墙钟累计），派生/非仿真状态；且必须 SKIP（否则污染 netHash）");
        SKIP_DOC.put("World.profGenNs", "同上");
        SKIP_DOC.put("World.profGenChunks", "同上（块生成计数）");
        SKIP_DOC.put("World.profCommitNs", "同上");
        SKIP_DOC.put("World.profShiftNs", "同上");
        SKIP_DOC.put("World.profComputeIncNs", "同上（增量光照计时）");
        SKIP_DOC.put("World.profComputeIncN", "同上（增量光照计数）");
        SKIP_DOC.put("World.profComputeFullNs", "同上（全量光照计时）");
        SKIP_DOC.put("World.profComputeFullN", "同上（全量光照计数）");
    }

    private static final int RECORDED_SKIPPED = 40;   // 31 -> 40：批④ 性能计时字段 World.prof*（9 个，墙钟派生，必须 SKIP）

    /**
     * **必须**在 SKIP 里的「派生状态」清单。
     *
     * <p>本断言防的是 N2-1 实测踩到的那类坑：派生的巨大字段被当成"状态"写进快照 —— 不会让门禁红
     * （快照仍"完整"），只会让快照**悄悄膨胀**。实测战绩：{@code surfaceCells} 651 KB +
     * {@code surfaceY} 130 KB + {@code surfaceTopY} 130 KB + {@code waterSurfaceCells} 5.5 KB
     * = v5 段的 **69%（916 KB）**，而它们读档后一行 {@code rebuildIndex()} 就全出来了。
     *
     * <p>性质：**"派生"是一种必须被断言的属性**，不能靠"写的人记得"。
     */
    private static final Set<String> MUST_BE_SKIPPED = new java.util.LinkedHashSet<String>(Arrays.asList(
            "World.mat", "World.mass",
            "World.typeCells", "World.nonAirCells", "World.indexDelta",
            "World.indexStale", "World.nonAirStale",
            "World.surfaceCells", "World.waterSurfaceCells", "World.surfaceY", "World.surfaceTopY",
            "World.dirtyChunks", "Civilization.urbanOrder",
            // 2026-09-29（第四十七批）：性能计时字段——"派生是一种必须被断言的属性"。
            // 它们墙钟派生 ⇒ 既不进快照、也必须不进 netHash；把它们钉进本清单，改名/漏 SKIP 都会立刻 FAIL。
            "World.profRelocNs", "World.profGenNs", "World.profGenChunks",
            "World.profCommitNs", "World.profShiftNs",
            "World.profComputeIncNs", "World.profComputeIncN",
            "World.profComputeFullNs", "World.profComputeFullN"));

    private static int props = 0;
    private static boolean fail = false;

    private static void ok(String name, boolean cond) {
        System.out.println("  " + (cond ? "ok  " : "FAIL") + " " + name);
        if (cond) props++; else fail = true;
    }

    /**
     * 兼容性探针：把 {@code Beast} 对象段的**字段条数**篡改后再读，必须抛"字段集不匹配"
     * （而非错位后崩在某个不相干的字段上）。这是 2026-09-30 新增**第一道防线**的行为证明。
     *
     * <p>字节布局（{@code StateCodec.anyWrite} 的对象段，tag=16）：
     * {@code tag(1) + writeUTF(类名: 2 字节长度 + N 字节内容) + 条数(int 4 字节) + 各字段值}。
     * 故条数偏移 = 1 + 2 + 类名长度。
     */
    private static boolean compatFieldCountMismatchRejected() {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(bo);
            core.net.StateCodec.writeAny(new core.world.Beast(1f, 2f, 3f, 0), d);
            d.flush();
            byte[] okBytes = bo.toByteArray();

            String cn = "core.world.Beast";
            int nPos = 1 + 2 + cn.length();
            int n = ((okBytes[nPos] & 0xff) << 24) | ((okBytes[nPos + 1] & 0xff) << 16)
                    | ((okBytes[nPos + 2] & 0xff) << 8) | (okBytes[nPos + 3] & 0xff);
            // 正样本对照（防探针自身假绿）：偏移若算错，读出的条数不会等于真实可写字段数，
            // 此时篡改的是别的字节 ⇒ 读取不会抛异常 ⇒ 断言会 FAIL（而不是"侥幸通过"）。
            int expect = core.net.StateCodec.writable(core.world.Beast.class).size();
            if (n != expect) {
                System.out.println("  info  COMPAT 条数定位错误：读出 " + n + " 期望 " + expect);
                return false;
            }
            int bad = n + 1;
            byte[] badBytes = okBytes.clone();
            badBytes[nPos] = (byte) (bad >>> 24); badBytes[nPos + 1] = (byte) (bad >>> 16);
            badBytes[nPos + 2] = (byte) (bad >>> 8); badBytes[nPos + 3] = (byte) bad;

            try {
                core.net.StateCodec.read(new Holder(),
                        new java.io.DataInputStream(new java.io.ByteArrayInputStream(badBytes)));
                System.out.println("  info  COMPAT 未抛异常（防线失效！）Beast 字段数=" + n);
                return false;
            } catch (java.io.IOException e) {
                String m = String.valueOf(e.getMessage());
                boolean hit = m.contains("字段集不匹配");
                if (!hit) System.out.println("  info  COMPAT 抛了别的异常: " + m);
                return hit;
            }
        } catch (Exception e) {
            System.out.println("  info  COMPAT 探针自身异常: " + e);
            return false;
        }
    }

    /** 兼容性探针的容器：{@code StateCodec.read} 按**外层对象的字段**逐值遍历。 */
    public static final class Holder {
        public core.world.Beast b = new core.world.Beast(0f, 0f, 0f, 0);
    }

    public static void main(String[] args) {
        Set<String> skip = new TreeSet<String>(StateCodec.SKIP);
        Set<String> want = new TreeSet<String>(SKIP_DOC.keySet());

        // ① 名单一致
        Set<String> undocumented = new TreeSet<String>(skip); undocumented.removeAll(want);
        Set<String> stale = new TreeSet<String>(want); stale.removeAll(skip);
        ok("SKIP_LIST_DOCUMENTED", undocumented.isEmpty() && stale.isEmpty());
        for (String s : undocumented) System.out.println("  FAIL 未经记录的跳过: " + s + " —— 请在 SKIP_DOC 里写下理由");
        for (String s : stale) System.out.println("  FAIL SKIP_DOC 里的条目已不是跳过字段: " + s);

        // ② 无幽灵条目：每条 SKIP 都必须是该类的真实实例字段（字段改名后不会永远留着）
        Set<String> phantom = new TreeSet<String>();
        for (String q : skip) {
            int i = q.indexOf('.');
            String cn = q.substring(0, i), fn = q.substring(i + 1);
            String fq = CLASSES.get(cn);
            if (fq == null) { phantom.add(q + "（未知类，未在 CLASSES 登记）"); continue; }
            boolean found = false;
            try {
                for (java.lang.reflect.Field f : Class.forName(fq).getDeclaredFields())
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getName().equals(fn)) { found = true; break; }
            } catch (ClassNotFoundException e) { phantom.add(q + "（类不存在）"); continue; }
            if (!found) phantom.add(q + "（字段已不存在）");
        }
        ok("NO_PHANTOM_SKIP", phantom.isEmpty());
        for (String s : phantom) System.out.println("  FAIL 幽灵 SKIP 条目: " + s);

        ok("SKIP_COUNT_BASELINE", skip.size() == RECORDED_SKIPPED);

        List<String> wFields = StateCodec.allInstanceFieldNames(core.world.World.class);
        List<String> pFields = StateCodec.allInstanceFieldNames(core.world.Player.class);
        ok("ENUM_FLOOR", wFields.size() >= WORLD_FLOOR && pFields.size() >= PLAYER_FLOOR);

        Set<String> wSkip = StateCodec.skippedNames(core.world.World.class);
        Set<String> pSkip = StateCodec.skippedNames(core.world.Player.class);
        Set<String> wPersist = StateCodec.writtenNames(core.world.World.class);
        Set<String> pPersist = StateCodec.writtenNames(core.world.Player.class);

        // ③ 穷尽：每个字段要么持久化、要么有理由
        ok("EVERY_FIELD_PERSISTED_OR_DOCUMENTED",
                wPersist.size() + wSkip.size() == wFields.size()
                        && pPersist.size() + pSkip.size() == pFields.size());

        // ⑥ 派生字段必须在 SKIP 里
        Set<String> missingDerived = new TreeSet<String>(MUST_BE_SKIPPED);
        missingDerived.removeAll(skip);
        ok("DERIVED_FIELDS_NOT_PERSISTED", missingDerived.isEmpty());
        for (String s : missingDerived)
            System.out.println("  FAIL 派生字段被当成状态写进快照: " + s
                    + "（会白占字节；读档后 rebuildIndex()/buildOrder() 本就会重算它）");

        // ⑦ 兼容性防线（2026-09-30）：字段条数不匹配 ⇒ 必须**响亮报错**，绝不按位置错位解析。
        //   真案例：Beast 于 2026-09-21 新增 kbDirX/kbDirZ/kbDist/kbProg ⇒ 旧存档读档时错位，
        //   抛裸反射消息 `Can not set int field ...recoverT to java.lang.Boolean` —— 既不说明是
        //   版本不兼容，Game 也只记一行 WARN 后静默开新世界（玩家以为进度莫名丢失）。
        ok("COMPAT_FIELDCOUNT_MISMATCH_REJECTED", compatFieldCountMismatchRejected());

        System.out.println("  info  enumerated: World=" + wFields.size()
                + " Player=" + pFields.size() + "  参与 SKIP 的类=" + CLASSES.keySet());
        System.out.println("  info  World : PERSISTED=" + wPersist.size() + " SKIPPED=" + wSkip.size()
                + "  Player: PERSISTED=" + pPersist.size() + " SKIPPED=" + pSkip.size()
                + "  合计跳过=" + skip.size() + "（基线 " + RECORDED_SKIPPED + "）");
        System.out.println("  info  逐条跳过理由：");
        for (Map.Entry<String, String> e : SKIP_DOC.entrySet()) {
            System.out.println("      - " + e.getKey() + " : " + e.getValue());
        }

        System.out.println((fail ? "SNAPSTATE FAIL (" : "SNAPSTATE PASS (") + props + " properties)");
        if (fail) System.exit(1);
    }
}
