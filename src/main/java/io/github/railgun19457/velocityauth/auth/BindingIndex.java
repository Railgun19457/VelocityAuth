package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.storage.BindingRepository;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号「已绑定渠道」内存索引：accountId → 渠道 id 集合。
 * <p>
 * 存在的唯一理由：{@code GameProfileRequestEvent} 是 @AwaitingEvent，代理会**同步阻塞**登录握手
 * 等待其返回（见 {@link AuthService} 中 sessionCache 的注释），因此
 * 「该账号是否满足注册后必须绑定的要求」必须在内存里回答，不能查库。
 * <p>
 * 只存「绑定了哪些渠道」，不存邮箱 / 账号本身。
 * <p>
 * <b>一致性</b>：所有改动绑定的地方都必须同步这里 ——
 * 启动预热、绑定、解绑、删号（{@link AuthService#forgetAccount}）。
 */
public final class BindingIndex {

    private final BindingRepository bindings;
    private final PluginLogger log;
    private final Map<Long, Set<String>> masks = new ConcurrentHashMap<>();

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

    public void put(long accountId, String providerId) {
        masks.computeIfAbsent(accountId, id -> ConcurrentHashMap.newKeySet()).add(providerId);
    }

    public void remove(long accountId, String providerId) {
        masks.computeIfPresent(accountId, (id, set) -> {
            set.remove(providerId);
            return set.isEmpty() ? null : set;
        });
    }

    /** 账号被删除时整体移除。 */
    public void removeAccount(long accountId) {
        masks.remove(accountId);
    }

    /** 账号是否已绑定 {@code required} 中的任一渠道；{@code required} 为空时恒为 true。 */
    public boolean satisfies(long accountId, Collection<String> required) {
        if (required.isEmpty()) {
            return true;
        }
        Set<String> bound = masks.get(accountId);
        if (bound == null) {
            return false;
        }
        for (String providerId : required) {
            if (bound.contains(providerId)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return masks.size();
    }
}
