package io.github.railgun19457.velocityauth.bot.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议测试向量（docs/protocol/vectors）：规范化、签名输入、密钥派生、密封帧。
 * <p>
 * 这是硬性门槛——bot 侧与插件侧都必须逐字节一致。
 */
@Timeout(30)
class ProtocolVectorsTest {

    private static final Path VECTORS = Path.of("docs", "protocol", "vectors");

    private static JsonObject load(String name) throws Exception {
        Path path = VECTORS.resolve(name);
        assertTrue(Files.isRegularFile(path), "找不到测试向量：" + path.toAbsolutePath());
        return Json.parseObject(Files.readString(path));
    }

    @Test
    void canonicalizationMatchesVectors() throws Exception {
        JsonObject root = load("canonicalization.json");
        for (var element : root.getAsJsonArray("cases")) {
            JsonObject caseObject = element.getAsJsonObject();
            Map<String, Object> input = Json.toMap(caseObject.getAsJsonObject("input"));
            assertEquals(caseObject.get("canonical").getAsString(), Canonical.of(input));
        }
    }

    @Test
    void keysAndFingerprintsMatchVectors() throws Exception {
        JsonObject root = load("keys.json");
        JsonObject client = root.getAsJsonObject("client_ed25519");
        JsonObject server = root.getAsJsonObject("server_ed25519");

        byte[] clientRaw = Base64Url.decode(client.get("public_key_b64url").getAsString());
        byte[] serverRaw = Base64Url.decode(server.get("public_key_b64url").getAsString());
        assertEquals(Crypto.RAW_KEY_LENGTH, clientRaw.length);
        assertEquals(Crypto.RAW_KEY_LENGTH, serverRaw.length);

        // 裸公钥 ↔ 公钥对象 往返一致
        assertEquals(Base64Url.encode(clientRaw),
                Base64Url.encode(Crypto.rawPublicKey(Crypto.ed25519Public(clientRaw))));
        assertEquals(Base64Url.encode(serverRaw),
                Base64Url.encode(Crypto.rawPublicKey(Crypto.ed25519Public(serverRaw))));

        // 指纹 = sha256(裸公钥) 的 b64url
        assertEquals(client.get("public_key_sha256_b64url").getAsString(),
                Hashes.sha256B64Url(clientRaw));
        assertEquals(server.get("public_key_sha256_b64url").getAsString(),
                Hashes.sha256B64Url(serverRaw));

        // 从 seed 构造私钥 + 签名/验签往返
        var privateKey = Crypto.ed25519Private(hex(server.get("seed_hex").getAsString()));
        byte[] signature = Crypto.ed25519Sign(privateKey, Crypto.utf8("hello"));
        assertTrue(Crypto.ed25519Verify(Crypto.ed25519Public(serverRaw), Crypto.utf8("hello"), signature));

        // 新生成的密钥对长度正确
        var generated = Crypto.ed25519Generate();
        assertEquals(Crypto.RAW_KEY_LENGTH, Crypto.rawPublicKey(generated.getPublic()).length);

        // X25519：裸公钥往返 + 双方协商出同一 shared secret
        JsonObject clientEph = root.getAsJsonObject("client_x25519_ephemeral");
        JsonObject serverEph = root.getAsJsonObject("server_x25519_ephemeral");
        byte[] clientPub = Base64Url.decode(clientEph.get("public_key_b64url").getAsString());
        byte[] serverPub = Base64Url.decode(serverEph.get("public_key_b64url").getAsString());
        assertEquals(Base64Url.encode(clientPub),
                Base64Url.encode(Crypto.rawX25519PublicKey(Crypto.x25519Public(clientPub))));

        var clientPrivate = Crypto.x25519Private(hex(clientEph.get("private_key_hex").getAsString()));
        var serverPrivate = Crypto.x25519Private(hex(serverEph.get("private_key_hex").getAsString()));
        byte[] shared = Crypto.x25519SharedSecret(clientPrivate, Crypto.x25519Public(serverPub));
        byte[] sharedOther = Crypto.x25519SharedSecret(serverPrivate, Crypto.x25519Public(clientPub));
        assertEquals(Base64Url.encode(shared), Base64Url.encode(sharedOther));
        assertEquals(32, shared.length);
    }

    @Test
    void pairingFramesMatchVectors() throws Exception {
        JsonObject root = load("pairing.json");
        String code = root.get("code").getAsString();
        JsonObject serverKeys = load("keys.json").getAsJsonObject("server_ed25519");
        var serverPrivate = Crypto.ed25519Private(hex(serverKeys.get("seed_hex").getAsString()));

        for (var element : root.getAsJsonArray("frames")) {
            JsonObject entry = element.getAsJsonObject();
            Frame frame = Frame.parse(entry.getAsJsonObject("frame").toString());
            String expectedInput = entry.get("signature_input").getAsString();

            String input = Transcript.signatureInput("pair", frame.type(), frame.id(), frame.ts(),
                    frame.nonce(), frame.payload(), code);
            assertEquals(expectedInput, input, "signature_input 不一致：" + frame.type());

            // 服务器签名的两帧必须能逐字节复现
            if (frame.type().equals("pair.challenge") || frame.type().equals("pair.complete")) {
                String produced = Base64Url.encode(
                        Crypto.ed25519Sign(serverPrivate, input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                assertEquals(frame.sig(), produced, "签名不一致：" + frame.type());
            }
            // 所有帧的签名都必须可验证
            JsonObject clientKeys = load("keys.json").getAsJsonObject("client_ed25519");
            var verifyKey = frame.type().equals("pair.challenge") || frame.type().equals("pair.complete")
                    ? Crypto.ed25519Public(Base64Url.decode(serverKeys.get("public_key_b64url").getAsString()))
                    : Crypto.ed25519Public(Base64Url.decode(clientKeys.get("public_key_b64url").getAsString()));
            assertTrue(Crypto.ed25519Verify(verifyKey,
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            Base64Url.decode(frame.sig())),
                    "验签失败：" + frame.type());
        }
    }

    @Test
    void capabilitiesFingerprintMatchesVectors() throws Exception {
        JsonObject pairing = load("pairing.json");
        JsonObject prove = pairing.getAsJsonArray("frames").get(2).getAsJsonObject()
                .getAsJsonObject("frame").getAsJsonObject("payload");
        String expected = prove.get("client_capabilities_sha256").getAsString();
        assertEquals(expected, Transcript.capabilitiesSha256(List.of("verify.v1")));
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
