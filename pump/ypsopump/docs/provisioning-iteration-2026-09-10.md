# Provisioning iteration: 2026-09-10

## Confirmed starting evidence

- Inspected build: package `info.nightscout.androidaps`, non-debuggable package flags.
- Installed APK SHA-256: `f8f64b375abf28f7e0463f125afb994301a7943eebcdb47c91c8ae4ceba282d9`.
  This matches the earlier regression APK, not the subsequent source-only UI fixes in `f8c1769956`.
- User reports low-contrast setup fields, an invisible key-visibility label, no loading feedback,
  and a status row listing internal availability causes.
- Manual/import setup currently installs a format-valid key before pump verification. Format validity
  alone cannot establish that a key belongs to the pump. Replacement verification must preserve the
  saved session until the candidate has passed identity and encrypted-status verification.
- Read-only ADB log inspection shows successful BLE connection, successful MD5 authentication,
  firmware reads reporting `V05.00.52`, and four status frame callbacks with GATT status zero.
  Encrypted status then fails with `Invalid key or tampered data` and the driver disconnects.
  This is not a failure to establish a Bluetooth connection. The error alone does not distinguish
  incorrect/stale key material from a payload assembly or decoding defect.
- No credential documents were opened or modified during this inspection. Frame payloads and keys
  are excluded from this evidence record.
- FullLoop and FullDebug app task graphs both include their library GATT write ownership guard.

## Acceptance checks for this iteration

- One intentional operator-facing status at a time; internal cause sets remain diagnostic data.
- Candidate verification never replaces a saved session on failure, cancellation, or process restart.
- Successful replacement requires independently matching identity and accepted encrypted status.
- Visible progress starts on both first-time setup and replacement, and resolves to a clear outcome.
- Fields and key visibility label are readable across supported phones, light/dark themes, display
  sizes, and font scales; verification prevents duplicate submissions and conflicting imports.
- Verify the installed APK hash against the newly built artifact before recording UI results.
- Keep therapy disabled and preserve AUTH-only write ownership.

These are starting findings and acceptance criteria, not a claim that the iteration or issue #5 is complete.

## Encrypted-status diagnosis

Read-only inspection reproduced three attempts with frame headers `14/24/34/44`, frame lengths
`20/20/20/16`, and a 72-byte reassembled envelope. Ciphertext and nonce changed between compared
attempts. Authentication failed before CRC, schema, and status decoding. No explicit code 140 was
observed; a generic AEAD exception must not be presented as that pump response.

The independent transformed fixture authenticates using system libsodium. The pinned extractor and
consumer both use direct hexadecimal encoding of the 32-byte key; no conversion mismatch was found.
These findings favor stale/incorrect key material or wrong local record selection over a general
framing defect, but do not establish the cause conclusively.

Further discrimination should compare key fingerprints without exposing key material and, if needed,
compare authentication of a protected single-frame read against multiframe status. Neither diagnostic
requires therapy commands. Correct public frame lengths alone do not prove payload integrity.

Import correctness and pump acceptance are separate checks. Synthetic integration coverage must trace
the canonical file's key bytes through parsing, candidate staging, and the connection's selected key,
then authenticate a protected fixture before promotion. A fresh extraction alone is not proof of pump
acceptance, and a failed encrypted read alone is not proof of a faulty extraction.

The replacement test cases must distinguish:

1. Malformed input rejected before a candidate exists.
2. Format-valid but incorrect key rejected by encrypted verification without replacing saved details.
3. Correct candidate bytes selected for the read even while the previous saved key is retained.
4. Authenticated data from a different identity rejected without promotion.
5. Successful identity/status verification atomically promoting the candidate.
6. Late callbacks from a cancelled or superseded attempt unable to promote any candidate.
7. Restart preserving the saved session and a pending candidate; final attempt results are
   process-local feedback for the interaction that produced them and are not replayed after restart,
   without making unverified credentials the saved session.
8. Replay floors surviving candidate cancellation and same-key re-import, including a fresh candidate
   that learned an authenticated floor before a later decode/identity rejection.

## Cross-device UI verification matrix

Exercise the same screen behavior across supported Android versions, light/dark themes, narrow and wide
windows, portrait/landscape, and enlarged system font/display scaling. No layout or behavior may depend
on a device model or an individual user's settings.

For each configuration check:

- Field labels, entered text, borders, supporting errors, and visibility-control labels have adequate
  contrast. Normal text targets at least 4.5:1, and meaningful control boundaries at least 3:1.
- Labels and actions remain reachable without clipped text, including with the keyboard visible.
- The labeled visibility control has a single accessibility action and at least a 48 dp touch target.
- Import reading, candidate verification, success, failure, and cancellation each have a clear state.
- TalkBack announces progress and terminal errors without exposing the key.
- Rotation/backgrounding cannot turn pending work into success, duplicate a submission, or lose the
  ability to resolve the attempt.
- First setup and replacement both show progress; a failed replacement leaves the saved session intact.

This matrix defines verification scope; it does not record unperformed checks as passes.

Static WCAG relative-luminance checks of the built-in `Color.kt` tokens used for field text,
labels, and unfocused borders against their field surface passed:

| Built-in palette | Primary text | Secondary text / unfocused border |
| --- | ---: | ---: |
| Dark | 15.41:1 | 6.83:1 |
| Light | 18.13:1 | 6.00:1 |

These token checks do not establish rendered layout, disabled/error-state contrast, custom-skin
contrast, or accessibility behavior; those require separate validation.

## Adversarial review blockers

The review of the in-progress candidate implementation identified the following scenarios requiring
reproduction and resolution before handover:

- Save/cancel holding the provisioning lock while waiting for BLE, concurrent with a BLE completion
  holding its operation lock while entering provisioning.
- A stale read failure looking up the newer connection's mutable ownership and rejecting that attempt.
- Returning to saved key A while candidate B is pending producing duplicate record identities.
- Consistency of candidate journal schema/version handling across restart.
- A canonical timestamp accepted within parser clock-skew tolerance but rejected during installation.
- An existing notification ID retaining obsolete text after the operator-facing state changes.

These findings refer to a changing working tree and are not recorded as resolved. The review found no
current AUTH-only write-policy bypass; hypothetical future therapy enablement is not current capability.

Baseline check: committed `f8c1769956` reads journal versions 1–2 and writes version 2. The review's
incompatible version-3 candidate layouts were intermediate, unpublished working-tree forms, not a
demonstrated upgrade regression in a released build. Validation must cover version-2 upgrade and final
candidate-schema restart rather than imply that those transient forms were shipped.

## Integration checkpoint

The first complete module run with Java 21 executed 130 tests and reported 11 failures. Failures span
legacy migration/immediate-install assumptions, BLE/status integration, and presentation test setup.
Focused candidate tests passing is insufficient: the complete suite must be reconciled with the new
contract without weakening replay, migration, identity, or stale-callback assertions.

Final module run: 164 tests, 0 failures, 0 errors. `:pump:ypsopump:lintFullDebug` passes.
`git diff --check` passes. Two tests that previously returned a non-`Unit` value from `runBlocking`
were silently skipped by JUnit 5; they now run and are included in that count.

## Adversarial review follow-up (2026-09-11)

A fresh-context adversarial review of the full branch diff against issue #5 found the findings below.
All confirmed items were fixed on the branch.

- **BLOCKER — lock-order inversion:** `install()`/`cancelCandidate()` held `provisioningLock` and
  entered BLE `disconnect()` (`opLock`), while BLE callbacks run under `opLock` and, for a candidate
  failure with no committed session, entered `provisioningLock` through the retained-legacy restore.
  The restore is now dispatched off the callback thread through `dispatchSessionRestore`, and
  `candidate failure never blocks on a held provisioning transaction` constructs the two-thread cycle.
  The previous disposition claiming this was resolved by running provisioning calls "outside opLock"
  was wrong: `complete()` delivers callbacks under `opLock`.
- **HIGH — fresh-candidate replay floor:** an authenticated counter committed before a later
  CRC/schema/identity rejection was deleted with the candidate. `retireCandidate` now retains a
  candidate that learned a floor as an inactive tombstone; covered by the reimport test.
- **HIGH — code-140 ownership race:** the AUTH callback captured `configuredGeneration`/attempt
  before teardown and records code 140 in the same ownership-gated transaction.
- **MEDIUM — identity cause overwritten:** only decode failures now attribute a generic
  encrypted-status cause; `markVerified` identity failures keep their specific cause.
- **MEDIUM — same-generation promotion race:** promotion validates generation and attempt id inside
  one `PumpSession` transaction.
- **MEDIUM — cancellation retry grant / terminal failures:** UI cancellation clears the one-shot
  retry grant; code-140 and identity failures on a candidate route through ownership-gated failure
  instead of leaving the candidate selected as pending.
- **MEDIUM — secret buffer lifetime:** `onDestroy`, parser validation failures and
  `installDocument`/`installManual` throws now zeroize decoded key material.
- **MEDIUM — typed failure classification:** `fail()` takes an explicit cause/code instead of parsing
  log text.
- **LOW — bytecode guard:** write dispatch is allowed only from methods carrying
  `@YpsoGuardedWrite`; prefix-impersonation negative test added.
- **Test integrity:** the two silently skipped tests now execute; fixtures use synthetic
  `10000001`/`EC:2A:F0:00:00:01` identities instead of the earlier values.
- Cross-module notification cancellation (`NotificationStore` not calling `NotificationManager.cancel`)
  remains out of scope for this module; the plugin now dismisses its availability notification on
  `onStop`, and the outstanding OS-level items stay recorded below.

## Adversarial blocker disposition (earlier round)

- Stale read failures: `ReadOwnership` (GATT, generation, attempt) is captured at read start and
  threaded through verification, rejection, and failure paths. Covered by stale-callback tests.
- Returning to the saved key while a replacement is pending: covered by revert and retained-key
  reimport tests; journal invariants reject duplicate corruption.
- Journal schema: shipped v2 documents carry no candidate fields, so version-2 upgrade is clean;
  the incompatible intermediate v3 forms were never shipped. Covered by the v2 upgrade test.
- Parser/install clock skew: review rejects future creation times, so a reviewed document always
  satisfies the install invariant. Covered by the skew test.
- Notification staleness: same-ID text replacement, no-churn, and one-shot stale dismissal are
  covered in `YpsoPumpPluginTest`. Framework-owned Android cancellation, same-ID system update, and
  `removeExpired()` remain cross-module issues outside this branch's scope.
- Logging: the MAC address no longer appears in logs; decrypted counters, event counts, therapy
  parameters, and raw command bytes moved from INFO to DEBUG. Decrypted status details remain behind
  the debug-build-only diagnostic gate.

## Device-independent hygiene

Test fixtures use the synthetic identity pair `10000001`/`EC:2A:F0:00:00:01` (and
`10000002`/`EC:2A:F0:00:00:02`); the earlier `10175983` values were replaced so no possibly observed
pump identity remains in fixtures, code or docs.

## Stable notification findings

The adversarial follow-up lists these as stable cross-module issues for the handover, separate from
the candidate lifecycle:

- Logical dismissal must also cancel the Android notification.
- Same-ID republication must reflect changed operator text.
- A durable condition needs an intentional dismissal/snooze and startup-reconciliation policy.
- `removeExpired()` needs a focused unit test for its double-removal path.
- Decrypted payload/therapy details must sit behind an explicit diagnostic logging gate.

These are recorded as unresolved.

### Ypso reconciliation policy

YpsoPump does not implement an independent snooze. A local dismiss clears the current overview entry
only; it does not resolve the durable availability condition. On every plugin start, the plugin refreshes
the durable provisioning state and reconciles `YPSOPUMP_UNAVAILABLE`: it republishes the current single
operator instruction when that condition is still actionable, and dismisses the ID when it is not. Within
one plugin lifetime, repeated identical state is deliberately quiet; a changed instruction is dismissed
then republished so the shared store receives fresh text. Android system-notification cancellation and
same-ID replacement semantics remain framework-owned blockers above, not claims made by this policy.
