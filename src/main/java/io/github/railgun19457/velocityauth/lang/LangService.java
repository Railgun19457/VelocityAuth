package io.github.railgun19457.velocityauth.lang;

import io.github.railgun19457.velocityauth.config.ConfigException;
import io.github.railgun19457.velocityauth.util.MessageRenderer;
import io.github.railgun19457.velocityauth.util.YamlUpgrade;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * i18n：从 jar 内 {@code lang/<code>.yml} 加载，支持数据目录 {@code lang/<code>.yml} 覆盖。
 * <p>
 * 消息文本使用 MiniMessage 语法，占位符形如 {@code {name}}。
 */
public final class LangService {

    private static final String FALLBACK = "en_us";

    /** 随插件发布的内置语言文件（会释放到数据目录供用户修改）。 */
    private static final List<String> SHIPPED = List.of("zh_cn", "en_us");
    private static final List<String> RETIRED = List.of(
            "auth.name-occupied", "command.bind.required-email", "command.bind.required-qq",
            "command.bind.qq-limit", "command.bind.email-limit", "verification.actions.bind-qq",
            "verification.qq-unavailable", "verification.qq-instruction",
            "admin.account.info-qq", "admin.account.info-email");

    private final String code;
    private final Map<String, String> messages;
    private final Component prefix;

    private LangService(String code, Map<String, String> messages, Component prefix) {
        this.code = code;
        this.messages = messages;
        this.prefix = prefix;
    }

    public static LangService load(String code, Class<?> resourceAnchor, Path dataDirectory, Logger logger)
            throws IOException {
        String requested = code == null || code.isBlank() ? FALLBACK : code.trim();
        Map<String, String> messages = loadOverride(requested, dataDirectory);
        if (messages == null) {
            messages = loadResource(requested, resourceAnchor);
        }
        if (messages != null) {
            return withFallback(requested, resourceAnchor, messages);
        }
        warnFallback(requested, logger);
        return withFallback(FALLBACK, resourceAnchor, loadOverride(FALLBACK, dataDirectory));
    }

    public record Prepared(LangService language, Map<String, YamlUpgrade.Prepared> files) {
        public void save(Logger logger) throws IOException {
            for (var entry : files.entrySet()) {
                try {
                    entry.getValue().save(logger);
                } catch (IOException e) {
                    if (entry.getKey().equals(language.code())) {
                        throw e;
                    }
                    if (logger != null) {
                        logger.warn("[VelocityAuth] 无法保存未启用的语言文件 lang/{}.yml：{}",
                                entry.getKey(), e.getMessage());
                    }
                }
            }
        }
    }

    /** 准备语言文件的升级内容，当前语种与候选文案先解析成功再写盘。 */
    public static Prepared prepare(String code, Class<?> anchor, Path dataDirectory, Logger logger)
            throws IOException {
        String requested = code == null || code.isBlank() ? FALLBACK : code.trim();
        Map<String, String> custom = SHIPPED.contains(requested) ? null : loadOverride(requested, dataDirectory);
        String effective = requested;
        if (!SHIPPED.contains(requested) && custom == null) {
            warnFallback(requested, logger);
            effective = FALLBACK;
        }
        Map<String, YamlUpgrade.Prepared> files = new LinkedHashMap<>();
        for (String locale : SHIPPED) {
            try {
                String filename = locale + ".yml";
                YamlUpgrade.Prepared file = YamlUpgrade.prepare(anchor, "/lang/" + filename,
                        dataDirectory.resolve("lang").resolve(filename), (version, keys) -> {
                            keys.moveLanguage("admin.status.qqbot", "admin.status.bot");
                            // 扁平键也可作为用户覆盖，移除废弃键时一并处理。
                            RETIRED.forEach(keys::removeLanguageKey);
                            keys.mergeLanguage(previousDefaults(locale, version));
                        });
                Map<String, String> entries = flatten(new StringReader(file.content()));
                if (locale.equals(effective)) {
                    custom = entries;
                }
                files.put(locale, file);
            } catch (IOException | YAMLException e) {
                if (locale.equals(effective)) {
                    throw new ConfigException("language", "无法加载语言文件 " + locale + ".yml", e);
                }
                if (logger != null) {
                    logger.warn("[VelocityAuth] 跳过无法升级的未启用语言文件 lang/{}.yml：{}", locale, e.getMessage());
                }
            }
        }
        return new Prepared(withFallback(effective, anchor, custom), Collections.unmodifiableMap(files));
    }

    /** 只记录可确定的旧默认值，未识别版本的现有文案保持原样。 */
    private static Map<String, String> previousDefaults(String locale, int version) {
        if (version < 1 || version > 5) {
            return Map.of();
        }
        return Map.of("command.logout.success", locale.equals("zh_cn")
                ? "<yellow>已退出登录，请重新连接。</yellow>"
                : "<yellow>Logged out. Please reconnect.</yellow>");
    }

    private static LangService withFallback(String code, Class<?> anchor, Map<String, String> override)
            throws IOException {
        Map<String, String> english = loadResource(FALLBACK, anchor);
        if (english == null) {
            throw new ConfigException("language", "JAR 内缺少 " + FALLBACK + ".yml");
        }
        Map<String, String> messages = new LinkedHashMap<>(english);
        if (!FALLBACK.equals(code)) {
            Map<String, String> localized = loadResource(code, anchor);
            if (localized != null) {
                messages.putAll(localized);
            }
        }
        if (override != null) {
            messages.putAll(override);
        }
        Component prefix = MessageRenderer.parse(messages.getOrDefault("prefix", ""));
        return new LangService(code, Map.copyOf(messages), prefix);
    }

    private static void warnFallback(String requested, Logger logger) {
        if (logger != null) {
            logger.warn("[VelocityAuth] 语言文件 {} 不存在，回退到 {}", requested, FALLBACK);
        }
    }

    private static Map<String, String> loadOverride(String code, Path dataDirectory) throws IOException {
        Path override = dataDirectory == null ? null : dataDirectory.resolve("lang").resolve(code + ".yml");
        if (override != null && Files.isRegularFile(override)) {
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                return flatten(reader);
            }
        }
        return null;
    }

    private static Map<String, String> loadResource(String code, Class<?> anchor) throws IOException {
        try (InputStream in = anchor.getResourceAsStream("/lang/" + code + ".yml")) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return flatten(reader);
            }
        }
    }

    private static Map<String, String> flatten(Reader reader) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
        Map<String, String> out = new LinkedHashMap<>();
        if (loaded instanceof Map<?, ?> map) {
            flattenInto(out, "", map, Collections.newSetFromMap(new IdentityHashMap<>()));
        } else if (loaded != null) {
            throw new YAMLException("语言文件根节点必须是映射");
        }
        return out;
    }

    private static void flattenInto(Map<String, String> out, String prefix, Map<?, ?> map, Set<Map<?, ?>> ancestors) {
        if (!ancestors.add(map)) {
            throw new YAMLException("语言文件含循环引用");
        }
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new YAMLException("语言键必须是字符串");
            }
            String key = prefix.isEmpty() ? name : prefix + "." + name;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flattenInto(out, key, nested, ancestors);
            } else if (value != null) {
                out.put(key, String.valueOf(value));
            }
        }
        ancestors.remove(map);
    }

    public String code() {
        return code;
    }

    public Component prefix() {
        return prefix;
    }

    /** 原始文本（未渲染）。 */
    public String rawText(String key) {
        String value = messages.get(key);
        return value == null ? key : value;
    }

    public boolean has(String key) {
        return messages.containsKey(key);
    }

    /** 带前缀的渲染结果。 */
    public Component msg(String key, Object... placeholders) {
        return MessageRenderer.prefix(prefix, MessageRenderer.parse(render(key, placeholders)));
    }

    /** 不带前缀的渲染结果。 */
    public Component raw(String key, Object... placeholders) {
        return MessageRenderer.parse(render(key, placeholders));
    }

    /** 返回填充占位符后的 MiniMessage 字符串。 */
    public String render(String key, Object... placeholders) {
        return applyPlaceholders(rawText(key), placeholders);
    }

    public static String applyPlaceholders(String template, Object... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return template;
        }
        String out = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = String.valueOf(placeholders[i]);
            Object raw = placeholders[i + 1];
            // 已渲染的组件直接按 MiniMessage 插入（保留其样式），其余内容一律转义为纯文本
            String value = raw instanceof Component component
                    ? MessageRenderer.toMiniMessage(component)
                    : raw == null ? "" : MessageRenderer.escapeTags(String.valueOf(raw));
            out = out.replace("{" + key + "}", value);
        }
        return out;
    }
}
