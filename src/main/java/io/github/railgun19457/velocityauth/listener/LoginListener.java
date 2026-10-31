package io.github.railgun19457.velocityauth.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.IdentityResolver;
import io.github.railgun19457.velocityauth.auth.ProfileFactory;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.util.Addresses;

import java.util.Optional;

/**
 * 登录握手阶段：profile 重写与未认证标记。
 * <p>
 * {@code GameProfileRequestEvent} 是唯一能改 name/UUID 的公开 API，也是唯一身份改写点。
 */
public final class LoginListener {

    private final VelocityAuthPlugin plugin;
    private final AuthSessionRegistry registry;

    public LoginListener(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /**
     * 必须在 Floodgate 之后执行（Floodgate 也监听该事件注入 profile），否则 profile 重写会被覆盖（R2）。
     * <p>
     * {@code priority = Short.MIN_VALUE + 1} 等价于旧的 {@code PostOrder.LAST}（Velocity 的事件优先级
     * 越大越先执行，见 {@code VelocityEventManager.POST_ORDER_MAP}），确保我们最后改写。
     * <p>
     * 正版与离线连接一视同仁：身份一律重写为账号规范身份，不做正版分支。
     */
    @Subscribe(priority = Short.MIN_VALUE + 1)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        String connectionName = event.getUsername();
        String ip = Addresses.ipOf(event.getConnection().getRemoteAddress());
        GameProfile original = event.getGameProfile();
        IdentityResolver.Resolved identity = plugin.identities().resolve(original.getId(), connectionName);

        // 1) 命中重连绑定 → 重写 profile 为账号规范身份
        Optional<Account> pending = plugin.authService().consumePendingBinding(identity.key(), ip);
        if (pending.isPresent()) {
            stageAuthenticated(event, original, connectionName, ip, pending.get());
            return;
        }

        // 2) 会话保持命中 → 直接认证
        Optional<Account> restored = plugin.authService().resolveSessionLogin(identity, ip);
        if (restored.isPresent()) {
            stageAuthenticated(event, original, connectionName, ip, restored.get());
            return;
        }

        // 3) 未认证：不改 profile，标记为未认证
        registry.stage(original.getId(), new AuthSession(connectionName, ip));
    }

    /** 标记会话为「账号身份」并登记，profile 重写为账号规范身份。 */
    private void stageAuthenticated(GameProfileRequestEvent event, GameProfile original, String connectionName,
                                    String ip, Account account) {
        AuthSession session = new AuthSession(connectionName, ip);
        session.authenticate(account);
        event.setGameProfile(ProfileFactory.profileFor(account, original.getProperties()));
        registry.stage(account.uuid(), session);
    }
}
