# Temporary target display (issue #65)

Overview and Actions read the current temporary glucose target from persistence on each refresh.
The Overview range, current-glucose comparison, inline target status and details row share one target snapshot.
An active target is visible even without a profile or glucose reading. Actions retains the existing
protected target dialog for editing/cancellation, including when the loop is stopped.

The fallback is the current profile range, as requested in issue #65. No cached APS result is used
as a fallback: immediately after cancellation/expiry that result can still describe the old target.
This change does not alter targets passed to APS, treatment records, pump commands, or the display
hypo/hyper thresholds used to colour glucose.

## Upstream comparison

Reviewed nightscout/AndroidAPS at `598e2eb39c7e15876e4c42876a2162bffcb4fe5f`:

- [OverviewFragment](https://github.com/nightscout/AndroidAPS/blob/598e2eb39c7e15876e4c42876a2162bffcb4fe5f/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt):
  `updateTemporaryTarget()` uses an active-at-now query, single-value/range formatting, and
  `untilString(end)` in a highlighted ribbon. The ribbon opens the protected target dialog.
  Target changes and periodic refresh update it. Its optional APS-adjusted fallback is deliberately
  not introduced here; the requested profile fallback avoids retaining a stale adjusted target.
- [PrepareTemporaryTargetDataWorker](https://github.com/nightscout/AndroidAPS/blob/598e2eb39c7e15876e4c42876a2162bffcb4fe5f/workflow/src/main/kotlin/app/aaps/workflow/PrepareTemporaryTargetDataWorker.kt):
  the graph samples the effective target midpoint every five minutes, using profile targets between
  temporary targets. The redesigned graph now has this separate stepped line and a midpoint label;
  the existing hypo/hyper shading and glucose colours remain unchanged. As upstream, this is a sampled
  historical cue, so target transitions on the graph can lag the actual boundary by up to five minutes.

Actions subscribes to target, effective-profile and preference changes while resumed. A disposable
one-minute timer updates the countdown and clears expired status without requiring a treatment event.
Overview retains its existing target-change subscription and one-minute refresh. Expiry/countdown
visibility can therefore lag by up to one minute while either screen remains open.

## Verification

Automated regression coverage:

```sh
./gradlew :plugins:main:testFullDebugUnitTest \
  --tests '*TargetDisplayTest' \
  --tests '*TargetChartDataTest' \
  --tests '*ActionsTargetStateTest' \
  --tests '*TemporaryTargetExtensionKtTest'
```

Covers target-relative text in both units, single/range formatting, inclusive glucose boundaries,
start/change/cancel/expiry, invalid/future targets, missing profile/glucose, and Actions visibility
and creation availability with a stopped/running loop.

PR #66 review follow-up (2026-09-28): both graph findings were reproduced by regression tests
against the original sampling loop before fixing it. Historical samples now use the profile that
was effective then (the timestamped target getters alone only select time-of-day blocks within a
profile). Targets are fetched in two queries per chart: the target overlapping the window start,
and valid targets starting within/after the window, filtered to its end in memory. Profile-switch
boundaries are loaded once, with a profile lookup at the window start and when a sampled boundary
is crossed. A 24-hour chart no longer performs 289 temporary-target lookups. These queries remain
on the background chart handler; the review did not establish a measured UI stall.

`TargetChartDataTest` covers the historical profile switch, bounded provider lookups for a 24-hour
chart, a target starting before the window, starts/replacements/cancellation/expiry, invalid/future
records, missing profile history, scheduled target blocks, the final partial sample, and mmol/L
conversion. Missing historical values are omitted and the renderer does not draw across those gaps.
The changes remain display-only: no dosing, pump commands, target persistence, or protection paths
are changed.

The follow-up validation passed all 18 focused tests and `:app:assembleFullDebug`.
[Full-app emulator screenshots and tested steps](review/pr66/README.md) cover starting a target,
its visibility on both screens with the loop disabled and no active profile/glucose, confirmation,
and cancellation refresh. They supersede the earlier synthetic UI samples.

Broader device checks remain (not established by the unit tests or these screenshots):

1. In both mg/dL and mmol/L, start a single target and a range target. Check Overview's inline status,
   details and comparison, and Actions' highlighted value/countdown at normal and large font sizes.
2. Edit and cancel from each screen; confirm the existing protection/confirmation flow is shown
   and that the other screen refreshes on return.
3. Leave each screen open through a minute tick and expiry. Check countdown changes and profile
   fallback within one minute. Pause/resume the screen during a running target.
4. With the loop stopped, confirm the active target remains visible and the dialog remains protected.
5. Check the dashed midpoint graph line against known historical targets, including an interval
   spanning a target start/end. Glucose colours and the broad warning band should be unchanged.


## Compact Overview target status

Following the design feedback, Overview no longer adds a separate active-target card. Its existing
comparison/range line displays `Temp target`, the effective range and remaining time in the accent
colour while a target is active. The line is tappable through the same protected edit/cancel action,
wraps on narrow screens, and still displays the target when glucose is missing. The profile range
returns in that same location after cancellation or expiry. Actions and the Details row are unchanged.

The comparison and range continue to come from the same `TargetDisplay` snapshot. Explicit regression
coverage now checks the screenshot example: glucose 136 with profile range 85–110 is 26 above target;
a temporary target of 140 changes it to 4 below target; a temporary range 120–150 is in range;
cancellation/expiry returns to 26 above target. A corresponding mmol/L transition test verifies
conversion and fallback. All 20 focused tests pass, and the full debug app builds.

[Compact inline screenshots and device verification](review/pr66/inline/README.md) supersede the
older Overview banner screenshots. No glucose-warning colours, dosing logic or protection paths
were changed.
