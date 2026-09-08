#!/usr/bin/env python3
"""Re-encrypt AUTH-only logcat frames with public test keys using system libsodium.

Usage: transform-status-capture.py PRIVATE_SESSION_JSON PRIVATE_TRACE OUTPUT_JSON
Never emits the private key or pump identity. Input traces must contain only the
selected session's YpsoPump frame lines; review output provenance before publishing.
This utility is independent of the Kotlin encoder, crypto wrapper and CRC implementation.
"""
import ctypes
import ctypes.util
import hashlib
import json
import re
import sys
from pathlib import Path


def main():
    session, trace, output = map(Path, sys.argv[1:])
    key = bytes.fromhex(json.loads(session.read_text())["shared_key"])
    sodium = ctypes.CDLL(ctypes.util.find_library("sodium"))
    sodium.sodium_init()
    fixtures = []
    frames = []
    origin = None
    for line in trace.read_text().splitlines():
        match = re.search(r"frame\[(\d+)\] from ([0-9a-f-]+): status=0 ([0-9a-f]+)", line)
        if not match:
            continue
        index, uuid, raw = match.groups()
        frame = bytes.fromhex(raw)
        if index == "0":
            frames = []
            origin = uuid
        if int(index) != len(frames):
            raise ValueError("Incomplete or interleaved capture")
        frames.append(frame)
        if len(frames) != (frame[0] & 15 or 1):
            continue
        encrypted = b"".join(f[1:] for f in frames)
        plaintext = ctypes.create_string_buffer(len(encrypted))
        length = ctypes.c_ulonglong()
        result = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
            plaintext, ctypes.byref(length), None, encrypted[:-24],
            ctypes.c_ulonglong(len(encrypted) - 24), None, ctypes.c_ulonglong(0), encrypted[-24:], key
        )
        if result != 0:
            raise ValueError("Capture authentication failed")
        body = plaintext.raw[:length.value]
        if len(body) < 12:
            raise ValueError("Missing counter tail")
        # Preserve status/integrity bytes, replace session-specific counters and nonce.
        transformed = body[:-12] + (8).to_bytes(4, "little") + (len(fixtures) + 1).to_bytes(8, "little")
        nonce = hashlib.sha256(b"ypso-public-fixture" + bytes([len(fixtures)])).digest()[:24]
        ciphertext = ctypes.create_string_buffer(len(transformed) + 16)
        assert sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
            ciphertext, ctypes.byref(length), transformed, ctypes.c_ulonglong(len(transformed)),
            None, ctypes.c_ulonglong(0), None, nonce, bytes(range(32))
        ) == 0
        public = ciphertext.raw[:length.value] + nonce
        chunks = [public[i:i + 19] for i in range(0, len(public), 19)]
        fixtures.append({
            "characteristic": origin,
            "raw_frames_sha256": hashlib.sha256(b"".join(frames)).hexdigest(),
            "public_envelope_sha256": hashlib.sha256(public).hexdigest(),
            "body_with_integrity": body[:-12].hex(),
            "frames": [(bytes([((i + 1) << 4) | len(chunks)]) + chunk).hex() for i, chunk in enumerate(chunks)]
        })
        frames = []
    if frames:
        raise ValueError("Truncated final capture")
    output.write_text(json.dumps({
        "public_key": bytes(range(32)).hex(),
        "trace_sha256": hashlib.sha256(trace.read_bytes()).hexdigest(),
        "fixtures": fixtures
    }, indent=2) + "\n")
    print(f"Transformed {len(fixtures)} authenticated captures")


if __name__ == "__main__":
    main()
