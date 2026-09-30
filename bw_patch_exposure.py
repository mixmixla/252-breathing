#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""字节级 GLSL 注入：给 world FS 与 sky FS 的 `grade()` 加自动曝光乘子。

`grade()` 的入参 `s` 是**色调映射之前**的线性-ish 颜色 ⇒ 曝光就乘在这里（UE 里也是在
Exposure 之后、ToneMapping 之前）。默认 `uExposure==1` ⇒ 逐字节等于改动前。

依赖：先在 Java 侧跑过 bw_patch_dither.py（本脚本的锚点含 dither 注入后的 `uniform float uDither;`）。
跑法：python bw_patch_exposure.py
"""
import io
import sys

PATH = "src/render/lwjgl/Game.java"

A = "uniform float uDither;\\nvec3 grade(vec3 s){"
N = "uniform float uDither;uniform float uExposure;\\nvec3 grade(vec3 s){s*=uExposure;"
EXPECT = 2   # world FS + sky FS


def main():
    s = io.open(PATH, encoding="utf-8", newline="").read()
    if "uniform float uExposure;" in s:
        print("ABORT: GLSL 'uniform float uExposure;' already present (already patched?)")
        return 1
    if s.count(A) != EXPECT:
        print("ABORT: anchor x%d (want %d) —— 多半是 dither 补丁还没跑" % (s.count(A), EXPECT))
        return 1
    s = s.replace(A, N)
    io.open(PATH, "w", encoding="utf-8", newline="").write(s)
    print("OK: exposure injected into %d grade() (decl + multiply)" % EXPECT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
