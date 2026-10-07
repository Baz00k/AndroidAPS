# Fully closed-loop controller

Engineering specification and public evidence for a new insulin-only AAPS controller.

## Start here

1. Read [SPEC.md](SPEC.md) for the requirements, candidate models and integration contract.
2. Use `equations.jsonl` for symbols, units, parameters and initialization; follow its references through `claims.jsonl` and `sources.jsonl` to the public literature. `PUBLIC` denotes source facts; `INFERENCE` denotes reasoning or inferred corrections.
3. Choose and justify the estimator, objective, safety supervisor, bounded adaptation, numerical method and startup/recovery behavior. Record assumptions and verify them with independent tests.
4. Implement against the current AAPS interfaces. Run dosing and failure-path simulation/replay, integration tests and Android timing/timeout checks before considering the implementation ready. Use simulated pumps.

## Clean workspace

Give the new agent these docs and a plain-file snapshot of the current source tree, without `.git` or previous conversations. The old controller has been removed from `main`, but remains in Git history: do not fetch earlier revisions, old PRs or research artifacts. Use the cited public literature and current source.

For docs-only transfers, verify the file set and hashes in `MANIFEST.json` against the separately supplied transfer receipt. `aaps-sources.json` records the integration baseline; recheck interfaces when that baseline changes.
