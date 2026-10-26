package io.github.railgun19457.velocityauth.storage.model;

/** 绑定类型。 */
public enum BindingType {
    EMAIL,
    QQ;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static BindingType fromDb(String value) {
        if (value == null) {
            return EMAIL;
        }
        return switch (value.toLowerCase()) {
            case "qq" -> QQ;
            default -> EMAIL;
        };
    }

    public static BindingType fromName(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase()) {
            case "email", "mail" -> EMAIL;
            case "qq" -> QQ;
            default -> null;
        };
    }
}
