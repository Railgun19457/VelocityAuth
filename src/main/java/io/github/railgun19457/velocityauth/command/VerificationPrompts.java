package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.util.MessageRenderer;
import io.github.railgun19457.velocityauth.verify.VerificationDispatch;

import java.util.function.Consumer;

/**
 * 验证发起阶段的玩家提示（统一渲染，避免各命令重复）。
 */
public final class VerificationPrompts {

    private VerificationPrompts() {
    }

    /**
     * 构造派发回调。QQ 渠道的提示在 {@link #sendQqInstruction} 中单独发送（需要验证码）。
     */
    public static Consumer<VerificationDispatch> dispatchHandler(VelocityAuthPlugin plugin, Player player,
                                                                String email) {
        long minutes = Math.max(1, plugin.config().verification().ttlSeconds() / 60);
        return dispatch -> {
            switch (dispatch) {
                case EMAIL_SENT -> player.sendMessage(plugin.lang().msg("verification.email-sent",
                        "email", email, "minutes", minutes));
                case EMAIL_FAILED -> player.sendMessage(plugin.lang().msg("verification.email-failed"));
                case EMAIL_UNAVAILABLE -> player.sendMessage(plugin.lang().msg("verification.email-unavailable"));
                case QQ_UNAVAILABLE -> player.sendMessage(plugin.lang().msg("verification.qq-unavailable"));
                case RATE_LIMITED -> player.sendMessage(plugin.lang().msg("verification.rate-limited"));
                case QQ_WATCHING -> {
                    // 由调用方在拿到 session 后发送带验证码的提示
                }
            }
        };
    }

    /** 游戏内出码（点击可复制），供玩家粘贴到 QQ 群。 */
    public static void sendQqInstruction(VelocityAuthPlugin plugin, Player player, String code) {
        player.sendMessage(plugin.lang().msg("verification.qq-instruction",
                "group", plugin.config().qqbot().groupId()));
        player.sendMessage(MessageRenderer.clickToCopy(
                plugin.lang().raw("verification.code-line", "code", code), code));
        player.sendMessage(plugin.lang().msg("verification.code-prompt"));
    }
}
