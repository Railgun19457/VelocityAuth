package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.bot.KeyStore;
import io.github.railgun19457.velocityauth.bot.PairingService;

import java.util.List;

/**
 * {@code /velocityauth}（别名 {@code /vauth}）管理命令。
 * <p>
 * M6 提供 bot 配对相关子命令；M8 补齐 status / account / sessions / forcelogin。
 */
public final class AdminCommand implements SimpleCommand {

    private final VelocityAuthPlugin plugin;

    public AdminCommand(VelocityAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!source.hasPermission("velocityauth.admin")) {
            source.sendMessage(plugin.lang().msg("generic.no-permission"));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            usage(source);
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "pair" -> pair(source, args);
            case "client" -> client(source, args);
            default -> usage(source);
        }
    }

    private void usage(CommandSource source) {
        source.sendMessage(plugin.lang().msg("generic.usage",
                "usage", "/vauth <pair|client> ..."));
    }

    // ------------------------------------------------------------------ pair

    private void pair(CommandSource source, String[] args) {
        PairingService pairings = plugin.pairings();
        if (pairings == null) {
            source.sendMessage(plugin.lang().msg("generic.feature-disabled"));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "new";
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
                if (pairings.revoke(args[2])) {
                    source.sendMessage(plugin.lang().msg("admin.pair.revoked", "id", args[2]));
                } else {
                    source.sendMessage(plugin.lang().msg("admin.pair.not-found", "id", args[2]));
                }
            }
            default -> usage(source);
        }
    }

    // ------------------------------------------------------------------ client

    private void client(CommandSource source, String[] args) {
        KeyStore keyStore = plugin.keyStore();
        if (keyStore == null) {
            source.sendMessage(plugin.lang().msg("generic.feature-disabled"));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
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
                            "last_seen", client.lastSeen() == 0 ? "-" : String.valueOf(client.lastSeen())));
                }
            }
            case "revoke" -> {
                if (args.length < 3) {
                    usage(source);
                    return;
                }
                if (keyStore.revoke(args[2])) {
                    source.sendMessage(plugin.lang().msg("admin.client.revoked", "id", args[2]));
                } else {
                    source.sendMessage(plugin.lang().msg("admin.client.not-found", "id", args[2]));
                }
            }
            default -> usage(source);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            return List.of("pair", "client");
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                case "pair" -> List.of("new", "list", "revoke");
                case "client" -> List.of("list", "revoke");
                default -> List.of();
            };
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocityauth.admin");
    }
}
