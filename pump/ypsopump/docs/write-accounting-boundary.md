# YpsoPump write-accounting boundary

This document records the Step 09b classification before the implementation boundary is moved.
The classification is about authority, not names: a serialized field containing `bench` may still
be required to authenticate and preserve an older journal without authorizing another experiment.

## Classification

### Production safety state

- `PumpSession` identity, generation, read/write floors, transaction ownership and key binding.
- Exact write intent (`operationId`, characteristic, purpose and plaintext hash), reservation phase,
  prior floor, accepted/unresolved predecessor bindings, immutable evidence and resolution.
- Persist-before-encrypt and persist-`POSSIBLY_SENT`-before-dispatch ordering; not-sent rollback,
  ACK persistence, unresolved-operation inhibition, reconciliation and owner teardown.
- `SessionJournal` authenticated envelope/anchor handling, versions 1–15 decoding, current v15
  serialization, atomic commit and rollback detection.
- Complete HMAC-bound ownership handoff and conflict rejection. A numeric counter is not an
  ownership handoff.
- The serialized write transport's exact GATT/connection/generation owner and typed outcomes.

Concrete production callers are `YpsoProfileSelectorCoordinator` and `YpsoBleManager`. Profile
selection needs only strict-next reservation and accepted semantic reconciliation. Future therapy
commands may consume the same accounting boundary, but this change adds no therapy command API.

### Reusable domain state

- Profile schedules/readback/configuration store and generation isolation.
- History identity/time models and transport framing/crypto.
- Read-only setting-selector policy for active program and schedule IDs 14–61.
- Retained profile knowledge. It is configuration evidence, **not therapy authorization**. Before
  bolus/TBR enablement, a command-specific gate must bind confirmation to current pump-side evidence,
  active-program continuity and schedule-edit detection. Immediate bolus must not acquire a complete
  schedule as a preflight.

### Compatibility-only audit data

- Historical `BENCH_*` candidate names, probe-attempt markers, bootstrap reference, history selector
  observations, accepted/unresolved predecessor chains and the retired alarm-cursor reservation.
- Their v1–15 JSON decoders, v15 encoder and validation rules.

These values remain authenticated and round-trip unchanged. They may constrain current ownership
(for example, an unresolved historical reservation still blocks writes), but production exposes no
operation that creates a new probe candidate, resets a marker or interprets an old probe as therapy
readiness.

### Qualification-only behavior

- Numeric write-baseline seeding.
- Strict-next experiment orchestration, forward-gap and duplicate-counter probes.
- New-epoch bootstrap, ambiguity convergence, settings-counter recovery/jump and their readiness
  branches.
- History selector probe binding/observation, bench policy and ADB/operator orchestration.
- `YpsoBenchWriteCoordinator` and the write-transport bench activity.

These compile only in standalone test artifacts. The no-Bluetooth `tests/session-bench` remains a
crash/rollback harness and compiles the qualification session adapter so historical probe transitions
remain regression-testable; neither adapter is a production source set.

## Final dependency direction

```text
AAPS Ypso driver
  YpsoProfileSelectorCoordinator
    -> YpsoWriteAccounting (whole-write lifecycle)
      -> PumpSession (strict-next durable accounting)
      -> YpsoSerializedWriteTransport

standalone write-transport bench
  qualification policy + coordinator + generated qualification PumpSession
    -> the same YpsoWriteAccounting / transport / journal sources

production driver  -X->  qualification policy or orchestration
```

Production callers provide a domain write request and receive typed outcomes. They cannot select a
probe mode, seed a write floor or inspect qualification markers. Selector-specific same-link identity
reconciliation remains in the profile coordinator; it is deliberately not generalized into therapy
effect reconciliation.

## Preservation dependencies

- `YpsoOwnershipHandoff` transfers the complete protected record, including compatibility audit data;
  dropping fields would change the HMAC-bound ownership claim.
- `PumpSession.validate` and `SessionJournal` retain nested predecessor/evidence checks because those
  chains explain the current floor and unresolved inhibition across reboot/restart.
- A committed envelope loads when its own Keystore anchor remains present alongside an unrelated
  extra anchor; it is rejected when its own anchor is absent. These are storage ownership rules, not
  qualification policy.
- Normal status polling, reconnect and KeepAlive do not call the write-accounting core. Explicit
  profile acquisition remains the only production selector consumer in this step.

## Mechanical boundary

Every Ypso library variant runs a class-level qualification-boundary check. It rejects the bench
coordinator/policy, executable probe/baseline method names and any production-artifact subclass of
`YpsoWriteAccounting` that could override reservation selection. The check intentionally permits
documented compatibility records and decoder symbols in `PumpSession` and `SessionJournal`.

## Candidate validation evidence

Validated on 2026-09-17 from source revision `df5c523787a10d9b4fbc855fe36d130938643cb2`
plus the uncommitted Step 09b candidate, under JDK 21 via `mise x java@21 -- ...`:

- `./gradlew :pump:ypsopump:testFullDebugUnitTest :pump:ypsopump:lintFullDebug
  :pump:ypsopump:verifyFullDebugGattWriteOwnership :pump:ypsopump:verifyFullDebugQualificationBoundary
  :pump:ypsopump:verifyFullReleaseGattWriteOwnership :pump:ypsopump:verifyFullReleaseQualificationBoundary
  --console=plain --max-workers=2` — passed; 330 module tests, zero failures/errors, lint passed,
  and both debug/release compiled-class boundaries passed.
- `./gradlew :app:assembleFullDebug --console=plain --max-workers=1` — passed; the FullDebug app
  assembled and its Ypso debug qualification and GATT ownership guards ran.
- `./gradlew :app:assembleFullLoop --console=plain --max-workers=1` — passed; the FullLoop app
  assembled and its Ypso release qualification and GATT ownership guards ran.
- From `tests/write-transport-bench`, `ANDROID_HOME=/home/jbuzuk/Android/Sdk mise x java@21 --
  ../../../../gradlew -p . testDebugUnitTest assembleDebug --console=plain --max-workers=2` — passed;
  82 tests, zero failures/errors, and the standalone qualification APK assembled against the production
  write-accounting/transport/journal sources plus a `PumpSession` generated from the exact production
  source with the qualification-only fragment inserted at its declared marker.
- From `tests/session-bench`, `ANDROID_HOME=/home/jbuzuk/Android/Sdk mise x java@21 --
  ../../../../gradlew -p . assembleDebug --console=plain --max-workers=2` — passed; the no-Bluetooth
  crash/rollback harness assembled.
- `bash pump/ypsopump/tests/verify-app-build-ownership.sh` — passed; FullDebug consumes the FullDebug
  ownership guard and FullLoop consumes the FullRelease ownership guard.
- `git diff --check` — passed.

The independent standards/spec reviews found an over-broad reservation callback in the production
request path, a duplicate-write-ID failure after durable advancement, missing direct production-session
regression coverage, omitted JUnit 4 qualification discovery, and a selector reconciliation regression
under ambiguous callback ownership. The candidate was revised to place experimental reservation
selection and persisted recovery in `YpsoQualificationWriteAccounting`, claim IDs before beginning a
durable transaction, run the retained JUnit 4 suites through Vintage, preserve fail-closed selector
reconciliation, and add direct production, reboot-policy and duplicate-ID tests. No hardware
qualification was performed because this architecture-only change does not intentionally alter pump
wire behavior; prior physical evidence is not represented as evidence for this candidate.
