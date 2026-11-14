package co.dothq.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.TypefaceSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import co.dothq.browser.R
import kotlin.math.abs

/* =============================================================================================
 * Toolbar — one row, like desktop Dot's: the address field (with identity + privacy shield),
 * the tab counter and the menu button. The field's outline doubles as the loading indicator.
 * =========================================================================================== */

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ToolbarView(context: Context) : LinearLayout(context) {
    interface Listener {
        fun onAddressTap()
        fun onAddressLongPress(anchor: View)
        /** Returns true if there was a tab that way. */
        fun onSwipeTabs(direction: Int): Boolean
        fun onIdentityTap(anchor: View)
        fun onPillAction()
        fun onTabsTap()
        fun onTabsLongPress(anchor: View)
        fun onMenuTap(anchor: View)
        fun onCancelEdit()
        fun onSubmit(text: String)
        fun onQueryChanged(text: String)
    }

    var listener: Listener? = null
    private lateinit var p: Palette

    val divider = View(context)
    val row = LinearLayout(context)
    val pillFrame = FrameLayout(context)
    val aurora = AuroraOutline(context)
    val pill = LinearLayout(context)
    val identity = ImageView(context)
    private val identityBox = FrameLayout(context)
    private val display = LinearLayout(context)
    val hostText = TextView(context)
    val pathText = TextView(context)
    val edit = EditText(context)
    val shieldChip = LinearLayout(context)
    private val shieldIcon = ImageView(context)
    private val shieldCount = TextView(context)
    val action = FrameLayout(context)
    private val actionIcon = ImageView(context)
    val tabsButton = FrameLayout(context)
    val tabCount = TabCountView(context)
    val menuButton = FrameLayout(context)
    private val menuIcon = ImageView(context)
    val cancel = TextView(context)

    var editing = false
        private set
    var atBottom = true
        private set
    private var suppressWatcher = false

    init {
        orientation = VERTICAL
        Fonts.init(context)
        build()
    }

    private fun build() {
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(6.dp, 0, 4.dp, 0)

        // Address pill + halo
        pillFrame.addView(aurora, flp(MATCH, MATCH).margins(t = 1.dp, b = 1.dp))
        pill.orientation = HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pillFrame.addView(pill, flp(MATCH, MATCH).margins(l = 7.dp, r = 7.dp, t = 8.dp, b = 8.dp))
        row.addView(pillFrame, lp(0, 56.dp, 1f))

        identityBox.addView(identity, flp(17.dp, 17.dp, Gravity.CENTER))
        identityBox.contentDescription = context.getString(R.string.site_info)
        pill.addView(identityBox, lp(40.dp, MATCH))

        display.orientation = HORIZONTAL
        display.gravity = Gravity.CENTER_VERTICAL
        hostText.apply { style(15f, 0); maxLines = 1; isSingleLine = true }
        pathText.apply { style(15f, 0); maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        display.addView(hostText, lp(WRAP, WRAP))
        display.addView(pathText, lp(0, WRAP, 1f))
        val center = FrameLayout(context)
        center.addView(display, flp(MATCH, MATCH))
        edit.apply {
            background = null
            setPadding(0, 0, 0, 0)
            style(15f, 0)
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelectAllOnFocus(true)
            visibility = GONE
            gravity = Gravity.CENTER_VERTICAL
        }
        center.addView(edit, flp(MATCH, MATCH))
        pill.addView(center, lp(0, MATCH, 1f))

        shieldChip.orientation = HORIZONTAL
        shieldChip.gravity = Gravity.CENTER_VERTICAL
        shieldChip.setPadding(8.dp, 0, 6.dp, 0)
        shieldChip.addView(shieldIcon, lp(15.dp, 15.dp))
        shieldCount.style(12f, 0, bold = true)
        shieldChip.addView(shieldCount, lp(WRAP, WRAP).margins(l = 4.dp))
        shieldChip.visibility = GONE
        shieldChip.contentDescription = context.getString(R.string.site_info)
        pill.addView(shieldChip, lp(WRAP, MATCH))

        action.addView(actionIcon, flp(17.dp, 17.dp, Gravity.CENTER))
        pill.addView(action, lp(40.dp, MATCH))

        // Tab counter + menu
        tabsButton.addView(tabCount, flp(MATCH, MATCH))
        tabsButton.contentDescription = context.getString(R.string.tabs_button)
        row.addView(tabsButton, lp(44.dp, 48.dp))
        menuButton.addView(menuIcon, flp(20.dp, 20.dp, Gravity.CENTER))
        menuButton.contentDescription = context.getString(R.string.menu_button)
        row.addView(menuButton, lp(44.dp, 48.dp))

        cancel.apply {
            text = context.getString(R.string.cancel)
            style(15f, 0, bold = true)
            gravity = Gravity.CENTER
            setPadding(12.dp, 0, 14.dp, 0)
            visibility = GONE
        }
        row.addView(cancel, lp(WRAP, 48.dp))

        addView(divider, lp(MATCH, 1))
        addView(row, lp(MATCH, 56.dp))

        wire()
    }

    private fun wire() {
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                listener?.onAddressTap(); return true
            }
            override fun onLongPress(e: MotionEvent) {
                Haptics.heavy(pill)
                listener?.onAddressLongPress(pill)
            }
        })
        // Drag the address sideways to move between tabs: the URL follows your finger, then the
        // next tab's address slides in (or it springs back if there's nowhere to go).
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downT = 0L
        var dragging = false
        pill.setOnTouchListener { v, e ->
            if (editing) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downT = e.eventTime; dragging = false
                    v.animate().scaleX(0.985f).scaleY(0.97f).setDuration(Motion.FAST).setInterpolator(Motion.ease).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                        dragging = true
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        gestures.onTouchEvent(cancel)
                        cancel.recycle()
                        v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.FAST).start()
                    }
                    if (dragging) {
                        display.translationX = dx * 0.55f
                        display.alpha = 1f - (abs(dx) / v.width.coerceAtLeast(1)).coerceAtMost(0.75f)
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.spring).start()
                    if (dragging) {
                        dragging = false
                        val dx = e.rawX - downX
                        val speed = abs(dx) / (e.eventTime - downT).coerceAtLeast(1)
                        val far = abs(dx) > 64.dp || (speed > 0.5f && abs(dx) > 28.dp)
                        val switched = e.actionMasked == MotionEvent.ACTION_UP && far &&
                            (listener?.onSwipeTabs(if (dx < 0) 1 else -1) ?: false)
                        if (!switched) {
                            display.animate().translationX(0f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Motion.spring).start()
                        }
                        return@setOnTouchListener true
                    }
                }
            }
            gestures.onTouchEvent(e)
        }
        listOf(identityBox, shieldChip, tabsButton, menuButton, cancel).forEach { it.sinkOnPress(0.88f) }
        action.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(Motion.FAST).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.spring).start()
            }
            false
        }

        identityBox.setOnClickListener { if (!editing) listener?.onIdentityTap(identityBox) }
        shieldChip.setOnClickListener { listener?.onIdentityTap(shieldChip) }
        action.setOnClickListener {
            Haptics.tap(it)
            if (!editing && !loading) {
                // A full turn of the reload arrow.
                actionIcon.animate().cancel()
                actionIcon.rotation = 0f
                actionIcon.animate().rotation(360f).setDuration(560).setInterpolator(Motion.ease).withEndAction { actionIcon.rotation = 0f }.start()
            }
            listener?.onPillAction()
        }
        tabsButton.setOnClickListener { Haptics.tap(it); listener?.onTabsTap() }
        tabsButton.setOnLongClickListener { Haptics.heavy(it); listener?.onTabsLongPress(it); true }
        menuButton.setOnClickListener { listener?.onMenuTap(it) }
        cancel.setOnClickListener { Haptics.tap(it); listener?.onCancelEdit() }

        edit.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                listener?.onSubmit(edit.text.toString()); true
            } else false
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppressWatcher) listener?.onQueryChanged(s?.toString() ?: "")
                updateActionIcon()
            }
        })
    }

    fun applyPalette(palette: Palette, bottom: Boolean) {
        p = palette
        atBottom = bottom
        setBackgroundColor(p.bg)
        divider.setBackgroundColor(p.divider)
        removeView(divider)
        if (bottom) addView(divider, 0, lp(MATCH, 1)) else addView(divider, lp(MATCH, 1))
        pill.background = rounded(p.field, Radius.field)
        aurora.setPrivate(p.isPrivate)
        hostText.setTextColor(p.text)
        pathText.setTextColor(p.text3)
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.text3)
        edit.highlightColor = withAlpha(p.accent, 0.3f)
        edit.hint = context.getString(if (p.isPrivate) R.string.private_search_hint else R.string.search_or_enter_address)
        identity.tint(p.icon)
        actionIcon.tint(p.icon)
        shieldIcon.setImageResource(R.drawable.ic_shield_check)
        shieldIcon.tint(p.shield)
        shieldCount.setTextColor(p.shield)
        shieldChip.background = ripple(p.ripple, Radius.small)
        identityBox.background = ripple(p.ripple, Radius.field)
        action.background = ripple(p.ripple, Radius.field)
        tabsButton.background = ripple(p.ripple, Radius.field)
        menuButton.background = ripple(p.ripple, Radius.field)
        tabCount.color = p.icon
        menuIcon.setImageResource(R.drawable.ic_more_horizontal)
        menuIcon.tint(p.icon)
        cancel.setTextColor(p.text)
        cancel.background = ripple(p.ripple, Radius.field)
        tintCursor()
    }

    private fun tintCursor() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            edit.textCursorDrawable = rounded(p.accent, 1f.dp).apply { setSize(2.dp, 20.dp) }
        }
    }

    /* ---- State ------------------------------------------------------------------------------ */

    private var loading = false

    /** [query] is shown instead of the URL when the page is a search results page. */
    fun showUrl(url: String, isHome: Boolean, query: String?) {
        if (isHome) {
            hostText.text = ""
            pathText.text = context.getString(if (p.isPrivate) R.string.private_search_hint else R.string.search_or_enter_address)
            pathText.setTextColor(p.text3)
            return
        }
        pathText.setTextColor(p.text3)
        if (query != null) {
            hostText.text = query
            pathText.text = ""
            return
        }
        val (host, rest) = co.dothq.browser.core.Urls.split(url)
        hostText.text = host
        pathText.text = rest
    }

    fun setIdentity(res: Int, tint: Int) {
        identity.setImageResource(res)
        identity.tint(tint)
    }

    fun setTrackers(count: Int, pulse: Boolean) {
        if (count <= 0 || editing) {
            shieldChip.visibility = GONE
            return
        }
        val wasHidden = shieldChip.visibility != VISIBLE
        shieldChip.visibility = VISIBLE
        val changed = shieldCount.text.toString() != count.toString()
        shieldCount.text = count.toString()
        if (changed && !wasHidden) {
            // The number rolls up into place.
            shieldCount.translationY = 6f.dp
            shieldCount.alpha = 0.2f
            shieldCount.animate().translationY(0f).alpha(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
        }
        if (wasHidden) {
            shieldChip.alpha = 0f
            shieldChip.scaleX = 0.6f; shieldChip.scaleY = 0.6f
            shieldChip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Motion.spring).start()
        } else if (pulse) {
            shieldIcon.animate().cancel()
            shieldIcon.scaleX = 1.35f; shieldIcon.scaleY = 1.35f
            shieldIcon.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Motion.spring).start()
        }
    }

    fun setLoading(isLoading: Boolean) {
        loading = isLoading
        updateActionIcon()
    }

    private var actionRes = 0

    private fun setActionIcon(res: Int) {
        if (res == actionRes) return
        val morph = actionRes != 0 && action.isShown && action.isAttachedToWindow && action.width > 0
        actionRes = res
        actionIcon.setImageResource(res)
        if (!morph) {
            actionIcon.animate().cancel()
            actionIcon.rotation = 0f; actionIcon.scaleX = 1f; actionIcon.scaleY = 1f; actionIcon.alpha = 1f
        } else {
            // Reload and stop trade places with a quarter turn.
            actionIcon.animate().cancel()
            actionIcon.rotation = -90f
            actionIcon.scaleX = 0.5f; actionIcon.scaleY = 0.5f
            actionIcon.alpha = 0f
            actionIcon.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(Motion.SLOW).setInterpolator(Motion.spring).start()
        }
    }

    private fun updateActionIcon() {
        when {
            editing -> {
                action.visibility = if (edit.text.isNullOrEmpty()) GONE else VISIBLE
                setActionIcon(R.drawable.ic_close)
                action.contentDescription = context.getString(R.string.clear)
            }
            loading -> {
                action.visibility = VISIBLE
                setActionIcon(R.drawable.ic_close)
                action.contentDescription = context.getString(R.string.stop)
            }
            else -> {
                action.visibility = if (hostText.text.isNullOrEmpty()) GONE else VISIBLE
                setActionIcon(R.drawable.ic_reload)
                action.contentDescription = context.getString(R.string.reload)
            }
        }
    }

    private var borderAnim: android.animation.ValueAnimator? = null

    /** Fades the accent ring of the address field in or out. */
    private fun animateBorder(to: Float) {
        borderAnim?.cancel()
        val bg = rounded(p.field, Radius.field, android.graphics.Color.TRANSPARENT, 1.5f.dp.toInt())
        pill.background = bg
        val from = if (to > 0f) 0f else 0.55f
        borderAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = Motion.NORMAL
            interpolator = Motion.ease
            addUpdateListener { bg.setStroke(1.5f.dp.toInt(), withAlpha(p.accent, it.animatedValue as Float)) }
            start()
        }
    }

    fun refreshAction() = updateActionIcon()

    fun enterEdit(initial: String) {
        if (editing) return
        editing = true
        suppressWatcher = true
        edit.setText(initial)
        suppressWatcher = false
        display.visibility = GONE
        edit.visibility = VISIBLE
        shieldChip.visibility = GONE
        edit.requestFocus()
        edit.selectAll()
        tabsButton.visibility = GONE
        menuButton.visibility = GONE
        cancel.visibility = VISIBLE
        cancel.alpha = 0f
        cancel.translationX = 12f.dp
        cancel.animate().alpha(1f).translationX(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
        animateBorder(0.55f)
        updateActionIcon()
    }

    fun exitEdit() {
        if (!editing) return
        editing = false
        edit.clearFocus()
        edit.visibility = GONE
        display.visibility = VISIBLE
        cancel.visibility = GONE
        tabsButton.visibility = VISIBLE
        menuButton.visibility = VISIBLE
        tabsButton.alpha = 0f; menuButton.alpha = 0f
        tabsButton.animate().alpha(1f).setDuration(Motion.NORMAL).start()
        menuButton.animate().alpha(1f).setDuration(Motion.NORMAL).start()
        animateBorder(0f)
        updateActionIcon()
    }

    fun setEditText(text: String) {
        edit.setText(text)
        edit.setSelection(edit.text.length)
    }

    /** A little slide when swiping between tabs on the address bar. */
    fun slideUrl(direction: Int) {
        display.translationX = 40f.dp * direction
        display.alpha = 0f
        display.animate().translationX(0f).alpha(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
    }
}

/* =============================================================================================
 * Find in page
 * =========================================================================================== */

@SuppressLint("ViewConstructor")
class FindBar(context: Context, p: Palette, onQuery: (String) -> Unit, onNext: (Boolean) -> Unit, onClose: () -> Unit) :
    LinearLayout(context) {
    val input = EditText(context)
    private val count = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(8.dp, 0, 4.dp, 0)
        val field = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.field, Radius.field)
            setPadding(12.dp, 0, 12.dp, 0)
        }
        field.addView(context.icon(R.drawable.ic_search, p.text3, 16).apply {
            (layoutParams as LayoutParams).marginEnd = 10.dp
        })
        input.apply {
            background = null
            setPadding(0, 0, 0, 0)
            style(15f, p.text)
            setHintTextColor(p.text3)
            hint = context.getString(R.string.find_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onQuery(s?.toString() ?: "")
            })
            setOnEditorActionListener { _, _, _ -> onNext(true); true }
        }
        field.addView(input, lp(0, MATCH, 1f))
        count.style(13f, p.text3)
        field.addView(count, lp(WRAP, WRAP))
        addView(field, lp(0, 40.dp, 1f).margins(r = 4.dp))
        addView(context.iconButton(R.drawable.ic_arrow_up, p.icon, p, iconDp = 18).apply {
            setOnClickListener { Haptics.tick(it); onNext(false) }
        })
        addView(context.iconButton(R.drawable.ic_arrow_up, p.icon, p, iconDp = 18).apply {
            iconView.rotation = 180f
            setOnClickListener { Haptics.tick(it); onNext(true) }
        })
        addView(context.iconButton(R.drawable.ic_close, p.icon, p, iconDp = 16).apply {
            setOnClickListener { Haptics.tap(it); onClose() }
        })
    }

    fun setResult(current: Int, total: Int, query: String) {
        count.text = when {
            query.isEmpty() -> ""
            total == 0 -> context.getString(R.string.find_none)
            else -> context.getString(R.string.find_count, current, total)
        }
    }
}

/* =============================================================================================
 * Suggestions — shown while typing in the address field. When the toolbar is at the bottom the
 * list grows upwards, so the best match is always closest to your thumb.
 * =========================================================================================== */

data class Suggestion(
    val kind: Kind,
    val title: String,
    val url: String,
    val subtitle: String? = null,
    val icon: android.graphics.Bitmap? = null,
    val tabId: Long? = null,
) {
    enum class Kind { SEARCH, URL, TAB, BOOKMARK, HISTORY, HEADER }
}

@SuppressLint("ViewConstructor", "NotifyDataSetChanged")
class SuggestionsView(context: Context, private val p: Palette, reverse: Boolean, private val onPick: (Suggestion) -> Unit, private val onFill: (Suggestion) -> Unit) :
    FrameLayout(context) {
    private val list = RecyclerView(context)
    private var items: List<Suggestion> = emptyList()
    private var query = ""

    init {
        setBackgroundColor(p.bg)
        isClickable = true
        // reverseLayout alone anchors the list to the bottom edge, nearest the toolbar.
        list.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, reverse)
        list.overScrollMode = OVER_SCROLL_NEVER
        list.setPadding(0, 8.dp, 0, 8.dp)
        list.clipToPadding = false
        list.adapter = Adapter()
        addView(list, flp(MATCH, MATCH))
    }

    private var firstSubmit = true
    private var cascadeUntil = 0L

    fun submit(q: String, s: List<Suggestion>) {
        if (firstSubmit && s.isNotEmpty()) {
            firstSubmit = false
            cascadeUntil = android.os.SystemClock.uptimeMillis() + 400
        }
        query = q
        items = s
        list.adapter?.notifyDataSetChanged()
        list.scrollToPosition(0)
    }

    private inner class Holder(v: LinearLayout) : RecyclerView.ViewHolder(v) {
        val iconBox = FrameLayout(v.context)
        val icon = ImageView(v.context)
        val title = TextView(v.context)
        val sub = TextView(v.context)
        val fill = FrameLayout(v.context)
        val header = TextView(v.context)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position].kind == Suggestion.Kind.HEADER) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            val h = Holder(row)
            if (viewType == 1) {
                h.header.style(12f, p.text3, bold = true, tracking = 0.06f)
                row.setPadding(20.dp, 14.dp, 20.dp, 6.dp)
                row.addView(h.header)
                return h
            }
            row.minimumHeight = 58.dp
            row.setPadding(12.dp, 6.dp, 4.dp, 6.dp)
            row.background = ripple(p.ripple, 0f)
            h.iconBox.background = rounded(p.field, Radius.field)
            h.iconBox.addView(h.icon, flp(18.dp, 18.dp, Gravity.CENTER))
            row.addView(h.iconBox, lp(38.dp, 38.dp).margins(l = 6.dp, r = 14.dp))
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            h.title.style(15f, p.text)
            h.title.maxLines = 1
            h.title.ellipsize = TextUtils.TruncateAt.END
            h.sub.style(13f, p.text3)
            h.sub.maxLines = 1
            h.sub.ellipsize = TextUtils.TruncateAt.MIDDLE
            texts.addView(h.title)
            texts.addView(h.sub, lp(WRAP, WRAP).margins(t = 3.dp))
            row.addView(texts, lp(0, WRAP, 1f))
            h.fill.addView(ImageView(c).apply {
                setImageResource(R.drawable.ic_arrow_up)
                tint(p.text3)
                rotation = -45f
            }, flp(16.dp, 16.dp, Gravity.CENTER))
            h.fill.background = ripple(p.ripple, Radius.field)
            h.fill.sinkOnPress(0.85f)
            row.addView(h.fill, lp(44.dp, 44.dp))
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val s = items[position]
            if (s.kind == Suggestion.Kind.HEADER) {
                h.header.text = s.title.uppercase()
                return
            }
            h.title.text = highlight(s.title, query)
            h.sub.text = s.subtitle ?: ""
            h.sub.visibility = if (s.subtitle.isNullOrEmpty()) GONE else VISIBLE
            h.icon.imageTintList = null
            when (s.kind) {
                Suggestion.Kind.SEARCH -> { h.icon.setImageResource(R.drawable.ic_search); h.icon.tint(p.icon) }
                Suggestion.Kind.URL -> { h.icon.setImageResource(R.drawable.ic_globe); h.icon.tint(p.icon) }
                Suggestion.Kind.TAB -> { h.icon.setImageResource(R.drawable.ic_pages); h.icon.tint(p.accent) }
                Suggestion.Kind.BOOKMARK -> if (s.icon != null) h.icon.setImageBitmap(s.icon) else { h.icon.setImageResource(R.drawable.ic_star); h.icon.tint(p.icon) }
                Suggestion.Kind.HISTORY -> if (s.icon != null) h.icon.setImageBitmap(s.icon) else h.icon.bindFavicon(null, co.dothq.browser.core.Urls.host(s.url))
                Suggestion.Kind.HEADER -> Unit
            }
            h.fill.visibility = if (s.kind == Suggestion.Kind.TAB) GONE else VISIBLE
            if (android.os.SystemClock.uptimeMillis() < cascadeUntil && position < 10) {
                h.itemView.alpha = 0f
                h.itemView.translationY = 10f.dp
                h.itemView.animate().alpha(1f).translationY(0f).setStartDelay(position * 22L)
                    .setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
            }
            h.itemView.setOnClickListener { Haptics.tap(it); onPick(s) }
            h.fill.setOnClickListener { Haptics.tick(it); onFill(s) }
        }
    }

    private fun highlight(text: String, q: String): CharSequence {
        if (q.isBlank()) return text
        val i = text.indexOf(q.trim(), ignoreCase = true)
        if (i < 0) return text
        val span = SpannableString(text)
        span.setSpan(BoldSpan(), i, i + q.trim().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    private class BoldSpan : TypefaceSpan("sans-serif") {
        override fun updateDrawState(ds: android.text.TextPaint) = apply(ds)
        override fun updateMeasureState(paint: android.text.TextPaint) = apply(paint)
        private fun apply(paint: android.text.TextPaint) {
            paint.typeface = Fonts.bold
        }
    }
}
