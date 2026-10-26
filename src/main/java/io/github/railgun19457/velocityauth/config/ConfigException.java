package io.github.railgun19457.velocityauth.config;

/**
 * 配置加载/校验失败时抛出，携带可读的字段路径，便于服主定位问题。
 */
public final class ConfigException extends RuntimeException {

    private final String fieldPath;

    public ConfigException(String fieldPath, String message) {
        super(fieldPath == null || fieldPath.isEmpty() ? message : "[" + fieldPath + "] " + message);
        this.fieldPath = fieldPath;
    }

    public ConfigException(String fieldPath, String message, Throwable cause) {
        super(fieldPath == null || fieldPath.isEmpty() ? message : "[" + fieldPath + "] " + message, cause);
        this.fieldPath = fieldPath;
    }

    public String fieldPath() {
        return fieldPath;
    }
}
