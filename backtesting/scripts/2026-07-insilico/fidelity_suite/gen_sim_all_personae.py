#!/usr/bin/env python3
"""Generate the FULL UVA/Padova (simglucose) cohort — all 30 in-silico personae:
10 adults, 10 adolescents, 10 children — with realistically randomised, BODY-WEIGHT-
SCALED announced meals, and cache per-patient CGM plus a persona-class label.

Why all 30: the FDA-accepted UVA/Padova simulator ships three age classes with very
different physiology. Testing an AID controller "on the simulator" means all of them,
so a fidelity comparison must cover all of them too. Meals are scaled by body weight
(reference 70 kg) so a child is not handed an adult-sized meal — each persona is given
its most realistic, best-shot scenario.

Meals are ANNOUNCED (BBController boluses on scenario CHO, using each patient's own
CR/CF) because simglucose has no working unannounced/closed-loop controller.

Output: sim_cohort_all.npz  (cgm_<name>, class_<name>, patients, days)
Run:    ~/.venvs/boost-insilico/bin/python gen_sim_all_personae.py [--days 21]
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

# meal template: (mean hour, hour jitter sd, mean grams @70kg, gram sd @70kg, prob present)
MEALS = [
    (7.0,  1.0, 45, 15, 0.92),
    (12.5, 1.2, 65, 20, 0.95),
    (18.5, 1.3, 80, 25, 0.97),
    (15.5, 1.5, 20, 10, 0.45),
    (22.0, 1.0, 18, 10, 0.35),
]


def random_meals(days, rng, bw):
    # weight-appropriate meal sizes, clipped: real carb intake scales only weakly with
    # body weight, so we shrink meals for the small child/adolescent personae without
    # inflating them for heavy adults (reference 70 kg).
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
    patient = T1DPatient.withName(name)
    bw = float(patient._params.BW)
    start = datetime(2026, 1, 1, 0, 0, 0)
    scen = CustomScenario(start_time=start, scenario=random_meals(days, rng, bw))
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
    return np.array([c for c in cgm if c is not None and c > 0], dtype=float), bw


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=21)
    ap.add_argument("--seed", type=int, default=20260729)
    ap.add_argument("--out", default="sim_cohort_all.npz")
    a = ap.parse_args()
    store = {}
    for i, name in enumerate(PATIENTS):
        cls = name.split("#")[0]
        rng = np.random.default_rng(a.seed + i)
        cgm, bw = run_patient(name, a.days, rng)
        store[f"cgm_{name}"] = cgm
        store[f"class_{name}"] = cls
        tir = 100 * np.mean((cgm >= 70) & (cgm < 180)) if len(cgm) else 0
        print(f"{name:>16} bw{bw:5.0f}kg  {len(cgm):6d} pts  mean {cgm.mean():3.0f}  "
              f"cv {100*cgm.std()/cgm.mean():2.0f}%  TIR {tir:2.0f}%", flush=True)
    store["patients"] = np.array(PATIENTS)
    store["days"] = a.days
    np.savez_compressed(a.out, **store)
    print(f"saved {a.out}")


if __name__ == "__main__":
    main()
