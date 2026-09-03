# Sensing cadence and dosing cadence in a closed loop: a pre-registered within-participant study

Registered 2026-08-09. Version 1.0. The hypotheses, arms, outcomes, sample size, stopping rules and
analysis model set out below were fixed before any trial data were collected.

Applies to the Boost fork of AndroidAPS, branch `v7-shadow-1m-test`, build `c0eaae13fe`.

## 1. Background

Continuous glucose monitors that report every minute are now available, and the assumption in
common circulation is that a faster feed should improve automated insulin delivery. Earlier
offline work in this programme gives reason to doubt that, and it is the reason this study is
modest in what it claims to be able to detect.

That work found the gain from a one minute feed to be one of latency rather than of information.
Interstitial lag is approximately 3.8 minutes, which removes most of the content that a faster
sample might otherwise carry. A comparison of real one minute and five minute eras found the two
differed by a single scale factor which was flat across lags from five to 120 minutes, with no
noise floor on either side, and short horizon prediction gained nothing measurable (a lift of 9.14
against 9.18). Rate of change was in fact estimated less accurately at one minute than at five. The
measurable benefit was around two minutes of latency, which is the time spent waiting for the next
sample on a five minute grid.

One observation survived that assessment. A faster feed appeared to help during rapid falls, where
two minutes is a material fraction of the time available to respond. That is a narrow claim, it was
reached observationally, and the identification constraint that governs this programme means no
backtest can produce the counterfactual glucose trajectory needed to settle it. An experiment is
therefore required, and this protocol specifies one.

A second consideration motivates the design. On this platform the rate at which the loop takes
decisions is not a user setting; it follows from the glucose series the loop trigger reads, which
is bucketed to five minutes irrespective of what the sensor reports. A one minute sensor alone
therefore yields one minute data feeding a five minute decision cycle. Faster sensing and more
frequent dosing are consequently separable, and any study that varies only the sensor confounds
them. The design below separates them deliberately.

## 2. Objectives

The primary objective is to determine whether computing dosing decisions from one minute glucose
data, without changing how often those decisions are taken, alters time below range or the depth
and duration of excursions following a rapid fall.

The secondary objective is to determine whether taking decisions every minute rather than every
five minutes, with the sensing cadence held constant, has either effect.

The null hypothesis in both cases is that there is no within-participant difference. Given the
offline findings summarised above, a null result is anticipated on aggregate glycaemia and is
regarded as informative rather than as a failure of the study.

## 3. Design

This is a within-participant study with three arms, run on a single binary in which sensing cadence
and decision cadence are set independently. Because all arms run the same build, no difference
between them can arise from the software under test.

| Arm | Sensor cadence | Decision cadence | Minimum interval between automated boluses |
|---|---|---|---|
| A | 5 min | 5 min | 5 min, set by the decision cycle |
| B | 1 min | 5 min | 5 min, set by the decision cycle |
| C | 1 min | 1 min | 3 min, set by the configured minimum |

The configured minimum interval between automated boluses is three minutes in every arm and is not
altered between them. In arms A and B the decision cycle is the binding constraint, so the
configured value has no effect and the opportunity to dose is identical. In arm C the decision
cycle is shorter than the configured minimum, so the configured value becomes binding.

Arm B is the platform's behaviour with a one minute sensor and no further configuration. The
glucose series used for the dosing decision is at one minute resolution, as is the series passed to
the smoother, while decisions continue to be taken every five minutes. Arm C differs
only in that the loop trigger is moved onto the native series by a preference, so that a decision
is taken on each reading.

The comparison of A with B varies sensing cadence while holding the opportunity to dose constant,
and is the comparison that addresses the primary objective. The comparison of B with C varies
decision cadence while holding sensing constant, and addresses the secondary objective. The
comparison of A with C varies both together and describes the configuration a user would adopt in
practice; it is reported for completeness and is interpreted only in the light of the other two.

## 4. Participant

A single participant, who is also the developer of the fork under test. The population using this
software is small and self selected, and a within-participant design was chosen in preference to a
between-participant one because it holds constant the basal profile, insulin sensitivity, sensor
site, meal pattern and every other fixed characteristic that would otherwise dominate a comparison
at this scale.

## 5. Randomisation

Arms B and C use the same sensor and differ only by a preference, so they can be alternated within
a single wear session. Assignment is by day, from a seeded pseudorandom generator keyed on the
participant identifier and the date, balanced in blocks of six days so that the arms remain even
within each week. Assignment is not alternated on consecutive days, since that would alias with
day of week.

Arm A requires a different sensor and therefore enters at the level of the wear period rather than
the day. The comparison of A with B is consequently confounded with calendar time, and is
interpreted with that limitation acknowledged rather than treated as equivalent to the randomised
comparison of B with C.

Blinding is not achievable, since the participant configures the arms. This is recorded as a
limitation.

## 6. Outcomes

The primary outcome is the daily proportion of time spent below 70 mg/dL. The second primary
outcome is defined at the level of the event rather than the day: for every descent steeper than
3 mg/dL per five minutes sustained for at least fifteen minutes, the nadir reached and the number
of minutes spent below 70 mg/dL in the following two hours. Defining this outcome over events
rather than days provides considerably more information per unit of observation, which matters
given the sample size considerations in section 7.

Secondary outcomes are the proportion of time below 54 mg/dL, the proportion of time between 70 and
180 mg/dL, the proportion of time between 63 and 140 mg/dL, the coefficient of variation of glucose,
and total daily insulin. The number of automated boluses per day is recorded as a manipulation
check rather than as an outcome, since it is expected to differ between arms by construction.

## 7. Sample size

The precision of a day randomised within-participant comparison is set by the between-day spread of
the outcome, which was measured from 178 complete days of the participant's own record rather than
assumed. A first order autocorrelation inflation was applied, since daily glycaemic outcomes are
not independent. The figures below assume a two sided test at the 5 per cent level with 80 per cent
power and equal numbers of days in each arm.

| Outcome | Baseline | Between-day SD | 14 d/arm | 28 d/arm | 56 d/arm |
|---|---|---|---|---|---|
| Time 70 to 180 mg/dL | 85.2% | 8.7 pp | 10.2 pp | 7.2 pp | 5.1 pp |
| Time 63 to 140 mg/dL | 68.7% | 12.0 pp | 14.6 pp | 10.4 pp | 7.3 pp |
| Time below 70 mg/dL | 4.7% | 3.9 pp | 4.9 pp | 3.5 pp | 2.5 pp |
| Time below 54 mg/dL | 1.0% | 1.4 pp | 1.8 pp | 1.2 pp | 0.9 pp |

These figures determine what the study can and cannot address. At 28 days per arm the smallest
detectable difference in time in range is 7.2 percentage points against a baseline of 85.2 per
cent, an effect which would require time in range to reach 92 per cent. No mechanism proposed for a
two minute latency gain predicts anything of that magnitude. Time in range is therefore not treated
as a primary outcome, and a null result on it will be reported as uninformative rather than as
evidence of no effect.

The planned duration is 56 days per arm, at which point a difference in time below 70 mg/dL of
around half the baseline rate becomes detectable. A shorter period may be adopted, in which case
the minimum detectable difference will be reported alongside every estimate.

## 8. Safety and stopping rules

Two absolute thresholds bind irrespective of any statistical consideration, and may be tightened
but not relaxed. An arm is stopped immediately if the proportion of time below 54 mg/dL exceeds
1 per cent over any rolling fourteen day window, or if the proportion of time below 70 mg/dL exceeds
4 per cent over the same window. Any single excursion below 54 mg/dL lasting more than thirty
minutes and attributable to a dosing decision triggers a pause pending review of the cycle logs.

A relative rule operates in addition to these, never in place of them. An arm is stopped if its
rolling fourteen day proportion of time below 70 mg/dL exceeds that of the concurrently running arm
over the same period by more than 1.5 percentage points. The comparator is the concurrent arm rather
than a historical baseline, because the arms are randomised within the same period and any factor
that varies with time rather than with assignment therefore affects both equally.

For context, the participant changed insulin preparation shortly before registration, moving from
a U200 formulation to the same analogue diluted to U100 strength. Recorded units per day
approximately doubled, as halving the concentration requires, and the total daily dose scaling
re-settled over the following two days. Time below 70 mg/dL was 4.1 per cent over the 89 days
preceding the change and 1.9 per cent over the stabilised days following it, with time below 54
mg/dL falling from 0.8 per cent to zero. No run-in period is required before the study begins,
since the randomised design makes each arm its own control against the other; a scaling that is
still settling affects the arms equally and cancels in the contrast, at some cost to precision but
none to validity.

## 9. Data capture and quality control

Each arm logs every decision cycle to Nightscout, from which the records are extracted into the
analysis database by the standard extractor. The realised sensor cadence is recorded per cycle and
the arm is recovered from it rather than from a manual label, so that a day whose realised cadence
disagrees with its assignment can be identified. Such days are excluded before analysis and their
number is reported.

Days that are incomplete are excluded throughout. A part day of good control is otherwise recorded
as a perfect one, which biases any summary computed while a day is still in progress.

## 10. Statistical analysis

Day level outcomes are compared between arms as a difference of means, with a 95 per cent confidence
interval obtained by bootstrap resampling of whole days rather than of individual readings, since
consecutive readings within a day are strongly dependent. Event level outcomes are compared with
clustering by day and the bootstrap taken over days.

Every estimate is reported with its interval and an explicit statement of whether it is
distinguishable from no effect. Where an interval covers zero the result is reported as unproven,
and the minimum detectable difference from section 7 is quoted alongside it so that a genuine null
can be told apart from an underpowered comparison. No subgroup analysis is specified in advance,
and any conducted subsequently will be identified as exploratory.

No adjustment for multiplicity is made and none would render these comparisons confirmatory. The study is
descriptive rather than confirmatory, and the analysis is framed accordingly.

## 11. Decision rule

The one minute configuration will be adopted only if time below 70 mg/dL is no worse and the rapid
fall outcome improves, with both intervals excluding no effect at the planned duration. It will be
rejected if time below range worsens beyond the threshold in section 8. A null result closes the
question of whether a faster sensor is worth pursuing for automated insulin delivery on this
platform, which is a useful outcome and the one the earlier offline work anticipates.

## 12. Limitations

The study has a single participant and cannot be blinded. Arm A enters at the level of the wear
period rather than the day, so the comparison that addresses the primary objective is confounded
with calendar time in a way that the secondary comparison is not. Time in range is underpowered at
any duration the study is likely to run for, as section 7 sets out. Sensor lag is not corrected
anywhere in the analysis. The findings apply to one implementation on one platform and should not
be read as a general statement about sampling cadence in automated insulin delivery.

## 13. Planned extension

A further arm, in which decisions are taken every minute while the minimum interval between
automated boluses is held at five, would separate the effect of responding sooner from the effect
of dosing more often. It requires no change to the software and will be registered separately.
