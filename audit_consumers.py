# -*- coding: utf-8 -*-
"""消费者审计（只读，秒级）：回答「这批新加的成员，到底有没有人用」。

用法：
    python audit_consumers.py [base-rev]      # 默认 base-rev = HEAD~1
    python audit_consumers.py --all           # 扫全部 src/（慢，用于摸底）

退出码：发现「零调用」成员 → 1（可直接接进 build_runner 做前置审计）。

## 为什么需要它（本项目已四次栽在同一处）

「声称接好了」≠「真的接好了」，而且必须是**可 grep 的事实**：

| 事故 | 形态 |
|---|---|
| `ContentRegistry.modules()` | 代码在、没人调 → 模块参数永远送不到 World.config |
| `World.nonAirCells` | 代码在、没人调 → 派生索引白烧 76.6% 的 tick |
| `WireSystem.isPowered` | **注释说有人调、其实没有** → 下一个人以为这里已接好 |
| `RecipeBook.craft` | **只有门禁在调** → 门禁测的路径生产不走 = 假覆盖 |

所以本工具把这条纪律机械化：**逐个公开成员 grep 调用点**，并区分
生产 / 仅门禁·探针 / 仅自身（私有助手）/ 零调用。

## 判据（划界，避免误报与过度清理）

- `OK(生产)`：生产代码（src/render、src/core 的非 Test/Probe 文件）里有调用 → 留着。
- `TEST-ONLY`：只有门禁/探针在调。**谓词/校验类可以**（如 `resourceKeys()` 被门禁断言）；
  **操作类不行** —— 那说明生产走的是另一条路，得对齐（见 `RecipeBook.craft` 的教训）。
- `SELF-ONLY`：只在声明文件内被调 → 私有助手，正常。
- `*** DEAD ***`：全仓零调用 → 删掉，或给出理由（如反射/序列化入口）。

已知误报（正则按 `名字(` 匹配，故漏计字段与构造器）：
  `AscensionSystem.ascensions`（私有字段）、`RecipeBook.RecipeBook`（私有构造器）。
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "src")
SKIP_NAMES = {"name", "update", "toString", "equals", "hashCode", "main", "run"}

DECL = re.compile(r"^\s*(?:public|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\],.\s]+?\s+(\w+)\s*\(")


def git(*args):
    return subprocess.run(["git"] + list(args), cwd=ROOT, capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def changed_files(base):
    out = git("diff", "--name-only", base, "HEAD")
    return [f for f in out.split() if f.endswith(".java")]


def added_members(base):
    """只取「本次 diff 新增的行」里的成员声明 —— 这才是「我这次加的有没有死代码」。

    为什么需要它：只按"改动的文件"扫会把**既有成员**一起报出来（本轮实测报了
    Player.isAttacking / World.echoNarrative 等 6 个），那些不是本次引入的，
    混在一起会淹没真正要看的信号。
    """
    out = git("diff", "-U0", base, "HEAD")
    cur, res = None, {}
    for ln in out.splitlines():
        if ln.startswith("+++ b/"):
            cur = ln[6:].strip()
            continue
        if not ln.startswith("+") or ln.startswith("+++"):
            continue
        if cur is None or not cur.endswith(".java"):
            continue
        m = DECL.match(ln[1:])
        if m:
            res.setdefault(cur, set()).add(m.group(1))
    return res


def all_files():
    res = []
    for dp, _, fns in os.walk(SRC):
        for f in fns:
            if f.endswith(".java"):
                res.append(os.path.relpath(os.path.join(dp, f), ROOT).replace("\\", "/"))
    for f in os.listdir(os.path.join(ROOT, "tools")):
        if f.endswith(".java"):
            res.append("tools/" + f)
    return res


def main():
    args = [a for a in sys.argv[1:]]
    base = next((a for a in args if not a.startswith("--")), "HEAD~1")
    added = None
    if "--all" in args:
        files = all_files()
    else:
        added = added_members(base)
        files = sorted(added.keys())
    if not files:
        print("没有改动/新增的 .java 文件（base 与 HEAD 相同？）")
        return 0

    texts = {}
    for f in all_files():
        p = os.path.join(ROOT, f)
        try:
            texts[f] = io.open(p, encoding="utf-8", errors="replace").read()
        except IOError:
            pass

    dead = []
    print("=== 消费者审计（base=%s）：%d 个改动文件 ===" % (base if added is not None else "--all", len(files)))
    for f in files:
        if f not in texts:
            continue
        if added is not None:
            members = set(added.get(f, ()))          # 只看本次新增的成员
        else:
            members = set()
            for ln in texts[f].splitlines():
                m = DECL.match(ln)
                if m:
                    members.add(m.group(1))
        members -= SKIP_NAMES
        if not members:
            continue
        rows = []
        for nm in sorted(members):
            pat = r"\b" + re.escape(nm) + r"\s*\("
            prod = test = self_ = 0
            for g, t in texts.items():
                n = len(re.findall(pat, t))
                if n == 0:
                    continue
                if g == f:
                    self_ = max(0, n - 1)          # 减去声明处
                elif os.path.basename(g).endswith(("Test.java", "Probe.java")):
                    test += n
                else:
                    prod += n
            if prod:
                tag = "OK(生产)"
            elif test:
                tag = "TEST-ONLY"
            elif self_:
                tag = "SELF-ONLY"
            else:
                tag = "*** DEAD ***"
                dead.append(f + " :: " + nm)
            rows.append((nm, prod, test, self_, tag))
        print("\n-- " + f)
        for nm, prod, test, self_, tag in rows:
            print("   %-24s prod=%-3d test=%-3d self=%-3d  %s" % (nm, prod, test, self_, tag))

    if dead:
        print("\n=== 零调用成员 %d 个（删掉，或给出理由）===" % len(dead))
        for d in dead:
            print("  " + d)
        return 1
    print("\n没有零调用成员。（TEST-ONLY 需人工判断：谓词/校验可以，操作类要对齐生产路径）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
