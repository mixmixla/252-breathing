package core.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * L4 身体系统：NPC/玩家的身体属性与生理状态（移植自 agents/body.py）。
 *
 * 设计纪律（零漂移）：
 *  - 所有生理推进均为纯函数式增量，无随机源；
 *  - 随机只来自调用方传入的 SeededRNG（本类不使用），绝不读 world.rng 主状态或 fxRng；
 *  - 同种子 + 同输入 → 同状态，可断言复现。
 *
 * 字段镜像 body.py：hp/max_hp、hunger/max_hunger、thirst/max_thirst、stamina/max_stamina
 * （体力即“能量”）、comfort_low/high、hunger_rate/thirst_rate；并按作业要求补 float x/y/z
 * 与可选 inventory（材料名 -> 质量），与 NPC.inventory 同构。
 */
public final class Body {

    public float hp, maxHp;
    public float hunger, maxHunger;
    public float thirst, maxThirst;
    public float stamina, maxStamina;        // 体力 / 能量
    public float comfortLow, comfortHigh;
    public float hungerRate, thirstRate;

    // 位置（Java 版世界为 3D；Python 版坐标挂在宿主 NPC 上，此处按作业要求置于 Body）
    public float x, y, z;

    // 可选库存（材料名 -> 质量），与 NPC.inventory 同构
    public final Map<String, Float> inventory = new HashMap<String, Float>();

    public Body() {
        this(100f, 100f, 0f, 100f, 0f, 100f, 100f, 100f, 5f, 45f, 0.3f, 0.25f);
    }

    public Body(float hp, float maxHp, float hunger, float maxHunger, float thirst, float maxThirst,
                float stamina, float maxStamina, float comfortLow, float comfortHigh,
                float hungerRate, float thirstRate) {
        this.maxHp = Math.max(maxHp, hp);
        this.hp = hp;
        this.hunger = hunger;
        this.maxHunger = maxHunger;
        this.thirst = thirst;
        this.maxThirst = maxThirst;
        this.maxStamina = Math.max(maxStamina, stamina);
        this.stamina = stamina;
        this.comfortLow = comfortLow;
        this.comfortHigh = comfortHigh;
        this.hungerRate = hungerRate;
        this.thirstRate = thirstRate;
    }

    /**
     * 每 tick 生理推进：饥饿 / 口渴增长 + 体力恢复（确定性，不含温度伤害）。
     * clock 为时钟膨胀系数（默认真实时钟 1.0；本地钟越慢，代谢增长越慢），非法值视为 1.0。
     */
    public void advance(float clock) {
        float c = (clock <= 0f || clock > 1f) ? 1f : clock;
        this.hunger = Math.min(this.maxHunger, this.hunger + this.hungerRate * c);
        this.thirst = Math.min(this.maxThirst, this.thirst + this.thirstRate * c);
        // 体力缓慢恢复（常量，与 Python stamina_regen=1.5 同义，可被外部覆盖）
        this.stamina = Math.min(this.maxStamina, this.stamina + 1.5f);
    }

    /**
     * 温度 / 灼烧伤害：ambientTemp 高于 hurtTemp 则掉血（确定性）。返回是否被高温灼烧。
     * 与 body.py 的 update 中温度分支同构；burnMult 为元素克制灼烧倍率（如精灵畏火 1.5）。
     */
    public boolean applyEnvironment(float ambientTemp, float hurtTemp, float hurtPerTick,
                                     float coldHp, float hotHp, float burnMult) {
        boolean burned = false;
        if (ambientTemp >= hurtTemp) {
            this.hp -= hurtPerTick * burnMult;
            burned = true;
        }
        if (ambientTemp < this.comfortLow) this.hp -= coldHp;
        else if (ambientTemp > this.comfortHigh) this.hp -= hotHp;
        return burned;
    }

    /** 极端饥饿 / 脱水虚弱掉血（确定性，无随机）。 */
    public void applyStarvation(float starveHp, float dehydrateHp) {
        if (this.hunger >= this.maxHunger) this.hp -= starveHp;
        if (this.thirst >= this.maxThirst) this.hp -= dehydrateHp;
    }

    public boolean dead() { return this.hp <= 0f; }

    /** 动作消耗体力；不足返回 false（动作受限）。 */
    public boolean spendStamina(float amount) {
        if (this.stamina < amount) return false;
        this.stamina -= amount;
        return true;
    }

    public void eat(float amount) { this.hunger = Math.max(0f, this.hunger - amount); }
    public void drink(float amount) { this.thirst = Math.max(0f, this.thirst - amount); }
}
