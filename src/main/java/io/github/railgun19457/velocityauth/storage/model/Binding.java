package io.github.railgun19457.velocityauth.storage.model;

/**
 * 绑定记录：某个账号绑定了一个外部渠道标识（邮箱地址 / 社交平台账号）。
 *
 * @param type      渠道 id（provider id），如 {@code email} / {@code qq} / {@code discord}
 * @param identifier 渠道标识（邮箱地址 / 平台账号）
 */
public record Binding(
        long id,
        long accountId,
        String type,
        String identifier,
        long verifiedAt,
        long createdAt
) {
}
