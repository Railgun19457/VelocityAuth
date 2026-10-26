package io.github.railgun19457.velocityauth.lang;

import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.helpers.NOPLogger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言文件一致性检查：两个语言文件的 key 集合必须完全一致，避免漏翻译导致显示原始 key。
 */
@Timeout(30)
class LangResourceTest {

    @Test
    void bothLanguagesLoadAndShareTheSameKeys() throws IOException {
        LangService zh = LangService.load("zh_cn", VelocityAuthPlugin.class, null, NOPLogger.NOP_LOGGER);
        LangService en = LangService.load("en_us", VelocityAuthPlugin.class, null, NOPLogger.NOP_LOGGER);
        assertEquals("zh_cn", zh.code());
        assertEquals("en_us", en.code());

        Set<String> zhKeys = flattenKeys("zh_cn");
        Set<String> enKeys = flattenKeys("en_us");

        Set<String> missingInEn = new TreeSet<>(zhKeys);
        missingInEn.removeAll(enKeys);
        Set<String> missingInZh = new TreeSet<>(enKeys);
        missingInZh.removeAll(zhKeys);

        assertTrue(missingInEn.isEmpty(), "en_us.yml 缺少 key：" + missingInEn);
        assertTrue(missingInZh.isEmpty(), "zh_cn.yml 缺少 key：" + missingInZh);
        assertFalse(zhKeys.isEmpty());
    }

    @Test
    void criticalKeysExist() throws IOException {
        LangService zh = LangService.load("zh_cn", VelocityAuthPlugin.class, null, NOPLogger.NOP_LOGGER);
        for (String key : new String[]{
                "prefix",
                "command.login.usage", "command.register.usage", "command.verify.usage",
                "verification.email-subject", "verification.email-body",
                "verification.qq-instruction", "verification.wrong-code",
                "auth.login-required", "routing.joining-login",
                "admin.status.header"}) {
            assertTrue(zh.has(key), "缺少语言 key：" + key);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> flattenKeys(String code) throws IOException {
        try (InputStream in = VelocityAuthPlugin.class.getResourceAsStream("/lang/" + code + ".yml")) {
            assertTrue(in != null, "找不到语言资源：" + code);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
                Set<String> keys = new LinkedHashSet<>();
                collect(keys, "", (Map<String, Object>) loaded);
                return keys;
            }
        }
    }

    private static void collect(Set<String> keys, String prefix, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                collect(keys, key, typed);
            } else {
                keys.add(key);
            }
        }
    }
}
