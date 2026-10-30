package io.github.railgun19457.velocityauth.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.IdentityResolver;
import io.github.railgun19457.velocityauth.auth.ProfileFactory;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.util.Addresses;

import java.util.Locale;
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
     * 正版名冲突（仅在线模式）：先来后到（ADR-8）。
     */
    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!plugin.enabled() || !plugin.server().getConfiguration().isOnlineMode()) {
            return;
        }
        VelocityAuthConfig cfg = plugin.config();
        if (!cfg.auth().premium().enabled()
                || cfg.auth().premium().nameConflict() != VelocityAuthConfig.Premium.NameConflict.FIRST_COME) {
            return;
        }
        String nameLower = event.getUsername().toLowerCase(Locale.ROOT);
        if (plugin.authService().cache().existsByNameLower(nameLower)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    plugin.lang().msg("auth.name-occupied", "name", event.getUsername())));
        }
    }

    /**
     * 必须在 Floodgate 之后执行（Floodgate 也监听该事件注入 profile），否则 profile 重写会被覆盖（R2）。
     * <p>
     * {@code priority = Short.MIN_VALUE + 1} 等价于旧的 {@code PostOrder.LAST}（Velocity 的事件优先级
     * 越大越先执行，见 {@code VelocityEventManager.POST_ORDER_MAP}），确保我们最后改写。
     */
    @Subscribe(priority = Short.MIN_VALUE + 1)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        VelocityAuthConfig cfg = plugin.config();
        String connectionName = event.getUsername();
        String ip = Addresses.ipOf(event.getConnection().getRemoteAddress());
        GameProfile original = event.getGameProfile();
        IdentityResolver.Resolved identity = plugin.identities().resolve(original.getId(), connectionName);
        boolean premium = event.isOnlineMode() && cfg.auth().premium().enabled();

        // 1) 正版玩家：保留真实 profile（ADR-8 / DESIGN 4.6）
        if (premium) {
            boolean keepRealProfile = cfg.auth().premium().keepRealUuid();
            // 刚完成 /login（require-password 或 keep-real-uuid=false 之后的这次重连）→ 消费重连绑定
            Optional<Account> pending = plugin.authService().consumePendingBinding(identity.key(), ip);
            if (pending.isPresent()) {
                stageAuthenticated(event, original, connectionName, ip, pending.get(), keepRealProfile);
                return;
            }
            // require-password=true：正版玩家仍需 /login，先保留真实 profile 并标记为未认证
            if (cfg.auth().premium().requirePassword()) {
                registry.stage(original.getId(), new AuthSession(connectionName, ip));
                return;
            }
            Account account = plugin.authService().cache()
                    .byNameLower(connectionName.toLowerCase(Locale.ROOT)).orElse(null);
            // 该账号存在但未满足「注册后必须绑定」的要求 → 不自动登录，走登录服提示（R: 堵住正版绕过）
            if (account != null && !plugin.authService().bindingSatisfied(account)) {
                registry.stage(original.getId(), new AuthSession(connectionName, ip));
                return;
            }
            if (account != null && keepRealProfile) {
                plugin.authService().cache().indexIdentity(identity.kind(), identity.identifier(), account.id());
            }
            if (account != null && !keepRealProfile) {
                // keep-real-uuid=false：正版也重写为账号规范身份
                stageAuthenticated(event, original, connectionName, ip, account, false);
                return;
            }
            AuthSession session = new AuthSession(connectionName, ip);
            session.authenticatePremium(account);
            registry.stage(original.getId(), session);
            return;
        }

        // 2) 命中重连绑定 → 重写 profile 为账号规范身份
        Optional<Account> pending = plugin.authService().consumePendingBinding(identity.key(), ip);
        if (pending.isPresent()) {
            stageAuthenticated(event, original, connectionName, ip, pending.get(), false);
            return;
        }

        // 3) 会话保持命中 → 直接认证
        Optional<Account> restored = plugin.authService().resolveSessionLogin(identity, ip);
        if (restored.isPresent()) {
            stageAuthenticated(event, original, connectionName, ip, restored.get(), false);
            return;
        }

        // 4) 未认证：不改 profile，标记为未认证
        registry.stage(original.getId(), new AuthSession(connectionName, ip));
    }

    /**
     * 标记会话为「账号身份」并登记。
     *
     * @param keepRealProfile true 时保留原始 profile（正版玩家 keep-real-uuid），否则重写为账号 name/uuid
     */
    private void stageAuthenticated(GameProfileRequestEvent event, GameProfile original, String connectionName,
                                    String ip, Account account, boolean keepRealProfile) {
        AuthSession session = new AuthSession(connectionName, ip);
        session.authenticate(account);
        if (keepRealProfile) {
            registry.stage(original.getId(), session);
        } else {
            event.setGameProfile(ProfileFactory.profileFor(account, original.getProperties()));
            registry.stage(account.uuid(), session);
        }
    }
}
