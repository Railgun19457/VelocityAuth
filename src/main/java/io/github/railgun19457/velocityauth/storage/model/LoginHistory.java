package io.github.railgun19457.velocityauth.storage.model;

/**
 * 登录历史条目（成功 / 失败 / 登出）。
 *
 * @param id        自增主键
 * @param accountId 账号 id；账号不存在（如失败登录用了未注册名）时为 {@code null}
 * @param at        发生时间（unix 秒）
 * @param ip        来源 IP（可空）
 * @param result    结果：{@link #SUCCESS} / {@link #FAILED} / {@link #LOGOUT}
 * @param identity  登录使用的连接名 / 基岩身份标识（可空）
 * @param detail    附加说明（如失败原因、命令来源；可空）
 */
public record LoginHistory(
        long id,
        Long accountId,
        long at,
        String ip,
        String result,
        String identity,
        String detail
) {

    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String LOGOUT = "logout";
}
