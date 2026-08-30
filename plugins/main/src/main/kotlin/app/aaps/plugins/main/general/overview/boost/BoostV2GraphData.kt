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
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
import com.jjoe64.graphview.DefaultLabelFormatter
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.Series
import java.text.NumberFormat
import java.util.Locale
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

        /** 2026-08-30 — see [formatAxis]'s Nachtrag. Above this span, X-axis labels always round to
         *  the nearest whole hour (never print minutes); at or below it, per-tick 5-min rounding is
         *  used so the V2-exclusive 3h zoom still gets its intentional half-hour labels. Picked well
         *  above the 3h view's own max span (3h history + capped ≤1h prediction = 4h) and well below
         *  the narrowest stock zoom option (6h) — 5h leaves a full hour of margin on both sides. */
        const val ROUND_TO_HOUR_THRESHOLD_MS = 5 * 3_600_000L
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

    // ── Delegated methods (unchanged logic, colours untouched from stock AAPS) ─────────────

    fun addBucketedData() {
        addSeries(overviewData.bucketedGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addBgReadings(addPredictions: Boolean, context: Context?) {
        val bgSeries = overviewData.bgReadingGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>
        // 2026-08-29 (user-reported, screenshot: V2-exclusive "3h" zoom showed an axis reaching 280
        // while the visible curve never exceeded ~130) — `overviewData.maxBgValue` is computed by a
        // shared background worker (PrepareBgDataWorker) whose own fromTime/toTime window isn't
        // guaranteed to already be refreshed to match a just-changed zoom level (3h has no stock
        // equivalent, so this path was never exercised at that granularity before). The axis then
        // reflects a wider/stale window's peak instead of what's actually on screen. Recompute
        // locally from the ACTUAL series points bounded to the current viewport — instead of
        // trusting a value that may not correspond to the window really being shown.
        val windowed = bgSeries.getValues(overviewData.fromTime.toDouble(), overviewData.endTime.toDouble())
        var realMax = Double.MIN_VALUE
        while (windowed.hasNext()) {
            val v = windowed.next() ?: break
            if (v.getY() > realMax) realMax = v.getY()
        }
        // Same margin convention as PrepareBgDataWorker.addUpperChartMargin, plus the same
        // "always show at least the configured High line" floor via OverviewHighMark.
        val highMark = preferences.get(UnitDoubleKey.OverviewHighMark)
        maxY = if (realMax == Double.MIN_VALUE) {
            if (units == GlucoseUnit.MGDL) 180.0 else 10.0
        } else {
            val withHighMark = max(realMax, highMark)
            if (units == GlucoseUnit.MGDL) Round.roundTo(withHighMark, 40.0) + 80 else Round.roundTo(withHighMark, 2.0) + 4
        }
        minY = 0.0
        addSeries(bgSeries)
        if (addPredictions) addSeries(overviewData.predictionsGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        bgSeries.setOnDataPointTapListener { _, dataPoint ->
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
        addSeries(overviewData.minusBgiSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.minusBgiHistSeries as FixedLineGraphSeries<ScaledDataPoint>)
    }

    fun addAbsIob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxIobValueFound
            minY = -overviewData.maxIobValueFound
        }
        overviewData.iobScale.multiplier = maxY * scale / overviewData.maxIobValueFound
        addSeries(overviewData.absIobSeries as FixedLineGraphSeries<ScaledDataPoint>)
    }

    fun addCob(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxCobValueFound
            minY = -overviewData.maxCobValueFound
        }
        overviewData.cobScale.multiplier = maxY * scale / overviewData.maxCobValueFound
        addSeries(overviewData.cobSeries as FixedLineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.cobMinFailOverSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addDeviations(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxDevValueFound
            minY = -maxY
        }
        overviewData.devScale.multiplier = maxY * scale / overviewData.maxDevValueFound
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
        addSeries(overviewData.dsMaxSeries as LineGraphSeries<ScaledDataPoint>)
        addSeries(overviewData.dsMinSeries as LineGraphSeries<ScaledDataPoint>)
    }

    fun addVarSens(useForScale: Boolean, scale: Double) {
        if (useForScale) {
            maxY = overviewData.maxVarSensValueFound
            minY = overviewData.minVarSensValueFound
        }
        overviewData.varSensScale.multiplier = maxY * scale / overviewData.maxVarSensValueFound
        addSeries(overviewData.varSensSeries as LineGraphSeries<ScaledDataPoint>)
    }

    /**
     * HR — reverted 2026-08-29 (user request) back to plain stock-style point/tick rendering
     * (matches `GraphData.addHeartRate`): adds the shared `overviewData.heartRateGraphSeries`
     * series object directly, no rebuilding/bucketing/gap-segmentation. The custom connected-LINE
     * version (`LineGraphSeries` + gap-segmentation) tried this session is gone — motivated by
     * aesthetic preference for the old look and to eliminate the whole "thousands of raw data
     * points" problem class it needed workarounds for (peak-bucketing, gap thresholds), not a
     * confirmed-fixed bug. `PointsWithLabelGraphSeries` draws isolated ticks, not connected lines,
     * so it structurally can't exhibit the gap-bridging/density artifacts that motivated the
     * custom version in the first place. Always the PRIMARY axis, same as before.
     */
    fun addHeartRateLine(useForScale: Boolean, context: Context?) {
        val hrSeries = overviewData.heartRateGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>
        if (useForScale) {
            minY = 30.0
            maxY = hrSeries.highestValueY
        }
        addSeries(hrSeries)
    }

    /**
     * Steps — reverted 2026-08-29 (user request, same reasoning as [addHeartRateLine]) back to
     * plain stock-style point/tick rendering: adds the shared `overviewData.stepsCountGraphSeries`
     * series object directly, no rebuilding/bucketing/BarGraphSeries. The dual-axis (secondScale)
     * ROUTING is explicitly kept (`useForScale` still decides primary vs. secondary axis, same as
     * before) — only the custom bar-chart rendering underneath it is gone. The integer-label-
     * formatter and label-cutoff fixes for the secondary axis stay, since those were real GridLabel-
     * Renderer bugs unrelated to the bucketing/rendering approach.
     */
    fun addStepsBars(context: Context?, useForScale: Boolean) {
        val stepsSeries = overviewData.stepsCountGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>
        val maxSteps = stepsSeries.highestValueY
        if (useForScale) {
            minY = 0.0
            maxY = maxSteps
            addSeries(stepsSeries)
        } else {
            secondScaleRequested = true
            val stepsFormat = NumberFormat.getIntegerInstance(Locale.US).also { it.isGroupingUsed = false }
            graph.secondScale.labelFormatter = DefaultLabelFormatter(stepsFormat, stepsFormat)
            secondScaleMaxY = max(secondScaleMaxY, maxSteps)
            secondScaleSeries.add(stepsSeries)
        }
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
        // 2026-08-29 (user-reported: duplicate-looking hour labels in the V2-exclusive "3h" zoom
        // level, e.g. "12 12 13 13 14 14 15"). Root cause: numHorizontalLabels=7 and the "HH"-only
        // format are both copied unchanged from stock GraphData.kt, whose narrowest zoom option is
        // 6h — at 6h/7 labels (~51 min apart) hour collisions are rare; V2 additionally offers 3h
        // (its own range button, not present in stock at all — see v2_range_3h), where 180min/7
        // ≈ 26 min apart makes same-hour ticks the common case, not an edge case, with an "HH"-only
        // formatter unable to tell them apart. First fix used a blanket "HH:mm" below a window-size
        // threshold, but the user preferred the terser "HH" wherever it isn't actually ambiguous
        // (2026-08-29 follow-up) — [HourMinuteXAxisFormatter] below picks per-TICK instead of
        // per-window: minutes only when a tick doesn't land on the hour. At 3h/7 labels, fromTime/
        // toTime are hour-aligned (OverviewDataImpl.initRange()) so ticks alternate exactly on the
        // hour and half-hour — this naturally prints "20 20:30 21 21:30 ..." instead of either an
        // all-"HH" collision or an all-"HH:mm" wall of text. At 6h+ (1h+ spacing) ticks land on
        // whole hours anyway, so it prints identically to the plain "HH" formatter there — no
        // separate window-size branch needed any more. Fixes the main graph too — it calls this
        // same function (BoostOverviewV2Fragment.kt's `graphData.formatAxis(...)`), not a separate path.
        //
        // 2026-08-30 Nachtrag (user-reported, live-reproduced via a fresh device screenshot): the
        // "6h+ ticks land on whole hours anyway" assumption above is NOT always true. fromTime/endTime
        // aren't always stamped from the same "now" — OverviewDataImpl.initRange() sets an initial
        // toTime, then PreparePredictionsWorker (a SEPARATE background worker, own independent
        // "nextFullHour(now)" calculation) overwrites fromTime/toTime/endTime again when predictions
        // are available. If that second worker's "now" happens to have ticked into a different minute
        // window than the first's, the two ends of the visible range no longer share one common
        // reference point, and the total span stops being a clean multiple of an hour — producing an
        // occasional stray "11:05" among otherwise-clean "08 09 10" labels. Confirmed intermittent
        // live: the SAME 6h view rendered perfectly clean moments later once both workers' "now"
        // happened to agree again. Same root pattern (independent worker timing) as the earlier
        // 3h-window Y-axis staleness fix, just the X-axis this time — fixed the same way: locally,
        // without touching the shared workers. Only the V2-exclusive 3h zoom actually NEEDS
        // half-hour-precision labels (see the KDoc above); every stock zoom level (6h/12h/18h/24h)
        // only ever wants whole hours anyway, so for any window wider than [ROUND_TO_HOUR_THRESHOLD_MS]
        // the formatter now always rounds to the nearest HOUR (not 5 min) for display — this can never
        // print a stray minute again there, regardless of any future worker-timing skew, without
        // touching the underlying fromTime/endTime data at all (the plotted data itself is unaffected,
        // only how axis labels round for display). Threshold picked well above the 3h view's own max
        // span (3h history + capped ≤1h prediction = 4h) and well below the narrowest stock option (6h).
        graph.gridLabelRenderer.labelFormatter = HourMinuteXAxisFormatter(roundToWholeHour = endTime - fromTime > ROUND_TO_HOUR_THRESHOLD_MS)
        graph.gridLabelRenderer.numHorizontalLabels = 7
    }

    /**
     * X-axis label formatter that shows just the hour ("20") when a tick lands on (or within 2.5
     * min of, to absorb [OverviewDataImpl]'s own ~100s rounding epsilon on `toTime`) a whole hour,
     * and "HH:mm" ("20:30") otherwise — see [formatAxis]'s KDoc for why. Extends the same
     * `DefaultLabelFormatter` base as the stock `TimeAsXAxisLabelFormatter`, just picks the pattern
     * per tick instead of once for the whole axis. Rounds the DISPLAYED value to the nearest 5 min
     * too (not just the hour-check), so a tick a few seconds off a clean mark still reads as one.
     *
     * @param roundToWholeHour 2026-08-30 — when true (windows wider than [ROUND_TO_HOUR_THRESHOLD_MS],
     *   i.e. every stock zoom level), rounds to the nearest HOUR instead of nearest 5 min, so it can
     *   only ever print a bare hour — see [formatAxis]'s Nachtrag for why this is needed even though
     *   6h+ "should" already land on whole hours.
     */
    private class HourMinuteXAxisFormatter(private val roundToWholeHour: Boolean) : DefaultLabelFormatter() {
        private val hourFormat = java.text.SimpleDateFormat("HH", Locale.getDefault())
        private val hourMinuteFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        override fun formatLabel(value: Double, isValueX: Boolean): String {
            if (!isValueX) {
                // 2026-08-29: same guard as the stock TimeAsXAxisLabelFormatter this replaces — its
                // own comment documents a real, previously-hit NPE from GridLabelRenderer calling
                // into DefaultLabelFormatter.formatLabel with isValueX=false at a point where its
                // internal viewport reference can be null. Never reproduced directly, but the guard
                // is cheap and this class is a straight swap for that one, so it keeps it.
                return try {
                    super.formatLabel(value, false)
                } catch (ignored: Exception) {
                    ""
                }
            }
            if (roundToWholeHour) {
                val roundedMs = Math.round(value / 3_600_000.0) * 3_600_000L // nearest whole hour
                return hourFormat.format(roundedMs)
            }
            val roundedMs = Math.round(value / 300_000.0) * 300_000L // nearest 5 min
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = roundedMs }
            return if (cal.get(java.util.Calendar.MINUTE) == 0) hourFormat.format(roundedMs) else hourMinuteFormat.format(roundedMs)
        }
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
        // 2026-08-28 (user-reported via screenshot): the right-hand (secondScale) axis labels
        // rendered in the library's own default colour, not the V2 grey — verified in
        // GridLabelRenderer.java, `verticalLabelsSecondScaleColor` defaults to the same raw
        // theme-attribute colour as the LEFT axis' own pre-V2 default, but only the left one
        // (`verticalLabelsColor`) was ever overridden here. Safe to set unconditionally on every
        // row (unlike anything touching `graph.secondScale` itself) — `gridLabelRenderer` is not
        // the lazy-instantiated object, only `graph.secondScale` is (see the NaN-pollution fix
        // further down); setting an unused style property here has no effect on rows without Steps.
        graph.gridLabelRenderer.verticalLabelsSecondScaleColor = LABEL_COLOR
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
