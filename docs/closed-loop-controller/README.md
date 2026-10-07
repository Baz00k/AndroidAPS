# Independent closed-loop controller handoff

Start here in a fresh agent session. This package provides public mathematical foundations, behavioral requirements and an AAPS integration contract for engineering a new insulin-only, fully closed-loop controller. It contains no commercial binary analysis, reference input/output fixtures or previous controller implementation.

## Receiving the package

The maintainer transfers **only this directory**, as plain files without a `.git` directory, into an empty workspace on the implementation machine. Verify the complete file set and hashes in `MANIFEST.json` before starting the agent, after checking the SHA-256 of `MANIFEST.json` itself against the maintainer's separately delivered transfer receipt. The manifest identifies bytes; it is not a cryptographic signature or a substitute for trusting the maintainer's transfer.

Do not clone this project's repository, download its full archive, fetch its history, or reuse research conversations. A sparse checkout still has a Git object database and is not an isolation boundary. Existing project files outside this package include excluded legacy work. The complete checkout, forks, historical PRs, mixed reports, credentials, binary archives and old agent sessions must be absent from the implementation machine's accessible workspace.

Use the public paper URLs and pinned OpenAPS/upstream AAPS source URLs in `sources.jsonl`. Public articles by Hovorka and colleagues are permitted scientific literature; the previous project's controller bearing that name is excluded. Internet access is not content-filtered by this package: restrict retrieval to the listed public sources and independently justified public dependencies. Do not retrieve excluded project code through a network service. Record the actual filesystem/network boundary at session start; these instructions alone do not enforce it.

The agent can design and implement the algorithm and an isolated simulation/adapter boundary using these documents. Android integration later needs a separately reviewed, history-free source snapshot supplied by the maintainer, with excluded code, reports and build/config references removed. `aaps-sources.json` identifies the inspected interfaces; it is **not** permission to fetch this fork. Do not resolve that revision or those paths through any Git host or mirror, including upstream repository URLs, which can serve objects from forks in the same network. If an interface is missing, request that specific sanitized input rather than opening the full project. Inspect and test the supplied integration baseline because the audit revision is not a promise about current main.

## Working order

1. Read [SPEC.md](SPEC.md) in full. It defines the objective, safety properties, two candidate foundations, integration seams and verification obligations. No model is preselected and no historical parameter is a patient default.
2. For a candidate design, read its records in `equations.jsonl`, then the linked claims and primary documents in `claims.jsonl` and `sources.jsonl`. Record units, source assumptions, inferred corrections, initialization, history horizon and design choices. PUBLIC is a source fact; INFERENCE is reasoning to verify, not a confirmed erratum. Some records mix these at statement level; read those tags.
3. Choose and justify the predictor/estimator, unannounced-meal treatment, objective, safety/exposure supervisor, bounded adaptation, numerical method and startup/recovery behavior. Make reasonable engineering choices within the contract rather than trying to reproduce another controller. Preserve manual/rescue treatment accounting.
4. Implement against an explicit time-labelled input snapshot and recommendation interface. Keep delivery retries, reconciliation and conservative provisional records in the application layers. Add a truthful algorithm/result identity when integrating with AAPS.
5. Verify dimensional boundaries, invalid data, low-glucose and exposure limits, adaptation confounding, restart and partial/uncertain delivery paths. Run changed dosing and relevant failures in simulation or replay before considering the implementation ready. Exercise actual Android timing/timeout and the selected integration independently of the predictor's own plant assumptions. Use simulated pumps; live-therapy interaction is outside this handoff.

Complete each stage with its assumptions and verification gaps recorded alongside the code or in the existing project documentation. Source facts are revision-bound. Numerical diagnostics in the ledgers have explicit premises but are not executable reference fixtures, mathematical proofs or clinical evidence; reproduce any diagnostic you rely on from public equations.

## Package scope

The maintainer requested preparation and publication of this docs handoff. The research basis has been reviewed for independent engineering sufficiency at a practical Oref0/Oref1 standard. This is not a finished algorithm, tested implementation, clinical validation or legal certification. Therapy safety overrides speed and simplification; preserve constraints and confirmations through integration.

- `SPEC.md`: engineering requirements and coherent mathematical/integration summary.
- `sources.jsonl`, `claims.jsonl`, `equations.jsonl`: stable evidence IDs, public URLs/identities, exact locators, assumptions and limitations.
- `aaps-sources.json`: identities of the inspected integration source files, without source bodies or history.
- `MANIFEST.json`: exact package files and integrity hashes, excluding the manifest itself.

Primary papers and third-party software are linked rather than redistributed. Article access does not grant a simulator-cohort redistribution licence. In particular, simulation software licensing and embedded virtual-patient provenance must be assessed separately. A different machine needs public-source access, not private research artifacts.
