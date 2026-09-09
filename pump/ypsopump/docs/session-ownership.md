# Durable session ownership

## Supported contract

The status-only artifact permits AUTH-only app-initiated GATT writes. `SessionCrypto` is
a stateless XChaCha20-Poly1305 codec: ciphertext/tag followed by a 24-byte nonce, with
a mandatory authenticated 12-byte little-endian reboot/read-counter tail. `PumpSession`
owns acceptance. AEAD failure, incomplete tails, unsupported signed counter ranges,
replay, lower reboot generations and unsupported generation transitions cannot update the record.

Each record binds a normalized pump MAC, SHA-256 key identity, random local key
generation, reboot generation, read floor, optional write floor and reservation. Serial
identity is unavailable on the validated target; MAC binding is therefore explicit.
Old key identities remain as tombstones/replay floors. Same-key import cannot reset or
reassign them. Connection tokens and transaction IDs reject old owners. BLE callback
delivery and session changes are serialized under the manager lock; disconnect drains
operations and quiesces the session.

Authenticated fresh messages commit their read floor before their body is returned.
CRC, schema and firmware rejection after that commit suppresses publication without
rolling back replay protection. Ordinary reconnect and process restart preserve the floor.

## Explicit initial migration

An imported key and write/reboot seed contain no read-replay evidence. Legacy preferences
and build-time write seeds never initialize command readiness. Before connecting an existing
key, explicitly call `YpsoBleManager.importReadBaseline(mac, keyHex, reboot, read)` with an
independently captured current pump read floor. This is an internal migration seam, not
a normal-build provisioning UI. Provisioning must also persist the matching key/MAC through
the existing setup mechanism. Real keys and identifying captures must remain private.

For debug/ADB migration only, place `files/ypso-read-baseline.json` in the app-private
directory while the app is force-stopped. Fields are `pump` (MAC), `keyId` (SHA-256 of
the matching raw key), `reboot` and `read` (independently authenticated counter values).
The next connection checks the identity, imports the floor and removes the file after
success. Failed imports remain for diagnosis and block that connection. Non-debuggable
artifacts never consume this input. Remove rejected test inputs before resuming normal
reads. The JSON contains no raw key but still contains private pump identity.

A fresh key needs its own independently validated baseline. Re-importing an existing key
can only retain/raise its floor within the same reboot generation. A mismatched, missing,
corrupt or restored established journal blocks import and connection; deleting state or
substituting a high seed is not recovery. Protected operator-facing provisioning and recovery
are separate work.

## Reboot and recovery decision

Storage-mode testing on V05.00.52 established the same key surviving reboot 16 → 17,
with the read counter restarting at 1 and increasing thereafter. The operator confirmed
self-test and date/time reset, and restored the clock. Cartridge rewind/self-check alone
preserved reboot 16 and continued its read counter.

Reboot recovery follows the existing firmware >= V05.00.52 compatibility policy and verified
control protocol, including newer firmware by assumption unless contradicted by evidence.
Only V05.00.52 has physical transition evidence. An authenticated next generation (+1) with
a positive read counter atomically replaces the reboot/read floor and makes write state
uncertain. The first observed read need not be 1: earlier responses may have been missed.
The transition response is discarded and the connection quiesced; a new connection must
authenticate a later response before publishing status. CRC/schema-invalid transition bodies
still consume the authenticated floor. Missing firmware/control eligibility, lower generations,
generation jumps, zero counters and any outstanding write record reject without adoption.

There are **zero speculative counter probes**, no inferred write reset, and no recovery by
same-key import that erases a replay floor. Write reset/acceptance and re-key reset semantics
remain separate evidence gaps.

The pinned source reference is
[`docs/19-key-lifecycle-pump-rotation.md` at de7e867241fafd2fb8061ceeecf42af2883b9eb4](https://github.com/SandraK82/ypsopump-research/blob/de7e867241fafd2fb8061ceeecf42af2883b9eb4/docs/19-key-lifecycle-pump-rotation.md).
Its big-endian description contradicts target captures. Its reset/increment rules and
key-lifetime assertions are source interpretations, not target pump acceptance evidence.

## Durability and write reservations

The no-backup journal stores no shared key. Each revision is MAC-authenticated using a unique
non-exportable Android Keystore HMAC key. Commit generates the new anchor, destroys old
anchors, then writes and fsyncs the new revision. A crash in the replacement interval makes
the journal unavailable rather than exposing an older replay floor. Restored files cannot
authenticate against deleted anchors. This assumes Android Keystore deletion survives process
death and Keystore state is not itself rolled back; whole-device/privileged rollback is outside
this local-storage guarantee. Device-level behavior needs validation on the target phone.

Reservations have four distinct durable phases: RESERVED → POSSIBLY_SENT → ACKED → VERIFIED.
Reservation increments with overflow checking and commits before any payload could be
dispatched; POSSIBLY_SENT must commit before the transport call. GATT ACK is not pump-effect
verification. Unresolved reservations survive restart and prevent another reservation.
Storage failure poisons the current owner. Write provisioning is unavailable: production
records have an uncertain (`null`) write floor and legacy write dispatch helpers reject.

The write-transport implementation must update this contract and wire the journal into its
serialized dispatch before enabling any encrypted writes. Required bench measurements:

- strict-next versus forward-gap acceptance;
- whether each rejection consumes a counter, including malformed commands;
- lost-ACK and partial-frame outcomes and independent effect verification;
- read/write behavior across reboot and re-key;
- counter range/overflow and exhaustion recovery;
- validated fresh-session recovery after storage loss.

Neither scanning, decrement, read-counter substitution nor `max(seed, persisted)` may establish
write readiness. No existing gated canary implementation is acceptance evidence.

## Software evidence scope

`PumpSessionTest` exercises reconnect/restart, generation isolation, lower/larger reboot
rejection, same/new-key import, wrong pump, storage unavailability, overflow, schema-invalid
authenticated messages, and injected failures before/after each reservation phase commit.
Injected store tests establish owner behavior, not Android Keystore crash durability.
`SessionJournalTest` additionally executes the actual journal serialization, HMAC and
replacement ordering with injected key/file storage, including crashes before/after anchor
creation/deletion, truncation, partial write and sync, stale-file restoration, loss and
record-invariant rejection. It does not emulate secure hardware persistence.
`SessionCryptoTest` executes real native AEAD including independent synthetic vectors;
`CapturedStatusProtocolTest` retains independently transformed target fixtures. BLE lifecycle
tests use mocked Android transport and codec metadata with the real session owner.
