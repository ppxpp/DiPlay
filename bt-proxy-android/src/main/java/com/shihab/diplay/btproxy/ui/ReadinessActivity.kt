package com.shihab.diplay.btproxy.ui

import android.widget.LinearLayout
import com.shihab.diplay.btproxy.R

/** The full readiness checklist: one row per prerequisite, with the action that clears it. */
class ReadinessActivity : ProxyActivity() {

    override val titleText: String get() = getString(R.string.readiness_title)

    override fun buildContent(parent: LinearLayout) {
        val card = card()
        controller.readiness().forEach { item ->
            val glyph = GlyphView(this, theme).apply { state = glyphOf(item.state) }
            val action = item.actionLabel?.let { label ->
                theme.compactButton(label, ButtonRole.OUTLINED) { perform(item.action) }.apply {
                    layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { marginStart = theme.dp(8) }
                }
            }
            card.addView(theme.statusRow(glyph, item.title, item.value, action))
        }
        card.addView(
            theme.compactButton(getString(R.string.action_refresh), ButtonRole.OUTLINED) {
                controller.refreshEnvironment()
            },
        )
        theme.normalizeSpacing(card)
        parent.addView(card)
        val note = card()
        note.addView(theme.note(getString(R.string.scope)))
        theme.normalizeSpacing(note)
        parent.addView(note)
    }
}
