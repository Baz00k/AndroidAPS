# Status-only lifecycle and write boundary

Connect, service discovery and access authentication each have an eight-second deadline. A failed read
or abandoned attempt invalidates the current measurements. A normal queue-idle disconnect preserves the
original sample time, but cannot extend its five-minute monotonic freshness budget. Reconnecting and
changing the phone's wall clock do not refresh a sample. The driver tab refreshes its display every five
seconds; Pump API consumers evaluate freshness when reading it.

KeepAlive can request status reads. The profile-check API returns satisfied to avoid repeatedly queuing
a blocked profile write; it does **not** verify equivalence with the pump's programmed profile. The
status-only pump advertises no dosing capabilities and exposes zero base basal to prevent loop/SMB
actuation. Authentication and a fresh status sample do not establish therapy readiness.

## Write ownership

Every variant's assembly, AAR packaging, unit tests and lint depend on a compiled-class ownership check.
It inspects JVM calls and method-reference handles targeting `BluetoothGatt.writeCharacteristic` or
`writeDescriptor` across the module's production classes, including generated classes. Only the
matching guarded dispatch methods in `YpsoBleManager` may call these Android APIs. Source formatting,
comments and unrelated methods with the same name do not affect the check.

The consumed compile/runtime library bundles also depend on this check, so app assembly cannot bypass
it by skipping the library's lifecycle tasks. Run `bash pump/ypsopump/tests/verify-app-build-ownership.sh`
to verify the FullLoop and FullDebug app task graphs.

This enforces ownership, not the correctness of the owner's policy. Behavioral mock-GATT recorders
exercise both Android dispatch forms and verify the allowed AUTH destination and independently derived
password bytes, phase checks, descriptor refusal, and counter preservation. Runtime policy review and
these tests remain necessary. Reflective invocation and native code are outside the bytecode call check.

## Synthetic integration fixture

`YpsoStatusIntegrationTest` uses a fixed plaintext status body
`002602000001550000006400000000000000b86a`. The last two bytes were calculated separately with a
bit-at-a-time CRC-32 polynomial `0x04c11db7`, initial value `0xffffffff`, no final XOR, over zero-padded
four-byte blocks in reverse byte order; the low 16 bits are stored little-endian. This exercises the
current CRC/schema interpretation, not an independently captured firmware response. Reservoir is 550
centi-units and battery is 85 percent. AEAD decryption is mocked in this test.

The AUTH fixture uses synthetic MAC `12:34:56:78:9A:BC` and Python `hashlib.md5` over MAC bytes followed
by access salt `4fc2454d9b8159a493bb`, yielding `04319d09e5ba61be2acf95ebebffe38a`.
No real pump identity or session key is included.

Availability is stored with the protected session rather than inferred from the current process. Distinct
causes cover unconfigured state, bond/permission, transport, authentication, encrypted-status unavailable,
suspected re-key required, counter uncertainty and identity mismatch. Transport failures use bounded
5 s / 15 s / 30 s / 60 s / 5 min backoff and become notification-actionable on the third consecutive
failure; non-transport causes alert immediately. Dismissal changes only notification presentation, not the
condition. A verified current-pump encrypted read clears status-related causes but leaves status-only
write-counter uncertainty explicit. Suspected re-key/session loss preserves code/operation/firmware and
blocks automatic retries. An explicit replacement save authorizes one verification attempt without clearing
that condition; only the verified encrypted read clears it.
