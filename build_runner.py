import os, sys, subprocess, glob, shutil, time


def _resolve_root():
    """仓库根 = `BW_ROOT`（显式覆盖）或**本脚本所在目录**。

    2026-09-30 修（B02）：原值是开发机绝对路径 `C:\\Users\\Administrator\\...`，
    后果有两层 —— ① 别人 clone 到别的路径后根本跑不起来；
    ② **更糟的是**：若那台机器上该路径仍然存在，会静默地**对另一个工作副本**执行审计/构建。
    故改为以脚本位置为准，并在此**校验仓库身份**：报错永远好过静默跑错目录。
    """
    explicit = os.environ.get("BW_ROOT")
    root = os.path.abspath(explicit) if explicit else os.path.dirname(os.path.abspath(__file__))
    if not os.path.isfile(os.path.join(root, "src", "core", "world", "World.java")):
        sys.stderr.write(
            "[FATAL] 仓库根校验失败：" + root + "\n"
            "        期望在此找到 src/core/world/World.java。\n"
            "        BW_ROOT = " + (explicit if explicit else "(未设置，已回退到脚本所在目录)") + "\n"
            "        请从仓库根目录运行，或把 BW_ROOT 指向正确的仓库根。\n")
        sys.exit(2)
    return root


ROOT = _resolve_root()
REPORT = os.path.join(ROOT, "build_report.txt")
LIBS = ["libs/jna-5.13.0.jar", "libs/gson-2.10.1.jar", "libs/lwjgl-3.3.3.jar", "libs/lwjgl-glfw-3.3.3.jar",
        "libs/lwjgl-opengl-3.3.3.jar", "libs/joml-1.10.5.jar"]
# 原生库（仅 SHADERGLSL 门禁需要：它要建真 GL 上下文来编译内联 GLSL）
NATIVES = ["libs/lwjgl-3.3.3-natives-windows.jar", "libs/lwjgl-glfw-3.3.3-natives-windows.jar",
           "libs/lwjgl-opengl-3.3.3-natives-windows.jar"]

def log(msg):
    with open(REPORT, "a", encoding="utf-8") as f:
        f.write(msg + "\n")

def find_exe(name):
    # PATH
    for p in os.environ.get("PATH", "").split(";"):
        cand = os.path.join(p, name)
        if os.path.isfile(cand):
            return cand
    # .jdks
    base = r"C:\Users\Administrator\.jdks"
    if os.path.isdir(base):
        for dirpath, _, files in os.walk(base):
            if name in files:
                return os.path.join(dirpath, name)
    # Program Files
    for base2 in [r"C:\Program Files", r"C:\Program Files (x86)"]:
        if os.path.isdir(base2):
            for dirpath, _, files in os.walk(base2):
                if name in files:
                    return os.path.join(dirpath, name)
    return None

def main():
    _t_wall0 = time.time()   # 批 D1：总墙钟。此前报告里只有"各门禁耗时之和"（并行下会因竞争虚高），
                             # 看不到真实构建时长 ⇒ 无法判断并行是否真的提速。
    open(REPORT, "w", encoding="utf-8").write("=== BUILD REPORT ===\n")
    javac = find_exe("javac.exe")
    java = find_exe("java.exe")
    if javac is None:
        log("NO_JAVAC")
        return
    log("JAVAC=" + javac)
    log("JAVA=" + str(java))

        # ---------- 构建前置：静态不变量审计（只读、秒级）----------
    # 纪律见 audit_invariants.py 顶部：命中必须可执行（噪声为 0）、豁免必须带理由并打印。
    # 有 FAIL 直接中止构建 —— 否则"审计过了没人看"等于没有审计（BW_SKIP_AUDIT=1 可临时跳过，跳过会留痕）。
    audit = os.path.join(ROOT, "audit_invariants.py")
    if os.environ.get("BW_SKIP_AUDIT") == "1":
        log("AUDIT_EXIT=SKIPPED (BW_SKIP_AUDIT=1)  <-- 本次未做前置审计，不得据此认为通过")
    elif os.path.isfile(audit):
        env2 = dict(os.environ); env2["BW_ROOT"] = ROOT
        try:
            ar = subprocess.run([sys.executable, audit], cwd=ROOT, env=env2,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            rc, aout = ar.returncode, ar.stdout.decode("utf-8", "replace")
        except Exception as e:
            rc, aout = 9, "audit runner exception: %r" % (e,)
        log("--- AUDIT (audit_invariants.py) ---")
        for aline in aout.splitlines():
            log(aline)
        log("AUDIT_EXIT=%d" % rc)
        if rc != 0:
            log("BUILD ABORTED BY AUDIT  <-- 先处置上面的 FAIL 项再构建")
            return
    else:
        log("AUDIT_EXIT=SKIPPED (audit_invariants.py 不存在)  <-- 前置审计缺失")

    out = os.path.join(ROOT, "out")
    # 清理 out/ 仅为去掉"已删源文件"的残留 .class —— javac 本就会全量重编我们显式列出的源，
    # 故增量验证时可置 BW_KEEP_OUT=1 跳过批量删除（沙箱对一次 >50 文件的删除会拦截）。
    if os.path.isdir(out) and os.environ.get("BW_KEEP_OUT") != "1":
        shutil.rmtree(out)
    os.makedirs(out, exist_ok=True)

    def collect(rel):
        """返回**相对 ROOT** 的路径（cwd 就是 ROOT）。

        <b>为什么必须是相对路径</b>（2026-09-18 事故）：此前返回绝对路径，245 个文件 ×
        约 140 字符 ≈ 34KB，<b>超过 Windows 命令行 32KB 上限</b> → javac 抛
        {@code [WinError 206] 文件名或扩展名太长}，而构建器当时只在日志里写
        {@code CORE_THREW=...} 后<b>继续往下跑门禁</b> —— 于是全部门禁跑在<b>旧 class</b> 上，
        产出一份"全绿"的假报告（且 {@code *_EXIT} 计数根本不含 {@code _THREW} 行，检查不出来）。
        改成相对路径后命令行降到约 7KB；下面的失败即中止是第二道防线。
        """
        files = []
        d = os.path.join(ROOT, rel)
        for fn in os.listdir(d):
            if fn.endswith(".java"):
                files.append(os.path.join(rel, fn).replace("\\", "/"))
        return files

    core = (collect("src/core/rng") + collect("src/core/world") +
            collect("src/core/agent") + collect("src/core/systems") +
            collect("src/core/audio") + collect("src/core/content") + collect("src/core/anim") +
            collect("src/core/net") + collect("src/core/sim"))
    soft = collect("src/render/software")
    audio = collect("src/render/audio")     # javax.sound 在 rt.jar：零新增依赖
    lwjgl = collect("src/render/lwjgl")

    compile_timings = []   # 批 D1：per-段编译墙钟。此前只有门禁有计时 ⇒「编译慢」只能靠总时长反推。

    def compile_files(label, files, classpath=None):
        cmd = [javac, "-encoding", "UTF-8", "-d", out]
        if classpath:
            cmd += ["-cp", classpath]
        cmd += files
        t0 = time.time()
        try:
            r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True,
                                encoding="gbk", errors="replace", timeout=300)
            out_txt = (r.stdout or "") + (r.stderr or "")
            for line in out_txt.splitlines():
                log(line)
            rc = r.returncode
        except Exception as e:
            # 第二道防线：抛异常（含 timeout）也必须落到 `<label>_EXIT`（否则"数 *_EXIT 非零"的检查
            # 会漏掉它），并且**中止构建**（见下面的 abort）—— 绝不能在编译失败时继续跑门禁（会假绿）。
            # 🐛 2026-09-30 修：此处原有**重复的 except 子句**，且第一个引用 `r`（subprocess 抛异常时
            #    `r` 未赋值 ⇒ UnboundLocalError，让异常处理自身崩溃）。合并为一个 except，改用 rc 变量。
            log(f"{label}_THREW={e}")
            rc = -1
        ms = int((time.time() - t0) * 1000)
        compile_timings.append((ms, label))
        log(f"{label}_EXIT={rc}")
        log(f"COMPILE_{label}_MS={ms}")
        return rc

    cp = "out;" + ";".join(LIBS)
    # **编译失败即中止**：否则门禁会跑在旧 class 上，产出"全绿"假报告（2026-09-18 事故）。
    for _label, _files, _cp in (("CORE", core, "out;" + ";".join(LIBS)),   # core/anim 依赖 gson
                                ("SOFT", soft, "out"),
                                ("AUDIO", audio, "out"),                    # javax.sound 来自 JDK
                                ("LWJGL", lwjgl, cp)):
        if compile_files(_label, _files, _cp) != 0:
            log(f"BUILD ABORTED BY {_label} COMPILE  <-- 编译失败，门禁未跑（不留下假绿报告）")
            return
    tools = collect("tools")            # 离线校验工具（ShaderCheck 等）；编译失败不得让门禁假绿
    if compile_files("TOOLS", tools, cp) != 0:
        log("BUILD ABORTED BY TOOLS COMPILE  <-- 编译失败，门禁未跑（不留下假绿报告）")
        return

    timings = []      # 批⑯：per-gate 墙钟。此前完全没有这个数据 ⇒ 82 门禁 ~2min 却"不知道谁慢"，
                      # 于是"门禁分层 / 并行化"只能靠猜。纯新增日志，不改任何门禁行为。

    # 批 D1（2026-09-30）：可选并行。实测编译段仅 **9.2s**、门禁占 **~102s** ⇒ 真要提速只能并行门禁
    # （**不能削弱门禁强度** —— 它们是正确性保障）。**默认 JOBS=1，行为与从前逐字节相同**；
    # 设 `BW_JOBS=N` 才并行（各门禁是独立进程、各自 new World；输出按**原调用顺序**落盘以保报告确定性）。
    _JOBS = max(1, int(os.environ.get("BW_JOBS", "1")))
    import concurrent.futures as _cf
    _pool = _cf.ThreadPoolExecutor(max_workers=_JOBS) if _JOBS > 1 else None
    _pending = []

    def _run_gate(label, ccp, cls):
        t0 = time.time()
        try:
            r = subprocess.run([java, "-cp", ccp, cls], cwd=ROOT, capture_output=True,
                                text=True, encoding="gbk", errors="replace", timeout=120)
            txt = (r.stdout or "") + (r.stderr or "")
            rc = r.returncode
        except Exception as e:
            txt = f"{label}_THREW={e}"
            rc = None
        return txt, rc, int((time.time() - t0) * 1000)

    def gate(label, cls, extra=""):
        ccp = "out;" + ";".join(LIBS) + ((";" + extra) if extra else "")   # 含 LIBS（core/anim 需 gson）
        if _pool is not None:
            _pending.append((label, _pool.submit(_run_gate, label, ccp, cls)))
            return
        txt, rc, ms = _run_gate(label, ccp, cls)
        for line in txt.splitlines():
            log(line)
        if rc is not None:
            log(f"{label}_EXIT={rc}")
        timings.append((ms, label))
        log(f"{label}_MS={ms}")

    def flush_gates():
        """并行模式：按**原调用顺序**落盘（保证报告可复现），并回收线程池。串行模式为 no-op。"""
        if _pool is None:
            return
        for label, fut in _pending:
            txt, rc, ms = fut.result()
            for line in txt.splitlines():
                log(line)
            if rc is not None:
                log(f"{label}_EXIT={rc}")
            timings.append((ms, label))
            log(f"{label}_MS={ms}")
        _pending.clear()
        _pool.shutdown()

    log("=== GATES ===")
    gate("DET", "core.sim.DeterminismTest")
    gate("ZD", "core.sim.ZeroDriftTest")
    gate("PHY", "core.sim.PhysicsTest")
    gate("STR", "core.sim.StreamingTest")
    gate("NPCD", "core.sim.NpcDeterminismTest")   # 批次 0 第二道：生成 NPC 后同种子自洽
    gate("SOCD", "core.sim.SocialDeterminismTest")  # 批次 1：社会核心（家族/情绪/规范）同种子自洽
    gate("STORYD", "core.sim.StorytellerDeterminismTest")  # 批次 1 余项：说书人/村志同种子自洽
    gate("CIVD", "core.sim.CivilizationDeterminismTest")    # 批次 2：文明深度（7 系统）同种子自洽
    gate("INDD", "core.sim.IndividualDeterminismTest")      # 批次 3：个体成长（18 系统）同种子自洽
    gate("POLT", "core.sim.PolityDeterminismTest")          # 批次 4：民政经济 + 灾害同种子自洽
    gate("TRIALD", "core.sim.TrialDeterminismTest")         # C2：试炼/遗物/世界之心 同种子自洽 + 目标链可达
    gate("FIRELEVER", "core.sim.FireLeverTest")              # P2-2：涌现杠杆（FLINT 引火→火蔓延）确定性 + 可达性
    gate("ELEMENTLEVER", "core.sim.ElementLeverTest")        # P2-2：水/雷杠杆（AQUA 灌田 / THUNDER 导电）确定性 + 可达性
    gate("MESHCULL", "core.sim.MeshCullTest")                # P2-3：透明方块面剔除（水/玻璃不遮挡邻居 + translucent 分类唯一）
    gate("GREEDY", "GreedyCheck")          # 批⑯：贪心网格合并的无头几何等价（唯一覆盖 render/lwjgl/Chunk.collectGreedyQuads，顶点 -69.7%）
    gate("MESHPASS", "MeshPassCheck")      # 批⑯：透明网格分工 + quadIndices 索引缓冲模式（与 MESHCULL 正交：它测"两条网格路径面集一致"）
    # 批⑯（复勘修正后接入）：这四条 SIM-PERF 系列等价探针**原本是空转的** —— 它们用裸 `new World(...)`
    # 建世界，而裸 World 不带系统（`World.systems` 只由 `Simulation.reg()` 填充）⇒ "N tick" 期间世界一格没变，
    # 只测了"生成后一瞬"，且其中两条**没有失败退出码**（照原样接门禁就是假绿）。
    # 现已改为真 `Simulation` 驱动 + 补 `System.exit(1)`，实测 600 tick 真演化下 4 条全绿且确定性稳定。
    gate("SURFACECACHE", "SurfaceCheck")        # surfaceY 缓存 == live 逐列下扫（真演化 600 tick，276480 次比对）
    gate("SURFACECELLS", "SurfaceCellsCheck")   # surfaceCells 集 == 期望集（增量维护路径，真演化）
    gate("WATERSURF", "WaterSurfCheck")         # waterSurfaceCells 集 == 期望集（增量维护路径，真演化）
    gate("ICEFORMEQ", "IceFormEquivCheck")      # IceForm 桶排法 == 原 topWaterY 扫描法（逐 tick，真演化）
    gate("LIGHT", "core.sim.LightTest")                  # 立项 C：块光传播（衰减/遮挡/FIRE半径/确定性/零漂移）
    gate("AUTOTILE", "core.sim.AutotileTest")            # 泰拉瑞亚缺口①：连通性拼贴边掩码（确定性+不变量）
    gate("EDGE", "core.sim.EdgeTest")                    # 四路步骤4：跨材质交界过渡（默认关闭零影响+方向+确定性+与autotile正交）
    gate("EDGESHADER", "core.sim.EdgeShaderTest")        # 四路步骤4·路线A：world shader 叠加层契约（属性通道+采样+单行字面量）
    gate("BLOOM", "core.sim.BloomTest")                  # 泰拉瑞亚缺口②：选择性泛光（模糊/阈值纯函数+FBO尺寸常量）
    gate("ART", "core.sim.WeaponArtTest")                   # C3：多武器/多战技（原型差异 + 边界 + 零漂移不变量）
    gate("MENU", "core.sim.MenuTest")                       # C5：菜单/暂停/设置（状态机 + 钳制 + 暂停透明性）
    gate("DAYNIGHT", "core.sim.DayNightTest")               # D3：昼夜/天空模型（连续性 + 射线基 + 纯函数零漂移）
    gate("AUDIO", "core.sim.AudioTest")                     # C4：程序化音频（纯函数 + 包络 + 声部池 + 零漂移）
    gate("MATTER", "core.sim.MatterDeterminismTest")
    gate("MEGA", "core.sim.MegalithDeterminismTest")
    gate("MOD", "core.sim.ModTest")                # mod support: multi-source merge/override/priority/zip
    gate("SKILL", "core.sim.SkillTest")            # skill tree + perfect dodge/riposte
    gate("TECH", "core.sim.TechTest")              # tech graph: acyclic/gates/chain/idempotent/pacing
    gate("FX", "core.sim.FxTest")                   # particle model: spawn/det/gravity/drag/ramp/cap
    gate("SYSTEMREG", "core.sim.SystemRegistryTest")  # system registry: golden order hash + phase labels + toggles
    gate("GAMEPLAY", "core.sim.GameplayTest")  # gameplay layer: ECA rules + presets + modules
    gate("CONTENT", "core.sim.ContentTest")   # content platform: load/validate/ref/cycle/deterministic scheduling
    gate("ANIM", "core.sim.AnimTest")        # 自建动作系统：插值/缓动/循环/混合/事件帧/坐标变换/确定性
    gate("SPAWN", "core.sim.SpawnPickTest")      # QA 2026-09-12：开局选址器性质（确定性+范围+平坦+边界）
    gate("SNEAK", "core.sim.SneakTest")          # QA：潜行防坠边（拦住+不误伤+减速比）
    gate("REFUGE", "core.sim.BeastRefugeTest")   # QA：敌兵生成性质（GRACE+村庄庇护所+不叠身）
    gate("SAVE", "core.sim.SaveLoadTest")        # 立项 F：存读档黄金测试（0-tick + 300-tick 演化一致）     # C6：巨构神殿材化（确定性+可达性+小世界豁免）        # 批次 5：物质·能量域（确定性 + 指纹隔离 + 可达性）
    gate("INVENTORY", "core.sim.InventoryTest")  # E 批：背包守恒/耐久/光标交互/确定性快照
    gate("ANIMCTL", "core.sim.AnimControllerTest")  # C 批：战斗动作语义（取消窗/判定帧唯一性/连招/确定性）
    gate("COMBAT", "core.sim.CombatTest")           # C 批②：攻击状态机接线（判定帧驱动/伤害唯一性/确定性）
    gate("BEACON", "core.sim.BeaconTest")           # D 批：地标入罗盘（恰好一次记录/坐标确定性/零漂移）
    gate("CHARACTER", "core.sim.CharacterTest")  # F 批：捏脸（内容加载/菜单状态机/零漂移/存读档往返）
    gate("ANIMLAYERS", "core.sim.AnimLayersTest")  # C 批③：root-motion/混合树/上下半身遮罩（UE5 对标）
    gate("STREAMCHUNK", "core.sim.StreamChunkTest")  # M3：分帧流式等价性 + 索引惰性重建等价 + 邻域查询去 O(N²) 等价
    gate("PORTABLEMATH", "core.sim.PortableMathTest")  # N0：联机可移植性（core 禁非 StrictMath 超越函数）
    gate("NETCODEC", "core.sim.NetCodecTest")  # N1：联机输入编解码（协议稳定/无损往返/拒绝而非截断/惰性）
    gate("SNAPSTATE", "core.sim.SnapshotStateTest")  # N2-0：快照完备性分类（穷尽分类/桶互斥/防假绿/缺口基线/派生字段禁持久化）
    gate("NETSNAP", "core.sim.NetSnapTest")  # N2-1：紧致快照（字节预算/压缩比/稀疏性/结构等价/双一致）
    gate("ROLLBACK", "core.sim.RollbackTest")  # N2-2/N2-3：原地恢复 + 回滚黄金判据（跑N→回滚RB→修正跑到N == 一开始就修正跑到N）
    gate("NETDESYNC", "core.sim.NetDesyncTest")  # N3-0：desync 检测宽哈希（同源一致/宽于 hashState/敏感/惰性/成本预算）
    gate("NETLOCK", "core.sim.LockstepTest")  # N3-1：锁步会话协议层（回环收敛/输入延迟≠语义/缺帧不推进/补录被拒/desync 可检出）
    gate("UDPLOCK", "core.sim.UdpLockTest")  # N3-2：真实 UDP + 跨 JVM 进程（两端收敛/三端收敛/晚加入被拒/--host 与 --join 子进程 hash 一致）
    gate("PREDROLLBACK", "core.sim.PredRollbackTest")  # N4：预测回滚（延迟 D=4 终态==纯锁步 / 预测真错→真回滚 / 确定性重演 / 基线已缓存）
    gate("SKILLCAST", "core.sim.SkillCastTest")  # A 批：技能链（cast 三道闸 / targeting 命中·截断·负例 / buff DOT·减速·到期）
    gate("SUBSYS", "core.sim.SubsystemTest")    # 空转参数落地：7 个子系统参数各自有牙 + 出厂默认 no-op
    gate("RECIPE", "core.sim.RecipeTest")      # 冶炼产业链显式契约：ore+coal->iron_bar / 不足不扣料 / 未知键响亮
    gate("NETINTEG", "core.sim.NetIntegTest")  # N5：会话接入渲染主循环（TickBody 注入 + 跨实例收敛 + 会话==直接演化忠实 + 自定义推进体生效且确定性）
    gate("DISPCAP", "core.sim.DisperserCapTest")  # 批⑤：散布者家族全局上限（派生式上限 / 各类型写入点尊重上限 / 收敛性护栏）
    # ARTUI：自研艺术 UI 契约（恒等纹理严格纯白 / 九宫格 UV 几何 / 描边受光不对称 / 无越界污染 /
    #        FS 单字面量纪律 + 恒等语义(不预乘 vCol.a) + 默认强度 0）。无头零 GL。
    # 注意：放在 tools（而非 core.sim）—— 它要 import render.lwjgl.TextureAtlas，而 core 层
    #       先于 lwjgl 编译且 classpath 不含它，放 core 会编译失败（并从旧 out/ 假绿）。
    gate("ARTUI", "ArtUiCheck")
    # MESHSTRIDE：顶点写入步长契约（无头零 GL，源码静态解析）。三条顶点发射路径
    #   Game.putV / Chunk.crossV / Chunk.emitFaceE 的 put 链长度必须 == Chunk.VERT_FLOATS(16)。
    # 2026-09-21 事故：crossV 只写 13 float（漏 edge3）→ 每个 cross quad 少推 12 float →
    # FloatBuffer 写满后 put 静默丢弃 → 花丛/植被区块尾部 quad 消失（丢量 ∝ 花数），
    # 而当时 65 道门禁全绿。本门禁专防此形态复发。
    gate("MESHSTRIDE", "MeshStrideCheck")
    # MESHASYNC（第三十二批 A2）：网格异步构建的**输出等价**门禁（无 GL、headless）。
    # 为什么需要：A2 把 Chunk.buildMesh 搬到了 worker 线程池（本项目第一次让渲染层并发），
    #   而"优化"最阴的翻车方式是**悄悄改变输出**（少一个顶点、换三角形序、边界格少画一面）——
    #   画面看着差不多，门禁也看不见，只在某个角度偶发"缺一块地形"。
    #   所以本门禁把不变量钉成**逐字节比对**：同世界同块，"worker 产出" 必须 == "主线程直接 buildMesh"
    #   的三个 pass（不透明/半透明/发光）；顺带钉住出队序策略、在飞上限、epoch 丢弃+回滚重标脏、
    #   以及"全部消费后 in-flight 归零"（缓冲是非 GC 内存，漏释放不会崩、只会慢慢吃内存）。
    # ⚠️ 必须带 NATIVES：Chunk 的三条缓冲走 LWJGL 的 memAllocFloat（**direct 原生内存**），
    #   而 gate 的默认 classpath 只有 LIBS（不含原生 jar）⇒ 会 UnsatisfiedLinkError: lwjgl.dll，
    #   且症状是"worker 线程静默死掉、门禁全红在别处"（本批亲历）。
    #   对照：ARTUI/TILEART 走 bakeAlbedoOffscreen 的**堆缓冲**，所以它们零原生依赖、不需要 NATIVES。
    gate("MESHASYNC", "MeshAsyncCheck", ";".join(NATIVES))
    # MESHSHIFT（第三十八批）：窗口平移后**网格复用 == 全量重建**门禁（无 GL、headless）。
    # 为什么需要：本批把"每次平移把 CX*CZ 个块全部重建"改成"只重建新进入的条带 + 两条边缘线，
    #   其余块复用旧网格 + 一个整数顶点偏移（uChunkShift）"。这类"省掉工作"的优化最阴的翻车方式是
    #   **少重建了本该重建的块** —— 世界看着正常，只在某条边上"少一堵墙 / 墙停在上一次的位置"。
    #   判据是**派生**的：平移后逐块比较"旧网格+偏移"与"当前帧全量重建"（逐 float 位模式），
    #   这个集合必须**恰好等于** World.dirtyChunks（双向：不漏标 / 不过标）；
    #   再叠"终局整窗几何 == 全量重建"（跨多轮累积偏移的精确性）与"已有脏键随内容重映射"。
    gate("MESHSHIFT", "MeshShiftCheck", ";".join(NATIVES))
    # LIGHTINCREMENTAL（第三十九批）：窗口平移后**增量光照 == 全量重算光照**（无 GL、headless）。
    # 为什么需要：本批把"每次平移把整窗光场全量重算"改成"光场随 mat 旋转、只重算新进入的条带"，
    #   代价从 O(全窗扫描) 降到 O(新条带)。这类优化若带区边界算窄了，平移后只有条带边缘几格的
    #   光照会"差一点点"（肉眼难察、但破坏了"复用旧光场"的零漂移契约）。
    #   判据：平移后对比"增量光场"与"同内容全量重算光场"逐字节相等（多方向 / 多块 / 零重叠平移都覆盖）；
    #   并钉住"增量标志只在该走增量时置位"（重叠=true / 零重叠=false，零重叠退回全量）。
    gate("LIGHTINCREMENTAL", "LightIncrementalCheck")
    gate("SANDFALLEQ", "SandFallEquivCheck")   # 第五刀：落沙新实现(体素索引迭代) == 原实现(全窗 stride-2 扫描) 逐 tick 逐字节（densityFlow off+on）
    gate("CELLEQUIV", "CellIndexEquivCheck")   # 批⑧：零分配平铺快照 copyOf == 迭代器 cellsOfType（11 类型逐元素同序同值）
    gate("FLOWERVINEEQ", "FlowerVineEquivCheck")  # 批⑧：花/藤蔓新实现(平铺快照/线性归并) == 原实现 逐 tick 逐字节
    gate("SCANNERSEQ", "ScannersEquivCheck")   # 批⑨：biodiversity/snowcap/cactus 新实现(索引计数/直读 mat) == 原实现 逐 tick 逐字节
    gate("SWITCHSMOKE", "SwitchSmokeTest")     # 批⑬：休眠开关(MATERIAL_WORKS)打开不崩 + 自治路径真执行 + TreeSet<int[]> 正样本对照
    gate("SWITCHREACH", "SwitchReachTest")     # 批⑭：MATERIAL_WORKS 行为可达性（灾害四子路径 off=原样/on=生效/逻辑逐字同）——补 SMOKE 只证"不崩"之缺
    gate("MATLEDGER", "core.sim.MaterialLedgerTest")   # 批⑮：物质账本（变换账目表 + 端到端 Δmass 对账 + 索引↔mat 总账自洽）——揭发三处伪"质量守恒"注释
    # TILEART（P2，2026-09-23）：方块贴图的「形状语言」下限（无 GL、headless，只读烘焙像素）。
    # 为什么需要：ARTUI 守的是 UI 图集/HUD 通道，**完全不覆盖方块贴图的艺术质量**；而"噪点堆出来"
    #   与"有形状语言"在代码里看起来一样合理（都是几行 hash）。本门禁把"形与节奏"变成可测量量：
    #   ① 形：每个 tile 的块均值自相关 |acf1| ≥ 0.35（隔 1 个图案单位明度还相不相关）
    #   ② 微糙：二阶差分均值 ≤ 6.0 级（斜率无关，精确对应"不要电视雪花"）
    # 两条都有实测双边基线（纯噪声版 0.407/8.96 → 形状语言版 0.817/4.18）。
    # ⚠️ 这是"不许退回噪声"的下限，**不是"好看"的判决**；好看只能靠 AtlasDump 导贴图表目视。
    gate("TILEART", "TileArtCheck")
    # MSDF（P1，2026-09-23）：字形距离场的**语义**门禁（无 GL、headless）。
    # 为什么不能只靠 SHADERGLSL：它只证"着色器能编译"，完全不证"距离场编码对" ——
    #   一个符号写反的 MSDF 照样编译通过、照样出图，只是字全部反色/糊掉。
    # 本门禁直接测 距离场 → 重建字形 这条链路，5 条断言：形状合法 / 内外符号 /
    #   1texel 召回+精确率 / 三色分解（单色与两色分解必然 FAIL）/ ★放大优势（MSDF 必须显著优于位图放大）。
    # 若无可用 CJK 字体则打印 SKIP 并 exit 0（本机无字体不是实现错误）。
    gate("MSDF", "MsdfCheck")
    # UILAYOUT（P3，2026-09-23）：HUD 布局引擎的**语义**门禁（纯计算、无 GL、headless）。
    # 为什么不能只靠截图：截图只证"这一组分辨率下画面没变"，不证"锚点算得对"、
    #   也不证"小窗口不再越界"。本门禁 7 条断言：
    #   ① ANCHOR     引擎求解 == 迁移前手写原式（9 分辨率 × 11 面板；偶数宽逐位相等，
    #                奇数宽容 0.5px 以容纳原式的 int 截断语义）—— 这是"重构没改坏画面"的证明；
    #   ② IDEMPOTENT 正常分辨率下 fit == solve（钳制只在溢出时允许生效）；
    #   ③ OVERFLOW   旧式在极小窗口下**必须**量到越界（否则检测无牙，P2 门禁就这么废过一次）；
    #   ④ FIT        钳制后 88 项全在屏内，且 1×1 退化画布安全收缩不抛异常；
    #   ⑤ OVERLAP    面板两两重叠诊断 + 回归上限（存量 5 对，详见输出）；
    #   ⑤b MIGRATION 直接解析 Game.java：不许再出现裸坐标的 panel 调用（防漏迁移）；
    #   ⑥ STACK      栈自动累加 == 手写 y 步进，且首项改高后第二项自动跟进；
    #   ⑦ HIT        contains() 左闭右开语义（后续拖拽/点击的接口）。
    gate("UILAYOUT", "UiLayoutCheck")
    # SHADOW（P4，2026-09-23）：体素方向光阴影的**几何语义**门禁（纯计算、无 GL、headless）。
    # 阴影是"看起来差不多就行"的重灾区 —— 方向反了 / 步长漏检 / 半影公式写错的阴影，
    # 在截图里依然像阴影（有雾与内散射兜底时肉眼极难判伪）。所以只测可预测的几何：
    #   平坦无影 / 阴影落在背光侧 / 阴影长度≈h/tan(仰角) / 天顶光只在正下方 /
    #   仰角单调 / 半影随距离连续变浅 / 世界外不遮挡 / buildOcc 与 mat 逐格一致。
    # 另含 SHADOW-GLSL：解析 world FS 源码断言与 Java 侧常量/结构无漂移
    #   （编译通过完全证明不了这些，同 EDGESHADER 的纪律）。
    gate("SHADOW", "ShadowCheck")
    # VARIETY（2026-09-23）：方块级自发差异 —— "同材质的不同方块不该逐像素相同"。
    # 本特性只把顶点色乘 ±6%/±5%，幅度<b>刻意很小</b>（大了会盖过 AO 的四档过渡），
    # 于是肉眼几乎判断不出它是否生效 —— 这类"看起来没问题"的功能最典型的结局是
    # 调用被删/被短路而所有门禁依然全绿。故本题既验数学性质（确定性/幅度/均匀性/降相关），
    # 也验"相邻方块确实不同"，并**静态校验两条发射路径的方法体里真的调用了它**。
    gate("VARIETY", "VarietyCheck")
    # GLSL-R1：内联着色器离屏真编译（无副本漂移：直接解析 Game.java 的 initXxxShader）。需原生库 → extra 传 NATIVES。
    # 无 GL/无显卡时 ShaderCheck 自己打印 SKIP 并 exit 0（本机没显卡也能构建，只是这道理性跳过）。
    gate("SHADERGLSL", "ShaderCheck", ";".join(NATIVES))
    flush_gates()   # 批 D1：并行模式下把结果按序落盘（串行时为 no-op）
    # 批⑯：门禁耗时分档（慢的最前）。目的只有一个 —— 以后谈"门禁分层 / 并行化"时有数据，不靠猜。
    if timings:
        log("=== GATE TIMINGS (ms, slowest first) ===")
        for ms, label in sorted(timings, reverse=True)[:15]:
            log(f"  {label:<16}{ms:>7} ms")
        log(f"  {'TOTAL':<16}{sum(ms for ms, _ in timings):>7} ms  ({len(timings)} gates)")
    # 批 D1：编译段计时（冷/热差异大，是"端到端总时长"的隐藏大头 —— 此前从未被单独量过）
    log("=== COMPILE TIMINGS (ms) ===")
    for ms, label in compile_timings:
        log(f"  {label:<16}{ms:>7} ms")
    log(f"  {'TOTAL':<16}{sum(ms for ms, _ in compile_timings):>7} ms  ({len(compile_timings)} segments)")
    log(f"WALL_MS={int((time.time() - _t_wall0) * 1000)}  BW_JOBS={_JOBS}")
    log("=== END ===")

if __name__ == "__main__":
    main()
