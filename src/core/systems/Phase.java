package core.systems;

/**
 * 系统职责域（System Phase）—— 给 92 个涌现系统一个<b>可读的身份标签</b>。
 *
 * <p><b>重要设计决策</b>：phase <b>不参与执行排序</b>。系统的执行顺序永远是
 * <b>注册顺序</b>——因为顺序决定演化结果，重排 = 确定性指纹漂移 = 违反铁律 1。
 *
 * <p>phase 的用途只有三个：
 * <ol>
 *   <li><b>可读性</b>：一个系统是"植被"还是"地质"，看标签就知道，不用读代码；</li>
 *   <li><b>分组开关</b>：玩法预设可以按域关掉一整类系统（如关掉 {@code WEATHER}）；</li>
 *   <li><b>审计</b>：注册表能打印"每个域有哪些系统"，为将来的依赖梳理提供地图。</li>
 * </ol>
 *
 * <p>这也是"系统层平台化"的第一步：先把 92 个系统<b>看清楚</b>，再谈治理。
 */
public enum Phase {

    /** 世界签名：繁荣等全局标量（最先推进）。 */
    SIGNATURE,
    /** 地形与水体：蔓延、生长、流动、沉降。 */
    TERRAIN,
    /** 植被与生态：草树苔藓、花蕨藤、蔓延与腐朽。 */
    VEGETATION,
    /** 气候与气象：雨雪雷电、季节纪元、风沙雾霾。 */
    WEATHER,
    /** 地质与矿物：矿脉、晶洞、熔岩、火山、地震。 */
    GEOLOGY,
    /** 聚落与社会：市集、节庆、道路、城墙、法度、行会。 */
    SOCIETY,
    /** 实体：野兽、祭坛、NPC、社交（不写网格的安全层）。 */
    ENTITY,
    /** 元层：叙事、审判、物质终局。 */
    META;

    /** 人类可读短名（F3 / 报告用）。 */
    public String label() {
        switch (this) {
            case SIGNATURE:  return "signature";
            case TERRAIN:    return "terrain";
            case VEGETATION: return "vegetation";
            case WEATHER:    return "weather";
            case GEOLOGY:    return "geology";
            case SOCIETY:    return "society";
            case ENTITY:     return "entity";
            default:         return "meta";
        }
    }
}
