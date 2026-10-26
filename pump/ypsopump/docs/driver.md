# Using YpsoPump with AAPS

Set up the connection first: [connection setup](provisioning.md).

## Doses

- Bolus: 0.1 U steps, 0.1–30 U, within your AAPS maximum bolus.
- Extended bolus: 15-minute steps, 15 minutes to 12 hours.
- Temporary basal: 0–500 %, 15 minutes to 24 hours. Percent only: absolute rates are converted, and 100 % cancels.

Combination boluses and TDD loading are not supported. AAPS cancels only boluses it started; cancel others on the pump.

## Basal profiles

Program the rates and active A/B schedule on the pump, then use **YpsoPump Preferences → Basal configuration → Read pump basal profiles** (one to three minutes). Read again after pump basal changes, A/B switches (**Check active pump profile**) and time zone changes.

AAPS never writes basal rates. A mismatch shows an urgent notification and a banner on the pump screen; the pump delivers its own schedule, so fix the pump or switch AAPS profiles.

## Status

AAPS trusts a status reading for five minutes; the pump screen shows how old it is. A pump you stopped or an empty reservoir counts as suspended in AAPS: no bolus or temporary basal.

## Alerts

Only the **Pump unreachable** alarm in Preferences → Local alerts reports a lost connection, using the threshold set there; turning it off leaves lost connections unreported.

YpsoPump notifications explain why AAPS cannot use the pump: setup not finished, Bluetooth off, Bluetooth pairing missing, missing permissions, a wrong or expired pump key, or a different pump answered. Fix key problems in **Pump connection setup**. Key expiry reminders are advisory and do not stop delivery ([details](provisioning.md#key-expiry-and-reminders)).

## When delivery is uncertain

If AAPS reports a bolus as uncertain or possibly given, check the pump and glucose and do not repeat the dose. The requested amount stays in insulin on board until pump history shows what was delivered.

If AAPS reports a temporary basal change as uncertain, check the pump before relying on the loop; it is not retried, and the warning stays until a later status reading resolves it.
