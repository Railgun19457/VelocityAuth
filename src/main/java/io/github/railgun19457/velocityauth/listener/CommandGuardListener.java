package io.github.railgun19457.velocityauth.listener;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;

import java.util.Locale;
import java.util.Set;

/**
 * 未认证隔离：拦截除认证相关命令外的一切命令（DESIGN 4.4）。
 */
public final class CommandGuardListener {

    /** 未认证玩家仍可执行的命令（认证相关 + 管理命令）。 */
    private static final Set<String> ALLOWED = Set.of(
            "login", "l",
            "register", "reg",
            "forgot",
            "verify",
            "resetpassword", "resetpw",
            "velocityauth", "vauth");

    private final VelocityAuthPlugin plugin;
    private final AuthSessionRegistry registry;

    public CommandGuardListener(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        CommandSource source = event.getCommandSource();
        if (!(source instanceof Player player)) {
            return;
        }
        AuthSession session = registry.get(player).orElse(null);
        if (session != null && session.authenticated()) {
            return;
        }
        String command = firstToken(event.getCommand());
        if (ALLOWED.contains(command)) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(plugin.lang().msg("auth.login-required"));
    }

    static String firstToken(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String trimmed = commandLine.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        String token = space < 0 ? trimmed : trimmed.substring(0, space);
        // 兼容 "plugin:command" 形式
        int colon = token.indexOf(':');
        if (colon >= 0) {
            token = token.substring(colon + 1);
        }
        return token.toLowerCase(Locale.ROOT);
    }
}
