package io.github.railgun19457.velocityauth.mail;

import io.github.railgun19457.velocityauth.util.ReleasedResource;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 验证码邮件模板：从数据目录 {@code email.yml} 读取，首次启动从 jar 内释放默认模板。
 * <p>
 * 与 {@code config.yml} 一样支持 {@code /vauth reload} 热重载；已存在则**不覆盖**用户改动。
 * <p>
 * 正文是**纯文本**（{@code MimeMessage#setText}），占位符形如 {@code {code}}。
 */
public final class EmailTemplateService {

    private static final String DEFAULT_RESOURCE = "/email.yml";
    private static final String DEFAULT_SUBJECT = "【{server}】邮箱验证码";
    private static final String DEFAULT_TEXT =
            "你的验证码是：{code}\n\n有效期 {minutes} 分钟，请勿泄露给他人。";
    private static final String DEFAULT_HTML = "<p>你的验证码是：<b>{code}</b></p>"
            + "<p>{minutes} 分钟内有效，请勿泄露给他人。</p>";

    private final Path dataDirectory;
    private final Path file;
    private final Class<?> resourceAnchor;
    private final Logger logger;

    private volatile String subject = DEFAULT_SUBJECT;
    private volatile String text = DEFAULT_TEXT;
    private volatile String html = DEFAULT_HTML;

    public EmailTemplateService(Path dataDirectory, Class<?> resourceAnchor, Logger logger) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.file = dataDirectory.resolve("email.yml");
        this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path file() {
        return file;
    }

    /** 释放/升级默认模板（版本落后时先备份再覆盖）。返回是否新建。 */
    public boolean releaseDefaultIfMissing() {
        ReleasedResource.Outcome outcome = ReleasedResource.release(
                resourceAnchor, DEFAULT_RESOURCE, file, true, logger);
        switch (outcome) {
            case CREATED -> logger.info("[VelocityAuth] 已释放默认邮件模板到 {}", file);
            case UPGRADED -> logger.info("[VelocityAuth] 邮件模板已升级到内置版本（原文件已备份）");
            case NEW_AVAILABLE -> logger.warn("[VelocityAuth] 邮件模板与内置版本不一致（你改动过且无版本标记），"
                    + "新版本已写出到 {}.new，请自行比对", file);
            default -> {
            }
        }
        return outcome == ReleasedResource.Outcome.CREATED;
    }

    /** 加载（或重载）模板；文件缺失/版本落后时按版本机制处理，解析失败时保留旧值。 */
    public void load() throws IOException {
        releaseDefaultIfMissing();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                logger.warn("邮件模板 {} 根节点应为映射，已忽略", file.getFileName());
                return;
            }
            Object subjectValue = map.get("subject");
            // 兼容旧键名 body（等价于 text）
            Object textValue = map.containsKey("text") ? map.get("text") : map.get("body");
            Object htmlValue = map.get("html");
            if (subjectValue != null && !String.valueOf(subjectValue).isBlank()) {
                this.subject = String.valueOf(subjectValue);
            }
            if (textValue != null) {
                this.text = String.valueOf(textValue);
            }
            if (htmlValue != null) {
                this.html = String.valueOf(htmlValue);
            }
        }
    }

    public String subject(String server, String action) {
        return fill(subject, "server", server, "action", action);
    }

    public String text(String code, long minutes, String server, String action) {
        return fill(text, "code", code, "minutes", Long.toString(minutes), "server", server, "action", action);
    }

    /** HTML 正文；模板留空时返回空串（只发纯文本）。 */
    public String html(String code, long minutes, String server, String action) {
        return fill(html, "code", code, "minutes", Long.toString(minutes), "server", server, "action", action);
    }

    private static String fill(String template, String... pairs) {
        String out = template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String value = pairs[i + 1] == null ? "" : pairs[i + 1];
            out = out.replace("{" + pairs[i] + "}", value);
        }
        return out;
    }
}
