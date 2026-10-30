package io.github.railgun19457.velocityauth.verify;

/**
 * 验证目的（DESIGN 5.2）。
 * <p>
 * 每个目的带一个 {@link #key()}，用于文案里的「操作占位符」{@code {action}}：
 * 邮件模板与游戏内提示据此区分这次验证是注册、绑定还是找回密码。
 */
public enum VerificationPurpose {
    /** 注册门槛。 */
    REGISTER("register"),
    /** 绑定邮箱。 */
    BIND_EMAIL("bind-email"),
    /** 绑定 QQ。 */
    BIND_QQ("bind-qq"),
    /** 找回密码。 */
    RECOVER("recover"),
    /** 解绑（需先通过一次验证）。 */
    UNBIND("unbind"),
    /** 注销账号（需先通过一次验证）。 */
    DELETE_ACCOUNT("delete-account");

    private final String key;

    VerificationPurpose(String key) {
        this.key = key;
    }

    /** 占位符取值用的短标识，如 {@code bind-email}。 */
    public String key() {
        return key;
    }

    /** 操作名称在语言文件中的 key：{@code verification.actions.<key>}。 */
    public String actionLangKey() {
        return "verification.actions." + key;
    }
}
