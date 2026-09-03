#!/usr/bin/env python3
"""Measure the S2013 refinement directly, as far as it can be measured openly.

The licensed UVA/Padova S2013 model is not freely available (simglucose implements the
2008 model). S2013's central refinement over 2008 is **time-varying insulin sensitivity**
(intraday and interday variability, plus a dawn-phenomenon component), calibrated by
Dalla Man et al. (2014) from clinical data. We implement exactly that mechanism on the
2008 personae and re-measure, to isolate whether the headline refinement closes the
fidelity gaps.

Implementation: in the UVA/Padova equations, insulin sensitivity enters through the
insulin-dependent glucose utilisation (Vmx) and hepatic insulin action on EGP (kp3). We
scale BOTH by a common time-varying factor SI(t) = diurnal(t) x interday(day):
  - diurnal: a dawn dip in sensitivity (lowest ~06:00, highest ~18:00), amplitude 20%.
  - interday: a per-day multiplicative factor, lognormal, day-to-day CV 22%, consistent
    with clinically observed within-subject insulin-sensitivity variability.
This is an S2013-STYLE augmentation of the 2008 personae, not the licensed S2013 model;
it isolates the effect of the refinement rather than reproducing the whole version.

Everything else (meals, announcement, body-weight scaling, sensor, controller, seeds) is
identical to gen_sim_all_personae.py, so the ONLY change from the 2008 baseline is the
time-varying sensitivity. Output: sim_cohort_s2013.npz.
Run: ~/.venvs/boost-insilico/bin/python gen_sim_s2013.py [--days 21]
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

CLASSES = ["adult", "adolescent", "child"]
PATIENTS = [f"{c}#{i:03d}" for c in CLASSES for i in range(1, 11)]
MEALS = [(7.0, 1.0, 45, 15, 0.92), (12.5, 1.2, 65, 20, 0.95), (18.5, 1.3, 80, 25, 0.97),
         (15.5, 1.5, 20, 10, 0.45), (22.0, 1.0, 18, 10, 0.35)]

DIURNAL_AMP = 0.20   # dawn dip amplitude in insulin sensitivity
INTERDAY_CV = 0.22   # day-to-day insulin-sensitivity CV (central clinical value)


class S2013Patient(T1DPatient):
    """2008 patient with an S2013-style time-varying insulin sensitivity."""
    def set_si_process(self, rng, n_days):
        self._Vmx0 = float(self._params["Vmx"])
        self._kp30 = float(self._params["kp3"])
        sigma = np.sqrt(np.log(1 + INTERDAY_CV ** 2))
        self._interday = rng.lognormal(-0.5 * sigma ** 2, sigma, size=n_days + 2)

    def _si(self):
        hour = (self.t / 60.0) % 24
        day = int(self.t // 1440)
        diurnal = 1.0 - DIURNAL_AMP * np.cos(2 * np.pi * (hour - 6) / 24)  # low at dawn
        return float(np.clip(diurnal * self._interday[day], 0.3, 2.5))

    def step(self, action):
        si = self._si()
        self._params["Vmx"] = self._Vmx0 * si   # insulin-dependent glucose uptake
        self._params["kp3"] = self._kp30 * si   # hepatic insulin action on EGP
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
    patient = S2013Patient.withName(name)
    bw = float(patient._params["BW"])
    patient.set_si_process(rng, days)
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
    ap.add_argument("--seed", type=int, default=20260729)   # SAME seeds as the 2008 baseline
    ap.add_argument("--out", default="sim_cohort_s2013.npz")
    a = ap.parse_args()
    store = {}
    for i, name in enumerate(PATIENTS):
        rng = np.random.default_rng(a.seed + i)
        cgm, bw = run_patient(name, a.days, rng)
        store[f"cgm_{name}"] = cgm
        store[f"class_{name}"] = name.split("#")[0]
        tir = 100 * np.mean((cgm >= 70) & (cgm < 180)) if len(cgm) else 0
        print(f"{name:>16} bw{bw:5.0f}  {len(cgm):6d}pts  mean {cgm.mean():3.0f}  "
              f"cv {100*cgm.std()/cgm.mean():2.0f}%  TIR {tir:2.0f}%", flush=True)
    store["patients"] = np.array(PATIENTS)
    store["days"] = a.days
    np.savez_compressed(a.out, **store)
    print(f"saved {a.out}")


if __name__ == "__main__":
    main()
