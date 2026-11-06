package io.github.railgun19457.velocityauth.storage.model;

/**
 * 审计日志条目。
 *
 * @param id        自增主键
 * @param at        发生时间（unix 秒）
 * @param accountId 目标账号 id（可空，例如未命中账号的失败登录）
 * @param action    动作标识
 * @param ip        来源 IP（可空）
 * @param detail    附加说明（可空）
 * @param actor     操作者（可空；玩家名 / console / bot 侧管理员标识）
 * @param source    来源：{@link #SOURCE_COMMAND} / {@link #SOURCE_BOT} / {@link #SOURCE_PLUGIN}（可空）
 * @param clientId  发起操作的 bot 客户端 id（仅 {@code source=bot} 时有值）
 */
public record AuditEntry(
        long id,
        long at,
        Long accountId,
        String action,
        String ip,
        String detail,
        String actor,
        String source,
        String clientId
) {

    /** 兼容旧调用：只有基础字段的条目。 */
    public AuditEntry(long id, long at, Long accountId, String action, String ip, String detail) {
        this(id, at, accountId, action, ip, detail, null, null, null);
    }

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

    /** 管理端动作（经 bot 管理接口触发时使用）。 */
    public static final String ADMIN_LOCK = "admin_lock";
    public static final String ADMIN_UNLOCK = "admin_unlock";
    public static final String ADMIN_RESET_PASSWORD = "admin_reset_password";
    public static final String ADMIN_DELETE = "admin_delete";
    public static final String ADMIN_UNBIND = "admin_unbind";
    public static final String ADMIN_KICK = "admin_kick";
    public static final String ADMIN_CLEAR_SESSION = "admin_clear_session";

    /** 来源：游戏内命令。 */
    public static final String SOURCE_COMMAND = "command";
    /** 来源：bot 管理接口。 */
    public static final String SOURCE_BOT = "bot";
    /** 来源：插件内部（注册 / 登录 / 解绑等自动动作）。 */
    public static final String SOURCE_PLUGIN = "plugin";
}
