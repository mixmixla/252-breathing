# 待办总览 · OPEN_ITEMS（唯一入口）

> **这是全项目唯一的"还剩什么要做"总览。** 你只需要开这一个文件。
> 状态锚点（**2026-09-21 实测**）：**62 道门禁出口全绿**（`build_runner.py` 实测 **62 处编译/运行退出码全 0**、断言 0 FAIL；审计 `FAIL 0 / WARN 0`，25 项）—— 仿真指纹 **DETERMINISM `6debe1bdac051472` / ZERO-DRIFT `707ea72a82e0c797` / STREAMING `87d5bf1cecb4c628` / NPC `aad7f2c7caf412aa`** 逐字节不变；`SHADERCHECK PASS (13 programs)`。
> **本轮（2026-09-21）新增**：按 `docs/FOUR_SOURCE_PICK.md` §7 落地前三步 —— ① Noita 时间累积泛光（`Bloom.java` + `BLOOM_GLOW_FS`，13 programs）；② Terraria 分帧切片泛化（`FrameSlicer`）；③ **Terraria 两扫光照**（`World.computeLight` 由逐源 BFS 重写为固定两轮扫描；`MaterialDef` 加 `lightDecay` 字段；`LIGHT_R` 14→64；A/B 像素差 27.6%）。全部零漂移。**并修好 `-Dbw.snap` 无头截图不可复现的既有缺陷**（根因：QA 下鼠标回调仍吃真实 OS 事件 → 视角漂移；现已冻结全部真实输入回调与真实 dt 演出量）。
> **状态锚点已过期提示**：旧值（`52 道门禁 / 57 处 *_EXIT`、系统数 98）为 2026-09-17 记录；此后经「材料反应表（批 C，94 系统）」「四路源码调研落地（步骤 1–3）」两批**有意演进**，门禁数增至 62，仿真指纹在每次演进后均由 `build_runner` 重算并保持**逐字节自洽**（`ZERO-DRIFT` 恒为核心判据）。历史值保留在 `orchestration/event_log.md`。
> **C1 中文上屏已落**（`CjkFont` 运行期位图字体 + 修复 ASCII 字体从不渲染的两处 bug）。
> **C3 多武器·多战技已落**（`Weapons` 原型表：突刺/回旋斩/远射三形态，随武器解锁、G 键切换）。
> **C5 菜单/暂停/设置已落**（`MenuModel` 纯逻辑状态机 + `World.paused` 丢 tick 语义；ESC 开菜单、暂停透明性由第 13 道门禁实证）。
> **D3 昼夜/天气视觉已落**（`DayCycle` 纯模型：天色/日照方向/环境光/光色/雾色随 tick 推移，夜晚有星空与月亮；HUD 顶部新增时钟；顺带修掉"天空贴在屏幕上不随视角动"的旧缺陷）。
> **MATTER 抽象字段已落**（批次5 收口）：`core/world/Matter`（物相/熵与有效能/聚变辐射/深海，15 开放标量，不进 `hashState`）+ `MatterSystem`（注册末位，`MATERIAL_WORKS` 默认关、**不消费任何 RNG**）+ HUD `MATTER`/`DEEPSEA` 两行；第 18 道门禁断言可达性 + 指纹隔离 + 零 RNG。`link`/`portal` 判定为「无世界状态的粘合/配置层」，显式排除。
> **C4 音频已落**（`Sfx` 23 音效参数表 + `AudioSynth` 纯函数合成 + `AudioMixer` 声部池/抢占/老化 + `javax.sound` 输出；**零新增依赖**——程序化合成不背资产，与 C1 用 `java.awt` 代替字体文件同思路；菜单 SETTINGS 新增 VOLUME/SFX）。
> 维护约定：**每次有任务完成或新增，必须同步更新本文件**（它是人的入口；`orchestration/task_board.json` 是机器的入口，两者必须一致）。

---

## 0. 一句话现状

- **已闭环**：引擎地基（确定性/流式/物理/渲染）、战斗（4 敌兵 + 动作演出 + **3 种差异化战技**）、养成（属性/装备/魂锻）、塞尔达能力环（3 祭坛）、塞尔达循环后半段（试炼/遗物/世界之心）、中文上屏（CJK 位图字体）、**菜单/暂停/设置层**、**昼夜/天气视觉（天色+日照+星空+雾）**、美术二期（方向光/描边/粒子/AO/天空雾/风动/接触阴影）、**NPC 社会层（地基 + 批次0 接入 + 批次1 社会核心 + 批次2 文明深度 + 批次3 个体成长 + 批次4 民政经济与灾害 + 批次5 抽象字段：物相/熵/聚变/深海）**、**美术三期（ART-PROG-3：半球环境光 / 每体素颗粒 / 花田五色盘 / 调色+暗角）**。
- **还欠**：**① 本机验收 1 批**（👤 你的活）——这是**唯一**能解锁观感/手感/听觉悬案的动作，且**零风险**（渲染/输入/音频层改动都不污染仿真确定性，可放心调参）。**② agent 端已无「移植/功能/技术债」待办**：主移植清单批次 0–5 全部 DONE；C 类与非移植功能全部收口（C1–C5 + 内容平台 P0–P4 + A–G 七批）；D 类 D2/D3/D4 已清，D1/D5 用户已暂缓。
- ✅ **M3「大世界规模流畅」已落地（2026-09-14）—— 技术欠账清零**：① 跨图流式**分帧**（`stepShift` 新增 `INDEX` 阶段，索引重建按预算切片推进）+ 索引表示 `TreeSet<int[]>`→**升序平铺 `int[]`**（去掉实测 301ms 的比较器+红黑树税）；② 实体邻域查询 `O(N²)`→**均匀网格 3×3**。**单帧最坏 508ms → 11.5ms（44x）**、COMMIT 帧 677ms → 2.67ms，四基线指纹逐字节不变（详见 §1 第 5 行 / §5 `M3-STREAM-SCALE` / §23）。
- ✅ **联机 N2-0 快照完备性（2026-09-14）**：新增 `core/net/StateCodec`（**反射式**状态编解码器，加字段自动覆盖、不依赖 JVM 声明顺序、原地恢复）+ ext **v5** 格式（World 63 + Player 38 字段 + 92 系统实例状态）。**双一致达成**（读档后再演化 120 tick hash 仍逐字节相同，`SAVEL-GATE doubleConsistency=true` 永久守住）。**架构级发现：状态不只在 World 上** —— `WindSystem` 持有 `private int t/gustX/gustZ`，读档后 `t` 归零 → 风向立刻不同；**这类「系统实例跨 tick 状态」必须一并快照**。第 39 道门禁 `SNAPSTATE` 改为**与 codec 同源**：`PERSISTED` 由 codec 算出，门禁只维护「跳过字段 + 理由」表，**跳过不可能沉默发生**。四基线指纹逐字节未变。
- 🔧 **立项 F 存读档修复（2026-09-14，联机 N2 前置）**：实测发现并修掉**两个真实 bug**——① **读档必崩**：`TrialSystem` 只在 `tick == 1` 布点，而 `World.load` 不跑 tick → ext 段读 `sites.get(i)` 越界（即"只要试炼点已布点，读档必崩"= 真实世界的每一次存读档）；② **读档后世界冻结**：`Simulation(World)`（读档装配路径）**漏注册 92 个系统** → NPC/文明/试炼全不演化。**两者此前都被门禁掩盖**（旧 `SaveLoadTest` 用裸 `World`，系统数为 0），已补真实路径回归护栏。**遗留（已归入联机 N2-0）**：实体层（npcs/shrines/beasts/chronicle）未持久化、快照 **22.3 MB**、读档后继续演化 hash 分叉。
- ✅ **空转参数与死内容键清零（2026-09-17）**：7 个「空转子系统参数」（`erosionRate`/`ascensionThreshold`/`haulRate`/`hungerRate`/`orbItemCost`/`hpThreshold`/`wireRange`）全部有了真实消费系统（新增 `Ascension`/`Hunger`/`Wire`/`Haul`/`Capture` 五个系统，**93 → 98**）+ 3 个配方键（`coal`/`ore`/`iron_bar`）经 `RecipeBook` **显式契约**落地 → 审计 `CONTENT_KEY_EXEMPT` **清空**。出厂默认逐字节 no-op → **四道仿真指纹一格未动**。顺带修掉两个真问题：「粒子系统整体不可用」（`ParticleSim.offset` 越界 → `FX` 门禁长期红灯）与「电路电源不能用火」（火被 FireSpread 每 tick 50% 概率熄灭）。引擎调参与美术续批见 §37。
- **📋 可选内容缺口（有权威记录，非遗漏）**：多阶段 Boss（现有 3 种 beast 均杂兵级）、招架、处决演出、**~~小地图~~（✅ 2026-09-17 已落，见 §37 ⑩）**、自适应音乐分层、跨局 meta、**~~合成台~~（✅ 2026-09-17 I 键手搓，见本轮 ⑪）**、箱子 —— 均属「内容扩张」范畴，**加它们不需要改引擎**（M1 已过），故不列为欠账，按需排期。详见 `docs/VISION_CHECK.md` §6 与 `docs/MC_ADOPTION_ROADMAP.md` §1/§2。

## 图例

| 标记 | 含义 |
|---|---|
| ✅ | 已完成（见 §5 归档） |
| ◑ | 进行中 / 部分完成 |
| ⏳ | 待办（未开工） |
| 🔵 | 可选（收益低或用户已决定暂缓） |
| 👤 | **需要你（用户）本机操作** |

---

## 1. 现在该做什么（优先级队列，从高到低）

| 序 | 事项 | 类别 | 归属 | 备注 |
|---|---|---|---|---|
| 1 | **本机跑 `run-game.bat` 验收**（**顶部昼夜时钟 DAY/nn:nn/时段/天气** + 天色昼夜推移 + 夜里星空与月亮 + 晨昏暖色晨雾 + 美术二期 + 村民 + 交谈**中文气泡** + **B 键村志浮层** + **G 键切换战技** + **ESC 菜单/暂停/设置** + `CIV`/`LIFE`/`POLITY`/`CALAMITY`/`TRIALS`/`OBJ` HUD + 试炼点/补给箱/世界之心 + **音效（C4，用耳朵听）**） | A 验收 | 👤 你 | 唯一零风险、解锁全部观感悬案的动作 |
| ~~2~~ | ~~批次5 抽象字段（phases / entropy / fusion / link\|portal / deepsea）~~ | B 移植 | systems-designer | ✅ **DONE（2026-09-11，见 §5 `B5-MATTER-FIELDS`）**：phases→物相·熵→熵与有效能·fusion→聚变辐射·deepsea→深海基地 已落为开放标量层；`link`/`portal`（无世界状态的粘合/配置层）**显式排除**；第 18 道门禁 |
| 3 | ~~音频~~（**✅ 已完成**，见 §5 `C4-AUDIO`：JDK `javax.sound` + 程序化合成，**零新增依赖**） | C 功能 | — | **C 类非移植功能全部收口** |
| 4 | 技术债（系统瘦身/~~村志去重~~/美术定稿/极端资源价格校准） | D | — | ✅ 村志去重 **D2 已清**；D4 已结；D1/D5 用户已暂缓（可选，非欠账） |
| 5 | ~~**M3 · 大世界规模流畅**（① 跨图流式分帧 ② 实体查询去 `O(N²)`）~~ | 引擎 | agent | ✅ **DONE（2026-09-14，见 §5 `M3-STREAM-SCALE`）**：① 索引表示由 `TreeSet<int[]>` 换**升序平铺 `int[]`**（去 301ms 比较器税）+ 增量补丁缓冲 + `rebuildIndex` 三趟合一 + **INDEX 阶段分帧**；② `SocialSystem` 邻域 O(N²)→均匀网格 3×3。**单帧最坏 508ms → 11.5ms（44x）**，四基线指纹逐字节不变 |
| 6 | 内容扩张（多阶段 Boss / 招架 / 处决 / 小地图 / 合成台 / 箱子 / 跨局 meta） | 内容 | — | 📋 按需排期，**加它们零改引擎**（M1 已过）；清单见 `VISION_CHECK.md` §6 + `MC_ADOPTION_ROADMAP.md` §1/§2 |
| 7 | ~~**联机服务**（N0 ✅ / N0b ✅ / N1 ✅ / N2 ✅ 快照·回滚收口 / N3 ✅ 真实 UDP 锁步）~~ | 引擎 | agent | ✅ **N3 已收口（2026-09-14）**，方案 `docs/NETPLAY_READINESS.md` §5.9。用户已定：**一开始就上预测回滚**。N0 可移植硬化 + N0b 跨 JVM 实证 + N1 输入编解码（6B）；**N2-0** 反射式 `StateCodec` 覆盖 World 58 + Player 38 字段 + **92 个系统实例的私有状态**（跨进程双一致）；**N2-1** 窗口编辑改「与 seed 基线稀疏差分」→ **22.25 MB → 317 KB（70x）**；**N2-2** `restoreInPlace` 原地恢复；**N2-3** 回滚黄金判据通过；**N3-0** `netHash` 宽哈希 desync 检测 + **N3-1** 锁步协议层（回环）+ **N3-2** 真实 UDP 传输 + 三启动形态（`--host`/`--dedicated`/`--join`）—— **跨 JVM 子进程 hash 逐字节一致 `d978824b368f0c71`**。顺带修掉 3 个真实 bug + 3 个「不会让门禁变红」的坑（派生字段膨胀 / SKIP 全局重名 / 幽灵条目）。✅ **N4 已收口（2026-09-14）**：基线缓存消除每 tick 204ms 全窗重生成（`ensureBaseline` 懒生成 + `baseMat/baseMass` 引用复用）+ `RollbackEngine`（应用前快照环形缓冲）+ `PredictiveSession`（GGPO 式客户端预测：本地即时 / 远端延迟到达时重复上次确认意图 / 权威帧不符则原地回滚重演）+ 第 45 道门禁 `PREDROLLBACK`（延迟 D=4 终态==纯锁步 / 预测真错→真回滚 / 确定性重演 / 基线已缓存）。✅ **N5 已收口（2026-09-14）**：把 `--host`/`--join` 联机会话接入 `render/lwjgl/Game` 主循环——抽象 `TickBody` 整 tick 推进体（含 rules/techs/effects 内容层），会话驱动完整整 tick（跨实例收敛 + 终态==单机 `world.tick` 演化 + TickBody 注入可验证），本地移动改按 tick 喂 `Intent.move` 给会话（确定性、零帧 dt），单机分支逐字不变；第 46 道门禁 `NETINTEG`（CROSS_INSTANCE / WRAPPER_FAITHFUL / TICKBODY_INJECTED 三断言） |
| 8 | ~~**空转参数落地 + 死内容键清零 + 引擎调参 + 美术续批**（7 个子系统参数 / 3 个配方键 / 帧预算 / 雨幕）~~ | 玩法+引擎+美术 | agent | ✅ **DONE（2026-09-17，见 §37）**：新增 5 系统（93→98）+ `RecipeBook` + 两道门禁 `SUBSYS`/`RECIPE`；`CONTENT_KEY_EXEMPT` 清空；**四道仿真指纹逐字节未变（无需重锁基线）**，仅 `SYSTEMREG` 黄金序列**有意重锁**；引擎实测 tick p95 28ms → `MAX_STEPS_PER_FRAME` 5→3 + 单帧仿真时间盒 + 积压钳制（审计 `C12` 锁值） |

---

## 2. A 类 · 本机验收（👤 你的活）

> 对照 `VALIDATION_CHECKLIST.md` §3 逐项打勾。重点新特性：

- [ ] **昼夜 / 天气视觉（D3，新）**：屏幕**顶部居中**新增时钟条 `DAY <n>  HH:MM  <时段>  CLEAR/RAIN` + 一条 24h 游标（左=00:00、中=12:00、右=24:00，白天金色、夜间银蓝）。
  - **一天约 25.6 秒**（512 tick @20tick/s），盯 1 分钟应能看到天色从正午亮蓝 → 黄昏橙红 → 夜里深蓝的完整推移；**夜里能看到星空与一轮小月亮**，日出前有暖色晨雾（雾更浓）。
  - **关键检查（本次修掉的旧缺陷）**：**抬头/低头时天空要跟着变**（抬头看天顶更深、低头看地平线更亮），而不是像贴纸一样固定在屏幕上。
  - 走到空地对准光源方向，能看到一轮**太阳/月亮盘**（世界方向锚定：转动视角时它会**滑出视野**，而不是跟着屏幕走）。
  - 下雨时天色**整体压暗偏冷**、雾变浓；停雨后**约 1 秒内平滑恢复**（不是硬切）。
  - 也可直接看 `Aurora`/`chron:` 日志：`aurora` 事件现在**只在夜里**出现（与天色同源）。
- [ ] **美术二期**：方块角落变暗（顶点 AO）／天空渐变 + 远景距离雾／树叶野花随风摆／玩家与敌兵脚下接触阴影。
- [ ] **美术三期（ART-PROG-3，新）**：① 野花**不再是一整片洋红**，而是 玫瑰/金盏/白/紫/珊瑚 五色碎花（远看花海、近看单朵）；② 背光面 / 雨夜**不再压死成黑**，山体与地形起伏仍可辨（半球环境光）；③ 草地 / 石壁有**细微材质颗粒**（每格微明度差）；④ 四周有**极轻暗角**、中心更聚焦；⑤ 整体更暖更通透（饱和 / 对比各 +6%）。
- [ ] **巨构（C6，新）**：① 抬头看地平线有**远景巨构剪影**（断拱独塔/双柱门，120–220 格高，雾中浮现）；② 向出生点 ~200 格内的**神殿/断拱门**走（真方块）：门洞/拱下**能走进去**，TOWER 中庭有**祭坛长明灯**（夜里可见导航光）；③ 条石/金饰与地形**材质区分**明显、人站在旁边**显小**；④ 挖神殿方块 → 走远再回来，**编辑保留**（chunkEdits 持久化）。
- [ ] **村民**：出生点附近有 **5 个按职业着色的人形**在走动；头顶有**情绪色标**（绿/黄/白/红/粉/暗红）。
- [ ] **物相 / 深海摘要（批次5，新）**：村志面板下另有两行 ASCII —— `MATTER PH … TR … ALLOY … EXE … ENT …`（物相/跃迁/合金/有效能/熵）与 `DEEPSEA SUB … PRES … MIN … POLL … COL … FUS … RAD …`（潜艇/压力/矿物/污染/殖民地/聚变/辐射）。长时观察 `TR`/`MIN`/`COL` 应缓慢增长且 `PH` 在 SOLID/LIQ/GAS 间切换（火多则偏 GAS），而**不是死值**。
- [ ] **交谈**：走近（4 格）出现 `T: TALK <职业> <性格> <情绪>`，按 **T** 有反馈；开 `dialogue.log` 有**中文回话**且措辞随情绪/时间变化。
- [ ] **村志（批次1 余项，新）**：左上常驻 `CHRONICLE D… REPEL n MARRY n BIRTH n` + `LAST: …`；击杀敌兵后 `REPEL` 计数增长（**这是"世界因你而变"的可见证据**）；开 `chronicle.log` 有**中文村志全文**（含"旅人已 N 次击退来犯之敌…"），且随游戏推进换版；**D2 起档案按“骨架”长程去重**（同一句只留一条，故不再被同义句刷屏，而计数仍随每次发生增长）。
- [ ] **文明 / 个体摘要（批次2/3，新）**：村志下另有两行 ASCII —— `CIV RES…TECH…TEMPLE…ROADS…REL…REGIME…`（文明）与 `LIFE KNOW…MUT…EVO…SICK…HERO…DREAM…`（个体）；随游戏推进，数字缓慢增长（研究累积、知识增长、进化代次上升、偶发疫病/英雄）。
- [ ] **民政 / 灾害摘要（批次4，新）**：再下两行 ASCII —— `POLITY LV<n> POP…FAM…COIN…INFL…CARAVAN…CRIME…JUDGE…`（城镇等级/货币/通胀/商队/犯罪/仲裁）与 `CALAMITY <灾害>…DRY…RAIN…FIRE…FLOOD…DROUGHT…QUAKE…EVAC…`（当前灾害/旱情/各灾种计数）。长时游玩可看到**犯罪后村中既有包庇也有惩戒**（`CRIME`/`JUDGE` 增长）、连雨触发洪涝、久旱触发干旱、周期地震。
- [ ] **中文上屏（C1，新）**：按 **T** 交谈 → 屏幕下方出现**中文回话气泡**（不再是空白/方框）；按 **B** → 右侧弹出**中文村志浮层**（说书人「当日村志」全文，标题 `CHRONICLE D<n>`）。若显示 `CJK UNAVAILABLE`，看 `render_diag.log` 里的 `CJK font:` 行。
- [ ] **多武器战技（C3，新）**：右上新增 `ART <形态>` 行 + `G: SWITCH ART x<n>`（已解锁形态数）。升级途中拿到**战斧/猎弓/符文刃**时，会弹 `NEW ART: CLEAVE` 之类横幅 + 中文气泡说明该战技怎么打；按 **G** 在已解锁形态间轮换；按 **R** 施放时三种形态演出不同 —— **突刺**=朝面向的蓝色斩痕；**回旋斩**=脚下扩散的橙色环（**一次打中身边多只并击退**）；**远射**=射向远处敌兵的绿色弹道（**隔 10 多格也能打**，超出射程/无目标时提示 `NO TARGET IN RANGE` 且**不耗体力**）。
- [ ] **菜单 / 暂停 / 设置（C5，新）**：按 **ESC** 弹出居中菜单（压暗背景 + 标题 `PAUSED` + 中文副标题「游戏已暂停」），此时**世界完全静止**（村民/敌兵/云影不动）、鼠标指针**放开可见**；**W/S** 上下选、**ENTER** 确认、**ESC** 逐层返回。
  - `SETTINGS` 里 **A/D** 调值（FOV / 灵敏度 / 视距即时生效，HUD 可 ON/OFF）——**改完回游戏应看到视野/灵敏度立刻变了**。
  - `CONTROLS` 列出全部按键；`QUIT` 需二次确认（**默认光标在 CANCEL**，防误退）。
  - **恢复后世界应从暂停那一刻接续**（不出现"暂停期间村民瞬移/补跑一大堆"）。
- [ ] **结亲添丁**：长时观察村民人数变多、出现更小的后代人形。
- [ ] **"四周很小"**：走几步后视野是否拓开（无限流式）——这是 EVAL-1 P1-D 唯一未验事项。
- [ ] 帧率体感 / 挖放 / 碰撞穿墙 / 颜色光照。

- [ ] **音效（C4，新）**：能听到声音吗？（**这项用耳朵，不用眼**；`render_diag.log` 的 `audio:` 行会报设备状态 —— `ON 22050Hz mono s16` 已开线，`UNAVAILABLE (...)` 表示本机无可用音频设备，此时游戏**照常运行、只是全程静音**。）
  - **挖/放**各有短促的碎土/落块声；**挥空**是风声、**命中**才是打击声（明显不同）；**击杀**另有明亮铃声；**升级**是三音上行琶音。
  - **三形态战技声音不同**（突刺上扬扫频／回旋斩低沉横扫／远射短促弹出）；**远射无目标**是"被拒"提示音而非战技声。
  - **菜单** W/S 有轻微 tick 声、ENTER 上行提示、ESC 下行提示；**`ESC → SETTINGS` 的 `VOLUME` / `SFX`** 即时生效（切 `OFF` 立刻静音、切回恢复）。
  - **暂停时无新声音**、恢复后**不补放**暂停期间按过的键的声音；**连按攻击**不应是"机关枪式"完全相同的重复音（有 ±2.5% 音高微扰）；**同屏多声**不应有刺耳爆音。

> 回贴格式见 `VALIDATION_CHECKLIST.md` §5。渲染/手感/音频问题**不会**污染仿真确定性，可放心调参。

---

## 3. B 类 · 移植路线图（主开发清单）

> 权威详情：`docs/PORTING_GAP.md`（91 个原系统逐条判定矩阵 + 5 步移植模板见 `PORTING_PLAYBOOK.md`）。

### 批次 0 · NPC 接入主循环 ✅ DONE（2026-09-10）
`NpcSystem` 注册进主循环，首 tick 确定性生成 5 人聚落 + 每 tick 驱动 body/mind/social/decision。

### 批次 1 · 高玩家价值·社会核心 ✅ DONE（2026-09-10）
- ✅ family / emotion / norms / dialogue + NPC 渲染 + T 键交谈
- ✅ **storyteller + village_memory 消费端**：`Chronicle`（村志档案库：事件→episode + bigram Jaccard 检索 + 叙述生成，**不进 hashState**）+ `StorytellerSystem`（注册进主循环）+ HUD `CHRONICLE` ASCII 摘要 + `chronicle.log` 中文全文 + `Dialogue` 引用村志

### 批次 2 · 文明深度 ✅ DONE（2026-09-10）（7 个）
- ✅ `culture` · `religion` · `tech` · `industry` · `urban` · `diplomacy` · `warfare` 全部移植：`Civilization`（状态容器）+ `CivilizationSystem`（主循环驱动，固定子步序）+ HUD `CIV` ASCII 摘要 + `Chronicle` 归档文明里程碑。
- 🚧 **材料执行层**（tech 自治修墙/重建、industry 网格磨蚀/铺缆）由 `MATERIAL_WORKS` 开关守护默认关（守指纹基线）；开启属批次 4。

### 批次 3 · 个体成长 ✅ DONE（2026-09-10）（18 个）
`learning` · `behavior` · `persona` · `genetics` · `evolution` · `medicine` · `robotics` · `firearms` · `fishing` · `taming` · `xeno` · `ascension` · `magic` · `cultivation` · `myth` · `dreamscape` · `chemistry` · `roleplay`
- ✅ `Individual`（个体层开放标量聚合容器，**不进 hashState**）+ `IndividualSystem`（主循环驱动，固定子步序）+ HUD `LIFE` ASCII 摘要 + `Chronicle` 归档个体里程碑。
- 🔧 **两处跨批修复**：① 个体层研究池 `Individual.research` **独立于** `civ.research`（信号源分离，避免个体层抽干文明科技池致 `medicine` 永久不解锁）；② 批次 2 遗留**工业链死尾**（`power=0` 致 `assemble` 不启动、`mine` 被 `MATERIAL_WORKS` 提前 return 致 ore=0）→ 改为数字层产矿 + 畜力/水力供电基线 → `robots` 复活。
- 🚧 **材料执行层**（magic 真实施法写 temp、chemistry 反应层写 mat/mass/temp）由 `MATERIAL_WORKS` 开关守护默认关；开启属批次 4。

### 批次 4 · 深度打磨已覆盖系统 ✅ DONE（2026-09-10）
- ✅ `civilization`（城镇等级 + 定价 + 犯罪仲裁）· `trade`（货币/商队/通胀/供应链）· `disasters`（触发/预警/撤离）全部移植：`Polity` + `PolitySystem` + `Calamity` + `CalamitySystem` + HUD `POLITY`/`CALAMITY` 摘要 + `Chronicle` 归档（TOWN/CRIME/JUDGE/DISASTER/INFLATION）。
- ✅ `farming`（季节 + 灌溉）与 `combat`（技能/装备树）**已由既有 `SeasonSystem`/`IrrigationSystem`/玩家技能树覆盖**，本次不重造。
- 🔧 **三处可达性修复（避免死尾）**：① 撤离改**局部危险感知**（原全局旗标致全村永久恐慌）；② 商队改**小数累积器**（原 `(int)rate` 恒 0）；③ 仲裁增设**累犯**维度（原“惩戒”分支不可达）→ `pen=17/shield=19`。
- 🚧 **材料执行层**（点燃/抬水位/房屋→木）由 `CalamitySystem.MATERIAL_WORKS` 开关守护默认关（守指纹基线）；开启可选，届时指纹如预期演进。

### 批次 5 · 抽象系统 ✅ DONE（2026-09-11）（4 个；2 个显式排除）
- ✅ `phases`（物相：温度→SOLID/LIQ/GAS + 跃迁计数 + 合金度）· `entropy`（供给/做功/熵/有效能）· `fusion`（反应堆/聚变产出/辐射）· `deepsea`（潜艇/压力/矿物/污染/殖民地）全部移植：`Matter`（开放标量容器，15 标量，**不进 hashState**）+ `MatterSystem`（注册进主循环末位，固定子步序，`MATERIAL_WORKS` 默认关、**不消费 rng**）+ HUD `MATTER`/`DEEPSEA` 两行 ASCII 摘要。
- 🚧 **材料执行层**（辐射扩散/作物变异/NPC 辐射伤害/海洋遮罩/潜艇布点）由 `MatterSystem.MATERIAL_WORKS` 开关守护默认关（守指纹基线）；开启属可选。
- 🔧 **两处判定/防死代码**：① 信号源代理映射（Java 无 Python `temp`/`radiation` 网格 → 温度←火/灯方块+昼夜、熵←供电+日照、聚变←科研/供电、深海←水方块+科研，并在类注释写明理由）；② `deepsea` 原门「科技+齿轮」在 Java **永不可达**（实测 `civ.gear≡0`）→ 改用「水≥100 且 科研≥60」门，防空死代码。
- ⛔ **显式排除**（无世界状态，非遗漏）：`link`（鸭子类型 `getattr` 跨系统粘合层）· `portal`（CLI/Web 配置中枢）—— 跑一遍不改任何 `hashState` 字段，属"粘合/配置"而非"世界状态"。

> **明确不移植**（与体素动作游戏定位不符，强移会稀释 USP）：quantum / relativity / galaxy / astro / orbital / timespace / timeline / timewarp / dimension / warp / space / spacecol / metaspace / logic / boardgame / cards / gamemaster / realmcfg / cosmiccat（~28 个）。

---

## 4. C 类 · 非移植功能待办 & D 类 · 技术债

### C 类 · 非移植功能 ⏳
| 序 | 事项 | 说明 | 依赖 |
|---|---|---|---|
| ~~C1~~ | ~~**CJK 位图字体**~~ | ✅ DONE（2026-09-10）——`CjkFont` 运行期位图字体；中文回话气泡（T）+ 村志浮层（B）**已上屏** | —— |
| ~~C2~~ | ~~**塞尔达循环后半段**~~ | ✅ DONE（2026-09-10）——试炼点（能力门）+ 补给箱 + 世界之心 + 目标链 | —— |
| ~~C3~~ | ~~**多武器/多战技**~~ | ✅ DONE（2026-09-10）——`Weapons` 原型表（突刺/回旋斩/远射），随武器解锁 + G 键切换 + 三套战技演出 | —— |
| ~~C4~~ | ~~**音频**~~ | ✅ DONE（2026-09-10）——`Sfx` 23 音效参数表 + `AudioSynth` 纯函数合成 + `AudioMixer` 声部池/抢占/老化 + `javax.sound` 输出（**零新增依赖**）；菜单 VOLUME/SFX；第 15 道门禁 | —— |
| ~~C5~~ | ~~**菜单/暂停/设置界面**~~ | ✅ DONE（2026-09-10）——`MenuModel` 纯逻辑状态机 + `World.paused` 丢 tick 语义 + ESC 菜单（RESUME/SETTINGS/CONTROLS/QUIT 二次确认）+ FOV/灵敏/视距/HUD 即时生效 + 第13道门禁 | —— |
| **C6** | **巨构 / 巨物主义（Megalith）** | **Phase 1 ✅ DONE (code)（2026-09-11）**——远景剪影 `Silhouettes.java`（120–220 格，render-only，`uFogScale` 雾缓解 0.35，雾中浮现 ~730 格）。**Phase 2 ✅ DONE (code)（2026-09-11）**——`Game` 世界 `SY` 48→**112**；`Megalith.java` 神殿材化进 `mat`（与剪影共用 `descAt`；TOWER 总高 48：环墙+11×15 门洞+中庭空腔+祭坛长明灯+金冠塔楼；GATE 断拱门；选址带 `[waterLevel+2, sy-58]`、`MIN_SY=96` 门控）；`Blocks` 追加 `ASHLAR` 家族+`GOLD`；近于 `SKIP_R=128` 剪影让位。**Phase 3 ✅ DONE (code)（2026-09-11）**——`weather()` 风化层（低处条石苔藓侵蚀 5%→25%）+ 倒伏半埋躺柱 ×2。**三期全落码：当时 18 道门禁全绿、四道基线逐字节不变（零基线漂移：MIN_SY 门控使门禁小世界 SY=40 走不到神殿路径，原预期「有意重基线」未发生）**；第 17 道门禁 `MegalithDeterminismTest` 六证（现基线 MEGA `3a08e840e0d16524`）。**待 👤 本机目视**（§2 巨构验收项）。规格 `ART_BIBLE.md §9`；参考 `refs/MCP-Reborn-1.20/REFERENCE.md` | 已由 art-director 亲任 technical-artist 落地 |

### D 类 · 技术债 / 打磨
| 序 | 事项 | 出处 | 状态 |
|---|---|---|---|
| D1 | 系统瘦身评估（审计：52 活跃 / 29 休眠） | EVAL-1 P2-A | 🔵 用户已决定暂缓 |
| ~~D2~~ | ~~村志记忆去重（长程重复同义句）~~ | EVAL-1 P2-B | ✅ DONE（2026-09-10）：`Chronicle` 骨架判等长程去重 + 计数/归档解耦 + 节日阈值改按发生次数；第 7 道门禁加固。实测 **episodes 60→18**（35 条重句收敛为 1）而 `REPEL 35` 计数不变 |
| ~~D3~~ | ~~昼夜/天气**视觉**反馈渲染（现仅记状态）~~ → **DONE：`DayCycle` 纯模型驱动天色/日照/环境光/光色/雾色 + 星空/日月盘 + HUD 时钟；第 14 道门禁** | EVAL-1 P2-C | ✅ DONE（2026-09-10） |
| D4 | 美术方向定稿 | EVAL-1 P2-D | ✅ 已立 `docs/ART_BIBLE.md` |
| D5 | 性能剩余热点（已 1.28ms/tick，收益递减） | EVAL-1 P2-E / SIM-PERF-5 | 🔵 用户已决定暂停 |

---

## 5. E 类 · 已完成归档（防误判为待办）

| 事项 | 状态 | 出处 |
|---|---|---|
| 引擎地基：确定性 / 零漂移 / 物理 / 无限流式 / 分块脏区 mesh | ✅ | `task_board.json` PROG/*、README §5 |
| 存档读档（`BWORLD1` 二进制，往返 hashState 一致） | ✅ | EVAL-1 技术就绪度表（GAMEPLAY-SAVE done）⚠️ README §5 仍写"尚未移植"（旧文档，以 EVAL-1 为准） |
| EVAL-1 **P0-A** 可交战敌人 / 战斗遭遇 | ✅ | `Beast` + `BeastSystem`（4 类型） |
| EVAL-1 **P0-B** NPC / 对话（世界回响读者） | ✅ | `core/agent/*` + `SocialSystem` + `Dialogue` + T 键交谈 |
| EVAL-1 **P0-C** HUD | ✅ | HUD（LV/HP/SOUL/PROSP/ART/ROLL/LOCKED）；⚠️ 菜单/音频仍在 C4/C5 |
| EVAL-2 **P0** 敌兵种类 + AI 差异 | ✅ | `EVAL2-P0-BEASTS` |
| EVAL-2 **P1** 动作演出层（挥砍/拖影/顿帧/屏震/闪白/击退） | ✅ | `COMBAT-FEEL` |
| EVAL-2 **P2** 养成 build + 塞尔达能力/探索 | ✅ | `BUILD-PROGRESSION` + `ZELDA-ABILITY` |
| EVAL-2 **P3** 程序化美术 | ✅ | `ART-PROG` + `ART-PROG-2`（AO/天空雾/风动/接触阴影） |
| NPC 社会层地基 + 批次0 + 批次1（含余项 storyteller/村志消费端） | ✅ | `NPC-SOC-FOUND` / `NPC-SOC-WIRE` / `NPC-SOC-PORT-B1` / `NPC-SOC-PORT-B1-CLOSE`（`Chronicle` + `StorytellerSystem` + HUD/`chronicle.log`） |
| 批次2 文明深度（culture/religion/tech/industry/urban/diplomacy/warfare） | ✅ | `NPC-SOC-PORT-B2`（`Civilization` + `CivilizationSystem` + HUD `CIV`） |
| 批次3 个体成长（18 个：learning/behavior/persona/genetics/evolution/medicine/robotics/firearms/fishing/taming/xeno/ascension/magic/cultivation/myth/dreamscape/chemistry/roleplay） | ✅ | `NPC-SOC-PORT-B3`（`Individual` + `IndividualSystem` + HUD `LIFE` + 第9道门禁 + 跨层研究池/工业链修复） |
| 批次4 深度打磨（civilization/trade/disasters + farming/combat 已覆盖） | ✅ | `NPC-SOC-PORT-B4`（`Polity` + `PolitySystem` + `Calamity` + `CalamitySystem` + HUD `POLITY`/`CALAMITY` + 第10道门禁 + 三处可达性修复） |
| C1 CJK 中文上屏（`CjkFont` 运行期位图字体，零资产零纹理） | ✅ | `C1-CJK-FONT`（+ 修复 `Font` 两处「ASCII 从不渲染」bug） |
| C2 塞尔达循环后半段（3 试炼点 + 4 补给箱 + 世界之心 + 世界目标链） | ✅ | `C2-ZELDA-LOOP-2`（`Trials` + `TrialSystem` + HUD `OBJ`/`TRIALS` + 第11道门禁 + 修复 shrine 横幅从不渲染的旧死分支） |
| C3 多武器·多战技（`Weapons` 原型表：LUNGE 突刺 / CLEAVE 回旋斩 AoE / SHOT 远射；随武器永久解锁 + G 键轮换 + 三套战技演出） | ✅ | `C3-WEAPON-ARTS`（`Weapons` + `Player.arts/cycleArt/unlockArt` + `Game` 三套 FX/HUD/中文播报 + 第12道门禁 `WeaponArtTest`） |
| C5 菜单/暂停/设置（`MenuModel` 纯状态机 + `World.paused` 丢 tick 语义；ESC 菜单 + RESUME/SETTINGS/CONTROLS/QUIT 二次确认 + FOV/灵敏/视距/HUD 即时生效） | ✅ | `C5-MENU-PAUSE`（`MenuModel` + `World.paused` + `Game` 菜单输入路由/光标模式/设置应用/浮层渲染 + 第13道门禁 `MenuTest`） |
| D3 昼夜/天气视觉（`DayCycle` 纯确定性模型：色调/日照方向/环境光/光色/雾色随 tick 推移；天空改为**按视线方向取色**（修掉"贴屏幕"旧缺陷）+ 程序化星空 + 日月盘 + 降雨压暗；`AuroraSystem` 的"夜"改用同一定义） | ✅ | `D3-DAYNIGHT-VISUAL`（`DayCycle` + `DayCycle.SkyBasis` + `Game` 天空/世界 shader 昼夜化 + HUD 时钟 + 第14道门禁 `DayNightTest`） |
| C4 程序化音频（`Sfx` 23 音效原型表 + `AudioSynth` 纯函数合成 + `AudioMixer` 声部池/抢占/老化 + `javax.sound` 输出（**零新增依赖**）+ `Game` 29 处触发 + 菜单 VOLUME/SFX + 修轻攻击暂停补放） | ✅ | `C4-AUDIO`（`core/audio/*` + `render/audio/AudioOut` + 第15道门禁 `AudioTest`） |
| D2 村志长程去重（`Chronicle` 骨架判等去重 + 计数/归档解耦 + `CivilizationSystem` 节日阈值改按发生次数 + 第7道门禁加固；实测 episodes 60→18 而 REPEL 35 计数不变） | ✅ | `D2-CHRONICLE-DEDUP`（`core/world/Chronicle.java` + `core/systems/CivilizationSystem.java` + `StorytellerDeterminismTest.java`） |
| 批次5 抽象字段（`Matter` 开放标量容器：物相/熵与有效能/聚变辐射/深海基地 + `MatterSystem`（`MATERIAL_WORKS` 默认关、不消费 RNG）+ HUD `MATTER`/`DEEPSEA` + 第16道门禁（可达性+指纹隔离+零RNG）；`link`/`portal` 显式排除） | ✅ | `B5-MATTER-FIELDS`（`core/world/Matter.java` + `core/systems/MatterSystem.java` + `core/sim/MatterDeterminismTest.java`） |
| 美术三期 ART-PROG-3（半球环境光 + 每体素颗粒 + 花田五色盘 + 调色/暗角；纯渲染层，零漂移） | ✅ CODE DONE，待本机 GL 目视验收 | `ART-PROG-3`（`Game.java` world/sky shader + `Chunk.vegTint/hash01` + `Blocks.FLOWER` + `DayCycle.ambient` + `docs/ART_BIBLE.md` §3/§8） |
| 创意支柱定稿 | ✅ | `docs/CD-PILLARS.md` |
| **M3① 跨图流式流畅（治本 + 分帧）**：索引表示 `TreeSet<int[]>` → **升序平铺 `int[]`**（去 301ms 比较器税）+ `setBlock` 增量补丁缓冲（`indexDelta`）+ `rebuildIndex` 三趟扫描合一 + **INDEX 阶段分帧**（`stepShift` phase 4，切 x 条带）。实测 **单帧最坏 508ms → 11.5ms（44x）**、COMMIT 帧 677ms → 2.67ms、smoothness gain 1.3x → 11.8x | ✅ | `M3-STREAM-SCALE`（`core/world/World.java` `CellSet`/`stepShift` + `render/lwjgl/Game.java` shifting 期暂停 tick + 第17道门禁 `StreamChunkTest`） |
| **M3② 实体查询去 `O(N²)`**：`SocialSystem.adjacencyAndMood` 由双重循环改**均匀网格 3×3 邻域查询**（判据/顺序不变，零漂移） | ✅ | 同上（`core/systems/SocialSystem.java`） |
| **N0 联机可移植性硬化**：`src/core` 56 处非 `StrictMath` 超越函数全部改 `StrictMath`（16 文件，行为保持）；第 37 道门禁 `PORTABLEMATH`（清洁性 + 扫描下限 ≥120 + 正样本对照，三重防假绿） | ✅ | `NETPLAY_READINESS` §5.1 |
| **N0b 跨 JVM 实证**：Corretto 8 与 OpenJDK 26 各跑全量门禁，**全部指纹逐字节一致**（相隔 18 个大版本）→ 联机方案 A 的地基由"理论"升级为"实测" | ✅ | `NETPLAY_READINESS` §5.1 |
| **N1 联机输入编解码**：`Intent` 补 `yaw`（量化 uint16，仿真零读 → 零漂移）+ `IntentCodec`（6B 显式协议）/`InputFrame`（`playerIds` 严格升序强校验）；第 38 道门禁 `NETCODEC`（14 项断言，首跑即抓到「截断抛错类型不符契约」） | ✅ | `NETPLAY_READINESS` §5.2 |
| **N2-0 快照完备性**：反射式 `StateCodec`（字段按名排序 / 自描述类型名 / 原地恢复 / `Unsafe.allocateInstance` 兜底 / 不支持类型**抛异常绝不静默**）+ ext v5（**92 个系统实例的私有状态**一并快照）；**跨进程双一致达成**。顺带修掉 2 个真实 bug（读档必崩 / 读档后世界冻结）；第 39 道门禁 `SNAPSTATE`（与 codec 同源 + 派生字段禁持久化 + 无幽灵条目，6 项断言） | ✅ | `NETPLAY_READINESS` §5.5/§5.6 |
| **N2-1 紧致快照**：窗口内编辑改「与 seed 基线的**稀疏差分**」（格式 `BWORLD2`，9B/格）→ **22.25 MB → 317 KB（70x）**；基线契约 = **全窗同源生成**（⚠️ 不能就地为单块重生成，`Megalith` 选址读邻列 `mat`）；挖出并修掉「派生字段被当状态写入（占 v5 段 69%）」「`SKIP` 全局按名匹配会静默跳过别类同名字段」两个**不会让门禁变红**的坑；第 40 道门禁 `NETSNAP`（7 项断言） | ✅ | `NETPLAY_READINESS` §5.7 |
| **N2-2 原地恢复 + N2-3 回滚黄金判据**：`World.restoreInPlace(byte[])`（不 new World / 不重注册系统 / 先强制重建基线再叠差分 / seed 尺寸不符响亮抛错）；**「跑 N → 回滚 RB → 修正输入跑到 N == 一开始就修正输入跑到 N」通过**，且实现比字面更狠（恢复前故意跑偏）；第 41 道门禁 `ROLLBACK`（5 项断言） | ✅ | `NETPLAY_READINESS` §5.7 |
| **N3-0/3-1/3-2 真实 UDP 锁步联机（全落地）**：`netHash` 宽哈希 desync 检测（与快照同源，21ms）+ `LockstepSession` 协议层（回环可测：缺帧不推进 / 输入延迟仅调度旋钮 / 补录历史被拒 / 篡改一字节两端都报）+ **真实 UDP 传输**（`UdpTransport` + `UdpRelay` 星形中继，人齐才发 WELCOME、晚加入响亮拒）+ **三种启动形态同码**（`--host` 本地服务 / `--dedicated` 纯中继 / `--join` 纯客户端）；**跨 JVM 子进程 hash 逐字节一致 `d978824b368f0c71`**；第 42/43/44 道门禁 `NETDESYNC`/`NETLOCK`/`UDPLOCK` | ✅ | `NETPLAY_READINESS` §5.8/§5.9 |
| **N4 预测回滚（GGPO 式客户端预测 + 权威帧到达回滚重演）**：`World` 基线缓存（`ensureBaseline` 懒生成 + `baseMat/baseMass` 引用复用，消除每 tick 204ms 全窗重生成 → `baselineRegenCount` ≤3 实测）+ `RollbackEngine`（应用前快照环形缓冲，按 `playerIds` 升序）+ `PredictiveSession`（本地意图即时跑在前面；远端延迟到达时用「重复上次确认远端意图」预测；权威帧与记录帧不符则 `restoreInPlace` 原地回滚 + 重演）；**黄金判据通过**：延迟 D=4 终态 `netHash` == 纯锁步（D=0）参照、预测真错→真回滚、确定性重演；第 45 道门禁 `PREDROLLBACK`（5 项断言） | ✅ | `NETPLAY_READINESS` §5.10 |
| **接入渲染主循环** | ✅ **N5 已收口（2026-09-14）**：`Game` 由会话驱动完整整 tick，窗口照常渲染；单机分支逐字不变 | — |

> ⚠️ `M3①` 有一处**行为契约变更**：`isShifting()` 期间渲染层**须暂停 tick**（INDEX 阶段索引不完整、GEN 阶段地形半新半旧）。`Game.loop` 已接线；门禁/无头步进走同步 `streamTo`（预算 = MAX）→ 单次调用内跑完，语义不变。

> ⚠️ 前述 ✅ 中，**美术二期 / NPC 社会层 / D3 昼夜视觉属"CODE DONE，待本机 GL 目视验收"**，**C4 音频属"CODE DONE，待本机用耳朵验收"**——即 §2 的 A 类第 1 项。

---

## 6. 各清单的权威源（交叉索引）

| 想查什么 | 去哪个文件 |
|---|---|
| 任务状态 / 负责人 / 门禁 | `orchestration/task_board.json` |
| 移植批次与逐系统判定 | `docs/PORTING_GAP.md` |
| 移植 5 步模板与纪律 | `PORTING_PLAYBOOK.md` |
| 推荐待办池 + 共享事实 | `orchestration/blackboard.json` |
| 本机验收操作与观察项 | `VALIDATION_CHECKLIST.md` |
| 创意身份裁决 | `docs/CD-PILLARS.md` |
| 美术规范 | `docs/ART_BIBLE.md` |
| 变更时间线（含指纹） | `orchestration/event_log.md` |
| 历史评测（存档） | `EVAL_REPORT.md`（EVAL-1）/ `EVAL_REPORT_2.md`（EVAL-2） |

> **基线指纹演进（完整链，以最新为准）**：DET `ad9e7b31ed47ec45` → `21a2de200fda8a8b`（2026-09-11 地形可玩性调参）→ `7fee9fbff5cffab1`（BEAST-SPAWN）→ `43723456791b5364`（SPAWN-PICK，2026-09-12）→ **`74ad826636fe8eb2`**（F4 三试炼地形门，2026-09-16，**现行**）。ZD 同步：`10ad281a` → `c9e1d983` → `8a69c3559c2d86bf` → `2927e48836bc7127` → `34c8bb722c5a1a6f` → **`5ce9392207387ebf`**（现行）。STREAMING `87d5bf1cecb4c628` 全程未变；NPC `390fbd4b` → `8560321416dc6ac5` → **`afa036e4b0065f35`**（现行）；TRIAL `202b17462c8791d4` → **`8352df75e6dcd359`**；MEGALITH `3a08e840e0d16524` → **`070a02ddb58ea0eb`**（hashB `f6eb884a0e1015f5`）；SOCIAL **`0dc8d8bef2718582`**。历史条目保留在 `orchestration/event_log.md`。门禁为同种子自洽断言，**52 道出口全绿**（57 处 `*_EXIT` 全 0）。M3（2026-09-14）与 2026-09-17 空转参数落地均为**零指纹变更**。

---

## 7. ACTION-1 / AVATAR-1（2026-09-11 · 动作系统 + 角色外观系统升级）

- **AVATAR-1 外观**（新增 `src/render/lwjgl/Avatar.java`，纯静态映射，零漂移）：
  - 玩家护甲→衣着主色/腿色（Rags 灰褐 / Leather 棕 / Plate 银白 / Ward 紫袍）；等级→肩章色（Lv2 灰 / 3 青 / 4 金 / 5+ 红）；
  - 武器→手持分段盒（Iron Sword 细长灰白 / War Axe 宽头 / Hunting Bow 长弓棕 / Rune Edge 符文青；Fist 空手不画），挥击窗口前伸；
  - 村民职业→帽式（farmer 草帽 / crafter 兜帽 / trader 高帽 / herbalist 兜帽 / guard 平盔）+ 双色着装（上衣职业色、裤压暗）；情绪色标/交谈提示上移防穿模；村民描边壳加高到 1.70。
- **ACTION-1 动作**（Game.java 渲染层）：行走 bob + walkBlend 走停平滑混合、待机呼吸、空中固定跨步姿态、落地压缩（landTimer 0.22s，挂在既有落地扬尘边沿点，含音效）、翻滚演出（rollAnim 0.42s 压低收腿 + 起手尘土粒子）、玩家受击模型混白。
- 铁律：全部只读玩家/村民已有字段渲染，不写 sim/RNG/网格；村民帽不含 RNG。门禁 22 出口全绿，四指纹逐字节一致（21a2de…/8a69c3…/87d5bf…/2597c5…）。
- 待 👍 目视验收：换装/升级外观变化、行走-待机过渡、翻滚尘土、村民职业剪影。

---

## 8. AP-TX 贴图/性能/PCG 轮 + 失焦修复（2026-09-11 · UE 思想借鉴落地）

- **AP-TX-A 图集 gutter+mipmap+aniso**（TextureAtlas.java）：图集 256→320（每 tile 内容 16px + 每边 2px gutter，UE Dilation 思想），dilateGutters 边缘复制；MIN=GL_NEAREST_MIPMAP_LINEAR + glGenerateMipmap（治远处闪烁），MAG 保持 NEAREST（近处锐利不变）；各向异性过滤（扩展探测，老驱动 try-catch 降级）。
- **AP-TX-B 细节纹理叠加**：新保留 DETAIL_TILE=254（两八度灰度噪声，中性 0.5）；world shader 按 vWorld.xz 采样 detail、距离 10→56 格 smoothstep 衰减归零，±18% 乘法打破近距大色块。
- **AP-TX-C HUD 文本 quad 缓存**（新增 HudText.java）：全参数拼 key → float[] 缓存，命中仅 memcpy；两帧未用即清（动态文本不堆积）。drawHud/drawHudOffHint/drawSkill 全部接入（菜单暂缓）。中文操作卡每帧数万顶点的字形重发射 → 归零。
- **AP-PCG-D 撒布参数表 + 碑林**：World 撒布密度/盐值/花密度提成 SCATTER_* 常量表（**执行骨架与 treeRng 短路序逐字节不变**，PCG 化第二层明确不做——顺序流风险）；Silhouettes 新增 STEIN 碑林幻影地标（每巨构 cell 10%、与 Megalith 互斥、无金饰守 §9.2-4、近距 80 格隐没=雾散幻影，纯渲染层零漂移）。
- **失焦修复**（LD-2026-09-11「画面晃+移动/转动没反应」）：根因=窗口失焦 GLFW 停投递输入且按住键收不到 RELEASE（keys[] 卡死），sim 不暂停 beast 照打。修复：glfwSetWindowFocusCallback 失焦清空 keys/动作队列/nav 边沿 + 解除锁定；handleMenu 暂停条件加 || !windowFocused；TAB 锁定补 LOCK ON/OFF/NO TARGET toast（锁定接管镜头此前无明示，像视角失灵）。
- 门禁 22 出口全绿，四指纹逐字节一致。待 👍 目视验收：远处方块不再闪烁、近处细节颗粒、碑林远景剪影、失焦自动暂停。

---

## 9. BEAST-SPAWN 开局体验修复（2026-09-11 · 指纹有意演进）

- **问题**：开局 tick 0 即在玩家 4-8 格刷敌，24 tick 后第二只；近战 move 无条件逼近到坐标重叠（实体无碰撞分离）→ 两兽贴脸围殴，开局空手 hp 见底 + 屏震红闪 =「两个东西堵着动不了，玩不了」。
- **修复**（`BeastSystem.java`，仿真层）：① `GRACE_TICKS=120` 出生宽限；② 刷距 4-8 → **10-18 格**；③ 近战逼近到 `contact*0.8` 即停，不再叠身。
- **指纹演进**（先例：地形调参）：DETERMINISM `21a2de…→7fee9fbff5cffab1`（300-tick 繁荣度 30→52，玩家存活率显著改善）、ZERO-DRIFT `8a69c3…→2927e48836bc7127`；STREAMING / NPC 指纹不变；22 出口全绿自洽。
- **AP-TX 同轮落地**（渲染层零漂移，详见 §8）：图集 320+gutter+mipmap+aniso、DETAIL_TILE 近距细节叠加、`HudText.java` 文本 quad 缓存、`World.SCATTER_*` 撒布参数表、Silhouettes 碑林幻影、失焦自动暂停 + TAB 锁定 toast。
- 待 👍 目视验收：**开局 6 秒无敌、首兽从远处来、近战停在贴脸前一格不再叠身**；远处方块不闪烁；切窗回来不卡键。


---

## 10. SPAWN-PICK 开局选址 + 方位罗盘（2026-09-12 · 指纹有意演进）

- **开局选址器**（Simulation.findSpawn，仿真层）：出生不再钉死世界中心（= 81 涌现系统落块区 + 地形随机凹地）。从中心 4 格起环形扫描至 24 格，取首个「3×3 开阔平坦」（邻列高差 <=1、上方 2 格净空）落点；确定性纯扫描零 RNG，无合格点回落中心。读档路径不重算（旧档兼容）。
- **方位罗盘**（Game.drawHud，渲染层零漂移）：顶部中央刻度条（视野 ±90° 映射），N/E/S/W 刻度 + 未领取祭坛（金点）+ 村庄方向（绿点）——玩家始终知道该往哪走（CD-PILLARS：世界因你而变要被看见）。
- **指纹演进**：DET 7fee9fbff5cffab1 -> 43723456791b5364（繁荣度 52 -> 59）、ZD 2927e48836bc7127 -> 34c8bb722c5a1a6f、NPC 390fbd4ba5f90ac5 -> 8560321416dc6ac5（npcCount 7 -> 5：村民环带随新出生点重摆，不合格落点跳过）。STREAMING 不变。22 出口全绿自洽。
- 待 👍 目视验收：出生在开阔平地（不再被围）；罗盘随视角滑动、金点指向祭坛、绿点指向村庄。


---

## 11. MC-CTRL 操作对标（2026-09-12 · 零漂移）

对照 MC 1.20（refs/MCP-Reborn-1.20 索引 + MC 公开行为）找出 4 个操作差距并落地 3 个：

| MC 机制 | 本作落地 |
|---|---|
| BlockItem.canPlace 防嵌墙 | doPlace 前置 blockTouchesPlayer（AABB 相交拒绝 + DENY 音） |
| 按住连续挖/放（rightClickDelay=4t） | mouseHeldL/R + 0.2s 节流，doDig/doPlace 单击/按住共用 |
| 疾跑（Ctrl，x1.3 + FOV 拉伸） | Ctrl+移动 x1.35 + FOV +8 度平滑（lastProjFov float） |
| Shift 潜行防坠边 | 未做（Shift=翻滚键位冲突，待键位重映射时一并考虑） |

操作卡第 11 条（CTRL 疾跑）+ MenuModel.CONTROLS 页同步。**零漂移验证**：22 出口全 0，四指纹逐字节不变——门禁世界无键鼠输入，输入/交互层改动天然不碰仿真。javac 坑：appliedFov 是 int，sprint 平滑必须独立 float 投影基准。


---

## 12. 村庄庇护所 + 夜幕横幅 + 罗盘 V2（2026-09-12 · 零漂移）

- **村庄庇护所**（BeastSystem.spawn，仿真层但门禁路径逐字节不变）：刷点落在村庄半径内（NpcSystem.VILLAGE_RADIUS）重掷至多 3 次后放弃——修复选址器副作用（玩家出生地进村 → 兽群屠村 → NPC 门禁 npcCount 7→5）。关卡语义：**村庄=安全区，野外=威胁区**，与罗盘绿点（回村）形成正反馈。
- **夜幕/黎明横幅**：DayCycle.isNight 边沿 → 中文横幅「夜幕降临 · 野兽在黑暗中游荡」/「天亮了 · 村庄醒来」；banner 绘制补 CJK 分支（中文走 ASCII 字体会变问号——历史遗留一并修）。
- **罗盘 V2**：金点下标能力首字母（G=滑翔 D=冲刺 B=炸弹），三个祭坛分得清先后。
- 22 出口全绿，四指纹逐字节不变。


---

## 13. 流畅移动 + 开局安全确认（2026-09-12 · 零漂移）

- **移动加速度平滑**（MC travel 手感对标）：输入向量指数平滑（dt*12 快响应），起手有加速、松键带滑停，替代旧版硬起硬停；菜单恢复清零防突跳；死区清零防蠕动。vsync 确认已开。
- **开局安全三件套闭环**：选址器（开阔平坦、距村心 >=4）→ 出生必在村庄庇护所内（村心 24 格 = beast 村内不刷）→ GRACE 120 tick；本轮补开局横幅告知庇护所状态与罗盘用法（只出一次）。
- 22 出口全绿，四指纹逐字节不变（43723456791b5364 / 34c8bb722c5a1a6f）。


---

## 14. 内容平台 P0：万物皆配置的内核（2026-09-13 · 零漂移）

**战略转向**：从「堆系统」转向「内容平台」——技能 / 魔法 / 粒子 / 特效 / 状态 / 物品 / 剧情 / 敌人全部 JSON 化，改文件即改游戏。诉求：游戏本身就是 mod 平台，万物可配置、可持续加入。

- 新增 `docs/CONTENT_PLATFORM.md`：五条铁律 / 目录规范 / 七类 schema / 13 条指令白名单 / 分期路线 / 诚实边界。
- 新增内核 `src/core/content/`：**Effect**（13 条指令白名单，未知即拒绝）、**EffectSink**（内核↔渲染唯一出口，core 零渲染依赖）、**EffectQueue**（确定性调度 + hash01 零 RNG 派生 + (dueTick,seq) 稳定排序）、**ContentRegistry**（加载/校验/引用完整性/循环检测/孤儿统计/确定性快照）。
- 新增 `core/systems/ContentSystem.java`：桥——实现同一 System 接口，**刻意不使用传入的 rng**（内容层零 RNG 纪律）。P0 未挂进 systems，P1 挂载。
- 新增门禁 **CONTENT**（第 27 出口，10 项性质）：LOAD / DET / WHITELIST / REF / CYCLE / ORDER / DELAY / QUEUE / HASH / FILE / DISPATCH。FILE 断言真实磁盘内容目录零错误 = 内容加载护栏。
- 新增示例内容 **11 个文件**（`assets/content/` 七类目录全部示范）：3 粒子 + 1 复合特效 + 3 技能 + 1 状态 + 1 物品 + 1 敌人 + 1 剧情。
- 27 出口全绿；四道指纹不变（43723456791b5364 / 34c8bb722c5a1a6f / 8560321416dc6ac5 / 3a08e840e0d16524）。
- **边界判断（诚实）**：93 个涌现 System（生态/气候/文明/灾害）**不配置化**——它们改网格、进指纹、互相耦合。配置化的是「内容」，不是「法则」。
- 路线：P1 粒子特效接入渲染（替换 Game.java 硬编码 Particle）→ P2 技能魔法（接 Weapons.Art / Soul-forge）→ P3 剧情系统（QuestGraph + 对话树）→ P4 MOD（zip 加载 + 热重载）。


---

## 15. 玩法层 P0.5：ECA 规则引擎 + 预设 + 平台蓝图（2026-09-13 · 零漂移）

**战略确认**：用户定调「先引擎、平台优先」——目标是「各个系统还能新增很多系统的平台」。本轮交付玩法层，并定位系统层为下一战场。

- 侦察关键发现：**World.events 事件总线早已存在**（`Event{system,action,params,result}` + 水印 eventsProcessed，明确不进指纹），93 系统一直在发 **116 种真实事件**（taming/firearms/robotics/industry/magic/cultivation/ascension/xeno/dreamscape/myth/diplomacy…）。**我们不缺玩法维度，缺的是组合层。**
- 新增内核 `core/content/`：**Rule**（ECA：on 事件 / if 条件（5 类 check 白名单）/ then 动作 —— 直接复用 13 条 Effect 指令，不发明新语言）、**RuleEngine**（订阅事件总线，自有水印，绝不写 world.eventsProcessed）、**Preset**（预设 = 模块开关 + 规则开关 + 参数覆盖；含 ModuleDef 元数据）。
- ContentRegistry 类型 7 → 10（+rules/presets/modules），新增规则悬空引用校验、预设模块/规则引用校验。
- 新增门禁 **GAMEPLAY**（第 28 出口，10 性质）：RULES / MODULE / PRESET / REJECT / MATCH / FIRE / WATERMARK / COOLDOWN / SCALAR / CHANCE / DET。**28 出口全绿、四指纹不变。**
- 新增示例 **13 个文件**：modules×5（survival/emergence/capture/automation/mythic）、rules×5（捕捉奖励/骑乘/收获物流/兽群压境/科技突破，事件名全部对齐真实系统）、presets×3（**breathing_world = 我们的预设** / palworld_like / mythic_sandbox）。
- 新增 `docs/PLATFORM_BLUEPRINT.md`：平台四层能力表 + 三种「加东西」手册 + 引擎层 MC 对标（学架构不搬代码）+ **下一批：SystemRegistry 系统层注册表化**。
- **系统层瓶颈实测**：`Simulation.java` 有 94 行 import + **92 个手工 addSystem** + 约 140 行注册代码，顺序敏感却不可见，且无法按预设开关系统 —— 这是「源源不断新增系统」的真实成本。


---

## 16. 系统层注册表化：92 系统分 10 域 + 黄金序列（2026-09-13 · 零漂移）

**战略目标落地**：用户定调「各个系统还能新增很多系统的平台」——本轮打通系统层（平台四层的最后一块核心）。

- 新增 `core/systems/Phase.java`（8 职责域）+ `core/systems/SystemRegistry.java`（**顺序单一真相** / 黄金序列 / 按域开关 / 重名拒绝）。
- `World`：`registry` 字段 + `addSystem(s, phase)` 重载；tick/tickAudited 内加 `isDisabled` 空集短路判定（**默认全开 → 逐字节等价**）。
- `Simulation.registerDefaultSystems()`：92 行手工注册 → **10 个域方法**（签名/环境/气候/水文/生物群系/纪元/生物链/社会/深部地质/实体），**顺序逐行保持**（脚本按原序列搬移，零重排）。
- 新增门禁 **SYSTEMREG**（第 29 出口，10 性质）：COUNT / **GOLDEN（黄金序列哈希 -6666983850743452865）** / UNIQUE / PHASES / NODRIFT / DISABLE / DISABLE_PHASE / ENABLE / DUPLICATE / REPORT。
- 域分布：signature 1 / terrain 14 / vegetation 33 / weather 13 / geology 9 / society 15 / entity 4 / meta 3 = 92。
- **29 出口全绿；四道仿真指纹逐字节不变。**
- **核心纪律**：phase 只是标签，**绝不参与排序** —— 顺序即指纹（改顺序 = 世界历史改变）。黄金序列哈希就是这条纪律的守卫。
- 新增系统成本：**1 个类 + 1 行 `reg(Phase.X, new YourSystem())`**。


---

## 17. 平台贯通三连：域报告 + 预设接系统 + 粒子配置化（2026-09-13 · 零漂移）

用户「1 2 3 都做吧」→ 三件全部落地，**30 出口全绿、四道仿真指纹逐字节不变**。

- **①系统域报告可见**：启动时把 `registry.report()`（域清单 + 开关状态）写进 `game_diag.log`；按 F3 也落一份（报障取证）；F3 面板新增 `SYS 92 OFF 0 PH 8` 一行。
- **②玩法层接通系统层**：`Preset` 新增 `disablePhases` / `disableSystems`；`Simulation.applyPreset()` 先复位再按预设关闭（**默认预设零禁用 → 演化逐字节不变**，显式关闭才是意图）。新预设 `peaceful_valley.json`（关掉 GEOLOGY 域 = 9 个系统）。门禁 SYSTEMREG 加 `PRESET_APPLY`（第 11 项）：breathingNoop=true / peaceful disabled=9/92 / restored=true。
- **③粒子配置化（P1）**：新增 `core/content/ParticleSim.java`（**core 层纯模型，SoA 平铺数组 512 池，零 GL 依赖 → 可无门禁**）；`Game` 实现 `EffectSink`（particle/fx/sfx/shake/banner/simulate），4 个硬编码发射点全部替换为 `particles.spawn(def,…)`，渲染循环读 `size/red/green/blue`（支持尺寸与色带插值）。新增粒子 `dust.json` / `mote.json`。新增门禁 **FX**（第 30 出口，10 项）：SPAWN/DET/GRAVITY/DRAG/LIFE/RAMP/CAP/SHAPE/CLEAR/HASH —— 首跑即抓出 2 个真 bug（尺寸未插值、丢弃计数只加 1）。
- `initContentLayer(seed)`：装配注册表 + 粒子 + 效果队列 + 规则引擎，并在每 tick 推进 `rules.tick → effects.tick`。
- 磁盘内容 27 项（FILE 门禁零错误）。
- 待接 P2：`sfx` 命名映射、`shake` 屏震合流、`simulate` 模拟类指令（伤害/给物品/传送）。


---

## 18. P2 补完 EffectSink 三缺口 + 对标学习清单（2026-09-13 · 零漂移）

**用户指令**：P2 三缺口必做；并点名 5 款参考（2077 捏脸 / DNF 技能组 / 饥荒缺氧科技链 / 塞尔达探索与攻防 / 上古卷轴 5 成长）+ 自省"动作系统与战技粗糙"。

- **sfx 缺口补齐**：`SFX_BY_NAME` 命名表（19 个映射：charge/dash/boom/hit/kill/loot/levelup…）+ 未命中回退 `Sfx.valueOf(大写)`；都不中则静默忽略（音效缺失不该打断玩法）。
- **shake 缺口补齐**：**累加式创伤模型**（幅度取大者）+ 平方衰减 + sin 噪声偏移；每帧 `updateShake`；帧内只偏移**视图 eye**，**不动 camPos、不写世界** → 纯渲染零漂移。
- **simulate 缺口补齐**（6 条指令接通）：`DAMAGE`（走 `Player.hitBeast` **同一套结算** —— 为此把 hitBeast 从 private 开为 public，避免内容伤害与武器伤害语义漂移）、`HEAL`、`TELEPORT`（沿朝向 sweep，带碰撞）、`KNOCKBACK`、`SUMMON`（Beast.make 入世界）、`SET_BLOCK`（唯一进指纹的指令 —— 因为它真的改世界）。**`GRANT_ITEM` 明确未实现**（无背包系统，P3），代码内注释标注，不假装。
- **30 出口全绿、四道仿真指纹逐字节不变。**
- 新增 `docs/REFERENCE_STUDY.md`：五款游戏的「学什么 → 落在哪一层 → 现在缺什么」对照 + **动作系统 6 大缺口**（取消窗 / 判定帧 / 连招链 / 根运动 / 混合树 / 上下半身遮罩）+ 分批路线 A→F（按投入产出比）。
- **核心判断**：对标项九成可落在内容层/玩法层（数据驱动，当天可见效）；只有**动作系统打磨（C 批）必须改 core/anim**，且届时必须补动作门禁（取消窗时序/连招合法性/判定帧唯一性）——"手感"写坏没有断言就挡不住。


---

## 19. A 批落地：科技链（配方图）+ 用中学成长（2026-09-13 · 零漂移）

**用户「下一步」→ 执行参考清单里的 A 批**（投入产出比最高：纯数据驱动、当天可见效）。

- 新增 `core/content/TechDef.java`（配方图节点：requires / minProsperity / station / cost / unlocks / effects）+ `TechTree.java`（运行时：按 20 tick 节流检查、幂等解锁、**静态环检测**、`availableIds/lockedIds` 供 UI）。
- `Effect` 白名单 13 → **14**：新增 `GRANT_SKILL`（把标记写进 `World.skills`）—— 它是「科技解锁」与「用中学成长」**共用的出口**，不发明第二套语言。
- `ContentRegistry` 类型 10 → **11**（+`techs`），加载期做环检测（成环即报错 —— 死锁必须在加载期抓出）。
- `TechTree` **不做成 System**：否则 `systems` 列表多一项 → 黄金序列哈希失配。改为渲染层主动 tick（与 ContentSystem 同策略）→ **新增能力与基线稳定不冲突**。
- `Game`：`initContentLayer` 装配 TechTree，每 tick 推进；`simulate` 新增 `GRANT_SKILL` 分支。
- 示例：`techs/` 4 节点配方图（mining → kiln → smelting → steel，繁荣门槛 5/8/12/20）+ `rules/learn_by_doing.json`（上古卷轴式：击杀累积 → 解锁 `combat_instinct`）。
- 新增门禁 **TECH**（第 31 出口，10 性质）：LOAD / GRAPH / CYCLE / GATE_PROSPERITY / GATE_REQUIRES / UNLOCK / CHAIN / EFFECTS / IDEMPOTENT / PACING / AVAILABLE —— **一次通过，零返工**。
- **31 出口全绿；四道仿真指纹逐字节不变**（门禁世界不启用 TechTree）。
- 说明：`World.skills` 在 `hashState()` 之内，所以**解锁会（有意地）改变指纹** —— 那是"解锁真的改变了世界"的正确表现，不是漂移。


---

## 20. 标杆补充：泰拉瑞亚（平台与 MOD 生态）2026-09-13

用户补充：「泰拉瑞亚也是学习榜样，什么都有 mod 又多，MC 也是」。

- 判断：**它比任何单一机制都更值得学** —— 它证明「结构清晰的引擎 + 数据表 + 开放扩展接口 = 能长 15 年的内容宇宙」。
- 可学四点：①**进程阶段**（打 Boss → 世界进入下一阶段，全局规则改变）；②**事件式入侵**（血月/哥布林 = 时间+条件触发的全局事件，我们的 `rules` + `events` 已具备）；③**统一 ID 表 + 引用**（我们以文件名作 ID，已具备）；④**tModLoader 式扩展点**（mod = 代码 + 数据）。
- **核心启示（修正"万物可配置"的表述）**：**万物可声明；机制需编码一次，之后无限复用。** 这正是我们的三层：数据（`assets/content`）→ 模块（`GameplayModule`）→ 引擎。
- **P4/MOD 支持提升优先级**：内容包（`mods/*.zip`）加载 + 同 ID 覆盖优先级 + 可选代码扩展 + 冲突报告（复用 ContentRegistry 校验）。"什么都有 mod 又多"是**接口开放**的结果，不是内容量的结果。
- 路线调整为：A ✅ → B → D → **G(MOD)** → C → E → F。
- 文档：`docs/REFERENCE_STUDY.md` 新增 §6 泰拉瑞亚章节。


---

## 21. B 批（技能树）+ D 批（完美闪避/反击）+ 引擎成熟度评估（2026-09-13 · 零漂移）

用户「B+D」+ 提问「引擎的搭建什么时候完善」。

- **B 批 · 技能树**：新增 `SkillTree`（DNF 式：`tier` / `requires` / `costSouls` / `art`）；`skills/*.json` 加四个字段即可组树；学习写 `Player.abilities`（**玩家**养成，不进指纹），可选解锁战技原型 `Player.arts`。Game 内 **K 键**学习第一个可学技能 + F3 显示 `SKILL n/m SOULS DODGE`。
- **D 批 · 完美闪避/反击（塞尔达式）**：侦察发现 `Player.invuln` 无敌帧**早就有了**，缺的是**奖励**。补：无敌帧内被击 → `perfectDodges++` + 开 0.7s 反击窗 → 窗口内命中**伤害翻倍**并消耗窗口。全在 `Player` 实体状态，零漂移。
- **架构整理**：把环检测抽成通用 `GraphCheck`（科技树与技能树**共用同一份实现**）；`ContentRegistry` 加载期对技能图也做环检测（成环=谁都学不了）。
- **新增门禁 SKILL**（第 32 出口，10 性质）：LOAD / GRAPH / CYCLE / GATE_SOULS / GATE_REQUIRES / LEARN / ART / AVAILABLE / DODGE / RIPOSTE。
- **32 出口全绿；四道仿真指纹逐字节不变。**
- **新增 `docs/ENGINE_MATURITY.md`** —— 直接回答"引擎何时完善"：
  - **判据**：不看功能数量，看**「加第 100 个内容时要不要改引擎」**。
  - **M1（加内容零改引擎）已达成**；M2 = 手感(C) + MOD(G)；M3 = 流式分帧 + 实体查询去 O(N²)。
  - **诚实结论**：引擎永远不会"完善"；但 M1 已过，**M2/M3 之前内容可以一直加**。建议**用内容反推引擎**，而不是先造完美引擎再想装什么。


---

## 22. G 批落地：MOD 支持（2026-09-13 · 零漂移）

用户「那就开始吧」→ 执行 G 批（泰拉瑞亚式扩展点）。

- 新增 `core/content/ContentSource.java`：内容来源抽象（目录源 / zip 源 + priority）。
- `ContentRegistry` 新增 **`loadAll(List<ContentSource>)`**（多来源合并 + **覆盖优先级** + 覆盖记录）、**`discoverMods(File)`**（扫描 `mods/`：目录 mod 与 zip mod，按名字典序 → 确定性）、`overrides()` 报告。
- **关键设计**：合并发生在**文件表**层，之后走同一个 `build()` —— 于是**校验、环检测、快照、解析全部复用，一行都不用改**。新增「来源」一个概念，换来整套 mod 能力。
- 覆盖语义：priority 低者先放、高者覆盖；**同 priority 按来源名字典序**（与文件系统枚举顺序无关）。
- 示例 mod：`mods/example_mod/`（**覆盖**官方 `skills/ember_harvest.json`：costSouls 25→10、产出 1→3；**新增** `particles/ember.json`）+ README。
- `Game.initContentLayer` 改为 `loadAll(base + discoverMods(mods/))`，启动时把来源数与覆盖清单写进 `game_diag.log`。
- 新增门禁 **MOD**（第 33 出口，10 性质）：DISCOVER / BASE_INTACT / LOADALL / OVERRIDE / REPORT / PRIORITY / DET / ZIP / ISOLATION / CONFLICT —— **一次通过**。
- **33 出口全绿；四道仿真指纹逐字节不变。**
- 文档：`CONTENT_PLATFORM` 新增 §10 MOD 支持（目录约定 / 合并规则 / 覆盖报告 / 为何新机制仍需代码 / 门禁）；`ENGINE_MATURITY` 更新（MOD 从 ❌ → ✅，**M2 只剩 C 批**）；`REFERENCE_STUDY` 路线更新（C 升为最优先）。

---

## 23. M3 落地：大世界规模流畅（2026-09-14 · 四基线指纹逐字节不变）

用户指令：**「做 M3，地图不是一直都要这么小的，后面要做无缝的大世界，现在不做，一时偷懒会导致后面不行重构」**
+ **「两者都做」**（既治本、也分帧）。故本轮**按未来无缝大世界标准**实现，不以「N≤24 现在够用」为由缩水。

### 23.1 问题定位（先量，不猜）

微基准拆解 160×112×160 窗口的一次 `rebuildIndex`（改造前实测 583ms）：

| 环节 | 耗时 |
|---|---|
| 纯扫描 2.87M 格（读 mat 计数） | 8.0 ms |
| + 每格 `new int[3]` × 97 万 | 20.3 ms（分配税 12.3 ms） |
| + 逐个 `TreeSet.add` | **321 ms（比较器 + 红黑树税 301 ms = 94%）** |

**结论**：停帧的绝对大头是 **`TreeSet<int[]>` 的数据结构税**，不是地形生成（GEN 仅 ~6ms/帧）。
这一条推翻了文档里「22s 停帧」的旧预设 —— 也说明**改动前必须先量**。

### 23.2 改造（治本四步 + 分帧一步）

1. **索引表示换血**：`TreeSet<int[]>` → `CellSet`（**升序平铺 `int[]`**，3 int/格）。
   - 全量重建：网格序 `x→y→z` 扫描**本身就是字典序升序** → 顺序 append（零移位、零分配）；
   - 增量变更：两分查找 + `arraycopy` 移位（变更量小，成本可控）；
   - 对外仍返回 `Collection<int[]>` —— 21 处调用点**仅 1 处**（`CivilizationSystem.mine` 的显式 `TreeSet` 声明）需改类型。
2. **`setBlock` 热路径去 TreeSet 插入**：改为把 `(旧类型→新类型, 坐标)` 追加进 `indexDelta` 紧凑缓冲（零分配 O(1)），
   消费前 `ensureIndex()` 只应用**这一小批变更**（而非全量重建 —— 早期「标脏全域 + 惰性全量重建」方案会让
   70 写 + 15 读系统交替时退化成每 tick 多次全域重建，INDD 门禁 120s 超时的教训）。
3. **`rebuildIndex` 三趟合一**：主扫描同时完成 ① 索引 append ② `surfaceCells`/`waterSurfaceCells` 判定
   ③ `surfaceY` 维护（经 `surfaceTopY` 暂存），消除原先对 97 万格的二/三次遍历。
4. **`stepShift` 新增 INDEX 阶段（phase 4）**：把索引重建切成 **x 条带**按预算推进，消除 59ms 的 COMMIT 尖峰。
   - ⚠️ **契约变更**：`isShifting()` 期间渲染层**须暂停 tick**（`Game.loop` 已接线）。
     因 INDEX 阶段索引不完整、GEN 阶段地形半新半旧，混态下 tick 会让演化失真。
   - 门禁 / 无头步进走同步 `streamTo`（预算 = `Integer.MAX_VALUE`）→ 单次调用跑完，**语义逐字节不变**。
5. **M3②**：`SocialSystem.adjacencyAndMood` 由 O(N²) 双重循环改**均匀网格 3×3 邻域查询**（判据 / 顺序不变）。
   （附注：Java 版**无寻路系统**，故 M3② 的真实对象只有这一处，非文档曾写的 `find_path`。）

### 23.3 实测（`M3Bench`：200 次预热 + 独立 World 实例 + 中位数，防 JIT 伪影）

| 指标 | 改造前 | 改造后 | 提升 |
|---|---|---|---|
| **单帧最坏**（sliced worst） | 508 ms | **11.5 ms** | **44x** |
| COMMIT 帧最坏 | 677 ms | **2.67 ms** | **253x** |
| 每帧平均 | — | 3.2 ms | — |
| smoothness gain | 1.3x | **11.8x** | — |
| immediate（整窗同步） | 346→659 ms（噪声大） | 136 ms（median） | — |

**11.5ms < 16.7ms（60fps 预算）→ 「无缝大世界」的流畅目标达成。**

### 23.4 零漂移证据

- 四基线指纹**逐字节不变**：DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / PHYSICS `PASS` / STREAMING `87d5bf1cecb4c628`；
- 全量 **36 道门禁、40 处 `*_EXIT` 全 0**；
- 新增 `StreamChunkTest` **第 4 段断言**：INDEX 最深分帧（每帧 1 条带，316 帧）与一次跑完的
  `window`/`hashState`/索引/`surfaceY` **完全一致**。

### 23.5 复用教训（写进工程记忆）

- `Override` / `System` 在本项目**被自定义类遮蔽** → 内部类里写注解 / 调用需 `@java.lang.Override` / `java.lang.System`。
- **分帧期间的「中间态一致性」是硬约束**：凡切片重建派生结构，必须同时规定「谁能读、能否 tick」，
  否则会静默污染仿真。
- `CellSet` 移植到**无 treeRng 顺序依赖**的纯派生索引最安全；一旦涉及 RNG 消费序，绝不可换容器。

---

## 24. N 系列落地：确定性锁步联机地基（2026-09-14 · 四基线指纹逐字节不变）

> 完整方案与决策记录见 `docs/NETPLAY_READINESS.md`（§0 结论 / §1 三盆冷水 / §5.1–§5.7 落地记录）。
> 本节只留"改了什么 / 实测多少 / 还剩什么"，防误判为待办。

### 24.1 各步与实测

| 步 | 内容 | 门禁 | 关键实测 |
|---|---|---|---|
| **N0** | `src/core` 56 处非 `StrictMath` 超越函数 → `StrictMath`（16 文件，**行为保持**：迁移前后指纹逐字节相同） | 第 37 道 `PORTABLEMATH` | 清洁性 + 扫描下限 ≥120 + 正样本对照（三重防假绿） |
| **N0b** | Corretto 8 vs OpenJDK 26 各跑全量门禁 | —（实验） | **相隔 18 个大版本，全部指纹逐字节一致** → 联机地基由"理论"升级为"实测" |
| **N1** | `Player.Intent` 补 `yaw`（量化 uint16，仿真零读 → 零漂移）+ `IntentCodec`（6B 显式协议）/ `InputFrame` | 第 38 道 `NETCODEC`（14 断言） | 首跑即抓到「截断抛 `AIOOBE` 而非契约声明的 `IAE`」 |
| **N2-0** | 反射式 `StateCodec` + ext v5（**92 个系统实例的私有状态**一并快照） | 第 39 道 `SNAPSTATE`（6 断言） | 跨进程**双一致**：存档→读档→再演化 120 tick hash 逐字节相同 |
| **N2-1** | 窗口内编辑改「与 seed 基线的**稀疏差分**」（`BWORLD2`，9B/格） | 第 40 道 `NETSNAP`（7 断言） | **22.25 MB → 317 KB（70x）**；窗口差分仅 0.65%；压缩比 68x |
| **N2-2** | `World.restoreInPlace(byte[])` 原地恢复 | 第 41 道 `ROLLBACK` | 恢复后 hash 与快照时**逐字节一致**、tick 回退到位 |
| **N2-3** | 回滚黄金判据 | 同上（5 断言） | `h@rollback == h@direct`，且恢复前**确实已跑偏**（防假绿） |

### 24.2 顺带修掉的真实 bug（原来都处于"门禁全绿"状态）

| bug | 根因 | 修法 |
|---|---|---|
| **读档必崩** | `TrialSystem` 只在 `tick==1` 布点，而 `load` 不跑 tick → `sites.get(i)` 越界 | `placeOnLoad` 用规范 `tick=1` 子流重建（`deriveStream` 与 `rng.state` 无关 → 可逐位复现） |
| **读档后世界冻结** | `Simulation(World)`（读档装配路径）漏注册 92 个系统 | `systemCount()==0` 时补注册（幂等） |
| **系统实例状态未纳入快照** | `WindSystem` 持 `private int t/gustX/gustZ`（t 定风向角），读档后系统新注册 → t 归零 → 风向立刻不同 | 快照按**系统名**记录每个系统实例状态，注册后按名对位写入 |

> 三个 bug 之所以一直没被抓到：**门禁走的路径和生产走的路径不一致**（旧 `SaveLoadTest` 用裸 `World`，系统数为 0）。

### 24.3 N2-1 途中挖出的两个「不会让门禁变红」的坑

1. **派生字段被当成状态写入**：`surfaceCells` 651KB / `surfaceY` 130KB / `surfaceTopY` 130KB / `waterSurfaceCells` 5.5KB
   = v5 反射段的 **69%（916KB）**，而它们读档后被一行 `rebuildIndex()` 全量重算。
   → `SNAPSTATE` 补 `DERIVED_FIELDS_NOT_PERSISTED`：**"派生"必须是可断言的性质**。
2. **`SKIP` 按纯字段名 = 全局匹配**：`ContentSystem.registry` 与 `World.registry` 重名而被"顺带"跳过。
   → 改**类限定名** + `NO_PHANTOM_SKIP`（字段改名后条目不会永远留着）。

### 24.4 还剩什么（N3 前置，诚实清单）

- **每 tick 取快照仍不可行**：`captureWindowDiffs()` 含一次**全窗基线重生成（204 ms）**。体积不是瓶颈
  （317KB × 60 帧 ring = 19MB），**时间**才是 → 需 ① 缓存基线（窗口不动时基线不变）+ ② 增量脏格记录替代全窗差分扫描。
- **窗口外编辑块仍整块落盘**（229KB/块）：`Megalith.fillChunk` 选址/地面高度会**读邻列 `mat`**，
  故「原始地形」只在整个窗口按固定序全量生成时才可复现 → 异窗上下文里稀疏差分**不安全**。块数随玩家实际路径增长（有界）。
- **`Player.intents` 不进快照**：属外部输入（N1 `InputFrame`），回滚由输入层重新注入 —— **契约：若某端一 tick 注入多条，队列会残留**。

### 24.5 N3-2 落地：真实 UDP 传输 + 三启动形态（2026-09-14）

N3 最后一块落地——把协议层（N3-1 的 `LockstepSession`）接到**真操作系统套接字**上，并用**一份权威代码**撑起三种启动形态。整套联机路线（N0→N3）至此闭环。

#### (a) 新文件

| 文件 | 角色 |
|---|---|
| `core/net/UdpTransport.java` | UDP `Transport` 实现（`DatagramChannel` 非阻塞 recv + 握手 join：并行连、人齐才 `connect` 收 WELCOME、重试超时响亮抛 `IOException`） |
| `core/net/UdpRelay.java` | 星形中继：`bind(port, seed, players)` 起后台线程收 HELLO，人齐一次发 WELCOME（重复 HELLO 幂等重发，避免幽灵玩家），人齐后 HELLO 计数 `rejects++` |
| `core/net/LockstepRunner.java` | 无头锁步驱动器（`--host`/`--join` 共用；`run` 收尾打印 `FINAL tick=.. hash=.. desync=..` 供跨进程门禁断言） |
| `core/net/NetMain.java` | 联机入口：**`--host`**（起中继+自己也是客户端）/ `--dedicated`（纯中继无仿真）/ `--join`（纯客户端）；`--out` 双写日志文件（stdout 管道在子进程销毁时机不可靠，文件不会） |
| `core/sim/UdpLockTest.java` | 第 44 道门禁 `UDPLOCK`（4 项断言）：两端/三端收敛、晚加入被拒、**跨 JVM 子进程 `--host`/`--join` hash 逐字节一致** |
| `core/net/RollbackEngine.java` | N4 回滚缓冲：按 tick 记录「应用前世界快照 + 所用输入帧」（按 playerIds 升序），`pruneBefore`/`pruneAfter` 环形裁剪控制内存 |
| `core/net/PredictiveSession.java` | N4 预测回滚会话：本地意图即时可知；远端意图延迟到达时用「重复上次确认远端意图」预测，权威帧与记录帧不符则 `restoreInPlace` 原地回滚 + 重演；复用 `World.snapshot()`/`restoreInPlace` 与原地恢复原语 |
| `core/sim/PredRollbackTest.java` | 第 45 道门禁 `PREDROLLBACK`（5 项断言）：延迟 D=4 终态==纯锁步 / 预测真错→真回滚 / 确定性重演 / 基线已缓存 |

#### (b) 三形态同码（§6 决策的兑现）

```
java -cp out core.net.NetMain --host     --port P [--seed S] --players N   # 本地服务：自己即权威 + 玩家
java -cp out core.net.NetMain --dedicated --port P [--seed S] --players N  # 纯中继（无头常驻，锁步下人人都是权威）
java -cp out core.net.NetMain --join 127.0.0.1:P                          # 纯客户端
```
三者只在「要不要起中继 / 要不要跑仿真」上有差别，握手/协议/输入路径完全一致——正是 §6「客户端模型 × 权威进程住哪是两个正交维度」的落地。

#### (c) 实测（第 44 道门禁 `UDPLOCK`）

| 断言 | 结果 |
|---|---|
| `UDP_TWO_CLIENTS_CONVERGE` | ✅ 真 UDP + 中继 + 2 客户端并行加入，N=20 tick 后 netHash 一致、无 desync |
| `UDP_THREE_CLIENTS_CONVERGE` | ✅ 星形中继在 3 人下同样成立，且与两端**同 hash**（人多人少不影响确定性） |
| `LATE_JOIN_REJECTED` | ✅ 人齐后的 HELLO 被拒（`relay.rejects > 0`）—— 晚加入 = 旧名单 = 必须响亮拒绝 |
| `SUBPROCESS_CROSS_JVM` | ✅ `--host` 与 `--join` 各起**真实 JVM 子进程**，FINAL hash 逐字节相同 `d978824b368f0c71`、且 `desync=false` |

> **跨 JVM 收敛的意义**：不只是"同一 JVM 里两个对象一致"（NETLOCK 已证），而是"两个进程、真网络、各自从种子重建世界，hash 仍相同"——本项目确定性卖点的终极形态。

#### (d) 途中踩的坑（写下来，下次不再犯）

1. **并行纪律**：握手要等人齐才发 WELCOME——客户端**必须并行 join**。第一版串行 join，第一个人等 WELCOME、WELCOME 要等第二个人 → 死锁，门禁以"加入超时"指出。
2. **`NotYetConnectedException`**：join 成功拿到 WELCOME 后才 `ch.connect(server)`，否则 recv 抛未连接异常。
3. **幽灵玩家**：join 重试每次新建 channel + relay 对重复 HELLO 不幂等 → 多出一个 `id=1` 的幽灵玩家。修：`channel` 在循环外建 + relay 重复 HELLO 幂等重发 WELCOME。
4. **`ProcessBuilder(List)` 别名 bug（最阴）**：Java 8 的 `new ProcessBuilder(List)` **存引用不拷贝**，两个 builder 共用同一 `base` 列表时，第二个 `addAll(--join)` 把 `--join` 也追加进 host 的命令行 → host 被解析成 join 模式（mode 覆盖）→ 全场没中继 → 双双超时。修：每个 builder 一份独立 `new ArrayList<>(base)` 拷贝。
5. **日志必须用文件**：stdout 管道在子进程被 `destroy()` 时静默吞 IO，跨进程门禁读不到 `FINAL` → `NetMain` 加 `--out` 双写文件，门禁从文件读。
6. **前向引用**：`cmdH.addAll(... "--out", fHost.getAbsolutePath())` 在 `fHost` 声明之前 → 编译期 `找不到符号`。修：文件在引用前创建。

#### (e) 还剩什么（诚实清单，N4 前置 → N4 已收口）

| 项 | 现状 | 下一步 |
|---|---|---|
| **每 tick 取快照性能** | ✅ **N4 已解决**：`World.ensureBaseline` 懒生成 + `baseMat/baseMass` 引用复用，窗口不动时零重生成；`baselineRegenCount` ≤3 实测（缓存生效） | — |
| **World.player 单数** | ✅ **N4 已绕过**：`PredictiveSession` 经 `InputSink` 按 `playerIds` 升序对位注入多玩家意图，不依赖 `World.player` 改复数 | — |
| **预测回滚未接** | ✅ **N4 已接**：`PredictiveSession` 驱动「本地预测 + 权威到达回滚」，复用 `World.snapshot()`/`restoreInPlace` 与原地恢复原语 | — |
| **接入渲染主循环** | ✅ **N5 已收口（2026-09-14）**：`Game` 由会话驱动完整整 tick，窗口照常渲染；单机分支逐字不变 | — |
| **房间/大厅服务** | ✅ **N5 已接（2026-09-14）**：`Game` 联机态经 `UdpRelay` 星形中继 + 本地 `LockstepSession`/`PredictiveSession` 驱动，与 `NetMain` 同构；“找房间/大厅 UI”层未做（可选，按需） | — |

#### (f) N4 实测（第 45 道门禁 `PREDROLLBACK`）

| 断言 | 结果 |
|---|---|
| `FINAL_EQUALS_REFERENCE` | ✅ 延迟 D=4 预测回滚终态 `netHash` == 纯锁步（D=0）参照 `netHash`（到达顺序不同，已确认历史演化结果不变） |
| `ROLLBACK_HAPPENED` | ✅ 预测与真实远端意图不同 → `rollbackCount > 0`（真回滚发生，非"延迟≠语义"假象） |
| `NO_ROLLBACK_STILL_CONVERGES` | ✅ 纯锁步参照 `rollbackCount == 0` 且终态一致（延迟 D=0 时本就无回滚） |
| `DETERMINISTIC_REPLAY` | ✅ 重演路径终态 `netHash` == 预测路径终态（确定性重演，无随机残留） |
| `BASELINE_CACHED` | ✅ `baselineRegenCount <= 3`（基线懒生成生效，消除每 tick 204ms 全窗重生成） |

> **N4 的意义**：纯锁步（N3）等齐所有输入才 advance（不卡但高延迟）；N4 本地意图即时跑在前面、远端意图延迟到达时用「重复上次确认意图」预测，权威帧不符则原地回滚重演——手感无延迟，且终点与纯锁步逐字节一致（确定性卖点的延续）。

### 24.6 N4 关键变更清单（零漂移已证）

- **`World.java`**：新增 `baseMat/baseMass/baseWinCX0/baseWinCZ0/baselineValid/baselineRegenCount` 字段；`ensureBaseline`（懒生成 pristine 世界数组，窗口原点变化才失效）/ `invalidateBaseline` / `copyBaseToMat` / `diffAgainstBaseline`（与旧 `diffAgainst(pristine)` 逐字节等价）；`captureWindowDiffs` 改为走缓存基线；`restoreWindowOrigin`·流式平移·`readInto` 原地去重均接 `invalidateBaseline`/`ensureBaseline`；新增 `World.snapshot()`（包 `save` 返回字节数组，不改动仿真状态）。
- **`StateCodec.java`**：`SKIP` 表新增 6 项（`baseMat`/`baseMass`/`baseWinCX0`/`baseWinCZ0`/`baselineValid`/`baselineRegenCount`）——它们是可重算缓存，非仿真状态，否则每快照膨胀 +23 MB（`NETSNAP` 预算 1 MB 因此失败，已修）。
- **`RollbackEngine.java`（NEW）**：`Frame{tick, intents[], snapshot[]}` + `TreeMap` 环形缓冲；`record`/`frameAt`/`snapshotAt`/`pruneBefore`/`pruneAfter`。
- **`PredictiveSession.java`（NEW）**：`InputSink`/`LocalIntentSource` 接口；`advance`/`receiveFrame`/`setAuthoritative`/`reconcile`/`rollbackTo`/`predictRemote`/`recomputeConfirmed`。
- **`PredRollbackTest.java`（NEW，第 45 道门禁）**：`SEED=0x9A77C0DE`、N=40、D=4、双玩家（本地即时 + 远端驱动）；与纯锁步参照对拍。
- **`SnapshotStateTest.java`**：`SKIP_DOC` 增补 6 项 + `RECORDED_SKIPPED` 19→25（门禁文档纪律，跳过不可能沉默发生）。
- **`build_runner.py`**：注册 `gate("PREDROLLBACK", ...)`（44→45 道 / 48→49 处退出码）；N5 追加 `gate("NETINTEG", "core.sim.NetIntegTest")`（45→46 道 / 49→50 处退出码）。四基线指纹逐字节不变（DET/ZD/NPC/MEGA/STREAMING 同 pre-N4 记录值）。

### 24.7 N5 落地：联机会话接入渲染主循环（2026-09-14）

N5 把「协议层（N0–N4）」接进真正的游戏窗口——`--host`/`--join` 不再只是 `NetMain` 无头驱动器，而是 `render/lwjgl/Game` 里可被玩家看见、可移动、可交互的联机对局。整套联机路线（N0→N5）至此闭环。

#### (a) 新文件 / 关键变更

| 文件 | 角色 |
|---|---|
| `core/net/TickBody.java`（NEW） | 整 tick 推进体接口：`void tick(World world)`——把"一整 tick 该跑什么"参数化，使会话既能驱动裸 `world.tick()`（无头/向后兼容），也能驱动含 `rules/techs/effects` 内容层的完整整 tick（渲染主循环 / 与单机演化逐字节等价） |
| `core/net/LockstepSession.java` | 新增带 `TickBody` 的构造（默认 `this.tickBody = world::tick;` 向后兼容 `NetMain`）；`advance()` 内 `sink.apply(t,arr); tickBody.tick(world);` 替换原 `world.tick();` |
| `core/net/PredictiveSession.java` | 同模式：加 `tickBody` 字段、保留默认构造、新构造注入、`advance()` 内 `engine.record(...); sink.apply(t, frame); tickBody.tick(world);` |
| `core/net/LockstepRunner.java` | 重载 `open(World, Joined, int inputDelay, TickBody tickBody)` → `new LockstepSession(..., tickBody)`；旧 `open` 保留（默认 `world::tick`） |
| `core/sim/NetIntegTest.java`（NEW，第 46 道门禁 `NETINTEG`） | 跨实例收敛 + 忠实包装 + TickBody 注入三断言 |
| `render/lwjgl/Game.java` | `main` 转发 `args`；`run(String[])` 解析 `--host`/`--join`/`--port`/`--seed`/`--players`/`--input-delay`（单机分支逐字不变）；联机态 `setupNetwork` 经 `UdpRelay`+`UdpTransport.join` 装配 `LockstepSession`（注入 `gameTickBody`），`loop()` 固定步长内由 `session.advance()` 驱动、`updateInput` 本地移动改按 tick 喂 `Intent.move`（`physicsTick` 固定步长、零帧 dt），`finally` 关 `relay` |

#### (b) 关键设计判断

- **单机整 tick = 会话整 tick**：`Game.loop()` 固定步长里那 4 行（`world.tick()` + `rules.tick` + `techs.tick` + `effects.tick`）正是 Game 版 `TickBody`；内容层（`RuleEngine`/`TechTree`/`EffectQueue`）**不在** `world.systems`（92 系统）内，故无头会话旧 `advance()` 只跑 `world.tick()` 会漏内容层——N5 用注入 `TickBody` 补齐，保证联机与单机演化一致。
- **本地移动确定性化**：`Player` 意图是 `Deque`（`setIntent` 入队、`World.tick` 消费 `pollIntent`），**无公开 `intent` 字段**；故网络态用自定义 `InputSink` 把 `arr[0]`（最低 id 意图，两端同序 → 确定性）同时 `setIntent`（供 `World.tick` 战斗/aggro，与单机一致）并暂存到 `applied[0]`，`gameTickBody` 先按 `applied[0]` 做固定步长确定性位移（`physicsTick`），再 `world.tick` + 内容层。单机态仍走连续 `physicsTick`（手感/重力/碰撞逐字保留）。
- **向后兼容**：`NetMain` 无头路径仍可用（默认 `world::tick`），跨 JVM 子进程门禁不受影响；三启动形态（`--host`/`--dedicated`/`--join`）语法不变。

#### (c) 实测（第 46 道门禁 `NETINTEG`）

| 断言 | 结果 |
|---|---|
| `CROSS_INSTANCE` | ✅ 两 `LockstepSession` 跨实例、N=30 tick 后 `simHash` 一致（`hashA==hashB`） |
| `WRAPPER_FAITHFUL` | ✅ 会话驱动终态 `simHash` == 直接 `world.tick()` 演化终态（`hashA==hashD`，`wD.tick==30`）；**首跑曾因逐 tick 相位错开假阳性失败**（回环投递时序使 t=1 时 `canAdvance()` 仍 false，`hA[1]` 记的是 tick=0 hash），改为比终态后通过 |
| `TICKBODY_INJECTED` | ✅ 注入带确定性副作用的自定义 `TickBody` 后两端仍一致且与默认 `world::tick` 不同（确被使用） |

> **N5 的意义**：联机不再是"无头验证工具"，而是玩家能真正进入的对局；且因 `TickBody` 把内容层一并驱动，联机演化与单机逐字节一致——确定性卖点从"协议层"延伸到"可见的对局"。

#### (d) 途中踩的坑（写下来，下次不再犯）

1. **`Player` 无公开 `intent` 字段**：初版误用 `w.player.intent` 做网络位移 → 编译失败；改为 `setIntent`/`pollIntent` + `applied[0]` 暂存（与单机"Deque 意图"语义一致，零漂移）。
2. **受检异常传递**：`setupNetwork` 声明 `throws Exception` 后，`run`/`main` 必须 `throws Exception`（rethrow 分析要求），否则编译失败。
3. **相位错开假阳性**：逐 tick 哈希比对会把"回环投递未推进"误判为"分叉"；`NETINTEG` 改为比终态 + 断言 tick 数一致，假阳性消除（不靠放宽判据）。
4. **`javac` 中文注释**：`Game.java`/`NetIntegTest.java` 含中文注释，编译须 `-encoding UTF-8`，否则 GBK 报错。

---

## 25. F4 · 三试炼地形门（2026-09-16 · 五基线指纹整体演进）

把 EVAL-3 的 F4 从「未做」落地：三座试炼点各刻一道**纯几何、零 rng**的地形门，且内容按距离外推。写 `mat`/`mass` 是首次在玩法层引入「确定性地形写入」，故五基线指纹整体改变（各自仍逐字节自洽，46 道门禁全绿）。

### 25.1 改动清单

- **`core/systems/TrialSystem.java`**：新增 `carveGate(World, sx, sz, ability)`，在 `place()`（tick==1，走 `World.simStream("trial:place")` 子流、不推进主 rng）后为每座试炼点刻 3×3 石环（2 格高），入口按能力改写：
  - `BOMB` = 石墙封口（`Blocks.STONE`，须 `Player.bomb` 炸开）；
  - `GLIDE` = 入口外侧 1 格浅阶（缓降入阶，STEP_HEIGHT 内不困人）；
  - `DASH` = 入口两侧补石墙夹出 1 格宽通道（窄隙长冲）。
- **`SITE_DIST`** 由 `{20,27,34}` 外推到 `{48,60,72}`（祭坛近 12~22、试炼点远，形成探索梯度）；`import core.world.Blocks` 已就位。
- **逻辑封印优先**：`Trials.abilityUsed` 仍是硬门（未真用过该能力 → 取不到遗物），地形门只作「风味 + 真实障碍」，不单独承担正确性 → 绝不软锁。

### 25.2 新基线指纹（重锁于 `orchestration/task_board.json`）

| 基线 | 旧值 | 新值 | 说明 |
|---|---|---|---|
| DETERMINISM | `43723456791b5364` | `74ad826636fe8eb2` | 全量世界哈希，地形写入必变 |
| ZERO_DRIFT | `34c8bb722c5a1a6f` | `5ce9392207387ebf` | simHash==refHash，两者同变 |
| STREAMING | `87d5bf1cecb4c628` | `87d5bf1cecb4c628` | 未变（门禁只校验自洽+建造持久化，哈希域未覆盖试炼地形） |
| NPC | `8560321416dc6ac5` | `afa036e4b0065f35` | 世界哈希含地形 |
| TRIAL | `202b17462c8791d4` | `8352df75e6dcd359` | 试炼确定性门禁 |
| MEGALITH | `3a08e840e0d16524` | `070a02ddb58ea0eb`（hashB=`f6eb884a0e1015f5`） | 特殊：hashA≠hashB 逻辑，门禁照过 |

> 注：§23/§24 的「四基线逐字节不变」是当时（2026-09-14）属实；F4 是 2026-09-16 经用户批准后的**首次有意演进**。门禁纪律要求「指纹一变 → task_board.json 同轮同步」，已执行。

---

## 26. EVAL-3 收官 · P1-1~P1-3 + 内容层接线 + 繁衍锚定（2026-09-16）

把 EVAL-3 全部致命伤（F1~F5）与内容层空转实证清掉。四件玩法/内容改动：

### 26.1 改动清单

- **P1-2 野兽尸体回收（实体层，零漂移）**：新增 `core/world/Corpse.java`（x,y,z,souls,beastType；补无参构造器供 `StateCodec` 反射回读）。`Player` 击杀野兽（`t.hp<=0`）时在其位置落 `Corpse`（souls 取 `b.maxHp/4`），玩家走回 1.5 格内 `+souls` 并 `w.log("combat","recover",...)`。不进窄 `hashState`。
- **P1-3 敌兵距离分档（实体层，零漂移）**：`BeastSystem.applyDistanceTier(World, Beast, dx, dz)` 确定性、零 rng——距出生点 >48 格的野兽 **+40% maxHp / +25% dmg / +15% speed**。内容表为空时不抽随机（既有种子刷怪序列零位移）。
- **`SocialSystem.tryReproduce` 站位依赖已修**：出生点从「依赖父母实时站位 `freeNear(a/b)`」改为「村心（`VILLAGE_RADIUS=24` 环）+ `World.simStream("social:birth:"+vs.npcSeq)` 派生的确定性环偏移」，每胎不同；删除 `freeNear`/`occupied` 两个随站位漂移的辅助方法。
- **P1-1 内容层接线 + C8 审计纪律**：`BeastDef`/`QuestDef` 已真消费（早前落地，本次确认）；`audit_invariants.py` 的 `CONTENT_KEY_EXEMPT` 由「blanket 理由」改为 **per-key 字典**，24 个未接线键（饥饿/风化/捕捉驯养/工业搬运/修仙飞升/技能增益/粒子特效/配方合成等尚未实现的子系统）逐条带精确理由登记、`C8` 审计每条打印 INFO，绝无静默死键。

### 26.2 基线影响

| 基线 | 值 | 说明 |
|---|---|---|
| DET / ZD / STREAMING / NPC / TRIAL / MEGA | 不变 | 尸体/分档不进窄哈希；繁衍在门禁窗口内未触发生育 |
| **SOCIAL** | `0dc8d8bef2718582`（原未变前的 `ed4b43107588cb41` 演进自 B/C/D 构建） | 仅 NPC 繁衍出生点重锚，已同轮重锁于 `task_board.json` |

> 门禁纪律：所有改动走「审计 → 编译 → 门禁」三段，46 道 `*_EXIT` 全 0、`AUDIT_EXIT=0`（FAIL 0 / WARN 0）。
> 本次**只有 SOCIAL 演进**（来自繁衍锚定），其余指纹与 F4 重锁后一致，无需再动。

### 26.3 途中踩的坑

1. **`StateCodec` 反射回读要无参构造器**：`Corpse` 初版只有 5 参构造 → 读档/联机回滚反序列化会失败；补 `Corpse()` 无参构造解决（`inst()` 先试 `newInstance()`，失败才 `Unsafe.allocateInstance`）。
2. **`simStream(name)` = `rng.deriveStream(name + ":" + tick)`**：同 tick 同流，**每胎必须用 `npcSeq` 进流名**才能保证子代出生点各不同；否则同 tick 多户生育会撞同一偏移。
3. **本机 Git Bash shim 坏 + `out/` 删除沙箱护栏**：构建统一走 `run_build.py`（`os.chdir` + `BW_KEEP_OUT=1` + 输出落文件），不再碰 shell 的 `cd`/`tail`。

### 25.3 验证

- `python build_runner.py`（经 `run_build.py` 启动器绕过本机坏掉的 `tail`/`cd` shim 与 `out/` 批量删除沙箱护栏 `BW_KEEP_OUT=1`）：审计 0 FAIL / 编译 0 / 46 道门禁 `*_EXIT` 全 0。
- `TrialDeterminismTest`：`sites=3 caches=4` 自洽、`gateReal`（不用能力→0 遗物）、`wardenReal`（守卫真挡终局）均 PASS。

---

### 27. P2-2 涌现杠杆·火种（2026-09-16）

- 把「火」从被动灾害变成玩家可主动驱动的涌现杠杆：新增 FLINT 能力（第 4 座祭坛 Shrine of Flame 授予）+ `Player.ignite(w,fx,fz)`。
- `ignite` 复用 `World.setBlock` 写路径（与 `bomb` 同）：正前方 1~3 格内放 FIRE，之后 FireSpreadSystem（沿 WOOD/LEAF）/ WildfireSystem（沿 GRASS）经 `simStream` 确定性子流自主蔓延——零玩家侧 rng。
- 输入边界：Game.java 键绑 `7`（55），`queuedIgnite` 同 `queuedBomb` 模式消费；审计 C9 加 `55:"7"` + `MenuModel.CONTROLS` 加「7  IGNITE」两处文档（C9 全绿）。
- 门禁纪律（determinism-gated-port 三证）：新增 `FireLeverTest`（FIRELEVER 门禁）——① 指纹隔离：ignite 不推进主 rng，两遍同种子自洽 `hashA==hashB`；② 零 RNG：火蔓延随机只来自系统 simStream；③ 可达性（负例护栏）：`peakFireA/B=23` 证火种真点燃并扩散（非「点了个寂寞」）。
- 零漂移结论：FLINT 是第 4 座祭坛（实体层，不进 hashState），写 mat 只在玩家实际引火时发生 → 既有 6 道基线（DET/ZD/STR/NPC/TRIAL/MEGA）+ SOCIAL 全部**未变**（与 F4 重锁值一致）；门禁数 46→47。
- 顺带修 P1-2 真缺口：野兽死亡原只拾取逻辑、从不落尸 → 在击杀点补 `w.corpses.add(new Corpse(...))`（无参构造供 StateCodec 反射；实体层不进 hashState）。
- 验证：审计 FAIL 0 / WARN 0 / 47 项；FIRELEVER_PASS（peakFire=23）；git 提交保护。
- 待续：P2-2 的「引水灌田 / 雷击导电」两个后续杠杆（火杠杆已落地，验证模型可照搬）。

### 27.1 火有牙 + 一次「静默假绿」事件（2026-09-16 晚）

**A) 火有牙（P2-2 补完）**：原火杠杆只烧地形、不伤任何实体 → 是「灯光秀」不是杠杆。现 `FireSpreadSystem` 在蔓延之后，对**身处火中的敌兵**每 tick 施加 `FIRE_DMG_PER_TICK=2`（20Hz→40 dps），
统一走 `Player.hitBeast(...)`「唯一一处伤害规则」（不另立伤害口径）。该段**不消耗 rng**（hitBeast 的扣血/升级/掉落皆确定性整数运算）→ simStream 序列不变、敌兵属实体层不进 hashState → **基线零漂移**。
`FireLeverTest` 扩为**五证**：① 指纹隔离 ② 零 RNG ③ 蔓延可达（peakFire=8）④ **伤敌（burnA/B=true）** ⑤ **伤玩家（hazardA/B=true）**。
**玩家也烧伤（火是双向危险，2026-09-16 深夜补完）**：新增 `Player.hurtByHazard(dmg, w)` —— 与 `hitByBeast` 的唯一区别是**不发放"完美闪避"奖励**：火是每 tick 判定的持续伤害，若复用 beast 路径，"站在火里翻滚"会把 `perfectDodges` 刷爆并白送反击窗口；拆成独立入口，让「唯一一处伤害规则」按**伤害来源**各自成立。i 帧仍免伤。

**B) 静默假绿事件（重要过程教训）**：`SocialSystem.java` 自 P1-2 重锚那次改动起 **一直无法编译** —— `birthSpot()` 使用了 `BIRTH_MIN_R`，但该常量声明的那次 Edit **未落盘**（commit 进去的也是缺声明的版本）。
后果：`build_runner` 的 CORE 编译 `CORE_EXIT=1`，javac **不产出任何 .class** → 门禁改为在**上一轮的陈旧 `out/`** 上跑，于是「47 道门禁全绿」是**假绿**。
- 真相是 `javac` 的 all-or-nothing：一个文件编译失败 → 整批不落地 → `out/` 残留旧类 → 门禁照样 PASS。
- 修复：补 `public static final float BIRTH_MIN_R = 6.0f;`（环带内半径），**清空 `out/` 后重建**（真·全量编译），47 道门禁全绿、**所有基线指纹与记录值逐一吻合**（DET 74ad826636fe8eb2 / ZD 5ce9392207387ebf / STR 87d5bf1cecb4c628 / NPC afa036e4b0065f35 / SOCIAL 0dc8d8bef2718582 / TRIAL 8352df75e6dcd359 / MEGA 070a02ddb58ea0eb）。
- 结论：**只 grep 门禁 `*_EXIT` 会被假绿骗到；必须同时核 `CORE_EXIT/SOFT_EXIT/AUDIO_EXIT/LWJGL_EXIT`**，且对结果存疑时一律「清 `out/` 重建」。BIRTH_MIN_R 的锚定逻辑此前从未真正运行过（旧类里还是 `freeNear`），现首次生效。

---

### 28. P2-2 收官：水 / 雷两杠杆（2026-09-16 深夜）

P2-2「涌现系统变玩家杠杆」三杠杆全部落地（火见 §27 / §27.1）。水、雷两杠杆同样遵循「**底层涌现系统已在 → 只补玩家触发**」：

**A) 水杠杆 —— `AQUA` / `Player.divert(w,fx,fz)`（引水灌田）**
- 正前方放一格 `WATER`；随后**确定性**灌溉相邻 `DIRT`：邻水且上方为 `AIR` 的田立即长出 `LEAF` 作物（判据与既有 `FarmSystem` 完全一致，走 `hitBeast` 之外的独立写路径，零 rng）。
- **为什么不"只放水、等系统自己发现"**：`IrrigationSystem`/`FarmSystem` 每 tick 只**随机采样 24 列**（世界数千列），玩家刚浇的地要等很久才被抽中 → 反馈太弱、不成杠杆。故把同一判据在**玩家动作**上立刻跑一遍；那两个系统仍按自身节奏在别处涌现 → **即时反馈 + 世界慢涌现并存**。

**B) 雷杠杆 —— `THUNDER` / `Player.thunder(w,fx,fz)`（雷击导电）**
- 正前方落雷：命中 `WOOD/LEAF` 即点燃（与既有 `LightningSystem` 同判据）；对落点半径 3.5 格内敌兵电击（22 伤害，走 `hitBeast`）；**带 40 tick 冷却**防连点。
- **导电**：若落点邻接 `WATER`，电流沿**连通水域 BFS**（≤64 格）传导，把电击覆盖铺到整片水面 → 把敌人引到水边/水里再引雷，一击放倒一片。
- 与 `LightningSystem` 的自然落雷**并存、互不干扰**（本能力是玩家主动引雷）。
- **零 rng**：纯 `setBlock`/`editBlock` + `hitBeast`，不消耗 simStream。

**C) 接线与门禁**
- 祭坛：第 5、6 座（Shrine of Tides / Storms）授予 `AQUA`/`THUNDER`（实体层，不进 hashState）。
- 键位：`J`(74) 引水 / `L`(76) 引雷；审计 C9 加 `74:"J"`/`76:"L"` + `MenuModel.CONTROLS` 两处文档（C9 全绿）。
- 新门禁 `ElementLeverTest`（ELEMENTLEVER，`2d769580c7b4693f`）：确定性自洽 + **水可达**（watered）+ **雷伤敌可达**（shocked）+ **导电可达（负例护栏）**（conducted：距落点 9 格的敌兵仅因站在水链上才被击中）。
- 回归：**48 道门禁 `*_EXIT` 全 0**（编译段 AUDIT/CORE/SOFT/AUDIO/LWJGL 全 0）；所有项目基线指纹**未变**。

---

### 29. 画面升级 · 火光照明（2026-09-16 深夜）

- **背景**：标准美术 pass 早已落地（见 `ART_BIBLE.md` §8：每体素颗粒 / 双光 `0.72*key+0.28*hemi` / 花田色盘 / 调色+暗角 / AP3 把夜环境光 0.20→0.26）。本次是**新增视觉轴**，不是重做。
- **缺口（规则 2 症状表之外）**：块光 BFS 只从 `LAMP` 取源 → **`FIRE` 不发光**。于是 P2-2 玩家放的火在夜里是"不亮的火"，燃烧森林毫无存在感。
- **改法（纯渲染层）**：`World.computeLight` 增加第二类源 `FIRE`（半径 `FIRE_LIGHT_R=9` < `LAMP` 的 14 → 火是局部暖光而非照明灯）。**未改一行 GLSL** —— 靠 worldShader 既有的 `vLamp` 项自然产生暖光，规避无 GL 沙箱下的 shader 编译风险（技能规则 4）。
- **顺带修既有隐患**：`computeLight` 直接读惰性索引 `typeCells` 却未 `ensureIndex()` → 刚放下/蔓延的灯/火会漏光（LAMP 同吃）。已补。
- **证明**：编译段全 0、48 道 `*_EXIT` 全 0、**所有基线指纹逐字节不变**（`lightGrid` 是渲染派生缓存，不进 hashState）；一次性探针实测火格 0.643 / 逐格衰减 / LAMP 1.000 互不覆盖。
- **未做（待本机目视/截图再定，不凭感觉改）**：① 火光的暖色更贴火块本色；② 雨天 sky 压暗 0.78 是否过猛；③ 更进阶的（水/玻璃透明、真阴影贴图、bloom）都需新增 GL 对象/FBO，风险高，未擅动。
- **已知局限**：本项目**渲染层无门禁**（48 道全在 sim 层），故火光这类视觉特性靠"派生缓存 + 全量零漂移"而非专项断言守护。

---

### 30. 画面进阶 · 渲染安全网 + 大气/光照升级（2026-09-16 深夜）

用户要"继续进阶"。**先上保险，再改 shader**：

- **A) 渲染安全网（基础设施，最重要）**：`Game.makeProgram` 原先在 GLSL 编译/链接失败时 `throw` → **一个 shader 错就把用户的游戏搞崩**，且无任何线索。改为：捕获 → `System.err` 打印真实 GLSL 日志 → **返回最简兜底 program**（VP 变换 + 顶点色）。从此进阶 shader 的风险从"崩游戏"降为"画面变素 + 一行日志"。
- **B) worldShader 三处进阶**：① 太阳侧**边缘散射 rim**（迎光轮廓暖边，出体积感）；② **谷地积雾**（低于相机的地形雾更浓，拉空间层次）。③ 沿用既有 `vLamp` 不新增 uniform。
- **C) skyShader 一处进阶**：太阳方位**地平线暖光带**（日出日落时该侧更暖更亮）。
- **无 GL 自检**：写一次性脚本抽取 shader 字符串 → 查 `()/{}` 平衡 + 与 `git HEAD` 比对**函数名集合**（确认零新增 GLSL 构造，技能规则 4 机械化），跑完即删。
- **证明**：48 道 `*_EXIT` 全 0（AUDIT/CORE/SOFT/AUDIO/LWJGL 全 0）、无编译错误、**所有基线指纹逐字节不变**。
- **未做（须本机 GL 验证，别在无 GL 环境赌）**：水/玻璃**透明**（需拆 chunk mesh + 独立混合通道）、**真阴影贴图**（FBO + 深度 pass）、**bloom**（FBO + 模糊 pass）。有安全网后它们"写错也不崩"，但正确性只能本机看。

---

### 31. 画面大件 · 水/玻璃透明（双 pass 网格 + 剔透规则，2026-09-16 深夜）

用户要「继续上大件」。先侦察后动手，结论：**水透明的问题不在混合，在剔除** —— 面剔除只看 `mat==AIR`，
所以水面之下的地面根本没有顶面；水一透明就会看穿成空洞。于是这次改的是**规则**，不只是混合。

- **1) `Blocks.Block.translucent`（新标记，纯渲染）**：仅 `WATER` / `GLASS` 为 true；**索引顺序不变**。
- **2) `core.world.FaceCull`（新类）**：剔除规则抽成 core 纯函数 —— 邻居 AIR/越界 → 可见；邻居**半透明且与本方块不同类** → 可见（看穿）；其余剔除。放 core 是为了能被 `core.sim` 门禁守护（render 层在 CORE 编译阶段尚不存在，够不着）。
- **3) `Chunk` 双缓冲**：每块两组 VAO/VBO（不透明 / 半透明），按 `translucent` 分区；**逐面与贪婪两条路径都分区** → `tools/GreedyCheck` 仍逐面等价（70356 单位面一致，合并省 69.7% 顶点）。
- **4) `Game` 透明 pass + shader**：不透明画完再画透明 —— 开混合 / `glDepthMask(false)` / **按块心距由远到近**；worldShader FS 的 `discard` 阈值 `0.5→0.02`（只留真镂空）、末行 `fc=vec4(col,tx.a)`。含水的区块可能 `faceCount==0` 而 `faceCountT>0`，两个都要判（否则纯水区块整个被跳过）。
- **顺带修掉一个既有 bug（本机可见）**：`TextureAtlas.vertexColored` 未含 `WATER` → 水一直按**灰度**渲染（`Blocks.WATER` 的蓝 58/123/208 从未生效）。已纳入 → 水恢复本色；水体 alpha 设 `0.60`。
- **新增门禁 `MESHCULL`**（`core.sim.MeshCullTest`，12 断言）：石上水面→石头顶面可见 / 水-水内部面剔除 / 水底面被石头遮挡 / 玻璃后石头顶面可见 / 不透明邻居仍遮挡（回归线）/ 越界与世界顶按空气 / `translucent` 分类唯一（全注册表只有水与玻璃）。
- **新增工具 `tools/MeshPassCheck`**（手工，同 GreedyCheck 惯例）：场景世界下两路径等价 + 分区完备 + 池底透水可见 + 水-水内面剔除。
- **证明**：`run_build.py` **49 道门禁 54 处 `*_EXIT` 全 0**（AUDIT/CORE/SOFT/AUDIO/LWJGL 全 0；逐条核对无缺漏）；`MESHCULL` PASS；**所有基线指纹逐字节不变**（纯渲染层派生缓存，不进 `hashState`）。
- **已知局限（须本机目视，不凭感觉改）**：① 世界关闭背面剔除 → 水中正/背面叠加混合，实感比 alpha 名义值更「实」，`alpha` 是唯一旋钮；② 排序粒度是**块**，同块内水与玻璃相接处可能有轻微顺序错（大片水体不受影响）；③ 玻璃仍是「框 + 高光、内部镂空」，它的改进是**不再遮挡其后几何**。
- **仍未做**：真阴影贴图（FBO + 深度 pass，会吃帧）、bloom（FBO + 模糊 pass）。

---

### 31.1 补充（同日）：alpha 收敛为单一来源 + 无 GPU 自检闭环

- **单一来源**：水体 alpha 原先是「`TextureAtlas` 里写 `0.60` + 软件渲染器另写一个」，违反「同一概念两处定义」（审计 C2）。
  已收敛为 `Blocks.WATER_ALPHA`，GL 图集与软件预览都取它 —— **调水浓淡只改这一个数**。
- **无 GPU 自检工具**：`render.software.SoftwareRenderer` 现在 ① 面可见性改用 `core.world.FaceCull`（成为该规则的**第二个独立实现**）；
  ② 半透明方块按 alpha 混合；③ 支持 `-Dwater.alpha=` 出图 A/B；④ 新增 `lake` 预览场景（挖浅湖灌水）。
- **看图定档**：`0.30` 太透（读成湿坑）/ `0.85` 太实（水底被盖掉）/ **`0.60` 平衡** → 保留 `0.60`。
- **边界**：CPU 平面着色、无 GL 着色器（光照/雾/块光/暗角）与分块排序 → 能判构图、几何、透明度量级，**不能替代最终 GL 观感**。
- 用法见 `docs/ART_BIBLE.md §13`。

### 32. 渲染优化 · 索引化绘制（顶点 −33%）+ 贪婪合并的量化裁定（2026-09-16 深夜）

用户「你自己开始优化吧」。先**量化再动手**（新工具 `tools/FaceCostProbe`，按真实窗口 160×112×160 统计）：

```
旧规则(邻居==AIR) 单位面      : 366,430
新规则(FaceCull) 单位面      : 369,661   增量 +0.9%      ← 透明改动几乎免费
  其中 看穿补面(水下地面等)  :   3,231
逐面路径 顶点数              : 2,217,966
贪婪合并 四边形数            :   141,346  → 顶点 848,076   可省 61.8%
```

**做了：索引化绘制（零视觉变化的等价优化）**
- 每四边形由「6 顶点 (V0,V1,V2,V0,V2,V3)」改为「**4 顶点 + 6 索引 {0,1,2, 0,2,3}**」——逐三角形完全一致，是**几何等价**而非"看起来差不多"。
- 收益：顶点着色调用 −33%、顶点缓冲字节数 −33%（52B×6 → 52B×4）。
- 落点：`Chunk` 两个 pass 各加一个 EBO + `Chunk.quadIndices(n)`（纯函数、可无头测）；`Game` 绘制改 `glDrawElements`；三个顶点发射器（`Game.emit` / `Chunk.emitQuad` / `Chunk.crossQuad`）统一改发 4 顶点。
- **运行时不变量**：`upload()` 校验「顶点数 == 四边形数×4」——任一发射路径忘了去掉重复顶点会**当场报错**，而不是让 GPU 读越界索引。
- 顺带修一个**潜伏 bug**：`rebuildGreedy` 的「按半透明分区」上一轮**漏改**（Edit 静默未落盘），因 `USE_GREEDY=false` 是死代码，编译期不报 → 一旦开启贪婪，水会落进不透明 pass。已补。

**没做（量化后裁定"不能盲开"）：贪婪网格**
省 61.8% 顶点很香，但有三个必须先解决的点：
① 合并大四边形的 **UV 会拉伸**（图集是绝对 UV，无法直接 wrap，需改顶点格式 + FS 里做 `fract`）；
② **FLOWER 在贪婪路径被当立方体**（逐面路径渲染成十字草）→ 会变成方盒；
③ 植被逐块色调丢失（`vegTint` 取基底方块坐标）。
这三点都**无法在无 GPU 环境验证观感**，故只量化留档，不擅自开启。

**未验证项（须本机）**：索引化在真实驱动上的净收益（顶点着色通常 −33%，但若瓶颈在片元/带宽则提升有限）。**帧率请以本机为准。**

### 33. 画面细腻度批次 · 纹理图集尺度修正 + 微糙层 + MSAA 4x（2026-09-16 深夜）

用户「帧数其实还可以，但可以优化一下画面细腻度」。这次靠**新长出来的眼睛**（导图集看图）驱动：

- **发现真错**：`TILE_PX` 早先由 16 提到 64，但 `paintTile` 的花纹仍是 16px 尺度 → LAMP 整块黑（辉光系数算成负数）、
  FIRE 下半黑、GLASS 框错位+斜纹多 4 倍、CACTUS 棱过密、STONE/SAND 细到像平板、GRASS 草皮看不见。
- **修法**：所有花纹改用**图案坐标** `sx = x / S`（`S = TILE_PX/16`）+ 两层噪声
  （`N`=块面尺度、`n`=全分辨率微糙 ±3~8%）+ 越界/负值钳制（LAMP 加下限、FIRE 渐变归一化）。
  微糙层正是 64px 相对 16px 的**真实细腻度红利**。
- **自检工具 `tools/AtlasDump`**：无头导出图集全览 + 重点方块 3× 放大表；走 `TextureAtlas.bakeAlbedoOffscreen()`
  （与 GL 上传同一套代码），并改成**堆缓冲**→ 审阅路径零原生依赖、无 GPU 也能出图。
- **MSAA 2x → 4x**：体素边缘抗锯齿；帧数有余时优先细腻度（掉帧可降回 2）。
- **验证**：49 道门禁 54 处 `*_EXIT` 全 0、逐条核对无缺漏、所有基线指纹逐字节不变（图集是纯渲染派生物）。

---

### 34. A/B/D 三批：技能链 · 技能特效专属化 · 战技扩展（2026-09-16）

**背景**：用户问「动作系统 / 战技系统 / 技能特效能做到吗」。盘点发现底层大半**已存在**
（`core.anim` 完整 + 判定帧驱动的攻击状态机 + `ParticleSim` + `EffectQueue` + `Game` 全实现 `EffectSink`），
但链子断了 5 处。分三批接上。

#### A 批 · 技能链（此前整条链是死的）
- **根因**：`core.systems.ContentSystem`（注释自称「内容层唯一一座桥」）**从未被实例化**——
  Game 在渲染层自己 new 了一个裸 `EffectQueue` 并手动 tick。于是 `ContentSystem.cast` **零调用**、
  `skills/*.json` 全是死数据。
- **接线**：Game 改持 `ContentSystem`（取代裸队列，消灭这处重复抽象）；新增键位 **O 切换技能 / P 施放技能**
  （数字键 1-9 已被 hotbar 占用 → 与战技 `R/G` 对称地各占一个字母键）。
- **targeting 真实现**（新增 `core.content.SkillDef`）：`sphere/radius/maxTargets` 此前**无人读**，
  `simulate(DAMAGE)` 走的是 `nearestBeast(w,5.0f)` 单目标硬编码 → Flame Burst 名义「半径 4 打 6 个」，
  实际只打最近 1 个。现在 `SkillDef.selectTargets` 确定性选目标（距离升序、同距按 `beasts` 下标 → 无随机 tie-break）。
- **锚点展开**：`cast` 按 `at`（其次 `target`）分派——`TARGET` 逐目标提交、`SELF/ORIGIN` 单份；
  「空放」也以玩家位置提交一份（有观感），且体力/冷照扣（空放有代价）。
- **buff 运行时**（新增 `core.systems.BuffSystem`）：`APPLY_BUFF` 此前落到 `simulate` 空分支被**静默丢弃**
  （`burning` 从未生效）。现在 DOT 按 `tickEvery` 触发、`modifiers.speed` 经 `Beast.slowMul` 生效、
  到期自动恢复原速。**与火杠杆的分工写进注释**：FireSpread＝站在火里（空间/及时），
  BuffSystem＝身上着了（时间/持续）；两者都只经 `Player.hitBeast` 一条伤害入口。
- **门禁 `SKILLCAST`（15 断言）**：DET（施法不扰动指纹）/ targeting 3 中 3 + 半径外**一只不打**（负例护栏）
  + 8 只截断到 6 / cast 三道闸（未学·体力·冷却）/ 体力 78 与 CD 1.4 / FANOUT（TARGET 锚点 3 命中 → DAMAGE×3 + FX×3）
  / BUFF 掉血 3·减速 0.85·到期停止。

#### B 批 · 技能特效专属化
- **`fx` 的三个键此前被忽略**：emitter 的 `at`（偏移）与 `delay`（时序）全部瞬间喷完、复合特效自带的 `sfx` 没播。
  现在 `delay` 走新增的**延迟发射队列**逐帧释放（先炸开 → 再冒烟 → 最后余烬），`at` 作为相对偏移，`sfx` 真播。
- **三个技能各有专属特效**：新增 `fx/gale_step.json`（叶片环 + 尘 + 光点）、`fx/ember_harvest.json`（火星 + 光点 + 尘）；
  `fx/flame_burst.json` 加余烬 emitter；`skills/gale_step|ember_harvest` 由裸 `SPAWN_PARTICLE` 改用 `SPAWN_FX`。
- **`anim` 键落地**：施法时按 `SkillDef.anim` 决定手持物摆幅（"roll" 类位移技更大更长）。

#### D 批 · 战技扩展
- 新增第 4 个战技原型 **CHARGE（蓄力重击）**：与回旋斩同骨架，但击退 **1.2 → 2.0 格** —— 定位「控制」而非「清场」。
- 新增第 6 把武器 **Great Maul**（atk12 / 倍率2.40 / 范围3.2 / 耗体40 / CD1.90），自带 CHARGE。
- **不可支配性**（`NO_DOMINANCE` 逐对 30 对）仍成立（手工核验 + 门禁通过）：它与 Rune Edge 互为制约
  （后者伤害更高但更贵更慢）、与 War Axe 互为制约（后者更便宜更快但范围/击退更小）。
- `WeaponArtTest` 同步：`COUNT` 5→6、新增 CHARGE 行为断言（2 命中 + 范围外不动 + 击退实测 **2.0** 格）。

#### 验证与纪律
- **50 道门禁 / 55 处 `*_EXIT` 全 0**；`SYSTEMREG` 因新增第 93 个系统 `BuffSystem` 重锁 golden
  （`-6666983850743452865` → `6156519497891339936`，92→93；**末尾追加**不动既有相对顺序，
  且它无 buff 时首行即返回 → 对既有种子零影响）。
- **所有仿真基线指纹逐字节未变**（DET `74ad8266…` / NPC `afa036e4…` / SOCIAL `0dc8d8…` / TRIAL `8352df75…` /
  MEGALITH `070a02dd…`）—— 技能/战技/特效全在实体层与渲染层。
- 审计 **`FAIL 0 / WARN 0`**：按 C8 纪律把已真接线的键从豁免表**移出**
  （`targeting`/`radius`/`maxTargets`/`at`/`modifiers`/`tickEvery`/`anim`/`sfx`）—— 24 键 → **16 键**，表未变脏。
- 键位文档：`KEYCODE_NAME` 加 `79:"O"`/`80:"P"`，`MenuModel.CONTROLS` 加两条（C9 全绿）。

---

### 35. C 批 · 骨骼动画：把 `core.anim` 的链路验证到「能看见」（2026-09-16）

**背景**：`core.anim` 的算法层早已完备（`AnimTest` 覆盖 插值/缓动/循环/混合/事件帧/骨架世界变换/确定性，
`AnimLayersTest` 覆盖 root-motion/混合树/上下半身遮罩），而且**已经在驱动渲染** ——
第一人称手持物的挥砍进度 `Player.attackSwing()` 就是 `atkAnim.animator().time / totalLen()`。
缺的只是**第三人称角色模型**的骨骼化，而那是纯外观改动、**必须人眼验证**。

**本轮交付（可验证的部分）**
- **内容化人形骨架** `assets/models/humanoid.json`：12 骨骼
  （root → pelvis → chest → head/armL·R → handL·R → legL·R → shinL·R）
  + 3 段动作（`idle` 2s 循环 / `walk` 1s 循环 / `swing` 0.5s 非循环，带事件帧 t=0.16）
  + 9 个部件（`ModelDef.parts`：bone → box → material）。骨架 / 动作 / 模型**同一文件**
  （Gson 各自忽略无关字段）。
- **无头姿态预览 `tools/PosePreview`**：把渲染层该走的那条链完整跑一遍 ——
  `AnimJson.parse → Animator(time) → Animator.apply(skeleton) → Skeleton.updateWorld() → ModelDef.parts 装配`，
  再用正交 3/4 投影把各关节的方块画出来（`proof/pose_preview.png`，11 帧并排）。
  它是骨骼动画的**软件替身**：证明数据与变换是对的；GL 那边只是把同样的
  `joint.worldPos / worldRot` 喂给顶点缓冲。
- **三条数值自检**（不依赖看图）：
  - `WALK_ALT` —— walk 的左右腿在相位上真的反号（t=0 时 +28 / −28，t=0.5 反号）；
  - `HIERARCHY` —— 旋转 `armR` 后子关节 `handR` 的世界 Y 从 `1.074 → 1.380`（层级真的在累积）；
  - `DET` —— 同 `(clip, time)` 两次采样**逐位相同**。

**看图结论**：`idle` 站立稳定 → `walk` 双腿前后交替 + 手臂反向摆 → `swing` 手臂抬举前挥。
**姿态、层级、比例、时序均正确。**

**已知边界（诚实声明）**：`PosePreview` 用正交投影 + 单色填充 + 画家顺序，**不含** GL 的光照/阴影/描边，
故它验证的是姿态与变换、**不是最终观感**。把 GL 第三人称模型换成这套骨骼姿态属「最后一公里」，
需要本机人眼确认（本机无 GPU，改了看不见）—— 这也是本轮**没有**盲改 `drawCharacterOverlay` 的原因：
现有角色几何我看不到、改坏也自检不出，不如把「内核已可验证」这件事做扎实。

**纪律**：本轮**未改任何 `src/` 代码** → 50 道门禁与所有基线指纹不受影响；审计 `FAIL 0 / WARN 0`。

---

### 36. 修「掉出世界」（里世界）+ HUD 定位行（2026-09-16，用户实测报障）

**报障**：用户「我怀疑我掉进里世界了，一点方块都看不见」。

**根因**：玩家挖方块路径 `Game.doDig()` **无条件** `world.editBlock(..., AIR)`，而 `editBlock`
对**世界底层外壳 `BEDROCK` 没有任何保护** —— 可以从地底一路挖穿掉出世界：此后永远下落、四周再没有任何方块。
项目里 `Player.bomb` 有"非基岩"过滤，但**挖方块路径没有** —— 典型的「同一概念只在少数调用点判断、其余漏掉」。

**修法（一处定义胜过逐点判断）**
1. **不可破坏收敛到唯一写路径** `World.editBlock`（挖 / 放 / 炸 / 引水 / 引火 / 刻地形全走它）：
   目标是 `BEDROCK` 时**拒绝**并返回 `false`。世界生成走 `setBlock`，不受约束（生成期必须能写基岩）。
2. **调用方必须给反馈**：`doDig` 判返回值 → 被拒时提示 `UNBREAKABLE` + 拒绝音效 + **提前 return**
   （此前无条件继续，会「明明挖不动却照播音效、照样 +1 物品」）。
3. **虚空兜底**：`Player.rescueFromVoid` —— `y < -1` 时拉回村心。爆炸 / 落雷 / 将来地形生成出空隙
   都可能再产生越界路径，这是最终保险。只在真越界时触发 → 零漂移。

**门禁**：`StreamingTest` 新增第 ③ 条断言（它本就在测 `editBlock` 的持久化语义）：
`bedrockKept`（生成可写 / 玩家被拒 / 改后仍是基岩）+ **负例** `normalEditable`
（普通方块必须仍可编辑 —— 防「一律拒绝」把功能改死）。实测 `STREAMING PASS`，hash 仍 `87d5bf1cecb4c628`。

**顺带（用户另一条反馈「不知道自己在哪」）**
- HUD 罗盘下方新增**定位行**：`X 128  Z 96   VILLAGE 42m NE`（坐标 + 到村心距离 + 八向方位）。
  来由：罗盘此前**只有刻度与色点**（绿点=村心、橙点=祭坛），新手读不出来；色点也不说明自己是谁。
- 村心绿点补 `V` 标签（与祭坛的"能力首字母"同风格）。
- `compass8` 是纯函数（约定 `-Z` 为北，与罗盘 `N` 刻度同约定），已用 10 个 case 验算（8 向 + 两个 22.5° 边界）。

**验证**：50 道门禁 / 55 处 `*_EXIT` 全 0；审计 `FAIL 0 / WARN 0`；所有仿真基线指纹逐字节未变。

---

## 37. 空转参数落地 + 死内容键清零 + 引擎调参 + 美术续批（2026-09-17 · 零指纹变更）

**一句话**：把「预设里被点名、背后却没有任何系统」的 7 个参数与 3 个配方键全部接线，
并**在没有移动任何仿真指纹的前提下**完成 —— 只重锁了 `SYSTEMREG` 的黄金序列。

### 37.1 先修了一个把粒子系统整个打死的真 bug

`ParticleSim.spawn` 把 `offset` 当**二元区间**读（`range()` 只返回 2 元）却按 `[dx,dy,dz]` 索引
→ **每次 spawn 必抛 `ArrayIndexOutOfBoundsException: 2`**。

- 后果：`FX` 门禁长期 `EXIT=1`，且**游戏内粒子全不显示**（技能特效、灰尘、余烬都没了）。
- 为什么潜伏：`build_runner.py` 的 **RC 恒为 0**，门禁红灯不反映在退出码上 —— 只看 RC 会以为全绿。
- 修法：新增 `vec3()` 读三元向量。**「向量」与「区间」是两种语义，必须分开读**（审计 C8 的思路同样适用于此处）。

### 37.2 七个空转子系统参数 → 全部有牙（新增 5 个系统，93 → 98）

| 参数 | 落地系统 | 行为 | 出厂默认 |
|---|---|---|---|
| `erosionRate` | `ErosionSystem`（改造） | 缩放「水蚀 DIRT→SAND」概率；**只缩放概率、不改变抽样次数** → 不位移任何子流 | `1.0` = 改动前 |
| `ascensionThreshold` | **`AscensionSystem`** | 道行（`Player.souls`）越阈即飞升：气血上限 +40、道行归零、记村志 | `1e9` 不可达 |
| `haulRate` | **`HaulSystem`** | 无人搬运：采样找矿石 → 搬进村心半径 5~8 仓储环 + `builtMass`；**需电力** | `0` 不搬 |
| `hungerRate` | **`HungerSystem`** | 饱食度递减；归零后每 2 秒扣 1 血（走 `hurtByHazard`）；药瓶补给回满 | `0` 不饿 |
| `orbItemCost` | **`CaptureSystem`** | 收编消耗捕捉球数（物品 `orb`） | `1` |
| `hpThreshold` | **`CaptureSystem`** | 收编的血量比门槛（Palworld 式「先削弱后投球」） | `1.0` |
| `wireRange` | **`WireSystem`** | 灯具导线网通电半径（切比雪夫有界 BFS）；**煤矿紧邻灯具 = 发电炉** | `0` 不建网 |

- **零漂移的三条设计原则**：① 出厂默认 `update()` 首行返回（零 RNG、零写入）；
  ② 全部**追加在注册序末尾**（既有 93 个相对顺序不变）；③ 参数落在 `World.config`（`WorldConfig`），
  并显式加进 `StateCodec.SKIP` + `SNAPSTATE.SKIP_DOC`（**配置不是演化量**）。
- 落点：`Simulation.applyPreset` 真正消费这 7 个键（开头 `world.config.reset()` 防跨预设泄漏）。
- 玩家入口：**`Y` = 投捕捉球**（`Game.queuedCapture` → `CaptureSystem.tryCapture`）；
  CONTROLS 页 + 键位审计 C9 同步；新规则 `rules/capture_ammo.json`（击退野兽 50% 掉 1 球）让球真有来源。
- **新门禁 `SUBSYS`**（9 断言）：`DEFAULT_NOOP` + 每参数一条「有牙」断言 + 负例 + `PARAM_RESET`。
- **`SYSTEMREG` 有意重锁**：`COUNT` 93→98、`GOLDEN_HASH` → `9078168720030413075`；
  `PRESET_PARAMS` **断言反转**（原断言「必须有未接线参数被点名」，现断言「不得再出现 `?param=`」
  且参数真落到 `WorldConfig` —— 防「只改了个没人读的字段」这种假接线）。

### 37.3 三个配方键 → `RecipeBook` 显式契约

`coal`/`ore`/`iron_bar` 此前由 `TechTree` 当**不透明字符串**消费：键名写错、物品不存在、
数量不足**都没有任何提示**（静默 `continue`）—— 这正是 C8 把它们记成「死键」的根因
（运行时确实读了，但源码里连一次字面引用都没有）。

新增 `core/content/RecipeBook`：声明三个资源键的语义 + 标准配方 `ore 3 + coal 2 → iron_bar 1`；
`TechTree` 改为经它**校验** cost/unlocks，未知键进 `recipeIssues`（**响亮记录**）。
**新门禁 `RECIPE`**（6 断言：真实物品 / 键集合 / 可结算 / 真扣真产 / **不足则整条不结算** / 未知键响亮）。

→ 审计 `CONTENT_KEY_EXEMPT` **清空 `{}`**（这是该表应有的终局状态：接线一个删一个，删到空即全部落地）。
C8 现在报「123 个内容键：未接线 0 个」。

### 37.4 引擎调参（实测驱动）—— 含一次**方法论纠错**与一个真热点修复

新增三个人工运行探针（均非门禁）：`core.sim.PerfProbe`（tick 成本 + 分块预算）、
`core.sim.AblationProbe`（消融归因）、`tools/WriteCostProbe`（单次写入成本）；
逐系统计时沿用既有 `tools/SimPerf`。

**① 方法论纠错（我自己的第一版是错的）**：第一版 `SystemPerfProbe` 把每个系统**连续调用 60 次
而不推进 tick** → `w.tick` 不变 → `simStream(name)` 返回**同一个 RNG**，世界状态被冻结，
系统在同一状态上反复写块 → 给出 `lava 8.13ms(47.6%) + flood 3.83ms(22.4%)` 的**假热点**。
该探针已删除。**教训：性能测量不能把被测对象从真实上下文里拿出来单独跑 —— 系统是状态机，
脱离 tick 推进就没有意义。** 正确做法是**消融**（关掉一个系统，看总时间少多少）或
**在真实 tick 循环内逐系统计时**（`tools/SimPerf`，其自检断言"探针循环 == 真 tick 的 hash"）。

**② 正确归因（消融法，160x112x160 / 98 系统，修前）**：

| 关掉的系统 | tick | Δ（省下） |
|---|---|---|
| （不关，baseline） | 17.874 ms | — |
| **`ash`（火山灰沉降）** | **4.188 ms** | **13.687 ms（76.6%）** |
| `sand` | 15.861 ms | 2.014 ms（11.3%） |
| `pond` | 16.445 ms | 1.430 ms（8.0%） |
| `vine` | 16.764 ms | 1.110 ms（6.2%） |
| `flood` | 16.829 ms | 1.046 ms（5.8%） |
| `lava` | 17.574 ms | 0.300 ms（**1.7%**） |

> 另一个反直觉信号：关掉 `flower` 反而让 tick 涨到 **47.1 ms**（Δ = **-163%**）——
> tick 成本**高度依赖世界状态**，不是「某几个系统的固有开销」。

**③ 根因（已定位）**：`setBlock` 本身只要 **0.3 µs**（`WriteCostProbe` 实测），
成本在**惰性 `ensureIndex()`** 里的 `CellSet` 插入/删除 —— 那是 `arraycopy` 移位 =
**O(该类型的格数)**。本世界 `nonAirCells` = **1,006,024 格**、`typeCells[STONE]` = **849,084 格**，
所以**任何跨 AIR 边界的写入**（`ash` 往地表堆一格 STONE 正是这种）一次就是**兆字节级 memmove**。

**④ 已修（零漂移可证）**：`nonAirCells` 的全仓唯一读者是 `World.nonAirCells()`，
而**生产代码从不调用它**（只有 `StreamChunkTest` 与 `tools/` 读）→ 改为**惰性重建**
（跨 AIR 边界只标脏；真正要读时按网格序 x→y→z 重建，与 `rebuildIndex` 的 nonAir 填充**同源同序**）。

| 指标 | 原始 | ① nonAir 惰性重建后 | ② CellSet 覆盖层后 |
|---|---|---|---|
| tick median | 19.65 ms | 5.13 ms | **2.01 ms（9.8x）** |
| tick p95 | 28.02 ms | 7.75 ms | **4.43 ms（6.3x）** |
| tick max | 35.41 ms | 11.95 ms | **15.09 ms** |
| 60fps 预算内可容 tick（p95） | 0.6 | 2.2 | **3.8** |
| `ash` 消融 Δ | 13.687 ms（76.6%） | 3.626 ms（64.3%） | **0.683 ms（19.8%）** |
| 消融 baseline | 17.874 ms | 5.638 ms | **3.458 ms（5.2x）** |

DET `74ad826636fe8eb2` 未变；`STREAMCHUNK`（专门断言 nonAir 集合与迭代序等价）PASS。

**⑤ 第二刀：`CellSet` 增量插入 O(n) → 摊还 O(|基线|/1024)（覆盖层 + 压实）**

第一刀之后 `ash` 仍占 64.3% —— 因为它的写入还要插进 `typeCells[STONE]`（84.9 万格），
而 `CellSet` 是「升序平铺 int[]」，插入/删除走 `arraycopy` 移位 = **O(该类型格数)**。

改法（**契约完全不变**，只是内部表示）：给 `CellSet` 加**待加入 / 待删除两个有序覆盖层**，
`addCell`/`removeCell` 只改覆盖层（O(覆盖层)），迭代时把「基线 − 待删除」与「待加入」**归并**输出
（仍是升序、仍是每格一次）；覆盖层合计超过 `OVERLAY_LIMIT` 就**压实**一次（O(基线) 归并）。
摊还代价 ≈ |基线| / 1024，相对原来的「每次 O(基线)」是**数百倍**改善。

`OVERLAY_LIMIT` 是**扫出来的**（256/512/1024/2048 → median 2.17/2.08/2.01/1.95 ms、
p95 7.64/4.90/4.43/4.33 ms），取 **1024**：p95 是帧节奏的主导项，2048 只再省 0.1ms。

**零漂移证据（这一刀最容易出事，所以门禁是安全网）**：`DETERMINISM 74ad826636fe8eb2` 未变、
`STREAMCHUNK`（**专门断言 `cellsOfType`/`nonAirCells` 的元素集合与迭代序在分帧/即时两条路径下逐元素等价**）
PASS、`PREDROLLBACK`/`SNAPSTATE`/`NETSNAP` 全 PASS、全套 7 个指纹逐字节未变、52 道门禁 57 处 `*_EXIT` 全 0。

**消融复核（改造后）**：baseline **3.458 ms**；成本**不再集中于单点** ——
`sand` 30.2% / `ash` 19.8% / `pond` 18.2% / `flood` 14.6% / `snowcap` 9.1% / `lava` 8.1% / `water` 5.2%。
（消融全跑耗时也从 2m24s 降到 **23s**。）

**⑥ 帧预算据此重定**：`MAX_STEPS_PER_FRAME` **3 → 2**（p95 4.43ms × 2 = 8.9ms，稳稳落在 16.67ms 内；
时间盒 12ms 也正好允许 2 步）；`MAX_FRAME_SIM_MS = 12.0`、`MAX_ACC_SEC = 0.15` 不变；
`SHIFT_CHUNKS_PER_STEP` 保持 8。审计 `C12` 依据同步为「tick p95 4.43ms」。

**⑧ 等价性护栏（补强）**：`CellSet` 改造前它只有**间接**覆盖（`STREAMCHUNK` 比的是「分帧 vs 即时」两条路径的索引是否相同 —— 如果两条路径**同错**就抓不到）。故给 `StreamChunkTest` 增第 **⑤ 段 `scanTruth`**：在 12000 次往返写入（**远超覆盖层上限 1024，强制多次压实**）之下，`cellsOfType(t)` / `nonAirCells()` 必须与**直接扫 `mat` 得到的地面真值**逐元素、逐序一致，并带三重防假绿（写入确实生效 / 真值非空 / 类型集合非空）。
按本项目既有做法**不新增门禁编号**（同 `DayNightTest` 的 CELESTIAL 段），故门禁数不变、锚点文档无需连带改动。

**⑨ 投递路径（本轮收尾时发现的缺口，已修）**：上面 7 个参数「消费端」接线了，但
**投递端没接** —— `ContentRegistry.modules()` 全仓**零生产调用**，模块里声明的 `params`
只有解析、没有消费者。后果：只在模块里出现的键（`wireRange` / `hungerRate` / `orbItemCost`）
**任何预设都送不到 `World.config`**；最直接的表现是 `palworld_like` 写了 `haulRate: 1.5`，
但 `wireRange` 恒 0 → `HaulSystem` 永远没电 → **无人搬运在游戏里从不发生**。

- **修法**：`Simulation.applyPreset` 改**两层**应用 —— ① 预设启用的**模块**各自的 params
  （模块默认值，按 `modules` 声明序叠加）；② 预设自身 params（**覆盖**模块值）。
  抽出单点 `applyParam()` 让两个来源走同一套键名映射与钳制（两处各写一份必然漂移）；
  未知模块参数同样点名（`?modparam=`）。
- **零漂移**：`breathing_world` 显式写 `"hungerRate": 0`，使「模块 + 预设」叠加后仍 == 出厂值
  → 默认世界不变。
- **顺带调平衡**：`HungerSystem.DRAIN_PER_TICK` `0.05 → 0.01`（rate=1.0 时 100 点 ≈ **8 分钟**）。
  原值意味着 100 秒就饿到扣血 —— 一旦某个预设真把饥饿打开，玩家会以为游戏坏了。
- **门禁**：`SUBSYS` 增第 9 组 **`PRESET_DELIVERY`**（默认==出厂 / 模块参数真送达 /
  预设覆盖模块 / **7 个键各自都能被某个出厂预设送出一个非出厂值** → `keysReachable=7/7`）。
  按既有做法**加组不加道**，门禁数不变。
- ⚠️ **需要你定的一处副作用**：`peaceful_valley` 也启用 `survival` → 它现在同样带饥饿（rate 1.0）。
  这是「启用模块 ⇒ 其参数生效」的一致结果，但「peaceful 却会饿」可能不合你意 ——
  要关的话在 `peaceful_valley.params` 加 `"hungerRate": 0` 即可（纯内容改动，一处）。

**⑩ 内容扩张第一项：小地图（2026-09-17）** —— 六项候选（多阶段 Boss / 招架 / 处决 / 小地图 / 合成台 / 箱子）里选它的理由：唯一**纯渲染层（零指纹风险）+ 模型能在无头环境断言**的，且直接回应玩家报过的「不知道自己在哪」。新增 `core/content/MapField`（纯采样：48×48 格地表俯视图 → 24×24 色，颜色直接用 `Blocks` 自带 `r,g,b` 世界本色 + 高度明暗，列顶是水则按水色）+ `Game.drawMinimap`（右上角 96×96，复用 HUD 2D 通道，不新增 GLSL）+ `N` 键开关（默认开；CONTROLS 已登记）。FX 门禁加 MAP 段 5 断言（15→20）：`MAP_DET`（纯读）/ `MAP_CENTER`（采样对齐）/ `MAP_WATER`（水色逐位相等）/ `MAP_EDGE`（越界 == OUT_OF_WORLD，不崩不环绕）/ `MAP_SHADE`（高度明暗单调有界）。

**⑪ 内容扩张第二项：合成台（I 键手搓）+ 修冶炼链不可达（2026-09-17）** —— 收尾时发现挖矿掉落靠 `blockId.toLowerCase()` 猜物品 id：挖 `COAL_ORE` 得 `coal_ore`、挖 `IRON_ORE` 得 `iron_ore`，而冶炼配方要 `coal`/`ore`（两者原来无 block 字段）→ **整条冶炼/钢铁链成本永远付不出去**。修法：`ContentRegistry.itemForBlock` 反向索引（由 item.block 决定掉落，旧内容无声明时回退命名匹配）+ `coal` 认领 `COAL_ORE` / `ore` 认领 `IRON_ORE` + 删除零引用重复物品 `coal_ore`/`iron_ore`；新增真实玩家入口 `I`：`RecipeBook.craftable` 选当前够料配方，`RecipeBook.craft` 全有或全无结算，Game 只负责按键→调用→反馈；CONTROLS 补 `I CRAFT (HAND)`。RECIPE 门禁增 `CHAIN_REACHABLE`（ore/coal 可由挖矿得到、映射准确）/ `CRAFT_PAYS_ALL`（富足真扣真产、不足一格不扣）/ `CRAFT_SELECTS`（穷→null、够→smelting），7 properties；门禁数不变。

**⑦ 踩坑（值得记）**：把 `nonAirStale` 加进 `World` 后 **`PREDROLLBACK` 立刻红灯** ——
新字段默认非 SKIP → 被编进 `netHash`，而生产世界里它几乎恒 `true`（跨边界写入就标脏）、
`restoreInPlace` 后是 `false` → 回滚对拍把「与行为无关的标记」报成 desync。
**修法**：`World.nonAirStale` 与同类隐患 `World.indexStale` 一并进 `StateCodec.SKIP` +
`SNAPSTATE.SKIP_DOC` + `MUST_BE_SKIPPED`（`RECORDED_SKIPPED` 27→29）。

### 37.5 美术续批（纯渲染层，零漂移）

- **雨幕层**（`Game.drawRainOverlay`，240 条斜雨丝）：原天气只有「天色压暗 + 雾稍浓」，
  玩家分不清在下雨还是入夜。三条纪律 —— ① 位置/速度全走 `EffectQueue.hash01` **纯哈希**
  （不碰 `fxRng`、不碰 `simStream`）；② **不新增 GLSL**，复用既有 HUD 2D 四边形通道
  （沙箱验不了 GLSL 编译，新写着色器 = 黑屏风险）；③ 丝数固定，顶点量恒定。
- 雨雾 `0.01 → 0.022`；夜色下限 `0.16 → 0.20`（UNIMPLEMENTED §3.1「雨夜不压死成黑」）；
  调色 饱和 `1.06 → 1.10` / 对比 `1.06 → 1.08` / 暗角 `0.80 → 0.86`（世界 + 天空两处同步，**只动数值常量**）。
- 暂停主菜单页新增**世界状态两行**（`DAY/时刻/天气` + `XYZ/敌兵/繁荣`）—— 暂停时最想知道「我在哪、世界什么样」。

> ⚠️ **GL 观感仍需本机人眼**：`render.software.SoftwareRenderer` 是 CPU 平面着色，
> 不含 GLSL 的雾/调色/暗角，**判不了**最终观感（它只能判构图/几何/透明度量级）。

### 37.6 途中踩的坑（写下来，下次不再犯）

1. **火不能当「电源」**：第一版 `WireSystem` 电源 = 紧邻 `FIRE` 的灯具，**实测当场失败** ——
   `FireSpreadSystem` 每 tick 有 50% 概率把火熄灭，火源平均活不过 2 tick。改用煤矿（稳定且语义正确）。
   *纸面很自然，跑起来才知道火是瞬态的。*
2. **采样型系统的测试夹具**：侵蚀/搬运这类「随机采样全窗」的系统，必须用小世界
   （`32x40x32`，**SX/SZ 必须是 16 的倍数**否则构造抛错）+ 密集样本层；
   6 格样本散在 20 万格里命中率 0.07%，断言会「看起来像没接线」。
3. **`core.systems.System` 遮蔽 `java.lang.System`**：门禁类里写 `System.out` 直接编译错 → 必须 `java.lang.System`。
4. **审计 C1 是按变量名匹配的启发式**：`ty = surfaceY[...]` 被误判成「实体高度取单列」；
   放**方块**的高度改名 `placeY` + 注释说明语义即消除。
5. **BFS 扩张循环必须守 `poweredCount < nodeCount`**：全通电时 `swap(i, poweredCount)` 会写到
   `nodes[nodeCount*3]` 越界（实测踩到）。

### 37.7 验证

`build_runner.py`：**57 处 `*_EXIT` 全 0 / 0 FAIL**；审计 **`FAIL 0 / WARN 0`（24 项）**。
四道基线指纹**逐字节未变**（见 §0 状态锚点）；`SUBSYS` / `RECIPE` / `SYSTEMREG` / `SNAPSTATE` 全 PASS。
