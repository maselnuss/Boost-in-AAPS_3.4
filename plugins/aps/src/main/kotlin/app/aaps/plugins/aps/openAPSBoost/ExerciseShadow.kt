package app.aaps.plugins.aps.openAPSBoost

import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * ExerciseShadow — Konzept 8 (2026-08-26), SHADOW ONLY. Renamed from CyclingShadow once scope
 * broadened from cycling-only to all Health-Connect-detected exercise types (user request).
 *
 * Pure decision logic for Health-Connect-detected exercise sessions of any type: (a) is a session
 * still worth reporting, and (b) for types we have real-world confidence about, which of the
 * existing post-exercise recovery mechanism's severity tiers it maps to — so the Shadow log can
 * state what that already-proven mechanism WOULD have done, had this signal been wired into it.
 * Never touches rT.units/rT.rate/TT itself — see OpenAPSBoostPlugin.kt's Shadow call site.
 */
object ExerciseShadow {

    /** A session still counts as "relevant" up to this many minutes after it ended — long enough to
     *  cover the typical delayed-onset exercise-hypo window (the real cycling incident this
     *  originally targeted), short enough that a session from yesterday never gets reported as if
     *  it just happened. */
    const val RELEVANT_AFTER_END_MIN = 90

    data class Session(val startMs: Long, val endMs: Long, val exerciseType: Int, val source: String)

    /** Is [session] still worth reporting at [nowMs]? True while still ongoing (endMs in the future,
     *  e.g. a record synced mid-session) or within [RELEVANT_AFTER_END_MIN] minutes after it ended. */
    fun isCurrentlyRelevant(session: Session, nowMs: Long): Boolean {
        if (nowMs < session.endMs) return true
        return nowMs - session.endMs <= RELEVANT_AFTER_END_MIN * 60_000L
    }

    // Severity-tier mapping (2026-08-26, user-confirmed in conversation) — sustained/continuous
    // cardio maps to the SAME tier as the real incident that motivated Konzept 8 (cycling). Racquet
    // sports are bursty/uncertain, deliberately mapped to the mechanism's own DEFAULT tier rather
    // than inventing new numbers for them. Walking/yoga/stretching/etc. are deliberately left
    // unmapped: walking is already covered by Boost's existing step-based detector (a dedicated
    // "walking detected" signal would add noise, not information), and low-intensity types don't
    // fit the delayed-exercise-hypo pattern this feature targets.
    private val SUSTAINED_CARDIO = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        // Hiking (2026-08-26, user correction): sustained elevation-change exertion over hours is
        // physiologically closer to cycling than to a normal walk — a watch labelling a session
        // "Hiking" specifically (vs. "Walking") is itself already a signal of something substantial.
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
    )
    private val STRENGTH = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP,
    )
    private val RACQUET = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON,
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH,
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS,
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL,
    )

    /** Which of OpenAPSBoostPlugin's post-exercise recovery tiers ("VIGOROUS_AEROBIC"/"RESISTANCE",
     *  matching its `when (lastExerciseStateAtTransition)` literals exactly) a detected type maps
     *  to. "MODERATE_AEROBIC" is returned for the racquet-sports group as a label only — that
     *  mechanism has no dedicated row for it, the caller must fall through to its own default/else
     *  values for that string, exactly as the real mechanism does for any non-VIGOROUS/RESISTANCE/
     *  LIGHT_AEROBIC state. Null = deliberately unmapped (see class doc) — log detection only, no
     *  hypothetical consequence computed. */
    fun severityTier(exerciseType: Int): String? = when (exerciseType) {
        in SUSTAINED_CARDIO -> "VIGOROUS_AEROBIC"
        in STRENGTH -> "RESISTANCE"
        in RACQUET -> "MODERATE_AEROBIC"
        else -> null
    }
}
