package co.dothq.browser.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import co.dothq.browser.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The intelligence glow: light flows in from the bottom of the screen, wraps around both edges
 * and meets at the top, then breathes in the Dot ring colours while the model works.
 */
class IntelligenceGlow(context: Context) : View(context) {
    private val colors = intArrayOf(
        0xFF2D8FE0.toInt(), 0xFF6A5CE0.toInt(), 0xFFC05CE0.toInt(), 0xFFE0526E.toInt(),
        0xFFF2893A.toInt(), 0xFFE9C230.toInt(), 0xFF1FB364.toInt(), 0xFF16B3A3.toInt(), 0xFF2D8FE0.toInt(),
    )
    private val path = Path()
    private val seg = Path()
    private val measure = PathMeasure()
    private var length = 0f
    private val m = Matrix()
    private var shader: SweepGradient? = null
    private val layers = listOf(
        Triple(46f.dp, 34f.dp, 0.45f),   // wide soft bloom
        Triple(18f.dp, 12f.dp, 0.75f),   // glow
        Triple(5f.dp, 2.5f.dp, 0.95f),   // bright core
        Triple(2f.dp, 0f, 1f),           // crisp edge
    ).map { (w, blur, a) ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w
            strokeCap = Paint.Cap.ROUND
            if (blur > 0) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        } to a
    }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private var reveal = 0f
    private var intensity = 0f
    private var working = false
    private val born = SystemClock.uptimeMillis()
    var cornerRadius = 40f.dp

    /**
     * How bright the light is, 0–1. A summary gets the full glow; work Dot carries on with in
     * the background gets a quieter one, so it can stay up for a minute without shouting.
     */
    var strength = 1f

    init {
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Follow the phone's own screen corners where Android tells us them.
        if (Build.VERSION.SDK_INT >= 31) {
            rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.let { if (it > 0) cornerRadius = it.toFloat() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = 1.5f.dp
        val r = RectF(inset, inset, w - inset, h - inset)
        val rad = min(cornerRadius, min(r.width(), r.height()) / 2)
        // Start at the bottom centre and go clockwise.
        path.reset()
        path.moveTo(r.centerX(), r.bottom)
        path.lineTo(r.left + rad, r.bottom)
        path.arcTo(RectF(r.left, r.bottom - 2 * rad, r.left + 2 * rad, r.bottom), 90f, 90f)
        path.lineTo(r.left, r.top + rad)
        path.arcTo(RectF(r.left, r.top, r.left + 2 * rad, r.top + 2 * rad), 180f, 90f)
        path.lineTo(r.right - rad, r.top)
        path.arcTo(RectF(r.right - 2 * rad, r.top, r.right, r.top + 2 * rad), 270f, 90f)
        path.lineTo(r.right, r.bottom - rad)
        path.arcTo(RectF(r.right - 2 * rad, r.bottom - 2 * rad, r.right, r.bottom), 0f, 90f)
        path.close()
        measure.setPath(path, false)
        length = measure.length
        shader = SweepGradient(w / 2f, h / 2f, colors, null)
        layers.forEach { it.first.shader = shader }
    }

    /** Light sweeps in from the bottom and wraps the screen. */
    fun begin() {
        working = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = Motion.ease
            addUpdateListener { reveal = it.animatedValue as Float; intensity = min(1f, reveal * 1.6f); invalidate() }
            start()
        }
        invalidate()
    }

    /** Settles to a gentle edge, then fades away. */
    fun end(then: () -> Unit) {
        working = false
        ValueAnimator.ofFloat(intensity, 0f).apply {
            duration = 700
            interpolator = Motion.easeIn
            addUpdateListener { intensity = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = then()
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (length == 0f || intensity <= 0.001f) return
        val t = (SystemClock.uptimeMillis() - born) / 1000f
        m.setRotate((t * 110f) % 360f, width / 2f, height / 2f)
        shader?.setLocalMatrix(m)

        // A soft wash of colour rising from the bottom edge as it arrives.
        if (reveal < 1f || working) {
            val washA = ((1f - reveal).coerceIn(0f, 1f) * 0.35f + if (working) 0.05f + 0.04f * sin(t * 2.2f) else 0f) * strength
            wash.shader = RadialGradient(width / 2f, height.toFloat(), max(width, height) * 0.55f,
                intArrayOf(withAlpha(colors[2], washA * intensity), withAlpha(colors[0], washA * 0.4f * intensity), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wash)
        }

        seg.reset()
        val half = length / 2f * reveal
        measure.getSegment(0f, half, seg, true)
        measure.getSegment(length - half, length, seg, true)
        val breathe = if (working) 0.85f + 0.15f * sin(t * 2.6f) else 1f
        for ((paint, a) in layers) {
            paint.alpha = (a * intensity * breathe * strength * 255).toInt().coerceIn(0, 255)
            canvas.drawPath(seg, paint)
        }
        postInvalidateOnAnimation()
    }
}

interface SummaryHost {
    fun summaryAsk(summary: String)
    fun summaryCopy(text: String)
    fun summaryDismissed()
}

/** The card the summary streams into, rising from the bottom of the screen. */
@SuppressLint("ViewConstructor")
class SummaryCard(context: Context, private val p: Palette, title: String, host: String, private val cb: SummaryHost) : FrameLayout(context) {
    private val body = TextView(context)
    private val stage = ShimmerText(context)
    private val actions = LinearLayout(context)
    private val card = LinearLayout(context)
    private var text = ""

    init {
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(p.surface, 26f.dp, withAlpha(p.text, 0.08f), 1)
        card.elevation = 22f.dp
        card.setPadding(20.dp, 16.dp, 20.dp, 14.dp)
        card.isClickable = true

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(DotRingView(context).apply { halo = false }, lp(20.dp, 20.dp).margins(r = 10.dp))
        head.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(0, WRAP, 1f)
            addView(context.label("Summary", 15f, p.text, bold = true))
            addView(context.label(title.ifBlank { host }, 12.5f, p.text3).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 2.dp, 0, 0)
            })
        })
        head.addView(context.iconButton(R.drawable.ic_close, p.text2, p, boxDp = 36, iconDp = 14, desc = "Close").apply {
            setOnClickListener { Haptics.tap(it); dismiss() }
        })
        card.addView(head)

        stage.style(14f, p.text2, bold = true)
        stage.text = "Reading the page"
        stage.setPadding(0, 14.dp, 0, 4.dp)
        card.addView(stage)

        body.style(15.5f, p.text)
        body.setLineSpacing(4f.dp, 1f)
        body.setTextIsSelectable(true)
        body.setPadding(0, 10.dp, 0, 4.dp)
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        card.addView(object : FrameLayout(context) {
            override fun onMeasure(w: Int, h: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.42f).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply { addView(scroll) }, lp(MATCH, WRAP))

        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(0, 12.dp, 0, 0)
        actions.visibility = GONE
        actions.addView(pill("Ask a follow-up", p.ink, p.onInk, R.drawable.ic_sparkle) { cb.summaryAsk(text); dismiss() }, lp(0, 46.dp, 1f).margins(r = 6.dp))
        actions.addView(pill("Copy", p.field, p.text, R.drawable.ic_copy) { cb.summaryCopy(text) }, lp(WRAP, 46.dp).margins(l = 6.dp))
        card.addView(actions)

        addView(card, flp(MATCH, WRAP, Gravity.BOTTOM).margins(12.dp, 0, 12.dp, 14.dp))
        enableSwipe()
    }

    private fun pill(label: String, bg: Int, fg: Int, icon: Int, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(18.dp, 0, 18.dp, 0)
        background = ripple(withAlpha(fg, 0.15f), Radius.pill, rounded(bg, Radius.pill))
        isClickable = true
        sinkOnPress()
        addView(context.icon(icon, fg, 16).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 8.dp })
        addView(context.label(label, 14.5f, fg, bold = true))
        setOnClickListener { Haptics.tap(it); onClick() }
    }

    fun show(bottom: Int) {
        (card.layoutParams as LayoutParams).bottomMargin = 14.dp + bottom
        card.translationY = 400f.dp
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setStartDelay(220).setDuration(Motion.SLOW + 100).setInterpolator(Motion.spring).start()
    }

    fun setStage(s: String) { stage.text = s }

    fun setText(t: String) {
        text = co.dothq.browser.ai.Assistant.cleanReply(t)
        if (text.isNotEmpty()) stage.visibility = GONE
        body.text = MiniMarkdown.render(text, p)
    }

    fun finish(error: String?) {
        stage.shimmering = false
        if (error != null) {
            stage.visibility = VISIBLE
            stage.text = error
            stage.setTextColor(p.text2)
        } else {
            actions.visibility = VISIBLE
            actions.alpha = 0f
            actions.animate().alpha(1f).setDuration(Motion.NORMAL).start()
            Haptics.confirm(this)
        }
    }

    fun dismiss() {
        card.animate().translationY(card.height + 40f.dp).alpha(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.easeIn)
            .withEndAction { (parent as? FrameLayout)?.removeView(this); cb.summaryDismissed() }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableSwipe() {
        var startY = 0f
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; false }
                MotionEvent.ACTION_MOVE -> { v.translationY = max(0f, e.rawY - startY); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (v.translationY > v.height * 0.3f) dismiss()
                    else v.animate().translationY(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.spring).start()
                    true
                }
                else -> false
            }
        }
    }
}
