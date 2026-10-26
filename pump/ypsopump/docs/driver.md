# Driver behavior

## What the driver does

The YpsoPump driver delivers immediate and square extended boluses and temporary basal rates (TBRs), and cancels either. Basal profiles are programmed on the pump: AAPS reads and compares them but never writes them. Combination-bolus UI and TDD loading are not supported.

- Immediate boluses use 0.1 U steps (0.1–30 U).
- Extended boluses use 15-minute steps (15 minutes to 12 hours).
- TBRs run 0–500% (15 minutes to 24 hours).
- AAPS can cancel only a bolus it started.

## Status

A status read provides reservoir, battery, pump running/Stop state and the active TBR. Pump Stop and an empty reservoir are shown as suspended. A status reading is used for therapy for five minutes; after that, AAPS must read the pump again.

## Alerts

AAPS notifies you about setup, Bluetooth, pairing, permission, key and connection problems. With **Pump unreachable** enabled, connection failures use that alarm and its configured threshold. If it is disabled, the YpsoPump driver still posts its own connection notification.

A pump code 140 means the pump rejected the saved key. AAPS stops automatic retries until you verify a different key. Key expiry reminders are advisory; see [connection setup](provisioning.md#key-expiry-and-reminders).

## Basal profiles

Basal programming and A/B selection are manual. After editing schedules or switching programs, use **YpsoPump Preferences → Basal configuration → Read pump basal profiles**. Use **Check active pump profile** after switching A/B. A mismatch shows an urgent notification and a pump-tab banner. A timezone change requires another full read.

## Temporary basal

The pump accepts percent TBRs only; absolute requests are converted to a percent of the profile rate. A 100% TBR is a cancellation. A running TBR cannot be replaced, so AAPS stops it and starts the new one.

A TBR reports success only when pump status proves the requested rate and duration. If the result cannot be proven, AAPS keeps the command open and shows an urgent notification until a later status read resolves it. AAPS does not retry it automatically.

## Bolus delivery

Before sending a bolus, AAPS checks that pump status and pump history are ready. A pump acknowledgement does not prove insulin was delivered. If delivery is uncertain, AAPS records the requested amount provisionally and waits for pump history; do not repeat the dose while that warning is shown. A cancellation does not reduce insulin already recorded as delivered.

## History and records

Pump history is the accounting authority for delivery. AAPS imports pump-originated boluses and TBRs when it can identify them, and reconciles its records with status. See [event history](history.md) for row identity, time and ingestion details.
