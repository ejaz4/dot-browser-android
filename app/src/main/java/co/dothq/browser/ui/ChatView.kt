package co.dothq.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import co.dothq.browser.R
import co.dothq.browser.ai.ChatItem
import co.dothq.browser.ai.ChatSession

/** Turns the small amount of Markdown models write (bold, code, bullets, headings) into spans. */
object MiniMarkdown {
    fun render(text: String, p: Palette): CharSequence {
        val out = SpannableStringBuilder()
        val lines = text.replace("\r", "").split('\n')
        lines.forEachIndexed { i, raw ->
            var line = raw
            val bullet = Regex("^\\s*([-*•]|\\d+[.)])\\s+").find(line)
            val heading = Regex("^#{1,4}\\s+").find(line)
            val start = out.length
            if (bullet != null) {
                val marker = bullet.groupValues[1].let { if (it.first().isDigit()) "$it " else "•  " }
                line = line.substring(bullet.range.last + 1)
                out.append(marker)
            } else if (heading != null) {
                line = line.substring(heading.range.last + 1)
            }
            inline(out, line, p)
            if (bullet != null) out.setSpan(LeadingMarginSpan.Standard(0, 18.dp), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (heading != null) out.setSpan(BoldSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i < lines.lastIndex) out.append('\n')
        }
        return out
    }

    private fun inline(out: SpannableStringBuilder, line: String, p: Palette) {
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) && line.indexOf("**", i + 2) > i + 2 -> {
                    val end = line.indexOf("**", i + 2)
                    val s = out.length
                    out.append(line, i + 2, end)
                    out.setSpan(BoldSpan(), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                }
                line[i] == '`' && line.indexOf('`', i + 1) > i + 1 -> {
                    val end = line.indexOf('`', i + 1)
                    val s = out.length
                    out.append(line, i + 1, end)
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(withAlpha(p.text, 0.07f)), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                }
                else -> { out.append(line[i]); i++ }
            }
        }
    }

    private class BoldSpan : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = Fonts.bold }
        override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = Fonts.bold }
    }
}

/** Text that shimmers with the ring gradient — for "Dot is working" moments. */
class ShimmerText(context: Context) : TextView(context) {
    private val colors = ringColors(context)
    private val born = SystemClock.uptimeMillis()
    var shimmering = true
        set(v) { field = v; if (!v) paint.shader = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (shimmering && width > 0) {
            val t = ((SystemClock.uptimeMillis() - born) % 2200) / 2200f
            val w = width.toFloat()
            val x = -w + 3 * w * t
            paint.shader = LinearGradient(
                x - w * 0.4f, 0f, x + w * 0.4f, 0f,
                intArrayOf(currentTextColor, colors[2], colors[3], colors[4], currentTextColor), null, Shader.TileMode.CLAMP,
            )
            postInvalidateOnAnimation()
        }
        super.onDraw(canvas)
    }
}

interface ChatHost {
    fun chatShowPage()
    fun chatModelName(): String?
    fun chatCapture(done: (Bitmap?) -> Unit)
    fun chatOpenSettings()
    val chatReady: Boolean
}

/**
 * A Chat tab. The conversation fills the tab; while Dot works on a page, a small live view of
 * that page floats at the top so you can watch it browse.
 */
@SuppressLint("ViewConstructor")
class ChatView(context: Context, private val host: ChatHost) : FrameLayout(context) {
    private lateinit var p: Palette
    private var session: ChatSession? = null
    private val backdrop = AuroraBackdrop(context)
    private val scroll = ScrollView(context)
    private val list = LinearLayout(context)
    private val empty = LinearLayout(context)
    private val composer = LinearLayout(context)
    private val input = EditText(context)
    private val send = FrameLayout(context)
    private val sendIcon = ImageView(context)
    private val stageView = ShimmerText(context)
    private val live = FrameLayout(context)
    private val liveImage = ImageView(context)
    private val liveHalo = AuroraOutline(context)
    private val liveLabel = TextView(context)
    private val header = LinearLayout(context)
    private val modelChip = TextView(context)
    private val rendered = mutableListOf<Pair<ChatItem, View>>()
    private val listener: () -> Unit = { sync() }
    var bottomInset = 0
        set(v) { field = v; requestLayout() }

    init {
        isClickable = true
        addView(backdrop, flp(MATCH, MATCH))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(column, flp(MATCH, MATCH))

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(16.dp, 10.dp, 8.dp, 6.dp)
        column.addView(header, lp(MATCH, WRAP))

        list.orientation = LinearLayout.VERTICAL
        list.setPadding(16.dp, 8.dp, 16.dp, 20.dp)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        val listFrame = FrameLayout(context)
        val inner = FrameLayout(context)
        inner.addView(list, flp(MATCH, WRAP))
        inner.addView(empty, flp(MATCH, MATCH))
        scroll.addView(inner)
        listFrame.addView(scroll, flp(MATCH, MATCH))
        column.addView(listFrame, lp(MATCH, 0, 1f))

        stageView.setPadding(20.dp, 0, 20.dp, 6.dp)
        column.addView(stageView, lp(MATCH, WRAP))
        composer.orientation = LinearLayout.HORIZONTAL
        composer.gravity = Gravity.CENTER_VERTICAL
        column.addView(composer, lp(MATCH, WRAP).margins(l = 12.dp, r = 12.dp, b = 10.dp))

        // Live view of the page Dot is working on.
        live.addView(liveHalo, flp(MATCH, MATCH))
        val liveCard = FrameLayout(context)
        liveCard.clipToOutline = true
        liveImage.scaleType = ImageView.ScaleType.CENTER_CROP
        liveCard.addView(liveImage, flp(MATCH, MATCH))
        liveLabel.style(10.5f, 0xFFFFFFFF.toInt(), bold = true)
        liveLabel.setPadding(8.dp, 3.dp, 8.dp, 4.dp)
        liveLabel.maxLines = 1
        liveLabel.ellipsize = TextUtils.TruncateAt.END
        liveCard.addView(liveLabel, flp(MATCH, WRAP, Gravity.BOTTOM))
        val m = liveHalo.glowMargin
        live.addView(liveCard, flp(MATCH, MATCH).margins(m, m, m, m))
        live.visibility = GONE
        live.elevation = 8f.dp
        live.setOnClickListener { Haptics.tap(it); host.chatShowPage() }
        addView(live, flp(110.dp + 2 * m, 150.dp + 2 * m, Gravity.TOP or Gravity.END).margins(t = 54.dp - m, r = 12.dp - m))
        liveCard.tag = "card"
    }

    fun bind(s: ChatSession, palette: Palette) {
        session?.removeListener(listener)
        session = s
        p = palette
        setBackgroundColor(p.bg)
        backdrop.setPrivate(p.isPrivate)
        backdrop.intensity = if (p.isDark) 0.16f else 0.10f
        liveHalo.cornerRadius = 14f.dp
        liveHalo.setPrivate(p.isPrivate)
        (live.getChildAt(1) as FrameLayout).background = rounded(p.surface, 14f.dp, p.border, 1)
        liveLabel.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB0000000.toInt(), 0x00000000),
        )
        buildHeader()
        buildComposer()
        buildEmpty()
        stageView.style(13f, p.text2, bold = true)
        list.removeAllViews()
        rendered.clear()
        s.addListener(listener)
        sync()
    }

    fun unbind() {
        session?.removeListener(listener)
        session = null
        stopLive()
    }

    private fun buildHeader() {
        header.removeAllViews()
        header.addView(DotRingView(context).apply { halo = false }, lp(22.dp, 22.dp).margins(r = 10.dp))
        header.addView(context.label("Chat", 19f, p.text, bold = true, tracking = -0.02f))
        modelChip.style(11.5f, p.text2, bold = true)
        modelChip.background = rounded(withAlpha(p.field, 0.9f), Radius.pill)
        modelChip.setPadding(9.dp, 4.dp, 9.dp, 4.dp)
        header.addView(modelChip, lp(WRAP, WRAP).margins(l = 10.dp))
        header.addView(View(context), lp(0, 1, 1f))
        header.addView(context.iconButton(R.drawable.ic_edit, p.icon, p, boxDp = 40, iconDp = 17, desc = "New chat").apply {
            setOnClickListener { Haptics.tap(it); session?.clear() }
        })
        header.addView(context.iconButton(R.drawable.ic_page, p.icon, p, boxDp = 40, iconDp = 17, desc = "Show page").apply {
            setOnClickListener { Haptics.tap(it); host.chatShowPage() }
        })
    }

    private fun buildComposer() {
        composer.removeAllViews()
        val field = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.field, 24f.dp, p.border, if (p.isDark) 0 else 1)
            setPadding(18.dp, 4.dp, 5.dp, 4.dp)
        }
        input.apply {
            background = null
            setPadding(0, 8.dp, 0, 8.dp)
            style(16f, p.text)
            setHintTextColor(p.text3)
            hint = if (host.chatReady) "Ask Dot anything" else "Set up Dot Assistant to chat"
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSend()
        })
        field.addView(input, lp(0, WRAP, 1f))
        send.removeAllViews()
        send.addView(sendIcon, flp(18.dp, 18.dp, Gravity.CENTER))
        send.sinkOnPress(0.86f)
        send.setOnClickListener {
            if (session?.running == true) { Haptics.reject(it); session?.stop() } else submit()
        }
        field.addView(send, lp(40.dp, 40.dp))
        composer.addView(field, lp(MATCH, WRAP))
        updateSend()
    }

    private fun updateSend() {
        val running = session?.running == true
        val canSend = input.text.isNotBlank() || running
        send.background = if (running) oval(p.ink)
        else android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            if (canSend) {
                colors = ringColors(context).let { intArrayOf(it[2], it[3], it[4]) }
                gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
                orientation = android.graphics.drawable.GradientDrawable.Orientation.BL_TR
            } else setColor(withAlpha(p.text, 0.08f))
        }
        sendIcon.setImageResource(if (running) R.drawable.ic_media_stop else R.drawable.ic_arrow_up)
        sendIcon.tint(if (running) p.onInk else if (canSend) 0xFFFFFFFF.toInt() else p.text3)
        send.contentDescription = if (running) "Stop" else "Send"
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!host.chatReady) { host.chatOpenSettings(); return }
        Haptics.confirm(send)
        input.setText("")
        session?.send(text)
    }

    /** Fills the box and sends — used by suggestions and "Ask about this page". */
    fun ask(text: String) {
        input.setText(text)
        submit()
    }

    fun focusInput() {
        input.requestFocus()
        input.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(input, 0)
        }
    }

    private fun buildEmpty() {
        empty.removeAllViews()
        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        empty.setPadding(28.dp, 20.dp, 28.dp, 40.dp)
        empty.addView(DotRingView(context), lp(72.dp, 72.dp).margins(b = 18.dp))
        empty.addView(context.label("What can I do for you?", 26f, p.text, bold = true, tracking = -0.03f).apply { gravity = Gravity.CENTER })
        empty.addView(context.label("I can answer questions, search the web, and use pages for you. I run entirely on your phone, so nothing you ask leaves it.", 14.5f, p.text2).apply {
            gravity = Gravity.CENTER
            setLineSpacing(3f.dp, 1f)
            setPadding(0, 10.dp, 0, 22.dp)
        })
        val suggestions = listOf(
            R.drawable.ic_page to "Summarise this page",
            R.drawable.ic_search to "Find the latest news about space",
            R.drawable.ic_sparkle to "Help me write a polite email declining an invitation",
        )
        suggestions.forEach { (icon, text) ->
            empty.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(p.ripple, 16f.dp, rounded(withAlpha(p.surface, 0.85f), 16f.dp, p.border, 1))
                setPadding(16.dp, 13.dp, 16.dp, 13.dp)
                isClickable = true
                sinkOnPress(0.98f)
                addView(context.icon(icon, p.accent, 17).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dp })
                addView(context.label(text, 14.5f, p.text).apply { layoutParams = lp(0, WRAP, 1f) })
                setOnClickListener { Haptics.tap(it); ask(text) }
            }, lp(MATCH, WRAP).margins(b = 8.dp))
        }
    }

    /* ---- Rendering the conversation ---------------------------------------------------------- */

    private fun sync() {
        val s = session ?: return
        modelChip.text = host.chatModelName() ?: "Not set up"
        empty.visibility = if (s.items.isEmpty()) VISIBLE else GONE
        input.hint = if (host.chatReady) "Ask Dot anything" else "Set up Dot Assistant to chat"

        // Drop views for items that went away; add or update the rest in order.
        val wasAtBottom = !scroll.canScrollVertically(1)
        rendered.removeAll { (item, view) ->
            if (s.items.none { it === item }) { list.removeView(view); true } else false
        }
        s.items.forEachIndexed { index, item ->
            val existing = rendered.firstOrNull { it.first === item }
            if (existing == null) {
                val v = viewFor(item)
                list.addView(v, index.coerceAtMost(list.childCount))
                rendered.add(index.coerceAtMost(rendered.size), item to v)
                v.alpha = 0f
                v.translationY = 10f.dp
                v.animate().alpha(1f).translationY(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
            } else update(item, existing.second)
        }
        stageView.visibility = if (s.running && s.stage != null) VISIBLE else GONE
        stageView.text = s.stage
        stageView.shimmering = s.running
        updateSend()
        val working = s.running && s.items.any { it is ChatItem.Step }
        if (working) startLive() else stopLive()
        if (wasAtBottom || s.running) scroll.post { scroll.fullScroll(FOCUS_DOWN) }
    }

    private fun viewFor(item: ChatItem): View = when (item) {
        is ChatItem.User -> FrameLayout(context).apply {
            addView(context.label(item.text, 15.5f, p.onInk).apply {
                background = rounded(p.ink, 20f.dp)
                setPadding(16.dp, 11.dp, 16.dp, 12.dp)
                setLineSpacing(2f.dp, 1f)
                setTextIsSelectable(true)
            }, flp(WRAP, WRAP, Gravity.END).margins(l = 48.dp))
            setPadding(0, 10.dp, 0, 6.dp)
        }
        is ChatItem.Reply -> LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp, 8.dp, 8.dp)
            addView(DotRingView(context).apply { halo = false; spinning = item.streaming }, lp(18.dp, 18.dp).margins(t = 2.dp, r = 12.dp))
            addView(context.label("", 15.5f, p.text).apply {
                setLineSpacing(4f.dp, 1f)
                setTextIsSelectable(true)
            }, lp(0, WRAP, 1f))
            update(item, this)
        }
        is ChatItem.Step -> LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30.dp, 3.dp, 0, 3.dp)
            addView(context.icon(stepIcon(item.kind), p.text2, 14).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 10.dp })
            addView(ShimmerText(context).apply {
                style(13.5f, p.text2)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(0, WRAP, 1f))
            update(item, this)
        }
        is ChatItem.Error -> context.label(item.text, 14f, context.col(R.color.dot_danger)).apply {
            setPadding(30.dp, 6.dp, 0, 6.dp)
        }
    }

    private fun update(item: ChatItem, v: View) {
        when (item) {
            is ChatItem.Reply -> {
                val row = v as LinearLayout
                val tv = row.getChildAt(1) as TextView
                val ring = row.getChildAt(0) as DotRingView
                ring.spinning = item.streaming
                tv.text = if (item.text.isEmpty() && item.streaming) "…" else MiniMarkdown.render(item.text, p)
                tv.setTextColor(if (item.text.isEmpty()) p.text3 else p.text)
            }
            is ChatItem.Step -> {
                val row = v as LinearLayout
                val tv = row.getChildAt(1) as ShimmerText
                val icon = row.getChildAt(0) as ImageView
                tv.text = item.label
                tv.shimmering = !item.done
                tv.setTextColor(if (item.failed) p.text3 else p.text2)
                if (item.done && !item.failed) icon.setImageResource(R.drawable.ic_check)
                icon.tint(if (item.done && !item.failed) p.accent else p.text2)
            }
            else -> Unit
        }
    }

    private fun stepIcon(kind: String) = when (kind) {
        "search" -> R.drawable.ic_search
        "open" -> R.drawable.ic_globe
        "read" -> R.drawable.ic_page
        "look" -> R.drawable.ic_screenshot
        "click" -> R.drawable.ic_tap
        "type" -> R.drawable.ic_edit
        "scroll" -> R.drawable.ic_arrow_up
        "back" -> R.drawable.ic_arrow_left
        else -> R.drawable.ic_sparkle
    }

    /* ---- Live page view ------------------------------------------------------------------------ */

    private var liveRunning = false
    private val liveTick = object : Runnable {
        override fun run() {
            if (!liveRunning) return
            host.chatCapture { bmp ->
                if (bmp != null && liveRunning) liveImage.setImageBitmap(bmp)
            }
            session?.tab?.let { liveLabel.text = co.dothq.browser.core.Urls.host(it.url).ifEmpty { "New tab" } }
            postDelayed(this, 1300)
        }
    }

    private fun startLive() {
        if (liveRunning) return
        liveRunning = true
        live.visibility = VISIBLE
        live.alpha = 0f
        live.scaleX = 0.8f; live.scaleY = 0.8f
        live.pivotX = live.width.toFloat(); live.pivotY = 0f
        live.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Motion.spring).start()
        liveHalo.start()
        post(liveTick)
    }

    private fun stopLive() {
        if (!liveRunning) return
        liveRunning = false
        removeCallbacks(liveTick)
        liveHalo.finish()
        live.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(Motion.NORMAL).withEndAction { live.visibility = GONE }.start()
    }

    /** Keeps the live halo progress moving with the page. */
    fun onPageProgress(progress: Int) {
        if (liveRunning) liveHalo.setProgress(progress.coerceAtLeast(10))
    }
}
