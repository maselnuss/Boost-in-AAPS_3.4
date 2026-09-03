#!/usr/bin/env python3
"""Behaviour layer: the person in the loop.

The physiology layers (gen_sim_s2013.py, gen_sim_realistic.py) and the sensor layer
(sensor_layer.py) between them left four signatures untouched, and REPORT_POC.md named
why: they are not properties of the model or of the sensor. They are things a person
does.

  rise tail P(dBG>10 / 5 min)   real 3.7-6.6%   sim 1.0-1.3%   people eat without telling
                                                               the algorithm
  hypo recovery to 100          real 50-59 min  sim 106-116    people eat carbohydrate
                                                               when they go low
  hypo rebound >180             real 23-28%     sim 0%         and they over-treat
  ISF drift (weekly CV)         real 8-22%      sim 0%         and the algorithm adapts

None of these yields to an ODE refinement, which is what the S2013 reconstruction
measured and why it closed one signature out of eleven. This module supplies the missing
layer instead: announcement behaviour, rescue-carbohydrate behaviour, and an adapting
insulin-sensitivity setting.

It is deliberately separable from the physiology, in the same way sensor_layer.py is, so
each layer can be switched on and off and attributed independently.

Parameter provenance is in PARAMS below: each value is either a directly measured
quantity from the real cohort or a published clinical figure, with the source named. No
parameter here is fitted to a fidelity signature, so the resulting signature values are a
test rather than a restatement of the inputs.
"""
import numpy as np
from simglucose.simulation.scenario import Action as ScenarioAction

# --------------------------------------------------------------------------- parameters
# Announcement. Carbohydrate counting error is well characterised: Brazeau et al. (2013)
# and Meade & Rushton (2016) both put mean absolute error around 15-25% of the meal, and
# Roversi et al. (2020) reports a similar spread from CGM-based inference. Announcement
# rate for main meals in AID users is high but not total; snacks are routinely missed,
# which is the standard clinical account of the unannounced-carbohydrate problem.
P_ANNOUNCE_MAIN = 0.80
P_ANNOUNCE_SNACK = 0.35
CARB_ESTIMATE_CV = 0.25

# Rescue. The 15-15 rule (15 g, recheck at 15 min) is the standard advice, and the
# observed practice is to exceed it: Savard et al. (2016) and the ADA's own guidance note
# routine over-treatment. Delay from alert to treatment is minutes rather than seconds.
RESCUE_THRESHOLD = 70.0
RESCUE_PREEMPT_BG = 80.0          # treat early when falling fast
RESCUE_PREEMPT_FALL = -2.0        # mg/dL/min
RESCUE_DELAY_MIN = (4.0, 16.0)    # uniform, minutes from decision to eating
RESCUE_GRAMS = (14.0, 24.0)       # uniform, grams of fast carbohydrate
P_OVERTREAT = 0.35                # probability of a second helping
OVERTREAT_EXTRA = (10.0, 25.0)    # grams
RESCUE_REFRACTORY_MIN = 20.0      # do not re-treat inside this window

# Adaptation. Real weekly ISF drift is 8-22% CV, measured on the cohort. A weekly
# adjustment bounded to +/-30% is the shape autosens/autotune already use.
ADAPT_PERIOD_MIN = 7 * 24 * 60
ADAPT_GAIN = 0.5
ADAPT_CLIP = (0.70, 1.30)


class Person:
    """Announcement behaviour, rescue-carbohydrate behaviour, and the record of both.

    Holds no glucose model. It is handed the CGM each cycle and returns what the person
    does: how much carbohydrate reaches the gut, and how much of it the algorithm is
    told about.
    """

    def __init__(self, rng, rescue=True, announce=True):
        self.rng = rng
        self.rescue_on = rescue
        self.announce_on = announce
        self.pending = []          # [(minute_due, grams)]
        self.last_rescue_min = -1e9
        self.rescue_events = 0
        self.rescue_grams = 0.0
        self.unannounced_meals = 0
        self.announced_meals = 0

    # ---------------------------------------------------------------- announcement
    def announce(self, grams, is_snack):
        """What the algorithm is told about a meal of `grams`. Zero means unannounced."""
        if not self.announce_on:
            return grams
        p = P_ANNOUNCE_SNACK if is_snack else P_ANNOUNCE_MAIN
        if self.rng.random() > p:
            self.unannounced_meals += 1
            return 0.0
        self.announced_meals += 1
        sigma = np.sqrt(np.log(1 + CARB_ESTIMATE_CV ** 2))
        return float(grams * self.rng.lognormal(-0.5 * sigma ** 2, sigma))

    # ---------------------------------------------------------------- rescue
    def observe(self, minute, cgm, fall_rate):
        """Called once per CGM sample. May schedule a rescue for a few minutes later."""
        if not self.rescue_on:
            return
        if minute - self.last_rescue_min < RESCUE_REFRACTORY_MIN:
            return
        low = cgm < RESCUE_THRESHOLD
        falling_fast = cgm < RESCUE_PREEMPT_BG and fall_rate < RESCUE_PREEMPT_FALL
        if not (low or falling_fast):
            return
        self.last_rescue_min = minute
        delay = float(self.rng.uniform(*RESCUE_DELAY_MIN))
        grams = float(self.rng.uniform(*RESCUE_GRAMS))
        if self.rng.random() < P_OVERTREAT:
            grams += float(self.rng.uniform(*OVERTREAT_EXTRA))
        self.pending.append((minute + delay, grams))
        self.rescue_events += 1
        self.rescue_grams += grams

    def due(self, minute, window):
        """Rescue carbohydrate reaching the gut in [minute, minute+window). Never
        announced: people treating a low do not enter it as a meal bolus."""
        if not self.pending:
            return 0.0
        take = [g for m, g in self.pending if minute <= m < minute + window]
        self.pending = [(m, g) for m, g in self.pending if not (minute <= m < minute + window)]
        return float(sum(take))


class BehaviourScenario:
    """A mutable scenario: the scheduled meals plus whatever the person eats reactively.

    simglucose calls get_action(t) once per model minute, so rescue carbohydrate can be
    injected between calls. The scheduled meals carry an `is_snack` flag and their
    announced amount is resolved on delivery, so the announcement decision is recorded
    at the moment the meal is eaten rather than at construction.
    """

    def __init__(self, start_time, meals, person):
        self.start_time = start_time
        self.person = person
        # meals: [(hours_from_start, grams, is_snack)]
        self.sched = {int(round(h * 60.0)): (g, snack) for h, g, snack in meals}
        # get_action is called once per model minute; the runner steps the environment
        # three minutes at a time, so announcements accumulate and are drained per step.
        self._announced = 0.0
        self._true = 0.0

    def reset(self):
        self._announced = 0.0
        self._true = 0.0

    def drain(self):
        """(announced grams, true grams) since the last call."""
        a, t = self._announced, self._true
        self._announced, self._true = 0.0, 0.0
        return a, t

    def get_action(self, t):
        minute = int(round((t - self.start_time).total_seconds() / 60.0))
        grams = 0.0
        if minute in self.sched:
            g, snack = self.sched.pop(minute)
            grams += g
            self._announced += self.person.announce(g, snack)
        grams += self.person.due(minute, 1.0)
        self._true += grams
        return ScenarioAction(meal=grams)


class AdaptiveRatios:
    """A weekly-adapting insulin-sensitivity setting, so ISF drift has something to read.

    The physiology layer already varies true sensitivity week to week. Nothing in the
    stock basal-bolus controller responds to it, which is why the drift signature reads a
    structural zero: it reads the algorithm's setting, not the person's physiology. A
    bounded weekly adjustment towards target is the same shape autosens and autotune use.
    """

    def __init__(self, cf0, cr0):
        self.cf0, self.cr0 = cf0, cr0
        self.factor = 1.0
        self.history = []           # [(minute, effective CF)]
        self._acc, self._n, self._last = 0.0, 0, 0.0

    def observe(self, minute, cgm, target=140.0):
        self._acc += cgm - target
        self._n += 1
        if minute - self._last >= ADAPT_PERIOD_MIN and self._n:
            bias = self._acc / self._n
            # running high means more insulin is needed: a SMALLER correction factor
            adj = 1.0 - ADAPT_GAIN * np.clip(bias / 60.0, -1.0, 1.0)
            self.factor = float(np.clip(self.factor * adj, *ADAPT_CLIP))
            self._acc, self._n, self._last = 0.0, 0, minute
        self.history.append((minute, self.cf0 * self.factor))

    @property
    def cf(self):
        return self.cf0 * self.factor

    @property
    def cr(self):
        return self.cr0 * self.factor

    def weekly_cv(self):
        """The signature the suite reads: weekly %CV of the effective sensitivity."""
        if len(self.history) < 2:
            return np.nan
        m = np.array([h[0] for h in self.history], float)
        v = np.array([h[1] for h in self.history], float)
        weeks = (m // ADAPT_PERIOD_MIN).astype(int)
        per = np.array([v[weeks == w].mean() for w in np.unique(weeks)])
        return float(100 * per.std() / per.mean()) if len(per) > 1 and per.mean() else np.nan


# --------------------------------------------------------------------------- loop layer
# The residual after the behaviour layer (rebound and autocorrelation both too high, CV
# too high) all point the same way: the simulated person is treated by a basal-bolus
# controller that does nothing between meals, while every real cohort in the comparison
# is running a closed loop that corrects continuously. An over-treated low rebounds
# freely here and gets mopped up there. This layer tests that reading.
#
# It is a generic correction loop, not Boost and not oref: correction insulin towards
# target subject to an insulin-on-board bound, and basal withdrawal when low or falling.
# The point is to establish whether continuous correction accounts for the residual, not
# to reproduce any particular algorithm.
DIA_MIN = 300.0           # insulin action duration
PEAK_MIN = 75.0           # bilinear activity peak
LOOP_TARGET = 120.0
LOOP_MAX_IOB_U = 6.0
LOOP_MIN_INTERVAL = 5.0   # minutes between corrections
LOOP_LOW_SUSPEND = 80.0   # withdraw basal below this
LOOP_FALL_SUSPEND = -1.5  # mg/dL/min


def _iob_fraction(age_min):
    """Fraction of a bolus still to act, bilinear activity with a peak at PEAK_MIN."""
    if age_min <= 0:
        return 1.0
    if age_min >= DIA_MIN:
        return 0.0
    # area-normalised bilinear activity, integrated from age to DIA
    p, d = PEAK_MIN, DIA_MIN
    if age_min < p:
        return 1.0 - (age_min ** 2) / (p * d)
    return ((d - age_min) ** 2) / (d * (d - p))


class CorrectionLoop:
    """Continuous correction with an insulin-on-board bound and basal withdrawal."""

    def __init__(self):
        self.boluses = []          # [(minute, units)]
        self.last_correction = -1e9

    def add(self, minute, units):
        if units > 0:
            self.boluses.append((minute, units))

    def iob(self, minute):
        return float(sum(u * _iob_fraction(minute - m) for m, u in self.boluses))

    def correction(self, minute, cgm, cf):
        if minute - self.last_correction < LOOP_MIN_INTERVAL:
            return 0.0
        need = (cgm - LOOP_TARGET) / cf - self.iob(minute)
        if need <= 0.05 or cgm < 150 or self.iob(minute) >= LOOP_MAX_IOB_U:
            return 0.0
        self.last_correction = minute
        dose = min(need * 0.5, 2.0)          # partial correction, as loops dose
        self.add(minute, dose)
        return float(dose)

    def basal_scale(self, cgm, fall_rate):
        if cgm < LOOP_LOW_SUSPEND or (cgm < 110 and fall_rate < LOOP_FALL_SUSPEND):
            return 0.0
        if cgm < 110:
            return 0.5
        return 1.0
