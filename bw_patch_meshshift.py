# -*- coding: utf-8 -*-
"""
bw_patch_meshshift.py —— 第三十八批：给两条**消费区块几何**的顶点着色器加 `uChunkShift`。

为什么必须走脚本：这两个 VS 都是**单行超长 Java 字符串字面量**（内部换行是字节 5C 6E 转义），
用编辑器工具做局部替换极易写坏转义。这里按字节 find/replace，并把"必须恰好命中 N 次"
做成硬断言（0 次或多次 → 报错退出，绝不静默）。

改动语义（详见 Game.updateMeshes / Chunk.meshOffX 的注释）：
  顶点是烘焙在**窗口本地**坐标系里的。窗口平移后，留在窗内的块内容换了本地位置，
  但**网格可以复用** —— 只要把"烘焙帧 → 当前帧"的整数位移加回顶点即可。
  ⇒ VS 里 `pos.xz` 与 `vWorld.xz` 都要加 uChunkShift；偏移恒为 0 时逐字节等价于改动前。
  ⚠️ 偏移必须加在**风摆之后**（风摆相位用的是 aPos.x，那是顶点原始坐标，
     若先加偏移会改变相位 → 植被摆动相位随平移漂移）。
"""
import io
import sys

SRC = r"src/render/lwjgl/Game.java"

PATCHES = [
    # ---------- 1. terrain VS（initWorldShader 的 `string`）----------
    (
        "terrain VS: 声明 uChunkShift",
        b"uniform vec3 uCamPos;out vec3 vCol;out vec3 vN;",
        # 紧跟 uCamPos 声明，避免碰 #version 那一行的转义邻域
        b"uniform vec3 uCamPos;uniform vec2 uChunkShift;out vec3 vCol;out vec3 vN;",
        1,
    ),
    (
        "terrain VS: vWorld 用当前帧坐标（雾/阴影/细节贴图都读它）",
        b"vWorld=aPos;vUv=aUv;",
        # vWorld 被 FS 用于：距离雾、细节贴图 fract(vWorld.xz*0.31)、阴影体素 floor(vWorld)
        # ⇒ 它必须是"当前帧的窗口本地坐标"，否则平移后雾/阴影会整体错位。
        b"vWorld=aPos+vec3(uChunkShift.x,0.0,uChunkShift.y);vUv=aUv;",
        1,
    ),
    (
        "terrain VS: 顶点位置加偏移（在风摆之后）",
        b"gl_Position=uVP*vec4(pos,1.0);}",
        # pos=aPos 已在前面被风摆改过；此处再叠加网格帧偏移 —— 位置平移与摆动互不干扰。
        b"pos.x+=uChunkShift.x;pos.z+=uChunkShift.y;gl_Position=uVP*vec4(pos,1.0);}",
        1,
    ),
    # ---------- 2. bloom 发光源 VS（BLOOM_EMISSIVE_VS）----------
    (
        "emissive VS: 声明 uChunkShift 并平移位置",
        b"uniform mat4 uVP;out vec3 vCol;void main(){vCol=aCol;gl_Position=uVP*vec4(aPos,1.0);}",
        # 发光源网格与主网格共用同一份顶点数据 ⇒ 不跟着平移就会"灯在旧位置、墙在新位置"。
        b"uniform mat4 uVP;uniform vec2 uChunkShift;out vec3 vCol;"
        b"void main(){vCol=aCol;gl_Position=uVP*vec4(aPos+vec3(uChunkShift.x,0.0,uChunkShift.y),1.0);}",
        1,
    ),
]


def main():
    with io.open(SRC, "rb") as f:
        data = f.read()
    for desc, old, new, expect in PATCHES:
        n = data.count(old)
        if n != expect:
            sys.stderr.write(
                "FAIL: %s\n  期望命中 %d 次，实际 %d 次（锚点字节串=%r）\n"
                % (desc, expect, n, old[:80]))
            return 1
        data = data.replace(old, new)
        print("ok   %s" % desc)
    with io.open(SRC, "wb") as f:
        f.write(data)
    print("PATCH-MESHSHIFT OK: %d 处" % len(PATCHES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
