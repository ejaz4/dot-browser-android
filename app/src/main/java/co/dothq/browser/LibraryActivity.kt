package co.dothq.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import co.dothq.browser.core.Favicons
import co.dothq.browser.core.Site
import co.dothq.browser.core.Urls
import co.dothq.browser.ui.Anchor
import co.dothq.browser.ui.DotMenu
import co.dothq.browser.ui.Fonts
import co.dothq.browser.ui.Haptics
import co.dothq.browser.ui.MATCH
import co.dothq.browser.ui.MenuEntry
import co.dothq.browser.ui.Palette
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.WRAP
import co.dothq.browser.ui.bindFavicon
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
import co.dothq.browser.ui.style
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import java.util.concurrent.Executors

/** History and bookmarks, one screen with a segmented switch. */
class LibraryActivity : AppCompatActivity() {
    private val app get() = application as DotApp
    private lateinit var p: Palette
    private lateinit var root: FrameLayout
    private lateinit var recycler: RecyclerView
    private lateinit var empty: LinearLayout
    private lateinit var segHolder: FrameLayout
    private lateinit var clearButton: TextView
    private var page = PAGE_HISTORY
    private var filter = ""
    private var rows: List<Row> = emptyList()
    private val io = Executors.newSingleThreadExecutor()
    private var menu: DotMenu? = null

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val site: Site) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        p = Palette.normal(this)
        page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_HISTORY
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.isDark
            isAppearanceLightNavigationBars = !p.isDark
        }

        root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, flp(MATCH, MATCH))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 0)
        }
        top.addView(iconButton(R.drawable.ic_arrow_left, p.icon, p, desc = getString(R.string.back)).apply {
            setOnClickListener { finish() }
        })
        top.addView(View(this), lp(0, 1, 1f))
        clearButton = label(getString(R.string.clear_history), 14f, getColor(R.color.dot_danger), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(14.dp, 0, 14.dp, 0)
            pressable(p, Radius.field)
            setOnClickListener { confirmClear() }
        }
        top.addView(clearButton, lp(WRAP, 40.dp))
        column.addView(top, lp(MATCH, WRAP))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 4.dp, 16.dp, 14.dp)
        }
        segHolder = FrameLayout(this)
        titleRow.addView(segHolder, lp(MATCH, WRAP))
        column.addView(titleRow, lp(MATCH, WRAP))

        // Search field
        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.field, Radius.field)
            setPadding(12.dp, 0, 12.dp, 0)
            addView(icon(R.drawable.ic_search, p.text3, 16).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 10.dp })
            addView(EditText(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                style(15f, p.text)
                setHintTextColor(p.text3)
                hint = getString(R.string.search_library)
                isSingleLine = true
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) { filter = s?.toString() ?: ""; load() }
                })
            }, lp(0, MATCH, 1f))
        }
        column.addView(search, lp(MATCH, 42.dp).margins(l = 16.dp, r = 16.dp, b = 8.dp))

        val content = FrameLayout(this)
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = Adapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, 24.dp)
            clipToPadding = false
        }
        content.addView(recycler, flp(MATCH, MATCH))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40.dp, 0, 40.dp, 80.dp)
        }
        content.addView(empty, flp(MATCH, WRAP, Gravity.CENTER))
        column.addView(content, lp(MATCH, 0, 1f))
        setContentView(root)
        renderSegments()
        load()
    }

    private fun renderSegments() {
        segHolder.removeAllViews()
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        fun tab(title: String, key: String) {
            val active = page == key
            wrap.addView(label(title, if (active) 30f else 20f, if (active) p.text else p.text3, bold = true, tracking = -0.035f).apply {
                setPadding(0, 0, 20.dp, 0)
                setOnClickListener {
                    if (page != key) { Haptics.tick(it); page = key; renderSegments(); load() }
                }
            })
        }
        tab(getString(R.string.history), PAGE_HISTORY)
        tab(getString(R.string.bookmarks), PAGE_BOOKMARKS)
        segHolder.addView(wrap)
        clearButton.visibility = if (page == PAGE_HISTORY) View.VISIBLE else View.GONE
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun load() {
        val f = filter
        val pg = page
        io.execute {
            val sites = if (pg == PAGE_HISTORY) app.db.history(500, f) else app.db.bookmarks(f)
            val out = mutableListOf<Row>()
            if (pg == PAGE_HISTORY) {
                val today = startOfDay(0)
                val yesterday = startOfDay(-1)
                var current: String? = null
                sites.forEach { s ->
                    val h = when {
                        s.time >= today -> getString(R.string.today)
                        s.time >= yesterday -> getString(R.string.yesterday)
                        else -> DateUtils.formatDateTime(this, s.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY)
                    }
                    if (h != current) { out += Row.Header(h); current = h }
                    out += Row.Item(s)
                }
            } else sites.forEach { out += Row.Item(it) }
            runOnUiThread {
                if (pg != page) return@runOnUiThread
                rows = out
                recycler.adapter?.notifyDataSetChanged()
                showEmpty(out.isEmpty())
            }
        }
    }

    private fun showEmpty(show: Boolean) {
        empty.removeAllViews()
        empty.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val history = page == PAGE_HISTORY
        empty.addView(FrameLayout(this).apply {
            background = oval(p.field)
            addView(icon(if (history) R.drawable.ic_history else R.drawable.ic_star, p.icon, 26).apply {
                layoutParams = flp(26.dp, 26.dp, Gravity.CENTER)
            })
        }, lp(64.dp, 64.dp).margins(b = 18.dp))
        empty.addView(label(getString(if (history) R.string.library_empty_history else R.string.library_empty_bookmarks), 18f, p.text, bold = true))
        empty.addView(label(getString(if (history) R.string.library_empty_history_body else R.string.library_empty_bookmarks_body), 14f, p.text2).apply {
            gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 0)
            setLineSpacing(3f.dp, 1f)
        })
    }

    private fun startOfDay(offset: Int) = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun open(site: Site, newTab: Boolean = false, private: Boolean = false) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, site.url).putExtra(EXTRA_NEW_TAB, newTab).putExtra(EXTRA_PRIVATE, private))
        finish()
    }

    private val sheet by lazy { co.dothq.browser.ui.Sheet(root, p) }

    private fun itemMenu(site: Site, anchor: View, x: Int, y: Int) {
        val host = Urls.host(site.url)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 2.dp, 16.dp, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(6.dp, 0, 6.dp, 14.dp)
                addView(ImageView(context).apply { bindFavicon(Favicons.memoryCached(host), host) }, lp(22.dp, 22.dp).margins(r = 12.dp))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = lp(0, WRAP, 1f)
                    addView(label(site.title.ifBlank { host }, 16f, p.text, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(label(Urls.pretty(site.url), 13f, p.text3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(0, 3.dp, 0, 0) })
                })
            })
            addView(co.dothq.browser.ui.actionList(context, p, listOf(
                co.dothq.browser.ui.SheetAction(R.drawable.ic_open_new, getString(R.string.open_in_new_tab)) { sheet.dismiss(); open(site, newTab = true) },
                co.dothq.browser.ui.SheetAction(R.drawable.ic_private, getString(R.string.open_in_private_tab)) { sheet.dismiss(); open(site, private = true) },
                co.dothq.browser.ui.SheetAction(R.drawable.ic_copy, getString(R.string.copy_link)) {
                    sheet.dismiss()
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("URL", site.url))
                },
                co.dothq.browser.ui.SheetAction(R.drawable.ic_bin, getString(R.string.delete), danger = true) {
                    sheet.dismiss()
                    io.execute {
                        if (page == PAGE_HISTORY) app.db.deleteHistory(site.url) else app.db.removeBookmark(site.url)
                        runOnUiThread { load() }
                    }
                },
            )))
        }
        sheet.show(box)
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> io.execute { app.db.clearHistory(); runOnUiThread { load() } } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet.isShowing) sheet.dismiss() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 0) return Holder(label("", 12f, p.text3, bold = true, tracking = 0.06f).apply {
                setPadding(20.dp, 18.dp, 20.dp, 6.dp)
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            })
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 60.dp
                setPadding(16.dp, 8.dp, 16.dp, 8.dp)
                background = ripple(p.ripple, 0f)
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                addView(FrameLayout(context).apply {
                    background = rounded(p.field, Radius.field)
                    addView(ImageView(context), flp(20.dp, 20.dp, Gravity.CENTER))
                }, lp(40.dp, 40.dp).margins(r = 14.dp))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = lp(0, WRAP, 1f)
                    addView(label("", 15f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(label("", 13f, p.text3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 3.dp, 0, 0) })
                })
                addView(label("", 12f, p.text3))
            }
            return Holder(row)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: Holder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> (h.itemView as TextView).text = r.title.uppercase()
                is Row.Item -> {
                    val row = h.itemView as LinearLayout
                    val iconView = (row.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView
                    val texts = row.getChildAt(1) as LinearLayout
                    val host = Urls.host(r.site.url)
                    iconView.bindFavicon(Favicons.memoryCached(host), host)
                    Favicons.get(this@LibraryActivity, host, allowNetwork = false) { bmp -> if (bmp != null) iconView.bindFavicon(bmp, host) }
                    (texts.getChildAt(0) as TextView).text = r.site.title.ifBlank { Urls.pretty(r.site.url) }
                    (texts.getChildAt(1) as TextView).text = Urls.pretty(r.site.url)
                    (row.getChildAt(2) as TextView).text = if (page == PAGE_HISTORY)
                        DateUtils.formatDateTime(this@LibraryActivity, r.site.time, DateUtils.FORMAT_SHOW_TIME) else ""
                    var lastX = 0
                    var lastY = 0
                    row.setOnTouchListener { _, e -> lastX = e.x.toInt(); lastY = e.y.toInt(); false }
                    row.setOnClickListener { Haptics.tap(it); open(r.site) }
                    row.setOnLongClickListener { Haptics.heavy(it); itemMenu(r.site, it, lastX, lastY); true }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val EXTRA_URL = "url"
        const val EXTRA_NEW_TAB = "new_tab"
        const val EXTRA_PRIVATE = "private"
        const val PAGE_HISTORY = "history"
        const val PAGE_BOOKMARKS = "bookmarks"
    }
}
