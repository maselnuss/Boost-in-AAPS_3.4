#!/usr/bin/env python3
"""Three-layer simulator: S2013-style physiology + behaviour + sensor.

Each layer targets the signatures the layer below it could not reach.

  physiology (gen_sim_s2013.py's SI process)   day-to-day and diurnal statistics
  behaviour  (behaviour.py)                    unannounced meals, rescue carbohydrate,
                                               an adapting sensitivity setting
  sensor     (sensor_layer.py, applied later)  jitter and compression lows

The physiology layer is the S2013 one rather than the fast-OU one from
gen_sim_realistic.py, because the OU process was measured not to close the signature it
was aimed at (REPORT_POC.md) while it did cost autocorrelation fidelity, whereas the SI
process closed glucose variability (REPORT_S2013.md).

--layers selects any subset, so each layer's contribution is attributable rather than
assumed. Output is the raw glucose trace plus the per-patient behaviour record; the
sensor layer is applied post hoc by the comparison script.

Run: ~/.venvs/boost-insilico/bin/python gen_sim_behaviour.py [--days 21] [--layers phys,behav]
"""
import argparse, numpy as np, pandas as pd
from datetime import datetime
from simglucose.patient.t1dpatient import T1DPatient
from simglucose.sensor.cgm import CGMSensor
from simglucose.actuator.pump import InsulinPump
from simglucose.simulation.env import T1DSimEnv
from simglucose.simulation.scenario import CustomScenario
from simglucose.controller.base import Action
from simglucose.controller.basal_bolus_ctrller import BBController, CONTROL_QUEST, PATIENT_PARA_FILE

import behaviour as B

CLASSES = ["adult", "adolescent", "child"]
# (centre hour, hour SD, grams, grams SD, probability, is_snack)
MEALS = [(7.0, 1.0, 45, 15, 0.92, False), (12.5, 1.2, 65, 20, 0.95, False),
         (18.5, 1.3, 80, 25, 0.97, False), (15.5, 1.5, 20, 10, 0.45, True),
         (22.0, 1.0, 18, 10, 0.35, True)]

DIURNAL_AMP = 0.20     # as gen_sim_s2013.py
INTERDAY_CV = 0.22


class S2013Patient(T1DPatient):
    """2008 patient with an S2013-style time-varying insulin sensitivity.

    Identical mechanism to gen_sim_s2013.py: a common factor scaling insulin-dependent
    glucose uptake (Vmx) and hepatic insulin action on EGP (kp3).
    """

    def set_si_process(self, rng, n_days):
        self._Vmx0 = float(self._params["Vmx"])
        self._kp30 = float(self._params["kp3"])
        sigma = np.sqrt(np.log(1 + INTERDAY_CV ** 2))
        self._interday = rng.lognormal(-0.5 * sigma ** 2, sigma, size=n_days + 2)
        self._si_on = True

    def _si(self):
        hour = (self.t / 60.0) % 24
        day = int(self.t // 1440)
        diurnal = 1.0 - DIURNAL_AMP * np.cos(2 * np.pi * (hour - 6) / 24)
        return float(np.clip(diurnal * self._interday[day], 0.3, 2.5))

    def step(self, action):
        if getattr(self, "_si_on", False):
            si = self._si()
            self._params["Vmx"] = self._Vmx0 * si
            self._params["kp3"] = self._kp30 * si
        return super().step(action)


class AnnouncedBBController:
    """Basal-bolus, but dosing the ANNOUNCED carbohydrate rather than the true amount,
    and through a sensitivity setting that may drift.

    The arithmetic is BBController's; what changes is the input. An unannounced meal
    gets no bolus at all, which is the mechanism the rise-tail signature is missing.
    """

    def __init__(self, name, adaptive=True, loop=False):
        quest = pd.read_csv(CONTROL_QUEST)
        params = pd.read_csv(PATIENT_PARA_FILE)
        q = quest[quest.Name.str.match(name)]
        p = params[params.Name.str.match(name)]
        self.cr0 = float(q.CR.values.item())
        self.cf0 = float(q.CF.values.item())
        self.basal = float(p.u2ss.values.item()) * float(p.BW.values.item()) / 6000.0
        self.target = 140.0
        self.ratios = B.AdaptiveRatios(self.cf0, self.cr0) if adaptive else None
        self.loop = B.CorrectionLoop() if loop else None
        self.bolus_units = 0.0

    def act(self, announced_grams, cgm, minute, sample_time, fall_rate=0.0):
        cr, cf = self.cr0, self.cf0
        if self.ratios is not None:
            self.ratios.observe(minute, cgm, self.target)
            cr, cf = self.ratios.cr, self.ratios.cf
        bolus = 0.0
        if announced_grams > 0:
            bolus = announced_grams / cr + (cgm > 150) * (cgm - self.target) / cf
            bolus = max(bolus, 0.0)
        basal = self.basal
        if self.loop is not None:
            self.loop.add(minute, bolus)
            bolus += self.loop.correction(minute, cgm, cf)
            basal *= self.loop.basal_scale(cgm, fall_rate)
        self.bolus_units += bolus
        return Action(basal=basal, bolus=bolus / sample_time)


def random_meals(days, rng, bw):
    scale = float(np.clip(bw / 70.0, 0.5, 1.15))
    out = []
    for d in range(days):
        for mh, hsd, mg, gsd, p, snack in MEALS:
            if rng.random() > p:
                continue
            h = mh + rng.normal(0, hsd)
            g = max(3.0, rng.normal(mg, gsd) * scale)
            out.append((d * 24 + float(np.clip(h, 0.5, 23.5)), round(float(g)), snack))
    return sorted(out)


def run_patient(name, days, rng, layers):
    phys = "phys" in layers
    behav = "behav" in layers
    patient = S2013Patient.withName(name)
    bw = float(patient._params["BW"])
    if phys:
        patient.set_si_process(rng, days)
    start = datetime(2026, 1, 1, 0, 0, 0)
    meals = random_meals(days, rng, bw)

    person = B.Person(rng, rescue=behav, announce=behav)
    if behav:
        scen = B.BehaviourScenario(start, meals, person)
    else:
        scen = CustomScenario(start_time=start, scenario=[(h, g) for h, g, _ in meals])

    env = T1DSimEnv(patient, CGMSensor.withName("Dexcom", seed=int(rng.integers(1, 1_000_000))),
                    InsulinPump.withName("Insulet"), scen)
    env.reset()
    st = env.sample_time
    loop = "loop" in layers
    ctrl = AnnouncedBBController(name, adaptive=behav, loop=loop) if behav else BBController()

    obs, reward, done, info = env.step(Action(basal=0, bolus=0))
    cgm, prev, minute, rate = [], None, 0, 0.0
    for _ in range(int(days * 24 * 60 / st)):
        if behav:
            announced, _true = scen.drain()
            act = ctrl.act(announced, obs.CGM, minute, st, rate)
        else:
            act = ctrl.policy(obs, reward, done, **info)
        obs, reward, done, info = env.step(act)
        v = env.CGM_hist[-1] if env.CGM_hist else obs.CGM
        cgm.append(v)
        minute += st
        if behav and v and v > 0:
            rate = (v - prev) / st if prev else 0.0
            person.observe(minute, v, rate)
            prev = v
        if done:
            break

    rec = dict(rescue_events=person.rescue_events, rescue_grams=person.rescue_grams,
               unannounced=person.unannounced_meals, announced=person.announced_meals,
               isf_cv=(ctrl.ratios.weekly_cv() if behav and ctrl.ratios else float("nan")))
    return np.array([c for c in cgm if c is not None and c > 0], dtype=float), bw, rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=21)
    ap.add_argument("--seed", type=int, default=20260729)   # same seeds as the siblings
    ap.add_argument("--layers", default="phys,behav")
    ap.add_argument("--classes", default="adult")
    ap.add_argument("--n", type=int, default=10)
    ap.add_argument("--out", default="sim_cohort_behaviour.npz")
    a = ap.parse_args()
    layers = set(x.strip() for x in a.layers.split(",") if x.strip())
    classes = [c.strip() for c in a.classes.split(",")]
    patients = [f"{c}#{i:03d}" for c in classes for i in range(1, a.n + 1)]

    store, isf = {}, []
    for i, name in enumerate(patients):
        rng = np.random.default_rng(a.seed + i)
        cgm, bw, rec = run_patient(name, a.days, rng, layers)
        store[f"cgm_{name}"] = cgm
        store[f"class_{name}"] = name.split("#")[0]
        store[f"isfcv_{name}"] = rec["isf_cv"]
        isf.append(rec["isf_cv"])
        tir = 100 * np.mean((cgm >= 70) & (cgm < 180)) if len(cgm) else 0
        tbr = 100 * np.mean(cgm < 70) if len(cgm) else 0
        print(f"{name:>16} bw{bw:5.0f} {len(cgm):6d}pts mean {cgm.mean():3.0f} "
              f"cv {100*cgm.std()/cgm.mean():2.0f}% TIR {tir:2.0f}% TBR {tbr:4.1f}% "
              f"| rescues {rec['rescue_events']:3d} ({rec['rescue_grams']:5.0f}g) "
              f"unannounced {rec['unannounced']:3d}/{rec['unannounced']+rec['announced']:3d} "
              f"isfCV {rec['isf_cv']:4.1f}%", flush=True)
    store["patients"] = np.array(patients)
    store["days"] = a.days
    store["layers"] = ",".join(sorted(layers))
    np.savez_compressed(a.out, **store)
    print(f"saved {a.out}  (median ISF weekly CV {np.nanmedian(isf):.1f}%)")


if __name__ == "__main__":
    main()
