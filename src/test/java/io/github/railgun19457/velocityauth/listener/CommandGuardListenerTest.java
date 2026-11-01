package io.github.railgun19457.velocityauth.listener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未认证命令隔离的判定逻辑。
 * <p>
 * 重点：管理命令（{@code /vauth} / {@code /velocityauth}）<b>不</b>得在未认证时放行。
 * 权限节点只表示「允许管理」，不表示「未登录可用」——未认证时身份未经校验，
 * 任何人都可能冒用管理员的名字拿到权限。
 */
@Timeout(30)
class CommandGuardListenerTest {

    @Test
    void authCommandsAllowedWhenUnauthenticated() {
        for (String command : new String[]{
                "login", "register", "bind", "forgot", "verify", "resetpassword"}) {
            assertTrue(CommandGuardListener.allowedWhenUnauthenticated(command),
                    command + " 应在未认证时放行");
        }
    }

    @Test
    void adminCommandsDeniedWhenUnauthenticated() {
        assertFalse(CommandGuardListener.allowedWhenUnauthenticated("velocityauth"),
                "未认证玩家不得使用管理命令 /velocityauth");
        assertFalse(CommandGuardListener.allowedWhenUnauthenticated("vauth"),
                "未认证玩家不得使用管理命令 /vauth");
    }

    @Test
    void otherCommandsDeniedWhenUnauthenticated() {
        assertFalse(CommandGuardListener.allowedWhenUnauthenticated("logout"));
        assertFalse(CommandGuardListener.allowedWhenUnauthenticated("changepassword"));
        assertFalse(CommandGuardListener.allowedWhenUnauthenticated("server"));
        assertFalse(CommandGuardListener.allowedWhenUnauthenticated("glist"));
    }

    @Test
    void firstTokenHandlesSlashPrefixAndNamespaces() {
        assertEquals("login", CommandGuardListener.firstToken("/login Alice secret"));
        assertEquals("vauth", CommandGuardListener.firstToken("/vauth status"));
        assertEquals("vauth", CommandGuardListener.firstToken("velocityauth:vauth status"));
        assertEquals("login", CommandGuardListener.firstToken("  /login  "));
        assertEquals("", CommandGuardListener.firstToken(null));
        assertEquals("", CommandGuardListener.firstToken("   "));
    }
}
