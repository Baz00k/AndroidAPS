# YpsoPump status viewer

> **Experimental and not therapy-ready.** The supported artifact is status-only. Do not rely on it for
> insulin delivery or as the only way to monitor the pump.

## Supported artifact

The supported artifact has `YpsoPumpConst.READ_ONLY_MODE` enabled. Its app-initiated GATT writes are
restricted to the access-authentication handshake (**AUTH-only**).

After a successful verified encrypted status read, the UI shows reservoir values and battery percent.
The pump reports battery as 0–5 bars; the driver maps bars × 20 to percent. Status fields have bench
evidence on firmware V05.00.52; see the [capability matrix](docs/status-protocol.md):

- running and Stop are the observed operating states; other mode values reject the status;
- battery percent is a coarse display mapping from reported bars, not a measured percentage;
- basal rate, TBR duration, bolus progress and history are unavailable;
- measurements expire five minutes after acquisition, including while disconnected;
- a BLE MAC is never displayed or synthesized as a serial.

Bolus, bolus cancellation, temporary basal, TBR cancellation, profile writes, history selectors,
treatment reconciliation and loop/SMB actuation are blocked.

## Protected setup

The signed, non-debuggable status-only artifact provides **Pump connection setup** in the YpsoPump
plugin preferences. The AAPS target phone does not need root, ADB, `run-as`, recompilation or direct
preference editing. Configuration requires all of:

1. **BLE bond** — Android must already be bonded to the pump. Bonding is OS-managed.
2. **Real serial and BLE MAC** — enter the supported eight-digit serial beginning with `10` and its
   matching `EC:2A:F0:xx:xx:xx` address. The serial is never synthesized from the MAC.
3. **Existing AEAD session key** — either enter the nonzero 32-byte key as 64 hexadecimal characters,
   or import the canonical schema-v1 `.session.json` produced by
   [`ypso-keys` at `df5badb6433c4127a41f7da589888acf5dd0309c`](https://github.com/Baz00k/ypso-keys/tree/df5badb6433c4127a41f7da589888acf5dd0309c).
4. **Independent identity and encrypted-status verification** — **Save and verify status** remains
   configured-but-unverified until the bonded pump name or GATT serial independently matches the claimed
   serial and an authenticated encrypted status is accepted. MD5 AUTH or successful decryption alone is
   not identity verification.

The key, identity, provenance, replay floor and availability state are installed atomically in the
no-backup session journal. The journal body is AES-GCM encrypted with a non-exportable Android Keystore
key. AAPS shows only a truncated SHA-256 fingerprint. The setup activity blocks screenshots and does not
save the plaintext key in UI state. A selected import document is never deleted by AAPS.

Obtaining the key is an external operation involving the genuine app and a separate rooted source device.
That extractor-side requirement does not apply to the AAPS target. See the complete
[provisioning, controller handoff and renewal procedure](docs/provisioning.md).

Legacy raw `ypso_ble_state` credentials are accepted only through one-way migration into protected
storage. A complete serial/MAC/key triple migrates automatically. Older MAC/key-only installations wait
for explicit real serial entry; entering the matching serial upgrades the existing generation without
resetting its replay floor. Build-compiled credentials are unsupported.

These remain separate security layers:

- the Android BLE bond permits link access;
- the pump's MD5 challenge/response authenticates access to GATT;
- the imported AEAD key decrypts the protected session/status payload;
- the independently observed pump name or serial binds the claimed serial to the connected device.

## Durable unavailability

Availability causes are persisted separately: unconfigured, bond/permission, transport, authentication,
encrypted-status unavailable, suspected re-key required, counter uncertain and identity mismatch.
Transport retries back off at 5 s, 15 s, 30 s, 60 s and 5 min; a durable alert is raised after the third
consecutive transport failure, while actionable non-transport failures alert immediately. Dismissing an
alert does not clear the condition. Only a verified current-pump encrypted status clears status-related
causes; status-only write-counter uncertainty remains explicit.

Code 140 is reported as **suspected re-key/session loss**, preserving the code, operation and observed
firmware. Its exact pump semantics and lifetime trigger remain unproven. Automatic retries stop until an
operator saves a replacement session and requests its one controlled verification read.

## Current limitations

- Firmware V05.00.52 is bench-tested on one pump and Android device/OS. Newer firmware is eligible by
  compatibility policy, not individually validated; older or malformed firmware and unsupported layouts
  fail closed.
- Status reads require observed control-service protocol `1.3` and the captured containing-service mapping.
- Replay protection is committed before status publication. Compatible authenticated next-reboot
  transitions adopt a new read floor and reconnect; lower/jumped generations, lost/restored journals and
  missing protected records block reads. Re-importing the same key cannot erase replay protection.
- A second controller cannot be reliably excluded by Android inspection alone. Exclusive ownership also
  requires the operator to quiesce and physically control the other phone.
- Therapy remains blocked unless a future exact artifact is independently qualified.

Implementation details are in [status lifecycle](docs/status-lifecycle.md), [session ownership](docs/session-ownership.md)
and [status protocol](docs/status-protocol.md). Any capability change must update user-facing usage text in
the same change. Unverified observations must not be presented as supported behavior.

## Build

```bash
./gradlew :app:assembleFullLoop   # non-debuggable; YpsoPump remains status-only
./gradlew :app:assembleFullDebug  # debuggable setup/testing artifact
```

This work builds on [SandraK82/ypsopump-research](https://github.com/SandraK82/ypsopump-research) and
[vicktor/ypsomed-pump](https://github.com/vicktor/ypsomed-pump). It is not affiliated with or endorsed by
Ypsomed, AndroidAPS or the Nightscout Foundation. License: AGPL-3.0.
