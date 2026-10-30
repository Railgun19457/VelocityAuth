package io.github.railgun19457.velocityauth.storage.model;

/**
 * 审计日志条目。
 */
public record AuditEntry(
        long id,
        long at,
        Long accountId,
        String action,
        String ip,
        String detail
) {
    /** 常用动作常量。 */
    public static final String REGISTER = "register";
    public static final String LOGIN = "login";
    public static final String LOGIN_FAILED = "login_failed";
    public static final String LOGOUT = "logout";
    public static final String BIND = "bind";
    public static final String UNBIND = "unbind";
    public static final String RECOVER = "recover";
    public static final String CHANGE_PASSWORD = "change_password";
    public static final String FORCELOGIN = "forcelogin";
    public static final String SESSION_RESTORE = "session_restore";
    public static final String DELETE_ACCOUNT = "delete_account";
}
