package io.github.railgun19457.velocityauth.config;

import io.github.railgun19457.velocityauth.lang.LangService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class ConfigAndLangTest {

    @Test
    void defaultsAreAppliedWhenSectionsMissing() {
        VelocityAuthConfig config = VelocityAuthConfig.from(ConfigNode.root(Map.of()));
        assertEquals("zh_cn", config.language());
        assertEquals(VelocityAuthConfig.Database.Type.SQLITE, config.database().type());
        assertEquals("lobby", config.servers().login());
        assertEquals("main", config.servers().defaultServer());
        assertEquals(java.util.List.of(), config.registration().requireBinding());
        assertEquals(30, config.registration().unboundTtlMinutes());
        assertTrue(config.auth().accountName().pattern().matcher("abc_123").matches());
    }

    @Test
    void invalidEnumValueReportsFieldPath() {
        Map<String, Object> root = Map.of("database", Map.of("type", "postgres"));
        ConfigException error = assertThrows(ConfigException.class, () -> VelocityAuthConfig.from(ConfigNode.root(root)));
        assertTrue(error.getMessage().contains("database.type"), error.getMessage());
        assertTrue(error.getMessage().contains("sqlite"), error.getMessage());
    }

    @Test
    void typeMismatchReportsFieldPath() {
        Map<String, Object> root = Map.of("servers", Map.of("login", 123));
        ConfigException error = assertThrows(ConfigException.class, () -> VelocityAuthConfig.from(ConfigNode.root(root)));
        assertTrue(error.getMessage().contains("servers.login"), error.getMessage());
    }

    @Test
    void invalidRangeIsRejected() {
        Map<String, Object> root = Map.of("auth", Map.of(
                "account-name", Map.of("min-length", 20, "max-length", 5)));
        ConfigException error = assertThrows(ConfigException.class, () -> VelocityAuthConfig.from(ConfigNode.root(root)));
        assertTrue(error.getMessage().contains("auth.account-name"), error.getMessage());
    }

    @Test
    void placeholdersAreSubstitutedAndEscaped() {
        assertEquals("你的验证码是：123456",
                LangService.applyPlaceholders("你的验证码是：{code}", "code", "123456"));
        assertEquals("x=\\<script>", LangService.applyPlaceholders("x={v}", "v", "<script>"));
        assertEquals("no placeholders", LangService.applyPlaceholders("no placeholders"));
    }

    @Test
    void unknownPlaceholderIsLeftIntact() {
        assertEquals("a={b}", LangService.applyPlaceholders("a={b}", "c", "d"));
    }

    @Test
    void componentPlaceholdersKeepTheirFormatting() {
        // 组件值不应被转义，否则会显示字面量标签（如 <green>已启用</green>）
        String out = LangService.applyPlaceholders("<white>{value}</white>", "value",
                Component.text("已启用", NamedTextColor.GREEN));
        assertTrue(out.contains("<green>"), out);
        assertFalse(out.contains("\\<"), out);
        // 纯文本值仍然转义
        assertEquals("<white>\\<script></white>",
                LangService.applyPlaceholders("<white>{value}</white>", "value", "<script>"));
    }
}
