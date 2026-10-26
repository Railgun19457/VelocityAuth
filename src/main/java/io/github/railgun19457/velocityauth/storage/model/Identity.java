package io.github.railgun19457.velocityauth.storage.model;

/**
 * 连接名/基岩身份 → 账号 的映射记录。
 */
public record Identity(
        long id,
        long accountId,
        IdentityKind kind,
        String identifier,
        long firstSeen,
        long lastSeen
) {
}
