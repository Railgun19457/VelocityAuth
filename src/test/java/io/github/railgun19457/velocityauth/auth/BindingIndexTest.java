package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绑定索引：预热、增量更新、删除清理，以及「门槛判断」的语义。
 */
@Timeout(30)
class BindingIndexTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private BindingIndex index;

    @BeforeEach
    void setUp() throws Exception {
        storage = StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("binding-index.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", "")), tempDir, NOPLogger.NOP_LOGGER);
        index = new BindingIndex(storage.bindings(), new PluginLogger(NOPLogger.NOP_LOGGER));
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private long account(String name) {
        return storage.accounts().insert(name, name.toLowerCase(), UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, null, 1L);
    }

    @Test
    void warmupReadsExistingBindings() {
        long emailOnly = account("EmailOnly");
        long qqOnly = account("QqOnly");
        long both = account("Both");
        storage.bindings().insert(emailOnly, BindingType.EMAIL, "a@qq.com", 1L, 1L);
        storage.bindings().insert(qqOnly, BindingType.QQ, "10001", 1L, 1L);
        storage.bindings().insert(both, BindingType.EMAIL, "b@qq.com", 1L, 1L);
        storage.bindings().insert(both, BindingType.QQ, "10002", 1L, 1L);

        index.load();

        assertEquals(3, index.size());
        assertTrue(index.satisfies(emailOnly, List.of(BindingType.EMAIL)));
        assertFalse(index.satisfies(emailOnly, List.of(BindingType.QQ)));
        assertTrue(index.satisfies(both, List.of(BindingType.EMAIL, BindingType.QQ)));
        // 空要求 = 无需绑定
        assertTrue(index.satisfies(emailOnly, List.of()));
        // 完全没有绑定的账号
        assertFalse(index.satisfies(account("NoBinding"), List.of(BindingType.EMAIL)));
    }

    @Test
    void putAndRemoveKeepMaskInSync() {
        long id = account("Sync");
        assertFalse(index.satisfies(id, List.of(BindingType.EMAIL, BindingType.QQ)));

        index.put(id, BindingType.EMAIL);
        assertTrue(index.satisfies(id, List.of(BindingType.EMAIL)));
        assertFalse(index.satisfies(id, List.of(BindingType.QQ)));

        index.put(id, BindingType.QQ);
        assertTrue(index.satisfies(id, List.of(BindingType.QQ)));

        // 解绑一个，另一个仍在 → 仍满足「任选其一」
        index.remove(id, BindingType.EMAIL);
        assertFalse(index.satisfies(id, List.of(BindingType.EMAIL)));
        assertTrue(index.satisfies(id, List.of(BindingType.QQ)));

        // 解绑最后一个 → 该账号从索引中消失
        index.remove(id, BindingType.QQ);
        assertEquals(0, index.size());
        assertFalse(index.satisfies(id, List.of(BindingType.EMAIL, BindingType.QQ)));
    }

    @Test
    void removeAccountDropsEverything() {
        long id = account("Gone");
        index.put(id, BindingType.EMAIL);
        index.put(id, BindingType.QQ);
        index.removeAccount(id);
        assertEquals(0, index.size());
        assertFalse(index.satisfies(id, List.of(BindingType.EMAIL)));
    }

    /**
     * {@code /unbind} 前置校验的语义：只有「解绑后仍能由其它渠道满足要求」才允许解绑。
     * <p>
     * 这里直接验证索引层面的判断（{@code AuthService.bindingSatisfiedWithout} 就是去掉该渠道后再问一次）。
     */
    @Test
    void unbindGuardSemantics() {
        long onlyEmail = account("OnlyEmail");
        index.put(onlyEmail, BindingType.EMAIL);
        // 要求 [email, qq]：去掉 email 后没有任何渠道 → 不允许解绑
        assertFalse(index.satisfies(onlyEmail, List.of(BindingType.QQ)));
        // 要求 [email]：去掉 email 后要求为空 → 视为满足（配置里 email 是唯一要求，解绑即等于关闭要求）
        assertTrue(index.satisfies(onlyEmail, List.of()));

        long both = account("BothTwo");
        index.put(both, BindingType.EMAIL);
        index.put(both, BindingType.QQ);
        // 要求 [email, qq]：去掉 email 后还有 qq → 允许解绑
        assertTrue(index.satisfies(both, List.of(BindingType.QQ)));
    }
}
