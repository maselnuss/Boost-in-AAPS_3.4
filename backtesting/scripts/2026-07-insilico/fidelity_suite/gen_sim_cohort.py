#!/usr/bin/env python3
"""Generate a simulator cohort under simglucose (UVA/Padova) with REALISTICALLY
randomised announced meals, and cache the per-patient CGM series.

Design choice: the shipped harness announces four identical meals at the same clock
times every single day, so its only day-to-day variation is sensor noise. That would
let the fidelity suite "win" trivially by detecting clockwork regularity that is an
artefact of the scenario, not the model. To give the simulator its best shot, we
jitter meal times and sizes per patient-day (occasionally skipping or adding a snack),
seeded for reproducibility. Any variability gap that survives this is the model's, not
the scenario's.

Meals are ANNOUNCED (BBController boluses on the scenario CHO) because simglucose has
no working unannounced/closed-loop controller. That announced-vs-unannounced regime
gap is itself one of the things the suite measures against our real (unannounced) loop.

Output: sim_cohort.npz  (cgm_<patient> arrays at 1-min cadence, + meta)
Run:    ~/.venvs/boost-insilico/bin/python gen_sim_cohort.py [--days 21]
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

# meal template: (mean hour, hour jitter sd, mean grams, gram sd, prob present)
MEALS = [
    (7.0,  1.0, 45, 15, 0.92),   # breakfast
    (12.5, 1.2, 65, 20, 0.95),   # lunch
    (18.5, 1.3, 80, 25, 0.97),   # dinner
    (15.5, 1.5, 20, 10, 0.45),   # afternoon snack (often skipped)
    (22.0, 1.0, 18, 10, 0.35),   # evening snack (often skipped)
]


def random_meals(days, rng):
    """List of (hour_from_start, grams) with per-day jitter, sizes, and skips."""
    out = []
    for d in range(days):
        for mh, hsd, mg, gsd, p in MEALS:
            if rng.random() > p:
                continue
            h = mh + rng.normal(0, hsd)
            g = max(5, rng.normal(mg, gsd))
            out.append((d * 24 + float(np.clip(h, 0.5, 23.5)), round(float(g))))
    return sorted(out)


def run_patient(name, days, rng):
    start = datetime(2026, 1, 1, 0, 0, 0)
    scen = CustomScenario(start_time=start, scenario=random_meals(days, rng))
    patient = T1DPatient.withName(name)
    sensor = CGMSensor.withName("Dexcom", seed=int(rng.integers(1, 1_000_000)))
    pump = InsulinPump.withName("Insulet")
    env = T1DSimEnv(patient, sensor, pump, scen)
    env.reset()
    ctrl = BBController()
    obs, reward, done, info = env.step(Action(basal=0, bolus=0))
    cgm = []
    for _ in range(int(days * 24 * 60 / env.sample_time)):
        obs, reward, done, info = env.step(ctrl.policy(obs, reward, done, **info))
        cgm.append(env.CGM_hist[-1] if env.CGM_hist else obs.CGM)
        if done:
            break
    return np.array([c for c in cgm if c is not None and c > 0], dtype=float)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=21)
    ap.add_argument("--seed", type=int, default=20260729)
    ap.add_argument("--out", default="sim_cohort.npz")
    a = ap.parse_args()
    store = {}
    for i, name in enumerate(PATIENTS):
        rng = np.random.default_rng(a.seed + i)   # deterministic per patient
        cgm = run_patient(name, a.days, rng)
        store[f"cgm_{name}"] = cgm
        print(f"{name}: {len(cgm)} pts  mean {cgm.mean():.0f}  cv {100*cgm.std()/cgm.mean():.0f}%  "
              f"TIR {100*np.mean((cgm>=70)&(cgm<180)):.0f}%", flush=True)
    store["patients"] = np.array(PATIENTS)
    store["days"] = a.days
    np.savez_compressed(a.out, **store)
    print(f"saved {a.out}")


if __name__ == "__main__":
    main()
