package io.github.railgun19457.velocityauth.storage.model;

/**
 * 绑定历史条目：某渠道标识的绑定、解绑、随账号删除。
 * <p>
 * 与 {@code bindings} 不同，本表**不随绑定删除或账号删除而清除**，
 * 因此可在解绑 / 注销后追溯某个标识曾绑定过哪些账号。
 *
 * @param id         自增主键
 * @param accountId  当时的账号 id（可空；账号已删除时保留原 id）
 * @param type       渠道 id（provider id）
 * @param identifier 渠道标识
 * @param action     事件：{@link #BIND} / {@link #UNBIND} / {@link #DELETE}
 * @param at         发生时间（unix 秒）
 * @param ip         来源 IP（可空）
 */
public record BindingHistory(
        long id,
        Long accountId,
        String type,
        String identifier,
        String action,
        long at,
        String ip
) {

    public static final String BIND = "bind";
    public static final String UNBIND = "unbind";
    public static final String DELETE = "delete";

    /** 是否为「释放」事件（解绑 / 注销），用于冷却判定。 */
    public boolean released() {
        return UNBIND.equals(action) || DELETE.equals(action);
    }
}
