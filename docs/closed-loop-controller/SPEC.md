# Independent fully closed-loop controller: engineering specification

## Goal

Engineer a from-scratch insulin-only controller for AAPS with no required routine meal announcement, glucose-trend response and bounded adaptation of insulin needs, including time-of-day behavior. Preserve manual/rescue treatment accounting. Use the practical behavioral and verification standard of Oref0/Oref1. Numerical equality with an existing algorithm is not a goal.

Basis A supplies a physiological predictor/plant; Basis B supplies a simulated MPC/MHE formulation. Choose the estimator, objective, supervisor, solver/discretization, initialization, bounded tuning and adaptation. Record assumptions and independent tests. Historical parameter values are not patient defaults or dose limits.

Therapy safety overrides speed and simplification. Trace inputs through decisions, constraints, commands and delivery records. Preserve safeguards and confirmations. Distinguish recommendations from acknowledged/confirmed delivery; app/driver uncertainty must not lead to duplicated insulin. No simulation or source review constitutes clinical validation.

Full symbols, units, parameters and initialization are in `equations.jsonl`; citations and source identities are in `claims.jsonl` and `sources.jsonl`. Inferred corrections are not confirmed errata. `CHK-nn` denotes numerical diagnostics, not proofs. The sampled Hessian diagnostics lack a complete sampling grid; establish convergence and failure behavior independently.

## Required behavior

Numerical thresholds come from profile/constraint inputs or justified design choices.

| Property | Required specification and independent check |
|---|---|
| Input validity | Required glucose/profile/time/insulin data must be finite, unit-labelled and have declared quality/age rules. Missing data cannot produce a guessed dose. Test both controller rejection and the composed app response, including any high TBR already running: returning no result is not automatically cancellation or a safe basal fallback. |
| Low-glucose protection | Define current/predicted-low quantities and their relationship to the predictor and safety supervisor. Test that invalid permissions or a declared low-glucose risk suppress additional automated bolus delivery and trigger the stated basal-reduction action. |
| Exposure and rate bounds | State the accounting domain (total versus basal-relative, bolus versus basal), units and time window of each limit. A basal rate is U/h; convert its contribution through the stated duration and scheduled-basal reference before comparing an amount limit. Evaluate combined TBR/SMB consequences and insulin effect beyond the prediction horizon, not a dimensionally undefined `IOB + request`. Preserve app constraints after command quantization. Define and test behavior at app max-IOB zero (LGS), including whether above-scheduled-basal rates are allowed when basal-deviation IOB is negative. |
| Dosing permission and fallback | Closed-loop/LGS/SMB permissions and `tempBasalFallback` must be honored. Fallback disables SMB and recomputes a temp-only decision from current accounting; it is not a retry or addition of a failed amount. Controller/app validity signals may inhibit dosing when accounting cannot be trusted. |
| Delivery boundary | No controller pump commands, delivery retries, reconciliation writes or parallel pending-insulin ledger. Source-recorded provisional insulin is accounted conservatively, not relabelled physically confirmed. Exercise queue rejection, partial/uncertain delivery representation and delayed history in the integration; transport-only metadata does not alter the mathematical decision unless accounting or safety permissions change. |
| Partial enactment | Test TBR-only, skipped commands, failed TBR, failed SMB and unchanged-success callbacks. TBR-first ordering is not proof of a newly applied protective zero temp. Do not assume an automated bolus is always paired with basal reduction; the chosen strategy must specify its own protection. |
| Unannounced meals and adaptation | Demonstrate response without carb entry, back-off when a rise stops, meal/sensor/sensitivity confounding, sensitivity shifts and bounded learning/time-of-day behavior. Optional carb/manual/rescue records must not double-count insulin or disturbances. State any deferred capability explicitly. |
| Time, replay and lifecycle | Inject one evaluation time; same complete snapshot/configuration/initial state/time gives the same mathematical result. Replay equality is not permission to enact twice. Rebuild or restore estimator state with explicit expiry/corruption/startup rules; restart must not erase app history or shorten bolus spacing. Cold-versus-warm dose ordering and global dose monotonicity are not universal laws for adaptive/MPC controllers. |
| Numerical failure | Bound computational work, detect nonfinite/infeasible/nonconvergent results, and test timeout/invalid-result handling through the actual app contract. No plausible dose or solver success code substitutes for validated finite outputs and active constraints. |

## Oref0/Oref1 benchmark

The comparison uses public source at `openaps/oref0@d219baf9559d62a8bb0bd42014192a2d7c0839ef` and upstream `nightscout/AndroidAPS@598e2eb39c7e15876e4c42876a2162bffcb4fe5f`. Source URLs, hashes and locators are in the ledgers. Upstream facts are not automatically facts about the integration baseline.

| Comparison fact | Implication for independent engineering | Evidence |
|---|---|---|
| Oref1 SMB/UAM logic is within the Oref0 codebase; its per-call algorithm derives timing/state from inputs. | Inject evaluation time and declare estimator state explicitly. Deterministic replay is useful; no requirement to imitate its heuristic equations. | `CLM-PUB-OREF-0001–0002` |
| Rig pump-loop checks and AAPS Loop/queue/pump-sync surround the algorithm. Failed SMB fallback is an app-triggered bolus-disabled recomputation. | Keep transport, reconciliation and pump retries outside the controller. Preserve permissions and fallback at the new adapter boundary. | `CLM-PUB-OREF-0003,0005–0007,0017` |
| TBR callback success can mean no new TBR was enacted. Oref can return an SMB plus a high TBR rather than invariably a protective low TBR. | Verify combined and partial actions; sequencing alone does not ensure basal reduction or confirm delivery. | `CLM-PUB-OREF-0006,0011` |
| Input guards and app early returns interact: a no-result path may leave a prior high TBR active. | Test the composed stale/missing-input response. Specify existing-temp handling separately from the absence of a new recommendation; do not guess a fallback basal. | `CLM-PUB-OREF-0008,0015` |
| Oref has predictor-specific low protection, decision-time insulin limits, per-bolus caps and pump rounding. Its IOB is basal-relative, not total physiological insulin mass. | Supply independently justified low/exposure protection and explicit units/domains for the new model. Keep app constraints and define the combined TBR/SMB exposure window. Oref constants are benchmark facts, not new clinical defaults. | `CLM-PUB-OREF-0009–0011` |
| Oref predicts four hours even when the insulin-action curve extends longer. | State treatment of delayed effects beyond the chosen horizon; the Oref horizon is not evidence of adequacy for another controller. | `CLM-PUB-OREF-0014` |
| Source tests express missing-input handling, but the inspected guard order cannot reach some asserted error returns. | Test finite/invalid/missing input behavior directly with independently justified expected properties. | `CLM-PUB-OREF-0013,0019` |
| Kotlin and JavaScript ports differ; restart assumptions depend on when drivers persist boluses. | Numerical equality is not the goal. Exercise history lag, repeated invocation, process restart and estimator reconstruction; a replay-equal request must not be enacted twice. | `CLM-PUB-OREF-0016,0018,0020` |

## Public mathematical foundations

### Unit convention and input accounting

- Basis A: time min; insulin rate mU/min, insulin states mU or mU/L; glucose mass mmol and concentration mmol/L. A bolus is an insulin-mass impulse into absorption state, not an infusion rate.
- Basis B: time index at `T_s = 5 min`; insulin U per sample relative to scheduled basal; glucose deviation mg/dL.
- Conversion: `1 U = 1000 mU`; `1 U/h = 1000/60 mU/min`; a constant rate `r U/h` over `Δt min` contributes `r·Δt/60 U`. The reviewed glucose factor is `18.0 (mg/dL)/(mmol/L)`; use one explicit conversion per boundary. Carbohydrate glucose equivalents use `1000/180 mmol/g`, with a source unit-label ambiguity retained.
- App-native IOB is **not** physiological compartment mass or a delivered-rate history. A rate-driven predictor consumes complete selected history once; under that premise, a second IOB-derived prediction effect counts that insulin twice. A separately defined exposure/safety supervisor is still allowed and required to preserve the chosen protection.
- Provisional source records remain conservatively accounted by the app. Distinguish that assumption from confirmed physical delivery. Unknown prior boluses are not cured by initializing to basal equilibrium; history/uncertainty/eligibility and the composed current-temp response require explicit startup design.

Evidence: `EQ-PUB-MATH-0001,0014,0016`; `CLM-PUB-MATH-0029`; `CLM-PUB-MATH-REVIEW-0004–0006`.

### Basis A: public physiological prediction

Compartment structure from public restatements (`EQ-PUB-MATH-0002–0009`; `SRC-PUB-MATH-0007–0010`; `SRC-PUB-MATH-REVIEW-0001–0002`):

```text
dS1/dt = u − S1/t_maxI
dS2/dt = (S1 − S2)/t_maxI
dI/dt  = S2/(t_maxI·V_I) − k_e·I
dx_i/dt = −k_ai·x_i + k_bi·I,     S_i = k_bi/k_ai, i=1,2,3

dQ1/dt = −F01c − F_R − x1·Q1 + k12·Q2 + EGP + U_G
dQ2/dt = x1·Q1 − (k12+x2)·Q2
G = Q1/V_G
EGP = max(EGP0·(1−x3), 0)

F01c = F01·min(G/4.5, 1)
F_R = 0.003·(G−9)·V_G if G≥9 mmol/L, otherwise 0

dD1/dt = A_G·D − D1/t_maxG
dD2/dt = (D1−D2)/t_maxG
U_G = D2/t_maxG
dC/dt = k_a_int·(G−C),           y_CGM = C + v
```

State units: `S1,S2` mU; `I` mU/L; `x1,x2` min⁻¹; `x3` dimensionless; `Q1,Q2,D1,D2` mmol; `G,C` mmol/L. Every derivative has its state's unit per minute. `F01c,F_R,EGP,U_G` are mmol/min. `V_I,V_G` are L; `k_e,k12,k_ai,k_a_int` min⁻¹; `k_b1,k_b2` L/(mU·min²), `k_b3` L/(mU·min). All symbols and historical parameter sets are in the equation ledger; their population/single-subject context must not be mixed into new patient defaults.

A separate published simulator variant uses `F01c=(F01/0.85)·G/(G+1)` and `F_R=R_cl·(G−R_thr)·V_G` only if `G≥R_thr`, otherwise zero. Its gut-appearance ceiling is **per kg**, so total flux is bounded against `U_Gceil·BW`; increasing `t_maxG` caps appearance without discarding absorbed mass. Published gut impulses and ingestion-rate inputs use different conventions. These are alternatives, not terms to combine indiscriminately (`EQ-PUB-MATH-0006–0008`).

**Explicit inferences/limitations:** the EGP sign is corroborated as suppression rather than the printed plus sign; the `0.161·BW` value in one source conflicts with its fasting balance and independently printed `0.0161·BW` value. These corrections are inferred, not confirmed publisher errata. Activation/deactivation naming differs across tables. Historical Gaussian priors can admit negative rates, and the printed exponential/lognormal transform must be resolved before sampling. An implementer must choose physically admissible parameters and preserve nonnegative states numerically; the truncation rule is not supplied by the ambiguous table (`CLM-PUB-MATH-0011,0015–0017`; `CLM-PUB-MATH-REVIEW-0002–0003,0009–0010`).

For fasting initialization on the applicable flux branches and where the EGP clamp is inactive, the algebraic balance is:

```text
S1,b = S2,b = u_b·t_maxI,  C_b = G*,  D1,b = D2,b = 0
I_b = u_b/(k_e·V_I),  x_i,b = S_i·I_b
Q1,b = V_G·G*,       Q2,b = x1,b·Q1,b/(k12+x2,b)
0 = EGP0·(1−S_IE·I_b) − F01c(G*) − F_R(G*)
    − x1,b·Q1,b·x2,b/(k12+x2,b)
```

Use the clamped EGP term outside that region. One basal/glucose equilibrium constrains a parameter combination, not all individual sensitivities (`EQ-PUB-MATH-0009,0015`). Clinical/AAPS IOB cannot be equated to `S1+S2+V_I·I`: absorption mass and delayed remote action differ; the reported x1-channel calculation is an arithmetic diagnostic, not observed glucose behavior or a clinical action curve.

Published Cambridge descriptions support Kalman-type model-mismatch flux/bioavailability updates, competing models, asymmetric target trajectories and time-of-day adaptation, but do not provide exact cost/weights/priors/model mixing/learning rules. Define and test the estimator/objective/controller for delayed insulin, CGM-only observability and unannounced meals. Meal appearance and a free corrective glucose flux enter additively; unconstrained joint adaptation can confuse meal/sensor/sensitivity effects (`EQ-PUB-MATH-0010–0015`).

### Basis B: published MPC with moving-horizon estimation

The author-hosted Copp–Gondhalekar–Hespanha manuscript supplies this discrete simulation formulation (`SRC-PUB-MATH-0001–0005`; `EQ-PUB-MATH-0016–0021`). Values below describe that published instance only.

```text
T_s = 5 min, y_s = 110 mg/dL
u_t = u_IN,t − u_BASAL·T_s/60          [U/sample]
y_t = y_BG,t − y_s                    [mg/dL]
p1 = 0.98, p2 = 0.965
g = −90·(1−p1)·(1−p2)^2
Y(z⁻¹)/U(z⁻¹) = (1800g/u_TDI)·z⁻³ / ((1−p1z⁻¹)·(1−p2z⁻¹)^2)

x_(t+1) = A·x_t + B·u_t + D·d_t,     y_t = C·x_t + n_t
A = [[p1+2p2, −2p1p2−p2², p1p2²], [1,0,0], [0,1,0]]
B = (1800g/u_TDI)·[1,0,0]ᵀ, D = −B/10, C = [0,0,1]

h(y) = (atan(0.1y)+π/2)·y + 10
c(y) = 0.02·h(80−y) + 0.005·h(y−120)

min_(u_t..t+4) max_(x_(t−3)∈X, d_(t−3)..d_(t+8)∈[0,0.5]) J_t
J_t = Σ_(k=t+1..t+9) c(C·x_k+y_s)^2 + 2Σ_(k=t..t+4) u_k^2
      −2Σ_(k=t−3..t+8) d_k^2 −300Σ_(k=t−3..t) n_k^2
n_k = y_k − C·x_k for measured window samples
0 ≤ u_k+u_BASAL·T_s/60 ≤ u_MAX,      u_k=0 for k≥t+5
Apply the first selected input, then recompute at the next sample.
```

`u_TDI` is daily insulin amount U; each state/output/noise is glucose deviation mg/dL. The disturbance is insulin-equivalent per sample, scaled through `D`, **not grams of carbohydrate**. Past inputs in the estimation window are known accounting inputs, not repeated dose decisions. Objective horizons are prediction 9, control 5 and estimation 3 samples. The positive disturbance set can explain rises; unexpected falls must be handled through the initial-state/noise treatment and safety design, not presumed representable as a negative meal.

The absolute-glucose argument `c(Cx+y_s)` is an **INFERENCE** from the plotted cost and inconsistent deviation notation. Cost constants carry units and must be rescaled when glucose units or sample period change. The published `u_MAX=25 U` is a simulation/data guard, **not an acceptable therapy cap**. The short 45-minute costed horizon does not establish safety against delayed insulin effects; define longer-tail protection and mismatch tests (`EQ-PUB-MATH-0019–0021`; `CLM-PUB-MATH-REVIEW-0006,0013–0014`).

**Consequential design choices:** `X`, startup with an incomplete measurement window, CGM gaps, realistic actuation/exposure limits, objective/horizon changes and numerical failure behavior. The theory assumes a saddle point; its sufficient conditions do not automatically certify this modified glucose instance. The reported sampled Hessian diagnostic is not a proof, existence threshold or clinical criterion. Inspect numerical convergence/duality gaps and require an explicit unusable-result path rather than assuming solver success implies a usable dose (`EQ-PUB-MATH-0020,0025`; `CLM-PUB-MATH-REVIEW-0007`).

The published comparator observer has a gain/sign-convention inconsistency (**INFERENCE**, arithmetic), with a scoped stability check. Re-derive any chosen observer under its predictor/filter convention; no numerical radius proves clinical closed-loop stability. Diurnal zone and IOB-constrained extensions are separate published work; figure-only IOB curves cannot be guessed, and meal-announcement bolus logic is not a fully closed-loop mechanism (`EQ-PUB-MATH-0023–0024`).

Keep each quantized command within active limits. Rounding residuals must not make up insulin across missed, suspended, partial or uncertain delivery; they may be discarded. Delivery recording/reconciliation remains app-owned (`EQ-PUB-MATH-0022`; `CLM-PUB-MATH-REVIEW-0008`).

### Adaptation

For either basis, choose the adaptation representation, rate/cadence and parameter bounds. Justify identifiability, evaluate time-of-day needs, and test meal/exercise/sensor confounding and inappropriate learning (`EQ-PUB-MATH-0015,0026`).

Preserve the required safety, exposure and input-validity checks regardless of the chosen model or adaptation method.

### Evaluation

Pinned simglucose code is MIT-licensed, but its virtual-cohort provenance/redistribution rights remain unresolved. It is a research S2008 reimplementation, not the FDA-accepted commercial simulator or a validated plant by virtue of downloading it. ReplayBG is GPL-3.0 and expects data in a prescribed format; use with purely synthetic inputs remains unverified here. A plant built from Basis A shares structure with Basis A control and cannot alone rule out shared-model bias (`SRC-PUB-MATH-0021–0024`).

Before implementation testing, define scenarios and independently justified criteria for unannounced meals, stalled rises, low/falling glucose, sensitivity shifts, noise/gaps/stale clocks, recent manual/provisional insulin, state loss/corruption, repeated triggers, failed/partial/uncertain/skipped delivery and optimizer invalid/timeout outcomes. Evaluate behavior and risk boundaries. Verify simulation-tool and cohort provenance, check model mismatch, and measure Android timing/timeout behavior through the actual integration.

Develop a simulation/testing harness alongside the controller. Exercise the implemented controller with explicit evaluation time, configuration and initial state, reproducible scenarios/seeds, and simulated CGM and pump delivery. Preserve scenario inputs and results so failures can be replayed and become regression tests. Expected safety properties and acceptance criteria must be justified independently of the controller's output.

Cover both closed-loop response under model mismatch and the composed input-to-delivery failure paths above, including app constraints and requested, acknowledged and confirmed delivery accounting. Keep the harness isolated from live therapy. Run repeatable automated checks in CI and use the results, together with Android integration and timing checks, for the feature branch's readiness review; report the harness's assumptions and remaining verification gaps.

## AAPS integration

### Controller input/output contract

Integration source identities are pinned in `aaps-sources.json`. Recheck these contracts when the source changes; interfaces may be redesigned for the new controller.

| Input | Units / semantics | Adapter responsibility |
|---|---|---|
| Evaluation time and CGM | Epoch milliseconds; glucose mg/dL; raw/smoothed/gap-filled origin preserved | One evaluation time; declare stale/future/error/gap policy and ages of derived data. The seven-minute status cutoff is Oref's provider behavior, not an automatic guard for every APS. |
| Profile and target | Basal U/h, ISF mg/dL/U, carb ratio g/U, insulin-action duration h, targets mg/dL | Resolve current profile/temp target, check applicable hard limits, preserve manual/rescue input semantics. Only fields required by the selected model are mathematical inputs. |
| Personalization scalars | Daily insulin U/day; body weight kg if the selected model needs it | No body-weight source is established by this package. Choose and validate each source and its missing-data rule. A daily-insulin value derived from the controller's own delivery is a feedback/adaptation path and must be bounded like other adaptation. |
| Insulin history or prediction summary | Bolus/extended amounts U; basal rates U/h; history durations ms. App-native IOB U and activity U/min | Include valid provisional records, exclude invalid/priming/reference copies, and handle extended-bolus-as-TBR exactly once. Use history for a rate-driven predictor or app summaries as appropriate; app-native exposure accounting remains separately declared. Reconstruct past absolute delivery against the effective profile in force at each past time, not the current profile. |
| Scheduled basal and current temp | U/h absolute, remaining duration min | Scheduled profile basal is the deviation-accounting reference; pump base basal is the current neutral-cancel reference. Use the processed active TBR/converted-extended view, not a raw table that drops emulated delivery. |
| Exposure/rate/bolus limits | U / U/h / U, with defined domain and window | Ensure finite active sources, preserve LGS/doubled-BG/expiry zero constraints, and provide justified profile-relative rate limits that otherwise belonged to Oref. A getter returning `Double.MAX_VALUE` is not a usable exposure cap. |
| Dosing permissions and fallback | Explicit booleans | Fold existing mode/SMB permissions with fallback. If automation/bolus is disallowed, emit no automated bolus. Oref's SMB preference is not automatically active under a different APS. |
| Pump resolution | Bolus U, basal U/h; capability/style flags | Quantize through declared adapter/platform ownership and verify limits remain effective after rounding. |

| Output intent | Current encoding / behavior | Required adaptation/test |
|---|---|---|
| No TBR change | `RT.rate` or `RT.duration` null; existing temp continues | Distinguish from invalid/no decision and explicit cancellation. A constrained zero rate with a false request flag is not a suspend request. |
| Cancel | `(rate=0 U/h,duration=0 min)`; cancellation if active, otherwise no-op | Preserve explicit intent through clone/constraints/persistence. Current Loop also treats a near-pump-base rate as cancel. |
| Set TBR | Finite nonnegative absolute U/h and positive minutes | Apply constraints; test current equivalent-temp let-run deduplication. Reject nonzero rate with zero/negative duration; do not rely on current Loop to validate it. Choose durations supported by the active pump; assess exposure over the full commanded duration because a missed later cycle leaves the temp running. |
| Automated bolus | Finite nonnegative U, capped and permission-gated | Existing platform may zero/reject it, and attempts it only after a TBR callback reports success/enacted. That can be a no-op, not proof of protective delivery. |
| No valid decision | No APS result; no new command, previous temp persists | Document each composed invalid-input outcome and surfaced signal. Cancellation requires a tested trusted app/reference path; never invent a fallback profile or dose. |
| Diagnostics and identity | New algorithm identity, source/evaluation timestamps, typed input echo, reasons/limiting factors and optional unit-labelled predictions | Add a dedicated result/input serialization path; round-trip it and isolate existing Oref readers. |

Express recommendation intents explicitly. Solver state may be persisted or rebuilt from history. Keep source timestamps and derived-data ages separate from command deadlines.

### Integration verification checklist

Exercise these with simulation/mocks, never a patient-connected pump:

1. Active finite exposure/rate limits and applicable zero constraints; mode/SMB preference/fallback denial produces no bolus.
2. No-change/cancel/set/let-run intents survive result cloning and truthful typed persistence round-trips; nonfinite data and serialization failure are rejected, surfaced and have an observed composed outcome.
3. Invalid/stale/profile-missing input with a high temp already running produces the explicitly documented response—not an assumed cancellation or guessed neutral dose.
4. Provisional→confirmed/corrected/merged history counts the event once; invalid/priming records are excluded, emulated TBR/extended delivery included, and app-native accounting remains distinct from predictor state.
5. TBR callback failure suppresses SMB; SMB failure recomputes with bolus disabled from fresh history, without make-up dosing or repeated controller retries. Temp-only recomputation may legitimately exceed the earlier SMB-run TBR.
6. Queued bolus, no-op TBR success, open-loop acceptance and skipped enactment each have a tested result; callback success is not a physical-delivery assertion.
7. Test the chosen exposure bound with explicit U/h-to-U conversion and window: per-cycle and joint TBR/SMB bounds are different policies, not interchangeable assertions. Cover equality, above-limit and negative basal-deviation cases.
8. Process restart/rebuild, repeated triggers and already-recorded/provisional SMB preserve accounting and spacing. Separately exercise delayed source data with a freshly refreshed command deadline.
9. For targeted drivers, verify provisional-before-delivery/reconciliation and uncertain/partial outcomes against simulated sinks; app-level inhibition or conservative accounting handles unrecorded insulin. Do not add a pump transport or retry mechanism to the controller.
