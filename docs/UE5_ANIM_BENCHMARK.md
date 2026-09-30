# UE5 动画系统对标审计 · UE5 Animation Benchmark

> 对标对象：本地 UE5 完整源码（`D:\study\开源项目\Engine`，`Source/Runtime/Engine` + `AnimGraphRuntime`）。
> 目的：**只学"机制原理"，不搬"工程实现"**。UE5 是为千万级资产的 AAA 引擎，其动画管线对一个手写
> 体素小引擎而言，绝大部分是**过度设计**（见 §4 判定表）。
> 本轮落地范围：`REFERENCE_STUDY.md §7` 里标为"最粗糙"的三项 —— **root-motion / 混合树 / 上下半身遮罩**。

---

## 1. 三块原语的源码依据

### 1.1 Root Motion —— 区间累积

**UE5 出处**：`UAnimMontage::ExtractRootMotionFromTrackRange(StartTrackPosition, EndTrackPosition, Context)`
（`Engine/Private/Animation/AnimMontage.cpp`）

```cpp
FTransform UAnimMontage::ExtractRootMotionFromTrackRange(float StartTrackPosition,
                                                        float EndTrackPosition,
                                                        const FAnimExtractContext& Context) const {
    FRootMotionMovementParams RootMotion;
    // For now assume Root Motion only comes from first track.
    if (SlotAnimTracks.Num() > 0) {
        const FAnimTrack& SlotAnimTrack = SlotAnimTracks[0].AnimTrack;
        // We can deal with looping animations, or multiple animations. So we break those up
        // into sequential operations. (Animation, StartFrame, EndFrame) so we can then
        // extract root motion sequentially.
        ExtractRootMotionFromTrack(SlotAnimTrack, StartTrackPosition, EndTrackPosition, Context, RootMotion);
    }
    return RootMotion.GetRootMotionTransform();
}
```

**核心语义（学到的东西）**
1. root motion = **根骨在 [start,end] 区间的位移/旋转增量**，抽出来交给上层角色运动组件去积分；
2. **分段顺序累积**：长区间被拆成若干子区间依次累积 —— 这直接给出「区间可加性」这条性质；
3. 只取第一条轨道（root 轨道）—— 我们的 `RootMotion` 用 `rootJoint` 名（默认 `"root"`）做同样的事。

**我们的实现**：`core/anim/RootMotion.java`
- `extract(t0,t1)` → `{dx,dy,dz,dyawDeg}`；`accumulate(...)` 消费式累加；
- 朝向差走**最短路径**（对应 UE5 `DeltaRotator` 的 quaternion shortest-arc）；
- `drivesTranslation()/drivesRotation()` 让上层判断是否走 root-motion 分支。

### 1.2 混合树 —— 二维参数空间 + 重心坐标三角插值

**UE5 出处**：
- `UBlendSpace`（`Engine/Private/Animation/BlendSpace.cpp`）—— 二维参数平面（X=速度 / Y=朝向）；
- `FBlendSpaceGrid::FindTriangleThisPointBelongsTo(TestPoint, OutBarycentricCoords, ...)`
  （`Engine/Private/Animation/BlendSpaceHelpers.cpp`）：

```cpp
// Calculate distance from point to triangle and sort the triangle list accordingly
SortedTriangles.Sort([](const FIndexAndDistance &A, const FIndexAndDistance &B) {
    return A.Distance < B.Distance; });
for (const FIndexAndDistance& SortedTriangle : SortedTriangles) {
    FTriangle* Triangle = TriangleList[SortedTriangle.Index];
    FVector Coords = GetBaryCentric2D(TestPoint, Triangle->Vertices[0]->Position,
                                      Triangle->Vertices[1]->Position,
                                      Triangle->Vertices[2]->Position);
    // Z coords often has precision error because it's derived from 1-A-B, do more precise check
    if (FMath::Abs(Coords.Z) < UE_KINDA_SMALL_NUMBER) Coords.Z = 0.f;
    // Is the point inside ... (接受则取其重心坐标作为样本权重)
}
```
- 歧义消歧：`EPreferredTriangulationDirection { None, Tangential, Radial }` —— 矩形切对角线时
  用固定规则消歧（否则不同机器三角化不同 → 不确定）。

**核心语义（学到的东西）**
1. 样本铺在参数平面 → **三角化** → 输入点落哪个三角形 → **重心坐标 = 三样本混合权重**；
2. 点落在凸包外时取**最近三角形**（按距离排序取第一个含点者）；
3. 三角化必须**确定性**（固定对角线方向）—— 否则同输入不同权重。

**我们的实现**：`core/anim/BlendSpace.java`
- 规则网格 strip 三角化（固定对角线 `lo[c] → hi[c+1]` = UE5 Tangential 的确定性替身）；
- `weightsAt(x,y)` 返回权重和恒为 1（内部重心 / 外部钳制到边 / <3 样本退化最近点独权）；
- `blendInto(x,y,skel)` 按 Overwrite 语义加权姿态（对应 `BlendTransform<Overwrite>`）。

### 1.3 上下半身遮罩 —— 掩码层级传播 + 逐骨混合

**UE5 出处**：
- `FAnimationRuntime::CreateMaskWeights`（`Engine/Private/Animation/AnimationRuntime.cpp`）：

```cpp
const float IncreaseWeightPerDepth = (BranchFilter.BlendDepth != 0) ? (1.f/((float)BranchFilter.BlendDepth)) : 1.f;
// go through skeleton bone hierarchy.
// Bones are ordered, parents before children. So we can start looking at MaskBoneIndex for children.
for (int32 BoneIndex = MaskBoneIndex; BoneIndex < NumBones; ++BoneIndex) {
    const int32 Depth = RefSkeleton.GetDepthBetweenBones(BoneIndex, MaskBoneIndex);
    if (Depth != -1) { /* 该骨在掩码子树内 → 写权重，随 Depth 递减 */ }
}
```
- `FAnimNode_LayeredBoneBlend::Evaluate_AnyThread` → 最终调
  `FAnimationRuntime::BlendPosesPerBoneFilter(BasePose, TargetBlendPoses, ..., CurrentBoneBlendWeights, BlendFlags, ...)`；
  另有 `bMeshSpaceRotationBlend / bRootSpaceRotationBlend / bMeshSpaceScaleBlend` 三个空间选项。

**核心语义（学到的东西）**
1. 指定「掩码骨」（如 `spine_01`）→ **向下遍历后代**，按深度递减权重（`1/BlendDepth`）；
   不在子树内的骨权重 = 0 → 这就是"上半身遮罩"；
2. 逐骨用各自权重插值 base/overlay（`Lerp(base, overlay, boneWeight)`）→
   "上半身挥剑、下半身继续跑"不需要两条完整动画；
3. **掩码随骨架变化要重建**（`ArePerBoneBlendWeightsValid` 校验 SkeletonGuid）——
   我们骨架是运行时构建一次、不变，故只需在构建时算一次。

**我们的实现**：`core/anim/BoneMask.java`
- `Mask.subtree(rootBone, weight, falloff)` = UE5 `CreateMaskWeights` 的深度递减传播；
- `Mask.upperBody(skel, spineBone, w)` / `lowerBody(...)` 两个常用便捷；
- `BoneMask.blendInto(base, overlay, mask)` = `BlendPosesPerBoneFilter` 的逐骨 Lerp。

---

## 2. 落地清单（本批交付）

| 原语 | 新增文件 | 门禁断言 |
|---|---|---|
| root-motion | `core/anim/RootMotion.java` | `ROOT_FULL / ROOT_ADDITIVE / ROOT_ZERO_NEG / ROOT_DRIVES` |
| 混合树 | `core/anim/BlendSpace.java` | `BS_TRI / BS_VERTEX / BS_SUM1 / BS_MID_BARY / BS_BLEND / BS_VERTEX_BLEND` |
| 上下半身遮罩 | `core/anim/BoneMask.java` | `MASK_UPPER / MASK_FALLOFF / MASK_BLEND / MASK_FULL` |
| 门禁 | `core/sim/AnimLayersTest.java` | `DET`（三项纯函数确定性）+ `ZERO_DRIFT`（不碰 World） |

**第 39 道门禁 `ANIMLAYERS`**，16 项断言全绿；四道基线指纹逐字节不变
（DET `43723456791b5364` / ZD `34c8bb722c5a1a6f` / NPC `8560321416dc6ac5` / MEGA `3a08e840e0d16524`）。

---

## 3. 设计取舍（我们与 UE5 的差异）

| 维度 | UE5 | 本项目 | 理由 |
|---|---|---|---|
| 旋转表示 | `FQuat` 四元数 + `FTransform` | 欧拉角 XYZ（度） | 手写小骨架，欧拉角可读、可 JSON、够用 |
| 根运动积分 | `CharacterMovementComponent`（重力/台阶/滑动） | 只产出增量，碰撞交给既有玩家移动 | 方块世界碰撞已成熟，不重造 |
| 三角化 | Delaunay + 歧义消歧枚举 | 规则网格固定对角线 | 样本少（网格点），固定对角线即确定性 |
| 掩码深度衰减 | `1/BlendDepth` 任意深度 | 逐代 `× falloff`（0..1） | 同一数学的直接参数化，更好调 |
| 姿态混合空间 | MeshSpace / RootSpace / LocalSpace 可选 | Local（局部） | 上半身遮罩用局部空间已正确；MeshSpace 是 LOD/大骨架优化 |

---

## 4. 判定表：学 / 不学 / 改造学

> 沿用 `MC_BENCHMARK_AUDIT.md` 的纪律：**只对"有永久收益"的学，对"过度设计/零收益"的明确不学。**

| UE5 机制 | 判定 | 理由 |
|---|---|---|
| root-motion 区间累积 | ✅ **学** | 位移由动画驱动是手感刚需；区间可加性是回放/网络同步的前提 |
| 二维 BlendSpace + 重心插值 | ✅ **学** | 走↔跑过渡的行业标准解；数学简单、收益直接 |
| per-bone 掩码 + 分层混合 | ✅ **学** | "边跑边挥砍"是动作游戏基本盘；实现只有几十行 |
| 掩码深度衰减 | 🔧 **改造学** | UE5 的 `1/BlendDepth` 换成 `× falloff`，参数更直观 |
| `AnimMontage` slot / 多轨道 montage | 🔶 **暂缓** | 我们连招表（`AnimController.combo[]`）已覆盖 90% 用例；多 slot 是大型项目并行动画需求 |
| `FAnimInstanceProxy` 多线程求值 | ❌ **不学** | 单线程体素小引擎，多线程动画求值是纯开销 |
| `bMeshSpaceRotationBlend` / RootSpace | ❌ **不学** | 局部空间已够；三空间枚举是 AAA 大骨架/LOD 的优化 |
| `UBlendProfile` 逐骨过渡时间 | ❌ **不学** | 精细的逐骨 blend 时长＝美术调参工作流，无程序收益 |
| `FMirrorDataTable` 镜像动画 | ❌ **不学** | 我们动作是程序化裁剪，不需要镜像表 |
| `FRootMotionSource` 网络同步群组 | ❌ **不学** | 无多人同步需求 |
| Delaunay 三角化（含歧义建议） | ❌ **不学** | 规则网格固定对角线已确定性；Delaunay 只在样本随机分布时才必要 |
| `FBlendedCurve` 曲线混合 | 🔶 **暂缓** | 我们的"事件帧"已承担 notify 职责；曲线（如 IK 权重）待有 IK 需求再说 |

**一句话总结**：UE5 值得学的三块，都是**"把动画数据变成位移/姿态的数学"**；
不值得学的，都是**"支撑超大规模资产与多平台的工程外骨骼"**。我们的平台战略
（数据层 → 模块层 → 引擎层）与 UE5 的分层是同构的，只是规模差两个数量级。

---

## 5. 与平台战略的衔接

这三项全部落在 **引擎层 `core/anim`（纯函数、零 RNG、不进指纹）**，
意味着：

1. 它们**离线于仿真内核** —— 动作层怎么改都不动 `hashState`（门禁 `ZERO_DRIFT` 已断言）；
2. 数据仍可外置 —— 混合树的样本点、遮罩的骨骼名都能写进 JSON（`AnimJson` 扩展点已在）；
3. 上层（`Game`）按需消费 —— root-motion 增量喂给玩家移动、BlendSpace 按输入速度采样、
   遮罩把攻击动画叠到移动姿态上。**引擎提供能力，内容决定组合。**

> 下一步可选：把 BlendSpace 样本点与 BoneMask 定义接进 `assets/content/`（如
> `blendspaces/locomotion.json`、`masks/upper_body.json`），让美术/策划不改代码就能调动作组合。
