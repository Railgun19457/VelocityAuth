package io.github.railgun19457.velocityauth.bot;

import java.util.Map;

/**
 * bot 侧 {@code admin.*} 管理消息的处理器（由插件侧注入；未启用时为 {@code null}）。
 * <p>
 * 授权（已配对 + 已就绪 + 客户端白名单）由 {@link BotServer} 在调用前完成；
 * 实现只负责参数校验、执行写操作与组装响应。
 */
@FunctionalInterface
public interface BotAdminHandler {

    /**
     * @param type     消息类型，如 {@code admin.account.set-status}
     * @param payload  已解析的扁平载荷（值均为字符串）
     * @param clientId 发起请求的已配对客户端 id（仅用于审计）
     * @param remoteIp bot 连接的远端地址（仅用于审计）
     * @return 处理结果；不得返回 {@code null}
     */
    QueryResponse handle(String type, Map<String, Object> payload, String clientId, String remoteIp);
}
