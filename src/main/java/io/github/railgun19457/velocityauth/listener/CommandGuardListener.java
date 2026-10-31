package io.github.railgun19457.velocityauth.listener;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * 未认证隔离：拦截除认证相关命令外的一切命令（DESIGN 4.4）。
 */
public final class CommandGuardListener {

    /** 未认证玩家仍可执行的命令（认证相关 + 管理命令）。 */
    private static final Set<String> ALLOWED = Set.of(
            "login",
            "register",
            "bind",
            "forgot",
            "verify",
            "resetpassword",
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

    /**
     * 未认证玩家的命令树只保留认证相关命令。
     * <p>
     * 否则客户端补全会列出后端服与其它插件的全部命令（虽然执行时会被拦），既吵又容易误导。
     * 这里直接裁剪发给该玩家的 Brigadier 根节点；已认证玩家原样放行。
     */
    @Subscribe
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        AuthSession session = registry.get(player).orElse(null);
        if (session != null && session.authenticated()) {
            return;
        }
        RootCommandNode<?> root = event.getRootNode();
        // 先复制再删，避免遍历时修改同一个集合
        for (CommandNode<?> child : new ArrayList<>(root.getChildren())) {
            if (!ALLOWED.contains(child.getName())) {
                root.getChildren().remove(child);
            }
        }
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
