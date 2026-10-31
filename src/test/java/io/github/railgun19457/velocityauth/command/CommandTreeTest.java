package io.github.railgun19457.velocityauth.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 Brigadier 节点树的形状——补全能不能到第二个参数，取决于子节点是否真的挂上了。
 */
@Timeout(30)
class CommandTreeTest {

    private static void dump(CommandNode<CommandSource> node, String indent, List<String> out) {
        String suggests = node.getCommand() != null ? " [exec]" : "";
        out.add(indent + node.getName() + " <" + node.getClass().getSimpleName() + ">" + suggests);
        for (CommandNode<CommandSource> child : node.getChildren()) {
            dump(child, indent + "  ", out);
        }
    }

    @Test
    void positionalBuildsFullChain() {
        LiteralArgumentBuilder<CommandSource> tree = CommandTree.positional(
                "forgot", null,
                (source, args) -> { },
                CommandTree.Arg.of("account"),
                CommandTree.Arg.of("channel", ctx -> List.of("email", "qq")));

        CommandNode<CommandSource> root = tree.build();
        List<String> lines = new ArrayList<>();
        dump(root, "", lines);

        System.out.println("--- forgot tree ---");
        lines.forEach(System.out::println);

        assertEquals("forgot <LiteralCommandNode> [exec]", lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.contains("account")),
                "缺少 account 节点：" + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("channel")),
                "缺少 channel 节点（补全到不了第二个参数）：" + lines);

        // channel 必须挂在 account 下面，而不是根节点下
        CommandNode<CommandSource> account = root.getChild("account");
        assertNotNull(account, "account 节点不存在");
        assertNotNull(account.getChild("channel"), "channel 没有挂在 account 下");
    }

    @Test
    void everyArgNodeHasSuggestionProviderWhenDeclared() {
        LiteralArgumentBuilder<CommandSource> tree = CommandTree.positional(
                "bind", null,
                (source, args) -> { },
                CommandTree.Arg.of("channel", ctx -> List.of("email", "qq")),
                CommandTree.Arg.of("value"));

        CommandNode<CommandSource> root = tree.build();
        CommandNode<CommandSource> channel = root.getChild("channel");
        assertNotNull(channel);
        assertTrue(channel instanceof com.mojang.brigadier.tree.ArgumentCommandNode,
                "channel 应为参数节点");
        assertNotNull(((com.mojang.brigadier.tree.ArgumentCommandNode<CommandSource, ?>) channel)
                .getCustomSuggestions(), "channel 缺少补全提供器");

        // value 没声明 suggests，允许为空（但节点必须存在，否则第二个参数无法输入）
        assertNotNull(channel.getChild("value"), "value 节点不存在");
    }
}
