package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

/**
 * 让既有的 {@link SimpleCommand} 实现直接挂到 Brigadier 树上执行。
 * <p>
 * 各命令的业务逻辑、权限判断、用法提示都保持不变，只是「补全」改由 Brigadier 的节点树提供，
 * 不再依赖 {@code SimpleCommand.Invocation#arguments()}（它在玩家已输入内容时会返回空数组）。
 */
public final class SimpleCommandAdapter {

    private SimpleCommandAdapter() {
    }

    /** 把 {@code (来源, 位置参数)} 转成该命令期望的 {@link SimpleCommand.Invocation} 并执行。 */
    public static void run(SimpleCommand command, String alias, CommandSource source, String[] args) {
        if (command == null) {
            return;
        }
        command.execute(new FixedInvocation(source, args, alias));
    }

    /**
     * 参数已由 Brigadier 解析完毕的 Invocation。
     * <p>
     * {@link #arguments()} 始终返回**完整且非空**的位置参数，因此命令内部的
     * {@code args.length < N} 判断与原先行为完全一致。
     */
    public record FixedInvocation(CommandSource source, String[] arguments, String alias)
            implements SimpleCommand.Invocation {
    }
}
