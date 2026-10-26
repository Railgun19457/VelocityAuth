package io.github.railgun19457.velocityauth.config;

import io.github.railgun19457.velocityauth.lang.LangService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(VelocityAuthConfig.Registration.Gate.NONE, config.registration().gate());
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
}
