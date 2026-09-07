# YpsoPump status viewer

> **Experimental and not therapy-ready.** The accepted artifact is status-only. Do not rely on it for
> insulin delivery or as the only way to monitor the pump.

## Supported artifact

The baseline accepted in [issue #3](https://github.com/Baz00k/AndroidAPS/issues/3) is merge commit
[`71bb79d0f3`](https://github.com/Baz00k/AndroidAPS/commit/71bb79d0f36a90901f68ac2b12a3fbd2266f563e).
Its app-initiated GATT writes are restricted to the access-authentication handshake (**AUTH-only**).

After a successful encrypted status read, the UI can show provisional reservoir and battery values.
Connection state is also shown. Firmware qualification and the status schema are not yet verified, so:

- delivery mode is **Unknown (not validated)**;
- basal rate, TBR duration, bolus progress and history are unavailable;
- serial and firmware remain unknown unless actually read; a BLE MAC is not shown as a serial;
- status age handling is incomplete; an earlier successful read is not proof of current contact.

Bolus, bolus cancellation, temporary basal, TBR cancellation, profile writes, history selectors,
treatment reconciliation and loop/SMB actuation are blocked. See the
[ordered hardening roadmap](https://github.com/Baz00k/AndroidAPS/issues/2); it is the only implementation
backlog for this driver.

## Setup boundary

The current artifact has no supported in-app provisioning screen. Configuration requires all of:

1. **BLE bond** — Android must already be bonded to the pump. Bonding is OS-managed.
2. **Pump MAC** — externally place `ypso_pump_mac` in the private `ypso_ble_state` preferences.
3. **AEAD session key** — externally import the existing 32-byte key as `ypso_shared_key` in the same
   preferences. Never commit, log or share a real key.

This interim storage is ordinary `MODE_PRIVATE` SharedPreferences: the key is plaintext inside the app
sandbox and is exposed by the debug/ADB access used to install it. It is not protected provisioning;
[issue #5](https://github.com/Baz00k/AndroidAPS/issues/5) owns replacement with protected storage.

Obtaining the key is an external provisioning operation involving the genuine app and a separate rooted
source device. The current AAPS target also needs debug/ADB preference access. There is no normal-user,
release-build setup flow yet.

These are separate security layers:

- the Android BLE bond permits link access;
- the pump's MD5 challenge/response authenticates access to GATT;
- the imported AEAD key decrypts the protected session/status payload.

A successful BLE connection or MD5 authentication ACK is **not a verified status read**. Only a completed,
accepted encrypted status response provides displayed measurements.

## Current limitations

- [Issue #11](https://github.com/Baz00k/AndroidAPS/issues/11): lifecycle, AUTH-only recording and freshness.
- [Issue #6](https://github.com/Baz00k/AndroidAPS/issues/6): firmware identity and status protocol validation.
- [Issue #5](https://github.com/Baz00k/AndroidAPS/issues/5): protected provisioning and durable unavailable state.
- Therapy remains blocked until the later roadmap tickets and final qualification decision are complete.

When a later capability changes, the same PR must update the user-facing usage text for that capability.
Protocol observations and unresolved assertions belong in their owning investigation ticket, not here.

## Build

```bash
./gradlew :app:assembleFullLoop   # non-debuggable; YpsoPump is still status-only
./gradlew :app:assembleFullDebug  # debuggable setup/testing artifact
```

This work builds on [SandraK82/ypsopump-research](https://github.com/SandraK82/ypsopump-research) and
[vicktor/ypsomed-pump](https://github.com/vicktor/ypsomed-pump). It is not affiliated with or endorsed by
Ypsomed, AndroidAPS or the Nightscout Foundation. License: AGPL-3.0.
