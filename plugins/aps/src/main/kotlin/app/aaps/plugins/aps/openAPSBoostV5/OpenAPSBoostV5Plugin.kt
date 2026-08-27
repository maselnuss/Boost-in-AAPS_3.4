package app.aaps.plugins.aps.openAPSBoostV5

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.aps.OapsProfileBoost
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.AlertDialogHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.aaps.plugins.aps.getBoostDosing
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import app.aaps.plugins.aps.openAPSBoost.BoostRiskModel
import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin
import kotlin.math.abs
import kotlin.math.max

/**
 * Boost V5 — Observe-Confirm-Commit dosing pipeline.
 *
 * Status: PRODUCTION — this is the user-facing "Boost V6" APS plugin, selectable for active dosing
 * (`showInList { config.APS }`). Validated through the test plan's acceptance gates and live use.
 * (Earlier header said PRE-ALPHA/shadow-only/hidden — that was superseded when V5/V6 graduated to
 * the selectable dosing engine; corrected 2026-06-28.)
 *
 * Architecture (see `boost_v5_redesign_proposal.md`):
 *   Phase 1 — state estimation (meal_signal_score → MealHypothesis state machine → AggressionBudget)
 *   Phase 2 — single decision rule (aggression_budget × meal_action_multiplier)
 *   Phase 3 — ordered safety gates (hard gates → soft gates ordered → final clamp)
 *
 * Design tenets:
 *   - Minimal user settings: 3 headline tuning knobs (Aggression / Hypo Caution / Sensitivity)
 *     plus a small advanced set (dose caps, fast-carb toggle, pre-meal target); ~14–15 internal
 *     constants frozen at release.
 *   - Sensitivity inheritance: baseInsulinReq is Boost-flavoured oref (DynISF + 7D TDD with W8H
 *     pull-down + TDD-anchored EMA sensitivity + autosens + hour-of-day ISF + TempTargets).
 *     V5 contains NO sensitivity logic of its own.
 *   - State persistence: MealHypothesis persists across cycles. All reset paths explicit
 *     (reboot, pump disconnect, loop suspend, profile switch, time jump > 30 min).
 *
 * Reference documents in this directory:
 *   - `MIGRATION.md`  — V4.4.1 → V5 mechanism mapping (where did Tier 5 go?)
 *   - `V1_VS_V5.md`   — V1 → V5 side-by-side comparison (architecture, settings, safety,
 *                       brake stacking, observability). The authoritative answer to
 *                       "what does V5 do differently from the original Boost?"
 *
 * V4 retention audit results are recorded in the V5 redesign proposal and migration doc.
 */
@Singleton
open class OpenAPSBoostV5Plugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val config: Config,
    private val preferences: Preferences,
    private val determineBasalBoostV5: DetermineBasalBoostV5,
    // Provider breaks the DI cycle (OpenAPSBoostPlugin injects Provider<OpenAPSBoostV5Plugin> for runShadow).
    private val openAPSBoostEngine: Provider<OpenAPSBoostPlugin>,
    // Auto-config from V1 history on first activation (2026-06-26).
    private val persistenceLayer: PersistenceLayer,
    private val tddCalculator: TddCalculator,
    private val constraintsChecker: ConstraintsChecker,
    private val dateUtil: DateUtil,
    private val uiInteraction: UiInteraction,
    // @Singleton — same instance the V1 engine scored this cycle; its cached feature vector powers
    // the projected-IOB re-score for Phase-3 postActionRiskCheck. (2026-07-02)
    private val boostRiskModel: BoostRiskModel,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.openaps_boost_v5)
        .shortName(R.string.boost_v5_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList { config.APS }
        .description(R.string.description_boost_v5)
        // Boost V6 is the default APS engine on a fresh install (moved from OpenAPSSMBPlugin) — this
        // fork exists to run V6, so a clean DB selects it directly rather than starting on stock SMB.
        // Exactly one APS plugin may carry .setDefault(); PluginStore force-enables it when nothing
        // else is selected and disables every other APS plugin (single-engine invariant).
        .setDefault(),
    aapsLogger, rh
), APS, PluginConstraints {

    override val algorithm = APSResult.Algorithm.BOOST
    // Reflect the engine's result LIVE (not a post-invoke copy): runEngine fires
    // EventAPSCalculationFinished mid-run, before invoke() returns, so a copy would let GUI
    // listeners read a stale/null result on that event. Delegating getters avoid the race.
    override var lastAPSResult: APSResult?
        get() = openAPSBoostEngine.get().lastAPSResult
        set(_) {}
    override var lastAPSRun: Long
        get() = openAPSBoostEngine.get().lastAPSRun
        set(_) {}

    /** State persistence across cycles. Initialised lazily on first access. */
    private val stateStore: V5StateStore by lazy { V5StateStore(preferences) }

    /**
     * "Boost V5" is the selectable face of the shared Boost engine. It runs the full V1 engine
     * with the V5 observe-confirm-commit SMB override ACTIVE, then exposes the engine's result as
     * its own. V5 owns no basal/prediction logic — it delegates to [OpenAPSBoostPlugin.runEngine]
     * (v5Active=true) and inherits the entire sensitivity/engine stack via OapsProfileBoost. The
     * engine still calls V5's [runShadow] internally; v5Active=true is what lets that decision
     * override the SMB. Selecting plain "Boost" runs the same engine with v5Active=false.
     */
    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        val now = dateUtil.now()
        // Shared, lazily-computed V1Profile: maybeAutoConfigure and maybePeriodicReview each need
        // it only past their OWN early-return gate (steady state: neither needs it most cycles).
        // `lazy` means the (fairly heavy — TDD calc + 14d bolus/BG queries) gather runs AT MOST
        // once per invoke(), shared if both happen to need it the same cycle, and not at all if
        // neither does — instead of each function pulling its own copy independently.
        val lazyProfile = lazy { gatherV1Profile(now) }

        // First-activation: seed the V5 knobs from the user's own V1 history (before the first dose
        // this cycle, so the engine picks up the values immediately). One-shot, guarded, never
        // overrides a knob the user already tuned.
        runCatching { maybeAutoConfigure { lazyProfile.value } }
            .onFailure { aapsLogger.error(LTag.APS, "BoostV5 auto-config failed (non-fatal)", it) }
        // Konzept 7 — periodic re-suggestion review. Independent of the one-shot auto-config above:
        // re-derives EVERY managed knob on an interval and, if anything differs, surfaces a
        // notification the user can open to review/apply per item. Never writes anything itself.
        runCatching { maybePeriodicReview(now) { lazyProfile.value } }
            .onFailure { aapsLogger.error(LTag.APS, "BoostV5 periodic review failed (non-fatal)", it) }
        // Run the shared engine with the V5 override active. lastAPSResult/lastAPSRun delegate to
        // the engine (see above), so the result is exposed the instant runEngine sets it.
        openAPSBoostEngine.get().runEngine(initiator, tempBasalFallback, v5Active = true)
    }

    /** Per-knob auto-config resolution marks (argument = the managed preference's key string). */
    private fun isResolved(prefKey: String) = preferences.get(BooleanComposedKey.BoostV5AutoConfigResolved, prefKey)
    private fun markResolved(prefKey: String) = preferences.put(BooleanComposedKey.BoostV5AutoConfigResolved, prefKey, value = true)

    /** Auto-config-managed boolean dosing switches (2026-07-17 convention: every new dosing switch is
     *  auto-config managed). Each resolves once, suggestion-only, exactly like the double knobs. */
    private val managedBooleanKeys = listOf(
        BooleanKey.ApsBoostV5FastCarbConfirm,
        BooleanKey.ApsBoostV5AggressiveEarlyConfirm,
        BooleanKey.ApsBoostV5VelocityBudgetActive,
        BooleanKey.ApsBoostV5PrimerTbrFallback,   // 2026-07-20 primer routing (NOT the user override, which is unmanaged)
    )

    private fun suggestionBoolean(s: BoostV5AutoConfig.V5Suggestion, key: BooleanKey): Boolean = when (key) {
        BooleanKey.ApsBoostV5FastCarbConfirm         -> s.fastCarbConfirm
        BooleanKey.ApsBoostV5AggressiveEarlyConfirm  -> s.aggressiveEarlyConfirm
        BooleanKey.ApsBoostV5VelocityBudgetActive    -> s.velocityBudgetFloor
        BooleanKey.ApsBoostV5PrimerTbrFallback       -> s.primerTbrFallback
        else                                         -> key.defaultValue
    }

    /**
     * Populate the V5 knobs from the user's last-14-day V1 dosing + glycaemia when V5/V6 runs
     * active. Suggestion-only: writes a knob ONLY while the user hasn't changed it from a factory
     * default — ANY factory default the key ever shipped with, so old-build users aren't frozen at
     * an old era's value (a value merely *persisted at* a default — settings import, pref-dialog
     * OK — does not block it). Dose-cap RAISES are additionally held back (surfaced as suggestions)
     * when the 14-day TBR<70 exceeds [BoostV5AutoConfigApply.TBR_RAISE_GUARD_PCT]. Each knob
     * resolves individually (see [BoostV5AutoConfigApply]): applied once, or skipped, and then
     * never revisited. If there isn't enough history yet, nothing resolves and every open knob
     * retries on a later cycle once data accrues. Never changes the dosing path itself — only its
     * settings.
     */
    // 2026-07-08: composed brake-floor hypo-gate. The floor is insulin-ADDING, so it may only engage
    // when the user's trailing-14d time-below-63 mg/dL (3.5 mmol) is under COMPOSED_FLOOR_MAX_TBR63_PCT.
    // A 14-day metric moves slowly, so it is recomputed at most hourly and cached; fail-closed (the
    // floor stays off) until the first successful compute and whenever CGM history is too thin.
    private val TBR_GATE_REFRESH_MS = 60L * 60 * 1000         // hourly

    // Konzept 7 (2026-08-26) — periodic auto-config review interval. 14 days, matching
    // BoostV5AutoConfig.LOOKBACK_DAYS: the review re-derives from a trailing-14d V1Profile, so a
    // 14-day cadence means each review's window has fully turned over (zero overlap) with the one
    // the previous review used — the tightest cadence with no double-counted data.
    private val PERIODIC_REVIEW_INTERVAL_MS = BoostV5AutoConfig.LOOKBACK_DAYS * 24L * 60 * 60 * 1000
    private val TBR_GATE_MIN_READINGS = 1000                  // ~3.5 days of 5-min CGM before the % is trusted
    @Volatile private var cachedTbrBelow63Pct: Double? = null
    @Volatile private var cachedTbrBelow70Pct: Double? = null
    @Volatile private var lastTbrGateComputeMs: Long = 0L

    // Konzept 7 — set by maybePeriodicReview() (runs in invoke(), before this cycle's rT exists)
    // when it surfaces a fresh review THIS cycle; consumed+cleared by runShadow() the moment the
    // live rT becomes available, so the note reaches NS/consoleError exactly once.
    @Volatile private var pendingPeriodicReviewNsNote: String? = null

    /** Throttled trailing-14d time-below-63 AND -70 mg/dL, then the fail-closed floor hypo-gate. */
    internal fun composedFloorTbrAllowed(now: Long): Boolean {
        if (cachedTbrBelow63Pct == null || now - lastTbrGateComputeMs >= TBR_GATE_REFRESH_MS) {
            val start = now - BoostV5AutoConfig.LOOKBACK_DAYS * 24L * 60 * 60 * 1000
            val bgs = persistenceLayer.getBgReadingsDataFromTimeToTime(start, now, true)
            val n = bgs.size
            if (n >= TBR_GATE_MIN_READINGS) {
                cachedTbrBelow63Pct = 100.0 * bgs.count { it.value >= 1.0 && it.value < 63.0 } / n
                cachedTbrBelow70Pct = 100.0 * bgs.count { it.value >= 1.0 && it.value < 70.0 } / n
            } else {
                cachedTbrBelow63Pct = null; cachedTbrBelow70Pct = null
            }
            lastTbrGateComputeMs = now
        }
        return composedFloorAllowedByTbr(cachedTbrBelow63Pct, cachedTbrBelow70Pct)
    }

    /** Short, human-readable knob name for log lines / notification text (drops the common prefixes). */
    private fun shortName(key: DoubleKey) = key.name.removePrefix("ApsBoostV5").removePrefix("ApsBoost")
    private fun shortName(key: BooleanKey) = key.name.removePrefix("ApsBoostV5").removePrefix("ApsBoost")

    /**
     * Gather the last-[BoostV5AutoConfig.LOOKBACK_DAYS]-day [BoostV5AutoConfig.V1Profile] from the
     * user's actual dosing + glycaemia history. Shared by [maybeAutoConfigure] (first-activation,
     * one-shot per knob) and [maybePeriodicReview] (Konzept 7, recurring, re-derives everything) —
     * both need the identical input gathering, only what they DO with the resulting suggestion
     * differs.
     */
    private fun gatherV1Profile(now: Long): BoostV5AutoConfig.V1Profile {
        val start = now - BoostV5AutoConfig.LOOKBACK_DAYS * 24L * 60 * 60 * 1000

        // TDD (median over available days) + days-of-data.
        val tdds = tddCalculator.calculate(BoostV5AutoConfig.LOOKBACK_DAYS, allowMissingDays = true)
        val tddValues = mutableListOf<Double>()
        if (tdds != null) for (i in 0 until tdds.size()) {
            val t = tdds.valueAt(i)
            val total = if (t.totalAmount > 0) t.totalAmount else t.basalAmount + t.bolusAmount
            if (total > 0) tddValues.add(total)
        }
        val tddMedian = BoostV5AutoConfig.percentile(tddValues, 50.0)
        val daysWithData = tddValues.size

        // Boluses split into manual (meal) vs SMB.
        val boluses = persistenceLayer.getBolusesFromTimeToTime(start, now, true)
        val manual = boluses.filter { it.type == BS.Type.NORMAL && it.amount > 0 }.map { it.amount }
        val smb = boluses.filter { it.type == BS.Type.SMB && it.amount > 0 }.map { it.amount }

        // Glycaemia (TBR / severe / mean) from CGM.
        val bgs = persistenceLayer.getBgReadingsDataFromTimeToTime(start, now, true)
        val n = bgs.size
        val tbr70 = if (n > 0) 100.0 * bgs.count { it.value >= 1.0 && it.value < 70.0 } / n else 0.0
        val sev54 = if (n > 0) 100.0 * bgs.count { it.value >= 1.0 && it.value < 54.0 } / n else 0.0
        val meanBg = if (n > 0) bgs.sumOf { it.value } / n else 0.0

        return BoostV5AutoConfig.V1Profile(
            daysWithData = daysWithData, bgReadingCount = n, tddMedianU = tddMedian,
            manualBolusesU = manual, smbAmountsU = smb,
            tbrBelow70Pct = tbr70, timeBelow54Pct = sev54, meanGlucoseMgdl = meanBg,
            currentMaxIobU = constraintsChecker.getMaxIOBAllowed().value(),
            currentMaxBolusU = constraintsChecker.getMaxBolusAllowed().value()
        )
    }

    private fun maybeAutoConfigure(profileProvider: () -> BoostV5AutoConfig.V1Profile) {
        // Migration from the legacy global one-shot flag (raw read — get() would mask it in simple
        // mode): mark resolved ONLY knobs whose stored value differs from the factory default (they
        // were plausibly applied by the old run, or user-set — don't rewrite them). Knobs still AT
        // factory default become eligible again — rescues installs where the old key-presence test
        // or a consumed/imported flag wrongly skipped them (field case: user H, committedCap stuck
        // at factory 0.5 with a derived 1.24). Clearing the flag makes the migration one-shot.
        if (preferences.getIfExists(BooleanKey.ApsBoostV5AutoConfigDone) == true) {
            val migrated = BoostV5AutoConfigApply.migrateLegacyDoneFlag(
                BoostV5AutoConfigApply.managedDoubleKeys,
                storedValue = { preferences.getIfExists(it) },
                markResolved = { markResolved(it.key) }
            ).map { it.key }.toMutableList()
            val fc = BooleanKey.ApsBoostV5FastCarbConfirm
            if (preferences.getIfExists(fc).let { it != null && it != fc.defaultValue }) {
                markResolved(fc.key); migrated += fc.key
            }
            preferences.put(BooleanKey.ApsBoostV5AutoConfigDone, false)
            aapsLogger.info(LTag.APS, "BoostV5 auto-config: migrated legacy done-flag → per-key; resolved=$migrated")
        }

        // Versioned re-migration of the persisted resolved flags (idempotent; stamps the schema
        // version; MUST run before the steady-state early-return — a stranded install has every
        // knob resolved). v2 rescues knobs the promoted 2026-07-06 APK's era-blind isUserTuned
        // mis-resolved at OLD factory values — see BoostV5AutoConfigApply.AUTO_CONFIG_SCHEMA_VERSION.
        BoostV5AutoConfigApply.runSchemaMigrations(
            storedVersion = preferences.get(IntNonKey.BoostV5AutoConfigSchemaVersion),
            keys = BoostV5AutoConfigApply.managedDoubleKeys,
            isResolved = { isResolved(it.key) },
            storedValue = { preferences.getIfExists(it) },
            clearResolved = { preferences.remove(BooleanComposedKey.BoostV5AutoConfigResolved, it.key) },
            setVersion = { preferences.put(IntNonKey.BoostV5AutoConfigSchemaVersion, it) }
        ).forEach {
            aapsLogger.info(
                LTag.APS,
                "BoostV5 auto-config re-migration v2: ${it.key} value ${preferences.getIfExists(it)} matches historical factory — re-opened for derivation"
            )
        }

        // Steady state: everything resolved → nothing to do (cheap check, no data pulls).
        val allKeys = BoostV5AutoConfigApply.managedDoubleKeys.map { it.key } + managedBooleanKeys.map { it.key }
        if (allKeys.all { isResolved(it) }) return

        val profile = profileProvider()
        val suggestion = BoostV5AutoConfig.compute(profile)
        if (suggestion == null) {
            // Nothing resolves here: every open knob stays eligible and genuinely retries next cycle.
            aapsLogger.info(LTag.APS, "BoostV5 auto-config: insufficient V1 history (days=${profile.daysWithData}, bg=${profile.bgReadingCount}) — will retry")
            return
        }

        // Undo safety net (2026-08-27): capture what every managed knob holds RIGHT NOW, before
        // anything below writes to it. Cheap (in-memory prefs cache) — filtered down to only the
        // knobs actually touched this cycle once that's known, a few lines down.
        val beforeDoubles = BoostV5AutoConfigApply.managedDoubleKeys.associateWith { preferences.getIfExists(it) ?: it.defaultValue }
        val beforeBooleans = managedBooleanKeys.associateWith { preferences.getIfExists(it) ?: it.defaultValue }

        // Apply only knobs the user (or a preset) hasn't TUNED (stored value differs from EVERY
        // factory default the key ever shipped with). Per-knob & independent — a tuned value is
        // KEPT and never blocks the others; each knob resolves (applied / kept-user-tuned /
        // suggested-not-applied) exactly once (see BoostV5AutoConfigApply, unit-tested). Dose-cap
        // RAISES are held back (suggestion-only) when TBR<70 exceeds the guard threshold; the
        // cumulative cap is recomputed inside from the final operative per-shot caps.
        val resolutions = BoostV5AutoConfigApply.applyAutoConfig(
            suggestion,
            tbrBelow70Pct = profile.tbrBelow70Pct,
            timeBelow54Pct = profile.timeBelow54Pct,
            isResolved = { isResolved(it.key) },
            storedValue = { preferences.getIfExists(it) },
            put = { key, value -> preferences.put(key, value) },
            markResolved = { markResolved(it.key) }
        )
        // Log every classification verbatim so field diagnosis never needs inference.
        resolutions.forEach { aapsLogger.info(LTag.APS, "BoostV5 auto-config: ${it.key.key} → ${it.reason}") }
        val appliedResolutions = resolutions.filter { it.outcome == BoostV5AutoConfigApply.Outcome.APPLIED }
        val appliedDoubleKeys = appliedResolutions.map { it.key }
        val applied = appliedResolutions.map { "${shortName(it.key)}=${it.suggestedValue}" }.toMutableList()
        // Boolean managed keys. 2026-07-17 convention: every new dosing switch is auto-config managed
        // (not shipped OFF-for-everyone requiring manual discovery). Same suggestion-only, per-key,
        // resolve-once semantics as the double knobs — write only a key still at a factory default.
        val appliedBooleanKeys = mutableListOf<BooleanKey>()
        for (bk in managedBooleanKeys) {
            if (isResolved(bk.key)) continue
            val value = suggestionBoolean(suggestion, bk)
            val stored = preferences.getIfExists(bk)
            if (stored == null || stored == bk.defaultValue) {
                preferences.put(bk, value)
                if (value != bk.defaultValue) {
                    applied += "${bk.name.removePrefix("ApsBoostV5")}=$value"
                    appliedBooleanKeys += bk
                }
            }
            markResolved(bk.key)
        }
        if (appliedDoubleKeys.isNotEmpty() || appliedBooleanKeys.isNotEmpty()) {
            val snapshot = BoostV5AutoConfigBackup.Snapshot(
                atMs = dateUtil.now(),
                trigger = "autoConfig",
                doubles = appliedDoubleKeys.associateWith { beforeDoubles.getValue(it) },
                booleans = appliedBooleanKeys.associateWith { beforeBooleans.getValue(it) }
            )
            preferences.put(StringNonKey.ApsBoostV5AutoConfigBackup, BoostV5AutoConfigBackup.pushSnapshot(preferences.get(StringNonKey.ApsBoostV5AutoConfigBackup), snapshot))
        }

        aapsLogger.info(LTag.APS, "BoostV5 auto-config applied [$applied]; rationale: ${suggestion.rationale}")
        // Only surface a banner if we ACTUALLY changed something or have a held-back suggestion to
        // show. If every knob was already tuned by the user, applyAutoConfig skips it — announcing
        // "configured" then changing nothing was the confusing behaviour Tim hit. One concise,
        // readable line per knob; TBR-held cap raises are surfaced as manual suggestions.
        val heldSuggestions = resolutions.filter { it.outcome == BoostV5AutoConfigApply.Outcome.SUGGESTED_NOT_APPLIED_TBR }
            .map {
                // Name whichever guard(s) actually tripped (<70 raise-guard and/or the 2026-07-07
                // <54 severe co-guard) so the user sees why the raise was held.
                val why = buildList {
                    if (profile.tbrBelow70Pct > BoostV5AutoConfigApply.TBR_RAISE_GUARD_PCT) add("time-below-70 is ${Math.round(profile.tbrBelow70Pct * 10.0) / 10.0}%")
                    if (profile.timeBelow54Pct >= BoostV5AutoConfigApply.TBR54_RAISE_GUARD_PCT) add("time-below-54 is ${Math.round(profile.timeBelow54Pct * 10.0) / 10.0}%")
                }.joinToString(" and ")
                "${shortName(it.key)}: suggested ${it.suggestedValue} U from your history — not auto-applied because " +
                    "$why; set manually in Advanced if desired"
            }
        if (applied.isNotEmpty() || heldSuggestions.isNotEmpty()) {
            val pretty = (applied + heldSuggestions).joinToString("\n") { "• $it" }
            uiInteraction.addNotification(
                Notification.USER_MESSAGE,
                "Boost V6 set ${applied.size} setting(s) from your last 14 days (your other settings were kept):\n$pretty",
                Notification.INFO
            )
        }
    }

    /**
     * Konzept 7 (2026-08-26) — periodic re-suggestion review. On [PERIODIC_REVIEW_INTERVAL_MS],
     * re-derive EVERY BoostV5AutoConfig-managed double knob (regardless of BoostV5AutoConfigApply's
     * per-knob resolution lock — this is a SEPARATE, recurring, human-confirmed path, not a
     * bypass of "a human decision takes precedence") from the user's CURRENT V1 history and diff
     * against the CURRENTLY operative value ([BoostV5PeriodicReview]). If anything differs, surface
     * ONE notification; tapping it opens a single dialog listing every changed item with its own
     * rationale and a "manually set" marker where applicable, with per-item checkboxes (all
     * pre-checked — unchecking is opt-out, not opt-in) plus apply/discard. Nothing is written until
     * the user confirms in the dialog.
     */
    private fun maybePeriodicReview(now: Long, profileProvider: () -> BoostV5AutoConfig.V1Profile) {
        val lastReview = preferences.get(LongNonKey.ApsBoostV5PeriodicReviewLastMs)
        if (lastReview != 0L && now - lastReview < PERIODIC_REVIEW_INTERVAL_MS) return

        val profile = profileProvider()
        // 2026-08-27 — covers the 4 managed BOOLEAN switches too, not just the double-valued knobs
        // (was a real gap: they were only ever decided ONCE by the one-shot AutoConfig at first V6
        // activation, never reconsidered here). Both calls share the SAME BoostV5AutoConfig.compute()
        // gate on the SAME profile, so they're null/non-null together — no real divergence risk.
        val doubleItems = BoostV5PeriodicReview.computeReviewItems(profile) { preferences.getIfExists(it) }
        val booleanItems = BoostV5PeriodicReview.computeBooleanReviewItems(profile) { preferences.getIfExists(it) }
        if (doubleItems == null || booleanItems == null) {
            // Insufficient history — do NOT stamp the timestamp, so this genuinely retries once
            // enough data accrues (same discipline as maybeAutoConfigure's insufficient-data path).
            aapsLogger.info(LTag.APS, "BoostV5 periodic review: insufficient V1 history (days=${profile.daysWithData}, bg=${profile.bgReadingCount}) — will retry")
            return
        }
        preferences.put(LongNonKey.ApsBoostV5PeriodicReviewLastMs, now)
        val totalCount = doubleItems.size + booleanItems.size
        if (totalCount == 0) {
            aapsLogger.info(LTag.APS, "BoostV5 periodic review: every managed knob/switch already matches its fresh suggestion — nothing to show")
            return
        }
        // Human-readable form for the app log only (free text, not parsed by anything).
        val logSummary = (doubleItems.map { "${shortName(it.key)} ${it.currentValue}→${it.suggestedValue}" } +
            booleanItems.map { "${shortName(it.key)} ${it.currentValue}→${it.suggestedValue}" }).joinToString()
        aapsLogger.info(LTag.APS, "BoostV5 periodic review: $totalCount item(s) differ from their fresh suggestion: $logSummary")
        // Threaded into THIS cycle's rT by runShadow() (called from runEngine(), right after
        // invoke() returns) — see pendingPeriodicReviewNsNote's KDoc. Gives this event the same
        // NS (it.reason) + in-app Script-debug (consoleError) visibility as every other shadow note,
        // even though — unlike those — this is a discrete settings-review event, not a continuous
        // per-cycle value (same category as the one-shot auto-config banner, which historically
        // only got a Notification, not NS/consoleError; this closes that gap for the recurring path).
        //
        // Format matches the SAME "tag: key=value key=value;" convention every other shadow note
        // uses (floorSlewShadow, exerciseShadow, alcoholShadow, ...) — deliberately NOT a new RT
        // field: RT.kt documents a reproduced VerifyError/instant-startup-crash (2026-07-18, KAIROS
        // Twin) from adding fields to this @Serializable class near the ART method-verifier's
        // register limit. items= holds "Key:current->suggested" triples, comma-separated (no spaces
        // inside the value so a naive split on whitespace still finds the other key=value pairs) —
        // double AND boolean items share the same flat list, no separate sub-tag needed.
        val itemsTag = (doubleItems.map { "${shortName(it.key)}:${it.currentValue}->${it.suggestedValue}" } +
            booleanItems.map { "${shortName(it.key)}:${it.currentValue}->${it.suggestedValue}" }).joinToString(",")
        pendingPeriodicReviewNsNote = "periodicReview: count=$totalCount items=$itemsTag; "

        // A previous review notification may still be sitting unread in the store (14-day gaps are
        // long). NotificationStore.add() de-dupes by id but KEEPS the OLD object on a collision
        // (only bumps date/validTo) — so without an explicit dismiss first, a stale notification
        // from the last cycle would silently swallow this fresh one, and tapping it would open the
        // OLD `items` (old suggestions, no longer matching current preferences). Dismiss first so
        // the store always ends up holding the fresh notification/items.
        uiInteraction.dismissNotification(Notification.BOOST_V5_PERIODIC_REVIEW)
        uiInteraction.addNotificationWithContextAction(
            id = Notification.BOOST_V5_PERIODIC_REVIEW,
            text = rh.gs(R.string.boost_v5_periodic_review_notification, totalCount),
            level = Notification.INFO,
            buttonText = R.string.boost_v5_periodic_review_button,
            action = { context -> showPeriodicReviewDialog(context, doubleItems, booleanItems) },
            validityCheck = null
        )
    }

    /**
     * Dialog-only rendering of a review item's rationale. [BoostV5AutoConfig.rationaleByKey] is
     * shared with the NS reason/consoleError log — precise and technical, written for retrospective
     * analysis, not for a quick-glance settings dialog. Found on review (2026-08-26): maxIOB/Bolus
     * read as self-contradictory next to a value that IS changing ("carried from your CURRENT
     * setting"), Primer is dense with internal dev jargon (temp-basal accounting details), and
     * Aggression/FloorSlewAggressiveness reference "shadow" — this project's internal terminology,
     * meaningless outside it. Doesn't touch [BoostV5AutoConfig.kt] itself (NS/log/tests stay
     * precise) — purely how the SAME rationale renders here.
     */
    private fun dialogRationale(key: DoubleKey, rawRationale: String): String = when (key) {
        DoubleKey.ApsBoostMaxIob ->
            "Matches your AAPS maxIOB constraint — not currently mirrored in this Boost setting."
        DoubleKey.ApsBoostBolus ->
            "Matches your AAPS max-bolus constraint — not currently mirrored in this Boost setting."
        DoubleKey.ApsBoostV5PrimerCapU ->
            "Small early dose while a meal is still being confirmed, sized from your own typical SMB amount. " +
                if (rawRationale.contains("retractable temp-basal")) "Delivered as a temp-basal that can unwind safely."
                else "Delivered as a small bolus."
        // Aggression and FloorSlewAggressiveness both end "(... ; shadow-only/refines after ...)" —
        // drop the internal-jargon clause after the ";", keep the plain-language reason before it.
        DoubleKey.ApsBoostV5Aggression, DoubleKey.ApsBoostFloorSlewAggressiveness ->
            rawRationale.substringAfter("(").substringBefore(";").trim().replaceFirstChar { it.uppercase() }
        // Generic case (HypoCaution, the caps): strip the redundant leading "<Name> <value>" — the
        // bold title line above already shows both — keep just the parenthetical "why".
        else ->
            rawRationale.substringAfter("(").substringBeforeLast(")").trim().replaceFirstChar { it.uppercase() }
    }

    /**
     * The Konzept 7 review dialog. Same construction pattern as every other AAPS dialog
     * ([app.aaps.core.ui.dialogs.OKDialog]): [MaterialAlertDialogBuilder] with
     * [app.aaps.core.ui.R.style.DialogTheme] + [AlertDialogHelper.buildCustomTitle] — this alone is
     * what makes it inherit the app's light/dark theme automatically, no manual styling needed.
     * One checkbox per changed knob (all pre-checked — these are reasoned, backtested-formula
     * suggestions), each row showing current → suggested plus its own rationale and a
     * "manually set" marker when the current value was a deliberate prior choice (not a factory
     * default). Two actions: Apply selected (positive, respects checkboxes — pre-checked, so doing
     * nothing and tapping this already applies everything) and Discard (negative, writes nothing).
     * Deliberately NOT a third "Apply all" button: with a long item list the standard Android
     * AlertDialog stacks 3 buttons vertically and can push the lower ones off-screen (confirmed
     * 2026-08-26 on-device, an 8-item list hid both Apply-all and Discard) — 2 buttons always fit
     * side-by-side. "Apply all" was redundant anyway: unchecking is opt-out, so an untouched dialog
     * already IS "apply all" via the positive button.
     */
    private fun showPeriodicReviewDialog(
        context: Context,
        doubleItems: List<BoostV5PeriodicReview.ReviewItem>,
        booleanItems: List<BoostV5PeriodicReview.BooleanReviewItem>
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Custom row layout instead of setMultiChoiceItems: the built-in list item centers its
        // checkbox against the WHOLE (multi-line) text block, which looks misaligned once the
        // rationale line wraps to 2-3 lines (confirmed on-device, 2026-08-26 — user feedback: looked
        // "unübersichtlich", checkbox didn't line up with anything). Here each row is built by hand
        // — checkbox top-aligned to the bold title line specifically, with a smaller/dimmer
        // rationale line below it — a clear title/detail hierarchy instead of one dense paragraph.
        // No hardcoded colors (theme-safe light/dark): the rationale is de-emphasised via alpha on
        // the theme's own default text color, not a fixed color value.
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        fun row(checkBox: CheckBox, name: String, tunedMarker: String, deltaText: String, deltaColor: Int?, rationale: String) {
            val prefix = "$name$tunedMarker: "
            val titleView = TextView(context).apply {
                text = SpannableString(prefix + deltaText).apply {
                    if (deltaColor != null) {
                        setSpan(ForegroundColorSpan(deltaColor), prefix.length, prefix.length + deltaText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                setTypeface(typeface, Typeface.BOLD)
            }
            val rationaleView = TextView(context).apply {
                text = rationale
                textSize = 13f
                alpha = 0.7f
                setPadding(0, dp(2), 0, 0)
            }
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleView)
                addView(rationaleView)
            }
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(10), 0, dp(10))
                addView(checkBox)
                addView(textColumn)
            }
            container.addView(rowLayout)
        }

        val doubleCheckBoxes = mutableListOf<CheckBox>()
        for (item in doubleItems) {
            val checkBox = CheckBox(context).apply { isChecked = true }
            doubleCheckBoxes += checkBox
            // Delta gets its own color (on top of the title's overall bold) so the actual CHANGE —
            // not just the knob name — jumps out, per feedback: it was "hidden in the text" before.
            // Standard Material palette green/red (500-weight — designed to read on light AND dark
            // surfaces, unlike the app's BG-specific high/low colors which carry a different meaning
            // here and would be confusing: this is an increase/decrease of a SETTING, not a glucose
            // reading).
            val deltaColor = if (item.suggestedValue > item.currentValue) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
            row(
                checkBox = checkBox,
                name = shortName(item.key),
                tunedMarker = if (item.wasUserTuned) " " + rh.gs(R.string.boost_v5_periodic_review_manually_set_marker) else "",
                deltaText = "${item.currentValue} → ${item.suggestedValue}",
                deltaColor = deltaColor,
                rationale = dialogRationale(item.key, item.rationale)
            )
        }

        // 2026-08-27 — boolean switches (fastCarbConfirm, aggressiveEarlyConfirm, velocityBudgetFloor,
        // primerTbrFallback), same row style as the double knobs above. No green/red delta coloring
        // here on purpose: ON/OFF has no inherent "higher is more" direction the way a number does,
        // so coloring it would imply a good/bad judgement that isn't actually there.
        val booleanCheckBoxes = mutableListOf<CheckBox>()
        for (item in booleanItems) {
            val checkBox = CheckBox(context).apply { isChecked = true }
            booleanCheckBoxes += checkBox
            row(
                checkBox = checkBox,
                name = shortName(item.key),
                tunedMarker = if (item.wasUserTuned) " " + rh.gs(R.string.boost_v5_periodic_review_manually_set_marker) else "",
                deltaText = "${if (item.currentValue) "ON" else "OFF"} → ${if (item.suggestedValue) "ON" else "OFF"}",
                deltaColor = null,
                rationale = item.rationale
            )
        }

        val scrollableContent = ScrollView(context).apply { addView(container) }

        // Items were computed when the notification fired, which may have been days ago (14-day
        // interval; nothing forces an immediate tap). If the user ALSO changed one of the same
        // knobs by hand in the meantime (e.g. in Settings), item.currentValue is stale — blindly
        // writing suggestedValue over it would silently clobber a more recent manual decision,
        // which is exactly backwards for a project built on "a human decision takes precedence"
        // (BoostV5AutoConfigApply's own core principle). So re-read the LIVE stored value right
        // before writing and skip (not overwrite) any item where it has drifted since capture.
        // Handles BOTH double and boolean selections and fires exactly ONE combined toast — see the
        // Notification.USER_MESSAGE dedup note below for why it must stay a single call.
        fun applySelections(doubleIndices: List<Int>, booleanIndices: List<Int>) {
            var applied = 0
            var skippedStale = 0
            // Undo safety net (2026-08-27): the "live" value re-read below for the staleness check
            // IS the value right before it gets overwritten — reuse it as the backup, no extra read.
            val beforeDoubles = mutableMapOf<DoubleKey, Double>()
            val beforeBooleans = mutableMapOf<BooleanKey, Boolean>()
            for (i in doubleIndices) {
                val item = doubleItems[i]
                val live = preferences.getIfExists(item.key) ?: item.key.defaultValue
                if (abs(live - item.currentValue) > 1e-6) {
                    skippedStale++
                    aapsLogger.info(
                        LTag.APS,
                        "BoostV5 periodic review: skipped ${shortName(item.key)} — value changed since this review " +
                            "was computed (now $live, review captured ${item.currentValue}); re-run the review to reconsider it"
                    )
                    continue
                }
                preferences.put(item.key, item.suggestedValue)
                beforeDoubles[item.key] = live
                applied++
            }
            for (i in booleanIndices) {
                val item = booleanItems[i]
                val live = preferences.getIfExists(item.key) ?: item.key.defaultValue
                if (live != item.currentValue) {
                    skippedStale++
                    aapsLogger.info(
                        LTag.APS,
                        "BoostV5 periodic review: skipped ${shortName(item.key)} — value changed since this review " +
                            "was computed (now $live, review captured ${item.currentValue}); re-run the review to reconsider it"
                    )
                    continue
                }
                preferences.put(item.key, item.suggestedValue)
                beforeBooleans[item.key] = live
                applied++
            }
            if (beforeDoubles.isNotEmpty() || beforeBooleans.isNotEmpty()) {
                val snapshot = BoostV5AutoConfigBackup.Snapshot(
                    atMs = dateUtil.now(),
                    trigger = "periodicReview",
                    doubles = beforeDoubles,
                    booleans = beforeBooleans
                )
                preferences.put(StringNonKey.ApsBoostV5AutoConfigBackup, BoostV5AutoConfigBackup.pushSnapshot(preferences.get(StringNonKey.ApsBoostV5AutoConfigBackup), snapshot))
            }
            if (applied > 0) aapsLogger.info(LTag.APS, "BoostV5 periodic review: applied $applied item(s)")
            // ONE addNotification call, not two: Notification.USER_MESSAGE is a shared/generic id
            // (reused across the app for ad-hoc one-off messages, incl. the auto-config banner
            // above) and NotificationStore.add() de-dupes by id but keeps the FIRST object on a
            // same-id collision within the same store update — two calls back-to-back here would
            // silently drop the second (see the same dedup behavior noted for
            // Notification.BOOST_V5_PERIODIC_REVIEW above). Combine into one message instead.
            val toastParts = buildList {
                if (applied > 0) add(rh.gs(R.string.boost_v5_periodic_review_applied_toast, applied))
                if (skippedStale > 0) add(rh.gs(R.string.boost_v5_periodic_review_skipped_stale_toast, skippedStale))
            }
            if (toastParts.isNotEmpty()) {
                uiInteraction.addNotification(Notification.USER_MESSAGE, toastParts.joinToString(" "), Notification.INFO)
            }
        }

        MaterialAlertDialogBuilder(context, app.aaps.core.ui.R.style.DialogTheme)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, rh.gs(R.string.boost_v5_periodic_review_dialog_title)))
            .setView(scrollableContent)
            .setPositiveButton(rh.gs(R.string.boost_v5_periodic_review_apply_selected)) { dialog, _ ->
                applySelections(
                    doubleCheckBoxes.indices.filter { doubleCheckBoxes[it].isChecked },
                    booleanCheckBoxes.indices.filter { booleanCheckBoxes[it].isChecked }
                )
                dialog.dismiss()
            }
            .setNegativeButton(rh.gs(R.string.boost_v5_periodic_review_discard)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Undo safety net (2026-08-27, user request: "was, wenn ich mich bei der Übernahme komplett
     * verschätzt habe?") — lets the user step the managed knobs back to their state from
     * immediately before the last (or 2nd-last) automatic apply, one-shot AutoConfig or Periodic
     * Review alike. See [BoostV5AutoConfigBackup] for the pure push/parse/consume logic and why
     * 2 slots, not 1 (a missed 14-day review cycle can mean two applies land unseen).
     *
     * Deliberately a plain tap-to-restore list (no extra confirm step): with only ever ≤2 entries,
     * each labelled with its own date/trigger/changed-values, the label itself IS the confirmation
     * — an extra dialog-on-dialog would only add friction to what's meant to be a quick undo.
     */
    private fun showRestoreBackupDialog(context: Context) {
        val snapshots = BoostV5AutoConfigBackup.parseSnapshots(preferences.get(StringNonKey.ApsBoostV5AutoConfigBackup))
        if (snapshots.isEmpty()) {
            // Toast, not addNotification (2026-08-27, user feedback: "dann weiss der user direkt
            // bescheid" — same pattern as HealthConnectPermissionActivity): addNotification surfaces
            // on the Overview tab, invisible from here in Settings where the tap just happened —
            // looked like the button did nothing. A Toast shows immediately, right on this screen.
            Toast.makeText(context, rh.gs(R.string.boost_v5_restore_backup_none), Toast.LENGTH_LONG).show()
            return
        }
        fun triggerLabel(t: String) = if (t == "autoConfig") rh.gs(R.string.boost_v5_restore_backup_trigger_autoconfig) else rh.gs(R.string.boost_v5_restore_backup_trigger_periodic_review)
        val labels = snapshots.map { snap ->
            val changes = (snap.doubles.map { "${shortName(it.key)}=${it.value}" } + snap.booleans.map { "${shortName(it.key)}=${it.value}" })
                .joinToString(", ")
            "${dateUtil.dateAndTimeString(snap.atMs)} (${triggerLabel(snap.trigger)})\n$changes"
        }.toTypedArray()

        MaterialAlertDialogBuilder(context, app.aaps.core.ui.R.style.DialogTheme)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, rh.gs(R.string.boost_v5_restore_backup_dialog_title)))
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                showRestoreItemsDialog(context, snapshots[which])
            }
            .setNegativeButton(app.aaps.core.ui.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /**
     * Step 2 of the restore flow (2026-08-27, user request: "immer nur gewissen teile" — per-knob
     * selection, not all-or-nothing) — same checkbox-row style as [showPeriodicReviewDialog]: every
     * changed knob from [snap] gets its own pre-checked box showing current → restore-to (same
     * "$current → $suggested" convention as the review dialog, no new formatting). Only checked
     * knobs are written back.
     *
     * The snapshot to consume is re-located by (atMs, trigger) in the CURRENT blob at apply-time,
     * not by the index captured when the picker opened — a background auto-apply could in theory
     * push a new entry while this dialog is open, shifting indices. If it's no longer found (already
     * consumed some other way), the selected values are still applied — restoring settings is safe
     * to do twice — just nothing is removed from the (now-different) backup list.
     */
    private fun showRestoreItemsDialog(context: Context, snap: BoostV5AutoConfigBackup.Snapshot) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun row(checkBox: CheckBox, name: String, valueText: String) {
            val titleView = TextView(context).apply {
                text = name
                setTypeface(typeface, Typeface.BOLD)
            }
            val valueView = TextView(context).apply {
                text = valueText
                textSize = 13f
                alpha = 0.7f
                setPadding(0, dp(2), 0, 0)
            }
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleView)
                addView(valueView)
            }
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(10), 0, dp(10))
                addView(checkBox)
                addView(textColumn)
            }
            container.addView(rowLayout)
        }

        val doubleKeys = snap.doubles.keys.toList()
        val doubleCheckBoxes = doubleKeys.map { key ->
            val checkBox = CheckBox(context).apply { isChecked = true }
            val restoreTo = snap.doubles.getValue(key)
            val current = preferences.getIfExists(key) ?: key.defaultValue
            row(checkBox, shortName(key), "$current → $restoreTo")
            checkBox
        }
        val booleanKeys = snap.booleans.keys.toList()
        val booleanCheckBoxes = booleanKeys.map { key ->
            val checkBox = CheckBox(context).apply { isChecked = true }
            val restoreTo = snap.booleans.getValue(key)
            val current = preferences.getIfExists(key) ?: key.defaultValue
            row(checkBox, shortName(key), "${if (current) "ON" else "OFF"} → ${if (restoreTo) "ON" else "OFF"}")
            checkBox
        }

        val scrollableContent = ScrollView(context).apply { addView(container) }

        MaterialAlertDialogBuilder(context, app.aaps.core.ui.R.style.DialogTheme)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, rh.gs(R.string.boost_v5_restore_backup_items_dialog_title)))
            .setView(scrollableContent)
            .setPositiveButton(rh.gs(R.string.boost_v5_periodic_review_apply_selected)) { dialog, _ ->
                var applied = 0
                doubleKeys.forEachIndexed { i, key ->
                    if (doubleCheckBoxes[i].isChecked) {
                        preferences.put(key, snap.doubles.getValue(key))
                        applied++
                    }
                }
                booleanKeys.forEachIndexed { i, key ->
                    if (booleanCheckBoxes[i].isChecked) {
                        preferences.put(key, snap.booleans.getValue(key))
                        applied++
                    }
                }
                // Only consume the backup slot (and toast success) if something was actually
                // restored — everything unchecked + "Apply selected" must behave like Cancel, not
                // silently burn one of only 2 precious backup slots for zero effect (found in
                // review: the original version consumed unconditionally here).
                if (applied > 0) {
                    val currentBlob = preferences.get(StringNonKey.ApsBoostV5AutoConfigBackup)
                    val currentIndex = BoostV5AutoConfigBackup.parseSnapshots(currentBlob).indexOfFirst { it.atMs == snap.atMs && it.trigger == snap.trigger }
                    if (currentIndex >= 0) {
                        preferences.put(StringNonKey.ApsBoostV5AutoConfigBackup, BoostV5AutoConfigBackup.consume(currentBlob, currentIndex))
                    }
                    aapsLogger.info(LTag.APS, "BoostV5 auto-config: restored $applied item(s) from backup ${dateUtil.dateAndTimeString(snap.atMs)} (${snap.trigger})")
                    // Toast, not addNotification — same reasoning as the empty-backup message above:
                    // this is a direct result of a Settings-screen tap, immediate feedback belongs
                    // right here, not on the Overview tab.
                    Toast.makeText(context, rh.gs(R.string.boost_v5_restore_backup_done_toast, dateUtil.dateAndTimeString(snap.atMs)), Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(app.aaps.core.ui.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // Enable/show under the same condition as plain Boost (temp-basal-capable pump) — delegate to
    // the engine so the two plugins stay in lock-step.
    override fun specialEnableCondition(): Boolean = openAPSBoostEngine.get().specialEnableCondition()
    override fun specialShowInListCondition(): Boolean = openAPSBoostEngine.get().specialShowInListCondition()

    /**
     * V5 decision runner. Called by the live V1 engine (`OpenAPSBoostPlugin.runEngine`) with the
     * inputs and result the engine just produced — V5 sees exactly what V1 saw, no duplication of
     * input gathering, no input drift. Runs in BOTH modes: `activeMode=false` (plain Boost selected;
     * decision is telemetry-only) and `activeMode=true` (V6 selected; the engine may adopt
     * `finalDose` as the SMB, subject to its own gates). (KDoc updated 2026-07-02 — previously
     * described the retired V4.4.1 sidecar arrangement.)
     *
     * V5 reads:
     *  - the engine's RT for `eventualBG`, `insulinReq` (used as `baseInsulinReq`), `mlHypoRisk`,
     *    `mlMealLikely` — the V1 engine already ran the ML predictions.
     *  - GlucoseStatus for `delta`, `shortAvgDelta`, `longAvgDelta`, `glucose`.
     *  - OapsProfileBoost for `target_bg`, `boost_maxIOB`, `recentLowBG`, `lgsThreshold`,
     *    and the `v5_*` activity fields V4.4.1 fills (exerciseActive, inPostExerciseWindow).
     *
     * V5 computes:
     *  - `delta_accl` from delta + shortAvgDelta with V3's denominator floor.
     *  - The 3-cycle deltaHistory from longAvgDelta / shortAvgDelta / delta.
     *  - Its own meal_signal_score, MealHypothesis transition, AggressionBudget, action
     *    multiplier, Phase 3 gates via [determineBasalBoostV5.decide].
     *
     * Output: V5 RT JSON logged via aapsLogger at INFO with prefix "BoostV5_RT:" and the
     * boostV5_* RT fields for NS — comparing V5 decisions against V1's delivery.
     *
     * Safety: any exception is caught and logged; V5 never propagates an error to the engine
     * (the engine falls back to its own dose).
     */
    fun runShadow(
        rT: RT,
        glucoseStatus: GlucoseStatus,
        iobArray: Array<IobTotal>,
        oapsProfile: OapsProfileBoost,
        pumpBolusStep: Double,
        activeMode: Boolean = false,
        microBolusAllowed: Boolean = true,
        flatBGsDetected: Boolean = false,
        asleep: Boolean = false,
        // 2026-07-06: post-rescue window flag (recentLowBG45Min < 75), computed by the engine at
        // the override seam. Gates the composed-floor SHADOW off; no dosing-path use in V5.
        postRescueWindow: Boolean = false,
    ): V5Decision? {
        return try {
            // Konzept 7 (2026-08-26) — periodic review note, if maybePeriodicReview() computed a
            // fresh review THIS cycle (rare, ~once/14d). Threaded through here (not written directly
            // from maybePeriodicReview, which runs in invoke() BEFORE this cycle's rT exists) so it
            // still lands in Nightscout (it.reason) and the in-app Script-debug screen
            // (consoleError = APSResult.scriptDebug), same visibility as every other shadow note.
            // Consumed exactly once — cleared immediately so it never repeats on a later cycle.
            pendingPeriodicReviewNsNote?.let { note ->
                rT.reason.append(note)
                rT.consoleError?.add(note.trimEnd(' ', ';'))
                pendingPeriodicReviewNsNote = null
            }
            val priorState = stateStore.load()
            val inputs = buildInputs(rT, glucoseStatus, iobArray, oapsProfile, pumpBolusStep, activeMode, microBolusAllowed, flatBGsDetected, asleep, postRescueWindow)
            val decision = determineBasalBoostV5.decide(inputs, priorState)
            stateStore.save(decision.newPersistedState)

            // Mutate V4.4.1's rT to attach V5 fields. The same rT instance is referenced by
            // V4.4.1's DetermineBasalResult.result and gets serialised via RT.serialize() when
            // LoopPlugin uploads NS deviceStatus — V5's fields ride along automatically.
            // V5's runShadow runs BEFORE V4.4.1 fires EventAPSCalculationFinished so any
            // listener sees the populated rT.
            rT.boostV5_score = decision.score
            rT.boostV5_state = decision.mealHypothesis.name
            rT.boostV5_age = decision.mealHypothesisAge
            rT.boostV5_budget = decision.aggressionBudget.budget
            rT.boostV5_actionMult = decision.actionMultiplier
            rT.boostV5_finalDose = decision.finalDose
            // Dose-chain intermediates (2026-07-10) so an offline port can be fidelity-validated
            // stage-by-stage: raw(budget×actionMult) → doseAfterCaps → doseAfterBrakes → finalDose.
            rT.boostV5_velocityFactor = decision.velocityFactor
            rT.boostV5_doseAfterCaps = decision.insulinToDeliver
            rT.boostV5_doseAfterBrakes = decision.phase3.finalDose
            rT.boostV5_gateReduction = formatGateReduction(decision)
            rT.boostV5_active = activeMode   // true => V5 is the selected/active doser (drives the V5 overview/widget)
            // Log the live per-user dose caps so the OBSERVING→CONFIRMED gate can be backtested against
            // REAL caps (not inferred/auto-formula estimates) and manual overrides are captured. (2026-07-02)
            rT.boostV5_committedCap = preferences.getBoostDosing(DoubleKey.ApsBoostV5CommittedCapU)
            rT.boostV5_confirmedCap = preferences.getBoostDosing(DoubleKey.ApsBoostV5ConfirmedCapU)
            // 2026-07-03 confirm-gate telemetry for the 2026-07-10 live gate review — a dose-adequacy
            // gate block was previously indistinguishable from a score fade in NS. Read-only.
            rT.boostV5_confirmGate = decision.confirmGate
            rT.boostV5_prospectiveShot = decision.prospectiveConfirmShot
            rT.boostV5_aggressionKnob = aggressionKnob
            // 2026-07-06 composed floor — DUAL semantics keyed on the Advanced toggle (see
            // composedFloorTargetDose + V5Decision.floorWouldAdd KDocs): toggle OFF = SHADOW,
            // extra U the Phase-3 floor (F=0.25) WOULD have added this cycle; toggle ON (per-user
            // activation) = the uplift actually APPLIED to finalDose. Null = floor conditions
            // unmet either way, so the 07-10 review reads one field regardless.
            rT.boostV5_floorWouldAdd = decision.floorWouldAdd
            // 2026-07-17 velocity-budget floor — same DUAL would/applied semantics keyed on the
            // ApsBoostV5VelocityBudgetActive toggle (see velocityBudgetFloorTarget). Null = conditions unmet.
            rT.boostV5_velocityBudgetWouldAdd = decision.velocityBudgetWouldAdd

            val rtJson = v5DecisionToRtJson(decision)
            aapsLogger.info(LTag.APS, "BoostV5_RT: ${rtJson} actual_smb=${rT.units ?: 0.0} actual_insulinReq=${rT.insulinReq ?: 0.0} activeMode=$activeMode")
            decision
        } catch (e: Throwable) {
            // Never let V5 break V4.4.1. Log and continue. Null → caller leaves V1's dose intact.
            aapsLogger.error(LTag.APS, "BoostV5 shadow error", e)
            null
        }
    }

    /**
     * Short-horizon minGuardBG for V5's hard safety gate (2026-05-15 fix).
     *
     * V4.4.x's `rT.minGuardBG` is `min()` taken over the full 4-hour prediction horizon. The
     * IOB-only forecast tail regularly dips to absurd lows (39 mg/dL is common) even when the
     * next 30 minutes is fine — reading this value caused V5's `HARD:min_guard_bg` to fire on
     * **50.4%** of cycles in the shadow window 2026-05-07 → 2026-05-15 (per
     * `boost_2026-05-14_evening_excursion.md` and the 5-fixes review).
     *
     * The hard gate is supposed to mean "imminent hypo, do not dose". 30 minutes is the
     * appropriate window — a basal cutoff issued now can plausibly prevent a hypo 30 min out;
     * the 4h-tail forecast is not actionable.
     *
     * Returns the min over the next 30 min (6 prediction points) of all available prediction
     * series, or null if no prediction array is available (caller falls back to V4.4.x's
     * `rT.minGuardBG`, then to current BG).
     */
    private fun shortHorizonMinGuard(rT: RT): Double? {
        val pred = rT.predBGs ?: return null
        val series = listOfNotNull(pred.IOB, pred.UAM, pred.ZT, pred.COB)
        if (series.isEmpty()) return null
        // Take min over the first 6 points (30 min at 5-min cycles) of every series, then
        // take the min across all series. Returns the worst-case 30-min-horizon prediction.
        val mins = series.mapNotNull { it.take(6).minOrNull()?.toDouble() }
        return mins.minOrNull()
    }

    /** Delegates to the single shared formatter (V5StateStore.kt) — the same string goes to the
     *  rT field, the log line, and v5DecisionToRtJson, so the three can never diverge. (2026-07-02) */
    private fun formatGateReduction(decision: V5Decision): String = formatGateReduction(decision.phase3.reductions)

    private fun buildInputs(
        rT: RT,
        gs: GlucoseStatus,
        iobArray: Array<IobTotal>,
        opb: OapsProfileBoost,
        pumpBolusStep: Double,
        activeMode: Boolean,
        microBolusAllowed: Boolean,
        flatBGsDetected: Boolean,
        asleep: Boolean,
        postRescueWindow: Boolean,
    ): V5Inputs {
        // delta_accl with V3's denominator floor — `max(|shortAvgDelta|, 2.0)` — carried over
        // verbatim from V3 input preprocessing.
        val deltaAccl = 100.0 * (gs.delta - gs.shortAvgDelta) / max(abs(gs.shortAvgDelta), 2.0)

        // 3-cycle delta history from glucose status. longAvgDelta is a longer-window average
        // so it's a reasonable proxy for "two cycles ago"; combined with shortAvgDelta and
        // current delta, deltaDeclining can reliably check the 2-cycle decline pattern.
        val deltaHistory = listOf(gs.longAvgDelta, gs.shortAvgDelta, gs.delta)

        // Fix 4 (2026-05-22): cumulative rise over ~30 min for slow-meal detection. shortAvgDelta
        // is per-5-min-cycle averaged over the 2.5–17.5 min lookback window (DeltaCalculator).
        // Multiplying by 6 projects 30 min of accumulated rise at the current cycle's rate.
        // Clamped non-negative — falling BG produces no sustained-rise signal.
        val cumulativeRise30min = max(0.0, gs.shortAvgDelta * 6.0)

        // baseInsulinReq directly from V4.4.1's computed value. V4.4.1 used the Boost-flavoured
        // formula `(min(minPredBG, eventualBG) - target_bg) / future_sens` with DynISF +
        // 7D-only TDD + EMA sensitivity all baked in. V5 trusts this number.
        val baseInsulinReq = (rT.insulinReq ?: 0.0).coerceAtLeast(0.0)

        val iob = iobArray.firstOrNull()?.iob ?: 0.0

        // Hour from dateUtil (not wall-clock LocalTime.now()) so tests/replays can fake time like
        // every other time read in this class. (2026-07-02)
        val hour = java.time.Instant.ofEpochMilli(dateUtil.now()).atZone(ZoneId.systemDefault()).hour

        // V0 SHADOW MODE: enableSmbPreChecks is permissive — V5 makes its own decision and the
        // operator compares against V4.4.1's actual delivery. Earlier code derived this from
        // `(units > 0) OR (insulinReq <= 0)`, but that returns false when V4.4.1 had a small
        // insulinReq that rounded to units=0 (e.g. insulinReq=0.01U with roundSMBTo=0.05). In
        // shadow we want V5's decision visible regardless. V5's other hard gates (minGuardBg
        // via rT.minGuardBG, maxIOB clamp, maxDelta) already cover safety. When V5 graduates
        // to alpha (active APS), this becomes a real V5-side enableSMB check.
        // ACTIVE-DOSING ALPHA (2026-06-11): gate on V1's real SMB permission (microBolusAllowed) so
        // V5 can only dose on cycles V1 itself permits an SMB — V1 is the outer safety envelope.
        // Shadow mode (activeMode=false) keeps the permissive value so shadow telemetry is unchanged.
        val enableSmbPreChecks = if (activeMode) microBolusAllowed else true

        return V5Inputs(
            delta = gs.delta,
            shortAvgDelta = gs.shortAvgDelta,
            deltaAccl = deltaAccl,
            bg = gs.glucose,
            eventualBg = rT.eventualBG ?: gs.glucose,
            targetBg = opb.target_bg,
            maxDelta = abs(gs.delta),
            // minGuardBg: V4.4.1's smart-selected predicted-low (COB/UAM/IOB-blended per the rules
            // at DetermineBasalBoostV3MLG3.kt:799-808). Reading rT.minGuardBG directly avoids the
            // bug from a previous attempt that did `min(predBGs.IOB+UAM+ZT)` over the full prediction
            // horizon — that picked up the IOB-only forecast tail (e.g. 39 mg/dL) and fired the V5
            // hard gate every cycle even when V4.4.1's own minGuardBG was 92 mg/dL (well above 80).
            minGuardBg = shortHorizonMinGuard(rT) ?: rT.minGuardBG ?: gs.glucose,
            minGuardThreshold = opb.lgsThreshold?.toDouble() ?: 80.0,
            deltaHistory = deltaHistory,
            iob = iob,
            // Hard-ceiling V5's IOB headroom to the system/oref maxIOB (opb.max_iob, already
            // constraint-applied) so a higher ApsBoostMaxIob can never let V5 exceed the IOB limit
            // V1 enforces. (Review 2026-06-26, LOW — defense-in-depth.)
            maxIob = minOf(opb.boost_maxIOB, opb.max_iob),
            baseInsulinReq = baseInsulinReq,
            roundSmbTo = pumpBolusStep,
            enableSmbPreChecks = enableSmbPreChecks,
            mlHypoRisk = rT.mlHypoRisk,
            mlMealLikely = rT.mlMealLikely,
            // 2026-07-02: postActionRiskCheck LIVE. Re-scores the (singleton) risk model at
            // projected IOB using this cycle's cached feature vector. Previously null since
            // V0-shadow — but in ACTIVE mode V5 replaces rT.units AFTER V1's postSmbScale ran,
            // so the delivered dose had neither damper. Null/unavailable → current risk
            // (gate passes through unchanged).
            riskAtProjectedIob = { projIob -> boostRiskModel.predictAtProjectedIob(projIob) ?: (rT.mlHypoRisk ?: 0.0) },
            recentLowBg = opb.recentLowBG,
            cumulativeRise30min = cumulativeRise30min,
            hour = hour,
            exerciseActive = opb.v5_exerciseActive,
            inPostExerciseWindow = opb.v5_inPostExerciseWindow,
            asleep = asleep,
            // 2026-07-06 composed-floor inputs (see V5Inputs KDocs):
            postRescueWindow = postRescueWindow,
            // rT.units here is V1's dose — runShadow runs before the engine's V6 override seam.
            v1WouldDoseU = rT.units,
            // 2026-07 composed brake-floor ACTIVATION — Advanced toggle, default OFF. Gated on:
            // (1) activeMode, so the floor only ever alters finalDose when V6 is the selected doser
            //     (in shadow mode the field keeps its pre-activation would-add semantics regardless);
            // (2) 2026-07-08 ENFORCED hypo-gate — trailing-14d time-below-63 mg/dL < 2.0% (fail-closed).
            //     The floor is insulin-adding, so it cannot engage for a hypo-prone user even if toggled on.
            composedFloorActive = activeMode &&
                preferences.getBoostDosing(BooleanKey.ApsBoostV5ComposedFloorActive) &&
                composedFloorTbrAllowed(dateUtil.now()),
            // 2026-07-17 velocity-budget floor (budget≈0 high tail) — same three-part activation as the
            // composed floor: V6 active, per-user Advanced toggle ON, and the SAME fail-closed 14d-TBR
            // gate (the floor is insulin-adding, so it never engages for a hypo-prone user).
            velocityBudgetActive = activeMode &&
                preferences.getBoostDosing(BooleanKey.ApsBoostV5VelocityBudgetActive) &&
                composedFloorTbrAllowed(dateUtil.now()),
            fastCarbConfirmEnabled = preferences.getBoostDosing(BooleanKey.ApsBoostV5FastCarbConfirm),
            // 2026-07-17 aggressive early-confirm — opt-in + auto-config managed (age −2). Read raw
            // (mask-bypassed) like the other dosing toggles; applies in BOTH shadow and active modes
            // (it changes the state transition, not the delivered dose directly).
            aggressiveEarlyConfirmEnabled = preferences.getBoostDosing(BooleanKey.ApsBoostV5AggressiveEarlyConfirm),
            sensorQualityOk = if (activeMode) !flatBGsDetected else true,
            profileSwitched = false,           // deferred reset trigger (microBolusAllowed gates actual dosing)
            pumpDisconnected = false,
            loopSuspended = false,
            timeJumpMinutes = 0.0,
            aggressionUserKnob = aggressionKnob,
            hypoCautionUserKnob = hypoCautionKnob,
            sensitivityUserKnob = sensitivityKnob,
            confirmedCapU = preferences.getBoostDosing(DoubleKey.ApsBoostV5ConfirmedCapU),
            committedCapU = preferences.getBoostDosing(DoubleKey.ApsBoostV5CommittedCapU),
            // 2026-07-20 V1-acceleration primer (LIVE). Mode = auto-config's temp-basal routing UNLESS
            // the user override (ApsBoostV5PrimerBolusMode) forces the bolus. Only active while V6 doses.
            primerCapU = if (activeMode) preferences.getBoostDosing(DoubleKey.ApsBoostV5PrimerCapU) else 0.0,
            primerUseTempBasal = preferences.getBoostDosing(BooleanKey.ApsBoostV5PrimerTbrFallback) &&
                !preferences.getBoostDosing(BooleanKey.ApsBoostV5PrimerBolusMode),
            nowMs = dateUtil.now(),   // 2026-07-21 wall-clock for the primer-IOB accumulator decay
        )
    }

    // When "Boost V5" is the active APS, GlucoseStatusProviderImpl + overview/Wear/Android Auto/
    // lockscreen widgets call these on activeAPS. V5 owns no glucose/ISF logic — delegate to the
    // engine so they behave identically to plain "Boost". (Returning null/the interface default
    // here broke those integrations: they showed stale data with only raw glucose. 2026-06-15.)
    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        openAPSBoostEngine.get().getGlucoseStatusData(allowOldData)

    override fun supportsDynamicIsf(): Boolean = openAPSBoostEngine.get().supportsDynamicIsf()
    override fun getIsfMgdl(profile: Profile, caller: String): Double? = openAPSBoostEngine.get().getIsfMgdl(profile, caller)
    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? = openAPSBoostEngine.get().getAverageIsfMgdl(timestamp, caller)
    override fun getSensitivityOverviewString(): String? = openAPSBoostEngine.get().getSensitivityOverviewString()

    // ── Delegated to the engine ─────────────────────────────────────────────────────────────
    // When "Boost V5" is the active APS, V1 (the engine) is DISABLED, so the constraints framework
    // (ConstraintsCheckerImpl: `if (!p.isEnabled()) continue`) skips V1 entirely and only polls V5.
    // V5 owns no constraint/config/lifecycle logic, so it must forward all of it to the engine —
    // otherwise Boost's night-mode SMB gate, UAM/autosens limits, maxIOB/maxBasal caps, the
    // post-calibration SMB block, and settings export would silently vanish under V5. (2026-06-15.)

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> = openAPSBoostEngine.get().isSMBModeEnabled(value)
    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> = openAPSBoostEngine.get().isUAMEnabled(value)
    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> = openAPSBoostEngine.get().isAutosensModeEnabled(value)
    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> = openAPSBoostEngine.get().isSuperBolusEnabled(value)
    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> = openAPSBoostEngine.get().applyMaxIOBConstraints(maxIob)
    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> = openAPSBoostEngine.get().applyBasalConstraints(absoluteRate, profile)

    // Lifecycle: forward so the engine's EventCalibrationDetected subscription (post-calibration
    // SMB block) is live whenever Boost V5 is the running plugin.
    override fun onStart() { super.onStart(); openAPSBoostEngine.get().onStart() }
    override fun onStop() { openAPSBoostEngine.get().onStop(); super.onStop() }

    override fun configuration(): JSONObject = openAPSBoostEngine.get().configuration()

    override fun applyConfiguration(configuration: JSONObject) = openAPSBoostEngine.get().applyConfiguration(configuration)

    // Dynamic pref visibility (SMB-with-COB/LowTt/AfterCarbs) — same shared SMB-safety switches
    // appear on V5's screen, so run the engine's logic against V5's fragment.
    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) =
        openAPSBoostEngine.get().preprocessPreferences(preferenceFragment)

    /** V5's three HEADLINE tuning knobs (advanced settings — caps, fast-carb, pre-meal — live on
     *  the preference screen; these three are the per-user calibration surface). */
    val aggressionKnob: Double get() = preferences.getBoostDosing(DoubleKey.ApsBoostV5Aggression)
    val hypoCautionKnob: Double get() = preferences.getBoostDosing(DoubleKey.ApsBoostV5HypoCaution)

    /**
     * Sensitivity knob ∈ [0.8, 1.2] — per-user calibration multiplier on the aggression budget.
     * Wired into [aggressionBudget] as of V6 (2026-06-15): the 4-user shadow backtest showed V5
     * runs hot for some users (User D over-dosed before lows), so a live <1.0 trim (or >1.0 for
     * resistant users) is warranted. This is the lever a future nightly per-user learner will
     * drive (loop deferred — see boost_v6_delivery_plan Phase 3). Default 1.0 = no change.
     */
    val sensitivityKnob: Double get() = preferences.getBoostDosing(DoubleKey.ApsBoostV5Sensitivity)

    // Preference sub-screens this plugin may (re)build — the V5 root, the Advanced parent, and the
    // shared engine sub-screens (they live nested under "Advanced" and are rebuilt by key when
    // navigated into). Must stay in lock-step with the keys used in OpenAPSBoostPlugin's screens.
    private val prefScreenKeys = setOf(
        "openapsboostv5_settings", "boost_advanced_settings", "absorption_smb_advanced",
        "boost_default_aaps_settings", "boost_dynisf_settings", "boost_floor_slew_shadow_settings",
        "boost_peak_shadow_settings", "boost_exercise_settings",
        "boost_stepcount_settings", "boost_hr_integration_settings",
        "boost_post_exercise_recovery_settings", "boost_hc_settings", "boost_meal_alcohol_buttons_settings",
        "boost_night_mode_settings", "boost_safety_settings",
    )

    override fun addPreferenceScreen(
        preferenceManager: PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?,
    ) {
        if (requiredKey != null && requiredKey !in prefScreenKeys) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "openapsboostv5_settings"
            title = rh.gs(R.string.openaps_boost_v5)
            initialExpandedChildrenCount = 0
        }
        // ── Essentials (top level) — the handful most users touch. Aggression/HypoCaution are
        //    auto-seeded by auto-config; everything else is auto-configured or learned and lives
        //    under "Advanced" below. ──
        category.addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostV5Aggression, dialogMessage = R.string.boost_v5_aggression_summary, title = R.string.boost_v5_aggression_title))
        category.addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostV5HypoCaution, dialogMessage = R.string.boost_v5_hypo_caution_summary, title = R.string.boost_v5_hypo_caution_title))
        category.addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostMaxIob, dialogMessage = R.string.boost_max_iob_summary, title = R.string.boost_max_iob_title))
        category.addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
        category.addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostNightModeEnabled, summary = R.string.boost_night_mode_enabled_summary, title = R.string.boost_night_mode_enabled_title))
        category.addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsBoostNightModeBgOffset, dialogMessage = R.string.boost_night_mode_bg_offset_summary, title = R.string.boost_night_mode_bg_offset_title))
        category.addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb, summary = R.string.enable_smb_summary, title = R.string.enable_smb))
        category.addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseUam, summary = R.string.enable_uam_summary, title = R.string.enable_uam))

        // ── Advanced — advanced V6 dosing knobs + the full grouped-by-function engine tree.
        //    Build it fully BEFORE attaching to the category. ──
        val advanced = preferenceManager.createPreferenceScreen(context).apply {
            key = "boost_advanced_settings"
            title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostV5Sensitivity, dialogMessage = R.string.boost_v5_sensitivity_summary, title = R.string.boost_v5_sensitivity_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostV5ConfirmedCapU, dialogMessage = R.string.boost_v5_confirmed_cap_summary, title = R.string.boost_v5_confirmed_cap_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostV5CommittedCapU, dialogMessage = R.string.boost_v5_committed_cap_summary, title = R.string.boost_v5_committed_cap_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostV5FastCarbConfirm, summary = R.string.boost_v5_fast_carb_confirm_summary, title = R.string.boost_v5_fast_carb_confirm_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostV5AggressiveEarlyConfirm, summary = R.string.boost_v5_aggressive_early_confirm_summary, title = R.string.boost_v5_aggressive_early_confirm_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostV5ComposedFloorActive, summary = R.string.boost_v5_composed_floor_summary, title = R.string.boost_v5_composed_floor_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostV5VelocityBudgetActive, summary = R.string.boost_v5_velocity_budget_summary, title = R.string.boost_v5_velocity_budget_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostV5PrimerCapU, dialogMessage = R.string.boost_v5_primer_cap_summary, title = R.string.boost_v5_primer_cap_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostV5PrimerBolusMode, summary = R.string.boost_v5_primer_bolus_mode_summary, title = R.string.boost_v5_primer_bolus_mode_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostV6PreMealTarget, summary = R.string.boost_v6_pre_meal_target_summary, title = R.string.boost_v6_pre_meal_target_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostV6PreMealTargetMgdl, dialogMessage = R.string.boost_v6_pre_meal_target_mgdl_summary, title = R.string.boost_v6_pre_meal_target_mgdl_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostV6PreMealLeadMin, dialogMessage = R.string.boost_v6_pre_meal_lead_min_summary, title = R.string.boost_v6_pre_meal_lead_min_title))
            // Meal/Alcohol confirmation buttons (Konzept 6, 2026-08-24) — placed directly next to the
            // V6 pre-meal settings they're the manual trigger for (not buried under Exercise
            // Settings), grouped into their own sub-screen (not bare switches) so the two long
            // descriptions don't bloat the Advanced list — best of both: right place, tidy list.
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "boost_meal_alcohol_buttons_settings"
                title = rh.gs(R.string.boost_meal_alcohol_buttons_title)
                summary = rh.gs(R.string.boost_meal_alcohol_buttons_summary)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostShowMealButton, summary = R.string.boost_show_meal_button_summary, title = R.string.boost_show_meal_button_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsBoostShowAlcoholButton, summary = R.string.boost_show_alcohol_button_summary, title = R.string.boost_show_alcohol_button_title))
                // Alcohol damping calibration (2026-08-26) — were hardcoded constants in
                // AlcoholShadow.kt, now user-adjustable. Defaults reproduce prior behaviour exactly.
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostAlcoholLightMultiplier, dialogMessage = R.string.boost_alcohol_light_multiplier_summary, title = R.string.boost_alcohol_light_multiplier_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostAlcoholModerateMultiplier, dialogMessage = R.string.boost_alcohol_moderate_multiplier_summary, title = R.string.boost_alcohol_moderate_multiplier_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostAlcoholHighMultiplier, dialogMessage = R.string.boost_alcohol_high_multiplier_summary, title = R.string.boost_alcohol_high_multiplier_title))
                addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsBoostAlcoholHyperBrakeThreshold, dialogMessage = R.string.boost_alcohol_hyper_brake_threshold_summary, title = R.string.boost_alcohol_hyper_brake_threshold_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsBoostAlcoholLowIobThreshold, dialogMessage = R.string.boost_alcohol_low_iob_threshold_summary, title = R.string.boost_alcohol_low_iob_threshold_title))
            })
        }
        // Shared engine settings nested under Advanced. includeEngineEssentials = false: the
        // 6 essentials above are NOT repeated inside the engine sub-screens (no duplicate keys).
        openAPSBoostEngine.get().addBoostEngineCategories(preferenceManager, advanced, context, includeEngineEssentials = false)
        // Undo safety net (2026-08-27, user request) — restore the managed knobs to their state
        // from immediately before the last (or 2nd-last) automatic AutoConfig/Periodic Review
        // apply. Added AFTER addBoostEngineCategories (2026-08-27, corrected — was previously
        // added before it, which left the whole shared-engine category tree rendered below it,
        // not "ganz ans Ende" as intended) so it's genuinely the last item in Advanced: a
        // maintenance-style action, not a tunable knob, belongs at the very bottom. See
        // BoostV5AutoConfigBackup.
        advanced.addPreference(androidx.preference.Preference(context).apply {
            key = "boost_v5_autoconfig_restore_backup"
            title = rh.gs(R.string.boost_v5_restore_backup_title)
            summary = rh.gs(R.string.boost_v5_restore_backup_summary)
            setOnPreferenceClickListener {
                showRestoreBackupDialog(context)
                true
            }
        })
        category.addPreference(advanced)
    }
}
