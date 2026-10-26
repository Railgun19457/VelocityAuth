package io.github.railgun19457.velocityauth.auth;

import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AuthCoreTest {

    @Test
    void offlineUuidMatchesVelocityAlgorithm() {
        for (String name : new String[]{"test1", "alice", "a_b_c", "player123"}) {
            UUID expected = GameProfile.forOfflinePlayer(name).getId();
            assertEquals(expected, ProfileFactory.offlineUuid(name), "UUID 不一致：" + name);
        }
    }

    @Test
    void accountNameCaseInsensitiveProducesSameUuid() {
        assertEquals(ProfileFactory.offlineUuid("test1"), ProfileFactory.offlineUuid("TEST1".toLowerCase()));
    }

    @Test
    void bcryptHashAndVerify() {
        PasswordHasher hasher = new PasswordHasher(4);
        String hash = hasher.hash("s3cret-password");
        assertTrue(hash.startsWith("$2"));
        assertTrue(hasher.verify("s3cret-password", hash));
        assertFalse(hasher.verify("wrong", hash));
        assertFalse(hasher.verify("s3cret-password", "not-a-hash"));
    }

    @Test
    void pendingBindingIsConsumedOnceAndScopedByIp() {
        PendingBindingStore store = new PendingBindingStore();
        store.put("test2", "1.2.3.4", 42L);

        assertTrue(store.peek("test2", "1.2.3.4").isPresent());
        assertTrue(store.peek("test2", "5.6.7.8").isEmpty(), "不同 IP 不应命中");

        assertEquals(Optional.of(42L), store.consume("test2", "1.2.3.4"));
        assertTrue(store.consume("test2", "1.2.3.4").isEmpty(), "消费即删");
    }

    @Test
    void pendingBindingExpires() throws InterruptedException {
        PendingBindingStore store = new PendingBindingStore();
        store.put("test2", "1.2.3.4", 7L, Duration.ofMillis(20));
        Thread.sleep(40);
        assertTrue(store.consume("test2", "1.2.3.4").isEmpty());
    }

    @Test
    void rateLimiterEnforcesWindow() {
        RateLimiter limiter = new RateLimiter();
        Duration window = Duration.ofMinutes(1);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("ip:1.2.3.4", 3, window), "第 " + (i + 1) + " 次应通过");
        }
        assertFalse(limiter.tryAcquire("ip:1.2.3.4", 3, window));
        assertEquals(0, limiter.remaining("ip:1.2.3.4", 3, window));
        limiter.reset("ip:1.2.3.4");
        assertTrue(limiter.tryAcquire("ip:1.2.3.4", 3, window));
    }

    @Test
    void rateLimiterSeparatesKeys() {
        RateLimiter limiter = new RateLimiter();
        Duration window = Duration.ofMinutes(1);
        assertTrue(limiter.tryAcquire("a", 1, window));
        assertFalse(limiter.tryAcquire("a", 1, window));
        assertTrue(limiter.tryAcquire("b", 1, window));
    }

    @Test
    void sessionRegistryTracksAuthentication() {
        AuthSessionRegistry registry = new AuthSessionRegistry();
        UUID uuid = UUID.randomUUID();
        AuthSession session = new AuthSession("Test2", "1.2.3.4");
        assertFalse(session.authenticated());
        registry.stage(uuid, session);
        assertTrue(registry.get(uuid).isPresent());
        assertTrue(registry.authenticatedSessions().isEmpty());
        assertNotEquals(0, registry.size());
    }
}
