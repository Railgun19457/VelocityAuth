package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;

/**
 * 命令注册。
 */
public final class CommandRegistrar {

    private final VelocityAuthPlugin plugin;
    private final AuthSessionRegistry registry;

    public CommandRegistrar(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void registerPlayerCommands() {
        register("login", new LoginCommand(plugin, registry), "l");
        register("register", new RegisterCommand(plugin, registry), "reg");
        register("logout", new LogoutCommand(plugin, registry));
        register("changepassword", new ChangePasswordCommand(plugin, registry), "changepw", "cp");
    }

    private void register(String name, SimpleCommand command, String... aliases) {
        CommandManager manager = plugin.server().getCommandManager();
        CommandMeta.Builder builder = manager.metaBuilder(name).plugin(plugin);
        if (aliases.length > 0) {
            builder.aliases(aliases);
        }
        manager.register(builder.build(), command);
    }
}
