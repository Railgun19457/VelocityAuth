package io.github.railgun19457.velocityauth.verify;

/**
 * 验证发起阶段的派发结果，供调用方渲染提示。
 */
public enum VerificationDispatch {
    /** 邮件已发出。 */
    EMAIL_SENT,
    /** 邮件发送失败。 */
    EMAIL_FAILED,
    /** 邮箱服务未启用/不可用。 */
    EMAIL_UNAVAILABLE,
    /** 已登记到 bot，等待社交渠道发码。 */
    SOCIAL_WATCHING,
    /** 社交渠道 bot 不可用。 */
    SOCIAL_UNAVAILABLE,
    /** 触发限流。 */
    RATE_LIMITED
}
