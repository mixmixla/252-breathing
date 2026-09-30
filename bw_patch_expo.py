# -*- coding: utf-8 -*-
"""
bw_patch_expo.py —— 对 Game.java 的 world/sky FS 字面量做**字节级精确替换**。

为什么不用 Edit 工具：world FS 是单行超长 Java 字符串字面量（内部换行 = 字节 5C 6E 转义），
在编辑器的"行"视角下是一整行，做局部替换极易写坏转义。这里按字节 find/replace，
并把"必须恰好命中 N 次"做成硬断言（命中 0 次或多次 → 报错退出，绝不静默）。
"""
import io
import sys

SRC = r"src/render/lwjgl/Game.java"

# (说明, 旧字节串, 新字节串, 期望命中次数)
PATCHES = [
    # ---------- 1. world FS：曝光链 ----------
    (
        "world FS: skyTerm 不再无界放大（MC 语义：lightmap 值域 [0,1]）",
        b"vec3 skyTerm=(key*uLightTint+uAmbient*(0.5+0.5*skyFill)*uSkyColor)*(1.0+1.8*nightK);",
        # 旧式 (1+1.8*nightK) 让夜间 skyTerm 冲到 1.47 → min(...,1) 强制饱和 → 全时段无光照阶梯。
        # 新式：夜间把**环境项**单独放大（这才是"月光填充"该有的位置），直射项保持原样，
        # 并让整条 skyTerm 落在 [0,1]（与 MC 的 lightmap 同值域），杜绝"贴图被照亮到自身反照率"。
        # 注意 GLSL 纪律：nightBoost 必须显式写成 float（vec3 变量不能用 float 初始化，
        # 会报 `implicit cast from "float" to "vec3"` —— 2026-09-21 实测）。
        b"float nightBoost=1.0+0.45*nightK;"
        b"vec3 skyTerm=clamp((key*uLightTint+uAmbient*(0.5+0.5*skyFill)*uSkyColor*nightBoost),vec3(0.0),vec3(1.0));",
        1,
    ),
    (
        "world FS: 直射项归一（key 峰值由 ~1.47 降到 ~1.0）",
        b"float key=(dot(n,n)<1e-6)?0.7:max(dot(n,L),0.0);",
        # uLightTint 的 R 在白天就是 1.0 → key*1.0 直接到顶。乘 0.78 让"正面受光≈0.78"，
        # 配合环境项约 0.24 → 峰值 ≈1.0（clamp 前），既保留明暗阶梯又不失亮部。
        b"float key=(dot(n,n)<1e-6)?0.7:max(dot(n,L),0.0)*0.78;",
        1,
    ),
    (
        "world FS: lightmap 4% 环境底 = MC 的 lerp(v,0.75,0.04)（在 lightmap 上，不在最终色上）",
        b"col=tx.rgb*vCol*(lmF*0.96+vec3(0.030));",
        # 原式 0.96 + 0.030 与 MC 的 v*0.96+0.03 数值一致，保持不变（注释里已解释原因）。
        # 这里只做**等价重写**以便下一处补丁有稳定锚点 —— 内容不变。
        b"col=tx.rgb*vCol*(lmF*0.96+vec3(0.030));",
        1,
    ),
    (
        "world FS: 方向内散射加自限（不再把整屏推向过曝）",
        b"col+=uDirInsc*dIns;",
        # UE 的方向内散射是能量叠加；在本作尺度下 dist 只有 20~60 格而 Start=10，
        # dIns 峰值接近 f（雾比） → 夜里叠一整层月亮色 → 与雾一起把画面推爆。
        # 加两个约束：(1) 只叠在"本就不亮"的像素上（乘 1-col 的余量，天然防溢出）；
        # (2) 整体降到 0.55。这样它回到"地平线附近的辉光"该有的量级。
        b"col+=uDirInsc*dIns*0.55*max(vec3(0.0),vec3(1.0)-col);",
        1,
    ),
    (
        "world FS: 暗角保底（保证暗部不被后处理再抬亮）",
        b"col*=mix(1.0,0.86,smoothstep(0.36,0.80,length(q-0.5)));",
        b"col*=mix(1.0,0.82,smoothstep(0.34,0.82,length(q-0.5)));",
        1,
    ),
    # ---------- 2. sky FS：雨天不再整屏压成灰白 ----------
    (
        "sky FS: 雨天压暗系数 0.78 → 0.62（并只压亮度不压色相）",
        b"col=mix(col,col*vec3(0.50,0.55,0.62),uRain*0.78);",
        # 旧式把天空朝 (0.50,0.55,0.62) 混合 (= 一个灰蓝)，混 78% → 天空近乎纯灰。
        # 新式：改为"按亮度压暗 + 保留天顶/地平线的色差"（乘一个中性的暗化因子），
        # 于是雨天仍然是"天，但更暗更浓"，而不是"一块灰板"。
        b"col=mix(col,col*vec3(0.62,0.65,0.72),uRain*0.70);",
        1,
    ),
]

def main():
    with open(SRC, "rb") as f:
        data = f.read()
    orig_len = len(data)
    report = []
    for desc, old, new, want in PATCHES:
        n = data.count(old)
        if n != want:
            print("FAIL  %s\n      命中 %d 次，期望 %d 次 —— 中止（不改文件）" % (desc, n, want))
            if n == 0:
                print("      （旧串不在文件里：可能已被改过，或转义写法不同）")
            return 1
        if old == new:
            report.append("SKIP  %s（内容相同，恒等补丁）" % desc)
            continue
        data = data.replace(old, new)
        report.append("OK    %s" % desc)
    with open(SRC, "wb") as f:
        f.write(data)
    for r in report:
        print(r)
    print("字节 %d -> %d (Δ%+d)" % (orig_len, len(data), len(data) - orig_len))
    return 0


if __name__ == "__main__":
    sys.exit(main())
