# Durable session ownership

## Supported contract

The distributed status-only artifact permits AUTH-only app-initiated GATT writes. `SessionCrypto` is
a stateless XChaCha20-Poly1305 codec: ciphertext/tag followed by a 24-byte nonce, with
a mandatory authenticated 12-byte little-endian reboot/read-counter tail. `PumpSession`
owns acceptance. AEAD failure, incomplete tails, unsupported signed counter ranges,
replay, lower reboot generations and unsupported generation transitions cannot update the record.

Each record binds a normalized pump MAC, real claimed serial, protected key material,
SHA-256 key identity, random local key generation, optional reboot/read floor, optional write
floor and reservation. The target firmware may omit the standard GATT serial characteristic;
verification therefore accepts either that characteristic or a matching supported bonded pump
name as the independent identity source. Key decryption alone never verifies the claimed serial.
Old key identities remain as tombstones/replay floors. Same-key import cannot reset or
reassign them. Connection tokens and transaction IDs reject old owners. BLE callback
delivery and session changes are serialized under the manager lock; disconnect drains
operations and quiesces the session.

Authenticated fresh messages commit their read floor before their body is returned.
CRC, schema and firmware rejection after that commit suppresses publication without
rolling back replay protection. Ordinary reconnect and process restart preserve the floor.

## Provisioning and legacy migration

Normal builds install manual serial/MAC/key input and canonical `ypso-keys` schema-v1 files through
one transactional provisioning service. Imported reboot metadata remains a hint; it is not used as
proof of the current read/write floor or command readiness. A new generation starts without a read
floor and adopts the first positive authenticated current-pump read before publishing status.

Legacy `ypso_ble_state` credentials migrate only into the protected journal. A validated complete
serial/MAC/key triple migrates automatically. For older MAC/key-only state, a recognized bonded-pump name
for that exact MAC may independently supply the real serial; the serial is never derived from the MAC. If
no such observation is available, migration waits for explicit real serial entry. Both paths upgrade the
matching existing key generation and preserve its replay floor. Raw credentials are removed only after a
successful protected install. Compiled credentials and direct preference editing are unsupported.

For debug/ADB migration only, place `files/ypso-read-baseline.json` in the app-private
directory while the app is force-stopped. Fields are `pump` (MAC), `keyId` (SHA-256 of
the matching raw key), `reboot` and `read` (independently authenticated counter values).
The next connection checks the identity, imports the floor and removes the file after
success. Failed imports remain for diagnosis and block that connection. Non-debuggable
artifacts never consume this input. Remove rejected test inputs before resuming normal
reads. The JSON contains no raw key but still contains private pump identity.

A fresh key establishes its initial authenticated floor from the selected current pump. Re-importing an
existing key retains its established floor. A mismatched, missing,
corrupt or restored established journal blocks import and connection; deleting state or
substituting a high seed is not recovery. See [protected provisioning and renewal](provisioning.md).

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

The no-backup journal stores the shared key only inside an AES-GCM sealed body using a unique,
non-exportable Android Keystore key. Commit generates the new anchor, destroys old
anchors, then writes and fsyncs the new revision. A crash in the replacement interval makes
the journal unavailable rather than exposing an older replay floor. Restored files cannot
authenticate against deleted anchors. This assumes Android Keystore deletion survives process
death and Keystore state is not itself rolled back; whole-device/privileged rollback is outside
this local-storage guarantee. Device-level behavior needs validation on the target phone.

Reservations have four distinct durable phases: RESERVED → POSSIBLY_SENT → ACKED → VERIFIED.
Reservation increments with overflow checking and commits before any payload could be
dispatched; POSSIBLY_SENT must commit before the transport call. GATT ACK is not pump-effect
verification. Unresolved reservations survive restart and prevent another reservation.
Each Step 07 reservation additionally binds a caller operation ID, destination characteristic,
typed purpose and SHA-256 of the exact plaintext command. Exact-counter encryption checks that
identity before producing ciphertext. A proven first-frame local dispatch refusal may roll the
reservation back; every callback failure, later-frame dispatch refusal, lost callback/deadline,
disconnect after dispatch or duplicate same-UUID callback remains durable uncertainty. All expected
fragment callbacks establish only `AcceptedUnverified`; measured semantic read-back and explicit
counter-consumption evidence are required to reach `VERIFIED`. A duplicate same-UUID callback cannot
be promoted to `Verified` by the same live transport because its frame ownership is unknowable;
external evidence may still classify the durable reservation for operator recovery. No possibly
effective operation is automatically retried.

Reviewed reconciliation is durably bound to the original operation/reservation/counter by the
SHA-256 of the exact evidence bundle and an operator detail. A restart-surviving `RESERVED` phase is
the only offline state that proves the dispatch boundary was never committed; it may be rolled back
only as rejected/counter-not-consumed, with the same evidence binding. `POSSIBLY_SENT` and `ACKED`
remain uncertain until measured semantic and counter evidence resolves them.

Storage failure poisons the current owner. Normal production records have an uncertain (`null`)
write floor and legacy write dispatch helpers reject. A separate-UID non-therapy bench artifact at
`tests/write-transport-bench/` can import a one-time independently measured write floor for the same
pump/key/reboot epoch. It permits only exact GLB event/alarm/system/settings selectors plus AUTH and
the required control-notification CCCD setup; it exposes no therapy/configuration write API and
persists redacted JSONL behavior evidence. Complaint selectors remain excluded because no
target-verified UUID resolves the conflicting references.

Software now wires the durable journal into serialized whole-write dispatch, but target behavior is
still unqualified. Required bench measurements before this contract can claim supported writes:

- strict-next versus forward-gap acceptance;
- whether each rejection consumes a counter, including malformed commands;
- lost-ACK and partial-frame outcomes and independent effect verification;
- read/write behavior across reboot and re-key;
- counter range/overflow and exhaustion recovery;
- validated fresh-session recovery after storage loss.

Neither scanning, decrement, read-counter substitution nor `max(seed, persisted)` may establish
write readiness. Bare numeric errors 134/138/139 never select a retry/cancel/recovery action. The
old normal-AAPS counter-canary/scan paths are inert status-only stubs and are not acceptance evidence;
all Step 07 selector measurements use the dedicated artifact and explicit reconciliation procedure.

## Software evidence scope

`PumpSessionTest` exercises reconnect/restart, generation isolation, lower/larger reboot
rejection, same/new-key import, wrong pump, storage unavailability, overflow, schema-invalid
authenticated messages, and injected failures before/after each reservation phase commit.
Injected store tests establish owner behavior, not Android Keystore crash durability.
`SessionJournalTest` additionally executes the actual journal serialization, authenticated sealing and
replacement ordering with injected key/file storage, including crashes before/after anchor
creation/deletion, truncation, partial write and sync, stale-file restoration, loss and
record-invariant rejection. It does not emulate secure hardware persistence.
`SessionCryptoTest` executes real native AEAD including independent synthetic vectors;
`CapturedStatusProtocolTest` retains independently transformed target fixtures. BLE lifecycle
tests use mocked Android transport and codec metadata with the real session owner.
