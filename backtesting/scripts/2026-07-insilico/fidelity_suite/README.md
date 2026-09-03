# Simulator-fidelity suite

A reproducible test suite that measures **where a published in-silico simulator
(simglucose / UVA-Padova) diverges from our real historic data**, so we know exactly
which questions it can and cannot stand in for.

This exists because every policy claim in the backtesting work carries the caveat
*"there is no glucodynamic simulator, so we cannot generate the counterfactual."* A
simulator does exist; the honest question is not "does one exist" but "does it
reproduce the statistics of our data well enough to trust a controller A/B on it." This
suite answers that, signature by signature, against roughly a year of real data per
user rather than a single month.

## What it is

A **registry of signatures**. Each signature computes the *same statistic* on both
cohorts and returns a verdict:

- **PASS** — the simulator reproduces the real statistic within tolerance.
- **FAIL** — it diverges (the statistic is in the model but comes out wrong).
- **STRUCTURAL** — the mechanism is absent from the model by construction, so the
  statistic cannot be reproduced at any parameter setting (see `../fidelity_test.py`).

The real cohort is the 9 users in the local TimescaleDB (`boost_cgm` +
`boost_decisions`, 2025-08 to 2026-07). The sim cohort is 10 UVA/Padova adults over 21
days with **realistically randomised announced meals** (jittered times/sizes, skipped
snacks), so the simulator is given its best shot and any surviving gap is the model's,
not a clockwork scenario.

## Files

| File | Role |
|---|---|
| `gen_sim_cohort.py` | run simglucose with randomised meals, cache `sim_cohort.npz` |
| `common.py` | DB loaders, sim-cohort loader, cadence handling, stats (bootstrap CI, ACF, KS) |
| `signatures.py` | the signature registry — add a signature here |
| `run_suite.py` | run every signature, emit `REPORT.md` + `fig_fidelity.png` |
| `REPORT.md` | generated: the verdict table + figure + notes |
| `sensor_layer.py` | post-hoc sensor realism: jitter and compression lows |
| `behaviour.py` | the person in the loop: announcement, rescue carbohydrate, an adapting ISF setting, and a generic correction loop |
| `gen_sim_behaviour.py` | run the layered simulator; `--layers phys,behav,loop` selects any subset |
| `behaviour_compare.py` | layer-by-layer attribution, emits `REPORT_BEHAVIOUR.md` + `fig_behaviour.png` |

## Layers

Each layer targets the signatures the layer below it could not reach, and each can be
switched off, so a movement is attributable to a named mechanism rather than to the
combination. `REPORT_BEHAVIOUR.md` has the measured table: 3 of 11 signatures inside the
real range for the stock 2008 personae, 6 of 11 with all four layers.

| Layer | Mechanism | What it closes |
|---|---|---|
| physiology | S2013-style time-varying insulin sensitivity | day-to-day and diurnal statistics |
| behaviour | unannounced meals, rescue carbohydrate, an adapting ISF setting | rise tail, hypo recovery, drift |
| loop | continuous correction with an IOB bound and basal withdrawal | autocorrelation, variability |
| sensor | jitter and compression lows, post hoc | the two sensor signatures |

Outcome spread at stuck-high is the one signature no layer has closed. It is the
efficacy blind spot, and it is the reason the simulator still cannot price a dosing
change.

## Signatures

Current results: 2 PASS, 5 FAIL, 3 STRUCTURAL of 10.

1. **Glucose variability (CV%)** — distribution of per-person CV. FAIL (30 vs 22).
2. **Short-horizon delta tails (5 min)** — fat positive tails are unannounced-meal
   onsets; the sim only sees announced meals. FAIL.
3. **Autocorrelation (30/60 min)** — how fast the glucose curve decorrelates. PASS.
4. **Outcome unpredictability (BG 180-240, +30 min)** — spread of where you end up
   30 min after a stuck-high band. Real is wide (efficacy and absorption vary); the sim
   is narrow. The efficacy blind spot, measured. FAIL (x1.5).
5. **Insulin-sensitivity drift (weekly)** — real sensitivity drifts week to week; the
   virtual patient's parameters are fixed. STRUCTURAL (22% vs 0%).
6. **Post-meal-exercise counterweight** — crash rate falls with insulin-on-board; the
   model has no exercise input. STRUCTURAL (see `../fidelity_test.py` Probe A).
7. **Diurnal amplitude** — peak-to-trough of the hour-of-day mean (TZ-invariant). PASS.
8. **Hypo recovery** — time from <70 back to >=100 and rebound rate. Real recovers
   about twice as fast and overshoots more (rescue carbs); the sim has none. FAIL.
9. **Compression lows** — sharp reversing dips from sensor compression per 30 days. The
   sensor model has no compression mechanism. STRUCTURAL (2.9 vs 0).
10. **Sensor-noise texture** — high-frequency jitter (gap-aware 2nd-diff SD). Real CGM
    is about twice as noisy as the Dexcom model. FAIL.

The scope is deliberately extensible: adding a signature is one function in
`signatures.py`. Candidate next signatures: dawn slope on local time, meal-response
rise shape, exercise-window drop (once an exercise input is modelled), overnight
stability.

## Reproduce

```
python3 -m venv ~/.venvs/boost-insilico
~/.venvs/boost-insilico/bin/python -m pip install simglucose scipy matplotlib psycopg2-binary "setuptools<81"
cd fidelity_suite
~/.venvs/boost-insilico/bin/python gen_sim_cohort.py --days 21
~/.venvs/boost-insilico/bin/python run_suite.py
```

The DB must be reachable at `dbname=oref host=127.0.0.1 port=5432`.
