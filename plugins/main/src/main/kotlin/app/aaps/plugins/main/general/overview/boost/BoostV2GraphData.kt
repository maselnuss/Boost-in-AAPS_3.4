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

        /** See [formatAxis]'s comment. Above this span, X-axis labels always round to the nearest
         *  whole hour (never print minutes); at or below it, per-tick 5-min rounding is used so the
         *  "3h" range still gets its intentional half-hour labels. Picked well above the 3h range's
         *  own max span (3h history + capped ≤1h prediction = 4h) and well below the next built-in
         *  option (6h) — 5h leaves a full hour of margin on both sides. */
        const val ROUND_TO_HOUR_THRESHOLD_MS = 5 * 3_600_000L
    }

    // ── Internal state (mirrors GraphData) ───────────────────────────────
    private var maxY = Double.MIN_VALUE
    private var minY = Double.MAX_VALUE
    private val units: GlucoseUnit get() = profileFunction.getUnits()
    private val series: MutableList<Series<*>> = ArrayList()

    private lateinit var graph: GraphView
    private lateinit var overviewData: OverviewData

    fun with(graph: GraphView, overviewData: OverviewData): BoostV2GraphData = this.also {
        it.graph = graph
        it.overviewData = overviewData
    }

    // ── Delegated methods (unchanged logic) ──────────────────────────────

    fun addBucketedData() {
        addSeries(overviewData.bucketedGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
    }

    fun addBgReadings(addPredictions: Boolean, context: Context?) {
        val bgSeries = overviewData.bgReadingGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>
        // overviewData.maxBgValue is computed by a shared background worker (PrepareBgDataWorker)
        // whose own fromTime/toTime window is not guaranteed to already be refreshed to match a
        // just-changed zoom level — the axis can then reflect a wider/stale window's peak instead of
        // what is actually on screen (the "3h" range's narrowness makes the worker's refresh lag
        // visible far more often than the wider built-in options do). Recompute the max locally from
        // the ACTUAL series points bounded to the current viewport instead of trusting a value that
        // may not correspond to the window really being shown.
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

    fun addHeartRate(useForScale: Boolean, scale: Double) {
        val maxHR = (overviewData.heartRateGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).highestValueY
        if (useForScale) {
            minY = 30.0
            maxY = maxHR
        }
        addSeries(overviewData.heartRateGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        overviewData.heartRateScale.multiplier = maxY * scale / maxHR
    }

    fun addSteps(useForScale: Boolean, scale: Double) {
        val maxSteps = (overviewData.stepsCountGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>).highestValueY
        if (useForScale) {
            minY = 0.0
            maxY = maxSteps
        }
        addSeries(overviewData.stepsCountGraphSeries as PointsWithLabelGraphSeries<DataPointWithLabelInterface>)
        overviewData.stepsForScale.multiplier = maxY * scale / maxSteps
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
        // The "3h" range option produces duplicate-looking hour labels ("12 12 13 13 14 14 15"):
        // numHorizontalLabels=7 and the plain "HH" formatter both assume the widest built-in options
        // (6h+), where 7 labels are 51+ min apart and hour collisions are rare. At 3h/7 labels
        // (~26 min apart), same-hour ticks are the common case, and "HH"-only can't tell them apart.
        // HourMinuteXAxisFormatter below picks the pattern per TICK instead of once for the whole
        // axis: minutes only when a tick doesn't land on the hour. Since fromTime/toTime are
        // hour-aligned (OverviewDataImpl.initRange()), 3h/7 ticks alternate exactly on the hour and
        // half-hour, printing "20 20:30 21 21:30 ..." instead of an all-"HH" collision or an
        // all-"HH:mm" wall of text.
        //
        // fromTime/endTime are also not always stamped from one shared "now": initRange() sets an
        // initial toTime, then PreparePredictionsWorker (its own independent "now") can overwrite
        // fromTime/toTime/endTime again once predictions are available. If the two workers' "now"
        // ticks into a different minute, the visible span stops being a clean multiple of an hour,
        // so even a 6h+ window can occasionally show a stray minute (e.g. "11:05") among otherwise-
        // clean hour labels — reproduced live, the same 6h view rendered cleanly again once both
        // workers' "now" happened to agree. Only the narrow "3h" range actually needs half-hour
        // precision; every wider built-in option only ever wants whole hours, so for any window
        // above ROUND_TO_HOUR_THRESHOLD_MS the formatter always rounds to the nearest HOUR — this
        // can never print a stray minute there, regardless of future worker-timing skew, without
        // touching fromTime/endTime or the plotted data at all.
        graph.gridLabelRenderer.labelFormatter = HourMinuteXAxisFormatter(roundToWholeHour = endTime - fromTime > ROUND_TO_HOUR_THRESHOLD_MS)
        graph.gridLabelRenderer.numHorizontalLabels = 7
    }

    /**
     * X-axis label formatter that shows just the hour ("20") when a tick lands on (or within 2.5
     * min of, to absorb OverviewDataImpl's own ~100s rounding epsilon on toTime) a whole hour, and
     * "HH:mm" ("20:30") otherwise — see [formatAxis]'s comment for why. Extends the same
     * `DefaultLabelFormatter` base as the stock `TimeAsXAxisLabelFormatter`, just picks the pattern
     * per tick instead of once for the whole axis. Rounds the DISPLAYED value to the nearest 5 min
     * too (not just the hour-check), so a tick a few seconds off a clean mark still reads as one.
     *
     * @param roundToWholeHour when true (windows wider than [ROUND_TO_HOUR_THRESHOLD_MS], i.e. every
     *   built-in range except "3h"), rounds to the nearest HOUR instead of nearest 5 min, so it can
     *   only ever print a bare hour — see [formatAxis]'s comment for why this is needed even though
     *   wide windows "should" already land on whole hours.
     */
    private class HourMinuteXAxisFormatter(private val roundToWholeHour: Boolean) : DefaultLabelFormatter() {
        private val hourFormat = java.text.SimpleDateFormat("HH", Locale.getDefault())
        private val hourMinuteFormat = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        override fun formatLabel(value: Double, isValueX: Boolean): String {
            if (!isValueX) {
                // Same guard as the stock TimeAsXAxisLabelFormatter this replaces — its own comment
                // documents a real NPE from GridLabelRenderer calling into DefaultLabelFormatter.
                // formatLabel with isValueX=false at a point where its internal viewport reference
                // can be null. Never reproduced directly, but the guard is cheap and this class is a
                // straight swap for that one, so it keeps it.
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
        var step = 1.0
        if (maxY < 1) step = 0.1
        graph.viewport.setMaxY(Round.ceilTo(maxY, step))
        graph.viewport.setMinY(Round.floorTo(minY, step))
        graph.viewport.isYAxisBoundsManual = true

        graph.onDataChanged(false, false)
        series.clear()
    }
}
