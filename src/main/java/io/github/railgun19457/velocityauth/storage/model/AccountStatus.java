package io.github.railgun19457.velocityauth.storage.model;

/** 账号状态。 */
public enum AccountStatus {
    ACTIVE,
    LOCKED;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static AccountStatus fromDb(String value) {
        if (value == null) {
            return ACTIVE;
        }
        return switch (value.toLowerCase()) {
            case "locked" -> LOCKED;
            default -> ACTIVE;
        };
    }
}
