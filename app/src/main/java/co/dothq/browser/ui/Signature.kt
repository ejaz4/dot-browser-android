package co.dothq.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
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
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ---------------------------------------------------------------------------------------------
 * The signature pieces. Dot's chrome is quiet and monochrome, so colour is reserved for moments
 * that matter: when the web is loading, and when Dot is protecting you. Colour always comes from
 * the Dot HQ ring.
 * ------------------------------------------------------------------------------------------- */

/**
 * The loading halo. Instead of a progress bar, the address field's own outline fills with the
 * Dot ring gradient — drawn from the left, around both edges, meeting on the right — then
 * blooms and fades when the page is ready.
 */
class AuroraOutline @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** Space around the field reserved for the glow. */
    val glowMargin = 7.dp
    var cornerRadius = Radius.field
        set(value) { field = value; rebuild() }

    private val stroke = 1.75f.dp
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke * 3.5f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(6f.dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val path = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private var length = 0f
    private val shaderMatrix = Matrix()
    private var shader: SweepGradient? = null
    private var colors = ringColors(context)

    private var shown = 0f      // what is drawn, eased towards target
    private var target = 0f
    private var opacity = 0f
    private var bloom = 0f
    private var active = false
    private var finishing = false
    private var finishStart = 0L
    private var lastFrame = 0L

    init {
        // Blur mask filters need a software layer before Android 9.
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPrivate(private: Boolean) {
        colors = if (private) intArrayOf(
            0xFFA78BFF.toInt(), 0xFF7C5CFF.toInt(), 0xFFE879F9.toInt(), 0xFF8B5CF6.toInt(), 0xFFA78BFF.toInt()
        ) else ringColors(context)
        rebuild()
    }

    /** 0–100 from Gecko. */
    fun setProgress(progress: Int) {
        if (!active) start()
        target = max(target, (progress / 100f).coerceIn(0.06f, 1f))
        if (progress >= 100) finish()
        tick()
    }

    fun start() {
        active = true
        finishing = false
        shown = 0f
        target = 0.06f
        opacity = 1f
        bloom = 0f
        tick()
    }

    fun finish() {
        if (!active || finishing) return
        target = 1f
        finishing = true
        finishStart = SystemClock.uptimeMillis()
        tick()
    }

    fun reset() {
        active = false
        finishing = false
        shown = 0f
        target = 0f
        opacity = 0f
        invalidate()
    }

    private fun tick() {
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        val inset = glowMargin.toFloat() + stroke / 2f - 0.5f.dp
        val r = RectF(inset, inset, width - inset, height - inset)
        val rad = min(cornerRadius, r.height() / 2f)
        val cy = r.centerY()
        // A closed loop starting at the left middle, clockwise.
        path.reset()
        path.moveTo(r.left, cy)
        path.lineTo(r.left, r.top + rad)
        path.arcTo(RectF(r.left, r.top, r.left + 2 * rad, r.top + 2 * rad), 180f, 90f)
        path.lineTo(r.right - rad, r.top)
        path.arcTo(RectF(r.right - 2 * rad, r.top, r.right, r.top + 2 * rad), 270f, 90f)
        path.lineTo(r.right, r.bottom - rad)
        path.arcTo(RectF(r.right - 2 * rad, r.bottom - 2 * rad, r.right, r.bottom), 0f, 90f)
        path.lineTo(r.left + rad, r.bottom)
        path.arcTo(RectF(r.left, r.bottom - 2 * rad, r.left + 2 * rad, r.bottom), 90f, 90f)
        path.close()
        measure.setPath(path, false)
        length = measure.length
        shader = SweepGradient(width / 2f, height / 2f, colors, null)
        line.shader = shader
        glow.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        if (!active || length == 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1, 64).toFloat()
        lastFrame = now

        // Ease towards target; never stall completely so slow pages still feel alive.
        val k = 1f - Math.pow(0.0035, (dt / 1000.0)).toFloat()
        shown += (target - shown) * min(1f, k * 2.2f)
        if (!finishing && shown < 0.92f) target = min(0.92f, target + dt * 0.00002f)

        if (finishing && shown > 0.995f) {
            val t = (now - finishStart - 120).coerceAtLeast(0) / 520f
            bloom = sin(min(1f, t) * PI.toFloat())
            opacity = (1f - t).coerceIn(0f, 1f)
            if (t >= 1f) {
                reset()
                return
            }
        }

        // The gradient slowly turns, so even a stalled load shimmers.
        val angle = (now % 3600L) / 10f
        shaderMatrix.setRotate(angle, width / 2f, height / 2f)
        shader?.setLocalMatrix(shaderMatrix)

        val half = length / 2f * shown
        segment.reset()
        measure.getSegment(0f, half, segment, true)
        measure.getSegment(length - half, length, segment, true)

        glow.alpha = (opacity * (0.55f + bloom * 0.45f) * 255).toInt().coerceIn(0, 255)
        glow.strokeWidth = stroke * (3.5f + bloom * 2.5f)
        canvas.drawPath(segment, glow)
        line.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        canvas.drawPath(segment, line)

        postInvalidateOnAnimation()
    }
}

/**
 * The Dot HQ mark — a thick ring in the brand gradient — rendered live. It turns very slowly
 * and breathes a soft halo, a quiet sign the browser is awake.
 */
class DotRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var spinning = true
    var halo = true
    /** Ring thickness as a fraction of the outer radius (the mark is ~0.58). */
    var thickness = 0.58f
    var monochrome: Int? = null
        set(value) { field = value; shader = null; invalidate() }
    private var colors = ringColors(context)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var shader: SweepGradient? = null
    private val m = Matrix()
    private val born = SystemClock.uptimeMillis()

    init {
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColors(c: IntArray) {
        colors = c
        shader = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val haloRoom = if (halo) min(width, height) * 0.2f else 0f
        val outer = min(width, height) / 2f - haloRoom
        val w = outer * thickness
        val r = outer - w / 2f
        if (shader == null) {
            shader = SweepGradient(cx, cy, colors, null)
        }
        val t = (SystemClock.uptimeMillis() - born) / 1000f
        if (spinning) {
            m.setRotate((t * 14f) % 360f, cx, cy)
            shader!!.setLocalMatrix(m)
        }
        val mono = monochrome
        if (halo && mono == null) {
            val breathe = 0.5f + 0.5f * sin(t * 1.6f)
            haloPaint.shader = shader
            haloPaint.strokeWidth = w * 1.1f
            haloPaint.maskFilter = BlurMaskFilter(max(1f, haloRoom * (0.45f + 0.15f * breathe)), BlurMaskFilter.Blur.NORMAL)
            haloPaint.alpha = (70 + 50 * breathe).toInt()
            canvas.drawCircle(cx, cy, r, haloPaint)
        }
        ring.strokeWidth = w
        if (mono != null) {
            ring.shader = null
            ring.color = mono
        } else {
            ring.shader = shader
        }
        canvas.drawCircle(cx, cy, r, ring)
        if (spinning || halo) postInvalidateOnAnimation()
    }
}

/**
 * A barely-there wash of the ring colours that drifts behind the new tab page and welcome
 * screen. You don't notice it until you do.
 */
class AuroraBackdrop @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var intensity = 0.16f
    private var colors = ringColors(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val born = SystemClock.uptimeMillis()
    var animated = true

    fun setPrivate(private: Boolean) {
        colors = if (private) intArrayOf(
            0xFF7C3AED.toInt(), 0xFFA78BFF.toInt(), 0xFFDB2777.toInt(), 0xFF4F46E5.toInt(), 0xFF7C3AED.toInt()
        ) else ringColors(context)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val t = (SystemClock.uptimeMillis() - born) / 1000f
        val blobs = 4
        for (i in 0 until blobs) {
            val phase = i * 1.7f
            val x = w * (0.2f + 0.6f * (0.5f + 0.5f * sin(t * 0.07f + phase)))
            val y = h * (0.05f + 0.22f * (0.5f + 0.5f * cos(t * 0.05f + phase * 1.3f)))
            val radius = max(w, h) * (0.45f + 0.08f * sin(t * 0.09f + phase))
            val c = colors[(i * 2) % (colors.size - 1)]
            val a = intensity * (0.75f + 0.25f * sin(t * 0.11f + phase))
            paint.shader = RadialGradient(
                x, y, radius,
                intArrayOf(withAlpha(c, a), withAlpha(c, a * 0.35f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        if (animated) postInvalidateDelayed(48)
    }
}

/** The tab counter: a rounded square holding the number of open tabs, like desktop Dot. */
class TabCountView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f.dp
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    var count = 1
        private set
    var color = Color.BLACK
        set(value) { field = value; invalidate() }

    fun setCount(n: Int, animate: Boolean) {
        if (n == count) return
        count = n
        contentDescription = "$n tabs"
        invalidate()
        if (animate) {
            animate().cancel()
            scaleX = 0.7f; scaleY = 0.7f
            animate().scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Motion.spring).start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        Fonts.init(context)
        val s = 18.5f.dp
        val cx = width / 2f
        val cy = height / 2f
        rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        box.color = color
        canvas.drawRoundRect(rect, 5f.dp, 5f.dp, box)
        val label = if (count > 99) "∞" else count.toString()
        text.typeface = Fonts.bold
        text.color = color
        text.textSize = if (label.length > 1) 9.5f.dp else 11f.dp
        val fm = text.fontMetrics
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }
}

/** Paints a TextView's glyphs with the ring gradient (used for the tracker counter). */
fun gradientText(view: android.widget.TextView, colors: IntArray) {
    view.post {
        val w = view.paint.measureText(view.text.toString()).coerceAtLeast(1f)
        view.paint.shader = android.graphics.LinearGradient(
            0f, 0f, w, view.textSize, colors, null, Shader.TileMode.CLAMP,
        )
        view.invalidate()
    }
}

/** Counts a number up with an ease, for stats that should feel earned. */
fun countUp(view: android.widget.TextView, to: Long, format: (Long) -> String, then: (() -> Unit)? = null) {
    val from = 0L
    if (to <= 0) { view.text = format(0); then?.invoke(); return }
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = (600 + min(900L, to * 4)).coerceAtMost(1400)
        interpolator = Motion.ease
        addUpdateListener {
            view.text = format(from + ((to - from) * (it.animatedValue as Float)).toLong())
        }
        doOnEnd { then?.invoke() }
        start()
    }
}

private fun ValueAnimator.doOnEnd(block: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}
