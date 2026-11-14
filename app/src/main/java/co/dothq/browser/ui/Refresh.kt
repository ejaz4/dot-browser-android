package co.dothq.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import kotlin.math.abs
import kotlin.math.min

/**
 * A GeckoView that asks Gecko, on every touch-down, how the page is going to treat the touch:
 * whether it can still scroll up, and whether the page's own script is handling it. That lets
 * pull-to-refresh step in only when it won't steal a gesture from the page.
 */
class DotGeckoView(context: Context) : GeckoView(context) {
    /** The answer for the current touch, or null until Gecko replies. */
    var touchDetail: PanZoomController.InputResultDetail? = null
        private set
    private var touchToken = 0

    /** Whether the page is scrolled to its very top (from Gecko's scroll delegate). */
    var atTop: () -> Boolean = { true }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) touchDetail = null
        // Gecko settles how it will treat a touch once it has seen enough of it, so keep asking
        // until it answers (like Firefox does).
        if (touchDetail == null && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
            if (action == MotionEvent.ACTION_DOWN) ++touchToken
            val token = touchToken
            onTouchEventForDetailResult(event).accept { detail ->
                if (token == touchToken && detail != null) touchDetail = detail
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    /** True when the page is at its top and isn't handling this touch itself. */
    fun canPullToRefresh(): Boolean {
        val d = touchDetail ?: return atTop()
        if (d.handledResult() == PanZoomController.INPUT_RESULT_HANDLED_CONTENT) return false
        return d.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP == 0 && atTop()
    }
}

/** The pull-to-refresh indicator: the Dot ring fills as you pull and spins as the page reloads. */
class RefreshIndicator(context: Context, private var p: Palette) : View(context) {
    var progress = 0f
        set(value) { field = value; invalidate() }
    private var spinning = false
    private var spinStart = 0L
    private val colors = ringColors(context)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val m = Matrix()

    init {
        elevation = 6f.dp
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = oval(p.surface)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun spin() {
        spinning = true
        spinStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun reset() {
        spinning = false
        progress = 0f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.27f
        val w = r * 0.55f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = w
        track.color = withAlpha(p.text, 0.08f)
        canvas.drawCircle(cx, cy, r, track)
        val shader = SweepGradient(cx, cy, colors, null)
        val t = (SystemClock.uptimeMillis() - spinStart) / 1000f
        m.setRotate(if (spinning) (t * 420f) % 360f else progress * 140f - 90f, cx, cy)
        shader.setLocalMatrix(m)
        ring.shader = shader
        ring.strokeWidth = w
        if (spinning) {
            canvas.drawArc(rect, (t * 420f) % 360f, 300f, false, ring)
            postInvalidateOnAnimation()
        } else {
            canvas.drawArc(rect, -90f, 360f * progress.coerceIn(0f, 1f), false, ring)
        }
    }

    fun setPalette(palette: Palette) {
        p = palette
        background = oval(p.surface)
        invalidate()
    }
}

/**
 * Wraps the page. When the page is at its top and you drag down, the Dot ring comes down to meet
 * your finger, fills as you pull, ticks at the threshold, and reloads the page on release.
 */
@SuppressLint("ViewConstructor")
class RefreshLayout(context: Context, private val gecko: DotGeckoView, p: Palette) : FrameLayout(context) {
    var onRefresh: (() -> Unit)? = null
    var refreshEnabled: () -> Boolean = { true }
    private val indicator = RefreshIndicator(context, p)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 88.dp.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var armed = false
    private var busy = false

    init {
        addView(gecko, flp(MATCH, MATCH))
        addView(indicator, flp(42.dp, 42.dp, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        indicator.translationY = -60f.dp
        indicator.alpha = 0f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; pulling = false; armed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (!busy && dy > slop * 1.5f && dy > abs(dx) * 1.6f && refreshEnabled() && gecko.canPullToRefresh()) {
                    pulling = true
                    downY = ev.y - slop // start from here, without a jump
                    indicator.animate().cancel()
                    indicator.reset()
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!pulling) return false
        val dy = (ev.y - downY).coerceAtLeast(0f)
        // Resistance: the further you pull, the harder it gets.
        val travel = threshold * (1f - 1f / (1f + dy / threshold)) * 2.1f
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val progress = (dy / (threshold * 1.6f)).coerceIn(0f, 1f)
                indicator.progress = progress
                indicator.alpha = (progress * 3f).coerceAtMost(1f)
                indicator.translationY = -60f.dp + travel + 12f.dp
                indicator.rotation = 0f
                val nowArmed = progress >= 1f
                if (nowArmed != armed) {
                    armed = nowArmed
                    Haptics.tick(this)
                    indicator.animate().scaleX(if (armed) 1.12f else 1f).scaleY(if (armed) 1.12f else 1f)
                        .setDuration(Motion.FAST).setInterpolator(Motion.spring).start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false
                if (armed && ev.actionMasked == MotionEvent.ACTION_UP) {
                    busy = true
                    Haptics.confirm(this)
                    indicator.spin()
                    indicator.animate().translationY(40f.dp).scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
                    onRefresh?.invoke()
                    // The loading halo takes over from here.
                    postDelayed({ finish() }, 900)
                } else finish()
            }
        }
        return true
    }

    fun finish() {
        indicator.animate().translationY(-60f.dp).alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(Motion.NORMAL)
            .setInterpolator(Motion.easeIn).withEndAction {
                indicator.reset()
                indicator.scaleX = 1f; indicator.scaleY = 1f
                busy = false
            }.start()
    }

    fun setPalette(p: Palette) = indicator.setPalette(p)
}
