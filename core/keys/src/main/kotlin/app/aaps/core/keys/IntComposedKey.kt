package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntComposedNonPreferenceKey

enum class IntComposedKey(
    override val key: String,
    override val format: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntComposedNonPreferenceKey {

    WidgetOpacity("appwidget_", "%d", 25),

    /** 2026-08-28 (user request): Boost widget corner radius in dp, per widget instance — same
     *  key pattern as [WidgetOpacity]. Default 16 matches the fixed radius the widget's own
     *  rounded background shape used before this became configurable (see
     *  `boost_widget_background.xml` / BoostWidget.kt's `setViewOutlinePreferredRadius`). */
    WidgetCornerRadius("appwidget_corner_radius_", "%d", 16)
}