package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 命令参数补全：{@code email} / {@code qq} 两个渠道值。
 * <p>
 * 只依赖 {@code Invocation#arguments()}，因此直接用假 Invocation 驱动（不启动代理）。
 */
@Timeout(30)
class CommandSuggestionTest {

    private static SimpleCommand.Invocation invocation(String... args) {
        return new SimpleCommand.Invocation() {
            @Override
            public CommandSource source() {
                return null;
            }

            @Override
            public String[] arguments() {
                return args;
            }

            @Override
            public String alias() {
                return "test";
            }
        };
    }

    @Test
    void forgotSuggestsChannelAtSecondArgument() {
        ForgotCommand command = new ForgotCommand(null, null);

        assertEquals(List.of("email", "qq"), command.suggest(invocation("Carol", "")));
        assertEquals(List.of("email"), command.suggest(invocation("Carol", "e")));
        assertEquals(List.of("email"), command.suggest(invocation("Carol", "EMAIL")));
        assertEquals(List.of("qq"), command.suggest(invocation("Carol", "q")));
        assertEquals(List.of(), command.suggest(invocation("Carol", "x")));
        // 第一个参数是账号名：不补全（避免暴露哪些账号存在）
        assertEquals(List.of(), command.suggest(invocation("")));
        // 参数已填完
        assertEquals(List.of(), command.suggest(invocation("Carol", "email", "")));
    }

    @Test
    void bindAndUnbindSuggestChannelAtFirstArgument() {
        BindCommand bind = new BindCommand(null, null);
        UnbindCommand unbind = new UnbindCommand(null, null);

        for (SimpleCommand command : List.of(bind, unbind)) {
            assertEquals(List.of("email", "qq"), command.suggest(invocation("")));
            assertEquals(List.of("qq"), command.suggest(invocation("q")));
            assertEquals(List.of(), command.suggest(invocation("email", "")));
        }
    }
}
