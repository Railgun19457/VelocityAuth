package io.github.railgun19457.velocityauth.verify;

/**
 * 验证目的（DESIGN 5.2）。
 */
public enum VerificationPurpose {
    /** 注册门槛。 */
    REGISTER,
    /** 绑定邮箱。 */
    BIND_EMAIL,
    /** 绑定 QQ。 */
    BIND_QQ,
    /** 找回密码。 */
    RECOVER,
    /** 解绑（需先通过一次验证）。 */
    UNBIND
}
