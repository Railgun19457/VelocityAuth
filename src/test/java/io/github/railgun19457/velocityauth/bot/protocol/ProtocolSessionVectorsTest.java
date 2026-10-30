package io.github.railgun19457.velocityauth.bot.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话握手 + 密钥派生 + 密封帧的测试向量校验。
 */
@Timeout(30)
class ProtocolSessionVectorsTest {

    private static final Path VECTORS = Path.of("docs", "protocol", "vectors");

    private static JsonObject load(String name) throws Exception {
        Path path = VECTORS.resolve(name);
        assertTrue(Files.isRegularFile(path), "找不到测试向量：" + path.toAbsolutePath());
        return Json.parseObject(Files.readString(path));
    }

    @Test
    void authFramesMatchVectors() throws Exception {
        JsonObject session = load("session.json");
        JsonObject keys = load("keys.json");
        var serverPrivate = Crypto.ed25519Private(hex(keys.getAsJsonObject("server_ed25519")
                .get("seed_hex").getAsString()));
        var serverPublic = Crypto.ed25519Public(Base64Url.decode(keys.getAsJsonObject("server_ed25519")
                .get("public_key_b64url").getAsString()));
        var clientPublic = Crypto.ed25519Public(Base64Url.decode(keys.getAsJsonObject("client_ed25519")
                .get("public_key_b64url").getAsString()));

        for (var element : session.getAsJsonArray("frames")) {
            JsonObject entry = element.getAsJsonObject();
            Frame frame = Frame.parse(entry.getAsJsonObject("frame").toString());
            String input = Transcript.signatureInput("auth", frame.type(), frame.id(), frame.ts(),
                    frame.nonce(), frame.payload(), null);
            assertEquals(entry.get("signature_input").getAsString(), input, "signature_input 不一致");

            boolean serverSigned = frame.type().equals("auth.challenge");
            if (serverSigned) {
                assertEquals(frame.sig(), Base64Url.encode(
                        Crypto.ed25519Sign(serverPrivate, Crypto.utf8(input))));
            }
            assertTrue(Crypto.ed25519Verify(serverSigned ? serverPublic : clientPublic, Crypto.utf8(input),
                    Base64Url.decode(frame.sig())), "验签失败：" + frame.type());
        }
    }

    @Test
    void keyScheduleMatchesVectors() throws Exception {
        JsonObject session = load("session.json");
        JsonObject keys = load("keys.json");
        JsonObject schedule = session.getAsJsonObject("key_schedule");

        byte[] clientPrivateRaw = hex(keys.getAsJsonObject("client_x25519_ephemeral")
                .get("private_key_hex").getAsString());
        byte[] serverPublicRaw = Base64Url.decode(keys.getAsJsonObject("server_x25519_ephemeral")
                .get("public_key_b64url").getAsString());
        byte[] shared = Crypto.x25519SharedSecret(Crypto.x25519Private(clientPrivateRaw),
                Crypto.x25519Public(serverPublicRaw));
        assertEquals(schedule.get("shared_secret_b64url").getAsString(), Base64Url.encode(shared));

        JsonObject hello = session.getAsJsonArray("frames").get(0).getAsJsonObject()
                .getAsJsonObject("frame").getAsJsonObject("payload");
        JsonObject challenge = session.getAsJsonArray("frames").get(1).getAsJsonObject()
                .getAsJsonObject("frame").getAsJsonObject("payload");
        byte[] clientNonce = Base64Url.decode(hello.get("client_nonce").getAsString());
        byte[] serverNonce = Base64Url.decode(challenge.get("server_nonce").getAsString());

        KeySchedule.SessionKeys derived = KeySchedule.derive(clientNonce, serverNonce, shared);
        assertEquals(schedule.get("salt").getAsString(), Base64Url.encode(derived.salt()));
        assertEquals(schedule.get("prk").getAsString(), Base64Url.encode(derived.prk()));
        assertEquals(schedule.get("key_c2s").getAsString(), Base64Url.encode(derived.keyC2s()));
        assertEquals(schedule.get("key_s2c").getAsString(), Base64Url.encode(derived.keyS2c()));
    }

    @Test
    void sealedFramesMatchVectors() throws Exception {
        JsonObject session = load("session.json");
        JsonObject schedule = session.getAsJsonObject("key_schedule");
        byte[] keyC2s = Base64Url.decode(schedule.get("key_c2s").getAsString());
        byte[] keyS2c = Base64Url.decode(schedule.get("key_s2c").getAsString());

        for (var element : session.getAsJsonArray("sealed")) {
            JsonObject entry = element.getAsJsonObject();
            long seq = entry.get("seq").getAsLong();
            long epoch = entry.get("epoch").getAsLong();
            byte[] key = entry.get("key").getAsString().equals("key_c2s") ? keyC2s : keyS2c;

            assertEquals(entry.get("nonce_b64url").getAsString(), Base64Url.encode(Sealer.nonce(epoch, seq)));
            assertEquals(entry.get("aad").getAsString(),
                    new String(Sealer.aad(seq), java.nio.charset.StandardCharsets.UTF_8));

            assertNotNull(entry.get("plaintext"));

            // 用解析器还原内层明文，再重新构造，确保格式完全一致
            byte[] expectedPlaintext = entry.get("plaintext").getAsString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Transcript.ParsedMessage parsed = Transcript.parseMessage(expectedPlaintext);
            byte[] plaintext = Transcript.messagePlaintext(parsed.type(), parsed.id(), parsed.ts(),
                    parsed.payload());
            assertEquals(entry.get("plaintext").getAsString(),
                    new String(plaintext, java.nio.charset.StandardCharsets.UTF_8));

            byte[] ct = Sealer.encrypt(key, epoch, seq, plaintext);
            assertEquals(entry.get("ct").getAsString(), Base64Url.encode(ct));

            byte[] decrypted = Sealer.decrypt(key, epoch, seq, Base64Url.decode(entry.get("ct").getAsString()));
            assertArrayEquals(plaintext, decrypted);
        }
    }

    @Test
    void messagePlaintextRoundTrips() throws Exception {
        JsonObject session = load("session.json");
        JsonObject sealedEntry = session.getAsJsonArray("sealed").get(2).getAsJsonObject();
        byte[] plaintext = sealedEntry.get("plaintext").getAsString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Transcript.ParsedMessage parsed = Transcript.parseMessage(plaintext);
        assertEquals("verify.watch", parsed.type());
        assertEquals("r_1", parsed.id());
        assertEquals("MC-8F3K2A", parsed.payload().get("code"));
        assertEquals("123456", parsed.payload().get("group_id"));
        assertEquals("300", parsed.payload().get("ttl"));
    }

    @Test
    void replayIsRejected() throws Exception {
        byte[] key = new byte[32];
        Sealer.Outbound outbound = new Sealer.Outbound(key, 0);
        byte[] ct0 = outbound.seal(Crypto.utf8("a"));
        assertEquals(1, outbound.nextSeq());
        outbound.seal(Crypto.utf8("b"));
        assertEquals(2, outbound.nextSeq());

        Sealer.Inbound inbound = new Sealer.Inbound(key, 0);
        assertArrayEquals(Crypto.utf8("a"), inbound.open(0, ct0));
        ProtocolException error = org.junit.jupiter.api.Assertions.assertThrows(ProtocolException.class,
                () -> inbound.open(0, ct0));
        assertEquals(ProtocolException.REPLAY_DETECTED, error.code());
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
