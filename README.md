# AndroidAPS experimental fork

> **Not clinically validated.** This fork contains experimental insulin-dosing code that is not part of
> upstream AndroidAPS and has not completed the AAPS review process. It is not affiliated with or endorsed
> by AndroidAPS, the Nightscout Foundation, Ypsomed or CamDiab. For supported AndroidAPS, use
> [the upstream project](https://github.com/nightscout/AndroidAPS).

Forked from `nightscout/AndroidAPS` at `43cc754` (2026-06-04). `main` contains the active fork;
`master` is the unchanged upstream mirror at the fork point.

## What this fork explores

| Area | Summary |
|---|---|
| [YpsoPump](#ypsopump-status-viewer) | Authenticated status viewer; therapy disabled |
| HovorkaMPC | Experimental nonlinear model-predictive controller |
| Infusion-site handling | Fresh-cannula state, wizard guidance and back-dated recording |
| Compose UI | Material 3 screens and file-based skins |
| Slim build | Reduced modules/locales/ABIs and an AOT-eligible build |
| Delivery safeguards | Stopped/empty-pump checks and insulin-record repair tools |

The non-Ypso work remains experimental and may deliver insulin when used with another supported pump.

## YpsoPump status viewer

The accepted baseline is merge commit
[`71bb79d0f3`](https://github.com/Baz00k/AndroidAPS/commit/71bb79d0f36a90901f68ac2b12a3fbd2266f563e).
It connects to an already bonded Ypsomed mylife YpsoPump and attempts encrypted status reads.

**Current release boundary:**

- App-initiated GATT writes are restricted to the access-authentication handshake (**AUTH-only**).
- A completed encrypted status read can display provisional reservoir and battery measurements.
- Firmware support, delivery mode and the full status schema are not yet verified.
- Basal/TBR/bolus details, history and current-status freshness guarantees are unavailable.
- Bolus, temporary basal, profile/history selector writes, treatment reconciliation and loop/SMB
  actuation are blocked.

Setup currently requires three separate layers:

1. an Android-managed BLE bond;
2. MD5 access authentication performed during connection;
3. an externally provisioned pump MAC and imported AEAD session key.

A BLE connection or successful MD5 authentication is **not** a verified status read. There is no in-app
provisioning screen in this artifact, and the imported session key must never be committed or shared.

Read the concise [YpsoPump setup and limitations](pump/ypsopump/README.md). Readiness and future work live
only in the [ordered hardening roadmap](https://github.com/Baz00k/AndroidAPS/issues/2); implementation detail
belongs to its linked tickets.

## Other fork changes

- **HovorkaMPC:** receding-horizon control based on the published Hovorka model, with optional adaptive
  layers. Validation is in-silico/replay only; see [`hovorka-mpc/`](hovorka-mpc/README.md).
- **Infusion-site handling:** SITE-GUARD suppresses SMB after a recorded cannula change; the wizard shows
  a fresh-site advisory and supports back-dated site events.
- **Compose UI:** redesigned home, dosing, loop, history, statistics, profile, configuration and pump
  status screens. Dosing still uses the existing constraint and confirmation paths.
- **Slim build:** removes unused modules and assets for this installation and supports Android AOT
  compilation. This is a packaging choice, not a safety qualification.
- **Private-network Nightscout:** permits explicitly configured cleartext hosts for VPN/mesh use. Never
  expose a plain-HTTP Nightscout endpoint to the public internet or an untrusted LAN.

## Build

```bash
./gradlew :app:assembleFullLoop   # non-debuggable/AOT-eligible; YpsoPump remains status-only
./gradlew :app:assembleFullDebug  # debuggable development build
```

Both variants currently use the debug signing key, allowing `adb install -r` to preserve app data.
If KSP reports stale generated types after a large refactor, run `./gradlew :app:clean` and rebuild.

## Issues and licence

Do not report fork-specific behavior to upstream AndroidAPS. Use this repository's issue tracker.

Licensed under AGPL-3.0; see [LICENSE.txt](LICENSE.txt). No warranty is provided.
