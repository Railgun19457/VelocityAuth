package io.github.railgun19457.velocityauth.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * MiniMessage 渲染工具：统一入口，便于将来替换渲染实现。
 */
public final class MessageRenderer {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private MessageRenderer() {
    }

    public static Component parse(String miniMessage) {
        if (miniMessage == null) {
            return Component.empty();
        }
        return MINI.deserialize(miniMessage);
    }

    public static Component prefix(Component prefix, Component message) {
        if (prefix == null || prefix.equals(Component.empty())) {
            return message;
        }
        return prefix.append(message);
    }

    /** 点击复制（QQ 验证码等）。 */
    public static Component clickToCopy(Component message, String text) {
        return message.clickEvent(ClickEvent.copyToClipboard(text));
    }

    public static Component hover(Component message, Component hoverText) {
        return message.hoverEvent(HoverEvent.showText(hoverText));
    }

    /**
     * 把组件序列化回 MiniMessage 文本，供其作为占位符值插入模板时使用。
     * <p>
     * 与 {@link #escapeTags(String)} 相对：已经渲染过的组件是可信的，插入模板后应继续参与解析
     * （例如 {@code <green>已启用</green>} 需要保持绿色），而不是被转义成字面量。
     */
    public static String toMiniMessage(Component component) {
        if (component == null) {
            return "";
        }
        return MINI.serialize(component);
    }

    /**
     * 转义 MiniMessage 标签起始符，避免玩家可控内容被当作标签解析。
     */
    public static String escapeTags(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("<", "\\<");
    }
}
