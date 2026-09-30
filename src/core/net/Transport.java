package core.net;

/**
 * N3：传输抽象 —— 锁步会话**不关心**对端是回环队列、UDP 还是 TCP。
 *
 * <p>这是 `docs/NETPLAY_READINESS.md` §6 决策的直接落地：客户端模型（锁步/状态流）与
 * **权威进程住哪**是两个正交维度 → 会话只依赖本接口，本地服务 / 专用服务器 / 回环测试
 * 共用同一份协议代码。
 *
 * <p><b>语义约定</b>：
 * <ul>
 *   <li>{@link #recv()} 每次返回**一条完整消息**；回环队列与 UDP 数据报天然如此，
 *       TCP 需要一个长度前缀帧器（N3 后续，不影响本层）。</li>
 *   <li>{@link #recv()} **必须非阻塞** —— 无消息返回 {@code null}。会话在主循环里轮询，
 *       绝不允许网络调用阻塞仿真线程。</li>
 *   <li>{@link #send(byte[])} 传入 {@code null}/空数组一律抛 {@link IllegalArgumentException}
 *       —— 静默丢包会把「上层 bug」变成「莫名其妙的卡顿」。</li>
 * </ul>
 */
public interface Transport {

    /** 发送一条完整消息（广播语义由实现决定）。 */
    void send(byte[] msg);

    /** 非阻塞收取一条完整消息；无则返回 {@code null}。 */
    byte[] recv();

    /** 释放底层资源（回环实现为空操作）。 */
    void close();
}
