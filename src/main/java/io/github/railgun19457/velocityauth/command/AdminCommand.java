package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.bot.KeyStore;
import io.github.railgun19457.velocityauth.bot.PairingService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
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
        Component qqbot = bot == null
                ? plugin.lang().raw("admin.status.disabled")
                : plugin.lang().raw("admin.status.connected")
                        .append(Component.text(" (" + bot.readyCount() + ") "))
                        .append(plugin.lang().raw("admin.status.disconnected"))
                        .append(Component.text(" (" + bot.pendingCount() + ")"));
        source.sendMessage(plugin.lang().msg("admin.status.qqbot", "value", qqbot));
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
                        .thenAccept(result -> source.sendMessage(result.ok()
                                ? plugin.lang().msg("admin.account.password-set", "account", account.name())
                                : plugin.lang().msg("command.register.password-too-short", "min",
                                plugin.config().auth().password().minLength())));
            }
            case "unbind" -> {
                if (args.length < 4) {
                    usage(source);
                    return;
                }
                BindingType type = BindingType.fromName(args[3]);
                if (type == null) {
                    usage(source);
                    return;
                }
                plugin.bindings().unbind(account, type, ip).thenAccept(outcome ->
                        source.sendMessage(outcome.ok()
                                ? plugin.lang().msg("admin.account.unbound", "account", account.name(),
                                "type", type.dbValue())
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
                plugin.async().run(() -> {
                    plugin.storage().accounts().delete(accountId);
                    plugin.authService().cache().remove(accountId);
                    plugin.storage().audit().log(
                            io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(), null,
                            "admin_delete", ip, "account=" + account.name() + " by=" + sourceName(source));
                    source.sendMessage(plugin.lang().msg("admin.account.deleted", "account", account.name()));
                });
                kickAccount(account, "账号已被删除");
            }
            default -> usage(source);
        }
    }

    private void accountInfo(CommandSource source, Account account) {
        source.sendMessage(plugin.lang().msg("admin.account.info-header", "account", account.name()));
        source.sendMessage(plugin.lang().msg("admin.account.info-id", "value", account.id()));
        source.sendMessage(plugin.lang().msg("admin.account.info-uuid", "value", account.uuid()));
        source.sendMessage(plugin.lang().msg("admin.account.info-status", "value", account.status().dbValue()));
        List<Binding> bindings = plugin.bindings().bindings(account.id());
        source.sendMessage(plugin.lang().msg("admin.account.info-email", "value",
                orNone(bindings.stream().filter(b -> b.type() == BindingType.EMAIL).map(Binding::identifier)
                        .findFirst())));
        source.sendMessage(plugin.lang().msg("admin.account.info-qq", "value",
                orNone(bindings.stream().filter(b -> b.type() == BindingType.QQ).map(Binding::identifier)
                        .findFirst())));
        source.sendMessage(plugin.lang().msg("admin.account.info-last-server", "value",
                account.lastServer() == null ? plugin.lang().raw("admin.account.none") : account.lastServer()));
        source.sendMessage(plugin.lang().msg("admin.account.info-identities", "value",
                plugin.storage().identities().countByAccountId(account.id())));
        source.sendMessage(plugin.lang().msg("admin.account.info-created", "value",
                TIME_FORMAT.format(Instant.ofEpochSecond(account.createdAt()))));
        source.sendMessage(plugin.lang().msg("admin.account.info-last-login", "value",
                account.lastLoginAt() == null ? plugin.lang().raw("admin.account.none")
                        : TIME_FORMAT.format(Instant.ofEpochSecond(account.lastLoginAt()))
                                + " (" + (account.lastIp() == null ? "?" : account.lastIp()) + ")"));
    }

    /** 绑定值缺失时用本地化的「无」组件占位，避免字面量标签。 */
    private Object orNone(Optional<String> value) {
        return value.<Object>map(v -> v).orElseGet(() -> plugin.lang().raw("admin.account.none"));
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
                var settings = plugin.config().qqbot().pairing();
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
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("status", "reload", "account", "sessions", "forcelogin", "pair", "client");
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "account" -> args.length == 3
                    ? List.of("info", "setpassword", "unbind", "lock", "unlock", "delete")
                    : List.of();
            case "sessions" -> args.length == 2 ? List.of("list", "kick") : List.of();
            case "pair" -> args.length == 2 ? List.of("new", "list", "revoke") : List.of();
            case "client" -> args.length == 2 ? List.of("list", "revoke") : List.of();
            default -> List.of();
        };
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // 返回 true 让命令始终可执行，权限不足时由 execute 给出明确提示
        return true;
    }
}
