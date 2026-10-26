package io.github.railgun19457.velocityauth.storage.model;

/**
 * 邮箱 / QQ 绑定记录。
 */
public record Binding(
        long id,
        long accountId,
        BindingType type,
        String identifier,
        long verifiedAt,
        long createdAt
) {
}
