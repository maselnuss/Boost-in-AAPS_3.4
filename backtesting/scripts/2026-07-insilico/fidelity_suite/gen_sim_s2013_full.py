#!/usr/bin/env python3
"""S2013-style FULL augmentation: the time-varying insulin sensitivity of gen_sim_s2013.py
PLUS a glucagon counter-regulation term. This adds the one remaining S2013 refinement that
could plausibly affect our hypoglycaemia signatures (recovery speed and rebound), so we can
test directly whether endogenous counter-regulation closes the hypo gap.

The licensed S2013 adds a glucagon secretion/action sub-model: glucagon rises when glucose
is low and falling, and raises endogenous glucose production. Its exact multi-state ODE and
per-subject parameters are not public, so we implement its FUNCTIONAL effect: during
hypoglycaemia we boost basal endogenous glucose production (kp1) with a static component
(how far below threshold) and a dynamic component (how fast glucose is falling), the two
drivers of glucagon secretion in the model. This is an S2013-style counter-regulation
augmentation approximating the glucagon sub-model's effect, not the licensed model.

Everything else (SI process, meals, sensor, controller, seeds) is identical to the SI-only
S2013-style cohort, so the ONLY change is the counter-regulation. Output:
sim_cohort_s2013_full.npz.
Run: ~/.venvs/boost-insilico/bin/python gen_sim_s2013_full.py [--days 21]
"""
import argparse, numpy as np
from datetime import datetime
from simglucose.sensor.cgm import CGMSensor
from simglucose.actuator.pump import InsulinPump
from simglucose.simulation.env import T1DSimEnv
from simglucose.simulation.scenario import CustomScenario
from simglucose.controller.base import Action
from simglucose.controller.basal_bolus_ctrller import BBController
from gen_sim_s2013 import S2013Patient, random_meals, PATIENTS

GTH = 80.0          # mg/dL, glucose threshold below which counter-regulation ramps
A_STATIC = 1.5      # EGP boost per unit (Gth-G)/Gth  (at G=40 -> +75% basal EGP)
A_DYN = 0.5         # EGP boost per (capped) mg/dL/min of fall


class S2013FullPatient(S2013Patient):
    """S2013-style: time-varying insulin sensitivity + glucagon counter-regulation."""
    def set_counterreg(self):
        self._kp10 = float(self._params["kp1"])
        self._Vg = float(self._params["Vg"])
        self._Gprev = None

    def step(self, action):
        G = self.state[3] / self._Vg                 # plasma glucose (mg/dL)
        dG = 0.0 if self._Gprev is None else (G - self._Gprev)
        self._Gprev = G
        static = max(0.0, (GTH - G) / GTH)
        dyn = min(1.0, max(0.0, -dG / 2.0))
        boost = A_STATIC * static + A_DYN * dyn
        self._params["kp1"] = self._kp10 * (1.0 + boost)   # counter-regulatory EGP rise
        return super().step(action)                        # SI patient then sets Vmx/kp3


def run_patient(name, days, rng):
    patient = S2013FullPatient.withName(name)
    bw = float(patient._params["BW"])
    patient.set_si_process(rng, days)
    patient.set_counterreg()
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
    ap.add_argument("--days", type=int, default=21)
    ap.add_argument("--seed", type=int, default=20260729)   # SAME seeds as SI-only + 2008
    ap.add_argument("--out", default="sim_cohort_s2013_full.npz")
    a = ap.parse_args()
    store = {}
    for i, name in enumerate(PATIENTS):
        rng = np.random.default_rng(a.seed + i)
        cgm, bw = run_patient(name, a.days, rng)
        store[f"cgm_{name}"] = cgm
        store[f"class_{name}"] = name.split("#")[0]
        tbr = 100 * np.mean(cgm < 70) if len(cgm) else 0
        print(f"{name:>16} bw{bw:5.0f}  {len(cgm):6d}pts  mean {cgm.mean():3.0f}  "
              f"cv {100*cgm.std()/cgm.mean():2.0f}%  TBR70 {tbr:4.1f}%", flush=True)
    store["patients"] = np.array(PATIENTS)
    store["days"] = a.days
    np.savez_compressed(a.out, **store)
    print(f"saved {a.out}")


if __name__ == "__main__":
    main()
