# Baseline after the U200 to U100 switch


Daily outcomes from 2026-05-07 to 2026-08-08. Switch dated 2026-08-05; the period from 2026-08-07 is treated as stabilised.


## The transition, day by day


| day | readings | units | TDD | ratio | TIR | TBR<70 | TBR<54 | period |
|---|---|---|---|---|---|---|---|---|
| 2026-07-29 | 317 | 6.8 | 11.6 | 1.00 | 87.1% | 12.9% | 0.6% | pre |
| 2026-07-30 | 319 | 4.3 | 10.6 | 1.00 | 91.8% | 4.4% | 0.0% | pre |
| 2026-07-31 | 331 | 9.1 | 12.2 | 1.00 | 87.6% | 0.9% | 0.3% | pre |
| 2026-08-01 | 336 | 6.2 | 12.5 | 1.00 | 89.6% | 0.0% | 0.0% | pre |
| 2026-08-02 | 336 | 9.9 | 13.1 | 1.00 | 75.3% | 3.6% | 1.2% | pre |
| 2026-08-03 | 321 | 8.0 | 13.6 | 1.00 | 92.8% | 4.4% | 1.2% | pre |
| 2026-08-04 | 292 | 11.6 | 27.3 | 1.00 | 92.5% | 5.5% | 0.0% | pre |
| 2026-08-05 | 333 | 14.0 | 26.3 | 1.00 | 80.2% | 7.8% | 4.2% | transition |
| 2026-08-06 | 363 | 16.9 | 26.3 | 1.00 | 90.6% | 1.9% | 0.6% | transition |
| 2026-08-07 | 434 | 17.1 | 24.5 | 1.00 | 88.7% | 1.6% | 0.0% | **stabilised** |
| 2026-08-08 | 429 | 11.9 | 22.6 | 1.00 | 95.6% | 2.1% | 0.0% | **stabilised** |

## Has it settled?


| period | days | TIR | TBR<70 (95% CI) | TBR<54 | mean units/day | TDD ratio |
|---|---|---|---|---|---|---|
| pre-switch | 89 | 84.5% | **4.1%** [3.4, 4.9] | 0.8% | 12.2 | 0.99 |
| transition | 2 | 85.4% | **4.9%** [-0.2, 9.9] | 2.4% | 15.4 | 1.00 |
| stabilised | 2 | 92.1% | **1.9%** [-3.2, 6.9] | 0.0% | 14.5 | 1.00 |

Intervals for periods shorter than ten days use the historical between-day SD (3.7 pp), not a bootstrap of the period itself, which at n=3 would resample only the three observed days and understate the spread.


Change in TBR<70 from pre-switch to stabilised: **-2.3 pp**.


## Can a window this short establish the floor is met?


Between-day SD of TBR<70 over the pre-switch record is **3.7 pp**. The precision of a mean over n days is that divided by the square root of n, so:


| days of baseline | 95% half-width on TBR<70 |
|---|---|
| 3 | +/-4.1 pp |
| 7 | +/-2.7 pp |
| 14 | +/-1.9 pp |
| 21 | +/-1.6 pp |
| 28 | +/-1.4 pp |

The stabilised period currently observes 1.9% against a floor of 4%. Separating those two with 95% confidence needs about **12 days** at this variability
, against the 2 available.


**The interval still spans the floor, so the floor cannot yet be declared met.** A lower point estimate is not the same as a demonstrated one, and the stopping rule is written against a demonstrated rate.
