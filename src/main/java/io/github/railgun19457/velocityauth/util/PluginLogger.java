package io.github.railgun19457.velocityauth.util;

import org.slf4j.Logger;

/**
 * 统一日志封装：统一前缀，并保证敏感信息（密码/验证码/配对码）绝不落日志。
 */
public final class PluginLogger {

    private static final String PREFIX = "[VelocityAuth] ";

    private final Logger delegate;

    public PluginLogger(Logger delegate) {
        this.delegate = delegate;
    }

    public Logger delegate() {
        return delegate;
    }

    public void info(String message, Object... args) {
        delegate.info(PREFIX + message, args);
    }

    public void warn(String message, Object... args) {
        delegate.warn(PREFIX + message, args);
    }

    public void error(String message, Object... args) {
        delegate.error(PREFIX + message, args);
    }

    public void error(String message, Throwable throwable) {
        delegate.error(PREFIX + message, throwable);
    }

    public void debug(String message, Object... args) {
        delegate.debug(PREFIX + message, args);
    }
}
