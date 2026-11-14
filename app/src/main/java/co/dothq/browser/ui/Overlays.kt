package co.dothq.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import co.dothq.browser.R
import kotlin.math.max
import kotlin.math.min

/* =============================================================================================
 * Dot menu — a direct translation of the desktop context menu: a white card with a hairline
 * border, a row of navigation buttons on top, then text-only items with right-aligned hints,
 * grouped by inset separators.
 * =========================================================================================== */

sealed class MenuEntry {
    data class Item(
        val label: String,
        val hint: String? = null,
        val chevron: Boolean = false,
        val toggle: Boolean? = null,
        val enabled: Boolean = true,
        val danger: Boolean = false,
        val keepOpen: Boolean = false,
        val onClick: () -> Unit,
    ) : MenuEntry()

    data class Nav(val buttons: List<NavButton>) : MenuEntry()
    data class Header(val title: String, val subtitle: String? = null, val icon: android.graphics.Bitmap? = null) : MenuEntry()
    data object Separator : MenuEntry()
}

data class NavButton(
    val icon: Int,
    val description: String,
    val enabled: Boolean = true,
    val active: Boolean = false,
    val activeIcon: Int? = null,
    val onClick: () -> Unit,
)

sealed class Anchor {
    /** Opens from a toolbar button, above it when the toolbar is at the bottom. */
    data class FromView(val view: View, val above: Boolean) : Anchor()
    /** Opens at a touch point (context menus), like desktop's at-cursor menus. */
    data class AtPoint(val x: Int, val y: Int) : Anchor()
}

class DotMenu(private val host: FrameLayout, private val p: Palette) {
    private var scrim: View? = null
    private var card: View? = null
    var onDismiss: (() -> Unit)? = null
    /** Called with the scrim's colour as it fades, so the system bars can dim along with it. */
    var onScrim: ((Int) -> Unit)? = null
    private val dim get() = withAlpha(0xFF000000.toInt(), if (p.isDark) 0.42f else 0.16f)
    val isShowing get() = card != null

    fun show(entries: List<MenuEntry>, anchor: Anchor) {
        dismiss(animate = false)
        val c = host.context
        val scrimView = View(c).apply {
            setBackgroundColor(dim)
            alpha = 0f
            setOnClickListener { dismiss() }
        }
        host.addView(scrimView, flp(MATCH, MATCH))
        scrim = scrimView

        val width = min(288.dp, host.width - 24.dp)
        val list = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4.dp, 0, 4.dp)
        }
        entries.forEach { list.addView(build(c, it)) }

        val scroller = ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        val box = FrameLayout(c).apply {
            background = rounded(p.surface, Radius.card, p.border, 1)
            elevation = 18f.dp
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, Radius.card)
                }
            }
            clipToOutline = true
            addView(scroller, flp(MATCH, WRAP))
        }
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val margin = 8.dp
        val contentH = list.measuredHeight

        val lp = flp(width, WRAP)
        var pivotX: Float
        var pivotY: Float
        when (anchor) {
            is Anchor.FromView -> {
                val r = rectIn(anchor.view)
                val right = min(host.width - margin, r.right - 2.dp)
                lp.leftMargin = max(margin, right - width)
                if (anchor.above) {
                    val bottom = r.top - 2.dp
                    val h = min(contentH, bottom - margin - topInset())
                    lp.height = h
                    lp.topMargin = bottom - h
                    pivotY = h.toFloat()
                } else {
                    val top = r.bottom + 2.dp
                    val h = min(contentH, host.height - top - margin)
                    lp.height = h
                    lp.topMargin = top
                    pivotY = 0f
                }
                pivotX = (r.centerX() - lp.leftMargin).toFloat().coerceIn(0f, width.toFloat())
            }
            is Anchor.AtPoint -> {
                val h = min(contentH, host.height - 2 * margin)
                lp.height = h
                lp.leftMargin = (anchor.x - width / 2).coerceIn(margin, host.width - width - margin)
                val below = anchor.y + 12.dp
                lp.topMargin = if (below + h < host.height - margin) below else max(margin, anchor.y - 12.dp - h)
                pivotX = (anchor.x - lp.leftMargin).toFloat()
                pivotY = if (lp.topMargin >= anchor.y) 0f else h.toFloat()
            }
        }
        host.addView(box, lp)
        card = box

        box.pivotX = pivotX
        box.pivotY = pivotY
        box.alpha = 0f
        box.scaleX = 0.88f
        box.scaleY = 0.88f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL + 40).setInterpolator(Motion.spring).start()
        scrimView.animate().alpha(1f).setDuration(Motion.NORMAL)
            .setUpdateListener { onScrim?.invoke(withAlpha(dim, scrimView.alpha * android.graphics.Color.alpha(dim) / 255f)) }.start()
        Haptics.tick(host)
    }

    private fun topInset(): Int = 0

    private fun rectIn(v: View): Rect {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        host.getLocationInWindow(b)
        val x = a[0] - b[0]
        val y = a[1] - b[1]
        return Rect(x, y, x + v.width, y + v.height)
    }

    fun dismiss(animate: Boolean = true) {
        val s = scrim
        val c = card
        scrim = null
        card = null
        if (s == null && c == null) return
        if (!animate) {
            host.removeView(s); host.removeView(c)
        } else {
            c?.animate()?.alpha(0f)?.scaleX(0.95f)?.scaleY(0.95f)?.setDuration(Motion.FAST)?.setInterpolator(Motion.easeIn)
                ?.withEndAction { host.removeView(c) }?.start()
            s?.animate()?.alpha(0f)?.setDuration(Motion.FAST)
                ?.setUpdateListener { onScrim?.invoke(withAlpha(dim, s.alpha * android.graphics.Color.alpha(dim) / 255f)) }
                ?.withEndAction { host.removeView(s); onScrim?.invoke(0) }?.start()
        }
        if (!animate) onScrim?.invoke(0)
        onDismiss?.invoke()
    }

    private fun build(c: Context, e: MenuEntry): View = when (e) {
        is MenuEntry.Separator -> View(c).apply {
            setBackgroundColor(p.divider)
            layoutParams = lp(MATCH, 1).margins(l = 10.dp, r = 10.dp, t = 4.dp, b = 4.dp)
        }

        is MenuEntry.Nav -> LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6.dp, 2.dp, 6.dp, 2.dp)
            layoutParams = lp(MATCH, 52.dp)
            e.buttons.forEach { b ->
                val tint = when {
                    !b.enabled -> p.iconDisabled
                    b.active -> p.accent
                    else -> p.icon
                }
                val btn = c.iconButton(if (b.active && b.activeIcon != null) b.activeIcon else b.icon, tint, p, desc = b.description)
                btn.layoutParams = lp(0, 48.dp, 1f)
                btn.isEnabled = b.enabled
                btn.setOnClickListener {
                    Haptics.tap(it)
                    dismiss()
                    b.onClick()
                }
                addView(btn)
            }
        }

        is MenuEntry.Header -> LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 10.dp, 16.dp, 8.dp)
            if (e.icon != null) {
                addView(ImageView(c).apply {
                    setImageBitmap(e.icon)
                    layoutParams = lp(18.dp, 18.dp).margins(r = 10.dp)
                })
            }
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, WRAP, 1f)
                addView(c.label(e.title, 13f, p.text, bold = true).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                e.subtitle?.let {
                    addView(c.label(it, 12f, p.text3).apply {
                        maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE
                        setPadding(0, 3.dp, 0, 0)
                    })
                }
            })
        }

        is MenuEntry.Item -> LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, 46.dp).margins(l = 4.dp, r = 4.dp)
            setPadding(12.dp, 0, 12.dp, 0)
            pressable(p, Radius.small + 2.dp)
            isEnabled = e.enabled
            alpha = if (e.enabled) 1f else 0.4f
            addView(c.label(e.label, 15f, if (e.danger) c.col(R.color.dot_danger) else p.text).apply {
                layoutParams = lp(0, WRAP, 1f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            when {
                e.toggle != null -> {
                    val sw = MiniSwitch(c, p).apply { setChecked(e.toggle, false) }
                    addView(sw, lp(34.dp, 20.dp))
                    setOnClickListener {
                        Haptics.tick(it)
                        sw.setChecked(!sw.checked, true)
                        postDelayed({ dismiss(); e.onClick() }, 180)
                    }
                    return@apply
                }
                e.chevron -> addView(c.icon(R.drawable.ic_chevron_right, p.text3, 14))
                e.hint != null -> addView(c.label(e.hint, 13f, p.text3))
            }
            setOnClickListener {
                Haptics.tap(it)
                if (!e.keepOpen) dismiss()
                e.onClick()
            }
        }
    }
}

/* ---- Mini switch ---------------------------------------------------------------------------- */

class MiniSwitch(context: Context, private val p: Palette) : View(context) {
    var checked = false
        private set
    private var pos = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var anim: ValueAnimator? = null

    fun setChecked(value: Boolean, animate: Boolean) {
        checked = value
        anim?.cancel()
        val target = if (value) 1f else 0f
        if (!animate) { pos = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = Motion.NORMAL
            interpolator = Motion.spring
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        rect.set(0f, 0f, w, h)
        paint.color = blend(p.border, p.accent, pos.coerceIn(0f, 1f))
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val r = h / 2 - 2.5f.dp
        val cx = h / 2 + (w - h) * pos
        paint.color = 0xFFFFFFFF.toInt()
        paint.setShadowLayer(1.5f.dp, 0f, 0.5f.dp, 0x33000000)
        canvas.drawCircle(cx, h / 2, r, paint)
        paint.clearShadowLayer()
    }
}

/* =============================================================================================
 * Bottom sheet — for site info and choice lists.
 * =========================================================================================== */

class Sheet(private val host: FrameLayout, private val p: Palette) {
    private var scrim: View? = null
    private var panel: View? = null
    val isShowing get() = panel != null
    var onDismiss: (() -> Unit)? = null
    var onScrim: ((Int) -> Unit)? = null
    private val dim get() = withAlpha(0xFF000000.toInt(), if (p.isDark) 0.55f else 0.32f)

    fun show(content: View, bottomInset: Int = 0) {
        dismiss(false)
        val c = host.context
        val s = View(c).apply {
            setBackgroundColor(dim)
            alpha = 0f
            setOnClickListener { dismiss() }
        }
        host.addView(s, flp(MATCH, MATCH))
        scrim = s

        val handle = View(c).apply {
            background = rounded(p.border, 2f.dp)
            layoutParams = lp(36.dp, 4.dp).margins(t = 10.dp, b = 6.dp).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(p.surface, Radius.sheet)
            elevation = 24f.dp
            setPadding(0, 0, 0, 16.dp + bottomInset)
            isClickable = true
            addView(handle)
            addView(content, lp(MATCH, WRAP))
        }
        val lp = flp(MATCH, WRAP, Gravity.BOTTOM)
        val maxW = 560.dp
        if (host.width > maxW) { lp.width = maxW; lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        host.addView(box, lp)
        panel = box
        enableDrag(box)

        box.translationY = host.height.toFloat()
        box.post {
            box.translationY = box.height.toFloat()
            box.animate().translationY(0f).setDuration(Motion.SLOW).setInterpolator(Motion.ease).start()
        }
        s.animate().alpha(1f).setDuration(Motion.NORMAL)
            .setUpdateListener { onScrim?.invoke(withAlpha(dim, s.alpha * android.graphics.Color.alpha(dim) / 255f)) }.start()
        Haptics.tick(host)
    }

    private fun enableDrag(box: View) {
        var startY = 0f
        var dragging = false
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (dy > 6.dp) dragging = true
                    if (dragging) v.translationY = max(0f, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && v.translationY > v.height * 0.25f) dismiss()
                    else v.animate().translationY(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
                    true
                }
                else -> false
            }
        }
    }

    fun dismiss(animate: Boolean = true) {
        val s = scrim
        val b = panel
        scrim = null
        panel = null
        if (s == null && b == null) return
        if (!animate) {
            host.removeView(s); host.removeView(b)
        } else {
            b?.animate()?.translationY(b.height.toFloat())?.setDuration(Motion.NORMAL)?.setInterpolator(Motion.easeIn)
                ?.withEndAction { host.removeView(b) }?.start()
            s?.animate()?.alpha(0f)?.setDuration(Motion.NORMAL)
                ?.setUpdateListener { onScrim?.invoke(withAlpha(dim, s.alpha * android.graphics.Color.alpha(dim) / 255f)) }
                ?.withEndAction { host.removeView(s); onScrim?.invoke(0) }?.start()
        }
        if (!animate) onScrim?.invoke(0)
        onDismiss?.invoke()
    }
}

/** A titled list of choices inside a sheet, with a check on the selected one. */
fun choiceList(
    c: Context, p: Palette, title: String, options: List<Pair<String, String?>>, selected: Int, onPick: (Int) -> Unit,
): View = LinearLayout(c).apply {
    orientation = LinearLayout.VERTICAL
    addView(c.label(title, 18f, p.text, bold = true, tracking = -0.01f).apply {
        setPadding(24.dp, 10.dp, 24.dp, 12.dp)
    })
    options.forEachIndexed { i, (name, detail) ->
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 56.dp
            setPadding(24.dp, 10.dp, 20.dp, 10.dp)
            pressable(p, 0f)
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, WRAP, 1f)
                addView(c.label(name, 16f, p.text))
                if (detail != null) addView(c.label(detail, 13f, p.text2).apply {
                    setPadding(0, 4.dp, 0, 0)
                    setLineSpacing(2f.dp, 1f)
                })
            })
            addView(c.icon(R.drawable.ic_check, if (i == selected) p.accent else 0, 18))
            setOnClickListener { Haptics.tick(it); onPick(i) }
        })
    }
}

/* =============================================================================================
 * Toast — a small ink pill above the toolbar, optionally with an action.
 * =========================================================================================== */

class DotToast(private val host: FrameLayout) {
    private var current: View? = null
    private val hide = Runnable { dismiss() }

    fun show(p: Palette, message: String, action: String? = null, bottomOffset: Int, topOffset: Int = 0, onAction: (() -> Unit)? = null) {
        dismiss(immediate = true)
        val c = host.context
        val pill = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.ink, Radius.card)
            elevation = 10f.dp
            setPadding(18.dp, 0, if (action != null) 6.dp else 18.dp, 0)
            minimumHeight = 48.dp
            addView(c.label(message, 14f, p.onInk).apply {
                layoutParams = lp(0, WRAP, 1f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (action != null) {
                addView(c.label(action, 14f, if (p.isDark) p.accent.let { blend(it, 0xFF000000.toInt(), 0.25f) } else 0xFF8FB5FF.toInt(), bold = true).apply {
                    setPadding(14.dp, 14.dp, 14.dp, 14.dp)
                    background = ripple(withAlpha(p.onInk, 0.15f), Radius.small)
                    setOnClickListener {
                        Haptics.tap(it)
                        onAction?.invoke()
                        dismiss()
                    }
                })
            }
        }
        val lp = flp(MATCH, WRAP, if (topOffset > 0) Gravity.TOP else Gravity.BOTTOM).margins(l = 12.dp, r = 12.dp)
        if (topOffset > 0) lp.topMargin = topOffset + 8.dp else lp.bottomMargin = bottomOffset + 8.dp
        host.addView(pill, lp)
        current = pill
        pill.alpha = 0f
        pill.translationY = if (topOffset > 0) -12f.dp else 12f.dp
        pill.animate().alpha(1f).translationY(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
        host.removeCallbacks(hide)
        host.postDelayed(hide, if (action != null) 4500 else 2800)
    }

    fun dismiss(immediate: Boolean = false) {
        val v = current ?: return
        current = null
        host.removeCallbacks(hide)
        if (immediate) host.removeView(v)
        else v.animate().alpha(0f).setDuration(Motion.NORMAL).withEndAction { host.removeView(v) }.start()
    }
}
