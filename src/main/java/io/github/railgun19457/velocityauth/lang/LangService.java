package io.github.railgun19457.velocityauth.lang;

import io.github.railgun19457.velocityauth.config.ConfigException;
import io.github.railgun19457.velocityauth.util.MessageRenderer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * i18n：从 jar 内 {@code lang/<code>.yml} 加载，支持数据目录 {@code lang/<code>.yml} 覆盖。
 * <p>
 * 消息文本使用 MiniMessage 语法，占位符形如 {@code {name}}。
 */
public final class LangService {

    private static final String FALLBACK = "en_us";

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
        Map<String, String> messages = tryLoad(requested, resourceAnchor, dataDirectory);
        String effective = requested;
        if (messages == null) {
            if (logger != null) {
                logger.warn("[VelocityAuth] 语言文件 {} 不存在，回退到 {}", requested, FALLBACK);
            }
            effective = FALLBACK;
            messages = tryLoad(FALLBACK, resourceAnchor, dataDirectory);
            if (messages == null) {
                throw new ConfigException("language", "找不到语言文件：" + requested + " / " + FALLBACK);
            }
        }
        Component prefix = MessageRenderer.parse(messages.getOrDefault("prefix", ""));
        return new LangService(effective, Map.copyOf(messages), prefix);
    }

    private static Map<String, String> tryLoad(String code, Class<?> anchor, Path dataDirectory) throws IOException {
        Path override = dataDirectory == null ? null : dataDirectory.resolve("lang").resolve(code + ".yml");
        if (override != null && Files.isRegularFile(override)) {
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                return flatten(reader);
            }
        }
        String resource = "/lang/" + code + ".yml";
        try (InputStream in = anchor.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return flatten(reader);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> flatten(Reader reader) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
        Map<String, String> out = new LinkedHashMap<>();
        if (loaded instanceof Map<?, ?> map) {
            flattenInto(out, "", (Map<String, Object>) map);
        }
        return out;
    }

    private static void flattenInto(Map<String, String> out, String prefix, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                flattenInto(out, key, typed);
            } else if (value != null) {
                out.put(key, String.valueOf(value));
            }
        }
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

    static String applyPlaceholders(String template, Object... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return template;
        }
        String out = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = String.valueOf(placeholders[i]);
            Object raw = placeholders[i + 1];
            String value = raw == null ? "" : MessageRenderer.escapeTags(String.valueOf(raw));
            out = out.replace("{" + key + "}", value);
        }
        return out;
    }
}
