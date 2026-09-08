# AndroidAPS experimental fork

> ## 🛑 READ THIS FIRST
>
> This fork contains highly experimental insulin-dosing code that is not part of upstream
> AndroidAPS, has not completed the AAPS review process and is **not clinically validated**.
> It is not affiliated with or endorsed by AndroidAPS, the Nightscout Foundation, Ypsomed or CamDiab.
> For supported AndroidAPS, use [the upstream project](https://github.com/nightscout/AndroidAPS).

Forked from `nightscout/AndroidAPS` at `43cc754` (2026-06-04). `main` contains the active fork;
`master` is the unchanged upstream mirror at the fork point.

## What this fork adds

| Area                                | Summary                                                            |
| ----------------------------------- | ------------------------------------------------------------------ |
| [YpsoPump](#ypsopump-status-viewer) | Authenticated status viewer; therapy disabled                      |
| Delivery safeguards                 | Stopped/empty-pump checks and insulin-record repair tools          |
| Infusion-site handling              | Fresh-cannula state, wizard guidance and back-dated recording      |
| Compose UI                          | Material 3 screens and file-based skins                            |
| HovorkaMPC                          | Experimental nonlinear model-predictive controller                 |
| Slim build                          | Reduced modules/locales/ABIs and an AOT compilation eligible build |

## YpsoPump status viewer

The YpsoPump integration is a **status viewer only**. It connects to an already paired pump and displays
provisional reservoir and battery measurements. Readings become unavailable when communication fails
or they are more than five minutes old. A connection alone does not mean a reading succeeded.

Insulin delivery, temporary basal, profile changes and automated dosing are disabled. Delivery details
and history are unavailable, and firmware support is not yet qualified.

Setup requires externally obtained pump credentials; there is no in-app setup screen yet.
See [YpsoPump setup and limitations](pump/ypsopump/README.md).

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

See the [build notes](docs/build-notes.md) for signing and troubleshooting details.

## Licence

Licensed under AGPL-3.0; see [LICENSE.txt](LICENSE.txt). No warranty is provided.
