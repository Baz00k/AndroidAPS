# YpsoPump driver

The YpsoPump plugin connects AndroidAPS to a bonded pump using an existing encrypted session. Its therapy path is experimental. The **build-time** `YpsoPumpConst.READ_ONLY_MODE` gate disables pump-changing therapy operations while leaving the protocol writes needed for reads (such as authentication and selectors) available. The gate covers immediate and square extended bolus delivery and cancellation, and temporary basal start and stop. Pump profile programming, combination-bolus UI and TDD loading are not supported. Check the gate in the artifact you build rather than assuming a particular value.

This gate applies to the AAPS plugin. The separate `tests/write-transport-bench` qualification app can issue bolus commands independently of it.

## Documentation

- [Connection setup](docs/provisioning.md): bond, import or manual session entry, verification and controller handoff.
- [Driver behavior](docs/driver.md): status, basal profiles, temporary basal, bolus accounting and availability.
- [Pump protocol](docs/protocol.md): BLE authentication, encrypted messages, counters, settings and status fields.
- [Event history](docs/history.md): row format, identity, pump-local time and ingestion.

## Build

```bash
./gradlew :app:assembleFullLoop
./gradlew :app:assembleFullDebug
```

The protocol implementation builds on [SandraK82/ypsopump-research](https://github.com/SandraK82/ypsopump-research) and [vicktor/ypsomed-pump](https://github.com/vicktor/ypsomed-pump). The research repository describes additional pump behavior; these pages describe only what this driver implements. This project is not affiliated with or endorsed by Ypsomed, AndroidAPS or the Nightscout Foundation. License: AGPL-3.0.
