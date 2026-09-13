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

The physical-test build exposes only bounded measurement seams: omission of one readiness fact to
prove local blocking, one `floor + 2` candidate to distinguish strict-next from a single forward gap,
read-only value observation bound to an unresolved selector's durable hash, and authenticated
next-reboot observation. The +2 reservation durably stores its exact prior floor; rejected/not-consumed
reconciliation restores that floor rather than decrementing the candidate. Larger gaps and all scans
are rejected. Reboot adoption makes write state uncertain until the epoch's one-shot counter-1
bootstrap is resolved from reviewed semantic/counter evidence; a separately measured epoch baseline
remains optional validation evidence rather than a runtime prerequisite.

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

## Target evidence still required

This document records no physical acceptance claim. Issue closure remains blocked until the exact
candidate is run against the target pump and records:

1. firmware, phone/OS, app revision/APK hash/signer and redacted identity;
2. initial pump, controller, reboot/read/write counter and clock state;
3. selector encoding and integrity behavior for each supported selector family;
4. required authorization and CCCD success/failure semantics;
5. strict-next versus gap behavior and counter consumption for acceptance and each rejection;
6. interruption at each fragment, lost ACK, duplicate callback and disconnect/restart behavior;
7. failure layer, characteristic, firmware and raw numeric code without bare-code inference;
8. expected/observed pump and app/database effects, trace hashes, cleanup and hand-back.

Injected fault results must be labelled injected and do not substitute for unobserved physical cases.
The full operator procedure and evidence export commands are in
`tests/write-transport-bench/README.md`.
