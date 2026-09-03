# Fidelity test: can the simglucose virtual patient stand in for our loop's hard cases?

**Date:** 2026-07-28  **Script:** `fidelity_test.py`  **Env:** `~/.venvs/boost-insilico`
**Model:** simglucose UVA/Padova 2008 adult cohort (`adult#001`–`adult#010`).

## Why this test exists

Every policy claim in the backtesting work carries the same caveat: *there is no
glucodynamic simulator, so we cannot generate the counterfactual glucose trace for a
dosing change.* The reasonable challenge is: a published in-silico model exists
(simglucose), we already have a harness around it, so why is the caveat still there?

This is the put-up-or-shut-up test. A simulator is only usable as a counterfactual
engine for **our** problem if it reproduces the failure modes that define our hard
cases. Two failure modes dominate, both measured out-of-sample in the cohort:

- **A — the exercise counterweight.** Post-meal-exercise crash rate *falls* with
  insulin-on-board (32 / 20 / 17% across IOB tertiles). The crash is a
  missing-carbohydrate problem, not a too-much-insulin problem.
- **B — the efficacy blind spot.** During a stuck high the loop typically has
  substantial insulin on board doing nothing, then over-corrects once sensitivity
  returns and the same insulin suddenly bites. Whether the insulin is working is
  unpredictable from anything we record.

If the simulator reproduces these, the harness becomes usable for counterfactual
dosing work and the caveat retires. If it cannot, that failure is itself the measured
result, and it says precisely why the counterfactual is unavailable to us.

## Result: both gates fail, and they fail by construction

| Probe | Question | Result |
|---|---|---|
| A | Can post-meal exercise even be represented? | **No.** Model input is `(CHO, insulin)`; the ODE has zero exercise / activity / heart-rate / steps term. |
| B | Does a stuck-high → rebound (insulin present but inactive) arise? | **No.** 0/10 corrections failed to fall monotonically once acting; zero glucodynamic variance across identical repeats (max Δ = 0.00 mg/dL). |

### Probe A — exercise is not in the model

The virtual patient's only inputs are carbohydrate and insulin. Insulin-independent
glucose disposal (`Fsnc`, `Vm0`/`Km0`) is a fixed parameter with no input path by
which activity can raise it. There is no dial for exercise, heart rate, or steps.

The counterweight finding is a statement about how an *exercise-driven,
insulin-independent* glucose drain interacts with residual carbohydrate flux. With no
exercise drain in the model, the finding is not merely hard to reproduce, it is
**not expressible**. To test it I would have to hand-author an exercise effect (e.g.
perturb `Vm0` on a schedule), at which point the simulator is reporting back my
assumption, not validating it. That is modelling, not simulating.

### Probe B — insulin here always works

Isolating insulin action (raise BG with a carb that finishes absorbing, then correct
into the settled high with no ongoing carb to confound the plateau):

- Every one of the ten patients fell monotonically to target once the correction
  bit. None showed insulin sitting inactive and then biting unpredictably.
- The only "stall" is a fixed ~21 min high-and-flat stretch **before** the correction
  acts, which is pharmacokinetic absorption lag, not efficacy failure.
- Re-running the identical drive produced a maximum plasma-glucose difference of
  **0.00 mg/dL**. Insulin action is a deterministic function of state. There is no
  time-varying sensitivity, no efficacy stochasticity, no mechanism for the same
  insulin to do nothing and then suddenly bite.

The stuck-high → rebound our loop hits is exactly a breakdown of the assumption this
model is built on. The model cannot reproduce it because that assumption is baked in.

## What this means

**The caveat stays, and now it is measured rather than asserted.** The reason we
cannot generate a counterfactual glucose trace for a dosing change is not that we
lack a simulator, it is that the available simulator does not contain the physics of
our two dominant failure modes. A controller A/B on these virtual patients would score
both controllers as safe in precisely the scenarios where real controllers crash
(exercise) or over-correct (efficacy), because neither scenario exists inside the
model.

**The harness is not useless — it is scope-limited.** simglucose remains a legitimate
tool for the parts of the job that *are* in the model: announced-meal dosing logic,
basal and correction behaviour in benign conditions, safety-floor plumbing, controller
sanity and regression checks. It is not a stand-in for the unannounced-meal, exercise,
and efficacy regimes that are our actual research frontier. Any use of it should be
gated to the first list and must not be quoted as counterfactual evidence for the
second.

**Building our own is a physiology problem, not an engineering one.** A simulator that
reproduced these would need an exercise/insulin-independent-disposal input and a
time-varying insulin-sensitivity process, both fitted per-person. Fitting the second
is the same identification wall the efficacy-signal probe already hit from the data
side: we could not predict insulin efficacy out-of-sample from anything we record, so
we cannot fit a generative model of it either. The two negative results are the same
wall seen from two directions.

## Reproduce

```
python3 -m venv ~/.venvs/boost-insilico
~/.venvs/boost-insilico/bin/python -m pip install simglucose matplotlib "setuptools<81"
~/.venvs/boost-insilico/bin/python fidelity_test.py
```
