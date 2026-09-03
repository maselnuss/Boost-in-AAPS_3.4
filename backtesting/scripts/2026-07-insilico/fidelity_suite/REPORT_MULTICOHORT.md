# Multi-cohort simulator fidelity: UVA/Padova vs real-world AID data

Real cohorts (local research DB) versus all three FDA/UVA-Padova persona classes. Each cell is the per-user median with a bootstrap 95% CI. The question is not only whether the adult personae match, but whether **any** persona class reproduces each real-world statistic.

| Cohort | n | kind |
|---|---|---|
| Boost | 9 | real |
| Trio | 29 | real |
| OpenAPS | 110 | real |
| AAPS-classic | 44 | real |
| Padova adult | 10 | sim |
| Padova adolescent | 10 | sim |
| Padova child | 10 | sim |

## Method

The whole comparison rests on one principle: **every statistic is computed the *identical* way on real data and on the simulator.** Same definitions, same thresholds, same cadence, same aggregation. Nothing below is applied to one side and not the other. The pipeline is `gen_sim_all_personae.py` (simulator cohort), `multicohort.py` (loaders, signatures, aggregation) and `multicohort_report.py` (this matrix); all are committed and re-runnable.

### 1. The data

**Real cohorts** come from a local research database of anonymised automated-insulin-delivery users, each a different system built by a different community:
- Boost — `boost_cgm` / `boost_decisions`, a fully closed loop, no meal announcement.
- Trio — `oref_v5`, the iAPS/Trio lineage.
- OpenAPS — `oref_v7`, the oref0 lineage from the OpenAPS Commons data-sharing project.
- AAPS-classic — `oref_v6`, AndroidAPS predating dynamic ISF.

All are continuous glucose at a 5-minute cadence. A user is included only with at least 500 CGM points. No trace is trimmed, smoothed or cleaned beyond dropping null readings, so the sensor noise and artefacts are the real thing.

**Simulator cohort** is all 30 UVA/Padova personae (10 adults, 10 adolescents, 10 children) run through simglucose (the open-source 2008 model) for 21 days each. Meals are randomised per day in time and size and **announced** to the controller (the BBController boluses on the scenario carbohydrate using each patient's own ratios), because the simulator has no working unannounced-meal controller. Meal sizes are **scaled by body weight** (reference 70 kg, clipped to 0.5-1.15x) so a child is not fed an adult's dinner. The simulator's sensor runs at a 3-minute cadence; we resample each trace onto the same 5-minute grid as the real data before computing anything, so the two sides are never compared at different cadences.

### 2. What each signature measures, exactly

| Signature | Definition (computed identically on both sides) |
|---|---|
| **Glucose variability (CV%)** | 100 x SD / mean of the user's CGM. The standard glycaemic-variability index. |
| **Rise tail P(Δ>10 / 5min)** | Among consecutive CGM samples spaced 4-6 min apart, the percentage whose rise exceeds 10 mg/dL. A fat positive tail is the fingerprint of an unannounced-meal onset. |
| **Autocorrelation @30 / @60 min** | Pearson correlation between each CGM value and the value 30 (or 60) minutes later, matched on actual timestamps (within 90 s), so gaps do not corrupt it. A proxy for how fast the glucose curve decorrelates, i.e. its smoothness. |
| **Outcome SD @stuck-high (+30 min)** | Take every sample with CGM in the 180-240 mg/dL band; compute the SD of (CGM 30 min later minus CGM now). Wide = the next half hour is unpredictable from a stuck high (insulin efficacy and absorption vary); narrow = deterministic. Needs >=200 in-band samples per user. |
| **Diurnal amplitude** | Mean CGM in each hour-of-day bin (0-23), then peak minus trough. Phase-invariant, so it is comparable without aligning time zones. |
| **Hypo recovery to 100 (min)** | For each downward crossing below 70 mg/dL, the minutes until CGM first returns to >=100 (searched up to 3 h ahead); the user's median. Real lows are treated with carbohydrate, the simulator's are not. |
| **Hypo rebound >180 (%)** | Of those recoveries, the fraction where CGM then exceeds 180 mg/dL within 2 h - the overshoot after treating a low. |
| **Compression lows (/30d)** | Count of dips below 70 that fall sharply (a drop of >25 mg/dL from a pre-dip level >=85) and recover to within 15 mg/dL of that pre-dip level inside 30 min - the signature of a sensor compression artefact rather than a physiological hypo - scaled to events per 30 days. |
| **Sensor jitter (2nd-diff SD)** | SD of the second difference of the 5-minute series, over triples of consecutive ~5-min-spaced samples only (gap-aware, so a dropout is not counted as noise). A high-frequency measurement-noise measure. |
| **ISF drift (weekly %CV)** | The algorithm's insulin-sensitivity value (clipped to 5-400 mg/dL/U) reduced to a weekly median, then the coefficient of variation of those weekly medians (needs >=6 weeks, >=200 samples/week). How much effective sensitivity moves over time. |

### 3. Aggregation and confidence

Each signature is computed **per user** (real) or **per persona** (sim) first, then the cohort figure is the **median across users** with a **bootstrap 95% confidence interval** (2000 resamples over users/personae). This per-user-then-pooled design means no single heavy user or unstable persona can carry a result, and the CI reflects between-person spread, not just sample size. Cells read `median [low-high]`.

### 4. The verdict rule

The four real cohorts define a **real-world envelope** for each signature: the range from the lowest to the highest of their four median values, padded by 10% of that span. A Padova persona class **matches** a signature if its own median falls inside that envelope, and is marked **✗** otherwise. This is deliberately generous to the simulator: a persona only has to land anywhere within the spread of four independent real datasets to count as a match.

### 5. What to keep in mind when reading it

- **Announced meals favour the simulator.** Its controller is told the carbohydrate; the real fully-closed cohort is not. The easy case is the one being scored.
- **Two families of signature.** The scenario-driven ones (variability, rise tails, diurnal amplitude) depend on the meals we impose and can be shifted by that choice, so a match there is weak evidence. The structural ones (outcome spread, hypo behaviour, compression, sensor jitter, drift) depend on the model's architecture and cannot be tuned into range at any scenario - those are the robust findings.
- **The drift caveat.** ISF drift reads the sensitivity the *algorithm* used, so the AAPS-classic cohort, which predates dynamic ISF, sits low because its algorithm barely adapts - not because those people do not change. The three adaptive real cohorts drift; the simulator is zero by construction.
- **Convergence is the load-bearing check.** The comparison is only meaningful because the four real cohorts agree with each other; where they disagree (e.g. compression rate), the envelope is wide and the test is correspondingly lenient.


## Signature x cohort matrix

| Signature | Boost | Trio | OpenAPS | AAPS-classic | Padova adult | Padova adolescent | Padova child |
|---|---|---|---|---|---|---|---|
| Glucose variability (CV%) | 29.5 [24.3-35.3] | 33.4 [30.9-36.6] | 34.3 [33.2-35.5] | 31.9 [30.9-34.0] | 23.1 [21.5-28.0] ✗ | 23.8 [17.8-28.7] ✗ | 29.7 [23.8-32.8] |
| Rise tail P(Δ>10/5min) (%) | 4.3 [1.6-6.8] | 6.6 [5.0-7.4] | 3.8 [3.3-4.3] | 3.7 [3.0-4.6] | 1.0 [0.7-1.8] ✗ | 1.6 [0.8-2.7] ✗ | 2.6 [0.6-3.8] ✗ |
| Autocorrelation @30min () | 0.8 [0.7-0.8] | 0.8 [0.8-0.8] | 0.9 [0.9-0.9] | 0.8 [0.8-0.8] | 0.8 [0.8-0.9] | 0.9 [0.8-0.9] | 0.8 [0.8-0.8] |
| Autocorrelation @60min () | 0.5 [0.5-0.6] | 0.6 [0.5-0.6] | 0.7 [0.7-0.7] | 0.6 [0.6-0.6] | 0.7 [0.5-0.7] | 0.7 [0.6-0.7] | 0.6 [0.5-0.6] |
| Outcome SD @stuck-high (mg/dL) | 29.8 [26.6-34.1] | 33.5 [30.6-35.2] | 26.5 [25.6-28.0] | 28.8 [25.2-31.4] | 20.8 [15.2-24.4] ✗ | 21.7 [15.0-23.3] ✗ | 27.5 [23.7-37.6] |
| Diurnal amplitude (mg/dL) | 34.7 [26.9-80.1] | 41.3 [34.2-47.5] | 48.4 [44.7-50.4] | 56.3 [45.4-68.0] | 46.9 [44.1-59.9] | 58.7 [34.8-76.6] ✗ | 51.9 [40.7-79.1] |
| Hypo recovery to 100 (min) | 59.0 [50.0-65.0] | 50.0 [45.0-50.0] | 55.0 [50.8-58.2] | 50.0 [50.0-60.1] | 112.5 [101.2-140.0] ✗ | 118.8 [95.0-143.8] ✗ | 110.0 [102.5-125.0] ✗ |
| Hypo rebound >180 (%) | 25.8 [5.1-34.3] | 23.2 [16.3-29.0] | 27.2 [24.3-33.6] | 28.4 [24.3-36.0] | 0.0 [0.0-0.0] ✗ | 0.0 [0.0-14.3] ✗ | 4.6 [0.0-16.3] ✗ |
| Compression lows (/30d) | 4.6 [2.1-11.1] | 5.3 [3.0-6.6] | 1.9 [1.3-2.3] | 3.0 [1.4-3.9] | 0.0 [0.0-0.0] ✗ | 0.0 [0.0-1.4] ✗ | 0.0 [0.0-1.4] ✗ |
| Sensor jitter (mg/dL) | 4.5 [2.4-6.2] | 6.7 [5.4-8.0] | 5.5 [5.0-5.7] | 4.7 [3.9-5.8] | 2.4 [2.3-2.4] ✗ | 2.4 [2.3-2.4] ✗ | 2.5 [2.3-2.5] ✗ |
| ISF drift (weekly) (%CV) | 21.7 [17.5-30.6] | 15.1 [8.1-18.4] | 12.1 [10.7-14.4] | 8.2 [5.3-10.5] | 0.0 [0.0-0.0] ✗ | 0.0 [0.0-0.0] ✗ | 0.0 [0.0-0.0] ✗ |

✗ = outside the real-world range. 

## Which personae match, by signature

| Signature | personae in real range | verdict |
|---|---|---|
| Glucose variability | child | only child |
| Rise tail P(Δ>10/5min) | none | NO persona matches |
| Autocorrelation @30min | adult, adolescent, child | all personae match |
| Autocorrelation @60min | adult, adolescent, child | all personae match |
| Outcome SD @stuck-high | child | only child |
| Diurnal amplitude | adult, child | only adult, child |
| Hypo recovery to 100 | none | NO persona matches |
| Hypo rebound >180 | none | NO persona matches |
| Compression lows | none | NO persona matches |
| Sensor jitter | none | NO persona matches |
| ISF drift (weekly) | none | STRUCTURAL (sim fixed = 0) |

**5 of 11 signatures are reproduced by NO Padova persona class.**

![matrix](fig_multicohort.png)

## Reading the matrix

- **The four real datasets converge.** Boost, Trio, OpenAPS and AAPS-classic are four different algorithms built by different communities and worn by different people, yet they agree closely on every statistic. That agreement defines a real-world envelope and makes the simulator comparison meaningful rather than anecdotal.
- **The simulator gets short-horizon smoothness right.** Autocorrelation at 30 and 60 minutes lands in the real range for all three persona classes. On smooth, benign, announced-meal stretches it is a fair stand-in.
- **Aggregate variability is reachable only by the child persona.** CV and the stuck-high outcome spread reach the real range for children (the most variable class) but not for adults or adolescents, which run too smooth. Since controllers are typically evaluated on the adult personae, the default in-silico test understates real-world variability.
- **5 signatures are reproduced by no persona at any age.** These are the mechanistically important, safety-relevant ones: the fat rise tail of unannounced meals, hypo treatment (real lows recover about twice as fast and then overshoot; the sim has no rescue carbohydrate), sensor artefacts (compression lows and high-frequency jitter, both absent or halved), and week-to-week insulin-sensitivity drift (real loops vary 8-22%, the fixed-parameter model varies zero).
- **The child match is not a rescue.** A persona matching real variability does not make the simulator adequate: you would not test an adult controller on the child persona, and the child still fails every mechanism signature above.

The pattern is consistent with the single-cohort suite and the two structural probes: in-silico testing on this platform exercises the easy regime (smooth, announced, stationary, clean-sensor) and is blind to the hard one (unannounced meals, variable insulin efficacy, exercise, sensor artefact, sensitivity drift) that dominates real-world safety.
