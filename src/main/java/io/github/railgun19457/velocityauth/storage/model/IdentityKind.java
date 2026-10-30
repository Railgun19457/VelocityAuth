package io.github.railgun19457.velocityauth.storage.model;

import java.util.Locale;

/** 身份类型。 */
public enum IdentityKind {
    /** Java 版：连接名小写。 */
    JAVA,
    /** 基岩版：Floodgate UUID。 */
    BEDROCK;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static IdentityKind fromDb(String value) {
        if (value == null) {
            return JAVA;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "bedrock" -> BEDROCK;
            default -> JAVA;
        };
    }
}
