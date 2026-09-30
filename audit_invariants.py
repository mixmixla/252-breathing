# -*- coding: utf-8 -*-
"""
一次性静态不变量审计（只读）。目标：构建前一次跑完，**每条命中都必须可执行**（不制造噪声）。

把本项目历史上反复出现的"常见 bug 类"变成检查项：

  C1 单列取地面残留        —— 实体高度只按"中心列"取 → 身体一侧埋进相邻方块（LD-2026-09-16）
  C2 同一概念多处定义      —— 台阶高度/走速/时间步长各写一份 → 改一处漏一处（漂移源头）
  C3 渲染盒 ⊆ 碰撞盒       —— 模型比碰撞盒宽/高 → 视觉穿模（LD-2026-09-16 同一根因）
  C4 时间倍率漏乘          —— 帧时基没统一乘 timeScale() → "别人比我的动作快"（LD-2026-09-16）
  C5 位图字体缺字形        —— 未注册字符静默渲染成 '?'（LD-2026-09-15）
  C6 菜单行数 ↔ 测试索引   —— SETTINGS_ROWS 改了、MenuTest 硬编码索引没跟（LD-2026-09-15）
  C7 阈值可达性            —— 形如 "A*var − B" 的净增式，阈值 B/A 是否真能被越过（LD-2026-09-16）

调准原则（第一版 19 项里 12 项是噪声，没人会看）：
  · 只报**违反了我们明确写下来的不变量**的项；
  · 每条例外必须写清"为什么它是对的"，不能靠猜。
"""
import io, os, re, sys

ROOT = os.environ.get("BW_ROOT",
    r"C:\Users\Administrator\Documents\trae_projects\AI\learn_demos\252_模拟世界游戏_会呼吸的世界\java\breathing-world")
SRC = os.path.join(ROOT, "src")

def java_files():
    for dp, _, fns in os.walk(SRC):
        for f in fns:
            if f.endswith(".java"):
                yield os.path.join(dp, f)

def rel(p): return os.path.relpath(p, SRC)
def read(p): return io.open(p, encoding="utf-8", errors="replace").read()

report = []
def out(check, level, msg): report.append((check, level, msg))
EXEMPT = []          # 被显式排除的项（附理由），一并打印，避免"静默放过"

# ---------------- C1 单列取地面残留 ----------------
# 判据：把实体位置 y **直接赋值**成 surfaceY[单格]（而不是走 World.floorY / spawnY）。
pos_assign = re.compile(r"(?:\b\w+\.y|\bty|\bby|\bny|\bnpc\.y|player\.y)\s*=\s*[^;]*surfaceY\[")
c1 = []
for p in java_files():
    for n, l in enumerate(read(p).splitlines(), 1):
        if pos_assign.search(l) and "floorY(" not in l and "spawnY(" not in l:
            c1.append(f"{rel(p)}:{n}: {l.strip()[:110]}")
for x in c1:
    out("C1 单列取地面", "FAIL", x + "   <-- 实体高度应走 World.floorY（整片足迹）")
if not c1:
    out("C1 单列取地面", "OK", "无残留：实体高度统一走 World.floorY（整片足迹）")

# ---------------- C2 同一概念多处定义 ----------------
# 只检查"我们明确声明必须一致"的概念表（白名单式，不猜）。
# 白名单式：只声明"必须一致"的概念，不靠猜同值（同值巧合太多 → 全是噪声）。
CONCEPTS = {
    "自动上台阶高度": ["STEP_HEIGHT", "STEP_H", "NPC_STEP", "BODY_STEP"],
}
CONCEPT_COLLECT = {}     # 名字 -> [(file, 数值)]   （真正的数值定义）
CONCEPT_ALIAS = {}       # 名字 -> 目标常量名        （"别名定义"，收成单一来源后仍要能看见）
for p in java_files():
    for n, l in enumerate(read(p).splitlines(), 1):
        m = re.match(r"\s*(?:public|private|protected)?\s*static\s+final\s+float\s+(\w+)\s*=\s*(-?\d+(?:\.\d+)?)f\s*;", l)
        if m:
            CONCEPT_COLLECT.setdefault(m.group(1), []).append((rel(p), float(m.group(2))))
            continue
        m2 = re.match(r"\s*(?:public|private|protected)?\s*static\s+final\s+float\s+(\w+)\s*=\s*([A-Za-z_][\w.]*)\s*;", l)
        if m2:
            CONCEPT_ALIAS[m2.group(1)] = m2.group(2).split(".")[-1]
for concept, names in CONCEPTS.items():
    found = []                      # (file, name, value)
    for nm in names:
        for (f, v) in CONCEPT_COLLECT.get(nm, []):
            found.append((f, nm, v))
    if not found:
        out("C2 同一概念同值", "INFO", f"{concept}: 未找到任何一份定义（可能已集中）")
        continue
    vals = sorted(set(v for _, _, v in found))
    files = sorted(set(f for f, _, _ in found))
    sites = " / ".join(f"{f}:{nm}={v}" for f, nm, v in found)
    alias = [f"{n}->{CONCEPT_ALIAS[n]}" for n in names if n in CONCEPT_ALIAS]
    if len(vals) == 1:
        lvl = "WARN" if len(files) > 1 else "OK"
        extra = f"；{len(alias)} 处已收成别名：{' / '.join(alias)}" if alias else ""
        out("C2 同一概念同值", lvl,
            f"{concept} = {vals[0]}（数值定义 {len(files)} 处：{sites}）{extra}")
    else:
        out("C2 同一概念同值", "FAIL", f"{concept} 值不一致 {vals}：{sites}")

# ---------------- C3 渲染盒 ⊆ 碰撞盒 ----------------  BOSS_RENDER_BOX_MARK
# 渲染盒尺寸来自 Game.java 的 addBoxWorld 字面量（改模型时必须同步本表 → 表本身就是"合同"）。
RENDER_BOX = {   # 实体: [(半宽, 顶高, 描述)]
    "npc":   [(0.26, 1.42, "躯干+头"), (0.28, 0.10, "肩甲/斗篷")],
    "beast": [(0.30, 1.10, "本体"), (0.34, 1.14, "剪影盒"), (0.35, 1.32, "角/横杆")],
    "boss":  [(0.75, 2.50, "躯干"), (0.80, 1.90, "肩甲"), (0.78, 2.22, "双眼"),
              (0.30, 2.85, "冠刺"), (0.96, 2.85, "前摇前肢")],
}
# 碰撞盒来源（唯一来源原则）：
#   npc        = NpcSystem.NPC_HW / NPC_H
#   beast/boss = Beast.HW[] / HH[]（按 type 索引）；BeastSystem.BODY_HW/BODY_H 是 HW[0]/HH[0] 的别名
BEAST_TYPE_BOSS = 4


def _float_array(java_rel, name):
    """读 `public static final float[] NAME = {a, b, ...};` → [a, b, ...]（读不到返回 None）。"""
    fp = os.path.join(SRC, java_rel.replace("/", os.sep))
    if not os.path.isfile(fp):
        return None
    m = re.search(r"float\[\]\s+" + name + r"\s*=\s*\{([^}]*)\}", read(fp))
    if not m:
        return None
    vals = []
    for x in m.group(1).split(","):
        x = x.strip().rstrip("fF")
        if x:
            vals.append(float(x))
    return vals


_npc = read(os.path.join(SRC, "core", "systems", "NpcSystem.java"))


def _npc_const(nm):
    m = re.search(r"public static final float " + nm + r"\s*=\s*([\d.]+)f", _npc)
    return float(m.group(1)) if m else None


_BW = _float_array("core/world/Beast.java", "HW")
_BH = _float_array("core/world/Beast.java", "HH")
COLL3 = {
    "npc":   (_npc_const("NPC_HW"), _npc_const("NPC_H")),
    "beast": (_BW[0] if _BW else None, _BH[0] if _BH else None),
    "boss":  (_BW[BEAST_TYPE_BOSS] if _BW and len(_BW) > BEAST_TYPE_BOSS else None,
              _BH[BEAST_TYPE_BOSS] if _BH and len(_BH) > BEAST_TYPE_BOSS else None),
}
for _who in ("npc", "beast", "boss"):
    _hw, _h = COLL3[_who]
    if _hw is None or _h is None:
        out("C3 渲染⊆碰撞", "FAIL", _who + ": 找不到碰撞盒来源（尺寸表缺失 / 结构改了）")
        continue
    _need_hw = max(w for w, _, _ in RENDER_BOX[_who])
    _need_h = max(t for _, t, _ in RENDER_BOX[_who])
    if _hw >= _need_hw - 1e-6 and _h >= _need_h - 1e-6:
        out("C3 渲染⊆碰撞", "OK",
            "%s: 碰撞盒 (hw=%s, h=%s) ⊇ 渲染盒 (hw=%s, h=%s)" % (_who, _hw, _h, _need_hw, _need_h))
    else:
        out("C3 渲染⊆碰撞", "FAIL",
            "%s: 碰撞盒 (hw=%s, h=%s) 装不下渲染盒 (hw=%s, h=%s) → 超出部分可被方块裁掉（穿模）"
            % (_who, _hw, _h, _need_hw, _need_h))

# 尺寸表唯一性：BeastSystem 里不许再出现第二个字面量（否则改一处漏一处）
_bs = read(os.path.join(SRC, "core", "systems", "BeastSystem.java"))
_lit = re.findall(r"public static final float (BODY_HW|BODY_H)\s*=\s*[\d.]+f", _bs)
if _lit:
    out("C3 渲染⊆碰撞", "FAIL",
        "BeastSystem 里重新出现字面量碰撞盒 %s —— 尺寸表必须唯一（Beast.HW/HH 按 type）" % _lit)
else:
    out("C3 渲染⊆碰撞", "OK", "BeastSystem.BODY_HW/BODY_H 是 Beast.HW[0]/HH[0] 的别名（尺寸表唯一）")


# ---------------- C4 时间倍率漏乘 ----------------
g = read(os.path.join(SRC, "render", "lwjgl", "Game.java"))
sites = []
for n, l in enumerate(g.splitlines(), 1):
    if re.search(r"this\.time\s*\+=|this\.acc\s*\+=|\.physicsTick\(", l):
        fixed = "确定性" in l or "锁步" in l or "0.05f" in l     # 固定 tick 路径：设计上不缩放
        sites.append((n, l.strip(), ("timeScale()" in l) or fixed, fixed))
bad = [(n, s) for n, s, ok, _ in sites if not ok]
for n, s in bad:
    out("C4 时间倍率", "FAIL", f"Game.java:{n}: {s[:110]}   <-- 帧时基必须乘 timeScale()")
ex = [n for n, _, _, f in sites if f]
if not bad:
    out("C4 时间倍率", "OK", f"{len(sites)} 处帧时基积分全部乘 timeScale()" + (f"；另有 {len(ex)} 处固定 tick 路径（设计上不缩放）" if ex else ""))
for n in ex:
    EXEMPT.append(f"C4 Game.java:{n} — 固定 tick（确定性锁步）路径，本就 1x")

# ---------------- C5 位图字体缺字形 ----------------
font = read(os.path.join(SRC, "render", "lwjgl", "Font.java"))
glyphs = set(re.findall(r"put\('(.)'", font))
lit = re.compile(r'"((?:[^"\\]|\\.)*)"')
missing = {}
for n, l in enumerate(g.splitlines(), 1):
    if re.search(r"HudText\.ascii\(|Font\.draw\(", l):
        for m in lit.finditer(l):
            for ch in m.group(1):
                if ch.isalnum() or ch in " \\":
                    continue
                if ch not in glyphs:
                    missing[ch] = missing.get(ch, 0) + 1
if missing:
    out("C5 字体字形", "FAIL", f"UI 文案里有未注册字形（渲染成 '?'）：{missing}")
else:
    out("C5 字体字形", "OK", f"已注册 {len(glyphs)} 个字形，覆盖全部 UI 文案")

# ---------------- C6 菜单行数 ↔ 测试索引 ----------------
mm = read(os.path.join(SRC, "core", "sim", "MenuModel.java"))
mt_lines = read(os.path.join(SRC, "core", "sim", "MenuTest.java")).splitlines()
rows = re.search(r"SETTINGS_ROWS\s*=\s*(\d+)", mm)
edit = re.search(r"SETTINGS_EDITABLE\s*=\s*(\d+)", mm)
if rows:
    R = int(rows.group(1)); bad6 = []
    for n, l in enumerate(mt_lines, 1):
        m = re.search(r"itemCount\(\)\s*==\s*(\d+)", l)
        if not m:
            continue
        # 判页必须用"同一条语句"，不能开窗口：窗口会把隔壁页面的断言误判进来（第一版即因此误报 3 条）。
        k = n
        while k > 1:
            prev = mt_lines[k - 2].rstrip()
            if prev == "" or prev.endswith(";") or prev.endswith("{") or prev.endswith("}") or prev.strip().startswith("//"):
                break
            k -= 1
        stmt = "\n".join(mt_lines[k - 1:n])
        if not re.search(r"Page\.SETTINGS|toSettings", stmt):
            EXEMPT.append(f"C6 MenuTest:{n} itemCount()=={m.group(1)} — 该语句属其他菜单页（{stmt.split('Page.')[-1][:12].split()[0] if 'Page.' in stmt else '?'}）")
            continue
        if int(m.group(1)) != R:
            bad6.append(f"MenuTest:{n} itemCount()=={m.group(1)} 但 SETTINGS_ROWS={R}")
    for b in bad6:
        out("C6 菜单行数", "FAIL", b)
    if not bad6:
        out("C6 菜单行数", "OK", f"SETTINGS_ROWS={R} / SETTINGS_EDITABLE={edit.group(1) if edit else '?'}；测试硬编码行数一致")

# ---------------- C7 阈值可达性 ----------------
susp = []
for p in java_files():
    if not os.path.basename(p).endswith("System.java"):
        continue
    for n, l in enumerate(read(p).splitlines(), 1):
        m = re.search(r"([\w.]+)\s*\+=\s*([\d.]+)f?\s*\*\s*([\w.]+)\s*-\s*([\d.]+)f", l)
        if m:
            coef, var, const = float(m.group(2)), m.group(3), float(m.group(4))
            susp.append(f"{rel(p)}:{n}: {l.strip()[:96]}   → 净增条件 {var} > {const / coef:.3f}")
for x in susp:
    out("C7 阈值可达性", "INFO", x + "（需人工确认该变量的可达范围）")
if not susp:
    out("C7 阈值可达性", "OK", "无 '系数×变量 − 常数' 型净增式")

# ---------------- C8 内容键 ↔ 运行时引用 ----------------
# 内容层（assets/**/*.json）有 schema 校验与测试，但**运行时尚未全部接线**（EVAL-3 已记录的系统性缺口）。
# 纪律同「SKIP 清单」：零引用的键必须**逐条登记并给出理由**，禁止新增静默死键；接线后须从表里删掉
# （删除后若键仍在 JSON 里而表里没有 → FAIL，即"幽灵条目"反向保护）。
# 每条豁免都带精确理由（C8 纪律：绝不许「静默死键」——未接线的内容键必须逐条登记理由）。
# 已接线并从本表移出的键（EVAL-3 P1 第一批，BeastDef/QuestDef 真的在消费）：
#   atk/hp/aggroRange/packSize/drop/item（beasts → BeastSystem 刷怪）/ nodes/text/on/next（quests → QuestEngine）
# 2026-09-17：**本表已清空** —— 曾经登记的最后 10 个键全部接线完毕：
#   7 个「空转子系统参数」→ 各自有了真正的消费系统（见 Simulation.registerDefaultSystems 末尾 5 行
#     + ErosionSystem 的 erosionRate）：
#       erosionRate        → ErosionSystem（概率缩放；rate 只缩放概率不改抽样次数 → 不位移子流）
#       ascensionThreshold → AscensionSystem（道行越阈即飞升；出厂 1e9 = 不可达）
#       haulRate           → HaulSystem（无人搬运入村心仓储环；需电力）
#       hungerRate         → HungerSystem（饱食度递减 + 归零扣血）
#       hpThreshold        → CaptureSystem（收编的血量比门槛）
#       orbItemCost        → CaptureSystem（收编消耗的捕捉球数）
#       wireRange          → WireSystem（灯具导线网的通电半径）
#   3 个「配方资源键」→ RecipeBook 显式声明语义（ore 3 + coal 2 → iron_bar 1），
#     TechTree 经 RecipeBook 校验并**响亮记录**未知键（不再静默跳过）。
# 空表不是"没人管"，而是"该表该有的终局状态"：接线一个就删一个，删到空即全部落地。
# 新键若要挂进内容层，必须要么被源码引用、要么重新登记在此并写清理由（禁止静默死键）。
CONTENT_KEY_EXEMPT = {}
try:
    import json as _json
    AST = os.path.join(ROOT, "assets")
    jkeys = set()
    for dp, _, fns in os.walk(AST):
        for f in fns:
            if not f.endswith(".json"):
                continue
            try:
                j = _json.load(io.open(os.path.join(dp, f), encoding="utf-8"))
            except Exception as e:
                out("C8 内容键", "FAIL", f"{os.path.relpath(os.path.join(dp, f), ROOT)} JSON 解析失败：{e}")
                continue
            def _walk(o):
                if isinstance(o, dict):
                    for k, v in o.items():
                        jkeys.add(k); _walk(v)
                elif isinstance(o, list):
                    for v in o: _walk(v)
            _walk(j)
    META = {"id", "name", "desc", "type", "version", "schema", "tags", "kind", "label", "title"}
    srcs = [read(p) for p in java_files()]
    dead, ghost = [], []
    for k in sorted(jkeys):
        if k in META:
            continue
        if sum(s.count('"' + k + '"') for s in srcs) == 0:
            if k not in CONTENT_KEY_EXEMPT:
                dead.append(k)
    for k in sorted(CONTENT_KEY_EXEMPT):
        if k not in jkeys:
            ghost.append(k)
    wired_already = [k for k in sorted(CONTENT_KEY_EXEMPT)
                     if sum(s.count('"' + k + '"') for s in srcs) > 0]
    if dead:
        out("C8 内容键", "FAIL", f"内容里有、代码零引用、且未登记的键（禁止静默死键）：{dead}")
    if wired_already:
        out("C8 内容键", "WARN", f"已接线、可从豁免表移出的键（勿让表永久变脏）：{wired_already}")
    if ghost:
        out("C8 内容键", "FAIL", f"豁免表里登记了、但 JSON 里已不存在的键（幽灵条目）：{ghost}")
    if not dead and not ghost:
        out("C8 内容键", "OK",
            f"{len(jkeys)} 个内容键：已接线或被豁免表逐条登记（当前未接线 {len(CONTENT_KEY_EXEMPT)} 个，"
            f"每条均带精确理由，见下方 INFO）")
    # 打印每条豁免的精确理由（C8 纪律：绝不许「静默死键」——未接线键的来由必须可见，不静默放过）
    for _k in sorted(CONTENT_KEY_EXEMPT):
        out("C8 豁免理由", "INFO", f"{_k} —— {CONTENT_KEY_EXEMPT[_k]}")
except Exception as e:
    out("C8 内容键", "FAIL", f"检查自身异常（视为失败，绝不静默放过）：{e!r}")

# ---------------- C9 UI 文案 ↔ 键位绑定 ----------------
# 两个历史上真出过的 bug 都属这一类：① 底部提示漏写 F3（功能无从发现）；
# ② 同一按键在两处文案里说成不同功能（用户照提示按 = 得到别的行为）。
KEYCODE_NAME = {
    32: "SPACE", 54: "6", 79: "O", 80: "P", 55: "7", 65: "A", 66: "B", 67: "C", 68: "D", 69: "E", 70: "F", 71: "G",
    72: "H", 74: "J", 75: "K", 76: "L", 81: "Q", 82: "R", 83: "S", 84: "T", 86: "V", 87: "W", 88: "X", 89: "Y", 90: "Z",
    256: "ESC", 257: "ENTER", 258: "TAB", 259: "BACKSPACE",
    290: "F1", 291: "F2", 292: "F3", 293: "F4", 294: "F5", 295: "F6", 296: "F7", 297: "F8",
    298: "F9", 299: "F10", 300: "F11", 301: "F12",
    340: "SHIFT", 341: "CTRL", 342: "ALT", 344: "SHIFT", 345: "CTRL", 346: "ALT",
}
STOP = {"the", "and", "for", "with", "hold", "repeat", "edge", "safe", "first", "third",
        "toggle", "panel", "menu", "target", "click", "heal", "art", "soul", "forge"}
mm_t = read(os.path.join(SRC, "core", "sim", "MenuModel.java"))
mblock = re.search(r"String\[\] CONTROLS\s*=\s*\{(.*?)\};", mm_t, re.S)
doc = {}      # key -> action（CONTROLS 页）
if mblock:
    for entry in re.findall(r'"([^"]*)"', mblock.group(1)):
        parts = re.split(r"\s{2,}", entry.strip(), maxsplit=1)
        if len(parts) == 2:
            for k in parts[0].upper().split("/"):
                doc[k.strip()] = parts[1].strip()
hint = {}     # key -> action（底部提示行）
for n, l in enumerate(g.splitlines(), 1):
    if "HudText.ascii(" not in l:
        continue
    for lit in re.findall(r'"([^"]*)"', l):
        if not re.search(r"(WASD|SPACE|ATTACK|attack|ESC|menu)", lit):
            continue
        for chunk in re.split(r"\s{2,}", lit.strip()):
            p2 = chunk.split(" ", 1)
            if len(p2) == 2 and p2[0].upper() in KEYCODE_NAME.values():
                hint.setdefault(p2[0].upper(), p2[1].strip())
conflict = []
for k in sorted(set(doc) & set(hint)):
    wa = set(w for w in re.findall(r"[a-z]+", doc[k].lower()) if len(w) >= 3 and w not in STOP)
    wb = set(w for w in re.findall(r"[a-z]+", hint[k].lower()) if len(w) >= 3 and w not in STOP)
    if wa and wb and not (wa & wb):
        conflict.append(f"{k}: CONTROLS 说「{doc[k]}」，底部提示说「{hint[k]}」")
for c in conflict:
    out("C9 文案↔键位", "FAIL", c + "   <-- 同一按键两处说法不一致")
# 已绑定键是否在任一处文案里出现过
used = set()
for m in re.finditer(r"n == (\d+)", g):
    c = int(m.group(1))
    if c in KEYCODE_NAME:
        used.add(KEYCODE_NAME[c])
documented = set(doc) | set(hint)
# 方向键在 CONTROLS 里是以"WASD"整体登记的 → 单个字母视为已覆盖。
KEY_GROUP_ALIAS = {"W": "WASD", "A": "WASD", "S": "WASD", "D": "WASD"}
# 明确豁免（必须带理由并打印，不许静默放过）。
C9_KEY_EXEMPT = {
    "ENTER": "菜单内确认键（导航语义，不是游戏操作键），按设计不进 CONTROLS 页",
}
undoc = []
for k in sorted(used):
    if k in documented or k in C9_KEY_EXEMPT:
        continue
    if KEY_GROUP_ALIAS.get(k, k) in documented:
        continue
    undoc.append(k)
for k in undoc:
    out("C9 文案↔键位", "FAIL", f"键 {k} 已绑定，但 CONTROLS 页与底部提示都没写 → 玩家不可能发现")
for k, why in sorted(C9_KEY_EXEMPT.items()):
    EXEMPT.append(f"C9 键 {k} — {why}")
if not conflict and not undoc:
    out("C9 文案↔键位", "OK",
        f"{len(used)} 个已绑定键全部有文档；{len(set(doc) & set(hint))} 个两处共有的键说法一致")
    out("C9 文案↔键位", "INFO",
        f"覆盖范围：CONTROLS {len(doc)} 条 / 底部提示 {len(hint)} 条；"
        f"只扫描 `n == <keycode>` 式绑定 —— 轮询式（glfwGetKey）与 SHIFT/CTRL 组合键不在内")

# ---------------- C10 sim 层静态可变状态 ----------------
# 静态可变字段是"同 JVM 多世界互相污染"的经典来源（本项目已有跨实例静态干扰的历史），
# 因此：① 全部列出；② 同名概念必须同值；③ 派生静态必须与其来源同步更新。
static_mut = []
for p in java_files():
    for n, l in enumerate(read(p).splitlines(), 1):
        if re.search(r"^\s*(?:public|private|protected)?\s*static\s+(?!final)"
                     r"(?:float|int|long|double|boolean|String)\s+\w+\s*(=|;)", l):
            static_mut.append(f"{rel(p)}:{n}: {l.strip()[:100]}")
concept = {}
for nm in ("MATERIAL_WORKS",):
    for p in java_files():
        for n, l in enumerate(read(p).splitlines(), 1):
            m = re.match(r"\s*public\s+static\s+(?:final\s+)?boolean\s+" + nm + r"\s*=\s*(\w+)\s*;", l)
            if m:
                concept.setdefault(nm, []).append((rel(p), m.group(1), "final" in l))
for nm, lst in concept.items():
    vals = set(v for _, v, _ in lst)
    fin = [f for f, _, isf in lst if isf]
    if len(vals) > 1:
        out("C10 静态可变", "FAIL", f"{nm} 同名概念却有不同值：{lst}")
    elif fin:
        out("C10 静态可变", "FAIL",
            f"{nm} 同名概念里 {fin} 是 final → 与其余几处不同质：那几个可以运行时打开，"
            f"这个永远关着（连带 if 分支被编译期消除）")
    else:
        out("C10 静态可变", "OK", f"{nm} 同名概念 {len(lst)} 处，值一致（{list(vals)[0]}）且都是可翻转开关")
dc = os.path.join(SRC, "core", "world", "DayCycle.java")
dt = read(dc)
m = re.search(r"void setDayLen\([^)]*\)\s*\{(.*?)\n    \}", dt, re.S)
if m:
    body = m.group(1)
    derived = [v for v in re.findall(r"^\s*public static int (\w+)\s*=\s*\w+ / 2;", dt, re.M)]
    miss = [v for v in derived if v not in body]
    if miss:
        out("C10 静态可变", "FAIL", f"DayCycle.setDayLen 未同步派生静态量 {miss} → 改 DAY LEN 后它们会变陈旧")
    else:
        out("C10 静态可变", "OK", f"DayCycle.setDayLen 已同步全部派生量 {derived or '（无）'}")
out("C10 静态可变", "INFO", f"sim/渲染层静态可变字段共 {len(static_mut)} 个（提示：新增前先问是否真需要跨实例共享）："
    + "; ".join(static_mut[:6]) + (" ..." if len(static_mut) > 6 else ""))

# ---------------- C11 内容产物 ↔ 渲染层消费者 ----------------
# 内容层最隐蔽的死法不是「键没人引用」（那是 C8），而是**解析器在、访问器在、运行时没人调**：
# 实测 presets 早已被 ContentRegistry 解析、`preset()` 访问器也有，`applyPreset` 却从未被 Game 调过
# —— grep 键名能搜到代码，看起来像接线了，其实整条路径是死的。
# 本检查断言：每类内容产物在 render/ 里必须有消费者（任一命中即算接线），否则 FAIL。
CONTENT_PRODUCT_CONSUMERS = {
    # 产物（ContentRegistry 访问器语义） -> 渲染层必须出现的消费点（任一）
    "presets(玩法预设)":    ("applyPreset", "applySelectedPreset"),
    "beasts(内容兽)":        ("beastDefs",),
    "quests(任务)":          ("QuestEngine",),
    "rules(规则)":           ("RuleEngine",),
    "techs(科技)":           ("TechTree",),
    "characters(捏脸)":      ("contentProvider",),
    "items(物品)":           ("itemBook", "Inventory"),   # Game: new Inventory(content.itemBook())
    # 2026-09-18 第 13 类：材料规格。消费者是 Game 的挖掘路径（硬度 + 按材料换碎屑粒子），
    # 两者都在渲染/输入层 → 零仿真漂移。加这条是因为"材料表建好却没人读"正是 C11 要防的死法。
    "materials(材料规格)":   ("materialBook",),
}
render_srcs = [read(p) for p in java_files() if os.sep + "render" + os.sep in os.path.normpath(p)]
if not render_srcs:
    out("C11 内容消费者", "WARN", "未找到 render/ 源文件（目录结构变化？）——检查失效")
else:
    rsrc = "\n".join(render_srcs)
    for prod, needles in sorted(CONTENT_PRODUCT_CONSUMERS.items()):
        if any(n in rsrc for n in needles):
            out("C11 内容消费者", "OK", f"{prod} → 渲染层已消费（{' / '.join(n for n in needles if n in rsrc)}）")
        else:
            out("C11 内容消费者", "FAIL",
                f"{prod} 在渲染层零消费者 = 整条内容路径是死的（解析器在、访问器在、没人调）")

# ---------------- C12 帧预算常量（引擎调参的实测依据，锁住不许悄悄改坏） ----------------
# 依据 2026-09-17 实测（core.sim.PerfProbe / core.sim.AblationProbe，160x112x160 / 98 系统）：
#   tick  median 5.13ms / p95 7.75ms / max 11.95ms
#     （同日先修「nonAirCells 每次跨 AIR 边界写入都做 O(百万) memmove」→ 由 19.65/28.02 降到这里）
#   分块预算 4 / 8 / 16 块每帧 -> worst 15.12 / 11.00 / 12.89 ms（噪声较大，8 仍是中位最优）
# 结论：MAX_STEPS_PER_FRAME 必须小（<=3；当前取 2 = p95 最坏 15.5ms 仍在 16.67ms 预算内）、
#       必须有单帧仿真时间盒、积压必须被钳制。
# 本检查把"调参结论"变成**可执行断言** —— 否则下一次有人把 5 改回来也没人拦。
FRAME_BUDGET = {
    "MAX_STEPS_PER_FRAME": (1, 3),
    "MAX_FRAME_SIM_MS":    (1.0, 16.0),
    "MAX_ACC_SEC":         (0.05, 0.30),
}
_c12_bad = []
for _nm, (_lo, _hi) in sorted(FRAME_BUDGET.items()):
    _m = re.search(r"static\s+final\s+(?:int|float)\s+" + _nm + r"\s*=\s*([\d.]+)f?\s*;", g)
    if not _m:
        _c12_bad.append(f"{_nm} 在 Game.java 里找不到（改名了？那必须同步本表）")
        continue
    _v = float(_m.group(1))
    if not (_lo <= _v <= _hi):
        _c12_bad.append(f"{_nm}={_v} 超出实测安全区间 [{_lo}, {_hi}]（改它请先重跑 PerfProbe 并更新本表）")
_world_src = read(os.path.join(SRC, "core", "world", "World.java"))
_ms = re.search(r"SHIFT_CHUNKS_PER_STEP\s*=\s*(\d+)\s*;", _world_src)
if not _ms:
    _c12_bad.append("World.SHIFT_CHUNKS_PER_STEP 找不到")
elif not (1 <= int(_ms.group(1)) <= 16):
    _c12_bad.append(f"SHIFT_CHUNKS_PER_STEP={_ms.group(1)} 超出 [1,16]（实测 16 的 commit 尖峰已到 8.48ms）")
for _b in _c12_bad:
    out("C12 帧预算", "FAIL", _b)
if not _c12_bad:
    out("C12 帧预算", "OK",
        f"MAX_STEPS_PER_FRAME={int(float(re.search(r'MAX_STEPS_PER_FRAME\s*=\s*([\d.]+)', g).group(1)))}"
        f" / MAX_FRAME_SIM_MS={re.search(r'MAX_FRAME_SIM_MS\s*=\s*([\d.]+)', g).group(1)}"
        f" / MAX_ACC_SEC={re.search(r'MAX_ACC_SEC\s*=\s*([\d.]+)', g).group(1)}"
        f" / SHIFT_CHUNKS_PER_STEP={_ms.group(1)}"
        f"（依据：tick p95 4.43ms；分块 8 -> worst 11.00ms）")

# ---------------- C13 排序集合元素类型 ----------------
# 判据：`new TreeSet<int[]>` / `new TreeMap<int[],...>` —— **显式数组类型实参**。数组永远不是 Comparable，
# 未传 Comparator 时首次 add()/put() 即抛 ClassCastException。这是"默认关的开关一打开就崩"的隐患类
# （真实案例：CivilizationSystem.autonomy() 的 `new TreeSet<int[]>(cellsOfType(...))`，MATERIAL_WORKS 一开就炸）。
# 只匹配显式数组实参（`new TreeSet<>(...)` 菱形无法静态定类型 → 不报，避免噪声）；
# 行内出现 Comparator / lambda(->) 视为"已提供比较器" → 放过。cellsOfType 本就升序，通常无需再包排序集合。
_sorted_arr = []
_pat_c13 = re.compile(r"new\s+(?:java\.util\.)?Tree(?:Set|Map)\s*<\s*[\w.]+\s*\[\s*\]")
for p in java_files():
    for n, l in enumerate(read(p).splitlines(), 1):
        code = l.split("//")[0]
        if "Comparator" in code or "->" in code:
            continue
        if _pat_c13.search(code):
            _sorted_arr.append(f"{rel(p)}:{n}: {l.strip()[:110]}")
if _sorted_arr:
    for x in _sorted_arr:
        out("C13 排序集合元素类型", "FAIL",
            x + "   <-- 数组非 Comparable，未传 Comparator 时 add() 会抛 ClassCastException"
                "（cellsOfType 已升序，无需再包 TreeSet）")
else:
    out("C13 排序集合元素类型", "OK",
        "无'TreeSet/TreeMap 装显式数组类型且未传 Comparator'的隐患")

# ---------------- 输出 ----------------
order = {"FAIL": 0, "WARN": 1, "INFO": 2, "OK": 3}
report.sort(key=lambda r: (order[r[1]], r[0]))
print("=" * 100)
print("一次性静态不变量审计（只读）   " + ROOT)
print("=" * 100)
for chk, lv, msg in report:
    print(f"[{lv:4s}] {chk}  {msg}")
if EXEMPT:
    print("-" * 100)
    print("显式排除（带理由，避免静默放过）：")
    for e in sorted(set(EXEMPT)):
        print("  · " + e)
print("=" * 100)
n_fail = sum(1 for r in report if r[1] == "FAIL")
n_warn = sum(1 for r in report if r[1] == "WARN")
print(f"FAIL {n_fail} / WARN {n_warn} / 合计 {len(report)} 项")
sys.exit(1 if n_fail else 0)
