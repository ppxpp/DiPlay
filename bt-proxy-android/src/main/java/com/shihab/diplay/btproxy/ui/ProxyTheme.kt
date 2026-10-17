package com.shihab.diplay.btproxy.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.btproxy.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The proxy screens reuse DiPlay's hand-rolled View language: palette colours live in the
 * colours resources so day/night resolves without code, while shape, spacing and type come from
 * this single place.
 */
class ProxyTheme(val context: Context) {

    val background get() = context.getColor(R.color.proxy_background)
    val surface get() = context.getColor(R.color.proxy_surface)
    val buttonSurface get() = context.getColor(R.color.proxy_button)
    val outline get() = context.getColor(R.color.proxy_outline)
    val text get() = context.getColor(R.color.proxy_text)
    val muted get() = context.getColor(R.color.proxy_text_muted)
    val accent get() = context.getColor(R.color.proxy_accent)
    val onAccent get() = context.getColor(R.color.proxy_on_accent)
    val success get() = context.getColor(R.color.proxy_success)
    val warning get() = context.getColor(R.color.proxy_warning)
    val danger get() = context.getColor(R.color.proxy_danger)
    val rippleColor get() = context.getColor(R.color.proxy_ripple)
    val focusColor get() = context.getColor(R.color.proxy_focus)
    val scrim get() = context.getColor(R.color.proxy_scrim)
    val selected get() = context.getColor(R.color.proxy_selected)
    val selectedOutline get() = context.getColor(R.color.proxy_selected_outline)
    val trackOn get() = context.getColor(R.color.proxy_track_on)

    val isNight: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun spPx(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        context.resources.displayMetrics,
    )

    /** Corner radius grows with the control, the way DiPlay's settings chrome does. */
    fun cardCornerPx() = dp(CORNER_BASE_DP) * 20f / 56f * 1.35f
    fun controlCornerPx(heightPx: Int) =
        if (heightPx > 0) min(dp(CONTROL_INSET_DP).toFloat(), heightPx * 20f / 56f) else dp(CORNER_BASE_DP) * 20f / 56f

    fun shape(color: Int, stroke: Int, cornerPx: Float, strokeWidthPx: Int = dp(1)) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = cornerPx
        setStroke(strokeWidthPx, stroke)
    }

    fun cardShape() = shape(surface, outline, cardCornerPx())

    /** A drawn background plus a bounded ripple, with no elevation so old heads stay flat and legible. */
    fun selectable(content: Drawable) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    /** Touch feedback for rows and cards that have no background of their own. */
    fun touchFeedback(view: View, cornerPx: Float = cardCornerPx()) {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = cornerPx
        }
        view.foreground = RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
        view.stateListAnimator = null
        view.isFocusable = true
    }

    fun focusRing(cornerPx: Float): Drawable {
        val radius = (cornerPx / context.resources.displayMetrics.density).roundToInt()
        val focused = shape(Color.TRANSPARENT, focusColor, cornerPx, dp(3))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    fun card(): LinearLayout = column().apply {
        background = cardShape()
        setPaddingRelative(dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP), dp(CARD_PADDING_DP))
    }

    fun space(heightDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    fun divider() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(GROUP_GAP_DP)
            bottomMargin = dp(GROUP_GAP_DP)
        }
        setBackgroundColor(outline)
    }

    fun label(
        value: String,
        sizeSp: Float,
        color: Int,
        medium: Boolean = false,
        mono: Boolean = false,
    ) = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        typeface = when {
            mono -> Typeface.create(Typeface.MONOSPACE, if (medium) Typeface.BOLD else Typeface.NORMAL)
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        setLineSpacing(dp(3).toFloat(), 1f)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setTextIsSelectable(false)
    }

    fun headline(value: String) = label(value, TYPE_HEADLINE, text, medium = true)
    fun title(value: String) = label(value, TYPE_TITLE, text, medium = true)
    fun body(value: String) = label(value, TYPE_BODY, text)
    fun note(value: String) = label(value, TYPE_NOTE, muted)
    fun caption(value: String) = label(value, TYPE_CAPTION, muted)
    fun mono(value: String) = label(value, TYPE_MONO, muted, mono = true)

    /** One vertical rhythm for a card: fixed control height, one gap between controls, notes hug. */
    fun normalizeSpacing(container: LinearLayout, isCard: Boolean = true) {
        if (container.orientation != LinearLayout.VERTICAL) return
        fun isNote(view: View?) = view is TextView && view !is android.widget.Button
        var previous: View? = null
        var last: View? = null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val next = if (i + 1 < container.childCount) container.getChildAt(i + 1) else null
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.topMargin = 0
            lp.bottomMargin = dp(when {
                isNote(child) && isNote(next) -> NOTE_LINE_GAP_DP
                isNote(child) && isNote(previous) -> NOTE_ATTACH_GAP_DP
                isNote(next) -> NOTE_ATTACH_GAP_DP
                else -> CONTROL_GAP_DP
            })
            child.layoutParams = lp
            if (child.visibility != View.GONE) last = child
            previous = child
        }
        if (isCard) {
            (last?.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.bottomMargin = 0
                last.layoutParams = it
            }
        }
    }

    /** Block gap between cards, so screens never hand-place vertical padding. */
    fun blockGap(): ViewGroup.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(BLOCK_GAP_DP)
    }

    companion object {
        const val BLOCK_GAP_DP = 16
        const val CARD_PADDING_DP = 24
        const val SCREEN_PADDING_DP = 20
        const val CONTROL_GAP_DP = 12
        const val GROUP_GAP_DP = 20
        const val NOTE_LINE_GAP_DP = 4
        const val NOTE_ATTACH_GAP_DP = 6
        const val CONTROL_HEIGHT_DP = 60
        const val CONTROL_HEIGHT_COMPACT_DP = 44
        const val CHIP_HEIGHT_DP = 36
        const val ROW_MIN_HEIGHT_DP = 52
        const val CORNER_BASE_DP = 44
        const val CONTROL_INSET_DP = 24
        const val TOUCH_TARGET_DP = 48

        const val TYPE_HEADLINE = 22f
        const val TYPE_TITLE = 17f
        const val TYPE_SECTION = 14f
        const val TYPE_BODY = 15f
        const val TYPE_NOTE = 13f
        const val TYPE_CAPTION = 12f
        const val TYPE_BADGE = 12f
        const val TYPE_MONO = 12f
        const val TYPE_METRIC = 20f
        const val TYPE_CODE = 30f
    }
}
