package co.dothq.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import co.dothq.browser.DotApp
import co.dothq.browser.R
import co.dothq.browser.core.Favicons
import co.dothq.browser.core.Site
import co.dothq.browser.core.Urls
import java.text.NumberFormat
import java.util.Calendar
import java.util.concurrent.Executors

interface NewTabHost {
    fun ntpOpen(url: String, newTab: Boolean)
    fun ntpSiteMenu(site: Site, anchor: View)
    fun ntpPrivacy()
    fun ntpFocusSearch()
}

/**
 * The new tab page. Quiet by design: the living ring, a greeting, your sites, and one number —
 * how many trackers Dot has stopped for you — painted in the ring's colours.
 */
@SuppressLint("ViewConstructor")
class NewTabPage(context: Context, private val app: DotApp, private val cb: NewTabHost) : FrameLayout(context) {
    private var private = false
    private lateinit var p: Palette
    private val backdrop = AuroraBackdrop(context)
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }
    private var counter: TextView? = null
    private var sinceLabel: TextView? = null
    private var grid: GridLayout? = null
    private var lastCount = -1L
    private val io = Executors.newSingleThreadExecutor()
    var toolbarAtBottom = true

    init {
        addView(backdrop, flp(MATCH, MATCH))
        addView(scroll, flp(MATCH, MATCH))
        isClickable = true
    }

    fun bind(palette: Palette, privateMode: Boolean) {
        p = palette
        private = privateMode
        setBackgroundColor(p.bg)
        backdrop.setPrivate(privateMode)
        backdrop.intensity = when {
            privateMode -> 0.30f
            p.isDark -> 0.20f
            else -> 0.13f
        }
        scroll.removeAllViews()
        scroll.addView(if (privateMode) buildPrivate() else buildNormal())
        lastCount = -1
        refresh()
    }

    /** Updates live bits: stats, shortcuts. */
    fun refresh() {
        if (private || !::p.isInitialized) return
        val total = app.tabs.totalTrackersStopped()
        sinceLabel?.text = context.getString(if (total == 0L) R.string.trackers_none_yet else R.string.trackers_stopped_since)
        counter?.let { c ->
            if (total != lastCount) {
                val fmt = NumberFormat.getIntegerInstance()
                if (lastCount < 0) countUp(c, total, { fmt.format(it) }) { gradientText(c, ringColors(context)) }
                else { c.text = fmt.format(total); gradientText(c, ringColors(context)) }
                lastCount = total
            }
        }
        loadShortcuts()
    }

    fun setAnimating(on: Boolean) {
        backdrop.animated = on
        if (on) backdrop.invalidate()
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return context.getString(
            when (h) {
                in 5..11 -> R.string.greeting_morning
                in 12..17 -> R.string.greeting_afternoon
                in 18..23 -> R.string.greeting_evening
                else -> R.string.greeting_night
            }
        )
    }

    private fun buildNormal(): View {
        val c = context
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, if (toolbarAtBottom) 72.dp else 40.dp, 24.dp, 32.dp)
        }
        val ring = DotRingView(c)
        col.addView(ring, lp(72.dp, 72.dp).margins(l = (-14).dp, t = (-8).dp, b = 8.dp))

        col.addView(c.label(greeting(), 36f, p.text, bold = true, tracking = -0.035f).apply {
            setPadding(0, 0, 0, 8.dp)
        })
        col.addView(c.label(c.getString(R.string.tagline), 17f, p.text2, tracking = -0.01f))

        // Shortcuts
        val g = GridLayout(c).apply {
            columnCount = 4
            useDefaultMargins = false
        }
        grid = g
        col.addView(g, lp(MATCH, WRAP).margins(t = 36.dp, l = (-6).dp, r = (-6).dp))

        // Privacy stat
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(p.ripple, 16f.dp, rounded(withAlpha(p.surface, if (p.isDark) 0.7f else 0.85f), 16f.dp, p.border, 1))
            setPadding(20.dp, 18.dp, 20.dp, 20.dp)
            isClickable = true
            setOnClickListener { Haptics.tap(it); cb.ntpPrivacy() }
            sinkOnPress(0.98f)
        }
        card.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(c.icon(R.drawable.ic_shield_check, p.shield, 18).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 8.dp
            })
            addView(c.label(c.getString(R.string.settings_privacy).uppercase(), 11f, p.text3, bold = true, tracking = 0.08f))
        })
        val number = c.label("0", 44f, p.text, bold = true, tracking = -0.04f).apply {
            setPadding(0, 14.dp, 0, 2.dp)
        }
        counter = number
        card.addView(number)
        card.addView(c.label(c.getString(R.string.trackers_stopped_label), 15f, p.text, bold = true))
        val since = c.label(c.getString(R.string.trackers_stopped_since), 14f, p.text2).apply {
            setPadding(0, 3.dp, 0, 0)
            setLineSpacing(2f.dp, 1f)
        }
        sinceLabel = since
        card.addView(since)
        col.addView(card, lp(MATCH, WRAP).margins(t = 28.dp))
        return col
    }

    private fun buildPrivate(): View {
        val c = context
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, if (toolbarAtBottom) 96.dp else 56.dp, 28.dp, 32.dp)
        }
        col.addView(FrameLayout(c).apply {
            background = oval(withAlpha(p.accent, 0.16f))
            addView(c.icon(R.drawable.ic_private, p.accent, 30).apply {
                layoutParams = flp(30.dp, 30.dp, Gravity.CENTER)
            })
        }, lp(64.dp, 64.dp).margins(b = 24.dp))
        col.addView(c.label(c.getString(R.string.private_ntp_title), 32f, p.text, bold = true, tracking = -0.035f).apply {
            setPadding(0, 0, 0, 12.dp)
        })
        col.addView(c.label(c.getString(R.string.private_ntp_body), 15f, p.text2).apply {
            setLineSpacing(4f.dp, 1f)
        })
        val chips = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 28.dp, 0, 0)
        }
        listOf(
            R.drawable.ic_history to "No history",
            R.drawable.ic_cookie to "Cookies forgotten on close",
            R.drawable.ic_shield_check to "Trackers blocked",
        ).forEach { (icon, text) ->
            chips.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 7.dp, 0, 7.dp)
                addView(c.icon(icon, p.accent, 18).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dp })
                addView(c.label(text, 15f, p.text))
            })
        }
        col.addView(chips)
        return col
    }

    private fun loadShortcuts() {
        val g = grid ?: return
        io.execute {
            val bookmarks = app.db.bookmarks().take(4)
            val top = app.db.topSites(8)
            val merged = LinkedHashMap<String, Site>()
            (bookmarks + top).forEach { s -> merged.putIfAbsent(Urls.host(s.url), s) }
            DEFAULTS.forEach { s -> if (merged.size < 8) merged.putIfAbsent(Urls.host(s.url), s) }
            val sites = merged.values.take(8)
            post { if (grid === g) fillGrid(g, sites) }
        }
    }

    private fun fillGrid(g: GridLayout, sites: List<Site>) {
        g.removeAllViews()
        val c = context
        val cellW = ((width - 48.dp + 12.dp).coerceAtLeast(4 * 72.dp)) / 4
        sites.forEach { site ->
            val host = Urls.host(site.url)
            val tile = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 6.dp, 0, 10.dp)
                pressable(p, Radius.card)
                setOnClickListener { Haptics.tap(it); cb.ntpOpen(site.url, false) }
                setOnLongClickListener { Haptics.heavy(it); cb.ntpSiteMenu(site, it); true }
            }
            val box = FrameLayout(c).apply {
                background = rounded(withAlpha(p.field, 0.9f), 16f.dp, p.border, if (p.isDark) 0 else 1)
            }
            val icon = ImageView(c)
            box.addView(icon, flp(28.dp, 28.dp, Gravity.CENTER))
            icon.bindFavicon(Favicons.memoryCached(host), host)
            Favicons.get(c, host, allowNetwork = true) { bmp -> if (bmp != null) icon.bindFavicon(bmp, host) }
            tile.addView(box, lp(56.dp, 56.dp).margins(b = 8.dp))
            val name = site.title.takeIf { it.isNotBlank() && it.length <= 14 } ?: prettyName(host)
            tile.addView(c.label(name, 12f, p.text2).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(4.dp, 0, 4.dp, 0)
            }, lp(MATCH, WRAP))
            g.addView(tile, GridLayout.LayoutParams().apply { width = cellW })
        }
    }

    private fun prettyName(host: String): String {
        val parts = host.split('.')
        val core = if (parts.size >= 2) parts[parts.size - 2] else host
        return core.replaceFirstChar { it.uppercaseChar() }
    }

    companion object {
        val DEFAULTS = listOf(
            Site("https://www.dothq.org/", "Dot HQ", 0),
            Site("https://en.wikipedia.org/", "Wikipedia", 0),
            Site("https://github.com/dothq", "GitHub", 0),
            Site("https://duckduckgo.com/", "DuckDuckGo", 0),
        )
    }
}
