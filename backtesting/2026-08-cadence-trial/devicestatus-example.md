# One Nightscout devicestatus record, annotated


Captured 2026-08-12 10:05:41Z from a Boost instance running the V5/V6 engine. This is a single record as the loop publishes it, with the document identifiers, the Nightscout subject, the handset model and any pump serial removed. Everything else is verbatim.


The record is about 22 kB, of which the two console blocks are roughly 20. Those blocks are the human-readable trace the engine writes for itself, and they are the reason a record is large rather than a sign that much is being uploaded.


## The envelope


What Nightscout stores about the upload rather than about the decision. `date` is when the loop published; `srvCreated` in the raw record is when the server received it, and the difference between the two is how a late arrival is detected.


| field | value |
|---|---|
| `date` | 2026-08-12 10:05:41Z |
| `isCharging` | False |
| `uploaderBattery` | 83 |
| `utcOffset` | 0 |
| `created_at` | 2026-08-12T10:05:41.024Z |

## Pump


Reported by the driver, not by the algorithm.


| field | value |
|---|---|
| `battery.percent` | 100 |
| `clock` | 2026-08-12T10:05:41.027Z |
| `extended.Version` | 3.4.2.2-3fa3eef279-2026.08.04 |
| `extended.LastBolus` | 8/12/26 11:05 |
| `extended.LastBolusAmount` | 0.55 |
| `extended.TempBasalAbsoluteRate` | 3.2 |
| `extended.TempBasalStart` | 8/12/26 11:05 |
| `extended.TempBasalRemaining` | 14 |
| `extended.BaseBasalRate` | 0.64 |
| `extended.ActiveProfile` | u200 dilute basal - 16-2u per day FLAT ISF |
| `reservoir` | 37 |
| `status.status` | Closed Loop |
| `status.timestamp` | 2026-08-12T10:05:38.746Z |

## Insulin on board


Published separately from the suggestion so that consumers wanting only IOB do not have to parse the whole determination.


| field | value |
|---|---|
| `iob` | 1.075 |
| `basaliob` | -0.098 |
| `activity` | 0.0182 |
| `time` | 2026-08-12T10:05:13.928Z |

## openaps.suggested


What the algorithm decided this cycle. Present on every cycle.


| field | value |
|---|---|
| `algorithm` | SMB |
| `runningDynamicIsf` | True |
| `timestamp` | 2026-08-12T10:05:13.928Z |
| `bg` | 172.7 |
| `tick` | +5 |
| `eventualBG` | 165 |
| `minGuardBG` | 165 |
| `targetBG` | 80 |
| `insulinReq` | 1.24 |
| `units` | 0.55 |
| `deliverAt` | 2026-08-12T10:05:12.912Z |
| `sensitivityRatio` | 1 |
| `reason` | COB: 0, Dev: 3.3, BGI: -0.3, ISF: 3.6, CR: 13.2, Target: 4.4, minPredBG 8.9, minGuardBG 9.2 (UAM), IOBpredBG 8.7, UAMpredBG 9.2; Eventual BG 9.2 >= 4.4, UAM Boost 1: 1.15; UAM Boost 2: 19.78; Delta: 4.55; ShortAvg: 3.95; Increased SMB as percentage of insulin required to 95.3151430462602%. SMB is 1.15;  insulinReq 1.24; standardMaxBolus 0.2. Microbolusing 1.15U. Additional basal trigger currently set to true; Add high basal with Boost: 1.6U; temp 1.79 < 3.2U/hr. prTrial=1,tight,0.6; twin=164.0,162.7,84.8,250.6,1.091,164.8,0.199,112.8,0; antBackout=ARMED,1.091,1.091,172,172,0,0,0,0.34,accel; accelMeal=1,4.2,4.0,-0.2,172,OBSERVING; V6-ACTIVE drove SMB 0.55U (base would=1.15U, state=OBSERVING); primer=bolus,0.45U;primerRoute=bolus-USER-OVERRIDE(recommended=tbr); primerScale=d=4.6,fR=0.47,fB=1.0,fI=0.87,tgt=0.487; plateau=0,0.0,172,4.0,1.08,OBSERVING,n/a; anticip=0.017,0.038,peruser,blend,0,0,0,0,0,0,34,44,148,40; V6 pre-meal WOULD apply 72 (learned ~11:51, 46min before, 25d); autordv=@2026-08-11T12:06:17.947Z,win=28d,ev=5,ch=0,held-CommittedCapU:2.19,held-CumulativeSmbCap60Min:8.5; sleep=AWAKE learned=01:18→07:05/58d; stepHistory: held phone 6228 over wear 5592; activityLoad: wear base 8427 last 6228 (0.77x) wouldΔISF -3.1% [inactivity] bridge[phone<-wear]; activityIntraday: today 2316 vs exp 3033 (0.44x) wouldΔISF +0.0%;  |
| `duration` | 30 |
| `rate` | 3.2 |
| `COB` | 0 |
| `IOB` | 1.075 |
| `variable_sens` | 64.4 |
| `boostTier` | PERCENT_SCALE |
| `boostActive` | True |
| `fastCarbProtection` | False |
| `dynamicISF` | 64.4 |
| `predictionISF` | 64.4 |
| `sensNormalTarget` | 92.2 |
| `tdd` | 24.7 |
| `tddRatio` | 1 |
| `insulinReqPctEffective` | 0 |
| `deltaAcceleration` | 15.19 |
| `boostProfileSwitch` | 100 |
| `mlHypoRisk` | 0.018 |
| `mlRiskScale` | 1 |
| `mlMealLikely` | 0.341 |
| `boostV5_score` | 0.4519907241991114 |
| `boostV5_state` | OBSERVING |
| `boostV5_age` | 1 |
| `boostV5_budget` | 1.24 |
| `boostV5_actionMult` | 0.3 |
| `boostV5_finalDose` | 0.55 |
| `boostV5_velocityFactor` | 0.4 |
| `boostV5_doseAfterCaps` | 0.14880000000000002 |
| `boostV5_doseAfterBrakes` | 0.1 |
| `boostV5_gateReduction` | none |
| `boostV5_active` | True |
| `boostV5_committedCap` | 2.190000057220459 |
| `boostV5_confirmedCap` | 4.5 |
| `boostV5_confirmGate` | n/a |
| `boostV5_prospectiveShot` | 1.1606399574279784 |
| `boostV5_aggressionKnob` | 1.2999999523162842 |
| `boostV5_postRescueWindow` | False |
| `boostV5_cumulativeCapU` | 4.300000190734863 |
| `boostV5_smbVol60Min` | 1.6500000000000001 |
| `boostV7_pool` | excluded |
| `boostV7_innovSensFrozen` | 36.6 |
| `hrBpmLatest` | 80 |
| `hrBpmAvg5m` | 80 |
| `hrBpmAvg15m` | 79.1 |
| `hrBpmMax5m` | 80 |
| `hrBpmMin5m` | 80 |
| `hrReadingsCount15m` | 11 |
| `hrSource_resolved` | worn:venu3 |
| `hrSource_states` | worn:venu3(f,26,4m) |
| `sleepState` | AWAKE |
| `sleepStateEnteredAtMs` | 1786514715845 |
| `sleepLearnedStartMin` | 78 |
| `sleepLearnedWakeMin` | 425 |
| `sleepLearnedDurationMin` | 146 |
| `sleepLearnedSessionCount` | 58 |
| `hrLearnedRestingBpm` | 63 |
| `hrLearnedDaytimeBpm` | 69 |
| `isfShadow_ratioRaw` | 0.872 |
| `isfShadow_ratioEma` | 0.868 |
| `isfShadow_warmup` | 1 |
| `isfShadow_variableSens` | 74.2 |
| `isfShadow_insulinReq` | 1.077 |
| `isfShadow_microBolus` | 0.999 |
| `isfShadow_deltaPct` | 15.15 |
| `boostActivityLoad_baselineSteps` | 8427 |
| `boostActivityLoad_lastDaySteps` | 6228 |
| `boostActivityLoad_ratio` | 0.77 |
| `boostActivityLoad_wouldDeltaIsfPct` | -3.1 |
| `boostActivityLoad_source` | wear |
| `boostActivityLoad_stepsToday` | 2316 |
| `boostActivityLoad_intradayRatio` | 0.44 |
| `boostActivityLoad_intradayDeltaIsfPct` | 0 |
| `boostActivityLoad_stepsSource` | wear |
| `boostActivitySource_resolved` | wear |
| `boostActivitySource_states` | wear(f,28d) phone(f,28d) |
| `boostActivitySource_bridge` | phone<-wear |
| `boostSteps_feed` | phone+wear |
| `boostAutosens_mode` | tdd |
| `boostAutosens_orefRatio` | 1 |
| `boostAutosens_curveRatio` | 1 |
| `boostAutosens_appliedRatio` | 1 |
| `isfMgdlForCarbs` | 61.199999999999996 |

### `suggested.predBGs`


Forward projections, one series per prediction type, five minutes apart. Length varies with how far the projection runs.


| series | points | first six values |
|---|---|---|
| `IOB` | 41 | 173, 176, 179, 181, 183, 184 … |
| `ZT` | 18 | 173, 167, 161, 156, 151, 146 … |
| `UAM` | 40 | 173, 176, 179, 181, 184, 185 … |

### `suggested.consoleLog`


548 characters. Reproduced whole, since its structure is the clearest statement of what the engine considered.


```
Insulin peak: 38, divisor: 82
Current sensitivity for predictions is 64.4 based on current bg
Circadian_sensitivity factor: 0.67097
Circadian ISF disabled
Autosens ratio: 1.0
Basal unchanged: 0.64
EBG: 172.98065 REBG: 1.9220072222222222
HypoPredBG = 221
Adjusting targets for high BG: min_bg from 90.0 to 80.0
target_bg from 90.0 to 80.0
EventualBG is 165.0
Future state sensitivity is 64.41153042866874 based on current bg due to +ve delta
Future sens adjusted to: 64.4
minPredBG: 160.0 minIOBPredBG: 156.0 minZTGuardBG: 126.0
 minUAMPredBG: 165.0
```


### `suggested.consoleError`


3,066 characters. Reproduced whole, since its structure is the clearest statement of what the engine considered.


```
═════════════════════════════════════════════════════════
  Boost V6 (Kotlin) | Profile: 100%
═════════════════════════════════════════════════════════
Steps: 5m=0 15m=0 30m=0 60m=286
Boost gate: night/sleep=false | Now: 11:05
Steps: 5m=11 15m=11 30m=18 60m=286
HR: HR: avg=79.1 bpm | HRR=9.1% | zone=zone1 | steps15m=11 => INACTIVE (HIGH)
Activity: normal (no adjustment)
✓ BOOST ACTIVE (normal)
── Glucose ─────────────────────────────────
BG: 172.7 mg/dl | Delta: 4.6 | Short avg: 4.0 | Long avg: -0.2
Delta acceleration: 15.19%
── Targets ─────────────────────────────────
min=90.0 max=90.0 target=90.0 (TT: false)
── ISF ─────────────────────────────────────
Profile sens: 61.2 | Variable sens: 64.4 | sensNormalTarget: 92.2
DynISF: normalTarget=5.5 | velocity=1.0 | bgCap=11.0 | bgCapped=172.7
TDD: 24.7 | ISF from TDD formula
  TDD data: 7D=29.2 | 1D=26.0 | 24H=25.4 | 4H=6.6 | 8-4H=2.3
  Weighted8H=31.9 (4H×1.4 + 8-4H×0.6)×3
  Standard blend (W8H×.33 + 7D×.34 + 1D×.33)
  Blended TDD=29.0
  Final TDD=24.7 (adj factor 85%)
  TDD ISF at target: 92.2 mg/dl/U (profile was 61.2)
  IsfShadow: tdd24=25.4 tdd7=29.2 | raw=0.872 | warmup=1.00 (days=69.9/5.0) | warmed=0.872 | ema(τ=3h)=0.868 | bounded=0.868
  Variable ISF at BG 173.0: 64.4 (velocity=100.0%)
── Boost Config ────────────────────────────
Bolus cap: 2.0 | maxIOB: 8.0 | scale: 2.2 | insulinReq%: 90.0
Percent scale: true (200.0) | Circadian ISF: false
── State ───────────────────────────────────
IOB: 1.08 | Activity: 0.0182 | COB: 0.0
SMB allowed: true | Flat BGs: false
═════════════════════════════════════════════════════════
Effective CR: 5
max_bg from 90.0 to 80.0
── Threshold ───────────────────────────────
Threshold raised from 3.3 to 4.7
Threshold lowered to 3.6 (delta accelerating)
LGS threshold: 3.6
SMB enabled due to enableSMB_always
profile.sens: 61.2, sens: 64.4, CSF: 4.64
Carb Impact: 9.8 mg/dL per 5m; CI Duration: 0.0 hours; remaining CI (~2h peak): 0.0 mg/dL per 5m
UAM Impact: 9.8 mg/dL per 5m; UAM Duration: 1.2 hours
avgPredBG: 160.0 | COB: 0.0 / 0.0
── Predictions ─────────────────────────────
Above min_bg (4.4): 240m
naive_eventualBG: 103.0 | bgUndershoot: -38.0 | zeroTempDuration: 240m | zeroTempEffect: 165 | carbsReq: -44
── ML Risk Model (observability only) ──────
ML hypo risk: 1.8%
ML meal likelihood: 34.1%
IOB 1.08 > -0.2; maxUAMSMBBasalMinutes: 20 × basal 0.64
── SMB Dosing ──────────────────────────────
InsulinReq%: 90.0% | Divisor: 1.05 (95.3%)
Percent scale: 200.0% from 200.0
insulinReq: 1.24 | UAM Boost1: 1.15 | UAM Boost2: 19.78
Boost scale: 2.2 (from 2.2) | Max bolus: 2.0 | MaxIOB: 8.0
Boost ACTIVE | Base insulin: 0.64U | delta_accl: 15.19
── Tier Decision ───────────────────────────
bg=172.7 | delta=4.6 | shortAvg=4.0 | delta_accl=15.19
eventualBG=165.0 | target=80.0 | IOB=1.08/8.0 | COB=0.0 | lastCarbAge=0
>>> TIER 5: Percent Scale <<<
Post percent scale trigger state: true
naive_eventualBG 103.0,0m 0.0U/h temp needed; last bolus 4.8m ago; maxBolus: 0.2
── High Basal ──────────────────────────────
iTimeActive: true | maxSafeBasal: 3.84
```


## openaps.enacted


What was actually sent to the pump. Absent when the decision changed nothing, which is why an enacted series is sparser than a suggested one.


| field | value |
|---|---|
| `algorithm` | SMB |
| `runningDynamicIsf` | True |
| `timestamp` | 2026-08-12T10:05:12.912Z |
| `bg` | 172.7 |
| `tick` | +5 |
| `eventualBG` | 165 |
| `minGuardBG` | 165 |
| `targetBG` | 80 |
| `insulinReq` | 1.24 |
| `units` | 0.55 |
| `deliverAt` | 2026-08-12T10:05:12.912Z |
| `sensitivityRatio` | 1 |
| `reason` | COB: 0, Dev: 3.3, BGI: -0.3, ISF: 3.6, CR: 13.2, Target: 4.4, minPredBG 8.9, minGuardBG 9.2 (UAM), IOBpredBG 8.7, UAMpredBG 9.2; Eventual BG 9.2 >= 4.4, UAM Boost 1: 1.15; UAM Boost 2: 19.78; Delta: 4.55; ShortAvg: 3.95; Increased SMB as percentage of insulin required to 95.3151430462602%. SMB is 1.15;  insulinReq 1.24; standardMaxBolus 0.2. Microbolusing 1.15U. Additional basal trigger currently set to true; Add high basal with Boost: 1.6U; temp 1.79 < 3.2U/hr. prTrial=1,tight,0.6; twin=164.0,162.7,84.8,250.6,1.091,164.8,0.199,112.8,0; antBackout=ARMED,1.091,1.091,172,172,0,0,0,0.34,accel; accelMeal=1,4.2,4.0,-0.2,172,OBSERVING; V6-ACTIVE drove SMB 0.55U (base would=1.15U, state=OBSERVING); primer=bolus,0.45U;primerRoute=bolus-USER-OVERRIDE(recommended=tbr); primerScale=d=4.6,fR=0.47,fB=1.0,fI=0.87,tgt=0.487; plateau=0,0.0,172,4.0,1.08,OBSERVING,n/a; anticip=0.017,0.038,peruser,blend,0,0,0,0,0,0,34,44,148,40; V6 pre-meal WOULD apply 72 (learned ~11:51, 46min before, 25d); autordv=@2026-08-11T12:06:17.947Z,win=28d,ev=5,ch=0,held-CommittedCapU:2.19,held-CumulativeSmbCap60Min:8.5; sleep=AWAKE learned=01:18→07:05/58d; stepHistory: held phone 6228 over wear 5592; activityLoad: wear base 8427 last 6228 (0.77x) wouldΔISF -3.1% [inactivity] bridge[phone<-wear]; activityIntraday: today 2316 vs exp 3033 (0.44x) wouldΔISF +0.0%;  |
| `duration` | 14 |
| `rate` | 3.2 |
| `COB` | 0 |
| `IOB` | 1.075 |
| `variable_sens` | 64.4 |
| `boostTier` | PERCENT_SCALE |
| `boostActive` | True |
| `fastCarbProtection` | False |
| `dynamicISF` | 64.4 |
| `predictionISF` | 64.4 |
| `sensNormalTarget` | 92.2 |
| `tdd` | 24.7 |
| `tddRatio` | 1 |
| `insulinReqPctEffective` | 0 |
| `deltaAcceleration` | 15.19 |
| `boostProfileSwitch` | 100 |
| `mlHypoRisk` | 0.018 |
| `mlRiskScale` | 1 |
| `mlMealLikely` | 0.341 |
| `boostV5_score` | 0.4519907241991114 |
| `boostV5_state` | OBSERVING |
| `boostV5_age` | 1 |
| `boostV5_budget` | 1.24 |
| `boostV5_actionMult` | 0.3 |
| `boostV5_finalDose` | 0.55 |
| `boostV5_velocityFactor` | 0.4 |
| `boostV5_doseAfterCaps` | 0.14880000000000002 |
| `boostV5_doseAfterBrakes` | 0.1 |
| `boostV5_gateReduction` | none |
| `boostV5_active` | True |
| `boostV5_committedCap` | 2.190000057220459 |
| `boostV5_confirmedCap` | 4.5 |
| `boostV5_confirmGate` | n/a |
| `boostV5_prospectiveShot` | 1.1606399574279784 |
| `boostV5_aggressionKnob` | 1.2999999523162842 |
| `boostV5_postRescueWindow` | False |
| `boostV5_cumulativeCapU` | 4.300000190734863 |
| `boostV5_smbVol60Min` | 1.6500000000000001 |
| `boostV7_pool` | excluded |
| `boostV7_innovSensFrozen` | 36.6 |
| `hrBpmLatest` | 80 |
| `hrBpmAvg5m` | 80 |
| `hrBpmAvg15m` | 79.1 |
| `hrBpmMax5m` | 80 |
| `hrBpmMin5m` | 80 |
| `hrReadingsCount15m` | 11 |
| `hrSource_resolved` | worn:venu3 |
| `hrSource_states` | worn:venu3(f,26,4m) |
| `sleepState` | AWAKE |
| `sleepStateEnteredAtMs` | 1786514715845 |
| `sleepLearnedStartMin` | 78 |
| `sleepLearnedWakeMin` | 425 |
| `sleepLearnedDurationMin` | 146 |
| `sleepLearnedSessionCount` | 58 |
| `hrLearnedRestingBpm` | 63 |
| `hrLearnedDaytimeBpm` | 69 |
| `isfShadow_ratioRaw` | 0.872 |
| `isfShadow_ratioEma` | 0.868 |
| `isfShadow_warmup` | 1 |
| `isfShadow_variableSens` | 74.2 |
| `isfShadow_insulinReq` | 1.077 |
| `isfShadow_microBolus` | 0.999 |
| `isfShadow_deltaPct` | 15.15 |
| `boostActivityLoad_baselineSteps` | 8427 |
| `boostActivityLoad_lastDaySteps` | 6228 |
| `boostActivityLoad_ratio` | 0.77 |
| `boostActivityLoad_wouldDeltaIsfPct` | -3.1 |
| `boostActivityLoad_source` | wear |
| `boostActivityLoad_stepsToday` | 2316 |
| `boostActivityLoad_intradayRatio` | 0.44 |
| `boostActivityLoad_intradayDeltaIsfPct` | 0 |
| `boostActivityLoad_stepsSource` | wear |
| `boostActivitySource_resolved` | wear |
| `boostActivitySource_states` | wear(f,28d) phone(f,28d) |
| `boostActivitySource_bridge` | phone<-wear |
| `boostSteps_feed` | phone+wear |
| `boostAutosens_mode` | tdd |
| `boostAutosens_orefRatio` | 1 |
| `boostAutosens_curveRatio` | 1 |
| `boostAutosens_appliedRatio` | 1 |
| `received` | True |
| `smb` | 0 |

### `enacted.predBGs`


Forward projections, one series per prediction type, five minutes apart. Length varies with how far the projection runs.


| series | points | first six values |
|---|---|---|
| `IOB` | 41 | 173, 176, 179, 181, 183, 184 … |
| `ZT` | 18 | 173, 167, 161, 156, 151, 146 … |
| `UAM` | 40 | 173, 176, 179, 181, 184, 185 … |

### `enacted.consoleLog`


548 characters. Reproduced whole, since its structure is the clearest statement of what the engine considered.


```
Insulin peak: 38, divisor: 82
Current sensitivity for predictions is 64.4 based on current bg
Circadian_sensitivity factor: 0.67097
Circadian ISF disabled
Autosens ratio: 1.0
Basal unchanged: 0.64
EBG: 172.98065 REBG: 1.9220072222222222
HypoPredBG = 221
Adjusting targets for high BG: min_bg from 90.0 to 80.0
target_bg from 90.0 to 80.0
EventualBG is 165.0
Future state sensitivity is 64.41153042866874 based on current bg due to +ve delta
Future sens adjusted to: 64.4
minPredBG: 160.0 minIOBPredBG: 156.0 minZTGuardBG: 126.0
 minUAMPredBG: 165.0
```


### `enacted.consoleError`


3,066 characters. Reproduced whole, since its structure is the clearest statement of what the engine considered.


```
═════════════════════════════════════════════════════════
  Boost V6 (Kotlin) | Profile: 100%
═════════════════════════════════════════════════════════
Steps: 5m=0 15m=0 30m=0 60m=286
Boost gate: night/sleep=false | Now: 11:05
Steps: 5m=11 15m=11 30m=18 60m=286
HR: HR: avg=79.1 bpm | HRR=9.1% | zone=zone1 | steps15m=11 => INACTIVE (HIGH)
Activity: normal (no adjustment)
✓ BOOST ACTIVE (normal)
── Glucose ─────────────────────────────────
BG: 172.7 mg/dl | Delta: 4.6 | Short avg: 4.0 | Long avg: -0.2
Delta acceleration: 15.19%
── Targets ─────────────────────────────────
min=90.0 max=90.0 target=90.0 (TT: false)
── ISF ─────────────────────────────────────
Profile sens: 61.2 | Variable sens: 64.4 | sensNormalTarget: 92.2
DynISF: normalTarget=5.5 | velocity=1.0 | bgCap=11.0 | bgCapped=172.7
TDD: 24.7 | ISF from TDD formula
  TDD data: 7D=29.2 | 1D=26.0 | 24H=25.4 | 4H=6.6 | 8-4H=2.3
  Weighted8H=31.9 (4H×1.4 + 8-4H×0.6)×3
  Standard blend (W8H×.33 + 7D×.34 + 1D×.33)
  Blended TDD=29.0
  Final TDD=24.7 (adj factor 85%)
  TDD ISF at target: 92.2 mg/dl/U (profile was 61.2)
  IsfShadow: tdd24=25.4 tdd7=29.2 | raw=0.872 | warmup=1.00 (days=69.9/5.0) | warmed=0.872 | ema(τ=3h)=0.868 | bounded=0.868
  Variable ISF at BG 173.0: 64.4 (velocity=100.0%)
── Boost Config ────────────────────────────
Bolus cap: 2.0 | maxIOB: 8.0 | scale: 2.2 | insulinReq%: 90.0
Percent scale: true (200.0) | Circadian ISF: false
── State ───────────────────────────────────
IOB: 1.08 | Activity: 0.0182 | COB: 0.0
SMB allowed: true | Flat BGs: false
═════════════════════════════════════════════════════════
Effective CR: 5
max_bg from 90.0 to 80.0
── Threshold ───────────────────────────────
Threshold raised from 3.3 to 4.7
Threshold lowered to 3.6 (delta accelerating)
LGS threshold: 3.6
SMB enabled due to enableSMB_always
profile.sens: 61.2, sens: 64.4, CSF: 4.64
Carb Impact: 9.8 mg/dL per 5m; CI Duration: 0.0 hours; remaining CI (~2h peak): 0.0 mg/dL per 5m
UAM Impact: 9.8 mg/dL per 5m; UAM Duration: 1.2 hours
avgPredBG: 160.0 | COB: 0.0 / 0.0
── Predictions ─────────────────────────────
Above min_bg (4.4): 240m
naive_eventualBG: 103.0 | bgUndershoot: -38.0 | zeroTempDuration: 240m | zeroTempEffect: 165 | carbsReq: -44
── ML Risk Model (observability only) ──────
ML hypo risk: 1.8%
ML meal likelihood: 34.1%
IOB 1.08 > -0.2; maxUAMSMBBasalMinutes: 20 × basal 0.64
── SMB Dosing ──────────────────────────────
InsulinReq%: 90.0% | Divisor: 1.05 (95.3%)
Percent scale: 200.0% from 200.0
insulinReq: 1.24 | UAM Boost1: 1.15 | UAM Boost2: 19.78
Boost scale: 2.2 (from 2.2) | Max bolus: 2.0 | MaxIOB: 8.0
Boost ACTIVE | Base insulin: 0.64U | delta_accl: 15.19
── Tier Decision ───────────────────────────
bg=172.7 | delta=4.6 | shortAvg=4.0 | delta_accl=15.19
eventualBG=165.0 | target=80.0 | IOB=1.08/8.0 | COB=0.0 | lastCarbAge=0
>>> TIER 5: Percent Scale <<<
Post percent scale trigger state: true
naive_eventualBG 103.0,0m 0.0U/h temp needed; last bolus 4.8m ago; maxBolus: 0.2
── High Basal ──────────────────────────────
iTimeActive: true | maxSafeBasal: 3.84
```

