package core.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 内核层 → 表现层 / 模拟层的<b>唯一出口</b>。
 *
 * <p>内容层内核（{@code core.content}）不许依赖渲染器、音频、UI——它只把「发生了什么」喊出去，
 * 由上层决定「怎么表现」。因此本接口是 core 与 render 之间的单向阀，core 保持零渲染依赖。
 *
 * <p>实现方（Game 层）负责：粒子/特效转成顶点、音效转成 {@code Sfx}、屏震转成相机抖动、
 * 对白转成 HUD 横幅。模拟类指令（DAMAGE / SET_BLOCK / TELEPORT …）走 {@link #simulate}，
 * 由上层按游戏语义落地。
 *
 * <p>无头环境（门禁）传 {@code null} 即可——{@link EffectQueue} 会跳过转发但仍计数，
 * 于是调度层的性质可以在没有渲染器的情况下被完整断言。
 */
public interface EffectSink {

    /** 粒子的定义（含发射器参数）——渲染层据此实例化粒子。 */
    void particle(JsonObject def, float x, float y, float z);

    /** 复合特效的定义（含多发射器 / 音效 / 屏震）。 */
    void fx(JsonObject def, float x, float y, float z);

    /** 音效名（对应 {@code core.audio.Sfx} 的命名）。 */
    void sfx(String id);

    /** 屏震（幅度 0..1）。 */
    void shake(float amp);

    /** 对白 / 横幅文本（HUD）。 */
    void banner(String text);

    /**
     * 模拟类指令出口：DAMAGE / HEAL / KNOCKBACK / TELEPORT / SET_BLOCK / SUMMON / GRANT_ITEM。
     *
     * @param type   指令名
     * @param id     关联内容 id（可空）
     * @param amount 数值（伤害/治疗量等）
     * @param x,y,z  效果原点
     */
    void simulate(String type, String id, float amount, float x, float y, float z);

    /** 空实现：门禁与无渲染环境使用（只计数、不表现）。 */
    EffectSink NONE = new EffectSink() {
        @Override public void particle(JsonObject def, float x, float y, float z) {}
        @Override public void fx(JsonObject def, float x, float y, float z) {}
        @Override public void sfx(String id) {}
        @Override public void shake(float amp) {}
        @Override public void banner(String text) {}
        @Override public void simulate(String type, String id, float amount, float x, float y, float z) {}
    };
}
