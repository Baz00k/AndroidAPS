# Connection setup and handoff

## What you need

- An Android BLE bond to the physical pump and its printed eight-digit serial beginning with `10`.
- The pump's matching `EC:2A:F0:xx:xx:xx` Bluetooth address.
- An existing nonzero 32-byte session key, either as 64 hexadecimal characters or in a canonical schema-v1 `.session.json` from [ypso-keys](https://github.com/Baz00k/ypso-keys). Key extraction uses a separate source device and the genuine app; the AAPS phone does not need root, ADB or preference editing. Follow the extractor's [supported combinations](https://github.com/Baz00k/ypso-keys/blob/df5badb6433c4127a41f7da589888acf5dd0309c/docs/compatibility.md).

Keep plaintext keys and import files private. AAPS stores the installed key in its protected no-backup journal; it does not delete the selected source file.

## Install or replace a session

1. Quiesce the other pump controller and finish any in-flight operation. Only one phone should control the pump/session at a time.
2. Bond the AAPS phone to the pump through Android.
3. Open **YpsoPump Preferences → Pump connection setup**. Enter the printed serial, matching address and key, or choose **Import file from ypso-keys**. The import review shows the claimed serial and address, never the key.
4. Start verification. The driver checks the serial independently against the bonded pump name or GATT serial and requires an accepted encrypted status from that pump. Successful file parsing, BLE authentication or decryption alone does not install a verified session. Saving makes the new key the one the next connection uses and asks for a status read. A status read that is already waiting, for example from opening AAPS, performs the check instead of a second one. A read that started with the old key cannot verify the new one. If AAPS gives up connecting before the check runs, the next time it connects to the pump for any command it queues the check again. After the pump has rejected a key (code 140), the new key gets only one attempt; to retry, cancel and enter or import the new key again.
5. Check the reported result and pump status. Failure or cancellation leaves the previous installed session intact. Keep the other controller quiescent while using AAPS.

The importer validates schema, timestamps, key and supported pump identity; a serial is never synthesized from a MAC. Manual entry and file import are the only ways to provision a pump. Previously persisted replay floors are retained on same-key imports.

## Key expiry and reminders

In **Pump connection setup**, the installed key has a tracked deadline, time remaining and reminder controls. AAPS uses a **28-day maximum** reported by the maintainer in [#211](https://github.com/Baz00k/AndroidAPS/issues/211), who reported a key stopping in AAPS on October 8, 2026. No firmware or incident details beyond that report are assumed, and no pump-reported expiry countdown is available.

For files from ypso-keys, `created_at` preserves the genuine app's `sharedKeyDate`, our best available proxy for generation. The deadline is that timestamp plus 28 elapsed 24-hour days, not extraction or import time. A manually entered key without a source date uses an explicitly labeled **first-import estimate**; the key may already be older. Invalid stored timing has no inferred deadline when neither baseline is usable; set the dates manually.

The automatic reminder is three elapsed days before the deadline. You can set an expiry override and a separate reminder date and time, or restore either automatic date. The displayed origin distinguishes source-derived dates, import estimates and user-entered dates. Edits keep the original source timestamp and do not renew or verify the key. Re-saving or importing the same key retains its first import and manual choices and cannot move a known creation date later. Only an authenticated, verified replacement becomes the installed key with its own timing; an unsuccessful candidate leaves the installed reminder intact.

Dates are stored as absolute instants and displayed in the current local time zone; DST or changing zones does not extend the 28-day interval. Notifications are checked while the YpsoPump plugin is running and again after restart; already-old imports warn after verification. Once observed due, a reminder or deadline stays due across clock rollback and restart unless you explicitly change its date. AAPS advances age using monotonic elapsed time while running and retains observed time across restart; an incorrect forward clock can therefore require a manual correction. Time spent stopped cannot be reconstructed if the phone clock was also rolled back. While the app is stopped, no notification can be delivered. Check the phone clock and obtain a replacement before access is lost.

Expiry presentation and date edits do not change dosing, replay counters, uncertain delivery records or key-rejection state. **Loss of remote access does not prove insulin delivery has stopped.**

## Handoff and session loss

Before switching back to the official app or another controller, let AAPS finish pending operations and quiesce it. Android cannot establish that a different phone has stopped using the same pump. AAPS keeps replay history for old sessions; clearing app data or editing numeric counters is not a session handoff.

If AAPS reports suspected re-key (pump code 140), it stops automatic retries. Obtain a new session through the genuine-app/service workflow, extract it and verify a **different** key on the AAPS phone. Re-importing the old key or editing its dates does not clear the condition. The tracked age is advisory, not pump acceptance evidence. Without an external renewal path, AAPS cannot create a replacement session offline.
