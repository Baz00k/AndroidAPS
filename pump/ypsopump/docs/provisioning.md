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
4. Start verification. The driver checks the serial independently against the bonded pump name or GATT serial and requires an accepted encrypted status from that pump. Successful file parsing, BLE authentication or decryption alone does not install a verified session.
5. Check the reported result and pump status. Failure or cancellation leaves the previous installed session intact. Keep the other controller quiescent while using AAPS.

The importer validates schema, timestamps, key and supported pump identity; a serial is never synthesized from a MAC. A legacy raw credential triple can migrate into protected storage; a MAC/key pair additionally requires the bonded name to supply the real serial. Previously persisted replay floors are retained during migration and same-key imports.

## Handoff and session loss

Before switching back to the official app or another controller, let AAPS finish pending operations and quiesce it. Android cannot establish that a different phone has stopped using the same pump. AAPS keeps replay history for old sessions; clearing app data or editing numeric counters is not a session handoff.

If AAPS reports suspected re-key (pump code 140), it stops automatic retries. Obtain a new session through the genuine-app/service workflow, extract it and verify a **different** key on the AAPS phone. Re-importing the old key does not clear the condition. Key age alone does not prove expiry; the pump's precise session lifetime is unknown. Without an external renewal path, AAPS cannot create a replacement session offline.
