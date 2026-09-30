#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""字节级 GLSL 注入（**只改 world FS**）：A2C 覆盖率 —— 修 discard 镂空边缘的 MSAA 无效。

问题：`discard` 是逐像素二值的，MSAA 样本共享同一个片元着色结果 ⇒ 树叶/草镂空边缘永远硬台阶。
做法：片元里用 **mip1 的 alpha** 估覆盖率（atlas 烘焙时 mip 的 alpha 走 4-tap 线性平均 ⇒ 天然是
分数覆盖），写进 `fc.a`，配合 `GL_SAMPLE_ALPHA_TO_COVERAGE` 把硬台阶换成软过渡。

为什么用 mip1 而不是手写 4-tap：mip1 就是 level-0 的 4-tap 盒平均，**一次采样**抵四次，
且不引入额外采样器状态。只对 `tx.a>0.98`（不透明材质）生效 ⇒ 大块半透明面（水/玻璃）
不会被 A2C 打成噪点（那是 A2C 的经典副作用）。

恒等：`uA2C==0` ⇒ 不改动 alpha ⇒ 与改动前一致（`-Dbw.a2c=0` 自证）。
⚠️ 与所有 GLSL 注入同样的坑：换行必须写成 Java 源码里的转义序列 `\\n`（两个字符），
   写成真换行会把 Java 字符串字面量截断（本次已踩过一次）。

跑法：python bw_patch_a2c.py
"""
import io
import sys

PATH = "src/render/lwjgl/Game.java"

A1 = "float voxOcc(vec3 p){"
N1 = "uniform float uA2C;\\nfloat voxOcc(vec3 p){"

A2 = "if(tx.a<0.02) discard;"
N2 = ("if(tx.a<0.02) discard;\\n"
      "  float covA=tx.a;if(uA2C>0.5&&tx.a>0.98){float m1=textureLod(uTex,vUv,1.0).a;"
      "if(m1<0.98&&m1>0.02)covA=m1;}")

A3 = "fc=vec4(clamp(col,0.0,1.0),tx.a);"
N3 = "fc=vec4(clamp(col,0.0,1.0),covA);"


def main():
    s = io.open(PATH, encoding="utf-8", newline="").read()
    if "uniform float uA2C;" in s:
        print("ABORT: GLSL 'uniform float uA2C;' already present (already patched?)")
        return 1
    for a in (A1, A2, A3):
        if s.count(a) != 1:
            print("ABORT: anchor x%d (want 1): %s" % (s.count(a), a[:48]))
            return 1
    s = s.replace(A1, N1).replace(A2, N2).replace(A3, N3)
    io.open(PATH, "w", encoding="utf-8", newline="").write(s)
    print("OK: A2C injected (uniform + coverage + alpha write)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
