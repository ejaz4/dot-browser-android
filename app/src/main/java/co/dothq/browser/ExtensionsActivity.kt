package co.dothq.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import co.dothq.browser.core.ExtensionManager
import co.dothq.browser.ui.DotToast
import co.dothq.browser.ui.Fonts
import co.dothq.browser.ui.Haptics
import co.dothq.browser.ui.MATCH
import co.dothq.browser.ui.MiniSwitch
import co.dothq.browser.ui.Palette
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.Sheet
import co.dothq.browser.ui.SheetAction
import co.dothq.browser.ui.WRAP
import co.dothq.browser.ui.actionList
import co.dothq.browser.ui.addEdgeFades
import co.dothq.browser.ui.dp
import co.dothq.browser.ui.flp
import co.dothq.browser.ui.icon
import co.dothq.browser.ui.iconButton
import co.dothq.browser.ui.label
import co.dothq.browser.ui.lp
import co.dothq.browser.ui.margins
import co.dothq.browser.ui.oval
import co.dothq.browser.ui.pressable
import co.dothq.browser.ui.ripple
import co.dothq.browser.ui.rounded
import co.dothq.browser.ui.sinkOnPress
import co.dothq.browser.ui.withAlpha
import org.mozilla.geckoview.WebExtension
import java.io.File

/** Your extensions: switch them on and off, set them up, remove them, find more. */
class ExtensionsActivity : AppCompatActivity(), ExtensionManager.Ui {
    private val app get() = application as DotApp
    private val ext get() = app.extensions
    private lateinit var p: Palette
    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var sheet: Sheet
    private lateinit var toast: DotToast
    private var previousUi: ExtensionManager.Ui? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installFromContent(uri)
    }

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
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 8.dp, 16.dp, 4.dp)
            addView(iconButton(R.drawable.ic_arrow_left, p.icon, p, desc = getString(R.string.back)).apply { setOnClickListener { finish() } })
        }, lp(MATCH, WRAP))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 0, 16.dp, 32.dp)
        }
        scroll.addView(list)
        val frame = FrameLayout(this)
        frame.addView(scroll, flp(MATCH, MATCH))
        addEdgeFades(frame, scroll, p.frame)
        column.addView(frame, lp(MATCH, 0, 1f))
        setContentView(root)
        sheet = Sheet(root, p)
        toast = DotToast(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        // Take over install prompts while this screen is in front.
        previousUi = ext.ui
        ext.ui = this
        ext.refresh()
    }

    override fun onStop() {
        super.onStop()
        if (ext.ui === this) ext.ui = previousUi
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet.isShowing) sheet.dismiss() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun render() {
        list.removeAllViews()
        list.addView(label(getString(R.string.extensions), 32f, p.text, bold = true, tracking = -0.035f).apply {
            setPadding(8.dp, 4.dp, 8.dp, 6.dp)
        })
        list.addView(label(getString(R.string.extensions_body), 15f, p.text2).apply {
            setPadding(8.dp, 0, 8.dp, 18.dp)
            setLineSpacing(3f.dp, 1f)
        })

        // Find more: the big friendly way in.
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(withAlpha(p.onInk, 0.18f), 16f.dp, rounded(p.ink, 16f.dp))
            setPadding(18.dp, 16.dp, 18.dp, 16.dp)
            isClickable = true
            sinkOnPress(0.98f)
            addView(icon(R.drawable.ic_jigsaw, p.onInk, 22).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 14.dp })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, WRAP, 1f)
                addView(label(getString(R.string.find_extensions), 16f, p.onInk, bold = true))
                addView(label("addons.mozilla.org", 13f, withAlpha(p.onInk, 0.65f)).apply { setPadding(0, 3.dp, 0, 0) })
            })
            addView(icon(R.drawable.ic_chevron_right, withAlpha(p.onInk, 0.7f), 14))
            setOnClickListener { Haptics.tap(it); openInBrowser(ExtensionManager.FIND_EXTENSIONS_URL) }
        }, lp(MATCH, WRAP))

        list.addView(label(getString(R.string.extensions_installed).uppercase(), 12f, p.text3, bold = true, tracking = 0.07f).apply {
            setPadding(10.dp, 26.dp, 10.dp, 8.dp)
        })
        val items = ext.installed
        if (items.isEmpty()) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(p.surface, 14f.dp, p.border, if (p.isDark) 0 else 1)
                setPadding(24.dp, 28.dp, 24.dp, 28.dp)
                addView(FrameLayout(context).apply {
                    background = oval(p.field)
                    addView(icon(R.drawable.ic_jigsaw, p.icon, 24).apply { layoutParams = flp(24.dp, 24.dp, Gravity.CENTER) })
                }, lp(56.dp, 56.dp).margins(b = 14.dp))
                addView(label(getString(R.string.no_extensions), 16f, p.text, bold = true))
                addView(label(getString(R.string.no_extensions_body), 14f, p.text2).apply {
                    gravity = Gravity.CENTER; setPadding(0, 6.dp, 0, 0); setLineSpacing(2f.dp, 1f)
                })
            }, lp(MATCH, WRAP))
        } else {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(p.surface, 14f.dp, p.border, if (p.isDark) 0 else 1)
                clipToOutline = true
            }
            items.forEachIndexed { i, e ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(p.divider) }, lp(MATCH, 1).margins(l = 70.dp))
                card.addView(row(e))
            }
            list.addView(card, lp(MATCH, WRAP))
        }

        list.addView(label(getString(R.string.install_from_file), 15f, p.text2).apply {
            gravity = Gravity.CENTER
            setPadding(0, 18.dp, 0, 18.dp)
            pressable(p, Radius.field)
            setOnClickListener { Haptics.tap(it); pickFile.launch(arrayOf("application/x-xpinstall", "application/zip", "application/octet-stream", "*/*")) }
        }, lp(MATCH, WRAP).margins(t = 10.dp))
    }

    private fun row(e: WebExtension): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 72.dp
        setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        pressable(p, 0f)
        addView(extIcon(e, 38), lp(38.dp, 38.dp).margins(r = 16.dp))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(0, WRAP, 1f)
            addView(label(e.metaData.name ?: e.id, 16f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val sub = e.metaData.description?.ifBlank { null } ?: "Version ${e.metaData.version}"
            addView(label(sub, 13f, p.text2).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 3.dp, 8.dp, 0) })
        })
        val sw = MiniSwitch(context, p).apply { setChecked(e.metaData.enabled, false) }
        sw.setOnClickListener {
            Haptics.tick(it)
            sw.setChecked(!sw.checked, true)
            ext.setEnabled(e, sw.checked) { render() }
        }
        addView(sw, lp(42.dp, 25.dp))
        setOnClickListener { Haptics.tap(it); details(e) }
    }

    private fun extIcon(e: WebExtension, sizeDp: Int): View {
        val bmp = ext.iconOf(e)
        return if (bmp != null) ImageView(this).apply { setImageBitmap(bmp) }
        else FrameLayout(this).apply {
            background = rounded(p.field, Radius.field)
            addView(icon(R.drawable.ic_jigsaw, p.icon, sizeDp / 2).apply { layoutParams = flp((sizeDp / 2).dp, (sizeDp / 2).dp, Gravity.CENTER) })
        }
    }

    private fun details(e: WebExtension) {
        val m = e.metaData
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 4.dp, 20.dp, 0)
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(extIcon(e, 44), lp(44.dp, 44.dp).margins(r = 14.dp))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, WRAP, 1f)
                addView(label(m.name ?: e.id, 19f, p.text, bold = true, tracking = -0.015f))
                addView(label(listOfNotNull("Version ${m.version}", m.creatorName).joinToString(" · "), 13f, p.text2).apply { setPadding(0, 3.dp, 0, 0) })
            })
        })
        m.description?.ifBlank { null }?.let {
            box.addView(label(it, 14f, p.text2).apply { setPadding(2.dp, 14.dp, 2.dp, 0); setLineSpacing(3f.dp, 1f) })
        }
        // Private tabs
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(2.dp, 18.dp, 0, 10.dp)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, WRAP, 1f)
                addView(label(getString(R.string.run_in_private), 16f, p.text))
                addView(label(getString(R.string.run_in_private_body), 13f, p.text2).apply { setPadding(0, 3.dp, 12.dp, 0) })
            })
            val sw = MiniSwitch(context, p).apply { setChecked(m.allowedInPrivateBrowsing, false) }
            sw.setOnClickListener {
                Haptics.tick(it)
                sw.setChecked(!sw.checked, true)
                ext.setPrivate(e, sw.checked) { render() }
            }
            addView(sw, lp(42.dp, 25.dp))
        })
        val actions = mutableListOf<SheetAction>()
        m.optionsPageUrl?.ifBlank { null }?.let { url ->
            actions += SheetAction(R.drawable.ic_settings, getString(R.string.extension_settings)) { sheet.dismiss(); openInBrowser(url) }
        }
        m.amoListingUrl?.ifBlank { null }?.let { url ->
            actions += SheetAction(R.drawable.ic_open_new, getString(R.string.extension_listing)) { sheet.dismiss(); openInBrowser(url) }
        }
        actions += SheetAction(R.drawable.ic_bin, getString(R.string.remove_extension), danger = true) {
            sheet.dismiss()
            ext.uninstall(e) {
                render()
                toast.show(p, getString(R.string.extension_removed, m.name ?: e.id), bottomOffset = 16.dp)
            }
        }
        box.addView(actionList(this, p, actions), lp(MATCH, WRAP).margins(t = 8.dp))
        sheet.show(box)
    }

    private fun openInBrowser(url: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_URL, url))
        finish()
    }

    private fun installFromContent(uri: Uri) {
        // GeckoView installs from file:// URLs, so copy the picked file somewhere it can read.
        try {
            val out = File(cacheDir, "extension-install.xpi")
            contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ext.install("file://${out.absolutePath}") { installed, error ->
                when {
                    installed != null -> { render(); toast.show(p, getString(R.string.extension_added, installed.metaData.name ?: installed.id), bottomOffset = 16.dp) }
                    error != null -> toast.show(p, error, bottomOffset = 16.dp)
                }
            }
        } catch (_: Exception) {
            toast.show(p, getString(R.string.extension_install_failed), bottomOffset = 16.dp)
        }
    }

    /* ---- ExtensionManager.Ui (while this screen is in front) -------------------------------- */

    override fun onInstallPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, done: (Boolean, Boolean) -> Unit) =
        ExtensionPrompts.install(this, root, p, ext, app.extensions.iconOf(ext), permissions, origins, sheet, done)

    override fun onPermissionPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, done: (Boolean) -> Unit) =
        ExtensionPrompts.permissions(this, p, ext, permissions, origins, sheet, done)

    override fun onExtensionsChanged() = render()

    override fun onPopup(ext: WebExtension, session: org.mozilla.geckoview.GeckoSession) {
        session.close()
    }

    override fun openTab(url: String) = openInBrowser(url)

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
    }
}
