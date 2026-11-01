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
 * <p>
 * 注意：管理员命令 {@code /velocityauth}（{@code /vauth}）**不**在放行列表里。
 * 权限节点只说明「谁允许管理」，不代表「未登录就能用」——未认证玩家的身份尚未验证，
 * 任何人（含冒名顶替者）都可能冒用管理员的名字拿权限；必须登录后才能用管理命令。
 */
public final class CommandGuardListener {

    /** 未认证玩家仍可执行的命令（仅认证相关）。 */
    private static final Set<String> ALLOWED = Set.of(
            "login",
            "register",
            "bind",
            "forgot",
            "verify",
            "resetpassword");

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
        if (allowedWhenUnauthenticated(command)) {
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
            if (!allowedWhenUnauthenticated(child.getName())) {
                root.getChildren().remove(child);
            }
        }
    }

    /** 未认证玩家能否执行该命令（统一判断，供执行拦截与补全裁剪共用）。 */
    static boolean allowedWhenUnauthenticated(String command) {
        return ALLOWED.contains(command);
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
