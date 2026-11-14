package co.dothq.browser

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import co.dothq.browser.core.ExtensionManager
import co.dothq.browser.ui.Haptics
import co.dothq.browser.ui.MiniSwitch
import co.dothq.browser.ui.Palette
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.Sheet
import co.dothq.browser.ui.WRAP
import co.dothq.browser.ui.dp
import co.dothq.browser.ui.flp
import co.dothq.browser.ui.icon
import co.dothq.browser.ui.label
import co.dothq.browser.ui.lp
import co.dothq.browser.ui.margins
import co.dothq.browser.ui.ripple
import co.dothq.browser.ui.rounded
import co.dothq.browser.ui.sinkOnPress
import co.dothq.browser.ui.withAlpha
import org.mozilla.geckoview.WebExtension

/** The "Add this extension?" and "allow more permissions?" sheets. */
object ExtensionPrompts {

    fun install(
        c: Context, host: FrameLayout, p: Palette, ext: WebExtension, icon: Bitmap?,
        permissions: List<String>, origins: List<String>, sheet: Sheet, done: (Boolean, Boolean) -> Unit,
    ) {
        var answered = false
        val name = ext.metaData.name ?: ext.id
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 4.dp, 22.dp, 0)
        }
        box.addView(header(c, p, icon, c.getString(R.string.add_extension_title, name), ext.metaData.creatorName))
        val lines = describe(permissions, origins)
        if (lines.isNotEmpty()) {
            box.addView(c.label(c.getString(R.string.extension_can), 14f, p.text2, bold = true).apply { setPadding(2.dp, 18.dp, 0, 6.dp) })
            lines.forEach { box.addView(bullet(c, p, it)) }
        }
        var allowPrivate = false
        box.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(2.dp, 16.dp, 0, 4.dp)
            addView(c.label(c.getString(R.string.run_in_private), 15f, p.text).apply { layoutParams = lp(0, WRAP, 1f) })
            val sw = MiniSwitch(c, p)
            sw.setOnClickListener { Haptics.tick(it); sw.setChecked(!sw.checked, true); allowPrivate = sw.checked }
            addView(sw, lp(42.dp, 25.dp))
        })
        box.addView(buttons(c, p, c.getString(R.string.cancel), c.getString(R.string.add)) { ok ->
            answered = true
            done(ok, ok && allowPrivate)
            sheet.dismiss()
        })
        sheet.onDismiss = { if (!answered) done(false, false); sheet.onDismiss = null }
        sheet.show(box)
    }

    fun permissions(
        c: Context, p: Palette, ext: WebExtension, permissions: List<String>, origins: List<String>, sheet: Sheet, done: (Boolean) -> Unit,
    ) {
        var answered = false
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 4.dp, 22.dp, 0)
        }
        box.addView(header(c, p, null, c.getString(R.string.extension_wants_more, ext.metaData.name ?: ext.id), null))
        describe(permissions, origins).forEach { box.addView(bullet(c, p, it)) }
        box.addView(buttons(c, p, c.getString(R.string.block), c.getString(R.string.allow)) { ok ->
            answered = true
            done(ok)
            sheet.dismiss()
        })
        sheet.onDismiss = { if (!answered) done(false); sheet.onDismiss = null }
        sheet.show(box)
    }

    private fun describe(permissions: List<String>, origins: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        permissions.forEach { ExtensionManager.describe(it)?.let(out::add) }
        if (origins.any { it == "<all_urls>" || it.contains("*://*/") }) out += "Access your data for all websites"
        else origins.take(4).forEach { out += ExtensionManager.describeOrigin(it) }
        if (origins.size > 4 && !out.contains("Access your data for all websites")) out += "Access your data for ${origins.size - 4} other sites"
        return out.toList()
    }

    private fun header(c: Context, p: Palette, icon: Bitmap?, title: String, subtitle: String?): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val iconView: View = if (icon != null) ImageView(c).apply { setImageBitmap(icon) }
        else FrameLayout(c).apply {
            background = rounded(withAlpha(p.accent, 0.12f), Radius.card)
            addView(c.icon(R.drawable.ic_jigsaw, p.accent, 22).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
        }
        addView(iconView, lp(48.dp, 48.dp).margins(r = 14.dp))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(0, WRAP, 1f)
            addView(c.label(title, 19f, p.text, bold = true, tracking = -0.015f).apply { setLineSpacing(1f.dp, 1f) })
            subtitle?.let { addView(c.label(it, 13f, p.text2).apply { setPadding(0, 4.dp, 0, 0) }) }
        })
    }

    private fun bullet(c: Context, p: Palette, text: String) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(2.dp, 5.dp, 0, 5.dp)
        addView(View(c).apply { background = rounded(p.text3, 3f.dp) }, lp(6.dp, 6.dp).margins(t = 8.dp, r = 12.dp))
        addView(c.label(text, 15f, p.text).apply { layoutParams = lp(0, WRAP, 1f); setLineSpacing(2f.dp, 1f) })
    }

    private fun buttons(c: Context, p: Palette, no: String, yes: String, onAnswer: (Boolean) -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 22.dp, 0, 0)
        fun pill(text: String, bg: Int, fg: Int, value: Boolean) = c.label(text, 15f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = ripple(withAlpha(fg, 0.15f), Radius.pill, rounded(bg, Radius.pill))
            isClickable = true
            sinkOnPress()
            setOnClickListener { if (value) Haptics.confirm(it) else Haptics.tap(it); onAnswer(value) }
        }
        addView(pill(no, p.field, p.text, false), lp(0, 50.dp, 1f).margins(r = 6.dp))
        addView(pill(yes, p.ink, p.onInk, true), lp(0, 50.dp, 1f).margins(l = 6.dp))
    }
}
