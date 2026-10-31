package io.github.railgun19457.velocityauth.lang;

import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
                "command.deleteaccount.request", "command.bind.email-domains-allow",
                "verification.social-instruction", "verification.wrong-code",
                "auth.login-required", "routing.joining-login",
                "admin.status.header"}) {
            assertTrue(zh.has(key), "缺少语言 key：" + key);
        }
    }

    /**
     * 提示里的 MiniMessage 点击事件必须真的被解析出来（而不是当字面量显示）。
     * <p>
     * 覆盖：入服提示的 [登录]/[注册] 填充按钮、QQ 验证码的点击复制（含占位符替换）。
     */
    @Test
    void promptsCarryClickEvents() throws IOException {
        LangService zh = LangService.load("zh_cn", VelocityAuthPlugin.class, null, NOPLogger.NOP_LOGGER);

        Set<String> suggests = new LinkedHashSet<>();
        collectClicks(zh.msg("routing.joining-login"), ClickEvent.Action.SUGGEST_COMMAND, suggests);
        assertTrue(suggests.contains("/login "), "缺少 /login 填充按钮：" + suggests);
        assertTrue(suggests.contains("/register "), "缺少 /register 填充按钮：" + suggests);

        Set<String> binds = new LinkedHashSet<>();
        collectClicks(zh.msg("command.bind.required", "provider", "email"),
                ClickEvent.Action.SUGGEST_COMMAND, binds);
        collectClicks(zh.msg("command.bind.required", "provider", "qq"),
                ClickEvent.Action.SUGGEST_COMMAND, binds);
        assertTrue(binds.contains("/bind email"), "缺少 /bind email 填充按钮：" + binds);
        assertTrue(binds.contains("/bind qq"), "缺少 /bind qq 填充按钮：" + binds);

        // 点击复制：占位符替换要作用到 click 的参数上
        Set<String> copies = new LinkedHashSet<>();
        collectClicks(zh.raw("verification.code-line", "code", "AbC123"),
                ClickEvent.Action.COPY_TO_CLIPBOARD, copies);
        assertEquals(Set.of("AbC123"), copies);

        // /vauth pair new 输出的 pairing_id 与配对码都要能点击复制（长串手打太痛苦）
        Set<String> pairCopies = new LinkedHashSet<>();
        collectClicks(zh.msg("admin.pair.created-id", "id", "pair_0123456789abcdef"),
                ClickEvent.Action.COPY_TO_CLIPBOARD, pairCopies);
        collectClicks(zh.msg("admin.pair.created-code", "code", "AbC-dEf_1234567890123456789012"),
                ClickEvent.Action.COPY_TO_CLIPBOARD, pairCopies);
        assertEquals(Set.of("pair_0123456789abcdef", "AbC-dEf_1234567890123456789012"), pairCopies,
                "配对信息缺少点击复制：" + pairCopies);

        // 列表类输出里的长标识（pairing_id / client_id / 指纹）同样要能点一下复制
        Set<String> listCopies = new LinkedHashSet<>();
        collectClicks(zh.msg("admin.pair.list-entry", "id", "pair_0123456789abcdef", "ttl", 600, "attempts", "0/5"),
                ClickEvent.Action.COPY_TO_CLIPBOARD, listCopies);
        collectClicks(zh.msg("admin.client.list-entry", "id", "node-bot-1",
                        "fingerprint", "r6dpmtwD_cwfGaktUYh4iYTx8T3bfMcEOMKlPvH3JEo", "last_seen", "-"),
                ClickEvent.Action.COPY_TO_CLIPBOARD, listCopies);
        assertEquals(Set.of("pair_0123456789abcdef", "node-bot-1",
                        "r6dpmtwD_cwfGaktUYh4iYTx8T3bfMcEOMKlPvH3JEo"), listCopies,
                "列表输出缺少点击复制：" + listCopies);

        // 账号 UUID 也是手打不出来的长串
        Set<String> uuidCopies = new LinkedHashSet<>();
        collectClicks(zh.msg("admin.account.info-uuid", "value", "aa2a593e-2c20-3794-860e-8db793863d7d"),
                ClickEvent.Action.COPY_TO_CLIPBOARD, uuidCopies);
        assertEquals(Set.of("aa2a593e-2c20-3794-860e-8db793863d7d"), uuidCopies, "UUID 缺少点击复制");

        // 已知账号时，[登录] 按钮要预填 /login <账号> ，玩家只需补密码
        Set<String> knownSuggests = new LinkedHashSet<>();
        collectClicks(zh.msg("routing.joining-login-known", "account", "Alice"),
                ClickEvent.Action.SUGGEST_COMMAND, knownSuggests);
        assertTrue(knownSuggests.contains("/login Alice "), "缺少预填账号的登录按钮：" + knownSuggests);
        assertEquals(Set.of("/login Alice ", "/forgot "), knownSuggests, "多余/缺失的按钮：" + knownSuggests);
        // 纯文本里不能残留未替换的占位符
        String knownPlain = PlainTextComponentSerializer.plainText()
                .serialize(zh.msg("routing.joining-login-known", "account", "Alice"));
        assertFalse(knownPlain.contains("{account}"), knownPlain);
        assertTrue(knownPlain.contains("Alice"), knownPlain);

        // 纯文本里不应残留未解析的标签（序列化回 MiniMessage 当然会带 <click>，所以看纯文本）
        String plain = PlainTextComponentSerializer.plainText().serialize(zh.msg("routing.joining-login"));
        assertFalse(plain.contains("click:"), plain);
        assertTrue(plain.contains("[登录]"), plain);
        assertTrue(plain.contains("[注册]"), plain);
    }

    private static void collectClicks(Component component, ClickEvent.Action action, Set<String> out) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.action() == action
                && click.payload() instanceof ClickEvent.Payload.Text text) {
            out.add(text.value());
        }
        for (Component child : component.children()) {
            collectClicks(child, action, out);
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
