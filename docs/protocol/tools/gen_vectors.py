#!/usr/bin/env python3
"""Generate deterministic test vectors for the VelocityAuth bot protocol v1.

Vectors are produced from fixed keys / nonces / timestamps so that any
independent implementation (Java plugin side, Python bot side, ...) can verify
that its canonicalization, signature input, HKDF schedule and AES-GCM sealing
match the reference byte-for-byte.

Run:  python docs/protocol/tools/gen_vectors.py
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

ROOT = Path(__file__).resolve().parent.parent
VECTORS = ROOT / "vectors"

V = 1


# --------------------------------------------------------------------------
# canonicalization / transcript
# --------------------------------------------------------------------------

def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def sha256_b64u(data: bytes) -> str:
    return b64u(hashlib.sha256(data).digest())


def _render(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return value.replace("\r", " ").replace("\n", " ")
    if isinstance(value, (list, tuple)):
        return ",".join(sorted(_render(item) for item in value))
    raise TypeError(f"unsupported payload value: {type(value)!r}")


def canonical(payload: dict) -> str:
    return "".join(f"{key}={_render(payload[key])}\n" for key in sorted(payload))


def signature_input(context: str, type_: str, id_: str, ts: int, nonce: str,
                    payload: dict, code: str | None = None) -> str:
    text = "VELOCITYAUTH-V1\n"
    text += f"context={context}\n"
    text += f"type={type_}\n"
    text += f"id={id_}\n"
    text += f"ts={ts}\n"
    text += f"nonce={nonce}\n"
    if code is not None:
        text += f"code={code}\n"
    text += canonical(payload)
    return text


def message_plaintext(type_: str, id_: str, ts: int, payload: dict) -> bytes:
    text = "VELOCITYAUTH-MSG-V1\n"
    text += f"type={type_}\n"
    text += f"id={id_}\n"
    text += f"ts={ts}\n"
    text += canonical(payload)
    return text.encode("utf-8")


def capabilities_sha256(capabilities) -> str:
    return sha256_b64u("\n".join(sorted(capabilities)).encode("utf-8"))


# --------------------------------------------------------------------------
# key schedule
# --------------------------------------------------------------------------

def hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    return hmac.new(salt, ikm, hashlib.sha256).digest()


def hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    okm = b""
    block = b""
    counter = 1
    while len(okm) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        okm += block
        counter += 1
    return okm[:length]


def derive_session_keys(client_nonce_b64: str, server_nonce_b64: str,
                        shared_secret: bytes) -> dict:
    client_nonce = base64.urlsafe_b64decode(client_nonce_b64 + "==")
    server_nonce = base64.urlsafe_b64decode(server_nonce_b64 + "==")
    salt = hashlib.sha256(b"VELOCITYAUTH-V1-SALT" + client_nonce + server_nonce).digest()
    prk = hkdf_extract(salt, shared_secret)
    return {
        "salt": b64u(salt),
        "prk": b64u(prk),
        "key_c2s": b64u(hkdf_expand(prk, b"velocityauth c2s", 32)),
        "key_s2c": b64u(hkdf_expand(prk, b"velocityauth s2c", 32)),
        "_key_c2s": hkdf_expand(prk, b"velocityauth c2s", 32),
        "_key_s2c": hkdf_expand(prk, b"velocityauth s2c", 32),
    }


def seal_nonce(epoch: int, seq: int) -> bytes:
    return epoch.to_bytes(4, "big") + seq.to_bytes(8, "big")


def seal_aad(seq: int) -> bytes:
    return f"VELOCITYAUTH-SEAL-V1\nv={V}\nseq={seq}\n".encode("utf-8")


def seal(key: bytes, epoch: int, seq: int, type_: str, id_: str, ts: int,
         payload: dict) -> str:
    nonce = seal_nonce(epoch, seq)
    plaintext = message_plaintext(type_, id_, ts, payload)
    return b64u(AESGCM(key).encrypt(nonce, plaintext, seal_aad(seq)))


# --------------------------------------------------------------------------

def main() -> None:
    VECTORS.mkdir(parents=True, exist_ok=True)

    bot_ed_seed = bytes(range(32))
    server_ed_seed = bytes(range(32, 64))
    bot_ed = Ed25519PrivateKey.from_private_bytes(bot_ed_seed)
    server_ed = Ed25519PrivateKey.from_private_bytes(server_ed_seed)
    bot_pub = bot_ed.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    server_pub = server_ed.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)

    bot_eph = X25519PrivateKey.from_private_bytes(bytes(range(64, 96)))
    server_eph = X25519PrivateKey.from_private_bytes(bytes(range(96, 128)))
    bot_eph_pub = bot_eph.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    server_eph_pub = server_eph.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)

    (VECTORS / "keys.json").write_text(json.dumps({
        "warning": "TEST KEYS ONLY - never use in production",
        "client_ed25519": {"seed_hex": bot_ed_seed.hex(),
                           "public_key_b64url": b64u(bot_pub),
                           "public_key_sha256_b64url": sha256_b64u(bot_pub),
                           "key_id": "bot-k1"},
        "server_ed25519": {"seed_hex": server_ed_seed.hex(),
                           "public_key_b64url": b64u(server_pub),
                           "public_key_sha256_b64url": sha256_b64u(server_pub),
                           "key_id": "srv-k1"},
        "client_x25519_ephemeral": {"private_key_hex": bytes(range(64, 96)).hex(),
                                    "public_key_b64url": b64u(bot_eph_pub)},
        "server_x25519_ephemeral": {"private_key_hex": bytes(range(96, 128)).hex(),
                                    "public_key_b64url": b64u(server_eph_pub)},
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    (VECTORS / "canonicalization.json").write_text(json.dumps({
        "rule": "keys sorted by UTF-8 byte order; 'key=value\\n'; arrays sorted and comma-joined",
        "cases": [
            {"input": {"b": 2, "a": "x", "c": ["z", "a"]}, "canonical": "a=x\nb=2\nc=a,z\n"},
            {"input": {"ts": 1730000000, "ok": True, "empty": None},
             "canonical": "empty=\nok=true\nts=1730000000\n"},
        ],
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # ---- pairing ----
    code = "kZ3r9Qm2vT7xLpW1bN6yHc4dFs8jA5eU"
    pairing_id = "01J8Z6Q0PAIRING0000000000"
    ts = 1730000000
    hello_nonce = b64u(bytes(range(0x10, 0x20)))
    challenge_nonce = b64u(bytes(range(0x20, 0x30)))
    prove_nonce = b64u(bytes(range(0x30, 0x40)))
    complete_nonce = b64u(bytes(range(0x40, 0x50)))

    hello_payload = {"pairing_id": pairing_id, "client_id": "astrbot-1",
                     "client_key_id": "bot-k1", "client_public_key": b64u(bot_pub),
                     "capabilities": ["verify.v1"]}
    hello_si = signature_input("pair", "pair.hello", "f_1", ts, hello_nonce, hello_payload, code=code)
    hello_sig = b64u(bot_ed.sign(hello_si.encode()))

    challenge_payload = {"pairing_id": pairing_id, "challenge_id": "ch_1", "expires_at": ts + 600,
                         "server_id": "velocity-1", "server_key_id": "srv-k1",
                         "server_public_key": b64u(server_pub), "server_nonce": challenge_nonce,
                         "selected_v": 1, "capabilities": ["verify.v1"]}
    challenge_si = signature_input("pair", "pair.challenge", "f_2", ts, challenge_nonce,
                                   challenge_payload, code=code)
    challenge_sig = b64u(server_ed.sign(challenge_si.encode()))

    prove_payload = {"pairing_id": pairing_id, "challenge_id": "ch_1", "hello_id": "f_1",
                     "client_id": "astrbot-1", "client_key_id": "bot-k1",
                     "client_public_key_sha256": sha256_b64u(bot_pub), "server_id": "velocity-1",
                     "server_key_id": "srv-k1", "server_public_key_sha256": sha256_b64u(server_pub),
                     "client_nonce": hello_nonce, "server_nonce": challenge_nonce, "selected_v": 1,
                     "client_capabilities_sha256": capabilities_sha256(["verify.v1"]),
                     "server_capabilities_sha256": capabilities_sha256(["verify.v1"])}
    prove_si = signature_input("pair", "pair.prove", "f_3", ts, prove_nonce, prove_payload, code=code)
    prove_sig = b64u(bot_ed.sign(prove_si.encode()))

    complete_payload = {"ok": True, "pairing_id": pairing_id, "session_id": "sess_pair_1",
                        "client_key_id": "bot-k1",
                        "client_key_fingerprint": sha256_b64u(bot_pub),
                        "server_key_id": "srv-k1",
                        "server_key_fingerprint": sha256_b64u(server_pub),
                        "server_time": ts, "selected_v": 1, "capabilities": ["verify.v1"]}
    complete_si = signature_input("pair", "pair.complete", "f_4", ts, complete_nonce,
                                  complete_payload, code=code)
    complete_sig = b64u(server_ed.sign(complete_si.encode()))

    (VECTORS / "pairing.json").write_text(json.dumps({
        "note": "sig is Ed25519 over signature_input(...); code is NEVER transmitted",
        "code": code, "pairing_id": pairing_id,
        "frames": [
            {"frame": {"v": 1, "kind": "plain", "type": "pair.hello", "id": "f_1", "ts": ts,
                       "nonce": hello_nonce, "payload": hello_payload, "sig": hello_sig},
             "signature_input": hello_si},
            {"frame": {"v": 1, "kind": "plain", "type": "pair.challenge", "id": "f_2", "ts": ts,
                       "nonce": challenge_nonce, "payload": challenge_payload, "sig": challenge_sig},
             "signature_input": challenge_si},
            {"frame": {"v": 1, "kind": "plain", "type": "pair.prove", "id": "f_3", "ts": ts,
                       "nonce": prove_nonce, "payload": prove_payload, "sig": prove_sig},
             "signature_input": prove_si},
            {"frame": {"v": 1, "kind": "plain", "type": "pair.complete", "id": "f_4", "ts": ts,
                       "nonce": complete_nonce, "payload": complete_payload, "sig": complete_sig},
             "signature_input": complete_si},
        ],
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # ---- auth + sealing ----
    client_nonce = b64u(bytes(range(0x50, 0x60)))
    server_nonce = b64u(bytes(range(0x60, 0x70)))

    ah_payload = {"client_id": "astrbot-1", "client_key_id": "bot-k1",
                  "client_public_key_sha256": sha256_b64u(bot_pub),
                  "client_ephemeral_key": b64u(bot_eph_pub), "client_nonce": client_nonce,
                  "capabilities": ["verify.v1"]}
    ah_nonce = b64u(bytes(range(0x70, 0x80)))
    ah_si = signature_input("auth", "auth.hello", "f_10", ts, ah_nonce, ah_payload)
    ah_sig = b64u(bot_ed.sign(ah_si.encode()))

    ac_payload = {"challenge_id": "ch_9", "expires_at": ts + 60, "server_id": "velocity-1",
                  "server_key_id": "srv-k1", "server_public_key_sha256": sha256_b64u(server_pub),
                  "server_ephemeral_key": b64u(server_eph_pub), "server_nonce": server_nonce,
                  "selected_v": 1, "capabilities": ["verify.v1"]}
    ac_nonce = b64u(bytes(range(0x80, 0x90)))
    ac_si = signature_input("auth", "auth.challenge", "f_11", ts, ac_nonce, ac_payload)
    ac_sig = b64u(server_ed.sign(ac_si.encode()))

    ap_payload = {"challenge_id": "ch_9", "hello_id": "f_10", "client_id": "astrbot-1",
                  "client_key_id": "bot-k1", "client_public_key_sha256": sha256_b64u(bot_pub),
                  "client_ephemeral_key": b64u(bot_eph_pub), "server_id": "velocity-1",
                  "server_key_id": "srv-k1", "server_public_key_sha256": sha256_b64u(server_pub),
                  "server_ephemeral_key": b64u(server_eph_pub), "client_nonce": client_nonce,
                  "server_nonce": server_nonce, "selected_v": 1,
                  "client_capabilities_sha256": capabilities_sha256(["verify.v1"]),
                  "server_capabilities_sha256": capabilities_sha256(["verify.v1"])}
    ap_nonce = b64u(bytes(range(0x90, 0xA0)))
    ap_si = signature_input("auth", "auth.prove", "f_12", ts, ap_nonce, ap_payload)
    ap_sig = b64u(bot_ed.sign(ap_si.encode()))

    shared = bot_eph.exchange(X25519PublicKey.from_public_bytes(server_eph_pub))
    assert shared == server_eph.exchange(X25519PublicKey.from_public_bytes(bot_eph_pub))

    ks = derive_session_keys(client_nonce, server_nonce, shared)
    key_c2s, key_s2c = ks.pop("_key_c2s"), ks.pop("_key_s2c")

    result_inner = {"ok": True, "session_id": "sess_1", "server_time": ts, "capabilities": ["verify.v1"]}
    confirm_inner = {"session_id": "sess_1"}
    watch_inner = {"code": "MC-8F3K2A", "provider": "qq", "ttl": 300}

    def sealed_entry(direction, seq, key_name, key, type_, id_, payload):
        return {"direction": direction, "seq": seq, "epoch": 0, "key": key_name,
                "nonce_b64url": b64u(seal_nonce(0, seq)), "aad": seal_aad(seq).decode(),
                "plaintext": message_plaintext(type_, id_, ts, payload).decode(),
                "ct": seal(key, 0, seq, type_, id_, ts, payload)}

    (VECTORS / "session.json").write_text(json.dumps({
        "note": "sealed frames use key_c2s / key_s2c from key_schedule",
        "frames": [
            {"frame": {"v": 1, "kind": "plain", "type": "auth.hello", "id": "f_10", "ts": ts,
                       "nonce": ah_nonce, "payload": ah_payload, "sig": ah_sig},
             "signature_input": ah_si},
            {"frame": {"v": 1, "kind": "plain", "type": "auth.challenge", "id": "f_11", "ts": ts,
                       "nonce": ac_nonce, "payload": ac_payload, "sig": ac_sig},
             "signature_input": ac_si},
            {"frame": {"v": 1, "kind": "plain", "type": "auth.prove", "id": "f_12", "ts": ts,
                       "nonce": ap_nonce, "payload": ap_payload, "sig": ap_sig},
             "signature_input": ap_si},
        ],
        "key_schedule": {"shared_secret_b64url": b64u(shared), **ks},
        "sealed": [
            sealed_entry("s2c", 0, "key_s2c", key_s2c, "auth.result", "f_13", result_inner),
            sealed_entry("c2s", 0, "key_c2s", key_c2s, "auth.confirm", "f_14", confirm_inner),
            sealed_entry("s2c", 1, "key_s2c", key_s2c, "verify.watch", "r_1", watch_inner),
        ],
    }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    for name in ("keys.json", "canonicalization.json", "pairing.json", "session.json"):
        print("wrote docs/protocol/vectors/" + name)


if __name__ == "__main__":
    main()
