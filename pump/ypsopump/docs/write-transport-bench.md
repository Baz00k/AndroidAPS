# Step 07 non-therapy write-transport evidence

## Candidate behavior

Normal AAPS remains status-only: only the reviewed authentication characteristic may be written.
The separate app under `tests/write-transport-bench/` is the sole Step 07 Bluetooth write artifact.
It permits authentication, the required control-notification CCCD enable, and exact 8-byte GLB
selectors for event, alarm, system history and setting ID. It contains no bolus, TBR, setting-value,
clock, history-clear or other configuration write surface.

Policy and readiness are checked before counter reservation or encryption. Command readiness binds
the exact GATT, connection ID and key generation and separately requires authentication, an encrypted
read in the current session, successful required CCCD setup and a certain write counter. Every logical
write has one whole-write deadline and one transport owner from first fragment through semantic
reconciliation. Android callbacks intentionally carry no submitted operation/frame ID.

Outcomes are `NotSent`, `ProvenRejected`, `PossiblyApplied`, `AcceptedUnverified`, and `Verified`.
The bench supplies no numeric rejection classifier: a first callback carrying 134/138/139 is therefore
`PossiblyApplied` until target evidence identifies the protocol layer and counter effect. Success for
all fragments is still `AcceptedUnverified`; the read-back is recorded, then an operator must enter
the measured semantic and counter result explicitly. Unknown evidence leaves the same durable write
blocked. Restart recovery uses persisted operation ID, counter, characteristic, purpose and plaintext
hash and never resends the write.

Write bootstrap is explicit and app-owned. Key-only provisioning starts `UNKNOWN_MID_EPOCH`; ordinary
authenticated reads cannot enable writes. Exact authenticated `old + 1` adoption transitions to
`OBSERVED_NEW_EPOCH` and permits one durable counter-1 selector candidate. The marker is persisted
before dispatch. A durable authenticated pre-reboot selected-value reference enforces the same
selector family and a different payload, so retained state cannot falsely prove acceptance. The
candidate cannot be retried and becomes `ESTABLISHED` only through explicit consumed
reconciliation. A measured external baseline remains optional validation evidence, not a runtime
dependency. Reviewed unresolved evidence from the prior epoch is retained immutably when reboot
adoption retires that epoch's live reservation.

Authenticated alarm/system count reads use the pinned `Alerts.COUNT` and `System.COUNT` mappings from
the target research revision `de7e867241fafd2fb8061ceeecf42af2883b9eb4`. Exact 8-byte GLB validation
is required. Target evidence, not the mapping alone, establishes support; missing, ambiguous, malformed,
or zero family counts leave the corresponding selector row blocked rather than guessed.

The standalone candidate reads master and supervisor firmware numerically and accepts every
well-formed version `>= V05.00.52`; it does not hardcode an exact accepted firmware. It additionally
requires the observed canonical control protocol `1.3\0`. Setup failures therefore retain capability
identity in evidence before CCCD and selector dispatch. AUTH retains its raw failure provenance and is
correlated with the exact-candidate run's independently recorded pump firmware rather than assuming
identity characteristics can be read before authentication.

The encrypted event-count characteristic used to prime readiness is an exact 8-byte GLB safe
variable and carries no CRC. This was confirmed on the target by an authenticated value of `3000`
with its bitwise complement. The bench rejects any non-exact GLB body before readiness or counter
reservation; CRC handling remains characteristic-specific for response types that actually carry it.

Alarm/system family counts use the same exact-GLB validation but are persisted separately with the
authenticated current epoch. Their selector writes are machine-bound to zero-based `count - 1` and
fail closed after reboot until fresh counts are read. A read-only selector-state action captures and
persists the CRC-valid embedded current history index; the reservation additionally requires that
durable pre-row value to differ from the requested index, and the count, pre-row value and exact
written index are stored immutably in the reservation and reconciliation evidence. A reservation
consumes the family's pre-row observation, so every alarm/system row needs a fresh read-only
selector-state action after the previous row is reconciled. Setting ID `1` read-back accepts an
exact GLB or a CRC-valid response containing a GLB and records the value observationally, because no
qualified setting-ID-to-layout mapping exists; the artifact never writes a setting value.

The physical-test build exposes only bounded measurement seams: omission of one readiness fact to
prove local blocking, one `floor + 2` candidate to distinguish strict-next from a single forward gap,
one `N + 1` ambiguity-convergence candidate bound to a hash-reviewed unresolved strict-next event at
`N`, one same-counter duplicate probe bound to a fully verified accepted event-selector predecessor,
read-only value observation bound to an unresolved selector's durable hash, and authenticated
next-reboot observation. The +2 reservation durably stores its exact prior floor; rejected/not-consumed
reconciliation restores that floor rather than decrementing the candidate. The convergence seam is
available only when the pump floor is bounded to `{N - 1, N}`; therefore `N + 1` is either the measured
`floor + 2` case or strict-next. Proven not-sent or rejected/not-consumed convergence restores the exact
unresolved predecessor reservation. Larger gaps, arbitrary counter input and all scans are rejected.
Reboot adoption makes write state uncertain until the epoch's one-shot counter-1 bootstrap is resolved
from reviewed semantic/counter evidence; a separately measured epoch baseline remains optional
validation evidence rather than a runtime prerequisite.

The duplicate probe is not an unresolved-write retry. It requires a new operation ID, a different event
payload, and immutable current-epoch binding to the predecessor's accepted evidence. An accepted
convergence predecessor also carries the complete nested binding to the older unresolved write. The
probe reuses exactly the predecessor counter once per epoch and cannot target settings, alarm/system
selectors, therapy or configuration. Every result remains subject to explicit reconciliation;
ambiguity still blocks writes.

The exact candidate provides labelled application-level interruption, callback suppression and
duplicate-callback injections. Those validate durable uncertainty behavior but are not substitutes for
independently captured physical link/callback observations. Rows that cannot be induced safely on the
provided phone/pump remain explicit blockers; the operator must not manufacture AUTH/CCCD failures,
counter errors or callbacks merely to complete the table.

## Software evidence

The following checks execute real `SessionCrypto` encryption/decryption and real journal transitions;
Android BLE manager tests use mocked framework objects and the standalone APK build compiles the same
driver sources copied by its Gradle task:

```sh
JAVA_HOME=/tmp/opencode/jdk21 PATH=/tmp/opencode/jdk21/bin:$PATH \
  ./gradlew :pump:ypsopump:testFullDebugUnitTest

JAVA_HOME=/tmp/opencode/jdk21 PATH=/tmp/opencode/jdk21/bin:$PATH \
  ./gradlew :pump:ypsopump:lintFullDebug --max-workers=2

JAVA_HOME=/tmp/opencode/jdk21 PATH=/tmp/opencode/jdk21/bin:$PATH \
  ./gradlew :app:assembleFullDebug --max-workers=2

JAVA_HOME=/tmp/opencode/jdk21 PATH=/tmp/opencode/jdk21/bin:$PATH \
ANDROID_HOME=/home/jbuzuk/Android/Sdk \
  ./gradlew -p pump/ypsopump/tests/write-transport-bench clean assembleDebug --max-workers=2
```

Publish the exact candidate commit, test count, lint/ownership/build results, APK byte size, APK
SHA-256 and signer-certificate SHA-256 together in the issue evidence. Rebuild and republish all of
those values after any candidate change; no prior APK hash may be reused. Software-build evidence is
not a target-pump acceptance claim.

The module ownership guard rejects unguarded Android GATT writes. Focused tests cover exact selector
encoding, policy relabelling, CCCD allowlisting, readiness owner changes, whole-write ownership,
stale/different callbacks, duplicate same-UUID callbacks, every fragment transition, first/later
dispatch refusal, callback rejection, lost ACK/deadline, disconnect, recorder facts, durable intent,
restart uncertainty, explicit counter consumption and semantic reconciliation.

## Target evidence and production conclusion

The exact V05.00.52 candidate established authenticated event/alarm/system selector encoding and
read-back, strict-next and one skipped-counter acceptance, same-counter duplicate-payload acceptance,
reboot bootstrap, readiness gating and raw error provenance. In particular, a write whose final frame
reported raw status 139 still changed authenticated semantic state. Neither callback completion nor a
numeric error can establish rejection, acceptance or safe retry.

This resolves the transport decision needed by the future production driver:

1. journal intent and counter before possible dispatch;
2. never resend a possibly effective command or rely on counter reuse for idempotency;
3. reconcile bolus/TBR from their own status and stable history identity;
4. if counter `N` remains ambiguous, permit at most one predesigned distinct recovery/cancellation at
   `N + 1`, which is within the measured strict-next/`floor + 2` window under either possible floor;
5. if that recovery is ambiguous, or insulin/basal effect remains unattributed, inhibit automated
   therapy rather than attempting `N + 2`, arbitrary gaps, scans or a routine reboot;
6. retain layer, characteristic, firmware, frame and raw code as diagnostics only.

Repeating every generic selector interruption does not change this decision and is not required for
Step 07 closure. Setting/profile semantics belong to the profile-read ticket; therapy-specific lost-ACK,
partial delivery, cancellation and history attribution belong to the bolus/TBR tickets and final exact-
artifact qualification. Injected transport tests remain useful deterministic state-machine coverage,
not substitutes for those command-specific physical tests.
