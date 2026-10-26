package io.github.railgun19457.velocityauth.verify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一验证会话（内存态，不落库，重启即失效 —— DESIGN 5.1）。
 */
public final class VerificationSession {

    private final String id;
    private final VerificationPurpose purpose;
    private final VerificationChannel channel;
    private final String code;
    private final UUID playerUuid;
    private final String accountName;
    private final String connectionName;
    private final String ip;
    private final String target;                 // 邮箱地址 / QQ 号（可空）
    private final Map<String, String> data;
    private final long createdAt;
    private final long expiresAt;
    private final int maxAttempts;
    private final AtomicInteger attempts = new AtomicInteger();

    private volatile boolean finished;
    /** QQ 渠道完成时由 bot 回报的 QQ 号。 */
    private volatile String resolvedValue;

    public VerificationSession(String id, VerificationPurpose purpose, VerificationChannel channel, String code,
                               UUID playerUuid, String accountName, String connectionName, String ip,
                               String target, Map<String, String> data, long createdAt, long expiresAt,
                               int maxAttempts) {
        this.id = id;
        this.purpose = purpose;
        this.channel = channel;
        this.code = code;
        this.playerUuid = playerUuid;
        this.accountName = accountName;
        this.connectionName = connectionName;
        this.ip = ip;
        this.target = target;
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.maxAttempts = maxAttempts;
    }

    public String id() {
        return id;
    }

    public VerificationPurpose purpose() {
        return purpose;
    }

    public VerificationChannel channel() {
        return channel;
    }

    public String code() {
        return code;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String accountName() {
        return accountName;
    }

    public String connectionName() {
        return connectionName;
    }

    public String ip() {
        return ip;
    }

    public String target() {
        return target;
    }

    public String data(String key) {
        return data.get(key);
    }

    public Map<String, String> data() {
        return data;
    }

    public long createdAt() {
        return createdAt;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int attempts() {
        return attempts.get();
    }

    public int remainingAttempts() {
        return Math.max(0, maxAttempts - attempts.get());
    }

    public boolean expired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    public boolean finished() {
        return finished;
    }

    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        // 恒定时间比较，避免通过响应时间猜测验证码
        String expected = code;
        String actual = candidate.trim();
        if (expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= Character.toUpperCase(expected.charAt(i)) ^ Character.toUpperCase(actual.charAt(i));
        }
        return diff == 0;
    }

    /** 记录一次尝试；返回是否仍在允许范围内。 */
    public boolean registerAttempt() {
        return attempts.incrementAndGet() <= maxAttempts;
    }

    public boolean attemptsExceeded() {
        return attempts.get() > maxAttempts;
    }

    public void markFinished() {
        this.finished = true;
    }

    /** 原子地标记结束；已被标记则返回 false（避免重复完成）。 */
    public synchronized boolean markFinishedIfOpen() {
        if (finished) {
            return false;
        }
        finished = true;
        return true;
    }

    /** QQ 渠道完成时由 bot 回报的 QQ 号（未完成时为空）。 */
    public String resolvedValue() {
        return resolvedValue;
    }

    public void resolvedValue(String value) {
        this.resolvedValue = value;
    }

    public long remainingSeconds() {
        return Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000L);
    }
}
