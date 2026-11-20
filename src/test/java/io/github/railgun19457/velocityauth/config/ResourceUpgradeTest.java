package io.github.railgun19457.velocityauth.config;

import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.lang.LangService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceUpgradeTest {

    @TempDir
    Path directory;

    private ConfigService config() {
        return new ConfigService(directory, ConfigService.class, NOPLogger.NOP_LOGGER);
    }

    private Path language(String code, String content) throws IOException {
        Path dir = Files.createDirectories(directory.resolve("lang"));
        return Files.writeString(dir.resolve(code + ".yml"), content);
    }

    private static List<Path> backups(Path path) throws IOException {
        try (var files = Files.list(path.getParent())) {
            return files.filter(file -> file.getFileName().toString().startsWith(path.getFileName() + ".bak-"))
                    .toList();
        }
    }

    @Test
    void migratesLegacyQqAndKeepsSettingsCommentsAndConflicts() throws Exception {
        ConfigService service = config();
        Path path = service.configFile();
        String original = """
                # server runbook
                # version: 1
                qqbot:
                  enabled: true
                  port: 9876
                  group-id: "12345"
                bot:
                  port: 8899 # proxy binding
                auth:
                  premium:
                    enabled: true
                registration:
                  require-binding: qq
                binding:
                  email:
                    max-accounts-per-email: 7 # email accounts
                  qq:
                    enabled: true
                    max-accounts-per-qq: 3
                  social:
                    discord:
                      display-name: "Discord RP"
                verification:
                  email-code-length: 5
                  qq-code-length: 8
                smtp:
                  password: "test-secret" # custom SMTP password
                extension:
                  enabled: false
                """;
        Files.writeString(path, original);
        var prepared = service.prepare();
        assertTrue(prepared.file().notices().stream().anyMatch(line -> line.contains("bot.port")));
        assertTrue(prepared.file().notices().stream().noneMatch(line -> line.contains("test-secret")));

        var config = service.load();
        String result = Files.readString(path);
        assertTrue(config.bot().enabled());
        assertEquals(8899, config.bot().port());
        assertEquals(100, config.bot().admin().maxPageSize());
        assertEquals(7, config.binding().email().maxAccounts());
        assertEquals(3, config.binding().social().get("qq").maxAccounts());
        assertEquals(8, config.binding().social().get("qq").codeLength());
        assertEquals("Discord RP", config.binding().social().get("discord").displayName());
        assertEquals(5, config.verification().codeLength());
        assertEquals("test-secret", config.smtp().password());
        assertEquals(List.of("qq"), config.registration().requireBinding());
        assertTrue(result.startsWith("# server runbook\n# version: 5\n"), result);
        for (String comment : List.of("# proxy binding", "# email accounts", "# custom SMTP password")) {
            assertTrue(result.contains(comment), comment);
        }
        assertFalse(result.contains("qqbot:"));
        assertFalse(result.contains("group-id"));
        assertFalse(result.contains("premium:"));
        assertFalse(result.contains("max-accounts-per"));
        assertTrue(result.contains("extension:\n  enabled: false"));
        assertTrue(result.contains("max-page-size: 100"));
        assertEquals(original, Files.readString(backups(path).getFirst()));
        var lastWrite = Files.getLastModifiedTime(path);
        service.load();
        assertEquals(lastWrite, Files.getLastModifiedTime(path));
        assertEquals(1, backups(path).size());
    }

    @Test
    void keepsExistingDefaultsAndResolvedYamlAliases() throws Exception {
        ConfigService service = config();
        Files.writeString(service.configFile(), """
                # version: 4
                routing-example: &custom
                  login: gated
                  allowed: []
                servers:
                  <<: *custom
                  default: game
                binding:
                  social:
                    discord:
                      enabled: false
                verification:
                  code-length: 8
                """);
        var migrated = service.load();
        assertEquals("gated", migrated.servers().login());
        assertEquals(List.of(), migrated.servers().allowed());
        assertEquals(List.of(), migrated.binding().email().domain().list());
        assertFalse(migrated.binding().social().get("discord").enabled());
        assertFalse(migrated.binding().social().containsKey("qq"));
        assertEquals(8, migrated.verification().codeLength());
    }

    @Test
    void migratedQqKeysMergeIntoAnExistingCaseInsensitiveProvider() throws Exception {
        ConfigService service = config();
        Files.writeString(service.configFile(), """
                # version: 4
                binding:
                  qq:
                    max-accounts-per-qq: 3
                  social:
                    QQ:
                      max-accounts: 9
                verification:
                  qq-code-length: 7
                """);
        var provider = service.load().binding().social().get("qq");
        assertEquals(9, provider.maxAccounts());
        assertEquals(7, provider.codeLength());
        assertTrue(Files.readString(service.configFile()).contains("    QQ:"));
        assertFalse(Files.readString(service.configFile()).contains("    qq:"));
    }

    @Test
    void keepsUnknownConfigAndNewerVersions() throws Exception {
        ConfigService service = config();
        Path path = service.configFile();
        String future = "# version: 99\nsmtp:\n  password: \"\"\n";
        Files.writeString(path, future);
        assertEquals("", service.load().smtp().password());
        assertEquals(future, Files.readString(path));
        assertTrue(backups(path).isEmpty());

        String unmarked = "# note\nregistration:\n  unbound-ttl-minutes: 0\n"
                + "auth:\n  session:\n    enabled: false\n";
        Files.writeString(path, unmarked);
        var loaded = service.load();
        assertEquals(0, loaded.registration().unboundTtlMinutes());
        assertFalse(loaded.auth().session().enabled());
        assertTrue(Files.readString(path).startsWith("# version: 5\n# note\n"));
    }

    @Test
    void preservesUtf8BomAndCrLfAndLeavesExplicitlyEmptyChannelsAlone() throws Exception {
        ConfigService service = config();
        Path path = service.configFile();
        String old = "\uFEFF# version: 4\r\n# custom note\r\nbinding:\r\n  social: {}\r\n"
                + "verification:\r\n  qq-code-length: 7\r\n";
        Files.writeString(path, old);
        assertTrue(service.load().binding().social().isEmpty());
        String content = Files.readString(path);
        assertTrue(content.startsWith("\uFEFF# version: 5\r\n# custom note\r\n"));
        assertFalse(content.replace("\r\n", "").contains("\n"));
        assertFalse(content.contains("qq-code-length"));
        assertEquals(old, Files.readString(backups(path).getFirst()));
    }

    @Test
    void invalidOrConcurrentEditsNeverReplaceTheOriginal() throws Exception {
        ConfigService service = config();
        var old = service.load();
        Path path = service.configFile();
        String invalid = "# version: 4\nservers:\n  login: 42\n";
        Files.writeString(path, invalid);
        assertThrows(ConfigException.class, service::load);
        assertSame(old, service.config());
        assertEquals(invalid, Files.readString(path));
        assertTrue(backups(path).isEmpty());

        Files.writeString(path, "# version: 4\nservers:\n  login: lobby\n");
        var candidate = service.prepare();
        String newerEdit = "# version: 4\nservers:\n  login: edited\n";
        Files.writeString(path, newerEdit);
        assertThrows(IOException.class, () -> candidate.file().save(NOPLogger.NOP_LOGGER));
        assertEquals(newerEdit, Files.readString(path));
        assertTrue(backups(path).isEmpty());
    }

    @Test
    void languageUpdatesOldDefaultsAndKeepsCustomizedAndDottedValues() throws Exception {
        String english = """
                # version: 4
                # server language notes
                command:
                  logout:
                    success: "<yellow>Logged out. Please reconnect.</yellow>" # log out hint
                admin.account.info-online-time: "Custom number {hours}"
                auth:
                  name-occupied: "retired"
                  extension: "kept"
                admin:
                  status.qqbot: "Custom bot status {value}"
                """;
        Path en = language("en_us", english);
        Path zh = language("zh_cn", "# version: 4\ncommand.logout.success: \"已下线，自定义提示\"\n");
        var plan = LangService.prepare("en_us", LangService.class, directory, NOPLogger.NOP_LOGGER);
        plan.save(NOPLogger.NOP_LOGGER);
        var defaults = LangService.load("en_us", LangService.class, null, NOPLogger.NOP_LOGGER);

        assertEquals(defaults.rawText("command.logout.success"), plan.language().rawText("command.logout.success"));
        assertEquals("Custom number {hours}", plan.language().rawText("admin.account.info-online-time"));
        assertEquals("Custom bot status {value}", plan.language().rawText("admin.status.bot"));
        assertEquals("kept", plan.language().rawText("auth.extension"));
        assertFalse(plan.language().has("auth.name-occupied"));
        assertFalse(plan.language().has("admin.status.qqbot"));
        String migrated = Files.readString(en);
        assertTrue(migrated.startsWith("# version: 8\n# server language notes\n"));
        assertTrue(migrated.contains("# log out hint"));
        assertEquals(english, Files.readString(backups(en).getFirst()));
        assertTrue(Files.readString(zh).contains("admin:"));
        assertEquals("已下线，自定义提示",
                LangService.load("zh_cn", LangService.class, directory, NOPLogger.NOP_LOGGER)
                        .rawText("command.logout.success"));
        LangService.prepare("en_us", LangService.class, directory, NOPLogger.NOP_LOGGER).save(NOPLogger.NOP_LOGGER);
        assertEquals(migrated, Files.readString(en));
        assertEquals(1, backups(en).size());
    }

    @Test
    void aCustomLanguageUsesEnglishFallbackWithoutRewritingTheFile() throws Exception {
        String custom = "# notes\nprefix: \"My server\"\ncustom.example: \"kept\"\n";
        Path path = language("my_locale", custom);
        LangService.Prepared prepared = LangService.prepare(
                "my_locale", LangService.class, directory, NOPLogger.NOP_LOGGER);
        prepared.save(NOPLogger.NOP_LOGGER);
        assertEquals("my_locale", prepared.language().code());
        assertEquals("kept", prepared.language().rawText("custom.example"));
        assertTrue(prepared.language().rawText("admin.account.info-online-time").contains("Total online time"));
        assertEquals(custom, Files.readString(path));
        assertTrue(backups(path).isEmpty());
    }

    @Test
    void invalidActiveLanguageAbortsBeforeAnyPreparedWrites() throws Exception {
        Path path = language("zh_cn", "# version: 7\nprefix: [unterminated\n");
        ConfigService service = config();
        Files.writeString(service.configFile(), "# version: 4\nservers:\n  login: gate\n");
        String original = Files.readString(service.configFile());
        var candidate = service.prepare();
        assertThrows(ConfigException.class, () -> LangService.prepare(
                candidate.config().language(), LangService.class, directory, NOPLogger.NOP_LOGGER));
        assertEquals(original, Files.readString(service.configFile()));
        assertEquals("# version: 7\nprefix: [unterminated\n", Files.readString(path));
        assertTrue(backups(path).isEmpty());
        assertFalse(Files.exists(directory.resolve("lang/en_us.yml")));
    }

    @Test
    void failedReloadKeepsTheActiveConfigAndLanguageTogether() throws Exception {
        VelocityAuthPlugin plugin = new VelocityAuthPlugin(null, NOPLogger.NOP_LOGGER, directory);
        plugin.reload();
        var previousConfig = plugin.config();
        var previousLanguage = plugin.lang();
        Path path = config().configFile();
        String candidate = "# version: 4\nlanguage: en_us\nservers:\n  login: gated\n";
        Files.writeString(path, candidate);
        language("en_us", "# version: 7\nprefix: [unterminated\n");

        assertThrows(ConfigException.class, plugin::reload);
        assertSame(previousConfig, plugin.config());
        assertSame(previousLanguage, plugin.lang());
        assertEquals(candidate, Files.readString(path));
        assertTrue(backups(path).isEmpty());

        language("en_us", "# version: 7\nprefix: \"Custom prefix\"\n");
        plugin.reload();
        assertEquals("gated", plugin.config().servers().login());
        assertEquals("en_us", plugin.lang().code());
        assertEquals("Custom prefix", plugin.lang().rawText("prefix"));
    }
}
