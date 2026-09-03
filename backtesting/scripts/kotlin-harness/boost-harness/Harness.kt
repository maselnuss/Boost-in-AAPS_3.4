/**
 * Boost engine harness (2026-07-20) — drives the REAL shipping Kotlin engines from a JSON request so the
 * backtest and the APK run identical code ("build and test once"). Reads one request from stdin, runs the
 * requested engine over the cycle batch (state carried across cycles as on-device), writes JSON to stdout.
 * The engine .kt files are the ACTUAL sources under plugins/aps — compiled here, not copied. See HARNESS_SPEC.md.
 */
import app.aaps.plugins.aps.openAPSBoostTwin.TwinShadow
import app.aaps.plugins.aps.openAPSBoostTwin.AnticipationBackoutShadow
import app.aaps.plugins.aps.openAPSBoostTwin.TwinWithdrawalShadow
import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector
import app.aaps.core.data.model.HR
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else getDouble(k)

fun main() {
    val req = JSONObject(System.`in`.readBytes().decodeToString())
    val engine = req.getString("engine")
    val cy = req.getJSONArray("cycles")
    val out = JSONArray()

    when (engine) {
        // ---- KAIROS Twin (real TwinShadow → TwinEnkf/TwinModel) ----
        "twin" -> {
            val tw = TwinShadow()
            for (i in 0 until cy.length()) {
                val c = cy.getJSONObject(i)
                val f = tw.runCycle(c.optDoubleOrNull("cgm"),
                    c.getDouble("insulinThisCycleU"), c.getDouble("expectedBasalPerCycleU"))
                val r = JSONObject().put("i", i)
                if (f != null) r.put("fc30", f.fc30).put("lo30", f.lo30).put("hi30", f.hi30)
                    .put("fc60", f.fc60).put("lo60", f.lo60).put("hi60", f.hi60)
                    .put("raMean", f.raMean).put("filteredGi", f.filteredGi)
                out.put(r)
            }
        }
        // ---- Twin-forecast insulin WITHDRAWAL: real Twin + real TwinWithdrawalShadow.decide per cycle ----
        "twinwithdraw" -> {
            val tw = TwinShadow()
            val thr = req.optJSONObject("params")?.optDouble("lo30Threshold", 70.0) ?: 70.0
            for (i in 0 until cy.length()) {
                val c = cy.getJSONObject(i)
                val f = tw.runCycle(c.optDoubleOrNull("cgm"),
                    c.getDouble("insulinThisCycleU"), c.getDouble("expectedBasalPerCycleU"))
                val r = JSONObject().put("i", i)
                if (f != null) {
                    val d = TwinWithdrawalShadow.decide(
                        lo30 = f.lo30, bg = c.getDouble("bg"),
                        deliverableU = c.getDouble("deliverableU"), lo30Threshold = thr)
                    r.put("lo30", f.lo30).put("raMean", f.raMean).put("fc30", f.fc30)
                        .put("withdraw", if (d.withdraw) 1 else 0)
                        .put("wouldWithholdU", d.wouldWithholdU).put("reason", d.reason)
                }
                out.put(r)
            }
        }
        // ---- anticipatory back-out controller (real AnticipationBackoutShadow) ----
        "backout" -> {
            val bo = AnticipationBackoutShadow()
            for (i in 0 until cy.length()) {
                val c = cy.getJSONObject(i)
                val tag = bo.runCycle(c.getLong("nowMs"), c.optDoubleOrNull("bg"),
                    c.optDoubleOrNull("ra"), c.optDoubleOrNull("lo30"), c.optDoubleOrNull("mealLikely"))
                out.put(JSONObject().put("i", i).put("antBackout", tag ?: JSONObject.NULL))
            }
        }
        // ---- sleep state machine (real SleepStateDetector, via minimal shims for HR/AAPSLogger/LTag) ----
        "sleep" -> {
            var state = SleepStateDetector.State()
            for (i in 0 until cy.length()) {
                val c = cy.getJSONObject(i)
                val hrs = ArrayList<HR>()
                if (c.has("hr")) { val a = c.getJSONArray("hr"); for (j in 0 until a.length()) {
                    val h = a.getJSONObject(j)
                    hrs.add(HR(duration = h.optLong("duration", 300000L), timestamp = h.getLong("timestamp"),
                        beatsPerMinute = h.getDouble("bpm"), device = "harness")) } }
                val inp = SleepStateDetector.Inputs(
                    nowMs = c.getLong("nowMs"), minuteOfDay = c.getInt("minuteOfDay"),
                    hrReadings = hrs, hrResting = c.getInt("hrResting"),
                    stepsLast15Min = c.getInt("stepsLast15Min"), mlMealLikely = c.optDoubleOrNull("mlMealLikely"),
                    nightStartMin = c.getInt("nightStartMin"), nightEndMin = c.getInt("nightEndMin"),
                    stepsToday = c.optInt("stepsToday", -1), sleepInStepsThreshold = c.optInt("sleepInStepsThreshold", 0))
                val res = SleepStateDetector.evaluate(state, inp, null)
                state = res.newState
                out.put(JSONObject().put("i", i).put("state", res.newState.state.name)
                    .put("transitioned", res.transitioned).put("wakeReason", res.wakeReason ?: JSONObject.NULL))
            }
        }
        else -> { System.err.println("unknown engine: $engine"); kotlin.system.exitProcess(2) }
    }
    println(JSONObject().put("engine", engine).put("schema", 1).put("results", out))
}
