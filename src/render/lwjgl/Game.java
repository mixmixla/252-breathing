package render.lwjgl;

import core.agent.Dialogue;
import core.agent.Npc;
import core.audio.Sfx;
import core.sim.MenuModel;
import core.sim.Simulation;
import core.systems.IndividualSystem;
import core.world.Beast;
import core.world.Autotile;
import core.world.Bloom;
import core.world.Blocks;
import core.world.Civilization;
import core.world.DayCycle;
import core.world.EdgeAtlas;
import core.world.Facing;
import core.world.Player;
import core.world.Trials;
import core.world.Weapons;
import core.world.World;
import core.net.LockstepSession;
import core.net.LockstepRunner;
import core.net.TickBody;
import core.net.UdpRelay;
import core.net.UdpTransport;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;
import render.audio.AudioOut;
import render.lwjgl.Avatar;
import render.lwjgl.Chunk;
import render.lwjgl.CjkFont;
import render.lwjgl.Font;
import render.lwjgl.HudText;
import render.lwjgl.Silhouettes;
import render.lwjgl.TextureAtlas;

public final class Game implements core.content.EffectSink {
    public interface Imm32Lib extends com.sun.jna.Library {
        Imm32Lib INSTANCE = com.sun.jna.Native.load("imm32", Imm32Lib.class);
        com.sun.jna.Pointer ImmAssociateContext(long hwnd, com.sun.jna.Pointer himc);
    }

    private long window;
    // QA（P3，2026-09-23）：-Dbw.vw / -Dbw.vh 覆盖初始窗口尺寸，用于验证 HUD 在极端分辨率下的适配
    // （P3 之前没有任何办法拍小屏截图，"小窗口 HUD 挤爆"这个问题既看不见也没人管）。
    // 纯渲染层旋钮：只影响画布尺寸，不进任何仿真状态或指纹。
    private int W = bwDim("bw.vw", 1280);
    private int H = bwDim("bw.vh", 800);

    private static int bwDim(String key, int def) {
        String s = System.getProperty(key);
        if (s == null) return def;
        try {
            int v = Integer.parseInt(s.trim());
            return v < 1 ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }
    private Simulation sim;
    private Chunk[][] chunks;
    /** 网格异步构建器（第三十二批 A2）；在 {@link #initBuffers()} 之后创建、退出时 shutdown。 */
    private MeshBuilder meshBuilder;
    /**
     * 上一帧的"世界身份 + 窗口原点"——用于检测"在飞网格已过期"。
     *
     * <p>{@code sim.world} 在开新局时会被整体替换（{@code Simulation} 重新 new World），
     * 而窗口平移（{@code stepShift} COMMIT）会改变同一个 World 覆盖的全局块范围：
     * 两者都会让在飞任务的结果对应到<b>别的地形</b>，必须 bump epoch 丢弃。
     */
    private World meshWorld;
    private int meshWinCX = Integer.MIN_VALUE, meshWinCZ = Integer.MIN_VALUE;
    private int chunkX;
    private int chunkZ;
    private int shader;
    private int uVP = -1;
    private int hudShader = -1;
    private int hudUVP = -1;
    /** 自研艺术 UI 层的强度（默认 0 = 逐字节等价旧纯色路径）与分辨率。见 {@link #initHudShader}。 */
    private int hudUArtAmt = -1;
    private int hudUArtRes = -1;
    /** HUD 图集采样器（自研艺术 UI 的九宫格面板贴图；旧调用点由发射器填纯白 UV → 恒等）。 */
    private int hudUTex = -1;
    private int worldShader = -1;
    private int worldVP = -1;
    private int uLightDir = -1;
    private int uTime = -1;
    private int uCamPos = -1;
    /**
     * 第三十八批：区块网格的"烘焙帧 → 当前帧"整数块偏移（{@code Chunk.meshOffX/meshOffZ}）。
     *
     * <p>窗口平移时留在窗内的块不重建网格，只把位移加回顶点（见 ShaderCheck / 地形 VS 的
     * {@code uChunkShift}）。绘制每个块之前单独设一次 —— 同一帧里"复用的老网格"（偏移≠0）
     * 与"刚重建的新网格"（偏移=0）会混在一起，所以它不能是每帧一个的全局量。
     */
    private int uChunkShift = -1;
    private int uFogColor = -1;
    private int uFogDensity = -1;
    private int uFogScale = -1;
    private int uFogFar = -1;
    // UE 指数高度雾（HeightFogCommon.ush）：d(z) = FogDensity·2^(-FogFalloff·(z-FogBase))。
    // uFogBase = "密度等于 uFogDensity" 的参考高度（取 SY*0.55，贴着常见地表 → 原调参在常见高度上不变，
    // 低处谷地/水面变浓、高处变薄）。uFogFalloff 单位 1/格。
    private int uFogFalloff = -1;
    private int uFogBase = -1;
    /** UE 方向性内散射（HeightFogCommon.ush）：DirColor·pow(saturate(dot(rayDir,L)),Exp)，越 StartDistance 后按雾透射率加权。 */
    private int uDirInsc = -1;
    private int uDirInscDir = -1;
    private int uDirInscExp = -1;
    private int uDirInscStart = -1;
    /** 水下程度 0..1（MC FogRenderer 的 FogType.WATER：雾色转水体色、雾距切到水下曲线）。 */
    private float underAmt = 0f;
    private int uUnder = -1;
    private int uWaterCol = -1;
    /** 天空程序里的同名 uniform（location 按 program 独立，不能与 world 的混用）。 */
    private int uSkyUnder = -1;
    private int uSkyWaterCol = -1;
    private final float[] dcWater = new float[3];
    /** 全屏流动水色叠加（MC ScreenEffectRenderer.renderWater：underwater.png 全屏 UV 流动 + alpha 0.1）。 */
    private int underShader = -1;
    private int uUndRes = -1;
    private int uUndTime = -1;
    private int uUndNoise = -1;
    private int uUndAmt = -1;
    private int uUndCol = -1;
    private int uWorldRes = -1;
    private int uTex = -1;
    private int uDetailOrg = -1;
    private int uDetailCell = -1;
    private int atlasTex = -1;
    private int nrmTex = -1;
    /** 可平铺梯度噪声纹理（云层/细节采样用；纯渲染资源，不进仿真、不进指纹）。 */
    private int noiseTex = -1;
    /** HUD 顶点缓冲容量（float 数）：hudBuf 与 hudVBO 都用此常量——单一真相，防止二者不一致导致静默失败（QA 2026-09-13 教训）。 */
    /** HUD 分节顶点计数开关（诊断）：{@code -Dbw.huddiag=1} 时把每个 HUD 分节贡献的顶点数写进 GameLog。
     *  与 {@code -Dbw.fpsdiag} 同范式 —— 存在理由见 {@link #hudMark}。默认关 ⇒ 零开销。 */
    private static final boolean HUD_DIAG = "1".equals(System.getProperty("bw.huddiag"));
    private static final int HUD_CAP_FLOATS = 1600000;
    /**
     * 自研艺术 UI 层强度 ∈ [0,1]，**出厂默认 0.0**（严格等价旧纯色路径 → 零漂移）。
     * <p>这是纯渲染层旋钮：<b>不进 World 状态、不进 any RNG 流、不进任何指纹</b>。
     * 可用 {@code -Dbw.uiart=<0..1>} 在无头截图 QA 里做 A/B 仪器（与 {@code bw.snap} 同族）。</p>
     */
    private final float uiArtAmount = readUiArtAmount();
    /**
     * QA：{@code -Dbw.rain=1} 强制"正在下雨"（纯渲染层，只影响 rainSmooth 平滑量，不进仿真/指纹）。
     * <p>存在理由：实机"雨夜糊屏"是 {@code -Dbw.snap} 默认晴天路径<b>复现不出</b>的，
     * 导致美术改动能在门禁上全绿却在实机不可接受。加此旋钮把该缺陷固定进无头仪器。</p>
     */
    private final float rainQa = readFloatProp("bw.rain");

    /** 读一个 float 系统属性；缺失/非法 → 0。 */
    private static float readFloatProp(String key) {
        String s = System.getProperty(key);
        if (s == null) return 0f;
        try {
            float v = Float.parseFloat(s.trim());
            return (Float.isNaN(v) || v <= 0f) ? 0f : Math.min(1f, v);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** 同 {@link #readFloatProp} 但**不夹到 1**（曝光乘数可 >1）；缺失/非法 → 0。 */
    private static float readFloatPropRaw(String key) {
        String s = System.getProperty(key);
        if (s == null) return 0f;
        try {
            float v = Float.parseFloat(s.trim());
            return (Float.isNaN(v) || v <= 0f) ? 0f : v;
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** 读一个 float 系统属性；**允许 0**（未设才取 dflt）——用于"0 是有意义的取值"的 QA 探针。 */
    private static float readFloatPropOr(String key, float dflt) {
        String s = System.getProperty(key);
        if (s == null) return dflt;
        try {
            float v = Float.parseFloat(s.trim());
            return Float.isNaN(v) ? dflt : v;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** 同 {@link #readFloatProp}，但属性缺失时取 {@code dflt} —— 用于**默认开**的开关
     *  （它们必须能被显式设 0 关掉，而"没设"要等于开）。 */
    private static float readFloatPropOn(String key, float dflt) {
        String s = System.getProperty(key);
        if (s == null) return dflt;
        try {
            float v = Float.parseFloat(s.trim());
            if (Float.isNaN(v) || v <= 0f) return 0f;
            return Math.min(1f, v);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
    private int uNrm = -1;
    // ---- P4 体素软阴影（2026-09-23）----
    // 把世界占用状态上传成一张 GL_R8 的 3D 纹理（160x112x160 = 2.87 MB），world FS 从片元
    // 沿太阳方向步进查询 —— 体素世界最直接的投射阴影做法：对悬垂/洞穴/树冠天然正确
    // （高度场做法会把悬崖下方错误地全黑），也没有阴影贴图的分辨率/份额化/acne 问题。
    // 数学与门禁的详细说明见 VoxelShadow 的类注释。
    // 纯渲染层：只读 World.mat 与 Blocks.opaque，不写任何仿真状态，不进指纹。
    private int shadowTex3D = -1;
    private int uShadowTex = -1;
    private int uShadowSize = -1;
    private int uShadowStrength = -1;
    private int uShadowSoft = -1;
    private int uAoStrength = -1;
    private int uShaftAmount = -1;
    /** §18 dither（world FS 的 uDither）。0 = 关（`-Dbw.dither=0` ⇒ 与改动前逐字节相同）。 */
    private int uDither = -1;
    /** §18 A2C 开关（world FS 的 uA2C，默认 1）。 */
    private int uA2C = -1;
    /** §18 自动曝光的 uExposure（world FS / sky FS 各一份 location）。 */
    private int uExposure = -1;
    private int uSkyExposure = -1;
    private int uShadowFall = -1;
    private int uShadowFade0 = -1;
    private int uShadowFade1 = -1;
    /** 3D 纹理的 CPU 侧暂存。**必须 DIRECT** —— 堆缓冲上传会静默变成全 0（2026-09-20 血案）。 */
    private java.nio.ByteBuffer shadowOccBuf;
    /** 占用字节数组复用缓冲：编辑时重传，避免每次新分配 2.87 MB 的 GC 压力。 */
    private byte[] shadowOccArr;
    /** 占用纹理是否需重传（世界编辑 / 窗口滑动 ⇒ world.lightDirty 置位时同步置位）。 */
    private boolean shadowOccDirty = true;
    /** 运行时不变量只校验一次（同 [ATLAS] FAIL 的纪律：上传失败会静默变成"永远没有阴影"）。 */
    private boolean shadowLogOnce;
    /** 阴影强度。QA 旋钮 -Dbw.shadow=0 可关，用于受控 A/B 量化这一项的净贡献。 */
    private final float shadowStrength = shadowStrength0();

    private static float shadowStrength0() {
        String s = System.getProperty("bw.shadow");
        if (s == null) return 0.85f;
        try {
            float v = Float.parseFloat(s.trim());
            return (v < 0f) ? 0f : Math.min(1f, v);
        } catch (NumberFormatException e) {
            return 0.85f;
        }
    }
    /**
     * 大尺度占用 AO 强度（≈ UE 的 DFAO 思路）。QA 旋钮 {@code -Dbw.ao=0} 可关，用于受控 A/B 量化净贡献。
     *
     * <p>存在理由：world FS 里只有**顶点 AO**（{@code Chunk} 烘焙的 {@code ao/aoT/aoE}，1 格尺度）——
     * 洞穴口、树冠下、峡谷里**不会变暗**，形体读不出体积。本项沿 6 条半球方向在**已有的 3D 占用纹理**
     * 上短距采样（复用 {@code uShadowTex}，零新增 FBO/纹理），得到 2–5 格尺度的遮蔽，只乘 skyTerm。
     *
     * <p>{@code 0} = 恒等（GLSL 里 {@code k<=0.002} 立即返回 1.0 ⇒ 与没有这一层**逐字节相同**）。
     */
    private final float aoStrength = aoStrength0();

    private static float aoStrength0() {
        String s = System.getProperty("bw.ao");
        if (s == null) return 1.00f;
        try {
            float v = Float.parseFloat(s.trim());
            return (v < 0f) ? 0f : Math.min(1.5f, v);
        } catch (NumberFormatException e) {
            return 1.00f;
        }
    }
    /**
     * 体积光轴强度。QA 旋钮 {@code -Dbw.shaft=0} 可关，用于受控 A/B 量化净贡献。
     *
     * <p>存在理由：方向内散射（UE ExponentialHeightFog 的 DirInScattering）此前**没有遮挡** ——
     * 光路畅通无阻 ⇒ 树冠/洞口**不会出现光柱**。本项沿视射线取 6 个样本、每个样本用朝光源方向的
     * 短程占用近似其受光，得透过率乘到内散射上（不新增 pass）。
     *
     * <p>{@code 0} = 恒等（GLSL 入口 {@code amt<=0.002} 立即返回 1.0 ⇒ 与没有这一层**逐字节相同**）。
     */
    private final float shaftAmount = shaftAmount0();

    private static float shaftAmount0() {
        String s = System.getProperty("bw.shaft");
        if (s == null) return 0.70f;
        try {
            float v = Float.parseFloat(s.trim());
            return (v < 0f) ? 0f : Math.min(1f, v);
        } catch (NumberFormatException e) {
            return 0.70f;
        }
    }

    /**
     * §18 dither 幅度（显示域，单位 = 色阶）：默认 {@code 1/255}（抖动 ±0.5 色阶）。
     *
     * <p><b>为什么</b>：ACES + 分级后的暗部/天空是**大块渐变**，8 bit 输出上会出现可见色带
     * （banding）。在 grade() 末尾叠一层 ±0.5 色阶的**蓝噪声样抖动**，把量化误差打散成噪点，
     * 肉眼即看不出带。
     *
     * <p><b>为什么用 gl_FragCoord 而不是 uTime</b>：抖动源必须**与帧无关**（同像素恒同值），
     * 否则无头截图的 A/B 契约（逐字节可复现）当场失效。用屏幕坐标哈希 = 静态噪点图。
     *
     * <p><b>恒等</b>：{@code -Dbw.dither=0} ⇒ 该项为 0 ⇒ 与改动前**逐字节相同**。
     */
    private final float ditherAmt = ditherAmt0();

    private static float ditherAmt0() {
        String s = System.getProperty("bw.dither");
        if (s == null) return 1.0f / 255.0f;
        try {
            float v = Float.parseFloat(s.trim());
            if (v <= 0f) return 0f;
            return Math.min(4f, v) / 255.0f;
        } catch (NumberFormatException e) {
            return 1.0f / 255.0f;
        }
    }
    private int skyShader = -1;
    private int skyVAO = 0;
    private int skyVBO = 0;
    // ---- 泰拉瑞亚缺口②：选择性泛光（bloom，纯渲染层，零漂移；基础场景绘制完全不变）----
    private int bloomFBO = -1, bloomTex = -1;        // 全分辨率：发光源提取
    private int brightFBO = -1, brightTex = -1;      // 半分辨率：亮部阈值
    private int blurFBO = -1, blurTex = -1;          // 半分辨率：高斯模糊 ping-pong
    // ---- 时间累积泛光（Noita post_glow1/2，2026-09-21）----
    // 半分辨率的「上一帧 glow」ping-pong 双缓冲：横/纵各一轮一维模糊并衰减 → 99% 记忆。
    // glowWrite 是本帧要写入的目标，glowRead 是上一帧结果；两者每帧交换，绝不读写同一张。
    private int glowFBOA = -1, glowTexA = -1;
    private int glowFBOB = -1, glowTexB = -1;
    private int glowProgram = -1, uGlowSrc = -1, uGlowPrev = -1, uGlowTexel = -1, uGlowPass = -1, uGlowGain = -1;
    private boolean glowReady = false;
    private int emissiveProgram = -1, uEmissiveVP = -1;
    /** 发光源网格与主网格共用顶点数据 ⇒ 必须用同一个 {@code uChunkShift} 平移（见 {@link #uChunkShift}）。 */
    private int uEmissiveChunkShift = -1;
    private int brightProgram = -1, uBrightSrc = -1, uBrightThresh = -1;
    private int blurProgram = -1, uBlurSrc = -1, uBlurDir = -1;
    private int compositeProgram = -1, uCompositeSrc = -1, uCompositeIntensity = -1;
    private boolean bloomReady = false;
    // 阈值/强度取自 core.world.Bloom（单一真相；9-tap 权重见 Bloom.WEIGHTS 与 initBloom blurFS 内联副本）
    private static final float BLOOM_THRESHOLD = Bloom.THRESHOLD;
    private static final float BLOOM_INTENSITY = Bloom.INTENSITY;
    // 泰拉瑞亚缺口② 的 4 个泛光程序 GLSL（提升为类级常量：ShaderCheck 可在构建期真编译验证，单一真相不漂移）
    private static final String BLOOM_EMISSIVE_VS = "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec3 aCol;uniform mat4 uVP;uniform vec2 uChunkShift;out vec3 vCol;void main(){vCol=aCol;gl_Position=uVP*vec4(aPos+vec3(uChunkShift.x,0.0,uChunkShift.y),1.0);}";
    private static final String BLOOM_EMISSIVE_FS = "#version 330 core\nin vec3 vCol;out vec4 fc;void main(){fc=vec4(vCol,1.0);}";
    private static final String BLOOM_QUAD_VS     = "#version 330 core\nlayout(location=0) in vec2 aP;out vec2 vP;void main(){vP=aP;gl_Position=vec4(aP,0.0,1.0);}";
    private static final String BLOOM_BRIGHT_FS   = "#version 330 core\nin vec2 vP;out vec4 fc;uniform sampler2D uSrc;uniform float uThresh;void main(){vec3 c=texture(uSrc,vP*0.5+0.5).rgb;float l=max(max(c.r,c.g),c.b);if(l<uThresh){fc=vec4(0.0);}else{fc=vec4(c*smoothstep(uThresh,uThresh+0.35,l),1.0);}}";
    private static final String BLOOM_BLUR_FS     = "#version 330 core\nin vec2 vP;out vec4 fc;uniform sampler2D uSrc;uniform vec2 uDir;void main(){vec2 uv=vP*0.5+0.5;vec3 s=texture(uSrc,uv).rgb*0.227027;vec2 o1=uDir*1.0;vec2 o2=uDir*2.0;vec2 o3=uDir*3.0;vec2 o4=uDir*4.0;s+=texture(uSrc,uv+o1).rgb*0.1945946;s+=texture(uSrc,uv-o1).rgb*0.1945946;s+=texture(uSrc,uv+o2).rgb*0.1216216;s+=texture(uSrc,uv-o2).rgb*0.1216216;s+=texture(uSrc,uv+o3).rgb*0.054054;s+=texture(uSrc,uv-o3).rgb*0.054054;s+=texture(uSrc,uv+o4).rgb*0.016216;s+=texture(uSrc,uv-o4).rgb*0.016216;fc=vec4(s,1.0);}";
    private static final String BLOOM_COMP_FS     = "#version 330 core\nin vec2 vP;out vec4 fc;uniform sampler2D uSrc;uniform float uIntensity;void main(){fc=vec4(texture(uSrc,vP*0.5+0.5).rgb*uIntensity,1.0);}";
    /**
     * §19 god-ray（屏幕空间体积光）：把场景按「朝太阳的径向」拉出光束。
     *
     * <p><b>为什么需要它</b>：§17 的体素光轴是**逐像素几何近似**（沿视射线探占用），它给出的是
     * "被遮挡的雾变暗"，形状对但**不成束**。真正的光柱来自**沿视线的累积散射**——屏幕空间径向模糊
     * 正是它的经典近似（GPU Gems 3 "Volumetric Light Scattering"）：先把亮部（太阳/亮天空）掩码，
     * 再朝太阳的屏幕位置重采样累加，遮挡**天然生效**（被挡住的太阳根本不在画面里）。
     *
     * <p><b>为什么加性而非替换</b>：本 pass 用 `GL_ONE/GL_ONE` 叠加，`uWeight==0` 时加的是 0.0
     * （float 加法恒等）⇒ 关掉即与改动前一致。
     *
     * <p><b>为什么乘 `(1-scene)`</b>：避免把已经亮的地方糊成一片白（与 §17 内散射同一手法）。
     */
    private static final String GODRAY_FS =
        "#version 330 core\nin vec2 vP;out vec4 fc;\n"
        + "uniform sampler2D uSrc;uniform vec2 uSun;uniform float uDensity;uniform float uDecay;"
        + "uniform float uWeight;uniform float uThresh;uniform float uConst;uniform float uFloor;uniform float uPow;\n"
        + "void main(){\n"
        // QA 二分探针：uConst>0 时直接输出常量（绕过纹理/掩码）——用来区分"pass 根本没跑"与"掩码没找到光源"。
        + "  if(uConst>0.0){fc=vec4(vec3(uConst),1.0);return;}\n"
        + "  vec2 uv=vP*0.5+0.5;\n"
        + "  vec2 delta=(uSun-uv)*(uDensity/24.0);\n"
        + "  vec2 p=uv;vec3 acc=vec3(0.0);float w=1.0;\n"
        + "  for(int i=0;i<24;i++){\n"
        + "    p+=delta;\n"
        + "    vec3 c=texture(uSrc,clamp(p,vec2(0.0),vec2(1.0))).rgb;\n"
        + "    float l=max(max(c.r,c.g),c.b);\n"
        // ⚠️ 掩码不能用"阈值 + 宽 smoothstep"：显示域里**太阳盘(1.0) 与亮天空(0.72~0.85) 只差 0.15~0.28**，
        // 0.30 宽的 ramp 会把整片天空一起算成"光源" ⇒ 结果是一层均匀的雾而不是光束（实测 55% 像素被改）。
        // 改成**幂次掩码**：先减去地板再归一，再取 6 次幂 ⇒ 0.72→0.0007 / 0.9→0.18 / 1.0→1.0，连续地只留最亮端。
        + "    float m=(l-uFloor)/max(1e-3,1.0-uFloor);m=pow(clamp(m,0.0,1.0),uPow);\n"
        + "    acc+=c*m*w;\n"
        + "    w*=uDecay;\n"
        + "  }\n"
        + "  float scene=dot(texture(uSrc,uv).rgb,vec3(0.2126,0.7152,0.0722));\n"
        + "  fc=vec4(acc*(uWeight/24.0)*(1.0-clamp(scene,0.0,1.0)),1.0);\n"
        + "}";
    // 时间累积泛光（Noita post_glow1/2.frag）：一轮里同时做「上一帧缓冲的一维模糊衰减」与「本帧新样本追赶」。
    // uPass=0 横扫（新样本来自 uSrc，累积衰减用 0.1）；uPass=1 纵扫（新样本已在 uSrc 里混好，累积衰减用 1/12.2）。
    // 数学与 core.world.Bloom 的常量一一对应（GLSL 无法调 Java，故为文档化副本 + 无头门禁锁 Java 侧真相）。
    private static final String BLOOM_GLOW_FS     = "#version 330 core\n"
            + "in vec2 vP;out vec4 fc;uniform sampler2D uSrc;uniform sampler2D uPrev;uniform vec2 uTexel;"
            + "uniform float uPass;uniform float uGain;"
            + "void main(){vec2 uv=vP*0.5+0.5;float w=1.0;"
            + "float e=1.0-0.05;if(uv.x>e){w=1.0-(uv.x-e)*2.0;}else if(uv.x<0.05){w=1.0-(0.05-uv.x)*2.0;}"
            + "if(uv.y>e){w=1.0-(uv.y-e)*2.0;}else if(uv.y<0.05){w=1.0-(0.05-uv.y)*2.0;}"
            + "vec3 acc=vec3(0.0);"
            + "if(uPass<0.5){for(int i=-5;i<=5;i++){acc+=texture(uPrev,uv+vec2(float(i)*uTexel.x*1.5,0.0)).rgb;}acc*=0.1;}"
            + "else{for(int i=-5;i<=5;i++){acc+=texture(uPrev,uv+vec2(0.0,float(i)*uTexel.y*1.5)).rgb;}acc*=0.081967213;}"
            + "vec3 tap=texture(uSrc,uv).rgb;"
            + "fc=vec4(tap*uGain+acc*w*(1.0-0.05)+mix(acc,tap,0.05).rgb*0.05,1.0);}";
    private int uSkyTop = -1;
    private int uSkyHorizon = -1;
    private int uAmbient = -1;
    private int uLightTint = -1;
    private int uSkyColor = -1;
    private int uTorchR = -1;
    private int uTorchCol = -1;
    private int uSkyFwd = -1;
    private int uSkyRight = -1;
    private int uSkyUp = -1;
    private int uSkyTan = -1;
    private int uSunDir = -1;
    private int uSunColor = -1;
    private int uStar = -1;
    private int uRain = -1;
    private int uSkyRes = -1;
    private int uSkyTime = -1;
    /** §18 dither（sky FS 的 uDither）。 */
    private int uSkyDither = -1;
    private int uSkyNoise = -1;
    private float rainSmooth = 0.0f;
    private final float[] dcDir = new float[3];
    private final float[] dcTop = new float[3];
    private final float[] dcHor = new float[3];
    private final float[] dcTint = new float[3];
    private final float[] dcSky = new float[3];
    private final float[] skyFwd = new float[3];
    private final float[] skyRight = new float[3];
    private final float[] skyUp = new float[3];
    private int shadowShader = -1;
    private int shadowVAO = 0;
    private int celVAO = 0;   // 天体专用动态 VAO/VBO：与 HUD 分开，避免同帧对 hudVBO 连续 SubData 造成隐式同步
    private int celVBO = 0;
    private int shadowVBO = 0;
    private int shadowUVP = -1;
    private int uShadowColor = -1;
    private int uShadowAlpha = -1;
    /** MC 破坏阶段裂纹叠加（{@code RenderType.destroy}）：不受光的纹理遮罩，贴在目标方块 6 面上。 */
    private int crackShader = -1;    private int crackVAO = 0;
    private int crackVBO = 0;
    private int uCrackVP = -1;
    private int uCrackTex = -1;
    private int uCrackCol = -1;
    private int uCrackA = -1;
    private final FloatBuffer crackBuf = BufferUtils.createFloatBuffer((int)180);   // 6 面 × 2 三角 × 3 顶点 × 5 float
    /**
     * QA 模式（{@code -Dbw.snap=<png>}）：<b>冻结仿真、演出时钟、以及全部真实输入回调</b>，
     * 让无头截图逐像素可复现。
     * <p>来由：没有这一步时，每轮截图的"世界时刻 + 动画相位"都不同，两次运行的差异会被"换了张画面"
     * 完全淹没 —— 本机实测同一版本两次运行只有 3.4% 像素相同，于是任何"改动前后差多少"都测不出来。
     * 冻结后 snapshot 才是一台能用的 A/B 仪器（本项目做美术/雾/光照改动都要靠它出证据）。</p>
     * <p><b>2026-09-21 补课</b>：只冻结"仿真时钟"是不够的 —— 还必须有
     * ① <b>所有用真实帧 dt 积分的演出量</b>（{@code walkAnim} 手臂摆动、各种 flash/timer；
     * 统一走 {@code fxDt=qaFrozen?0:f}），以及
     * ② <b>所有真实 OS 输入回调</b>（{@code glfwSetCursorPosCallback} 在窗口创建时会收到
     * 系统回中光标产生的事件 → yaw/pitch 漂移 → 整屏不同）。
     * 漏掉 ② 时症状是"所有状态量看起来一致、像素却 93% 不同"，极难查。见 {@code docs/OPEN_ITEMS.md}。</p>
     */
    private final boolean qaFrozen = System.getProperty("bw.snap") != null;
    /**
     * QA 预热 tick 数（{@code -Dbw.warm=N}，默认 0 = 不预热）。
     *
     * <p><b>为什么需要它（第三十五批踩到的工具盲区）</b>：{@code -Dbw.snap} 只用
     * {@code world.tick = DAY_LEN*phase} <b>伪造 tick 计数器</b>（为了让昼夜相位好看），
     * 世界<b>从未真正演化过</b> ⇒ 任何"随时间累积"的改动（灰沉降、植被蔓延、水位、冰盖）
     * 在快照里<b>完全看不到</b> —— 本项目的 A/B 图对这类改动是<b>瞎的</b>。
     * 本批就是被它骗过一次：修完灰的无界蔓延后 {@code simHash} 变了、画面却逐像素相同。
     *
     * <p>预热是确定性的（同种子 + 同 N ⇒ 逐像素仍可复现），所以不破坏 {@code -Dbw.snap} 的可复现契约。
     * 每帧最多跑 {@link #QA_WARM_PER_FRAME} 个 tick，避免一帧卡太久。</p>
     */
    private int qaWarmLeft = Integer.getInteger("bw.warm", 0);
    /** 预热时每帧最多推进几个 tick（只是分帧，不改变总 tick 数 ⇒ 不影响结果）。 */
    private static final int QA_WARM_PER_FRAME = 400;
    /** QA 截图帧号（{@code -Dbw.snapFrame=N} 可覆盖，默认 48 = 世界流式加载已跑完）。 */
    private final int qaSnapFrame = qaSnapFrame0();

    /**
     * §18 A2C（alpha-to-coverage）强度：{@code 0} = 关（恒等），默认 1 = 开。
     *
     * <p><b>为什么需要</b>：{@code discard} 是<b>逐像素二值</b>的，MSAA 对它<b>完全无效</b>
     * （样本共享同一个片元着色结果）⇒ 树叶/草的镂空边缘永远是硬台阶。配合
     * {@code GL_SAMPLE_ALPHA_TO_COVERAGE} + 片元里用 mip1 的 alpha 估覆盖率，
     * 把硬台阶换成**分数覆盖**（atlas 烘焙时 mip 的 alpha 走 4-tap 线性平均 ⇒ 天然是分数）。
     */
    private final float a2cAmount = readFloatPropOn("bw.a2c", 1f);

    /**
     * MSAA 采样数。**QA 冻结时默认 0**（{@code -Dbw.snap}）—— MSAA 的 resolve 不是逐位确定的
     * （2026-09-21 实测同版本连跑两次全屏散点不同），会毁掉截图的 A/B 仪器契约。
     * {@code -Dbw.msaa=<n>} 可显式覆盖（0..8），用于"A2C 效果"这类**只能开 MSAA 才存在**的
     * 特性的统计 A/B（此时比的是分布差异，不是逐字节）。
     */
    private final int msaaSamples = msaaSamples0();

    private int msaaSamples0() {
        String s = System.getProperty("bw.msaa");
        if (s != null) {
            try {
                int v = Integer.parseInt(s.trim());
                return (v < 0) ? 0 : Math.min(8, v);
            } catch (NumberFormatException ignored) { }
        }
        return this.qaFrozen ? 0 : 4;
    }
    /**
     * QA 日相位（{@code -Dbw.phase=<0..1>}，0=午夜 / 0.25=日出 / 0.5=正午 / 0.75=日落）。
     * {@code <0} = 未指定。每帧重施到 {@code world.tick}（见 render 内的注释），因为一次性设置
     * 会被 initContentLayer / applySelectedPreset 覆盖。纯渲染层 QA 旋钮，不进任何仿真状态 / 指纹。
     */
    private final float qaPhase01 = qaPhase01_0();
    /**
     * QA 旋钮 {@code -Dbw.menu=1}：强制打开主菜单。
     * 用途：菜单里画着 P1 的中文大字号标题（MSDF），而无头截图默认不进菜单 ——
     * 没有这个旋钮就没法目视验证标题渲染。纯渲染层，不进任何仿真状态 / 指纹。
     */
    private final boolean qaMenu = "1".equals(System.getProperty("bw.menu"));

    private static float qaPhase01_0() {
        String s = System.getProperty("bw.phase");
        if (s == null) return -1f;
        try {
            float v = Float.parseFloat(s.trim());
            return (Float.isNaN(v) || v < 0f) ? -1f : Math.min(1f, v);
        } catch (NumberFormatException e) { return -1f; }
    }

    private static int qaSnapFrame0() {
        String s = System.getProperty("bw.snapFrame");
        if (s != null) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return 48;
    }
    private FloatBuffer shadowBuf = BufferUtils.createFloatBuffer((int)20000);
    // QA 2026-09-15 天体（真实日月轨迹 / 相位 / 食）：全部为渲染层只读派生量，不进任何仿真状态 / hashState
    private final float[] celSun = new float[3];
    private final float[] celMoon = new float[3];
    private final float[] celLight = new float[3];
    private final float[] celAnti = new float[3];
    private final float[] celPt = new float[3];
    private final float[] celNdcM = new float[2];
    private final float[] celNdcS = new float[2];
    private final float[] celNdcA = new float[2];
    private FloatBuffer celBuf = BufferUtils.createFloatBuffer((int)15500);   // 2026-09-21：9 float/顶点（加 uv）
    private long eclipseNextLunar = -2L;      // -2 = 未算；-1 = 找不到
    private long eclipseNextSolar = -2L;
    private int eclipseCacheDay = -1;
    private core.world.Celestial.Eclipse prevEclipse = core.world.Celestial.Eclipse.NONE;
    private float time = 0.0f;
    private final Silhouettes silhouettes = new Silhouettes();
    private final Matrix4f vpFrame = new Matrix4f();
    private final Vector4f[] frustumReuse = new Vector4f[]{new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f()};
    private float lastSilX = Float.NaN;
    private float lastSilZ = Float.NaN;
    private int silFrame = 0;
    private int lineVAO = 0;
    private int lineVBO = 0;
    private final FloatBuffer lineBuf = BufferUtils.createFloatBuffer((int)512);
    private int[] rayTarget = null;
    private int[] rayPlace = null;
    private boolean debugHud = false;
    public static boolean DEBUG = false;        // 调试沙盒：默认关；--debug / F6 开启（零漂移：默认关不影响基线）
    private float fpsEma = 60.0f;
    private long lastFrameNanos = 0L;
    private final FloatBuffer vpBuf = BufferUtils.createFloatBuffer((int)16);
    private int frameCount = 0;
    private float lastDiagLog = -1.0f;          // 渲染诊断日志：每秒记录一次 drawnChunks / 用于区分"没加载"与"全黑"
    /**
     * 单帧最多模拟步数（**安全上限**）—— 超出即丢弃积压，防 render 慢时 tick 雪崩。
     *
     * <p><b>2026-09-17 定值 2，依据是实测而不是感觉</b>（{@code core.sim.PerfProbe}，
     * 160x112x160 / 98 系统）：单 tick <b>median 5.13ms / p95 7.75ms / max 11.95ms</b>
     * （同日先把 `nonAirCells` 改惰性重建，tick 从 19.65ms 降到 5.13ms）。
     * 2 步 p95 最坏 15.5ms —— 刚好落在 60fps 的 16.67ms 预算内；3 步就是 23.2ms（越预算）。
     * 再叠加下面的时间盒，实际通常只跑 1~2 步。
     */
    private static final int MAX_STEPS_PER_FRAME = 2;
    /**
     * 跨图**流式重建**的每帧时间预算（毫秒）—— 取代原先"每帧固定 {@code SHIFT_CHUNKS_PER_STEP = 8} 块"。
     *
     * <p><b>为什么改（第二十六批，性能）</b>：单块地形生成要 1~5ms，8 块就是 **8~40ms/帧** ⇒
     * 一跨区块就掉帧，表现正是用户报的"走路一卡一卡 / 动作不流畅 / 跟地形交互卡顿"
     * （挖方块后触发的重建走的是同一套预算，所以症状同源）。
     * 改成按时间切分后帧时间平稳；代价只是跨图加载多花几帧（每帧仍在推进，不会停住）。
     */
    private static final float SHIFT_BUDGET_MS = 4.0f;
    /**
     * 网格构建的 worker 线程数（第三十二批 A2）。
     *
     * <p>取 {@code cores-1} 并钳在 1..4：留至少一个核给主线程（仿真 + GL 提交 + 输入），
     * 否则 worker 会把主线程挤成瓶颈 —— 那就本末倒置了。上限 4 是因为收益在"块数/帧"上先饱和，
     * 再多的线程只会争内存带宽（每条 direct 缓冲都是 MB 级）。
     */
    private static final int MESH_THREADS = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    /**
     * 在飞网格任务上限（内存闸门）。
     *
     * <p>每块 MeshData 持三条 direct 缓冲，稠密块可达 MB 级 ⇒ 不设上限时"跨图把全窗标脏"
     * 会在瞬间堆出几百 MB 的非 GC 内存。2×线程数是"让 worker 永远不挨饿"的最小值。
     */
    private static final int MESH_MAX_INFLIGHT = MESH_THREADS * 2;
    /**
     * 每帧最多上传（{@code glBufferData}）几块 —— 对应 MC 的 {@code highPriorityQuota}。
     *
     * <p>上传必须在主线程（GL 上下文），所以它是新的主线程成本来源；配额把它压回可控范围。
     * 4 块/帧的实测上传成本远低于重建（同一份数据，只是搬运），这是"重活搬走"换来的。
     */
    private static final int MESH_APPLY_MAX = 4;
    /** 每帧上传的时间上限（毫秒，兜底：单块极稠密时别把主线程一口气吃满）。 */
    private static final float MESH_UPLOAD_BUDGET_MS = 3.0f;
    /**
     * 单帧仿真时间盒（毫秒）：本帧已经花在仿真上的时间超过它就**立刻收手**，剩余积压留到下帧。
     *
     * <p>为什么需要它（而不只是一个步数上限）：步数上限假设"每步一样贵"，而实测 tick 成本
     * 随世界年龄增长（熔岩/漫流两个系统的写入量单调增长）。时间盒让"一帧最多花多少在仿真上"
     * 成为**硬约束**，与世界变大无关。取 12ms = 60fps 预算 16.67ms 里留给渲染的 4.7ms 之外的余量
     * （按 tick p95 7.75ms 计，12ms 盒 ≈ 允许 1~2 步）。
     */
    private static final float MAX_FRAME_SIM_MS = 12.0f;
    /**
     * 积压钳制（秒）：落后超过它就**放弃追帧**（清零 acc）。
     *
     * <p>配合时间盒使用：时间盒会让"积压越大、每帧仍只跑一步"，于是落后会长期存在
     * → 世界进入**长期慢动作**（比冻结更糟：玩家以为游戏坏了）。钳制把"追不回来"变成
     * 一次明确的跳帧（时间前跳），语义干净。
     */
    private static final float MAX_ACC_SEC = 0.15f;
    /** 粒子渲染剔除距离^2（与区块裁剪同距 128 格）：超出/屏幕外的粒子不提交顶点。纯渲染，不影响仿真。 */
    private static final float PARTICLE_CULL_DIST_SQ = 16384.0f;
    private PrintWriter diag;
    private static final String DIAG_PATH = "render_diag.log";
    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Vector3f camPos = new Vector3f();
    private boolean heldTorch = false;                 // F2：手持火把（测试用渲染层点光，零仿真漂移）
    private static final float[] TORCH_RADII = {16.0f, 24.0f, 36.0f, 52.0f};
    private int torchRadIdx = 1;
    private float torchRadius = TORCH_RADII[1];
    private float yaw = -45.0f;
    private float pitch = -25.0f;
    private final boolean[] keys = new boolean[349];
    private double lastMx;
    private double lastMy;
    private boolean firstMouse = true;
    private boolean windowFocused = true;
    private int heldSlot;                    // E 批：当前手持 hotbar 槽（0-8）；方块索引由该槽物品定义派生
    private float blockFrontTimer;
    private float stuckAllTimer;   // 四方向全堵累计（LD-2026-09-12：15 秒自动脱困）
    private boolean mouseHeldL;
    private boolean mouseHeldR;
    private float clickRepeat;
    private boolean sprinting;
    private float sprintFov;
    private boolean prevNight;
    private boolean spawnHintDone;
    private float smoothMx;
    private float smoothMz;
    private long lastInputMs = System.currentTimeMillis();
    private long lastFocusLossMs = 0L;   // 最近一次失焦的时刻（挂起判定去抖用）
    // QA 2026-09-16 实体渲染插值统一：全部是渲染侧状态，不进任何仿真状态 / hashState / 快照。
    // 之前只有 NPC 做了插值，野兽与所有「剪影暗盒」都直接吃 20Hz 仿真坐标 —— 玩家自己是渲染帧率
    // 物理（顺滑），对比之下其他角色就一格一格跳，看起来「一闪一闪」。
    private float[] beastPrevPos;    // 每个 beast 的「上一 tick 位置」（3 float/个）
    private Beast[] beastPrevRef;    // 与 beastPrevPos 同行；按对象身份对齐，列表变动时自然失配退回原位
    private int beastPrevCount;
    private float entAlpha = 0.0f;   // 插值因子：只在世界真正推进 tick 时更新（暂停/hitstop/跨图冻结而非清零）   // 输入防抖：最近 5 秒内有输入 = 活跃（焦点抖动免疫）
    private float dbgMx;
    private boolean stuckLogged = false;
    private boolean everFocused = false;
    private boolean inventoryOpen = false;                   // E 键 MC 风格背包界面（E 批：27 格可交互）
    private core.content.Inventory inventory;                // E 批：背包（纯模型在 core.content，可无头门禁）
    private int winW = 0, winH = 0;                          // 窗口逻辑尺寸（DPI 换算光标 → HUD 坐标）
    private boolean pickupWarned = false;                    // 方块缺物品定义只提示一次
    private boolean prevShowHud = true;
    private float dbgMz;
    private boolean thirdPerson;
    private static final Vector3f UP = new Vector3f(0.0f, 1.0f, 0.0f);
    private static final float SIM_DT = 0.05f;
    private float acc;
    private long lastNs;
    private static final boolean RENDER_CULL = true;
    private static final int REBUILD_BUDGET = 4;
    private static final float RENDER_DISTANCE = 128.0f;
    private int occludedChunks;
    private int drawnChunks;
    /** 透明 pass 按距相机远→近排序的比较器（复用单例，避免每帧 new Comparator）。 */
    private final java.util.Comparator<Chunk> transChunkCmp = new java.util.Comparator<Chunk>() {
        @Override public int compare(Chunk a, Chunk b) {
            float ax = (a.cx * 16 + 8) - Game.this.camPos.x, az = (a.cz * 16 + 8) - Game.this.camPos.z;
            float bx = (b.cx * 16 + 8) - Game.this.camPos.x, bz = (b.cz * 16 + 8) - Game.this.camPos.z;
            float da = ax * ax + az * az, db = bx * bx + bz * bz;
            return da < db ? 1 : (da > db ? -1 : 0);   // 距离降序 = 由远到近
        }
    };
    private int drawnFaces;
    private final List<Chunk> transChunks = new ArrayList<>();   // 可见且含半透明面(水/玻璃)的区块：透明 pass，按距离由远到近绘
    private static final boolean GREEDY_MESH = false;
    private static final int[] FRUSTUM_PLANE_ORDER = new int[]{0, 1, 2, 3, 4, 5};
    private int entVAO;
    private int entVBO;
    private int hudVAO;
    private int hudVBO;
    private FloatBuffer entBuf;
    private FloatBuffer hudBuf;
    // ---- P1（2026-09-23）：MSDF 字体通道 ----
    // 为什么要独立 VAO/VBO/程序：HUD 通道是"图集纹理 × 顶点色"（straight alpha，RGB 只乘 t.rgb），
    // 而 MSDF 需要对采样的距离场做 median + 屏幕像素换算的 smoothstep，是完全不同的 FS。
    // 把两者塞进同一个 FS 会让每一片 HUD 像素都多走一遍分支；独立通道则只在真的画标题时才付出代价。
    // 顶点布局与 HUD 一致（pos3+col4+uv2 = 9 float），所以容量常量同源复用。
    private static final int MSDF_CAP_FLOATS = 240000;
    /** P1：菜单里的大字号中文标题与副标题。用 MSDF 渲染（任意字号清晰 + 可描边）。 */
    private static final String ZH_TITLE = "\u4f1a\u547c\u5438\u7684\u4e16\u754c";           // 会呼吸的世界
    private static final String ZH_SUBTITLE = "\u786e\u5b9a\u6027\u6d8c\u73b0\u7684\u4f53\u7d20\u4e16\u754c"; // 确定性涌现的体素世界
    private int msdfVAO;
    private int msdfVBO;
    private FloatBuffer msdfBuf;
    private int msdfShader;
    private int msdfUVP;
    private int msdfUTex;
    private int msdfUAtlas;
    private int msdfURange;
    private int msdfUOutline;
    private int msdfUOutlineCol;
    private float walkAnim;
    private float landTimer;
    private float rollAnim;
    private float walkBlend;
    private Beast locked;
    private boolean queuedArt;
    private boolean queuedRoll;
    private boolean queuedLock;
    private boolean queuedCycleArt;
    private Weapons.Art artFxArt;
    private float artFxTimer;
    private float artFxDur;
    private float artFxDx;
    private float artFxDz;
    private float artFxTx;
    private float artFxTy;
    private float artFxTz;
    private boolean artFxHasTarget;
    private int prevArtCount;
    private final Matrix4f IDENTITY;
    private float hurtFlash;
    private float killFlash;
    private float skillFlash;            // 施法彩色闪光（纯渲染，不进仿真）
    private float skillFlashR, skillFlashG, skillFlashB;
    /** 招架/处决专属世界空间演出计时（纯渲染层，不进仿真；只读玩家位置，零漂移）。 */
    private float parryFxTimer, executeFxTimer;
    private static final float PARRY_FX_DUR = 0.34f, EXECUTE_FX_DUR = 0.48f;
    /** MC 手感（§1 P0 #1）：挖掘进度状态（目标格 + 已累计"挖掘秒" + 松手保留计时）。纯输入/渲染层，不进 sim。 */
    private int digBx = Integer.MIN_VALUE, digBy, digBz;
    private float digProg = 0.0f, digIdle = 0.0f;
    private static final float DIG_HOLD = 2.0f;      // 松手后进度保留 2s（MC 语义）
    private float digChipTimer = 0.0f;               // 挖掘碎屑节流（纯渲染层粒子）
    /**
     * 材料规格书（内容层第 13 类，2026-09-18）—— 挖掘「硬度」与「碎屑粒子」按方块查表，
     * 取代原先 {@code Game.digHardness()} 里的 {@code id.contains(...)} 字符串链。
     * 加载期构建、运行时只读；纯渲染/输入层消费 → 不进 sim、不进 hashState。
     */
    private core.content.MaterialBook matBook = core.content.MaterialBook.empty(core.world.Blocks.count());
    /** 命中反馈（纯 HUD）：命中标记计时 + 漂浮伤害数字环形缓冲（屏幕空间）。 */
    private float hitMarkTimer = 0.0f;
    private final float[] dmgX = new float[8], dmgY = new float[8], dmgT = new float[8], dmgRnd = new float[8];
    private final int[] dmgV = new int[8];
    private int dmgHead = 0;
    /** 成就（**会话级**：纯本地，不进 sim/存档 —— 避免联机两端状态分叉）。 */
    private final java.util.LinkedHashSet<String> achDone = new java.util.LinkedHashSet<String>();
    private static final String[] ACH_IDS = {"FIRST DIG", "FIRST KILL", "FIRST CRAFT", "FIRST CHEST", "FIRST PICKUP", "FIRST PARRY",
            "TURNED A BLOCK"};   // 第十一批：潜行+右键旋转朝向（新增成就要登记在这里，否则进度计数对不上）
    private String achBanner = null;
    private float achBannerTimer = 0.0f;
    private int prevBeasts;
    private int prevHp;
    private AudioOut audio;
    private int prevBeastHp;
    private int appliedVolume;
    private boolean appliedSfxOn;
    private int lastLevel;
    private float levelUpTimer;
    private float hitstop;
    private boolean queuedBomb;
    private boolean queuedIgnite;
    private float skillCastAnim = 0f;     // 技能施放动作计时（秒，递减）；B 批特效用它做缩放/闪白
    private boolean queuedSkillCast;      // P：施放当前技能
    private boolean queuedSkillSwitch;    // O：在已学技能间轮换
    private boolean queuedAqua;
    private boolean queuedThunder;
    private String queuedInvest;
    private boolean prevOnGround;
    private int prevShrineClaimed;
    private float bannerTimer;
    private String bannerText;
    private final Set<String> announcedRelics;
    private boolean prevHeartClaimed;
    /** P0-4：上一帧守卫是否存在（用于"苏醒/倒下"横幅的边沿检测）。 */
    private boolean prevWardenAlive;
    private float toastTimer;
    /** P0-3：上一次看到的"已获得武器"掩码（<0 = 尚未同步，首帧/读档只同步不提示）。 */
    private int prevWeaponOwned = -1;
    /** 预设选择器：上次**已施加**到当前世界的预设 id（null = 尚未施加 / 出厂）。 */
    private String appliedPresetId = null;
    /** 只有预设**真的改变了世界**时才非空 —— 默认预设不占 HUD（不加噪）。 */
    private String presetHudLine = "";
    private String toastText;
    /**
     * 告示牌文本编辑态（第七批，2026-09-24）：编辑中的格子 + 输入缓冲。
     *
     * <p><b>为什么是渲染层状态</b>：铭文的**权威存储**是仿真层的 {@code World.signText}
     * （非 SKIP → 自动持久化/回滚，但不进 {@code hashState} → 零漂移）；这里的缓冲只是
     * "还没按回车"的草稿，属纯 UI 态 —— 若把它也塞进仿真 World 就成了"双真相"。
     *
     * <p>编辑态独占键盘（见 {@code glfwSetKeyCallback}）与鼠标（见
     * {@code glfwSetCursorPosCallback} / {@link #handleClick}）→ 打字不会同时驱动移动/挖/放。
     */
    private boolean signEditing;
    private int signEditX, signEditY, signEditZ;
    private final StringBuilder signEditBuf = new StringBuilder();
    /** 告示牌铭文上限：HUD 一行放得下、且不超出面板宽度的字符数。 */
    private static final int SIGN_MAX = 28;
    /**
     * 按钮脉冲队列 {@code {x,y,z,剩余毫秒}}（第五批红石）：右键按钮 → 该格 {@code meta=1}，
     * 倒计时归零后**自动复位**为 0（电路断）。纯渲染层倒计时，meta 不进 hashState → 零漂移。
     */
    private final java.util.List<int[]> buttonPulses = new java.util.ArrayList<>();
    /** 按钮脉冲时长（毫秒）。 */
    private static final int BUTTON_PULSE_MS = 1000;
    private boolean queuedTalk;
    /** Y 键：投捕捉球（捕捉驯养子系统 CaptureSystem 的玩家入口）。 */
    private boolean queuedCapture;
    /** I 键：手搓合成（RecipeBook.craft 的玩家入口 —— 它是该 API 唯一的真实消费者）。 */
    private boolean queuedCraft;
    /** F12：打开最近的箱子。 */
    private boolean queuedChest;
    /** F4：主动招架；F11：处决硬直低血敌兵。 */
    private boolean queuedParry, queuedExecute;
    private Npc nearNpc;
    private static final String[] TUT_KEY = new String[]{"W A S D", "\u9f20 \u6807", "\u7a7a \u683c", "F", "R / G", "Q", "TAB", "T / B", "ESC", "1-4 \u00b7 \u5de6/\u53f3\u952e", "CTRL", "SHIFT"};
    private static final String[] TUT_DESC = new String[]{"\u79fb\u52a8", "\u89c6\u89d2", "\u8df3\u8dc3", "\u8f7b\u653b\u51fb", "\u6218\u6280\u00b7\u5207\u6362", "\u7ffb\u6eda\u95ea\u907f", "\u9501\u5b9a\u76ee\u6807", "\u4ea4\u8c08\u00b7\u6751\u5fd7", "\u83dc\u5355\u00b7\u6309\u952e\u8bf4\u660e", "\u9009\u65b9\u5757 \u00b7 \u5de6\u952e\u6316 / \u53f3\u952e\u653e\uff08\u88ab\u6321\u5c31\u6316\u5f00\u6216\u57ab\u811a\uff09", "\u6309\u4f4f\u75be\u8dd1\uff08\u8d76\u8def\u66f4\u5feb\uff09", "\u6f5c\u884c\uff08\u6162\u8d70\u00b7\u4e0d\u4f1a\u8d70\u51fa\u8fb9\u7f18\uff09"};
    private static final float TUT_FADE = 1.5f;
    private final float[] tutDoneAt;
    private float tutYaw0;
    private float tutPitch0;
    private PrintWriter talkLog;
    private String talkToast;
    private float talkToastTimer;
    private PrintWriter chronicleLog;
    private String lastChronicleWritten;
    private String talkReply;
    private String talkReplyLabel;
    private float talkReplyTimer;
    private boolean showChronicle;
    private final MenuModel menu;
    private int appliedFov;
    private int appliedViewDist;
    private float lastProjFov;
    private boolean queuedPause;
    private boolean queuedMenuConfirm;
    private boolean queuedNavUp;
    private boolean queuedNavDown;
    private boolean queuedNavLeft;
    private boolean queuedNavRight;
    // ---- 平台化 2026-09-13：粒子由配置驱动；内容层 / 玩法层在 run() 里装配 ----
    private core.content.ParticleSim particles;
    /**
     * fx 的**延迟发射队列** —— 内容 {@code fx/*.json} 里每个 emitter 可以写 {@code delay}，
     * 此前被忽略（全部瞬间喷完）。这里按秒倒计时逐帧释放，于是「先炸开、再冒烟、最后余烬」
     * 这种分层时序才真的看得见。纯渲染层、不消耗任何 RNG → 零漂移。
     */
    private static final class PendingEmit {
        final com.google.gson.JsonObject emitter;
        final float x, y, z;
        final boolean burst;
        float ttl;
        PendingEmit(com.google.gson.JsonObject e, float x, float y, float z, float ttl, boolean burst) {
            this.emitter = e; this.x = x; this.y = y; this.z = z; this.ttl = ttl; this.burst = burst;
        }
    }
    private final java.util.List<PendingEmit> pendingFx = new java.util.ArrayList<PendingEmit>();
    private core.content.ContentRegistry content;
    /**
     * 内容层引擎（A 批）：{@code EffectQueue} 的持有者 + <b>技能施放入口</b>。
     * 此前 Game 直接持有一个裸 {@code EffectQueue}，而 {@code core.systems.ContentSystem}
     * （注释自称「内容层唯一一座桥」）从未被实例化 —— 技能链正是断在那里。
     */
    private core.systems.ContentSystem contentSys;
    /** 已学技能（world.skills ∩ 内容层 skills，按 id 升序）—— O 键轮换 / HUD 显示。 */
    private java.util.List<String> learnedSkills = new java.util.ArrayList<String>();
    private int skillIdx = 0;
    private core.content.RuleEngine rules;
    private core.content.QuestEngine questEngine;   // P1：任务运行时（与 rules/techs/effects 并列的第 4 个引擎）
    private core.content.TechTree techs;          // 科技链（饥荒/缺氧式配方图）
    private core.content.SkillTree skillTree;     // 技能树（DNF 式前置/点数）
    private MenuModel.CharacterProvider contentProvider;   // F 批：捏脸预设提供方（内容层桥）
    private MenuModel.Page prevMenuPage = MenuModel.Page.CLOSED;   // F 批：检测进入/离开捏脸页

    // ---- N5：联机会话（tick 由会话推进；单机态为 null → 走原 4 行内联 tick）----
    private core.net.LockstepSession netSession;
    private core.net.UdpRelay relay;
    private Player.Intent netLocalIntent = Player.Intent.idle();

    // ---- P2 2026-09-13：屏震（纯渲染偏移，绝不动 camPos 本身、绝不进指纹）----
    private float shakeAmp, shakePhase, shakeOffX, shakeOffY;

    /** 内容里的音效名 → 既有 Sfx 枚举的命名表（缺省再试 {@code valueOf} 大写）。 */
    private static final java.util.Map<String, core.audio.Sfx> SFX_BY_NAME =
            new java.util.HashMap<String, core.audio.Sfx>();
    static {
        SFX_BY_NAME.put("dig", core.audio.Sfx.DIG);
        SFX_BY_NAME.put("place", core.audio.Sfx.PLACE);
        SFX_BY_NAME.put("swing", core.audio.Sfx.SWING);
        SFX_BY_NAME.put("hit", core.audio.Sfx.HIT);
        SFX_BY_NAME.put("roll", core.audio.Sfx.ROLL);
        SFX_BY_NAME.put("jump", core.audio.Sfx.JUMP);
        SFX_BY_NAME.put("land", core.audio.Sfx.LAND);
        SFX_BY_NAME.put("hurt", core.audio.Sfx.HURT);
        SFX_BY_NAME.put("kill", core.audio.Sfx.KILL);
        SFX_BY_NAME.put("loot", core.audio.Sfx.LOOT);
        SFX_BY_NAME.put("charge", core.audio.Sfx.ART_LUNGE);
        SFX_BY_NAME.put("dash", core.audio.Sfx.ROLL);
        SFX_BY_NAME.put("boom", core.audio.Sfx.BOMB);
        SFX_BY_NAME.put("talk", core.audio.Sfx.TALK);
        SFX_BY_NAME.put("deny", core.audio.Sfx.DENY);
        SFX_BY_NAME.put("levelup", core.audio.Sfx.LEVELUP);
        SFX_BY_NAME.put("unlock", core.audio.Sfx.UNLOCK);
        SFX_BY_NAME.put("relic", core.audio.Sfx.RELIC);
        SFX_BY_NAME.put("heart", core.audio.Sfx.HEART);
    }

    public Game() {
        this.heldSlot = 0;
        this.blockFrontTimer = 0.0f;
        this.mouseHeldL = false;
        this.mouseHeldR = false;
        this.clickRepeat = 0.0f;
        this.sprinting = false;
        this.sprintFov = 0.0f;
        this.prevNight = false;
        this.spawnHintDone = false;
        this.smoothMx = 0.0f;
        this.smoothMz = 0.0f;
        this.dbgMx = 0.0f;
        this.dbgMz = 0.0f;
        this.thirdPerson = true;
        this.acc = 0.0f;
        this.occludedChunks = 0;
        this.drawnChunks = 0;
        this.drawnFaces = 0;
        this.entBuf = BufferUtils.createFloatBuffer((int)400000);
        this.hudBuf = BufferUtils.createFloatBuffer((int)HUD_CAP_FLOATS);
        this.msdfBuf = BufferUtils.createFloatBuffer((int)MSDF_CAP_FLOATS);
        this.walkAnim = 0.0f;
        this.landTimer = 0.0f;
        this.rollAnim = 0.0f;
        this.walkBlend = 0.0f;
        this.locked = null;
        this.queuedArt = false;
        this.queuedRoll = false;
        this.queuedLock = false;
        this.queuedCycleArt = false;
        this.artFxArt = null;
        this.artFxTimer = 0.0f;
        this.artFxDur = 0.3f;
        this.artFxDx = 0.0f;
        this.artFxDz = 1.0f;
        this.artFxTx = 0.0f;
        this.artFxTy = 0.0f;
        this.artFxTz = 0.0f;
        this.artFxHasTarget = false;
        this.prevArtCount = 1;
        this.IDENTITY = new Matrix4f();
        this.hurtFlash = 0.0f;
        this.killFlash = 0.0f;
        this.skillFlash = 0.0f;
        this.parryFxTimer = 0.0f; this.executeFxTimer = 0.0f;
        this.prevBeasts = -1;
        this.prevHp = -1;
        this.prevBeastHp = -1;
        this.appliedVolume = -1;
        this.appliedSfxOn = true;
        this.lastLevel = 1;
        this.levelUpTimer = 0.0f;
        this.hitstop = 0.0f;
        this.queuedBomb = false;
        this.queuedIgnite = false;
        this.queuedSkillCast = false;
        this.queuedSkillSwitch = false;
        this.queuedAqua = false;
        this.queuedThunder = false;
        this.queuedInvest = null;
        this.prevOnGround = true;
        this.prevShrineClaimed = 0;
        this.bannerTimer = 0.0f;
        this.bannerText = "";
        this.announcedRelics = new HashSet<String>();
        this.prevHeartClaimed = false;
        this.prevWardenAlive = false;
        this.toastTimer = 0.0f;
        this.toastText = "";
        this.queuedTalk = false;
        this.queuedCapture = false;
        this.queuedCraft = false;
        this.queuedChest = false;
        this.queuedParry = false; this.queuedExecute = false;
        this.nearNpc = null;
        this.tutDoneAt = new float[TUT_KEY.length];
        this.tutYaw0 = Float.NaN;
        this.tutPitch0 = Float.NaN;
        for (int i = 0; i < this.tutDoneAt.length; ++i) {
            this.tutDoneAt[i] = -1.0f;
        }
        this.talkToast = "";
        this.talkToastTimer = 0.0f;
        this.lastChronicleWritten = "";
        this.talkReply = "";
        this.talkReplyLabel = "";
        this.talkReplyTimer = 0.0f;
        this.showChronicle = false;
        this.menu = new MenuModel();
        this.appliedFov = 70;
        this.appliedViewDist = 500;
        this.lastProjFov = 70.0f;
        this.queuedPause = false;
        this.queuedMenuConfirm = false;
        this.queuedNavUp = false;
        this.queuedNavDown = false;
        this.queuedNavLeft = false;
        this.queuedNavRight = false;
        this.particles = new core.content.ParticleSim(0L);   // 真种子在 run() 里注入
    }

    public static boolean terrainOccluded(float f, float f2, float f3, float f4, float f5, float f6, Chunk[][] chunkArray, int n, int n2) {
        float f7 = f4 - f;
        float f8 = f6 - f3;
        float f9 = (float)Math.sqrt(f7 * f7 + f8 * f8);
        if (f9 < 64.0f) {
            return false;
        }
        int n3 = (int)(f9 / 16.0f);
        if (n3 < 2) {
            return false;
        }
        for (int i = 1; i < n3; ++i) {
            float f10 = (float)i / (float)n3;
            float f11 = f + f7 * f10;
            float f12 = f3 + f8 * f10;
            float f13 = f2 + (f5 - f2) * f10 + 2.0f;
            int n4 = (int)f11 >> 4;
            int n5 = (int)f12 >> 4;
            int n6 = 0;
            if (n4 >= 0 && n5 >= 0 && n4 < n && n5 < n2 && chunkArray[n4][n5] != null) {
                n6 = chunkArray[n4][n5].maxY;
            }
            if (!((float)n6 >= f13)) continue;
            return true;
        }
        return false;
    }

    public static void main(String[] stringArray) throws Exception {
        new Game().run(stringArray);
    }

    private void run(String[] args) throws Exception {
        // N5：解析启动参数（单机 seed / 联机 --host --join --port --seed --players --input-delay）
        long seed = 20260908L;
        String netMode = null, joinTarget = null;
        int port = 27700, players = 2, inputDelay = 3;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--host".equals(a)) netMode = "host";
            else if ("--join".equals(a)) { netMode = "join"; joinTarget = args[++i]; }
            else if ("--port".equals(a)) port = Integer.parseInt(args[++i]);
            else if ("--players".equals(a)) players = Integer.parseInt(args[++i]);
            else if ("--input-delay".equals(a)) inputDelay = Integer.parseInt(args[++i]);
            else if ("--seed".equals(a)) seed = Long.parseLong(args[++i]);
            else if (a.matches("-?\\d+")) seed = Long.parseLong(a);
            else if ("--debug".equals(a)) DEBUG = true;
            else this.logPhase("WARN unknown arg " + a);
        }
        try {
            if (netMode != null) {
                this.setupNetwork(netMode, joinTarget, port, players, inputDelay, seed);
            } else {
                this.sim = new Simulation(seed, 160, 112, 160);
                // QA：-Dbw.phase=<0..1> 把世界时钟设到指定日相位（0=午夜 0.25=日出 0.5=正午 0.75=日落），
                // 配合 -Dbw.snap 就能拍白天/黄昏的美术对比（截图会冻结仿真，所以不设就永远是初始的午夜）。
                String qaPhase = System.getProperty("bw.phase");
                if (qaPhase != null) {
                    try {
                        long t = (long) (core.world.DayCycle.DAY_LEN * Float.parseFloat(qaPhase.trim()));
                        this.sim.world.tick = (int) t;
                        this.logPhase("QA phase override -> tick=" + t);
                    } catch (NumberFormatException ignored) { }
                }
                this.initContentLayer(seed);
                // QA：-Dbw.yaw=<deg> / -Dbw.pitch=<deg> 固定朝向（默认沿用初始值 -45/-25）。
                // 存在的理由：QA 冻结后鼠标回调仍会收到真实 OS 事件（窗口创建时鼠标被 grab/回中），
                // 使 yaw/pitch 随"物理鼠标当时在哪"漂移 → 两次运行视角完全不同 → 截图不可复现。
                // 因此 QA 下彻底忽略鼠标视角输入，朝向只由这两个旋钮决定（截图仪器才有 A/B 意义）。
                String qaYaw = System.getProperty("bw.yaw");
                if (qaYaw != null) { try { this.yaw = Float.parseFloat(qaYaw.trim()); } catch (NumberFormatException ignored) { } }
                String qaPitch = System.getProperty("bw.pitch");
                if (qaPitch != null) { try { this.pitch = Float.parseFloat(qaPitch.trim()); } catch (NumberFormatException ignored) { } }
                this.sim.player.x = (float)this.sim.world.SX / 2.0f + 0.5f;
                this.sim.player.z = (float)this.sim.world.SZ / 2.0f + 0.5f;
                this.sim.player.y = Player.spawnY(this.sim.world);
                this.camPos.set(this.sim.player.x, this.sim.player.y + 1.6f, this.sim.player.z);
                // QA：-Dbw.edge=<0..1> 开启跨材质交界过渡（步骤 4，Noita edge_files）。
                // 出厂默认 0 = 关闭 = 与本特性落地前【逐字节等价】——这是"加段而非加道"的纪律：
                // 新渲染特性必须能在默认态自证零影响，再单独开启验收视觉。
                String qaEdge = System.getProperty("bw.edge");
                if (qaEdge != null) {
                    try {
                        core.world.EdgeAtlas.STRENGTH = core.world.EdgeAtlas.clampStrength(Float.parseFloat(qaEdge.trim()));
                        this.logPhase("QA edge strength = " + core.world.EdgeAtlas.STRENGTH);
                    } catch (NumberFormatException ignored) { }
                }
                // QA：-Dbw.smoothlight=<0..1> 块光平滑量。0 = 退回旧的「单格采样」（与改动前逐字节等价）；
                // 1（出厂）= 顶点块光取面外 2×2 格均值，消除"方块状照明"硬边（见 Chunk.blockLight）。
                String qaSmooth = System.getProperty("bw.smoothlight");
                if (qaSmooth != null) {
                    try {
                        Chunk.SMOOTH_LIGHT = Math.max(0f, Math.min(1f, Float.parseFloat(qaSmooth.trim())));
                        this.logPhase("QA smoothlight = " + Chunk.SMOOTH_LIGHT);
                    } catch (NumberFormatException ignored) { }
                }
                // QA：-Dbw.lamp=1 在出生点正前方挂一盏 LAMP（并把周围清成空气）。
                // 存在的理由：出生点附近本来一个光源都没有（实测 lamps=0/fires=0），
                // 于是"光照改动前后"的 A/B 截图逐像素相同 —— 仪器测的是空场，证不了任何事。
                // 只有把光源摆进画面，光照改动才可见。纯 QA 路径：不设该属性时世界逐字节不变。
                if ("1".equals(System.getProperty("bw.lamp"))) {
                    int lx = (int) this.sim.player.x + 3;
                    int ly = (int) this.sim.player.y + 1;
                    int lz = (int) this.sim.player.z;
                    for (int dx = -2; dx <= 2; dx++)
                        for (int dy = -1; dy <= 2; dy++)
                            for (int dz = -2; dz <= 2; dz++)
                                this.sim.world.setBlock(lx + dx, ly + dy, lz + dz, core.world.Blocks.AIR.index);
                    this.sim.world.setBlock(lx, ly, lz, core.world.Blocks.LAMP.index);
                    this.logPhase("QA lamp placed at " + lx + "," + ly + "," + lz);
                }
                // 注（2026-09-30）：-Dbw.lampscene 的放灯**不在**这里 —— 在此处（initGL 之前）放的灯会被
                // 后续初始化抹掉，渲染时 lightGrid 全 0 ⇒「灯下照明」的 A/B 变成空场（实测 blockLight
                // 真实调用 single=0.0，而此刚放好时 single=0.906）。现统一改到 initBuffers 之前重放。
                // QA：-Dbw.edgescene=1 在出生点前方砌【材质交界带】——四路调研步骤 4 的专用 A/B 仪器。
                // 存在的理由与 bw.lamp 同源：自然地形里"沙紧挨着雪、雪紧挨着石"不一定恰好出现在取景框里，
                // 不摆场景就拍不出交界过渡的差异（等于没有 A/B 证据）。
                // 摆法：宽体块（每个材质 4 格宽，不是 1 格薄墙）——这样每块只有"一条"交界，
                // 不会像 1 格薄墙那样让每种材质四面全是交界（那是病态场景，会把整面都压暗）。
                if ("1".equals(System.getProperty("bw.edgescene"))) {
                    int ex = (int) this.sim.player.x + 3;
                    int ez = (int) this.sim.player.z;
                    int ey = (int) Player.spawnY(this.sim.world);
                    int[] band = {core.world.Blocks.SAND.index, core.world.Blocks.SNOW.index,
                                  core.world.Blocks.STONE.index, core.world.Blocks.ASH.index};
                    // 每个材质 4 格宽 → 只有相邻两块之间的那条缝才是交界
                    for (int i = 0; i < band.length; i++)
                        for (int w = 0; w < 4; w++)
                            for (int dy = 0; dy <= 3; dy++)
                                for (int dz = -4; dz <= 4; dz++)
                                    this.sim.world.setBlock(ex + i * 4 + w, ey + dy, ez + dz, band[i]);
                    // 面前清空，保证取景框里看得见这排材质块
                    for (int dx = -3; dx < band.length * 4 + 3; dx++)
                        for (int dz = -6; dz <= 6; dz++)
                            for (int dy = 4; dy <= 8; dy++)
                                this.sim.world.setBlock(ex + dx, ey + dy, ez + dz, core.world.Blocks.AIR.index);
                    this.logPhase("QA edge scene built at x=" + ex + " y=" + ey + " z=" + ez);
                }
                // QA：-Dbw.forest=1 在出生点前方种一片【确定性密林】—— 体积光轴（§17）的专用 A/B 仪器。
                // 存在的理由与 bw.lamp / bw.edgescene 同源：自然地形是 SCATTER_TREE_DENOM=250 的
                // 稀疏散树，开阔取景框里**根本没有遮挡物** ⇒ 光轴在物理上就不存在（实测扫 8 个朝向全部
                // 零信号），拍不出"光穿过树冠"的画面。种一片密林才能让光轴可见。
                // 纯 QA 路径：不设该属性时世界逐字节不变；布局全走确定性整数哈希（不碰任何 RNG）。
                if ("1".equals(System.getProperty("bw.forest"))) {
                    int fx = (int) this.sim.player.x + 2;
                    int fz = (int) this.sim.player.z;
                    int fy = (int) Player.spawnY(this.sim.world);
                    final int NX = 24, HALF_Z = 12;
                    // 1) 整平一块林地（草面 + 上方清空），保证每棵树都有水平落脚点。
                    for (int i = -2; i <= NX; i++)
                        for (int j = -HALF_Z; j <= HALF_Z; j++) {
                            int x = fx + i, z = fz + j;
                            for (int dy = 1; dy <= 14; dy++)
                                this.sim.world.setBlock(x, fy + dy, z, core.world.Blocks.AIR.index);
                            this.sim.world.setBlock(x, fy, z, core.world.Blocks.GRASS.index);
                        }
                    // 2) 3 格间距的密林：树干 5~8 高，球状树冠（半径 2）。
                    //    高度用确定性整数 mix（同一坐标恒同高）——两次运行逐字节一致，无需 RNG。
                    int trees = 0;
                    for (int i = 0; i <= NX; i += 3)
                        for (int j = -HALF_Z; j <= HALF_Z; j += 3) {
                            int x = fx + i, z = fz + j;
                            int m = (x * 73856093) ^ (z * 19349663);
                            m ^= (m >>> 13); m *= 1274126177; m ^= (m >>> 16);
                            int trunk = 5 + Math.abs(m % 4);           // 5..8
                            for (int t = 1; t <= trunk; t++)
                                this.sim.world.setBlock(x, fy + t, z, core.world.Blocks.WOOD.index);
                            int cy = fy + trunk + 1;
                            for (int dx = -2; dx <= 2; dx++)
                                for (int dz = -2; dz <= 2; dz++)
                                    for (int dy = -1; dy <= 1; dy++) {
                                        int lx = x + dx, ly = cy + dy, lz = z + dz;
                                        if (lx < 0 || lx >= this.sim.world.SX || ly < 0 || ly >= this.sim.world.SY
                                                || lz < 0 || lz >= this.sim.world.SZ) continue;
                                        if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy) > 2) continue;
                                        if (this.sim.world.mat[lx][ly][lz] == core.world.Blocks.AIR.index)
                                            this.sim.world.setBlock(lx, ly, lz, core.world.Blocks.LEAF.index);
                                    }
                            trees++;
                        }
                    this.logPhase("QA forest planted at x=" + fx + " z=" + fz + " y=" + fy + " trees=" + trees);
                }
                this.logPhase("sim created (systems=" + this.sim.world.systemCount() + ")");
                if (DEBUG) { grantDebugSandbox(); this.logPhase("debug sandbox granted"); }
            }
            // 平台化：启动即把系统域报告写进 game_diag.log（域清单 + 开关状态，审计/排查用）
            for (String line : this.sim.world.registry.report().split("\n")) GameLog.log("SYS", line);
            this.initWindow();
            this.logPhase("window created");
            this.initGL();
            this.logPhase("GL ready");
            this.initCallbacks();
            this.logPhase("callbacks set");
            this.initShader();
            this.logPhase("shader linked (uVP=" + this.uVP + ")");
            this.initHudShader();
            this.logPhase("hud shader linked (hudUVP=" + this.hudUVP + ")");
            this.initWorldShader();
            this.logPhase("world shader linked (worldVP=" + this.worldVP + ")");
            File file = new File("save", "world.sav");
            if (file.exists()) {
                try {
                    FileInputStream fileInputStream = new FileInputStream(file);
                    World world = World.load(fileInputStream);
                    fileInputStream.close();
                    this.sim = new Simulation(world);
                    this.attachContentToWorld();   // P1：读档换了世界对象，内容资产要重挂
                    this.logPhase("save loaded (tick=" + this.sim.world.tick + ")");
                }
                catch (Exception exception) {
                    this.logPhase("WARN save load failed: " + exception);
                    // 2026-09-30：读档失败**不再静默**。此前只在日志留一行 WARN，玩家看到的是
                    // "进了一个新世界"，会以为进度莫名丢失（实测：旧存档因字段集变化读不进）。
                    // 现在明确上屏 8 秒 + 仍写日志。bannerText 于构造函数初始化，run() 在其后 ⇒ 不会被清。
                    this.bannerText = "SAVE INCOMPATIBLE - STARTING A NEW WORLD (see game_diag.log)";
                    this.bannerTimer = 8.0f;
                }
            }
            this.atlasTex = TextureAtlas.bake();
            this.nrmTex = TextureAtlas.bakeNormal();
            this.noiseTex = NoiseTex.bake();   // GLSL-R2：云层噪声改走纹理采样（省掉逐像素哈希，mip 顺带消走样）
            this.logPhase("texture atlas baked (" + TextureAtlas.ATLAS_PX + "px + " + TextureAtlas.MIP_LEVELS
                    + " mip levels + normal, tex=" + this.atlasTex + "/" + this.nrmTex
                    + ", noise=" + this.noiseTex + ")");
            this.initSkyShader();
            this.initUnderwaterOverlay();
            this.initCrackPass();
            this.initShadowShader();
            // 2026-09-30（仪器缺陷修复）：QA 灯具场景必须在**世界/内容全部初始化之后**重放 ——
            // 放在 1147~1220 的那些 QA 块会被后续初始化抹掉，渲染时 lightGrid 全 0，"灯下照明"的
            // A/B 就成了空场（实测：blockLight 的真实调用 single=0.0，而刚放好灯那一刻 single=0.906）。
            if ("1".equals(System.getProperty("bw.lampscene"))) {
                int rcx = (int) this.sim.player.x, rcy = (int) this.sim.player.y, rcz = (int) this.sim.player.z;
                this.sim.world.setBlock(rcx + 4, rcy, rcz, core.world.Blocks.LAMP.index);
                this.sim.world.setBlock(rcx - 4, rcy, rcz, core.world.Blocks.LAMP.index);
                this.sim.world.setBlock(rcx, rcy, rcz + 4, core.world.Blocks.LAMP.index);
                this.sim.world.setBlock(rcx, rcy, rcz - 4, core.world.Blocks.LAMP.index);
            }
            // 🩸 2026-09-30（真 bug 修复）：**光场必须在建初始网格之前算好**。
            //   渲染循环里的 computeLight 排在 initBuffers 之后 ⇒ 初始网格烘焙出的顶点块光 aLamp 全是 0
            //   （lightGrid == null 时 lightAt 直接返回 0）⇒ 世界里**自带的**发光方块（FIRE / CRYSTAL /
            //   CAMPFIRE / LAMP）在初始网格里照不亮周围，要等到某块被编辑重建才偶然而局部地出现。
            //   ⚠️ 这不是 QA 专属问题：**任何"启动即有光源"的世界**都受影响（QA 灯具场景只是把它放大到
            //   一眼可见）。零漂移：lightGrid 是渲染派生缓存（不进 hashState），computeLight 不碰 mat/mass。
            this.sim.world.computeLight();
            this.sim.world.lightDirty = false;   // 已算完 ⇒ 清脏，免得渲染循环首帧再全量算一次
            // 自证日志（纪律：QA 场景必须证明自己**真的进了画面**，而不只是"参数传了"）。
            // 必须放在 computeLight 之后才有意义 —— 之前放在之前，打印出的 self=0.0 是误导。
            if ("1".equals(System.getProperty("bw.lampscene"))) {
                int qlx = (int) this.sim.player.x + 4, qly = (int) this.sim.player.y, qlz = (int) this.sim.player.z;
                GameLog.log("QA", "lampscene(replay) self=" + this.sim.world.lightAt(qlx, qly, qlz)
                        + " nb=" + this.sim.world.lightAt(qlx - 1, qly, qlz));
            }
            this.initBuffers();
            this.initBloom();
            this.initAutoExposure();   // §18：亮度计（blit 降采样 + 读回）
            this.initGodRays();        // §19：屏幕空间体积光（quarter-res 径向模糊）
            this.initShadowVolume();   // P4：体素占用 3D 纹理（软阴影的数据源）
            this.logPhase("buffers built (totalFaces=" + this.totalFaces() + ")");
            this.initOverlayBuffers();
            this.logPhase("overlay buffers ready");
            this.initMsdfShader();
            MsdfFont.glInit();
            this.logPhase("msdf shader linked (msdfUVP=" + this.msdfUVP + ")  " + MsdfFont.status());
            this.audio = new AudioOut();
            this.audio.start();
            this.logPhase("audio: " + this.audio.status());
            this.lastNs = System.nanoTime();
            this.logPhase("LOOP START");
            this.logPhase("CJK font: " + CjkFont.status());
            this.loop();
            this.logPhase("LOOP END (window closed)");
        }
        catch (Throwable throwable) {
            this.logCrash(throwable);
            throw throwable;
        }
        finally {
            if (this.diag != null) {
                try {
                    this.diag.println("render_diag.log  end");
                    this.diag.flush();
                    this.diag.close();
                }
                catch (Exception exception) {}
            }
            if (this.talkLog != null) {
                try {
                    this.talkLog.println("dialogue.log  end");
                    this.talkLog.flush();
                    this.talkLog.close();
                }
                catch (Exception exception) {}
            }
            if (this.chronicleLog != null) {
                try {
                    this.chronicleLog.println("chronicle.log  end");
                    this.chronicleLog.flush();
                    this.chronicleLog.close();
                }
                catch (Exception exception) {}
            }
            if (this.audio != null) {
                this.audio.close();
            }
            if (this.relay != null) {
                try { this.relay.close(); } catch (Exception exception) {}
            }
        }
    }

    // ---- N5：联机启动（与 core.net.NetMain 同构，但接的是本渲染主循环）----
    private void setupNetwork(String mode, String joinTarget, int port, int players, int inputDelay, long seed) throws Exception {
        core.net.UdpTransport.Joined j;
        if ("host".equals(mode)) {
            relay = core.net.UdpRelay.bind(port, seed, players);
            relay.startBackground();
            this.logPhase("HOST relay port=" + relay.localPort());
            j = core.net.UdpTransport.join("127.0.0.1", relay.localPort(), 20000);
        } else {
            int colon = joinTarget.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("--join 需要 HOST:PORT");
            j = core.net.UdpTransport.join(joinTarget.substring(0, colon),
                    Integer.parseInt(joinTarget.substring(colon + 1)), 20000);
        }
        long s = j.seed;
        this.sim = new Simulation(s, 160, 112, 160);
        this.initContentLayer(s);
        this.sim.player.x = (float)this.sim.world.SX / 2.0f + 0.5f;
        this.sim.player.z = (float)this.sim.world.SZ / 2.0f + 0.5f;
        this.sim.player.y = Player.spawnY(this.sim.world);
        this.camPos.set(this.sim.player.x, this.sim.player.y + 1.6f, this.sim.player.z);
        this.logPhase("net sim created id=" + j.yourId + " players="
                + java.util.Arrays.toString(j.playerIds) + " seed=" + s);
        // 本 tick 已注入的意图（arr[0]，最低 id；两端同序 → 确定性位移），供 gameTickBody 读取
        final Player.Intent[] applied = { Player.Intent.idle() };
        core.net.LockstepSession.InputSink sink = (t, arr) -> {
            this.sim.player.setIntent(arr[0]);   // 给 world.tick 消费（战斗/aggro），与单机一致
            applied[0] = arr[0];                  // 给 gameTickBody 做确定性位移
        };
        // 整 tick 推进体 = 位移（固定步长，零帧 dt）+ 单行循环那 4 行（world.tick + 内容层），
        // 与单机演化逐字节等价（单机由 physicsTick + 内联 4 行完成，形态不同但每 tick 输入同源）
        core.net.TickBody gameTick = (core.world.World w) -> {
            Player.Intent it = applied[0];
            float mvx = 0.0f, mvz = 0.0f;
            if (it != null && it.type == Player.Intent.Type.MOVE) {
                mvx = it.dx; mvz = it.dz;
                float len = (float)Math.hypot(mvx, mvz);
                if (len > 1.0f) { mvx /= len; mvz /= len; }
                float sp = 4.5f;
                mvx *= sp; mvz *= sp;
            }
            w.player.physicsTick(w, mvx, mvz, false, false, false, 0.05f);  // 确定性位移（先于 world.tick）
            w.tick();
            this.rules.tick(w, this.contentSys.queue(), w.tick);
            this.techs.tick(w, this.contentSys.queue(), w.tick, this.inventory);
            this.contentSys.update(w, null);   // A 批：内容层引擎（rng 不传 —— 内容层零 RNG）
            this.questEngine.tick(w, w.tick);      // P1：任务引擎（联机两端同码 → 同结果）
        };
        this.netSession = new core.net.LockstepSession(this.sim.world, j.yourId, j.playerIds, inputDelay, j.transport, sink, gameTick);
    }

    /** N5：本地键盘 → 本 tick 的 Intent（移动按 Cardinal/Diagonal 量化；左键=攻击）。*/
    private Player.Intent deriveNetIntent(Vector3f fwd, Vector3f fwdRight) {
        if (this.mouseHeldL || this.mouseHeldR) return Player.Intent.attack();
        int ix = 0, iz = 0;
        if (this.keys[87]) { ix += (int)Math.signum(fwd.x); iz += (int)Math.signum(fwd.z); }
        if (this.keys[83]) { ix -= (int)Math.signum(fwd.x); iz -= (int)Math.signum(fwd.z); }
        if (this.keys[68]) { ix += (int)Math.signum(fwdRight.x); iz += (int)Math.signum(fwdRight.z); }
        if (this.keys[65]) { ix -= (int)Math.signum(fwdRight.x); iz -= (int)Math.signum(fwdRight.z); }
        if (ix == 0 && iz == 0) return Player.Intent.idle();
        return Player.Intent.move(ix, iz);
    }

    private void logPhase(String string) {
        if (this.diag == null) {
            return;
        }
        try {
            this.diag.printf(Locale.US, "[PHASE] %s%n", string);
            this.diag.flush();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private void logCrash(Throwable throwable) {
        if (this.diag == null) {
            return;
        }
        try {
            this.diag.printf(Locale.US, "[CRASH] %s : %s%n", throwable.getClass().getName(), String.valueOf(throwable.getMessage()));
            throwable.printStackTrace(this.diag);
            this.diag.flush();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private void initWindow() {
        if (!GLFW.glfwInit()) {
            throw new RuntimeException("glfwInit failed");
        }
        GLFW.glfwWindowHint((int)131075, (int)0);   // LD-2026-09-13 GLFW_FOCUSED=false：创建时不抢焦点，用户点击才开始（根除启动焦点抖动）
        // QA（2026-09-21）：-Dbw.snap 下创建**隐藏窗口**（GLFW_VISIBLE=false）。
        // 存在理由：无头截图只需一个离屏 GL 上下文，但"可见窗口"要求真有桌面会话
        // （在 CI / 无交互 shell / 远程会话里 glfwCreateWindow 会直接失败 → 截图永远出不来，
        // 而本地开发机有桌面时又看不出来）。tools/ShaderPreview 从一开始就是这么做的，
        // 现在把同一手法用到游戏本体，让 -Dbw.snap 在**任何**环境下都能出图。
        // §18：`-Dbw.show=1` 例外 —— 隐藏窗口上驱动**会拒绝 MSAA**（实测 GLFW_SAMPLES(req)=4 →
        // GL_SAMPLES(actual)=0），于是"A2C 修镂空边缘"这类**只在 MSAA 下存在**的特性在无头仪器上
        // 永远测不出来（灯下黑）。该开关让 QA 用**可见**窗口出图，专门用来验 MSAA 相关特性；
        // 代价是 resolve 非逐位确定 ⇒ 只能做统计 A/B（见 msaaSamples 注释）。
        boolean qaShow = qaFrozen && "1".equals(System.getProperty("bw.show"));
        if (this.qaFrozen && !qaShow) GLFW.glfwWindowHint((int)131076, (int)0);   // GLFW_VISIBLE=0x00020004
        GLFW.glfwWindowHint((int)139266, (int)3);
        GLFW.glfwWindowHint((int)139267, (int)3);
        GLFW.glfwWindowHint((int)139272, (int)204801);
        GLFW.glfwWindowHint((int)139270, (int)1);
        GLFW.glfwWindowHint((int)135173, (int)24);
        GLFW.glfwWindowHint((int)135174, (int)8);
        // A·MSAA 硬件抗锯齿：默认帧缓冲按 4x 多重采样创建（当前渲染器零 AA，边缘锯齿/黑边最直接修法；纯渲染，零漂移）
        // QA 例外（2026-09-21）：MSAA 的 resolve 不是逐位确定的（同版本连跑两次全屏散点不同，实测 93% 像素差异、
        // 与任何游戏状态无关）。无头截图是 A/B 仪器，必须逐字节可比 → QA 下关 MSAA（0 samples），运行时保持 4x。
        GLFW.glfwWindowHint((int)327681, this.msaaSamples);   // GLFW_SAMPLES=0x00050001（见 msaaSamples 字段：QA 默认 0，运行期 4）
        this.window = GLFW.glfwCreateWindow((int)this.W, (int)this.H, (CharSequence)"Breathing World [" + this.buildTag() + "]  -  Java/LWJGL3", (long)0L, (long)0L);
        if (this.window == 0L) {
            throw new RuntimeException("glfwCreateWindow failed");
        }
        GLFW.glfwMakeContextCurrent((long)this.window);
        // QA 卡死根因（2026-09-21）：开启 vsync 后，`glfwSwapBuffers` 会等待显示器的垂直回扫信号。
        // 在**无桌面会话**（CI / 远程 shell）下隐藏窗口没有绑定的显示器 → 该等待永不返回 →
        // 主循环在第一帧就挂死（实测：日志停在 IME detached 之后，90 秒零输出、无 HB 心跳、无截图）。
        // 无头截图不需要呈现节流，故 QA 下关闭 vsync（这不影响画面内容，只是不等回扫）。
        GLFW.glfwSwapInterval(this.qaFrozen ? 0 : 1);
        // LD-2026-09-13：解除本窗口的 IME 关联——游戏窗口永远英文输入（字母键不被输入法拦截）
        try {
            Imm32Lib.INSTANCE.ImmAssociateContext((long)this.window, null);
            GameLog.log("IME", "detached (window is EN-only)");
        } catch (Throwable t) {
            GameLog.log("IME", "detach failed (JNA unavailable): " + t);
        }
    }

    private void initGL() {
        GL.createCapabilities();
        int n = GL33.glGetError();
        // 注：本方法内 f2/f3/f4/f5/f6 为 CFR 遗留的局部复用临时变量（后续分块还原时再改名）：
        //   f5/f6 先用于出生点偏移判定，后分别被赋值为 starAlpha / ambient；
        //   f2/f3/f4 在区块循环与射线框绘制中复用为临时 dx/dy/dz。语义以每次赋值处为准。
        GL33.glEnable((int)2929);
        // §18 A2C：让片元的 alpha 参与 MSAA 覆盖掩码 —— 修 discard 镂空（树叶/草）的边缘：
        // `discard` 是逐像素二值的，MSAA 本身对它的边缘无效；配合片元里用 mip1 alpha 估的
        // 分数覆盖率，硬台阶就变成软过渡。MSAA=0（QA 默认）时本开关是 no-op ⇒ 截图不受影响。
        GL33.glEnable((int)32926);   // GL_SAMPLE_ALPHA_TO_COVERAGE = 0x809E
        GL33.glClearColor((float)0.5f, (float)0.72f, (float)0.95f, (float)1.0f);
        GL33.glViewport((int)0, (int)0, (int)this.W, (int)this.H);
        this.proj.setPerspective((float)Math.toRadians(70.0), (float)this.W / (float)this.H, 0.1f, 500.0f);
        String string = GL33.glGetString((int)7938);
        // §18：把**真实**的默认帧缓冲采样数读回来（请求 GLFW_SAMPLES=4 不等于拿到 4 —— 驱动/隐藏窗口
        // 都可能拒绝）。A2C 只在 samples>1 时才有意义，所以这个读数必须在日志里可见，否则"A2C 没效果"
        // 会被误判成"实现错了"。
        int actualSamples = GL33.glGetInteger((int)32937);   // GL_SAMPLES = 0x80A9
        this.openDiag();
        this.logPhase("GL version=" + string + "  glErrAfterCreate=" + Integer.toHexString(n));
        this.logPhase("GLFW_SAMPLES(req)=" + this.msaaSamples + "  GL_SAMPLES(actual)=" + actualSamples
                + "  a2c=" + this.a2cAmount + "  dither=" + this.ditherAmt);
    }

    private void openDiag() {
        try {
            this.diag = new PrintWriter(new FileWriter(DIAG_PATH, false));
            this.diag.println("render_diag.log  start  W=" + this.W + " H=" + this.H);
            this.diag.flush();
        }
        catch (Exception exception) {
            this.diag = null;
        }
        try {
            this.talkLog = new PrintWriter(new OutputStreamWriter((OutputStream)new FileOutputStream("dialogue.log", false), "UTF-8"));
            this.talkLog.println("dialogue.log  start\uff08T \u952e\u4e0e\u6751\u6c11\u4ea4\u8c08\uff09");
            this.talkLog.flush();
        }
        catch (Exception exception) {
            this.talkLog = null;
        }
        try {
            this.chronicleLog = new PrintWriter(new OutputStreamWriter((OutputStream)new FileOutputStream("chronicle.log", false), "UTF-8"));
            this.chronicleLog.println("chronicle.log  start\uff08\u8bf4\u4e66\u4eba \u00b7 \u5f53\u65e5\u6751\u5fd7\uff09");
            this.chronicleLog.flush();
        }
        catch (Exception exception) {
            this.chronicleLog = null;
        }
    }

    private void initCallbacks() {
        GLFW.glfwSetKeyCallback((long)this.window, (l, n, n2, n3, n4) -> {
            try {
            this.lastInputMs = System.currentTimeMillis();
            if (n3 == 1) { GameLog.log("KEY", "key=" + n + " focused=" + this.windowFocused + " paused=" + this.sim.world.paused); }
            // 告示牌文本编辑态（第七批）：独占键盘 —— 字母/数字/符号入缓冲，ENTER 保存 / ESC 取消 / BACKSPACE 退格。
            // **必须在下面写 keys[] 之前拦截**：否则打字会同时把 W/A/S/D 置真 → 边打字边走路，
            // 且 Q/F/R 等会顺着后面的分支触发技能。这里直接 return 即"吞掉该键"。
            if (this.signEditing) {
                if (n3 == 1) this.signEditKey(n, n4);
                return;
            }
            if (n >= 0 && n < this.keys.length) {
                if (n3 == 1) {
                    this.keys[n] = true;
                } else if (n3 == 0) {
                    this.keys[n] = false;
                }
            }
            if (n == 294 && n3 == 1) {
                boolean bl = this.thirdPerson = !this.thirdPerson;
            }
            if (n == 298 && n3 == 1) {
                this.quickSave();
            }
            if (n == 299 && n3 == 1) {
                this.quickLoad();
            }
            if (n == 292 && n3 == 1) {
                boolean bl = this.debugHud = !this.debugHud;
                // 按 F3 时把「系统域 + 开关状态」快照写进日志（报障时可直接取证）
                if (this.debugHud) for (String line : this.sim.world.registry.report().split("\n")) GameLog.log("SYS", line);
            }
            if (n == 295 && n3 == 1) {          // F6：调试沙盒开关（测试期“什么都有”，正式发布默认关 → 初态）
                this.toggleDebugSandbox();
            }
            if (n == 72 && n3 == 1) { this.menu.showHud = !this.menu.showHud; }
            if (n == 77 && n3 == 1 && !this.menu.isOpen()) {          // M：在已获得武器间循环（P0-3 手动取舍）
                if (this.sim.player.cycleWeapon()) {
                    this.toastText = "WEAPON " + this.sim.player.weaponName()
                            + "  ATK+" + Weapons.def(this.sim.player.weapon).atk;
                    this.toastTimer = 1.6f;
                } else {
                    this.toastText = "NO OTHER WEAPON YET";
                    this.toastTimer = 1.0f;
                }
            }
            if (n == 75 && n3 == 1 && !this.menu.isOpen()) {              // K：学习技能树（学第一个可学的）
                java.util.List<String> avail = this.skillTree.availableIds(this.sim.player);
                if (avail.isEmpty()) {
                    this.bannerText = "No skill available (need prereq or souls)";
                    this.bannerTimer = 2.0f;
                    this.sfx(Sfx.DENY);
                } else {
                    String sid = avail.get(0);
                    core.content.SkillTree.Node node = this.skillTree.node(sid);
                    if (this.skillTree.learn(node, this.sim.player, this.sim.world)) {
                        this.bannerText = "Learned " + sid + "  (souls " + this.sim.player.souls + ")";
                        this.bannerTimer = 2.6f;
                        this.sfx(Sfx.UNLOCK);
                    }
                }
            }
            if (n == 85 && n3 == 1 && !this.menu.isOpen()) {              // U：喝药（ER 风药瓶）
                if (this.sim.player.useFlask(this.sim.world)) {
                    this.sfx(Sfx.LEVELUP);
                    this.toastText = "FLASK  " + this.sim.player.flask + " / " + Player.FLASK_MAX;
                    this.toastTimer = 1.2f;
                } else {
                    this.sfx(Sfx.DENY);
                }
            }
            if (n == 69 && n3 == 1 && !this.menu.isOpen()) { this.inventoryOpen = !this.inventoryOpen; }   // E 背包界面）
            if (n == 296 && n3 == 1) {
                this.emergencyRespawn();
            }
            // GLFW 键码：290=F1 … 292=F3, 294=F5, 296=F7, **297=F8**, 298=F9, 299=F10
            // （曾把 F8 写成 293 = F4，被 audit_invariants.py 的 C9「已绑定键必须有文档」当场拦下）
            if (n == 297 && n3 == 1 && !this.menu.isOpen()) {              // F8：调试 —— 把守卫召唤到身边
                // P0-4 验收快捷键：满血重召一只世界之心守卫，方便反复试破势 / 翻滚 i 帧 / 药瓶。
                // 只往实体层（beasts）塞一只 Beast + 置 bossSpawned 标志；不写 mat/mass、不碰任何 RNG
                // → 四基线指纹不受影响（门禁也不会按这个键）。打死它仍走正常结算（combat.repel → 繁荣链）。
                World dw = this.sim.world;
                for (int i = dw.beasts.size() - 1; i >= 0; i--) {
                    if (dw.beasts.get(i).type == Beast.TYPE_BOSS) dw.beasts.remove(i);
                }
                core.systems.BeastSystem.spawnWarden(dw, this.sim.player.x + 4.0f, this.sim.player.z,
                        this.sim.player.y);
                dw.trials.bossSpawned = true;
                this.bannerText = "DEBUG: SUMMIT WARDEN SUMMONED";
                this.bannerTimer = 2.6f;
                this.sfx(Sfx.UNLOCK);
            }
            if (n == 291 && n3 == 1 && !this.menu.isOpen()) {   // F2：手持火把开关（测试点光，零仿真漂移）
                this.heldTorch = !this.heldTorch;
                this.toastText = this.heldTorch ? ("TORCH ON  r=" + (int)this.torchRadius) : "TORCH OFF";
                this.toastTimer = 1.4f;
            }
            if (n == 290 && n3 == 1 && !this.menu.isOpen()) {   // F1：循环火把半径
                this.torchRadIdx = (this.torchRadIdx + 1) % TORCH_RADII.length;
                this.torchRadius = TORCH_RADII[this.torchRadIdx];
                this.toastText = "TORCH RADIUS " + (int)this.torchRadius;
                this.toastTimer = 1.4f;
            }
            if (n3 == 1) {
                if (n == 256) {
                    this.queuedPause = true;
                } else if (n == 257 || n == 335) {
                    this.queuedMenuConfirm = true;
                } else if (n == 265 || n == 87) {
                    this.queuedNavUp = true;
                } else if (n == 264 || n == 83) {
                    this.queuedNavDown = true;
                } else if (n == 263 || n == 65) {
                    this.queuedNavLeft = true;
                } else if (n == 262 || n == 68) {
                    this.queuedNavRight = true;
                }
            }
            if (n == 70 && n3 == 1 && !this.menu.isOpen()) {
                this.sim.player.setIntent(Player.Intent.attack());
                this.sfx(Sfx.SWING);
            }
            if (n == 82 && n3 == 1) {
                this.queuedArt = true;
            }
            if (n == 71 && n3 == 1) {
                this.queuedCycleArt = true;
            }
            if (n == 81 && n3 == 1) {
                this.queuedRoll = true;
            }
            if (n == 258 && n3 == 1) {
                this.queuedLock = true;
            }
            if (n == 54 && n3 == 1) {
                this.queuedBomb = true;
            }
            if (n == 55 && n3 == 1) {
                this.queuedIgnite = true;
            }
            if (n == 74 && n3 == 1) {
                this.queuedAqua = true;
            }
            if (n == 76 && n3 == 1) {
                this.queuedThunder = true;
            }
            if (n == 79 && n3 == 1) {
                this.queuedSkillSwitch = true;
            }
            if (n == 80 && n3 == 1) {
                this.queuedSkillCast = true;
            }
            if (n == 90 && n3 == 1) {
                this.queuedInvest = "STR";
            }
            if (n == 88 && n3 == 1) {
                this.queuedInvest = "VIT";
            }
            if (n == 67 && n3 == 1) {
                this.queuedInvest = "END";
            }
            if (n == 86 && n3 == 1) {
                this.queuedInvest = "DEX";
            }
            if (n == 84 && n3 == 1) {
                this.queuedTalk = true;
            }
            if (n == 89 && n3 == 1) {
                this.queuedCapture = true;
            }
            if (n == 78 && n3 == 1) {                       // N：小地图开关
                this.minimapOn = !this.minimapOn;
            }
            if (n == 73 && n3 == 1) {                       // I：手搓合成
                this.queuedCraft = true;
            }
            if (n == 301 && n3 == 1 && !this.menu.isOpen()) { // F12：打开箱子
                this.queuedChest = true;
            }
            if (n == 293 && n3 == 1 && !this.menu.isOpen()) this.queuedParry = true;
            if (n == 300 && n3 == 1 && !this.menu.isOpen()) this.queuedExecute = true;
            if (n == 66 && n3 == 1) {
                boolean bl = this.showChronicle = !this.showChronicle;
            }
            if (n3 == 1) {
                if (n == 32) {
                    this.tutMark(2);
                } else if (n == 70) {
                    this.tutMark(3);
                } else if (n == 82 || n == 71) {
                    this.tutMark(4);
                } else if (n == 81) {
                    this.tutMark(5);
                } else if (n == 258) {
                    this.tutMark(6);
                } else if (n == 84 || n == 66) {
                    this.tutMark(7);
                } else if (n == 256) {
                    this.tutMark(8);
                } else if (!(n != 87 && n != 65 && n != 83 && n != 68 || this.menu.isOpen())) {
                    this.tutMark(0);
                }
            }
            } catch (Throwable t) {
                GameLog.err("keyCB", t);
            }
        });
        GLFW.glfwSetCursorPosCallback((long)this.window, (l, d, d2) -> {
            if (this.firstMouse || this.menu.isOpen() || this.inventoryOpen || this.signEditing) {   // 背包/告示牌编辑开着：移动鼠标不转视角
                this.lastMx = d;
                this.lastMy = d2;
                this.firstMouse = false;
                return;
            }
            // QA：无头截图下彻底忽略鼠标视角输入（真实 OS 事件会让 yaw/pitch 随物理鼠标位置漂移 → 截图不可复现）。
            // 只在更新 lastMx/lastMy 后返回，保证后续即使解冻也不会一次性吃到巨大 delta。
            if (this.qaFrozen) {
                this.lastMx = d;
                this.lastMy = d2;
                return;
            }
            float f = (float)(d - this.lastMx);
            float f2 = (float)(this.lastMy - d2);
            this.lastMx = d;
            this.lastMy = d2;
            float f3 = this.menu.mouseSens();
            this.yaw += f * f3;
            this.pitch = Math.max(-89.0f, Math.min(89.0f, this.pitch + f2 * f3));
            // QA 2026-09-15：转视角也必须算"有输入" —— 否则本机焦点被抢走时，只要 5 秒没按键盘，
            // 世界就会被自动暂停（表现为反复卡顿 + 昼夜毫无规律地跳）。这是"好卡"的真实根因之一。
            this.lastInputMs = System.currentTimeMillis();
            if (Float.isNaN(this.tutYaw0)) {
                this.tutYaw0 = this.yaw;
                this.tutPitch0 = this.pitch;
            } else if (Math.abs(this.yaw - this.tutYaw0) > 0.35f || Math.abs(this.pitch - this.tutPitch0) > 0.25f) {
                this.tutMark(1);
            }
        });
        GLFW.glfwSetFramebufferSizeCallback((long)this.window, (l, n, n2) -> {
            this.W = n;
            this.H = n2;
            // E 批：同步窗口逻辑尺寸 —— 光标坐标是窗口坐标，HUD 是帧缓冲坐标；DPI 缩放时两者不等
            try {
                java.nio.IntBuffer bw = org.lwjgl.BufferUtils.createIntBuffer(1);
                java.nio.IntBuffer bh = org.lwjgl.BufferUtils.createIntBuffer(1);
                GLFW.glfwGetWindowSize((long)this.window, bw, bh);
                this.winW = bw.get(0);
                this.winH = bh.get(0);
            } catch (Throwable t) {
                GameLog.err("winSize", t);
            }
            GL33.glViewport((int)0, (int)0, (int)this.W, (int)this.H);
            this.resizeBloom();
            this.proj.setPerspective((float)Math.toRadians(this.menu.fov), (float)this.W / (float)this.H, 0.1f, (float)this.menu.viewDist);
            this.appliedFov = this.menu.fov;
            this.appliedViewDist = this.menu.viewDist;
            this.lastProjFov = this.menu.fov;
        });
        GLFW.glfwSetMouseButtonCallback((long)this.window, (l, n, n2, n3) -> {
            if (n2 == 1) {
                this.handleClick(n);
            }
            if (n == 0) {
                boolean bl = this.mouseHeldL = n2 == 1;
            }
            if (n == 1) {
                this.mouseHeldR = n2 == 1;
            }
        });
        GLFW.glfwSetWindowFocusCallback((long)this.window, (l, bl) -> {
            GameLog.log("FOCUS", "focused=" + bl);
            if (bl) this.everFocused = true;
            this.windowFocused = bl;
            if (!bl) this.lastFocusLossMs = System.currentTimeMillis();
            if (!bl) {
                Arrays.fill(this.keys, false);
                this.clearQueuedActions();
                this.discardNav();
                this.queuedPause = false;
                this.queuedMenuConfirm = false;
                this.locked = null;
            }
        });
        GLFW.glfwSetInputMode((long)this.window, (int)208897, (int)212995);
    }

    private void handleMenu() {
        // QA 旋钮 -Dbw.menu=1：强制打开主菜单（供无头截图验证菜单内的大字号标题）。
        // 与 -Dbw.phase 同理：**每帧重施**，否则第一次的 open() 会被后续逻辑翻回去。
        if (this.qaMenu && !this.menu.isOpen()) this.menu.open();
        if (this.queuedPause) {
            this.queuedPause = false;
            if (!this.menu.isOpen()) {
                this.menu.open();
                this.setCursorMode(false);
                this.sfx(Sfx.MENU_SELECT);
            } else {
                this.sfx(Sfx.MENU_BACK);
                this.applyMenuAction(this.menu.back());
            }
        }
        if (this.menu.isOpen()) {
            if (this.queuedNavUp) {
                this.queuedNavUp = false;
                this.menu.up();
                this.sfx(Sfx.MENU_MOVE);
            }
            if (this.queuedNavDown) {
                this.queuedNavDown = false;
                this.menu.down();
                this.sfx(Sfx.MENU_MOVE);
            }
            if (this.queuedNavLeft) {
                this.queuedNavLeft = false;
                this.menu.adjust(-1);
                this.sfx(Sfx.MENU_MOVE);
            }
            if (this.queuedNavRight) {
                this.queuedNavRight = false;
                this.menu.adjust(1);
                this.sfx(Sfx.MENU_MOVE);
            }
            if (this.queuedMenuConfirm) {
                this.queuedMenuConfirm = false;
                this.sfx(Sfx.MENU_SELECT);
                this.applyMenuAction(this.menu.activate());
            }
            // F 批：进入捏脸页时种子工作副本；在页内实时回写玩家外观（身份数据，不进指纹）
            if (this.menu.page() == MenuModel.Page.CHARACTER) {
                if (this.prevMenuPage != MenuModel.Page.CHARACTER) {
                    this.menu.openCharacter(this.sim.player.appearance, this.contentProvider);
                }
                this.sim.player.appearance.copyFrom(this.menu.working);
            }
            // 预设选择器：比较「已施加」与「菜单选中」，而不是只盯某一次按键 ——
            // 方向键 / 确认键 / 鼠标任何一条改变选择的路径都走同一处施加逻辑。
            if (!presetIdEq(this.menu.presetId(), this.appliedPresetId)) {
                this.applySelectedPreset(true, false);
            }
            this.prevMenuPage = this.menu.page();
        } else {
            this.discardNav();
            this.queuedMenuConfirm = false;
        }
        this.sim.world.paused = this.menu.isOpen() || (!this.isActiveNow() && this.everFocused);   // 输入防抖：5 秒内有过输入就不算挂起（焦点抖动免疫）
    }

    private void applyMenuAction(MenuModel.Action action) {
        if (action == MenuModel.Action.QUIT) {
            GLFW.glfwSetWindowShouldClose((long)this.window, (boolean)true);
            return;
        }
        if (action == MenuModel.Action.RESUME) {
            this.menu.close();
            this.setCursorMode(true);
        }
    }

    private void setCursorMode(boolean bl) {
        GLFW.glfwSetInputMode((long)this.window, (int)208897, (int)(bl ? 212995 : 212993));
        this.firstMouse = true;
    }

    private void applySettings() {
        if (prevShowHud != menu.showHud) { prevShowHud = menu.showHud; GameLog.log("HUD", "showHud=" + menu.showHud); }
        if (this.appliedFov != this.menu.fov || this.appliedViewDist != this.menu.viewDist) {
            this.appliedFov = this.menu.fov;
            this.appliedViewDist = this.menu.viewDist;
            this.lastProjFov = this.menu.fov;
            this.proj.setPerspective((float)Math.toRadians(this.appliedFov), (float)this.W / (float)this.H, 0.1f, (float)this.appliedViewDist);
        }
        if (this.audio != null && (this.appliedVolume != this.menu.volume || this.appliedSfxOn != this.menu.sfxOn)) {
            this.appliedVolume = this.menu.volume;
            this.appliedSfxOn = this.menu.sfxOn;
            this.audio.mixer().setEnabled(this.menu.sfxOn);
            this.audio.mixer().setVolume(this.menu.volumeF());
        }
    }

    private void sfx(Sfx sfx) {
        if (this.audio != null) {
            this.audio.mixer().play(sfx);
        }
    }

    private void discardNav() {
        this.queuedNavRight = false;
        this.queuedNavLeft = false;
        this.queuedNavDown = false;
        this.queuedNavUp = false;
    }

    private void clearQueuedActions() {
        this.queuedArt = false;
        this.queuedRoll = false;
        this.queuedLock = false;
        this.queuedCycleArt = false;
        this.queuedBomb = false;
        this.queuedIgnite = false;
        this.queuedSkillCast = false;
        this.queuedSkillSwitch = false;
        this.queuedAqua = false;
        this.queuedThunder = false;
        this.queuedInvest = null;
        this.queuedTalk = false;
        this.queuedCapture = false;
        this.queuedCraft = false;
        this.queuedChest = false;
        this.queuedParry = false; this.queuedExecute = false;
    }

    private String pageHint() {
        switch (this.menu.page()) {
            case MAIN: {
                return "\u6e38\u620f\u5df2\u6682\u505c";
            }
            case SETTINGS: {
                return "\u8bbe\u7f6e\u00b7\u5373\u65f6\u751f\u6548";
            }
            case CONTROLS: {
                return "\u6309\u952e\u8bf4\u660e";
            }
            case QUIT_CONFIRM: {
                return "\u786e\u8ba4\u9000\u51fa\uff1f";
            }
            case CHARACTER: {
                return "\u634f\u8138\u00b7\u5373\u65f6\u9884\u89c8";
            }
        }
        return "";
    }

    private void drawMenuOverlay(FloatBuffer floatBuffer) {
        int n = this.menu.itemCount();
        boolean bl = this.menu.page() == MenuModel.Page.CONTROLS;
        // MC 风格：全屏暗化 + 按钮直接浮于暗背景（无面板框）
        this.addRect2D(floatBuffer, 0.0f, 0.0f, this.W, this.H, 0.0f, 0.0f, 0.0f, 0.6f);
        float f = bl ? 460.0f : 320.0f;                       // 按钮宽（MC 200*2 比例）
        float f2 = 30.0f;                                     // 按钮高
        float f3 = 110.0f + (float)n * (f2 + 6.0f);           // 内容总高（标题+按钮列）
        float f6 = ((float)this.W - f) / 2.0f;                // 按钮左
        float f7 = (float)this.H * 0.28f;                     // 标题顶
        // ---- P1（2026-09-23）：中文大字号标题（MSDF）----
        // 上面那行 ASCII 标题走 5x7 位图 Font，放大 1.6 倍已经把方块像素拉大（边缘是阶梯）。
        // 中文标题用 MSDF：距离场 + fwidth 换算，46px 下依旧是连续边缘；且描边是由距离阈值给的
        // （不重新光栅化）。字号刻意取得大 —— 这正是位图字体最糊、MSDF 最有优势的区间。
        if (MsdfFont.available()) {
            float zhSize = 46.0f;
            float zhW = MsdfFont.width(ZH_TITLE, zhSize);
            MsdfFont.draw(this.msdfBuf, ((float)this.W - zhW) / 2.0f, Math.max(8.0f, f7 - zhSize - 10.0f),
                    zhSize, 0.97f, 0.95f, 0.90f, 1.0f, ZH_TITLE, this.W, this.H);
            float subSize = 15.0f;
            float subW = MsdfFont.width(ZH_SUBTITLE, subSize);
            MsdfFont.draw(this.msdfBuf, ((float)this.W - subW) / 2.0f,
                    Math.max(8.0f, f7 - zhSize - 10.0f) + zhSize + 4.0f,
                    subSize, 0.70f, 0.76f, 0.84f, 0.95f, ZH_SUBTITLE, this.W, this.H);
        }
        Font.draw(floatBuffer, (float)this.W / 2.0f - Font.width(this.menu.title(), 1.6f) / 2.0f, f7, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f, this.menu.title(), this.W, this.H);
        // 暂停页世界状态（纯渲染、只读）：暂停时最想知道"我在哪、世界什么样"。
        // 只在主菜单页加 —— CONTROLS/CHARACTER 这类长列表页按钮已经占满，再加会挤爆。
        if (this.menu.page() == MenuModel.Page.MAIN) {
            World pw = this.sim.world;
            float pph = DayCycle.phase(pw.tick);
            String st1 = "DAY " + DayCycle.dayNumber(pw.tick) + "  " + DayCycle.clock(pph) + "  "
                    + DayCycle.label(pph) + "  " + (pw.raining ? "RAIN" : "CLEAR");
            String st2 = "XYZ " + (int)this.sim.player.x + " / " + (int)this.sim.player.y + " / "
                    + (int)this.sim.player.z + "   BEASTS " + pw.beasts.size()
                    + "   PROSPERITY " + pw.prosperity;
            // 注意：f8（按钮列顶）在本方法里声明在标题绘制之后，此处直接用等价表达式，避免前向引用。
            float sty = f7 + 46.0f + (float)n * (f2 + 6.0f) + 26.0f;
            Font.draw(floatBuffer, ((float)this.W - Font.width(st1, 0.9f)) / 2.0f, sty, 0.9f,
                    0.80f, 0.86f, 0.95f, 0.95f, st1, this.W, this.H);
            Font.draw(floatBuffer, ((float)this.W - Font.width(st2, 0.9f)) / 2.0f, sty + 22.0f, 0.9f,
                    0.68f, 0.74f, 0.84f, 0.9f, st2, this.W, this.H);
        }
        float f8 = f7 + 46.0f;                                // 按钮列顶
        // 视口滚动：行数超过可视区时，让选中行始终可见（CONTROLS/CHARACTER 等长列通用）
        float fBottom = (float)this.H * 0.86f;
        int rowH = (int)(f2 + 6.0f);
        int visRows = Math.max(1, (int)((fBottom - f8) / (float)rowH));
        int selIdx = this.menu.selected();
        int scroll = 0;
        if (n > visRows) {
            if (selIdx < scroll) scroll = selIdx;
            else if (selIdx >= scroll + visRows) scroll = selIdx - visRows + 1;
            if (scroll < 0) scroll = 0;
            if (scroll > n - visRows) scroll = n - visRows;
        }
        for (int i = scroll; i < Math.min(n, scroll + visRows); ++i) {
            boolean bl2 = i == selIdx;
            float f9 = f8 + (float)(i - scroll) * (f2 + 6.0f);
            // MC 按钮体：灰石底 + 亮顶边/暗底边
            this.addRect2D(floatBuffer, f6, f9, f, f2, 0.42f, 0.42f, 0.46f, bl2 ? 0.98f : 0.85f);
            this.addRect2D(floatBuffer, f6, f9, f, 2.0f, 0.62f, 0.62f, 0.66f, bl2 ? 1.0f : 0.9f);
            this.addRect2D(floatBuffer, f6, f9 + f2 - 2.0f, f, 2.0f, 0.24f, 0.24f, 0.28f, bl2 ? 1.0f : 0.9f);
            if (bl2) {
                // 选中：白外框
                this.addRect2D(floatBuffer, f6 - 2.0f, f9 - 2.0f, f + 4.0f, 2.0f, 1.0f, 1.0f, 1.0f, 0.95f);
                this.addRect2D(floatBuffer, f6 - 2.0f, f9 + f2, f + 4.0f, 2.0f, 1.0f, 1.0f, 1.0f, 0.95f);
                this.addRect2D(floatBuffer, f6 - 2.0f, f9, 2.0f, f2, 1.0f, 1.0f, 1.0f, 0.95f);
                this.addRect2D(floatBuffer, f6 + f, f9, 2.0f, f2, 1.0f, 1.0f, 1.0f, 0.95f);
            }
            // 条目文字（居中）
            String string = "  " + this.menu.label(i);
            String string2 = this.menu.value(i);
            String string3 = bl2 ? "> " + string + (string2.isEmpty() ? "" : "  " + string2) : string;
            float f10 = (float)this.W / 2.0f - Font.width(string3, 1.05f) / 2.0f;
            Font.draw(floatBuffer, f10, f9 + (f2 - 14.0f) / 2.0f, 1.05f, bl2 ? 1.0f : 0.82f, bl2 ? 1.0f : 0.82f, bl2 ? 1.0f : 0.82f, 1.0f, string3, this.W, this.H);
        }
        // 底部提示
        String string4 = bl ? "W/S UP/DOWN  -  ENTER CONFIRM" : "ESC resume  -  W/S navigate  -  ENTER confirm";
        Font.draw(floatBuffer, (float)this.W / 2.0f - Font.width(string4, 0.8f) / 2.0f, (float)this.H * 0.86f, 0.8f, 0.65f, 0.68f, 0.72f, 0.9f, string4, this.W, this.H);
    }

    private void updateRayTarget() {
        int[] nArray;
        int[] nArray2;
        int n;
        int n2;
        int n3;
        Vector3f vector3f = this.forward();
        float f = this.sim.player.x;
        float f2 = this.sim.player.y + 1.6f;
        float f3 = this.sim.player.z;
        int n4 = -1;
        int n5 = -1;
        int n6 = -1;
        int n7 = -1;
        int n8 = -1;
        int n9 = -1;
        float f4 = f;
        float f5 = f2;
        float f6 = f3;
        for (int i = 0; i < 7 && this.sim.world.inBounds(n3 = (int)Math.floor(f += vector3f.x * 0.5f), n2 = (int)Math.floor(f2 += vector3f.y * 0.5f), n = (int)Math.floor(f3 += vector3f.z * 0.5f)); ++i) {
            if (this.sim.world.getBlock(n3, n2, n) != Blocks.AIR.index) {
                n4 = n3;
                n5 = n2;
                n6 = n;
                n7 = (int)Math.floor(f4);
                n8 = (int)Math.floor(f5);
                n9 = (int)Math.floor(f6);
                break;
            }
            f4 = f;
            f5 = f2;
            f6 = f3;
        }
        if (n4 < 0) {
            nArray2 = null;
        } else {
            int[] nArray3 = new int[3];
            nArray3[0] = n4;
            nArray3[1] = n5;
            nArray2 = nArray3;
            nArray3[2] = n6;
        }
        this.rayTarget = nArray2;
        if (n4 < 0) {
            nArray = null;
        } else {
            int[] nArray4 = new int[3];
            nArray4[0] = n7;
            nArray4[1] = n8;
            nArray = nArray4;
            nArray4[2] = n9;
        }
        this.rayPlace = nArray;
    }

    private void handleClick(int n) {
        this.lastInputMs = System.currentTimeMillis();
        GameLog.log("CLICK", "btn=" + n + " focused=" + this.windowFocused + " paused=" + this.sim.world.paused);
        if (this.menu.isOpen()) {
            return;
        }
        // 告示牌编辑态：点击 = 提交铭文（吞掉，不穿透成挖/放/交互）。
        if (this.signEditing) {
            this.commitSignEdit(this.sim.world);
            return;
        }
        if (this.inventoryOpen && !this.menu.isOpen()) {       // E 批：背包 UI 优先吃掉点击（不穿透成挖/放）
            int slot = this.slotUnderCursor();
            if (slot < 0) {
                this.inventory.settleCursor();                 // 点面板外 = 把手持放回背包（守恒）
            } else if (n == 0) {
                this.inventory.clickLeft(slot);
            } else if (n == 1) {
                this.inventory.clickRight(slot);
            }
            this.sfx(Sfx.MENU_SELECT);
            return;
        }
        this.tutMark(9);
        if (n == 0) {
            this.doDig();
        } else if (n == 1) {
            // MC 语义：右键 = 交互。瞄准功能方块（门/箱/熔炉/工作台）→ 使用它；否则放置手持方块。
            this.updateRayTarget();
            if (this.rayTarget != null) {
                int tb = this.sim.world.getBlock(this.rayTarget[0], this.rayTarget[1], this.rayTarget[2]);
                // 第十一批：**潜行 + 右键 = 旋转朝向**（MC 用扳手类工具，本作用潜行，不占键位）。
                // 放在 isUsableBlock 之前 → 对"可定向且可使用的方块"（活塞/比较器/…）也优先旋转。
                if ((this.keys[340] || this.keys[344]) && Blocks.orientableOrdinal(tb) >= 0) {
                    this.rotateFacing(this.sim.world, tb);
                    return;
                }
                if (isUsableBlock(tb)) {
                    this.tryUseBlock(this.sim.world);
                    return;
                }
            }
            this.doPlace();
        }
    }

    /** 单击左键 = 对目标格推进一小段挖掘进度（MC：点一下也是"挖"，不再秒破）。 */
    private void doDig() {
        this.digTick(0.12f);
    }

    /**
     * 右键：使用/交互**瞄准的功能方块**（门/箱/熔炉/工作台）。
     *
     * <p><b>为什么走渲染层按键（右键），而不是新增一个 sim 意图</b>：本项目所有"玩家动用背包"的动作
     * （{@code CaptureSystem.tryCapture(w, inv)} / {@code RecipeBook.craft} / {@code doPlace}）
     * 都由渲染层在按键时发起，**背包作为参数传入**；仿真的 {@code Player} 并不持有背包。
     * 若把 USE 做成仿真意图，就得在仿真 side 再复制一份背包 → 双背包、必然漂移。
     * 故沿用既有纪律：功能方块的**状态**住在仿真 World（可持久化/回滚），**背包**在渲染层，
     * 二者仅在这**一次动作**里对接。
     *
     * <p><b>零漂移</b>：门/箱/熔炉只改 {@code World.meta}/{@code chestStore}/{@code furnaceStore}
     * （均不进 {@code hashState}）→ 开关门/存取物不改变仿真指纹。
     */
    /**
     * 右键「使用」的方块集合：瞄准这些方块时右键 = 使用（而非放置）。
     * <b>新增功能方块必须登记到这里</b> —— 否则右键会退化成"放置"，功能永远摸不到
     * （第五批就漏过一次：LEVER/BUTTON/BED/WIRE 加了 tryUseBlock 分支却没进这个集合）。
     */
    private static boolean isUsableBlock(int tb) {
        return tb == Blocks.DOOR.index || tb == Blocks.CHEST.index
                || tb == Blocks.FURNACE.index || tb == Blocks.WORKBENCH.index
                || tb == Blocks.LEVER.index || tb == Blocks.BUTTON.index
                || tb == Blocks.BED.index || tb == Blocks.WIRE.index
                || tb == Blocks.TRAPDOOR.index || tb == Blocks.HOPPER.index
                || tb == Blocks.SIGN.index
                || tb == Blocks.REPEATER.index || tb == Blocks.COMPARATOR.index
                || tb == Blocks.PLATE.index
                || tb == Blocks.CAMPFIRE.index || tb == Blocks.FARMLAND.index
                || tb == Blocks.DISPENSER.index || tb == Blocks.PISTON.index
                || tb == Blocks.OBSERVER.index
                || tb == Blocks.BARREL.index || tb == Blocks.LECTERN.index
                || tb == Blocks.CAULDRON.index
                || tb == Blocks.RAIL.index;      // 第二十一批：轨道（生成 / 推动矿车）
    }

    /**
     * 旋转可定向方块的朝向（潜行 + 右键）：按 {@link Facing} 的规范次序 0→1→2→3→4→5→0 循环。
     *
     * <p><b>为什么要有它</b>：第十批的朝向只能靠"放置时对准视线"决定，放歪了只能拆掉重放 ——
     * 对活塞/观察者这种"正面必须对准目标"的元件来说体验很差。这一条把朝向变成**可编辑**的。
     *
     * <p>改朝向只写 {@code blockState}（不进 hashState）→ 零漂移；但**必须 markDirty**：
     * 端口 tile 是烘进网格的，不重建就看不见朝向变了。
     */
    private void rotateFacing(core.world.World world, int tb) {
        if (this.rayTarget == null) return;
        int bx = this.rayTarget[0], by = this.rayTarget[1], bz = this.rayTarget[2];
        int f = (world.getFacing(bx, by, bz) + 1) % core.world.Facing.COUNT;
        world.setFacing(bx, by, bz, f);
        world.markDirty(bx, by, bz);
        this.toastText = Blocks.byIndex(tb).id + " FACING -> " + Facing.name(f);
        this.toastTimer = 1.1f; this.sfx(Sfx.MENU_SELECT);
        this.unlockAch("TURNED A BLOCK");
    }

    /** 坩埚满水位（{@code meta} 上限）。 */
    private static final int CAULDRON_FULL = 3;
    /** 喝一口恢复的 HP。 */
    private static final int CAULDRON_SIP_HP = 4;

    private void tryUseBlock(core.world.World world) {
        if (this.rayTarget == null) {
            this.toastText = "NOTHING TO USE"; this.toastTimer = 0.9f; this.sfx(Sfx.DENY); return;
        }
        int bx = this.rayTarget[0], by = this.rayTarget[1], bz = this.rayTarget[2];
        int b = world.getBlock(bx, by, bz);
        long cell = core.world.World.cellKey(bx, by, bz);

        // 第二十一/二十二/二十三批：轨道（含矿车）的右键 —— 上/下车 · 装/卸货 · 生成。
        if (b == Blocks.RAIL.index) {
            // 优先级：在车上 → 下车（**最高**，保证随时下得来）；
            //   手持物品 + 有车 → 装货；空手 + 车有货 → 卸货；空手 + 空车 → 上车；空格 → 生成一辆。
            if (this.sim.player.ridingCartIdx >= 0) {
                this.sim.player.ridingCartIdx = -1;
                this.toastText = "DISEMBARK"; this.toastTimer = 0.9f; this.sfx(Sfx.DENY);
                return;
            }
            core.world.Minecart cart = world.cartAt(bx, by, bz);
            if (cart != null) {
                if (this.heldItem() != null) { this.cartStore(cart); return; }   // 装货
                if (!cart.cargo.isEmpty()) { this.cartTake(cart); return; }      // 卸货
                this.sim.player.ridingCartIdx = world.carts.indexOf(cart);
                this.toastText = "BOARD"; this.toastTimer = 0.9f; this.sfx(Sfx.PLACE);
                return;
            }
            world.carts.add(new core.world.Minecart(bx + 0.5f, by, bz + 0.5f));
            this.toastText = "MINECART"; this.toastTimer = 0.9f; this.sfx(Sfx.PLACE);
            return;
        }
        if (b == Blocks.DOOR.index) {                       // 门：开 ⇄ 关
            int open = world.getMeta(bx, by, bz) == 1 ? 0 : 1;
            world.setMeta(bx, by, bz, open);
            this.toastText = open == 1 ? "DOOR OPEN" : "DOOR CLOSED";
            this.toastTimer = 0.9f; this.sfx(Sfx.UNLOCK);
            return;
        }
        if (b == Blocks.CHEST.index) {                      // 箱：空箱且玩家有物 → 全部存入；否则全部取出
            this.useContainer(world, cell);
            return;
        }
        if (b == Blocks.HOPPER.index) {                     // 漏斗：先把上方容器抽空，再当箱子用
            long aboveCell = core.world.World.cellKey(bx, by + 1, bz);
            java.util.LinkedHashMap<String, Integer> above = world.chestOf(aboveCell);
            if (above != null && !above.isEmpty()) {
                java.util.LinkedHashMap<String, Integer> mine = world.ensureChest(cell);
                java.util.List<java.util.Map.Entry<String, Integer>> es =
                        new java.util.ArrayList<java.util.Map.Entry<String, Integer>>(above.entrySet());
                for (java.util.Map.Entry<String, Integer> e : es) {
                    Integer curc = mine.get(e.getKey());
                    mine.put(e.getKey(), Integer.valueOf((curc == null ? 0 : curc.intValue()) + e.getValue().intValue()));
                }
                world.chestStore.remove(aboveCell);         // 上方容器清空（保稀疏）
                this.toastText = "HOPPER PULL"; this.toastTimer = 1.0f; this.sfx(Sfx.UNLOCK);
                return;
            }
            this.useContainer(world, cell);                 // 上方无容器 → 与箱子同
            return;
        }
        if (b == Blocks.TRAPDOOR.index) {                   // 活板门：开 ⇄ 关（开态可穿过）
            int open = world.getMeta(bx, by, bz) == 1 ? 0 : 1;
            world.setMeta(bx, by, bz, open);
            this.toastText = open == 1 ? "TRAPDOOR OPEN" : "TRAPDOOR CLOSED";
            this.toastTimer = 0.9f; this.sfx(Sfx.UNLOCK);
            return;
        }
        if (b == Blocks.SIGN.index) {                       // 告示牌：进入文本编辑态（第七批：真·输入 UI）
            this.beginSignEdit(world, bx, by, bz);
            return;
        }
        if (b == Blocks.FURNACE.index) {                    // 熔炉：取走已炼成的锭
            java.util.LinkedHashMap<String, Integer> out = world.furnaceStoreOf(cell);
            if (out == null || out.isEmpty()) {
                this.toastText = "FURNACE WORKING"; this.toastTimer = 0.9f; this.sfx(Sfx.DENY); return;
            }
            int movedTotal = 0;
            java.util.List<java.util.Map.Entry<String, Integer>> es =
                    new java.util.ArrayList<java.util.Map.Entry<String, Integer>>(out.entrySet());
            for (java.util.Map.Entry<String, Integer> e : es) {
                int left = this.inventory.add(e.getKey(), e.getValue().intValue());
                movedTotal += e.getValue().intValue() - left;
                if (left <= 0) out.remove(e.getKey()); else out.put(e.getKey(), Integer.valueOf(left));
            }
            if (out.isEmpty()) world.furnaceStore.remove(cell);
            this.toastText = movedTotal > 0 ? ("SMELT +" + movedTotal) : "INVENTORY FULL";
            this.toastTimer = 1.0f; this.sfx(movedTotal > 0 ? Sfx.UNLOCK : Sfx.DENY);
            return;
        }
        if (b == Blocks.WORKBENCH.index) {                  // 工作台：用材料合成功能方块
            this.craftAtWorkbench();
            return;
        }
        if (b == Blocks.LEVER.index) {                      // 拉杆：开 ⇄ 关（红石开关）
            int on = world.getMeta(bx, by, bz) == 1 ? 0 : 1;
            world.setMeta(bx, by, bz, on);
            this.toastText = on == 1 ? "LEVER ON" : "LEVER OFF";
            this.toastTimer = 0.9f; this.sfx(Sfx.UNLOCK);
            return;
        }
        if (b == Blocks.BUTTON.index) {                     // 按钮：脉冲（约 1 秒后自动复位）
            world.setMeta(bx, by, bz, 1);
            this.buttonPulses.add(new int[]{bx, by, bz, BUTTON_PULSE_MS});
            this.toastText = "BUTTON"; this.toastTimer = 0.7f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        if (b == Blocks.BED.index) {                        // 床：设重生点 + 夜里睡到黎明（跳过夜晚）
            this.sim.player.cpX = bx + 0.5f; this.sim.player.cpY = by + 1f; this.sim.player.cpZ = bz + 0.5f;
            this.sim.player.cpSet = true;
            // 时间在本项目是 World.tick 的纯函数 → "跳到清晨"就是把时钟前推到下一个黎明（不重放 tick）：
            // 不耗 RNG、不跑系统、不改网格 ⇒ 这一步本身不可能引入仿真漂移（门禁世界从不右键床）。
            if (core.world.DayCycle.isNight(world.tick)) {
                long target = core.world.DayCycle.nextDawn(world.tick);
                int skipped = (int) (target - world.tick);
                world.skipToTick(target);
                this.toastText = "SPAWN SET + SLEPT (" + (skipped / 20) + "s TO DAWN)";
                this.toastTimer = 1.7f; this.sfx(Sfx.UNLOCK);
            } else {
                this.toastText = "SPAWN SET"; this.toastTimer = 1.3f; this.sfx(Sfx.UNLOCK);
            }
            return;
        }
        if (b == Blocks.WIRE.index) {                       // 导线：只读**信号强度**（不是"动作"）
            core.systems.System ws = world.registry.get("wire");
            int st = (ws instanceof core.systems.WireSystem)
                    ? ((core.systems.WireSystem) ws).strengthAt(bx, by, bz) : world.getMeta(bx, by, bz);
            this.toastText = "WIRE signal " + st + "/" + core.systems.WireSystem.MAX_SIGNAL
                    + (st > 0 ? "" : "  (no signal)");
            this.toastTimer = 1.0f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        // ---- 第七批：红石逻辑三件套（同样只读状态 —— 三者的带电态由 RedstoneLogicSystem 每 tick 推导）----
        if (b == Blocks.REPEATER.index) {
            int f = world.getFacing(bx, by, bz);
            int held = world.getMeta(bx, by, bz);
            int in = core.systems.RedstoneLogicSystem.rearStrength(world, bx, by, bz, f);
            this.toastText = "REPEATER out=" + held + " in(back)=" + in + " -> " + Facing.name(f)
                    + "  (samples every " + core.systems.RedstoneLogicSystem.REPEATER_PERIOD + "t)";
            this.toastTimer = 1.3f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        if (b == Blocks.COMPARATOR.index) {
            // 第十批：右键 = 切 compare / subtract（MC 语义）。这是本作第一个"右键改仿真行为"的元件。
            boolean sub = !world.isSubtractMode(bx, by, bz);
            world.setSubtractMode(bx, by, bz, sub);
            world.markDirty(bx, by, bz);
            int f = world.getFacing(bx, by, bz);
            this.toastText = "COMPARATOR " + (sub ? "SUBTRACT (A-B)" : "COMPARE (max A,B)")
                    + "  A=" + core.systems.RedstoneLogicSystem.rearStrength(world, bx, by, bz, f)
                    + " B=" + core.systems.RedstoneLogicSystem.sideStrength(world, bx, by, bz, f)
                    + " -> " + Facing.name(f);
            this.toastTimer = 1.6f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        if (b == Blocks.PLATE.index) {
            int p = world.getMeta(bx, by, bz);
            this.toastText = p == 1 ? "PLATE PRESSED (entity on it)" : "PLATE idle (step on it)";
            this.toastTimer = 1.1f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        if (b == Blocks.CAMPFIRE.index) {
            this.toastText = "CAMPFIRE LIT (light source / standing on it burns)";
            this.toastTimer = 1.2f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        if (b == Blocks.FARMLAND.index) {
            this.toastText = "FARMLAND x" + core.systems.CropSystem.TILL_BONUS + " crop growth";
            this.toastTimer = 1.2f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        // ---- 第十二批：玩法纵深（容器 / 文本 / 水位）----
        if (b == Blocks.BARREL.index) {                     // 木桶：小容器（与箱子同一套整存整取，复用 chestStore）
            this.useContainer(world, cell);
            return;
        }
        if (b == Blocks.LECTERN.index) {                    // 讲台：复用告示牌的文本编辑（同一份 signText 状态）
            this.beginSignEdit(world, bx, by, bz);
            return;
        }
        if (b == Blocks.CAULDRON.index) {                   // 坩埚：装水 / 喝一口（水位住 meta 0..3）
            int lvl = world.getMeta(bx, by, bz);
            if (lvl <= 0) {
                boolean near = world.getBlock(bx, by + 1, bz) == Blocks.WATER.index
                        || world.getBlock(bx + 1, by, bz) == Blocks.WATER.index
                        || world.getBlock(bx - 1, by, bz) == Blocks.WATER.index
                        || world.getBlock(bx, by, bz + 1) == Blocks.WATER.index
                        || world.getBlock(bx, by, bz - 1) == Blocks.WATER.index;
                if (near) {
                    world.setMeta(bx, by, bz, CAULDRON_FULL);
                    this.toastText = "CAULDRON FILLED " + CAULDRON_FULL + "/" + CAULDRON_FULL;
                    this.toastTimer = 1.2f; this.sfx(Sfx.UNLOCK);
                } else {
                    this.toastText = "CAULDRON EMPTY  (needs water beside/above)";
                    this.toastTimer = 1.2f; this.sfx(Sfx.DENY);
                }
            } else {
                this.sim.player.hp = Math.min(this.sim.player.maxHp, this.sim.player.hp + CAULDRON_SIP_HP);
                world.setMeta(bx, by, bz, lvl - 1);
                this.toastText = "DRANK  hp+" + CAULDRON_SIP_HP + "  water " + (lvl - 1) + "/" + CAULDRON_FULL;
                this.toastTimer = 1.3f; this.sfx(Sfx.UNLOCK);
            }
            world.markDirty(bx, by, bz);                    // 水位改变顶点色 → 必须重建（否则"喝了没反应"）
            return;
        }
        if (b == Blocks.OBSERVER.index) {                   // 观察者：读"它在看什么 / 看没看出变化"
            int m = world.getMeta(bx, by, bz);
            boolean pulsing = (m & 1) != 0;
            this.toastText = "OBSERVER watches " + Facing.name(core.world.Facing.opposite(world.getFacing(bx, by, bz)))
                    + (m >>> 1 == 0 ? "  (no baseline yet)" : (pulsing ? "  PULSE" : "  steady"))
                    + "  [SNEAK + R CLICK = ROTATE]";
            this.toastTimer = 1.5f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        // ---- 第九批：红石驱动的机械（只读状态；动作由 RedstoneLogicSystem 在通电上升沿执行）----
        if (b == Blocks.DISPENSER.index) {
            java.util.LinkedHashMap<String, Integer> mag = world.chestOf(cell);
            int tot = 0;
            if (mag != null) for (Integer v : mag.values()) tot += v.intValue();
            this.toastText = "DISPENSER mag=" + tot + "  powered=" + (world.getMeta(bx, by, bz) == 1)
                    + "  out -> " + Facing.name(world.getFacing(bx, by, bz)) + "  (fires on rising edge)";
            this.toastTimer = 1.3f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        if (b == Blocks.PISTON.index) {
            this.toastText = "PISTON powered=" + (world.getMeta(bx, by, bz) == 1)
                    + "  push -> " + Facing.name(world.getFacing(bx, by, bz)) + "  (1 block per rising edge)";
            this.toastTimer = 1.3f; this.sfx(Sfx.MENU_SELECT);
            return;
        }
        this.toastText = "NOT USABLE"; this.toastTimer = 0.9f; this.sfx(Sfx.DENY);
    }

    /**
     * 工作台合成：把背包材料换成功能方块（木门/箱子/工作台 ← 木；熔炉 ← 石）。
     * 取**第一条可做**的配方（固定优先级），产物直接进背包；不足则提示材料。
     */
    // ==================================================================
    // 告示牌文本编辑（第七批，2026-09-24）：把第六批的"固定短语循环"升级为真输入 UI。
    //
    // 为什么走**按键回调 + 手写映射**而不是 glfwSetCharCallback：
    //   ① 项目此前没有任何 char 回调（Callbacks 释放路径未与它打过交道），加它等于新增一条
    //      输入通道与生命周期风险；② 手写映射（GLFW 键码 A-Z=65..90 恰好等于 ASCII 大写）
    //      对"只有 ASCII 位图字体"这个前提是**完备且可测**的；③ 编辑态必须独占输入
    //      （打字不能同时驱动移动/技能），而"在写 keys[] 之前拦截"正好只可能发生在按键回调里。
    // 可输入字符集 = 位图字体的字符集子集（见 Font 的字形表）：A-Z a-z 0-9 空格 以及 : / . - ! % + < > ( ) _ = , ?
    // ==================================================================

    /** 右键告示牌 → 进入编辑态：载入当前铭文为草稿，并**清空按键态**（否则进去那一刻仍在走路）。 */
    private void beginSignEdit(core.world.World world, int bx, int by, int bz) {
        this.signEditing = true;
        this.signEditX = bx; this.signEditY = by; this.signEditZ = bz;
        this.signEditBuf.setLength(0);
        String cur = world.signTextOf(core.world.World.cellKey(bx, by, bz));
        if (cur != null) this.signEditBuf.append(cur);
        java.util.Arrays.fill(this.keys, false);
        this.toastText = "SIGN EDIT"; this.toastTimer = 0.7f; this.sfx(Sfx.MENU_SELECT);
    }

    /** ENTER / 鼠标点击 → 提交草稿到仿真层 {@code World.signText}（空串 = 擦除铭文）。 */
    private void commitSignEdit(core.world.World world) {
        if (!this.signEditing) return;
        world.setSignText(this.signEditX, this.signEditY, this.signEditZ, this.signEditBuf.toString());
        String t = this.signEditBuf.toString();
        this.toastText = t.isEmpty() ? "SIGN CLEARED" : ("SIGN: " + t);
        this.toastTimer = 1.4f; this.sfx(Sfx.UNLOCK);
        this.signEditing = false;
        java.util.Arrays.fill(this.keys, false);
    }

    /** ESC → 丢弃草稿（不写世界）。 */
    private void cancelSignEdit() {
        if (!this.signEditing) return;
        this.signEditing = false;
        this.toastText = "SIGN EDIT CANCELLED"; this.toastTimer = 1.0f; this.sfx(Sfx.DENY);
        java.util.Arrays.fill(this.keys, false);
    }

    /** 编辑态按键处理（GLFW 键码 → 字符）。返回后调用方必须<b>吞掉</b>该键（不写 {@code keys[]}）。 */
    private void signEditKey(int n, int mods) {
        if (n == 257 || n == 335) { this.commitSignEdit(this.sim.world); return; }   // ENTER / KP_ENTER = 保存
        if (n == 256) { this.cancelSignEdit(); return; }                             // ESC = 取消
        if (n == 259 || n == 261) {                                                  // BACKSPACE / DELETE = 退格
            if (this.signEditBuf.length() > 0) this.signEditBuf.deleteCharAt(this.signEditBuf.length() - 1);
            return;
        }
        if (this.signEditBuf.length() >= SIGN_MAX) return;                           // 触顶：静默忽略（不溢出面板）
        boolean shift = (mods & 0x0001) != 0;                                        // GLFW_MOD_SHIFT
        char c = 0;
        if (n >= 65 && n <= 90) c = shift ? (char) n : (char) (n + 32);               // A-Z（键码即 ASCII 大写）
        else if (n >= 48 && n <= 57) c = (char) n;                                    // 0-9
        else if (n >= 320 && n <= 329) c = (char) ('0' + (n - 320));                  // 小键盘 0-9
        else if (n == 32) c = ' ';
        else if (n == 47) c = shift ? '?' : '/';
        else if (n == 46) c = shift ? '>' : '.';
        else if (n == 44) c = shift ? '<' : ',';
        else if (n == 45) c = shift ? '_' : '-';
        else if (n == 61) c = shift ? '+' : '=';
        else if (n == 331) c = '/';
        else if (n == 332) c = '+';
        else if (n == 333) c = '-';
        else if (n == 330) c = '.';
        if (c != 0) this.signEditBuf.append(c);                                       // 其余键吞掉
    }

    /** 容器（箱/漏斗）通用存取：空且玩家有物 → 全部存入；否则全部取出。 */
    private void useContainer(core.world.World world, long cell) {
        java.util.LinkedHashMap<String, Integer> store = world.chestOf(cell);
        boolean chestEmpty = store == null || store.isEmpty();
        boolean invEmpty = this.inventory == null || this.inventory.totalItems() == 0;
        if (chestEmpty && !invEmpty) {
            java.util.LinkedHashMap<String, Integer> dst = world.ensureChest(cell);
            for (int s = 0; s < core.content.Inventory.SLOTS; s++) {
                String iid = this.inventory.idAt(s);
                if (iid == null) continue;
                int have = this.inventory.countOf(iid);
                if (have <= 0) continue;
                int moved = this.inventory.remove(iid, have);
                Integer cur = dst.get(iid);
                dst.put(iid, Integer.valueOf((cur == null ? 0 : cur.intValue()) + moved));
            }
            this.toastText = "STORED"; this.toastTimer = 1.0f; this.sfx(Sfx.UNLOCK);
        } else if (store != null && !store.isEmpty()) {
            int movedTotal = 0;
            java.util.List<java.util.Map.Entry<String, Integer>> es =
                    new java.util.ArrayList<java.util.Map.Entry<String, Integer>>(store.entrySet());
            for (java.util.Map.Entry<String, Integer> e : es) {
                int left = this.inventory.add(e.getKey(), e.getValue().intValue());
                movedTotal += e.getValue().intValue() - left;
                if (left <= 0) store.remove(e.getKey()); else store.put(e.getKey(), Integer.valueOf(left));
            }
            if (store.isEmpty()) world.chestStore.remove(cell);     // 取空 → 清表（保稀疏）
            this.toastText = movedTotal > 0 ? ("TAKE x" + movedTotal) : "INVENTORY FULL";
            this.toastTimer = 1.0f; this.sfx(movedTotal > 0 ? Sfx.UNLOCK : Sfx.DENY);
        } else {
            this.toastText = "CONTAINER EMPTY"; this.toastTimer = 0.8f; this.sfx(Sfx.DENY);
        }
    }

    /**
     * 矿车装货（第二十三批）：把背包**全部**倒进车厢 —— 与箱子/漏斗同一套"一键全转移"语义。
     * 容器来源是实体（{@code Minecart.cargo}）而不是坐标（{@code chestStore}），因为矿车的身份是对象本身。
     */
    private void cartStore(core.world.Minecart cart) {
        if (this.inventory == null || this.inventory.totalItems() == 0) {
            this.toastText = "NOTHING TO LOAD"; this.toastTimer = 0.8f; this.sfx(Sfx.DENY); return;
        }
        int moved = 0;
        for (int s = 0; s < core.content.Inventory.SLOTS; s++) {
            String iid = this.inventory.idAt(s);
            if (iid == null) continue;
            int have = this.inventory.countOf(iid);
            if (have <= 0) continue;
            int m = this.inventory.remove(iid, have);
            Integer cur = cart.cargo.get(iid);
            cart.cargo.put(iid, Integer.valueOf((cur == null ? 0 : cur.intValue()) + m));
            moved += m;
        }
        this.toastText = moved > 0 ? ("LOAD x" + moved) : "NOTHING TO LOAD";
        this.toastTimer = 0.9f; this.sfx(Sfx.PLACE);
    }

    /** 矿车卸货（第二十三批）：把车厢**全部**倒回背包；装不下的留在车厢里（不凭空消失）。 */
    private void cartTake(core.world.Minecart cart) {
        if (this.inventory == null) { this.toastText = "NO INVENTORY"; this.toastTimer = 0.8f; return; }
        int moved = 0;
        java.util.List<java.util.Map.Entry<String, Integer>> es =
                new java.util.ArrayList<java.util.Map.Entry<String, Integer>>(cart.cargo.entrySet());
        for (java.util.Map.Entry<String, Integer> e : es) {
            int left = this.inventory.add(e.getKey(), e.getValue().intValue());
            moved += e.getValue().intValue() - left;
            if (left <= 0) cart.cargo.remove(e.getKey()); else cart.cargo.put(e.getKey(), Integer.valueOf(left));
        }
        this.toastText = moved > 0 ? ("UNLOAD x" + moved) : "INVENTORY FULL";
        this.toastTimer = 0.9f; this.sfx(moved > 0 ? Sfx.UNLOCK : Sfx.DENY);
    }

    private void craftAtWorkbench() {
        if (this.inventory == null) { this.toastText = "NO INVENTORY"; this.toastTimer = 0.8f; return; }
        // 第七批：扩表 —— 红石三件套（石 + 铜锭）+ 篝火（木）+ 耕地（泥）。
        // 取**第一条可做**的配方（固定优先级，与既有纪律一致）。
        String[] outItem = { "door", "chest", "workbench", "furnace",
                             "repeater", "comparator", "plate", "campfire", "farmland",
                             "dispenser", "piston" };
        String[] matItem = { "wood", "wood", "wood", "stone",
                             "stone", "stone", "stone", "wood", "dirt",
                             "stone", "iron_bar" };
        int[]    matNeed = { 2,        4,      3,        4,
                             3,        3,      2,        3,      2,
                             4,        2 };
        // 电路/机械额外需要 1 个铜锭（体现"电路要用金属"）——用第二个材料位表达，缺失则跳过该条。
        String[] mat2Item = { null, null, null, null, "copper_bar", "copper_bar", null, null, null,
                              "copper_bar", null };
        for (int i = 0; i < outItem.length; i++) {
            if (this.inventory.countOf(matItem[i]) < matNeed[i]) continue;
            if (mat2Item[i] != null && this.inventory.countOf(mat2Item[i]) < 1) continue;
            this.inventory.remove(matItem[i], matNeed[i]);
            if (mat2Item[i] != null) this.inventory.remove(mat2Item[i], 1);
            this.inventory.add(outItem[i], 1);
            this.toastText = "CRAFTED " + outItem[i];
            this.toastTimer = 1.3f; this.sfx(Sfx.UNLOCK);
            this.unlockAch("FIRST CRAFT");
            return;
        }
        this.toastText = "NEED WOOD/STONE/DIRT/COPPER/IRON"; this.toastTimer = 1.1f; this.sfx(Sfx.DENY);
    }

    /**
     * MC 手感（路线图 §1 P0 #1）：**按住左键渐进破坏**。
     *
     * <p>目标格不变则累计进度；换目标 / 松手超时（{@link #DIG_HOLD}）→ 进度清零；
     * 进度达该方块硬度 → 破坏（走 {@link #breakTarget}）。
     * 进度状态纯在输入/渲染层（不进 sim、不进 hashState）；只有破坏那一刻才写世界。
     */
    private void digTick(float dt) {
        this.updateRayTarget();
        if (this.rayTarget == null) { this.digReset(); return; }
        int bx = this.rayTarget[0], by = this.rayTarget[1], bz = this.rayTarget[2];
        int mat = this.sim.world.getBlock(bx, by, bz);
        if (mat == Blocks.AIR.index) { this.digReset(); return; }
        if (bx != this.digBx || by != this.digBy || bz != this.digBz) {
            this.digBx = bx; this.digBy = by; this.digBz = bz; this.digProg = 0f;
        }
        this.digIdle = 0f;
        this.digProg += dt * this.digSpeed();
        this.digChipTimer -= dt;
        if (this.digChipTimer <= 0.0f && this.content != null) {   // 画面细腻度：挖的时候持续溅碎屑（按材料换粒子）
            this.digChipTimer = 0.18f;
            com.google.gson.JsonObject pdef = this.digParticleDef(mat);
            if (pdef != null) this.particles.spawn(pdef, bx + 0.5f, by + 0.5f, bz + 0.5f, 3);
        }
        if (this.digProg >= digHardness(mat)) {
            this.breakTarget(bx, by, bz, mat);
            this.digReset();
        }
    }

    /** 进度清零（换目标 / 松手超时）。 */
    private void digReset() {
        this.digBx = Integer.MIN_VALUE;
        this.digProg = 0f;
    }

    /**
     * 方块挖掘硬度（秒，空手基准）—— <b>查材料规格表</b>
     * （{@code assets/content/materials/<BLOCK_ID>.json}）。
     *
     * <p><b>2026-09-18 改造</b>：此前这里是一串 {@code id.contains("STONE")} 硬编码链
     * （见 {@code docs/NOITA_LESSONS.md} §2.1 的现场证据）—— 加一种材料就要回来改分支，
     * 忘了就静默掉进兜底 {@code 0.70f}。现在材料的物理量集中在数据表里，改数值不用改代码。
     *
     * <p><b>零行为变化</b>：{@code ContentTest.MATERIAL} 逐方块断言「表里的值与改造前
     * 字符串链的结果完全一致」（25/25），所以这次迁移对玩法是逐帧等价的。
     */
    private float digHardness(int mat) {
        if (mat < 0 || mat >= core.world.Blocks.count()) return 0.02f;   // 越界/非方块：与原 b==null 分支一致
        return this.matBook.hardness(mat);
    }

    /**
     * 挖掘某材料时该溅什么粒子 —— 同样查材料规格表（土溅灰、叶飞叶、冰溅晶、金溅火星）。
     * 无内容/无规格 → 兜底 {@code dust}（= 历史行为）。纯渲染层，不进仿真。
     */
    private com.google.gson.JsonObject digParticleDef(int mat) {
        if (this.content == null) return null;
        com.google.gson.JsonObject def = this.content.get("particles", this.matBook.digParticle(mat));
        if (def == null) def = this.content.get("particles", core.content.MaterialDef.DEFAULT_DIG_PARTICLE);
        return def;
    }

    /** 每秒挖掘推进倍率：持工具（镐类）挖得更快（MC pickaxe 语义）。 */
    private float digSpeed() {
        core.content.ItemDef held = this.heldItem();
        return (held != null && held.tool) ? 2.2f : 1.0f;
    }

    /** 真正破坏一格：拒 BEDROCK → 音效 → 掉落物 → 工具耐久。 */
    private void breakTarget(int bx, int by, int bz, int mat) {
        // 不可破坏方块（世界底层外壳）在这里被拦下：挖得动才继续 —— 否则会一路掉出世界。
        // 注意必须判返回值：此前无条件继续，导致「明明挖不动却照播音效、照样 +1 物品」。
        if (!this.sim.world.editBlock(bx, by, bz, Blocks.AIR.index)) {
            this.toastText = "UNBREAKABLE";
            this.toastTimer = 0.9f;
            this.sfx(Sfx.DENY);
            return;
        }
        this.sfx(Sfx.DIG);
        if (this.content != null) {                                          // 画面细腻度：破坏瞬间爆一簇碎屑（按材料换粒子）
            com.google.gson.JsonObject pdef = this.digParticleDef(mat);
            if (pdef != null) this.particles.spawn(pdef, bx + 0.5f, by + 0.5f, bz + 0.5f, 8);
        }
        this.unlockAch("FIRST DIG");
        // E 批：挖掉 → 物品入包（有定义才拾取；无定义保持旧行为，只提示一次）
        if (this.inventory != null && this.content != null) {
            core.world.Blocks.Block b = core.world.Blocks.byIndex(mat);
            if (b != null) {
                // 反向索引：由内容的 item.block 决定"这个方块掉什么物品"，不再靠 id.toLowerCase() 猜。
                // 修的就是"挖 COAL_ORE 掉 coal_ore、而配方要 coal"这种**静默失配**。
                String itemId = this.content.itemForBlock(b.id);
                if (itemId == null) {
                    if (!this.pickupWarned) {
                        this.pickupWarned = true;
                        GameLog.log("INV", "no item def for block " + b.id + " (dig pickup skipped)");
                    }
                } else {
                    // MC 手感（路线图 §1 P0 #2）：不再直接入包 —— 变成掉在地上的小方块，走近自动吸入。
                    // （入包发生在 collectDrops()，与箱子同分工：sim 只存掉落数据，背包转移在渲染/输入层。）
                    this.sim.world.spawnDrop(bx, by, bz, itemId, 1);
                }
            }
        }
        // 工具耐久：手持工具挖一格消耗 1 点（空手挖掘不设门槛 —— 引入耐久但不引入进度门槛）
        if (this.inventory != null) {
            core.content.ItemDef held = this.heldItem();
            if (held != null && held.tool && this.inventory.damage(this.heldSlot, 1)) {
                this.toastText = held.name + " BROKE";
                this.toastTimer = 1.8f;
                this.sfx(Sfx.DENY);
            }
        }
    }

    private void doPlace() {
        this.updateRayTarget();
        if (this.rayTarget == null) {
            return;
        }
        // E 批：放置 = 消耗手持槽的物品（方块索引由物品定义派生；空手/工具不可放置）
        int blockIdx = this.heldBlockIndex();
        if (blockIdx < 0 || this.inventory == null || this.inventory.countAt(this.heldSlot) <= 0) {
            this.sfx(Sfx.DENY);
            return;
        }
        int n = this.rayPlace[0];
        int n2 = this.rayPlace[1];
        int n3 = this.rayPlace[2];
        if (!this.sim.world.inBounds(n, n2, n3) || this.sim.world.getBlock(n, n2, n3) != Blocks.AIR.index) {
            return;
        }
        if (this.blockTouchesPlayer(n, n2, n3)) {
            this.sfx(Sfx.DENY);
            return;
        }
        // 第十二批（种植回路）+ 第十五批（邻域条件）：带种植要求的物品只在**合适的地方**能放下。
        //  ⚠️ 缺省（无 sowOn / 不邻水 / 光照 0）⇒ 无要求 ⇒ 既有"花/蕨/蘑菇随处可摆"的玩法逐字不变。
        //  判据是 ItemDef 上的**纯函数**（canSow(SowEnv)）；渲染层在这里只做一件事：
        //  把世界**采样**成 SowEnv 的 3 个值（基质 / 邻水格数 / 见天程度）。
        //  ⚠️ 采样与判据必须分开：判据纯 → 门禁能穷举；采样读世界 → 只有跑游戏才能验（诚实边界）。
        core.content.ItemDef sowing = this.heldItem();
        if (sowing != null && sowing.hasSowRule()) {
            core.world.World w = this.sim.world;
            String below = w.inBounds(n, n2 - 1, n3) ? Blocks.byIndex(w.getBlock(n, n2 - 1, n3)).id : null;
            int water = 0;                                       // 水平四邻中的水格数
            for (int d = 0; d < 4; d++) {
                int wx = n + (d == 0 ? 1 : (d == 1 ? -1 : 0));
                int wz = n3 + (d == 2 ? 1 : (d == 3 ? -1 : 0));
                if (w.inBounds(wx, n2, wz) && w.getBlock(wx, n2, wz) == Blocks.WATER.index) water++;
            }
            int sky = 0;                                         // 见天程度：向上连续空气格数（封顶 15）
            while (sky < 15 && w.inBounds(n, n2 + 1 + sky, n3)
                    && w.getBlock(n, n2 + 1 + sky, n3) == Blocks.AIR.index) sky++;
            if (!sowing.canSow(new core.content.ItemDef.SowEnv(below, water, sky))) {
                this.toastText = Blocks.byIndex(blockIdx).id + " NEEDS " + sowing.sowNeed();
                this.toastTimer = 1.3f; this.sfx(Sfx.DENY);
                return;
            }
        }
        this.sim.world.editBlock(n, n2, n3, blockIdx);
        // 第十批：可定向方块记下朝向 = 玩家视线主轴（抬头=+Y / 平视=水平推出去）。
        // ⚠️ 必须在 editBlock **之后**写：setBlock 会清掉该格的功能状态（含朝向），先写会被抹掉。
        // 再显式 markDirty 一次，确保这次朝向写入一定进本次网格重建（否则可能落在脏标记之后）。
        // 第十七批：台阶也需要朝向（决定"上半格往哪边偏"），但它**不**属于 ORIENTABLE
        // （那份名单的语义是"哪一面画端口图案"）⇒ 判据写成"可定向 **或** 是台阶"。
        // 台阶只认 4 个水平方向：视线俯仰会产生 ±Y，必须水平化，否则放下去形状随机。
        // 判据收敛在 `Blocks.needsFacing`（可定向 **或** 形状/贴图随朝向变）—— 别在这里堆第三个 `||`。
        // 台阶与轨道只认 4 个水平方向（视线俯仰会产生 ±Y；不水平化则形状/走向随机）。
        if (Blocks.needsFacing(blockIdx)) {
            Vector3f fw = this.forward();
            int dir = Facing.fromLook(fw.x, fw.y, fw.z);
            if (Blocks.isStairs(blockIdx) || Blocks.isRail(blockIdx)) dir = Facing.horizontal(dir);
            this.sim.world.setFacing(n, n2, n3, dir);
            this.sim.world.markDirty(n, n2, n3);
        }
        this.inventory.consumeOne(this.heldSlot);
        this.sfx(Sfx.PLACE);
    }

    // ---- E 批：背包 / hotbar 几何与物品派生（渲染与命中测试共用同一组坐标，防止双份漂移）----

    /** 当前手持槽的物品定义（空槽 / 未初始化返回 null）。 */
    private core.content.ItemDef heldItem() {
        if (this.inventory == null || this.content == null) return null;
        String it = this.inventory.idAt(this.heldSlot);
        return it == null ? null : this.content.item(it);
    }

    /** 当前手持物品可放置的方块索引（-1 = 不可放置：空手 / 工具）。 */
    private int heldBlockIndex() {
        core.content.ItemDef d = this.heldItem();
        if (d == null || d.block == null || !Blocks.has(d.block)) return -1;
        return Blocks.index(d.block);
    }

    private float hotbarCell() { return 44.0f; }
    private float hotbarX() {
        float cell = this.hotbarCell();
        return ((float)this.W - (9.0f * cell + 8.0f)) / 2.0f;
    }
    private float hotbarY() { return (float)this.H - 56.0f; }

    private float bpCell() { return 36.0f; }
    private float bpGap() { return 4.0f; }
    private float bpX() {
        float c = this.bpCell(), g = this.bpGap();
        return ((float)this.W - (9.0f * c + 8.0f * g)) / 2.0f;
    }
    private float bpH() {
        float c = this.bpCell(), g = this.bpGap();
        return 3.0f * c + 2.0f * g + 30.0f;
    }
    private float bpY() { return this.hotbarY() - 26.0f - this.bpH(); }

    /** 光标（换算到 HUD 坐标）下的格子：0-8 hotbar / 9-35 背包 / -1 面板外。 */
    private int slotUnderCursor() {
        float sx = (this.winW > 0 && this.winW != this.W) ? (float)this.W / (float)this.winW : 1.0f;
        float sy = (this.winH > 0 && this.winH != this.H) ? (float)this.H / (float)this.winH : 1.0f;
        float mx = (float)this.lastMx * sx;
        float my = (float)this.lastMy * sy;
        float cell = this.hotbarCell();
        float hbX = this.hotbarX(), hbY = this.hotbarY();
        if (mx >= hbX && mx < hbX + 9.0f * cell + 8.0f && my >= hbY && my < hbY + cell) {
            int i = (int)((mx - hbX - 4.0f) / cell);
            if (i >= 0 && i < 9) return i;
        }
        float c = this.bpCell(), g = this.bpGap();
        float bX = this.bpX(), bY = this.bpY();
        float bW = 9.0f * c + 8.0f * g;
        if (mx >= bX && mx < bX + bW && my >= bY && my < bY + this.bpH()) {
            int col = (int)((mx - bX) / (c + g));
            int row = (int)((my - bY - 28.0f) / (c + g));
            if (col >= 0 && col < 9 && row >= 0 && row < 3) {
                float cx = bX + (float)col * (c + g);
                float cy = bY + 28.0f + (float)row * (c + g);
                if (mx >= cx && mx < cx + c && my >= cy && my < cy + c) return 9 + row * 9 + col;
            }
        }
        return -1;
    }

    /** 物品图标（色块 + 高光边）+ 数量角标 + 耐久条（低耐久变红）。 */
    private void drawItemIcon(FloatBuffer buf, float x, float y, float size, core.content.ItemDef d, int count, int dur) {
        int c = (d != null && d.color != 0) ? d.color : 0x9A9AA2;
        float cr = (float)((c >> 16) & 255) / 255.0f;
        float cg = (float)((c >> 8) & 255) / 255.0f;
        float cb = (float)(c & 255) / 255.0f;
        this.addRect2D(buf, x, y, size, size, cr, cg, cb, 0.97f);
        this.addRect2D(buf, x, y, size, 4.0f, Math.min(1f, cr * 1.3f), Math.min(1f, cg * 1.3f), Math.min(1f, cb * 1.3f), 0.97f);
        if (count > 1) {
            HudText.ascii(buf, x + size - 10.0f, y + size - 10.0f, 0.62f, 1f, 1f, 1f, 1f,
                    String.valueOf(count), this.W, this.H);
        }
        if (d != null && d.durability > 0) {
            float ratio = Math.max(0f, Math.min(1f, (float)dur / (float)d.durability));
            this.addRect2D(buf, x, y + size + 2.0f, size, 3.0f, 0.05f, 0.05f, 0.06f, 0.9f);
            this.addRect2D(buf, x, y + size + 2.0f, size * ratio, 3.0f,
                    ratio > 0.35f ? 0.3f : 0.9f, ratio > 0.35f ? 0.85f : 0.2f, 0.15f, 0.95f);
        }
    }

    /** E 批：背包面板（27 格 MC 风 + 光标手持跟随鼠标）。 */
    private void drawInventoryPanel() {
        float c = this.bpCell(), g = this.bpGap();
        float bX = this.bpX(), bY = this.bpY();
        float bW = 9.0f * c + 8.0f * g;
        float bH = this.bpH();
        this.addRect2D(this.hudBuf, 0, 0, this.W, this.H, 0f, 0f, 0f, 0.25f);          // 全屏轻暗化（聚焦面板）
        if (this.uiArtAmount > 0f) {
            // 自研艺术 UI（-Dbw.uiart>0）：整块面板换成程序化九宫格贴图（圆角 + 恒定描边 + 纸纹 + 单侧受光）。
            // 默认 uiArtAmount=0 → 走下面原来的三个纯色矩形，逐字节不变。
            this.addArtPanel(this.hudBuf, bX - 6.0f, bY - 6.0f, bW + 12.0f, bH + 12.0f,
                    0.86f, 0.86f, 0.90f, 0.97f);
        } else {
            this.addRect2D(this.hudBuf, bX - 6.0f, bY - 6.0f, bW + 12.0f, bH + 12.0f, 0.14f, 0.14f, 0.17f, 0.94f);
            this.addRect2D(this.hudBuf, bX - 6.0f, bY - 6.0f, bW + 12.0f, 2.0f, 0.42f, 0.42f, 0.47f, 0.95f);
            this.addRect2D(this.hudBuf, bX - 6.0f, bY + bH + 4.0f, bW + 12.0f, 2.0f, 0.06f, 0.06f, 0.08f, 0.95f);
        }
        HudText.ascii(this.hudBuf, bX + 2.0f, bY + 8.0f, 0.8f, 0.9f, 0.9f, 0.95f, 1.0f,
                "INVENTORY  (E to close  -  click to move)", this.W, this.H);
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int slot = 9 + row * 9 + col;
                float x = bX + (float)col * (c + g);
                float y = bY + 28.0f + (float)row * (c + g);
                this.addRect2D(this.hudBuf, x, y, c, c, 0.07f, 0.07f, 0.09f, 0.85f);
                if (this.uiArtAmount > 0f) {
                    // 自研艺术 UI：槽位用图集中央格贴图（含圆角内阴影 + 纸纹），下有原纯色槽打底。
                    this.addArtQuad(this.hudBuf, TextureAtlas.UI_CORNER_TL, x, y, c, c, 1.0f, 1.0f, 1.06f, 0.92f);
                }
                this.addRect2D(this.hudBuf, x, y, c, 2.0f, 0.04f, 0.04f, 0.05f, 0.9f);
                this.addRect2D(this.hudBuf, x, y + c - 2.0f, c, 2.0f, 0.3f, 0.3f, 0.34f, 0.9f);
                String it = this.inventory.idAt(slot);
                if (it != null) {
                    this.drawItemIcon(this.hudBuf, x + 4.0f, y + 4.0f, c - 8.0f,
                            this.content.item(it), this.inventory.countAt(slot), this.inventory.durAt(slot));
                }
            }
        }
        if (this.inventory.cursorId != null) {
            float sx = (this.winW > 0 && this.winW != this.W) ? (float)this.W / (float)this.winW : 1.0f;
            float sy = (this.winH > 0 && this.winH != this.H) ? (float)this.H / (float)this.winH : 1.0f;
            float mx = (float)this.lastMx * sx - c / 2.0f;
            float my = (float)this.lastMy * sy - c / 2.0f;
            this.drawItemIcon(this.hudBuf, mx, my, c - 6.0f,
                    this.content.item(this.inventory.cursorId), this.inventory.cursorCount, this.inventory.cursorDur);
        }
    }

    private boolean blockTouchesPlayer(int n, int n2, int n3) {
        float f = this.sim.player.x;
        float f2 = this.sim.player.y;
        float f3 = this.sim.player.z;
        float f4 = 0.3f;
        return (float)n + 1.0f > f - f4 && (float)n < f + f4 && (float)n2 + 1.0f > f2 && (float)n2 < f2 + 1.8f && (float)n3 + 1.0f > f3 - f4 && (float)n3 < f3 + f4;
    }

    /** 四方向受困判定：目标列脚下+胸口两层都 solid（2 格墙，跳不过去）。 */
    /** 活跃判定：窗口聚焦 或 最近 5 秒内有任何输入（OrayIdd 等环境焦点每 0.1s 被抢走，但键盘仍在到达——按实际输入判定）。 */
    /**
     * 记录「本 tick 之前」的野兽位置，供渲染插值（必须在 world.tick() 之前调用）。
     *
     * <p>为什么放在渲染层而不是给 {@code Beast} 加 prev 字段：这是纯粹的显示问题，
     * 放到仿真对象上会进 StateCodec 快照/指纹（NPC 的 prev 就是这样被写进快照的），
     * 无谓地扩大状态面。渲染层自己记一份，零漂移风险。
     */
    private void sampleBeastPrev() {
        World w = this.sim.world;
        if (w == null) return;
        int n = w.beasts.size();
        if (this.beastPrevPos == null || this.beastPrevPos.length < n * 3 || this.beastPrevRef == null
                || this.beastPrevRef.length < n) {
            int cap = Math.max(16, n + 8);
            this.beastPrevPos = new float[cap * 3];
            this.beastPrevRef = new Beast[cap];
        }
        this.beastPrevCount = n;
        for (int i = 0; i < n; i++) {
            Beast b = w.beasts.get(i);
            this.beastPrevRef[i] = b;
            this.beastPrevPos[i * 3] = b.x;
            this.beastPrevPos[i * 3 + 1] = b.y;
            this.beastPrevPos[i * 3 + 2] = b.z;
        }
    }

    /**
     * 仿真时间倍率（GAME SPEED）—— **全项目唯一一处定义**。
     *
     * <p>主循环积分、动画钟、玩家物理<b>必须</b>都乘它：否则会出现“世界跑 2x、玩家跑 1x”，
     * 也就是 2026-09-16 用户报的“其他角色比我的动作快”（村民位置由仿真推进、玩家位置由渲染帧推进）。
     * 联机会话固定 1x —— 锁步不允许客户端自行缩放时间。
     */
    private float timeScale() { return this.netSession == null ? this.menu.gameSpeed() : 1.0f; }

    private boolean isActiveNow() {
        if (this.qaFrozen) return true;   // QA：无头截图不模拟失焦遮罩/暂停（它们依赖真实时钟 → 不可复现）
        long now = System.currentTimeMillis();
        if (this.windowFocused) return true;
        if (now - this.lastInputMs < 5000L) return true;
        // QA 2026-09-15：本机环境焦点会被反复抢走（0.1s 级）-> 再加一道"刚丢焦点"的 3 秒去抖，
        // 避免焦点抖动把世界反复暂停/恢复（那会让昼夜看起来在乱跳）。
        if (now - this.lastFocusLossMs < 3000L) return true;
        return false;
    }

    private boolean wall2At(World world, float fx, float fy, float fz) {
        int bx = (int)Math.floor(fx), bz = (int)Math.floor(fz), by = (int)Math.floor(fy);
        return world.inBounds(bx, by, bz) && world.inBounds(bx, by + 1, bz)
            && Blocks.byIndex(world.getBlock(bx, by, bz)).solid
            && Blocks.byIndex(world.getBlock(bx, by + 1, bz)).solid;
    }

    /** F7 困境重生：把玩家送回开局选址的「安全出生点」并脚下铺 3×3 石台。
     *  旧实现只在头顶搭台，若已掉出世界/悬空则平台也搭在虚空里，救不回来。
     *  安全出生点持久化在 World.safeSpawnX/Z，读档后仍可用。写 mat 仅 Game 层按键触发，门禁世界无 F7 零漂移。 */
    private void emergencyRespawn() {
        Player player = this.sim.player;
        World world = this.sim.world;
        int sx = (world.safeSpawnX >= 0) ? world.safeSpawnX : world.SX / 2;
        int sz = (world.safeSpawnZ >= 0) ? world.safeSpawnZ : world.SZ / 2;
        float sy = Player.spawnY(world, sx, sz);
        int iy = (int)Math.floor(sy);
        // 先清出脚部/头部空间，再铺脚下 3×3 石台
        for (int i = -1; i <= 1; ++i) {
            for (int j = -1; j <= 1; ++j) {
                int nx = sx + i, nz = sz + j;
                if (!world.inBounds(nx, 0, nz)) continue;
                for (int y = iy; y <= iy + 2; ++y) {
                    if (world.inBounds(nx, y, nz)) world.editBlock(nx, y, nz, Blocks.AIR.index);
                }
                int gy = iy - 1;
                if (world.inBounds(nx, gy, nz)) world.editBlock(nx, gy, nz, Blocks.STONE.index);
            }
        }
        player.x = sx + 0.5f;
        player.z = sz + 0.5f;
        player.y = sy;
        player.vy = 0.0f;
        this.locked = null;
        this.smoothMx = 0.0f;
        this.smoothMz = 0.0f;
        this.talkToast = "RESCUED - returned to safe spawn";
        this.talkToastTimer = 2.2f;
        this.sfx(Sfx.MENU_SELECT);
        this.quickSave();
    }

    private void quickSave() {
        try {
            File file = new File("save");
            if (!file.exists()) {
                file.mkdirs();
            }
            FileOutputStream fileOutputStream = new FileOutputStream(new File(file, "world.sav"));
            this.sim.world.save(fileOutputStream);
            fileOutputStream.close();
            this.talkToast = "SAVED";
            this.talkToastTimer = 1.6f;
        }
        catch (Exception exception) {
            this.talkToast = "SAVE FAILED: " + exception.getMessage();
            this.talkToastTimer = 2.5f;
        }
    }

    private void quickLoad() {
        try {
            File file = new File("save", "world.sav");
            if (!file.exists()) {
                this.talkToast = "NO SAVE FILE";
                this.talkToastTimer = 1.6f;
                return;
            }
            FileInputStream fileInputStream = new FileInputStream(file);
            World world = World.load(fileInputStream);
            fileInputStream.close();
            this.sim = new Simulation(world);
            this.attachContentToWorld();           // P1：读档换了世界对象，内容资产要重挂
            for (int i = 0; i < this.chunkX; ++i) {
                for (int j = 0; j < this.chunkZ; ++j) {
                    this.chunks[i][j].rebuild(this.sim.world);
                }
            }
            this.talkToast = "LOADED";
            this.talkToastTimer = 1.6f;
        }
        catch (Exception exception) {
            this.talkToast = "LOAD FAILED: " + exception.getMessage();
            this.talkToastTimer = 2.5f;
        }
    }

    private Vector3f forward() {
        float f = (float)Math.cos(Math.toRadians(this.pitch));
        return new Vector3f(f * (float)Math.sin(Math.toRadians(this.yaw)), (float)Math.sin(Math.toRadians(this.pitch)), -f * (float)Math.cos(Math.toRadians(this.yaw)));
    }

    private static float wrapDeg(float f) {
        while (f > 180.0f) {
            f -= 360.0f;
        }
        while (f < -180.0f) {
            f += 360.0f;
        }
        return f;
    }

    private static void lineEdge(FloatBuffer floatBuffer, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8, float f9) {
        floatBuffer.put(f).put(f2).put(f3).put(f7).put(f8).put(f9);
        floatBuffer.put(f4).put(f5).put(f6).put(f7).put(f8).put(f9);
    }

    private static void hudQuad(FloatBuffer floatBuffer, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8, float f9, float f10) {
        int[][] nArrayArray;
        float f11 = f / f9 * 2.0f - 1.0f;
        float f12 = (f + f3) / f9 * 2.0f - 1.0f;
        float f13 = 1.0f - f2 / f10 * 2.0f;
        float f14 = 1.0f - (f2 + f4) / f10 * 2.0f;
        float[] fArray = new float[]{f11, f12, f12, f11};
        float[] fArray2 = new float[]{f13, f13, f14, f14};
        int[][] nArrayArray2 = nArrayArray = new int[][]{{0, 1, 2}, {0, 2, 3}};
        int n = nArrayArray2.length;
        for (int i = 0; i < n; ++i) {
            int[] nArray;
            for (int n2 : nArray = nArrayArray2[i]) {
                floatBuffer.put(fArray[n2]).put(fArray2[n2]).put(0.0f).put(f5).put(f6).put(f7).put(f8);
            }
        }
    }

    private void initShader() {
        String string = "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec3 aCol;uniform mat4 uVP;out vec3 vCol;void main(){vCol=aCol;gl_Position=uVP*vec4(aPos,1);}";
        String string2 = "#version 330 core\nin vec3 vCol;out vec4 fc;void main(){fc=vec4(vCol,1);}";
        this.shader = Game.makeProgram(string, string2);
        this.uVP = GL33.glGetUniformLocation((int)this.shader, (CharSequence)"uVP");
        int n = GL33.glGetError();
        if (this.uVP == -1) {
            this.logPhase("WARN: glGetUniformLocation(uVP) == -1  glErr=" + Integer.toHexString(n));
        } else {
            this.logPhase("uVP located=" + this.uVP + "  glErrAfterLink=" + Integer.toHexString(n));
        }
    }

    /**
     * 安全网（2026-09-16）：着色器编译/链接失败时**不再抛异常崩游戏**，而是打印错误日志并降级到最简着色器
     * （只做 uVP 变换 + 顶点色），画面变素但游戏照跑、日志里能读到真实的 GLSL 错误。
     *
     * <p>为什么需要：开发环境无 GPU/显示器，GLSL 编译错误**无法本地发现**；旧行为（throw）会把用户的游戏
     * 直接搞崩，且没有任何可读线索。有了这层网，进阶 shader 工作即使写错也只是"画面降级 + 一行日志"。
     */
    private static final String FALLBACK_VS =
            "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec3 aCol;uniform mat4 uVP;out vec3 vCol;void main(){vCol=aCol;gl_Position=uVP*vec4(aPos,1);}";
    private static final String FALLBACK_FS =
            "#version 330 core\nin vec3 vCol;out vec4 fc;void main(){fc=vec4(vCol,1);}";

    private static int makeProgram(String string, String string2) {
        try {
            return makeProgramStrict(string, string2);
        } catch (RuntimeException e) {
            System.err.println("[SHADER-FALLBACK] 着色器编译/链接失败，降级到最简着色器（画面变素但不崩）：" + e.getMessage());
        }
        return makeProgramStrict(FALLBACK_VS, FALLBACK_FS);
    }

    private static int makeProgramStrict(String string, String string2) {
        int n = GL33.glCreateShader((int)35633);
        GL33.glShaderSource((int)n, (CharSequence)string);
        GL33.glCompileShader((int)n);
        if (GL33.glGetShaderi((int)n, (int)35713) == 0) {
            throw new RuntimeException("VS: " + GL33.glGetShaderInfoLog((int)n));
        }
        int n2 = GL33.glCreateShader((int)35632);
        GL33.glShaderSource((int)n2, (CharSequence)string2);
        GL33.glCompileShader((int)n2);
        if (GL33.glGetShaderi((int)n2, (int)35713) == 0) {
            throw new RuntimeException("FS: " + GL33.glGetShaderInfoLog((int)n2));
        }
        int n3 = GL33.glCreateProgram();
        GL33.glAttachShader((int)n3, (int)n);
        GL33.glAttachShader((int)n3, (int)n2);
        GL33.glLinkProgram((int)n3);
        if (GL33.glGetProgrami((int)n3, (int)35714) == 0) {
            throw new RuntimeException("LINK: " + GL33.glGetProgramInfoLog((int)n3));
        }
        GL33.glDeleteShader((int)n);
        GL33.glDeleteShader((int)n2);
        return n3;
    }

    private int[] makeDynamicVAO() {
        int n = GL33.glGenVertexArrays();
        GL33.glBindVertexArray((int)n);
        int n2 = GL33.glGenBuffers();
        GL33.glBindBuffer((int)34962, (int)n2);
        GL33.glBufferData((int)34962, (long)1600000L, (int)35048);
        GL33.glVertexAttribPointer((int)0, (int)3, (int)5126, (boolean)false, (int)24, (long)0L);
        GL33.glEnableVertexAttribArray((int)0);
        GL33.glVertexAttribPointer((int)1, (int)3, (int)5126, (boolean)false, (int)24, (long)12L);
        GL33.glEnableVertexAttribArray((int)1);
        GL33.glBindVertexArray((int)0);
        return new int[]{n, n2};
    }

    /**
     * 读取自研艺术 UI 层强度（{@code -Dbw.uiart=<0..1>}）。默认 0 → 严格等价旧纯色路径。
     * <p>非法/缺失/越界一律回落 0（fail-safe 到"无艺术层"，绝不会因为写错参数而改变默认画面）。</p>
     */
    private static float readUiArtAmount() {
        String s = System.getProperty("bw.uiart");
        if (s == null) return 0f;
        try {
            float v = Float.parseFloat(s.trim());
            if (Float.isNaN(v) || v <= 0f) return 0f;
            return Math.min(1f, v);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private void initHudShader() {
        // VS：额外把裁剪空间位置反算成屏幕 UV（vSuv ∈ [0,1]），供 FS 的艺术材质层使用。
        // 为什么从 gl_Position 反算而不是直接传 aPos：HUD 有两种投影 —— 真实 uVP（world 空间下的
        // 血条/面板）与 uVP=IDENTITY（aPos 已是 NDC 的 minimap/天体）。反算对两者同时成立。
        //
        // aUv（location=2）是**自研艺术 UI 的图集 UV**。旧调用点（addRect2D 等）不传 UV →
        // 由发射器填 WHITE_TILE 的 UV → 采样返回纯白 → 与"无纹理"逐字节等价（identity 元素）。
        String string = "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec4 aCol;layout(location=2) in vec2 aUv;uniform mat4 uVP;out vec4 vCol;out vec2 vSuv;out vec2 vUv;void main(){vCol=aCol;vUv=aUv;gl_Position=uVP*vec4(aPos,1);vSuv=gl_Position.xy/max(abs(gl_Position.w),1e-6)*0.5+0.5;}";
        // FS：图集纹理 × 顶点色，再叠自研艺术材质层（纸纹噪点 / 暗角）。
        // 恒等性：uArtAmt=0 且 UV=UI_WHITE_TILE(严格纯白) → tex=vec4(1) → c=vCol → 与旧式
        //         fc=vec4(vCol.rgb, vCol.a) 逐字节相同。
        //
        // ⚠️⚠️ RGB **绝不能**乘 vCol.a！旧式是"直通 alpha"（straight alpha）+ blendFunc(SRC_ALPHA,
        //    ONE_MINUS_SRC_ALPHA)。若在 FS 里预乘（c.rgb*=vCol.a），所有 alpha<1 的 HUD 元素
        //    （minimap 面板 a=0.60、焦点遮罩 a=0.25、物品栏底 a=0.94…）会**在自己的 alpha 上再压一次**，
        //    表现为全屏确定性压暗。2026-09-21 实测：默认态与改动前相差 23.025% 像素、maxΔ 91，
        //    在 minimap 区域比值恰为 53/85≈0.62≈其 alpha 0.60 —— 这就是该 bug 的指纹。
        //    t.a 参与 alpha 是**对的**（它会让恒等纹理的 alpha=1 严格退化），RGB 只乘 t.rgb。
        //
        // ⚠️⚠️ 必须是**单个字面量**，绝不能写成 "..." + "..." 拼接！
        //   ShaderCheck.extractMethodShaders → extractFirstLiteralContaining → readLiteral
        //   只读到**第一个未转义双引号**为止，不解析 Java 的 + 拼接 —— 拼接会让门禁只编译第一段，
        //   得到一个残缺 FS 却报 PASS（**静默假绿**）。2026-09-21 已实测：拼接时抽取长度仅 100B、
        //   函数体全丢。ArtUiTest 有断言专门钉这一条。
        String string2 = "#version 330 core\nin vec4 vCol;in vec2 vSuv;in vec2 vUv;uniform float uArtAmt;uniform vec2 uArtRes;uniform sampler2D uTex;out vec4 fc;\nfloat h21(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}\nfloat vnoise(vec2 p){vec2 i=floor(p);vec2 f=fract(p);f=f*f*(3.0-2.0*f);float a=h21(i);float b=h21(i+vec2(1.0,0.0));float c=h21(i+vec2(0.0,1.0));float d=h21(i+vec2(1.0,1.0));return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);}\nvoid main(){\n  vec4 t=texture(uTex,vUv);\n  vec4 c=vec4(vCol.rgb*t.rgb,vCol.a*t.a);\n  if(uArtAmt>0.0001){\n    vec2 r=max(uArtRes,vec2(1.0));\n    vec2 px=vSuv*r;\n    float grain=vnoise(px*0.55)*0.6+vnoise(px*2.3)*0.4;\n    c.rgb*=1.0+(grain-0.5)*0.16*uArtAmt;\n    vec2 vig=abs(vSuv-0.5)*2.0;\n    c.rgb*=1.0-smoothstep(0.55,1.0,max(vig.x,vig.y))*0.22*uArtAmt;\n  }\n  fc=c;\n}";
        this.hudShader = Game.makeProgram(string, string2);
        this.hudUVP = GL33.glGetUniformLocation((int)this.hudShader, (CharSequence)"uVP");
        this.hudUArtAmt = GL33.glGetUniformLocation((int)this.hudShader, (CharSequence)"uArtAmt");
        this.hudUArtRes = GL33.glGetUniformLocation((int)this.hudShader, (CharSequence)"uArtRes");
        this.hudUTex = GL33.glGetUniformLocation((int)this.hudShader, (CharSequence)"uTex");
    }

    /**
     * P1（2026-09-23）：MSDF 通道的着色程序。
     *
     * <p><b>为什么不能复用 HUD 程序</b>：HUD 的 FS 是"图集纹理 × 顶点色"；MSDF 要对采样到的
     * 距离场做 median 重建 + 屏幕像素换算的 smoothstep —— 语义完全不同。合成一个 FS 会让
     * 每一片 HUD 像素都多付一次分支。
     *
     * <p><b>核心公式</b>（msdfgen 官方写法，本实现的距离场符号约定与它对齐，见 {@link MsdfGen}）：
     * <pre>
     *   sd          = median(rgb)                      每通道一个距离，取中位数把它们还原成"真距离"
     *   screenRange = max(0.5 · dot(pxRange/atlasSize, 1/fwidth(uv)), 1.0)
     *   alpha       = clamp(screenRange·(sd−0.5) + 0.5, 0, 1)
     * </pre>
     * {@code fwidth} 是关键：它给出"一个纹理 texel 在屏幕上占多少像素"，于是同一张距离场在
     * 任意字号下都自动得到合适的平滑宽度 —— 这正是位图字体做不到的（位图放大只会把方像素拉大）。
     * {@code max(..., 1.0)} 是防缩到极小时的振铃（屏幕像素/Texel 小于 1 时按 1 处理）。
     *
     * <p><b>描边</b>：同一个距离场再取一次阈值 {@code uOutline}，把 [−uOutline, 0] 这段
     * 距离染成描边色。这是距离场的白送能力（位图实现要重新光栅化一套描边图）。
     *
     * <p>⚠️ 纪律：GLSL 必须是**单个字面量**且**内部绝不能出现裸双引号**（否则既截断 Java 字符串，
     * 又骗过 {@code ShaderCheck} 的朴素引号扫描器，得到"残缺却报 PASS"的静默假绿）。
     * 本方法已登记进 {@code ShaderCheck.METHODS}（显示名 msdf → 方法签名 initMsdfShader）。
     */
    private void initMsdfShader() {
        String vs = "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec4 aCol;layout(location=2) in vec2 aUv;uniform mat4 uVP;out vec4 vCol;out vec2 vUv;void main(){vCol=aCol;vUv=aUv;gl_Position=uVP*vec4(aPos,1.0);}";
        String fs = "#version 330 core\nin vec4 vCol;in vec2 vUv;uniform sampler2D uTex;uniform vec2 uAtlas;uniform float uPxRange;uniform float uOutline;uniform vec3 uOutlineCol;out vec4 fc;\nvoid main(){\n  vec3 s=texture(uTex,vUv).rgb;\n  float med=max(min(s.r,s.g),min(max(s.r,s.g),s.b));\n  vec2 unit=vec2(uPxRange)/uAtlas;\n  vec2 screenTex=vec2(1.0)/max(fwidth(vUv),vec2(1e-8));\n  float range=max(0.5*dot(unit,screenTex),1.0);\n  float d=range*(med-0.5);\n  float aFill=clamp(d+0.5,0.0,1.0);\n  if(uOutline<=0.0001){fc=vec4(vCol.rgb,vCol.a*aFill);return;}\n  float aOut=clamp(d+uOutline+0.5,0.0,1.0);\n  vec3 col=mix(uOutlineCol,vCol.rgb,aFill);\n  fc=vec4(col,vCol.a*aOut);\n}";
        this.msdfShader = Game.makeProgram(vs, fs);
        this.msdfUVP = GL33.glGetUniformLocation((int)this.msdfShader, (CharSequence)"uVP");
        this.msdfUTex = GL33.glGetUniformLocation((int)this.msdfShader, (CharSequence)"uTex");
        this.msdfUAtlas = GL33.glGetUniformLocation((int)this.msdfShader, (CharSequence)"uAtlas");
        this.msdfURange = GL33.glGetUniformLocation((int)this.msdfShader, (CharSequence)"uPxRange");
        this.msdfUOutline = GL33.glGetUniformLocation((int)this.msdfShader, (CharSequence)"uOutline");
        this.msdfUOutlineCol = GL33.glGetUniformLocation((int)this.msdfShader, (CharSequence)"uOutlineCol");
    }

    private void initWorldShader() {
        String string = "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec3 aCol;layout(location=2) in vec3 aNormal;layout(location=3) in float aWind;layout(location=4) in vec2 aUv;layout(location=5) in float aLamp;layout(location=6) in vec3 aEdge;uniform mat4 uVP;uniform float uTime;uniform vec3 uCamPos;uniform vec2 uChunkShift;out vec3 vCol;out vec3 vN;out vec3 vWorld;out vec2 vUv;out float vLamp;out vec3 vEdge;void main(){  vec3 pos=aPos;  if(aWind>0.5){float t=uTime*1.6;float ph=aPos.x*0.35+aPos.z*0.35;    pos.x+=sin(t+ph)*0.09;pos.z+=cos(t*0.8+ph)*0.06;pos.y+=sin(t*2.0+ph)*0.02;}  vCol=aCol;vN=aNormal;vWorld=aPos+vec3(uChunkShift.x,0.0,uChunkShift.y);vUv=aUv;vLamp=aLamp;vEdge=aEdge;  pos.x+=uChunkShift.x;pos.z+=uChunkShift.y;gl_Position=uVP*vec4(pos,1.0);}";
        String string2 = "#version 330 core\nin vec3 vCol;in vec3 vN;in vec3 vWorld;in vec2 vUv;in float vLamp;in vec3 vEdge;\nuniform vec3 uLightDir;uniform vec3 uFogColor;uniform float uFogDensity;uniform vec3 uCamPos;uniform float uTorchR;uniform vec3 uTorchCol;\nuniform float uAmbient;uniform vec3 uSkyColor;uniform vec3 uLightTint;uniform vec2 uRes;uniform float uFogScale;uniform float uFogFar;uniform float uFogFalloff;uniform vec3 uDirInsc;uniform vec3 uDirInscDir;uniform float uDirInscExp;uniform float uDirInscStart;uniform float uFogBase;uniform float uUnder;uniform vec3 uWaterCol;\nuniform sampler2D uTex;uniform sampler2D uNrm;uniform sampler3D uShadowTex;uniform vec3 uShadowSize;uniform float uShadowStrength;uniform float uShadowSoft;uniform float uAoStrength;uniform float uShaftAmount;uniform float uShadowFall;uniform float uShadowFade0;uniform float uShadowFade1;uniform vec2 uDetailOrg;uniform vec2 uDetailCell;\nuniform float uTime;\nout vec4 fc;\nfloat h31(vec3 p){return fract(sin(dot(p,vec3(12.9898,78.233,37.719)))*43758.5453);}\nvec3 acesF(vec3 x){const float a=2.51,b=0.03,c=2.43,d=0.59,e=0.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}\nuniform float uDither;uniform float uExposure;\nvec3 grade(vec3 s){s*=uExposure;vec3 lin=s*s*0.6659+0.0035;vec3 o=sqrt(acesF(lin));o=(o-0.5)*1.08+0.5;float lumM=dot(o,vec3(0.2126,0.7152,0.0722));o=mix(vec3(lumM),o,1.10);o+=(h31(vec3(gl_FragCoord.xy,0.0))-0.5)*uDither;return clamp(o,0.0,1.0);}\nuniform float uA2C;\nfloat voxOcc(vec3 p){ivec3 ip=ivec3(floor(p));if(any(lessThan(ip,ivec3(0)))||any(greaterThanEqual(ip,ivec3(uShadowSize))))return 0.0;return texelFetch(uShadowTex,ip,0).r;}\nfloat voxAO(vec3 wp,vec3 n,float dist){float k=uAoStrength*(1.0-smoothstep(12.0,56.0,dist));if(k<=0.002)return 1.0;vec3 t1=(abs(n.y)<0.9)?normalize(cross(n,vec3(0.0,1.0,0.0))):vec3(1.0,0.0,0.0);vec3 t2=cross(n,t1);vec3 o=wp+n*0.75;float occ=0.0;for(int i=0;i<6;i++){float a=float(i)*1.0472;vec3 d=normalize(n*0.75+(t1*cos(a)+t2*sin(a))*0.66);occ+=voxOcc(o+d*2.0)*0.5+voxOcc(o+d*4.5);}occ*=0.1111111;return clamp(1.0-occ*k,0.25,1.0);}\nfloat voxShaft(vec3 wp,vec3 vdir,float dist,float amt){if(amt<=0.002)return 1.0;vec3 SL=normalize(uDirInscDir);float span=min(dist,24.0);if(span<=0.2)return 1.0;float lit=0.0;for(int i=0;i<4;i++){vec3 p=wp+vdir*(span*((float(i)+0.5)*0.25));float occ=voxOcc(p+SL*4.0)+voxOcc(p+SL*12.0)+voxOcc(p+SL*28.0);lit+=1.0-clamp(occ*0.3333333,0.0,1.0);}lit*=0.25;return mix(1.0,lit,amt);}\nfloat sunShadow(vec3 wp,vec3 n,vec3 L,float dist){float k=uShadowStrength*(1.0-smoothstep(uShadowFade0,uShadowFade1,dist));if(k<=0.002||L.y<=0.02)return 1.0;vec3 o=wp+n*0.10;float t=0.6;for(int i=0;i<14;i++){vec3 p=o+L*t;if(any(lessThan(p,vec3(0.0)))||any(greaterThan(p,uShadowSize)))break;if(voxOcc(p)>0.5)return 1.0-uShadowSoft/(1.0+t*uShadowFall);t+=(i<8)?0.7:3.8;}return 1.0;}\nvoid main(){\n  vec4 tx=texture(uTex,vUv);\n  if(tx.a<0.02) discard;\n  float covA=tx.a;if(uA2C>0.5&&tx.a>0.98){float m1=textureLod(uTex,vUv,1.0).a;if(m1<0.98&&m1>0.02)covA=m1;}\n  float dist=distance(vWorld,uCamPos);\n  float nearMix=1.0-smoothstep(12.0,92.0,dist);\n  if(nearMix>0.001){vec2 duv=fract(vWorld.xz*0.31);float dv=texture(uTex,uDetailOrg+uDetailCell*duv).r;tx.rgb*=1.0+(dv-0.5)*0.36*nearMix;\n    vec2 duv2=fract(vWorld.yz*0.53+vec2(0.37,0.61));float dv2=texture(uTex,uDetailOrg+uDetailCell*duv2).r;tx.rgb*=1.0+(dv2-0.5)*0.16*nearMix;}\n  vec3 dpx=dFdx(vWorld);vec3 dpy=dFdy(vWorld);vec3 tT=normalize(dpx);vec3 tB=normalize(cross(vN,tT));\n  vec3 nn=normalize(vN);\n  vec3 n=normalize(mat3(tT,tB,nn)*(texture(uNrm,vUv).rgb*2.0-1.0)*0.55+nn*0.45);\n  vec3 vDir=normalize(uCamPos-vWorld);\n  vec3 L=normalize(uLightDir);\n  float sh=sunShadow(vWorld,n,L,dist);float key=((dot(n,n)<1e-6)?0.7:max(dot(n,L),0.0)*0.78)*sh;\n// sky fill (moonlight): independent uSkyColor channel, never 0 (MC dual-channel + UE SkyLight fill)\n  float skyFill=0.6+0.4*n.y;\n  float nightK=clamp((0.58-uAmbient)/0.28,0.0,1.0);\n  // MC LightTexture: skyTerm = (f,f,1)-tinted sky channel; nightK is our night lift, applied to the\n  // SKY channel only (MC has no such lift; boosting the lamp channel too made night torches blow out).\n  float nightBoost=1.0+0.45*nightK;vec3 skyTerm=clamp((key*uLightTint+uAmbient*(0.5+0.5*skyFill)*uSkyColor*nightBoost),vec3(0.0),vec3(1.0));\n  float aoL=voxAO(vWorld,nn,dist);skyTerm*=aoL;vec3 col=tx.rgb*vCol*skyTerm;\n  float torchT=0.0;if(uTorchR>0.5){float td=distance(vWorld,uCamPos);float tr=clamp(1.0-td/uTorchR,0.0,1.0);tr=tr*tr*(3.0-2.0*tr);torchT=tr*(0.78+0.22*sin(uTime*11.0+vWorld.x*0.7+vWorld.z*1.3));}float lampV=max(vLamp,torchT);vec3 lampCol=mix(vec3(0.95,0.72,0.45),uTorchCol,clamp(torchT,0.0,1.0));\n  // MC: lightmap = clamp(blockTerm + skyTerm, 0, 1), final = texture * lightmap. The old additive form\n  // (texture*lampV*warm*2.5) overshot the albedo by ~27% in R -> snow/sand burned to cream.\n  vec3 lmF=min(skyTerm+lampV*lampCol,vec3(1.0));\n  // The 4% ambient floor must live in the LIGHTMAP (MC does lerp(lightmap,0.75,0.04)): applied there\n  // it is a multiplication on the albedo and keeps hue; applied to the final colour it washed dark\n  // blocks toward gray (bedrock lost half its saturation).\n  col=tx.rgb*vCol*(lmF*0.96+vec3(0.030));if(vEdge.z>0.0){float ep=texture(uTex,vEdge.xy).r;col*=mix(vec3(1.0),vec3(ep*2.0),clamp(vEdge.z,0.0,1.0));}\n  vec3 hv=normalize(L+vDir);\n  float spec=pow(max(dot(n,hv),0.0),40.0)*0.22;\n  col+=spec*uLightTint*sh;\n  float rim=pow(1.0-max(dot(n,vDir),0.0),3.0)*key;\n  col+=rim*uLightTint*0.09;\n  if(tx.a<0.98){\n    // åéæé¢ï¼æ°´/ç»çè¾¹æ¡ï¼ï¼è²æ¶å°åå°å¤©ç©ºè² + æ´èçå¤ªé³éé¢ï¼æ¿ä»£ååå¹³æ·¡çåºå® alpha\n    float fres=0.03+0.97*pow(1.0-max(dot(n,vDir),0.0),5.0);\n    vec3 skyRef=uFogColor*(0.85+0.35*clamp(dot(nn,L),0.0,1.0));\n    col=mix(col,skyRef,clamp(fres*0.80,0.0,0.80));\n    col+=uLightTint*pow(max(dot(n,hv),0.0),190.0)*0.55;\n  }\n  // å¤§æ°éè§ï¼é¾è²æå¤ªé³æ¹ååæï¼éåæé¾/é¡ºåå·é¾ï¼ï¼å¹¶ä¿çé«åº¦é¾\n  float sunAmt=clamp(dot(vDir,L),0.0,1.0);\n  float hdist=length(vWorld.xz-uCamPos.xz);\n  // UE ExponentialHeightFog (HeightFogCommon.ush): d(z)=Density*2^(-Falloff*(z-FogBase)), integrated\n  // analytically along the view ray. shape = (1-2^-A)/A / ln2 is that integral normalised so\n  // shape(A->0)==1: a horizontal view at FogBase reproduces the previous 1-exp(-D*dist) exactly,\n  // so the existing tuning carries over unchanged and the height term only modulates it.\n  float densCam=uFogDensity*uFogScale*exp2(-uFogFalloff*(uCamPos.y-uFogBase));\n  float fA=clamp(uFogFalloff*(vWorld.y-uCamPos.y),-20.0,20.0);\n  float shape=abs(fA)>1e-3?((1.0-exp2(-fA))/fA)*1.4427:(1.0-0.3466*fA);\n  shape=min(shape,8.0);\n  float fAir=clamp(1.0-exp(-densCam*shape*dist),0.0,1.0);\n  // MC FogType.WATER: fog range becomes start=-8 / end=96 and fog color becomes the water\n  // fog color. Deviation, deliberate: our streaming window is only ~61 blocks half-width, so\n  // MC's end=96 is effectively no fog at all at this scale -> rescaled to 64 (start stays -8,\n  // i.e. an 8% baseline tint right at the eye, exactly like MC).\n  float fWater=clamp((dist+8.0)/64.0,0.0,1.0);\n  float f=mix(fAir,fWater,uUnder);\n  // Window-edge saturation must apply in BOTH media (otherwise the void outside the streaming\n  // window peeks through while submerged, since fWater only reaches 0.72 at the window rim).\n  f=max(f,smoothstep(uFogFar*0.75,uFogFar,hdist));\n  vec3 fogC=mix(uFogColor,uWaterCol,uUnder);\n  col=mix(col,fogC,f);\n  // UE ExponentialHeightFog directional in-scattering: DirColor * pow(saturate(dot(rayDir,L)),Exp),\n  // weighted by the fog transmittance past DirectionalInscatteringStartDistance (UE default Exp=4).\n  // uDirInsc carries the sun/moon tint, so the glow automatically follows the celestial body.\n  // NOTE: the light DIRECTION must follow the body that is actually up (uLightDir is always the SUN\n  // direction, which is below the horizon at night -> using it made the glow point the wrong way).\n  float dAmt=pow(clamp(dot(vDir,normalize(uDirInscDir)),0.0,1.0),uDirInscExp);\n  float dIns=dAmt*f*(1.0-uUnder)*smoothstep(uDirInscStart,uDirInscStart*2.2,dist);if(dAmt>0.001)dIns*=voxShaft(vWorld,vDir,dist,uShaftAmount);\n  col+=uDirInsc*dIns*0.55*max(vec3(0.0),vec3(1.0)-col);\n  // MC LightTexture:130 lerps the lightmap toward 0.75 by 4% (= v*0.96+0.03). That is an\n  // ADDITIVE floor which PRESERVES hue; a max() clamp desaturates dark blocks toward gray\n  // (measured vs MC: bedrock went from saturated blue to pure gray, dirt lost 2/3 saturation).\n  col=grade(col);\n  vec2 q=gl_FragCoord.xy/uRes;\n  col*=mix(1.0,0.82,smoothstep(0.34,0.82,length(q-0.5)));\n  fc=vec4(clamp(col,0.0,1.0),covA);\n}";
        this.worldShader = Game.makeProgram(string, string2);
        this.worldVP = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uVP");
        this.uLightDir = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uLightDir");
        this.uTime = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uTime");
        this.uCamPos = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uCamPos");
        this.uChunkShift = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uChunkShift");
        this.uFogColor = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uFogColor");
        this.uFogDensity = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uFogDensity");
        this.uFogScale = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uFogScale");
        this.uFogFar = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uFogFar");
        this.uFogFalloff = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uFogFalloff");
        this.uFogBase = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uFogBase");
        this.uDirInsc = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uDirInsc");
        this.uDirInscDir = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uDirInscDir");
        this.uDirInscExp = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uDirInscExp");
        this.uDirInscStart = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uDirInscStart");
        this.uUnder = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uUnder");
        this.uWaterCol = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uWaterCol");
        this.uAmbient = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uAmbient");
        this.uLightTint = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uLightTint");
        this.uSkyColor = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uSkyColor");
        this.uTorchR = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uTorchR");
        this.uTorchCol = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uTorchCol");
        this.uWorldRes = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uRes");
        this.uTex = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uTex");
        this.uNrm = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uNrm");
        this.uShadowTex = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uShadowTex");
        this.uShadowSize = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uShadowSize");
        this.uShadowStrength = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uShadowStrength");
        this.uShadowSoft = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uShadowSoft");
        this.uAoStrength = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uAoStrength");
        this.uShaftAmount = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uShaftAmount");
        this.uDither = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uDither");
        this.uA2C = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uA2C");
        this.uExposure = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uExposure");
        this.uShadowFall = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uShadowFall");
        this.uShadowFade0 = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uShadowFade0");
        this.uShadowFade1 = GL33.glGetUniformLocation((int)this.worldShader, (CharSequence)"uShadowFade1");
    }

    private void initSkyShader() {
        String string = "#version 330 core\nlayout(location=0) in vec2 aP;out vec2 vP;void main(){vP=aP;gl_Position=vec4(aP,0.0,1.0);}";
        String string2 = "#version 330 core\nin vec2 vP;out vec4 fc;\nuniform vec3 uSkyTop,uSkyHorizon,uSkyFwd,uSkyRight,uSkyUp,uSunDir,uSunColor;\nuniform vec2 uSkyTan;uniform float uUnder;uniform vec3 uWaterCol;uniform float uStar,uRain,uTime;uniform vec2 uRes;\nuniform sampler2D uNoise;\nfloat h31(vec3 p){return fract(sin(dot(p,vec3(12.9898,78.233,37.719)))*43758.5453);}\nvec3 acesF(vec3 x){const float a=2.51,b=0.03,c=2.43,d=0.59,e=0.14;return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);}\nuniform float uDither;uniform float uExposure;\nvec3 grade(vec3 s){s*=uExposure;vec3 lin=s*s*0.6659+0.0035;vec3 o=sqrt(acesF(lin));o=(o-0.5)*1.08+0.5;float lumM=dot(o,vec3(0.2126,0.7152,0.0722));o=mix(vec3(lumM),o,1.10);o+=(h31(vec3(gl_FragCoord.xy,0.0))-0.5)*uDither;return clamp(o,0.0,1.0);}\nvoid main(){\n  vec3 dir=normalize(uSkyFwd+uSkyRight*(vP.x*uSkyTan.x)+uSkyUp*(vP.y*uSkyTan.y));\n  float up=dir.y;\n  float t=clamp(up,0.0,1.0);t=t*t*(3.0-2.0*t);\n  vec3 col=mix(uSkyHorizon,uSkyTop,t);\n  col=mix(col,uSkyHorizon,clamp(-up*3.0,0.0,1.0));\n  vec3 sunN=normalize(uSunDir);\n  float day=1.0-uStar;\n  float sunAz=clamp(dot(normalize(vec3(dir.x,0.0,dir.z)+vec3(1e-4,0.0,0.0)),normalize(vec3(uSunDir.x,0.0,uSunDir.z)+vec3(1e-4,0.0,0.0))),0.0,1.0);\n  sunAz=sunAz*sunAz;\n  float band=clamp(1.0-abs(up)*6.0,0.0,1.0);\n  col+=uSunColor*(band*sunAz*(0.15+0.22*day)*(1.0-uStar*0.85));\n  if(uStar>0.001){\n    vec3 sp=dir*72.0;vec3 g=floor(sp);float r0=h31(g);\n    if(r0>0.70){\n      vec3 off=vec3(h31(g+11.0),h31(g+37.0),h31(g+71.0))-0.5;\n      float dd=length(fract(sp)-0.5-off*0.55);\n      float mag=pow((r0-0.70)/0.30,0.55);\n      float tw=0.74+0.26*sin(uTime*1.3+r0*61.0);\n      col+=vec3(0.86,0.90,1.0)*smoothstep(0.040+0.055*(1.0-mag),0.0,dd)*(0.22+0.90*mag)*tw*uStar*clamp(up*4.0,0.0,1.0);\n    }\n    float mw=exp(-pow(dot(dir,normalize(vec3(0.62,0.28,-0.73)))*2.5,2.0));\n    col+=vec3(0.30,0.34,0.46)*mw*(0.55+0.40*(texture(uNoise,dir.xz*0.9+dir.y*0.45).r-0.5)*2.0)*0.55*uStar*clamp(up*3.0,0.0,1.0);\n  }\n  float sd=dot(dir,sunN);float hz=smoothstep(-0.03,0.01,up);\n  col+=uSunColor*pow(max(sd,0.0),220.0)*1.05*hz;\n  col+=uSunColor*smoothstep(0.99988,0.99997,sd)*2.2*day*hz;\n  col+=vec3(0.86,0.90,1.0)*smoothstep(0.9960,0.9991,sd)*1.7*uStar*hz;\n  if(up>0.02){\n    vec2 cp=dir.xz/max(up,0.10);\n    float w=uTime*0.020;\n    vec2 w1=vec2(w,w*0.36);\n    float d=texture(uNoise,cp*0.288+w1).r*0.50\n           +texture(uNoise,cp*0.600-w1*1.4).r*0.30\n           +texture(uNoise,cp*1.240+w1*2.0).r*0.20;\n    float th=texture(uNoise,cp*0.420-w1*0.6).g*2.0-1.0;\n    float cov=smoothstep(0.46,0.70,d)*smoothstep(0.07,0.26,up);\n    float thick=smoothstep(-0.22,0.34,th);\n    float litS=smoothstep(-0.10,0.80,clamp(dot(normalize(dir),sunN),0.0,1.0));\n    vec3 shadowC=mix(vec3(0.36,0.40,0.49),uSkyHorizon*0.80,uStar);\n    vec3 litC=mix(vec3(1.08,1.06,1.01),uSunColor*1.32,clamp(1.0-day*0.62,0.0,1.0));\n    vec3 cCol=mix(shadowC,litC,clamp(litS*0.62+0.38*thick,0.0,1.0));float rlCld=dot(cCol,vec3(0.2126,0.7152,0.0722))*0.24;cCol=mix(cCol,vec3(rlCld),uRain*0.5);\n    col=mix(col,cCol,cov*(0.52+0.48*day));\n  }\n  float rlSky=dot(col,vec3(0.2126,0.7152,0.0722))*0.6;col=mix(col,vec3(rlSky),uRain*0.75);\n  // MC: while the eye is in water the clear color / sky background is the water fog color.\n  col=mix(col,uWaterCol,clamp(uUnder,0.0,1.0)*0.92);\n  col=grade(col);\n  vec2 q=gl_FragCoord.xy/uRes;\n  col*=mix(1.0,0.88,smoothstep(0.38,0.82,length(q-0.5)));\n  fc=vec4(clamp(col,0.0,1.0),1.0);\n}";
        this.skyShader = Game.makeProgram(string, string2);
        this.uSkyTop = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uSkyTop");
        this.uSkyHorizon = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uSkyHorizon");
        this.uSkyFwd = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uSkyFwd");
        this.uSkyRight = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uSkyRight");
        this.uSkyUp = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uSkyUp");
        this.uSkyTan = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uSkyTan");
        this.uSunDir = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uSunDir");
        this.uSunColor = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uSunColor");
        this.uStar = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uStar");
        this.uRain = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uRain");
        this.uSkyRes = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uRes");
        this.uSkyTime = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uTime");
        this.uSkyDither = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uDither");
        this.uSkyExposure = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uExposure");
        this.uSkyNoise = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uNoise");
        this.uSkyUnder = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uUnder");
        this.uSkyWaterCol = GL33.glGetUniformLocation((int)this.skyShader, (CharSequence)"uWaterCol");
        this.skyVAO = GL33.glGenVertexArrays();
        GL33.glBindVertexArray((int)this.skyVAO);
        this.skyVBO = GL33.glGenBuffers();
        GL33.glBindBuffer((int)34962, (int)this.skyVBO);
        float[] fArray = new float[]{-1.0f, -1.0f, 3.0f, -1.0f, -1.0f, 3.0f};
        FloatBuffer floatBuffer = BufferUtils.createFloatBuffer((int)fArray.length);
        floatBuffer.put(fArray).flip();
        GL33.glBufferData((int)34962, (FloatBuffer)floatBuffer, (int)35044);
        GL33.glVertexAttribPointer((int)0, (int)2, (int)5126, (boolean)false, (int)8, (long)0L);
        GL33.glEnableVertexAttribArray((int)0);
        GL33.glBindVertexArray((int)0);
    }

    /**
     * 水下全屏叠加层（学 MC {@code ScreenEffectRenderer.renderWater}）。
     *
     * <p>MC 的做法：眼位在水里时，画一张铺满屏幕的 {@code underwater.png}，UV 随时间**匀速流动**，
     * 颜色乘 {@code setShaderColor(f,f,f, 0.1)} —— <b>alpha 只有 0.1</b>，且亮度 {@code f} 跟随
     * 眼睛所在方块的光照（夜里水下更暗）。水色本身由**雾**承担（MC 把雾色换成了水体色），叠加层只提供
     * "流动的水纹"质感。</p>
     *
     * <p>我们照抄这个分工：水体色靠 world/sky 的雾色切换，本层用现成的噪声纹理（{@code NoiseTex}，
     * 已有 mipmap 与 REPEAT）当"水下图案"，两层不同频率反向流动。因为我们的天空盒不像 MC 那样被雾染色，
     * 这里 alpha 比 MC 略高（0.16~0.38 随图案起伏），由 {@code uUnder} 平滑淡入淡出。</p>
     */
    private void initUnderwaterOverlay() {
        // 顶点复用天空那枚全屏三角（skyVAO，attrib 0 = vec2），无需新 VAO。
        String string = "#version 330 core\nlayout(location=0) in vec2 aP;out vec2 vP;void main(){vP=aP;gl_Position=vec4(aP,0.0,1.0);}";
        String string2 = "#version 330 core\nin vec2 vP;out vec4 fc;\nuniform sampler2D uNoise;uniform float uAmt,uTime;uniform vec3 uWaterCol;uniform vec2 uRes;\nvoid main(){\n  vec2 uv=vP*0.5+0.5;\n  vec2 p=vec2(uv.x*uRes.x/max(uRes.y,1.0),uv.y);\n  float t=uTime*0.06;\n  float a=texture(uNoise,p*1.55+vec2(t*0.70,t*0.42)).r;\n  float b=texture(uNoise,p*3.05-vec2(t*0.46,t*1.05)).r;\n  float pat=0.55*a+0.45*b;\n  float al=uAmt*(0.12+0.20*pat);\n  if(al<=0.002) discard;\n  fc=vec4(uWaterCol*(0.72+0.56*pat),al);\n}";
        this.underShader = Game.makeProgram(string, string2);
        this.uUndNoise = GL33.glGetUniformLocation((int)this.underShader, (CharSequence)"uNoise");
        this.uUndAmt = GL33.glGetUniformLocation((int)this.underShader, (CharSequence)"uAmt");
        this.uUndTime = GL33.glGetUniformLocation((int)this.underShader, (CharSequence)"uTime");
        this.uUndCol = GL33.glGetUniformLocation((int)this.underShader, (CharSequence)"uWaterCol");
        this.uUndRes = GL33.glGetUniformLocation((int)this.underShader, (CharSequence)"uRes");
    }

    /**
     * 破坏阶段裂纹叠加（学 MC {@code RenderType.destroy} + {@code BlockRenderDispatcher.renderBreakingTexture}）。
     *
     * <p>MC 挖方块时把 {@code destroy_stage_0..9} 叠在方块表面，而<b>不是</b>让方块整体变暗——
     * 方块自身纹理始终看得见，玩家读得出"还差多少"。顶点布局与阴影 pass 同构（pos3 + uv2、stride 20），
     * 只多一次纹理采样：拿图集裂纹 tile 的 <b>alpha 当遮罩</b>，颜色与不透明度由 uniform 给，
     * 于是裂纹<b>不参与光照</b>（夜里也看得见）—— 与 MC 那个"贴上去"的叠加层语义一致。</p>
     */
    private void initCrackPass() {
        String string = "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec2 aUv;uniform mat4 uVP;out vec2 vUv;void main(){vUv=aUv;gl_Position=uVP*vec4(aPos,1.0);}";
        // 注意：内联 GLSL 一律写成**单个**字面量（不要用 + 拼接）。门禁 ShaderCheck 靠朴素引号扫描
        // 抽取方法体内的字面量，拼接会被截断成半截 GLSL。**而且本注释里也不能出现双引号**——
        // 扫描器不认注释，引号会配错对，把后面真正的字面量截歪（实测报 syntax error）。
        String string2 = "#version 330 core\nin vec2 vUv;out vec4 fc;uniform sampler2D uTex;uniform vec3 uCrackCol;uniform float uCrackA;\nvoid main(){float a=texture(uTex,vUv).a*uCrackA;if(a<0.03) discard;fc=vec4(uCrackCol,a);}";
        this.crackShader = Game.makeProgram(string, string2);
        this.uCrackVP = GL33.glGetUniformLocation((int)this.crackShader, (CharSequence)"uVP");
        this.uCrackTex = GL33.glGetUniformLocation((int)this.crackShader, (CharSequence)"uTex");
        this.uCrackCol = GL33.glGetUniformLocation((int)this.crackShader, (CharSequence)"uCrackCol");
        this.uCrackA = GL33.glGetUniformLocation((int)this.crackShader, (CharSequence)"uCrackA");
        this.crackVAO = GL33.glGenVertexArrays();
        GL33.glBindVertexArray((int)this.crackVAO);
        this.crackVBO = GL33.glGenBuffers();
        GL33.glBindBuffer((int)34962, (int)this.crackVBO);
        GL33.glBufferData((int)34962, (long)720L, (int)35048);      // 180 float 动态
        GL33.glVertexAttribPointer((int)0, (int)3, (int)5126, (boolean)false, (int)20, (long)0L);
        GL33.glEnableVertexAttribArray((int)0);
        GL33.glVertexAttribPointer((int)1, (int)2, (int)5126, (boolean)false, (int)20, (long)12L);
        GL33.glEnableVertexAttribArray((int)1);
        GL33.glBindVertexArray((int)0);
    }

    private void initShadowShader() {
        String string = "#version 330 core\nlayout(location=0) in vec3 aPos;layout(location=1) in vec2 aUv;uniform mat4 uVP;out vec2 vUv;void main(){vUv=aUv;gl_Position=uVP*vec4(aPos,1.0);}";
        String string2 = "#version 330 core\nin vec2 vUv;out vec4 fc;uniform vec3 uShadowColor;uniform float uShadowAlpha;void main(){float d=length(vUv);float a=clamp(1.0-d,0.0,1.0);a=a*a*(3.0-2.0*a)*uShadowAlpha;if(a<=0.001) discard;fc=vec4(uShadowColor,a);}";
        this.shadowShader = Game.makeProgram(string, string2);
        this.shadowUVP = GL33.glGetUniformLocation((int)this.shadowShader, (CharSequence)"uVP");
        this.uShadowColor = GL33.glGetUniformLocation((int)this.shadowShader, (CharSequence)"uShadowColor");
        this.uShadowAlpha = GL33.glGetUniformLocation((int)this.shadowShader, (CharSequence)"uShadowAlpha");
        this.shadowVAO = GL33.glGenVertexArrays();
        GL33.glBindVertexArray((int)this.shadowVAO);
        this.shadowVBO = GL33.glGenBuffers();
        GL33.glBindBuffer((int)34962, (int)this.shadowVBO);
        GL33.glBufferData((int)34962, (long)80000L, (int)35048);
        GL33.glVertexAttribPointer((int)0, (int)3, (int)5126, (boolean)false, (int)20, (long)0L);
        GL33.glEnableVertexAttribArray((int)0);
        GL33.glVertexAttribPointer((int)1, (int)2, (int)5126, (boolean)false, (int)20, (long)12L);
        GL33.glEnableVertexAttribArray((int)1);
        GL33.glBindVertexArray((int)0);
    }

    // ---------- P4 体素软阴影：3D 占用纹理 ----------
    /**
     * 建立体素占用 3D 纹理（GL_R8 / NEAREST / CLAMP_TO_EDGE）并上传当前窗口。
     *
     * <p>为什么 NEAREST 不能省：线性插值会在"空(0)/实(1)"之间插出中间值，于是光线步进会采到
     * "半实体"、阴影边缘糊成一片渐变。体素占用是离散量，必须最近邻。
     */
    private void initShadowVolume() {
        core.world.World w = this.sim.world;
        this.shadowTex3D = GL33.glGenTextures();
        GL33.glBindTexture(GL33.GL_TEXTURE_3D, this.shadowTex3D);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_R, GL33.GL_CLAMP_TO_EDGE);
        // 先只分配存储（内容 null），随即由 uploadShadowVolume 用 glTexSubImage3D 填满。
        // ⚠️ 缓冲区必须 memAlloc：堆缓冲上传会静默变成全 0（2026-09-20 血案）。
        GL33.glTexImage3D(GL33.GL_TEXTURE_3D, 0, GL33.GL_R8, w.SX, w.SY, w.SZ, 0,
                GL33.GL_RED, GL33.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        this.shadowOccArr = new byte[w.SX * w.SY * w.SZ];
        this.shadowOccBuf = org.lwjgl.system.MemoryUtil.memAlloc(w.SX * w.SY * w.SZ);
        GL33.glBindTexture(GL33.GL_TEXTURE_3D, 0);
        this.shadowOccDirty = true;
    }

    /**
     * 重传 3D 占用纹理。世界编辑（挖/放）与窗口滑动都会改变 {@code mat}，两者都由
     * {@code world.lightDirty} 标脏（{@code editBlock} 与窗口重建都置位）→ 这里同步跟随。
     *
     * <p>整张重传约 2.87 MB（~1 ms）。刻意<b>不做</b>局部 {@code glTexSubImage3D} 的区域追踪：
     * 那要维护一份"哪些格变过"的并行状态，正是零漂移纪律里最该避免的冗余状态源，
     * 而编辑是玩家手动的低频操作，省下的那点带宽换不来这个风险。
     */
    private void uploadShadowVolume() {
        if (this.shadowTex3D < 0 || this.shadowOccBuf == null || this.shadowOccArr == null) {
            this.shadowOccDirty = false;
            return;
        }
        core.world.World w = this.sim.world;
        int n = w.SX * w.SY * w.SZ;
        if (this.shadowOccArr.length != n) {          // 世界尺寸变了 → 重建存储与缓冲
            org.lwjgl.system.MemoryUtil.memFree(this.shadowOccBuf);
            this.shadowOccArr = new byte[n];
            this.shadowOccBuf = org.lwjgl.system.MemoryUtil.memAlloc(n);
            GL33.glBindTexture(GL33.GL_TEXTURE_3D, this.shadowTex3D);
            GL33.glTexImage3D(GL33.GL_TEXTURE_3D, 0, GL33.GL_R8, w.SX, w.SY, w.SZ, 0,
                    GL33.GL_RED, GL33.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        }
        VoxelShadow.fillOcc(w, this.shadowOccArr);
        this.shadowOccBuf.clear();
        this.shadowOccBuf.put(this.shadowOccArr).flip();
        GL33.glBindTexture(GL33.GL_TEXTURE_3D, this.shadowTex3D);
        // 单字节/texel 的数据必须显式对齐到 1：默认 UNPACK_ALIGNMENT=4 在行宽不是 4 的倍数时
        // 会按行错位读取（这里的行宽 = SX = 160，恰好是 4 的倍数所以碰巧没事 —— 但世界尺寸
        // 一旦改成非 4 倍数就会静默错位，不能靠"碰巧"）。
        GL33.glPixelStorei(GL33.GL_UNPACK_ALIGNMENT, 1);
        GL33.glTexSubImage3D(GL33.GL_TEXTURE_3D, 0, 0, 0, 0, w.SX, w.SY, w.SZ,
                GL33.GL_RED, GL33.GL_UNSIGNED_BYTE, this.shadowOccBuf);
        this.shadowOccDirty = false;
        if (!this.shadowLogOnce) {
            this.shadowLogOnce = true;
            // 运行时不变量（同 [ATLAS] FAIL 的纪律）：把纹理从 GPU 读回来与 CPU 数组逐字节比对。
            // 存在理由：一张"上传失败但全绿"的纹理不会有任何报错，只会让阴影恒等于"全亮"，
            // 而画面看起来只是"没有阴影"（不像坏掉），极难判伪。
            int nz = 0;
            for (byte b : this.shadowOccArr) if (b != 0) nz++;
            java.nio.ByteBuffer back = org.lwjgl.system.MemoryUtil.memAlloc(n);
            GL33.glGetTexImage(GL33.GL_TEXTURE_3D, 0, GL33.GL_RED, GL33.GL_UNSIGNED_BYTE, back);
            int diff = 0;
            for (int i = 0; i < n; i++) if (back.get(i) != this.shadowOccArr[i]) diff++;
            org.lwjgl.system.MemoryUtil.memFree(back);
            if (diff != 0) {
                System.err.println("[SHADOW] FAIL 3D 占用纹理与 CPU 数组不一致（diff=" + diff + "/" + n
                        + "）→ 阴影会静默失效。检查上传缓冲是否 DIRECT / GL_UNPACK_ALIGNMENT。");
            }
            GameLog.log("SHADOW", "tex=" + this.shadowTex3D + " occ=" + nz + "/" + n
                    + " readbackDiff=" + diff + " size=" + w.SX + "x" + w.SY + "x" + w.SZ
                    + " strength=" + this.shadowStrength);
        }
        GL33.glBindTexture(GL33.GL_TEXTURE_3D, 0);
    }

    // ---------- 泰拉瑞亚缺口②：选择性泛光（bloom）----------
    // 思路（克制、零漂移）：基础场景照旧画到默认帧缓冲；本段只在它「之上」叠加发光源的辉光。
    // 发光源（LAMP/FIRE/GOLD）有独立网格（Chunk.vaoE），渲染进全分辨率 FBO → 亮部阈值 → 半分辨率
    // 高斯模糊（横/纵 ping-pong）→ 加性混合回默认帧缓冲。bloomReady 为假则整段跳过（基础画面零影响）。

    private static int makeColorTex(int w, int h) {
        int t = GL33.glGenTextures();
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, t);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_LINEAR);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_LINEAR);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, GL33.GL_RGBA, w, h, 0, GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        return t;
    }

    // ---------- §18 自动曝光（眼适应）----------
    /** 亮度计：默认帧缓冲 blit 降采样到 LUM_W×LUM_H 的小 FBO，再 glReadPixels 读回求平均。 */
    private int lumFBO = -1, lumTex = -1;
    private java.nio.ByteBuffer lumBuf;
    /** 平滑后的曝光（乘在世界/天空的色调映射之前，=1 表示不改）。 */
    private float exposureValue = 1f;
    /** 非 QA 时每 8 帧才测一次亮度（readback 会打断流水线，逐帧测不值）。 */
    private int exposureTick = 0;
    /** QA：曝光是否已锁存（锁存后不再更新 ⇒ 截图可复现）。 */
    private boolean exposureLatched = false;
    /** 锁存发生的帧号（截图条件要多等 1 帧，确保被拍那帧用的就是锁存值）。 */
    private int exposureLatchedFrame = 0;
    private static final int LUM_W = 6, LUM_H = 4;
    /**
     * 目标显示域平均亮度（标定值）。取法：实测正午晴天的帧缓冲平均亮度 ≈ **0.55**
     * （`-Dbw.explog=1` 读出：正午 0.583 / 黎明 0.445 / 黄昏 0.343 / 午夜 0.283 / 密林黄昏 0.091）。
     * 于是**正午 exposure ≈ 1.0**（常规观感不动），越暗才越提亮。
     */
    private static final float EXPOSURE_TARGET = 0.55f;
    /** 适应强度指数：{@code 0.5} = 取平方根 ⇒ 温和（不把夜景一把拉到白天）。 */
    private static final float EXPOSURE_EXP = 0.5f;
    /** 适应范围（乘数）。**刻意夹得窄**：这是"补光/压光"，不是"重调色调"——
     *  实测最暗的密林场景裸曝光只到 0.091，若不夹就会拉到 6 倍、把夜景彻底洗白。 */
    private static final float EXPOSURE_MIN = 0.80f, EXPOSURE_MAX = 1.35f;

    /**
     * §18 自动曝光（eye adaptation）强度：{@code 0} = 关（uExposure 恒 1 ⇒ 与改动前相同），默认 1 = 开。
     *
     * <p><b>为什么</b>：洞里↔地表进出时人眼会适应（UE 的 Auto Exposure）。缺了它，
     * 出洞那一下过曝白一片、进洞那一下黑到看不见；而本作此前只有"夜色下限 0.32"这种全局兜底，
     * 无法按"眼前有多亮"自适应。
     *
     * <p><b>怎么做</b>：blit 降采样默认帧缓冲（顺带 resolve MSAA）→ 读 24 像素 →
     * Rec.709 亮度平均 → 目标曝光 = TARGET/lum（夹 [0.55,1.8]）→ 平滑。
     * <b>QA 下瞬时收敛</b>（不做跨帧记忆）⇒ 截图仍逐字节可复现（与泛光记忆/失焦遮罩同一纪律）。
     *
     * <p><b>诚实的边界</b>：这是**基于帧缓冲实测亮度**的实现，不是 UE 的直方图自动曝光；
     * 采样点只有 24 个 ⇒ 适合"整体明暗适应"，不做局部/中心加权测光。
     */
    private final float exposureAmt = readFloatPropOn("bw.exposure", 1f);
    /**
     * QA 用的**常数曝光**（`-Dbw.expofixed=<乘数>`）。设了就跳过自适应、直接用这个值 ——
     * 这是自动曝光唯一能与"逐字节 A/B"共存的形态（见 {@link #updateAutoExposure} 的契约冲突说明）。
     */
    private final float exposureFixed = readFloatPropRaw("bw.expofixed");

    // ---------- §19 god-ray（屏幕空间体积光）----------
    /** 源（场景拷贝）：**半分辨率** —— 见 19.2 的教训：1/4 分辨率会把太阳盘"平均掉"，只剩大片的亮天空。 */
    private int godFBO = -1, godTex = -1;
    /** 结果（光束）：1/4 分辨率 —— 径向模糊是低频信号，便宜 16 倍。 */
    private int godOutFBO = -1, godOutTex = -1;
    private int godProgram = -1, uGodSrc = -1, uGodSun = -1, uGodDensity = -1, uGodDecay = -1, uGodWeight = -1, uGodThresh = -1, uGodConst = -1;
    private int uGodFloor = -1, uGodPow = -1;
    /** god-ray 增益：{@code 0} = 关（整段跳过 ⇒ 与改动前一致），默认 1 = 开。 */
    private final float godrayGain = readFloatPropOn("bw.godray", 1f);
    /** quarter-res：光轴是低频信号，四分之一分辨率足够（也顺带便宜 16 倍）。 */
    private static final int GODRAY_DIV = 4;
    /**
     * 源（场景拷贝）的分辨率除数 = 2（半分辨率）。
     *
     * <p><b>为什么源不能也用 1/4</b>（实测教训）：径向模糊的"光源掩码"取自这张拷贝，而 1/4 分辨率下
     * 一个 4×4 盒平均会把**太阳盘**（全分辨率约 9px）压到阈值以下 ⇒ 掩码里只剩**大片亮天空**
     * ⇒ 结果是"整屏蒙一层雾"而不是光束；而把阈值抬到能滤掉天空（0.85）时又什么都滤没了——
     * 实测 0.72→meanAbs 4.79（雾），0.85→0.18（几乎无）。
     * 半分辨率能保住盘面峰值，掩码才真正锁得住"光源"。
     */
    private static final int GODRAY_SRC_DIV = 2;
    /** 径向采样的总跨度（1.0 ≈ 一直采到太阳位置）与逐样本衰减。 */
    private static final float GODRAY_DENSITY = 0.62f, GODRAY_DECAY = 0.94f;
    /** 掩码地板：低于它的像素完全不算光源（剩下的用幂次连续衰减）。默认 0.60（实测显示域里亮天空 ≈0.72~0.85、太阳盘 =1.0）。 */
    private static final float GODRAY_THRESH = 0.60f;
    /** QA 探针：覆盖掩码地板（`-Dbw.godthresh=0` ⇒ 几乎所有像素都算光源）。 */
    private final float godThresh = readFloatPropOr("bw.godthresh", GODRAY_THRESH);
    /** 掩码幂次（`-Dbw.godpow`）：越大越只保留最亮端（6 ⇒ 0.72→0.0007 / 0.9→0.18 / 1.0→1.0）。 */
    private final float godPow = readFloatPropOr("bw.godpow", 6f);
    /** QA 二分探针：`-Dbw.godconst=<v>` ⇒ 该 pass 直接输出常量（绕过纹理/掩码）。 */
    private final float godProbeConst = readFloatPropRaw("bw.godconst");

    private void initAutoExposure() {
        try {
            this.lumFBO = GL33.glGenFramebuffers();
            this.lumTex = makeColorTex(LUM_W, LUM_H);
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, this.lumFBO);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0,
                    GL33.GL_TEXTURE_2D, this.lumTex, 0);
            boolean ok = GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) == GL33.GL_FRAMEBUFFER_COMPLETE;
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, 0);
            if (!ok) this.lumFBO = -1;
            this.lumBuf = org.lwjgl.system.MemoryUtil.memAlloc(LUM_W * LUM_H * 3);
            this.logPhase("auto-exposure init fbo=" + this.lumFBO + " tex=" + this.lumTex + " amt=" + this.exposureAmt);
        } catch (Throwable t) {
            this.lumFBO = -1;   // 任何失败 → 降级为"曝光恒 1"（画面回到改动前）
        }
    }

    /**
     * 测光 + 适应。必须在**世界+泛光画完之后、HUD 之前**调用：否则 HUD 的亮块（血条/白字）
     * 会把测光带偏（HUD 是 UI，不该参与"场景有多亮"）。
     */
    private void updateAutoExposure() {
        // ⚠️ 契约冲突（2026-09-29 实测）：自动曝光把**画面自己**反馈进一个**全局乘子** ⇒
        // 帧缓冲里任何 ≤1 LSB 的抖动都会被它放大成可见差异（实测：默认开启时 dusk/dawn/midnight
        // 两次截图不一致，而 noon 一致）。这与"无头截图必须逐字节可比"的仪器契约**根本冲突**。
        // 因此：**QA 下默认不启用自适应**（uExposure 恒 1），要对比就用 `-Dbw.expofixed=<乘数>`
        // 给一个**常数**曝光 —— 既保留 A/B 能力，又不引入反馈。自适应本身只在运行时生效，
        // 其验收只能靠实机观察（诚实登记，不假装仪器测过）。
        if (this.qaFrozen) {
            this.exposureValue = (this.exposureFixed > 0f) ? this.exposureFixed : 1f;
            this.exposureLatched = true;
            return;
        }
        if (this.exposureAmt <= 0f || this.lumFBO == -1 || this.lumBuf == null) {
            this.exposureValue = 1f;
            this.exposureLatched = true;      // 关掉/降级 ⇒ 立即"已定"，不阻塞截图
            return;
        }
        if ((this.exposureTick++ & 7) != 0) {
            return;   // 每 8 帧测一次（readback 会打断流水线，逐帧测不值）
        }
        try {
            GL33.glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, 0);
            GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, this.lumFBO);
            GL33.glBlitFramebuffer(0, 0, this.W, this.H, 0, 0, LUM_W, LUM_H,
                    GL33.GL_COLOR_BUFFER_BIT, GL33.GL_LINEAR);
            GL33.glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, this.lumFBO);
            GL33.glReadBuffer(GL33.GL_COLOR_ATTACHMENT0);
            this.lumBuf.clear();
            GL33.glReadPixels(0, 0, LUM_W, LUM_H, GL33.GL_RGB, GL33.GL_UNSIGNED_BYTE, this.lumBuf);
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, 0);
        } catch (Throwable t) {
            this.exposureValue = 1f;
            return;
        }
        float sum = 0f;
        for (int i = 0; i < LUM_W * LUM_H; i++) {
            int r = this.lumBuf.get(i * 3) & 255;
            int g = this.lumBuf.get(i * 3 + 1) & 255;
            int b = this.lumBuf.get(i * 3 + 2) & 255;
            sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;   // Rec.709 亮度
        }
        float lum = sum / (LUM_W * LUM_H);
        // 幂次形式（而不是直接 T/lum）：把"亮度差 → 曝光差"的映射压平，避免过冲震荡。
        float want = (float) Math.pow(EXPOSURE_TARGET / Math.max(0.04f, lum), EXPOSURE_EXP);
        if (want < EXPOSURE_MIN) want = EXPOSURE_MIN;
        else if (want > EXPOSURE_MAX) want = EXPOSURE_MAX;
        if (System.getProperty("bw.explog") != null) {
            this.logPhase(String.format(java.util.Locale.US, "exposure lum=%.4f want=%.4f cur=%.4f", lum, want, this.exposureValue));
        }
        // 只有运行时才会走到这里（QA 在上面的早退里已定值）⇒ 这里只有"眼适应"一条路径。
        this.exposureValue += (want - this.exposureValue) * 0.06f;        // ~1s @60fps
    }

    private void initBloom() {
        try {
            bloomFBO = GL33.glGenFramebuffers();
            bloomTex = makeColorTex(Math.max(1, this.W), Math.max(1, this.H));
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, bloomFBO);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, bloomTex, 0);
            boolean bloomFboOk = GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) == GL33.GL_FRAMEBUFFER_COMPLETE;
            int bw = Math.max(1, this.W / 2), bh = Math.max(1, this.H / 2);
            brightFBO = GL33.glGenFramebuffers(); brightTex = makeColorTex(bw, bh);
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, brightFBO);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, brightTex, 0);
            boolean brightFboOk = GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) == GL33.GL_FRAMEBUFFER_COMPLETE;
            blurFBO = GL33.glGenFramebuffers(); blurTex = makeColorTex(bw, bh);
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, blurFBO);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, blurTex, 0);
            boolean blurFboOk = GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) == GL33.GL_FRAMEBUFFER_COMPLETE;
            // 时间累积泛光：半分辨率 ping-pong 双缓冲（A 读 B 写 / B 读 A 写，每帧交换）。
            glowFBOA = GL33.glGenFramebuffers(); glowTexA = makeColorTex(bw, bh);
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, glowFBOA);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, glowTexA, 0);
            boolean glowAOk = GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) == GL33.GL_FRAMEBUFFER_COMPLETE;
            glowFBOB = GL33.glGenFramebuffers(); glowTexB = makeColorTex(bw, bh);
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, glowFBOB);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0, GL33.GL_TEXTURE_2D, glowTexB, 0);
            boolean glowBOk = GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) == GL33.GL_FRAMEBUFFER_COMPLETE;
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, 0);

            emissiveProgram = Game.makeProgram(BLOOM_EMISSIVE_VS, BLOOM_EMISSIVE_FS);
            uEmissiveVP = GL33.glGetUniformLocation(emissiveProgram, "uVP");
            uEmissiveChunkShift = GL33.glGetUniformLocation(emissiveProgram, "uChunkShift");
            brightProgram = Game.makeProgram(BLOOM_QUAD_VS, BLOOM_BRIGHT_FS);
            uBrightSrc = GL33.glGetUniformLocation(brightProgram, "uSrc");
            uBrightThresh = GL33.glGetUniformLocation(brightProgram, "uThresh");
            blurProgram = Game.makeProgram(BLOOM_QUAD_VS, BLOOM_BLUR_FS);
            uBlurSrc = GL33.glGetUniformLocation(blurProgram, "uSrc");
            uBlurDir = GL33.glGetUniformLocation(blurProgram, "uDir");
            compositeProgram = Game.makeProgram(BLOOM_QUAD_VS, BLOOM_COMP_FS);
            uCompositeSrc = GL33.glGetUniformLocation(compositeProgram, "uSrc");
            uCompositeIntensity = GL33.glGetUniformLocation(compositeProgram, "uIntensity");
            glowProgram = Game.makeProgram(BLOOM_QUAD_VS, BLOOM_GLOW_FS);
            uGlowSrc = GL33.glGetUniformLocation(glowProgram, "uSrc");
            uGlowPrev = GL33.glGetUniformLocation(glowProgram, "uPrev");
            uGlowTexel = GL33.glGetUniformLocation(glowProgram, "uTexel");
            uGlowPass = GL33.glGetUniformLocation(glowProgram, "uPass");
            uGlowGain = GL33.glGetUniformLocation(glowProgram, "uGain");

            bloomReady = bloomFboOk && brightFboOk && blurFboOk
                    && emissiveProgram > 0 && brightProgram > 0 && blurProgram > 0 && compositeProgram > 0;
            // 时间累积是 bloom 之上的可选增强：FBO/程序任一失败则单独降级（基础泛光照旧，画面零影响）。
            glowReady = glowAOk && glowBOk && glowProgram > 0 && bloomReady;
        } catch (Throwable t) {
            bloomReady = false;
            glowReady = false;
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, 0);
        }
    }

    private void resizeBloom() {
        if (bloomFBO == -1) return;
        try {
            GL33.glDeleteFramebuffers(bloomFBO); GL33.glDeleteTextures(bloomTex);
            GL33.glDeleteFramebuffers(brightFBO); GL33.glDeleteTextures(brightTex);
            GL33.glDeleteFramebuffers(blurFBO); GL33.glDeleteTextures(blurTex);
            if (glowFBOA != -1) { GL33.glDeleteFramebuffers(glowFBOA); GL33.glDeleteTextures(glowTexA); }
            if (glowFBOB != -1) { GL33.glDeleteFramebuffers(glowFBOB); GL33.glDeleteTextures(glowTexB); }
            initBloom();
        } catch (Throwable t) { bloomReady = false; glowReady = false; }
    }

    private void bindTarget(int fbo, int w, int h) {
        GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, fbo);
        GL33.glViewport(0, 0, w, h);
    }

    /** §19：god-ray 的两个目标（源=半分辨率场景拷贝；结果=1/4 分辨率光束）。懒分配 + 尺寸变了就重建。 */
    private void ensureGodTargets(int sw, int sh, int ow, int oh) {
        if (this.godTex != -1 && this.godW == sw && this.godH == sh && this.godOutW == ow && this.godOutH == oh) return;
        if (this.godTex != -1) {
            GL33.glDeleteFramebuffers(this.godFBO);
            GL33.glDeleteTextures(this.godTex);
        }
        if (this.godOutFBO != -1) {
            GL33.glDeleteFramebuffers(this.godOutFBO);
            GL33.glDeleteTextures(this.godOutTex);
        }
        this.godFBO = -1;
        this.godOutFBO = -1;
        try {
            this.godFBO = GL33.glGenFramebuffers();
            this.godTex = makeColorTex(sw, sh);
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, this.godFBO);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0,
                    GL33.GL_TEXTURE_2D, this.godTex, 0);
            boolean ok1 = GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) == GL33.GL_FRAMEBUFFER_COMPLETE;

            this.godOutFBO = GL33.glGenFramebuffers();
            this.godOutTex = makeColorTex(ow, oh);
            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, this.godOutFBO);
            GL33.glFramebufferTexture2D(GL33.GL_FRAMEBUFFER, GL33.GL_COLOR_ATTACHMENT0,
                    GL33.GL_TEXTURE_2D, this.godOutTex, 0);
            boolean ok2 = GL33.glCheckFramebufferStatus(GL33.GL_FRAMEBUFFER) == GL33.GL_FRAMEBUFFER_COMPLETE;

            GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, 0);
            if (!ok1 || !ok2) { this.godFBO = -1; this.godOutFBO = -1; return; }
            this.godW = sw; this.godH = sh; this.godOutW = ow; this.godOutH = oh;
        } catch (Throwable t) {
            this.godFBO = -1;
            this.godOutFBO = -1;
        }
    }

    private int godW = 0, godH = 0, godOutW = 0, godOutH = 0;

    /**
     * §19 画 god-ray。**必须紧挨在世界/实体之后、泛光之前**：
     * 泛光之后画的话，灯火/岩浆的辉光会被掩码当成"光源"拉成假光束（且方向被拽向太阳）。
     * 太阳出画、在地平线下、或在相机背后时**整段跳过**（⇒ 关掉与"没有这一层"等价）。
     */
    private void drawGodRays() {
        if (this.godrayGain <= 0f || this.godProgram <= 0) return;
        // ⚠️ 不能照抄 §17 的"celSun 在上就用 celSun" —— 那只保证"哪个天体在天上"，**不保证在镜头里**。
        // 实测黄昏朝 yaw=180 时"在天上"的那个在**相机背后**（`w<0`）⇒ 光轴永远画不出来。
        // 正确判据：在**地平线以上**的天体里，取**与相机前向最同向**的那个（= 屏幕上能看到光源的那个）。
        boolean dbg = System.getProperty("bw.godlog") != null;
        float[] fwd = { -this.view.m02(), -this.view.m12(), -this.view.m22() };
        float[] d = null;
        String who = "";
        for (int i = 0; i < 2; i++) {
            float[] c = (i == 0) ? this.celSun : this.celMoon;
            if (c[1] <= 0.02f) continue;                                   // 地平线下 → 不是可见光源
            float dt = c[0] * fwd[0] + c[1] * fwd[1] + c[2] * fwd[2];
            if (d == null || dt > (d[0] * fwd[0] + d[1] * fwd[1] + d[2] * fwd[2])) {
                d = c;
                who = (i == 0) ? "sun" : "moon";
            }
        }
        if (d == null) {
            if (dbg) this.logPhase("godray SKIP no body above horizon");
            return;
        }
        // world 方向 → view → clip，取屏幕 UV（用 JOML 自己算，避免手推符号出错）
        Vector4f t = new Vector4f(d[0], d[1], d[2], 0.0f);
        this.view.transform(t);
        this.proj.transform(t);
        if (t.w <= 1.0e-4f) {
            if (dbg) this.logPhase(String.format(java.util.Locale.US, "godray SKIP behind body=%s w=%.5f", who, t.w));
            return;                     // 相机背后
        }
        float su = t.x / t.w * 0.5f + 0.5f;
        float sv = t.y / t.w * 0.5f + 0.5f;
        if (dbg) this.logPhase(String.format(java.util.Locale.US,
                "godray body=%s dY=%.4f uv=(%.3f,%.3f) w=%.4f gain=%.3f",
                who, d[1], su, sv, t.w, this.godrayGain));
        if (su < -0.25f || su > 1.25f || sv < -0.25f || sv > 1.25f) return;   // 远出画（留一点余量给屏幕外太阳的光晕）
        int sw = Math.max(1, this.W / GODRAY_SRC_DIV), sh = Math.max(1, this.H / GODRAY_SRC_DIV);
        int ow = Math.max(1, this.W / GODRAY_DIV), oh = Math.max(1, this.H / GODRAY_DIV);
        ensureGodTargets(sw, sh, ow, oh);
        if (this.godFBO == -1 || this.godOutFBO == -1) return;
        // 1) 默认帧缓冲（世界+实体，尚未泛光）→ **半分辨率**场景拷贝
        GL33.glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, 0);
        GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, this.godFBO);
        GL33.glBlitFramebuffer(0, 0, this.W, this.H, 0, 0, sw, sh,
                GL33.GL_COLOR_BUFFER_BIT, GL33.GL_LINEAR);
        GL33.glBindFramebuffer(GL33.GL_READ_FRAMEBUFFER, 0);
        GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, 0);
        // 2) 径向模糊：半分辨率拷贝 → 1/4 分辨率光束
        bindTarget(this.godOutFBO, ow, oh);
        GL33.glDisable(GL33.GL_DEPTH_TEST);
        GL33.glUseProgram(this.godProgram);
        GL33.glActiveTexture(GL33.GL_TEXTURE0);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, this.godTex);
        if (this.uGodSrc != -1) GL33.glUniform1i(this.uGodSrc, 0);
        if (this.uGodSun != -1) GL33.glUniform2f(this.uGodSun, su, sv);
        if (this.uGodDensity != -1) GL33.glUniform1f(this.uGodDensity, GODRAY_DENSITY);
        if (this.uGodDecay != -1) GL33.glUniform1f(this.uGodDecay, GODRAY_DECAY);
        if (this.uGodThresh != -1) GL33.glUniform1f(this.uGodThresh, this.godThresh);   // 旧阈值（shader 已不用，留位防 -1）
        if (this.uGodFloor != -1) GL33.glUniform1f(this.uGodFloor, this.godThresh);
        if (this.uGodPow != -1) GL33.glUniform1f(this.uGodPow, this.godPow);
        if (this.uGodConst != -1) GL33.glUniform1f(this.uGodConst, this.godProbeConst);
        if (this.uGodWeight != -1) GL33.glUniform1f(this.uGodWeight, 1.0f);   // 强度改由合成段 uIntensity 控制
        drawFullscreen();
        // 3) 加性合成回默认帧缓冲（复用泛光的 composite 程序：uSrc × uIntensity）
        bindTarget(0, this.W, this.H);
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFunc(GL33.GL_ONE, GL33.GL_ONE);
        GL33.glUseProgram(this.compositeProgram);
        GL33.glActiveTexture(GL33.GL_TEXTURE0);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, this.godOutTex);
        if (this.uCompositeSrc != -1) GL33.glUniform1i(this.uCompositeSrc, 0);
        if (this.uCompositeIntensity != -1) GL33.glUniform1f(this.uCompositeIntensity, this.godrayGain);
        drawFullscreen();
        GL33.glActiveTexture(GL33.GL_TEXTURE0);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, 0);
        GL33.glDisable(GL33.GL_BLEND);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        if (dbg) {
            this.logPhase("godray done glErr=" + Integer.toHexString(GL33.glGetError())
                    + " src=" + sw + "x" + sh + " out=" + ow + "x" + oh);
        }
    }

    private void initGodRays() {
        try {
            this.godProgram = Game.makeProgram(BLOOM_QUAD_VS, GODRAY_FS);
            this.uGodSrc = GL33.glGetUniformLocation(this.godProgram, "uSrc");
            this.uGodSun = GL33.glGetUniformLocation(this.godProgram, "uSun");
            this.uGodDensity = GL33.glGetUniformLocation(this.godProgram, "uDensity");
            this.uGodDecay = GL33.glGetUniformLocation(this.godProgram, "uDecay");
            this.uGodWeight = GL33.glGetUniformLocation(this.godProgram, "uWeight");
            this.uGodThresh = GL33.glGetUniformLocation(this.godProgram, "uThresh");
            this.uGodConst = GL33.glGetUniformLocation(this.godProgram, "uConst");
            this.uGodFloor = GL33.glGetUniformLocation(this.godProgram, "uFloor");
            this.uGodPow = GL33.glGetUniformLocation(this.godProgram, "uPow");
            if (this.godProgram <= 0) this.godProgram = -1;
            this.logPhase("god-ray program=" + this.godProgram + " gain=" + this.godrayGain);
        } catch (Throwable t) {
            this.godProgram = -1;
        }
    }

    private void drawFullscreen() {
        GL33.glBindVertexArray(this.skyVAO);
        GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, 3);
        GL33.glBindVertexArray(0);
    }

    /** 选择性泛光：发光源网格 → 全分辨率 FBO → 亮部阈值 → 半分辨率模糊 → 加性合成到默认帧缓冲。 */
    private void drawBloom(Matrix4f vp) {
        if (!bloomReady) return;
        int bw = Math.max(1, this.W / 2), bh = Math.max(1, this.H / 2);
        // 1) 发光源网格 → bloomFBO（全分辨率，关深度测试：发光源多在空气中，近似可接受）
        bindTarget(bloomFBO, this.W, this.H);
        GL33.glClearColor(0f, 0f, 0f, 1f);
        GL33.glClear(GL33.GL_COLOR_BUFFER_BIT);
        GL33.glDisable(GL33.GL_DEPTH_TEST);
        GL33.glUseProgram(emissiveProgram);
        if (uEmissiveVP != -1) GL33.glUniformMatrix4fv(uEmissiveVP, false, this.vpBuf);
        for (int n = 0; n < this.chunkX; ++n)
            for (int i = 0; i < this.chunkZ; ++i) {
                Chunk c = this.chunks[n][i];
                if (c.vaoE != 0 && c.faceCountE > 0) {
                    GL33.glBindVertexArray(c.vaoE);
                    // 第三十八批：发光源网格与主网格同源同坐标 ⇒ 同一个网格帧偏移。
                    if (uEmissiveChunkShift != -1) GL33.glUniform2f(uEmissiveChunkShift, (float)c.meshOffX, (float)c.meshOffZ);
                    // 发光网格索引与主网格一致：每四边形 6 个三角形索引 → 用 GL_TRIANGLES
                    GL33.glDrawElements(GL33.GL_TRIANGLES, c.faceCountE * 6, GL33.GL_UNSIGNED_INT, 0L);
                }
            }
        GL33.glBindVertexArray(0);
        // 2) 亮部阈值：bloomFBO(全) → brightFBO(半)
        bindTarget(brightFBO, bw, bh);
        GL33.glUseProgram(brightProgram);
        GL33.glActiveTexture(GL33.GL_TEXTURE0); GL33.glBindTexture(GL33.GL_TEXTURE_2D, bloomTex);
        if (uBrightSrc != -1) GL33.glUniform1i(uBrightSrc, 0);
        if (uBrightThresh != -1) GL33.glUniform1f(uBrightThresh, BLOOM_THRESHOLD);
        drawFullscreen();
        // 3) 高斯模糊横：brightFBO → blurFBO
        bindTarget(blurFBO, bw, bh);
        GL33.glUseProgram(blurProgram);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, brightTex);
        if (uBlurSrc != -1) GL33.glUniform1i(uBlurSrc, 0);
        if (uBlurDir != -1) GL33.glUniform2f(uBlurDir, 1.0f / bw, 0f);
        drawFullscreen();
        // 4) 高斯模糊纵：blurFBO → brightFBO
        bindTarget(brightFBO, bw, bh);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, blurTex);
        if (uBlurDir != -1) GL33.glUniform2f(uBlurDir, 0f, 1.0f / bh);
        drawFullscreen();
        // 4b) 时间累积泛光（Noita post_glow1/2）：上一步的模糊结果作为「本帧新样本」喂进 glow 链，
        //     与上一帧的 glow 缓冲做一轮横向 + 一轮纵向的一维模糊衰减，得到带记忆的辉光。
        //     与 4) 的 9-tap 模糊不同：这条链帧间有记忆 → 火光有拖尾/余晖（"火焰感"）。
        //     glowReady 为假则整段跳过，走旧的 4)-5) 路径（画面回到 2026-09-21 之前的行为）。
        //
        //     QA 冻结（{@code -Dbw.snap}）：时间累积是跨帧的，而无头截图在"加载稳态"触发 ——
        //     两次运行累积的帧数不一定相同 → 截图不可复现（实测 93% 像素不同，与"旧版 95% 不同"同型）。
        //     与"冻结仿真 / 冻结演出时钟 / 冻结失焦遮罩"同一纪律：**QA 下整段跳过**，
        //     走无记忆的旧路径（等价于单帧采样）→ 逐字节可复现。运行时（非 QA）保留完整时间记忆。
        int compositeTex = brightTex;
        if (this.glowReady && !this.qaFrozen) {
            float texelX = 1.0f / bw, texelY = 1.0f / bh;
            GL33.glUseProgram(glowProgram);
            GL33.glActiveTexture(GL33.GL_TEXTURE0); GL33.glBindTexture(GL33.GL_TEXTURE_2D, brightTex);
            if (uGlowSrc != -1) GL33.glUniform1i(uGlowSrc, 0);
            GL33.glActiveTexture(GL33.GL_TEXTURE1);
            if (uGlowTexel != -1) GL33.glUniform2f(uGlowTexel, texelX, texelY);
            if (uGlowPass != -1) GL33.glUniform1f(uGlowPass, 0f);
            if (uGlowGain != -1) GL33.glUniform1f(uGlowGain, Bloom.GLOW_DIRECT_GAIN);
            // 第一轮（横扫）：读 B → 写 A
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, glowTexB);
            if (uGlowPrev != -1) GL33.glUniform1i(uGlowPrev, 1);
            bindTarget(glowFBOA, bw, bh);
            drawFullscreen();
            // 第二轮（纵扫）：读 A → 写 B
            GL33.glActiveTexture(GL33.GL_TEXTURE0); GL33.glBindTexture(GL33.GL_TEXTURE_2D, glowTexA);
            GL33.glActiveTexture(GL33.GL_TEXTURE1); GL33.glBindTexture(GL33.GL_TEXTURE_2D, glowTexA);
            if (uGlowPass != -1) GL33.glUniform1f(uGlowPass, 1f);
            if (uGlowGain != -1) GL33.glUniform1f(uGlowGain, Bloom.GLOW_DIRECT_GAIN);
            bindTarget(glowFBOB, bw, bh);
            drawFullscreen();
            GL33.glActiveTexture(GL33.GL_TEXTURE0);
            compositeTex = glowTexB;                              // 累积结果留给第 5 步合成
        }
        // 5) 加性合成：累积辉光（或旧模糊结果）→ 默认帧缓冲
        GL33.glBindFramebuffer(GL33.GL_FRAMEBUFFER, 0);
        GL33.glViewport(0, 0, this.W, this.H);
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFunc(GL33.GL_ONE, GL33.GL_ONE);
        GL33.glUseProgram(compositeProgram);
        GL33.glActiveTexture(GL33.GL_TEXTURE0); GL33.glBindTexture(GL33.GL_TEXTURE_2D, compositeTex);
        if (uCompositeSrc != -1) GL33.glUniform1i(uCompositeSrc, 0);
        if (uCompositeIntensity != -1) GL33.glUniform1f(uCompositeIntensity, BLOOM_INTENSITY);
        drawFullscreen();
        GL33.glDisable(GL33.GL_BLEND);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        GL33.glBindVertexArray(0);
    }

    private void initBuffers() {
        int n;
        World world = this.sim.world;
        Chunk.USE_GREEDY = false;
        this.chunkX = world.SX / 16;
        this.chunkZ = world.SZ / 16;
        this.chunks = new Chunk[this.chunkX][this.chunkZ];
        int n2 = 0;
        for (n = 0; n < this.chunkX; ++n) {
            for (int i = 0; i < this.chunkZ; ++i) {
                Chunk chunk = new Chunk(n, i);
                chunk.init();
                if (chunk.vao == 0 || chunk.vbo == 0) {
                    this.logPhase("WARN: chunk(" + n + "," + i + ") vao/vbo=0");
                }
                chunk.rebuild(world);
                n2 += chunk.faceCount;
                this.chunks[n][i] = chunk;
            }
        }
        n = GL33.glGetError();
        this.logPhase("initBuffers done totalFaces=" + n2 + "  glErr=" + Integer.toHexString(n));
        // 第三十二批 A2：全窗首次构建仍是**同步**的（启动一次性成本，且此刻还没进主循环）。
        // 之后的主循环里，脏块走 MeshBuilder 的 worker 池 + 每帧上传配额。
        this.meshBuilder = new MeshBuilder(MESH_THREADS);
        this.logPhase("MeshBuilder started threads=" + MESH_THREADS + " maxInflight=" + MESH_MAX_INFLIGHT);
    }

    /**
     * 第三十二批 A2：每帧的网格流水线 —— <b>提交脏块 + 应用已完成结果</b>（取代原先在主线程内联的
     * "每帧重建 N 块"）。必须在 {@link #drawFrame()} <b>之前</b>调用，这样本帧刚上传的块当帧就能画出来。
     *
     * <p>三步：① epoch 维护（世界身份/窗口原点变化 ⇒ 在飞结果作废）；② 把脏块按"近者优先"提交给 worker；
     * ③ 从完成队列取回并上传（GL 只能在主线程，这一步是主线程唯一的网格成本）。
     *
     * <p>为什么不在这里 {@code break} 而是靠配额：与旧实现同一条教训 —— 一个 {@code break} 只跳出内层循环，
     * 外层还会接着扫并继续提交，配额就失效了。
     */
    private void updateMeshes() {
        if (this.meshBuilder == null) return;
        World world = this.sim.world;
        // ① 世界身份 / 窗口原点的变化维护。两种情况的处置**不同**（第三十八批）：
        //    · 换世界（meshWorld 变了）或窗口原点跳得比一屏还远（零重叠，如初次定位 / 传送）：
        //      没有"可复用的重叠区" ⇒ 块对象全部按当前槽重新指派、偏移归零，并全窗标脏（老行为）。
        //    · 常规平移（|d| < 一屏）：留在窗内的块**网格原样有效**（内容一个字节都没变，只是整个
        //      本地坐标系移了 d）⇒ 只做一次引用旋转 + 偏移累积，重建只做 World 标出的那几条线
        //      （见 World.retargetDirtyOnShift）。**这是"平移后整窗重建风暴"的解药**。
        //    历史：旧实现对任何原点变化都 bumpEpoch + 让全窗重建（CX*CZ=100 块/次）。
        //    两种情况下都必须 bumpEpoch：在飞任务是在**旧帧**里算的，其顶点坐标对应旧帧
        //    （丢弃时 pollCompleted 会用 chunk 当前的 cx/cz 重新标脏，故不会漏掉那块的编辑）。
        int ocx = world.windowOriginCX(), ocz = world.windowOriginCZ();
        boolean worldChanged = (world != this.meshWorld);
        boolean originMoved = (ocx != this.meshWinCX || ocz != this.meshWinCZ);
        if (worldChanged || originMoved || world.isShifting()) {
            this.meshBuilder.bumpEpoch();
        }
        if (worldChanged) {
            resetChunkFrames();                       // 新世界：每块按自己的槽重读地形
            this.meshWorld = world;
            this.meshWinCX = ocx; this.meshWinCZ = ocz;
        } else if (originMoved) {
            int dcx = ocx - this.meshWinCX, dcz = ocz - this.meshWinCZ;
            if (Math.abs(dcx) < this.chunkX && Math.abs(dcz) < this.chunkZ) {
                // 块对象随内容一起"搬"：对象总数恒定，不分配也不删除 GL 资源。
                this.chunks = Chunk.relocateArray(this.chunks, dcx, dcz);
            } else {
                resetChunkFrames();                   // 零重叠：没有可复用的块，全窗重建
                world.markAllChunksDirty();
            }
            this.meshWinCX = ocx; this.meshWinCZ = ocz;
        }
        if (world.isShifting()) return;   // 平移期间不提交：地形正在被重写，建出来的立刻就会过期
        // ② 提交脏块（近者优先）。脏集合是 HashSet ⇒ 遍历序不定，故出队序的二级键用块键而非序号。
        if (!world.dirtyChunks.isEmpty()) {
            java.util.ArrayList<Long> keys = new java.util.ArrayList<Long>(world.dirtyChunks);
            final float px = this.camPos.x, pz = this.camPos.z;
            // 按"到相机距离²"升序 —— 与 worker 的出队序一致，保证最先提交的就是最先做的。
            java.util.Collections.sort(keys, new java.util.Comparator<Long>() {
                @Override public int compare(Long a, Long b) {
                    return Float.compare(dist2Of(a.longValue(), px, pz), dist2Of(b.longValue(), px, pz));
                }
            });
            for (Long k : keys) {
                if (this.meshBuilder.inflight() >= MESH_MAX_INFLIGHT) break;
                long key = k.longValue();
                // ⚠️ dirtyChunks 用的是 **chunkKey(x,z)**（窗口相对块索引），与绘制循环里的
                // `World.chunkKey(n*16, i*16)` 同一套坐标系 —— 不是全局块坐标（那是 chunkKeyGlobal，
                // 只有 editedChunks 用）。所以这里直接当本地下标用，不要再减窗口原点。
                int lx = (int) (key >> 32), lz = (int) key;
                if (lx < 0 || lz < 0 || lx >= this.chunkX || lz >= this.chunkZ) continue;
                Chunk chunk = this.chunks[lx][lz];
                if (chunk == null) continue;
                if (!world.dirtyChunks.remove(key)) continue;   // 并发安全：本方法只在主线程跑
                this.meshBuilder.submit(chunk, world, key,
                        dist2Of(key, px, pz), MESH_MAX_INFLIGHT);
            }
        }
        // ③ 应用（唯一的 GL 步骤）
        this.meshBuilder.applyReady(world, MESH_APPLY_MAX, MESH_UPLOAD_BUDGET_MS);
    }

    /**
     * 把每个块对象重新绑定到它**当前的槽**、并把网格帧偏移归零（顶点即将/已经按当前帧烘焙）。
     *
     * <p>只用于"没有可复用网格"的两种场合：换世界、窗口零重叠跳转。常规平移走
     * {@link Chunk#relocateArray}（保留网格与偏移）。
     *
     * <p>⚠️ 在它之后必须保证那些块**被标脏**（换世界由 World 构造期全窗标脏；零重叠跳转由调用方
     * 显式 {@code markAllChunksDirty()}）—— 否则块会带着另一个槽的地形顶点画在新位置上。
     */
    private void resetChunkFrames() {
        if (this.chunks == null) return;
        for (int lx = 0; lx < this.chunkX; lx++)
            for (int lz = 0; lz < this.chunkZ; lz++) {
                Chunk c = this.chunks[lx][lz];
                if (c != null) c.resetMeshFrame(lx, lz);
            }
    }

    /** 块键 → 块中心到相机的水平距离²（相机高度对"近者优先"没有意义，故只用 XZ）。 */
    private static float dist2Of(long key, float px, float pz) {
        float dx = (float) ((int) (key >> 32)) * 16.0f + 8.0f - px;
        float dz = (float) ((int) key) * 16.0f + 8.0f - pz;
        return dx * dx + dz * dz;
    }

    private void initOverlayBuffers() {
        int[] nArray = this.makeDynamicVAO();
        this.entVAO = nArray[0];
        this.entVBO = nArray[1];
        this.hudVAO = GL33.glGenVertexArrays();
        GL33.glBindVertexArray((int)this.hudVAO);
        this.hudVBO = GL33.glGenBuffers();
        GL33.glBindBuffer((int)34962, (int)this.hudVBO);
        GL33.glBufferData((int)34962, (long)(HUD_CAP_FLOATS * 4), (int)35048);   // 与 hudBuf 同源常量   // QA 2026-09-13: HUD VBO 4->12MB（8 万顶点 HUD 需 2.24MB/帧，旧 1.6MB 溢出静默失败 = HUD 不可见根因）
        // 布局 pos3 + col4 + uv2 = 9 float = 36B（2026-09-21：加 uv 以支持自研艺术 UI 九宫格贴图）
        GL33.glVertexAttribPointer((int)0, (int)3, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)0L);
        GL33.glEnableVertexAttribArray((int)0);
        GL33.glVertexAttribPointer((int)1, (int)4, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)12L);
        GL33.glEnableVertexAttribArray((int)1);
        GL33.glVertexAttribPointer((int)2, (int)2, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)28L);
        GL33.glEnableVertexAttribArray((int)2);
        // P1 MSDF 通道：与 HUD 同布局（pos3+col4+uv2），但独立 VAO/VBO —— 它在 HUD 之后
        // 用另一个程序绘制，同帧对同一个 VBO 连续 SubData 会触发驱动隐式同步（同 celVAO 的理由）。
        this.msdfVAO = GL33.glGenVertexArrays();
        GL33.glBindVertexArray((int)this.msdfVAO);
        this.msdfVBO = GL33.glGenBuffers();
        GL33.glBindBuffer((int)34962, (int)this.msdfVBO);
        GL33.glBufferData((int)34962, (long)(MSDF_CAP_FLOATS * 4), (int)35048);
        GL33.glVertexAttribPointer((int)0, (int)3, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)0L);
        GL33.glEnableVertexAttribArray((int)0);
        GL33.glVertexAttribPointer((int)1, (int)4, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)12L);
        GL33.glEnableVertexAttribArray((int)1);
        GL33.glVertexAttribPointer((int)2, (int)2, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)28L);
        GL33.glEnableVertexAttribArray((int)2);
        // QA 2026-09-15：天体绘制走自己的 VAO/VBO（容量 = celBuf 的 12000 float）。
        // 为什么不复用 hudVBO：天体在「天空之后、地形之前」画，HUD 在世界之后画 ——
        // 同一帧对同一个 VBO 连续两次 glBufferSubData 会让驱动做隐式同步（CPU 等 GPU），白白掉帧。
        this.celVAO = GL33.glGenVertexArrays();
        GL33.glBindVertexArray((int)this.celVAO);
        this.celVBO = GL33.glGenBuffers();
        GL33.glBindBuffer((int)34962, (int)this.celVBO);
        GL33.glBufferData((int)34962, (long)(15500 * 4), (int)35048);   // 2026-09-21：9 float/顶点（加 uv），容量按 12000/7*9 上调
        GL33.glVertexAttribPointer((int)0, (int)3, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)0L);
        GL33.glEnableVertexAttribArray((int)0);
        GL33.glVertexAttribPointer((int)1, (int)4, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)12L);
        GL33.glEnableVertexAttribArray((int)1);
        GL33.glVertexAttribPointer((int)2, (int)2, (int)5126, (boolean)false, (int)(HUD_VERT_FLOATS * 4), (long)28L);
        GL33.glEnableVertexAttribArray((int)2);
        GL33.glBindVertexArray((int)0);
    }

    private int totalFaces() {
        if (this.chunks == null) {
            return 0;
        }
        int n = 0;
        Chunk[][] chunkArray = this.chunks;
        int n2 = chunkArray.length;
        for (int i = 0; i < n2; ++i) {
            Chunk[] chunkArray2;
            for (Chunk chunk : chunkArray2 = chunkArray[i]) {
                n += chunk.faceCount;
            }
        }
        return n;
    }

    /**
     * 装配内容层与玩法层（平台化 2026-09-13）。
     *
     * <p>随种子重建：粒子模型、效果队列（延迟调度用 tick 对齐）、规则引擎。
     * 内容注册表读 {@code assets/content/}；无目录时自动回落内置内容。
     */
    private void initContentLayer(long seed) {
        // MOD 支持：官方内容 + mods/ 下的目录 mod 与 zip mod（后者可覆盖前者同名 ID）
        java.util.List<core.content.ContentSource> srcs = new java.util.ArrayList<core.content.ContentSource>();
        srcs.add(core.content.ContentSource.dir("base", new java.io.File("assets/content"), 0));
        srcs.addAll(core.content.ContentRegistry.discoverMods(new java.io.File("mods")));
        this.content = core.content.ContentRegistry.loadAll(srcs);
        // 材料规格书：按方块索引展开（渲染层 O(1) 查表）。内容为 null 时退回空书 = 历史兜底行为。
        this.matBook = (this.content == null)
                ? core.content.MaterialBook.empty(core.world.Blocks.count())
                : this.content.materialBook();
        // F 批：把内容注册表包成捏脸预设提供方（纯函数，零 RNG / 零世界引用）
        final core.content.ContentRegistry reg = this.content;
        this.contentProvider = new MenuModel.CharacterProvider() {
            @Override public core.world.Appearance preset(String id) {
                com.google.gson.JsonObject o = reg.get("characters", id);
                if (o == null) return null;
                core.content.CharacterDef d = core.content.CharacterDef.fromJson(id, o);
                core.world.Appearance a = new core.world.Appearance(); a.applyDef(d); return a;
            }
            @Override public String[] ids() {
                return reg.characters().keySet().toArray(new String[0]);
            }
        };
        GameLog.log("MOD", "sources=" + srcs.size() + " (base + " + (srcs.size() - 1) + " mod)");
        for (String ov : this.content.overrides()) GameLog.log("MOD", ov);
        this.particles = new core.content.ParticleSim(seed);
        this.contentSys = new core.systems.ContentSystem(this.content, seed);
        this.contentSys.setSink(this);     // 效果出口 = Game 自己（实现 EffectSink）
        this.rules = new core.content.RuleEngine(this.content.rules());
        // P1 内容层接线：任务从"有 schema 没运行时"变成真能接、真能完成。
        // 驱动位置与 rules/techs/effects 完全一致（固定步长里、world.tick() 之后）。
        // 预设选择器：把内容层的 presets 喂给菜单（菜单只存 id / 显示名，不持有内容引用）。
        java.util.List<core.content.Preset> ps = this.content.presets();
        String[] pIds = new String[ps.size()], pNames = new String[ps.size()];
        for (int i = 0; i < ps.size(); i++) { pIds[i] = ps.get(i).id; pNames[i] = ps.get(i).name; }
        this.menu.setPresets(pIds, pNames);
        this.menu.selectPreset("breathing_world");   // 出厂默认；不在列表里则保持第 0 项
        this.appliedPresetId = null;                 // 换了内容层 → 强制重施到新世界
        this.questEngine = new core.content.QuestEngine(this.content.questDefs());
        this.questEngine.setSink(this);            // 效果出口复用同一个 EffectSink（GRANT_ITEM → 背包）
        this.attachContentToWorld();               // 内容兽表挂到当前世界
        this.techs = new core.content.TechTree(this.content);
        this.skillTree = new core.content.SkillTree(this.content);
        // E 批：背包（36 格 MC 风，0-8 hotbar + 9-35 背包）+ 新手物资包（挖→拾取→建 循环自给）
        this.inventory = new core.content.Inventory(this.content.itemBook());
        this.inventory.setSlot(0, "grass", 64);
        this.inventory.setSlot(1, "stone", 64);
        this.inventory.setSlot(2, "wood", 64);
        this.inventory.setSlot(3, "sand", 64);
        this.inventory.setSlot(4, "lamp", 64);
        this.inventory.setSlot(5, "pickaxe", 1);   // stack=1 + durability=120（工具耐久演示）
        this.heldSlot = 0;
        GameLog.log("INV", "starter kit: " + this.inventory.snapshot());
        for (String line : this.content.report().split("\n")) GameLog.log("CONTENT", line);
        GameLog.log("CONTENT", "rules=" + this.content.rules().size()
                + " presets=" + this.content.presets().size()
                + " modules=" + this.content.modules().size()
                + " techs=" + this.content.techs().size()
                + " beasts=" + this.content.beastDefs().size()
                + " quests=" + this.content.questDefs().size());
    }

    /**
     * P1：把内容层资产挂到**当前**世界上。
     *
     * <p>新游戏、读档、重开三条路径都会换掉 {@code this.sim}（读档是 {@code World.load} + 新
     * {@code Simulation}），所以必须有一个统一入口 —— 散着写必然漏一条，而漏掉的表现是
     * "读档后内容兽变回纯原型"这种极难复现的 bug。
     */
    private void attachContentToWorld() {
        if (this.sim == null || this.sim.world == null) return;
        this.sim.world.beastDefs = (this.content == null)
                ? java.util.Collections.<core.content.BeastDef>emptyList()
                : this.content.beastDefs();
        // 材料规格书（内容第 13 类）：密度判据（SandFallSystem → MaterialBook.sinksInto）的数据来源。
        // 与 beastDefs 同一条通路 —— 加载期资产挂到 World，被 StateCodec.SKIP 排除（不进快照）。
        // 无内容时退回空书 = 全部兜底值 → sinksInto 恒 false，即使误开 densityFlow 也不会乱动世界。
        this.sim.world.materials = (this.content == null)
                ? core.content.MaterialBook.empty(core.world.Blocks.count())
                : this.content.materialBook();
        // 材料反应表（内容第 14 类）：反应求解器（ReactionSystem）的唯一输入。同一条通路 / 同一条纪律。
        // 无内容时退回空表 = 任何材料对都查不到规则 → 即使误开 reactionTable 也零写入。
        this.sim.world.reactions = (this.content == null)
                ? core.content.ReactionBook.empty(core.world.Blocks.count())
                : this.content.reactionBook();
        // 预设选择器：把「选中的玩法组合」施加到**当前**世界。
        // 与内容兽表同理，必须在这里做 —— 新游戏 / 读档 / 重开三条路径都经过本方法，
        // 而 World.registry 的开关状态不进快照，读档后不重施 = 预设静默失效。
        this.applySelectedPreset(false, true);
    }

    /**
     * 把菜单里选中的玩法预设施加到当前世界（内容层 → 玩法层唯一入口）。
     *
     * <p>纪律：**菜单只保存选择，施加只在这一处**。散着写必然出现「设置页能切、世界里没变」
     * 或「读档后失效」这类只在某条路径上错的分歧。
     *
     * @param announce true = 玩家主动切换（挂横幅 + 音效）；false = 新游戏 / 读档的静默重施
     * @param force    true = 无条件重施（换了 World 就要重施，见下方注释）
     * @return 是否真的施加了（false = 没有变化 / 世界未就绪）
     */
    private boolean applySelectedPreset(boolean announce, boolean force) {
        if (this.sim == null || this.sim.world == null || this.menu == null) return false;
        String id = this.menu.presetId();
        // force：新游戏 / 读档必须**无条件**重施 —— 读档会换一个 World，而 registry 的开关状态
        // 不进快照，只比 id 会误判成「已经施过了」，于是预设静默失效（菜单写着 PEACEFUL VALLEY，
        // 地质域却在跑）。
        if (!force && presetIdEq(id, this.appliedPresetId)) return false;
        core.content.Preset p = (id == null || this.content == null) ? null : this.content.preset(id);
        String rep = this.sim.applyPreset(p);
        this.appliedPresetId = id;
        GameLog.log("PRESET", rep);
        // HUD 行只在「真的改变了世界」时才挂：参数与出厂值不同、或关掉了系统。
        boolean changed = p != null && (!p.disablePhases.isEmpty() || !p.disableSystems.isEmpty()
                || this.sim.world.beastCap != core.world.World.BEAST_CAP
                || core.world.DayCycle.DAY_LEN != core.world.DayCycle.DEFAULT_DAY_LEN);
        this.presetHudLine = changed
                ? "PLAYSTYLE  " + this.menu.presetName().toUpperCase(java.util.Locale.US) : "";
        if (announce && changed) {
            this.bannerText = "PLAYSTYLE: " + this.menu.presetName().toUpperCase(java.util.Locale.US);
            this.bannerTimer = 3.0f;
            this.sfx(Sfx.UNLOCK);
        }
        return true;
    }

    /** 字符串相等（含 null）—— 预设 id 可能为 null（无内容 = 出厂配置）。 */
    private static boolean presetIdEq(String a, String b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    private void loop() {
        long fpsT0 = System.nanoTime();
        int fpsF0 = 0;
        while (!GLFW.glfwWindowShouldClose((long)this.window)) {
        try {
            GLFW.glfwPollEvents();
            long nowNs = System.nanoTime();
            // QA 诊断：{@code -Dbw.fpsdiag=1} 时每秒打一行真实帧率 + 流式加载稳态条件（写 GameLog）。
            // 存在理由：{@code -Dbw.snap} 的截图触发依赖"加载稳态"，而 stdout 在重定向下是全缓冲
            // （进程被杀时整段丢失），只能靠 GameLog 这类自 flush 的文件日志来判读；
            // 且启动期帧率（世界生成 + 96 块网格重建）是"卡不卡"的第一手证据。
            if ("1".equals(System.getProperty("bw.fpsdiag"))) {
                double el = (nowNs - fpsT0) / 1.0E9;
                if (el >= 1.0) {
                    int df = this.frameCount - fpsF0;
                    GameLog.log("FPSDIAG", String.format(java.util.Locale.US,
                            "fps=%.1f frames=%d frameCount=%d shifting=%s dirty=%d lightDirty=%s mesh=%d/%d built=%d dropped=%d failed=%d W=%d H=%d",
                            df / el, df, this.frameCount, this.sim.world.isShifting(),
                            this.sim.world.dirtyChunks.size(), this.sim.world.lightDirty,
                            // A2：mesh=在飞/待应用 —— 稳态下两者都该回到 0（而 dirty 也应为 0）；
                            // built/dropped 是累计值（dropped 非零只说明世界在移动，正常）；
                            // failed **必须恒为 0** —— 非零意味着某块地形永远建不出来。
                            this.meshBuilder == null ? 0 : this.meshBuilder.inflight(),
                            this.meshBuilder == null ? 0 : this.meshBuilder.ready(),
                            this.meshBuilder == null ? 0L : this.meshBuilder.builtTotal(),
                            this.meshBuilder == null ? 0L : this.meshBuilder.droppedTotal(),
                            this.meshBuilder == null ? 0L : this.meshBuilder.failedTotal(),
                            this.W, this.H));
                    fpsT0 = nowNs; fpsF0 = this.frameCount;
                }
            }
            float frameDt = (float)(nowNs - this.lastNs) / 1.0E9f;
            this.lastNs = nowNs;
            this.acc += frameDt * this.timeScale();
            this.updateInput(frameDt);
            if (this.sim.world.paused) {
                this.acc = 0.0f;
            } else if (this.sim.world.isShifting()) {
                // M3① 跨图流式分帧：INDEX 阶段索引不完整、GEN 阶段地形半新半旧 —— 期间禁止 tick
                // （系统会读 cellsOfType/nonAirCells 与 terrain，混态下演化会失真）。
                // 分帧通常 12~20 帧完成（<0.4s），视觉上是"地形流式加载"，玩家不可感知。
                this.acc = 0.0f;
            } else if (this.hitstop > 0.0f) {
                this.hitstop -= frameDt;
                this.acc = 0.0f;
            } else {
                if (this.qaFrozen) this.acc = 0.0f;      // QA：冻结仿真 → 无头截图可复现（见 qaFrozen 注释）
                // QA 预热（第三十五批）：`-Dbw.warm=N` 让无头截图**真的跑 N 个 tick** 再拍。
                // 不预热时 -Dbw.snap 的世界**从未演化**（tick 只是被伪造的计数器）⇒ 对"随时间累积"的
                // 改动（灰沉降/植被蔓延/水位）完全看不到。详见 qaWarmLeft 的字段注释。
                if (this.qaWarmLeft > 0) {
                    int nWarm = Math.min(this.qaWarmLeft, QA_WARM_PER_FRAME);
                    for (int i = 0; i < nWarm; i++) this.sim.world.tick();
                    this.qaWarmLeft -= nWarm;
                }
                int steps = 0;
                long simT0 = java.lang.System.nanoTime();
                while (this.acc >= 0.05f && steps < MAX_STEPS_PER_FRAME) {
                    // 时间盒：本帧仿真已超预算就收手（首步必跑，保证世界一定在推进）。
                    if (steps > 0
                            && (java.lang.System.nanoTime() - simT0) / 1000000.0f >= MAX_FRAME_SIM_MS) break;
                    if (this.netSession != null) {
                        // N5：联机模式 —— tick 由会话推进（含内容层），本地输入按 tick 喂 Intent
                        int nextTick = (int) this.netSession.simTick() + 1;
                        this.netSession.submitLocal(nextTick, this.netLocalIntent);
                        this.netSession.pump();
                        if (this.netSession.canAdvance()) {
                            this.sampleBeastPrev();          // 必须在推进前采样（渲染插值用）
                            this.netSession.advance();
                            this.acc -= 0.05f; ++steps;
                        } else {
                            break;   // 锁步：缺远端帧不推进，先渲染本帧
                        }
                    } else {
                        this.sampleBeastPrev();              // 必须在 tick 前采样（渲染插值用）
                        this.sim.world.tick();
                        // 内容层 + 玩法层每 tick 推进（规则读世界事件 → 效果入队 → 出口表现）
                        this.rules.tick(this.sim.world, this.contentSys.queue(), this.sim.world.tick);
                        this.techs.tick(this.sim.world, this.contentSys.queue(), this.sim.world.tick, this.inventory);
                        this.contentSys.update(this.sim.world, null);   // A 批：内容层引擎
                        this.questEngine.tick(this.sim.world, this.sim.world.tick);   // P1
                        this.acc -= 0.05f; ++steps;
                    }
                }
                // QA 2026-09-16：插值因子只在本帧真的推进了 tick 时更新。暂停 / hitstop / 跨图分帧这些
                // 分支不会走到这里 -> entAlpha 被「冻结」，实体停在原处而不是弹回上一 tick（以前每次
                // 命中触发 hitstop 时，所有插值实体都会闪一下，就是这个原因）。落后丢积压时不更新，
                // 免得 entAlpha 在 0 与 1 之间来回跳（那是另一种闪烁）。
                if (steps > 0 && steps < MAX_STEPS_PER_FRAME) {
                    this.entAlpha = Math.min(1.0f, this.acc / 0.05f);
                }
                if (steps >= MAX_STEPS_PER_FRAME) this.acc = 0.0f;  // 防螺旋死亡：落后过多丢弃积压，避免越慢越卡
                if (this.acc > MAX_ACC_SEC) this.acc = 0.0f;        // 追不回来就明确跳帧（避免长期慢动作）
            }
            int targetCx = this.sim.world.windowOriginCX() + (int)(this.sim.player.x / 16.0f);
            int targetCz = this.sim.world.windowOriginCZ() + (int)(this.sim.player.z / 16.0f);
            // M3① 跨图流式分帧：登记目标（不阻塞），再按每帧预算推进重生成，消除整窗停帧。
            // 与旧的 this.sim.world.streamTo(targetCx, targetCz) 结果逐字节一致（仅把「何时算」切片），
            // 分帧状态机在 World 内，预算 = SHIFT_CHUNKS_PER_STEP 块/帧。
            this.sim.world.streamToSliced(targetCx, targetCz);
            // 第二十六批（性能）：流式重建从"每帧固定 8 块"改成**按时间预算分片**（见 SHIFT_BUDGET_MS）。
            // stepShift(1) 循环与旧的 stepShift(8) 语义等价（阶段 1 只在首次调用做一次；阶段 2/4 都按块数切），
            // 区别只是"切得更细、且本帧时间一到就收手" ⇒ 帧时间平稳。
            long shiftT0 = java.lang.System.nanoTime();
            while (!this.sim.world.stepShift(1)) {
                if ((java.lang.System.nanoTime() - shiftT0) / 1000000.0f >= SHIFT_BUDGET_MS) break;
            }
            // 第三十二批 A2：网格流水线（提交脏块 → 应用已完成结果）必须在 drawFrame **之前** ——
            // 本帧刚上传的块当帧就能画出来；放到帧尾会平白多等一帧（跨图时是可见的整体延迟）。
            this.updateMeshes();
            Vector3f vector3f = this.forward();
            this.updateShake(frameDt);
            Vector3f eye = new Vector3f((Vector3fc)this.camPos);
            eye.x += this.shakeOffX;                            // 屏震只偏移眼睛，不动 camPos
            eye.y += this.shakeOffY;
            this.view.setLookAt((Vector3fc)eye, (Vector3fc)new Vector3f().set((Vector3fc)eye).add((Vector3fc)vector3f), (Vector3fc)UP);
            this.drawFrame();
            HudText.frameEnd();
            if (this.audio != null) {
                this.audio.frame(frameDt);
            }
            GLFW.glfwSwapBuffers((long)this.window);
            GameLog.hb("focus=" + this.windowFocused + " paused=" + this.sim.world.paused + " pos=" + (int)this.sim.player.x + "/" + (int)this.sim.player.y + "/" + (int)this.sim.player.z + " beasts=" + this.sim.world.beasts.size() + " npcs=" + this.sim.world.npcs.size());
        } catch (Throwable t) {
            GameLog.err("loop", t);
        }
        }
    }

    private void updateInput(float f) {
        this.windowFocused = GLFW.glfwGetWindowAttrib((long)this.window, (int)262144) != 0;   // GLFW_FOCUSED 轮询：不依赖回调（防焦点事件丢失假死）
        if (this.windowFocused) this.everFocused = true;
        boolean bl;
        float f2;
        boolean bl2;
        int n;
        Beast beast;
        Weapons.Art art;
        boolean bl3;
        float f3;
        float f4;
        float f5;
        float f6;
        World world = this.sim.world;
        this.handleMenu();
        this.applySettings();
        if (this.menu.isOpen()) {
            this.clearQueuedActions();
            this.smoothMx = 0.0f;
            this.smoothMz = 0.0f;
            return;
        }
        this.clearPlayerBreathingRoom(world);
        Vector3f vector3f = this.forward();
        vector3f.y = 0.0f;
        vector3f.normalize();
        // LD-2026-09-12 受困检测升级：四方向 1.2 格处 2 格墙——前方计教学提示，四向全堵计自动脱困
        Vector3f vector3f2 = new Vector3f().set((Vector3fc)vector3f).cross((Vector3fc)UP).normalize();
        boolean wallF = this.wall2At(world, this.sim.player.x + vector3f.x * 1.2f, this.sim.player.y, this.sim.player.z + vector3f.z * 1.2f);
        boolean wallB = this.wall2At(world, this.sim.player.x - vector3f.x * 1.2f, this.sim.player.y, this.sim.player.z - vector3f.z * 1.2f);
        boolean wallR = this.wall2At(world, this.sim.player.x + vector3f2.x * 1.2f, this.sim.player.y, this.sim.player.z + vector3f2.z * 1.2f);
        boolean wallL = this.wall2At(world, this.sim.player.x - vector3f2.x * 1.2f, this.sim.player.y, this.sim.player.z - vector3f2.z * 1.2f);
        this.blockFrontTimer = wallF ? this.blockFrontTimer + f : 0.0f;
        this.stuckAllTimer = (wallF && wallB && wallR && wallL) ? this.stuckAllTimer + f : 0.0f;
        float f7 = 0.0f;
        float f8 = 0.0f;
        if (this.keys[87]) {
            f7 += vector3f.x;
            f8 += vector3f.z;
        }
        if (this.keys[83]) {
            f7 -= vector3f.x;
            f8 -= vector3f.z;
        }
        if (this.keys[68]) {
            f7 += vector3f2.x;
            f8 += vector3f2.z;
        }
        if (this.keys[65]) {
            f7 -= vector3f2.x;
            f8 -= vector3f2.z;
        }
        float f9 = Math.min(1.0f, f * 12.0f);
        this.smoothMx += (f7 - this.smoothMx) * f9;
        this.smoothMz += (f8 - this.smoothMz) * f9;
        if (Math.abs(this.smoothMx) < 0.001f) {
            this.smoothMx = 0.0f;
        }
        if (Math.abs(this.smoothMz) < 0.001f) {
            this.smoothMz = 0.0f;
        }
        if (!(Math.abs(this.smoothMx) < 1000000.0f)) {
            this.smoothMx = 0.0f;
        }
        if (!(Math.abs(this.smoothMz) < 1000000.0f)) {
            this.smoothMz = 0.0f;
        }
        f7 = this.smoothMx;
        f8 = this.smoothMz;
        if (this.locked != null && world.beasts.contains(this.locked)) {
            f9 = this.locked.x - this.sim.player.x;
            float f10 = this.locked.z - this.sim.player.z;
            f6 = (float)Math.toDegrees(Math.atan2(f9, -f10));
            f5 = this.locked.y + 0.8f - (this.sim.player.y + 1.4f);
            f4 = Math.max(-60.0f, Math.min(40.0f, (float)Math.toDegrees(Math.atan2(f5, Math.hypot(f9, f10)))));
            for (f3 = f6 - this.yaw; f3 > 180.0f; f3 -= 360.0f) {
            }
            while (f3 < -180.0f) {
                f3 += 360.0f;
            }
            this.yaw += f3 * Math.min(1.0f, f * 8.0f);
            this.pitch += (f4 - this.pitch) * Math.min(1.0f, f * 8.0f);
        }
        boolean bl5 = this.keys[32] && !this.sim.player.onGround;
        boolean bl6 = bl3 = this.keys[340] || this.keys[344];
        if (bl3) {
            this.tutMark(11);
        }
        boolean bl7 = this.sprinting = (this.keys[341] || this.keys[345]) && !bl3 && (Math.abs(f7) > 1.0E-4f || Math.abs(f8) > 1.0E-4f);
        if (this.sprinting) {
            this.tutMark(10);
        }
        this.sprintFov += ((this.sprinting ? 1.0f : 0.0f) - this.sprintFov) * Math.min(1.0f, f * 6.0f);
        f6 = (float)this.menu.fov + 8.0f * this.sprintFov;
        if (Math.abs(f6 - this.lastProjFov) > 0.01f) {
            this.lastProjFov = f6;
            this.proj.setPerspective((float)Math.toRadians(this.lastProjFov), (float)this.W / (float)this.H, 0.1f, (float)this.appliedViewDist);
        }
        if (this.netSession == null) {
            // dt 与仿真同基：GAME SPEED 同时加快世界与玩家，玩家不再是唯一按真实时间走的那个
            this.sim.player.physicsTick(world, f7 * (this.sprinting ? 1.35f : 1.0f), f8 * (this.sprinting ? 1.35f : 1.0f), this.keys[32], bl5, bl3, f * this.timeScale());
        } else {
            this.netLocalIntent = this.deriveNetIntent(vector3f, vector3f2);
        }
        if (blockFrontTimer > 5.0f && !stuckLogged) {
            stuckLogged = true;
            GameLog.log("STUCK", "player=" + sim.player.x + "/" + sim.player.y + "/" + sim.player.z + " mx=" + dbgMx + " mz=" + dbgMz);
        }
        if (this.stuckAllTimer > 15.0f) {
            this.stuckAllTimer = 0.0f;
            this.emergencyRespawn();
            this.bannerText = "\u68c0\u6d4b\u5230\u88ab\u56f0 \u00b7 \u5df2\u81ea\u52a8\u8131\u56f0\u56de\u5230\u51fa\u751f\u70b9\u5e76\u4fdd\u5b58";
            this.bannerTimer = 4.0f;
        }
        if (this.menu.isOpen()) {
            this.mouseHeldL = false;
            this.mouseHeldR = false;
        }
        this.clickRepeat -= f;
        if ((this.mouseHeldL || this.mouseHeldR) && !this.inventoryOpen && this.netSession == null) {   // 背包开着/联机：点击归 UI 或禁用（联机世界编辑需经会话以保持确定性）
            if (this.mouseHeldL) {
                this.digTick(f);                       // MC 手感（§1 P0 #1）：挖掘每帧推进进度，不再 0.2s 秒破
            } else if (this.clickRepeat <= 0.0f) {
                this.doPlace();                        // 放置仍按 0.2s 节流（防连点刷墙）
                this.clickRepeat = 0.2f;
            }
        } else if (this.digBx != Integer.MIN_VALUE) {
            this.digIdle += this.qaFrozen ? 0.0f : f;  // 松手：进度保留 DIG_HOLD 秒后清零（MC 语义）；QA 冻结
            if (this.digIdle > DIG_HOLD) this.digReset();
        }
        this.dbgMx = f7;
        this.dbgMz = f8;
        f5 = (float)Math.sin(Math.toRadians(this.yaw));
        f4 = (float)(-Math.cos(Math.toRadians(this.yaw)));
        f3 = f7;
        float f11 = f8;
        if (Math.hypot(f3, f11) < (double)0.001f) {
            f3 = f5;
            f11 = f4;
        }
        if (this.queuedCycleArt) {
            this.queuedCycleArt = false;
            art = this.sim.player.cycleArt();
            this.toastText = "ART " + Weapons.artName(art) + "  (" + this.sim.player.weaponName() + ")";
            this.toastTimer = 1.4f;
            this.sfx(Sfx.MENU_MOVE);
        }
        if (this.queuedArt) {
            this.queuedArt = false;
            art = this.sim.player.currentArt;
            Weapons.Def object = Weapons.def(this.sim.player.weapon);
            Beast beast2 = beast = art == Weapons.Art.SHOT ? this.sim.player.nearestBeast(world, object.range) : null;
            if (this.sim.player.weaponArt(world, f3, f11)) {
                this.sfx((art == Weapons.Art.CLEAVE || art == Weapons.Art.CHARGE)
                        ? Sfx.ART_CLEAVE : (art == Weapons.Art.SHOT ? Sfx.ART_SHOT : Sfx.ART_LUNGE));
                this.artFxArt = art;
                this.artFxDx = f3;
                this.artFxDz = f11;
                this.artFxTimer = this.artFxDur = (art == Weapons.Art.CLEAVE) ? 0.34f
                        : (art == Weapons.Art.CHARGE ? 0.42f : 0.26f);   // CHARGE 的弧光更慢更大
                boolean bl8 = this.artFxHasTarget = art == Weapons.Art.SHOT && beast != null;
                if (this.artFxHasTarget) {
                    this.artFxTx = beast.x;
                    this.artFxTy = beast.y + 0.8f;
                    this.artFxTz = beast.z;
                    this.spawnSparks(beast.x, beast.y + 0.6f, beast.z);
                } else {
                    this.spawnSparks(this.sim.player.x + f3 * 2.2f, this.sim.player.y + 1.1f, this.sim.player.z + f11 * 2.2f);
                }
            } else {
                this.toastText = this.sim.player.level < 2 ? "ART LOCKED (Lv2)" : (this.sim.player.currentArt == Weapons.Art.SHOT ? "NO TARGET IN RANGE" : "ART BUSY / NO STAMINA");
                this.toastTimer = 0.9f;
                this.sfx(Sfx.DENY);
            }
        }
        if ((n = this.sim.player.arts.size()) > this.prevArtCount) {
            this.prevArtCount = n;
            Weapons.Art art2 = this.sim.player.currentArt;
            this.bannerText = "NEW ART: " + Weapons.artName(art2) + "  " + this.sim.player.weaponName();
            this.bannerTimer = 2.8f;
            this.talkReply = "\u4e60\u5f97\u6218\u6280\u300c" + Weapons.artNameCn(art2) + "\u300d\u2014\u2014" + Weapons.artDescCn(art2) + "\u3002\u6309 G \u53ef\u5728\u5df2\u89e3\u9501\u6218\u6280\u95f4\u5207\u6362\u3002";
            this.talkReplyLabel = "WEAPON ART / " + this.sim.player.weaponName();
            this.talkReplyTimer = 7.0f;
            this.sfx(Sfx.UNLOCK);
        }
        // P0-3：武器"获得 ≠ 装备"，所以拿到时必须明确告诉玩家（否则没人知道按 M 能换）
        int ownedNow = this.sim.player.weaponOwned;
        if (this.prevWeaponOwned < 0) {
            this.prevWeaponOwned = ownedNow;                       // 首帧/读档：只同步，不提示
        } else if (ownedNow != this.prevWeaponOwned) {
            int gained = ownedNow & ~this.prevWeaponOwned;
            this.prevWeaponOwned = ownedNow;
            if (gained != 0) {
                int gi = Integer.numberOfTrailingZeros(gained);
                this.bannerText = "WEAPON OBTAINED: " + Weapons.def(gi).name + "   (M TO EQUIP)";
                this.bannerTimer = 3.2f;
            }
        }
        if (this.artFxTimer > 0.0f) {
            this.artFxTimer = Math.max(0.0f, this.artFxTimer - f * this.timeScale());
        }
        // QA：landTimer/rollAnim/skillCastAnim 是角色动作相位（驱动身体/手臂姿态），冻结以免截图漂移。
        float animDt = this.qaFrozen ? 0.0f : f * this.timeScale();
        if (this.landTimer > 0.0f) {
            this.landTimer = Math.max(0.0f, this.landTimer - animDt);
        }
        if (this.rollAnim > 0.0f) {
            this.rollAnim = Math.max(0.0f, this.rollAnim - animDt);
        }
        if (this.skillCastAnim > 0.0f) {
            this.skillCastAnim = Math.max(0.0f, this.skillCastAnim - animDt);
        }
        if (this.queuedRoll) {
            this.sim.player.dodgeRoll(world, f3, f11);
            this.queuedRoll = false;
            this.sfx(Sfx.ROLL);
            this.rollAnim = 0.42f;
            this.particles.spawn(this.content.get("particles", "dust"),
                    this.sim.player.x, this.sim.player.y + 0.1f, this.sim.player.z, 5);
        }
        if (this.queuedLock) {
            if (this.locked != null) {
                this.locked = null;
                this.toastText = "LOCK OFF";
                this.toastTimer = 0.9f;
            } else {
                Beast beast3 = this.sim.player.nearestBeast(world, 14.0f);
                if (beast3 != null) {
                    this.locked = beast3;
                    this.toastText = "LOCK ON";
                    this.toastTimer = 0.9f;
                } else {
                    this.toastText = "NO TARGET";
                    this.toastTimer = 0.9f;
                }
            }
            this.queuedLock = false;
            this.sfx(Sfx.MENU_MOVE);
        }
        if (this.queuedBomb) {
            this.sim.player.bomb(world, f5, f4);
            this.queuedBomb = false;
            this.sfx(Sfx.BOMB);
        }
        if (this.queuedIgnite) {
            this.sim.player.ignite(world, f5, f4);
            this.queuedIgnite = false;
            this.sfx(Sfx.BOMB);
        }
        if (this.queuedAqua) {
            this.sim.player.divert(world, f5, f4);
            this.queuedAqua = false;
            this.sfx(Sfx.DIG);
        }
        if (this.queuedThunder) {
            this.sim.player.thunder(world, f5, f4);
            this.queuedThunder = false;
            this.sfx(Sfx.BOMB);
        }
        // A 批（技能链）：O 轮换 / P 施放。走 ContentSystem.cast —— 由它做
        // 「已学 + 冷却 + 体力」校验、targeting 选目标、再按锚点展开效果链。
        if (this.queuedSkillSwitch) {
            this.queuedSkillSwitch = false;
            this.refreshLearnedSkills();
            if (this.learnedSkills.isEmpty()) {
                this.toastText = "NO SKILL LEARNED (K)";
                this.toastTimer = 1.1f;
                this.sfx(Sfx.DENY);
            } else {
                this.skillIdx = (this.skillIdx + 1) % this.learnedSkills.size();
                String sid = this.learnedSkills.get(this.skillIdx);
                core.content.SkillDef sd = this.contentSys.skill(sid);
                this.toastText = "SKILL " + (sd == null ? sid.toUpperCase() : sd.name.toUpperCase());
                this.toastTimer = 1.5f;
                this.sfx(Sfx.MENU_MOVE);
            }
        }
        if (this.queuedSkillCast) {
            this.queuedSkillCast = false;
            this.refreshLearnedSkills();
            if (this.learnedSkills.isEmpty()) {
                this.toastText = "NO SKILL LEARNED (K)";
                this.toastTimer = 1.1f;
                this.sfx(Sfx.DENY);
            } else {
                String sid = this.learnedSkills.get(this.skillIdx % this.learnedSkills.size());
                core.content.SkillDef sd = this.contentSys.skill(sid);
                boolean cast = this.contentSys.cast(world, this.sim.player, sid, world.tick);
                if (cast) {
                    // anim 键的落地："roll"（风步类位移技）摆动更大更长，其余为前挥
                    this.skillCastAnim = (sd != null && "roll".equals(sd.anim)) ? 0.42f : 0.30f;
                    this.sfx(Sfx.ART_CLEAVE);
                    // 演出（纯渲染层，不入仿真；粒子由 fx 管线按锚点发射，这里只补彩色闪光/顿帧）
                    if ("flame_burst".equals(sid)) {
                        this.skillFlash = 0.55f; this.skillFlashR = 1.0f; this.skillFlashG = 0.42f; this.skillFlashB = 0.10f;
                        this.hitstop = Math.max(this.hitstop, 0.05f);
                        this.shake(0.42f);
                    } else if ("gale_step".equals(sid)) {
                        this.skillFlash = 0.32f; this.skillFlashR = 0.55f; this.skillFlashG = 0.92f; this.skillFlashB = 1.0f;
                    } else if ("ember_harvest".equals(sid)) {
                        this.skillFlash = 0.30f; this.skillFlashR = 1.0f; this.skillFlashG = 0.58f; this.skillFlashB = 0.18f;
                    }
                } else {
                    int need = (sd == null) ? 0 : sd.cost;
                    this.toastText = (sd != null && this.sim.player.stamina < need) ? "NOT ENOUGH STAMINA" : "SKILL ON COOLDOWN";
                    this.toastTimer = 1.0f;
                    this.sfx(Sfx.DENY);
                }
            }
        }
        if (this.queuedInvest != null) {
            String string = this.queuedInvest;
            this.queuedInvest = null;
            if (this.sim.player.investSoul(string)) {
                this.toastText = string + " +1  (SOUL " + this.sim.player.souls + ")";
                this.toastTimer = 1.3f;
                this.sfx(Sfx.LOOT);
            } else {
                this.toastText = "NEED MORE SOUL";
                this.toastTimer = 1.0f;
                this.sfx(Sfx.DENY);
            }
        }
        if (this.locked != null && !world.beasts.contains(this.locked)) {
            this.locked = null;
        }
        for (int hb = 0; hb < 9; ++hb) {                      // E 批：1-9 = 选 hotbar 槽（方块由槽内物品定义派生）
            if (this.keys[49 + hb]) this.heldSlot = hb;
        }
        this.nearNpc = this.nearestNpc(world, 4.0f);
        // 捕捉驯养：Y 投球。两段判定（血量比 <= hpThreshold、背包球 >= orbItemCost）在
        // CaptureSystem 内完成 —— 渲染层只负责"按键 → 调用 → 反馈"，不复制玩法规则。
        if (this.queuedCapture) {
            this.queuedCapture = false;
            core.systems.System capSys = world.registry.get("capture");
            boolean captured = false;
            if (capSys instanceof core.systems.CaptureSystem) {
                captured = ((core.systems.CaptureSystem) capSys).tryCapture(world, this.inventory);
            }
            if (captured) {
                this.sfx(Sfx.UNLOCK);
                this.toastText = "CAPTURED";
                this.toastTimer = 1.4f;
            } else {
                this.sfx(Sfx.DENY);
                this.toastText = "CAPTURE FAILED (WEAKEN + ORB)";
                this.toastTimer = 1.2f;
            }
        }
        // 手搓合成（I）：结算当前能做的第一条配方。规则全在 RecipeBook（不足整条不扣料），
        // 渲染层只负责"按键 → 调用 → 反馈"，不复制玩法规则。
        if (this.queuedParry) {
            this.queuedParry = false;
            if (this.sim.player.tryParry(world)) {
                // 招架成功：青白屏闪 + 扩散环（见 drawEntities）+ 金属脆响。
                this.toastText = "PARRY WINDOW"; this.toastTimer = 0.8f;
                this.parryFxTimer = PARRY_FX_DUR;
                this.skillFlash = 0.16f; this.skillFlashR = 0.50f; this.skillFlashG = 0.95f; this.skillFlashB = 1.00f;
                this.sfx(Sfx.PARRY);
                this.unlockAch("FIRST PARRY");
            } else { this.toastText = "PARRY COOLDOWN"; this.toastTimer = 0.8f; this.sfx(Sfx.DENY); }
        }
        if (this.queuedExecute) {
            this.queuedExecute = false;
            if (this.sim.player.executeNearest(world)) {
                // 处决：红黑屏闪 + 冲击粒子 + 微屏震 + 沉重闷响。
                this.toastText = "EXECUTION"; this.toastTimer = 1.2f;
                this.executeFxTimer = EXECUTE_FX_DUR;
                this.skillFlash = 0.30f; this.skillFlashR = 0.95f; this.skillFlashG = 0.14f; this.skillFlashB = 0.12f;
                this.spawnSparks(this.sim.player.x, this.sim.player.y + 1.0f, this.sim.player.z);
                this.shake(0.6f);
                this.sfx(Sfx.EXECUTE);
            } else { this.toastText = "NO EXECUTION"; this.toastTimer = 0.8f; this.sfx(Sfx.DENY); }
        }
        if (this.queuedChest) {
            this.queuedChest = false;
            boolean opened = false;
            for (core.world.World.Chest ch : world.chests) {
                if (ch.opened || ch.itemId == null) continue;
                float dx = this.sim.player.x - ch.x, dz = this.sim.player.z - ch.z;
                if (dx*dx + dz*dz > 12.25f) continue;
                int left = this.inventory.add(ch.itemId, ch.count);
                if (left == 0) {
                    ch.opened = true; opened = true;
                    this.toastText = "CHEST +" + ch.itemId + " x" + ch.count;
                    this.toastTimer = 1.4f; this.sfx(Sfx.UNLOCK);
                    this.unlockAch("FIRST CHEST");
                } else {
                    this.toastText = "INVENTORY FULL"; this.toastTimer = 1.2f; this.sfx(Sfx.DENY);
                }
                break;
            }
            if (!opened && this.toastTimer <= 0f) {
                this.toastText = "NO CHEST NEARBY"; this.toastTimer = 1.0f; this.sfx(Sfx.DENY);
            }
        }
        if (this.queuedCraft) {
            this.queuedCraft = false;
            core.content.RecipeBook.Recipe rcp =
                    core.content.RecipeBook.craftable(this.content, this.inventory);
            if (rcp == null) {
                this.sfx(Sfx.DENY);
                this.toastText = "NOTHING TO CRAFT";
                this.toastTimer = 1.2f;
            } else if (core.content.RecipeBook.craft(rcp, this.inventory, this.content)) {
                this.sfx(Sfx.UNLOCK);
                this.toastText = "CRAFTED " + rcp.output + " x" + rcp.outputCount;
                this.toastTimer = 1.4f;
                this.unlockAch("FIRST CRAFT");
            }
        }
        if (this.queuedTalk) {
            this.queuedTalk = false;
            if (this.nearNpc != null) {
                this.sfx(Sfx.TALK);
                String string = Dialogue.talk(world, "player", "\u65c5\u4eba", this.nearNpc, "", true);
                this.talkToast = "TALK " + this.nearNpc.profession + "  " + Dialogue.asciiPersona(this.nearNpc) + "  " + Dialogue.asciiMood(this.nearNpc);
                this.talkToastTimer = 2.6f;
                this.talkReplyLabel = this.nearNpc.profession + " / " + Dialogue.asciiPersona(this.nearNpc) + " / " + Dialogue.asciiMood(this.nearNpc);
                this.talkReply = string;
                this.talkReplyTimer = 12.0f;
                this.logTalk(this.nearNpc.profession + "/" + Dialogue.asciiPersona(this.nearNpc) + "/" + Dialogue.asciiMood(this.nearNpc) + "  >>  " + string);
            } else {
                this.talkToast = "NO VILLAGER NEAR";
                this.talkToastTimer = 1.2f;
                this.sfx(Sfx.DENY);
            }
        }
        // QA：所有"用真实帧 dt 衰减/积分"的演出计时器统一走 fxDt；冻结后为 0，保证无头截图逐像素可复现。
        // （fxDt=0 时这些量停在初始值 —— 初始值由 qaFrozen 下的出生状态决定，因此仍是确定性的。）
        float fxDt = this.qaFrozen ? 0.0f : f;
        if (this.talkToastTimer > 0.0f) {
            this.talkToastTimer -= fxDt;
        }
        if (this.talkReplyTimer > 0.0f) {
            this.talkReplyTimer -= fxDt;
        }
        // 按钮脉冲倒计时（第五批红石）：到点把 meta 复位为 0（电路断）。纯渲染层，不进 hashState。
        for (int bi = this.buttonPulses.size() - 1; bi >= 0; bi--) {
            int[] bp = this.buttonPulses.get(bi);
            bp[3] -= (int) (fxDt * 1000f);
            if (bp[3] <= 0) {
                if (world.getBlock(bp[0], bp[1], bp[2]) == Blocks.BUTTON.index) {
                    world.setMeta(bp[0], bp[1], bp[2], 0);
                }
                this.buttonPulses.remove(bi);
            }
        }
        this.writeChronicleLog(world);
        boolean bl9 = bl2 = Math.hypot(f7, f8) > (double)0.001f;
        // QA：walkAnim/walkBlend 是手持模型（第一人称手臂摆动）的相位，用真实帧 dt 积分。
        // 不冻结时同一版本两次运行在第 48 帧的相位必然不同 → 手臂位置不同 → 全屏像素差异（A/B 仪器失效）。
        if (!this.qaFrozen) {
            if (bl2) {
                this.walkAnim += f * 9.0f * this.timeScale();     // 与仿真同基：步频跟着世界时间走
            }
            this.walkBlend += ((bl2 ? 1.0f : 0.0f) - this.walkBlend) * Math.min(1.0f, f * 10.0f);
        }
        if (!this.qaFrozen) this.time += f * this.timeScale();   // QA：冻结演出时钟（云/星/波纹相位固定）
        Vector3f eye = new Vector3f(this.sim.player.x, this.sim.player.y + 1.6f, this.sim.player.z);
        Vector3f vector3f3 = this.forward();
        // ===== P0④ 摄像机：指数平滑 + 台阶吸收 + 第三人称避障（2026-09-21）=====
        // 旧实现：camPos.set(...) 逐帧硬设 → 台阶上/下时画面"顿跳"，第三人称背贴墙时相机穿墙。
        // 三项改进（纯渲染层，不改 camPos 的语义，仅让它**滞后地**追目标）：
        //   ① 指数平滑（与帧率无关）：camPos += (target-camPos)*(1-exp(-dt/tau))，tau 水平 0.06 / 垂直 0.10；
        //   ② 台阶吸收：垂直目标不取玩家 y 的逐帧值，而是走更慢的 tau_y（0.10 s）→ 上楼梯不颠；
        //   ③ 第三人称避障：向 -forward 扫掠，撞到 solid 就把机位缩到命中点前 0.3 格。
        float camTauXZ = 0.06f, camTauY = 0.10f;   // QA 冻结时平滑会卡住 → qaFrozen 下 tau=0（= 硬跟随，保像素可复现）
        if (this.qaFrozen) { camTauXZ = 0f; camTauY = 0f; }
        float camTargetX, camTargetY, camTargetZ;
        if (this.thirdPerson) {
            float camDist = cameraCollideDist(eye.x, eye.y, eye.z, -vector3f3.x, -vector3f3.y, -vector3f3.z, 4.5f);
            camTargetX = eye.x + (-vector3f3.x) * camDist;
            camTargetY = eye.y + (-vector3f3.y) * camDist + 1.4f;
            camTargetZ = eye.z + (-vector3f3.z) * camDist;
        } else {
            camTargetX = eye.x; camTargetY = eye.y; camTargetZ = eye.z;
        }
        if (camTauXZ <= 0f) {
            this.camPos.set(camTargetX, camTargetY, camTargetZ);
        } else {
            float kx = 1f - (float) Math.exp(-f / camTauXZ);
            float ky = 1f - (float) Math.exp(-f / camTauY);
            this.camPos.x += (camTargetX - this.camPos.x) * kx;
            this.camPos.z += (camTargetZ - this.camPos.z) * kx;
            this.camPos.y += (camTargetY - this.camPos.y) * ky;
        }
        // 相机不许低于世界底面（y=1 = 可站面；y=0 是 BEDROCK 外壳）。
        // 理由：眼位一旦低于所有区块的底面，四周再无任何地形几何 → 整屏被天空盒/雾/方向辉光填满，
        // 即用户报的"掉进里世界后被强光糊脸"。这是纯渲染层护栏（不改玩家物理、不进指纹）：
        // 玩家本身会由 Player.rescueFromVoid 拉回，相机在那一帧之前不再把"虚空"渲染成满屏强光。
        if (this.camPos.y < 1.0f) this.camPos.y = 1.0f;
        // P0④：落地下沉（软着陆感）—— landTimer 期间相机轻微下沉
        if (this.landTimer > 0f) this.camPos.y -= 0.10f * (this.landTimer / 0.22f);
        // P0④：受击抖动改 time 驱动（旧用 frameCount → 517fps 下抖动快到看不见，且帧率无关性丢失）
        if ((f2 = (this.hurtFlash + this.killFlash) * 0.35f + (this.hitstop > 0.0f ? 0.22f : 0.0f)) > 0.001f) {
            this.camPos.x += (float)Math.sin(this.time * 62.0f) * f2;
            this.camPos.y += (float)Math.cos(this.time * 84.0f) * f2;
        }
        if (this.sim.player.level > this.lastLevel) {
            this.lastLevel = this.sim.player.level;
            this.levelUpTimer = 2.0f;
            this.sfx(Sfx.LEVELUP);
        }
        this.levelUpTimer = Math.max(0.0f, this.levelUpTimer - fxDt);
        int n5 = world.beasts.size();
        boolean bl10 = bl = this.prevBeasts >= 0 && n5 < this.prevBeasts;
        if (bl) {
            this.killFlash = 0.8f;
            this.hitstop = Math.max(this.hitstop, 0.11f);   // P0②：击杀档（0.065 → 0.11，配合屏震分级）
            this.shake(0.30f);
            this.spawnSoulMotes(this.sim.player.x, this.sim.player.y + 1.0f, this.sim.player.z);
            this.sfx(Sfx.KILL);
            this.unlockAch("FIRST KILL");
        }
        this.prevBeasts = n5;
        // P0②：假击退衰减（纯渲染层）。kbProg 1→0 用 0.18 s，位移按 (1-kbProg) 抛物线：
        // 先被推开的冲刺段（sin 前段）再回弹归位（sin 后段），整体是"被打退 + 站稳"。
        // 走 fxDt → QA 冻结时不动，无头截图可复现；kbProg/kbDir/kbDist 是 Beast 渲染字段，不进 hashState。
        for (Beast bk : world.beasts) {
            if (bk.kbProg > 0f) bk.kbProg = Math.max(0f, bk.kbProg - fxDt / 0.18f);
        }
        int n6 = 0;
        for (Beast beast4 : world.beasts) {
            n6 += Math.max(0, beast4.hp);
        }
        if (this.prevBeastHp >= 0 && n6 < this.prevBeastHp && !bl) {
            this.sfx(Sfx.HIT);
            // 画面细腻度：命中标记（准星 X）+ 漂浮伤害数字（本帧敌兵总血量降幅 = 这一击打掉多少）
            this.hitMarkTimer = 0.22f;
            this.pushDamageNumber(Math.max(1, this.prevBeastHp - n6));
            // P0②：普通命中顿帧 + 轻屏震（旧实现普通命中既无顿帧也无屏震 → "打中没感觉"）。
            // 分级：普通 0.045 / 击杀 0.11 —— 让"手感重量"逐级可读。
            this.hitstop = Math.max(this.hitstop, 0.045f);
            this.shake(0.10f);
            // P0②：命中火花 —— 在**最近那只 kbProg=1 的敌兵**处喷火花（复用处决的 spawnSparks）。
            // 只在渲染层发射粒子，不改世界、不耗 sim RNG（fx 管线有独立随机源）。
            for (Beast hb : world.beasts) {
                if (hb.kbProg >= 1f) {
                    this.spawnSparks(hb.x, hb.y + 0.7f, hb.z);
                    break;
                }
            }
        }
        this.prevBeastHp = n6;
        int n7 = this.sim.player.hp;
        if (this.prevHp >= 0 && n7 < this.prevHp) {
            this.hurtFlash = 0.8f;
            this.sfx(Sfx.HURT);
        }
        this.prevHp = n7;
        // QA：以下演出计时器已在上面统一用 fxDt 冻结（见该处注释）。
        this.killFlash = Math.max(0.0f, this.killFlash - fxDt);
        this.hurtFlash = Math.max(0.0f, this.hurtFlash - fxDt);
        this.skillFlash = Math.max(0.0f, this.skillFlash - fxDt);
        if (this.parryFxTimer > 0.0f) this.parryFxTimer = Math.max(0.0f, this.parryFxTimer - fxDt);
        if (this.executeFxTimer > 0.0f) this.executeFxTimer = Math.max(0.0f, this.executeFxTimer - fxDt);
        this.collectDrops();                 // MC 手感：走近掉落物自动吸入背包
        if (this.hitMarkTimer > 0.0f) this.hitMarkTimer = Math.max(0.0f, this.hitMarkTimer - fxDt);
        for (int i = 0; i < 8; i++) if (this.dmgT[i] > 0.0f) this.dmgT[i] = Math.max(0.0f, this.dmgT[i] - fxDt);
        if (this.achBannerTimer > 0.0f) this.achBannerTimer = Math.max(0.0f, this.achBannerTimer - fxDt);
        if (this.sim.player.onGround && !this.prevOnGround) {
            this.spawnDust(this.sim.player.x, this.sim.player.y, this.sim.player.z);
            this.sfx(Sfx.LAND);
            this.landTimer = 0.22f;
        } else if (!this.sim.player.onGround && this.prevOnGround) {
            this.sfx(Sfx.JUMP);
        }
        this.prevOnGround = this.sim.player.onGround;
        int n8 = 0;
        for (World.Shrine object2 : world.shrines) {
            if (!object2.claimed) continue;
            ++n8;
        }
        if (n8 > this.prevShrineClaimed) {
            for (World.Shrine shrine : world.shrines) {
                if (!shrine.claimed) continue;
                this.bannerText = "ABILITY AWAKENED: " + shrine.ability;
            }
            this.bannerTimer = 2.6f;
            this.sfx(Sfx.UNLOCK);
        }
        this.prevShrineClaimed = n8;
        for (Trials.Site site : world.trials.sites) {
            if (!site.claimed || !this.announcedRelics.add(site.ability)) continue;
            this.bannerText = "RELIC: " + site.ability + "   (" + world.trials.claimedSites() + "/" + world.trials.sites.size() + ")";
            this.bannerTimer = 2.6f;
            this.sfx(Sfx.RELIC);
        }
        if (world.trials.heartClaimed && !this.prevHeartClaimed) {
            this.bannerText = "WORLD HEART CLAIMED";
            this.bannerTimer = 3.4f;
            this.sfx(Sfx.HEART);
        }
        this.prevHeartClaimed = world.trials.heartClaimed;
        // P0-4：守卫苏醒 / 倒下 —— 终局的两个高潮点，必须有即时反馈（横幅 + 音效）。
        boolean wardenAlive = false;
        for (Beast bb : world.beasts) if (bb.type == Beast.TYPE_BOSS) { wardenAlive = true; break; }
        if (wardenAlive && !this.prevWardenAlive) {
            this.bannerText = "THE SUMMIT WARDEN AWAKENS";
            this.bannerTimer = 3.6f;
            this.sfx(Sfx.UNLOCK);
        } else if (!wardenAlive && this.prevWardenAlive) {
            this.bannerText = "THE SUMMIT WARDEN FALLS - THE HEART IS OPEN";
            this.bannerTimer = 3.6f;
            this.sfx(Sfx.HEART);
        }
        this.prevWardenAlive = wardenAlive;
        this.toastTimer = Math.max(0.0f, this.toastTimer - fxDt);
        this.bannerTimer = Math.max(0.0f, this.bannerTimer - fxDt);
        this.updateParticles(fxDt);
    }

    private void drawFrame() {
        // P1（2026-09-23）：每帧重置 MSDF 字形的"钉住"标记。
        // 必须在**任何** MsdfFont.draw 之前调用：本帧用到的槽在淘汰时会被跳过，
        // 否则同一帧内先发射过顶点的字形可能被后来的字覆盖掉（画面出现"半句新半句旧"）。
        MsdfFont.beginFrame();
        // 单帧描边样式（标题用细白描边把字从深色背景里拔出来）；改这里即可全通道调整。
        MsdfFont.setOutline(2.0f, 0.05f, 0.06f, 0.09f);
        // 注：本方法内 f2/f3/f4/f5/f6 为 CFR 遗留的局部复用临时变量（后续分块还原时再改名）：
        //   f5/f6 先用于出生点偏移判定，后分别被赋值为 starAlpha / ambient；
        //   f2/f3/f4 在区块循环与射线框绘制中复用为临时 dx/dy/dz。语义以每次赋值处为准。
        float f;
        float f2;
        float f3;
        float f4;
        int n;
        int n2;
        float f5;
        float f6;
        float f7;
        long l = System.nanoTime();
        if (this.lastFrameNanos > 0L && (f7 = (float)(l - this.lastFrameNanos) / 1.0E9f) > 1.0E-4f) {
            if (!this.qaFrozen) this.fpsEma = this.fpsEma * 0.95f + 1.0f / f7 * 0.05f;   // QA：冻结帧率读数
        }
        this.lastFrameNanos = l;
        GL33.glClear((int)17664);
        GL33.glEnable((int)2929);
        Matrix4f matrix4f = this.vpFrame;
        this.proj.mul((Matrix4fc)this.view, matrix4f);
        World world = this.sim.world;
        float dayPhase = DayCycle.phase(world.tick);
        boolean isNight = DayCycle.isNight(world.tick);
        if (isNight != this.prevNight) {
            this.prevNight = isNight;
            this.bannerText = isNight ? "\u591c\u5e55\u964d\u4e34 \u00b7 \u91ce\u517d\u5728\u9ed1\u6697\u4e2d\u6e38\u8361" : "\u5929\u4eae\u4e86 \u00b7 \u6751\u5e84\u9192\u6765";
            this.bannerTimer = 3.2f;
        }
        if (!this.spawnHintDone) {
            this.spawnHintDone = true;
            f6 = this.sim.player.x - (float)world.SX / 2.0f;
            f5 = this.sim.player.z - (float)world.SZ / 2.0f;
            boolean inSafeZone = f6 * f6 + f5 * f5 <= 576.0f;
            this.bannerText = inSafeZone ? "\u4f60\u5728\u6751\u5e84\u5e87\u62a4\u6240\u5185 \u00b7 \u91ce\u517d\u4e0d\u6562\u9760\u8fd1\uff08\u7eff\u70b9=\u6751\u5fc3\uff09" : "\u7f57\u76d8\u91d1\u70b9\u6307\u5f15\u796d\u575b \u00b7 \u7eff\u70b9\u6307\u5f15\u6751\u5e84";
            this.bannerTimer = 5.0f;
        }
        DayCycle.skyTop(dayPhase, this.dcTop);
        DayCycle.skyHorizon(dayPhase, this.dcHor);
        // 雾色去饱和 + 向天顶色收敛（2026-09-21 修「黎明整屏橙红糊脸」）。
        // 根因：SKY_HORIZON 的晨昏帧是强暖橙 (0.95,0.52,0.25)，而 uFogColor 直接用它 →
        // 一旦雾占比高（晨昏密度偏高 + 雨天 +0.022），整个屏幕被染成橙红，地表细节被吃掉。
        // 真实大气不是这样的：暖色只集中在太阳方向的地平线**一条带**上，其余方向是天空散射色。
        // 做法（对标 MC FogRenderer：fogColor = skyColor*(1-blend) + sunsetColor*blend）：
        //   ① 先与天顶色按 0.35 混合 —— 把"地平线暖带"摊薄成全天空的中间调；
        //   ② 再把饱和度压到 0.62 —— 雾是散射介质，不该比天光本身更艳。
        // 这是纯渲染层改动，只影响 uFogColor/uSkyHorizon 的取值，不进任何仿真状态或指纹。
        float horLum = this.dcHor[0] * 0.2126f + this.dcHor[1] * 0.7152f + this.dcHor[2] * 0.0722f;
        for (int ci = 0; ci < 3; ci++) {
            float mixed = this.dcHor[ci] * 0.65f + this.dcTop[ci] * 0.35f;
            this.dcHor[ci] = horLum + (mixed - horLum) * 0.62f;
        }
        DayCycle.lightDir(dayPhase, this.dcDir);
        DayCycle.lightTint(dayPhase, this.dcTint);
        DayCycle.skyFillColor(dayPhase, this.dcSky);
        // 泰拉瑞亚缺口① 配套：月全食/深夜时 lightTint 可能接近 0，而 world shader 把环境光也乘了 uLightTint，
        // 导致整个地形被抹成纯黑。给主光源颜色加一个下限，保证几何始终可辨（纯渲染，零漂移）。
        float tintLen = (float) Math.sqrt(this.dcTint[0] * this.dcTint[0] + this.dcTint[1] * this.dcTint[1] + this.dcTint[2] * this.dcTint[2]);
        if (tintLen < 0.22f && tintLen > 1e-6f) {
            float s = 0.22f / tintLen;
            this.dcTint[0] *= s; this.dcTint[1] *= s; this.dcTint[2] *= s;
        }
        // QA 2026-09-15：真实天体——太阳走真实日弧（正午过天顶、日出日落贴地平线、夜里落到地平线下），
        // 月球有自己的轨道（每天晚升 ~49 分钟）+ 黄纬（决定能否成食）。纯读，零漂移。
        core.world.Celestial.sunDir(world.tick, this.celSun);
        core.world.Celestial.moonDir(world.tick, this.celMoon);
        core.world.Celestial.lightDir(world.tick, this.celLight);
        // 食：进入/退出时挂横幅（只读判定；不进仿真）
        core.world.Celestial.Eclipse celNow = core.world.Celestial.eclipse(this.celMoon, this.celSun);
        if (celNow != this.prevEclipse) {
            if (core.world.Celestial.notableEclipse(celNow)) {
                this.bannerText = core.world.Celestial.eclipseName(celNow);
                this.bannerTimer = 4.2f;
                this.sfx(Sfx.UNLOCK);
            }
            this.prevEclipse = celNow;
        }
        // 下一次月食/日食预报：每个游戏日算一次（前向扫描是纯读，不影响仿真）
        int celDay = DayCycle.dayNumber(world.tick);
        if (celDay != this.eclipseCacheDay) {
            this.eclipseCacheDay = celDay;
            this.eclipseNextLunar = core.world.Celestial.nextLunarEclipse(world.tick, 90, 600);
            this.eclipseNextSolar = core.world.Celestial.nextSolarEclipse(world.tick, 400, 600);
        }
        f6 = DayCycle.ambient(dayPhase);
        // 夜色下限：保证几何始终可辨（纯渲染，不改仿真）。
        // 2026-09-18：0.20 → 0.32 —— 月全食/雨夜/新 autotile 边缝暗化叠加后，地形仍会全黑。
        if (f6 < 0.32f) f6 = 0.32f;
        f5 = DayCycle.starAlpha(dayPhase);
        // QA：-Dbw.rain=1 强制降雨（无头截图复现"雨夜糊屏"用）。
        // 2026-09-21：实机截图证明"雨夜整屏泛白"是无头截图<b>复现不出</b>的路径，导致此前若干轮
        // 美术改动在门禁上"全绿"却在实机上不可接受 —— 仪器测的是一个不存在的游戏。加此旋钮固定该缺陷。
        boolean rainNow = world.raining || rainQa > 0.5f;
        // ⚠️ 2026-09-29（§18 修既有 QA 缺陷）：平滑量是**每帧累积**的（*0.04）⇒ 其结果取决于
        // "加载稳态触发前究竟跑了几帧"，而那个帧数随机器负载波动 ⇒ **雨景截图两次运行不同**
        // （实测 `noon_rain` / `night_rain` 交替漂移，而 4 个非雨场景逐字节稳定）。
        // 这与"冻结仿真 / 冻结演出时钟 / 冻结泛光记忆 / 退回同步光照"是同一纪律：
        // **QA 下必须让跨帧累积量取确定的终值**。故冻结时直接取目标值（等价于已收敛），
        // 不冻结时保留真实的渐变手感（雨起/雨停的过渡）。
        if (this.qaFrozen) {
            this.rainSmooth = rainNow ? 1.0f : 0.0f;
        } else {
            this.rainSmooth += ((rainNow ? 1.0f : 0.0f) - this.rainSmooth) * 0.04f;
            if (this.rainSmooth < 0.002f) {
                this.rainSmooth = 0.0f;
            }
        }
        // 雨天加雾：0.01 → 0.022 —— 原值几乎看不出差别，雨天只剩「天色变暗」，
        // 玩家分不清是下雨还是入夜（雨丝见 drawRainOverlay）。
        // ⚠️ 2026-09-28（学 MC）：**雨天必须压暗雾色**，否则"亮雾糊屏"。
        // MC 的天气环境属性是 `visual/fog_color multiply [1.0, 0.5, 0.5, 0.6]` —— R 保持、**G/B 减半**
        // ⇒ 雾变暗且偏暖红。本项目此前只加了雾的**浓度**（+0.022）却没动**颜色**，于是当地平线色
        // 因晨昏/月食偏亮时，整屏被亮雾糊死（用户实机报的"雨夜糊屏"；该缺陷此前的 QA 截图
        // 用晴天路径复现不出，见 `bw.rain` 旋钮的来历）。MC 的雨天**只会更暗**，绝不会发白。
        float rainFogK = 1.0f - 0.5f * this.rainSmooth;
        this.dcHor[1] *= rainFogK;                       // G 减半（与 MC 的 [1.0,0.5,0.5] 一致）
        this.dcHor[2] *= rainFogK;                       // B 减半
        float fogDensity = DayCycle.fogDensity(dayPhase) + 0.022f * this.rainSmooth;
        // MC FogRenderer 的 FOG_TERRAIN 模型：雾在"区块渲染边界"处刚好饱和（end = renderDistance），
        // 于是边界外的东西被雾吞掉、看不见。我们的世界是以玩家为中心的流式窗口，窗口外即虚空；
        // 把雾的饱和距离钉在窗口边缘内缩处，就能把"80 格外露出天空盒/月亮"这个缺口彻底盖住。
        // 最坏情况：玩家在中心块末端，距最近边缘 ≈ 半宽 − 一块；再内缩 3 格留余量（防边缘那列先穿帮）。
        float fogFarDist = Math.max(24.0f, 0.5f * (float)world.SX - (float)core.world.World.CHUNK - 3.0f);
        // 水下程度（学 MC FogRenderer：`camera.getFluidInCamera() == WATER`）。眼位所在方块是水 → 1，否则 0，
        // 帧间做指数平滑，避免刚入水时雾色/视距瞬跳。QA：-Dbw.under=1 可强制为水下（无头截图验证用）。
        boolean eyeInWater = world.getBlock(
                (int)Math.floor(this.camPos.x), (int)Math.floor(this.camPos.y), (int)Math.floor(this.camPos.z))
                == core.world.Blocks.index("WATER");
        if (System.getProperty("bw.under") != null) {
            this.underAmt = 1.0f;            // QA：强制满水下（直接置位，保证第 5 帧截图就是水下状态）
        } else {
            this.underAmt += ((eyeInWater ? 1.0f : 0.0f) - this.underAmt) * 0.16f;
            if (this.underAmt < 0.002f) this.underAmt = 0.0f;
        }
        DayCycle.waterFogColor(this.dcWater);
        float ambientWithRain = f6 * (1.0f - 0.24f * this.rainSmooth);   // MC: sky_light_factor 1.0→0.7625（-24%）
        this.skyFwd[0] = -this.view.m02();
        this.skyFwd[1] = -this.view.m12();
        this.skyFwd[2] = -this.view.m22();
        this.skyRight[0] = this.view.m00();
        this.skyRight[1] = this.view.m10();
        this.skyRight[2] = this.view.m20();
        this.skyUp[0] = this.view.m01();
        this.skyUp[1] = this.view.m11();
        this.skyUp[2] = this.view.m21();
        DayCycle.SkyBasis skyBasis = new DayCycle.SkyBasis(this.skyFwd, this.skyRight, this.skyUp, this.appliedFov, (float)this.W / (float)this.H);
        GL33.glDisable((int)2929);
        GL33.glUseProgram((int)this.skyShader);
        if (this.uSkyTop != -1) {
            GL33.glUniform3f((int)this.uSkyTop, (float)this.dcTop[0], (float)this.dcTop[1], (float)this.dcTop[2]);
        }
        if (this.uSkyHorizon != -1) {
            GL33.glUniform3f((int)this.uSkyHorizon, (float)this.dcHor[0], (float)this.dcHor[1], (float)this.dcHor[2]);
        }
        if (this.uSkyFwd != -1) {
            GL33.glUniform3f((int)this.uSkyFwd, (float)skyBasis.fx, (float)skyBasis.fy, (float)skyBasis.fz);
        }
        if (this.uSkyRight != -1) {
            GL33.glUniform3f((int)this.uSkyRight, (float)skyBasis.rx, (float)skyBasis.ry, (float)skyBasis.rz);
        }
        if (this.uSkyUp != -1) {
            GL33.glUniform3f((int)this.uSkyUp, (float)skyBasis.ux, (float)skyBasis.uy, (float)skyBasis.uz);
        }
        if (this.uSkyTan != -1) {
            GL33.glUniform2f((int)this.uSkyTan, (float)skyBasis.tanX, (float)skyBasis.tanY);
        }
        if (this.uSunDir != -1) {
            GL33.glUniform3f((int)this.uSunDir, (float)this.celSun[0], (float)this.celSun[1], (float)this.celSun[2]);
        }
        if (this.uSunColor != -1) {
            GL33.glUniform3f((int)this.uSunColor, (float)this.dcTint[0], (float)this.dcTint[1], (float)this.dcTint[2]);
        }
        if (this.uStar != -1) {
            GL33.glUniform1f((int)this.uStar, (float)(f5 * (1.0 - this.rainSmooth)));
        }
        if (this.uRain != -1) {
            GL33.glUniform1f((int)this.uRain, (float)this.rainSmooth);
        }
        if (this.uSkyRes != -1) {
            GL33.glUniform2f((int)this.uSkyRes, (float)this.W, (float)this.H);
        }
        if (this.uSkyTime != -1) {
            GL33.glUniform1f((int)this.uSkyTime, (float)this.time);
        }
        if (this.uSkyDither != -1) {
            GL33.glUniform1f((int)this.uSkyDither, this.ditherAmt);
        }
        if (this.uSkyExposure != -1) {
            GL33.glUniform1f((int)this.uSkyExposure, this.exposureValue);
        }
        // 水下：天空盒整体偏向水体色（MC 水下时 clearColor = 水雾色，整屏底色就是水色；我们的天空盒
        // 不走雾，所以把这一步显式做在天空 FS 里，叠加层只补"流动水纹"）。
        if (this.uSkyUnder != -1) {
            GL33.glUniform1f((int)this.uSkyUnder, this.underAmt);
        }
        if (this.uSkyWaterCol != -1) {
            GL33.glUniform3f((int)this.uSkyWaterCol, this.dcWater[0], this.dcWater[1], this.dcWater[2]);
        }
        // 云层噪声纹理绑定到单元 2（0=图集, 1=法线图，均属 world pass）。绑完把活动单元复位到 0。
        if (this.uSkyNoise != -1 && this.noiseTex != -1) {
            GL33.glUniform1i((int)this.uSkyNoise, 2);
            GL33.glActiveTexture(33986);
            GL33.glBindTexture(3553, this.noiseTex);
            GL33.glActiveTexture(33984);
        }
        // 天空只是背景：必须关深度写！全屏三角 gl_Position.z=0 → 窗口深度 0.5，
        // 而地形在 near=0.1/far=320 下深度≈0.99 → GL_LESS 全失败 → 整片地形被天空挡掉、画不出来
        // （现象：屏幕上只剩天空+掉落物，地形全"消失"）。写完立刻恢复，供随后地形写深度做遮挡。
        GL33.glDepthMask((boolean)false);
        GL33.glBindVertexArray((int)this.skyVAO);
        GL33.glDrawArrays((int)4, (int)0, (int)3);
        GL33.glBindVertexArray((int)0);
        GL33.glEnable((int)2929);
        GL33.glDepthMask((boolean)true);
        GL33.glDisable((int)3042);
        this.drawCelestial(skyBasis, matrix4f);   // QA 2026-09-15：月盘/相位/本影食/日食掩星
        // 分帧切片（Terraria EngineState 泛化）：一帧只做一件事，把"编辑时全量重算光照"
        // 摊到环里的某一帧，避免连续挖放时每帧重建（旧行为 = 每次编辑都同步重建）。
        // 注意：lightDirty 保持为真直到真正轮到该槽位 → 不丢帧（分帧不改变最终结果）。
        //
        // QA 例外（{@code -Dbw.snap}）：无头截图在"加载稳态"触发，而分帧让"第 N 帧时光照是否已收敛"
        // 取决于前序帧的相位 → 截图不可复现（实测 93% 像素不同）。与"冻结仿真/时钟/失焦"同一纪律：
        // **QA 下退回同步重建**（等价于分帧改造前的行为），保证截图仍是逐字节可比的 A/B 仪器。
        int slice = this.qaFrozen ? 0 : this.slicer.next();
        if (slice == 0 && world.lightDirty && !world.isShifting()) {
            world.computeLight();
            world.lightDirty = false;
            // P4：方块变了 / 窗口滑动了 → 体素占用纹理同源标脏（复用同一个脏标记，不引入第二个状态）
            this.shadowOccDirty = true;
        }
        if (this.shadowOccDirty) {
            this.uploadShadowVolume();
        }
        GL33.glUseProgram((int)this.worldShader);
        this.vpBuf.clear();
        for (int i = 0; i < 4; ++i) {
            for (n2 = 0; n2 < 4; ++n2) {
                this.vpBuf.put(matrix4f.get(i, n2));
            }
        }
        this.vpBuf.flip();
        if (this.worldVP != -1) {
            GL33.glUniformMatrix4fv((int)this.worldVP, (boolean)false, (FloatBuffer)this.vpBuf);
        }
        if (this.uLightDir != -1) {
            GL33.glUniform3f((int)this.uLightDir, (float)this.celLight[0], (float)this.celLight[1], (float)this.celLight[2]);
        }
        if (this.uTime != -1) {
            GL33.glUniform1f((int)this.uTime, (float)this.time);
        }
        if (this.uDither != -1) {
            GL33.glUniform1f((int)this.uDither, this.ditherAmt);
        }
        if (this.uA2C != -1) {
            GL33.glUniform1f((int)this.uA2C, this.a2cAmount);
        }
        if (this.uExposure != -1) {
            GL33.glUniform1f((int)this.uExposure, this.exposureValue);
        }
        if (this.uCamPos != -1) {
            GL33.glUniform3f((int)this.uCamPos, (float)this.camPos.x, (float)this.camPos.y, (float)this.camPos.z);
        }
        if (this.uFogColor != -1) {
            GL33.glUniform3f((int)this.uFogColor, (float)this.dcHor[0], (float)this.dcHor[1], (float)this.dcHor[2]);
        }
        if (this.uFogDensity != -1) {
            GL33.glUniform1f((int)this.uFogDensity, (float)fogDensity);
        }
        if (this.uFogScale != -1) {
            // 夜间雾尺降 0.4：避免远景地形被雾糊成天空色而"消失"；白天保持 1.0。
            float nightFactor = DayCycle.nightFactor(dayPhase);
            GL33.glUniform1f((int)this.uFogScale, (float)(0.38f + 0.62f * (1.0f - nightFactor)));
        }
        if (this.uFogFar != -1) {
            GL33.glUniform1f((int)this.uFogFar, (float)fogFarDist);
        }
        // UE 指数高度雾：参考高度取 SY*0.55（贴着常见地表高度）→ 常见高度上雾量与原调参一致，
        // 低处（谷地/水面）按 2^(-k·Δz) 变浓、高处变薄。
        if (this.uFogFalloff != -1) {
            GL33.glUniform1f((int)this.uFogFalloff, 0.028f);
        }
        if (this.uFogBase != -1) {
            GL33.glUniform1f((int)this.uFogBase, (float)world.SY * 0.55f);
        }
        // UE 方向性内散射：颜色取当前天体色（白天=太阳、夜里=月亮，自动跟随），
        // 指数用 UE 组件默认值 4.0（`DirectionalInscatteringExponent = 4.0f`）；
        // StartDistance 按本作尺度折算（UE 默认 10000cm=100m，我们窗口半宽仅 61 格）→ 10 格起。
        // ---- P4 体素软阴影：uniform + 占用纹理绑到单元 3（0=图集 1=法线 2=云噪声）----

        // 距离淡出 26~64 格：近景付步进成本、远景直接返回"全亮"。这不是偷懒而是必要优化 ——

        // 体素渲染有大量 overdraw（FS 里的 discard 让 early-Z 失效），让每像素无差别地跑 14 步

        // 会在远视野下白白烧掉几倍带宽。

        if (this.uShadowStrength != -1) {

            GL33.glUniform1f((int)this.uShadowStrength, this.shadowStrength);

        }

        if (this.uShadowSize != -1) {

            GL33.glUniform3f((int)this.uShadowSize, (float)world.SX, (float)world.SY, (float)world.SZ);

        }

        if (this.uShadowSoft != -1) {

            GL33.glUniform1f((int)this.uShadowSoft, 0.85f);

        }

        if (this.uAoStrength != -1) {

            GL33.glUniform1f((int)this.uAoStrength, this.aoStrength);

        }

        if (this.uShaftAmount != -1) {

            GL33.glUniform1f((int)this.uShaftAmount, this.shaftAmount);

        }

        if (this.uShadowFall != -1) {

            GL33.glUniform1f((int)this.uShadowFall, 0.22f);

        }

        if (this.uShadowFade0 != -1) {

            GL33.glUniform1f((int)this.uShadowFade0, 4.0f);

        }

        if (this.uShadowFade1 != -1) {

            GL33.glUniform1f((int)this.uShadowFade1, 56.0f);

        }

        if (this.uShadowTex != -1 && this.shadowTex3D != -1) {

            GL33.glUniform1i((int)this.uShadowTex, 3);

            GL33.glActiveTexture(33987);                     // GL_TEXTURE3

            GL33.glBindTexture(GL33.GL_TEXTURE_3D, this.shadowTex3D);

            GL33.glActiveTexture(33984);                     // 复位 GL_TEXTURE0

        }

        if (this.uDirInsc != -1) {
            // 增益做成可覆盖旋钮：既方便调参，也让"方向性内散射到底贡献了多少"能做受控 A/B
            // （同一版本跑 -Dbw.dirinsc=0 与默认值，两图相减即为该项的净贡献）。
            float gain = 0.55f;
            String g = System.getProperty("bw.dirinsc");
            if (g != null) {
                try { gain = Float.parseFloat(g); } catch (NumberFormatException ignored) { }
            }
            GL33.glUniform3f((int)this.uDirInsc,
                    this.dcTint[0] * gain, this.dcTint[1] * gain, this.dcTint[2] * gain);
        }
        if (this.uDirInscExp != -1) {
            GL33.glUniform1f((int)this.uDirInscExp, 4.0f);
        }
        // 方向：白天朝太阳、夜里朝月亮（uLightDir 恒为太阳方向，夜里在地平线下，不能直接用）
        if (this.uDirInscDir != -1) {
            float[] d = (this.celSun[1] > 0.0f) ? this.celSun : this.celMoon;
            GL33.glUniform3f((int)this.uDirInscDir, d[0], d[1], d[2]);
        }
        if (this.uDirInscStart != -1) {
            GL33.glUniform1f((int)this.uDirInscStart, 10.0f);
        }
        if (this.uUnder != -1) {
            GL33.glUniform1f((int)this.uUnder, this.underAmt);
        }
        if (this.uWaterCol != -1) {
            GL33.glUniform3f((int)this.uWaterCol, this.dcWater[0], this.dcWater[1], this.dcWater[2]);
        }
        if (this.uDetailOrg != -1) {
            float f11 = 0.003125f;
            GL33.glUniform2f((int)this.uDetailOrg, (float)(282.0f * f11), (float)(302.0f * f11));
            GL33.glUniform2f((int)this.uDetailCell, (float)(16.0f * f11), (float)(16.0f * f11));
        }
        if (this.uAmbient != -1) {
            GL33.glUniform1f((int)this.uAmbient, (float)ambientWithRain);
        }
        if (this.uLightTint != -1) {
            GL33.glUniform3f((int)this.uLightTint, (float)this.dcTint[0], (float)this.dcTint[1], (float)this.dcTint[2]);
        }
        if (this.uSkyColor != -1) {
            GL33.glUniform3f((int)this.uSkyColor, (float)this.dcSky[0], (float)this.dcSky[1], (float)this.dcSky[2]);
        }
        if (this.uTorchR != -1) {
            GL33.glUniform1f((int)this.uTorchR, this.heldTorch ? this.torchRadius : 0.0f);
        }
        if (this.uTorchCol != -1) {
            GL33.glUniform3f((int)this.uTorchCol, 1.0f, 0.55f, 0.22f);
        }
        if (this.uWorldRes != -1) {
            GL33.glUniform2f((int)this.uWorldRes, (float)this.W, (float)this.H);
        }
        if (this.uTex != -1) {
            GL33.glUniform1i((int)this.uTex, (int)0);
            GL33.glActiveTexture((int)33984);
            GL33.glBindTexture((int)3553, (int)this.atlasTex);
        }
        if (this.uNrm != -1 && this.nrmTex != -1) {
            GL33.glUniform1i((int)this.uNrm, (int)1);
            GL33.glActiveTexture((int)33985);
            GL33.glBindTexture((int)3553, (int)this.nrmTex);
        }
        Game.extractFrustumPlanes(matrix4f, this.frustumReuse);
        Vector4f[] vector4fArray = this.frustumReuse;
        this.occludedChunks = 0;
        this.drawnChunks = 0;
        this.drawnFaces = 0;
        this.transChunks.clear();
        // 第三十二批 A2：**这里不再重建任何区块**。原先"每帧在主线程重建 N 块"（5~15ms/块）
        // 已整体搬到 MeshBuilder 的 worker 池 —— 见 Game.updateMeshes()（在 drawFrame 之前调用），
        // 它负责提交脏块 + 把已完成结果上传。绘制这一遍只读 faceCount/maxY，不产生网格。
        for (n = 0; n < this.chunkX; ++n) {
            for (int i = 0; i < this.chunkZ; ++i) {
                Chunk chunk = this.chunks[n][i];
                // 注意：含水的区块可能 faceCount==0（不透明面为 0）而 faceCountT>0 → 两个都要判
                if ((chunk.faceCount <= 0 && chunk.faceCountT <= 0) || (f4 = (f3 = (float)(n * 16) + 8.0f) - this.camPos.x) * f4 + (f2 = (f = (float)(i * 16) + 8.0f) - this.camPos.z) * f2 > 16384.0f || Game.aabbOutsideFrustum(vector4fArray, n * 16, 0.0f, i * 16, n * 16 + 16, world.SY, i * 16 + 16)) continue;
                int n3 = chunk.maxY + 1;
                if ((float)n3 < this.camPos.y - 4.0f && Game.terrainOccluded(this.camPos.x, this.camPos.y + 1.6f, this.camPos.z, (float)(n * 16) + 8.0f, n3, (float)(i * 16) + 8.0f, this.chunks, this.chunkX, this.chunkZ)) {
                    ++this.occludedChunks;
                    continue;
                }
                if (chunk.vao != 0 && chunk.faceCount > 0) {
                    GL33.glBindVertexArray((int)chunk.vao);
                    // 第三十八批：本块网格可能是**平移时复用**来的（顶点还停在烘焙帧）⇒ 每块设一次偏移。
                    // 偏移恒为 0（没有发生过平移）时与改动前的着色结果逐字节相同。
                    if (this.uChunkShift != -1) GL33.glUniform2f(this.uChunkShift, (float)chunk.meshOffX, (float)chunk.meshOffZ);
                    // 索引化绘制：每四边形 4 顶点，索引数 = 四边形数 * 6（几何与旧 6 顶点发射逐三角形一致）
                    GL33.glDrawElements((int)4, (int)(chunk.faceCount * 6), (int)5125, 0L);
                    ++this.drawnChunks;
                    this.drawnFaces += chunk.faceCount;
                }
                if (chunk.faceCountT > 0 && chunk.vaoT != 0) this.transChunks.add(chunk);   // 透明 pass 留到最后
            }
        }
        GL33.glBindVertexArray((int)0);
        // MC 破坏阶段裂纹：不透明之后、半透明之前（挡在方块前的水/玻璃仍能正确混在上层）。
        this.drawCrackOverlay();
        // —— 透明 pass（水/玻璃）：不透明画完之后，开混合、关深度写、按「远→近」绘以保证背后先着色 ——
        if (!this.transChunks.isEmpty()) {
            final float camX = this.camPos.x, camZ = this.camPos.z;
            java.util.Collections.sort(this.transChunks, this.transChunkCmp);
            GL33.glEnable((int)3042);                       // GL_BLEND
            GL33.glBlendFunc((int)770, (int)771);           // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
            GL33.glDepthMask((boolean)false);
            for (Chunk c : this.transChunks) {
                GL33.glBindVertexArray(c.vaoT);
                if (this.uChunkShift != -1) GL33.glUniform2f(this.uChunkShift, (float)c.meshOffX, (float)c.meshOffZ);
                GL33.glDrawElements((int)4, (int)(c.faceCountT * 6), (int)5125, 0L);   // 索引化
                this.drawnFaces += c.faceCountT;
            }
            GL33.glBindVertexArray((int)0);
            GL33.glDepthMask((boolean)true);
            GL33.glDisable((int)3042);
        }
        // 渲染诊断：每秒输出一次，区分"地形全黑"与"区块没绘制"
        if (this.time - this.lastDiagLog > 1.0f) {
            this.lastDiagLog = this.time;
            this.logPhase("WORLD_DRAWN chunks=" + this.drawnChunks + " faces=" + this.drawnFaces + " occluded=" + this.occludedChunks + " ambient=" + ambientWithRain + " tintLen=" + tintLen + " camY=" + this.camPos.y);
        }
        ++this.silFrame;
        if (Math.abs(this.camPos.x - this.lastSilX) > 64.0f || Math.abs(this.camPos.z - this.lastSilZ) > 64.0f || this.silFrame >= 15) {
            this.silhouettes.update(world, this.camPos.x, this.camPos.z, this.menu.viewDist);
            this.lastSilX = this.camPos.x;
            this.lastSilZ = this.camPos.z;
            this.silFrame = 0;
        }
        if (this.uFogScale != -1) {
            GL33.glUniform1f((int)this.uFogScale, (float)0.35f);
        }
        if (this.uFogFar != -1) {
            // 剪影本就画在视距处当"远景暗示"，别被窗口雾提前吃掉 → 临时把雾饱和推到视距。
            GL33.glUniform1f((int)this.uFogFar, (float)this.menu.viewDist);
        }
        this.silhouettes.draw(this.camPos.x, this.camPos.z, world.SY >= 96);
        if (this.uFogScale != -1) {
            GL33.glUniform1f((int)this.uFogScale, (float)1.0f);
        }
        if (this.uFogFar != -1) {
            GL33.glUniform1f((int)this.uFogFar, (float)fogFarDist);
        }
        this.updateRayTarget();
        if (this.rayTarget != null && !this.menu.isOpen()) {
            if (this.lineVAO == 0) {
                this.lineVAO = GL33.glGenVertexArrays();
                this.lineVBO = GL33.glGenBuffers();
                GL33.glBindVertexArray((int)this.lineVAO);
                GL33.glBindBuffer((int)34962, (int)this.lineVBO);
                GL33.glVertexAttribPointer((int)0, (int)3, (int)5126, (boolean)false, (int)24, (long)0L);
                GL33.glEnableVertexAttribArray((int)0);
                GL33.glVertexAttribPointer((int)1, (int)3, (int)5126, (boolean)false, (int)24, (long)12L);
                GL33.glEnableVertexAttribArray((int)1);
                GL33.glBindVertexArray((int)0);
            }
            this.lineBuf.clear();
            float f12 = 0.004f;
            float f13 = (float)this.rayTarget[0] - f12;
            float f14 = (float)this.rayTarget[1] - f12;
            float f15 = (float)this.rayTarget[2] - f12;
            float f16 = (float)(this.rayTarget[0] + 1) + f12;
            f3 = (float)(this.rayTarget[1] + 1) + f12;
            f = (float)(this.rayTarget[2] + 1) + f12;
            f4 = 0.05f;
            f2 = 0.05f;
            float f17 = 0.08f;
            Game.lineEdge(this.lineBuf, f13, f14, f15, f16, f14, f15, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f16, f14, f15, f16, f14, f, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f16, f14, f, f13, f14, f, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f13, f14, f, f13, f14, f15, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f13, f3, f15, f16, f3, f15, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f16, f3, f15, f16, f3, f, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f16, f3, f, f13, f3, f, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f13, f3, f, f13, f3, f15, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f13, f14, f15, f13, f3, f15, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f16, f14, f15, f16, f3, f15, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f16, f14, f, f16, f3, f, f4, f2, f17);
            Game.lineEdge(this.lineBuf, f13, f14, f, f13, f3, f, f4, f2, f17);
            this.lineBuf.flip();
            GL33.glUseProgram((int)this.shader);
            this.setVP(matrix4f);
            GL33.glBindVertexArray((int)this.lineVAO);
            GL33.glBindBuffer((int)34962, (int)this.lineVBO);
            GL33.glBufferData((int)34962, (FloatBuffer)this.lineBuf, (int)35048);
            GL33.glDrawArrays((int)1, (int)0, (int)(this.lineBuf.remaining() / 6));
            GL33.glBindVertexArray((int)0);
        }
        // QA 2026-09-15：接触影总开关（SETTINGS > SHADOWS）。关闭后地面/方块边缘不再出现黑色影块。
        if (this.menu.shadowsOn) {
        GL33.glEnable((int)3042);
        GL33.glBlendFunc((int)770, (int)771);
        GL33.glDepthMask((boolean)false);
        GL33.glUseProgram((int)this.shadowShader);
        this.vpBuf.clear();
        for (n = 0; n < 4; ++n) {
            for (int i = 0; i < 4; ++i) {
                this.vpBuf.put(matrix4f.get(n, i));
            }
        }
        this.vpBuf.flip();
        // 实体阴影也用与 drawEntities 一致的 tick 间插值（entAlpha：暂停/hitstop 时冻结，不会突然回弹）
        final float entLerp = this.entAlpha;
        if (this.shadowUVP != -1) {
            GL33.glUniformMatrix4fv((int)this.shadowUVP, (boolean)false, (FloatBuffer)this.vpBuf);
        }
        // 阶段 C（安全版）：接触影随太阳方向偏移+拉长+软化（纯 Java，不改 GLSL，无黑屏风险）
        float sdx = -this.celLight[0]; float sdz = -this.celLight[2];
        float sl = (float)Math.sqrt((double)(sdx * sdx + sdz * sdz));
        if (sl < 1e-4f) { sdx = 0.0f; sdz = 1.0f; sl = 1.0f; }
        sdx /= sl; sdz /= sl;
        float elev = this.celLight[1]; if (elev < 0.05f) elev = 0.05f; if (elev > 1.0f) elev = 1.0f;
        float len = Math.min(2.2f, 1.0f + (1.0f - elev) * 1.6f);
        if (this.uShadowColor != -1) {
            GL33.glUniform3f((int)this.uShadowColor, (float)0.08f, (float)0.09f, (float)0.12f);
        }
        if (this.uShadowAlpha != -1) {
            GL33.glUniform1f((int)this.uShadowAlpha, (float)(0.15f * (0.55f + 0.45f * elev)));
        }
        this.shadowBuf.clear();
        this.addShadow(this.shadowBuf, this.sim.player.x, this.sim.player.y + 0.05f, this.sim.player.z, 0.5f, sdx, sdz, len);
        int bSIdx = 0;
        for (Beast beast : this.sim.world.beasts) {
            float bSx = beast.x, bSy = beast.y, bSz = beast.z;
            if (this.beastPrevRef != null && bSIdx < this.beastPrevCount && this.beastPrevRef[bSIdx] == beast) {
                bSx = this.beastPrevPos[bSIdx * 3] + (beast.x - this.beastPrevPos[bSIdx * 3]) * entLerp;
                bSy = this.beastPrevPos[bSIdx * 3 + 1] + (beast.y - this.beastPrevPos[bSIdx * 3 + 1]) * entLerp;
                bSz = this.beastPrevPos[bSIdx * 3 + 2] + (beast.z - this.beastPrevPos[bSIdx * 3 + 2]) * entLerp;
            }
            // P0②：假击退渲染偏移（与主绘制同源，保证影子跟形体一起退）
            float bSkb = kbCurve(beast.kbProg) * beast.kbDist;
            bSx += beast.kbDirX * bSkb; bSz += beast.kbDirZ * bSkb;
            ++bSIdx;
            // 渲染层剔除：远距实体阴影（>128格，与主绘制同阈值；远处光影盘亚像素级，纯渲染无仿真影响）
            float bSddx = bSx - this.sim.player.x;
            float bSddz = bSz - this.sim.player.z;
            if (bSddx * bSddx + bSddz * bSddz > PARTICLE_CULL_DIST_SQ) continue;
            this.addShadow(this.shadowBuf, bSx, bSy + 0.05f, bSz,
                    beast.type == Beast.TYPE_BOSS ? 1.15f : 0.42f, sdx, sdz, len);
        }
        for (Npc npc : this.sim.world.npcs) {
            if (npc.dead()) continue;
            // 阴影与实体位置同步插值，避免影子闪现
            float npcSX = npc.prevX + (npc.x - npc.prevX) * entLerp;
            float npcSY = npc.prevY + (npc.y - npc.prevY) * entLerp;
            float npcSZ = npc.prevZ + (npc.z - npc.prevZ) * entLerp;
            // 渲染层剔除：远距 NPC 阴影（与主绘制同阈值）
            float nSddx = npcSX - this.sim.player.x;
            float nSddz = npcSZ - this.sim.player.z;
            if (nSddx * nSddx + nSddz * nSddz > PARTICLE_CULL_DIST_SQ) continue;
            this.addShadow(this.shadowBuf, npcSX, npcSY + 0.05f, npcSZ, 0.36f, sdx, sdz, len);
        }
        for (Trials.Site site : this.sim.world.trials.sites) {
            this.addShadow(this.shadowBuf, site.x, site.y + 0.05f, site.z, 0.58f, sdx, sdz, len);
        }
        for (Trials.Cache cache : this.sim.world.trials.caches) {
            if (cache.claimed) continue;
            this.addShadow(this.shadowBuf, cache.x, cache.y + 0.05f, cache.z, 0.34f, sdx, sdz, len);
        }
        this.shadowBuf.flip();
        GL33.glBindVertexArray((int)this.shadowVAO);
        GL33.glBindBuffer((int)34962, (int)this.shadowVBO);
        GL33.glBufferSubData((int)34962, (long)0L, (FloatBuffer)this.shadowBuf);
        GL33.glDrawArrays((int)4, (int)0, (int)(this.shadowBuf.remaining() / 5));
        GL33.glBindVertexArray((int)0);
        GL33.glDepthMask((boolean)true);
        GL33.glDisable((int)3042);
        }
        this.drawEntities(matrix4f);
        // §19 god-ray：必须在泛光之前 —— 否则灯火辉光会被掩码当"光源"拉成假光束（见 drawGodRays 注释）。
        this.drawGodRays();
        // 泰拉瑞亚缺口②：选择性泛光（发光源网格 → FBO 提取/模糊 → 加性合成到默认帧缓冲）。
        // 基础场景已在默认帧缓冲，bloom 仅在其上叠加辉光；bloomReady 为假时整段跳过（不影响基础画面）。
        this.drawBloom(matrix4f);
        // §18 自动曝光测光：必须在此处（世界+泛光之后、HUD/雨丝之前）——
        // 早了测的是"天空+地形"还没有泛光，晚了 HUD 的白字会污染测光。
        this.updateAutoExposure();
        // 天气观感：雨丝层（世界之上、HUD/菜单之下）。纯渲染，零漂移。
        this.drawRainOverlay();
        // 水下观感（MC ScreenEffectRenderer 的位置：世界之上、HUD 之下）。纯渲染，零漂移。
        this.drawUnderwaterOverlay();
        // LD-2026-09-12 失焦遮罩：画序=世界之上、UI 之下（旧版盖住 HUD/菜单，把 H/ESC 全"吞"了）
        if (!this.isActiveNow() && !this.menu.isOpen()) {
            this.drawFocusOverlay();
        }
        if (this.menu.showHud || this.menu.isOpen()) {
            this.drawHud();
        } else if (!this.menu.isOpen()) {
            this.drawHudOffHint();
        }
        // 内容扩张（小地图）：HUD 之上再叠一层，自带开关/菜单/HUD 判断。纯渲染，零漂移。
        this.drawMinimap();
        // QA（2026-09-20）：无头自截图 —— 启动加 -Dbw.snap=<png> 时，在第 qaSnapFrame 帧把默认帧缓冲存成 PNG 后退出。
        // 存在的理由：开发环境看不见窗口，"画面没画出来"类问题（GL 状态/纹理上传/深度）无法靠 ShaderCheck
        // 发现（它只验 shader 能不能编译）；有了它就能无头出真像素、自己判读。
        // 帧号默认 48 而非 4：世界是分帧流式加载的（12~20 帧），第 4 帧时加载进度还会随真实时钟波动，
        // 于是两次运行的画面差得比"被测改动"还多（实测 90% 像素不同、A/B 完全失效）。等加载跑完再拍。
        // 触发条件不是固定帧号：流式加载（{@code isShifting}）是按真实时间分帧跑的，机器负载一变，
        // "第 N 帧"时加载到哪就不一样 → 截图不可复现（实测同一版本两次运行 95% 像素不同）。
        // 改成"加载态结束 + 待重建区块清空"这个**状态条件**，才是真正的稳态；qaSnapFrame 只做最少等待。
        // QA phase 强制（2026-09-21 修）：{@code -Dbw.phase} 只在世界初始化时设过一次 tick，
        // 但随后 initContentLayer / applySelectedPreset 会把 tick 拉回开局值（实测设 0.5=正午，
        // HUD 仍显示 03:11 NIGHT，6 张不同相位的截图全拍成同一张）。
        // 改为在渲染前**每帧重施**：qaFrozen 下 tick 不增长，重施是幂等的，不会破坏可复现性。
        if (this.qaPhase01 >= 0f) {
            this.sim.world.tick = (int) (core.world.DayCycle.DAY_LEN * this.qaPhase01);
        }
        // ⚠️ 第三十五批：稳态判据还要加上 `qaWarmLeft == 0` —— 预热没跑完就拍，会拍到"演化到一半"的世界，
        // 而那种图既不代表初始态也不代表目标态（等于假证据）。
        if (System.getProperty("bw.snap") != null && this.frameCount >= this.qaSnapFrame
                && this.qaWarmLeft == 0
                // ⚠️ 第三十二批 A2：稳态判据必须把"在飞/待应用的网格"也算进去 ——
                // 脏集合现在在**提交**时就被清空（而不是重建完），只看 dirtyChunks.isEmpty()
                // 会在"结果还没上传"的那一帧就判稳态，把截图拍成一片未建网格的空地形。
                // 这是把"重活搬去 worker"必然带来的联动：**所有"何时算闲"的判据都要跟着改**。
                // §18：自动曝光锁存后要多等 1 帧 —— uniform 在**世界 pass 之前**上传，
                // 所以"锁存那一帧"画出来的仍是上一帧的曝光值。不等这一帧，慢机器会拍到未锁存的画面。
                && this.exposureLatchedFrame + 2 <= this.frameCount
                && (((!this.sim.world.isShifting()) && this.sim.world.dirtyChunks.isEmpty()
                        && this.meshBuilder != null && this.meshBuilder.inflight() == 0
                        && this.meshBuilder.ready() == 0)
                    || this.frameCount > 900)) {
            String snapPath = System.getProperty("bw.snap");
            int sw = this.W, sh = this.H;
            java.nio.ByteBuffer px = org.lwjgl.system.MemoryUtil.memAlloc(sw * sh * 3);
            GL33.glPixelStorei(GL33.GL_PACK_ALIGNMENT, 1);
            GL33.glReadPixels(0, 0, sw, sh, GL33.GL_RGB, GL33.GL_UNSIGNED_BYTE, org.lwjgl.system.MemoryUtil.memAddress(px));
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int yy = 0; yy < sh; yy++)
                for (int xx = 0; xx < sw; xx++) {
                    int ii = ((sh - 1 - yy) * sw + xx) * 3;
                    int rr = px.get(ii) & 255, gg = px.get(ii + 1) & 255, bb2 = px.get(ii + 2) & 255;
                    img.setRGB(xx, yy, (rr << 16) | (gg << 8) | bb2);
                }
            org.lwjgl.system.MemoryUtil.memFree(px);
            try {
                java.io.File sf = new java.io.File(snapPath);
                if (sf.getParentFile() != null) sf.getParentFile().mkdirs();
                javax.imageio.ImageIO.write(img, "png", sf);
                System.out.println("SNAP wrote " + snapPath + " " + sw + "x" + sh
                        + "  frame=" + this.frameCount + " shifting=" + this.sim.world.isShifting()
                        + " dirty=" + this.sim.world.dirtyChunks.size()
                        + " lightDirty=" + this.sim.world.lightDirty
                        + " slice=" + this.slicer.cursor() + " time=" + this.time
                        + " tick=" + this.sim.world.tick);
                // QA 自证摘要（永久保留）：把"这一帧的渲染输入"压成一个哈希打进日志。
                // 存在理由：2026-09-21 曾出现"截图 93% 像素不同、但状态量看不出差别"的事故，
                // 根因是光标回调仍在吃真实鼠标事件 → 朝向漂移。当时只能靠临时插桩才定位。
                // 有了这一行，以后任何"可复现性回归"都能一眼看出是**相机/朝向**（camHash 变）
                // 还是**世界状态**（tick 变）还是**渲染结果本身**（fbHash 变而 camHash 不变）。
                // 三行输出对比即判定，不必再写一次性诊断代码。
                int samples = GL33.glGetInteger(0x80A9);   // GL_SAMPLES：QA 下必须为 0（MSAA 非逐位确定）
                java.nio.ByteBuffer hb = org.lwjgl.system.MemoryUtil.memAlloc(sw * sh * 4);
                GL33.glReadPixels(0, 0, sw, sh, 0x1908, 0x1401, org.lwjgl.system.MemoryUtil.memAddress(hb)); // RGBA8
                long fbHash = 1469598103934665603L;
                for (int i = 0; i < sw * sh * 4; i++) { fbHash ^= (hb.get(i) & 255); fbHash *= 1099511628211L; }
                org.lwjgl.system.MemoryUtil.memFree(hb);
                java.util.zip.CRC32 c32 = new java.util.zip.CRC32();
                java.nio.ByteBuffer fbb = java.nio.ByteBuffer.allocate(4);
                float[] camInputs = { this.camPos.x, this.camPos.y, this.camPos.z,
                        this.yaw, this.pitch, this.walkAnim, this.walkBlend,
                        this.time, this.hurtFlash, this.killFlash };
                for (float v : camInputs) {
                    fbb.clear();
                    fbb.putInt(Float.floatToIntBits(v));
                    c32.update(fbb.array());
                }
                System.out.println("SNAPDIGEST msaa=" + samples
                        + " fbHash=" + Long.toHexString(fbHash)
                        + " camHash=" + Long.toHexString(c32.getValue())
                        + " yaw=" + this.yaw + " pitch=" + this.pitch
                        + " tick=" + this.sim.world.tick);
            } catch (Throwable t) { System.out.println("SNAP failed: " + t); }
            System.exit(0);
        }
        if (this.frameCount < 5) {
            this.logPhase("FRAME " + this.frameCount + " END");
        }
        ++this.frameCount;
    }

    private void setVP(Matrix4f matrix4f) {
        this.vpBuf.clear();
        for (int i = 0; i < 4; ++i) {
            for (int j = 0; j < 4; ++j) {
                this.vpBuf.put(matrix4f.get(i, j));
            }
        }
        this.vpBuf.flip();
        if (this.uVP != -1) {
            GL33.glUniformMatrix4fv((int)this.uVP, (boolean)false, (FloatBuffer)this.vpBuf);
        }
    }

    /** P1：MSDF 通道的 VP（与 {@link #setHudVP} 同构，但目标是 msdfShader 的 uVP）。 */
    private void setMsdfVP(Matrix4f matrix4f) {
        if (this.msdfUVP == -1) return;
        this.vpBuf.clear();
        for (int i = 0; i < 4; ++i) {
            for (int j = 0; j < 4; ++j) {
                this.vpBuf.put(matrix4f.get(i, j));
            }
        }
        this.vpBuf.flip();
        GL33.glUniformMatrix4fv((int)this.msdfUVP, (boolean)false, (FloatBuffer)this.vpBuf);
    }

    private void setHudVP(Matrix4f matrix4f) {
        this.vpBuf.clear();
        for (int i = 0; i < 4; ++i) {
            for (int j = 0; j < 4; ++j) {
                this.vpBuf.put(matrix4f.get(i, j));
            }
        }
        this.vpBuf.flip();
        if (this.hudUVP != -1) {
            GL33.glUniformMatrix4fv((int)this.hudUVP, (boolean)false, (FloatBuffer)this.vpBuf);
        }
        // 自研艺术 UI 层强度：默认 0（严格等价旧纯色路径）。所有 HUD 提交都以 setHudVP 为前置，
        // 故在此设一次即可覆盖 drawHud / minimap / 天体 / 提示条全部通道。
        if (this.hudUArtAmt != -1) {
            GL33.glUniform1f((int)this.hudUArtAmt, this.uiArtAmount);
        }
        if (this.hudUArtRes != -1) {
            GL33.glUniform2f((int)this.hudUArtRes, (float)this.W, (float)this.H);
        }
        // 图集绑定到单元 0（与 world 共用 atlasTex）。旧调用点 UV=WHITE_TILE → 采样纯白 → 恒等。
        if (this.hudUTex != -1 && this.atlasTex > 0) {
            GL33.glActiveTexture((int)33984);
            GL33.glBindTexture((int)3553, (int)this.atlasTex);
            GL33.glUniform1i((int)this.hudUTex, 0);
        }
    }

    private void rot(float f, float f2, float f3, float f4, float[] fArray) {
        fArray[0] = f * (float)Math.cos(f4) + f3 * (float)Math.sin(f4);
        fArray[1] = f2;
        fArray[2] = -f * (float)Math.sin(f4) + f3 * (float)Math.cos(f4);
    }

    private void addShadow(FloatBuffer floatBuffer, float f, float f2, float f3, float f4, float sdx, float sdz, float len) {
        float L = f4 * len;            // 沿太阳反方向的半长（影子拉长）
        float W = f4 * 0.8f;           // 垂直方向半宽
        float rx = f4 * (float)Math.sqrt((double)((len * sdx) * (len * sdx) + (0.8f * sdz) * (0.8f * sdz)));
        float rz = f4 * (float)Math.sqrt((double)((len * sdz) * (len * sdz) + (0.8f * sdx) * (0.8f * sdx)));
        float ox = sdx * (L - f4) * 0.5f;   // 中心朝太阳反方向偏移，近端仍落在实体正下方
        float oz = sdz * (L - f4) * 0.5f;
        float cx = f + ox; float cz = f3 + oz;
        int[][] nArrayArray;
        float[][] fArrayArray = new float[][]{{cx - rx, f2, cz - rz}, {cx + rx, f2, cz - rz}, {cx + rx, f2, cz + rz}, {cx - rx, f2, cz + rz}};
        float[][] fArrayArray2 = new float[][]{{-1.0f, -1.0f}, {1.0f, -1.0f}, {1.0f, 1.0f}, {-1.0f, 1.0f}};
        for (int[] nArray : nArrayArray = new int[][]{{0, 1, 2}, {0, 2, 3}}) {
            float[] fArray = fArrayArray[nArray[0]];
            float[] fArray2 = fArrayArray[nArray[1]];
            float[] fArray3 = fArrayArray[nArray[2]];
            float[] fArray4 = fArrayArray2[nArray[0]];
            float[] fArray5 = fArrayArray2[nArray[1]];
            float[] fArray6 = fArrayArray2[nArray[2]];
            floatBuffer.put(fArray[0]).put(fArray[1]).put(fArray[2]).put(fArray4[0]).put(fArray4[1]);
            floatBuffer.put(fArray2[0]).put(fArray2[1]).put(fArray2[2]).put(fArray5[0]).put(fArray5[1]);
            floatBuffer.put(fArray3[0]).put(fArray3[1]).put(fArray3[2]).put(fArray6[0]).put(fArray6[1]);
        }
    }

    private Npc nearestNpc(World world, float f) {
        Npc npc = null;
        float f2 = Float.MAX_VALUE;
        for (Npc npc2 : world.npcs) {
            float f3;
            float f4;
            float f5;
            float f6;
            if (npc2.dead() || !((f6 = (float)Math.sqrt((f5 = npc2.x - this.sim.player.x) * f5 + (f4 = npc2.y - this.sim.player.y) * f4 + (f3 = npc2.z - this.sim.player.z) * f3)) < f2) || !(f6 <= f)) continue;
            f2 = f6;
            npc = npc2;
        }
        return npc;
    }

    private static float[] moodColor(String string) {
        switch (string) {
            case "calm": {
                return new float[]{0.3f, 0.85f, 0.4f};
            }
            case "wary": {
                return new float[]{0.92f, 0.85f, 0.2f};
            }
            case "scared": {
                return new float[]{0.95f, 0.95f, 0.95f};
            }
            case "furious": {
                return new float[]{0.95f, 0.2f, 0.15f};
            }
            case "fond": {
                return new float[]{0.95f, 0.5f, 0.72f};
            }
            case "hostile": {
                return new float[]{0.6f, 0.1f, 0.1f};
            }
        }
        return new float[]{0.3f, 0.85f, 0.4f};
    }

    private static float[] profColor(String string) {
        if (string == null) {
            return new float[]{0.7f, 0.7f, 0.75f};
        }
        switch (string) {
            case "farmer": {
                return new float[]{0.36f, 0.6f, 0.26f};
            }
            case "crafter": {
                return new float[]{0.62f, 0.46f, 0.26f};
            }
            case "trader": {
                return new float[]{0.86f, 0.7f, 0.24f};
            }
            case "herbalist": {
                return new float[]{0.3f, 0.72f, 0.56f};
            }
            case "guard": {
                return new float[]{0.4f, 0.46f, 0.66f};
            }
        }
        return new float[]{0.7f, 0.7f, 0.75f};
    }

    private void logTalk(String string) {
        if (this.talkLog == null) {
            return;
        }
        try {
            this.talkLog.println(string);
            this.talkLog.flush();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private void writeChronicleLog(World world) {
        if (this.chronicleLog == null || world == null || world.chronicle == null) {
            return;
        }
        String string = world.chronicle.story;
        if (string == null || string.isEmpty() || string.equals(this.lastChronicleWritten)) {
            return;
        }
        this.lastChronicleWritten = string;
        try {
            this.chronicleLog.println("===== D" + world.chronicle.days + "  tick " + world.chronicle.storyTick + "  =====");
            this.chronicleLog.println(world.chronicle.asciiHeader());
            this.chronicleLog.println(string);
            this.chronicleLog.println();
            this.chronicleLog.flush();
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private void addBoxWorld(FloatBuffer floatBuffer, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8, float f9) {
        int[][] nArrayArray;
        float[][] fArrayArray = new float[][]{{f, f2, f3}, {f4, f2, f3}, {f4, f5, f3}, {f, f5, f3}, {f, f2, f6}, {f4, f2, f6}, {f4, f5, f6}, {f, f5, f6}};
        for (int[] nArray : nArrayArray = new int[][]{{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {2, 3, 7, 6}, {1, 2, 6, 5}, {0, 3, 7, 4}}) {
            float[] fArray = fArrayArray[nArray[0]];
            float[] fArray2 = fArrayArray[nArray[1]];
            float[] fArray3 = fArrayArray[nArray[2]];
            float[] fArray4 = fArrayArray[nArray[3]];
            floatBuffer.put(fArray[0]).put(fArray[1]).put(fArray[2]).put(f7).put(f8).put(f9);
            floatBuffer.put(fArray2[0]).put(fArray2[1]).put(fArray2[2]).put(f7).put(f8).put(f9);
            floatBuffer.put(fArray3[0]).put(fArray3[1]).put(fArray3[2]).put(f7).put(f8).put(f9);
            floatBuffer.put(fArray[0]).put(fArray[1]).put(fArray[2]).put(f7).put(f8).put(f9);
            floatBuffer.put(fArray3[0]).put(fArray3[1]).put(fArray3[2]).put(f7).put(f8).put(f9);
            floatBuffer.put(fArray4[0]).put(fArray4[1]).put(fArray4[2]).put(f7).put(f8).put(f9);
        }
    }

    /** 体素盒重叠间隙：相邻体素块各向外延展 {@link #VOX_GAP}，消除方块接缝处的「黑边」。 */
    private static final float VOX_GAP = 0.006f;

    private void addBoxWorldP(FloatBuffer floatBuffer, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8, float f9) {
        addBoxWorld(floatBuffer, f - VOX_GAP, f2 - VOX_GAP, f3 - VOX_GAP, f4 + VOX_GAP, f5 + VOX_GAP, f6 + VOX_GAP, f7, f8, f9);
    }

    /**
     * MC 手感（路线图 §1 P0 #2）：走近掉落物 → 自动吸入背包。
     *
     * <p>与箱子同一种分工：`World.drops` 只存数据（sim 层、确定性、无 RNG），背包转移在渲染/输入层做。
     * 吸入半径 1.5 格（MC pickUp 语义）；背包满则把剩余量留在地上，不静默吞掉。
     */
    private void collectDrops() {
        if (this.inventory == null) return;
        final float r2 = 1.5f * 1.5f;
        java.util.List<core.world.World.Drop> ds = this.sim.world.drops;
        for (int i = ds.size() - 1; i >= 0; i--) {
            core.world.World.Drop d = ds.get(i);
            float dx = this.sim.player.x - d.x, dz = this.sim.player.z - d.z;
            if (dx * dx + dz * dz > r2) continue;                        // 水平半径内
            if (Math.abs((this.sim.player.y + 0.9f) - d.y) > 2.2f) continue;   // 垂直放宽（上下层不误吸）
            int left = this.inventory.add(d.itemId, d.count);
            if (left == 0) {
                ds.remove(i);
                this.toastText = "+" + d.count + " " + d.itemId;
                this.toastTimer = 0.9f;
                this.sfx(Sfx.LOOT);
                this.unlockAch("FIRST PICKUP");
            } else if (left < d.count) {
                d.count = left;                                          // 装不下的留在地上
                this.toastText = "INVENTORY FULL";
                this.toastTimer = 1.2f;
                this.sfx(Sfx.DENY);
            }
        }
    }

    /** 画面细腻度：记一个漂浮伤害数字（8 槽环形缓冲，屏幕空间）。 */
    private void pushDamageNumber(int v) {
        this.dmgHead = (this.dmgHead + 1) & 7;
        this.dmgV[this.dmgHead] = v;
        this.dmgT[this.dmgHead] = 0.85f;                                  // 存活时长（秒）
        this.dmgRnd[this.dmgHead] = (this.time * 1.73f) % 1.0f;           // 纯本地抖动（不碰任何 RNG 流）
        this.dmgX[this.dmgHead] = this.W * 0.5f + (this.dmgRnd[this.dmgHead] - 0.5f) * 120.0f;
        this.dmgY[this.dmgHead] = this.H * 0.5f - 30.0f;
    }

    /** 成就（**会话级**，纯本地）：首次达成 → 弹横幅一次。不进 sim/存档（避免联机状态分叉）。 */
    private void unlockAch(String id) {
        if (this.achDone.add(id)) {
            this.achBanner = id;
            this.achBannerTimer = 3.2f;
            this.sfx(Sfx.UNLOCK);
            GameLog.log("ACH", "unlock " + id + " (" + this.achDone.size() + "/" + ACH_IDS.length + ")");
        }
    }

    /** 在 entBuf 上画一圈世界空间小方块（招架/处决的扩散环；纯渲染，只写顶点缓冲）。 */
    private void drawFxRing(float cx, float cy, float cz, float rad, float half, float r, float g, float b) {
        final int SEG = 18;
        for (int i = 0; i < SEG; i++) {
            double d = (double) i * 2.0 * Math.PI / SEG;
            float rx = cx + (float) Math.cos(d) * rad;
            float rz = cz + (float) Math.sin(d) * rad;
            this.addBoxWorld(this.entBuf, rx - half, cy - half, rz - half, rx + half, cy + half, rz + half, r, g, b);
        }
    }

    private void drawEntities(Matrix4f matrix4f) {
        // M3⑦ NPC 渲染插值因子：在相邻 sim tick 之间平滑位置，消除 20Hz 闪现感。
        // entAlpha 由主循环维护：只在真的推进 tick 时更新，暂停/hitstop/跨图时冻结（避免回弹闪烁）。
        final float entLerp = this.entAlpha;
        float f;
        float f2;
        float f3;
        float f4;
        float f5;
        float f6;
        float f7;
        int n;
        float f8;
        float f9;
        GL33.glUseProgram((int)this.shader);
        Player player = this.sim.player;
        float f10 = (float)Math.toRadians(this.yaw);
        float[] fArray = new float[3];
        float f11 = (float)Math.sin(this.walkAnim) * 0.18f;
        final float hs = Avatar.heightScale(player.appearance.height);
        final int faceShape = player.appearance.faceShape;
        final int hsHair = Avatar.hairType(player.appearance.hairStyle);
        final boolean hasBeard = Avatar.hasBeard(player.appearance.beard);
        final int beardT = Avatar.beardType(player.appearance.beard);
        final boolean hasTattoo = Avatar.hasTattoo(player.appearance.tattoo);
        final int tattooT = Avatar.tattooType(player.appearance.tattoo);
        final float[] skinTint = Avatar.ageSkinTint(player.appearance.age);
        final float[] hairRGB = Avatar.rgb(player.appearance.hair);
        final float[] fd = Avatar.faceDims(faceShape);
        this.entBuf.clear();
        if (this.thirdPerson) {
            // ===== P0③ 姿态状态机（2026-09-21）=====
            // 旧实现：只有一条 walkBlend 线性插值 → 起跳/下落/落地三态身体姿态完全一样，
            // 且腿摆幅仅 ±0.18 rad（真人走路 ≈±25~30°）、身体起伏 6mm 在 4.5 格机位下几乎不可见。
            // 这里按「地面/空中↑/空中↓/落地/受伤/翻滚」分态，各自的姿态参数独立可调（纯渲染，零漂移）。
            float f14 = Math.min(1.0f, this.hurtFlash / 0.8f);
            f9 = this.landTimer / 0.22f;
            f8 = this.rollAnim / 0.42f;
            n = !player.onGround ? 1 : 0;
            boolean airUp = n != 0 && player.vy > 0.5f;          // 上升段
            boolean airDown = n != 0 && player.vy < -0.5f;       // 下落段
            // 姿态权重（0..1）
            float poseAirUp = airUp ? 1f : 0f;
            float poseAirDown = airDown ? 1f : 0f;
            float poseLand = f9;
            float poseRoll = f8;
            // 腿摆幅：地面 ±0.42 rad（≈24°，接近真人 ±25~30°）；跑步再 ×1.15；空中改为固定姿态
            float legSwingAmp = 0.42f * (this.sprinting ? 1.15f : 1.0f);
            float legSwing = (float) Math.sin(this.walkAnim) * legSwingAmp * this.walkBlend;
            // 空中：双腿前后分开（上升=收腿，下落=前伸准备落地）
            if (poseAirUp > 0f) legSwing = 0.30f;
            else if (poseAirDown > 0f) legSwing = -0.22f;
            // 身体起伏：走路 ±0.055（旧 0.03），呼吸 0.006
            float bobWalk = (float) Math.abs(Math.sin(this.walkAnim)) * 0.055f * hs * this.walkBlend;
            float bobIdle = (float) Math.sin(this.time * 2.0) * 0.006f * hs * (1f - this.walkBlend);
            // 落地缓冲：半蹲更深（0.16）+ 0.10 s 回弹过冲
            float landSquat = poseLand * 0.16f * hs;
            float landOvershoot = poseLand > 0.6f ? (float) Math.sin((1f - poseLand) / 0.4f * Math.PI) * 0.03f * hs : 0f;
            f6 = f7 = (0.7f - 0.3f * poseRoll) * hs - landSquat + landOvershoot + bobWalk + bobIdle;
            f5 = f7 + (0.55f - 0.1f * poseRoll) * hs;
            f4 = 1.3f - 0.4f * poseRoll - 0.12f * poseLand + bobWalk * 0.7f + bobIdle;
            f3 = f6 + 0.05f;
            f2 = legSwing;
            f = -legSwing;                                        // 左右腿反相
            float f12 = bobWalk;                                  // 手臂摆动（与走路同相，见下方 arms）
            float f13 = bobIdle;
            float[] fArray2 = Avatar.bodyColor(player.armor);
            float[] fArray3 = Avatar.legColor(player.armor);
            float f15 = fArray2[0] + (1.0f - fArray2[0]) * f14;
            float f16 = fArray2[1] + (1.0f - fArray2[1]) * f14;
            float f17 = fArray2[2] + (1.0f - fArray2[2]) * f14;
            float f18 = fArray3[0] + (1.0f - fArray3[0]) * f14;
            float f19 = fArray3[1] + (1.0f - fArray3[1]) * f14;
            float f20 = fArray3[2] + (1.0f - fArray3[2]) * f14;
            float f21 = 0.92f + 0.07999998f * f14;
            float f22 = 0.72f + 0.27999997f * f14;
            float f23 = 0.55f + 0.45f * f14;
            // ===== 阶段2/3：高分辨率体素角色 + 动画（零漂移：纯渲染，不进 hashState）=====
            // P0③：起伏已并入 f6/f5/f4/f3（见上方姿态状态机），此处不再二次叠加 bodyOff。
            float bodyOff = 0f;
            float legTop = f6;                   // 腿顶（髋）
            float torsoTop = f5;                 // 肩
            float armYb = f3;                    // 臂根
            float headYb = f4;                   // 颈根
            // 攻击挥砍进度（atkCd>0 时右臂与武器抬升扫弧）
            float atkProg = 0.0f;
            if (player.atkCd > 0.0f) {
                float cd = player.artCdFor(player.currentArt);
                if (cd > 0.0f) atkProg = 1.0f - player.atkCd / cd;
            }
            float atkSwing = atkProg > 0.0f ? (float)Math.sin(Math.PI * atkProg) * 0.7f : 0.0f;
            float bootH = 0.12f * hs;
            float legH = legTop;                 // 腿总长（含微浮）
            float thighH = legH * 0.5f;
            float calfTop = bootH + thighH;
            // --- 腿：靴 + 大腿 + 小腿（相邻块重叠 VOX_GAP 消除黑边）---
            this.rot(-0.16f, 0.0f, f2, f10, fArray);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.085f), player.y + fArray[1] + bootH, player.z + (fArray[2] - 0.085f),
                    player.x + (fArray[0] + 0.085f), player.y + fArray[1] + bootH + thighH, player.z + (fArray[2] + 0.085f), f18, f19, f20);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.075f), player.y + fArray[1] + calfTop - 0.02f, player.z + (fArray[2] - 0.075f),
                    player.x + (fArray[0] + 0.075f), player.y + fArray[1] + legH, player.z + (fArray[2] + 0.075f), f18, f19, f20);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.105f), player.y + fArray[1], player.z + (fArray[2] - 0.105f),
                    player.x + (fArray[0] + 0.105f), player.y + fArray[1] + bootH + 0.03f, player.z + (fArray[2] + 0.105f), f18 * 0.7f, f19 * 0.7f, f20 * 0.72f);
            this.rot(0.16f, 0.0f, -f2, f10, fArray);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.085f), player.y + fArray[1] + bootH, player.z + (fArray[2] - 0.085f),
                    player.x + (fArray[0] + 0.085f), player.y + fArray[1] + bootH + thighH, player.z + (fArray[2] + 0.085f), f18, f19, f20);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.075f), player.y + fArray[1] + calfTop - 0.02f, player.z + (fArray[2] - 0.075f),
                    player.x + (fArray[0] + 0.075f), player.y + fArray[1] + legH, player.z + (fArray[2] + 0.075f), f18, f19, f20);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.105f), player.y + fArray[1], player.z + (fArray[2] - 0.105f),
                    player.x + (fArray[0] + 0.105f), player.y + fArray[1] + bootH + 0.03f, player.z + (fArray[2] + 0.105f), f18 * 0.7f, f19 * 0.7f, f20 * 0.72f);
            // --- 躯干：腹 + 胸 + 腰带 + 肩章 ---
            this.rot(0.0f, legTop, 0.0f, f10, fArray);
            float torsoH = torsoTop - legTop;
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.22f), player.y + fArray[1], player.z + (fArray[2] - 0.13f),
                    player.x + (fArray[0] + 0.22f), player.y + fArray[1] + torsoH * 0.42f, player.z + (fArray[2] + 0.13f), f15, f16, f17);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.26f), player.y + fArray[1] + torsoH * 0.42f - 0.02f, player.z + (fArray[2] - 0.15f),
                    player.x + (fArray[0] + 0.26f), player.y + fArray[1] + torsoH, player.z + (fArray[2] + 0.15f), f15, f16, f17);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.28f), player.y + fArray[1] - 0.01f, player.z + (fArray[2] - 0.17f),
                    player.x + (fArray[0] + 0.28f), player.y + fArray[1] + 0.05f, player.z + (fArray[2] + 0.17f), f18 * 0.6f, f19 * 0.6f, f20 * 0.65f);
            float[] fArray4 = Avatar.rankColor(player.level);
            if (fArray4 != null) {
                this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.29f), player.y + fArray[1] + torsoH - 0.11f, player.z + (fArray[2] - 0.18f),
                        player.x + (fArray[0] + 0.29f), player.y + fArray[1] + torsoH - 0.03f, player.z + (fArray[2] - 0.15f), fArray4[0], fArray4[1], fArray4[2]);
            }
            // 左右肩章
            this.rot(-0.34f, torsoTop - 0.02f, 0.0f, f10, fArray);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.15f), player.y + fArray[1], player.z + (fArray[2] - 0.15f),
                    player.x + (fArray[0] + 0.15f), player.y + fArray[1] + 0.13f, player.z + (fArray[2] + 0.15f), f15, f16, f17);
            this.rot(0.34f, torsoTop - 0.02f, 0.0f, f10, fArray);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.15f), player.y + fArray[1], player.z + (fArray[2] - 0.15f),
                    player.x + (fArray[0] + 0.15f), player.y + fArray[1] + 0.13f, player.z + (fArray[2] + 0.15f), f15, f16, f17);
            // --- 颈 ---
            this.rot(0.0f, torsoTop - 0.02f, 0.0f, f10, fArray);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.07f), player.y + fArray[1], player.z + (fArray[2] - 0.07f),
                    player.x + (fArray[0] + 0.07f), player.y + fArray[1] + 0.10f, player.z + (fArray[2] + 0.07f),
                    f21 * skinTint[0], f22 * skinTint[1], f23 * skinTint[2]);
            // --- 头（脸型调宽高 + 年龄肤色 + 身高缩放）+ 耳 ---
            this.rot(0.0f, headYb, 0.0f, f10, fArray);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - fd[0]), player.y + fArray[1], player.z + (fArray[2] - fd[0]),
                    player.x + (fArray[0] + fd[0]), player.y + fArray[1] + fd[1] * hs, player.z + (fArray[2] + fd[0]),
                    f21 * skinTint[0], f22 * skinTint[1], f23 * skinTint[2]);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - fd[0] - 0.03f), player.y + fArray[1] + fd[1] * hs * 0.45f, player.z + (fArray[2] - 0.03f),
                    player.x + (fArray[0] - fd[0] + 0.01f), player.y + fArray[1] + fd[1] * hs * 0.78f, player.z + (fArray[2] + 0.03f),
                    f21 * skinTint[0] * 0.95f, f22 * skinTint[1] * 0.95f, f23 * skinTint[2] * 0.95f);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] + fd[0] - 0.01f), player.y + fArray[1] + fd[1] * hs * 0.45f, player.z + (fArray[2] - 0.03f),
                    player.x + (fArray[0] + fd[0] + 0.03f), player.y + fArray[1] + fd[1] * hs * 0.78f, player.z + (fArray[2] + 0.03f),
                    f21 * skinTint[0] * 0.95f, f22 * skinTint[1] * 0.95f, f23 * skinTint[2] * 0.95f);
            // 阶段 B：服装层覆盖几何（与 gameplay player.armor 正交，纯外观；零漂移）
            // 胸甲（覆盖躯干胸腹）
            float[] cp = Avatar.chestplateColor(player.appearance.armor);
            if (cp != null) {
                this.rot(0.0f, legTop, 0.0f, f10, fArray);
                float cph = torsoH * 0.50f;
                this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.30f), player.y + fArray[1] + torsoH * 0.40f - 0.03f, player.z + (fArray[2] - 0.17f),
                        player.x + (fArray[0] + 0.30f), player.y + fArray[1] + torsoH * 0.40f + cph, player.z + (fArray[2] + 0.17f), cp[0], cp[1], cp[2]);
            }
            // 头盔（覆盖头顶）
            float[] hm = Avatar.helmetColor(player.appearance.helmet);
            if (hm != null) {
                this.rot(0.0f, headYb + fd[1] * hs, 0.0f, f10, fArray);
                float hh = fd[1] * hs;
                this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - (fd[0] + 0.02f)), player.y + fArray[1] + hh * 0.50f, player.z + (fArray[2] - (fd[0] + 0.02f)),
                        player.x + (fArray[0] + (fd[0] + 0.02f)), player.y + fArray[1] + hh * 0.50f + 0.14f * hs, player.z + (fArray[2] + (fd[0] + 0.02f)), hm[0], hm[1], hm[2]);
            }
            // 披风（背后；长披风更长）
            float[] cl = Avatar.cloakColor(player.appearance.cloak);
            if (cl != null) {
                this.rot(0.0f, legTop, 0.0f, f10, fArray);
                float cyl = player.appearance.cloak == 2 ? torsoH * 1.02f : torsoH * 0.62f;
                this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.24f), player.y + fArray[1] + 0.02f, player.z + (fArray[2] - 0.20f),
                        player.x + (fArray[0] + 0.24f), player.y + fArray[1] + cyl, player.z + (fArray[2] - 0.13f), cl[0], cl[1], cl[2]);
            }
            // 头发（hairStyle 0..5；3=光不画）
            if (hsHair != 3) {
                float hr = hairRGB[0], hg = hairRGB[1], hb = hairRGB[2];
                this.rot(0.0f, headYb + fd[1] * hs, 0.0f, f10, fArray);
                this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - fd[0] * 1.05f), player.y + fArray[1], player.z + (fArray[2] - fd[0] * 1.05f),
                        player.x + (fArray[0] + fd[0] * 1.05f), player.y + fArray[1] + 0.11f * hs, player.z + (fArray[2] + fd[0] * 1.05f), hr, hg, hb);
                if (hsHair == 2 || hsHair == 4) { // 长发/辫：后披发块
                    this.rot(0.0f, headYb + 0.10f * hs, 0.22f, f10, fArray);
                    this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - fd[0] * 0.7f), player.y + fArray[1], player.z + (fArray[2] - 0.05f),
                            player.x + (fArray[0] + fd[0] * 0.7f), player.y + fArray[1] + 0.34f * hs, player.z + (fArray[2] + 0.05f), hr, hg, hb);
                }
                if (hsHair == 5) { // 丸子头：头顶球
                    this.rot(0.0f, headYb + fd[1] * hs + 0.06f * hs, 0.0f, f10, fArray);
                    this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.07f), player.y + fArray[1], player.z + (fArray[2] - 0.07f),
                            player.x + (fArray[0] + 0.07f), player.y + fArray[1] + 0.14f * hs, player.z + (fArray[2] + 0.07f), hr, hg, hb);
                }
            }
            // --- 手臂：上臂 + 前臂 + 手（带攻击挥砍）---
            this.rot(-0.34f, armYb, -f, f10, fArray);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.085f), player.y + fArray[1], player.z + (fArray[2] - 0.085f),
                    player.x + (fArray[0] + 0.085f), player.y + fArray[1] + 0.22f, player.z + (fArray[2] + 0.085f), f15, f16, f17);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.075f), player.y + fArray[1] + 0.20f, player.z + (fArray[2] - 0.075f),
                    player.x + (fArray[0] + 0.075f), player.y + fArray[1] + 0.43f, player.z + (fArray[2] + 0.075f), f15, f16, f17);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.06f), player.y + fArray[1] + 0.41f, player.z + (fArray[2] - 0.06f),
                    player.x + (fArray[0] + 0.06f), player.y + fArray[1] + 0.50f, player.z + (fArray[2] + 0.06f),
                    f21 * skinTint[0], f22 * skinTint[1], f23 * skinTint[2]);
            this.rot(0.34f, armYb, f + atkSwing, f10, fArray);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.085f), player.y + fArray[1], player.z + (fArray[2] - 0.085f),
                    player.x + (fArray[0] + 0.085f), player.y + fArray[1] + 0.22f, player.z + (fArray[2] + 0.085f), f15, f16, f17);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.075f), player.y + fArray[1] + 0.20f, player.z + (fArray[2] - 0.075f),
                    player.x + (fArray[0] + 0.075f), player.y + fArray[1] + 0.43f, player.z + (fArray[2] + 0.075f), f15, f16, f17);
            this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.06f), player.y + fArray[1] + 0.41f, player.z + (fArray[2] - 0.06f),
                    player.x + (fArray[0] + 0.06f), player.y + fArray[1] + 0.50f, player.z + (fArray[2] + 0.06f),
                    f21 * skinTint[0], f22 * skinTint[1], f23 * skinTint[2]);
            // 胡须（下巴 / 两颊）
            if (hasBeard) {
                float br = f21 * 0.8f, bg = f22 * 0.8f, bb = f23 * 0.8f;
                this.rot(0.0f, headYb + 0.06f * hs, 0.0f, f10, fArray);
                if (beardT == 1) {
                    this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.12f), player.y + fArray[1], player.z + (fArray[2] - fd[0] - 0.02f),
                            player.x + (fArray[0] + 0.12f), player.y + fArray[1] + 0.05f * hs, player.z + (fArray[2] - fd[0] + 0.02f), br, bg, bb);
                } else if (beardT == 2) {
                    this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - fd[0]), player.y + fArray[1], player.z + (fArray[2] - fd[0] - 0.02f),
                            player.x + (fArray[0] + fd[0]), player.y + fArray[1] + 0.16f * hs, player.z + (fArray[2] - fd[0] + 0.02f), br, bg, bb);
                } else {
                    this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.07f), player.y + fArray[1], player.z + (fArray[2] - fd[0] - 0.02f),
                            player.x + (fArray[0] + 0.07f), player.y + fArray[1] + 0.12f * hs, player.z + (fArray[2] - fd[0] + 0.02f), br, bg, bb);
                }
            }
            // 纹身 / 疤痕（左脸疤 / 右臂纹 / 额痕）
            if (hasTattoo) {
                if (tattooT == 2) {
                    this.rot(0.34f, armYb + 0.20f, f + atkSwing, f10, fArray);
                    this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.11f), player.y + fArray[1], player.z + (fArray[2] - 0.11f),
                            player.x + (fArray[0] + 0.11f), player.y + fArray[1] + 0.30f * hs, player.z + (fArray[2] + 0.11f), 0.5f, 0.30f, 0.6f);
                } else {
                    this.rot(0.0f, headYb + fd[1] * hs * 0.5f, 0.0f, f10, fArray);
                    if (tattooT == 1) {
                        this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - fd[0] - 0.02f), player.y + fArray[1], player.z + (fArray[2] - 0.03f),
                                player.x + (fArray[0] - fd[0] + 0.02f), player.y + fArray[1] + 0.18f * hs, player.z + (fArray[2] + 0.03f), 0.6f, 0.2f, 0.2f);
                    } else {
                        this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - 0.10f), player.y + fArray[1], player.z + (fArray[2] - 0.02f),
                                player.x + (fArray[0] + 0.10f), player.y + fArray[1] + 0.04f * hs, player.z + (fArray[2] + 0.02f), 0.6f, 0.2f, 0.2f);
                    }
                }
            }
            // 武器（贴右手，攻击时整体抬升扫弧）
            float[] fArray5 = Avatar.weapon(player.weapon);
            if (fArray5 != null) {
                float f24 = player.atkCd > player.artCdFor(player.currentArt) - 0.15f ? 0.16f : 0.0f;
                float f25 = 0.34f + atkSwing * 0.5f;
                float f26 = armYb + 0.5f;
                float f27 = (f + atkSwing) * 0.5f + 0.1f + f24;
                for (int i = 0; i < 3; ++i) {
                    boolean bl = i == 2 && fArray5[2] > 0.01f;
                    float f28 = bl ? fArray5[1] * 2.0f : fArray5[1];
                    float f29 = bl ? fArray5[2] : fArray5[0];
                    this.rot(f25, f26, f27, f10, fArray);
                    this.addBoxWorldP(this.entBuf, player.x + (fArray[0] - f28), player.y + fArray[1] - f28, player.z + (fArray[2] - f28),
                            player.x + (fArray[0] + f28), player.y + fArray[1] + f28, player.z + (fArray[2] + f28), fArray5[3], fArray5[4], fArray5[5]);
                    f27 += f29;
                }
            }
        }
        // 第二十一批：矿车（与野兽同一个实体 pass）。`addBoxWorld` 是"以世界坐标给盒"的通用发射器 ——
        // 矿车正好是一个 0.8×0.45×0.8 的盒，不需要任何新几何设施。
        for (core.world.Minecart cart : this.sim.world.carts) {
            // 第二十二批：**运动外推代替位置插值**。野兽/村民用的是"保存上一 tick 位置 + entLerp 插值"，
            // 那需要每个实体一套 prev 快照与维护点；而矿车是**匀速滑行**的 ⇒
            // "按剩余时间片把当前位置回拉"与"在两帧之间插值"在视觉上等效，却零新状态、零维护点。
            // （若将来矿车出现加速度，这条近似会失效 —— 那时再补真正的 prev 快照。）
            float back = (1f - entLerp) * (1f / 20f);      // 20Hz = 世界 tick 频率
            float ccx = cart.x - cart.vx * back, ccz = cart.z - cart.vz * back;
            float ccy = cart.y;
            // 第二十三批（载物）：有货 → 车身换木箱色，并在车斗上摞一个更小的"货堆"盒 ⇒ 一眼能看出载重。
            if (cart.hasCargo()) {
                this.addBoxWorld(this.entBuf, ccx - 0.4f, ccy, ccz - 0.4f,
                        ccx + 0.4f, ccy + 0.45f, ccz + 0.4f, 0.62f, 0.45f, 0.28f);
                this.addBoxWorld(this.entBuf, ccx - 0.28f, ccy + 0.28f, ccz - 0.28f,
                        ccx + 0.28f, ccy + 0.72f, ccz + 0.28f, 0.85f, 0.72f, 0.42f);
            } else {
                this.addBoxWorld(this.entBuf, ccx - 0.4f, ccy, ccz - 0.4f,
                        ccx + 0.4f, ccy + 0.45f, ccz + 0.4f, 0.45f, 0.34f, 0.22f);
            }
        }
        int bAIdx = 0;
        for (Beast beast : this.sim.world.beasts) {
            // QA 2026-09-16：野兽同样做 tick 间插值（以前只有 NPC 做了 -> 野兽按 20Hz 一跳一跳）
            float bAx = beast.x, bAy = beast.y, bAz = beast.z;
            if (this.beastPrevRef != null && bAIdx < this.beastPrevCount && this.beastPrevRef[bAIdx] == beast) {
                bAx = this.beastPrevPos[bAIdx * 3] + (beast.x - this.beastPrevPos[bAIdx * 3]) * entLerp;
                bAy = this.beastPrevPos[bAIdx * 3 + 1] + (beast.y - this.beastPrevPos[bAIdx * 3 + 1]) * entLerp;
                bAz = this.beastPrevPos[bAIdx * 3 + 2] + (beast.z - this.beastPrevPos[bAIdx * 3 + 2]) * entLerp;
            }
            // P0②：假击退渲染偏移（只动绘制位置，不动 beast.x/z → 零漂移）
            float bAkb = kbCurve(beast.kbProg) * beast.kbDist;
            bAx += beast.kbDirX * bAkb; bAz += beast.kbDirZ * bAkb;
            ++bAIdx;
            // 渲染层剔除：远距(>128格)+视锥外的野兽跳过顶点提交（纯渲染，零漂移）
            {
                float bdx = bAx - this.camPos.x, bdy = bAy - this.camPos.y, bdz = bAz - this.camPos.z;
                if (bdx * bdx + bdy * bdy + bdz * bdz > 16384.0f) continue;
                if (Game.aabbOutsideFrustum(this.frustumReuse, bAx - 0.85f, bAy, bAz - 0.85f, bAx + 0.85f, bAy + 3.0f, bAz + 0.85f)) continue;
            }
            float f30;
            switch (beast.type) {
                case 1: {
                    f9 = 0.6f;
                    f8 = 0.3f;
                    f30 = 0.85f;
                    break;
                }
                case 2: {
                    f9 = 0.5f;
                    f8 = 0.5f;
                    f30 = 0.58f;
                    break;
                }
                case 3: {
                    f9 = 0.3f;
                    f8 = 0.85f;
                    f30 = 0.38f;
                    break;
                }
                default: {
                    f9 = 0.8f;
                    f8 = 0.25f;
                    f30 = 0.25f;
                }
            }
            f7 = beast == this.locked ? 1.15f : 1.0f;
            f6 = beast.hitFlash > 0.0f ? Math.min(1.0f, beast.hitFlash / 0.15f) : 0.0f;
            f9 += (1.0f - f9) * f6;
            f8 += (1.0f - f8) * f6;
            f30 += (1.0f - f30) * f6;
            // QA 2026-09-16：DS/ER 式「读招」——前摇期间体色向暖色过渡并沿锁定方向抬起前肢，
            // 越接近出手越亮；被破势/命中硬直时压暗发青。玩家据此决定“翻滚还是抢刀”。
            float bWarn = beast.warn();
            if (bWarn > 0f) {
                f9 += (1.0f - f9) * bWarn;
                f8 += (0.55f - f8) * bWarn;
                f30 *= (1.0f - 0.75f * bWarn);
                boolean bW = beast.type == Beast.TYPE_BOSS;
                float aw = bW ? 0.34f : 0.17f;
                float aOff = bW ? 0.62f : 0.34f;
                float armX = bAx + beast.lockX * aOff, armZ = bAz + beast.lockZ * aOff;
                float armY = bW ? 1.35f : 1.0f;
                float armH = bW ? (0.25f + 1.25f * bWarn) : (0.12f + 0.62f * bWarn);
                this.addBoxWorld(this.entBuf, armX - aw, bAy + armY, armZ - aw,
                        armX + aw, bAy + armY + armH, armZ + aw, 0.92f, 0.42f, 0.12f);
            } else if (beast.stagger > 0) {
                f9 *= 0.55f; f8 *= 0.55f; f30 = Math.min(1.0f, f30 + 0.45f);
            }
            if (beast.type == Beast.TYPE_BOSS) {
                // P0-4：守卫体型 ≈ 2.5x（躯干 ±0.75 / 肩甲 ±0.80 / 双眼 / 冠刺顶 2.85）。
                // 全部尺寸必须落在 Beast.HW[4]=1.00 / HH[4]=2.90 之内 —— 这是
                // audit_invariants.py C3（渲染盒 ⊆ 碰撞盒）的合同，改模型必须同步那张表。
                this.addBoxWorld(this.entBuf, bAx - 0.75f, bAy, bAz - 0.75f, bAx + 0.75f, bAy + 2.5f, bAz + 0.75f, f9 * f7, f8 * f7, f30 * f7);
                this.addBoxWorld(this.entBuf, bAx - 0.8f, bAy + 1.45f, bAz - 0.6f, bAx + 0.8f, bAy + 1.9f, bAz + 0.6f, 0.52f, 0.19f, 0.17f);
                this.addBoxWorld(this.entBuf, bAx - 0.42f, bAy + 2.05f, bAz - 0.78f, bAx - 0.24f, bAy + 2.22f, bAz - 0.7f, 1.0f, 0.34f, 0.2f);
                this.addBoxWorld(this.entBuf, bAx + 0.24f, bAy + 2.05f, bAz - 0.78f, bAx + 0.42f, bAy + 2.22f, bAz - 0.7f, 1.0f, 0.34f, 0.2f);
                this.addBoxWorld(this.entBuf, bAx - 0.3f, bAy + 2.5f, bAz - 0.3f, bAx - 0.08f, bAy + 2.85f, bAz + 0.3f, 0.6f, 0.23f, 0.2f);
                this.addBoxWorld(this.entBuf, bAx + 0.08f, bAy + 2.5f, bAz - 0.3f, bAx + 0.3f, bAy + 2.85f, bAz + 0.3f, 0.6f, 0.23f, 0.2f);
            } else {
            this.addBoxWorld(this.entBuf, bAx - 0.3f, bAy, bAz - 0.3f, bAx + 0.3f, bAy + 1.1f, bAz + 0.3f, f9 * f7, f8 * f7, f30 * f7);
            this.addBoxWorld(this.entBuf, bAx - 0.18f, bAy + 0.75f, bAz - 0.32f, bAx - 0.05f, bAy + 0.95f, bAz - 0.25f, 1.0f, 1.0f, 0.2f);
            this.addBoxWorld(this.entBuf, bAx + 0.05f, bAy + 0.75f, bAz - 0.32f, bAx + 0.18f, bAy + 0.95f, bAz - 0.25f, 1.0f, 1.0f, 0.2f);
            f5 = (float)Math.max(0, beast.hp) / (float)beast.maxHp;
            this.addBoxWorld(this.entBuf, bAx - 0.35f, bAy + 1.2f, bAz - 0.05f, bAx + 0.35f, bAy + 1.32f, bAz + 0.05f, 0.1f, 0.1f, 0.1f);
            this.addBoxWorld(this.entBuf, bAx - 0.33f, bAy + 1.22f, bAz - 0.04f, bAx - 0.33f + 0.66f * f5, bAy + 1.3f, bAz + 0.04f, 0.2f, 0.9f, 0.2f);
            }
            if (!(beast.hitFlash > 0.05f) || this.frameCount % 2 != 0) continue;
            this.spawnSparks(bAx, bAy, bAz);
        }
        for (Npc npc : this.sim.world.npcs) {
            if (npc.dead()) continue;
            // 在相邻 sim tick 之间插值位置，消除 20Hz 位置跳变（闪现）
            float npcX = npc.prevX + (npc.x - npc.prevX) * entLerp;
            float npcY = npc.prevY + (npc.y - npc.prevY) * entLerp;
            float npcZ = npc.prevZ + (npc.z - npc.prevZ) * entLerp;
            // 用 this.time（连续真实秒）统一所有实体动画时基，不再用 frameCount（帧率抖动会顿）
            float npcPhase = (float)(npc.id.hashCode()) * 0.3f;
            float moveDist = (float)Math.sqrt((npc.x - npc.prevX) * (npc.x - npc.prevX)
                    + (npc.z - npc.prevZ) * (npc.z - npc.prevZ));
            float npcSpeed = moveDist * 20.0f;   // 每 tick 0.05s -> units/sec
            float walkBob = Math.abs((float)Math.sin(this.time * 10.0f + npcPhase)) * Math.min(npcSpeed * 0.04f, 0.06f);
            float drawY = npcY + walkBob;
            // 渲染层剔除：远距+视锥外的 NPC 跳过顶点提交（纯渲染，零漂移）
            {
                float ndx = npcX - this.camPos.x, ndy = npcY - this.camPos.y, ndz = npcZ - this.camPos.z;
                if (ndx * ndx + ndy * ndy + ndz * ndz > 16384.0f) continue;
                if (Game.aabbOutsideFrustum(this.frustumReuse, npcX - 0.3f, npcY - 0.1f, npcZ - 0.3f, npcX + 0.3f, npcY + 2.3f, npcZ + 0.3f)) continue;
            }
            float[] fArray6 = Game.profColor(npc.profession);
            float[] fArray7 = Game.moodColor(npc.social.mood);
            float f31 = fArray6[0] * 0.55f;
            f7 = fArray6[1] * 0.55f;
            f6 = fArray6[2] * 0.58f;
            this.addBoxWorld(this.entBuf, npcX - 0.26f, drawY, npcZ - 0.26f, npcX + 0.26f, drawY + 0.42f, npcZ + 0.26f, f31, f7, f6);
            this.addBoxWorld(this.entBuf, npcX - 0.26f, drawY + 0.42f, npcZ - 0.26f, npcX + 0.26f, drawY + 1.05f, npcZ + 0.26f, fArray6[0], fArray6[1], fArray6[2]);
            this.addBoxWorld(this.entBuf, npcX - 0.18f, drawY + 1.05f, npcZ - 0.18f, npcX + 0.18f, drawY + 1.42f, npcZ + 0.18f, 0.92f, 0.78f, 0.62f);
            float[] fArray8 = Avatar.villagerHat(npc.profession);
            f4 = drawY + 1.42f;
            if (fArray8[3] == 1.0f) {
                this.addBoxWorld(this.entBuf, npcX - 0.28f, f4, npcZ - 0.28f, npcX + 0.28f, f4 + 0.07f, npcZ + 0.28f, fArray8[0], fArray8[1], fArray8[2]);
            } else if (fArray8[3] == 2.0f) {
                this.addBoxWorld(this.entBuf, npcX - 0.12f, f4, npcZ - 0.12f, npcX + 0.12f, f4 + 0.26f, npcZ + 0.12f, fArray8[0], fArray8[1], fArray8[2]);
            } else if (fArray8[3] == 3.0f) {
                this.addBoxWorld(this.entBuf, npcX - 0.2f, f4 - 0.06f, npcZ - 0.2f, npcX + 0.2f, f4 + 0.12f, npcZ + 0.2f, fArray8[0], fArray8[1], fArray8[2]);
            } else if (fArray8[3] == 4.0f) {
                this.addBoxWorld(this.entBuf, npcX - 0.21f, f4 - 0.02f, npcZ - 0.21f, npcX + 0.21f, f4 + 0.1f, npcZ + 0.21f, fArray8[0], fArray8[1], fArray8[2]);
            }
            f3 = (float)Math.sin(this.time * 2.0f + npcPhase) * 0.06f;
            this.addBoxWorld(this.entBuf, npcX - 0.09f, drawY + 1.78f + f3, npcZ - 0.09f, npcX + 0.09f, drawY + 1.96f + f3, npcZ + 0.09f, fArray7[0], fArray7[1], fArray7[2]);
            if (npc != this.nearNpc) continue;
            this.addBoxWorld(this.entBuf, npcX - 0.13f, drawY + 2.06f, npcZ - 0.13f, npcX + 0.13f, drawY + 2.19f, npcZ + 0.13f, 1.0f, 1.0f, 0.7f);
        }
        for (World.Shrine shrine : this.sim.world.shrines) {
            f9 = shrine.claimed ? 0.55f : 0.3f;
            f8 = shrine.claimed ? 0.55f : 0.85f;
            float f32 = shrine.claimed ? 0.2f : 1.0f;
            this.addBoxWorld(this.entBuf, shrine.x - 0.35f, shrine.y - 0.1f, shrine.z - 0.35f, shrine.x + 0.35f, shrine.y + 0.2f, shrine.z + 0.35f, f9, f8, f32);
            this.addBoxWorld(this.entBuf, shrine.x - 0.12f, shrine.y + 0.2f, shrine.z - 0.12f, shrine.x + 0.12f, shrine.y + 3.2f, shrine.z + 0.12f, f9, f8, f32);
            f7 = (float)Math.sin(this.time * 4.0f) * 0.15f;
            this.addBoxWorld(this.entBuf, shrine.x - 0.25f, shrine.y + 3.2f + f7, shrine.z - 0.25f, shrine.x + 0.25f, shrine.y + 3.7f + f7, shrine.z + 0.25f, Math.min(1.0f, f9 * 1.3f), Math.min(1.0f, f8 * 1.3f), Math.min(1.0f, f32 * 1.3f));
        }
        Trials trials = this.sim.world.trials;
        for (Trials.Site site : trials.sites) {
            float f33;
            if ("GLIDE".equals(site.ability)) {
                f8 = 0.45f;
                f33 = 0.85f;
                f7 = 0.98f;
            } else if ("DASH".equals(site.ability)) {
                f8 = 0.98f;
                f33 = 0.78f;
                f7 = 0.25f;
            } else {
                f8 = 0.98f;
                f33 = 0.45f;
                f7 = 0.28f;
            }
            if (site.claimed) {
                f8 *= 0.38f;
                f33 *= 0.38f;
                f7 *= 0.38f;
            }
            this.addBoxWorld(this.entBuf, site.x - 0.42f, site.y - 0.1f, site.z - 0.42f, site.x + 0.42f, site.y + 0.25f, site.z + 0.42f, f8, f33, f7);
            this.addBoxWorld(this.entBuf, site.x - 0.14f, site.y + 0.25f, site.z - 0.14f, site.x + 0.14f, site.y + 3.6f, site.z + 0.14f, f8, f33, f7);
            if (site.claimed) continue;
            boolean bl = this.sim.player.abilities.contains(site.ability);
            f5 = bl ? 1.0f : 0.6f;
            f4 = (float)Math.sin(this.time * 3.0f + site.x) * 0.14f;
            this.addBoxWorld(this.entBuf, site.x - 0.24f, site.y + 3.6f + f4, site.z - 0.24f, site.x + 0.24f, site.y + 4.05f + f4, site.z + 0.24f, Math.min(1.0f, f8 * f5 + 0.25f), Math.min(1.0f, f33 * f5 + 0.25f), Math.min(1.0f, f7 * f5 + 0.25f));
        }
        for (Trials.Cache cache : trials.caches) {
            if (cache.claimed) continue;
            this.addBoxWorld(this.entBuf, cache.x - 0.28f, cache.y, cache.z - 0.28f, cache.x + 0.28f, cache.y + 0.48f, cache.z + 0.28f, 0.52f, 0.34f, 0.16f);
            this.addBoxWorld(this.entBuf, cache.x - 0.3f, cache.y + 0.48f, cache.z - 0.3f, cache.x + 0.3f, cache.y + 0.6f, cache.z + 0.3f, 0.85f, 0.68f, 0.25f);
            f8 = (float)Math.sin(this.time * 5.0f + cache.z) * 0.08f;
            this.addBoxWorld(this.entBuf, cache.x - 0.08f, cache.y + 0.7f + f8, cache.z - 0.08f, cache.x + 0.08f, cache.y + 0.86f + f8, cache.z + 0.08f, 1.0f, 0.96f, 0.62f);
        }
        // 内容扩张：容器箱（实体层 World.chests）—— 品质分档上色，纯渲染层读世界状态、零仿真影响：
        //   COMMON 木箱(琥珀扣) / RARE 铁箱(银蓝扣) / HIDDEN 幽紫石箱(紫扣，呼吸更亮作微弱提示)。
        // 未开启才画，开启后自动从画面消失。
        for (World.Chest chest : this.sim.world.chests) {
            if (chest.opened) continue;
            float br, bg, bb, lr, lg, lb, cr, cg, cb;      // 箱身 / 箱盖 / 锁扣
            if (chest.tier == World.Chest.TIER_HIDDEN) {
                br=0.20f; bg=0.17f; bb=0.30f; lr=0.46f; lg=0.34f; lb=0.72f; cr=1.00f; cg=0.62f; cb=1.00f;
            } else if (chest.tier == World.Chest.TIER_RARE) {
                br=0.30f; bg=0.32f; bb=0.38f; lr=0.60f; lg=0.66f; lb=0.78f; cr=0.80f; cg=0.88f; cb=1.00f;
            } else {
                br=0.40f; bg=0.24f; bb=0.11f; lr=0.72f; lg=0.52f; lb=0.20f; cr=1.00f; cg=0.85f; cb=0.30f;
            }
            this.addBoxWorld(this.entBuf, chest.x - 0.30f, chest.y, chest.z - 0.30f, chest.x + 0.30f, chest.y + 0.34f, chest.z + 0.30f, br, bg, bb);
            this.addBoxWorld(this.entBuf, chest.x - 0.34f, chest.y + 0.34f, chest.z - 0.34f, chest.x + 0.34f, chest.y + 0.46f, chest.z + 0.34f, lr, lg, lb);
            float chGlow = (float)Math.sin(this.time * 4.0f + chest.z) * 0.06f;
            this.addBoxWorld(this.entBuf, chest.x - 0.07f, chest.y + 0.18f + chGlow, chest.z - 0.35f, chest.x + 0.07f, chest.y + 0.32f + chGlow, chest.z - 0.28f, cr, cg, cb);
        }
        // 挖掘进度（MC 手感 §1 P0 #1）：原先在目标格上叠一层"随进度整体变暗的暗色方块"。
        // 2026-09-20 升级为 **MC 原版做法**：贴 10 级破坏裂纹贴图（{@link #drawCrackOverlay()}）——
        // 好处是方块自身的纹理始终可见，玩家能读出"还差多少"；暗色方块则会把纹理整个盖住。
        // 画序也从这里（实体 pass）挪到"不透明世界之后、半透明之前"，见 drawFrame。
        // 掉落物（MC 手感 §1 P0 #2）：小方块 + 上下浮动 + 绕自身旋转（浮动/旋转纯渲染层，用 this.time；
        // 位置取自 sim 层 Drop，零仿真影响）。用两片偏移方块近似「旋转的小立方」，不新增 GLSL。
        for (core.world.World.Drop dr : this.sim.world.drops) {
            core.content.ItemDef idef = (this.content != null) ? this.content.item(dr.itemId) : null;
            int dc = (idef != null && idef.color != 0) ? idef.color : 0x9A9AA2;
            float dr0 = ((dc >> 16) & 255) / 255.0f, dg0 = ((dc >> 8) & 255) / 255.0f, db0 = (dc & 255) / 255.0f;
            float bob = (float) Math.sin(this.time * 3.0f + dr.x * 1.7f) * 0.09f;
            float dyc = dr.y + 0.30f + bob;
            float sz = 0.13f;
            float ang = this.time * 2.4f + dr.z;
            float ox = (float) Math.cos(ang) * 0.09f, oz = (float) Math.sin(ang) * 0.09f;
            this.addBoxWorld(this.entBuf, dr.x - sz + ox, dyc - sz, dr.z - sz + oz, dr.x + sz + ox, dyc + sz, dr.z + sz + oz, dr0, dg0, db0);
            this.addBoxWorld(this.entBuf, dr.x - sz - ox, dyc - sz, dr.z - sz - oz, dr.x + sz - ox, dyc + sz, dr.z + sz - oz,
                    Math.min(1.0f, dr0 * 1.3f), Math.min(1.0f, dg0 * 1.3f), Math.min(1.0f, db0 * 1.3f));
        }
        if (trials.heartRevealed && !trials.heartClaimed) {
            float f34 = (float)Math.sin(this.time * 2.5f) * 0.3f;
            this.addBoxWorld(this.entBuf, trials.heartX - 0.55f, trials.heartY + 0.5f + f34, trials.heartZ - 0.55f, trials.heartX + 0.55f, trials.heartY + 1.7f + f34, trials.heartZ + 0.55f, 1.0f, 0.32f, 0.55f);
            this.addBoxWorld(this.entBuf, trials.heartX - 0.3f, trials.heartY + 0.95f + f34, trials.heartZ - 0.3f, trials.heartX + 0.3f, trials.heartY + 1.35f + f34, trials.heartZ + 0.3f, 1.0f, 0.92f, 0.95f);
        }
        if (this.artFxArt != null && this.artFxTimer > 0.0f) {
            float f35 = 1.0f - this.artFxTimer / this.artFxDur;
            f9 = Math.max(0.05f, 1.0f - f35);
            if (this.artFxArt == Weapons.Art.CLEAVE || this.artFxArt == Weapons.Art.CHARGE) {
                f8 = 0.9f + f35 * 2.3f;
                n = (int)Math.floor(this.sim.player.y) + 1;
                int n2 = 20;
                for (int i = 0; i < n2; ++i) {
                    double d = (double)i * 2.0 * Math.PI / (double)n2;
                    f3 = this.sim.player.x + (float)Math.cos(d) * f8;
                    f2 = this.sim.player.z + (float)Math.sin(d) * f8;
                    this.addBoxWorld(this.entBuf, f3 - 0.1f, n, f2 - 0.1f, f3 + 0.1f, (float)n + 0.16f, f2 + 0.1f, 0.98f * f9, 0.66f * f9, 0.24f * f9);
                }
            } else if (this.artFxArt == Weapons.Art.SHOT && this.artFxHasTarget) {
                f8 = this.sim.player.x;
                float f36 = this.sim.player.y + 1.3f;
                f7 = this.sim.player.z;
                for (int i = 1; i <= 9; ++i) {
                    f5 = (float)i / 9.0f;
                    f4 = f8 + (this.artFxTx - f8) * f5;
                    f3 = f36 + (this.artFxTy - f36) * f5;
                    f2 = f7 + (this.artFxTz - f7) * f5;
                    f = 0.05f + 0.05f * f5;
                    this.addBoxWorld(this.entBuf, f4 - f, f3 - f, f2 - f, f4 + f, f3 + f, f2 + f, 0.55f * f9, 0.98f * f9, 0.62f * f9);
                }
            } else {
                f8 = this.artFxDx;
                float f37 = this.artFxDz;
                for (int i = 1; i <= 6; ++i) {
                    f6 = 0.55f + (float)i * 0.52f;
                    f5 = f9 * (1.0f - (float)i * 0.1f);
                    f4 = this.sim.player.x + f8 * f6;
                    f3 = this.sim.player.z + f37 * f6;
                    f2 = this.sim.player.y + 1.05f + (float)i * 0.06f;
                    f = 0.085f;
                    this.addBoxWorld(this.entBuf, f4 - f, f2 - f, f3 - f, f4 + f, f2 + f, f3 + f, 0.72f * f5, 0.88f * f5, 1.0f * f5);
                }
            }
        }
        // 粒子绘制：远距 + 视锥剔除，避免把成百上千（含远处）粒子全量提交顶点/填充率
        final float pcx = this.camPos.x, pcy = this.camPos.y, pcz = this.camPos.z;
        for (int pi = 0; pi < this.particles.count(); ++pi) {
            float pxx = this.particles.x(pi), pyy = this.particles.y(pi), pzz = this.particles.z(pi);
            float pdx = pxx - pcx, pdy = pyy - pcy, pdz = pzz - pcz;
            if (pdx * pdx + pdy * pdy + pdz * pdz > PARTICLE_CULL_DIST_SQ) continue;   // 过远不提交
            float ps = this.particles.size(pi);
            if (Game.aabbOutsideFrustum(this.frustumReuse, pxx - ps, pyy - ps, pzz - ps, pxx + ps, pyy + ps, pzz + ps)) continue;  // 屏幕外不提交
            // 死键落地（§2.1）：material/format/fade 影响粒子表现（纯渲染层，不影响仿真指纹）。
            // 用 visibility alpha 缩放 RGB，使 color1 的 alpha 与 fade 淡入淡出真正生效。
            float pa = this.particles.alpha(pi);
            float pr = this.particles.red(pi) * pa, pg = this.particles.green(pi) * pa, pb = this.particles.blue(pi) * pa;
            if (this.particles.glow(pi)) { pr = Math.min(1f, pr * 1.6f + 0.15f); pg = Math.min(1f, pg * 1.6f + 0.15f); pb = Math.min(1f, pb * 1.6f + 0.15f); }
            else if (this.particles.format(pi) == 1) { pr *= 0.85f; pg *= 0.85f; pb *= 0.85f; } // streak 略暗
            else if (this.particles.format(pi) == 2) { pr = Math.min(1f, pr + 0.12f); pg = Math.min(1f, pg + 0.12f); pb = Math.min(1f, pb + 0.12f); } // soft 略亮
            this.addBoxWorld(this.entBuf, pxx - ps, pyy - ps, pzz - ps, pxx + ps, pyy + ps, pzz + ps,
                    pr, pg, pb);
        }
        // 招架 / 处决专属演出（纯渲染层）：以玩家为中心扩散的环。
        // 招架 = 青白冷环（收得快）；处决 = 红黑冲击环（更大更慢）。只读玩家位置 + 两个渲染计时 → 零仿真影响。
        if (this.parryFxTimer > 0.0f) {
            float u = 1.0f - this.parryFxTimer / PARRY_FX_DUR;          // 0→1
            float rad = 0.55f + u * 2.4f, al = Math.max(0.06f, 1.0f - u);
            this.drawFxRing(this.sim.player.x, this.sim.player.y + 1.05f, this.sim.player.z, rad, 0.085f,
                    0.45f * al, 0.95f * al, 1.00f * al);
        }
        if (this.executeFxTimer > 0.0f) {
            float u = 1.0f - this.executeFxTimer / EXECUTE_FX_DUR;
            float rad = 0.50f + u * 3.2f, al = Math.max(0.06f, 1.0f - u);
            this.drawFxRing(this.sim.player.x, this.sim.player.y + 0.95f, this.sim.player.z, rad, 0.11f,
                    1.00f * al, 0.16f * al, 0.13f * al);
        }
        this.entBuf.flip();
        this.setVP(matrix4f);
        GL33.glBindVertexArray((int)this.entVAO);
        GL33.glBindBuffer((int)34962, (int)this.entVBO);
        GL33.glEnable((int)2960);
        GL33.glStencilOp((int)7680, (int)7680, (int)7681);
        GL33.glStencilFunc((int)519, (int)1, (int)255);
        GL33.glStencilMask((int)255);
        GL33.glBufferSubData((int)34962, (long)0L, (FloatBuffer)this.entBuf);
        GL33.glDrawArrays((int)4, (int)0, (int)(this.entBuf.remaining() / 6));
        this.entBuf.clear();
        if (this.thirdPerson) {
            this.addBoxWorld(this.entBuf, player.x - 0.28f, player.y - 0.02f, player.z - 0.28f, player.x + 0.28f, player.y + 1.6f, player.z + 0.28f, 0.03f, 0.03f, 0.05f);
        }
        int bDIdx = 0;
        for (Beast beast : this.sim.world.beasts) {
            float bDx = beast.x, bDy = beast.y, bDz = beast.z;
            if (this.beastPrevRef != null && bDIdx < this.beastPrevCount && this.beastPrevRef[bDIdx] == beast) {
                bDx = this.beastPrevPos[bDIdx * 3] + (beast.x - this.beastPrevPos[bDIdx * 3]) * entLerp;
                bDy = this.beastPrevPos[bDIdx * 3 + 1] + (beast.y - this.beastPrevPos[bDIdx * 3 + 1]) * entLerp;
                bDz = this.beastPrevPos[bDIdx * 3 + 2] + (beast.z - this.beastPrevPos[bDIdx * 3 + 2]) * entLerp;
            }
            // P0②：假击退渲染偏移（剪影暗盒与本体必须同位置，否则"被遮挡时错位闪烁"）
            float bDkb = kbCurve(beast.kbProg) * beast.kbDist;
            bDx += beast.kbDirX * bDkb; bDz += beast.kbDirZ * bDkb;
            ++bDIdx;
            // 渲染层剔除（与本体一致）：远距+视锥外不画剪影暗盒
            {
                float bdx = bDx - this.camPos.x, bdy = bDy - this.camPos.y, bdz = bDz - this.camPos.z;
                if (bdx * bdx + bdy * bdy + bdz * bdz > 16384.0f) continue;
                float bDs2 = beast.type == Beast.TYPE_BOSS ? 2.5f : 1.0f;
                if (Game.aabbOutsideFrustum(this.frustumReuse, bDx - 0.34f * bDs2, bDy - 0.02f, bDz - 0.34f * bDs2, bDx + 0.34f * bDs2, bDy + 1.14f * bDs2, bDz + 0.34f * bDs2)) continue;
            }
            // 剪影暗盒随体型缩放（守卫 2.5x）—— 暗盒比本体小会让"被遮挡时显出半个身子"
            float bDs = beast.type == Beast.TYPE_BOSS ? 2.5f : 1.0f;
            this.addBoxWorld(this.entBuf, bDx - 0.34f * bDs, bDy - 0.02f, bDz - 0.34f * bDs,
                    bDx + 0.34f * bDs, bDy + 1.14f * bDs, bDz + 0.34f * bDs, 0.03f, 0.03f, 0.05f);
        }
        for (Npc npc : this.sim.world.npcs) {
            if (npc.dead()) continue;
            // 剪影暗盒必须和 NPC 本体用同一个插值位置，否则两层会互相错开 -> 看起来在闪
            float nDx = npc.prevX + (npc.x - npc.prevX) * entLerp;
            float nDy = npc.prevY + (npc.y - npc.prevY) * entLerp;
            float nDz = npc.prevZ + (npc.z - npc.prevZ) * entLerp;
            // 渲染层剔除（与本体一致）：远距+视锥外不画剪影暗盒
            {
                float ndx = nDx - this.camPos.x, ndy = nDy - this.camPos.y, ndz = nDz - this.camPos.z;
                if (ndx * ndx + ndy * ndy + ndz * ndz > 16384.0f) continue;
                if (Game.aabbOutsideFrustum(this.frustumReuse, nDx - 0.29f, nDy - 0.02f, nDz - 0.29f, nDx + 0.29f, nDy + 1.7f, nDz + 0.29f)) continue;
            }
            this.addBoxWorld(this.entBuf, nDx - 0.29f, nDy - 0.02f, nDz - 0.29f, nDx + 0.29f, nDy + 1.7f, nDz + 0.29f, 0.03f, 0.03f, 0.05f);
        }
        this.entBuf.flip();
        this.setVP(matrix4f);
        GL33.glBindVertexArray((int)this.entVAO);
        GL33.glBindBuffer((int)34962, (int)this.entVBO);
        GL33.glStencilFunc((int)514, (int)0, (int)255);
        GL33.glStencilMask((int)0);
        GL33.glEnable((int)32823);
        GL33.glPolygonOffset((float)2.5f, (float)6.0f);
        GL33.glBufferSubData((int)34962, (long)0L, (FloatBuffer)this.entBuf);
        GL33.glDrawArrays((int)4, (int)0, (int)(this.entBuf.remaining() / 6));
        GL33.glDisable((int)32823);
        GL33.glDisable((int)2960);
        GL33.glStencilMask((int)255);
        GL33.glBindVertexArray((int)0);
        this.entBuf.clear();
    }

    private void spawnSoulMotes(float f, float f2, float f3) {
        this.particles.spawn(this.content.get("particles", "mote"), f, f2, f3, 14);
    }

    private void spawnDust(float f, float f2, float f3) {
        this.particles.spawn(this.content.get("particles", "dust"), f, f2 + 0.05f, f3, 8);
    }

    private void spawnSparks(float f, float f2, float f3) {
        this.particles.spawn(this.content.get("particles", "spark"), f, f2 + 0.6f, f3, 4);
    }

    private void updateParticles(float f) {
        this.particles.update(f, this.sim.world);   // 物理全在 core.content.ParticleSim（可门禁）；注入 world 供 collide 停驻
        this.advancePendingFx(f);          // B 批：fx emitter 的 delay 分层时序
    }

    /**
     * HUD 顶点布局（float 数）：{@code pos3 + col4 + uv2}。共 9 个 float。
     * <p><b>为什么加 uv</b>：自研艺术 UI 的九宫格面板需要采样图集贴图。旧调用点（127 处
     * {@link #addRect2D}）不传 UV → 由发射器填 {@link TextureAtlas#WHITE_TILE} 的 UV
     * → 采样恒为纯白 → 与"无纹理"逐字节等价（identity 元素），因此<b>调用方零改动</b>。</p>
     */
    private static final int HUD_VERT_FLOATS = 9;
    /** 纯白 tile 的 UV 中心（identity：纹理采样恒返回白）。 */
    private static final float HUD_WHITE_U = TextureAtlas.uOf(TextureAtlas.UI_WHITE_TILE, 0.5f);
    private static final float HUD_WHITE_V = TextureAtlas.vOf(TextureAtlas.UI_WHITE_TILE, 0.5f);

    private void addRect2D(FloatBuffer floatBuffer, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8) {
        float f9 = f / (float)this.W * 2.0f - 1.0f;
        float f10 = 1.0f - f2 / (float)this.H * 2.0f;
        float f11 = (f + f3) / (float)this.W * 2.0f - 1.0f;
        float f12 = 1.0f - (f2 + f4) / (float)this.H * 2.0f;
        floatBuffer.put(f9).put(f10).put(0.0f).put(f5).put(f6).put(f7).put(f8).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f11).put(f10).put(0.0f).put(f5).put(f6).put(f7).put(f8).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f11).put(f12).put(0.0f).put(f5).put(f6).put(f7).put(f8).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f9).put(f10).put(0.0f).put(f5).put(f6).put(f7).put(f8).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f11).put(f12).put(0.0f).put(f5).put(f6).put(f7).put(f8).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f9).put(f12).put(0.0f).put(f5).put(f6).put(f7).put(f8).put(HUD_WHITE_U).put(HUD_WHITE_V);
    }

    private void addDiamond2D(FloatBuffer floatBuffer, float f, float f2, float f3, float f4, float f5, float f6, float f7) {
        float f8 = (f - f3) / (float)this.W * 2.0f - 1.0f;
        float f9 = (f + f3) / (float)this.W * 2.0f - 1.0f;
        float f10 = 1.0f - (f2 - f3) / (float)this.H * 2.0f;
        float f11 = 1.0f - (f2 + f3) / (float)this.H * 2.0f;
        float f12 = f / (float)this.W * 2.0f - 1.0f;
        float f13 = f2 / (float)this.H * 2.0f;
        floatBuffer.put(f12).put(f10).put(0.0f).put(f4).put(f5).put(f6).put(f7).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f9).put(f13).put(0.0f).put(f4).put(f5).put(f6).put(f7).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f12).put(f11).put(0.0f).put(f4).put(f5).put(f6).put(f7).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f12).put(f10).put(0.0f).put(f4).put(f5).put(f6).put(f7).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f12).put(f11).put(0.0f).put(f4).put(f5).put(f6).put(f7).put(HUD_WHITE_U).put(HUD_WHITE_V);
        floatBuffer.put(f8).put(f13).put(0.0f).put(f4).put(f5).put(f6).put(f7).put(HUD_WHITE_U).put(HUD_WHITE_V);
    }

    /**
     * 自研艺术 UI 的<b>九宫格面板</b>：用图集里的 UI 区贴图拼一块任意尺寸的面板
     * （圆角 + 描边恒定粗细 + 纸纹底 + 单侧受光），取代"几个纯色矩形叠出来的假面板"。
     *
     * <p>九宫格语义：4 个角不拉伸、4 条边单轴拉伸、中央双轴拉伸 → 描边粗细与圆角半径
     * 与面板尺寸无关。这是 SLG/沙盒类 UI 的标准做法（本作零外部素材，贴图程序化烘焙于
     * {@link TextureAtlas#UI_TILE_BASE}）。</p>
     *
     * <p><b>零漂移</b>：纯渲染层，只读传入参数与图集，不碰仿真状态/不进指纹。默认不被调用。</p>
     *
     * @param x,y 左上角像素坐标；w,h 面板尺寸（px）；r,g,b,a 顶点色乘子（贴图是灰阶，色相由此给）
     */
    private void addArtPanel(FloatBuffer b, float x, float y, float w, float h, float r, float g, float bl, float a) {
        float bw = Math.min(Math.min(w, h) * 0.5f, TextureAtlas.TILE_PX * TextureAtlas.UI_NINE_SLICE);  // 边框像素宽
        float x0 = x, x1 = x + bw, x2 = x + w - bw, x3 = x + w;
        float y0 = y, y1 = y + bw, y2 = y + h - bw, y3 = y + h;
        // 若面板比两倍边框还小，退化为整块单格（避免负宽度）
        if (x + w < 2 * bw || y + h < 2 * bw) { addArtQuad(b, TextureAtlas.UI_CENTER, x, y, w, h, r, g, bl, a); return; }
        int[] CX = {TextureAtlas.UI_CORNER_TL, TextureAtlas.UI_EDGE_T, TextureAtlas.UI_CORNER_TR,
                    TextureAtlas.UI_EDGE_L,   TextureAtlas.UI_CENTER, TextureAtlas.UI_EDGE_R,
                    TextureAtlas.UI_CORNER_BL, TextureAtlas.UI_EDGE_B, TextureAtlas.UI_CORNER_BR};
        float[] XS = {x0, x1, x2, x3};
        float[] YS = {y0, y1, y2, y3};
        for (int gy = 0; gy < 3; gy++) {
            for (int gx = 0; gx < 3; gx++) {
                float px0 = XS[gx], px1 = XS[gx + 1];
                float py0 = YS[gy], py1 = YS[gy + 1];
                if (px1 - px0 <= 0.01f || py1 - py0 <= 0.01f) continue;
                addArtQuad(b, CX[gy * 3 + gx], px0, py0, px1 - px0, py1 - py0, r, g, bl, a);
            }
        }
    }

    /** 单个 UI 贴图格 → 屏幕矩形（4 顶点，UV 取该 slot 的局部 [0,1]）。 */
    private void addArtQuad(FloatBuffer b, int slot, float x, float y, float w, float h, float r, float g, float bl, float a) {
        float[] xs = {x, x + w}, ys = {y, y + h};
        float[] us = {TextureAtlas.uiU(slot, 0f), TextureAtlas.uiU(slot, 1f)};
        float[] vs = {TextureAtlas.uiV(slot, 0f), TextureAtlas.uiV(slot, 1f)};
        // 两个三角形：TL,TR,BR / TL,BR,BL
        putArtVert(b, xs[0], ys[0], us[0], vs[0], r, g, bl, a);
        putArtVert(b, xs[1], ys[0], us[1], vs[0], r, g, bl, a);
        putArtVert(b, xs[1], ys[1], us[1], vs[1], r, g, bl, a);
        putArtVert(b, xs[0], ys[0], us[0], vs[0], r, g, bl, a);
        putArtVert(b, xs[1], ys[1], us[1], vs[1], r, g, bl, a);
        putArtVert(b, xs[0], ys[1], us[0], vs[1], r, g, bl, a);
    }

    private void putArtVert(FloatBuffer b, float px, float py, float u, float v, float r, float g, float bl, float a) {
        float cx = px / (float) this.W * 2.0f - 1.0f;
        float cy = 1.0f - py / (float) this.H * 2.0f;
        b.put(cx).put(cy).put(0.0f).put(r).put(g).put(bl).put(a).put(u).put(v);
    }

    /** 像素坐标 → clip 顶点（与 addRect2D 同投影；用于旋转面）。 */
    private void addClipVert(FloatBuffer b, float px, float py, float r, float g, float bl, float a) {        float cx = px / (float)this.W * 2.0f - 1.0f;
        float cy = 1.0f - py / (float)this.H * 2.0f;
        b.put(cx).put(cy).put(0.0f).put(r).put(g).put(bl).put(a).put(HUD_WHITE_U).put(HUD_WHITE_V);
    }

    /** 任意四角四边形（2 三角）。角点按 a→b→c→d 顺序（顺时针/逆时针均可，不参与背面剔除）。 */
    // ===================== QA 2026-09-15：真实天体绘制 =====================
    // 月盘 / 月相（终结线）/ 地影食 / 日食掩星。
    // 全部画成"世界空间 billboard"，并保持深度测试开启 → 山体与建筑自然遮挡天体；
    // 数据全部来自 core.world.Celestial（纯函数），这里只做几何展开，零漂移。

    /** 两位小数（Locale.US，与 HUD 其它格式化一致）。 */
    private String fmt2(float v) {
        return String.format(java.util.Locale.US, "%.2f", Float.valueOf(v));
    }

    private void addCelVert(FloatBuffer b, float x, float y, float z, float r, float g, float bl, float a) {
        b.put(x).put(y).put(z).put(r).put(g).put(bl).put(a).put(HUD_WHITE_U).put(HUD_WHITE_V);
    }

    private void addCelQuad(FloatBuffer b,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz,
                            float r, float g, float bl, float a) {
        this.addCelVert(b, ax, ay, az, r, g, bl, a);
        this.addCelVert(b, bx, by, bz, r, g, bl, a);
        this.addCelVert(b, cx, cy, cz, r, g, bl, a);
        this.addCelVert(b, ax, ay, az, r, g, bl, a);
        this.addCelVert(b, cx, cy, cz, r, g, bl, a);
        this.addCelVert(b, dx, dy, dz, r, g, bl, a);
    }

    /** 天体表面点：天体中心 + 屏幕像素偏移 → 世界坐标（billboard 与相机基平行）。 */
    private void celPoint(DayCycle.SkyBasis basis, float[] dir, float far, float pxPerWorld,
                          float dxPx, float dyPx, float[] out3) {
        float ox = dxPx / pxPerWorld;
        float oy = -dyPx / pxPerWorld;                 // 屏幕 y 向下 → 世界上方向取负
        out3[0] = this.camPos.x + dir[0] * far + basis.rx * ox + basis.ux * oy;
        out3[1] = this.camPos.y + dir[1] * far + basis.ry * ox + basis.uy * oy;
        out3[2] = this.camPos.z + dir[2] * far + basis.rz * ox + basis.uz * oy;
    }

    /**
     * 在"天体像素系"里发一个平行四边形：(u,v) 为两根正交单位轴（像素系），
     * 跨度为 u∈[u0,u1]、v∈[v0,v1]。四个角各自换算到世界坐标。
     */
    private void celQuadPx(DayCycle.SkyBasis basis, float[] dir, float far, float pxPerWorld,
                           float cxPx, float cyPx, float ux, float uy, float vx, float vy,
                           float u0, float u1, float v0, float v1,
                           float r, float g, float bl, float a) {
        float[] p = this.celPt;
        this.celPoint(basis, dir, far, pxPerWorld, cxPx + ux * u0 + vx * v0, cyPx + uy * u0 + vy * v0, p);
        float ax = p[0], ay = p[1], az = p[2];
        this.celPoint(basis, dir, far, pxPerWorld, cxPx + ux * u1 + vx * v0, cyPx + uy * u1 + vy * v0, p);
        float bx = p[0], by = p[1], bz = p[2];
        this.celPoint(basis, dir, far, pxPerWorld, cxPx + ux * u1 + vx * v1, cyPx + uy * u1 + vy * v1, p);
        float ccx = p[0], ccy = p[1], ccz = p[2];
        this.celPoint(basis, dir, far, pxPerWorld, cxPx + ux * u0 + vx * v1, cyPx + uy * u0 + vy * v1, p);
        this.addCelQuad(this.celBuf, ax, ay, az, bx, by, bz, ccx, ccy, ccz, p[0], p[1], p[2], r, g, bl, a);
    }

    /** 实心圆盘（行分带；上下极点附近按行宽淡出，边缘不出现硬边）。 */
    private void celDisc(DayCycle.SkyBasis basis, float[] dir, float far, float pxPerWorld,
                         float cx, float cy, float rpx, float r, float g, float bl, float a) {
        int rows = 24;
        float db = 2.0f * rpx / rows;
        for (int i = 0; i < rows; i++) {
            float b0 = -rpx + i * db;
            float bm = b0 + db * 0.5f;
            float h2 = rpx * rpx - bm * bm;
            if (h2 <= 0.25f) continue;
            float h = (float) Math.sqrt(h2);
            float edge = Math.min(1.0f, h / rpx * 3.0f);
            this.celQuadPx(basis, dir, far, pxPerWorld, cx, cy, 1.0f, 0.0f, 0.0f, 1.0f,
                    -h, h, b0, b0 + db, r, g, bl, a * edge);
        }
    }

    private void drawCelestial(DayCycle.SkyBasis basis, Matrix4f vp) {
        if (basis == null || this.celBuf == null) return;
        long tick = this.sim.world.tick;
        final float far = Math.max(48.0f, this.appliedViewDist * 0.8f);

        this.celAnti[0] = -this.celSun[0];
        this.celAnti[1] = -this.celSun[1];
        this.celAnti[2] = -this.celSun[2];

        float lm = core.world.Celestial.lunarMagnitude(this.celMoon, this.celSun);
        float pm = core.world.Celestial.penumbralMagnitude(this.celMoon, this.celSun);
        float sm = core.world.Celestial.solarMagnitude(this.celMoon, this.celSun);
        float cosPhase = (float) Math.cos(core.world.Celestial.elongation(tick));   // +1 满月 / −1 新月
        boolean night = DayCycle.isNight(tick);
        boolean solarOccult = sm > 0.0f;
        boolean showDarkSide = night || solarOccult;
        boolean sunFront = basis.ndc(this.celSun, this.celNdcS);
        boolean moonFront = basis.ndc(this.celMoon, this.celNdcM);

        GL33.glUseProgram((int) this.hudShader);
        this.setHudVP(vp);
        GL33.glEnable((int) 3042);
        GL33.glDepthMask((boolean) false);
        this.celBuf.clear();

        // ---- 太阳：大而亮的一颗（真实 0.265° 在 800px/70° 下只有 ~3px，不放大看不见）----
        if (sunFront && this.celSun[1] > -0.02f) {
            float sdot = basis.fx * this.celSun[0] + basis.fy * this.celSun[1] + basis.fz * this.celSun[2];
            if (sdot > 1e-4f) {
                float spx = this.H / (2.0f * far * sdot * basis.tanY);
                float scx = (this.celNdcS[0] * 0.5f + 0.5f) * this.W;
                float scy = (1.0f - (this.celNdcS[1] * 0.5f + 0.5f)) * this.H;
                float sr = Math.max(6.0f, this.H * 0.0215f);
                float dim = 1.0f - DayCycle.nightFactor(DayCycle.phase(tick));
                if (!solarOccult) {
                    // 光晕：加性混合，只加不减（天空会因此真正"发亮"）
                    GL33.glBlendFunc((int) 770, (int) 1);
                    this.celDisc(basis, this.celSun, far, spx, scx, scy, sr * 2.6f, 1.0f, 0.80f, 0.42f, 0.075f * dim);
                    this.celDisc(basis, this.celSun, far, spx, scx, scy, sr * 1.6f, 1.0f, 0.90f, 0.62f, 0.16f * dim);
                    GL33.glBlendFunc((int) 770, (int) 771);
                }
                this.celDisc(basis, this.celSun, far, spx, scx, scy, sr, 1.0f, 0.97f, 0.86f, Math.min(1.0f, 0.55f + dim));
            }
        }

        // 地平线裁剪（对齐太阳的 celSun[1] > -0.02f）：月亮沉到地平线下就不再绘制，
        // 否则它仍会被投影到屏幕、从流式世界窗口边缘的虚空处露出来 —— "透过地底看到下面的月亮"。
        if (moonFront && this.celMoon[1] > -0.02f) {
            float mdot = basis.fx * this.celMoon[0] + basis.fy * this.celMoon[1] + basis.fz * this.celMoon[2];
            if (mdot > 1e-4f && (showDarkSide || lm > 0.0f)) {
                float mpx = this.H / (2.0f * far * mdot * basis.tanY);
                float mcx = (this.celNdcM[0] * 0.5f + 0.5f) * this.W;
                float mcy = (1.0f - (this.celNdcM[1] * 0.5f + 0.5f)) * this.H;
                float rpx = Math.max(9.0f, this.H * 0.021f);

                // 亮暗分界的朝向：屏幕上"月亮 → 太阳"的方向（用相机基投影，太阳在地平线下也稳定）
                float vx0 = this.celSun[0] - this.celMoon[0];
                float vy0 = this.celSun[1] - this.celMoon[1];
                float vz0 = this.celSun[2] - this.celMoon[2];
                float uxs = vx0 * basis.rx + vy0 * basis.ry + vz0 * basis.rz;
                float uys = -(vx0 * basis.ux + vy0 * basis.uy + vz0 * basis.uz);
                float ul = (float) Math.sqrt(uxs * uxs + uys * uys);
                if (ul < 1e-6f) { uxs = 1.0f; uys = 0.0f; ul = 1.0f; }
                uxs /= ul; uys /= ul;
                float vxs = -uys, vys = uxs;

                // 地影圆心（反日点）在"月盘像素系"里的位置与半径
                boolean antiFront = basis.ndc(this.celAnti, this.celNdcA);
                float au = 0.0f, bu = 0.0f;
                float ur = rpx * (core.world.Celestial.UMBRA_RADIUS_DEG / core.world.Celestial.MOON_RADIUS_DEG);
                float pr = rpx * (core.world.Celestial.PENUMBRA_RADIUS_DEG / core.world.Celestial.MOON_RADIUS_DEG);
                if (antiFront) {
                    float adot = basis.fx * this.celAnti[0] + basis.fy * this.celAnti[1] + basis.fz * this.celAnti[2];
                    if (adot > 1e-4f) {
                        float apx = this.H / (2.0f * far * adot * basis.tanY);
                        float acx = (this.celNdcA[0] * 0.5f + 0.5f) * this.W;
                        float acy = (1.0f - (this.celNdcA[1] * 0.5f + 0.5f)) * this.H;
                        float ddx = acx - mcx, ddy = acy - mcy;
                        au = ddx * uxs + ddy * uys;
                        bu = ddx * vxs + ddy * vys;
                        // 半影/本影半径已按角半径比例（UMBRA/PENUMBRA ÷ MOON_R 视半径）由 rpx 缩放，
                        // 影与月共用同一像素尺度 → 交叠比例与真实几何一致。
                    }
                }

                final int rows = 26;
                final float db = 2.0f * rpx / rows;
                final float litR = 0.97f, litG = 0.96f, litB = 0.90f;
                for (int i = 0; i < rows; i++) {
                    float b0 = -rpx + i * db;
                    float bm = b0 + db * 0.5f;
                    float h2 = rpx * rpx - bm * bm;
                    if (h2 <= 0.25f) continue;
                    float h = (float) Math.sqrt(h2);
                    float edge = Math.min(1.0f, h / rpx * 3.0f);
                    float a0 = -cosPhase * h;                        // 终结线（明暗分界）
                    if (a0 < h - 0.02f) {                            // 被照亮的一侧
                        this.celQuadPx(basis, this.celMoon, far, mpx, mcx, mcy, uxs, uys, vxs, vys,
                                a0, h, b0, b0 + db, litR, litG, litB, 1.0f);
                    }
                    if (showDarkSide && a0 > -h + 0.02f) {           // 背光面（地照：极暗，不是纯黑）
                        this.celQuadPx(basis, this.celMoon, far, mpx, mcx, mcy, uxs, uys, vxs, vys,
                                -h, a0, b0, b0 + db, 0.045f, 0.055f, 0.090f, 0.92f * edge);
                    }
                    if (antiFront && pm > 0.0f) {                    // 半影：整盘轻微压暗
                        float dv = bm - bu;
                        if (Math.abs(dv) < pr) {
                            float hu = (float) Math.sqrt(pr * pr - dv * dv);
                            float lo = Math.max(-h, au - hu), hi = Math.min(h, au + hu);
                            if (hi > lo) {
                                this.celQuadPx(basis, this.celMoon, far, mpx, mcx, mcy, uxs, uys, vxs, vys,
                                        lo, hi, b0, b0 + db, 0.10f, 0.10f, 0.14f, 0.17f * Math.min(1.0f, pm));
                            }
                        }
                    }
                    if (antiFront && lm > 0.0f) {                    // 本影：偏红（血月），压在亮面之上
                        float dv = bm - bu;
                        if (Math.abs(dv) < ur) {
                            float hu = (float) Math.sqrt(ur * ur - dv * dv);
                            float lo = Math.max(-h, au - hu), hi = Math.min(h, au + hu);
                            if (hi > lo) {
                                this.celQuadPx(basis, this.celMoon, far, mpx, mcx, mcy, uxs, uys, vxs, vys,
                                        lo, hi, b0, b0 + db, 0.32f, 0.075f, 0.05f, 0.94f);
                            }
                        }
                    }
                }
            }
        }

        this.celBuf.flip();
        if (this.celBuf.remaining() > 0) {
            GL33.glBlendFunc((int) 770, (int) 771);
            GL33.glBindVertexArray((int) this.celVAO);
            GL33.glBindBuffer((int) 34962, (int) this.celVBO);
            GL33.glBufferSubData((int) 34962, (long) 0L, (FloatBuffer) this.celBuf);
            GL33.glDrawArrays((int) 4, (int) 0, (int) (this.celBuf.remaining() / HUD_VERT_FLOATS));
            GL33.glBindVertexArray((int) 0);
        }
        GL33.glDepthMask((boolean) true);
        GL33.glDisable((int) 3042);
    }

    private void addQuad2D(FloatBuffer b, float ax, float ay, float bx, float by,
                           float cx2, float cy2, float dx, float dy,
                           float r, float g, float bl, float a) {
        addClipVert(b, ax, ay, r, g, bl, a); addClipVert(b, bx, by, r, g, bl, a); addClipVert(b, cx2, cy2, r, g, bl, a);
        addClipVert(b, ax, ay, r, g, bl, a); addClipVert(b, cx2, cy2, r, g, bl, a); addClipVert(b, dx, dy, r, g, bl, a);
    }

    /**
     * C 批③：第一人称手持物视模型。跟随攻击状态机摆动（纯渲染读取 player.attackSwing()）。
     * 画一个伪 3D 立方体（前脸最亮 + 顶脸亮 + 右脸暗 + 手部），挥砍时绕手部支点旋转抬落。
     * 不写任何 sim/指纹状态 → 零漂移。
     */
    private void drawHeldViewmodel(FloatBuffer buf, core.content.ItemDef d, float swing) {
        float cx = (float)this.W / 2.0f + 64.0f;       // 屏幕中心偏右（MC 风）
        float baseY = (float)this.H - 26.0f;          // 手部支点（近底边）
        float s = 56.0f;
        float rest = -0.18f;                           // 静止微倾角
        float ang, bob;
        if (swing < 0f) { ang = rest; bob = 0f; }
        else { ang = rest + (float)Math.sin(swing * Math.PI) * 1.15f; bob = (float)Math.sin(swing * Math.PI) * s * 0.45f; }
        int col = (d != null && d.color != 0) ? d.color : 0x9A9AA2;
        float cr = (float)((col >> 16) & 255) / 255.0f;
        float cg = (float)((col >> 8) & 255) / 255.0f;
        float cb = (float)(col & 255) / 255.0f;
        // 手部（皮肤色小方块，不旋转）
        this.addRect2D(buf, cx - 11.0f, baseY - 16.0f, 22.0f, 26.0f, 0.85f, 0.66f, 0.52f, 0.98f);
        // 立方体中心：相对手部抬起 + 随挥砍绕支点旋转 + 抬落
        float lx = 0f, ly = -(s * 0.95f);
        float rx = lx * (float)Math.cos(ang) - ly * (float)Math.sin(ang);
        float ry = lx * (float)Math.sin(ang) + ly * (float)Math.cos(ang);
        float icx = cx + rx, icy = baseY + ry - bob;
        float hs = s / 2.0f;
        float ca = (float)Math.cos(ang), sa = (float)Math.sin(ang);
        float[][] fc = new float[4][2];
        float[][] off = {{-hs, -hs}, {hs, -hs}, {hs, hs}, {-hs, hs}};
        for (int k = 0; k < 4; ++k) {
            float dx = off[k][0], dy = off[k][1];
            fc[k][0] = icx + dx * ca - dy * sa;
            fc[k][1] = icy + dx * sa + dy * ca;
        }
        float ox = s * 0.34f, oy = -s * 0.34f;          // 伪深度偏移（右上）
        // 顶脸（前上边 + 偏移）
        addQuad2D(buf, fc[0][0], fc[0][1], fc[1][0], fc[1][1], fc[1][0] + ox, fc[1][1] + oy, fc[0][0] + ox, fc[0][1] + oy,
                Math.min(1f, cr * 1.2f), Math.min(1f, cg * 1.2f), Math.min(1f, cb * 1.2f), 0.97f);
        // 右脸（前右边 + 偏移）
        addQuad2D(buf, fc[1][0], fc[1][1], fc[2][0], fc[2][1], fc[2][0] + ox, fc[2][1] + oy, fc[1][0] + ox, fc[1][1] + oy,
                cr * 0.55f, cg * 0.55f, cb * 0.55f, 0.97f);
        // 前脸（最亮，最后画盖住侧面根部）
        addQuad2D(buf, fc[0][0], fc[0][1], fc[1][0], fc[1][1], fc[2][0], fc[2][1], fc[3][0], fc[3][1], cr, cg, cb, 0.99f);
        // 工具：中央一道深色握把线（辨识镐/剑）
        if (d != null && d.tool) {
            this.addRect2D(buf, icx - 3.0f, icy - hs * 0.9f, 6.0f, hs * 1.8f, 0.15f, 0.12f, 0.10f, 0.9f);
        }
    }

    /** F 批：捏脸预览头像（纯渲染读取 menu.working，零漂移；不进任何仿真状态）。 */
    private void drawCharacterOverlay(FloatBuffer buf) {
        core.world.Appearance a = this.menu.working;
        float px = (float)this.W / 2.0f - 380.0f;
        float py = (float)this.H / 2.0f - 150.0f;
        float pw = 300.0f, ph = 320.0f;
        this.panel(buf, px, py, pw, ph, 0.06f, 0.07f, 0.11f, 0.92f);
        this.addRect2D(buf, px - 3.0f, py - 3.0f, pw + 6.0f, 3.0f, 0.5f, 0.55f, 0.6f, 0.9f);
        float cx = px + pw / 2.0f;
        float headTop = py + 70.0f;
        float[] fd2 = Avatar.faceDims(a.faceShape);
        float headW = 150.0f * (fd2[0] / 0.18f), headH = 175.0f * (fd2[1] / 0.30f);
        float r = (float)((a.skin >> 16) & 255) / 255.0f;
        float g = (float)((a.skin >> 8) & 255) / 255.0f;
        float b = (float)(a.skin & 255) / 255.0f;
        float[] tint = Avatar.ageSkinTint(a.age);
        r *= tint[0]; g *= tint[1]; b *= tint[2];
        float hr = (float)((a.hair >> 16) & 255) / 255.0f;
        float hg = (float)((a.hair >> 8) & 255) / 255.0f;
        float hb = (float)(a.hair & 255) / 255.0f;
        float er = (float)((a.eye >> 16) & 255) / 255.0f;
        float eg = (float)((a.eye >> 8) & 255) / 255.0f;
        float eb = (float)(a.eye & 255) / 255.0f;
        float ar = (float)((a.accent >> 16) & 255) / 255.0f;
        float ag = (float)((a.accent >> 8) & 255) / 255.0f;
        float ab = (float)(a.accent & 255) / 255.0f;
        // 颈/肩（accent 色；build 影响肩宽）
        float shoulderW = headW * (0.9f + a.build * 0.28f);
        this.addRect2D(buf, cx - shoulderW / 2.0f, headTop + headH - 6.0f, shoulderW, 70.0f, ar, ag, ab, 0.95f);
        // 脸
        this.addRect2D(buf, cx - headW / 2.0f, headTop, headW, headH, r, g, b, 1.0f);
        // 头发（hairType 0短/1中/2长/3光/4辫/5丸子）
        int ht = Avatar.hairType(a.hairStyle);
        if (ht == 0) {
            this.addRect2D(buf, cx - headW / 2.0f - 3.0f, headTop - 12.0f, headW + 6.0f, 40.0f, hr, hg, hb, 1.0f);
        } else if (ht == 1) {
            this.addRect2D(buf, cx - headW / 2.0f - 4.0f, headTop - 14.0f, headW + 8.0f, 64.0f, hr, hg, hb, 1.0f);
        } else if (ht == 2) {
            this.addRect2D(buf, cx - headW / 2.0f - 5.0f, headTop - 14.0f, headW + 10.0f, 64.0f, hr, hg, hb, 1.0f);
            this.addRect2D(buf, cx - headW / 2.0f - 5.0f, headTop, 22.0f, headH + 50.0f, hr, hg, hb, 1.0f);
            this.addRect2D(buf, cx + headW / 2.0f - 17.0f, headTop, 22.0f, headH + 50.0f, hr, hg, hb, 1.0f);
        } else if (ht == 4) { // 辫：顶盖 + 右侧垂辫
            this.addRect2D(buf, cx - headW / 2.0f - 4.0f, headTop - 14.0f, headW + 8.0f, 60.0f, hr, hg, hb, 1.0f);
            this.addRect2D(buf, cx + headW / 2.0f - 8.0f, headTop + 10.0f, 14.0f, headH + 40.0f, hr, hg, hb, 1.0f);
        } else if (ht == 5) { // 丸子：顶盖 + 顶结
            this.addRect2D(buf, cx - headW / 2.0f - 4.0f, headTop - 8.0f, headW + 8.0f, 40.0f, hr, hg, hb, 1.0f);
            this.addRect2D(buf, cx - 16.0f, headTop - 40.0f, 32.0f, 32.0f, hr, hg, hb, 1.0f);
        } // ht == 3：光头不画
        // 眉（browStyle 0平/1浓/2挑）
        float browY = headTop + 66.0f;
        float browH = a.browStyle == 1 ? 9.0f : 6.0f;
        this.addRect2D(buf, cx - 44.0f, browY, 30.0f, browH, 0.12f, 0.10f, 0.08f, 0.9f);
        this.addRect2D(buf, cx + 14.0f, browY, 30.0f, browH, 0.12f, 0.10f, 0.08f, 0.9f);
        if (a.browStyle == 2) { // 挑：内端上扬（两层细条近似）
            this.addRect2D(buf, cx - 16.0f, browY - 4.0f, 12.0f, 4.0f, 0.12f, 0.10f, 0.08f, 0.9f);
            this.addRect2D(buf, cx + 4.0f, browY - 4.0f, 12.0f, 4.0f, 0.12f, 0.10f, 0.08f, 0.9f);
        }
        // 眼（eyeShape 0圆/1细长/2上扬）
        float eyeY = headTop + 78.0f;
        if (a.eyeShape == 1) { // 细长
            this.addRect2D(buf, cx - 42.0f, eyeY + 5.0f, 28.0f, 10.0f, er, eg, eb, 1.0f);
            this.addRect2D(buf, cx + 16.0f, eyeY + 5.0f, 28.0f, 10.0f, er, eg, eb, 1.0f);
        } else {
            this.addRect2D(buf, cx - 42.0f, eyeY, 26.0f, 18.0f, er, eg, eb, 1.0f);
            this.addRect2D(buf, cx + 16.0f, eyeY, 26.0f, 18.0f, er, eg, eb, 1.0f);
            if (a.eyeShape == 2) { // 上扬：外上角加小条
                this.addRect2D(buf, cx - 18.0f, eyeY - 4.0f, 10.0f, 5.0f, er, eg, eb, 1.0f);
                this.addRect2D(buf, cx + 30.0f, eyeY - 4.0f, 10.0f, 5.0f, er, eg, eb, 1.0f);
            }
        }
        // 鼻（noseStyle 0无/1小/2挺）
        if (a.noseStyle == 1) {
            this.addRect2D(buf, cx - 6.0f, headTop + 100.0f, 12.0f, 16.0f, r * 0.85f, g * 0.85f, b * 0.85f, 0.9f);
        } else if (a.noseStyle == 2) {
            this.addRect2D(buf, cx - 4.0f, headTop + 98.0f, 8.0f, 22.0f, r * 0.80f, g * 0.80f, b * 0.80f, 0.9f);
        }
        // 嘴（mouthStyle 0平/1笑/2抿）
        float mouthY = headTop + 138.0f;
        if (a.mouthStyle == 1) { // 笑：略宽 + 上扬两端
            this.addRect2D(buf, cx - 26.0f, mouthY, 52.0f, 8.0f, 0.30f, 0.18f, 0.16f, 0.9f);
            this.addRect2D(buf, cx - 26.0f, mouthY - 6.0f, 10.0f, 6.0f, 0.30f, 0.18f, 0.16f, 0.9f);
            this.addRect2D(buf, cx + 16.0f, mouthY - 6.0f, 10.0f, 6.0f, 0.30f, 0.18f, 0.16f, 0.9f);
        } else if (a.mouthStyle == 2) { // 抿：细窄
            this.addRect2D(buf, cx - 18.0f, mouthY + 1.0f, 36.0f, 5.0f, 0.30f, 0.18f, 0.16f, 0.9f);
        } else {
            this.addRect2D(buf, cx - 22.0f, mouthY, 44.0f, 8.0f, 0.30f, 0.18f, 0.16f, 0.9f);
        }
        // 胡须（beardType 1短髭/2络腮/3山羊）
        if (Avatar.hasBeard(a.beard)) {
            int bt = Avatar.beardType(a.beard);
            float[] beardCol = new float[]{r * 0.45f, g * 0.40f, b * 0.36f};
            if (bt == 1) { // 短髭
                this.addRect2D(buf, cx - 20.0f, headTop + 124.0f, 40.0f, 8.0f, beardCol[0], beardCol[1], beardCol[2], 0.95f);
            } else if (bt == 2) { // 络腮：下颊两侧 + 下巴
                this.addRect2D(buf, cx - headW / 2.0f + 4.0f, headTop + 110.0f, 18.0f, headH - 70.0f, beardCol[0], beardCol[1], beardCol[2], 0.95f);
                this.addRect2D(buf, cx + headW / 2.0f - 22.0f, headTop + 110.0f, 18.0f, headH - 70.0f, beardCol[0], beardCol[1], beardCol[2], 0.95f);
                this.addRect2D(buf, cx - 18.0f, headTop + headH - 30.0f, 36.0f, 30.0f, beardCol[0], beardCol[1], beardCol[2], 0.95f);
            } else { // 山羊胡
                this.addRect2D(buf, cx - 8.0f, headTop + headH - 16.0f, 16.0f, 24.0f, beardCol[0], beardCol[1], beardCol[2], 0.95f);
            }
        }
        // 纹身 / 疤痕（tattooType 1左脸疤/2右臂纹/3额痕）
        if (Avatar.hasTattoo(a.tattoo)) {
            int tt = Avatar.tattooType(a.tattoo);
            float tcx = 0.20f, tcy = 0.16f, tcz = 0.14f;
            if (tt == 1) { // 左脸疤
                this.addRect2D(buf, cx - headW / 2.0f + 6.0f, headTop + 90.0f, 5.0f, 34.0f, tcx, tcy, tcz, 0.9f);
            } else if (tt == 3) { // 额痕
                this.addRect2D(buf, cx - 16.0f, headTop + 14.0f, 32.0f, 5.0f, tcx, tcy, tcz, 0.9f);
            } else { // 右臂纹（头像只见肩，落于右肩 accent 区）
                this.addRect2D(buf, cx + shoulderW / 2.0f - 26.0f, headTop + headH + 6.0f, 18.0f, 30.0f, tcx, tcy, tcz, 0.9f);
            }
        }
        // 名字
        String nm = a.name == null ? "" : a.name;
        if (CjkFont.available()) {
            float nw = CjkFont.width(nm, 1.6f);
            HudText.cjk(buf, cx - nw / 2.0f, py + ph - 40.0f, 1.6f, 0.95f, 0.97f, 1.0f, 1.0f, nm, this.W, this.H);
        } else {
            HudText.ascii(buf, cx - 90.0f, py + ph - 40.0f, 1.4f, 0.95f, 0.97f, 1.0f, 1.0f, nm.isEmpty() ? "CHAR" : nm, this.W, this.H);
        }
    }

    /**
     * 八向方位名（世界坐标，**不随视角旋转**）：
     * 约定与罗盘一致 —— {@code -Z} 为北（yaw=0 时面朝 -Z，罗盘 "N" 落在屏幕正中）。
     * 纯函数、零 RNG；只给 HUD 用，不进仿真。
     */
    private static String compass8(float dx, float dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        if (a < 0.0) a += 360.0;
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.floor((a + 22.5) / 45.0) % 8];
    }

    /**
     * HUD 分节顶点计数：把"自上一次 mark 以来发射了多少顶点"记进本帧的 {@link #hudMarkLog}。
     *
     * <p><b>存在理由</b>：HUD 每帧重建整条顶点缓冲（本项目实测 <b>9.7 万顶点 ≈ 3.5MB/帧</b>），
     * 是"每帧固定开销"里最大的一块 —— 但它由 20+ 个分节拼成，**不数就不知道该砍谁**。
     * 靠读代码猜"哪个循环最贵"在本项目已被证伪过一次（热键栏+红心只有几百 quad）。
     * 关掉开关时本方法与一次断言同价。</p>
     */
    private void hudMark(String name) {
        if (!HUD_DIAG) return;
        int now = this.hudBuf.position() / HUD_VERT_FLOATS;
        this.hudMarkLog.append(name).append('=').append(now - this.hudMarkAt).append(' ');
        this.hudMarkAt = now;
    }

    /** 本帧 HUD 分节记录（仅诊断用；见 {@link #hudMark}）。 */
    private final StringBuilder hudMarkLog = new StringBuilder();
    private int hudMarkAt = 0;
    /** 上一帧 HUD 构建 / 上传耗时（毫秒；仅诊断）。顶点数不是成本，时间才是 —— 见 hudMark 的说明。 */
    private float hudMsBuild = 0f, hudMsUpload = 0f;
    /** 区间累计（用于报平均值 —— 单帧样本在冷启动/GC 时噪声极大）。 */
    private float hudMsBuildSum = 0f;
    private int hudMsBuildN = 0;
    private int hudDiagFrame = -999;

    private void drawHud() {
        long hudT0 = java.lang.System.nanoTime();
        float f;
        float f2;
        float f32;
        float f4;
        int n;
        String string;
        float f5;
        this.hudBuf.clear();
        Player player = this.sim.player;
        if (this.hurtFlash > 0.01f) {
            float f6 = Math.min(0.5f, this.hurtFlash * 0.6f);
            int n2 = 30;
            this.addRect2D(this.hudBuf, 0.0f, 0.0f, this.W, n2, 0.7f, 0.02f, 0.02f, f6);
            this.addRect2D(this.hudBuf, 0.0f, this.H - n2, this.W, n2, 0.7f, 0.02f, 0.02f, f6);
            this.addRect2D(this.hudBuf, 0.0f, 0.0f, n2, this.H, 0.7f, 0.02f, 0.02f, f6);
            this.addRect2D(this.hudBuf, this.W - n2, 0.0f, n2, this.H, 0.7f, 0.02f, 0.02f, f6);
        }
        if (this.skillFlash > 0.01f) {
            float fa = Math.min(0.32f, this.skillFlash * 0.5f);
            this.addRect2D(this.hudBuf, 0.0f, 0.0f, this.W, this.H, this.skillFlashR, this.skillFlashG, this.skillFlashB, fa);
        }
        this.hudMark("A_overlay");
        // ============ 画面细腻度：水下色调 / 低血红脉冲 / 命中标记 / 漂浮伤害数字 / 成就横幅 ============
        // 全走既有 HUD 2D 通道（addRect2D + HudText.ascii），不新增 GLSL；只读玩家血量/镜头位置与本地计时 → 零仿真影响。
        if (this.sim.world.getBlock((int) Math.floor((double) this.camPos.x),
                (int) Math.floor((double) this.camPos.y), (int) Math.floor((double) this.camPos.z)) == Blocks.WATER.index) {
            this.addRect2D(this.hudBuf, 0.0f, 0.0f, this.W, this.H, 0.12f, 0.38f, 0.58f, 0.32f);   // 镜头入水：蓝青压暗
        }
        if (this.sim.player.maxHp > 0) {
            float hpRatio = Math.max(0.0f, Math.min(1.0f, (float) this.sim.player.hp / (float) this.sim.player.maxHp));
            if (hpRatio < 0.30f && !this.sim.player.godMode) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(this.time * 6.0f);        // 心跳脉动
                float red = (0.30f - hpRatio) / 0.30f;                                 // 血越少越红
                float ea = Math.min(0.42f, (0.10f + 0.32f * red) * (0.55f + 0.45f * pulse));
                float band = this.H * 0.10f, vband = this.W * 0.06f;
                this.addRect2D(this.hudBuf, 0.0f, 0.0f, this.W, band, 0.85f, 0.06f, 0.08f, ea);
                this.addRect2D(this.hudBuf, 0.0f, this.H - band, this.W, band, 0.85f, 0.06f, 0.08f, ea);
                this.addRect2D(this.hudBuf, 0.0f, 0.0f, vband, this.H, 0.85f, 0.06f, 0.08f, ea);
                this.addRect2D(this.hudBuf, this.W - vband, 0.0f, vband, this.H, 0.85f, 0.06f, 0.08f, ea);
            }
        }
        this.hudMark("B_hitmark");
        if (this.hitMarkTimer > 0.0f) {                                                // 命中标记（准星四角 X）
            float ha = Math.min(1.0f, this.hitMarkTimer / 0.22f);
            float cx = this.W * 0.5f, cy = this.H * 0.5f, L = 12.0f, T = 2.6f, gap = 6.0f;
            this.addRect2D(this.hudBuf, cx - gap - L, cy - gap - T, L, T, 1.0f, 0.95f, 0.85f, ha);
            this.addRect2D(this.hudBuf, cx + gap,     cy - gap - T, L, T, 1.0f, 0.95f, 0.85f, ha);
            this.addRect2D(this.hudBuf, cx - gap - L, cy + gap,     L, T, 1.0f, 0.95f, 0.85f, ha);
            this.addRect2D(this.hudBuf, cx + gap,     cy + gap,     L, T, 1.0f, 0.95f, 0.85f, ha);
        }
        this.hudMark("C_dmg");
        for (int i = 0; i < 8; i++) {                                                  // 漂浮伤害数字（上飘渐隐）
            if (this.dmgT[i] <= 0.0f) continue;
            float a = Math.min(1.0f, this.dmgT[i] / 0.85f);
            float rise = (1.0f - this.dmgT[i] / 0.85f) * 36.0f;
            HudText.ascii(this.hudBuf, this.dmgX[i] + (this.dmgRnd[i] - 0.5f) * 26.0f, this.dmgY[i] - rise, 1.05f,
                    1.0f, 0.42f + 0.42f * a, 0.20f + 0.28f * a, a, String.valueOf(this.dmgV[i]), this.W, this.H);
        }
        this.hudMark("D_banner");
        if (this.achBannerTimer > 0.0f && this.achBanner != null) {                     // 成就横幅
            float ba = Math.min(1.0f, this.achBannerTimer / 0.6f);
            String t = "ACHIEVEMENT  " + this.achBanner;
            HudText.ascii(this.hudBuf, this.W * 0.5f - t.length() * 4.05f, this.H * 0.16f, 1.15f,
                    1.0f, 0.88f, 0.35f, ba, t, this.W, this.H);
        }
        World world = this.sim.world;
        float f7 = 300.0f;
        float f8 = 14.0f;
        this.hudMark("E_compass");
        float f9 = (float)this.W / 2.0f - f7 / 2.0f;
        float f10 = 6.0f;
        this.addRect2D(this.hudBuf, f9, f10, f7, f8, 0.04f, 0.05f, 0.09f, 0.45f);
        float f11 = f7 / 2.0f;
        float f12 = 90.0f;
        this.addRect2D(this.hudBuf, (float)this.W / 2.0f - 1.0f, f10 + f8, 2.0f, 3.0f, 1.0f, 0.95f, 0.8f, 0.9f);
        String[] stringArray = new String[]{"N", "E", "S", "W"};
        for (int i = 0; i < 4; ++i) {
            float f13 = Game.wrapDeg((float)i * 90.0f - this.yaw);
            if (Math.abs(f13) > f12) continue;
            f5 = (float)this.W / 2.0f + f13 / f12 * f11;
            HudText.ascii(this.hudBuf, f5 - 3.0f, f10 + 2.0f, 0.7f, 0.72f, 0.82f, 0.95f, 0.9f, stringArray[i], this.W, this.H);
        }
        this.hudMark("F_shrineMark");
        for (World.Shrine shrine : world.shrines) {
            float f14;
            if (shrine.claimed || Math.abs(f14 = Game.wrapDeg((f5 = (float)Math.toDegrees(Math.atan2(shrine.x - this.sim.player.x, -(shrine.z - this.sim.player.z)))) - this.yaw)) > f12) continue;
            float f15 = (float)this.W / 2.0f + f14 / f12 * f11;
            this.addRect2D(this.hudBuf, f15 - 2.0f, f10 + 3.0f, 4.0f, 4.0f, 1.0f, 0.8f, 0.3f, 0.95f);
            if (shrine.ability == null || shrine.ability.isEmpty()) continue;
            HudText.ascii(this.hudBuf, f15 - 3.0f, f10 + 9.0f, 0.65f, 1.0f, 0.85f, 0.4f, 0.9f, shrine.ability.substring(0, 1), this.W, this.H);
        }
        // D 批：地标（繁荣灯塔）入罗盘。有灯塔时显示黄色“塔”标记；否则退回世界中心绿点（导航参考）。
        this.hudMark("G_beacon");
        if (!world.beacons.isEmpty()) {
            for (World.Beacon beacon : world.beacons) {
                float f16 = Game.wrapDeg((float)Math.toDegrees(Math.atan2(beacon.x - this.sim.player.x, -(beacon.z - this.sim.player.z))) - this.yaw);
                if (Math.abs(f16) > f12) continue;
                float f17 = (float)this.W / 2.0f + f16 / f12 * f11;
                this.addRect2D(this.hudBuf, f17 - 3.0f, f10 + 2.0f, 6.0f, 6.0f, 1.0f, 0.85f, 0.2f, 0.95f);
                HudText.ascii(this.hudBuf, f17 - 3.0f, f10 + 9.0f, 0.65f, 1.0f, 0.9f, 0.3f, 0.9f, "塔", this.W, this.H);
            }
        } else {
            float f16 = Game.wrapDeg((float)Math.toDegrees(Math.atan2((float)world.SX / 2.0f - this.sim.player.x, -((float)world.SZ / 2.0f - this.sim.player.z))) - this.yaw);
            if (Math.abs(f16) <= f12) {
                float f17 = (float)this.W / 2.0f + f16 / f12 * f11;
                this.addRect2D(this.hudBuf, f17 - 2.0f, f10 + 3.0f, 4.0f, 4.0f, 0.4f, 0.95f, 0.5f, 0.95f);
                // 给孤立色点一个名字：这是「村心」（世界中心 = 聚落所在）。
                HudText.ascii(this.hudBuf, f17 - 3.0f, f10 + 9.0f, 0.65f, 0.5f, 1.0f, 0.6f, 0.9f, "V", this.W, this.H);
            }
        }
        this.hudMark("H_whereami");
        // ── 「我在哪」：罗盘只有刻度与色点，新手读不出自己在哪。这里补一行可读定位：
        //    坐标 + 到村心的距离 + 八向方位（世界方位，不随视角旋转）。
        {
            float dxc = (float) world.SX / 2.0f - this.sim.player.x;
            float dzc = (float) world.SZ / 2.0f - this.sim.player.z;
            String loco = "X " + Math.round(this.sim.player.x) + "  Z " + Math.round(this.sim.player.z)
                    + "   VILLAGE " + Math.round((float) Math.hypot(dxc, dzc)) + "m "
                    + compass8(dxc, dzc);
            HudText.ascii(this.hudBuf, (float) this.W / 2.0f - f11 + 4.0f, f10 + 15.0f, 0.62f,
                    0.62f, 0.72f, 0.85f, 0.88f, loco, this.W, this.H);
        }
        float f18 = (float)this.W / 2.0f;
        f7 = (float)this.H / 2.0f;
        this.addRect2D(this.hudBuf, f18 - 1.0f, f7 - 1.0f, 2.0f, 2.0f, 1.0f, 1.0f, 1.0f, 0.9f);
        this.addRect2D(this.hudBuf, f18 - 9.0f, f7 - 0.5f, 5.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.8f);
        this.addRect2D(this.hudBuf, f18 + 4.0f, f7 - 0.5f, 5.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.8f);
        this.addRect2D(this.hudBuf, f18 - 0.5f, f7 - 9.0f, 1.0f, 5.0f, 1.0f, 1.0f, 1.0f, 0.8f);
        this.addRect2D(this.hudBuf, f18 - 0.5f, f7 + 4.0f, 1.0f, 5.0f, 1.0f, 1.0f, 1.0f, 0.8f);
        if (this.blockFrontTimer > 0.9f && CjkFont.available()) {
            String string2 = "\u524d\u65b9\u6321\u8def \u00b7 \u5de6\u952e\u6316\u5f00 / \u53f3\u952e\u57ab\u811a\u8df3\u51fa\uff081-4 \u9009\u65b9\u5757\uff09\u00b7 F7 \u56de\u51fa\u751f\u70b9";
            f9 = CjkFont.width(string2, 0.8f);
            f10 = 0.72f + 0.25f * (float)Math.sin((double)this.time * 4.0);
            HudText.cjk(this.hudBuf, ((float)this.W - f9) / 2.0f, f7 + 26.0f, 0.8f, 1.0f, 0.86f, 0.45f, f10, string2, this.W, this.H);
        }
        this.hudMark("I_leftTop");
        // P3 布局引擎：左上区块改竖向栈（间距 8 → y = 14 / 88，与原手写逐位一致）。
        // 收益：今后改状态块高度，村志面板自动跟着下移，不必手算；小屏溢出时整块一起被钳制。
        UiLayout.Stack hudLeft = new UiLayout.Stack(UiLayout.TL, 14f, 14f, 8f, this.W, this.H);
        UiLayout.Rect pStatus = hudLeft.next("hud-status", 244.0f, 66.0f);
        this.panel(this.hudBuf, pStatus.x, pStatus.y, pStatus.w, pStatus.h, 0.06f, 0.08f, 0.12f, 0.55f);
        f8 = (float)Math.max(0, player.hp) / (float)player.maxHp;
        this.addRect2D(this.hudBuf, pStatus.x + 8f, pStatus.y + 8f, pStatus.w - 16f, 16.0f, 0.1f, 0.05f, 0.05f, 0.9f);
        this.addRect2D(this.hudBuf, pStatus.x + 9f, pStatus.y + 9f, (pStatus.w - 18f) * f8, 14.0f, 0.85f, 0.12f, 0.12f, 1.0f);
        this.addRect2D(this.hudBuf, pStatus.x + 9f, pStatus.y + 9f, (pStatus.w - 18f) * f8, 4.0f, 1.0f, 0.45f, 0.45f, 0.55f);
        f9 = (float)Math.max(0, player.stamina) / (float)player.maxStamina;
        this.addRect2D(this.hudBuf, pStatus.x + 8f, pStatus.y + 30f, pStatus.w - 16f, 12.0f, 0.1f, 0.09f, 0.02f, 0.9f);
        this.addRect2D(this.hudBuf, pStatus.x + 9f, pStatus.y + 31f, (pStatus.w - 18f) * f9, 10.0f, 0.95f, 0.78f, 0.18f, 1.0f);
        this.addRect2D(this.hudBuf, pStatus.x + 9f, pStatus.y + 31f, (pStatus.w - 18f) * f9, 3.0f, 1.0f, 0.95f, 0.5f, 0.5f);
        HudText.ascii(this.hudBuf, pStatus.x + 8f, pStatus.y + 48f, 1.3f, 0.95f, 0.97f, 1.0f, 1.0f, "LV " + player.level + "  HP " + player.hp + "/" + player.maxHp, this.W, this.H);
        UiLayout.Rect pChron = hudLeft.next("hud-chronicle", 520.0f, 176.0f);
        this.panel(this.hudBuf, pChron.x, pChron.y, pChron.w, pChron.h, 0.06f, 0.08f, 0.12f, 0.5f);
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 8f, 0.95f, 0.82f, 0.94f, 1.0f, 0.95f, this.sim.world.chronicle.asciiHeader(), this.W, this.H);
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 24f, 0.9f, 0.78f, 0.9f, 0.98f, 0.82f, this.sim.world.chronicle.asciiLast(), this.W, this.H);
        Civilization civilization = this.sim.world.civ;
        f11 = 0.0f;
        for (float f19 : civilization.relations.values()) {
            f11 += f19;
        }
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 40f, 0.8f, 0.75f, 0.9f, 0.82f, 0.72f, String.format(Locale.US, "CIV  RES %d  TECH %d  TEMPLE %d  ROADS %d  REL %.2f  REGIME %d", (int)civilization.research, civilization.unlocked.size(), civilization.temples, civilization.roads, Float.valueOf(f11 /= (float)Math.max(1, civilization.relations.size())), civilization.regimes), this.W, this.H);
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 56f, 0.8f, 0.8f, 0.92f, 0.78f, 0.9f, IndividualSystem.asciiSummary(this.sim.world.individual), this.W, this.H);
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 72f, 0.8f, 0.92f, 0.86f, 0.6f, 0.62f, this.sim.world.polity.asciiSummary(), this.W, this.H);
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 88f, 0.8f, 0.9f, 0.7f, 0.62f, 0.7f, this.sim.world.calamity.asciiSummary(), this.W, this.H);
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 104f, 1.0f, 1.0f, 0.86f, 0.42f, 1.0f, this.sim.world.trials.objective(this.sim.player.abilities), this.W, this.H);
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 120f, 0.8f, 0.72f, 0.95f, 0.92f, 0.95f, this.sim.world.trials.asciiSummary(), this.W, this.H);
        // P1 内容层接线：任务目标行。中文正文走 CjkFont（与村志同一套），无 CJK 时退回 ASCII id。
        if (!this.menu.isOpen() && this.questEngine != null) {
            String qa = this.questEngine.hudLineAscii(this.sim.world);
            if (!qa.isEmpty()) {
                if (CjkFont.available()) {
                    HudText.cjk(this.hudBuf, pChron.x + 10f, pChron.y + 168f, 1.0f, 1.0f, 0.88f, 0.45f, 0.95f,
                            this.questEngine.hudLineCjk(this.sim.world), this.W, this.H);
                } else {
                    HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 168f, 0.9f, 1.0f, 0.88f, 0.45f, 0.95f, qa, this.W, this.H);
                }
            }
        }
        // 预设选择器：只在预设**真的改变了世界**时才占 HUD（默认预设不加噪）。
        if (!this.menu.isOpen() && !this.presetHudLine.isEmpty()) {
            HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 184f, 1.0f, 0.86f, 0.62f, 0.95f, 1.0f, this.presetHudLine, this.W, this.H);
        }
        // QA 2026-09-16：封印提示（塞尔达式“以能力破印”）+ 血渍位置（DaS 尸体回收）
        String sealLine = this.sim.world.trials.sealHint(this.sim.player, this.sim.world.tick);
        if (!sealLine.isEmpty()) {
            HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 136f, 0.85f, 1.0f, 0.82f, 0.32f, 1.0f, sealLine, this.W, this.H);
        }
        if (this.sim.player.hasLost) {
            float bdx = this.sim.player.x - this.sim.player.lostX;
            float bdz = this.sim.player.z - this.sim.player.lostZ;
            HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 152f, 0.85f, 1.0f, 0.48f, 0.34f, 1.0f,
                    "BLOODSTAIN  SOULS " + this.sim.player.lostSouls + "   "
                            + Math.round((float) Math.sqrt(bdx * bdx + bdz * bdz)) + "m AWAY",
                    this.W, this.H);
        }
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 136f, 0.8f, 0.86f, 0.92f, 0.66f, 0.9f, this.sim.world.matter.asciiSummary(), this.W, this.H);
        HudText.ascii(this.hudBuf, pChron.x + 10f, pChron.y + 152f, 0.8f, 0.66f, 0.86f, 0.94f, 0.9f, this.sim.world.matter.deepSummary(), this.W, this.H);
        this.hudMark("J_soulBars");
        // P3 布局引擎：左下 SOUL 条（BL 锚 = 底边距 78；原式 y = H-104 且 h = 26，等价）
        UiLayout.Rect pSoul = UiLayout.fit("hud-soul", UiLayout.BL, 14f, 78f, 220f, 26f, this.W, this.H);
        this.panel(this.hudBuf, pSoul.x, pSoul.y, pSoul.w, pSoul.h, 0.06f, 0.08f, 0.12f, 0.55f);
        this.addDiamond2D(this.hudBuf, pSoul.x + 16f, pSoul.y + 13f, 7.0f, 0.55f, 0.85f, 1.0f, 0.95f);
        HudText.ascii(this.hudBuf, pSoul.x + 30f, pSoul.y + 6f, 1.3f, 0.7f, 0.9f, 1.0f, 1.0f, "SOUL " + player.souls, this.W, this.H);
        HudText.ascii(this.hudBuf, pSoul.x + 136f, pSoul.y + 6f, 1.0f, 0.8f, 0.85f, 0.9f, 0.85f, "PROSP " + this.sim.world.prosperity, this.W, this.H);
        float f20 = DayCycle.phase(this.sim.world.tick);
        boolean bl = DayCycle.isNight(this.sim.world.tick);
        // P3 布局引擎：顶部时间条（TC 锚 = 顶边距 12；原式 x=(W-306)/2 即水平居中）
        UiLayout.Rect pClock = UiLayout.fit("hud-clock", UiLayout.TC, 0f, 12f, 306f, 42f, this.W, this.H);
        this.panel(this.hudBuf, pClock.x, pClock.y, pClock.w, pClock.h, 0.06f, 0.08f, 0.12f, 0.55f);
        this.addRect2D(this.hudBuf, pClock.x + 12f, pClock.y + 9f, pClock.w - 24f, 8.0f, 0.1f, 0.11f, 0.16f, 0.9f);
        this.addRect2D(this.hudBuf, pClock.x + 12f, pClock.y + 9f, (pClock.w - 24f) * f20, 8.0f, bl ? 0.22f : 0.45f, bl ? 0.26f : 0.4f, bl ? 0.44f : 0.2f, 0.85f);
        this.addDiamond2D(this.hudBuf, pClock.x + 12f + (pClock.w - 24f) * f20, pClock.y + 13f, 6.0f, bl ? 0.75f : 1.0f, bl ? 0.82f : 0.86f, 1.0f, 1.0f);
        HudText.ascii(this.hudBuf, pClock.x + 12f, pClock.y + 25f, 1.15f, bl ? 0.74f : 1.0f, bl ? 0.82f : 0.93f, 1.0f, 0.95f, "DAY " + DayCycle.dayNumber(this.sim.world.tick) + "  " + DayCycle.clock(f20) + "  " + DayCycle.label(f20) + "  " + (this.sim.world.raining ? "RAIN" : "CLEAR"), this.W, this.H);
        // P3 布局引擎：右上技能面板（TR 锚 = 右边距 14；原式 x = W-220 且 w = 206）
        UiLayout.Rect pSkills = UiLayout.fit("hud-skills", UiLayout.TR, 14f, 14f, 206f, 104f, this.W, this.H);
        this.panel(this.hudBuf, pSkills.x, pSkills.y, pSkills.w, pSkills.h, 0.06f, 0.08f, 0.12f, 0.55f);
        this.drawSkill(this.hudBuf, pSkills.x + 12f, pSkills.y + 12f, "ART " + Weapons.artName(player.currentArt), player.level >= 2, player.level >= 2 && player.artCd <= 0.0f, "Lv2");
        this.drawSkill(this.hudBuf, pSkills.x + 12f, pSkills.y + 38f, "ROLL", player.level >= 3, player.level >= 3 && player.rollCd <= 0.0f, "Lv3");
        if (!this.learnedSkills.isEmpty()) {
            String sid = this.learnedSkills.get(this.skillIdx % this.learnedSkills.size());
            core.content.SkillDef sd = this.contentSys.skill(sid);
            String nm = (sd == null) ? sid : sd.name;
            boolean ready = player.skillCd <= 0.0f && (sd == null || player.stamina >= sd.cost);
            this.drawSkill(this.hudBuf, pSkills.x + 12f, pSkills.y + 64f, "SKILL " + nm.toUpperCase(), true, ready, "P");
        }
        HudText.ascii(this.hudBuf, pSkills.x + 12f, pSkills.y + 56f, 0.82f, 0.78f, 0.86f, 0.95f, 0.9f, "G: SWITCH ART  x" + player.arts.size(), this.W, this.H);
        HudText.ascii(this.hudBuf, pSkills.x + 12f, pSkills.y + 72f, 0.82f,
                player.flask > 0 ? 0.95f : 0.45f, player.flask > 0 ? 0.78f : 0.42f,
                player.flask > 0 ? 0.45f : 0.40f, 0.95f,
                "U: FLASK  " + player.flask + "/" + Player.FLASK_MAX, this.W, this.H);
        if (this.locked != null && this.sim.world.beasts.contains(this.locked)) {
            string = Beast.typeName(this.locked.type);
            // P3 布局引擎：锁定目标血条（TC 锚 = 顶边距 66；原式 x = W/2 - 120）
            UiLayout.Rect pLock = UiLayout.fit("hud-lockon", UiLayout.TC, 0f, 66f, 240f, 44f, this.W, this.H);
            this.panel(this.hudBuf, pLock.x, pLock.y, pLock.w, pLock.h, 0.05f, 0.06f, 0.1f, 0.6f);
            HudText.ascii(this.hudBuf, pLock.x + 12f, pLock.y + 6f, 1.3f, 1.0f, 0.85f, 0.2f, 1.0f, string, this.W, this.H);
            f4 = (float)Math.max(0, this.locked.hp) / (float)this.locked.maxHp;
            this.addRect2D(this.hudBuf, pLock.x + 12f, pLock.y + 24f, pLock.w - 24f, 10.0f, 0.08f, 0.02f, 0.02f, 0.95f);
            this.addRect2D(this.hudBuf, pLock.x + 13f, pLock.y + 25f, (pLock.w - 26f) * f4, 8.0f, 0.9f, 0.2f, 0.2f, 1.0f);
            this.addRect2D(this.hudBuf, pLock.x + pLock.w * 0.5f - 5.0f, pLock.y + 44f, 10.0f, 4.0f, 1.0f, 0.85f, 0.2f, 0.9f);
        }
        // P0-4：守卫血条 —— 常显在屏幕上方居中。守卫体型大、头顶血条会被地形/自身挡住，
        // 而且它要打 40+ 击，血条必须随时可读（参照 DS/ER 的 Boss 血条位置）。
        Beast bossNow = null;
        for (Beast bb : this.sim.world.beasts) if (bb.type == Beast.TYPE_BOSS) { bossNow = bb; break; }
        if (bossNow != null) {
            float fBoss = (float)Math.max(0, bossNow.hp) / (float)bossNow.maxHp;
            // P3 布局引擎：Boss 血条（TC 锚 = 顶边距 88，含 8px 外边距；原式面板 x = (W-430)/2 - 8, w = 446）
            UiLayout.Rect pBoss = UiLayout.fit("hud-boss", UiLayout.TC, 0f, 88f, 446f, 50f, this.W, this.H);
            this.panel(this.hudBuf, pBoss.x, pBoss.y, pBoss.w, pBoss.h, 0.05f, 0.05f, 0.09f, 0.62f);
            HudText.ascii(this.hudBuf, pBoss.x + 8f, pBoss.y + 8f, 1.35f, 1.0f, 0.82f, 0.30f, 1.0f,
                    "THE SUMMIT WARDEN   (" + bossNow.phaseName() + ")", this.W, this.H);
            this.addRect2D(this.hudBuf, pBoss.x + 8f, pBoss.y + 30f, pBoss.w - 16f, 12.0f, 0.10f, 0.03f, 0.03f, 0.95f);
            this.addRect2D(this.hudBuf, pBoss.x + 9f, pBoss.y + 31f, (pBoss.w - 18f) * fBoss, 10.0f,
                    bossNow.enraged() ? 1.0f : 0.86f, bossNow.enraged() ? 0.42f : 0.16f, 0.18f, 1.0f);
        }
        this.hudMark("K_rayTarget");
        // 功能方块提示（第三批，2026-09-23）：瞄准门/箱/熔炉/工作台时给出「这是什么 + 当前状态 + 右键可用」。
        // 纯展示（HudText.ascii，非面板调用）→ 不触碰 UILAYOUT/MIGRATION；只在瞄准功能方块时才占 HUD。
        if (this.rayTarget != null) {
            core.world.World fw = this.sim.world;
            int fx = this.rayTarget[0], fy = this.rayTarget[1], fz = this.rayTarget[2];
            int fb = fw.getBlock(fx, fy, fz);
            long fcell = core.world.World.cellKey(fx, fy, fz);
            String useLine = null;
            if (fb == Blocks.DOOR.index) {
                useLine = "DOOR " + (fw.getMeta(fx, fy, fz) == 1 ? "OPEN" : "CLOSED") + "   [R CLICK = TOGGLE]";
            } else if (fb == Blocks.CHEST.index) {
                int tot = 0;
                java.util.LinkedHashMap<String, Integer> cs = fw.chestOf(fcell);
                if (cs != null) for (Integer v : cs.values()) tot += v.intValue();
                useLine = "CHEST " + tot + " items   [R CLICK = STORE / TAKE]";
            } else if (fb == Blocks.FURNACE.index) {
                int bars = 0;
                java.util.LinkedHashMap<String, Integer> fo = fw.furnaceStoreOf(fcell);
                if (fo != null) for (Integer v : fo.values()) bars += v.intValue();
                int prog = fw.getMeta(fx, fy, fz);
                String st = bars > 0 ? ("READY +" + bars)
                        : (prog > 0 ? ("SMELTING " + (prog * 100 / core.systems.FurnaceSystem.SMELT_TICKS) + "%")
                        : "IDLE (needs ore + fuel adjacent)");
                useLine = "FURNACE " + st + "   [R CLICK = TAKE]";
            } else if (fb == Blocks.WORKBENCH.index) {
                useLine = "WORKBENCH   [R CLICK = CRAFT: door / chest / workbench / furnace / repeater / comparator / plate / campfire / farmland / dispenser / piston]";
            } else if (fb == Blocks.LEVER.index) {
                useLine = "LEVER " + (fw.getMeta(fx, fy, fz) == 1 ? "ON" : "OFF") + "   [R CLICK = TOGGLE]";
            } else if (fb == Blocks.BUTTON.index) {
                useLine = "BUTTON   [R CLICK = PULSE]";
            } else if (fb == Blocks.WIRE.index) {
                core.systems.System ws = fw.registry.get("wire");
                String net = "";
                boolean pw = false;
                if (ws instanceof core.systems.WireSystem) {
                    core.systems.WireSystem wsys = (core.systems.WireSystem) ws;
                    pw = wsys.strengthAt(fx, fy, fz) > 0;
                    net = "  signal=" + wsys.strengthAt(fx, fy, fz) + "/" + core.systems.WireSystem.MAX_SIGNAL
                            + "  net=" + wsys.poweredCount() + "/" + wsys.nodeCount();
                }
                useLine = "WIRE " + (pw ? "POWERED" : "off") + net + "   [signal decays 1 per block]";
            } else if (fb == Blocks.BED.index) {
                useLine = core.world.DayCycle.isNight(fw.tick)
                        ? "BED   [R CLICK = SET SPAWN + SLEEP TO DAWN]"
                        : "BED   [R CLICK = SET SPAWN]";
            } else if (fb == Blocks.LADDER.index) {
                useLine = "LADDER   [JUMP = CLIMB UP / SNEAK = DOWN]";
            } else if (fb == Blocks.TRAPDOOR.index) {
                useLine = "TRAPDOOR " + (fw.getMeta(fx, fy, fz) == 1 ? "OPEN" : "CLOSED") + "   [R CLICK = TOGGLE]";
            } else if (fb == Blocks.HOPPER.index) {
                int tot = 0;
                java.util.LinkedHashMap<String, Integer> hs = fw.chestOf(fcell);
                if (hs != null) for (Integer v : hs.values()) tot += v.intValue();
                useLine = "HOPPER " + tot + " items  auto:" + core.systems.HopperSystem.ticksToNext(fw.tick)
                        + "t  out -> " + Facing.name(fw.getFacing(fx, fy, fz))
                        + "   [auto: pulls from above, pushes out the front]";
            } else if (fb == Blocks.SIGN.index) {
                String st = fw.signTextOf(fcell);
                useLine = "SIGN " + (st == null ? "(blank)" : st) + "   [R CLICK = EDIT TEXT]";
            } else if (fb == Blocks.REPEATER.index) {
                useLine = "REPEATER out=" + fw.getMeta(fx, fy, fz)
                        + "  in(back)=" + core.systems.RedstoneLogicSystem.rearStrength(fw, fx, fy, fz, fw.getFacing(fx, fy, fz))
                        + "  in -> " + Facing.name(fw.getFacing(fx, fy, fz))
                        + "   [samples the back every " + core.systems.RedstoneLogicSystem.REPEATER_PERIOD + "t, regen to the front]";
            } else if (fb == Blocks.COMPARATOR.index) {
                int cf = fw.getFacing(fx, fy, fz);
                useLine = "COMPARATOR " + (fw.isSubtractMode(fx, fy, fz) ? "SUBTRACT" : "COMPARE")
                        + "  A(back)=" + core.systems.RedstoneLogicSystem.rearStrength(fw, fx, fy, fz, cf)
                        + " B(sides)=" + core.systems.RedstoneLogicSystem.sideStrength(fw, fx, fy, fz, cf)
                        + " out=" + fw.getMeta(fx, fy, fz) + " -> " + Facing.name(cf)
                        + "   [R CLICK = TOGGLE compare/subtract]";
            } else if (fb == Blocks.PLATE.index) {
                useLine = "PRESSURE PLATE " + (fw.getMeta(fx, fy, fz) == 1 ? "PRESSED" : "idle")
                        + "   [step on it with any entity]";
            } else if (fb == Blocks.CAMPFIRE.index) {
                useLine = "CAMPFIRE LIT   [light source; standing on it burns]";
            } else if (fb == Blocks.FARMLAND.index) {
                useLine = "FARMLAND   [crop growth x" + core.systems.CropSystem.TILL_BONUS + "]";
            } else if (fb == Blocks.DISPENSER.index) {
                int dtot = 0;
                java.util.LinkedHashMap<String, Integer> dm = fw.chestOf(fcell);
                if (dm != null) for (Integer v : dm.values()) dtot += v.intValue();
                useLine = "DISPENSER mag=" + dtot + "  " + (fw.getMeta(fx, fy, fz) == 1 ? "POWERED" : "idle")
                        + "  out -> " + Facing.name(fw.getFacing(fx, fy, fz))
                        + "   [rising edge: 1 item out the front (container, else ejected as a drop)]";
            } else if (fb == Blocks.PISTON.index) {
                useLine = "PISTON " + (fw.getMeta(fx, fy, fz) == 1 ? "POWERED" : "idle")
                        + "  push -> " + Facing.name(fw.getFacing(fx, fy, fz))
                        + "   [rising edge: pushes the block in front 1 step. SNEAK + R CLICK = ROTATE]";
            } else if (fb == Blocks.OBSERVER.index) {
                int om = fw.getMeta(fx, fy, fz);
                useLine = "OBSERVER watches " + Facing.name(Facing.opposite(fw.getFacing(fx, fy, fz)))
                        + (om >>> 1 == 0 ? "  (no baseline)" : ((om & 1) != 0 ? "  PULSE" : "  steady"))
                        + "   [1 tick pulse on change. SNEAK + R CLICK = ROTATE]";
            } else if (fb == Blocks.BARREL.index) {
                int btl = 0;
                java.util.LinkedHashMap<String, Integer> bbag = fw.chestOf(fcell);
                if (bbag != null) for (Integer v : bbag.values()) btl += v.intValue();
                useLine = "BARREL " + btl + " items   [R CLICK = STORE / TAKE ALL]";
            } else if (fb == Blocks.LECTERN.index) {
                String ltx = fw.signTextOf(fcell);
                useLine = "LECTERN " + (ltx == null ? "(blank)" : ltx) + "   [R CLICK = EDIT TEXT]";
            } else if (fb == Blocks.CAULDRON.index) {
                useLine = "CAULDRON water " + fw.getMeta(fx, fy, fz) + "/" + CAULDRON_FULL
                        + "   [R CLICK = FILL FROM ADJACENT WATER / DRINK]";
            } else if (fb == Blocks.CHAIN.index || fb == Blocks.SCAFFOLDING.index) {
                useLine = Blocks.byIndex(fb).id + "   [JUMP = CLIMB UP / SNEAK = DOWN]";
            } else if (fb == Blocks.IRON_BARS.index) {
                useLine = "IRON BARS   [blocks movement, see-through]";
            } else if (fb == Blocks.SAPLING.index) {
                useLine = "SAPLING   [planted on dirt/farmland -> grows slowly into a tree]";
            }
            if (useLine != null) {
                HudText.ascii(this.hudBuf, 16.0f, this.H - 82, 1.06f, 1.0f, 0.86f, 0.45f, 1.0f, useLine, this.W, this.H);
            }
        }
        HudText.ascii(this.hudBuf, 16.0f, this.H - 64, 0.95f, 0.8f, 0.9f, 1.0f, 0.75f, "WASD move  MOUSE look  SPACE jump  F attack  R art  Q roll  U flask  TAB lock", this.W, this.H);
        HudText.ascii(this.hudBuf, 16.0f, this.H - 46, 0.95f, 0.8f, 0.9f, 1.0f, 0.75f, "L click dig  R click place / USE (door/chest/workbench/lever/button/bed/hopper/sign. repeater comparator piston dispenser = place FACING your look)  E inventory  1-5 block  T talk  B chronicle  F3 debug  F9 save  F10 load  ESC menu", this.W, this.H);
        this.hudMark("L_moon");
        // QA 2026-09-15：月相 / 食 状态 + 下一次月食预报（左下角，纯展示）
        if (!this.menu.isOpen()) {
            core.world.Celestial.Eclipse hudEcl = core.world.Celestial.eclipse(this.celMoon, this.celSun);
            // 机制状态前缀：**只在机制真的启用/有值时才占 HUD**（与 presetHudLine 同纪律：
            // 没改变世界就不加噪）—— 默认预设 hungerRate=0、无驯养兽，玩家看不到这一项。
            // 来由（2026-09-17 自查）：这两个机制此前**都没有任何可见反馈** ——
            // 饥饿会扣血致死却看不到读数、驯养兽与敌对兽渲染一致却看不到数量，
            // 属"机制接线了但对玩家不可见"，与"接线了但没接通"同类。
            String statusHud = "";
            if (this.sim.world.config.hungerRate > 0f) {
                statusHud += "HUNGER " + Math.round(this.sim.player.hunger) + "%"
                        + (this.sim.player.hunger <= 0f ? " STARVING" : "") + "   ";
            }
            core.systems.System capHud = this.sim.world.registry.get("capture");
            if (capHud instanceof core.systems.CaptureSystem) {
                int tamedNow = ((core.systems.CaptureSystem) capHud).tamedAlive();
                if (tamedNow > 0) statusHud += "TAMED " + tamedNow + "   ";
            }
            String moonLine = statusHud + "FPS " + Math.round(this.fpsEma) + "   MOON " + core.world.Celestial.phaseName(this.sim.world.tick)
                    + "  " + Math.round(core.world.Celestial.moonLit(this.sim.world.tick) * 100.0f) + "%"
                    + (core.world.Celestial.moonUp(this.sim.world.tick) ? "  UP" : "  BELOW HORIZON");
            HudText.ascii(this.hudBuf, 16.0f, this.H - 28, 0.95f, 0.90f, 0.92f, 1.0f, 0.85f, moonLine, this.W, this.H);
            String eclLine;
            if (core.world.Celestial.notableEclipse(hudEcl)) {
                boolean solar = hudEcl == core.world.Celestial.Eclipse.PARTIAL_SOLAR
                        || hudEcl == core.world.Celestial.Eclipse.TOTAL_SOLAR;
                float mag = solar ? core.world.Celestial.solarMagnitude(this.celMoon, this.celSun)
                                  : core.world.Celestial.lunarMagnitude(this.celMoon, this.celSun);
                eclLine = core.world.Celestial.eclipseName(hudEcl) + "  MAG " + this.fmt2(mag);
            } else if (this.eclipseNextLunar < 0L) {
                eclLine = "";
            } else {
                long inDays = Math.max(0L, (this.eclipseNextLunar - this.sim.world.tick) / DayCycle.DAY_LEN);
                // 只在临近（3 个游戏日内）才显示预报：HUD 每个字都要逐像素光栅化，能省则省
                eclLine = inDays <= 3L
                        ? ("NEXT LUNAR ECLIPSE: DAY " + DayCycle.dayNumber(this.eclipseNextLunar)
                           + "  (in " + inDays + " d)   NEXT SOLAR: "
                           + (this.eclipseNextSolar < 0L ? "--" : "DAY " + DayCycle.dayNumber(this.eclipseNextSolar)))
                        : "";
            }
            if (!eclLine.isEmpty()) {
                HudText.ascii(this.hudBuf, 16.0f, this.H - 14, 0.95f, 0.82f, 0.74f, 0.98f, 0.9f, eclLine, this.W, this.H);
            }
        }
        if (!this.menu.isOpen() && CjkFont.available()) {
            // QA 冻结（2026-09-21）：教程面板的淡出用真实挂钟（glfwGetTime - tutDoneAt）/1.5 ——
            // 这是**第五个必须冻结的时钟**（前四个：仿真步进 / 演出时钟 this.time / 失焦遮罩 / 流式加载）。
            // 不冻结时，同一次运行里"拍摄那一刻淡出到哪"取决于真实耗时 → 面板区域 alpha 随机
            // → 无头截图 A/B 失效（实测 93% 像素不同，且**逐次随机**，同一版本连跑两次也不同）。
            // QA 下把 alpha 固定在"完全显示"（f21 恒取 tutDoneAt 之前的值），使面板逐字节稳定。
            float f21 = this.qaFrozen ? 0.0f : (float)GLFW.glfwGetTime();
            if (Float.isNaN(this.tutYaw0)) {
                this.tutYaw0 = this.yaw;
                this.tutPitch0 = this.pitch;
            }
            n = 0;
            this.hudMark("M_tutorial");
            for (float f32a : this.tutDoneAt) {
                if (!(f32a < 0.0f) && !(f21 - f32a < 1.5f)) continue;
                ++n;
            }
            if (n > 0) {
                float f22 = 19.0f;
                float f23 = 10.0f;
                float f24 = 214.0f;
                // P3 布局引擎：右下教程面板（BR 锚 = 右边距 16 / 底边距 76；原式 x = W-214-16, y = H-f32-76）
                f32 = f23 * 2.0f + 16.0f + (float)TUT_KEY.length * f22;
                UiLayout.Rect pTut = UiLayout.fit("hud-tutorial", UiLayout.BR, 16f, 76f, f24, f32, this.W, this.H);
                this.panel(this.hudBuf, pTut.x, pTut.y, pTut.w, pTut.h, 0.06f, 0.08f, 0.12f, 0.62f);
                HudText.cjk(this.hudBuf, pTut.x + 12.0f, pTut.y + f23 - 3.0f, 0.8f, 0.55f, 0.72f, 0.95f, 0.95f, "\u64cd\u4f5c \u00b7 \u505a\u51fa\u52a8\u4f5c\u540e\u9690\u53bb", this.W, this.H);
                for (int i = 0; i < TUT_KEY.length; ++i) {
                    float f25;
                    if (this.tutDoneAt[i] < 0.0f) {
                        f25 = 0.95f;
                    } else {
                        f25 = 0.95f * (1.0f - (f21 - this.tutDoneAt[i]) / 1.5f);
                        if (f25 <= 0.02f) continue;
                    }
                    float f26 = pTut.y + f23 + 14.0f + (float)i * f22;
                    HudText.cjk(this.hudBuf, pTut.x + 12.0f, f26, 0.85f, 1.0f, 0.85f, 0.35f, f25, TUT_KEY[i], this.W, this.H);
                    HudText.cjk(this.hudBuf, pTut.x + 84.0f, f26, 0.85f, 0.85f, 0.9f, 0.98f, f25 * 0.95f, TUT_DESC[i], this.W, this.H);
                }
            }
        }
        if (this.debugHud) {
            this.drawDebugPanel();
        }
        if (this.nearNpc != null) {
            string = "T: TALK  " + this.nearNpc.profession + "  " + Dialogue.asciiPersona(this.nearNpc) + "  " + Dialogue.asciiMood(this.nearNpc);
            HudText.ascii(this.hudBuf, (float)this.W / 2.0f - 128.0f, (float)this.H / 2.0f + 58.0f, 1.25f, 1.0f, 1.0f, 0.75f, 0.95f, string, this.W, this.H);
        }
        if (this.talkToastTimer > 0.01f) {
            HudText.ascii(this.hudBuf, 16.0f, this.H - 108, 1.35f, 1.0f, 0.9f, 0.55f, Math.min(1.0f, this.talkToastTimer), this.talkToast, this.W, this.H);
        }
        float f27 = 20.25f;
        String string3 = null;
        this.hudMark("N_trials");
        for (World.Shrine shrine : this.sim.world.shrines) {
            if (shrine.claimed || !((f2 = (f4 = player.x - shrine.x) * f4 + (f32 = player.z - shrine.z) * f32) < f27)) continue;
            f27 = f2;
            string3 = CjkFont.available() ? "\u796d\u575b \u00b7 \u9760\u8fd1\u89c9\u9192\u80fd\u529b" : "SHRINE - approach to awaken";
        }
        for (Trials.Site site : this.sim.world.trials.sites) {
            if (site.claimed || !((f2 = (f4 = player.x - site.x) * f4 + (f32 = player.z - site.z) * f32) < f27)) continue;
            f27 = f2;
            boolean bl2 = CjkFont.available();
            if ("GLIDE".equals(site.ability)) {
                string3 = bl2 ? "\u8bd5\u70bc\u4e4b\u5730 \u00b7 \u98ce\uff08\u6ed1\u7fd4\uff09" : "TRIAL OF GLIDE";
                continue;
            }
            if ("DASH".equals(site.ability)) {
                string3 = bl2 ? "\u8bd5\u70bc\u4e4b\u5730 \u00b7 \u75be\uff08\u51b2\u523a\uff09" : "TRIAL OF DASH";
                continue;
            }
            string3 = bl2 ? "\u8bd5\u70bc\u4e4b\u5730 \u00b7 \u529b\uff08\u7206\u5f39\uff09" : "TRIAL OF BOMB";
        }
        if (string3 != null) {
            if (CjkFont.available()) {
                float f28 = CjkFont.width(string3, 1.0f);
                HudText.cjk(this.hudBuf, ((float)this.W - f28) / 2.0f, (float)this.H / 2.0f + 76.0f, 1.0f, 0.78f, 0.95f, 1.0f, 0.9f, string3, this.W, this.H);
            } else {
                HudText.ascii(this.hudBuf, (float)this.W / 2.0f - 110.0f, (float)this.H / 2.0f + 76.0f, 1.15f, 0.78f, 0.95f, 1.0f, 0.9f, string3, this.W, this.H);
            }
        }
        if (this.levelUpTimer > 0.01f) {
            f27 = Math.min(1.0f, this.levelUpTimer);
            string3 = player.level >= 3 ? "UNLOCK: ROLL (SHIFT)" : (player.level >= 2 ? "UNLOCK: ART (R)" : "");
            HudText.ascii(this.hudBuf, (float)this.W / 2.0f - 120.0f, (float)this.H / 2.0f - 150.0f, 2.6f, 1.0f, 0.9f, 0.3f, f27, "LEVEL UP!", this.W, this.H);
            if (!string3.isEmpty()) {
                HudText.ascii(this.hudBuf, (float)this.W / 2.0f - 110.0f, (float)this.H / 2.0f - 118.0f, 1.3f, 0.8f, 1.0f, 0.9f, f27, string3, this.W, this.H);
            }
        }
        if (this.killFlash > 0.01f) {
            HudText.ascii(this.hudBuf, (float)this.W / 2.0f - 70.0f, (float)this.H / 2.0f - 200.0f, 1.8f, 1.0f, 0.85f, 0.2f, Math.min(1.0f, this.killFlash + 0.2f), "SOUL +8", this.W, this.H);
        }
        if (this.bannerTimer > 0.01f) {
            if (CjkFont.available()) {
                f27 = CjkFont.width(this.bannerText, 1.7f);
                HudText.cjk(this.hudBuf, (float)this.W / 2.0f - f27 / 2.0f, (float)this.H / 2.0f - 128.0f, 1.7f, 1.0f, 0.9f, 0.45f, Math.min(1.0f, this.bannerTimer), this.bannerText, this.W, this.H);
            } else {
                HudText.ascii(this.hudBuf, (float)this.W / 2.0f - 150.0f, (float)this.H / 2.0f - 128.0f, 1.7f, 1.0f, 0.9f, 0.45f, Math.min(1.0f, this.bannerTimer), this.bannerText, this.W, this.H);
            }
        }
        // 告示牌文本编辑面板（第七批）：底部居中 —— 输入框 + 光标 + 操作提示 + 已用字符数。
        // 「光标」用 '_' 表示：位图字体是 5x7 点阵，没有真正的闪烁块，下划线是这套字体里最像光标的字。
        if (this.signEditing) {
            float eScale = 1.05f;
            String shown = this.signEditBuf.toString();
            String field = shown + "_";
            float eW = Math.min((float)this.W - 120.0f, 620.0f);
            float eH = 58.0f;
            UiLayout.Rect pEd = UiLayout.fit("hud-signedit", UiLayout.BC, 0f, 150f, eW, eH, this.W, this.H);
            this.panel(this.hudBuf, pEd.x, pEd.y, pEd.w, pEd.h, 0.05f, 0.06f, 0.10f, 0.86f);
            HudText.ascii(this.hudBuf, pEd.x + 12.0f, pEd.y + 6.0f, 0.78f, 1.0f, 0.86f, 0.42f, 1.0f,
                    "SIGN TEXT  " + shown.length() + "/" + SIGN_MAX, this.W, this.H);
            HudText.ascii(this.hudBuf, pEd.x + 12.0f, pEd.y + 22.0f, eScale, 0.98f, 0.98f, 1.0f, 1.0f,
                    field, this.W, this.H);
            HudText.ascii(this.hudBuf, pEd.x + 12.0f, pEd.y + 44.0f, 0.74f, 0.72f, 0.78f, 0.86f, 1.0f,
                    "TYPE A-Z 0-9  ENTER = SAVE   ESC = CANCEL   BACKSPACE = DEL", this.W, this.H);
        }
        if (this.talkReplyTimer > 0.01f && this.talkReply != null && !this.talkReply.isEmpty()) {
            f27 = 1.0f;
            float f29 = Math.min((float)this.W - 80.0f, 760.0f);
            List<String> list = CjkFont.wrap(this.talkReply, f27, f29 - 28.0f);
            if (list.size() > 4) {
                list = list.subList(0, 4);
            }
            float f30 = 20.0f * f27;
            f4 = 30.0f + (float)list.size() * f30;
            // P3 布局引擎：底部对话框（BC 锚 = 底边距 160；原式 x = (W-f29)/2, y = H-160-f4）
            UiLayout.Rect pTalk = UiLayout.fit("hud-dialogue", UiLayout.BC, 0f, 160f, f29, f4, this.W, this.H);
            f = 0.74f * Math.min(1.0f, this.talkReplyTimer / 2.0f);
            this.panel(this.hudBuf, pTalk.x, pTalk.y, pTalk.w, pTalk.h, 0.05f, 0.06f, 0.1f, f);
            HudText.ascii(this.hudBuf, pTalk.x + 14.0f, pTalk.y + 8.0f, 0.9f, 1.0f, 0.9f, 0.55f, 1.0f, this.talkReplyLabel, this.W, this.H);
            float f31 = pTalk.y + 26.0f;
            if (CjkFont.available()) {
                this.hudMark("O_list1");
                for (String string4 : list) {
                    HudText.cjk(this.hudBuf, pTalk.x + 14.0f, f31, f27, 0.95f, 0.97f, 1.0f, 1.0f, string4, this.W, this.H);
                    f31 += f30;
                }
            } else {
                HudText.ascii(this.hudBuf, pTalk.x + 14.0f, f31, 0.85f, 1.0f, 0.45f, 0.45f, 1.0f, "CJK UNAVAILABLE - see render_diag.log", this.W, this.H);
            }
        }
        if (this.showChronicle) {
            String string5 = this.sim.world.chronicle.story;
            if (string5 == null) {
                string5 = "";
            }
            float f45 = 0.95f;
            float f33 = Math.min((float)this.W - 80.0f, 580.0f);
            float f34 = (float)this.W - f33 - 20.0f;
            f4 = 210.0f;
            List<String> list = CjkFont.wrap(string5, f45, f33 - 28.0f);
            if (list.size() > 7) {
                list = list.subList(0, 7);
            }
            f2 = 19.0f * f45;
            f = 48.0f + (float)list.size() * f2;
            // P3 布局引擎：右侧编年史面板（TR 锚 = 右边距 20 / 顶边距 210；原式 x = W-f33-20）
            UiLayout.Rect pStory = UiLayout.fit("hud-story", UiLayout.TR, 20f, 210f, f33, f, this.W, this.H);
            this.panel(this.hudBuf, pStory.x, pStory.y, pStory.w, pStory.h, 0.05f, 0.06f, 0.1f, 0.76f);
            HudText.ascii(this.hudBuf, pStory.x + 14.0f, pStory.y + 8.0f, 0.95f, 0.85f, 0.94f, 1.0f, 1.0f, "CHRONICLE  D" + this.sim.world.chronicle.days + "   (B toggle)", this.W, this.H);
            float f35 = pStory.y + 28.0f;
            if (CjkFont.available()) {
                this.hudMark("P_list2");
                for (String string6 : list) {
                    HudText.cjk(this.hudBuf, pStory.x + 14.0f, f35, f45, 0.92f, 0.95f, 0.98f, 1.0f, string6, this.W, this.H);
                    f35 += f2;
                }
            } else {
                HudText.ascii(this.hudBuf, pStory.x + 14.0f, f35, 0.85f, 1.0f, 0.45f, 0.45f, 1.0f, "CJK UNAVAILABLE - see render_diag.log", this.W, this.H);
            }
            HudText.ascii(this.hudBuf, f34 + 14.0f, f4 + f - 15.0f, 0.72f, 0.7f, 0.8f, 0.9f, 0.85f, "full text: chronicle.log", this.W, this.H);
        }
        if (menu.isOpen()) {
            drawMenuOverlay(hudBuf);
            if (menu.page() == MenuModel.Page.CHARACTER) drawCharacterOverlay(hudBuf);
        }
        // ---- MC 风格 HUD v2（QA 2026-09-13）：凹槽 hotbar + 像素心形红心 + 经验条 ----
        {
            float cell = 44.0f;
            float hbW = 9.0f * cell + 8.0f;
            float hbX = ((float)this.W - hbW) / 2.0f;
            float hbY = (float)this.H - 56.0f;
            // 外框（MC 灰底 + 亮顶暗底）
            this.addRect2D(this.hudBuf, hbX - 5.0f, hbY - 5.0f, hbW + 10.0f, cell + 18.0f, 0.16f, 0.16f, 0.19f, 0.85f);
            this.addRect2D(this.hudBuf, hbX - 5.0f, hbY - 5.0f, hbW + 10.0f, 2.0f, 0.45f, 0.45f, 0.5f, 0.9f);
            this.addRect2D(this.hudBuf, hbX - 5.0f, hbY + cell + 11.0f, hbW + 10.0f, 2.0f, 0.08f, 0.08f, 0.1f, 0.9f);
            // E 批：9 个 hotbar 槽 = 背包槽 0-8（物品图标由背包内容驱动，不再硬编码方块）
            this.hudMark("Q_hotbar");
            for (int i = 0; i < 9; ++i) {
                float gx = hbX + (float)i * cell + 4.0f;
                // 格子凹槽：暗底 + 左上暗边 + 右下亮边（MC 内凹感）
                this.addRect2D(this.hudBuf, gx, hbY, cell, cell, 0.08f, 0.08f, 0.10f, 0.75f);
                this.addRect2D(this.hudBuf, gx, hbY, cell, 2.0f, 0.05f, 0.05f, 0.06f, 0.9f);
                this.addRect2D(this.hudBuf, gx, hbY, 2.0f, cell, 0.05f, 0.05f, 0.06f, 0.9f);
                this.addRect2D(this.hudBuf, gx, hbY + cell - 2.0f, cell, 2.0f, 0.35f, 0.35f, 0.38f, 0.85f);
                this.addRect2D(this.hudBuf, gx + cell - 2.0f, hbY, 2.0f, cell, 0.35f, 0.35f, 0.38f, 0.85f);
                String it = (this.inventory != null) ? this.inventory.idAt(i) : null;
                if (it != null) {
                    this.drawItemIcon(this.hudBuf, gx + 7.0f, hbY + 7.0f, cell - 14.0f,
                            this.content.item(it), this.inventory.countAt(i), this.inventory.durAt(i));
                }
                if (this.heldSlot == i) {
                    // 选中白框（MC 高亮）
                    this.addRect2D(this.hudBuf, gx - 2.0f, hbY - 2.0f, cell + 4.0f, 3.0f, 1.0f, 1.0f, 1.0f, 1.0f);
                    this.addRect2D(this.hudBuf, gx - 2.0f, hbY + cell - 1.0f, cell + 4.0f, 3.0f, 1.0f, 1.0f, 1.0f, 1.0f);
                    this.addRect2D(this.hudBuf, gx - 2.0f, hbY - 2.0f, 3.0f, cell + 4.0f, 1.0f, 1.0f, 1.0f, 1.0f);
                    this.addRect2D(this.hudBuf, gx + cell - 1.0f, hbY - 2.0f, 3.0f, cell + 4.0f, 1.0f, 1.0f, 1.0f, 1.0f);
                }
            }
            // 像素心形红心行（每心 2 HP；9×9 像素心 = 十字块拼合，MC 观感）
            float hy = hbY - 16.0f;
            int hearts = 10;
            this.hudMark("R_hearts");
            for (int i = 0; i < hearts; ++i) {
                float hx = hbX + (float)i * 18.0f;
                float hpIn = (float)this.sim.player.hp - (float)i * 2.0f;
                float full = hpIn >= 2.0f ? 1.0f : (hpIn >= 1.0f ? 0.5f : 0.0f);
                float cr = full > 0.0f ? 0.78f : 0.22f, cg = full > 0.0f ? 0.08f : 0.08f, cb = full > 0.0f ? 0.10f : 0.10f;
                float a = full > 0.0f ? 0.98f : 0.5f;
                float u = 2.0f;   // 像素单位
                if (full <= 0.0f) {
                    // 空心底（暗灰心形）
                    cr = 0.22f; cg = 0.22f; cb = 0.25f; a = 0.75f;
                }
                // 心形像素图（行×列，1=实体）：经典 MC 心轮廓
                int[][] heartPx = {
                    {0,1,1,0,0,1,1,0},
                    {1,1,1,1,1,1,1,1},
                    {1,1,1,1,1,1,1,1},
                    {1,1,1,1,1,1,1,1},
                    {0,1,1,1,1,1,1,0},
                    {0,0,1,1,1,1,0,0},
                    {0,0,0,1,1,0,0,0},
                };
                for (int py = 0; py < heartPx.length; ++py) {
                    for (int pxx = 0; pxx < heartPx[py].length; ++pxx) {
                        if (heartPx[py][pxx] == 0) continue;
                        // 半心：右半列压暗（左半红右半暗 = 半血直观）
                        boolean dim = full < 0.9f && full > 0.1f && pxx >= 4;
                        float rr = dim ? cr * 0.3f : cr, gg = dim ? cg * 0.3f : cg, bb = dim ? cb * 0.3f : cb;
                        this.addRect2D(this.hudBuf, hx + (float)pxx * u, hy + (float)py * u, u, u, rr, gg, bb, a);
                    }
                }
                // 高光点（满心才有）
                if (full >= 2.0f) {
                    this.addRect2D(this.hudBuf, hx + 2.0f, hy + 2.0f, 2.0f, 2.0f, 1.0f, 0.55f, 0.6f, 0.95f);
                }
            }
            this.hudMark("S_xp");
            // 经验条（MC 绿条：暗槽 + 亮绿填充）
            float xpRatio = Math.min(1.0f, (float)this.sim.player.xp / ((float)Math.max(1, this.sim.player.level) * 24.0f));
            this.addRect2D(this.hudBuf, hbX, hy - 10.0f, hbW - 8.0f, 6.0f, 0.08f, 0.08f, 0.08f, 0.85f);
            this.addRect2D(this.hudBuf, hbX, hy - 10.0f, (hbW - 8.0f) * xpRatio, 6.0f, 0.35f, 0.9f, 0.2f, 0.95f);
            this.addRect2D(this.hudBuf, hbX, hy - 10.0f, (hbW - 8.0f), 1.0f, 0.6f, 1.0f, 0.45f, 0.9f);
        }

        this.hudMark("T_viewmodel");
        // C 批③：第一人称手持物视模型（跟随攻击状态机摆动；纯渲染、零漂移）
        // QA 2026-09-15：菜单/背包打开时隐藏手持物 —— 否则视模型会叠加在菜单 UI 之上（绿方块浮在设置页上）
        if (!this.menu.isOpen() && !this.inventoryOpen) {
            this.drawHeldViewmodel(this.hudBuf, this.heldItem(),
                    this.sim.player.attackSwing() + this.skillCastSwing());
        }

        // E 批：背包界面（E 键打开）：覆盖在 HUD 之上
        if (this.inventoryOpen) this.drawInventoryPanel();

        // QA diag: HUD submit scale (5s throttle)
        if (HUD_DIAG) {
            this.hudMark("Z_end");
            this.hudMsBuild = (java.lang.System.nanoTime() - hudT0) / 1000000.0f;
            this.hudMsBuildSum += this.hudMsBuild; this.hudMsBuildN++;
            // 节流到 ~1/s：本行只为看清一次稳态，逐帧刷会把 game_diag.log 撑爆
            // 节流用 **frameCount** 而不是 this.time：QA 冻结（-Dbw.snap）下 time 不增长，
            // 用时间节流只会打印一行（而且是最冷的首帧）—— 那样测出来的"耗时"没有意义。
            if (this.frameCount - this.hudDiagFrame >= 20) {
                this.hudDiagFrame = this.frameCount;
                GameLog.log("HUDDIAG", "frame=" + this.frameCount
                        + " total=" + (this.hudBuf.position() / HUD_VERT_FLOATS)
                        + " verts  msBuild=" + String.format(java.util.Locale.US, "%.2f", this.hudMsBuild)
                        + " msBuildAvg=" + String.format(java.util.Locale.US, "%.2f",
                                this.hudMsBuildSum / Math.max(1, this.hudMsBuildN))
                        + " msUpload=" + String.format(java.util.Locale.US, "%.2f", this.hudMsUpload)
                        + " | " + this.hudMarkLog);
            }
            this.hudMarkLog.setLength(0);
            this.hudMarkAt = 0;
            this.hudMsBuildSum = 0f; this.hudMsBuildN = 0;
        }
        GameLog.hb("HUD verts=" + (this.hudBuf.position() / HUD_VERT_FLOATS) + " W=" + this.W + " H=" + this.H + " showHud=" + this.menu.showHud + " cap=" + this.hudBuf.capacity() + " msdfChars=" + MsdfFont.cachedChars());
        this.hudBuf.flip();
        GL33.glDisable((int)2929);
        GL33.glEnable((int)3042);
        GL33.glBlendFunc((int)770, (int)771);
        GL33.glUseProgram((int)this.hudShader);
        this.setHudVP(this.IDENTITY);
        GL33.glBindVertexArray((int)this.hudVAO);
        GL33.glBindBuffer((int)34962, (int)this.hudVBO);
        long hudU0 = java.lang.System.nanoTime();
        GL33.glBufferSubData((int)34962, (long)0L, (FloatBuffer)this.hudBuf);
        GL33.glDrawArrays((int)4, (int)0, (int)(this.hudBuf.remaining() / HUD_VERT_FLOATS));
        this.hudMsUpload = (java.lang.System.nanoTime() - hudU0) / 1000000.0f;
        GL33.glBindVertexArray((int)0);
        GL33.glDisable((int)3042);
        GL33.glEnable((int)2929);
        this.hudBuf.clear();

        // ---- P1（2026-09-23）：MSDF 通道（中文大字号标题）----
        // 独立程序 + 独立 VAO/VBO，画在 HUD 之后（标题要压在菜单暗化层之上）。
        // 混合模式与 HUD 完全一致（straight alpha：FS 输出 vec4(rgb, a)，blendFunc(SRC_ALPHA, 1-SRC_ALPHA)）
        // —— 若这里改成预乘，MSDF 的抗锯齿边缘会被自己的 alpha 再压一次（HUD 通道踩过的同一个坑）。
        this.drawMsdf();
    }

    /** MSDF 文本通道的提交：上传脏槽 → 设置 uniform → 一次 DrawArrays。 */
    private void drawMsdf() {
        if (this.msdfBuf.position() == 0) { this.msdfBuf.clear(); return; }
        MsdfFont.flushUploads();                 // 本帧新生成的字形写进图集纹理
        this.msdfBuf.flip();
        GL33.glDisable((int)2929);
        GL33.glEnable((int)3042);
        GL33.glBlendFunc((int)770, (int)771);
        GL33.glUseProgram((int)this.msdfShader);
        this.setMsdfVP(this.IDENTITY);
        GL33.glActiveTexture((int)33984);
        GL33.glBindTexture((int)3553, MsdfFont.texture());
        GL33.glUniform1i((int)this.msdfUTex, (int)0);
        GL33.glUniform2f((int)this.msdfUAtlas, (float)MsdfFont.ATLAS, (float)MsdfFont.ATLAS);
        GL33.glUniform1f((int)this.msdfURange, MsdfGen.PX_RANGE);
        GL33.glUniform1f((int)this.msdfUOutline, MsdfFont.outlineWidth());
        GL33.glUniform3f((int)this.msdfUOutlineCol,
                MsdfFont.outlineR(), MsdfFont.outlineG(), MsdfFont.outlineB());
        GL33.glBindVertexArray((int)this.msdfVAO);
        GL33.glBindBuffer((int)34962, (int)this.msdfVBO);
        GL33.glBufferSubData((int)34962, (long)0L, (FloatBuffer)this.msdfBuf);
        GL33.glDrawArrays((int)4, (int)0, (int)(this.msdfBuf.remaining() / HUD_VERT_FLOATS));
        GL33.glBindVertexArray((int)0);
        GL33.glDisable((int)3042);
        GL33.glEnable((int)2929);
        this.msdfBuf.clear();
    }

    private void tutMark(int n) {
        if (n >= 0 && n < this.tutDoneAt.length && this.tutDoneAt[n] < 0.0f) {
            this.tutDoneAt[n] = (float)GLFW.glfwGetTime();
        }
    }

    /** 构建戳：取自身 class 文件的编译时刻（自动反映构建日期，永不过期）。 */
    private static String buildTag() {
        try {
            File f = new File(Game.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            long t = f.isDirectory() ? new File(f, "render/lwjgl/Game.class").lastModified() : f.lastModified();
            return "BUILD " + new java.text.SimpleDateFormat("MMdd-HHmm").format(new java.util.Date(t));
        } catch (Throwable t2) {
            return "BUILD unknown";
        }
    }

    private void drawDebugPanel() {
    if (this.debugHud) {
        Game.hudQuad(this.hudBuf, 14.0f, this.H - 128, 340.0f, 104.0f, 0.0f, 0.0f, 0.0f, 0.55f, this.W, this.H);
        float dbgMs = this.fpsEma > 0.01f ? 1000.0f / this.fpsEma : 0.0f;
        HudText.ascii(this.hudBuf, 20.0f, this.H - 122, 0.9f, 0.85f, 1.0f, 0.85f, 1.0f, String.format("FPS %.0f (%.1f ms)   XYZ %.1f / %.1f / %.1f", Float.valueOf(this.fpsEma), Float.valueOf(dbgMs), Float.valueOf(this.sim.player.x), Float.valueOf(this.sim.player.y), Float.valueOf(this.sim.player.z)), this.W, this.H);
        HudText.ascii(this.hudBuf, 20.0f, this.H - 110, 0.9f, 0.85f, 1.0f, 0.85f, 1.0f, String.format("CHUNK %d,%d   DIRTY %d   ENT %d   BIOME %s   DRAW %d/%d   FACES %dk   OCCL %d   PART %d", (int)Math.floor(this.sim.player.x) / 16, (int)Math.floor(this.sim.player.z) / 16, this.sim.world.dirtyChunks.size(), this.sim.world.npcs.size() + this.sim.world.beasts.size(), World.biomeName(this.sim.world.biomeAt((int)Math.floor(this.sim.player.x), (int)Math.floor(this.sim.player.z))), this.drawnChunks, this.chunkX * this.chunkZ, this.drawnFaces / 1000, this.occludedChunks, this.particles.count()), this.W, this.H);
        HudText.ascii(this.hudBuf, 20.0f, this.H - 98, 0.9f, 0.85f, 1.0f, 0.85f, 1.0f, this.rayTarget == null ? "TARGET none" : String.format("TARGET %d,%d,%d", this.rayTarget[0], this.rayTarget[1], this.rayTarget[2]), this.W, this.H);
        HudText.ascii(this.hudBuf, 20.0f, this.H - 86, 0.9f, 0.85f, 1.0f, 0.85f, 1.0f, String.format("HP %d/%d  SOULS %d  LV %d", this.sim.player.hp, this.sim.player.maxHp, this.sim.player.souls, this.sim.player.level), this.W, this.H);
        HudText.ascii(this.hudBuf, 20.0f, this.H - 74, 0.9f, 1.0f, 0.8f, 0.5f, 1.0f, String.format("MOVE mx=%.2f mz=%.2f WASD=%d%d%d%d sneak=%d paused=%d focus=%d gnd=%d", Float.valueOf(this.dbgMx), Float.valueOf(this.dbgMz), this.keys[87] ? 1 : 0, this.keys[83] ? 1 : 0, this.keys[65] ? 1 : 0, this.keys[68] ? 1 : 0, this.keys[340] || this.keys[344] ? 1 : 0, this.sim.world.paused ? 1 : 0, this.windowFocused ? 1 : 0, this.sim.player.onGround ? 1 : 0), this.W, this.H);
        HudText.ascii(this.hudBuf, 20.0f, this.H - 62, 0.9f, 0.6f, 0.95f, 0.6f, 1.0f, this.buildTag() + " - all fixes live", this.W, this.H);
        HudText.ascii(this.hudBuf, 20.0f, this.H - 50, 0.9f, 0.7f, 1.0f, 0.7f, 1.0f,
                String.format("SYS %d OFF %d PH %d   SKILL %d/%d  SOULS %d  DODGE %d",
                        this.sim.world.registry.size(), this.sim.world.registry.disabledCount(),
                        core.systems.Phase.values().length,
                        this.skillTree.learnedCount(this.sim.player), this.skillTree.size(),
                        this.sim.player.souls, this.sim.player.perfectDodges), this.W, this.H);
    }
    }

    /** 失焦遮罩（LD-2026-09-12 重构为独立 pass）：只暗化"世界"，**永不盖住 HUD/菜单**——
     *  旧版盖一切，远程串流场景下 H/ESC/菜单全被盖住像"没反应"。窗口失焦即自动暂停（点击窗口恢复）。 */
    /** 雨丝输出复用缓冲（避免每帧 240 次分配；几何本身在 core.content.RainField，可无头断言）。 */
    private final float[] rainStreak = new float[core.content.RainField.FIELDS];

    /** 小地图开关（N 键）。默认开 —— 玩家曾报过"不知道自己在哪"。 */
    private boolean minimapOn = true;
    /** 小地图像素复用缓冲（采样模型在 core.content.MapField，可无头断言）。 */
    private final int[] minimapCells =
            new int[core.content.MapField.CELLS * core.content.MapField.CELLS];
    // ---- 分帧切片环（Terraria LightingEngine.EngineState 的泛化，2026-09-21）----
    // 一帧只做一件事：把"每帧全算"（小地图采样）与"编辑时全量重算"（光照）摊到几帧里。
    // 任务号：0 = 光照重建；1 = 小地图采样。纯计数器、无 RNG、不进指纹。
    private final core.content.FrameSlicer slicer = new core.content.FrameSlicer(2);
    /** 小地图脏标记：玩家跨格或世界编辑才置脏（避免每帧 576 格全采样）。 */
    private boolean minimapDirty = true;
    /** 上次采样时的玩家格坐标（判断是否跨格 → 置脏）。 */
    private int minimapLastX = Integer.MIN_VALUE, minimapLastZ = Integer.MIN_VALUE;

    /**
     * 雨幕（纯渲染层，零漂移）：世界之后、HUD 之前的一层斜雨丝。
     *
     * <p><b>为什么需要</b>：{@code World.raining} 原本只让"天色压暗 + 雾稍浓"—— 玩家很难分辨
     * 到底在下雨还是天黑了。雨丝是最直接的天气语言（也是 UNIMPLEMENTED §3.1「天气视觉」的缺口）。
     *
     * <p><b>三条实现纪律</b>：
     * <ol>
     *   <li><b>不碰 RNG</b>：位置/长度/速度全部走 {@code EffectQueue.hash01} 纯哈希派生
     *       （不碰 {@code fxRng}、更不碰 {@code simStream}）→ 对四道仿真指纹零影响；</li>
     *   <li><b>不加 GLSL</b>：复用既有 HUD 的 2D 四边形通道（{@code addRect2D} + hudShader）。
     *       沙箱无法验证 GLSL 编译，新写着色器 = 黑屏风险（本项目已有前车之鉴）；</li>
     *   <li><b>有界</b>：丝数固定 {@link #RAIN_STREAKS}，每帧顶点量恒定。</li>
     * </ol>
     *
     * <p>斜度用"主丝 + 偏移的短副丝"模拟（轴对齐四边形做不出真斜线，这是最省的近似）。
     */
    /**
     * 画破坏阶段裂纹（见 {@link #initCrackPass()}）。画序：不透明世界之后、半透明 pass 之前
     * （这样挡在方块前面的水/玻璃仍会正确混在裂纹之上）。深度测试开、深度写关。
     * QA：-Dbw.dig=0.6 可强制破坏阶段（无头截图验证用，目标取当前瞄准方块）。
     */
    private void drawCrackOverlay() {
        if (this.crackShader == 0) return;
        String forced = System.getProperty("bw.dig");
        int bx, by, bz;
        float pr;
        if (forced != null) {
            if (this.rayTarget == null) return;
            bx = this.rayTarget[0]; by = this.rayTarget[1]; bz = this.rayTarget[2];
            try { pr = Float.parseFloat(forced); } catch (NumberFormatException ex) { pr = 0.5f; }
        } else {
            if (this.digBx == Integer.MIN_VALUE) return;
            int m = this.sim.world.getBlock(this.digBx, this.digBy, this.digBz);
            float hd = this.digHardness(m);
            pr = hd <= 0.0f ? 1.0f : Math.min(1.0f, this.digProg / hd);
            bx = this.digBx; by = this.digBy; bz = this.digBz;
        }
        if (pr <= 0.02f) return;
        pr = Math.min(pr, 1.0f);
        int stage = Math.min(TextureAtlas.CRACK_STAGES - 1, (int)(pr * TextureAtlas.CRACK_STAGES));
        int tile = TextureAtlas.CRACK_TILE_BASE + stage;

        final float e = 0.004f;      // 沿法线外扩：压住方块自身表面，避免 z-fighting（MC 亦用轻微外扩）
        float x0 = bx - e, y0 = by - e, z0 = bz - e;
        float x1 = bx + 1.0f + e, y1 = by + 1.0f + e, z1 = bz + 1.0f + e;
        float[][][] quads = {
            {{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}},   // +X
            {{x0, y0, z1}, {x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}},   // -X
            {{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}},   // +Y
            {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},   // -Y
            {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},   // +Z
            {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}},   // -Z
        };
        float[][] cuv = {{0f, 0f}, {1f, 0f}, {1f, 1f}, {0f, 1f}};
        int[] tri = {0, 1, 2, 0, 2, 3};
        this.crackBuf.clear();
        for (int fi = 0; fi < 6; fi++) {
            for (int t : tri) {
                float[] p = quads[fi][t];
                this.crackBuf.put(p[0]).put(p[1]).put(p[2])
                        .put(TextureAtlas.uOf(tile, cuv[t][0])).put(TextureAtlas.vOf(tile, cuv[t][1]));
            }
        }
        this.crackBuf.flip();

        GL33.glEnable((int)3042);                       // GL_BLEND
        GL33.glBlendFunc((int)770, (int)771);           // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
        GL33.glDepthMask((boolean)false);
        GL33.glUseProgram((int)this.crackShader);
        if (this.uCrackVP != -1) {
            GL33.glUniformMatrix4fv((int)this.uCrackVP, (boolean)false, (FloatBuffer)this.vpBuf);
        }
        if (this.uCrackTex != -1) {
            GL33.glUniform1i((int)this.uCrackTex, 0);
        }
        if (this.uCrackCol != -1) {
            GL33.glUniform3f((int)this.uCrackCol, 0.055f, 0.050f, 0.058f);
        }
        if (this.uCrackA != -1) {
            GL33.glUniform1f((int)this.uCrackA, 0.34f + 0.52f * pr);   // 越接近破坏，裂纹越实
        }
        GL33.glActiveTexture((int)33984);
        GL33.glBindTexture((int)3553, this.atlasTex);
        GL33.glBindVertexArray((int)this.crackVAO);
        GL33.glBindBuffer((int)34962, (int)this.crackVBO);
        GL33.glBufferSubData((int)34962, (long)0L, (FloatBuffer)this.crackBuf);
        GL33.glDrawArrays((int)4, (int)0, (int)(this.crackBuf.remaining() / 5));
        GL33.glBindVertexArray((int)0);
        GL33.glDepthMask((boolean)true);
        GL33.glDisable((int)3042);
    }

    /**
     * 水下全屏叠加层（见 {@link #initUnderwaterOverlay()}）。画序与世界一致：世界之上、HUD/菜单之下。
     * 深度测试关、深度写关（纯背景叠加），混合 SRC_ALPHA。
     */
    private void drawUnderwaterOverlay() {
        if (this.underAmt <= 0.002f || this.underShader == 0) return;
        GL33.glDisable(GL33.GL_DEPTH_TEST);
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
        GL33.glUseProgram((int)this.underShader);
        if (this.uUndAmt != -1) GL33.glUniform1f((int)this.uUndAmt, this.underAmt);
        if (this.uUndTime != -1) GL33.glUniform1f((int)this.uUndTime, (float)this.time);
        if (this.uUndRes != -1) GL33.glUniform2f((int)this.uUndRes, (float)this.W, (float)this.H);
        if (this.uUndCol != -1) {
            GL33.glUniform3f((int)this.uUndCol, this.dcWater[0], this.dcWater[1], this.dcWater[2]);
        }
        if (this.uUndNoise != -1 && this.noiseTex != -1) {
            GL33.glActiveTexture(33984);
            GL33.glBindTexture(3553, this.noiseTex);
        }
        GL33.glBindVertexArray((int)this.skyVAO);
        GL33.glDrawArrays((int)4, (int)0, (int)3);   // 全屏三角（复用 skyVAO）
        GL33.glBindVertexArray((int)0);
        GL33.glDisable((int)3042);
        GL33.glEnable((int)2929);
    }

    private void drawRainOverlay() {
        if (this.rainSmooth <= 0.01f) return;
        this.hudBuf.clear();
        // 几何在 core.content.RainField（纯函数，门禁可断言）—— 这里只负责"把它画出来"。
        float[] st = this.rainStreak;
        for (int i = 0; i < core.content.RainField.STREAKS; i++) {
            core.content.RainField.streak(i, this.W, this.H, this.time, this.rainSmooth, st);
            this.addRect2D(this.hudBuf, st[0], st[1], 1.6f, st[2], 0.74f, 0.82f, 0.94f, st[4]);
            this.addRect2D(this.hudBuf, st[0] + st[3], st[1] + st[2] * 0.35f, 1.3f, st[2] * 0.6f,
                    0.80f, 0.88f, 0.98f, st[5]);
        }
        this.hudBuf.flip();
        GL33.glDisable(GL33.GL_DEPTH_TEST);
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
        GL33.glUseProgram(this.hudShader);
        this.setHudVP(this.IDENTITY);
        GL33.glBindVertexArray(this.hudVAO);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, this.hudVBO);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0L, this.hudBuf);
        GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, this.hudBuf.remaining() / HUD_VERT_FLOATS);
        GL33.glBindVertexArray(0);
        GL33.glDisable(GL33.GL_BLEND);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        this.hudBuf.clear();
    }

    /**
     * 小地图（内容扩张第一项）：右上角 {24×24} 格地表俯视图 + 中心玩家标记。
     *
     * <p><b>纯渲染层，零漂移</b>：采样模型在 {@code core.content.MapField}（纯读 mat/surfaceY，
     * 不写状态、不碰 RNG），这里只负责画。颜色直接用方块本色 → 图上颜色与世界的方块一致。
     *
     * <p><b>为什么放在 core</b>：与 {@code ParticleSim}/{@code RainField} 同一理由 ——
     * 放 core 才能在无头环境断言（否则它只有有 GL 的机器才看得见，写错了没人知道）。
     *
     * <p><b>开关</b>：{@code N} 键切换；HUD 关闭或菜单打开时不画（与其它 HUD 元素同规矩）。
     */
    private void drawMinimap() {
        if (!this.minimapOn || this.menu.isOpen() || !this.menu.showHud) return;
        final int px = core.content.MapField.PIXELS;
        final int cell = core.content.MapField.CELL_PX;
        // P3 布局引擎：小地图（TR 锚 = 右边距 9 / 顶边距 9，含 5px 内嵌；原式 x0 = W-px-14 → 面板 x = W-px-19）
        UiLayout.Rect pMap = UiLayout.fit("hud-minimap", UiLayout.TR, 9f, 9f,
                (float)px + 10f, (float)px + 10f, this.W, this.H);
        float x0 = pMap.x + 5f;
        float y0 = pMap.y + 5f;
        // 分帧 + 脏标记（Terraria EngineState 泛化）：地表俯视图在玩家不动时几秒才变一次，
        // 没必要每帧重采 576 格。只在「玩家跨格」或「世界被编辑」时置脏，且脏了也要等本帧轮到
        // 小地图槽位才真正重采 —— 采样始终在渲染前完成，不丢帧（旧值一直可用，最多晚一帧）。
        int pcx = (int)Math.floor(this.sim.player.x), pcz = (int)Math.floor(this.sim.player.z);
        if (pcx != this.minimapLastX || pcz != this.minimapLastZ) {
            this.minimapDirty = true;
            this.minimapLastX = pcx; this.minimapLastZ = pcz;
        }
        if (this.minimapDirty && this.slicer.isDue(1)) {
            core.content.MapField.sample(this.sim.world, pcx, pcz, this.minimapCells);
            this.minimapDirty = false;
        }
        this.hudBuf.clear();
        this.panel(this.hudBuf, pMap.x, pMap.y, pMap.w, pMap.h,
                0.03f, 0.05f, 0.07f, 0.60f);
        for (int cz = 0; cz < core.content.MapField.CELLS; cz++) {
            for (int cx = 0; cx < core.content.MapField.CELLS; cx++) {
                int c = this.minimapCells[cz * core.content.MapField.CELLS + cx];
                this.addRect2D(this.hudBuf, x0 + (float)(cx * cell), y0 + (float)(cz * cell),
                        (float)cell, (float)cell,
                        (float)((c >> 16) & 0xFF) / 255.0f, (float)((c >> 8) & 0xFF) / 255.0f,
                        (float)(c & 0xFF) / 255.0f, 0.94f);
            }
        }
        // 玩家：中心菱形（与罗盘的玩家/村心标记同形，读起来一致）
        this.addDiamond2D(this.hudBuf, x0 + (float)px * 0.5f, y0 + (float)px * 0.5f, 4.5f,
                1.0f, 0.94f, 0.36f, 1.0f);
        // 四边描边
        this.addRect2D(this.hudBuf, x0 - 1.0f, y0 - 1.0f, (float)px + 2.0f, 1.5f, 0.55f, 0.62f, 0.72f, 0.9f);
        this.addRect2D(this.hudBuf, x0 - 1.0f, y0 + (float)px, (float)px + 2.0f, 1.5f, 0.55f, 0.62f, 0.72f, 0.9f);
        this.addRect2D(this.hudBuf, x0 - 1.0f, y0 - 1.0f, 1.5f, (float)px + 2.0f, 0.55f, 0.62f, 0.72f, 0.9f);
        this.addRect2D(this.hudBuf, x0 + (float)px, y0 - 1.0f, 1.5f, (float)px + 2.0f, 0.55f, 0.62f, 0.72f, 0.9f);
        this.hudBuf.flip();
        GL33.glDisable(GL33.GL_DEPTH_TEST);
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
        GL33.glUseProgram(this.hudShader);
        this.setHudVP(this.IDENTITY);
        GL33.glBindVertexArray(this.hudVAO);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, this.hudVBO);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0L, this.hudBuf);
        GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, this.hudBuf.remaining() / HUD_VERT_FLOATS);
        GL33.glBindVertexArray(0);
        GL33.glDisable(GL33.GL_BLEND);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        this.hudBuf.clear();
    }

    private void drawFocusOverlay() {
        hudBuf.clear();
        addRect2D(hudBuf, 0, 0, W, H, 0f, 0f, 0f, 0.5f);
        if (CjkFont.available()) {
            String m1 = "窗口未聚焦 · 已自动暂停";
            String m2 = "点击游戏窗口继续（H 切 HUD · F7 困境重生）";
            float w1 = CjkFont.width(m1, 1.1f), w2 = CjkFont.width(m2, 0.85f);
            HudText.cjk(hudBuf, (W - w1) / 2f, H / 2f - 26, 1.1f, 1f, 0.95f, 0.7f, 1f, m1, W, H);
            HudText.cjk(hudBuf, (W - w2) / 2f, H / 2f + 14, 0.85f, 0.9f, 0.95f, 1f, 0.95f, m2, W, H);
        }
        hudBuf.flip();
        GL33.glDisable(GL33.GL_DEPTH_TEST);
        GL33.glEnable(GL33.GL_BLEND);
        GL33.glBlendFunc(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA);
        GL33.glUseProgram(this.hudShader);
        this.setHudVP(this.IDENTITY);
        GL33.glBindVertexArray(this.hudVAO);
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, this.hudVBO);
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0L, this.hudBuf);
        GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, this.hudBuf.remaining() / HUD_VERT_FLOATS);
        GL33.glBindVertexArray(0);
        GL33.glDisable(GL33.GL_BLEND);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        hudBuf.clear();
    }

    private void drawHudOffHint() {
        this.hudBuf.clear();
        if (this.debugHud) {
            this.drawDebugPanel();
        }
        if (CjkFont.available()) {
            String string = "HUD \u5df2\u5173\u95ed \u00b7 \u518d\u6309 H \u5f00\u542f \u00b7 ESC \u83dc\u5355\u53ef\u7528 \u00b7 F7 \u56f0\u5883\u8131\u56f0";
            float f = CjkFont.width(string, 1.0f);
            float fPulse = 0.75f + 0.25f * (float)Math.sin(this.time * 4.0);
            HudText.cjk(this.hudBuf, ((float)this.W - f) / 2.0f, (float)this.H / 2.0f + 40.0f, 1.0f, 1.0f, 0.9f, 0.5f, fPulse, string, this.W, this.H);
        } else {
            HudText.ascii(this.hudBuf, (float)this.W / 2.0f - 190.0f, (float)this.H / 2.0f + 40.0f, 1.1f, 1.0f, 0.9f, 0.5f, 0.9f, "HUD OFF  (ESC > SETTINGS > HUD > LEFT/RIGHT to restore)", this.W, this.H);
        }
        this.hudBuf.flip();
        GL33.glDisable((int)2929);
        GL33.glEnable((int)3042);
        GL33.glBlendFunc((int)770, (int)771);
        GL33.glUseProgram((int)this.hudShader);
        this.setHudVP(this.IDENTITY);
        GL33.glBindVertexArray((int)this.hudVAO);
        GL33.glBindBuffer((int)34962, (int)this.hudVBO);
        long hudU0 = java.lang.System.nanoTime();
        GL33.glBufferSubData((int)34962, (long)0L, (FloatBuffer)this.hudBuf);
        GL33.glDrawArrays((int)4, (int)0, (int)(this.hudBuf.remaining() / HUD_VERT_FLOATS));
        this.hudMsUpload = (java.lang.System.nanoTime() - hudU0) / 1000000.0f;
        GL33.glBindVertexArray((int)0);
        GL33.glDisable((int)3042);
        GL33.glEnable((int)2929);
        this.hudBuf.clear();
    }

    private void panel(FloatBuffer floatBuffer, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8) {
        this.addRect2D(floatBuffer, f, f2, f3, f4, f5, f6, f7, f8);
        this.addRect2D(floatBuffer, f, f2, f3, 2.0f, 0.5f, 0.7f, 0.9f, f8 * 0.9f);
        this.addRect2D(floatBuffer, f, f2 + f4 - 2.0f, f3, 2.0f, 0.5f, 0.7f, 0.9f, f8 * 0.9f);
        this.addRect2D(floatBuffer, f, f2, 2.0f, f4, 0.5f, 0.7f, 0.9f, f8 * 0.9f);
        this.addRect2D(floatBuffer, f + f3 - 2.0f, f2, 2.0f, f4, 0.5f, 0.7f, 0.9f, f8 * 0.9f);
    }

    private void drawSkill(FloatBuffer floatBuffer, float f, float f2, String string, boolean bl, boolean bl2, String string2) {
        float ir = bl ? (bl2 ? 0.3f : 0.4f) : 0.25f;
        float ig = bl ? (bl2 ? 0.9f : 0.4f) : 0.25f;
        float ib = bl ? (bl2 ? 0.4f : 0.4f) : 0.25f;
        this.addRect2D(floatBuffer, f, f2 - 2.0f, 12.0f, 12.0f, ir, ig, ib, 1.0f);
        String state = !bl ? ("LOCK " + string2) : (bl2 ? "READY" : "BUSY");
        float tr = !bl ? 0.5f : (bl2 ? 0.4f : 0.9f);
        float tg = !bl ? 0.5f : (bl2 ? 0.9f : 0.4f);
        float tb = !bl ? 0.5f : (bl2 ? 0.4f : 0.4f);
        HudText.ascii(floatBuffer, f + 18.0f, f2, 1.1f, tr, tg, tb, 1.0f, string + "  " + state, this.W, this.H);
    }

    private void clearPlayerBreathingRoom(World world) {
        Player player = this.sim.player;
        int n = (int)Math.floor(player.x - 0.3f);
        int n2 = (int)Math.floor(player.x + 0.3f - 1.0E-4f);
        int n3 = (int)Math.floor(player.z - 0.3f);
        int n4 = (int)Math.floor(player.z + 0.3f - 1.0E-4f);
        int n5 = (int)Math.floor(player.y);
        int n6 = n5 + 5;
        for (int i = n; i <= n2; ++i) {
            for (int j = n3; j <= n4; ++j) {
                for (int k = n5; k <= n6; ++k) {
                    if (!world.inBounds(i, k, j) || world.getBlock(i, k, j) == Blocks.AIR.index) continue;
                    world.editBlock(i, k, j, Blocks.AIR.index);
                }
            }
        }
    }

    private static void extractFrustumPlanes(Matrix4f matrix4f, Vector4f[] vector4fArray) {
        for (int i = 0; i < 6; ++i) {
            matrix4f.frustumPlane(FRUSTUM_PLANE_ORDER[i], vector4fArray[i]);
        }
    }

    private static boolean aabbOutsideFrustum(Vector4f[] vector4fArray, float f, float f2, float f3, float f4, float f5, float f6) {
        for (int i = 0; i < 6; ++i) {
            float f7;
            Vector4f vector4f = vector4fArray[i];
            float f8 = vector4f.x >= 0.0f ? f4 : f;
            float f9 = vector4f.y >= 0.0f ? f5 : f2;
            float f10 = f7 = vector4f.z >= 0.0f ? f6 : f3;
            if (!(vector4f.x * f8 + vector4f.y * f9 + vector4f.z * f7 + vector4f.w < 0.0f)) continue;
            return true;
        }
        return false;
    }

    static boolean air(World world, int n, int n2, int n3) {
        if (n2 >= world.SY) {
            return true;
        }
        if (!world.inBounds(n, n2, n3)) {
            return true;
        }
        return world.mat[n][n2][n3] == Blocks.AIR.index;
    }

    static void emit(FloatBuffer floatBuffer, World world, int n, int n2, int n3, int n4, int n5, int n6, Blocks.Block block, float f) {
        if (block.stairs) {
            // 第十七批（三）台阶：L 形 = **下半盒**（整格范围、高 0..0.5）+ **上半盒**（朝向那半、高 0.5..1）。
            // 两个盒都发全 6 面：盒与盒的贴合处是"同方块同色"的共面重叠 ⇒ 深度测试 LEQUAL + 确定发射序
            // ⇒ 稳定覆盖、不可见。刻意**不**为"按方向裁面"引入逐面判据（那要动 36 处调用点）——
            // 与半砖批同一取舍：过绘无害，少画才是看得见的空洞。
            int d = Facing.horizontal(world.getFacing(n, n2, n3));   // 只读 per-block 朝向 → 零漂移
            emitBox(floatBuffer, world, n, n2, n3, n4, n5, n6, block, f, 0f, 1f, 0f, 0.5f, 0f, 1f);
            float bx0 = 0f, bx1 = 1f, bz0 = 0f, bz1 = 1f;
            if (d == 0) bx0 = 0.5f;          // +X：高半边在 +x
            else if (d == 1) bx1 = 0.5f;     // -X
            else if (d == 4) bz0 = 0.5f;     // +Z
            else bz1 = 0.5f;                 // -Z
            emitBox(floatBuffer, world, n, n2, n3, n4, n5, n6, block, f, bx0, bx1, 0.5f, 1f, bz0, bz1);
            return;
        }
        // 普通方块 = **一个竖直区间** (shapeBase, shapeBase+shapeHeight)：整格即 (0,1) ⇒ 逐字节等价旧行为，
        // 而半砖/上半砖/薄层只是换两个数 —— 这就是第十六/十七批"形状语言"的全部。
        emitBox(floatBuffer, world, n, n2, n3, n4, n5, n6, block, f,
                0f, 1f, block.shapeBase, block.shapeBase + block.shapeHeight, 0f, 1f);
    }

    /**
     * 发射**一个轴对齐盒**中朝向 (n4,n5,n6) 的那一面（4 顶点、索引化）。
     *
     * <p>盒边界以<b>格内 0..1 的小数</b>给出 —— 这是"形状语言"的最小完备表达：整格 {@code (0,1, 0,1, 0,1)}、
     * 半砖、雪层、台阶的两个盒，全都是这一段的实例。满格时它与本方法出现之前<b>逐字节等价</b>。
     */
    private static void emitBox(FloatBuffer floatBuffer, World world, int n, int n2, int n3, int n4, int n5, int n6,
                                Blocks.Block block, float f,
                                float bx0, float bx1, float by0, float by1, float bz0, float bz1) {
        int n7;
        float[][] fArrayArray;
        float f2 = (float)block.r / 255.0f * f;
        float f3 = (float)block.g / 255.0f * f;
        float f4 = (float)block.b / 255.0f * f;
        float f5 = n + bx0;
        float f6 = n + bx1;
        float f7 = n2 + by0;
        float f8 = n2 + by1;
        float f9 = n3 + bz0;
        float f10 = n3 + bz1;
        if (n4 == 1) {
            fArrayArray = new float[][]{{f6, f7, f10}, {f6, f7, f9}, {f6, f8, f9}, {f6, f8, f10}};
            n7 = 0;
        } else if (n4 == -1) {
            fArrayArray = new float[][]{{f5, f7, f9}, {f5, f7, f10}, {f5, f8, f10}, {f5, f8, f9}};
            n7 = 1;
        } else if (n5 == 1) {
            fArrayArray = new float[][]{{f5, f8, f10}, {f6, f8, f10}, {f6, f8, f9}, {f5, f8, f9}};
            n7 = 2;
        } else if (n5 == -1) {
            fArrayArray = new float[][]{{f5, f7, f9}, {f6, f7, f9}, {f6, f7, f10}, {f5, f7, f10}};
            n7 = 3;
        } else if (n6 == 1) {
            fArrayArray = new float[][]{{f5, f7, f10}, {f6, f7, f10}, {f6, f8, f10}, {f5, f8, f10}};
            n7 = 4;
        } else {
            fArrayArray = new float[][]{{f6, f7, f9}, {f5, f7, f9}, {f5, f8, f9}, {f6, f8, f9}};
            n7 = 5;
        }
        float f11 = block.wind ? 1.0f : 0.0f;
        // 索引化（PERF）：每四边形只发 4 个顶点。旧实现发 6 个（V0,V1,V2,V0,V2,V3）；
        // 现在由索引缓冲 {0,1,2, 0,2,3} 复现<b>同样的两个三角形</b> → 零视觉变化、顶点数 −33%。
        for (int n8 = 0; n8 < 4; n8++) {
            Game.putV(floatBuffer, world, n7, fArrayArray[n8], block, f, n4, n5, n6, f11, n, n2, n3, Chunk.CU[n8][0], Chunk.CU[n8][1]);
        }
    }

    private static void putV(FloatBuffer floatBuffer, World world, int n, float[] fArray, Blocks.Block block, float f, float f2, float f3, float f4, float f5, int n2, int n3, int n4, float f6, float f7) {
        // 第三十八批修正：逐块色差必须锚定**绝对世界坐标**。n2/n3/n4 是窗口本地坐标，窗口每次平移后
        // 同一世界块的本地坐标会变 → 复用网格与新网格颜色不一致（平移闪烁 bug，由 MeshShiftCheck 证实）。
        // Y 不随窗口平移，故世界 Y == 本地 Y。
        int gwX = world.windowOriginCX() * World.CHUNK + n2;
        int gwZ = world.windowOriginCZ() * World.CHUNK + n4;
        float f8;
        float f9;
        float f10;
        float f11 = Chunk.vertexAO(world, fArray[0], fArray[1], fArray[2], n);
        // 第七批（第十批收敛）：导线按连通掩码、可定向方块朝的那面走端口 tile。
        // 走 Chunk.tileForFace 这个**唯一解析口** —— 与贪婪路径共用，避免"两条 emit 路径走散"。
        // 数据来源是渲染层的只读采样（邻居是不是电路元件 + 本格朝向）→ 不改世界、不进指纹。
        int n5 = Chunk.tileForFace(world, block, n, n2, n3, n4);
        float f12 = TextureAtlas.uOf(n5, f6);
        // 第二十批：**侧壁**的竖直 UV 跟随实心区间（否则半砖的侧面贴图被压扁成半格）。
        // 顶/底面（法线竖直）保持完整 UV —— 从上看/下看时贴图本就该完整。
        // 判据与数学都在 core 的 ShapeUV（纯函数 ⇒ 可门禁断言）；满格时它恒等 ⇒ 逐字节等价。
        float f13 = TextureAtlas.vOf(n5, (f3 == 0f) ? core.world.ShapeUV.sideV(block, f7) : f7);
        // 第十九批的"整张 UV 转 90°"已在第二十五批**移除**：走向改由**形状 tile 库**表达
        // （Chunk.tileForFace 按四邻掩码从 16 张里选）⇒ 直线 / 弯 / 十字 / T 字都能正确表达，
        // 而 UV 转只能表达"直线沿哪个轴"。⚠️ 与 Chunk.putV 同构，两条 emit 路径不许走散。
        if (TextureAtlas.vertexColored(block)) {
            float[] fArray2 = Chunk.vegTint(block, gwX, n3, gwZ);
            f10 = fArray2[0] * f * f11;
            f9 = fArray2[1] * f * f11;
            f8 = fArray2[2] * f * f11;
        } else {
            float f14;
            f10 = f14 = f * f11;
            f9 = f14;
            f8 = f14;
        }
        // 泰拉瑞亚缺口①：自动拼贴边缝暗化（纯渲染，零漂移；非拼贴方块 seam=1）。
        int na = Autotile.normalAxis(f2, f3, f4);
        float seam = Autotile.seam(world, block, n2, n3, n4, na, f6, f7);
        f10 *= seam; f9 *= seam; f8 *= seam;
        // 方块级自发差异（见 VoxelVariety）：现实里没有两块完全一样的石头 —— 缺了这层，
        // 一整片石砌地面读起来就是"瓷砖墙"（同材质逐像素完全相同 = 复制粘贴感）。
        // 明度 ±6% 打破重复；色相 ±5% 给出"青石/黄石混砌"的观感。
        // 只对不透明方块做色相：水/玻璃的顶点色是"材质本色"（承担颜色），
        // 逐块改色相会把连续的水面切成一块块色斑。
        float vb = VoxelVariety.bright(gwX, n3, gwZ);
        if (!block.isFullShape()) {
            // 第十六批：非满格方块（台阶/半砖）**不做逐格随机色差** —— 与贪婪路径 Chunk.putV 同一条判据
            // （两条 emit 路径不许走散）。硬理由：面剔除判据不知道方向 ⇒ "并排/贴合"的半砖之间会各画一张
            // **共面四边形**；两边若各带 ±6% 随机明度，重叠处就显出一条接缝。关掉 ⇒ 两面色逐位相同 ⇒ 重叠不可见。
            // 半砖本来也不是"整块地面的砖"，不需要这层打散重复感的处理。
        } else if (block.translucent) {
            f10 *= vb; f9 *= vb; f8 *= vb;
        } else {
            float vh = VoxelVariety.hue(gwX, n3, gwZ);
            f10 *= vb * (1f + vh);
            f9 *= vb;
            f8 *= vb * (1f - vh);
        }
        // 第五批·红石可视化（第九批补接到本路径 + 随强度变化）：通电的红石元件顶点色提亮。
        // ⚠️ 第六批把这段只写进了 Chunk.putV —— 那是**贪婪网格**专用方法（USE_GREEDY=false 时不会被调用），
        //    于是默认路径下导线只泛光、并没有提亮。这里用与 Chunk 相同的谓词/系数接上，
        //    并让亮度随信号强度变化（0..15 级 → 1.0~1.9×）。纯渲染层（meta 不进 hashState）→ 零漂移。
        float litK = Chunk.redstoneBrightness(world, block.index, n2, n3, n4);
        if (litK != 1f) { f10 *= litK; f9 *= litK; f8 *= litK; }
        // 第十二批：装水的坩埚偏蓝提亮（与 Chunk.putV 用同一谓词 —— 两条 emit 路径不许走散）
        if (Chunk.isFilledCauldron(world, block.index, n2, n3, n4)) {
            f10 *= 1.25f; f9 *= 1.35f; f8 *= 1.90f;
        }
        // 步骤 4（Noita edge_files）· 路线 A「叠加层」（与 Chunk.putV 同构；默认关闭时位级 no-op）。
        float edgeInfl = EdgeAtlas.edgeInfluence(world, block, n2, n3, n4, na, f6, f7);        float eU = 0f, eV = 0f;
        if (edgeInfl > 0f) {
            float sc = EdgeAtlas.scale(edgeInfl);
            f10 *= sc; f9 *= sc; f8 *= sc;
            float[] et = Chunk.edgeUV(world, block, n2, n3, n4, na, f6, f7);
            eU = et[0]; eV = et[1];
        }
        // 2026-09-30：块光改走 Chunk.blockLight（2×2 格平滑）—— 与贪婪路径共用**唯一定义处**，
        // 否则两条 emit 路径的观感会走散（本项目已因此踩过两次坑）。
        float f15 = Chunk.blockLight(world, fArray[0], fArray[1], fArray[2], f2, f3, f4, Chunk.SMOOTH_LIGHT);
        floatBuffer.put(fArray[0]).put(fArray[1]).put(fArray[2]).put(f10).put(f9).put(f8).put(f2).put(f3).put(f4).put(f5).put(f12).put(f13).put(f15)
                   .put(eU).put(eV).put(edgeInfl);
    }

    private void cleanup() {
        if (this.chunks != null) {
            for (int i = 0; i < this.chunkX; ++i) {
                for (int j = 0; j < this.chunkZ; ++j) {
                    this.chunks[i][j].dispose();
                }
            }
        }
        GL33.glDeleteProgram((int)this.shader);
        if (this.atlasTex != -1) {
            GL33.glDeleteTextures((int)this.atlasTex);
        }
        if (this.bloomFBO != -1) { GL33.glDeleteFramebuffers(this.bloomFBO); GL33.glDeleteTextures(this.bloomTex); }
        if (this.brightFBO != -1) { GL33.glDeleteFramebuffers(this.brightFBO); GL33.glDeleteTextures(this.brightTex); }
        if (this.blurFBO != -1) { GL33.glDeleteFramebuffers(this.blurFBO); GL33.glDeleteTextures(this.blurTex); }
        if (this.diag != null) {
            try {
                this.diag.println("render_diag.log  end");
                this.diag.flush();
                this.diag.close();
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        Callbacks.glfwFreeCallbacks((long)this.window);
        GLFW.glfwDestroyWindow((long)this.window);
        GLFW.glfwTerminate();
        // 第三十二批 A2：收掉网格 worker 池。⚠️ 必须在 **GL 上下文销毁之后**且**进程退出之前**做唯一一次 ——
        // 队列/完成队列里可能还压着未消费的 MeshData，那是 memAlloc 的**非 GC 内存**，
        // 不 release 就是泄漏（进程退出虽会回收，但长期运行的开发流程里它会累积）。
        if (this.meshBuilder != null) { this.meshBuilder.shutdown(); this.meshBuilder = null; }
    }

    // ==================================================================
    // 调试沙盒：F6 / --debug 开启后“什么都有”，便于先测试内容再正式发布。
    // 全部是实体层/渲染层（能力 + 技能 + 无敌），不进 hashState，默认关 → 零漂移。
    // ==================================================================
    private void toggleDebugSandbox() {
        DEBUG = !DEBUG;
        if (DEBUG) grantDebugSandbox();
        else this.sim.player.godMode = false;   // 关沙盒即撤无敌；能力/技能保留（无害），重开即初始态
        this.bannerText = DEBUG ? "DEBUG SANDBOX ON" : "DEBUG SANDBOX OFF";
        this.bannerTimer = 2.4f;
        this.sfx(DEBUG ? Sfx.UNLOCK : Sfx.DENY);
    }

    private void grantDebugSandbox() {
        Player p = this.sim.player;
        for (String ab : new String[]{"GLIDE", "DASH", "BOMB", "FLINT", "AQUA", "THUNDER"})
            if (!p.abilities.contains(ab)) p.grantAbility(ab);
        for (String sk : new String[]{"flame_burst", "gale_step", "ember_harvest"})
            if (!this.sim.world.hasSkill(sk)) this.sim.world.addSkill(sk);
        p.godMode = true;
        this.refreshLearnedSkills();
        this.toastText = "GRANTED: 6 abilities + 3 skills + GOD";
        this.toastTimer = 2.4f;
    }

    // ==================================================================
    // 平台化 2026-09-13：内容层 / 玩法层 → 表现层的【唯一入口】
    // Game 实现 EffectSink，于是 core 侧完全不需要认识渲染器/音频/UI。
    // ==================================================================

    /** 粒子：按内容定义发射（def 为 null 时退回默认 spark）。 */
    @Override public void particle(com.google.gson.JsonObject def, float x, float y, float z) {
        this.particles.spawn(def != null ? def : this.content.get("particles", "spark"), x, y, z);
    }

    /** 复合特效：展开 emitters 逐个发射，并承接 fx 自带的屏震。 */
    @Override public void fx(com.google.gson.JsonObject def, float x, float y, float z) {
        if (def == null) return;
        com.google.gson.JsonElement em = def.get("emitters");
        if (em != null && em.isJsonArray()) {
            for (com.google.gson.JsonElement el : em.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                com.google.gson.JsonObject eo = el.getAsJsonObject();
                if (!eo.has("particle")) continue;
                // B 批：emitter 的 at（相对锚点偏移）与 delay（分层时序）真驱动。
                float[] off = fxOffset(eo);
                float delay = eo.has("delay") ? eo.get("delay").getAsFloat() : 0f;
                // 死键落地（§2.1）：emitter 的 burst 标记位 → 纯径向炸开（去上抛偏置）。
                boolean eburst = eo.has("burst") && eo.get("burst").getAsBoolean();
                if (delay > 0f) {
                    this.pendingFx.add(new PendingEmit(eo, x + off[0], y + off[1], z + off[2], delay, eburst));
                } else {
                    this.particles.spawn(this.content.get("particles", eo.get("particle").getAsString()),
                            x + off[0], y + off[1], z + off[2], eburst);
                }
            }
        }
        // B 批：复合特效自带的音效此前被忽略（fx/*.json 的 "sfx" 字段）
        if (def.has("sfx")) this.sfx(def.get("sfx").getAsString());
        if (def.has("shake")) this.shake(def.get("shake").getAsFloat());
    }

    /** emitter 的 {@code at} 偏移 {@code [dx,dy,dz]}（缺省 0）。 */
    private static float[] fxOffset(com.google.gson.JsonObject eo) {
        com.google.gson.JsonElement at = eo.get("at");
        if (at != null && at.isJsonArray() && at.getAsJsonArray().size() >= 3) {
            com.google.gson.JsonArray a = at.getAsJsonArray();
            return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        }
        return new float[]{0f, 0f, 0f};
    }

    /** 推进延迟发射队列（每帧；用未缩放 dt —— 特效是表现层，不跟 gameSpeed）。 */
    private void advancePendingFx(float dt) {
        for (int i = this.pendingFx.size() - 1; i >= 0; i--) {
            PendingEmit p = this.pendingFx.get(i);
            p.ttl -= dt;
            if (p.ttl <= 0f) {
                this.pendingFx.remove(i);
                if (p.emitter.has("particle")) {
                    this.particles.spawn(this.content.get("particles", p.emitter.get("particle").getAsString()),
                            p.x, p.y, p.z, p.burst);
                }
            }
        }
    }

    /** 施法瞬间的手持物挥动量（0..1，随 skillCastAnim 衰减）—— anim 键的视觉落地。 */
    private float skillCastSwing() {
        return this.skillCastAnim > 0f ? (this.skillCastAnim / 0.30f) : 0f;
    }

    /**
     * 音效：内容里的音效名 → 既有 {@link core.audio.Sfx}。
     *
     * <p>查表优先（{@code SFX_BY_NAME}），未命中再试大写枚举名；都不中则<b>静默忽略</b>
     * —— 音效缺失不该打断玩法（但名字写错可在 CONTENT 门禁的命名约定里被审）。
     */
    @Override public void sfx(String id) {
        if (id == null || id.isEmpty()) return;
        core.audio.Sfx out = SFX_BY_NAME.get(id);
        if (out == null) {
            try { out = core.audio.Sfx.valueOf(id.trim().toUpperCase()); }
            catch (RuntimeException ignored) { }
        }
        if (out != null) this.sfx(out);
    }

    /**
     * 屏震：累加式创伤模型（幅度取大者），由 {@link #updateShake} 每帧衰减。
     *
     * <p>纯渲染：只偏移视图矩阵的 eye 位置，<b>不改 camPos、不写世界</b>，因此零漂移。
     */
    @Override public void shake(float amp) {
        if (amp > this.shakeAmp) this.shakeAmp = Math.min(1.0f, amp);
    }

    /** 对白 / 横幅：直接复用既有横幅通道（内容层可配剧情文本）。 */
    @Override public void banner(String text) {
        this.bannerText = text;
        this.bannerTimer = 3.0f;
    }

    /**
     * 模拟类指令出口 —— 内容/玩法层改世界的<b>唯一通道</b>（2026-09-13 P2）。
     *
     * <p>已接通：{@code DAMAGE}（走 {@link Player#hitBeast} 同一套结算，不重写伤害规则）、
     * {@code HEAL}、{@code TELEPORT}（沿朝向 sweep，带碰撞）、{@code KNOCKBACK}、
     * {@code SUMMON}、{@code SET_BLOCK}（唯一会进指纹的指令 —— 因为它真的改世界）、
     * {@code GRANT_ITEM}（E 批：塞进背包；背包满则丢弃余量并提示，不卡死流程）。
     */
    @Override public void simulate(String type, String id, float amount, float x, float y, float z) {
        if (type == null) return;
        core.world.World w = this.sim.world;
        core.world.Player pl = this.sim.player;

        if ("DAMAGE".equals(type)) {
            core.world.Beast t = pl.nearestBeast(w, 5.0f);
            if (t != null) pl.hitBeast(w, t, Math.max(1, Math.round(amount)), "content");
        } else if ("HEAL".equals(type)) {
            pl.hp = Math.min(pl.maxHp, pl.hp + Math.max(1, Math.round(amount)));
        } else if ("TELEPORT".equals(type)) {
            float ya = (float) Math.toRadians(this.yaw);
            pl.sweepStep(w, -(float) Math.sin(ya) * amount, 0.0f, -(float) Math.cos(ya) * amount);
        } else if ("KNOCKBACK".equals(type)) {
            core.world.Beast t = pl.nearestBeast(w, 6.0f);
            if (t != null) {
                float dx = t.x - x, dz = t.z - z;
                float len = (float) Math.hypot(dx, dz);
                if (len > 1e-3f) { t.x += dx / len * amount; t.z += dz / len * amount; }
            }
        } else if ("SUMMON".equals(type)) {
            int bt = 0;
            try { if (id != null && !id.isEmpty()) bt = Integer.parseInt(id); }
            catch (RuntimeException ignored) { }
            if (bt < 0 || bt >= core.world.Beast.N_TYPES) bt = 0;
            w.beasts.add(core.world.Beast.make(bt, x, y + 1.0f, z));
        } else if ("GRANT_SKILL".equals(type)) {
            // 解锁标记写入 World.skills（在 hashState 之内 → 解锁"有意地"改变指纹）
            if (id != null && !id.isEmpty()) w.addSkill(id);
        } else if ("GRANT_ITEM".equals(type)) {
            // E 批：把物品直接塞进背包（内容/玩法层改玩家持有的唯一通道）。
            // 背包满 → 余量丢弃并提示（守恒：不凭空消失，但也不卡死流程）。
            if (this.inventory != null && id != null && !id.isEmpty()) {
                int n = Math.max(1, Math.round(amount));
                int leftover = this.inventory.add(id, n);
                GameLog.log("INV", "GRANT_ITEM " + id + " x" + n + (leftover > 0 ? " (inv full, " + leftover + " dropped)" : ""));
                if (leftover == 0) { this.toastText = "+" + n + " " + id; this.toastTimer = 0.9f; }
                else { this.toastText = "INVENTORY FULL"; this.toastTimer = 1.0f; this.sfx(Sfx.DENY); }
            }
        } else if ("SET_BLOCK".equals(type)) {
            if (id == null || !core.world.Blocks.has(id)) return;
            w.setBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
                    core.world.Blocks.index(id));
        } else if ("APPLY_BUFF".equals(type)) {
            // A 批（技能链）：状态施加。此前这条指令落到本方法的空分支被**静默丢弃**，
            // 所以 buffs/burning.json 的持续伤害从未生效。
            // 目标 = (x,y,z) 附近的敌人；而该坐标正是 cast 的 targeting 选出来的目标位置，
            // 于是「谁中招」与 targeting 声明严格一致。定义交给 core 的 BuffSystem 执行（可门禁）。
            com.google.gson.JsonObject bdef = this.content.get("buffs", id);
            if (bdef == null) return;
            core.world.Beast bt = nearestBeastAt(w, x, y, z, 3.0f);
            if (bt != null) this.sim.buffs.apply(bdef, id, bt);
        }
    }

    /** 找 (x,y,z) 半径内最近的敌兵（APPLY_BUFF 的目标锚定；渲染层辅助，不影响确定性）。 */
    private static core.world.Beast nearestBeastAt(core.world.World w, float x, float y, float z, float radius) {
        core.world.Beast best = null;
        float bd = radius * radius;
        for (int i = 0; i < w.beasts.size(); i++) {
            core.world.Beast b = w.beasts.get(i);
            if (b == null || b.hp <= 0) continue;
            float dx = b.x - x, dy = b.y - y, dz = b.z - z;
            float q = dx * dx + dy * dy + dz * dz;
            if (q <= bd) { bd = q; best = b; }
        }
        return best;
    }

    /**
     * 已学技能 = {@code world.skills} ∩ 内容层 skills 表，按 id 升序。
     * 升序是刻意的：O 键轮换顺序必须跨会话/跨端一致（否则同名不同序 = 联机两端选到不同技能）。
     */
    private void refreshLearnedSkills() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String sid : this.content.of("skills").keySet()) {
            if (this.sim.world.hasSkill(sid)) out.add(sid);
        }
        java.util.Collections.sort(out);
        this.learnedSkills = out;
        if (this.skillIdx >= out.size()) this.skillIdx = 0;
    }

    /**
     * P0②：假击退位移曲线。输入 {@code kbProg}（1=刚被击中，0=已归位），输出 ∈[0,1] 的位移系数。
     *
     * <p>形状：先"被打退"（前 45% 进度内冲到峰值 1.0，快），再"站稳回弹"（后 55% 缓回 0）。
     * 用 {@code sin} 拼两段，保证 C0 连续（无跳变）。纯函数、零 RNG、不读不写仿真状态。
     */
    private static float kbCurve(float prog) {
        if (prog <= 0f) return 0f;
        float p = Math.min(1f, prog);
        return (float) Math.sin(Math.PI * p * p);
    }

    /**
     * P0④：第三人称避障 —— 从眼位沿相机反方向（{@code (dx,dy,dz)}，单位向量）扫掠，
     * 返回"不撞进地形"的最大机位距离（≤ {@code maxDist}）。
     *
     * <p>做法：把 0..maxDist 采样 12 步，遇到第一个 solid 方块就退到前一步 - 0.3 格。
     * 纯读取世界方块（不写、不变世界），零漂移。
     */
    private float cameraCollideDist(float ex, float ey, float ez, float dx, float dy, float dz, float maxDist) {
        final int N = 12;
        float lastOk = maxDist;
        for (int i = 1; i <= N; i++) {
            float d = maxDist * i / N;
            int bx = (int) Math.floor(ex + dx * d);
            int by = (int) Math.floor(ey + dy * d);
            int bz = (int) Math.floor(ez + dz * d);
            if (this.sim.world.inBounds(bx, by, bz)
                    && Blocks.byIndex(this.sim.world.getBlock(bx, by, bz)).solid) {
                return Math.max(0.6f, lastOk - 0.3f);   // 退到命中点前，且不至于贴脸
            }
            lastOk = d;
        }
        return maxDist;
    }

    /** 屏震推进 + 偏移计算（sin 噪声，确定性、平滑、无分配）。 */
    private void updateShake(float dt) {        if (this.shakeAmp > 0.0f) {
            this.shakeAmp = Math.max(0.0f, this.shakeAmp - dt * 2.4f);
            this.shakePhase += dt * 46.0f;
        }
        float amp = this.shakeAmp * this.shakeAmp;              // 平方 → 更像衰减的震感
        if (amp > 1.0E-4f) {
            this.shakeOffX = (float) Math.sin(this.shakePhase) * amp * 0.34f;
            this.shakeOffY = (float) Math.sin(this.shakePhase * 1.63f + 1.1f) * amp * 0.26f;
        } else {
            this.shakeOffX = 0.0f;
            this.shakeOffY = 0.0f;
        }
    }

}
