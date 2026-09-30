# 252-breathing · Bug 检修计划

> **基准**：`main` @ `43aa305`（我 2026-09-30 推送的初始提交；= 报告审阅的源码快照）
> **方法**：**独立核验**，不转述。报告自己要求"不要因为我提出了就默认我的判断正确"——我逐条回源码对了。每条带 `文件:行号`。
> **来源**：朋友 GPT 的《Bug 审计报告 v1》（17 项缺陷 + 5 个 Codex 任务包）
> **日期**：2026-09-30

---

## 0. 先说三件事

1. **这份报告的对象是《会呼吸的世界》**（上一份 `6abcca64…` 是 321_FISH 的，已分清）。
2. **报告质量非常高。** 我独立核验了 17 项中的 15 项，**全部属实**，且其中至少 4 项比报告的描述**更严重或更精确**。这是一份可以信赖的审计。
3. **报告指出了我上一轮工作里的两处错误，我认。** 详见 §1.3 —— 其中一处**已经被我推到了公开仓库**，会影响你朋友的第一次构建。

---

## 1. 核验总表

### 1.1 逐条核验（B01–B17）

| 编号 | 报告结论 | 我的核验 | 证据（文件:行） |
|---|---|---|---|
| **B01** | 构建失败不能可靠传播为进程失败；超时/异常可能缺 EXIT | ✅ 属实 | `build_runner.py` **全文无 `sys.exit`**；`subprocess.run(..., timeout=180/300/120)` 三条超时路径，`rc=None` 时无对应 `*_EXIT` 行 |
| **B02** | `build_runner` 依赖开发机 ROOT；Unix 入口失配 | ✅ **属实，且 Unix 侧比报告说的还多一处** | `build_runner.py:3` 写死 `C:\Users\Administrator\...\java\breathing-world`；`audit_invariants.py:21-22` 默认同路径。**`build.sh` 编译段缺 `src/core/content/*.java` 与 `src/core/net/*.java`**（build.bat 有），**且 `LIBS` 缺 Gson/JNA**；`run-game.sh` classpath 同样缺 ⇒ **Linux 上必然编译失败** |
| **B03** | `build.bat` 只跑 17 道门禁且忽略测试错误 | ✅ **100% 属实** | `build.bat`：`echo BUILD OK.` 出现在 **17 个 java 测试之前**；每个 `java -cp ... XxxTest` 后**没有 `if errorlevel 1`**；末尾无条件 `echo ALL 17 GATES DONE.`。而 README §1 把它写成"一次性构建，输出 BUILD OK 即成功" |
| **B04** | 88 EXIT 不是可靠验收标准 | ✅ 属实 | 82 道 gate（已数）+ 5 编译 + 1 审计 = 88 EXIT；但 ① `SHADERGLSL` 无 GL 时 **SKIP 并 `exit 0`**；② 超时/启动异常可能**不产出 EXIT 行**；③ **`AUDIO_EXIT` 同时出现在门禁段与编译段**（同名两行） |
| **B05** | 保存直接覆盖正式存档，保存失败可毁掉旧档 | ✅ **属实，逐字确认** | `Game.quickSave`：`new FileOutputStream(new File(file, "world.sav"))` → `world.save(...)` → `close()`。**打开即截断**；无临时文件、无 rename 原子提交；catch 里只设 toast |
| **B06** | 流式卸载丢弃箱子等内容物状态 | ✅ **属实，且我拿到了完整数据流** | `World.java:3272-3277` 对 `meta/chestStore/furnaceStore/signText/blockState/builtCells` 调 `remapLocalKeys`；其定义（`3282-3294`）里 **`if (nx<0‖nz<0‖nx>=SX‖nz>=SZ) continue; // 移出窗口 ⇒ 丢弃`**；而窗口差分的持久化只比 `mat` 与 `mass`（`966`/`980-990`）⇒ **内部状态既不在内存里保留，也不落盘** |
| **B07** | `Player.atkAnim` 被排除在快照外，但它决定伤害 | ✅ **属实，而且是本项目最漂亮的一条** | `StateCodec.java:114` 在 SKIP 列表；`SnapshotStateTest.java:97` 的 `SKIP_DOC` 写着 **`"KNOWN GAP：AnimController 无无参构造，攻击状态机中间态暂不持久化"`** ⇒ **门禁把它当"有理由的排除"打印并 PASS** |
| **B08** | 字段数检查不能防旧档静默串位 | ✅ 属实（逻辑成立） | `StateCodec` 按字段名排序后**按位置**赋值；同数同类型时第二层类型异常保护**不触发** ⇒ `int a=11,int b=22` → `int b,int c` 会静默串位。**我 2026-09-30 加的 `COMPAT_FIELDCOUNT_MISMATCH_REJECTED` 只防空不了这个** |
| **B09** | 已发送的本地输入可被覆盖但不重发 | ✅ **属实，链条完整** | `LockstepSession.submitLocal:123-126` **无条件覆盖** `frames[tick][localIdx]`；`pump:137` `if (localSent.containsKey(t)) continue;` ⇒ 不再发；`Game.java:4391` 每帧提交 `nextTick` ⇒ 等待对端期间改键 ⇒ **本端 B / 对端 A** |
| **B10** | 迟到哈希、多对端哈希可能漏检 desync | ✅ 属实 | `exchangeHash:174` `long t = simTick`（**只比当前 tick**）；`182` `remoteHash.get(t)`；`handleMessage` 的 `MSG_HASH` 分支 `remoteHash.put((long) t, h)` —— **无 peer 身份**，3+ 人对同 tick 互相覆盖；且 `remote == null → return true`（**没比较却返回成功**） |
| **B11** | 单个输入 UDP 包丢失后无恢复 | ✅ 属实 | `src/core/net/` 下**没有 ack/重传/去重模块**；`pump()` 靠 `localSent` 只发一次 |
| **B12** | 预测历史与快照未裁剪，长局持续增长 | ✅ 属实 | `pruneBefore` **全仓只有一个定义**（`RollbackEngine.java:59`），**零调用方** |
| **B13** | 同块旧网格可能晚到覆盖新网格 | ✅ 属实（未做真机复现） | `MeshBuilder.Task:61/68` 只带**全局 `epoch`**，`key` 是块键**不是版本号**；`applyReady` 只判 `t.epoch != this.epoch` ⇒ 同一 epoch 内同块两个任务可反序完成 |
| **B14** | 关闭后仍可能发布无人消费的结果 | ✅ 属实 | `MeshBuilder.shutdown()`：`interrupt()` 后**不 join**，清空 `done` 后 **`inflight.set(0)` 强制置零** ⇒ worker 随后 `done.add` 就成了 `inflight=0, done=1` |
| **B15** | 加入异常路径未确定性关闭 channel | ✅ **属实，且是字面级证据** | `UdpTransport.java:92-95`：`finally { if (!ch.isOpen() ‖ ch.isConnected()) { /* no-op */ } }` —— **整个 finally 体是空操作**；`ch.close()` 在 `96` 行，**只有走到"超时"分支才执行** ⇒ 循环内抛别的异常 ⇒ **DatagramChannel 泄漏** |
| **B16** | 文档的门禁数量/入口/"完成"声明漂移 | ✅ 属实（我上轮也独立发现了） | `build_runner` 82 道/理论 88 EXIT；`OPEN_ITEMS.md` 头部仍写 62 EXIT；`orchestration/task_board.json` 门禁计数 52；README 对 `build.bat` 的描述与实测 17 项冲突 |
| **B17** | `fxRng` 被纳入宽哈希，表现随机可污染 desync 判断 | ✅ **属实，且有额外发现** | `World.java:399` `public final SeededRNG fxRng`（**非 static，带内部 state**）；`StateCodec.java` 的 SKIP 列表里**搜不到 `fxRng`** ⇒ 会被编码 ⇒ 进 `netHash`。**额外发现：`StateCodec` 自己的类注释自相矛盾** —— 第 32 行说"自动跳过 final 字段"，第 53 行说"其余非 SKIP 字段一律写出（含 final）"，第 140 行的 `writable()` 实现是"非 static 且不在 SKIP" ⇒ **第 32 行注释是错的** |

### 1.2 未独立核验的项（沿用报告，不作为我的结论）

- 报告附带的两份材料（`252-breathing_Bug_Audit_v1.md` 全文、`..._Bug_Audit_v1.zip` 里的 5 个任务包）在 `sandbox:/mnt/data/...` 路径下，**我拿不到**。
- 报告的自述边界：**未执行**原仓库全量门禁、真实游戏、Windows/JDK8、OpenGL、音频设备测试；本地反例用的是 **OpenJDK 21 编译到 Java 8 目标**，**不是 Java 8 运行时验证**。⇒ 所有"已确认"都是**源码控制流级**的确认，不是集成级。
- 我未复核：B13 的真实网格反序复现（报告也标注为"高概率，待真机"）。

### 1.3 🩸 报告指出了我上一轮的两处错误（我认）

| # | 我说过什么 | 实际情况 | 影响 |
|---|---|---|---|
| **1** | 我在《指挥官简报》里写：冷启动 `built=1484` 块而窗口只有 100 块 ⇒ "**强线索：平均每块被重复烘焙约 14 次**" | **报告是对的，我的推断不成立。** 合法编辑、系统演化、窗口移动、**过期结果**与重新构建都会增加累计值 ⇒ 不能由它推出"重复工作" | 我把"线索"写得像结论。正确的说法是"**无法由该数字推断，须按报告给的取证协议测**"（§3） |
| **2** | 我修订了 `README.md` / 新增 `CONTRIBUTING.md`，**并把它推到了 public 仓库**（为了让朋友能上手）。这两份文档都把 `build.bat` 当作"一次性构建，输出 `BUILD OK` 即成功" | **`build.bat` 只在 `BUILD OK` 之后跑 17 道门禁、完全不检查每个测试的退出码、最后无条件打印 `ALL 17 GATES DONE`** ⇒ **朋友的第一次构建会被误导成"全绿"** | 🔴 **这是我已经交付出去的缺陷。** 见 §2 P0-1 |
| **3** | 我把纪律写成"**SKIP 必须带理由 ⇒ 跳过永远是审查过的决定，不可能沉默发生**" | **只对了一半。** `atkAnim` 恰恰**有理由**（"KNOWN GAP：…暂不持久化"）—— 门禁只检查"有没有理由"，**不检查理由是否可接受** ⇒ 一条自认的缺口可以长期以"已解释"的姿态通过 | 纪律需要升级为：**SKIP 的每条理由必须能被判为「可接受」或「必须修」两类，`KNOWN GAP` 属于后者** |

> 补充一条：**`build.sh` 缺编译 `core/content` + `core/net`、缺 Gson/JNA** —— 报告只说了"依赖列表也没有 Gson/JNA"，我核对时发现**编译段本身就少了两条包路径**，Linux 上会直接编译失败。

---

## 2. 检修清单

> 统一格式：**症状 → 根因（文件:行）→ 修法 → 验收判据（不变量）→ 回归测试**
> 分级沿用报告口径，但**我按"能不能信任这套验证体系"重排了顺序**。

### 🔴 P0-1 · 让验证体系能可靠报错（B01 + B02 + B03 + B16）
> 报告的核心判断我完全同意：**在这件事做完之前，后面任何"全部通过"都不足以作为验收依据。**

- **症状**：构建/门禁的失败不能可靠变成进程失败；`build.bat` 会在测试前就喊 `BUILD OK`；门禁数量在不同文件里是 82 / 88 / 62 / 52 / 17 五个数。
- **根因**：
  - `build_runner.py` **无 `sys.exit`** ⇒ 正常处理的失败（缺工具、审计红、编译红的部分分支）只是 `return`；超时 `rc=None` 时不产出 EXIT 行
  - `build_runner.py:3` / `audit_invariants.py:21-22` **ROOT 写死为我这台机器的绝对路径**
  - `build.sh` 编译段缺 `src/core/content/*.java`、`src/core/net/*.java`，`LIBS` 缺 Gson/JNA；`run-game.sh` 同样
  - `build.bat` 17 道 + 不检查退出码 + 无条件 `ALL 17 GATES DONE`
  - `OPEN_ITEMS.md` / `task_board.json` 锚点滞后
- **修法**（报告已给方向，我同意）：
  1. `ROOT = os.path.dirname(os.path.abspath(__file__))`（**不要**改成 cwd）；`BW_ROOT` 显式覆盖时**校验仓库身份**
  2. 统一失败聚合：所有失败路径（缺工具/审计红/编译红/门禁红/超时/异常）⇒ **非 0 退出码**
  3. 门禁结果**结构化**：跳过（SKIP）与通过（PASS）必须是**不同状态**，且 `SKIP` 不得被上层当"通过"
  4. **消除 `AUDIO_EXIT` 同名两行**（门禁段 + 编译段）—— 同名会让"数行数"的验收永远算不清
  5. `build.bat`/`build.sh`：**只转发到修好的主构建器并透传 RC**，不再手工维护第二套 17 项清单
  6. 文档锚点改为**从 `build_report.txt` 生成**
- **验收判据**：
  - **故障注入**（报告原话）：编译红 / 审计红 / 测试红 / 超时 / 异常 / 并行任务失败 / 缺结果 / **"测试打印 FAIL 却 exit 0"** —— 每一种都必须能被**外部**准确判失败
  - **门禁数量不得减少**
  - 在**别人的 clone 路径**下 `python build_runner.py` 能正常跑（不能依赖我的绝对路径）
- ⚠️ **不要做**：不要因为"让失败传播"就把 `SKIP` 改成失败 —— 无 GL 的机器上 `SHADERGLSL` 必须仍能跳过；要修的是**语义分层**，不是把跳过变红。

### 🔴 P0-2 · 存档原子提交（B05）
- **症状**：保存过程中任何失败（磁盘满、断电、异常）都会**毁掉上一份正常存档**。
- **根因**：`Game.quickSave` **打开正式文件即截断**，没有"写临时文件 → 完整关闭 → 原子替换"的提交边界。
- **修法**：同目录写 `world.sav.tmp` → 完整 `flush` + `close` → 用平台可靠的替换策略覆盖正式文件；**任一步失败 ⇒ 旧档字节不变 + 不显示 `SAVED`**。
- **验收判据（不变量）**：
  > **保存失败后，旧档必须逐字节不变且仍可加载；且不得显示 `SAVED`。**
- **回归测试**：注入首字节 / 中段 / 末段写入失败、关闭失败、替换失败 —— **四种都要**。
- ⚠️ **不要做**：不能用"保存失败就删旧档重开"来处理。

### 🔴 P0-3 · 流式卸载保全功能方块内部状态（B06）
- **症状**：**箱子里的物品永久丢失。** 走几步让箱子离开窗口、再回来 —— 箱子还在（`mat` 是全局确定性函数），**里面的东西没了**。
- **根因（完整数据流）**：
  1. `chestStore` / `furnaceStore` / `signText` / `meta` / `blockState` 以**窗口本地坐标 `cellKey`** 为键
  2. `relocateWindow` → `World.java:3272-3277` 调 `remapLocalKeys`
  3. `remapLocalKeys`（`3282-3294`）：`if (nx<0 ‖ nz<0 ‖ nx>=SX ‖ nz>=SZ) continue;` ⇒ **移出窗口的条目直接丢弃**
  4. 窗口差分的落盘只比 `mat` + `mass`（`966` / `980-990`）⇒ **无恢复来源**
- **修法**：**按全局区块身份归档内部状态**（而不是保留旧本地 key——那会把状态挂到错误的新坐标），载入时再重映射；同时保证"只有物品内容变化、`blockId` 未变"也能正确持久化。
- **验收判据（不变量）**：
  > **绕行一圈回来，箱子内容、熔炉产出、铭文、朝向必须逐项相等；且不得复制、不得串位。**
- **回归测试（报告给的，我照抄）**：真实 `Simulation` 中给即将离开条带的箱子放 **3 个 `ore`**，记录全局位置，执行一次**有重叠的平移**再返回，比较箱体与内容；**还要覆盖"卸载后保存 → 重启 → 回到原地"**。
- ⚠️ 这条与 P0-4 都动持久化结构 ⇒ **必须串行**，且是 B08 的前置。

### 🔴 P0-4 · 攻击运行态进快照（B07）+ 补上 B08 的格式防线
- **症状**：玩家起手攻击 → 命中帧前存档/回滚 → 新世界 `atkAnim` 没恢复 ⇒ **伤害凭空消失**；回滚路径还可能保留"恢复前对象上的未来攻击状态"。
- **根因**：`StateCodec.java:114` 把 `Player.atkAnim` 排除；`SnapshotStateTest.java:97` 用 `"KNOWN GAP：…暂不持久化"` 作为**被接受的**理由。
- **修法**：
  - 保存**最小攻击运行态**（连段索引 / 播放时间 / 事件是否已发 / 取消-完成状态 / 恢复到 `null` 的语义），**不要把整套动画资产塞进快照**
  - **升级 SKIP 纪律**：`SKIP_DOC` 的每条理由要能被判为「可接受」或「必须修」；**`KNOWN GAP` 一律归后者**，门禁对后者**不得 PASS**
  - B08：不要一上来就写通用自描述格式；先加**显式 schema/版本身份 + 加载前拒绝**，迁移单独设计
- **验收判据**：
  > **各攻击阶段恢复后，伤害值与事件次数必须恰好一次、逐步一致。**
  报告点名：**"仅凭窄哈希相等，甚至当前宽哈希相等，都不足以证明它修好了"** ⇒ 必须断言**实际伤害与命中次数**。

### 🔴 P0-5 · 锁步输入：提交后不可变（B09）
- **症状**：两人联机，一方在等待对端时改了按键 ⇒ **本端执行 B / 对端执行 A**，直接 desync。
- **根因**：`LockstepSession.submitLocal:123-126` 无条件覆盖；`pump:137` 因 `localSent` 已存在而不再发送；`Game.java:4391` 每帧提交。
- **修法**：**已提交的 tick 输入不可变**；后续实时变化进**未来 tick**；同 tick 重复提交**幂等**；冲突**不得静默覆盖**。
- ⚠️ **不要做**：不能靠"忽略所有新按键"来修 —— 那会变成丢动作或卡键。
- **验收判据**：等待期间真实改键，也不产生同 tick 输入分叉。
- ⚠️ 现有 `deterministicIntent(pid,tick)` 每次返回同值，**恰好会掩盖这个问题** —— 测试夹具必须先改掉。

### 🟠 P1-1 · 哈希漏检（B10）
- **根因**：`exchangeHash` 只比 `simTick`；`remoteHash` 以 **tick 为唯一 key、无 peer 身份**；`remote == null → true`。
- **修法**：按 **session / peer / tick** 三元配对；保留**有界**本地检查点；"没有远端结果"必须是**第三种状态**（未判定），不能是"一致"。
- **验收判据**：迟到哈希能配上历史检查点；3+ 玩家同 tick 的不同远端哈希**不得互相覆盖**；任一端单独篡改必须被报出。

### 🟠 P1-2 · UDP 丢包恢复（B11）
- **根因**：输入只发一次，`Transport`/`Relay` 无补偿；缺帧不能推进 + `localSent` 阻止重发。
- **修法**：**有界确认 + 重传 + 去重 + 明确失联策略**。
- ⚠️ **不要做**：不能"缺帧时补 idle 强行推进"——那是把卡住换成**确定性损坏**。

### 🟠 P1-3 · 预测历史上界（B12）
- **根因**：`pruneBefore` 有实现（`RollbackEngine.java:59`）但**零调用方**；`reconcile()` 从 tick 1 开始扫描。
- **修法**：在正常推进与确认路径调用裁剪；明确**保留窗口**与**最大预测超前**。
- **验收**：N / 2N / 4N tick 下的**保留量**与**耗时**曲线有界。
- ⚠️ 报告提醒（我同意）：**不能**写成"你的游戏现在会 OOM" —— 当前 Game 循环走的是 `LockstepSession`，不是 `PredictiveSession`。

### 🟠 P1-4 · `fxRng` 的哈希边界（B17）
- **症状**：只要**一端多消费了表现随机**，就可能把本应一致的仿真判成 desync。
- **根因**：`World.java:399` `public final SeededRNG fxRng`（非 static、有 state）；不在 `StateCodec.SKIP` ⇒ 进 `netHash`。
- **修法**：明确区分「**需要保存的状态**」与「**需要网络一致性比较的状态**」——这两者不同。把 `fxRng` 从**宽哈希**里排除。
- ⚠️ **不要做**：**不要为了排除 `fxRng` 而顺手排除真实的 Player / 实体 / 事件状态**（那会把 B07 那类问题一起藏掉）。
- ⚠️ 诚实边界：报告与我**都没找到** `Game` 主文件里实际消费 `fxRng` 的路径 ⇒ **不能说真实每局必触发**。
- **附带修**：`StateCodec` 的类注释**自相矛盾**（第 32 行 vs 第 53 行）—— 按"注释不得说谎"纪律，以 `writable()` 实现为准，改掉错的那句。

### 🟡 P2-1 · 网格并发两个竞态（B13 + B14）
- **B13**：`MeshBuilder.Task` 只有全局 `epoch`，`key` 不是版本号 ⇒ 同块两个任务可**反序完成**，旧网格覆盖新网格。
  - 修法二选一：**单块在飞合并**（同块有在飞任务就合并需求）或**每块单调版本 + 拒收过期**。
  - 验收：用**真实 `Chunk.buildMesh` + 真实 MeshData** 强制制造同块反序完成。
- **B14**：`shutdown()` 不 `join` worker，清空 `done` 后 **`inflight.set(0)` 强制置零** ⇒ worker 之后仍可 `done.add`（实测隔离反例 `inflight=0, done=1`）。
  - 修法：等待 worker 退出；`inflight` **不得**被强制置零来"看起来干净"。
  - ⚠️ 报告提醒：**本次没测到真实 native 泄漏规模**，别夸大。
- **B15**：`UdpTransport.java:92-95` 的 `finally` 是**字面空操作**；`ch.close()` 只在超时分支 ⇒ 异常路径**泄漏 DatagramChannel**。
  - 修法：区分"成功转移所有权"与"失败清理"——失败必关，成功不得误关。

### 🟡 P2-2 · 冷启动性能（**只测量，不优化**）
见 §3，报告给的取证协议我整段照搬。

### 🔵 P3 · 文档一致性（B16）
把 `OPEN_ITEMS.md` / `task_board.json` / `README.md` 的锚点改为**从 `build_report.txt` 生成**；历史记录保留但与"当前验收状态"**分开**。
> 正确状态 = **固定 commit + 本次运行 ID + 单一门禁计划 + 实际执行结果**。没有运行证据就显示"**未验证**"，而不是沿用过去的"全部完成"。

---

## 3. 冷启动：报告的方法论修正（**我上轮说错了，这条以报告为准**）

报告明确否掉了我上轮的推断：

> **"累计 built 大于窗口块数，不能单独证明错误重复工作。"**
> 合法编辑、系统演化、窗口移动、**过期结果**与重新构建都会增加累计值。B13 的调度风险也不能直接当作 13.5 秒卡顿的根因。

报告给的取证协议（**先取证，后优化**）：

| 步骤 | 必须得到的证据 |
|---|---|
| 固定实验条件 | commit、JDK、驱动、种子、尺寸、内容、有无存档、**冷启动的定义** |
| 建立时间线 | 内容 → 地形 → 索引 → 光照 → **同步首轮网格** → 上传 → 首帧 → 可操作 → 稳定 |
| 记录网格身份 | 全局 chunk、**world epoch、content revision、dirty reason** |
| 记录任务生命周期 | 提交 / 开始 / 完成 / 应用 / 丢弃 / 失败 与各阶段耗时 |
| 消融实验 | 原版基线；诊断性冻结 sim；隔离流式；**区分 CPU 构建与 GL 上传** |
| 修复验收 | 多次冷/热样本、p50/p95/p99、**重复版本工作量**、稳定期不退化、指纹与几何等价 |

源码侧能确认的一条（报告已指出，我复核同意）：**`initBuffers()` 仍同步做首次全窗构建，之后才启动 MeshBuilder worker** ⇒ **首次启动成本**与**进入主循环后的反复重建**必须**分开测**。

> ⚠️ 报告第三条纪律：**诊断必须跑在真实 tick 演化过程中**，不能把一个系统在冻结 World 上连续调用再拿占比当热点。
> **当前状态：性能根因未确认；不建议现在加缓存、加线程或削减更新。**
> 我上轮确实按这个错误前提写了"疑似重复烘焙" —— 现在纠正。

---

## 4. 执行顺序

报告已给出 5 个 Codex 任务包（`T01`–`T05`），我同意其顺序与**串行约束**：

| 序 | 任务 | 目标 | 核心验收 |
|---|---|---|---|
| **T01** | **可信构建与门禁入口** | 修 ROOT、失败传播、SKIP 语义、旧入口漂移 | **每一种失败注入都能被外部准确判失败；不减门禁** |
| **T02** | **存档原子提交** | 保存失败不破坏上一份有效档 | 所有写入/替换故障后，**旧档字节不变且可加载** |
| **T03** | **流式卸载状态保全** | 箱内容、生产结果、铭文随全局方块持久化 | 往返 / 卸载后重启 / 重复加载 均不丢失、不复制、不串位 |
| **T04** | **攻击运行态快照** | 保存与回滚保持攻击判定 | 各攻击阶段恢复后，**伤害与事件恰好一次、逐步一致** |
| **T05** | **锁步输入提交后不可变** | 已发送输入 == 本地执行输入 | 等待期间真实改键也不造成同 tick 分叉 |

**约束（报告明确、我同意）**：
- 🔴 **不建议 5 个任务同时改。** 两人项目建议同时最多维持 **1 个核心编码任务 + 1 个设计/验证任务**。
- 🔴 **T03、T04 都涉及持久化与状态结构，必须串行**；一旦涉及格式变化，**B08 的版本与兼容策略立即成为前置约束**。
- 🔴 **T01 必须是第一张实际派发票** —— 在构建体系还不能可靠区分**失败 / 跳过 / 完成**之前，后续任何"全部通过"都不足以作为最终验收依据。
- **T01–T05 之后**：B10 哈希漏检 → B17 fxRng 边界 → B11 丢包恢复 → B12 历史上界；网格并发与冷启动测量随后推进，**但不能用性能调优掩盖数据与确定性问题**。

**每个任务返回时必须带**：修改文件、关键 diff、根因、新增测试、**修复前失败与修复后通过的原始输出**、完整门禁状态、**真实 sim/net 指纹**、性能数据或明确 N/A、环境与 commit、仍未解决的问题。

---

## 5. 需要你定的三件事

1. **`build.bat` 怎么处理**：**转发**到修好的主构建器（推荐，报告也建议），还是**升级**成 82 道？我倾向前者——不要再维护第二套清单。
2. **存档格式演进策略**：先做"**schema 版本 + 加载前拒绝**"（最小、可立即落地），还是直接上"**自描述格式**"（彻底但工作量大）？报告建议前者。
3. **联机的目标形态**：`lowestIdSink()` 目前只把最低 ID 的输入应用到**单个 `World.player`** ⇒ **"N0–N5 完成"不等于多角色体验可用**。这轮要不要**明确能力边界并写进文档**（报告建议：**不在本轮借机开发多人玩法**）？

---

## 附 A · 核验方法（可复现）

```bash
cd java/breathing-world

# 1) 构建入口的真实行为（不要信 README）
cat build.bat                     # BUILD OK 在测试之前；17 个 java 调用后无 if errorlevel
grep -n "ROOT" build_runner.py    # 第 3 行：开发机绝对路径
grep -n "BW_ROOT" audit_invariants.py
diff <(grep -o 'src/core/[a-z]*/\*\.java' build.bat | sort -u) \
     <(grep -o 'src/core/[a-z]*/\*\.java' build.sh  | sort -u)   # build.sh 少 content / net

# 2) 存档原子性
grep -n "new FileOutputStream" src/render/lwjgl/Game.java        # 直接开正式文件

# 3) 流式卸载丢状态
sed -n '3272,3294p' src/core/world/World.java                    # remapLocalKeys 的 continue 丢弃

# 4) SKIP 的理由是否成立
grep -n "atkAnim" src/core/net/StateCodec.java src/core/sim/SnapshotStateTest.java

# 5) 宽哈希边界
grep -n "fxRng" src/core/world/World.java                        # 399: public final（非 static）
grep -c "fxRng" src/core/net/StateCodec.java                     # 0 ⇒ 未 SKIP ⇒ 进 netHash

# 6) 锁步输入不可变性
sed -n '123,145p' src/core/net/LockstepSession.java              # 无条件覆盖 + localSent 跳过
sed -n '173,215p' src/core/net/LockstepSession.java              # 只比 simTick；remoteHash 以 tick 为 key
grep -rn "pruneBefore" src/                                      # 只有定义，零调用方
```

## 附 B · 全部证据行号索引

| 编号 | 位置 |
|---|---|
| B01 | `build_runner.py`（无 `sys.exit`）、`subprocess.run(timeout=180/300/120)` |
| B02 | `build_runner.py:3`、`audit_invariants.py:21-22`、`build.sh`（编译段 + `LIBS`）、`run-game.sh` |
| B03 | `build.bat`（`echo BUILD OK.` 位置 / 17 个 `java` 调用 / 末尾 `ALL 17 GATES DONE.`） |
| B04 | `build_runner.py` 82 个 `gate(...)`；`build_report.txt` 88 行 `*_EXIT`；`AUDIO_EXIT` 同名两行；`SHADERGLSL` SKIP→exit 0 |
| B05 | `Game.java` → `quickSave()` |
| B06 | `World.java:3272-3277`、`3282-3294`、`966`、`980-990` |
| B07 | `StateCodec.java:114`、`SnapshotStateTest.java:97` |
| B08 | `StateCodec.java:140`（`writable()`：非 static + 不在 SKIP，按名排序）、对象段按位置赋值 |
| B09 | `LockstepSession.java:123-126`、`137-139`、`Game.java:4391` |
| B10 | `LockstepSession.java:174`、`182`、`212`（`MSG_HASH` 分支） |
| B11 | `src/core/net/`（无 ack/重传模块）、`LockstepSession.pump()` |
| B12 | `RollbackEngine.java:59`（唯一定义，零调用） |
| B13 | `MeshBuilder.java:61/68/105/130/147`（全局 epoch） |
| B14 | `MeshBuilder.shutdown()` |
| B15 | `UdpTransport.java:92-95`（空 finally）、`96`（`ch.close()`） |
| B16 | `OPEN_ITEMS.md` 头部、`orchestration/task_board.json`、`README.md`、`build.bat` |
| B17 | `World.java:399`、`StateCodec.java` SKIP 列表（无 `fxRng`）、`StateCodec.java:32` vs `53` |
