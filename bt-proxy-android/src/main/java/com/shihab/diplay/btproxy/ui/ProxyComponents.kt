package com.shihab.diplay.btproxy.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.btproxy.R

enum class ButtonRole { PRIMARY, TONAL, OUTLINED, DANGER, GHOST }

/**
 * Semantic builders over [ProxyTheme]: every control on the proxy screens is assembled here so
 * height, corner radius and colour pairs stay in one place.
 */

fun ProxyTheme.button(
    value: String,
    role: ButtonRole,
    matchWidth: Boolean = true,
    heightDp: Int = ProxyTheme.CONTROL_HEIGHT_DP,
    textSizeSp: Float = ProxyTheme.TYPE_TITLE,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Button {
    val heightPx = dp(heightDp)
    val corner = controlCornerPx(heightPx)
    val fill = when (role) {
        ButtonRole.PRIMARY -> accent
        ButtonRole.TONAL -> selected
        ButtonRole.OUTLINED, ButtonRole.DANGER, ButtonRole.GHOST -> Color.TRANSPARENT
    }
    val stroke = when (role) {
        ButtonRole.PRIMARY -> accent
        ButtonRole.TONAL -> selectedOutline
        ButtonRole.OUTLINED -> outline
        ButtonRole.DANGER -> danger
        ButtonRole.GHOST -> Color.TRANSPARENT
    }
    val foregroundColour = when (role) {
        ButtonRole.PRIMARY -> onAccent
        ButtonRole.TONAL, ButtonRole.GHOST -> accent
        ButtonRole.OUTLINED -> text
        ButtonRole.DANGER -> danger
    }
    return Button(context).apply {
        text = value
        isAllCaps = false
        textSize = textSizeSp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(foregroundColour)
        gravity = Gravity.CENTER
        minimumWidth = dp(ProxyTheme.TOUCH_TARGET_DP)
        minHeight = heightPx
        setPadding(dp(18), 0, dp(18), 0)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            if (matchWidth) -1 else -2,
            -2,
        )
        background = selectable(shape(fill, stroke, corner))
        foreground = focusRing(corner)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { if (isEnabled) onClick() }
    }
}

fun ProxyTheme.compactButton(
    value: String,
    role: ButtonRole = ButtonRole.OUTLINED,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = button(
    value = value,
    role = role,
    matchWidth = false,
    heightDp = ProxyTheme.CONTROL_HEIGHT_COMPACT_DP,
    textSizeSp = ProxyTheme.TYPE_BODY,
    enabled = enabled,
    onClick = onClick,
)

/** Filter chip: activation drives the drawn state, so the row repaints on selection rather than on tap. */
fun ProxyTheme.chip(value: String, active: Boolean, onClick: () -> Unit): TextView {
    val corner = dp(ProxyTheme.CHIP_HEIGHT_DP) * 0.5f
    fun chipShape(fill: Int, stroke: Int) = shape(fill, stroke, corner).also { it.cornerRadius = corner }
    return TextView(context).apply {
        text = value
        textSize = ProxyTheme.TYPE_CAPTION
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        minimumHeight = dp(ProxyTheme.CHIP_HEIGHT_DP)
        minWidth = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        isClickable = true
        stateListAnimator = null
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), chipShape(selected, selectedOutline))
            addState(intArrayOf(), chipShape(Color.TRANSPARENT, outline))
        }
        isActivated = active
        setTextColor(if (active) accent else muted)
        setOnClickListener {
            onClick()
        }
    }
}

/** Outlined pill used for experimental/tested markers and short qualifiers. */
fun ProxyTheme.badge(value: String, colour: Int): TextView = TextView(context).apply {
    text = value
    textSize = ProxyTheme.TYPE_BADGE
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(colour)
    gravity = Gravity.CENTER
    maxLines = 1
    setPadding(dp(10), dp(3), dp(10), dp(3))
    background = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), colour)
    }
    layoutParams = LinearLayout.LayoutParams(-2, -2)
}

fun ProxyTheme.dotMarker(colour: Int, sizeDp: Int = 8): View = View(context).apply {
    val size = dp(sizeDp)
    layoutParams = LinearLayout.LayoutParams(size, size)
    background = GradientDrawable().apply {
        setColor(colour)
        cornerRadius = size / 2f
    }
}

/** Title/value/action row used by the readiness checklist. */
fun ProxyTheme.statusRow(
    glyph: GlyphView,
    title: String,
    value: String,
    action: View? = null,
): LinearLayout = row().apply {
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(ProxyTheme.ROW_MIN_HEIGHT_DP)
    addView(glyph)
    addView(
        column().apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            }
            addView(label(title, ProxyTheme.TYPE_BODY, text, medium = true))
            addView(label(value, ProxyTheme.TYPE_NOTE, muted))
        },
    )
    action?.let { addView(it) }
}

fun ProxyTheme.switchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
): Pair<LinearLayout, SwitchView> {
    val toggle = SwitchView(context, this).apply { this.checked = checked }
    val container = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ProxyTheme.ROW_MIN_HEIGHT_DP)
        addView(
            column().apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) }
                addView(label(title, ProxyTheme.TYPE_BODY, text, medium = true))
                summary?.let { addView(label(it, ProxyTheme.TYPE_NOTE, muted)) }
            },
        )
        addView(toggle)
        setOnClickListener { toggle.performClick() }
        touchFeedback(this, controlCornerPx(dp(ProxyTheme.ROW_MIN_HEIGHT_DP)))
    }
    toggle.onToggle = { onChange(it) }
    return container to toggle
}

/** Single-choice device row replacing the Spinner. */
fun ProxyTheme.deviceRow(
    name: String,
    detail: String,
    marker: String?,
    selected: Boolean,
    onClick: () -> Unit,
): LinearLayout {
    val mark = RadioMark(context, this).apply { checked = selected }
    val corner = cardCornerPx()
    return row().apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPaddingRelative(dp(12), dp(10), dp(12), dp(10))
        background = if (selected) shape(context.getColor(R.color.proxy_surface), selectedOutline, corner) else null
        addView(mark)
        addView(
            column().apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(12)
                    marginEnd = dp(8)
                }
                addView(label(name, ProxyTheme.TYPE_BODY, if (selected) accent else text, medium = true))
                addView(label(detail, ProxyTheme.TYPE_NOTE, muted))
            },
        )
        marker?.let { addView(badge(it, if (selected) accent else muted)) }
        isClickable = true
        contentDescription = "$name，${if (selected) "已选中" else "未选中"}"
        setOnClickListener { onClick() }
        touchFeedback(this, corner)
    }
}

/** Labelled number used for the live byte counters. */
class MetricCell(val container: LinearLayout, val value: TextView, val caption: TextView)

fun ProxyTheme.metricCell(value: String, caption: String, colour: Int): MetricCell {
    val container = column().apply {
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        gravity = Gravity.START
    }
    val valueView = label(value, ProxyTheme.TYPE_METRIC, colour, medium = true, mono = true)
    val captionView = label(caption, ProxyTheme.TYPE_CAPTION, muted)
    container.addView(valueView)
    container.addView(captionView)
    return MetricCell(container, valueView, captionView)
}

fun ProxyTheme.sectionHeading(title: String, trailing: View? = null): LinearLayout = row().apply {
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(ProxyTheme.GROUP_GAP_DP)
        bottomMargin = dp(10)
    }
    addView(label(title, ProxyTheme.TYPE_SECTION, text, medium = true).apply {
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    })
    trailing?.let { addView(it) }
}

/** Title/value/chevron row that opens a secondary page. */
fun ProxyTheme.navRow(title: String, value: String, valueColour: Int, onClick: () -> Unit): LinearLayout {
    val design = this
    val corner = controlCornerPx(dp(ProxyTheme.ROW_MIN_HEIGHT_DP))
    return row().apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ProxyTheme.ROW_MIN_HEIGHT_DP)
        setPaddingRelative(dp(2), dp(8), dp(2), dp(8))
        addView(
            column().apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
                addView(label(title, ProxyTheme.TYPE_BODY, text, medium = true))
                addView(label(value, ProxyTheme.TYPE_NOTE, valueColour))
            },
        )
        addView(ChevronView(context, design))
        isClickable = true
        contentDescription = title
        setOnClickListener { onClick() }
        touchFeedback(this, corner)
    }
}
