package io.github.railgun19457.velocityauth.lang;

import io.github.railgun19457.velocityauth.config.ConfigException;
import io.github.railgun19457.velocityauth.util.MessageRenderer;
import io.github.railgun19457.velocityauth.util.ReleasedResource;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * i18n：从 jar 内 {@code lang/<code>.yml} 加载，支持数据目录 {@code lang/<code>.yml} 覆盖。
 * <p>
 * 消息文本使用 MiniMessage 语法，占位符形如 {@code {name}}。
 */
public final class LangService {

    private static final String FALLBACK = "en_us";

    /** 随插件发布的内置语言文件（会释放到数据目录供用户修改）。 */
    private static final List<String> SHIPPED = List.of("zh_cn", "en_us");

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
        Map<String, String> messages = tryLoad(requested, resourceAnchor, dataDirectory, logger);
        String effective = requested;
        if (messages == null) {
            if (logger != null) {
                logger.warn("[VelocityAuth] 语言文件 {} 不存在，回退到 {}", requested, FALLBACK);
            }
            effective = FALLBACK;
            messages = tryLoad(FALLBACK, resourceAnchor, dataDirectory, logger);
            if (messages == null) {
                throw new ConfigException("language", "找不到语言文件：" + requested + " / " + FALLBACK);
            }
        }
        Component prefix = MessageRenderer.parse(messages.getOrDefault("prefix", ""));
        return new LangService(effective, Map.copyOf(messages), prefix);
    }

    /**
     * 把内置语言文件释放到数据目录 {@code lang/}，并按版本升级。
     * <p>
     * 用户改过的文件不会丢：版本落后时会先备份成 {@code <名>.bak-<时间>} 再覆盖；
     * 若用户文件没有版本标记且内容已被改动，则只写出 {@code <名>.new} 供比对。
     */
    public static void releaseShipped(Class<?> resourceAnchor, Path dataDirectory, Logger logger) {
        if (dataDirectory == null) {
            return;
        }
        Path dir = dataDirectory.resolve("lang");
        for (String code : SHIPPED) {
            ReleasedResource.Outcome outcome = ReleasedResource.release(
                    resourceAnchor, "/lang/" + code + ".yml", dir.resolve(code + ".yml"), true, logger);
            if (logger == null) {
                continue;
            }
            switch (outcome) {
                case CREATED -> logger.info("[VelocityAuth] 已释放语言文件 lang/{}.yml", code);
                case UPGRADED -> logger.info("[VelocityAuth] 语言文件 lang/{}.yml 已升级到内置版本", code);
                case NEW_AVAILABLE -> logger.warn("[VelocityAuth] 语言文件 lang/{}.yml 与内置版本不一致"
                        + "（你改动过且无版本标记），新版本已写出到 lang/{}.yml.new，请自行比对", code, code);
                default -> {
                }
            }
        }
    }

    /**
     * 优先读数据目录覆盖文件；存在时用 jar 内置文案补齐它缺少的键
     * （这样升级新增的文案不会显示成原始 key）。
     */
    private static Map<String, String> tryLoad(String code, Class<?> anchor, Path dataDirectory, Logger logger)
            throws IOException {
        Map<String, String> defaults = loadResource(code, anchor);
        Path override = dataDirectory == null ? null : dataDirectory.resolve("lang").resolve(code + ".yml");
        if (override != null && Files.isRegularFile(override)) {
            Map<String, String> custom;
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                custom = flatten(reader);
            }
            if (defaults != null) {
                int filled = 0;
                for (Map.Entry<String, String> entry : defaults.entrySet()) {
                    if (custom.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                        filled++;
                    }
                }
                if (filled > 0 && logger != null) {
                    logger.info("[VelocityAuth] 语言文件 {} 缺少 {} 个键，已用内置文案补齐", code, filled);
                }
            }
            return custom;
        }
        return defaults;
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
