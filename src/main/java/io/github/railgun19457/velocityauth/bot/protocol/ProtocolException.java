package io.github.railgun19457.velocityauth.bot.protocol;

/**
 * 协议错误（PROTOCOL.md 9）。抛出后应立即关闭连接。
 */
public final class ProtocolException extends RuntimeException {

    private final String code;

    public ProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    // ---- 错误码常量 ----
    public static final String PAIRING_MISSING = "pairing_missing";
    public static final String PAIRING_EXPIRED = "pairing_expired";
    public static final String PAIRING_ATTEMPTS_EXCEEDED = "pairing_attempts_exceeded";
    public static final String SIGNATURE_INVALID = "signature_invalid";
    public static final String KEY_NOT_PAIRED = "key_not_paired";
    public static final String VERSION_UNSUPPORTED = "version_unsupported";
    public static final String DECRYPT_FAILED = "decrypt_failed";
    public static final String REPLAY_DETECTED = "replay_detected";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String INTERNAL_ERROR = "internal_error";
}
