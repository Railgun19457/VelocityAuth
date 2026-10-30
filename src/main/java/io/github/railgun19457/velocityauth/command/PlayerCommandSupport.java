package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.IdentityResolver;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.util.Addresses;

import java.util.Optional;

/**
 * 玩家命令公共基类：统一的玩家校验、认证态校验与提示。
 */
public abstract class PlayerCommandSupport implements SimpleCommand {

    protected final VelocityAuthPlugin plugin;
    protected final AuthSessionRegistry registry;

    protected PlayerCommandSupport(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** 要求执行者是玩家，否则提示并返回 null。 */
    protected Player requirePlayer(Invocation invocation) {
        CommandSource source = invocation.source();
        if (source instanceof Player player) {
            return player;
        }
        source.sendMessage(plugin.lang().msg("generic.player-only"));
        return null;
    }

    protected AuthSession sessionOf(Player player) {
        return registry.get(player).orElse(null);
    }

    /** 已认证则提示并返回 false。 */
    protected boolean requireUnauthenticated(Player player) {
        AuthSession session = sessionOf(player);
        if (session != null && session.authenticated()) {
            player.sendMessage(plugin.lang().msg("generic.already-authenticated"));
            return false;
        }
        return true;
    }

    /** 未认证则提示并返回 false。 */
    protected boolean requireAuthenticated(Player player) {
        AuthSession session = sessionOf(player);
        if (session == null || !session.authenticated()) {
            player.sendMessage(plugin.lang().msg("generic.not-authenticated"));
            return false;
        }
        return true;
    }

    /** 本次连接的身份（Java = 连接名小写；基岩 = Floodgate UUID）。 */
    protected io.github.railgun19457.velocityauth.auth.IdentityResolver.Resolved identityOf(Player player) {
        return plugin.identities().resolve(player.getUniqueId(), player.getUsername());
    }

    protected String ipOf(Player player) {
        return Addresses.ipOf(player);
    }

    /**
     * 未认证玩家可用（登录 / 注册 / 找回 / 重置密码）。
     * <p>
     * 同时作为 Velocity 命令节点的 {@code requires} 谓词：返回 false 时该命令**不会**出现在
     * 玩家的补全列表里，也无法执行。
     */
    protected boolean availableWhenUnauthenticated(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            return false;
        }
        AuthSession session = registry.get(player).orElse(null);
        return session == null || !session.authenticated();
    }

    /** 已认证玩家可用（登出 / 改密 / 绑定 / 解绑 / 注销）。 */
    protected boolean availableWhenAuthenticated(Invocation invocation) {
        return invocation.source() instanceof Player player
                && registry.get(player).map(AuthSession::authenticated).orElse(false);
    }

    /** 内置渠道（当插件上下文不可用时作为补全兜底）。 */
    private static final java.util.List<String> DEFAULT_CHANNELS = java.util.List.of("email", "qq");

    /** 在指定参数位补全已配置的绑定渠道 id（email / 各社交渠道）。 */
    protected java.util.List<String> suggestBindingChannel(Invocation invocation, int index) {
        String[] args = invocation.arguments();
        if (args.length != index + 1) {
            return java.util.List.of();
        }
        String prefix = args[index] == null ? "" : args[index].toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> ids = plugin == null
                ? DEFAULT_CHANNELS
                : io.github.railgun19457.velocityauth.auth.Provider.ids(plugin.config());
        return ids.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    /**
     * 提示玩家完成「注册后必须绑定」的渠道（多选一）。
     * <p>
     * 只按配置输出可用的渠道行；未配置要求时不输出任何内容。
     */
    protected void sendBindingRequirement(Player player) {
        java.util.List<String> required = plugin.config().registration().requireBinding();
        if (required.isEmpty()) {
            return;
        }
        for (String providerId : required) {
            player.sendMessage(plugin.lang().msg("command.bind.required", "provider", providerId));
        }
    }

    /**
     * 认证成功收尾。
     * <p>
     * 分两条路：
     * <ul>
     *   <li><b>profile 已是账号身份</b>（基岩已关联账号，或 Java 连接名与账号名完全一致）：
     *       重写是空操作，无需重连 → 就地认证并直接切到目标子服。
     *       <b>此时不会创建一次性重连绑定</b>（没有重连就没有消费方，否则它会留到过期，
     *       变成「同身份 + 同 IP 免密进入」的漏洞）。</li>
     *   <li><b>否则</b>：建立一次性重连绑定并触发重连，由下次握手消费它完成 profile 重写。</li>
     * </ul>
     * 基岩玩家还会先做账号认领（含「一个账号只保留一个基岩身份」的自动换绑）。
     */
    protected void finishAuthAndReconnect(Player player, IdentityResolver.Resolved identity, String ip,
                                          long accountId, net.kyori.adventure.text.Component successMessage) {
        Account account = plugin.authService().cache().byId(accountId).orElse(null);
        boolean seamless = account != null && player.getUniqueId().equals(account.uuid());
        if (!seamless) {
            plugin.authService().createPendingBinding(identity.key(), ip, accountId);
        }
        plugin.authService().claimBedrockAccount(identity, accountId)
                .thenRun(() -> {
                    player.sendMessage(successMessage);
                    if (seamless && enterAuthenticated(player, account)) {
                        return;
                    }
                    if (seamless) {
                        // 免重连失败 → 回退重连，此时才需要一次性绑定
                        plugin.authService().createPendingBinding(identity.key(), ip, accountId);
                    }
                    plugin.reconnectService().reconnect(player, plugin.lang().msg("auth.login-disconnect"));
                });
    }

    /**
     * 就地认证并直接切到目标子服（免重连）。
     *
     * @return 是否成功切服；false 时调用方回退为「重连重写 profile」
     */
    private boolean enterAuthenticated(Player player, Account account) {
        AuthSession session = plugin.sessionRegistry().get(player).orElse(null);
        if (session == null || plugin.routing() == null) {
            return false;
        }
        // 先认证：targetServer 要读「记忆的上次子服」，它取自 session.account()
        session.authenticate(account);
        // 免重连路径不会再触发 PostLogin，这里补记会话保持（否则同名登录无法免密重连）
        plugin.authService().recordSessionAsync(account.id(), session.ip());
        Optional<RegisteredServer> target = plugin.routing().targetServer(session);
        if (target.isEmpty()) {
            return false;
        }
        plugin.log().info("账号 {} 的 profile 已是账号身份，免重连直接进入 {}",
                account.name(), target.get().getServerInfo().getName());
        player.createConnectionRequest(target.get()).fireAndForget();
        return true;
    }

    protected static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
