package com.shihab.diplay.btproxy.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

data class SheetAction(val label: String, val role: ButtonRole, val closeFirst: Boolean = true, val run: () -> Unit)

/**
 * Dialog chrome drawn from the same palette as the rest of the screen: the framework AlertDialog
 * keeps its own light panel even when the palette flips to night, which reads as broken.
 */
class ProxySheet(private val activity: Activity, private val theme: ProxyTheme) {

    private var overlay: View? = null

    val isShowing: Boolean get() = overlay != null

    fun show(
        title: String,
        message: String? = null,
        body: View? = null,
        actions: List<SheetAction> = emptyList(),
        dismissible: Boolean = true,
        withKeyboard: Boolean = false,
    ) {
        dismiss()
        val panel = theme.card().apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            addView(theme.headline(title))
            message?.let { addView(theme.note(it)) }
            body?.let { addView(it) }
            if (actions.isNotEmpty()) {
                addView(
                    theme.row().apply {
                        gravity = Gravity.END
                        addView(theme.space(4))
                        actions.forEach { action ->
                            addView(
                                theme.compactButton(action.label, action.role) {
                                    if (action.closeFirst) dismiss()
                                    action.run()
                                }.apply {
                                    layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                                        marginStart = theme.dp(8)
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(theme.scrim)
            setPadding(0, 0, 0, 0)
            addView(
                ScrollView(activity).apply {
                    isVerticalScrollBarEnabled = false
                    addView(
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPaddingRelative(theme.dp(24), theme.dp(24), theme.dp(24), theme.dp(24))
                            addView(panel)
                        },
                    )
                    layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)
                },
            )
            if (dismissible) setOnClickListener { dismiss() }
        }
        if (withKeyboard) {
            ViewCompat.setOnApplyWindowInsetsListener(scrim) { target, insets ->
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                target.setPadding(0, 0, 0, (ime - bars).coerceAtLeast(0))
                insets
            }
            ViewCompat.requestApplyInsets(scrim)
        }
        overlay = scrim
        activity.rootOverlay().addView(scrim, FrameLayout.LayoutParams(-1, -1))
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(140).start()
        panel.alpha = 0f
        panel.translationY = theme.dp(16).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(180).start()
        if (withKeyboard) {
            panel.findFocus()?.let { focus ->
                activity.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun dismiss() {
        val current = overlay ?: return
        overlay = null
        current.animate().alpha(0f).setDuration(120).withEndAction {
            (current.parent as? ViewGroup)?.removeView(current)
        }.start()
    }

    fun inputField(hint: String, initial: String = "") = EditText(activity).apply {
        this.hint = hint
        setText(initial)
        setTextColor(theme.text)
        setHintTextColor(theme.muted)
        textSize = ProxyTheme.TYPE_BODY
        background = theme.shape(theme.surface, theme.outline, theme.controlCornerPx(theme.dp(48)))
        setPaddingRelative(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12))
        isSingleLine = true
        minWidth = 0
    }

    /** The window content container, which sits under the status and navigation bars. */
    private fun Activity.rootOverlay(): ViewGroup = findViewById(android.R.id.content)
}
