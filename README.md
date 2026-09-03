# Boost V6 — experimental AndroidAPS fork

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/aUzQ8q5zQd)

> ⚠️ Experimental. Not medical advice. Not a released or approved product.
> This is a developer's research fork of AndroidAPS that changes the automated insulin-dosing
> decision. Do not run it on a pump unless you fully understand the code, accept the risk, and can
> self-manage the consequences. You are the safety system.

## What Boost V6 is

Boost keeps the entire AndroidAPS engine — basal, dynamic ISF, glucose predictions and every
safety gate — and changes only one thing: the super-micro-bolus (SMB) decision. Stock
AndroidAPS sizes one isolated micro-bolus each cycle, from scratch. Boost V6 instead carries a
*meal hypothesis* across cycles and scales its dosing to how confident it is that a meal is under
way. Nothing else about how AndroidAPS runs your pump is touched.

The result is a system that holds back before a meal is proven, then catches up firmly once it is —
and that you tune with just three dials (far fewer than before), most of which you never touch,
because Boost sets them from your own history on day one.

> *Naming:* the plugin is labelled "Boost V6", but its code and its settings keys still carry the
> earlier "V5" name (`ApsBoostV5…`) from its lineage — V1 → V2 → V3 → v4.4 → v4.4.2 → V6. If you
> read the source, "V5" and "V6" refer to the same current engine.

## How it works — in one glance

A meal-hypothesis state machine drives the SMB:

```
IDLE → OBSERVING → CONFIRMED → COMMITTED → RECOVERING → IDLE
```

It observes lightly while a rise builds, commits a firm catch-up shot once a meal is confirmed,
holds through the meal, then deliberately winds down as insulin takes hold. A layer of safety guards
and personal context (heart rate, sleep, activity) sits around it, and every stock AndroidAPS gate
still runs underneath.

→ Full detail: [How Boost V6 works](docs/v6-how-it-works.md) (the dosing core, the state machine,
the July-2026 safety guards, and the learners).

## Getting started

Boost V6 is the default APS engine in this fork, and you go straight to it — there is no need to
run an older engine first. On its first active cycle it seeds its three dials and its dose caps from
your own recent dosing history (the last 14 days), so it starts where your prior control left off
rather than on a stranger's numbers. That history is read from the AndroidAPS database, and it is
already there if you upgraded in place, restored your AndroidAPS database, or have
Nightscout sync (NSClient) enabled — which backfills up to ~100 days of your treatments and CGM
into the database on first load. It draws from whatever you were running before — standard
AndroidAPS/oref or an earlier Boost — because it only reads dosing history and glucose, not the
engine that produced them.

1. **Install the build.** On a fresh setup Boost V6 is already selected as the APS engine; on an
   in-place upgrade your existing selection is kept, so switch the APS plugin to "Boost V6" if you
   want it.
2. **Make sure your history is present** before you rely on the auto-tuning — enable Nightscout sync,
   or restore your AndroidAPS database, so the last two weeks of dosing are in the app. With no
   history at all, Boost waits and runs on conservative factory defaults until enough data
   accumulates, tuning itself once it has.
3. **It tunes itself and tells you what it set.** You should rarely need to change anything; the three
   dials below are the only ones you would normally touch.

Prefer to watch before you switch? You still can. Selecting the "Boost" plugin (instead of
"Boost V6") runs the same engine with the V6 layer in shadow — it logs what it *would* dose to
Nightscout without driving your pump — and the
[Analyser](https://tim2000s.github.io/Boost-in-AAPS_3.4/boost_analyser.html) compares V1 vs V6 on
your own data. That is optional now, not a required warm-up.

| APS plugin you select | What drives your pump |
|---|---|
| **"Boost V6"** *(default)* | active — the state machine drives the SMB |
| **"Boost"** | the same engine with the V6 override in shadow — logs what it *would* do, does not dose |
| (any other engine) | unchanged — Boost not involved |

> ⚠️ Going active means an experimental algorithm is dosing your pump. You are the safety system —
> watch it, understand it, and keep your own limits (max-IOB, max-bolus) sensible.

## The three levers

These are the only dials you would normally touch — and auto-config already sets each one from your
own dosing history, so most people leave them alone. Each scales *aggressiveness*; none can bypass
a safety limit.

| Setting (as named in the app) | Where | Range (default) | What it is | Turn it up → | Turn it down → |
|---|---|---|---|---|---|
| **Aggression** | Boost V6 | 0.7–1.6 (1.0) | How firm the one meal catch-up shot is (it scales the CONFIRMED commit only — routine holds are bounded by the caps, not this). | a bigger catch-up shot on confirmed meals — for people who peak high | a gentler meal response |
| **Hypo Caution** | Boost V6 | 1.0–2.0 (1.0) | How hard Boost backs off when its hypo-risk model is worried (it does nothing while risk is low). | more insulin trimmed on elevated risk = more hypo-defensive | 1.0 is the floor = least caution |
| **Meal-detection Sensitivity** | Boost V6 → Advanced Settings | 0.8–1.2 (1.0) | A single overall-strength dial for the whole engine — one number that scales *all* of Boost's dosing up or down. **The name is misleading in two ways:** it is not your insulin sensitivity / ISF (that lives in your profile), and despite "meal-detection" it does not change how meals are detected — it scales the per-cycle insulin (aggression) budget. Reach for it when Boost feels uniformly too strong or too weak for you. | firmer everywhere — if Boost runs too weak for you | gentler everywhere — if Boost runs too strong for you |

The **Where** column gives the exact path in the app, and the names above are the exact labels you
will see on those screens — so you can match documentation to settings without guessing.

**How to use them:** start from the auto-config values, change one at a time, and check the
caps and Max IOB first — if a cap or the IOB clamp is what's binding, more Aggression changes
nothing. The [Tuning Guide](https://tim2000s.github.io/Boost-in-AAPS_3.4/boost_tuning_guide.html)
shows each dial on a conservative→aggressive spectrum with worked scenarios.

### Two dose caps you will see referenced

These are not dials you tune day to day, but they are the two limits most often hit, so it helps to
know what they are when a dose looks smaller than you expected.

| Setting (as named in the app) | Where | Range (default) | What it does |
|---|---|---|---|
| **Boost Bolus Cap** | Boost V6 | 0.1–10 U (2.5) | The largest single bolus Boost will give outside the UAM basal-minutes limit below. Auto-config sets it from your own dosing history. With carbs on board Boost may exceed it, up to the greater of this cap or your carbs on board divided by your carb ratio. |
| **Max minutes of basal to limit SMB to for UAM** | Preferences → SMB settings | 15–120 min (15) | Sets the ceiling on a single microbolus as a number of minutes of your own basal: the limit is your current basal rate times these minutes, divided by 60. At a basal of 0.6 U/h, 15 minutes is a ceiling of 0.15 U. It applies when the loop is dosing on unannounced meals rather than carbs, so it is mostly an overnight limit and does not need to be large. |

The companion setting **Max Minutes of basal to limit SMB to**, on the same screen and with the same
default of 15 minutes, does the same job for microboluses given when Boost is not the active engine.

The cap is a size and not a rate: it limits any one bolus and says nothing about how often that
bolus may repeat. Raising it raises every dose the cap was holding down, so change it in small steps
and watch what follows.

Everything else — the cumulative cap, fast-carb confirm, the opt-in aggression levers, DynISF,
activity — is [advanced and set for you on install](docs/v6-advanced-settings.md). You
should rarely need to open that page except to understand a value auto-config chose.

## Interactive tools

Three self-contained HTML tools — no install, no data leaves your machine. A good order for a
newcomer is Tuning Guide → Simulator → Analyser:

- **[▶ Tuning Guide](https://tim2000s.github.io/Boost-in-AAPS_3.4/boost_tuning_guide.html)**
  ([source](boost_tuning_guide.html)) — *learn what each setting does.* Every knob on a
  conservative→aggressive spectrum, with real-world tuning scenarios, for both V1 and V6.
- **[▶ Simulator](https://tim2000s.github.io/Boost-in-AAPS_3.4/boost_simulator.html)**
  ([source](boost_simulator.html)) — *play with the dosing maths.* Set BG, trend, IOB, TDD and the
  settings (or pull a snapshot from Nightscout) and watch the ISF and SMB recompute, live, for both
  the V1 tier ladder and the V6 state machine.
- **[▶ Analyser](https://tim2000s.github.io/Boost-in-AAPS_3.4/boost_analyser.html)**
  ([source](boost_analyser.html)) — *V1 vs V6 on your own data.* Enter your Nightscout URL + a
  read token and it reads the shadow telemetry every Boost build logs, for a real paired comparison.
  Runs entirely in your browser; the token goes only to your Nightscout.

> These tools validate decisions (what dose, which state, why) — not glucose outcomes. They model
> the algorithm, not a body.

## Learn more

- **[How Boost V6 works](docs/v6-how-it-works.md)** — the dosing core, state machine, safety guards, learners.
- **[Advanced settings](docs/v6-advanced-settings.md)** — everything auto-config sets on install, and how.
- **[Heart rate, steps & night mode](docs/v6-heart-rate-and-sleep.md)** — sleep detection and overnight dosing.
- **[Safety, "no training" & validation](docs/v6-safety-and-validation.md)** — why changing a live dosing algorithm is defensible.
- **[Backtesting method & shadow validation](backtesting/README.md)** — the data-analysis toolkit.
- **[Legacy V1 / V2 / v4.x settings](docs/boost-v1-settings.md)** — the earlier plugins' full reference.

---

*Boost is a research fork and an experimental dosing algorithm. Read the code, understand the
risk, and keep your own safety limits sensible. Shadow mode (the "Boost" plugin) is there if you want
to watch before you switch.*
