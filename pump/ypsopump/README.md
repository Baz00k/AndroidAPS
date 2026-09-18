# YpsoPump driver

> **Experimental.** Immediate bolus delivery is implemented behind `YpsoPumpConst.READ_ONLY_MODE`.
> Keep the gate enabled unless you are deliberately building a therapy-enabled artifact.

## Supported artifact

The default artifact has `YpsoPumpConst.READ_ONLY_MODE` enabled. Its app-initiated GATT writes permit
access authentication, the required control-notification CCCD, and profile setting selectors `1`
and `14–61`. Selectors require an established durable write floor and strict-next accounting;
an unknown floor or unresolved write blocks profile acquisition. They do not change configuration.

After a successful verified encrypted status read, the UI shows reservoir values and battery percent.
The pump reports battery as 0–5 bars; the driver maps bars × 20 to percent. Status fields have bench
evidence on firmware V05.00.52; see the [capability matrix](docs/status-protocol.md):

- running and Stop are the observed operating states; other mode values reject the status;
- battery percent is a coarse display mapping from reported bars, not a measured percentage;
- scheduled base basal is available only from fresh verified active-profile evidence;
- TBR duration, bolus progress and production history-row ingestion are unavailable;
- measurements expire five minutes after acquisition, including while disconnected;
- a BLE MAC is never displayed or synthesized as a serial.

When `READ_ONLY_MODE` is changed to `false`, normal AAPS `deliverTreatment()` and
`stopBolusDelivering()` use the production immediate-bolus controller: current status, exact dose
validation, durable persist-before-dispatch ownership, same-link fast-block
identity proof, cancellation of only that proven identity, terminal history reconciliation, and
idempotent PumpSync ingestion. Temporary basal, extended bolus and profile writes remain unsupported.

Profile programming and activation are manual. In **YpsoPump Preferences → Basal configuration**,
use **Read pump basal profiles** during setup and
after editing the pump's schedules. This explicitly reads active-before → all A/B rows → active-after
→ clock on one connection. Keep the pump configuration unchanged during the read. The driver
compares the complete effective AAPS schedule against the last successfully read pump configuration.
It does not claim to detect every unreported manual change or a switch away and back during reading.

Complete schedules are persisted for the installed pump-session generation, with their read time,
and survive disconnects and app restarts. They do not expire every five minutes. Routine status polls
perform **zero profile-selector writes** and no history sentinel. After manually switching A/B,
use **Check active pump profile**: it reads the active selector (plus an identity witness if needed)
and reuses the stored schedules. AAPS profile edits rerun the comparison locally.

Full acquisition takes about 60–78 seconds on the qualified setup. Each selector requires four
encrypted frames and separate authenticated identity/value readbacks. This work is explicit and
yields to newly queued commands after the current selector has been reconciled. An incomplete
refresh leaves the previous complete configuration intact. The UI shows the last observed program
and schedule read age; an unreported pump edit can leave this information outdated. A different
phone timezone inhibits comparison until a configuration read confirms the clock in that zone.
Profile configuration is monitoring evidence, not immediate-bolus authorization. Bolus delivery does
not read or compare basal schedules; profile acquisition remains an explicit operator action.

### Divergence is reported, not tolerated

Every comparison records one of three verdicts: *not read*, *matches*, or *mismatch*. A mismatch —
typically a basal program switched by hand on the pump — raises an urgent notification and a banner
at the top of the pump tab. The pump keeps delivering its own schedule; AAPS cannot correct that, so
it names the program and the manual fix. Each configuration action also reports its own result as a
toast when it finishes.

`setNewBasalProfile()` still never writes to the pump. It succeeds, without enacting, only when the
retained configuration already equals the requested effective schedule, which lets AAPS record the
effective profile switch it needs to run a loop. Reporting failure in that case would leave
`ProfileFunction` with no running profile and make the keepalive re-raise the failed-basal-update
alarm every five minutes while the pump delivered exactly the requested schedule. An unread or
divergent configuration still fails, because neither proves what the pump is delivering.

Immediate-bolus preflight reads current pump and immediate-bolus status. It uses the existing durable
history cursor instead of scanning history before dispatch. Routine status and KeepAlive polling also
never scans history inline, because those commands share the serialized therapy queue. History is read
afterward to confirm delivered insulin and complete PumpSync accounting. Same-command bolus status may
update UI progress, but only terminal history is accounting authority. Bolus delivery does not acquire
or compare the basal profile. TBR remains unsupported.

## Protected setup

The signed artifact provides **Pump connection setup** in the YpsoPump
plugin preferences. The AAPS target phone does not need root, ADB, `run-as`, recompilation or direct
preference editing. Configuration requires all of:

1. **BLE bond** — Android must already be bonded to the pump. Bonding is OS-managed.
2. **Real serial and BLE MAC** — enter the supported eight-digit serial beginning with `10` and its
   matching `EC:2A:F0:xx:xx:xx` address. The serial is never synthesized from the MAC.
3. **Existing AEAD session key** — either enter the nonzero 32-byte key as 64 hexadecimal characters,
   or import the canonical schema-v1 `.session.json` produced by
   [`ypso-keys` at `df5badb6433c4127a41f7da589888acf5dd0309c`](https://github.com/Baz00k/ypso-keys/tree/df5badb6433c4127a41f7da589888acf5dd0309c).
4. **Independent identity and encrypted-status verification** — submitted details are staged as a
   candidate, preserving the saved session until the bonded pump name or GATT serial independently
   matches the claimed serial and an authenticated encrypted status is accepted. MD5 AUTH or successful
   decryption alone is not identity verification.

The key, identity, provenance, replay floor and availability state are installed atomically in the
no-backup session journal. The journal body is AES-GCM encrypted with a non-exportable Android Keystore
key. The setup screen deliberately shows only the serial and Bluetooth address (the import review shows
the file's claimed serial and address); the key fingerprint, source and key age are not displayed, which
is a recorded deviation from the issue's review-disclosure list. The setup activity blocks screenshots
and does not save the plaintext key in UI state. A selected import document is never deleted by AAPS.

Obtaining the key is an external operation involving the genuine app and a separate rooted source device.
That extractor-side requirement does not apply to the AAPS target. See the complete
[provisioning, controller handoff and renewal procedure](docs/provisioning.md).

Legacy raw `ypso_ble_state` credentials are accepted only through one-way migration into protected
storage. A complete serial/MAC/key triple migrates automatically. Older MAC/key-only installations also
migrate when the same MAC is already bonded and its recognized pump name independently supplies the real
serial; otherwise they wait for explicit real serial entry. Migration preserves an existing generation and
replay floor. Build-compiled credentials are unsupported.

The supported pump serial format is eight digits beginning with `10`. Check the physical serial printed
on the pump against the bonded pump name (`YpsoPump_10XXXXXX` or `mylife YpsoPump XXXXXX`).
This is the driver's supported identity format, not evidence that every YpsoPump model uses that format.
AAPS enforces `10[0-9]{6}` on every manual, import and migration path and never synthesizes a serial from
the MAC: the MAC only selects which bonded device may supply the independently observed serial, and the
pair must still satisfy the serial↔MAC derivation check. The importer is deliberately narrower than the
canonical schema: a syntactically valid file outside this supported identity domain, or with a
`created_at` timestamp in the future, is rejected rather than guessed.

These remain separate security layers:

- the Android BLE bond permits link access;
- the pump's MD5 challenge/response authenticates access to GATT;
- the imported AEAD key decrypts the protected session/status payload;
- the independently observed pump name or serial binds the claimed serial to the connected device.

## Durable unavailability

Availability causes are persisted separately: unconfigured, bond/permission, transport, authentication,
encrypted-status unavailable, suspected re-key required, counter uncertain and identity mismatch.
The presentation boundary translates these diagnostic facts into one operator-facing state and next
action. Screens and notifications consume that presentation model rather than displaying cause sets.
Write-counter uncertainty (`COUNTER_UNCERTAIN`) remains internal replay protection; a verified status-only
session normally retains it because no write floor exists, without making status monitoring unavailable.
Transport retries back off at 5 s, 15 s, 30 s, 60 s and 5 min; a durable alert is raised after the third
consecutive transport failure, while actionable non-transport failures alert immediately. Dismissing an
alert does not clear the condition. Only a verified current-pump encrypted status clears status-related
causes; status-only write-counter uncertainty remains explicit.

Code 140 is reported as **suspected re-key/session loss**, preserving the code, operation and observed
firmware. Its exact pump semantics and lifetime trigger remain unproven. Automatic retries stop until an
operator saves a replacement session and requests its one controlled verification read. Re-saving the
identical key is refused while this condition is sticky: the setup screen requires a different key before
another verification attempt is allowed.

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
and [status protocol](docs/status-protocol.md). The qualified read-only event schema, pump-local time
conversion and still-blocked command-attribution boundary are documented in
[history identity and time](docs/history-identity-time.md). Any capability change must update user-facing usage text in
the same change. Unverified observations must not be presented as supported behavior.

## Build

```bash
./gradlew :app:assembleFullLoop   # non-debuggable; YpsoPump remains status-only
./gradlew :app:assembleFullDebug  # debuggable setup/testing artifact
```

This work builds on [SandraK82/ypsopump-research](https://github.com/SandraK82/ypsopump-research) and
[vicktor/ypsomed-pump](https://github.com/vicktor/ypsomed-pump). It is not affiliated with or endorsed by
Ypsomed, AndroidAPS or the Nightscout Foundation. License: AGPL-3.0.
