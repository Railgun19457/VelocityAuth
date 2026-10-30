package io.github.railgun19457.velocityauth.util;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可编辑资源（语言文件 / 邮件模板 / 配置模板）的释放与升级。
 * <p>
 * 文件首行的 {@code # version: N} 标记版本。升级时：
 * <ul>
 *   <li>用户文件版本 **不低于** 内置 → 不动（尊重用户改动）；</li>
 *   <li>用户文件版本落后且 {@code autoUpgrade=true} → 先备份为 {@code <名>.bak-<时间>} 再覆盖；</li>
 *   <li>用户文件版本落后但 {@code autoUpgrade=false}（如 config.yml，含凭据不能自动覆盖）
 *       → 只写出 {@code <名>.new} 供比对；</li>
 *   <li>用户文件**没有版本标记**：与内置逐字节相同（说明从未改过）→ 直接更新；
 *       否则视为用户改过 → 只写出 {@code <名>.new}。</li>
 * </ul>
 */
public final class ReleasedResource {

    public enum Outcome {
        /** 文件不存在，已释放。 */
        CREATED,
        /** 无需变更。 */
        KEPT,
        /** 版本落后，已备份并升级。 */
        UPGRADED,
        /** 用户文件已改动、无法安全覆盖，已写出 {@code <名>.new}。 */
        NEW_AVAILABLE,
        /** 处理失败（IO 异常）。 */
        FAILED
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\s*#\\s*version\\s*:\\s*(\\d+)\\s*$");
    private static final int SCAN_LINES = 12;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ReleasedResource() {
    }

    public static Outcome release(Class<?> anchor, String resource, Path target, boolean autoUpgrade, Logger logger) {
        try {
            byte[] builtin = readResource(anchor, resource);
            if (builtin == null) {
                if (logger != null) {
                    logger.warn("jar 内缺少资源 {}，无法释放到 {}", resource, target);
                }
                return Outcome.FAILED;
            }
            if (!Files.exists(target)) {
                write(target, builtin);
                return Outcome.CREATED;
            }

            byte[] user = Files.readAllBytes(target);
            int userVersion = versionOf(user);
            int builtinVersion = versionOf(builtin);

            if (userVersion >= 0) {
                if (builtinVersion <= userVersion) {
                    return Outcome.KEPT;
                }
                if (!autoUpgrade) {
                    Path next = newPath(target);
                    if (Files.exists(next) && Arrays.equals(Files.readAllBytes(next), builtin)) {
                        // 已经提示过且 .new 就是最新内置版本 → 静默，避免每次启动重复告警
                        return Outcome.KEPT;
                    }
                    write(next, builtin);
                    return Outcome.NEW_AVAILABLE;
                }
                backup(target, logger);
                write(target, builtin);
                return Outcome.UPGRADED;
            }

            // 没有版本标记：内容与内置完全一致 ⇒ 用户没改过，可安全更新
            if (Arrays.equals(user, builtin)) {
                return Outcome.KEPT;
            }
            Path next = newPath(target);
            if (Files.exists(next) && Arrays.equals(Files.readAllBytes(next), builtin)) {
                return Outcome.KEPT;
            }
            write(next, builtin);
            return Outcome.NEW_AVAILABLE;
        } catch (IOException e) {
            if (logger != null) {
                logger.warn("释放/升级 {} 失败：{}", target.getFileName(), e.getMessage());
            }
            return Outcome.FAILED;
        }
    }

    /** 读取 {@code # version: N} 标记；没有标记返回 -1。 */
    public static int versionOf(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\r?\n", SCAN_LINES + 1);
        for (int i = 0; i < Math.min(lines.length, SCAN_LINES); i++) {
            Matcher matcher = VERSION_PATTERN.matcher(lines[i]);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return -1;
    }

    private static byte[] readResource(Class<?> anchor, String resource) throws IOException {
        try (InputStream in = anchor.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void write(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, content);
    }

    private static void backup(Path target, Logger logger) throws IOException {
        String stamp = LocalDateTime.now().format(STAMP);
        Path backup = target.resolveSibling(target.getFileName() + ".bak-" + stamp);
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        if (logger != null) {
            logger.info("[VelocityAuth] 升级前已备份原文件到 {}", backup.getFileName());
        }
    }

    private static Path newPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".new");
    }
}
