# 移植差距矩阵 · 原系统 → Java LWJGL3 版

> 配套文档：`PORTING_PLAYBOOK.md`（5 步移植模板 + 零漂移门禁）、`CD-PILLARS.md`（创意支柱）
> 状态锚点（**2026-09-17 实测**）：**52 道门禁出口全绿**（57 处 `*_EXIT` 全 0；审计 `FAIL 0 / WARN 0`）—— DETERMINISM `74ad826636fe8eb2` / ZERO-DRIFT `5ce9392207387ebf` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628` / NPC `afa036e4b0065f35` / SOCIAL `0dc8d8bef2718582` / TRIAL `8352df75e6dcd359` / MEGALITH `070a02ddb58ea0eb` + STORYTELLER·CIVILIZATION·INDIVIDUAL·POLITY·MATTER 同种子自洽 + WEAPON-ART（C3）/ MENU（C5）/ DAYNIGHT（D3）/ AUDIO（C4）/ MATTER（批次5）/ MEGA（C6）/ MOD·SKILL·TECH·FX·SYSTEMREG·GAMEPLAY·CONTENT·ANIM（内容平台）/ SPAWN·SNEAK·REFUGE·SAVE·INVENTORY·ANIMCTL·COMBAT·BEACON·CHARACTER·ANIMLAYERS·STREAMCHUNK（M3）/ PORTABLEMATH·NETCODEC·SNAPSTATE·NETSNAP·ROLLBACK·NETDESYNC·NETLOCK·UDPLOCK·PREDROLLBACK·NETINTEG（N0–N5）/ SKILLCAST（A 批）/ **SUBSYS·RECIPE（2026-09-17 空转参数落地）**。系统数 **98**（2026-09-17 由 93 追加 5 个）；旧值「43 道 / 47 处 / DET `43723456791b5364`」为 2026-09-14 记录。
> **注**：下文中各批次「实测」行保留当时的记录（历史条目留旧数字，含 `21a2de…`/`8a69c3…` 两轮有意演进前的值）；**当前值一律以本行为准**。
> NPC 社会层（NPC-SOC-FOUND 地基 + 批次 0–5 **全部已接入主循环**）已落 `src/core/agent/` 并验证零漂移。

---

## 1. 执行摘要

**两个"81"是不同批次，不是同一套系统的搬运。**

| | Python 原版（你的游戏初衷） | Java LWJGL3 版（现状） |
|---|---|---|
| 规模 | `systems/` **91** 个概念系统 + `agents/` **6** 个社会认知层 | `src/core/systems/` **81** 个体素/生态/天气涌现系统 |
| 本质 | **社会模拟引擎**（NPC 文明自生长：心智/社交/生理/决策） | **体素涌现世界**（方块/生态/天气/地质自演化） |
| NPC 层 | `agents/`：mind / social / body / npc / decision / player | `core/agent/`：Body / Mind / Social / Npc / Decision（**地基已建，批次 0–5 已接入 tick**） |

**结论（直接回答“系统迁移完了吗”）：✅ 已搬完（2026-09-11 收口）。**
- **~52 个**依赖 NPC 社会层的系统（family / culture / emotion / religion / tech / diplomacy / dialogue / magic …）**已全部移植**（批次 1 社会核心 / 批次 2 文明深度 / 批次 3 个体成长 / 批次 4 民政经济与灾害 + 批次 5 抽象字段），并全部接入 `World.tick()` 主循环，全部门禁（当前 43 道）指纹逐字节不变。
- **~13 个**原本已直通/简化的体素系统（prosperity / biodiversity / climate / farming / trade / civilization 拆分 …）**保持在线并可继续打磨深度**（批次 4 已补定价/犯罪仲裁/灾害触发层）。
- **~28 个**纯宇宙/物理/时间抽象（quantum / relativity / galaxy / orbital / timewarp / dimension …）**按设计不移植**（强行塞进体素世界只会稀释 USP）；其中 **6 个曾被列为“可抽象”** 者已在 **批次 5** 处置：`phases`/`entropy`/`fusion`/`deepsea` → 落为 `Matter` 开放标量层，`link`/`portal` → **显式排除**（无世界状态的粘合/配置层，非遗漏）。

---

## 2. 差距矩阵（按主题分组）

列含义：
- **依赖 NPC 层?** —— 系统是否需要 `agents/` 的 mind/social/body/npc 才能有意义
- **Java 对应?** —— 是否有同名/同义 Java System
- **判定** —— `ALREADY`（已直通）/ `SIMPLIFIED`（简化体素版）/ `NEEDS-NPC`（需先接 NPC 层）/ `DOES-NOT-FIT`（纯抽象，跳过或抽象字段）

### 2.1 已直通 / 简化覆盖（~13，可继续打磨深度）
| Python 系统 | 依赖NPC | Java 对应 | 判定 |
|---|---|---|---|
| prosperity | N | ProsperitySystem | ALREADY（签名：读 events→繁荣→蓝图→村志） |
| biodiversity | N | BiodiversitySystem | ALREADY |
| symbiosis | N | SymbiosisSystem | ALREADY |
| swarm | N | SwarmSystem | ALREADY |
| climate | N | ClimateSystem | ALREADY |
| farming | N | FarmSystem | SIMPLIFIED（无季节/灌溉耦合） |
| trade | N | TradeCaravanSystem | SIMPLIFIED（仅商队记忆，无定价） |
| civilization | N | Craftsman/Ruins/Monument/Guild/Law/Market/Festival/Road/Wall | SIMPLIFIED（极简体素版，无城镇等级/定价/犯罪仲裁） |
| combat | N | Beast（IMPL-COMBAT）+ ProsperitySystem.on_repel | SIMPLIFIED（真实战斗遭遇闭环，无技能/装备树） |
| ecology | N | PlantSpread/Decay/Erosion/Mushroom/Vine/Nest/Reed 等 | ALREADY（拆分多系统） |
| terrain | N | World.generateChunk（值噪声地形） | ALREADY |
| village_memory | N | World.villageMemory + ProsperitySystem.recordMemory | SIMPLIFIED（无 NPC 忆往消费端） |
| disasters | N | Earthquake/Volcano/Lightning/Drought/Flood/Hail/DustStorm/Heatwave/Smog/AshFall | SIMPLIFIED（子集；缺海啸/陨石/瘟疫） |

### 2.2 依赖 NPC 社会层（~52，批次 0–5 全部 DONE，已接入主循环）
| Python 系统 | 依赖NPC | 落点（Java 地基组件） | 判定 |
|---|---|---|---|
| family | Y | Social.affinity + Mind.goals | NEEDS-NPC |
| culture | Y | Social.longTerm + Mind.traits | NEEDS-NPC |
| emotion | Y | Mind.emotion 向量 | NEEDS-NPC |
| memorymgmt | Y | Mind.memory 限容 | NEEDS-NPC |
| norms | Y | Social.drift(DECENCY) | NEEDS-NPC |
| behavior | Y | Decision.choose 规则 | NEEDS-NPC |
| persona | Y | Mind.traits + Social.traits | NEEDS-NPC |
| learning | Y | Mind（长期权重） | NEEDS-NPC |
| diplomacy | Y | Social.affinity（跨聚落） | NEEDS-NPC |
| religion | Y | Social.longTerm + traits | NEEDS-NPC |
| genetics | Y | Body（遗传字段） | NEEDS-NPC |
| evolution | Y | Body + 选择压力 | NEEDS-NPC |
| tech | Y | Mind.goals + Build（工具树） | NEEDS-NPC |
| industry | Y | Body + Build + 产线 | NEEDS-NPC |
| urban | Y | Craftsman/Road/Wall 扩展 | NEEDS-NPC |
| medicine | Y | Body（治伤/疾病） | NEEDS-NPC |
| robotics | Y | Body（机械体） | NEEDS-NPC |
| firearms | Y | combat 扩展 | NEEDS-NPC |
| fishing | Y | 行为 + 资源 | NEEDS-NPC |
| taming | Y | Social（驯服亲疏） | NEEDS-NPC |
| warfare | Y | Social.hostile + combat | NEEDS-NPC |
| xeno | Y | Social（异种亲疏） | NEEDS-NPC |
| magic | Y | Mind + 附魔方块（抽象） | NEEDS-NPC |
| arcana | Y | magic 子概念 | NEEDS-NPC |
| ascension | Y | progression（成长可见 P4） | NEEDS-NPC |
| cultivation | Y | farming + Body | NEEDS-NPC |
| roleplay | Y | Decision + Mind | NEEDS-NPC |
| storyteller | Y | village_memory 消费端（叙事） | NEEDS-NPC |
| myth | Y | storyteller + religion | NEEDS-NPC |
| dialogue | Y | Mind + 对话 UI（Ludonarrative Consonance 关键） | NEEDS-NPC |
| dreamscape | Y(部分) | Mind.emotion 派生 | NEEDS-NPC |
| chemistry | Y(部分) | 方块反应（Fire/Erosion/Clay 扩展） | NEEDS-NPC（部分可纯方块） |
| comeback | N | Player 重生（已落 P0-1） | ALREADY（归玩家回环，不属 systems） |

### 2.3 纯抽象 / 不适合体素世界（~28，按设计不移植；其中 6 个“可抽象”候选已在批次 5 处置）
| Python 系统 | 类别 | 判定 |
|---|---|---|
| quantum | 物理抽象 | DOES-NOT-FIT |
| relativity | 物理抽象 | DOES-NOT-FIT |
| galaxy | 宇宙 | DOES-NOT-FIT |
| astro | 天文 | DOES-NOT-FIT |
| orbital | 轨道 | DOES-NOT-FIT |
| phases | 月相/相位 | ✅ **批次 5 抽象落地** → `Matter.phases/transitions/alloy`（物相层；温度←火/灯方块+昼夜气候） |
| timespace | 时空 | DOES-NOT-FIT |
| timeline | 时间线 | DOES-NOT-FIT |
| timeskills | 时间技能 | DOES-NOT-FIT |
| timewarp | 时间扭曲 | DOES-NOT-FIT |
| deeptime | 深时 | DOES-NOT-FIT |
| dimension | 维度 | DOES-NOT-FIT |
| warp | 跃迁 | DOES-NOT-FIT |
| space | 太空 | DOES-NOT-FIT |
| spacecol | 太空殖民 | DOES-NOT-FIT |
| metaspace | 元空间 | DOES-NOT-FIT |
| cosmiccat | 宇宙猫 | DOES-NOT-FIT（彩蛋，可事件） |
| fusion | 聚变能量 | ✅ **批次 5 抽象落地** → `Matter.reactors/fusionOut/radiation`（门：科研≥60 且 供电≥1） |
| logic | 逻辑门 | DOES-NOT-FIT |
| entropy | 熵 | ✅ **批次 5 抽象落地** → `Matter.supply/work/entropy/exergy`（供给←供电+日照） |
| link | 链接 | ⛔ **显式排除**（鸭子类型 `getattr` 跨系统粘合层，跑一遍不改任何 `hashState` 字段） |
| portal | 传送门 | ⛔ **显式排除**（CLI/Web 配置中枢，无世界状态） |
| realmcfg | 配置 | DOES-NOT-FIT（配置，非系统） |
| gamemaster | 叙事导演 | DOES-NOT-FIT（meta，归 storyteller） |
| boardgame | 桌游 | DOES-NOT-FIT |
| cards | 卡牌 | DOES-NOT-FIT |
| deepsea | 深海 | ✅ **批次 5 抽象落地** → `Matter.subs/pressure/mineral/pollution/colonies`（原门“科技+齿轮”在 Java 永不可达 → 改“水≥100 且 科研≥60”，防空死代码） |
| tectonics | 板块 | DOES-NOT-FIT（Earthquake 已覆盖地震） |

> 小计：DOES-NOT-FIT ≈ 28（其中 4 个已在批次 5 抽象落地、2 个显式排除），NEEDS-NPC ≈ 52（**全部 DONE**），ALREADY/SIMPLIFIED ≈ 13 → 覆盖全部 91 个 Python 系统（含工具文件）。

---

## 3. 架构前置条件（**已满足并已接入**）

`core/agent/`（Body/Mind/Social/Npc/Decision）已落地并验证零漂移：
- `World.npcs`（`List<Npc>`）字段已存在，NPC 实体**不进 `hashState()`**（与 beasts/shrines 同纪律），空列表不影响指纹。
- 所有随机只走 `world.simStream(...)` 派生的 `SeededRNG`，绝不读 `world.rng` 主状态或 `fxRng`。
- **接入状态（2026-09-11 更新）**：NPC 已全部接入 `World.tick()` 主循环 —— `NpcSystem`（批次 0）+ `SocialSystem`（批次 1）+ `StorytellerSystem`（批次 1 余项）+ `CivilizationSystem`（批次 2）+ `IndividualSystem`（批次 3）+ `PolitySystem`/`CalamitySystem`（批次 4）+ `MatterSystem`（批次 5）。全部为实体级/派生状态，**不进 `hashState()`**，全部门禁（当前 43 道）指纹逐字节不变。

---

## 4. 优先级移植路线图（每批末跑四门禁）

> 参照 `PORTING_PLAYBOOK.md` 5 步模板；NPC 依赖批在 `NPC-SOC-FOUND` 接入主循环后方可开工。

**批次 0 · NPC 接入主循环（前置，解锁后续全部 NEEDS-NPC）** ✅ DONE（2026-09-10）
- 目标：`World.tick()` 驱动 `npcs`；`NpcSystem` 包装 `body/mind/social/decision` step。
- 门禁：NPC 空列表指纹不变；spawn 少量 NPC 后同种子自洽。
- 实测：`NpcSystem` 注册进主循环，首 tick 确定性生成 5 人聚落；`NpcDeterminismTest` hashA==hashB；四门禁指纹不变。

**批次 1 · 高玩家价值·社会核心（NEEDS-NPC）** ✅ DONE（2026-09-10）
- family / emotion / social(norms) / dialogue / **storyteller / village_memory(消费端)** 全部 DONE。
- 已落：`VillageSocial`（家族/情绪/规范，实体级不进指纹）+ `SocialSystem`（邻里升温/情绪状态机/情绪/规范/结亲生育，确定性无 rng）+ `Dialogue`（persona/tone/规则回话/忆往）+ NPC 渲染（职业着色人形+情绪色标）+ T 键交谈（回话落 `dialogue.log`）。
- 收口补：`Chronicle`（村志档案库：事件→episode + bigram Jaccard 检索 + 村志叙述生成，**不进 hashState**）+ `StorytellerSystem`（注册进主循环，每 tick 归档转折事件、周期出“当日村志”）+ 消费端（HUD ASCII 村志摘要 + `chronicle.log` 中文全文 + `Dialogue.talk` 引用村志档案）。
- 实测：四门禁指纹不变（DETERMINISM `21a2de200fda8a8b` / ZERO-DRIFT `8a69c3559c2d86bf` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`）；`SocialDeterminismTest` hashA==hashB，families=4 births=11 mood=0.405 trust=0.424 coop=0.845 norm=true；`StorytellerDeterminismTest` hashA==hashB，CHRONICLE D11 REPEL 35 MARRY 2 BIRTH 3 episodes=41。
- 对齐 CD-PILLARS **P1「世界活着」设计测试**：让"因你而变"被看见（Ludonarrative Consonance 铁律）——`Chronicle` 把“旅人击退来犯之敌 N 次”与婚育史结构化，HUD 常驻显示 `REPEL/MARRY/BIRTH` 计数（玩家可指认“这变是因为我”）；`Dialogue.talk(useMemory=true)` 引用最新村志档案。**中文村志浮层上屏**仍待 CJK 字体（已落 `chronicle.log`）。

**批次 2 · 文明深度（NEEDS-NPC）** ✅ DONE（2026-09-10）
- culture / religion / tech / industry / urban / diplomacy / warfare —— 全部移植。
- 已落：`Civilization`（文明状态容器：节日/绰号/禁忌/传说 + 神殿/信徒/虔诚/异象 + 研究/解锁/乘子/蓝图 + 矿石/生铁/齿轮/电力/电网 + 区划覆盖层/宜居/地价 + 势力/关系/协定/冲突 + 政权/军势/民怨）+ `CivilizationSystem`（注册进 `registerDefaultSystems` 末位，固定子步序 urban(10)→tech(20)→industry(10)→religion(10)→diplomacy(20)→warfare(10)→culture(24)，**全确定性、无 rng 主状态**）。
- 信号源替换（Java 无 Python trade/market/robots/deepsea）：coins=prosperity×1、市场盈余=prosperity×0.02、污染/犯罪=0；`link.couple` 跨系统联动（religion↔warfare↔research）改为同容器直写。
- **材料执行层**（tech 自治修墙/重建、industry 网格磨蚀/铺缆）由 `CivilizationSystem.MATERIAL_WORKS` 开关守护，**默认关** → 网格一字不改 → 指纹仍 `21a2de200fda8a8b`；开启则按 1:1 守恒做材料重排（届时指纹演进，属预期），统一留待批次 4「文明实体化」。
- 实测：四门禁指纹不变（DETERMINISM `21a2de200fda8a8b` / ZERO-DRIFT `8a69c3559c2d86bf` / PHYSICS PASS / STREAMING `87d5bf1cecb4c628`）；`CivilizationDeterminismTest` hashA==hashB，research=175（解锁 farming2/medicine）· temples=8 · 区划 res37/ind48/farm55·roads24 · 外交 nomad0.38/hill0.63/valley0.55 · 政权 2·革命 1 · 文化立节 2。
- 对齐 CD-PILLARS **P1「世界活着」**：`Chronicle` 新增归档科技/神殿/异象/协定/战争/革命/文化里程碑 → 文明进展也进村志（HUD `CIV` 摘要常驻：RES/TECH/TEMPLE/ROADS/REL/REGIME）。

**批次 3 · 个体成长（NEEDS-NPC）** ✅ DONE（2026-09-10）
- learning / behavior / persona / genetics / evolution / medicine / robotics / firearms / fishing / taming / xeno / ascension / magic / cultivation / myth / dreamscape / chemistry / roleplay —— **18 个全部移植**。
- 已落：`Individual`（个体层开放标量聚合容器，实体级不进 hashState）+ `IndividualSystem`（注册进 `registerDefaultSystems` 末位，固定子步序 learning(10)→behavior(8)→persona(20)→genetics(20)→evolution(10)→medicine(5)→robotics(10)→firearms(10)→fishing(8)→taming(8)→xeno(10)→ascension(12)→magic(12)→cultivation(20)→myth(10)→dreamscape(8)→chemistry(12)→roleplay(20)）。
- **材料执行层**（magic 真实施法写 temp/set_cell、chemistry 反应层写 mat/mass/temp）由 `IndividualSystem.MATERIAL_WORKS` 开关守护，**默认关** → 网格一字不改 → 指纹仍 `21a2de200fda8a8b`；开启留待批次 4。
- 实测：九门禁全绿（DETERMINISM `21a2de200fda8a8b` 与接入前逐字节一致 / ZERO-DRIFT `8a69c3559c2d86bf` / PHYSICS / STREAMING `87d5bf1cecb4c628`）；`IndividualDeterminismTest` hashA==hashB，know=954·evoGen=121·mf=0.26·spec=2·robots=11·guns=8·recovered=24·spells=1·realm=6（化神）·heroes=4·lucid=44·discoveries=3。
- **跨层修复（本批关键）**：Python 的全局 `world._research` 被 tech/firearms/myth/dreamscape 共享；照搬会使个体层抽干文明科技池（实测 `civ.research` 175→**6.4**，`medicine` 永久不解锁）。改为**信号源分离**——个体层自持 `Individual.research`（learning/myth/dreamscape 供养、firearms/magic 消费），`civ.research` 由 `CivilizationSystem` 独享 → 两层互不饥饿（`civ.research` 复原至 108.6）。
- **工业链死尾修复（批次 2 遗留）**：`power()` 原仅取 GENERATOR/WIRE 方块（Java 无此块→power=0）致 `assemble()`（需 ASSEMBLE_POWER=1）永不启动；`mine()` 原 `!MATERIAL_WORKS` 直接 return 致 ore=0。现改为**数字层产矿**（按速率计入账本、不磨蚀网格）+ **畜力/水力供电基线**（外部替代）→ 工业链复活（ore/iron>0、robots=11），且网格与指纹不动。
- 对齐 CD-PILLARS **P1「世界活着」**：`Chronicle` 新增归档个体/文明里程碑（科技/神殿/奇观/觉醒/机器学习/飞升/史诗…）→ 一切长进都进村志。

**批次 4 · 深度打磨已覆盖系统** ✅ DONE（2026-09-10）
- civilization（城镇等级+定价+犯罪仲裁）/ trade（定价市场）/ disasters（触发/预警/撤离）—— 主要三项完成；**farming 季节+灌溉** 与 **combat 技能树** 已由既有系统覆盖（`SeasonSystem`/`IrrigationSystem`/玩家技能树），本次不重造。
- 已落：`Polity`（民政经济容器：城镇等级/集市定价/犯罪/仲裁/累犯 + 货币/商队/通胀/供应链）+ `PolitySystem`（注册进 `registerDefaultSystems` 末位，子步序 civ(5): town→price→crime→judge / trade(10): labor→mint→caravan→inflation→supply-chain）；`Calamity`（灾害状态容器：dryTicks/rainStreak/flag/各灾种计数 + 撤离）+ `CalamitySystem`（触发层：干燥→点燃、连雨→洪涝、久旱→干旱、周期→地震 + NPC 撤离信号）。
- **与既有系统分工**（不重复造轮子）：`WildfireSystem`/`FloodSystem`/`DroughtSystem`/`EarthquakeSystem` 只做“蔓延/搬运”，**无触发器**；本批补的正是 Python `disasters.py` 的**触发层**。
- **材料执行层**（点燃易燃格 / 抬高水位 / 房屋→木残骸）由 `CalamitySystem.MATERIAL_WORKS` 开关守护，**默认关** → 网格一字不改 → 指纹仍 `21a2de200fda8a8b`。
- **可达性修复（本批关键，避免死尾）**：
  ① 撤离原按“全局旗标”见谁吓谁 → 久旱旗标滞留致**全村永久 scared**（`evac=877`、不再漫步）→ 改为**局部危险感知**（邻近 5×5 有火/淹才逃）；
  ② 商队 `(int)rate`（rate≈0.88）恒为 0 → 改**小数累积器**（攒够 1 才 +1）→ `caravans=105`；
  ③ 仲裁“惩戒”分支不可达（Java 邻里亲疏被 `SocialSystem` 烘热，人人都有 ≥0.45 相护者 → `pen=0/shield=36`）→ 增设**累犯**维度（初犯可获人情、惯犯失众望一律惩戒）→ `pen=17/shield=19/recid=17`。
- 实测：**十道门禁全绿**（DETERMINISM `21a2de200fda8a8b` 与接入前逐字节一致 / ZERO-DRIFT `8a69c3559c2d86bf` / PHYSICS / STREAMING `87d5bf1cecb4c628` + NPC/SOCIAL/STORYTELLER/CIVILIZATION/INDIVIDUAL/POLITY 同种子自洽）；`PolityDeterminismTest` hashA==hashB，lv=3(堡垒)·alive=24·fams=7·crime=36(theft15/fight21)·judge=36(pen17/shield19)·coins=184.82·infl=1.44·caravans=105·inflating=true；`Calamity` fire=1·flood=3·drought=4·quake=1·alerts=23·evac=60。
- 对齐 CD-PILLARS **P1「世界活着」**：`Chronicle` 新增归档镇级/犯罪/仲裁/灾害/通胀里程碑（TOWN/CRIME/JUDGE/DISASTER/INFLATION）→ 是非、天灾、物价也进村志（HUD `POLITY`/`CALAMITY` 摘要常驻）。
- 已知校准注记（**非缺陷**）：极端资源价格贴合上下限（`iron`/`food` 触顶、`stone` 触底），因供给量级悬殊；`wood`/`berry` 保留动态信号。

**批次 5 · 抽象系统** ✅ DONE（2026-09-11）（4 个；2 个显式排除）
- `phases`→物相 · `entropy`→熵与有效能 · `fusion`→聚变辐射 · `deepsea`→深海 已落为开放标量层（`Matter` 15 标量，不进 `hashState`）+ `MatterSystem`（注册末位，`MATERIAL_WORKS` 默认关、**不消费 rng**）+ HUD `MATTER`/`DEEPSEA` 两行；第 18 道门禁（可达性 + 指纹隔离 + 零 RNG）。
- ⛔ **显式排除**：`link`（跨系统粘合层）/ `portal`（CLI/Web 配置中枢）—— 无世界状态，跑一遍不改任何 `hashState` 字段。

> **不移植**：quantum / relativity / galaxy / astro / orbital / timespace / timeline / timewarp / dimension / warp / space / spacecol / metaspace / logic / boardgame / cards / gamemaster / realmcfg / cosmiccat —— 与体素动作游戏定位不符，强行移植只会稀释 USP。

---

## 5. 风险与纪律

1. **零漂移铁律**：所有系统随机只走入参 `rng`（`world.simStream(name+":"+tick)`）；NPC 行动绝不读 `world.rng` 主状态或 `fxRng`；NPC/玩家/敌兵/祭坛实体不进 `hashState()`。
2. **维度映射**：Python `mat[y,x]` 行主序 → Java `mat[x][y][z]`，移植逐处核对。
3. **叙事消费端**（✅ 已解决，批次 1）：Python 大量系统写 `world.events`/叙事，Java 原无消费者 → 已由 `Chronicle` + `StorytellerSystem` + HUD/`chronicle.log`/`Dialogue` 引用村志补齐，“世界因你而变”已可见（EVAL-1 P0-B 悬案关闭）。
4. **门禁闸控**：每批末 `build.bat` 跑**全部门禁**（当前 43 道，权威清单见 `build_runner.py` 的 `gate(...)`）全绿方可合并；任一指纹非预期翻红即回退该批改动。
5. **不与 JS 75% 胜率基线耦合**：Java 版独立守全部门禁（当前 43 道，含四道零漂移基线），动 Java 不影响浏览器原型 `sim_core.js` 基线。

> **基线指纹演进（2026-09-11）**：（2026-09-11 地形可玩性调参后基线指纹有意演进：振幅减半 + 尺度 18/6，修复"卡在冲沟"）。旧值 ad9e7b31ed47ec45 / c9e1d98362321283 / e722b7f80ffc8317 / 0a8533fd5eb2ec6b 为演进前记录（event_log 历史条目保留）。门禁为同种子自洽断言，18 道仍全绿。
