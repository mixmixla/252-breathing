# -*- coding: utf-8 -*-
"""
bw_patch_ao.py —— 给 Game.java 的 world FS 注入「大尺度占用 AO（≈UE DFAO）」。

为什么用脚本而不是 Edit 工具：world FS 是**单行超长 Java 字符串字面量**（内部换行 = 字节 5C 6E），
在编辑器的"行"视角下是一整行，做局部替换极易写坏转义。这里按字节 find/replace，
并把"必须恰好命中 1 次"做成硬断言（命中 0/多次 → 报错退出，绝不静默）。

设计（三条硬约束，来自 voxel-render-art-pass 规则 26/15）：
  1. **复用已有的 3D 占用纹理**（`uShadowTex` / `uShadowSize` / `voxOcc()` 已在 world FS 里）
     ⇒ 零新增 FBO / 零新增 pass / 零新增纹理。
  2. 采样用 `texelFetch`（规则 26.4：归一化 + NEAREST 仍可能插值，texelFetch 绕过 filter）。
  3. 只乘 **skyTerm**（天空/太阳通道），**不乘 lampV**（火把/块光）—— 否则洞穴里的火把会被"大尺度遮蔽"吃掉。
  4. 入口即早退 `k<=0.002` ⇒ `-Dbw.ao=0` 时 **恒等返回 1.0**，与改动前逐字节相同（A/B 的零基线）。
"""
import sys

SRC = r"src/render/lwjgl/Game.java"

# 6 条半球方向 × 2 段步长（2.0 / 4.5 格），避开自身格（先沿法线抬 0.5）。
# 归一化用 1/9（6 方向 × (0.5+1.0)）⇒ occ ∈ [0,1]；下限钳 0.25 防死黑。
AO_FN = (
    "float voxAO(vec3 wp,vec3 n,float dist){"
    "float k=uAoStrength*(1.0-smoothstep(12.0,56.0,dist));"
    "if(k<=0.002)return 1.0;"
    "vec3 t1=(abs(n.y)<0.9)?normalize(cross(n,vec3(0.0,1.0,0.0))):vec3(1.0,0.0,0.0);"
    "vec3 t2=cross(n,t1);"
    "vec3 o=wp+n*0.5;"
    "float occ=0.0;"
    "for(int i=0;i<6;i++){"
    "float a=float(i)*1.0472;"
    "vec3 d=normalize(n*0.75+(t1*cos(a)+t2*sin(a))*0.66);"
    "occ+=voxOcc(o+d*2.0)*0.5+voxOcc(o+d*4.5);"
    "}"
    "occ*=0.1111111;"
    "return clamp(1.0-occ*k,0.25,1.0);"
    "}"
)

# (说明, 旧字节串, 新字节串, 期望命中次数)
PATCHES = [
    (
        "world FS: 声明 uAoStrength",
        b"uniform float uShadowSoft;",
        b"uniform float uShadowSoft;uniform float uAoStrength;",
        1,
    ),
    (
        "world FS: 在 voxOcc 之后、sunShadow 之前插入 voxAO()",
        b"return texelFetch(uShadowTex,ip,0).r;}\\nfloat sunShadow(",
        b"return texelFetch(uShadowTex,ip,0).r;}\\n" + AO_FN.encode("ascii") + b"\\nfloat sunShadow(",
        1,
    ),
    (
        "world FS: 把 AO 乘进 skyTerm（不动 lampV）",
        b"vec3 col=tx.rgb*vCol*skyTerm;",
        b"float aoL=voxAO(vWorld,n,dist);skyTerm*=aoL;vec3 col=tx.rgb*vCol*skyTerm;",
        1,
    ),
]


def main():
    with open(SRC, "rb") as f:
        data = f.read()
    orig = len(data)
    for desc, old, new, want in PATCHES:
        n = data.count(old)
        if n != want:
            print("FAIL  %s\n      命中 %d 次，期望 %d 次 —— 中止（不改文件）" % (desc, n, want))
            return 1
        data = data.replace(old, new, 1 if want == 1 else n)
        print("OK    %s" % desc)
    # 纪律（规则 14）：GLSL 字面量里绝不能出现双引号（会截断 Java 字符串）。
    # 这里只做一次全局安全告警：新注入的片段自身不得含双引号。
    for frag in (AO_FN,):
        if '"' in frag:
            print("FAIL  注入片段含双引号 —— 会截断 Java 字面量，中止")
            return 1
    with open(SRC, "wb") as f:
        f.write(data)
    print("字节 %d -> %d (Δ%+d)" % (orig, len(data), len(data) - orig))
    return 0


if __name__ == "__main__":
    sys.exit(main())
