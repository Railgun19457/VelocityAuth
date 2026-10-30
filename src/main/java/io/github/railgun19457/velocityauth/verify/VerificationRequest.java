package io.github.railgun19457.velocityauth.verify;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 发起一次验证的请求参数。
 *
 * @param purpose        验证目的
 * @param provider       渠道 id（provider id，如 {@code email} / {@code qq}）
 * @param playerUuid     发起者
 * @param accountName    待注册/找回的账号名（可空）
 * @param connectionName 本次连接名
 * @param ip             发起者 IP
 * @param target         目标标识（email 渠道为邮箱地址；社交渠道为空）
 * @param data           完成验证后要写入的数据（如已哈希的密码）
 * @param onDispatch     派发结果回调
 */
public record VerificationRequest(
        VerificationPurpose purpose,
        String provider,
        UUID playerUuid,
        String accountName,
        String connectionName,
        String ip,
        String target,
        Map<String, String> data,
        Consumer<VerificationDispatch> onDispatch
) {
    public static Builder builder(VerificationPurpose purpose, String provider, UUID playerUuid) {
        return new Builder(purpose, provider, playerUuid);
    }

    /** 便捷构建器。 */
    public static final class Builder {
        private final VerificationPurpose purpose;
        private final String provider;
        private final UUID playerUuid;
        private String accountName;
        private String connectionName;
        private String ip;
        private String target;
        private Map<String, String> data = Map.of();
        private Consumer<VerificationDispatch> onDispatch = dispatch -> {
        };

        private Builder(VerificationPurpose purpose, String provider, UUID playerUuid) {
            this.purpose = purpose;
            this.provider = provider;
            this.playerUuid = playerUuid;
        }

        public Builder accountName(String value) {
            this.accountName = value;
            return this;
        }

        public Builder connectionName(String value) {
            this.connectionName = value;
            return this;
        }

        public Builder ip(String value) {
            this.ip = value;
            return this;
        }

        public Builder target(String value) {
            this.target = value;
            return this;
        }

        public Builder data(Map<String, String> value) {
            this.data = value == null ? Map.of() : value;
            return this;
        }

        public Builder onDispatch(Consumer<VerificationDispatch> value) {
            this.onDispatch = value == null ? dispatch -> {
            } : value;
            return this;
        }

        public VerificationRequest build() {
            return new VerificationRequest(purpose, provider, playerUuid, accountName, connectionName, ip,
                    target, data, onDispatch);
        }
    }
}
