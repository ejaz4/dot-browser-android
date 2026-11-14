package co.dothq.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import co.dothq.browser.R
import co.dothq.browser.tabs.Tab
import co.dothq.browser.tabs.TabManager
import kotlin.math.abs
import kotlin.math.max

interface SwitcherHost {
    val tabManager: TabManager
    fun paletteFor(private: Boolean): Palette
    fun onSwitcherPick(tab: Tab)
    fun onSwitcherNewTab(private: Boolean)
    fun onSwitcherClose(tab: Tab)
    fun onSwitcherCloseAll(private: Boolean)
    fun onSwitcherModeChanged(private: Boolean)
}

/** Keeps a child at a fixed aspect ratio (height = width × ratio). */
class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w * ratio).toInt(), MeasureSpec.EXACTLY))
    }
}

/**
 * The tab overview: a grid of cards modelled on desktop Dot's tab previews — favicon, title and
 * close button above a snapshot of the page. The current tab wears the Dot ring.
 */
class TabSwitcher(private val host: FrameLayout, private val switcherHost: SwitcherHost) {
    private var root: FrameLayout? = null
    private var private = false
    private var items: MutableList<Tab> = mutableListOf()
    private var adapter: CardAdapter? = null
    private var recycler: RecyclerView? = null
    private lateinit var p: Palette
    val isShowing get() = root != null
    val showingPrivate get() = private

    fun show(privateMode: Boolean) {
        if (root != null) return
        private = privateMode
        val r = FrameLayout(host.context).apply { isClickable = true }
        host.addView(r, flp(MATCH, MATCH))
        root = r
        render(animateIn = true)
        r.alpha = 0f
        r.animate().alpha(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
    }

    fun hide(then: (() -> Unit)? = null) {
        val r = root ?: run { then?.invoke(); return }
        root = null
        adapter = null
        recycler = null
        r.animate().alpha(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).withEndAction {
            host.removeView(r)
        }.start()
        then?.invoke()
    }

    /** Called when tabs are added/removed/changed elsewhere. */
    fun refresh() {
        if (root == null) return
        val fresh = switcherHost.tabManager.tabs.filter { it.isPrivate == private }
        if (fresh.map { it.id } != items.map { it.id }) {
            items = fresh.toMutableList()
            adapter?.notifyDataSetChanged()
            updateHeaderCounts()
            updateEmpty()
        }
    }

    fun refreshTab(tab: Tab) {
        val i = items.indexOf(tab)
        if (i >= 0) adapter?.notifyItemChanged(i, "content")
    }

    private var cascadeUntil = 0L
    private var tabsCount: TextView? = null
    private var privateCount: TextView? = null
    private var empty: View? = null

    private fun setMode(privateMode: Boolean) {
        if (privateMode == private) return
        private = privateMode
        switcherHost.onSwitcherModeChanged(private)
        val r = root ?: return
        r.animate().alpha(0.0f).setDuration(Motion.FAST).withEndAction {
            render(animateIn = true)
            r.animate().alpha(1f).setDuration(Motion.NORMAL).start()
        }.start()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun render(animateIn: Boolean) {
        val r = root ?: return
        val c = r.context
        p = switcherHost.paletteFor(private)
        r.removeAllViews()
        r.setBackgroundColor(p.frame)
        items = switcherHost.tabManager.tabs.filter { it.isPrivate == private }.toMutableList()

        val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        r.addView(column, flp(MATCH, MATCH))

        // Header: title + segmented control
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 14.dp, 16.dp, 10.dp)
        }
        header.addView(c.label(c.getString(R.string.tabs_title), 26f, p.text, bold = true, tracking = -0.03f).apply {
            layoutParams = lp(0, WRAP, 1f)
        })
        header.addView(segmented(c))
        column.addView(header, lp(MATCH, WRAP))

        val content = FrameLayout(c)
        column.addView(content, lp(MATCH, 0, 1f))

        val rv = RecyclerView(c).apply {
            layoutManager = GridLayoutManager(c, if (host.width > 600.dp) 3 else 2)
            setPadding(10.dp, 4.dp, 10.dp, 24.dp)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator?.changeDuration = 0
        }
        val a = CardAdapter()
        rv.adapter = a
        adapter = a
        recycler = rv
        content.addView(rv, flp(MATCH, MATCH))
        attachTouch(rv)

        val e = emptyState(c)
        content.addView(e, flp(MATCH, WRAP, Gravity.CENTER))
        empty = e
        updateEmpty()

        // Bottom bar
        val bar = FrameLayout(c).apply {
            setPadding(8.dp, 8.dp, 8.dp, 10.dp)
        }
        bar.addView(textButton(c, c.getString(R.string.close_all), p.text2) {
            if (items.isNotEmpty()) switcherHost.onSwitcherCloseAll(private)
        }, flp(WRAP, 48.dp, Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(newTabPill(c), flp(WRAP, 48.dp, Gravity.CENTER))
        bar.addView(textButton(c, c.getString(R.string.done), p.text, bold = true) {
            val sel = switcherHost.tabManager.selected
            val target = if (sel != null && sel.isPrivate == private) sel else items.lastOrNull() ?: sel
            if (target != null) switcherHost.onSwitcherPick(target)
        }, flp(WRAP, 48.dp, Gravity.END or Gravity.CENTER_VERTICAL))
        column.addView(bar, lp(MATCH, WRAP))

        // Scroll the current tab into view
        val selIndex = items.indexOf(switcherHost.tabManager.selected)
        if (selIndex >= 0) rv.scrollToPosition(selIndex)

        if (animateIn) cascadeUntil = android.os.SystemClock.uptimeMillis() + 500
    }

    private fun updateEmpty() {
        empty?.visibility = if (items.isEmpty() && private) View.VISIBLE else View.GONE
    }

    private fun updateHeaderCounts() {
        tabsCount?.text = switcherHost.tabManager.normalTabs().size.toString()
        privateCount?.text = switcherHost.tabManager.privateTabs().size.toString()
    }

    private fun segmented(c: Context): View {
        val wrap = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.field, Radius.field)
            setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        }
        fun seg(icon: Int, label: String, count: Int, active: Boolean, onClick: () -> Unit): Pair<View, TextView> {
            val countView = c.label(count.toString(), 12f, if (active) p.text2 else p.text3, bold = true)
            val v = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp, 0, 12.dp, 0)
                if (active) {
                    background = rounded(p.surface, Radius.field - 2.dp)
                    elevation = 1.5f.dp
                } else pressable(p, Radius.field - 2.dp)
                addView(c.icon(icon, if (active) p.icon else p.text2, 16).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 6.dp
                })
                addView(c.label(label, 14f, if (active) p.text else p.text2, bold = active))
                addView(countView.apply { setPadding(6.dp, 0, 0, 0) })
                setOnClickListener { Haptics.tick(it); onClick() }
            }
            wrap.addView(v, lp(WRAP, 34.dp))
            return v to countView
        }
        val tm = switcherHost.tabManager
        tabsCount = seg(R.drawable.ic_pages, c.getString(R.string.tabs_title), tm.normalTabs().size, !private) { setMode(false) }.second
        privateCount = seg(R.drawable.ic_private, c.getString(R.string.private_tabs_title), tm.privateTabs().size, private) { setMode(true) }.second
        return wrap
    }

    private fun emptyState(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(40.dp, 0, 40.dp, 40.dp)
        addView(FrameLayout(c).apply {
            background = oval(p.field)
            addView(c.icon(R.drawable.ic_private, p.icon, 28).apply {
                layoutParams = flp(28.dp, 28.dp, Gravity.CENTER)
            })
        }, lp(64.dp, 64.dp).margins(b = 18.dp))
        addView(c.label(c.getString(R.string.no_private_tabs), 18f, p.text, bold = true))
        addView(c.label(c.getString(R.string.no_private_tabs_body), 14f, p.text2).apply {
            gravity = Gravity.CENTER
            setLineSpacing(3f.dp, 1f)
            setPadding(0, 8.dp, 0, 0)
        })
    }

    private fun textButton(c: Context, text: String, color: Int, bold: Boolean = false, onClick: () -> Unit) =
        c.label(text, 15f, color, bold).apply {
            gravity = Gravity.CENTER
            setPadding(16.dp, 0, 16.dp, 0)
            pressable(p, Radius.field)
            setOnClickListener { Haptics.tap(it); onClick() }
        }

    private fun newTabPill(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(18.dp, 0, 22.dp, 0)
        background = ripple(withAlpha(p.onInk, 0.2f), Radius.pill, rounded(p.ink, Radius.pill))
        elevation = 3f.dp
        isClickable = true
        addView(c.icon(R.drawable.ic_add, p.onInk, 18).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 8.dp
        })
        addView(c.label(c.getString(if (private) R.string.private_tab_title else R.string.new_tab), 15f, p.onInk, bold = true))
        sinkOnPress()
        setOnClickListener { Haptics.confirm(it); switcherHost.onSwitcherNewTab(private) }
    }

    private fun attachTouch(rv: RecyclerView) {
        val drag = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        val swipe = ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(drag, swipe) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                switcherHost.tabManager.moveTab(items[from], items[to])
                val t = items.removeAt(from)
                items.add(to, t)
                adapter?.notifyItemMoved(from, to)
                Haptics.tick(rv)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos < 0 || pos >= items.size) return
                val tab = items.removeAt(pos)
                adapter?.notifyItemRemoved(pos)
                Haptics.reject(rv)
                switcherHost.onSwitcherClose(tab)
                updateHeaderCounts()
                updateEmpty()
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.setDuration(Motion.FAST)?.start()
                    viewHolder?.itemView?.let { Haptics.heavy(it) }
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.alpha = 1f
                vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(Motion.FAST).start()
            }

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    vh.itemView.alpha = 1f - (abs(dX) / max(1, vh.itemView.width)).coerceIn(0f, 1f) * 0.9f
                }
                super.onChildDraw(c, rv, vh, dX, dY, state, active)
            }
        }).attachToRecyclerView(rv)
    }

    /* ---- Cards ------------------------------------------------------------------------------ */

    private inner class CardHolder(val outer: FrameLayout) : RecyclerView.ViewHolder(outer) {
        /** The visual card; animated separately so RecyclerView's item animator can't cut it short. */
        val frame = FrameLayout(outer.context)
        val card: LinearLayout
        val favicon: ImageView
        val title: TextView
        val close: FrameLayout
        val thumb: TopCropImageView
        val placeholder: FrameLayout

        init {
            val c = outer.context
            outer.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(5.dp, 5.dp, 5.dp, 5.dp) }
            outer.addView(frame, flp(MATCH, WRAP))
            frame.setPadding(3.dp, 3.dp, 3.dp, 3.dp)
            card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(p.surface, Radius.card, p.border, 1)
                clipToOutline = true
                elevation = if (p.isDark) 0f else 1f.dp
            }
            frame.addView(card, flp(MATCH, WRAP))

            val head = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp, 0, 2.dp, 0)
            }
            favicon = ImageView(c)
            head.addView(favicon, lp(16.dp, 16.dp).margins(r = 9.dp))
            title = c.label("", 13f, p.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            head.addView(title, lp(0, WRAP, 1f))
            close = c.iconButton(R.drawable.ic_close, p.text2, p, boxDp = 36, iconDp = 13, desc = c.getString(R.string.close_tab))
            head.addView(close)
            card.addView(head, lp(MATCH, 40.dp))

            val shot = AspectFrame(c, 1.18f).apply { setBackgroundColor(p.bg) }
            thumb = TopCropImageView(c)
            shot.addView(thumb, flp(MATCH, MATCH))
            placeholder = FrameLayout(c)
            shot.addView(placeholder, flp(MATCH, MATCH))
            card.addView(View(c).apply { setBackgroundColor(p.divider) }, lp(MATCH, 1))
            card.addView(shot, lp(MATCH, WRAP))

            frame.sinkOnPress(0.97f)
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardHolder(FrameLayout(parent.context))

        override fun onBindViewHolder(h: CardHolder, position: Int) {
            val tab = items[position]
            val c = h.frame.context
            val selected = tab === switcherHost.tabManager.selected
            h.frame.background = if (selected) RingBorder(c, Radius.card + 3.dp, 2.25f.dp) else null
            h.title.text = tab.displayTitle(c.getString(if (tab.isPrivate) R.string.private_tab_title else R.string.new_tab_title))
            if (tab.isHome) {
                h.favicon.setImageResource(if (tab.isPrivate) R.drawable.ic_private else R.drawable.dot_ring)
                h.favicon.imageTintList = if (tab.isPrivate) android.content.res.ColorStateList.valueOf(p.icon) else null
            } else h.favicon.bindFavicon(tab.favicon, tab.host)

            val shot = tab.thumbnail
            h.placeholder.removeAllViews()
            if (shot != null && !tab.isHome) {
                h.thumb.visibility = View.VISIBLE
                h.thumb.setImageBitmap(shot)
            } else {
                h.thumb.visibility = View.GONE
                h.thumb.setImageDrawable(null)
                val mark: View = if (tab.isHome) {
                    if (tab.isPrivate) c.icon(R.drawable.ic_private, p.text3, 36)
                    else DotRingView(c).apply { halo = false; spinning = false }
                } else ImageView(c).apply { bindFavicon(tab.favicon, tab.host) }
                h.placeholder.setBackgroundColor(p.surface)
                h.placeholder.addView(mark, flp(40.dp, 40.dp, Gravity.CENTER))
            }

            if (android.os.SystemClock.uptimeMillis() < cascadeUntil) {
                h.frame.alpha = 0f
                h.frame.translationY = 22f.dp
                h.frame.scaleX = 0.96f; h.frame.scaleY = 0.96f
                h.frame.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(position * 38L).setDuration(Motion.SLOW).setInterpolator(Motion.ease).start()
            }
            h.frame.setOnClickListener {
                // The chosen card swells toward you, then the page takes over.
                h.frame.animate().scaleX(1.05f).scaleY(1.05f).setDuration(Motion.FAST).setInterpolator(Motion.ease)
                    .withEndAction { switcherHost.onSwitcherPick(tab) }.start()
            }
            h.close.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos < 0) return@setOnClickListener
                Haptics.reject(it)
                items.removeAt(pos)
                notifyItemRemoved(pos)
                switcherHost.onSwitcherClose(tab)
                updateHeaderCounts()
                updateEmpty()
            }
            h.frame.contentDescription = h.title.text
        }
    }
}
