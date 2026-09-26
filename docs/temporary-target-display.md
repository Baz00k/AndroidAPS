# Temporary target display (issue #65)

Overview and Actions read the current temporary glucose target from persistence on each refresh.
The Overview range, current-glucose comparison, ribbon and details row share one target snapshot.
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
  --tests '*ActionsTargetStateTest' \
  --tests '*TemporaryTargetExtensionKtTest'
```

Covers target-relative text in both units, single/range formatting, inclusive glucose boundaries,
start/change/cancel/expiry, invalid/future targets, missing profile/glucose, and Actions visibility
and creation availability with a stopped/running loop.

Device checks still required (not performed by the unit tests):

1. In both mg/dL and mmol/L, start a single target and a range target. Check Overview's ribbon,
   details and comparison, and Actions' highlighted value/countdown at normal and large font sizes.
2. Edit and cancel from each screen; confirm the existing protection/confirmation flow is shown
   and that the other screen refreshes on return.
3. Leave each screen open through a minute tick and expiry. Check countdown changes and profile
   fallback within one minute. Pause/resume the screen during a running target.
4. With the loop stopped, confirm the active target remains visible and the dialog remains protected.
5. Check the dashed midpoint graph line against known historical targets, including an interval
   spanning a target start/end. Glucose colours and the broad warning band should be unchanged.
