# Using YpsoPump with AAPS

Set up the connection first. See [Connection setup](provisioning.md).

## Basal rates

You set basal rates on the pump. AAPS cannot send basal rates to the pump. It reads them and checks that they match your AAPS profile.

Your AAPS profile must have the same rate as the pump's active program (A or B) for every hour. The pump has one rate per hour, so change rates in your AAPS profile only on the full hour. A profile switch with a percentage or time shift must also end up with exactly the pump's rates.

AAPS does not see changes you make on the pump until you read the pump again:

| When                                                  | Tap                                                       |
| ----------------------------------------------------- | --------------------------------------------------------- |
| After setup, and after every basal change on the pump | **Read pump basal profiles** (takes one to three minutes) |
| After you switch between A and B on the pump          | **Check active pump profile** (takes a few seconds)       |
| After the phone changes time zone                     | **Read pump basal profiles**                              |

To find these, open the YpsoPump tab, open the menu and choose **YpsoPump Preferences**. They are under **Basal configuration**. Keep the pump close to the phone while AAPS reads it.

When the read is done, AAPS tells you whether the pump matches. For example, it shows **Pump profile A matches the active AAPS profile.** If you see **Not completed; previous schedules retained**, try again. If **Check active pump profile** does not complete, use **Read pump basal profiles** instead.

### When the pump and AAPS do not match

You see this notification, and the same text at the top of the YpsoPump tab:

**Pump basal profile A does not match the active AAPS profile. The pump is delivering its own schedule. Fix it on the pump or switch AAPS profiles.**

The pump keeps giving its own rates, not the rates in your AAPS profile. Correct the rates on the pump, or switch to an AAPS profile with the same rates. If you changed the pump, read it again.

If you switch profiles in AAPS and the new profile does not match the pump, AAPS shows the **Failed to update basal profile** alarm. AAPS keeps the previous profile and shows the alarm again until the rates match. You get the same alarm before you first read the pump. Set the pump to the same rates, then tap **Read pump basal profiles**. AAPS then finishes the profile switch.

## Doses

From AAPS you can give:

- boluses from 0.1 to 30 U, in steps of 0.1 U
- extended boluses lasting 15 minutes to 12 hours, in 15-minute steps
- temporary basals from 0 to 500 %, lasting 15 minutes to 24 hours

Your AAPS limits, such as maximum bolus, also apply. AAPS cannot give combination (multiwave) boluses. It cannot read daily totals from the pump.

AAPS can stop only the boluses that AAPS started. To stop a bolus or extended bolus that you started on the pump, use the pump.

Doses you give on the pump show up in AAPS after a delay. An extended bolus started on the pump shows up only after it has finished. AAPS does not record combination (multiwave) boluses given on the pump, so your insulin on board in AAPS will be too low. Avoid them while you use AAPS.

## If AAPS is not sure a dose was given

The connection can drop while AAPS is sending a bolus. AAPS then cannot tell if the pump gave it. You get a bolus error alarm and this notification:

**A previous bolus could not be fully confirmed. Check the pump and glucose before giving more insulin.**

If AAPS ever tells you to check the pump before giving more insulin:

1. Do not give the bolus again.
2. Look at the bolus history on the pump.
3. Check your glucose.

For a normal bolus, AAPS already counts the full amount in insulin on board. It corrects the amount once the pump history shows what was given.

If you see **The pump did not confirm that the extended bolus stopped; it may still be delivering.**, look at the pump. If the extended bolus is still running, stop it on the pump.

If you see **A temporary basal change could not be confirmed. Check the pump before relying on the loop.**, look at the pump to see which temporary basal is running, if any.

## Pump stopped or reservoir empty

If you stop the pump, or the reservoir is empty, the pump gives no insulin. AAPS shows **Pump suspended** and pauses the loop. AAPS cannot give boluses or temporary basals until the pump runs again. If you try, it says **The pump is stopped or the reservoir is empty.**

When the reservoir gets low, AAPS warns you: **Pump reservoir low: … U left. Change the cartridge soon.** You choose when this warning starts with the **Threshold critical reservoir level [U]** setting. An empty reservoir sounds an alarm: **Pump reservoir is EMPTY — no insulin is being delivered. Change the cartridge now.**

When you start the pump again, AAPS notices the next time it reads the pump. Then the loop continues.

## Alerts

### Lost connection

In AAPS, only the **Pump unreachable** alarm tells you that AAPS cannot reach the pump. You find it in **Preferences → Local alerts**:

- **Alert if pump is unreachable** turns the alarm on or off. It is on by default.
- **Pump unreachable threshold [min]** sets how long AAPS waits before the alarm sounds. You can pick 30 to 300 minutes.

If you turn this alarm off, AAPS does not warn you when it cannot reach the pump.

When the alarm sounds, bring the phone close to the pump and check that Bluetooth is on. Even when AAPS cannot reach it, the pump keeps giving insulin by itself.

### Problems you need to fix

YpsoPump notifications start with **YpsoPump:** and tell you what to do, for example **YpsoPump: Turn Bluetooth on.** Do what the notification says. For pairing and pump key problems, see [Connection setup](provisioning.md).

## Less common situations

### Pump clock and time zones

AAPS reads the pump clock each time it reads pump history, and the pump history records every change of its date or time. Doses keep their right time even when the pump clock differs from the phone's, and across changes made while the phone was away. The pump never changes its clock for daylight saving time and does not follow the phone's time zone: set it by hand after a daylight saving time change or when you travel, then tap **Read pump basal profiles**.

Before a daylight saving time change, AAPS shows **Daylight Saving time change in 24h or less**. After the change, AAPS pauses the closed loop for 3 hours.

If AAPS cannot read the pump clock, pump history waits until the next read that can.

### History incomplete

**YpsoPump history synchronization incomplete. Insulin accounting may be incomplete. Check pump history and AAPS records.** means some doses from the pump may be missing in AAPS. If so, insulin on board in AAPS is wrong. Compare the pump history with the doses in AAPS before you give extra insulin.

If history synchronization stays stuck for a long time, AAPS keeps only its most recent temporary basal commands. When synchronization resumes, AAPS takes the older temporary basals it set from pump history, so they show as set on the pump.

### Battery level

The pump reports its battery in bars, so AAPS shows the battery level in steps of 20 %.
