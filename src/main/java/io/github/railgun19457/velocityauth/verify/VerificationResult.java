package io.github.railgun19457.velocityauth.verify;

/**
 * 一次验证的最终结果。
 */
public record VerificationResult(Status status, VerificationSession session, String reason) {

    public enum Status {
        /** 验证通过。 */
        COMPLETED,
        /** 玩家主动取消。 */
        CANCELLED,
        /** 超时。 */
        EXPIRED,
        /** 尝试次数超限。 */
        TOO_MANY_ATTEMPTS,
        /** 被同一玩家的新会话取代。 */
        REPLACED,
        /** 发起失败或渠道错误。 */
        FAILED
    }

    public boolean completed() {
        return status == Status.COMPLETED;
    }

    public static VerificationResult failed(String reason) {
        return new VerificationResult(Status.FAILED, null, reason);
    }
}
