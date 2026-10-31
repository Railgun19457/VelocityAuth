package io.github.railgun19457.velocityauth.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.velocitypowered.api.command.CommandSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 用 Brigadier 注册命令，但**执行逻辑仍走既有的 {@code String[]} 版本**。
 * <p>
 * 为什么需要 Brigadier：{@code SimpleCommand.Invocation#arguments()} 在玩家已经打出参数内容时
 * 返回**空数组**（而不是 {@code ["已输入内容"]}），服务端无法得知当前停在第几个参数，
 * 于是补全只能靠 {@code args.length} 猜——猜错的直接结果就是「按 Tab 没反应」。
 * Brigadier 有完整的参数节点树，客户端据此做补全，不依赖残缺的 {@code args}。
 */
public final class CommandTree {

    private CommandTree() {
    }

    /** 一个位置参数。{@code suggests} 为空表示不提供候选。 */
    public record Arg(String name, Function<CommandContext<CommandSource>, List<String>> suggests) {

        public static Arg of(String name) {
            return new Arg(name, null);
        }

        public static Arg of(String name, Function<CommandContext<CommandSource>, List<String>> suggests) {
            return new Arg(name, suggests);
        }
    }

    /**
     * 构造一个只有位置参数的命令节点，返回**根 builder**。
     * <p>
     * 每个层级都挂 {@code executes}，这样「参数没输完就回车」会走原有逻辑输出用法提示，
     * 而不是被 Brigadier 判为不完整命令。
     */
    public static LiteralArgumentBuilder<CommandSource> positional(
            String name,
            Predicate<CommandSource> require,
            BiConsumer<CommandSource, String[]> executor,
            Arg... args) {
        LiteralArgumentBuilder<CommandSource> root = LiteralArgumentBuilder.literal(name);
        if (require != null) {
            root.requires(require);
        }
        root.executes(ctx -> {
            executor.accept(ctx.getSource(), collect(ctx, args));
            return Command.SINGLE_SUCCESS;
        });

        // 逐层往下挂：每一层都挂到上一层的节点上。
        // 注意：Brigadier 的 then() 会**立即 build() 子节点**，所以必须先建最深层再往回挂，
        // 否则先挂上去的是「还没有子节点」的快照，后续追加的节点不会出现在树里。
        ArgumentBuilder<CommandSource, ?> child = null;
        for (int i = args.length - 1; i >= 0; i--) {
            Arg spec = args[i];
            RequiredArgumentBuilder<CommandSource, String> node =
                    RequiredArgumentBuilder.argument(spec.name(), StringArgumentType.string());
            if (spec.suggests() != null) {
                node.suggests(adapt(spec.suggests()));
            }
            node.executes(ctx -> {
                executor.accept(ctx.getSource(), collect(ctx, args));
                return Command.SINGLE_SUCCESS;
            });
            if (child != null) {
                node.then(child);
            }
            child = node;
        }
        if (child != null) {
            root.then(child);
        }
        return root;
    }

    /** 收集玩家**已经输入**的位置参数（遇到第一个缺失就停止）。 */
    private static String[] collect(CommandContext<CommandSource> ctx, Arg[] args) {
        List<String> values = new ArrayList<>(args.length);
        for (Arg spec : args) {
            String value;
            try {
                value = ctx.getArgument(spec.name(), String.class);
            } catch (IllegalArgumentException e) {
                break;
            }
            values.add(value);
        }
        return values.toArray(new String[0]);
    }

    /** 把「按上下文返回候选列表」适配成 Brigadier 的 {@link SuggestionProvider}。 */
    public static SuggestionProvider<CommandSource> adapt(
            Function<CommandContext<CommandSource>, List<String>> source) {
        return (ctx, builder) -> {
            List<String> candidates;
            try {
                candidates = source.apply(ctx);
            } catch (RuntimeException e) {
                candidates = List.of();
            }
            if (candidates == null) {
                return builder.buildFuture();
            }
            String remaining = builder.getRemainingLowerCase();
            for (String candidate : candidates) {
                if (remaining.isEmpty() || candidate.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(candidate);
                }
            }
            return builder.buildFuture();
        };
    }

    /** 便捷方法：静态候选列表。 */
    public static Function<CommandContext<CommandSource>, List<String>> fixed(String... values) {
        return ctx -> List.of(values);
    }
}
