package io.github.railgun19457.velocityauth.command;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.Provider;

import java.util.List;
import java.util.function.Predicate;

/**
 * 命令注册（Brigadier）。
 * <p>
 * 用 Brigadier 的节点树声明参数，补全由客户端按节点树请求，因此<b>按 Tab 就能直接出候选</b>，
 * 不依赖 {@code SimpleCommand.Invocation#arguments()}（后者在玩家已输入内容时返回空数组，
 * 是此前「按 Tab 没反应」的根因）。
 * <p>
 * 注意：不在 {@code requires} 里做「登录状态」判断——它在命令声明阶段就会被求值，未登录玩家的
 * 命令会被整条从命令树剔除，客户端因此既看不到命令也无法补全。状态隔离统一交给
 * {@code CommandGuardListener} 在运行时拦截。
 */
public final class CommandRegistrar {

    private final VelocityAuthPlugin plugin;
    private final AuthSessionRegistry registry;

    public CommandRegistrar(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void registerPlayerCommands() {
        CommandManager manager = plugin.server().getCommandManager();

        register(manager, "login", new LoginCommand(plugin, registry),
                CommandTree.Arg.of("account"),
                CommandTree.Arg.of("password"));

        register(manager, "register", new RegisterCommand(plugin, registry),
                CommandTree.Arg.of("account"),
                CommandTree.Arg.of("password"),
                CommandTree.Arg.of("confirm"));

        register(manager, "verify", new VerifyCommand(plugin, registry),
                CommandTree.Arg.of("code", ctx -> List.of("cancel")));

        register(manager, "resetpassword", new ResetPasswordCommand(plugin, registry),
                CommandTree.Arg.of("password"),
                CommandTree.Arg.of("confirm"));

        register(manager, "forgot", new ForgotCommand(plugin, registry),
                CommandTree.Arg.of("account"),
                CommandTree.Arg.of("channel", ctx -> channelIds()));

        register(manager, "logout", new LogoutCommand(plugin, registry));

        register(manager, "changepassword", new ChangePasswordCommand(plugin, registry),
                CommandTree.Arg.of("old-password"),
                CommandTree.Arg.of("new-password"));

        register(manager, "bind", new BindCommand(plugin, registry),
                CommandTree.Arg.of("channel", ctx -> channelIds()),
                CommandTree.Arg.of("value"));

        register(manager, "unbind", new UnbindCommand(plugin, registry),
                CommandTree.Arg.of("channel", ctx -> channelIds()));

        register(manager, "deleteaccount", new DeleteAccountCommand(plugin, registry));

        registerAdmin(manager);
    }

    /** 已配置的绑定渠道 id（email + 各社交渠道）。 */
    private List<String> channelIds() {
        if (plugin == null) {
            return List.of("email", "qq");
        }
        try {
            List<String> ids = Provider.ids(plugin.config());
            return ids.isEmpty() ? List.of("email", "qq") : ids;
        } catch (RuntimeException e) {
            return List.of("email", "qq");
        }
    }

    /** 注册一个只有位置参数的命令。 */
    private void register(CommandManager manager, String name, PlayerCommandSupport command,
                          CommandTree.Arg... args) {
        // requires 会在「为每个玩家构建可用命令树」时求值，因此可以用来按登录状态隐藏命令：
        // 未登录玩家看不到 /logout、/changepassword 等，登录后看不到 /login、/register。
        // 执行阶段的兜底仍由 CommandGuardListener 负责。
        Predicate<CommandSource> require = source -> command.hasPermission(
                new SimpleCommandAdapter.FixedInvocation(source, new String[0], name));
        LiteralArgumentBuilder<CommandSource> tree = CommandTree.positional(name, require,
                (source, values) -> SimpleCommandAdapter.run(command, name, source, values), args);
        CommandMeta meta = manager.metaBuilder(name).plugin(plugin).build();
        manager.register(meta, new BrigadierCommand(tree));
    }

    /**
     * {@code /velocityauth}（别名 {@code /vauth}）的子命令树。
     * <p>
     * 子命令解析仍由 {@link AdminCommand} 按位置参数完成，但每个子命令声明成独立节点，
     * 补全因此能精确到层级。
     */
    private void registerAdmin(CommandManager manager) {
        AdminCommand admin = new AdminCommand(plugin);
        Predicate<CommandSource> require = source -> admin.hasPermission(
                new SimpleCommandAdapter.FixedInvocation(source, new String[0], "velocityauth"));

        LiteralArgumentBuilder<CommandSource> root = LiteralArgumentBuilder.literal("velocityauth");
        root.requires(require);
        root.executes(ctx -> {
            SimpleCommandAdapter.run(admin, "velocityauth", ctx.getSource(), new String[0]);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        });

        for (String sub : List.of("status", "reload")) {
            root.then(LiteralArgumentBuilder.<CommandSource>literal(sub).executes(ctx -> {
                SimpleCommandAdapter.run(admin, "velocityauth", ctx.getSource(), new String[]{sub});
                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
            }));
        }

        addSub(root, admin, "sessions",
                CommandTree.Arg.of("action", ctx -> List.of("list", "kick")),
                CommandTree.Arg.of("account"));
        addSub(root, admin, "pair",
                CommandTree.Arg.of("action", ctx -> List.of("new", "list", "revoke")),
                CommandTree.Arg.of("id"));
        addSub(root, admin, "client",
                CommandTree.Arg.of("action", ctx -> List.of("list", "revoke")),
                CommandTree.Arg.of("id"));
        addSub(root, admin, "account",
                CommandTree.Arg.of("account"),
                CommandTree.Arg.of("action",
                        ctx -> List.of("info", "setpassword", "unbind", "lock", "unlock", "delete")),
                CommandTree.Arg.of("value", ctx -> channelIds()));
        addSub(root, admin, "forcelogin",
                CommandTree.Arg.of("account"),
                CommandTree.Arg.of("kick", ctx -> List.of("--kick")));

        CommandMeta meta = manager.metaBuilder("velocityauth").aliases("vauth").plugin(plugin).build();
        manager.register(meta, new BrigadierCommand(root));
    }

    /** {@code /vauth <literal> <args...>}：执行时把 literal 作为第一个位置参数传给 AdminCommand。 */
    private void addSub(LiteralArgumentBuilder<CommandSource> root, AdminCommand admin,
                        String literal, CommandTree.Arg... args) {
        LiteralArgumentBuilder<CommandSource> node = LiteralArgumentBuilder.literal(literal);
        node.executes(ctx -> {
            SimpleCommandAdapter.run(admin, "velocityauth", ctx.getSource(), new String[]{literal});
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        });
        ArgumentBuilder<CommandSource, ?> chain = buildChain(admin, literal, args, 0);
        if (chain != null) {
            node.then(chain);
        }
        root.then(node);
    }

    /**
     * 建参数链。
     * <p>
     * Brigadier 的 {@code then()} 会<b>立即 {@code build()} 子节点</b>，因此必须先把更深层
     * 完整建好再挂上来，否则挂上去的是「还没有子节点」的快照，后续层级会丢失。
     */
    private ArgumentBuilder<CommandSource, ?> buildChain(AdminCommand admin, String sub,
                                                         CommandTree.Arg[] args, int index) {
        if (index >= args.length) {
            return null;
        }
        CommandTree.Arg spec = args[index];
        RequiredArgumentBuilder<CommandSource, String> node =
                RequiredArgumentBuilder.argument(spec.name(), StringArgumentType.string());
        if (spec.suggests() != null) {
            node.suggests(CommandTree.adapt(spec.suggests()));
        }
        node.executes(ctx -> {
            List<String> values = new java.util.ArrayList<>();
            values.add(sub);
            for (CommandTree.Arg a : args) {
                try {
                    values.add(ctx.getArgument(a.name(), String.class));
                } catch (IllegalArgumentException e) {
                    break;
                }
            }
            SimpleCommandAdapter.run(admin, "velocityauth", ctx.getSource(),
                    values.toArray(new String[0]));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        });
        ArgumentBuilder<CommandSource, ?> deeper = buildChain(admin, sub, args, index + 1);
        if (deeper != null) {
            node.then(deeper);
        }
        return node;
    }
}
