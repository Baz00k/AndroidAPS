# Project direction

This highly experimental fork aims to modernize AAPS and develop a closed-loop insulin-delivery algorithm. Favor a clean, minimal, readable, native Android UI and reduce technical debt as the project evolves. It may eventually become a separate project; [upstream AndroidAPS](https://github.com/nightscout/AndroidAPS) remains a useful reference, not a compatibility target.

Development is fast-moving, with no legacy-support commitment. Prefer removing obsolete code and simplifying designs over preserving compatibility layers. This fork is for experimental use at the user's own risk, not a general-purpose supported AAPS distribution.

## Therapy safety

**Therapy safety overrides every other goal, including development speed and simplification.** This app can command insulin delivery; an overdose can cause severe injury or death. Experimental status never lowers the safety bar.

- For changes affecting therapy, trace the path from inputs through dosing decisions, constraints, pump commands, and delivery records. Account for units, time, stale or missing data, and retries or uncertain delivery where relevant.
- Preserve safety constraints and confirmations through refactors and UI changes. Removing legacy support is not a reason to remove a safeguard.
- Handle uncertainty explicitly: distinguish requested, acknowledged, and confirmed delivery, and prevent retries from duplicating insulin. Use the established safe fallback when inputs or delivery state cannot be trusted.
- Verify changed dosing behavior and relevant failure paths in simulation or replay before considering it ready. Passing tests or simulations is not clinical validation; report remaining uncertainty clearly.

## Implementation

- Favor small, cohesive designs, clear names, explicit state, and readable control flow. Reduce indirection and duplication when working in an area.
- Follow local conventions where they support the fork's direction; improve obsolete patterns rather than copying them into new code.
- Keep the native UI minimal and understandable, with therapy state, dose units, and consequential actions unambiguous.
- Discover build tasks and setup requirements from the Gradle configuration and project tooling.

## Verification

- Test quality matters more than test count or coverage percentage. Choose coverage by behavioral risk, with particular attention to dosing boundaries and failure handling.
- Tests should catch plausible regressions using independently justified expectations. Avoid tautological assertions, implementation-mirroring tests, redundant cases, and tests of external libraries themselves; test our integration behavior where it matters.
- Run the checks relevant to the change. For therapy logic, include applicable boundary, invalid-input, and failure-path cases; for low-impact changes, keep verification proportionate.
- Use available simulators, Android emulators, and real devices connected through ADB to exercise changed behavior autonomously. For UI changes, interact with the affected flow and inspect the rendered result; use logs to investigate failures.
- Keep manual testing isolated from live therapy: use simulated pumps or a verified non-patient-connected test setup for delivery actions. Preserve therapy settings and records on real devices.
- Report what was actually verified and any gaps, including unavailable devices or simulations.

## Documentation

Keep local Markdown documentation to the absolute minimum. Prefer self-explanatory code and focused comments explaining non-obvious reasoning; put user-facing behavior and setup guidance in the existing user-facing documentation.

Keep `README.md` a short, human-readable project introduction.

Create a new local document only when durable information has no suitable existing home. Keep each fact in one authoritative place; rely on source and build configuration for discoverable details rather than maintaining duplicate inventories, command lists, or task reports.
