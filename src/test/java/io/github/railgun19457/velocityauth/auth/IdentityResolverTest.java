package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.platform.FloodgateHook;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.helpers.NOPLogger;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份解析（Java 连接名 vs 基岩 Floodgate UUID）与重连绑定 key 的隔离性。
 */
@Timeout(30)
class IdentityResolverTest {

    private static FloodgateHook fakeFloodgate(Set<UUID> bedrockIds) {
        return new FloodgateHook(true, new io.github.railgun19457.velocityauth.util.PluginLogger(
                NOPLogger.NOP_LOGGER)) {
            @Override
            public boolean isFloodgatePlayer(UUID uuid) {
                return bedrockIds.contains(uuid);
            }
        };
    }

    @Test
    void javaPlayerUsesLowercasedConnectionName() {
        IdentityResolver resolver = new IdentityResolver(fakeFloodgate(Set.of()));
        UUID uuid = UUID.nameUUIDFromBytes("OfflinePlayer:Test2".getBytes());
        IdentityResolver.Resolved resolved = resolver.resolve(uuid, "Test2");
        assertEquals(IdentityKind.JAVA, resolved.kind());
        assertEquals("test2", resolved.identifier());
        assertEquals("java:test2", resolved.key());
        assertFalse(resolved.bedrock());
    }

    @Test
    void floodgatePlayerUsesFloodgateUuid() {
        UUID bedrockUuid = UUID.nameUUIDFromBytes("Floodgate:12345".getBytes());
        IdentityResolver resolver = new IdentityResolver(fakeFloodgate(Set.of(bedrockUuid)));
        // 基岩玩家连接名通常是 ".Steve"，直接用它做身份会让所有基岩玩家互相顶号
        IdentityResolver.Resolved resolved = resolver.resolve(bedrockUuid, ".Steve");
        assertEquals(IdentityKind.BEDROCK, resolved.kind());
        assertEquals(bedrockUuid.toString(), resolved.identifier());
        assertEquals("bedrock:" + bedrockUuid, resolved.key());
        assertTrue(resolved.bedrock());
    }

    @Test
    void twoBedrockPlayersDoNotCollide() {
        UUID first = UUID.nameUUIDFromBytes("Floodgate:1".getBytes());
        UUID second = UUID.nameUUIDFromBytes("Floodgate:2".getBytes());
        IdentityResolver resolver = new IdentityResolver(fakeFloodgate(Set.of(first, second)));
        assertFalse(resolver.resolve(first, ".Steve").key().equals(resolver.resolve(second, ".Steve").key()));
    }

    @Test
    void withoutFloodgateEverythingIsJava() {
        IdentityResolver resolver = new IdentityResolver(new FloodgateHook(false, null) {
        });
        IdentityResolver.Resolved resolved = resolver.resolve(UUID.randomUUID(), ".Steve");
        assertEquals(IdentityKind.JAVA, resolved.kind());
        assertEquals(".steve", resolved.identifier());
        assertFalse(resolver.floodgateAvailable());
    }

    @Test
    void javaAndBedrockKeysNeverCollide() {
        UUID bedrockUuid = UUID.nameUUIDFromBytes("Floodgate:9".getBytes());
        IdentityResolver resolver = new IdentityResolver(fakeFloodgate(Set.of(bedrockUuid)));
        String javaKey = resolver.resolve(UUID.randomUUID(), bedrockUuid.toString()).key();
        String bedrockKey = resolver.resolve(bedrockUuid, bedrockUuid.toString()).key();
        assertFalse(javaKey.equals(bedrockKey));
    }
}
