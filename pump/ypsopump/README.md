# YpsoPump status viewer

> **Experimental and not therapy-ready.** The accepted artifact is status-only. Do not rely on it for
> insulin delivery or as the only way to monitor the pump.

## Supported artifact

The supported artifact is the build with `YpsoPumpConst.READ_ONLY_MODE` enabled. Its app-initiated GATT
writes are restricted to the access-authentication handshake (**AUTH-only**).

After a successful encrypted status read, the UI shows reservoir values and battery percent.
The pump reports battery as 0–5 bars (confirmed at 2, 3 and 5 bars against the pump display);
the driver maps bars × 20 to percent for display and framework consumers. Only bar values 2,
3 and 5 have been observed; 0/1/4-bar behavior and low-battery thresholds are unvalidated.
Connection state is also shown. Firmware qualification and the status schema are not yet verified, so:

- delivery mode is not shown because its interpretation is not validated;
- battery percent is bars × 20 as reported by the pump; values below 40% and low-battery
  thresholds are unvalidated;
- basal rate, TBR duration, bolus progress and history are unavailable;
- serial and firmware are shown only when available; a BLE MAC is not shown as a serial;
- measurements expire five minutes after acquisition, including while disconnected; an earlier successful read is not proof of current contact.

Bolus, bolus cancellation, temporary basal, TBR cancellation, profile writes, history selectors,
treatment reconciliation and loop/SMB actuation are blocked.

## Setup boundary

The current artifact has no supported in-app provisioning screen. Configuration requires all of:

1. **BLE bond** — Android must already be bonded to the pump. Bonding is OS-managed.
2. **Pump MAC** — externally place `ypso_pump_mac` in the private `ypso_ble_state` preferences.
3. **AEAD session key** — externally import the existing 32-byte key as `ypso_shared_key` in the same
   preferences. Never commit, log or share a real key.

This interim storage is ordinary `MODE_PRIVATE` SharedPreferences: the key is plaintext inside the app
sandbox and is exposed by the debug/ADB access used to install it. It is not protected provisioning.

Obtaining the key is an external provisioning operation involving the genuine app and a separate rooted
source device. The current AAPS target also needs debug/ADB preference access. There is no normal-user,
release-build setup flow yet.

These are separate security layers:

- the Android BLE bond permits link access;
- the pump's MD5 challenge/response authenticates access to GATT;
- the imported AEAD key decrypts the protected session/status payload.

A successful BLE connection or MD5 authentication ACK is **not a verified status read**. Only a completed,
accepted encrypted status response provides displayed measurements.

Implementation and verification details are in [the driver lifecycle documentation](docs/status-lifecycle.md).
Firmware and field observations are recorded in [the status protocol documentation](docs/status-protocol.md).

## Current limitations

- Firmware identity and the status protocol are not qualified.
- Protected provisioning and durable unavailable-state reporting are not implemented.
- Therapy remains blocked unless a future artifact is independently qualified for it.

Any capability change must update its user-facing usage text in the same change. Unverified protocol
observations must not be presented as supported behavior.

## Build

```bash
./gradlew :app:assembleFullLoop   # non-debuggable; YpsoPump is still status-only
./gradlew :app:assembleFullDebug  # debuggable setup/testing artifact
```

This work builds on [SandraK82/ypsopump-research](https://github.com/SandraK82/ypsopump-research) and
[vicktor/ypsomed-pump](https://github.com/vicktor/ypsomed-pump). It is not affiliated with or endorsed by
Ypsomed, AndroidAPS or the Nightscout Foundation. License: AGPL-3.0.
