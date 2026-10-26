package io.github.railgun19457.velocityauth.storage.model;

/**
 * 会话保持记录（可选功能）。
 */
public record LoginSession(
        long id,
        long accountId,
        String ip,
        long createdAt,
        long expiresAt
) {
    public boolean expired(long now) {
        return now >= expiresAt;
    }
}
