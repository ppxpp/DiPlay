package com.shihab.diplay.btproxy.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.shihab.diplay.btproxy.ProxyController
import com.shihab.diplay.btproxy.ProxySnapshot
import com.shihab.diplay.btproxy.ProxyStage
import com.shihab.diplay.btproxy.R
import com.shihab.diplay.btproxy.ReadinessAction
import com.shihab.diplay.btproxy.ReadinessState

/**
 * Shared chrome for every screen of the proxy app: palette, insets, header, and the controller
 * listener. Secondary activities show only their own title and content, the way a normal Android
 * page does.
 */
abstract class ProxyActivity : AppCompatActivity() {

    protected lateinit var theme: ProxyTheme
        private set
    protected lateinit var sheet: ProxySheet
        private set
    protected val controller: ProxyController by lazy { ProxyController.get(this) }

    private lateinit var body: LinearLayout
    private lateinit var bar: Toolbar
    private var pageScroll: ScrollView? = null

    /** Screen title, shown in the action bar. */
    protected abstract val titleText: String

    /** Entry screen shows the overall readiness state as the action bar subtitle. */
    protected open val isEntryScreen: Boolean get() = false

    protected abstract fun buildContent(parent: LinearLayout)

    private val listener = object : ProxyController.Listener {
        override fun onStructureChanged() {
            if (!isFinishing) rebuild()
        }

        override fun onLiveChanged(snapshot: ProxySnapshot) {
            this@ProxyActivity.onLiveChanged(snapshot)
        }

        override fun onLogChanged() {
            this@ProxyActivity.onLogChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = ProxyTheme(this)
        sheet = ProxySheet(this, theme)
        bar = actionBar()
        body = theme.column().apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setPaddingRelative(theme.dp(ProxyTheme.SCREEN_PADDING_DP), 0, theme.dp(ProxyTheme.SCREEN_PADDING_DP), 0)
        }
        val root = theme.column().apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(theme.background)
            addView(bar)
            addView(theme.divider())
            addView(body)
        }
        buildContent(body)

        setSupportActionBar(bar)
        supportActionBar?.setDisplayHomeAsUpEnabled(!isEntryScreen)
        window.statusBarColor = theme.surface
        window.navigationBarColor = theme.background

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPaddingRelative(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        applyBarContrast()
        renderToolbar()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) {
            finish()
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    /** A conventional AndroidX action bar: title, optional Up affordance, platform back arrow. */
    private fun actionBar(): Toolbar = Toolbar(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, theme.dp(56))
        setBackgroundColor(theme.surface)
        setTitleTextColor(theme.text)
        setTitleTextAppearance(context, R.style.TextAppearance_BtProxy_ToolbarTitle)
        setSubtitleTextAppearance(context, R.style.TextAppearance_BtProxy_ToolbarSubtitle)
        setContentInsetStartWithNavigation(theme.dp(16))
        navigationContentDescription = context.getString(R.string.action_back)
        setNavigationIcon(R.drawable.ic_arrow_back)
        navigationIcon?.let { icon ->
            icon.mutate()
            icon.setTint(theme.text)
            navigationIcon = icon
        }
    }

    override fun onResume() {
        super.onResume()
        controller.addListener(listener)
        controller.onResume()
        applyBarContrast()
    }

    override fun onPause() {
        controller.removeListener(listener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ProxyController.PERMISSION_REQUEST) controller.onPermissionsResult()
    }

    /** Rebuild the page body; the scroll offset is preserved. */
    protected open fun rebuild() {
        val restore = pageScroll?.scrollY ?: 0
        body.removeAllViews()
        pageScroll = null
        buildContent(body)
        val scroll = pageScroll
        if (restore > 0 && scroll != null) {
            scroll.post { scroll.scrollTo(0, restore) }
        }
        renderToolbar()
    }

    protected open fun onLiveChanged(snapshot: ProxySnapshot) = Unit

    protected open fun onLogChanged() = rebuild()

    /** Wrap a page body in the only scroller the screen owns. */
    protected fun scrollable(column: LinearLayout): View {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column)
        }
        pageScroll = scroll
        return scroll
    }

    protected fun pageBody() = theme.column().apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setPaddingRelative(0, theme.dp(4), 0, theme.dp(24))
    }

    protected fun card() = theme.card().apply { layoutParams = theme.blockGap() }

    // ---- action bar ----

    /** Entry screen surfaces the overall state as the action bar subtitle. */
    protected fun renderToolbar() {
        if (!isEntryScreen) {
            bar.title = titleText
            bar.subtitle = null
            return
        }
        val items = controller.readiness()
        val ready = items.all { it.state == ReadinessState.READY }
        val relaying = controller.snapshot.stage == ProxyStage.RELAYING
        bar.title = titleText
        bar.subtitle = getString(
            when {
                relaying -> R.string.header_running
                !ready -> R.string.header_blocked
                else -> R.string.header_ready
            },
        )
        bar.setSubtitleTextColor(
            when {
                controller.snapshot.blocked -> theme.danger
                relaying -> theme.success
                !ready -> theme.warning
                else -> theme.accent
            },
        )
    }

    protected fun packageVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"

    private fun applyBarContrast() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !theme.isNight
            isAppearanceLightNavigationBars = !theme.isNight
        }
    }

    // ---- shared pieces ----

    protected fun pausedCard(): View {
        val card = card()
        card.addView(
            theme.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    theme.column().apply {
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = theme.dp(8) }
                        addView(theme.body(getString(R.string.paused_for_probe)))
                        addView(theme.note(getString(R.string.paused_for_probe_summary)))
                    },
                )
                addView(
                    theme.compactButton(getString(R.string.action_resume_gateway), ButtonRole.TONAL) {
                        controller.resumeGateway()
                    },
                )
            },
        )
        theme.normalizeSpacing(card)
        return card
    }

    protected fun outcomeLine(phase: String): View {
        val running = controller.isRunning(phase)
        val outcome = controller.outcomeOf(phase)
        val text = when {
            running -> getString(R.string.diagnostics_running)
            outcome != null -> getString(R.string.diagnostics_last, outcome)
            else -> getString(R.string.diagnostics_none)
        }
        val failed = outcome?.contains("FAIL") == true || outcome?.contains("TIMEOUT") == true ||
            outcome?.contains("ERROR") == true || outcome?.contains("REJECT") == true ||
            outcome?.contains("MISSING") == true
        val colour = when {
            running -> theme.accent
            outcome == null -> theme.muted
            failed -> theme.danger
            else -> theme.success
        }
        return theme.label(text, ProxyTheme.TYPE_NOTE, colour, mono = outcome != null && !running)
    }

    protected fun glyphOf(state: ReadinessState) = when (state) {
        ReadinessState.READY -> GlyphState.READY
        ReadinessState.ACTIVE -> GlyphState.ACTIVE
        ReadinessState.BLOCKED -> GlyphState.BLOCKED
        ReadinessState.PENDING -> GlyphState.PENDING
    }

    protected fun perform(action: ReadinessAction) {
        when (action) {
            ReadinessAction.PERMISSION -> controller.requestBluetoothPermissions(this)
            ReadinessAction.MAC_INPUT -> askForBluetoothAddress()
            ReadinessAction.BLUETOOTH_SETTINGS, ReadinessAction.PAIR_SETTINGS ->
                openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            ReadinessAction.NETWORK_SETTINGS -> openSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
            ReadinessAction.NONE -> Unit
        }
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { controller.appendLog("phase=SETTINGS result=UNAVAILABLE action=${intent.action}") }
    }

    protected fun askForBluetoothAddress() {
        val error = theme.note("")
        val field = sheet.inputField(getString(R.string.pairing_mac_hint), controller.pairingAddress().orEmpty())
        sheet.show(
            title = getString(R.string.pairing_first_run_title),
            message = getString(R.string.pairing_first_run_message),
            body = theme.column().apply {
                addView(field)
                addView(error)
            },
            actions = listOf(
                SheetAction(getString(R.string.action_cancel), ButtonRole.GHOST) {},
                SheetAction(getString(R.string.action_save), ButtonRole.PRIMARY, closeFirst = false) {
                    if (controller.saveBluetoothAddress(field.text.toString())) {
                        sheet.dismiss()
                        rebuild()
                    } else {
                        error.text = getString(R.string.pairing_mac_invalid)
                        error.setTextColor(theme.danger)
                    }
                },
            ),
            withKeyboard = true,
        )
        field.requestFocus()
    }

    protected fun confirmClearLog() {
        sheet.show(
            title = getString(R.string.log_clear_title),
            message = getString(R.string.log_clear_message),
            actions = listOf(
                SheetAction(getString(R.string.action_cancel), ButtonRole.GHOST) {},
                SheetAction(getString(R.string.action_clear), ButtonRole.DANGER, closeFirst = false) {
                    controller.clearLog()
                    sheet.dismiss()
                    onLogChanged()
                },
            ),
        )
    }

    /** Reused views must leave their previous parent before being added again. */
    protected fun <T : View> reattach(view: T): T {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }
}
