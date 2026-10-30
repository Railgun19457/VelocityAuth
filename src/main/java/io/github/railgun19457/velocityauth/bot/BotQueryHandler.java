package io.github.railgun19457.velocityauth.bot;

import java.util.Map;

/**
 * bot 侧 {@code query.*} 业务消息的处理器（由插件侧注入；未启用时为 {@code null}）。
 */
@FunctionalInterface
public interface BotQueryHandler {

    /**
     * @param type    消息类型，如 {@code query.account}
     * @param payload 已解析的扁平载荷（值均为字符串）
     * @return 处理结果；不得返回 {@code null}
     */
    QueryResponse handle(String type, Map<String, Object> payload);
}
