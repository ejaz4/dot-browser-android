package co.dothq.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import co.dothq.browser.core.Favicons
import co.dothq.browser.core.SearchEngines
import co.dothq.browser.ui.DotRingView
import co.dothq.browser.ui.DotToast
import co.dothq.browser.ui.Fonts
import co.dothq.browser.ui.Haptics
import co.dothq.browser.ui.MATCH
import co.dothq.browser.ui.MiniSwitch
import co.dothq.browser.ui.Palette
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.Sheet
import co.dothq.browser.ui.WRAP
import co.dothq.browser.ui.choiceList
import co.dothq.browser.ui.dp
import co.dothq.browser.ui.flp
import co.dothq.browser.ui.icon
import co.dothq.browser.ui.iconButton
import co.dothq.browser.ui.label
import co.dothq.browser.ui.lp
import co.dothq.browser.ui.margins
import co.dothq.browser.ui.pressable
import co.dothq.browser.ui.rounded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.BuildConfig as GeckoBuild
import org.mozilla.geckoview.StorageController

/** Settings, grouped into Dot-style cards. */
class SettingsActivity : AppCompatActivity() {
    private val app get() = application as DotApp
    private lateinit var p: Palette
    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var sheet: Sheet
    private lateinit var toast: DotToast

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        p = Palette.normal(this)
        window.statusBarColor = p.frame
        window.navigationBarColor = p.frame
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.isDark
            isAppearanceLightNavigationBars = !p.isDark
        }
        root = FrameLayout(this).apply { setBackgroundColor(p.frame) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, flp(MATCH, MATCH))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 8.dp, 16.dp, 4.dp)
            addView(iconButton(R.drawable.ic_arrow_left, p.icon, p, desc = getString(R.string.back)).apply {
                setOnClickListener { finish() }
            })
        }
        column.addView(header, lp(MATCH, WRAP))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 0, 16.dp, 32.dp)
        }
        scroll.addView(list)
        column.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
        sheet = Sheet(root, p)
        toast = DotToast(root)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet.isShowing) sheet.dismiss() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun render() {
        list.removeAllViews()
        val prefs = app.prefs
        list.addView(label(getString(R.string.settings), 32f, p.text, bold = true, tracking = -0.035f).apply {
            setPadding(8.dp, 4.dp, 8.dp, 18.dp)
        })

        group(getString(R.string.settings_general)) {
            choice(getString(R.string.settings_search_engine), prefs.searchEngine.name) {
                val engines = SearchEngines.ALL
                pick(getString(R.string.settings_search_engine), engines.map { it.name to it.home }, engines.indexOf(prefs.searchEngine)) {
                    prefs.searchEngineId = engines[it].id
                }
            }
            divider()
            choice(getString(R.string.settings_toolbar), getString(if (prefs.toolbarAtBottom) R.string.settings_toolbar_bottom else R.string.settings_toolbar_top)) {
                pick(
                    getString(R.string.settings_toolbar),
                    listOf(getString(R.string.settings_toolbar_bottom) to "Easy to reach with one hand", getString(R.string.settings_toolbar_top) to "Like Dot on desktop"),
                    if (prefs.toolbarAtBottom) 0 else 1,
                ) { prefs.toolbarPosition = if (it == 0) "bottom" else "top" }
            }
            divider()
            val themes = listOf("system", "light", "dark")
            val themeNames = listOf(R.string.settings_theme_system, R.string.settings_theme_light, R.string.settings_theme_dark).map { getString(it) }
            choice(getString(R.string.settings_theme), themeNames[themes.indexOf(prefs.theme).coerceAtLeast(0)]) {
                pick(getString(R.string.settings_theme), themeNames.map { it to null }, themes.indexOf(prefs.theme).coerceAtLeast(0)) {
                    prefs.theme = themes[it]
                    app.applyTheme()
                    app.applyPrivacySettings()
                }
            }
        }

        group(getString(R.string.settings_privacy)) {
            choice(getString(R.string.settings_tracking), getString(if (prefs.strictTracking) R.string.settings_tracking_strict else R.string.settings_tracking_standard)) {
                pick(
                    getString(R.string.settings_tracking),
                    listOf(
                        getString(R.string.settings_tracking_strict) to getString(R.string.settings_tracking_strict_body),
                        getString(R.string.settings_tracking_standard) to getString(R.string.settings_tracking_standard_body),
                    ),
                    if (prefs.strictTracking) 0 else 1,
                ) {
                    prefs.strictTracking = it == 0
                    app.applyPrivacySettings()
                }
            }
            divider()
            toggle(getString(R.string.settings_https_only), getString(R.string.settings_https_only_body), prefs.httpsOnly) {
                prefs.httpsOnly = it; app.applyPrivacySettings()
            }
            divider()
            toggle(getString(R.string.settings_strip_params), getString(R.string.settings_strip_params_body), prefs.stripTrackingParams) {
                prefs.stripTrackingParams = it; app.applyPrivacySettings()
            }
            divider()
            toggle(getString(R.string.settings_gpc), getString(R.string.settings_gpc_body), prefs.globalPrivacyControl) {
                prefs.globalPrivacyControl = it; app.applyPrivacySettings()
            }
            divider()
            action(getString(R.string.settings_clear_data), getString(R.string.settings_clear_data_body), danger = true) { clearData() }
        }

        group(getString(R.string.settings_about)) {
            info(getString(R.string.settings_version), BuildConfig.VERSION_NAME)
            divider()
            info(getString(R.string.settings_engine), getString(R.string.settings_engine_value, GeckoBuild.MOZ_APP_VERSION))
            divider()
            info(getString(R.string.settings_licence), getString(R.string.settings_licence_value))
            divider()
            action(getString(R.string.settings_source), "github.com/dothq/browser-android") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dothq/browser-android")).setPackage(packageName))
            }
        }

        // Sign-off
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 28.dp, 0, 8.dp)
            addView(DotRingView(context).apply { halo = false }, lp(28.dp, 28.dp).margins(b = 10.dp))
            addView(label(getString(R.string.settings_made_by), 13f, p.text3))
        })
    }

    /* ---- Builders --------------------------------------------------------------------------- */

    private fun group(title: String, build: LinearLayout.() -> Unit) {
        list.addView(label(title.uppercase(), 12f, p.text3, bold = true, tracking = 0.07f).apply {
            setPadding(10.dp, 18.dp, 10.dp, 8.dp)
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.surface, 14f.dp, p.border, if (p.isDark) 0 else 1)
            clipToOutline = true
            build()
        }
        list.addView(card, lp(MATCH, WRAP))
    }

    private fun LinearLayout.divider() {
        addView(View(context).apply { setBackgroundColor(p.divider) }, lp(MATCH, 1).margins(l = 18.dp))
    }

    private fun LinearLayout.row(title: String, subtitle: String?, titleColor: Int = p.text, trailing: View?, onClick: (() -> Unit)?) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 56.dp
            setPadding(18.dp, 12.dp, 16.dp, 12.dp)
            if (onClick != null) {
                pressable(p, 0f)
                setOnClickListener { Haptics.tap(it); onClick() }
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, WRAP, 1f)
                addView(label(title, 16f, titleColor))
                if (subtitle != null) addView(label(subtitle, 13f, p.text2).apply {
                    setPadding(0, 4.dp, 12.dp, 0)
                    setLineSpacing(2f.dp, 1f)
                })
            })
            if (trailing != null) addView(trailing)
        })
    }

    private fun LinearLayout.choice(title: String, value: String, onClick: () -> Unit) {
        val trailing = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(value, 15f, p.text2))
            addView(icon(R.drawable.ic_chevron_right, p.text3, 14).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 8.dp })
        }
        row(title, null, trailing = trailing, onClick = onClick)
    }

    private fun LinearLayout.toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = MiniSwitch(context, p).apply { setChecked(value, false) }
        sw.layoutParams = lp(42.dp, 25.dp)
        row(title, subtitle, trailing = sw) {
            sw.setChecked(!sw.checked, true)
            onChange(sw.checked)
        }
    }

    private fun LinearLayout.info(title: String, value: String) {
        row(title, null, trailing = label(value, 15f, p.text2), onClick = null)
    }

    private fun LinearLayout.action(title: String, subtitle: String?, danger: Boolean = false, onClick: () -> Unit) {
        row(title, subtitle, titleColor = if (danger) getColor(R.color.dot_danger) else p.text, trailing = null, onClick = onClick)
    }

    private fun pick(title: String, options: List<Pair<String, String?>>, selected: Int, onPick: (Int) -> Unit) {
        sheet.show(choiceList(this, p, title, options, selected) { i ->
            onPick(i)
            sheet.dismiss()
            root.postDelayed({ render() }, 180)
        })
    }

    private fun clearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_data)
            .setMessage(R.string.settings_clear_data_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                app.db.clearHistory()
                Favicons.clear(this)
                app.runtime.storageController.clearData(StorageController.ClearFlags.ALL)
                toast.show(p, getString(R.string.settings_cleared), bottomOffset = 16.dp)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
