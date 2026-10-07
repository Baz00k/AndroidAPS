# AndroidAPS experimental fork

> [!WARNING]
> This fork contains highly experimental insulin-dosing code that is not part of upstream AndroidAPS,
> has not been through the AAPS review process and is **not clinically validated**. It is not affiliated
> with or endorsed by AndroidAPS, the Nightscout Foundation, Ypsomed or CamDiab.
> If you need a supported system, use [upstream AndroidAPS](https://github.com/nightscout/AndroidAPS).

This is a fork of [AndroidAPS](https://github.com/nightscout/AndroidAPS) that modernizes the app with a
native Android UI and develops a closed-loop insulin-delivery algorithm.

## What this fork changes

- [YpsoPump](pump/ypsopump/README.md): a pump driver with status, event history, boluses and temporary
  basals, usable in open and closed loop. Basal schedules are programmed on the pump by hand; AAPS only
  compares them with its profile. Credentials come from an external source-device workflow. The AAPS
  device needs neither root nor ADB.
- Infusion sites: the bolus wizard shows a fresh-site advisory, and site-change recording accepts
  back-dated events.
- Compose UI: Material 3 screens for home, dosing, loop, history, statistics, profile, configuration and
  pump status, plus file-based skins. Dosing still goes through the existing constraint and confirmation
  paths.
- Slim build: fewer modules, locales and ABIs, and eligible for Android AOT compilation.
- Private-network Nightscout: allows explicitly configured cleartext hosts for VPN or mesh use.

## Upstream AndroidAPS

Most of the app is still AndroidAPS. For concepts, setup guides and everything this fork has not changed,
see the [AndroidAPS documentation](https://wiki.aaps.app/en/latest/) and the
[upstream repository](https://github.com/nightscout/AndroidAPS). The upstream documentation describes
upstream behavior, so it may not match the changes listed above.

## Build

```bash
./gradlew :app:assembleFullLoop   # non-debuggable/AOT-eligible
./gradlew :app:assembleFullDebug  # debuggable development build
```

## Licence

Licensed under AGPL-3.0; see [LICENSE.txt](LICENSE.txt). No warranty is provided.
