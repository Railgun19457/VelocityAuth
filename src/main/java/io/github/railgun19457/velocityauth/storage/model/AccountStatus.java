package io.github.railgun19457.velocityauth.storage.model;

/** 账号状态。 */
public enum AccountStatus {
    ACTIVE,
    LOCKED,
    /**
     * 已注册但未完成绑定：不能登录，超过 {@code registration.unbound-ttl-minutes}
     * 会被自动清除（见 {@code AuthService#purgeExpired}）。
     */
    PENDING_BINDING;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static AccountStatus fromDb(String value) {
        if (value == null) {
            return ACTIVE;
        }
        return switch (value.toLowerCase()) {
            case "locked" -> LOCKED;
            case "pending_binding" -> PENDING_BINDING;
            default -> ACTIVE;
        };
    }
}
