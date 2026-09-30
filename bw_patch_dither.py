#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""字节级 GLSL 注入：给 world FS 与 sky FS 的 `grade()` 末尾加显示域 dither（修天空/暗部色带）。

纪律（照 bw_patch_expo.py / bw_patch_ao.py 范式）：
- 每个锚点必须命中**精确次数**，否则中止（宁可失败也不留半成品）。
- dither 源只用 `gl_FragCoord`（确定性：同像素恒同值）⇒ 截图仍逐字节可复现。
  **不能用 uTime**：那会让同一像素随帧变化，破坏 A/B 仪器契约。
- `uDither==0` 时该项为 0 ⇒ 与改动前**逐字节相同**（恒等由 `-Dbw.dither=0` 自证）。

跑法：python bw_patch_dither.py
"""
import io
import sys

PATH = "src/render/lwjgl/Game.java"
ANCHOR_GRADE = "vec3 grade(vec3 s){"
ANCHOR_TAIL = "o=mix(vec3(lumM),o,1.10);return clamp(o,0.0,1.0);}"

NEW_GRADE = "uniform float uDither;\\nvec3 grade(vec3 s){"
NEW_TAIL = "o=mix(vec3(lumM),o,1.10);o+=(h31(vec3(gl_FragCoord.xy,0.0))-0.5)*uDither;return clamp(o,0.0,1.0);}"

EXPECT_GRADE = 2   # world FS + sky FS
EXPECT_TAIL = 2


def main():
    s = io.open(PATH, encoding="utf-8", newline="").read()
    ng, nt = s.count(ANCHOR_GRADE), s.count(ANCHOR_TAIL)
    if ng != EXPECT_GRADE or nt != EXPECT_TAIL:
        print("ABORT: anchors count grade=%d(want %d) tail=%d(want %d)" % (ng, EXPECT_GRADE, nt, EXPECT_TAIL))
        return 1
    if "uniform float uDither;" in s:
        print("ABORT: GLSL 'uniform float uDither;' already present (already patched?)")
        return 1
    s = s.replace(ANCHOR_GRADE, NEW_GRADE)
    s = s.replace(ANCHOR_TAIL, NEW_TAIL)
    io.open(PATH, "w", encoding="utf-8", newline="").write(s)
    print("OK: dither injected (uniform decl x%d, tail x%d)" % (ng, nt))
    return 0


if __name__ == "__main__":
    sys.exit(main())
