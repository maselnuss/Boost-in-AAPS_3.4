package app.aaps.plugins.main.general.overview.boost

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.graph.data.AreaGraphSeries
import app.aaps.core.graph.data.BarGraphSeries
import app.aaps.core.graph.data.BolusDataPoint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.DeviationDataPoint
import app.aaps.core.graph.data.DoubleDataPoint
import app.aaps.core.graph.data.EffectiveProfileSwitchDataPoint
import app.aaps.core.graph.data.FixedLineGraphSeries
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.LineGraphSeries
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.ScaledDataPoint
import app.aaps.core.graph.data.TimeAsXAxisLabelFormatter
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.Series
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

/**
 * Graph data class for the Boost Overview V2 dark-theme design.
 *
 * This replaces [app.aaps.plugins.main.general.overview.graphData.GraphData] for V2 graphs,
 * applying the dark-theme colour palette instead of the default AAPS theme colours.
 *
 * Colour reference (from the V2 HTML mockup):
 *  - In-range band fill:    Color.argb(12, 0, 212, 255)  — very subtle cyan tint
 *  - In-range upper border: cyan at 20 % opacity, dashed
 *  - In-range lower border: red  at 30 % opacity, dashed
 *  - Basal bars:            #fb923c at 35 % opacity (orange)
 *  - Now line:              white at 10 % opacity, dashed
 *  - Target line:           accent green #6ee7b7 at 50 % opacity
 *  - TT deviation zones:    green/red tint between profile target and active target
 *  - IOB line:              #60a5fa (blue)
 *  - IOB fill:              #60a5fa at 40 % opacity
 *  - Grid lines:            #1a1d28
 *  - Axis labels:           #aaaaaa (matches non-highlighted period buttons)
 */
@Suppress("UNCHECKED_CAST")
class BoostV2GraphData @Inject constructor(
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val rh: ResourceHelper
) {

    // ── V2 colour constants ──────────────────────────────────────────────
    companion object {
        /** Very subtle cyan tint for in-range band */
        val IN_RANGE_FILL = Color.argb(12, 0, 212, 255)

        /** Upper border — cyan at 20 % opacity */
        val IN_RANGE_UPPER_BORDER = Color.argb(51, 0, 212, 255)

        /** Lower border — red at 30 % opacity */
        val IN_RANGE_LOWER_BORDER = Color.argb(77, 255, 82, 82)

        /** Basal fill — orange #fb923c at 35 % */
        val BASAL_FILL = Color.argb(89, 251, 146, 60)

        /** Now line — white at 10 % opacity */
        val NOW_LINE = Color.argb(26, 255, 255, 255)

        /** Target line — V2 accent green #6ee7b7 at 50 % opacity.
         *  Hierarchy: the target is the most visible reference line, the range borders come
         *  next, and the now-line is the most subtle. Using a hue (not brighter white) keeps
         *  it distinguishable from the white now-line and grid by colour, not just dash. */
        val TARGET_LINE = Color.argb(128, 110, 231, 183)

        /** Temp-target deviation zone, target RAISED above profile — accent-green tint.
         *  Deliberately near IN_RANGE_FILL subtlety (alpha 12) — a tint, not a block. */
        val TT_RAISED_ZONE_FILL = Color.argb(20, 110, 231, 183)

        /** Temp-target deviation zone, target LOWERED below profile — red tint */
        val TT_LOWERED_ZONE_FILL = Color.argb(20, 255, 82, 82)

        /** IOB line colour — blue #60a5fa */
        val IOB_LINE = Color.parseColor("#60a5fa")

        /** IOB fill — blue #60a5fa at 40 % opacity */
        val IOB_FILL = Color.argb(102, 96, 165, 250)

        /** Graph background */
        val GRAPH_BG = Color.parseColor("#0a0c10")

        /** Grid colour */
        val GRID_COLOR = Color.parseColor("#1a1d28")

        /** Axis label colour — matches the non-highlighted period-selector buttons (#aaaaaa).
         *  applyV2Theme() re-applies this to the BG & IOB graphs on every refresh, so this
         *  constant (not the one-time onViewCreated setup) is the effective colour. */
        val LABEL_COLOR = Color.parseColor("#aaaaaa")

        /** 2026-08-28: gap threshold for [addHeartRateLine] — a break in HR readings (watch off
         *  wrist, Bluetooth drop) longer than this starts a new line segment instead of drawing a
         *  straight line across the gap. Normal Wear sampling is 1-5 min, so 15 min is comfortably
         *  above sampling jitter while still catching real gaps of an hour+ well before they'd
         *  otherwise get silently bridged. */
        // Double, not Long: compared directly against DataPoint.getX() (graph X coordinates are
        // epoch-millis as Double) — Kotlin has no implicit Double/Long comparison.
        const val HR_GAP_THRESHOLD_MS = 15 * 60_000.0

        /** 2026-08-28 (user request): V2 colours for the secondary-graph debug traces that were
         *  previously left on their theme-attribute defaults (`addAbsIob`/`addCob`/`addMinusBGI`/
         *  `addRatio`/`addVarSens`/`addDeviationSlope` — see the "Delegated methods" section KDoc).
         *  These series objects are rebuilt fresh every refresh by the SHARED
         *  `PrepareIobAutosensGraphDataWorker` (also feeds the stock Overview screen) — overriding
         *  `.color` here follows the same already-established pattern `addIob` above uses for the
         *  main IOB line, not a new risk. Colour choices extend the existing V2 hue family
         *  (cyan/orange/green/blue) rather than reusing IOB_LINE/BASAL_FILL outright, so each trace
         *  stays visually distinguishable when several are shown on the same row. Deviations
         *  deliberately excluded — its colour is per-bar (green/red by sign), assigned where the
         *  underlying `DeviationDataPoint`s are built, not overridable at the series level here. */
        val COB_LINE = Color.parseColor("#fbbf24")   // amber — carbs, distinct from BASAL_FILL's orange
        val COB_FILL = Color.argb(102, 251, 191, 36)  // amber, 40%
        val BGI_LINE = Color.parseColor("#f87171")    // soft red — BG-impact
        val ABS_IOB_LINE = Color.parseColor("#38bdf8") // light cyan-blue — distinct from IOB_LINE's darker blue
        val RATIO_LINE = Color.parseColor("#a78bfa")  // violet — sensitivity ratio
        val DEV_SLOPE_POS = Color.parseColor("#6ee7b7") // reuses TARGET_LINE's green — "accelerating up" reads as the same "good/reference" hue family
        val DEV_SLOPE_NEG = Color.parseColor("#ff5252") // reuses IN_RANGE_LOWER_BORDER's red — "accelerating down"
    }

    // ── Internal state (mirrors GraphData) ───────────────────────────────
    private var maxY = Double.MIN_VALUE
    private var minY = Double.MAX_VALUE
    private val units: GlucoseUnit get() = profileFunction.getUnits()
    private val series: MutableList<Series<*>> = ArrayList()

    /** 2026-08-28: series destined for `graph.secondScale` (currently: Steps bars) — kept separate
     *  from [series] because `GraphView.removeAllSeries()` does NOT clear `secondScale`'s own series
     *  list (verified by reading GraphView.java directly — it only iterates `mSeries`), so
     *  [performUpdate] must clear/re-add secondScale's series itself each refresh or old bars would
     *  pile up indefinitely. */
    private val secondScaleSeries: MutableList<Series<*>> = ArrayList()
    private var secondScaleMaxY = 1.0

    /** 2026-08-28: true once [addStepsBars] has run for this row (regardless of whether it found
     *  any data) — distinguishes "this row wants Steps, currently empty, still show an axis" from
     *  "this row never asked for Steps, leave secondScale untouched" (see [performUpdate]). */
    private var secondScaleRequested = false

    private lateinit var graph: GraphView
    private lateinit var overviewData: OverviewData

    fun with(graph: GraphView, overviewData: OverviewData): BoostV2GraphData = this.also {
        it.graph = graph
        it.overviewData = overviewData
    }

    // ── Delegated methods (unchanged logic, colours now V2-overridden 2026-08-28 — see the
    //    companion-object colour constants above; Deviations excluded, see its KDoc) ────────────

    fun addBucketedData() {
        addSeries(overviewData.bucketedGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addBgReadings(addPredictions: Boolean, context: Context?) {
        maxY = if (overviewData.bgReadingsArray.isEmpty()) {
            if (units == GlucoseUnit.MGDL) 180.0 else 10.0
        } else overviewData.maxBgValue
        minY = 0.0
        addSeries(overviewData.bgReadingGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        if (addPredictions) addSeries(overviewData.predictionsGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        (overviewData.bgReadingGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is GlucoseValueDataPoint) ToastUtils.infoToast(context, dataPoint.label)
        }
    }

    fun addRunningModes() {
        addSeries(overviewData.runningModesSeries as PointsWithLabelGraphSeries<DataPoint>)
    }

    fun addTreatments(context: Context?) {
        maxY = maxOf(maxY, overviewData.maxTreatmentsValue)
        addSeries(overviewData.treatmentsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        (overviewData.treatmentsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is BolusDataPoint) ToastUtils.infoToast(context, dataPoint.label)
        }
    }

    fun addEps(context: Context?, scale: Double) {
        addSeries(overviewData.epsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        (overviewData.epsSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).setOnDataPointTapListener { _, dataPoint ->
            if (dataPoint is EffectiveProfileSwitchDataPoint) ToastUtils.infoToast(context, dataPoint.data.originalCustomizedName)
        }
        overviewData.epsScale.multiplier = maxY * scale / overviewData.maxEpsValue
    }

    fun addTherapyEvents() {
        maxY = maxOf(maxY, overviewData.maxTherapyEventValue)
        addSeries(overviewData.therapyEventSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addActivity(scale: Double) {
        addSeries(overviewData.activitySeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.activityPredictionSeries as FixedLineGraphSeries<ScaledDataPoint>)
        overviewData.actScale.multiplier = maxY * scale / overviewData.maxIAValue
    }

    fun addMinusBGI(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxBGIValue
            minY = -overviewData.maxBGIValue
        }
        overviewData.bgiScale.multiplier = maxY * scale / overviewData.maxBGIValue
        // 2026-08-28: V2 colour override (user request) — see the BGI_LINE KDoc in the companion
        // object for why this is safe to mutate directly (same pattern as addIob's IOB_LINE above).
        (overviewData.minusBgiSeries as FixedLineGraphSeries<ScaledDataPoint>).color = BGI_LINE
        (overviewData.minusBgiHistSeries as FixedLineGraphSeries<ScaledDataPoint>).color = BGI_LINE
        addSeries(overviewData.minusBgiSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.minusBgiHistSeries as FixedLineGraphSeries<ScaledDataPoint>)
    }

    fun addAbsIob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxIobValueFound
            minY = -overviewData.maxIobValueFound
        }
        overviewData.iobScale.multiplier = maxY * scale / overviewData.maxIobValueFound
        (overviewData.absIobSeries as FixedLineGraphSeries<ScaledDataPoint>).color = ABS_IOB_LINE
        addSeries(overviewData.absIobSeries as FixedLineGraphSeries<ScaledDataPoint>)
    }

    fun addCob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxCobValueFound
            minY = -overviewData.maxCobValueFound
        }
        overviewData.cobScale.multiplier = maxY * scale / overviewData.maxCobValueFound
        (overviewData.cobSeries as FixedLineGraphSeries<ScaledDataPoint>).also {
            it.color = COB_LINE
            it.backgroundColor = COB_FILL
        }
        addSeries(overviewData.cobSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.cobMinFailOverSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addDeviations(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxDevValueFound
            minY = -maxY
        }
        overviewData.devScale.multiplier = maxY * scale / overviewData.maxDevValueFound
        // No V2 colour override here — deliberately. Deviations colours per-bar (green/red by sign)
        // via DeviationDataPoint.color, assigned where the points are built (PrepareIobAutosens-
        // GraphDataWorker), not something a single series-level `.color` override could replace.
        addSeries(overviewData.deviationsSeries as BarGraphSeries<DeviationDataPoint>)
    }

    fun addRatio(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = 100.0 + max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            minY = 100.0 - max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            overviewData.ratioScale.multiplier = 1.0
            overviewData.ratioScale.shift = 100.0
        } else {
            overviewData.ratioScale.multiplier = maxY * scale / max(overviewData.maxRatioValueFound, abs(overviewData.minRatioValueFound))
            overviewData.ratioScale.shift = 0.0
        }
        (overviewData.ratioSeries as LineGraphSeries<ScaledDataPoint>).color = RATIO_LINE
        addSeries(overviewData.ratioSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun addDeviationSlope(useForScale: Boolean, scale: Double, isRatioScale: Boolean = false) {
        if (useForScale) {
            maxY = max(overviewData.maxFromMaxValueFound, overviewData.maxFromMinValueFound)
            minY = -maxY
        }
        var graphMaxY = maxY
        if (isRatioScale) {
            graphMaxY = maxY - 100.0
            overviewData.dsMinScale.shift = 100.0
            overviewData.dsMaxScale.shift = 100.0
        } else {
            overviewData.dsMinScale.shift = 0.0
            overviewData.dsMaxScale.shift = 0.0
        }
        overviewData.dsMaxScale.multiplier = graphMaxY * scale / overviewData.maxFromMaxValueFound
        overviewData.dsMinScale.multiplier = graphMaxY * scale / overviewData.maxFromMinValueFound
        (overviewData.dsMaxSeries as LineGraphSeries<ScaledDataPoint>).color = DEV_SLOPE_POS
        (overviewData.dsMinSeries as LineGraphSeries<ScaledDataPoint>).color = DEV_SLOPE_NEG
        addSeries(overviewData.dsMaxSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.dsMinSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun addVarSens(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxVarSensValueFound
            minY = overviewData.minVarSensValueFound
        }
        overviewData.varSensScale.multiplier = maxY * scale / overviewData.maxVarSensValueFound
        // Reuses RATIO_LINE — VarSens already shared ratioColor pre-V2 (PrepareIobAutosens-
        // GraphDataWorker), same conceptual "sensitivity" family, kept paired here too.
        (overviewData.varSensSeries as LineGraphSeries<ScaledDataPoint>).color = RATIO_LINE
        addSeries(overviewData.varSensSeries as LineGraphSeries<ScaledDataPoint>)
    }

    /**
     * V2-specific (2026-08-28, user request): HR as a real connected LINE on the PRIMARY axis,
     * instead of the shared-axis floating-tick rendering `addHeartRate` used. Segmented at real
     * data gaps (watch off wrist etc. — [HR_GAP_THRESHOLD_MS]) so a break in readings shows as a
     * visible gap, never a straight line silently bridging hours of no data (LineGraphSeries itself
     * has no gap awareness — draw() in the vendored jjoe64 lib always connects consecutive points,
     * verified by reading it directly). Reads the SAME underlying series
     * (`overviewData.heartRateGraphSeries`, populated by `PrepareTreatmentsDataWorker`) via the
     * public `getValues()` — does not touch or duplicate the shared data-prep pipeline, so the
     * stock (non-Boost) Overview screen is unaffected. Steps moves to `graph.secondScale` (see
     * [addStepsBars]) so the two no longer fight over one shared axis (root cause of the original
     * complaint: Steps values near 0 were clipped below the HR-driven viewport floor).
     */
    fun addHeartRateLine(useForScale: Boolean, context: Context?) {
        val values = (overviewData.heartRateGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
            .getValues(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        val points = mutableListOf<DataPoint>()
        while (values.hasNext()) {
            val v = values.next() ?: break
            points.add(DataPoint(v.getX(), v.getY()))
        }
        // 2026-08-28 bug found via screenshot (emulator has no HR source at all, so this path is
        // always hit there): the old code set minY/maxY only AFTER an empty-check early return —
        // with no data at all, the class-level minY/maxY sentinels (Double.MAX_VALUE/MIN_VALUE,
        // never overwritten) flowed straight into graph.viewport.setMinY/setMaxY in performUpdate(),
        // corrupting the axis into "NaN" tick labels (confirmed on-device — GridLabelRenderer can't
        // compute a step size for an inverted MAX_VALUE..MIN_VALUE range). Fixed: always set a real
        // fallback range (30-100, a plausible resting-to-elevated HR span) BEFORE the empty check,
        // so a data-less window still gets a sane, if empty, axis — only the line-drawing below is
        // actually skipped when there's nothing to draw.
        val maxHR = points.maxOfOrNull { it.getY() } ?: 100.0
        if (useForScale) {
            minY = 30.0
            maxY = maxHR
        }
        if (points.isEmpty()) return
        val color = rh.gac(context, app.aaps.core.ui.R.attr.heartRateColor)
        var segment = mutableListOf(points[0])
        for (i in 1 until points.size) {
            if (points[i].getX() - points[i - 1].getX() > HR_GAP_THRESHOLD_MS) {
                addHrLineSegment(segment, color)
                segment = mutableListOf()
            }
            segment.add(points[i])
        }
        addHrLineSegment(segment, color)
    }

    /** A single unbroken run of HR readings (no gap > [HR_GAP_THRESHOLD_MS] inside it). Segments
     *  with a single point can't form a line (nothing to connect to) and are dropped. */
    private fun addHrLineSegment(segment: List<DataPoint>, color: Int) {
        if (segment.size < 2) return
        addSeries(
            LineGraphSeries(segment.toTypedArray()).also {
                it.color = color
                it.thickness = 4
            }
        )
    }

    /**
     * V2-specific (2026-08-28, user request): Steps as a real bar chart (`BarGraphSeries`, bars
     * grow from a 0 baseline — matches Google Fit/Apple Health convention, unlike `addSteps`'s
     * floating ticks) on its own independent `graph.secondScale` axis — never shares scale with HR
     * or anything else on the row. Zero-step buckets get a small forced-visible stub height (bars
     * at height 0 render as literally nothing, indistinguishable from a missing sample) in a dimmed
     * grey via [ValueDependentColor], so "0 steps, actually measured" reads differently from "no
     * data here at all" at a glance. Real (unclamped) step count drives the colour decision, not
     * the display height — see [StepsBarPoint].
     */
    fun addStepsBars(context: Context?) {
        // 2026-08-28: set BEFORE the empty check (found via screenshot after the NaN fix: HR now
        // shows a sane fallback axis with zero data, but Steps showed no axis at all — inconsistent,
        // since this row DID request Steps, it's just temporarily empty, not a row that never wanted
        // Steps). [secondScaleRequested] lets performUpdate() distinguish "this row wants Steps but
        // has none right now" (still show an empty axis, matching HR's fallback) from "this row never
        // had addStepsBars called at all" (never touch secondScale — see the NaN-pollution fix above).
        secondScaleRequested = true
        val values = (overviewData.stepsCountGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
            .getValues(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        val raw = mutableListOf<Pair<Double, Double>>() // x, real steps
        while (values.hasNext()) {
            val v = values.next() ?: break
            raw.add(v.getX() to v.getY())
        }
        // 100.0 fallback (no real data) mirrors addHeartRateLine's fallback; 1.15 = 15% headroom
        // so the tallest real bar isn't clipped at the very top of its band.
        val maxSteps = raw.maxOfOrNull { it.second } ?: 100.0
        secondScaleMaxY = max(secondScaleMaxY, maxSteps * 1.15)
        if (raw.isEmpty()) return
        // 3% of the day's own peak, floored at 1.0 so a near-flat/sedentary day (tiny maxSteps)
        // still gets a visible-but-clearly-minimal stub rather than an invisible sliver.
        val zeroStubHeight = max(maxSteps * 0.03, 1.0)
        val points = raw.map { (x, realSteps) ->
            StepsBarPoint(x = x, realSteps = realSteps, displayY = if (realSteps <= 0.0) zeroStubHeight else realSteps)
        }
        val activeColor = rh.gac(context, app.aaps.core.ui.R.attr.stepsColor)
        val zeroColor = Color.argb(140, 170, 170, 170) // dimmed grey — "measured, but zero"
        val barSeries = BarGraphSeries(points.toTypedArray()).also {
            it.setValueDependentColor { p -> if (p.realSteps <= 0.0) zeroColor else activeColor }
        }
        secondScaleSeries.add(barSeries)
    }

    /** [x]/[displayY] implement [DataPointInterface] for [BarGraphSeries]; [realSteps] is the
     *  actual (never height-clamped) step count, read by the `ValueDependentColor` callback in
     *  [addStepsBars] to tell a true zero apart from a forced-visible stub. */
    private class StepsBarPoint(
        private val x: Double,
        private val displayY: Double,
        val realSteps: Double,
    ) : com.jjoe64.graphview.series.DataPointInterface {
        override fun getX() = x
        override fun getY() = displayY
    }

    // ── V2-styled methods (overridden colours) ───────────────────────────

    /**
     * In-range area with V2 styling: subtle cyan fill, dashed upper (cyan) and lower (red) borders.
     */
    fun addInRangeArea(fromTime: Long, toTime: Long, lowLine: Double, highLine: Double) {
        // Filled band
        val inRangeAreaDataPoints = arrayOf(
            DoubleDataPoint(fromTime.toDouble(), lowLine, highLine),
            DoubleDataPoint(toTime.toDouble(), lowLine, highLine)
        )
        addSeries(AreaGraphSeries(inRangeAreaDataPoints).also {
            it.color = 0
            it.isDrawBackground = true
            it.backgroundColor = IN_RANGE_FILL
        })

        // Upper border — dashed cyan line at the high-line level
        val upperLinePoints = arrayOf(
            DataPoint(fromTime.toDouble(), highLine),
            DataPoint(toTime.toDouble(), highLine)
        )
        addSeries(LineGraphSeries(upperLinePoints).also {
            it.isDrawDataPoints = false
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.pathEffect = DashPathEffect(floatArrayOf(8f, 12f), 0f)
                paint.color = IN_RANGE_UPPER_BORDER
            })
        })

        // Lower border — dashed red line at the low-line level
        val lowerLinePoints = arrayOf(
            DataPoint(fromTime.toDouble(), lowLine),
            DataPoint(toTime.toDouble(), lowLine)
        )
        addSeries(LineGraphSeries(lowerLinePoints).also {
            it.isDrawDataPoints = false
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.pathEffect = DashPathEffect(floatArrayOf(8f, 12f), 0f)
                paint.color = IN_RANGE_LOWER_BORDER
            })
        })
    }

    /**
     * Basals with V2 styling: orange fill instead of theme cyan.
     */
    fun addBasals() {
        overviewData.basalScale.multiplier = 1.0
        var maxBasalValue =
            maxOf(0.1, (overviewData.baseBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY, (overviewData.tempBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY)
        maxBasalValue =
            maxOf(
                maxBasalValue,
                (overviewData.basalLineGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY,
                (overviewData.absoluteBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).highestValueY
            )

        // Override basal colours to V2 orange before adding
        (overviewData.baseBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).also {
            it.backgroundColor = BASAL_FILL
        }
        (overviewData.tempBasalGraphSeries as LineGraphSeries<ScaledDataPoint>).also {
            it.backgroundColor = BASAL_FILL
        }

        addSeries(overviewData.baseBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.tempBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.basalLineGraphSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.absoluteBasalGraphSeries as LineGraphSeries<ScaledDataPoint>)
        maxY = max(maxY, preferences.get(UnitDoubleKey.OverviewHighMark))
        val scale = preferences.get(UnitDoubleKey.OverviewLowMark) / maxY / 1.2
        overviewData.basalScale.multiplier = maxY * scale / maxBasalValue
    }

    /**
     * Target line with V2 styling: accent green at 50 % opacity, thickness 2.
     *
     * Temp-target deviation zones are added first so the line draws on top of them.
     */
    fun addTargetLine() {
        addTargetDeviationZones()
        (overviewData.temporaryTargetSeries as LineGraphSeries<DataPoint>).also {
            it.color = TARGET_LINE
            it.thickness = 2
        }
        addSeries(overviewData.temporaryTargetSeries as LineGraphSeries<DataPoint>)
    }

    /**
     * Translucent zones for every interval where the active target deviates from the
     * profile default target — makes overrides (exercise 150, lowered targets) visible
     * at a glance. Green tint when the target is raised, red tint when lowered.
     *
     * Interval source: [OverviewData.temporaryTargetSeries] is a step line built by
     * PrepareTemporaryTargetDataWorker at 5-min resolution — consecutive points with the
     * same y form horizontal intervals of constant active target. We iterate those points
     * directly and compare against the profile default target, mirroring the worker's
     * unit conversion exactly:
     *   series y  = profileUtil.fromMgdlToUnits(target mg/dL)          → display units
     *   default   = profileUtil.fromMgdlToUnits((lowMgdl+highMgdl)/2)  → display units
     * so both sides of the comparison are in the user's display units (mgdl or mmol).
     *
     * Like [addInRangeArea], this never touches maxY/minY — the bands sit between two
     * target values that are already well inside the BG axis range.
     */
    private fun addTargetDeviationZones() {
        val targetSeries = overviewData.temporaryTargetSeries as LineGraphSeries<DataPoint>
        if (targetSeries.isEmpty) return
        val profile = profileFunction.getProfile() ?: return

        // Epsilon ≈ 1 mg/dL expressed in display units (≈ 0.056 mmol/L)
        val epsilon = profileUtil.fromMgdlToUnits(1.0)
        // Same sampling cadence the series was built with — catches profile-target
        // schedule steps that occur inside a single temp-target interval.
        val sampleMs = 5 * 60 * 1000.0

        val points = ArrayList<DataPoint>()
        val iterator = targetSeries.getValues(targetSeries.lowestValueX, targetSeries.highestValueX)
        while (iterator.hasNext()) points.add(iterator.next())
        if (points.size < 2) return

        var bandOpen = false
        var bandStartX = 0.0
        var bandActive = 0.0
        var bandDefault = 0.0

        fun closeBand(endX: Double) {
            if (!bandOpen) return
            bandOpen = false
            if (endX <= bandStartX) return
            val lo = minOf(bandActive, bandDefault)
            val hi = maxOf(bandActive, bandDefault)
            val fill = if (bandActive > bandDefault) TT_RAISED_ZONE_FILL else TT_LOWERED_ZONE_FILL
            addSeries(AreaGraphSeries(arrayOf(
                DoubleDataPoint(bandStartX, lo, hi),
                DoubleDataPoint(endX, lo, hi)
            )).also { s ->
                s.color = 0 // no outline — fill only (same pattern as the in-range band)
                s.isDrawBackground = true
                s.backgroundColor = fill
            })
        }

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            // Vertical step edges (same x or different y) are not intervals
            if (a.y != b.y || b.x <= a.x) continue
            val active = a.y
            var t = a.x
            while (t < b.x) {
                val defaultTarget = profileUtil.fromMgdlToUnits(
                    (profile.getTargetLowMgdl(t.toLong()) + profile.getTargetHighMgdl(t.toLong())) / 2.0
                )
                val deviates = abs(active - defaultTarget) > epsilon
                // Close the running band on any change of deviation state or band geometry
                if (bandOpen && (!deviates || active != bandActive || defaultTarget != bandDefault)) closeBand(t)
                if (deviates && !bandOpen) {
                    bandOpen = true
                    bandStartX = t
                    bandActive = active
                    bandDefault = defaultTarget
                }
                t += sampleMs
            }
        }
        closeBand(points[points.size - 1].x)
    }

    /**
     * Now line with V2 styling: white at 10 % opacity, dashed.
     */
    fun addNowLine(now: Long) {
        val nowPoints = arrayOf(
            DataPoint(now.toDouble(), 0.0),
            DataPoint(now.toDouble(), maxY)
        )
        addSeries(LineGraphSeries(nowPoints).also {
            it.isDrawDataPoints = false
            it.setCustomPaint(Paint().also { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.pathEffect = DashPathEffect(floatArrayOf(10f, 20f), 0f)
                paint.color = NOW_LINE
            })
        })
    }

    /**
     * IOB with V2 styling: blue #60a5fa line and fill.
     */
    fun addIob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxIobValueFound
            minY = -overviewData.maxIobValueFound
        }
        overviewData.iobScale.multiplier = maxY * scale / overviewData.maxIobValueFound

        // Override IOB series colours to V2 blue
        (overviewData.iobSeries as FixedLineGraphSeries<ScaledDataPoint>).also {
            it.color = IOB_LINE
            it.backgroundColor = IOB_FILL
        }

        addSeries(overviewData.iobSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.iobPredictions1Series as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    // ── Axis / layout ────────────────────────────────────────────────────

    fun setNumVerticalLabels() {
        graph.gridLabelRenderer.numVerticalLabels = max(3, if (units == GlucoseUnit.MGDL) (maxY / 40 + 1).toInt() else (maxY / 2 + 1).toInt())
    }

    fun formatAxis(fromTime: Long, endTime: Long) {
        graph.viewport.setMaxX(endTime.toDouble())
        graph.viewport.setMinX(fromTime.toDouble())
        graph.viewport.isXAxisBoundsManual = true
        graph.gridLabelRenderer.labelFormatter = TimeAsXAxisLabelFormatter("HH")
        graph.gridLabelRenderer.numHorizontalLabels = 7
    }

    /**
     * Apply V2 dark-theme styling to the graph's chrome (background, grid, labels).
     * Call this after [performUpdate].
     */
    fun applyV2Theme() {
        graph.viewport.backgroundColor = GRAPH_BG
        graph.gridLabelRenderer.gridColor = GRID_COLOR
        graph.gridLabelRenderer.horizontalLabelsColor = LABEL_COLOR
        graph.gridLabelRenderer.verticalLabelsColor = LABEL_COLOR
    }

    // ── Internal plumbing ────────────────────────────────────────────────

    private fun addSeries(s: Series<*>) = series.add(s)

    fun performUpdate() {
        graph.removeAllSeries()

        for (s in series) {
            if (!s.isEmpty) {
                s.onGraphViewAttached(graph)
                graph.series.add(s)
            }
        }
        // 2026-08-28: second, more general NaN guard, found while re-checking the HR fallback fix
        // above for OTHER rows it doesn't cover. Steps deliberately no longer participates in the
        // useXForScale reference chain (own secondScale axis now) — a row with ONLY Steps enabled
        // (no HR/IOB/COB/...) means nothing at all ever overwrites the minY/maxY sentinels, which
        // would corrupt the axis into NaN exactly like the HR-empty case did, just for a different
        // trigger (row composition, not empty data). Same fix shape: fall back to a sane range
        // whenever nothing has claimed the scale, right before it's actually used.
        if (maxY == Double.MIN_VALUE) maxY = 1.0
        if (minY == Double.MAX_VALUE) minY = 0.0
        var step = 1.0
        if (maxY < 1) step = 0.1
        graph.viewport.setMaxY(Round.ceilTo(maxY, step))
        graph.viewport.setMinY(Round.floorTo(minY, step))
        graph.viewport.isYAxisBoundsManual = true

        // 2026-08-28: secondScale (currently: Steps bars). Two things verified by reading
        // GraphView.java directly, not assumed:
        //  1. GraphView.removeAllSeries() above does NOT touch secondScale's own series list — old
        //     series must be detached + cleared here manually, every refresh, or bars pile up forever.
        //  2. graph.secondScale is a lazy getter (GraphView.getSecondScale()): the FIRST touch —
        //     even just reading it to check whether it's empty — permanently instantiates it, and
        //     from then on GraphView unconditionally reserves right-axis label width
        //     (getGraphContentWidth()) and draws the (empty) right axis on THIS GraphView object
        //     forever, with no way to undo it (no public reset). So secondScale must be left
        //     completely untouched on rows that never show Steps — including the "just checking if
        //     it's empty to clean up" case — or every non-Steps row (IOB/COB/... only) would grow an
        //     unwanted empty axis the first time it happened to run through this code path.
        //     Accepted trade-off: if a row HAD Steps and is then reconfigured to drop it (rare —
        //     chart-type selection isn't normally changed live between refreshes), the last-drawn
        //     bars/axis stay on screen until the Fragment is recreated rather than being cleaned up
        //     — better than every Steps-less row permanently growing an axis the moment it's ever
        //     touched. Gated on [secondScaleRequested] (was addStepsBars called this row at all?),
        //     NOT on secondScaleSeries.isEmpty() — a row that wants Steps but currently has zero
        //     data should still show an empty axis (parity with addHeartRateLine's fallback), not
        //     vanish entirely.
        if (secondScaleRequested) {
            for (s in graph.secondScale.series) s.onGraphViewDetached(graph)
            graph.secondScale.series.clear()
            for (s in secondScaleSeries) {
                if (!s.isEmpty) {
                    s.onGraphViewAttached(graph)
                    graph.secondScale.addSeries(s)
                }
            }
            graph.secondScale.minY = 0.0
            graph.secondScale.maxY = secondScaleMaxY
        }

        graph.onDataChanged(false, false)
        series.clear()
        secondScaleSeries.clear()
        secondScaleMaxY = 1.0
        secondScaleRequested = false
    }
}
