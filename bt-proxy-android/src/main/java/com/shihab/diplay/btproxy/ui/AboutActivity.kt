package com.shihab.diplay.btproxy.ui

import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import com.shihab.diplay.btproxy.R

/** What the app is for, what it does on the link, and how to use it. */
class AboutActivity : ProxyActivity() {

    override val titleText: String get() = getString(R.string.page_about)

    override fun buildContent(parent: LinearLayout) {
        parent.addView(scrollable(pageBody().apply {
            addView(identityCard())
            addView(sectionCard(getString(R.string.about_position_title), getString(R.string.about_position_body)))
            addView(roleCard())
            addView(usageCard())
            addView(notesCard())
        }))
    }

    private fun identityCard(): android.view.View {
        val card = card()
        card.addView(
            theme.label(getString(R.string.app_name), ProxyTheme.TYPE_HEADLINE, theme.text, medium = true).apply {
                gravity = Gravity.CENTER
            },
        )
        card.addView(theme.note(getString(R.string.about_tagline)).apply { gravity = Gravity.CENTER })
        card.addView(
            theme.caption(
                listOf(
                    getString(
                        R.string.environment_line,
                        Build.MANUFACTURER,
                        Build.MODEL,
                        Build.VERSION.RELEASE,
                        Build.VERSION.SDK_INT,
                    ),
                    getString(R.string.version_caption, packageVersion()),
                ).joinToString(" · "),
            ).apply { gravity = Gravity.CENTER },
        )
        theme.normalizeSpacing(card)
        return card
    }

    private fun sectionCard(title: String, body: String): android.view.View {
        val card = card()
        card.addView(theme.title(title))
        card.addView(theme.body(body))
        theme.normalizeSpacing(card)
        return card
    }

    private fun roleCard(): android.view.View {
        val card = card()
        card.addView(theme.title(getString(R.string.about_role_title)))
        listOf(
            getString(R.string.about_role_bluetooth),
            getString(R.string.about_role_network),
            getString(R.string.about_role_relay),
        ).forEach { text -> card.addView(bullet(text)) }
        theme.normalizeSpacing(card)
        return card
    }

    private fun usageCard(): android.view.View {
        val card = card()
        card.addView(theme.title(getString(R.string.about_usage_title)))
        listOf(
            getString(R.string.about_usage_1),
            getString(R.string.about_usage_2),
            getString(R.string.about_usage_3),
            getString(R.string.about_usage_4),
            getString(R.string.about_usage_5),
            getString(R.string.about_usage_6),
        ).forEachIndexed { index, text -> card.addView(step(index + 1, text)) }
        theme.normalizeSpacing(card)
        return card
    }

    private fun notesCard(): android.view.View {
        val card = card()
        card.addView(theme.title(getString(R.string.about_notes_title)))
        listOf(
            getString(R.string.about_notes_relay),
            getString(R.string.about_notes_mac),
            getString(R.string.about_notes_probe),
            getString(R.string.about_notes_carplay),
        ).forEach { text -> card.addView(bullet(text)) }
        theme.normalizeSpacing(card)
        return card
    }

    private fun bullet(text: String) = themedRow("·", text)

    private fun step(number: Int, text: String) = themedRow(number.toString(), text)

    private fun themedRow(mark: String, text: String) = theme.row().apply {
        gravity = Gravity.TOP
        addView(
            theme.label(mark, ProxyTheme.TYPE_NOTE, theme.accent, medium = true, mono = true).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2)
                minWidth = theme.dp(28)
                setPaddingRelative(0, 0, theme.dp(10), 0)
            },
        )
        addView(theme.body(text).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
    }
}
