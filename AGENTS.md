# Project direction

This experimental fork aims to modernize AAPS and develop a closed-loop insulin-delivery algorithm. [Upstream AndroidAPS](https://github.com/nightscout/AndroidAPS) is a reference, not a compatibility target.

There is no legacy-support commitment. Prefer removing obsolete code and simplifying designs over preserving compatibility layers.

## Therapy safety

**Therapy safety overrides every other goal.** This app can command insulin delivery; an overdose can cause severe injury or death.

- For changes affecting therapy, trace the path from inputs through dosing decisions, constraints, pump commands, and delivery records. Account for units, time, stale or missing data, and retries or uncertain delivery where relevant.
- Preserve safety constraints and confirmations through refactors, UI changes, and legacy-code removal.
- Handle uncertainty explicitly: distinguish requested, acknowledged, and confirmed delivery, and prevent retries from duplicating insulin. Use the established safe fallback when inputs or delivery state cannot be trusted.

## Implementation

- Keep the native UI minimal and understandable, with therapy state, dose units, and consequential actions unambiguous.
- Use Conventional Commits: `type: brief summary`, with an optional scope.

## Issues and pull requests

- Keep each pull request to one concern, and make its type match its content. Production changes discovered during test, CI, or build work belong in a separate pull request.
- Agents may narrow, defer, or split the scope of an issue and should say so. Adding requirements or blocking dependencies to an issue needs maintainer agreement; record other follow-ups as separate, non-blocking issues.
- If the plan or the issue turns out to be wrong, stop and report with a proposed correction instead of working around it.
- Describe in the pull request what changed, why, how it was verified, and known gaps. CI is the record of builds and test results: do not post command logs, test counts, hashes, or toolchain inventories in issues or pull requests. Resolve an issue by linking its pull request, with one line on anything decided or deferred.

## Verification

- Choose coverage by behavioral risk. For therapy logic, cover dosing boundaries, invalid inputs, and failure paths; keep low-impact verification proportionate.
- Test observable behavior with independently justified expectations. For external libraries, test our integration behavior.
- A bug fix includes a regression test when the failure can be reproduced in a test; a new test must fail without the change it covers.
- Verify changed dosing behavior and relevant failure paths in simulation or replay before considering it ready. Passing tests or simulations is not clinical validation.
- Use simulators and Android emulators to exercise changed behavior autonomously. Use real devices connected through ADB for hardware-dependent behavior, such as pumps, BLE, or sensors; no particular personal device is a reference or baseline. For UI changes, interact with the affected flow and inspect the rendered result; use logs to investigate failures.
- Keep manual testing isolated from live therapy: use simulated pumps or a verified non-patient-connected test setup for delivery actions. Preserve therapy settings and records on real devices.
- Report what was actually verified, remaining uncertainty, and gaps, including unavailable devices or simulations.
- Keep one-off tests and temporary tooling out of commits and pull requests; retain only durable tests and maintained tooling.

## Documentation

Put user-facing behavior and setup guidance in the existing user-facing documentation.

Keep `README.md` a short, human-readable project introduction.

Create a new local document only when durable information has no suitable existing home. Keep each fact in one authoritative place; rely on source and build configuration for discoverable details rather than maintaining duplicate inventories, command lists, or task reports.
