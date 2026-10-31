package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.PasswordHasher;
import io.github.railgun19457.velocityauth.auth.Provider;
import io.github.railgun19457.velocityauth.bot.KeyStore;
import io.github.railgun19457.velocityauth.bot.PairingService;
import io.github.railgun19457.velocityauth.query.AccountQueryService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.util.Addresses;
import net.kyori.adventure.text.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /velocityauth}（别名 {@code /vauth}）管理命令（DESIGN 9.2）。
 * <p>
 * 权限：{@code velocityauth.admin}；强制登录另有 {@code velocityauth.admin.forcelogin}。
 */
public final class AdminCommand implements SimpleCommand {

    private static final String PERMISSION = "velocityauth.admin";
    private static final String PERMISSION_FORCELOGIN = "velocityauth.admin.forcelogin";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final VelocityAuthPlugin plugin;

    public AdminCommand(VelocityAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(plugin.lang().msg("generic.no-permission"));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            usage(source);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(source);
            case "reload" -> reload(source);
            case "account" -> account(source, args);
            case "sessions" -> sessions(source, args);
            case "forcelogin" -> forcelogin(source, args);
            case "pair" -> pair(source, args);
            case "client" -> client(source, args);
            default -> usage(source);
        }
    }

    private void usage(CommandSource source) {
        source.sendMessage(plugin.lang().msg("generic.usage", "usage",
                "/vauth <status|reload|account|sessions|forcelogin|pair|client> ..."));
    }

    // ------------------------------------------------------------------ status

    private void status(CommandSource source) {
        source.sendMessage(plugin.lang().msg("admin.status.header"));
        source.sendMessage(plugin.lang().msg("admin.status.database", "value",
                plugin.storage().describe() + (plugin.storage().ping() ? "" : " (不可用)")));
        source.sendMessage(plugin.lang().msg("admin.status.smtp", "value",
                plugin.config().smtp().enabled() ? plugin.lang().raw("admin.status.enabled")
                        : plugin.lang().raw("admin.status.disabled")));
        var bot = plugin.botServer();
        Component botStatus = bot == null
                ? plugin.lang().raw("admin.status.disabled")
                : plugin.lang().raw("admin.status.connected")
                        .append(Component.text(" (" + bot.readyCount() + ") "))
                        .append(plugin.lang().raw("admin.status.disconnected"))
                        .append(Component.text(" (" + bot.pendingCount() + ")"));
        source.sendMessage(plugin.lang().msg("admin.status.bot", "value", botStatus));
        source.sendMessage(plugin.lang().msg("admin.status.sessions", "value",
                plugin.sessionRegistry().authenticatedSessions().size()));
        source.sendMessage(plugin.lang().msg("admin.status.pending-verifications", "value",
                plugin.verifications().pendingCount()));
        source.sendMessage(plugin.lang().msg("admin.status.paired-clients", "value",
                plugin.keyStore() == null ? 0 : plugin.keyStore().clients().size()));
        source.sendMessage(plugin.lang().msg("admin.status.floodgate", "value",
                plugin.identities().floodgateAvailable()
                        ? plugin.lang().raw("admin.status.enabled")
                        : plugin.lang().raw("admin.status.disabled")));
        source.sendMessage(plugin.lang().msg("admin.status.bedrock-link", "value",
                plugin.floodgate().linkingAvailable()
                        ? plugin.lang().raw("admin.status.enabled")
                        : plugin.lang().raw("admin.status.disabled")));
    }

    private void reload(CommandSource source) {
        try {
            plugin.reload();
            source.sendMessage(plugin.lang().msg("generic.reloaded"));
        } catch (Exception e) {
            source.sendMessage(plugin.lang().msg("generic.internal-error"));
            plugin.log().error("重载配置失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ account

    private void account(CommandSource source, String[] args) {
        if (args.length < 3) {
            usage(source);
            return;
        }
        String name = args[1];
        String action = args[2].toLowerCase(Locale.ROOT);
        Optional<Account> found = findAccount(name);
        if (found.isEmpty()) {
            source.sendMessage(plugin.lang().msg("admin.account.not-found", "account", name));
            return;
        }
        Account account = found.get();
        String ip = source instanceof Player player ? Addresses.ipOf(player) : "console";
        switch (action) {
            case "info" -> accountInfo(source, account);
            case "setpassword" -> {
                if (args.length < 4) {
                    usage(source);
                    return;
                }
                plugin.authService().forcePassword(account.id(), args[3], ip, AuditEntry.CHANGE_PASSWORD)
                        .thenAccept(result -> source.sendMessage(switch (result.status()) {
                            case SUCCESS -> plugin.lang().msg("admin.account.password-set",
                                    "account", account.name());
                            case PASSWORD_TOO_SHORT -> plugin.lang().msg("command.register.password-too-short",
                                    "min", plugin.config().auth().password().minLength());
                            case PASSWORD_TOO_LONG -> plugin.lang().msg("command.register.password-too-long",
                                    "max", plugin.config().auth().password().maxLength());
                            case PASSWORD_TOO_LONG_BYTES -> plugin.lang().msg(
                                    "command.register.password-too-long-bytes",
                                    "bytes", PasswordHasher.MAX_PASSWORD_BYTES);
                            case NOT_FOUND, WRONG_PASSWORD, SAME_AS_OLD, ERROR ->
                                    plugin.lang().msg("generic.internal-error");
                        }));
            }
            case "unbind" -> {
                if (args.length < 4) {
                    usage(source);
                    return;
                }
                String providerId = args[3].toLowerCase(Locale.ROOT);
                if (!Provider.exists(plugin.config(), providerId)) {
                    usage(source);
                    return;
                }
                plugin.bindings().unbind(account, providerId, ip).thenAccept(outcome ->
                        source.sendMessage(outcome.ok()
                                ? plugin.lang().msg("admin.account.unbound", "account", account.name(),
                                "type", providerId)
                                : plugin.lang().msg("command.unbind.not-bound")));
            }
            case "lock", "unlock" -> {
                boolean lock = action.equals("lock");
                plugin.async().run(() -> {
                    plugin.storage().accounts().updateStatus(account.id(),
                            lock ? AccountStatus.LOCKED : AccountStatus.ACTIVE);
                    plugin.storage().accounts().findById(account.id())
                            .ifPresent(updated -> plugin.authService().cache().put(updated));
                    plugin.storage().audit().log(
                            io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(), account.id(),
                            lock ? "admin_lock" : "admin_unlock", ip, "by=" + sourceName(source));
                    source.sendMessage(lock
                            ? plugin.lang().msg("admin.account.locked", "account", account.name())
                            : plugin.lang().msg("admin.account.unlocked", "account", account.name()));
                });
                if (lock) {
                    kickAccount(account, "账号已被锁定");
                }
            }
            case "delete" -> {
                long accountId = account.id();
                String detail = "account=" + account.name() + " by=" + sourceName(source);
                plugin.authService().deleteAccount(accountId, ip, "admin_delete", detail)
                        .thenAccept(removed -> source.sendMessage(
                                plugin.lang().msg("admin.account.deleted", "account", account.name())));
                kickAccount(account, "账号已被删除");
            }
            default -> usage(source);
        }
    }

    private void accountInfo(CommandSource source, Account account) {
        AccountQueryService.AccountView view = plugin.queries().view(account);
        source.sendMessage(plugin.lang().msg("admin.account.info-header", "account", view.name()));
        source.sendMessage(plugin.lang().msg("admin.account.info-id", "value", view.id()));
        source.sendMessage(plugin.lang().msg("admin.account.info-uuid", "value", view.uuid()));
        source.sendMessage(plugin.lang().msg("admin.account.info-status", "value", view.status()));
        String bindings = view.bindings().stream()
                .map(binding -> binding.type() + "=" + binding.identifier())
                .collect(java.util.stream.Collectors.joining(", "));
        source.sendMessage(plugin.lang().msg("admin.account.info-bindings", "value",
                bindings.isEmpty() ? plugin.lang().raw("admin.account.none") : bindings));
        source.sendMessage(plugin.lang().msg("admin.account.info-last-server", "value",
                view.lastServer() == null ? plugin.lang().raw("admin.account.none") : view.lastServer()));
        source.sendMessage(plugin.lang().msg("admin.account.info-identities", "value",
                view.identities().size()));
        source.sendMessage(plugin.lang().msg("admin.account.info-created", "value",
                TIME_FORMAT.format(Instant.ofEpochSecond(view.createdAt()))));
        source.sendMessage(plugin.lang().msg("admin.account.info-last-login", "value",
                view.lastLoginAt() == null ? plugin.lang().raw("admin.account.none")
                        : TIME_FORMAT.format(Instant.ofEpochSecond(view.lastLoginAt()))
                                + " (" + (view.lastIp() == null ? "?" : view.lastIp()) + ")"));
    }

    private Optional<Account> findAccount(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return plugin.authService().cache().byNameLower(lower)
                .or(() -> plugin.storage().accounts().findByNameLower(lower));
    }

    // ------------------------------------------------------------------ sessions

    private void sessions(CommandSource source, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("list")) {
            source.sendMessage(plugin.lang().msg("admin.sessions.header"));
            List<AuthSession> sessions = plugin.sessionRegistry().authenticatedSessions();
            if (sessions.isEmpty()) {
                source.sendMessage(plugin.lang().msg("admin.sessions.empty"));
                return;
            }
            for (AuthSession session : sessions) {
                source.sendMessage(plugin.lang().msg("admin.sessions.entry",
                        "account", session.account() == null ? "?" : session.account().name(),
                        "ip", session.ip() == null ? "?" : session.ip(),
                        "name", session.connectionName()));
            }
            return;
        }
        if (sub.equals("kick")) {
            if (args.length < 3) {
                usage(source);
                return;
            }
            Optional<Account> found = findAccount(args[2]);
            if (found.isEmpty()) {
                source.sendMessage(plugin.lang().msg("admin.account.not-found", "account", args[2]));
                return;
            }
            int kicked = kickAccount(found.get(), "管理员已将会话踢出");
            source.sendMessage(plugin.lang().msg("admin.sessions.kicked",
                    "account", found.get().name(), "count", kicked));
            return;
        }
        usage(source);
    }

    private int kickAccount(Account account, String reason) {
        int count = 0;
        for (AuthSession session : plugin.sessionRegistry().sessionsOfAccount(account.id())) {
            Player player = session.player();
            if (player != null) {
                player.disconnect(Component.text(reason));
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ forcelogin

    private void forcelogin(CommandSource source, String[] args) {
        if (!source.hasPermission(PERMISSION_FORCELOGIN)) {
            source.sendMessage(plugin.lang().msg("generic.no-permission"));
            return;
        }
        if (!(source instanceof Player player)) {
            source.sendMessage(plugin.lang().msg("generic.player-only"));
            return;
        }
        if (args.length < 2) {
            source.sendMessage(plugin.lang().msg("admin.forcelogin.usage"));
            return;
        }
        Optional<Account> found = findAccount(args[1]);
        if (found.isEmpty()) {
            source.sendMessage(plugin.lang().msg("admin.account.not-found", "account", args[1]));
            return;
        }
        Account target = found.get();
        boolean kick = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--kick")) {
                kick = true;
            }
        }
        final boolean kickExisting = kick;
        if (kick) {
            int kicked = kickAccount(target, "管理员强制登录");
            source.sendMessage(plugin.lang().msg("admin.forcelogin.kicked", "count", kicked));
        }
        var identity = plugin.identities().resolve(player.getUniqueId(), player.getUsername());
        plugin.authService().createPendingBinding(identity.key(), Addresses.ipOf(player), target.id());
        plugin.async().run(() -> plugin.storage().audit().log(
                io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(), target.id(),
                AuditEntry.FORCELOGIN, Addresses.ipOf(player),
                "by=" + player.getUsername() + " kick=" + kickExisting));
        source.sendMessage(plugin.lang().msg("admin.forcelogin.success"));
        plugin.reconnectService().reconnect(player, plugin.lang().msg("auth.login-disconnect"));
    }

    // ------------------------------------------------------------------ pair / client

    private void pair(CommandSource source, String[] args) {
        PairingService pairings = plugin.pairings();
        if (pairings == null) {
            source.sendMessage(plugin.lang().msg("generic.feature-disabled"));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "new";
        switch (sub) {
            case "new" -> {
                var settings = plugin.config().bot().pairing();
                PairingService.Created created = pairings.create(settings.ttlSeconds(), settings.maxAttempts());
                source.sendMessage(plugin.lang().msg("admin.pair.created"));
                source.sendMessage(plugin.lang().msg("admin.pair.created-id", "id", created.pairingId()));
                source.sendMessage(plugin.lang().msg("admin.pair.created-code", "code", created.code()));
                source.sendMessage(plugin.lang().msg("admin.pair.created-ttl", "ttl", created.ttlSeconds()));
                // 配对码绝不写入日志
            }
            case "list" -> {
                source.sendMessage(plugin.lang().msg("admin.pair.list-header"));
                List<PairingService.Pairing> list = pairings.list();
                if (list.isEmpty()) {
                    source.sendMessage(plugin.lang().msg("admin.pair.empty"));
                    return;
                }
                for (PairingService.Pairing pairing : list) {
                    source.sendMessage(plugin.lang().msg("admin.pair.list-entry",
                            "id", pairing.pairingId(),
                            "ttl", pairing.remainingSeconds(),
                            "attempts", pairing.attempts() + "/" + pairing.maxAttempts()));
                }
            }
            case "revoke" -> {
                if (args.length < 3) {
                    usage(source);
                    return;
                }
                source.sendMessage(pairings.revoke(args[2])
                        ? plugin.lang().msg("admin.pair.revoked", "id", args[2])
                        : plugin.lang().msg("admin.pair.not-found", "id", args[2]));
            }
            default -> usage(source);
        }
    }

    private void client(CommandSource source, String[] args) {
        KeyStore keyStore = plugin.keyStore();
        if (keyStore == null) {
            source.sendMessage(plugin.lang().msg("generic.feature-disabled"));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "list" -> {
                source.sendMessage(plugin.lang().msg("admin.client.list-header"));
                List<KeyStore.PairedClient> clients = keyStore.clients();
                if (clients.isEmpty()) {
                    source.sendMessage(plugin.lang().msg("admin.client.empty"));
                    return;
                }
                for (KeyStore.PairedClient client : clients) {
                    source.sendMessage(plugin.lang().msg("admin.client.list-entry",
                            "id", client.clientId(),
                            "fingerprint", client.fingerprint(),
                            "last_seen", client.lastSeen() == 0 ? "-"
                                    : TIME_FORMAT.format(Instant.ofEpochSecond(client.lastSeen()))));
                }
            }
            case "revoke" -> {
                if (args.length < 3) {
                    usage(source);
                    return;
                }
                source.sendMessage(keyStore.revoke(args[2])
                        ? plugin.lang().msg("admin.client.revoked", "id", args[2])
                        : plugin.lang().msg("admin.client.not-found", "id", args[2]));
            }
            default -> usage(source);
        }
    }

    private static String sourceName(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "console";
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // 同时作为命令节点的 requires 谓词：权限不足时该命令不出现在补全里，也无法执行
        return invocation.source().hasPermission(PERMISSION);
    }
}
