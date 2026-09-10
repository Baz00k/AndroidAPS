# Protected provisioning, handoff and renewal

> The supported artifact is AUTH-only and status-only. Provisioning never enables therapy. Keep an
> independent way to monitor the pump and follow the pump/clinical instructions for operational fallback;
> this document does not prescribe dosing.

## Supported components and trust boundary

- **AAPS target:** signed non-debuggable AndroidAPS build, Android 12 / API 31 or newer, existing Android
  BLE bond, YpsoPump status-only plugin.
- **Extraction source:** a separate rooted device running a supported genuine mylife app/profile and
  `ypso-keys` pinned to commit `df5badb6433c4127a41f7da589888acf5dd0309c`. Confirm the exact supported
  source app/tool/OS combination in the pinned
  [compatibility matrix](https://github.com/Baz00k/ypso-keys/blob/df5badb6433c4127a41f7da589888acf5dd0309c/docs/compatibility.md)
  before extraction.
- **External services:** the genuine app's registration/authorization and Ypsomed backend may be needed
  to create or renew a session. Extraction recovers an existing session; it does not mint or renew one
  and does not prove that the pump still accepts it.

The canonical JSON and manual key are plaintext secrets before AAPS imports them. Transfer privately,
avoid cloud/chat/issue attachments, and remove temporary transfer copies after verification. Retain any
operator-controlled source backup according to the operator's security procedure. AAPS keeps an encrypted
no-backup copy and never silently deletes the selected document.

## Initial provisioning on the AAPS target

1. Stop active controller operations and observe the pump. Ensure there is no in-flight operation or
   unresolved accounting. Do not alternate controllers during setup.
2. On the source device, use the pinned `ypso-keys` workflow to extract the existing session. Record the
   source profile, genuine-app version, tool commit and source OS for the evidence log. Do not record the
   plaintext key in logs or screenshots.
3. Privately transfer the canonical `.session.json` to the AAPS target, or display the 8-digit pump
   serial (the supported format starts with `10`; check the number printed on the pump), BLE MAC and 64 hexadecimal key for manual
   entry. The serial is never derived from the MAC.
4. Ensure Android on the AAPS target is bonded to that physical pump.
5. Open **YpsoPump → Pump connection setup**.
6. Choose one supported path:
   - **Manual:** enter the supported 8-digit serial beginning with `10` exactly as printed on the pump,
     the matching
     colon-separated Bluetooth address and key. When later editing identity metadata, leave the key blank
     to retain the installed key — except after a suspected re-key, which requires the pump’s current
     (different) key.
   - **Import:** choose **Import file from ypso-keys** in the system picker. The screen shows the
     serial number and Bluetooth address read from the file for confirmation; the key is never
     displayed.
7. Start verification. The old BLE connection is quiesced and the submitted details are staged as a
   protected candidate. The saved session is retained while the candidate is checked. A format-valid
   key is not treated as a working key.
8. Confirm that the screen shows verification progress followed by a clear result. Only an independent
   serial match plus an accepted encrypted status can promote the candidate to the saved session.
   Failure or cancellation leaves the previous saved details intact. AUTH success, reconnect, file
   parsing, and candidate staging alone do not constitute successful setup.
9. Restart AAPS and re-open setup/status. Confirm the real serial, the set-up state and durable
   availability survive. Delete the temporary target transfer copy only after confirming the protected
   copy and fallback procedure.

If serial, MAC, key, schema, timestamps, identity or file-size validation fails, correct the source input.
Do not work around validation by editing preferences or counters. A cancelled picker and failed review do
not modify the active session.

## Exclusive controller handoff and hand-back

The session is pump-bound and local session/counter ownership is exclusive. Before moving control:

1. Quiesce the current controller and wait for any operation/reconciliation to finish.
2. Record the current controller, intended next controller, pump identity fingerprint, status time and
   availability without recording secrets.
3. Provision and verify the next controller as above before relying on it for monitoring.
4. Keep only one controller active. Android inspection can confirm local AAPS state and nearby bonds, but
   cannot reliably prove that another phone is not using the same pump/session. Physical/operator control
   of the other phone is therefore part of the procedure.
5. For hand-back, quiesce AAPS, use the official app/service workflow, and verify that controller against
   the pump. AAPS retains old generations as replay tombstones; do not delete its state to make an old
   session appear fresh.

Unexpected concurrent-controller divergence, replay rejection, counter uncertainty or unresolved
accounting is an unavailable condition, not a prompt to guess or edit a numeric counter.

## Renewal and actual session-loss recovery

Treat **28 days only as an operator reminder**, not as a guaranteed pump lifetime. The exact pump-side
lifetime/invalidating event remains unresolved. Track these evidence classes separately:

1. injected protocol/error simulations;
2. physical invalidation or observed code 140;
3. successful official service-cycle renewal and import;
4. long-duration unchanged-session access.

When AAPS reports code 140 or suspected re-key/session loss:

1. preserve the displayed code, operation, firmware, time and redacted pump identity;
2. stop retry loops and use the independent monitoring/fallback procedure;
3. use the tested genuine-app/service-phone workflow to create or renew access;
4. extract the resulting new canonical session with the pinned tool;
5. import it as a key rotation for the same pump and perform a fresh encrypted-status verification;
6. rehearse hand-back if the official app is to resume ownership.

Do not claim recovery from an old extracted file unless the pump accepts a fresh verified read. Do not
infer expiry solely from age.

## Backend, internet or authorization failure

If registration, authorization, internet access or the service phone is unavailable, AAPS cannot perform
an offline re-key. Keep the durable unavailable condition visible, retain diagnostic metadata, and use the
operator's independent pump-monitoring/service fallback. Do not clear state, import an unrelated pump's
key, repeatedly switch controllers or prescribe treatment changes from this workflow.

## Evidence record for a release walkthrough

For each manual/import/renewal exercise record: exact AAPS revision and APK hash, signer fingerprint,
`android:debuggable` result, target model/OS, pump firmware and redacted identity, source app/profile/OS,
`ypso-keys` commit, initial controller state, procedure, expected/observed result, restart/upgrade result,
availability transitions, AUTH-only write trace hash, cleanup and final controller ownership. Keep real
keys and identifying captures out of the record.

Repository tests cover synthetic compatibility and failure atomicity. Real target walkthrough, physical
renewal/session-loss recovery and long-duration evidence remain hardware evidence and must be attached to
the owning issue before it can be closed.
