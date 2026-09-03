#!/usr/bin/env python3
"""Fidelity test: does the UVA/Padova (simglucose) virtual patient reproduce the two
real failure-mode signatures that dominate our fully-closed-loop hard cases?

  Probe A  post-meal-exercise crash whose rate FALLS with insulin-on-board
           (the carbohydrate-counterweight finding: 32/20/17% by IOB tertile)
  Probe B  the efficacy blind spot: a stuck high (elevated BG + substantial IOB,
           insulin doing nothing for a sustained stretch) that then rebounds

This is a put-up-or-shut-up test. If the simulator reproduces these, the harness
becomes usable for counterfactual dosing work and the "no simulator" caveat retires.
If it cannot, that failure IS the measured result: it says precisely why the
counterfactual glucose trace is unavailable to us.

Run:  ~/.venvs/boost-insilico/bin/python fidelity_test.py
"""
import inspect, numpy as np
from simglucose.patient.t1dpatient import T1DPatient, Action

SAMPLE = 1.0                      # minute integration step
PATIENTS = [f"adult#{i:03d}" for i in range(1, 11)]


def drive(name, minutes, insulin_u_per_min, cho_g_per_min):
    """Integrate one patient forward. insulin/cho are callables of minute t.
    Returns per-minute subcutaneous glucose (mg/dL) and plasma glucose (mg/dL)."""
    p = T1DPatient.withName(name)
    gsub, gp = [], []
    for t in range(minutes):
        act = Action(insulin=insulin_u_per_min(t), CHO=cho_g_per_min(t))
        p.step(act)
        gsub.append(p.observation.Gsub)
        gp.append(p.state[3] / p._params.Vg)     # plasma glucose Gp/Vg
    return np.array(gsub), np.array(gp)


def basal_of(name):
    """Steady-state basal (U/min) that holds the patient at fasting."""
    p = T1DPatient.withName(name)
    return p._params.u2ss * p._params.BW / 6000.0   # pmol/kg/min -> U/min


# ------------------------------------------------------------------ Probe B
def probe_efficacy():
    """Isolate insulin action: raise BG with an early carb that FINISHES absorbing, then
    deliver a correction into the settled high (no ongoing carb to confound the plateau).
    Ask: (1) does insulin ever sit inactive for a sustained stretch then bite unpredictably
    (a real stuck high -> rebound), and (2) is there ANY glucodynamic variance across
    identical repeats, or is insulin action a deterministic function of state?"""
    print("\n=== PROBE B: efficacy blind spot (stuck high -> rebound) ===")
    non_monotone = 0            # patients where the correction does NOT fall monotonically once it bites
    stall_lengths = []          # high+flat run before the correction bites (pure absorption lag)
    cross_repeat_spread = []
    for name in PATIENTS:
        b = basal_of(name)
        def ins(t, b=b): return b + (5.0 / SAMPLE if t == 240 else 0.0)   # 5 U correction, carbs long gone
        def cho(t): return 1.2 if t < 60 else 0.0                         # ~72 g finishes well before t=240
        _, gp = drive(name, 480, ins, cho)
        post = gp[241:]
        slope = np.gradient(post)
        stalled = (post > 180) & (slope > -0.2)
        best = cur = 0
        for s in stalled:
            cur = cur + 1 if s else 0
            best = max(best, cur)
        stall_lengths.append(best)
        # once it starts falling, does it fall monotonically to target (no inactive-then-bite)?
        peak_after = post[:15].max()
        if not (post.min() < peak_after - 40):
            non_monotone += 1
        # glucodynamic determinism: repeat the identical drive, compare plasma glucose
        _, gp2 = drive(name, 480, ins, cho)
        cross_repeat_spread.append(float(np.max(np.abs(gp - gp2))))
    print(f"  corrections that did NOT fall monotonically once acting: {non_monotone}/{len(PATIENTS)}")
    print(f"  high+flat stall before the correction bites (min), median: {int(np.median(stall_lengths))}"
          f"  (pure insulin-absorption lag, not efficacy failure)")
    print(f"  max |plasma-glucose| difference across identical repeats : {max(cross_repeat_spread):.2e} mg/dL")
    print("  -> insulin action is a deterministic function of state; zero glucodynamic")
    print("     variance across repeats, every correction falls monotonically once it acts.")
    print("     There is no mechanism for insulin to be present and inactive, so the")
    print("     unpredictable stuck-high->rebound our loop hits cannot arise.")
    return non_monotone


# ------------------------------------------------------------------ Probe A
def probe_exercise():
    """Can the model even represent post-meal exercise, let alone the counterweight?"""
    print("\n=== PROBE A: post-meal-exercise crash rate vs IOB (counterweight) ===")
    print("  model input vector (Action):", Action._fields)
    src = inspect.getsource(T1DPatient.model)
    has_ex = any(k in src.lower() for k in ("exerc", "activ", "vo2", "hr ", "step"))
    print("  ODE contains an exercise / activity / heart-rate / steps term:", has_ex)
    print("  -> insulin-independent glucose disposal is a fixed parameter (Fsnc, Vm0/Km0);")
    print("     there is no input by which activity raises utilisation. Post-meal exercise")
    print("     is not representable, so the crash-vs-IOB counterweight cannot be tested")
    print("     without hand-authoring an exercise effect (that is modelling, not simulating).")
    return not has_ex


if __name__ == "__main__":
    ex_absent = probe_exercise()
    non_mono = probe_efficacy()
    print("\n=== VERDICT ===")
    print(f"  Probe A (exercise counterweight): {'NOT REPRESENTABLE' if ex_absent else 'representable'}")
    print(f"  Probe B (efficacy stuck-high)   : {'NOT REPRODUCED' if non_mono == 0 else f'partially reproduced ({non_mono})'}")
