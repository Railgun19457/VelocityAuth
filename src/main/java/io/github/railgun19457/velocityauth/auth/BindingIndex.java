package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.storage.BindingRepository;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号「已绑定渠道」内存索引：accountId → 位掩码（bit0 = email，bit1 = qq）。
 * <p>
 * 存在的唯一理由：{@code GameProfileRequestEvent} 是 @AwaitingEvent，代理会**同步阻塞**登录握手
 * 等待其返回（见 {@link AuthService} 中 sessionCache 的注释），因此
 * 「该账号是否满足注册后必须绑定的要求」必须在内存里回答，不能查库。
 * <p>
 * 只存「有没有绑定」这一个 bit，不存邮箱/QQ 本身（那些只在发码时用，走命令路径查库即可）。
 * 每个账号约 4 字节量级，相对 {@link AccountCache} 已经缓存的账号对象可以忽略不计。
 * <p>
 * <b>一致性</b>：所有改动绑定的地方都必须同步这里 ——
 * 启动预热、绑定、解绑、删号（{@link AuthService#forgetAccount}）。
 */
public final class BindingIndex {

    private static final int EMAIL_BIT = 1;
    private static final int QQ_BIT = 1 << 1;

    private final BindingRepository bindings;
    private final PluginLogger log;
    private final Map<Long, Integer> masks = new ConcurrentHashMap<>();

    public BindingIndex(BindingRepository bindings, PluginLogger log) {
        this.bindings = bindings;
        this.log = log;
    }

    /** 启动时全量预热。 */
    public void load() {
        masks.clear();
        for (Binding binding : bindings.findAll()) {
            put(binding.accountId(), binding.type());
        }
        log.info("绑定索引已预热：{} 个账号有绑定", masks.size());
    }

    public void put(long accountId, BindingType type) {
        masks.merge(accountId, bit(type), (a, b) -> a | b);
    }

    public void remove(long accountId, BindingType type) {
        masks.computeIfPresent(accountId, (id, mask) -> {
            int next = mask & ~bit(type);
            return next == 0 ? null : next;
        });
    }

    /** 账号被删除时整体移除。 */
    public void removeAccount(long accountId) {
        masks.remove(accountId);
    }

    /** 账号是否已绑定 {@code required} 中的任一渠道；{@code required} 为空时恒为 true。 */
    public boolean satisfies(long accountId, List<BindingType> required) {
        if (required.isEmpty()) {
            return true;
        }
        Integer mask = masks.get(accountId);
        if (mask == null) {
            return false;
        }
        for (BindingType type : required) {
            if ((mask & bit(type)) != 0) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return masks.size();
    }

    private static int bit(BindingType type) {
        return switch (type) {
            case EMAIL -> EMAIL_BIT;
            case QQ -> QQ_BIT;
        };
    }
}
