#!/usr/bin/env python3
"""PoC 'realistic' physiology layer: a FAST stochastic insulin-efficacy process, in the
ODE, on top of the 2008 UVA/Padova personae.

Where S2013-style time-varying sensitivity (gen_sim_s2013.py) moves the DAY-TO-DAY and
DIURNAL statistics towards real data, it leaves the WITHIN-DAY unpredictability gap
untouched: the 2008 (and S2013) personae are too predictable minute-to-minute, so
"stuck-high" outcomes 30 minutes later are far more deterministic in the sim than in
real data (outcome SD ~21 vs a real 27-34 mg/dL). Real insulin action varies on a much
faster timescale than day-to-day drift (absorption-site variability, minute-to-minute
physiological noise), so this layer adds a FAST mean-reverting stochastic factor
(Ornstein-Uhlenbeck, correlation time tau ~45 min) on top of a much smaller, slower
week-scale random-walk component, and scales the same two physiological handles as
S2013 (Vmx, insulin-dependent glucose utilisation; kp3, hepatic insulin action on EGP)
by their product.

This is deliberately a DIFFERENT mechanism from gen_sim_s2013.py's day-level lognormal
drift: this layer targets the short-horizon unpredictability signature, not the
day-to-day/diurnal ones (both remain independently useful; they are not combined here
because the PoC only needs to isolate what closes the target gaps).

sigma_fast was fit (see tune_efficacy.py, scratchpad) so the resulting outcome-SD
signature lands near the Boost+Trio target band, while checking autocorrelation@30/60
stay inside the real range (a too-high sigma collapses the ACF). sigma_slow is small
by construction (week-scale, not a fitted target of this PoC).

Meals, announcement, body-weight scaling, controller and seeding follow the same
pattern as gen_sim_all_personae.py / gen_sim_s2013.py; the ONLY change from the 2008
baseline is this efficacy process. Scope: 10 ADULT personae only, 14 days each (kept
small deliberately -- see CLAUDE.md PoC scope).

Output: sim_cohort_realistic.npz  (cgm_<name>, class_<name>, patients, days)
Run:    ~/.venvs/boost-insilico/bin/python gen_sim_realistic.py [--days 14]
"""
import argparse, numpy as np
from datetime import datetime
from simglucose.patient.t1dpatient import T1DPatient
from simglucose.sensor.cgm import CGMSensor
from simglucose.actuator.pump import InsulinPump
from simglucose.simulation.env import T1DSimEnv
from simglucose.simulation.scenario import CustomScenario
from simglucose.controller.base import Action
from simglucose.controller.basal_bolus_ctrller import BBController

PATIENTS = [f"adult#{i:03d}" for i in range(1, 11)]
MEALS = [(7.0, 1.0, 45, 15, 0.92), (12.5, 1.2, 65, 20, 0.95), (18.5, 1.3, 80, 25, 0.97),
         (15.5, 1.5, 20, 10, 0.45), (22.0, 1.0, 18, 10, 0.35)]

TAU_FAST = 45.0        # min, fast OU correlation time (per spec)
TAU_SLOW = 10080.0     # min, week-scale slow-drift correlation time (1 week)
# sigma_fast fitted by sweep (scratchpad/tune_efficacy.py, tune_efficacy2.py) against the
# Boost+Trio outcome-SD target (27-34 mg/dL band): 0.015-0.05 left outcome SD stuck at
# 23.5-24.3 (barely moved from the 2008 baseline ~21-23); 0.07 was the first value that
# reached the target band (outcome SD ~29). This is a COMPROMISE: at 0.07 the
# autocorrelation@30/60 signatures sit a little outside the tight real envelope
# (0.78-0.87 / 0.50-0.68) on the proxy sweep -- see REPORT_POC.md for the measured
# values on the full cohort, reported honestly rather than re-swept to hide it.
SIGMA_FAST = 0.07
SIGMA_SLOW = 0.006      # small by construction, not a fitted target
SI_LO, SI_HI = 0.3, 2.5  # physiological clip on the combined efficacy factor


class RealisticPatient(T1DPatient):
    """2008 patient with a fast OU + slow random-walk stochastic insulin-efficacy
    factor scaling Vmx and kp3 (same mechanism as S2013Patient in gen_sim_s2013.py,
    but a different, faster process aimed at a different signature)."""

    def set_efficacy_process(self, rng):
        self._Vmx0 = float(self._params["Vmx"])
        self._kp30 = float(self._params["kp3"])
        self._si_fast = 1.0
        self._si_slow = 1.0
        self._rng = rng

    def _step_efficacy(self, dt):
        self._si_fast += ((1 - self._si_fast) * dt / TAU_FAST
                           + SIGMA_FAST * np.sqrt(dt) * self._rng.standard_normal())
        self._si_slow += ((1 - self._si_slow) * dt / TAU_SLOW
                           + SIGMA_SLOW * np.sqrt(dt) * self._rng.standard_normal())
        return float(np.clip(self._si_fast * self._si_slow, SI_LO, SI_HI))

    def step(self, action):
        si = self._step_efficacy(dt=self.sample_time)  # patient internal step = 1 min
        self._params["Vmx"] = self._Vmx0 * si
        self._params["kp3"] = self._kp30 * si
        return super().step(action)


def random_meals(days, rng, bw):
    scale = float(np.clip(bw / 70.0, 0.5, 1.15))
    out = []
    for d in range(days):
        for mh, hsd, mg, gsd, p in MEALS:
            if rng.random() > p:
                continue
            h = mh + rng.normal(0, hsd)
            g = max(3, rng.normal(mg, gsd) * scale)
            out.append((d * 24 + float(np.clip(h, 0.5, 23.5)), round(float(g))))
    return sorted(out)


def run_patient(name, days, rng):
    patient = RealisticPatient.withName(name)
    bw = float(patient._params["BW"])
    patient.set_efficacy_process(rng)
    start = datetime(2026, 1, 1, 0, 0, 0)
    scen = CustomScenario(start_time=start, scenario=random_meals(days, rng, bw))
    env = T1DSimEnv(patient, CGMSensor.withName("Dexcom", seed=int(rng.integers(1, 1_000_000))),
                    InsulinPump.withName("Insulet"), scen)
    env.reset()
    ctrl = BBController()
    obs, reward, done, info = env.step(Action(basal=0, bolus=0))
    cgm = []
    for _ in range(int(days * 24 * 60 / env.sample_time)):
        obs, reward, done, info = env.step(ctrl.policy(obs, reward, done, **info))
        cgm.append(env.CGM_hist[-1] if env.CGM_hist else obs.CGM)
        if done:
            break
    return np.array([c for c in cgm if c is not None and c > 0], dtype=float), bw


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=14)
    ap.add_argument("--seed", type=int, default=20260729)  # same seed convention as siblings
    ap.add_argument("--out", default="sim_cohort_realistic.npz")
    a = ap.parse_args()
    store = {}
    for i, name in enumerate(PATIENTS):
        rng = np.random.default_rng(a.seed + i)
        cgm, bw = run_patient(name, a.days, rng)
        store[f"cgm_{name}"] = cgm
        store[f"class_{name}"] = "adult"
        tir = 100 * np.mean((cgm >= 70) & (cgm < 180)) if len(cgm) else 0
        print(f"{name:>16} bw{bw:5.0f}  {len(cgm):6d}pts  mean {cgm.mean():3.0f}  "
              f"cv {100*cgm.std()/cgm.mean():2.0f}%  TIR {tir:2.0f}%", flush=True)
    store["patients"] = np.array(PATIENTS)
    store["days"] = a.days
    np.savez_compressed(a.out, **store)
    print(f"saved {a.out}")


if __name__ == "__main__":
    main()
