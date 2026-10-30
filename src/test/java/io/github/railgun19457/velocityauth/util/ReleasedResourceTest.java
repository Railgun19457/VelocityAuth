package io.github.railgun19457.velocityauth.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可编辑资源的释放/升级：版本标记比较、备份、{@code .new} 兜底。
 */
@Timeout(30)
class ReleasedResourceTest {

    private static final String V1 = "/released/v1.yml";
    private static final String V2 = "/released/v2.yml";

    @TempDir
    Path tempDir;

    private Path target(String name) {
        return tempDir.resolve(name);
    }

    private Path write(String name, String content) throws IOException {
        Path path = target(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private List<Path> backups(String name) throws IOException {
        try (Stream<Path> stream = Files.list(tempDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(name + ".bak-")).toList();
        }
    }

    @Test
    void versionMarkerIsParsed() {
        assertEquals(1, ReleasedResource.versionOf("# version: 1\nkey: x\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(7, ReleasedResource.versionOf("\n\n#version:7\nkey: x\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(-1, ReleasedResource.versionOf("key: x\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void createsWhenMissing() throws IOException {
        Path path = target("new.yml");
        assertEquals(ReleasedResource.Outcome.CREATED,
                ReleasedResource.release(getClass(), V2, path, true, NOPLogger.NOP_LOGGER));
        assertTrue(Files.readString(path).contains("value2"));
    }

    @Test
    void keepsWhenUserVersionIsNotOlder() throws IOException {
        Path same = write("same.yml", "# version: 2\nkey: mine\n");
        assertEquals(ReleasedResource.Outcome.KEPT,
                ReleasedResource.release(getClass(), V2, same, true, NOPLogger.NOP_LOGGER));
        assertTrue(Files.readString(same).contains("mine"), "不应覆盖版本相同的用户文件");

        Path ahead = write("ahead.yml", "# version: 9\nkey: mine\n");
        assertEquals(ReleasedResource.Outcome.KEPT,
                ReleasedResource.release(getClass(), V2, ahead, true, NOPLogger.NOP_LOGGER));
        assertTrue(Files.readString(ahead).contains("mine"));
    }

    @Test
    void upgradesOlderFileWithBackup() throws IOException {
        Path path = write("up.yml", "# version: 1\nkey: mine\n");
        assertEquals(ReleasedResource.Outcome.UPGRADED,
                ReleasedResource.release(getClass(), V2, path, true, NOPLogger.NOP_LOGGER));
        assertTrue(Files.readString(path).contains("value2"));
        assertEquals(1, backups("up.yml").size(), "升级前应留一份备份");
        assertTrue(Files.readString(backups("up.yml").get(0)).contains("mine"), "备份应保留用户原内容");
    }

    @Test
    void writesNewFileInsteadOfOverwritingWhenAutoUpgradeDisabled() throws IOException {
        Path path = write("manual.yml", "# version: 1\nkey: mine\n");
        assertEquals(ReleasedResource.Outcome.NEW_AVAILABLE,
                ReleasedResource.release(getClass(), V2, path, false, NOPLogger.NOP_LOGGER));
        assertTrue(Files.readString(path).contains("mine"), "手动模式绝不覆盖用户文件");
        assertTrue(Files.readString(target("manual.yml.new")).contains("value2"));
        assertTrue(backups("manual.yml").isEmpty());
    }

    @Test
    void newFileIsWrittenOnlyOnce() throws IOException {
        Path path = write("once.yml", "# version: 1\nkey: mine\n");
        assertEquals(ReleasedResource.Outcome.NEW_AVAILABLE,
                ReleasedResource.release(getClass(), V2, path, false, NOPLogger.NOP_LOGGER));
        // .new 已是最新内置版本 → 之后不再重复提示/写出
        assertEquals(ReleasedResource.Outcome.KEPT,
                ReleasedResource.release(getClass(), V2, path, false, NOPLogger.NOP_LOGGER));
    }

    @Test
    void identicalUnmarkedFileIsUpdatedInPlace() throws IOException {
        // 无版本标记但内容与内置完全一致 ⇒ 从未改过，可直接更新
        String builtin;
        try (var in = getClass().getResourceAsStream(V1)) {
            builtin = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path path = write("plain.yml", builtin);
        assertEquals(ReleasedResource.Outcome.KEPT,
                ReleasedResource.release(getClass(), V1, path, true, NOPLogger.NOP_LOGGER));

        // 无版本标记且内容不同 ⇒ 视为用户改过，只写 .new
        Path edited = write("edited.yml", "key: edited-by-user\n");
        assertEquals(ReleasedResource.Outcome.NEW_AVAILABLE,
                ReleasedResource.release(getClass(), V1, edited, true, NOPLogger.NOP_LOGGER));
        assertTrue(Files.readString(edited).contains("edited-by-user"));
        assertTrue(Files.exists(target("edited.yml.new")));
    }
}
