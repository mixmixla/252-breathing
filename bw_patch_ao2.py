# -*- coding: utf-8 -*-
"""
bw_patch_ao2.py —— 修正 voxAO 的"自遮蔽"：半球方向与抬升偏移改用**几何法线**。

为什么：第一版把 `n`（= 几何法线 `nn` 与法线贴图按 55/45 混合后的**扰动法线**）喂给 voxAO。
扰动法线会侧偏 → 半球射线斜着打回自身方块所在格 → **平坦地面被均匀压暗**（差异图上大片红）。
AO 是大尺度**几何**效应，应当围绕几何法线 `nn` 计算；同时把抬升偏移 0.5 → 0.75 格，
确保起点在自身格之外。
"""
import sys

SRC = r"src/render/lwjgl/Game.java"

PATCHES = [
    ("AO: 半球方向改用几何法线 nn（不是法线贴图扰动后的 n）",
     b"float aoL=voxAO(vWorld,n,dist);",
     b"float aoL=voxAO(vWorld,nn,dist);", 1),
    ("AO: 抬升偏移 0.5 -> 0.75 格（确保起点在自身格之外）",
     b"vec3 o=wp+n*0.5;",
     b"vec3 o=wp+n*0.75;", 1),
]


def main():
    with open(SRC, "rb") as f:
        data = f.read()
    orig = len(data)
    for desc, old, new, want in PATCHES:
        n = data.count(old)
        if n != want:
            print("FAIL  %s\n      命中 %d 次，期望 %d 次 —— 中止" % (desc, n, want))
            return 1
        data = data.replace(old, new)
        print("OK    %s" % desc)
    with open(SRC, "wb") as f:
        f.write(data)
    print("字节 %d -> %d (Δ%+d)" % (orig, len(data), len(data) - orig))
    return 0


if __name__ == "__main__":
    sys.exit(main())
