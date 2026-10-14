# Connection setup and handoff

## What you need

- The pump's printed eight-digit serial beginning with `10`, and this phone paired with the pump over Bluetooth. Connection setup can pair it; see [Bluetooth pairing](#bluetooth-pairing).
- The pump's matching `EC:2A:F0:xx:xx:xx` Bluetooth address.
- An existing nonzero 32-byte session key, either as 64 hexadecimal characters or in a canonical schema-v1 `.session.json` from [ypso-keys](https://github.com/Baz00k/ypso-keys). Key extraction uses a separate source device and the genuine app; the AAPS phone does not need root, ADB or preference editing. Follow the extractor's [supported combinations](https://github.com/Baz00k/ypso-keys/blob/df5badb6433c4127a41f7da589888acf5dd0309c/docs/compatibility.md).

Keep plaintext keys and import files private. AAPS stores the installed key in its protected no-backup journal; it does not delete the selected source file.

## Install or replace a session

1. Quiesce the other pump controller and finish any in-flight operation. Only one phone should control the pump/session at a time.
2. Pair the AAPS phone with the pump; see [Bluetooth pairing](#bluetooth-pairing).
3. Open **YpsoPump Preferences → Pump connection setup**. Enter the printed serial, matching address and key, or choose **Import file from ypso-keys**. The import review shows the claimed serial and address, never the key.
4. Start verification. The driver checks the serial independently against the bonded pump name or GATT serial and requires an accepted encrypted status from that pump. Successful file parsing, BLE authentication or decryption alone does not install a verified session. Saving makes the new key the one the next connection uses and asks for a status read. A status read that is already waiting, for example from opening AAPS, performs the check instead of a second one. A read that started with the old key cannot verify the new one. If AAPS gives up connecting before the check runs, the next time it connects to the pump for any command it queues the check again. After the pump has rejected a key (code 140), the new key gets only one attempt; to retry, cancel and enter or import the new key again.
5. Check the reported result and pump status. Failure or cancellation leaves the previous installed session intact. Keep the other controller quiescent while using AAPS.

The importer validates schema, timestamps, key and supported pump identity; a serial is never synthesized from a MAC. Manual entry and file import are the only ways to provision a pump. Previously persisted replay floors are retained on same-key imports.

## Bluetooth pairing

The phone's Bluetooth pairing with the pump is separate from the session key. AAPS connects only to a paired pump and never pairs on its own.

1. Put the pump in Bluetooth pairing mode as its user manual describes and keep it close to the phone.
2. Open **YpsoPump Preferences → Pump connection setup**. Enter the serial and matching address, or keep the saved ones, and tap **Pair pump**.
3. Accept Android's pairing request. If Android asks for a code, use the one the pump shows.

Android accepting the request does not pair the phone; setup shows the pump as paired only once Android reports the bond. Android does not tell AAPS whether pairing was cancelled or rejected, so both show the same message. AAPS stops waiting after one minute.

Pairing changes no key, counter, therapy setting or delivery record and never resends a pump command. When the paired pump has saved details, AAPS then reads the pump status with the usual checks: Bluetooth authentication, pump identity and a fresh encrypted status, or the check of a new key. Only that check shows the pump as ready. A new pairing does not retry after a suspected re-key.

If Android loses the pairing, for example after `HCI_ERR_KEY_MISSING`, AAPS cannot connect until the phone is paired again. Pair again from connection setup; the saved session stays in place. Pairing through Android's Bluetooth settings also works, but some phones hide the pump there.

## Key expiry and reminders

AAPS estimates when the installed key will expire and reminds you to replace it. The pump does not report its own expiry, so AAPS assumes the 28-day maximum lifetime reported in [#211](https://github.com/Baz00k/AndroidAPS/issues/211). The date is an estimate. It is not proof that the key still works or has stopped working, and it never renews or verifies the key, changes dosing or clears a pump code 140 rejection. Losing remote access to the pump does not prove that insulin delivery has stopped.

The expiry is the key's source creation time plus 28 days of 24 hours. For a file from ypso-keys that time is `created_at`, which holds the genuine app's `sharedKeyDate`. A key entered by hand has no source date, so AAPS counts from the first import and labels the date as estimated. The key may already be older. If no usable date exists, the expiry shows as unknown and you can set it yourself. The pump status screen shows the expiry too.

Settings are under **YpsoPump Preferences → Pump key**:

- Key reminder is automatic, 3 days before expiry. Choose Custom date… and confirm a date and time to override it. Cancelling the picker leaves the setting unchanged.
- Key expiry is automatic. You can set a custom date the same way.
- Show key status on overview adds a Pump key indicator to the overview. It is off by default.

Reminders appear as AAPS notifications. Dates belong to one key on one pump. Importing the same key again keeps them. A replacement key starts with its own dates once it is verified, and a candidate that fails verification does not change the installed key's reminder.

AAPS checks while the YpsoPump plugin is running and after restart; it cannot notify while stopped. Moving the clock back does not hide an observed due date. AAPS cannot reconstruct elapsed time during a stopped process with clock rollback, and a clock set too far forward can show expiry early. Check the phone clock before correcting a date.

Reminder storage is separate from therapy records. A storage error can disable date settings or reminders without affecting pump operation. Resolve the storage problem and reopen preferences; do not clear app data or edit the session journal to repair a reminder.

## Handoff and session loss

Before switching back to the official app or another controller, let AAPS finish pending operations and quiesce it. Android cannot establish that a different phone has stopped using the same pump. AAPS keeps replay history for old sessions; clearing app data or editing numeric counters is not a session handoff.

If AAPS reports suspected re-key (pump code 140), it stops automatic retries. Obtain a new session through the genuine-app/service workflow, extract it and verify a **different** key on the AAPS phone. Re-importing the old key or editing its dates does not clear the condition. Key age alone does not prove expiry; the pump's precise session lifetime is unknown. Without an external renewal path, AAPS cannot create a replacement session offline.
