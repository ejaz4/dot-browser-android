package co.dothq.browser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

/* ---------------------------------------------------------------------------------------------
 * Spatial transitions. Pages never just appear: they shrink into the card they belong to,
 * expand out of the thing you touched, slide the way you swiped. A "ghost" — a snapshot of the
 * page — flies between two rectangles so you can always see where something came from.
 * ------------------------------------------------------------------------------------------- */

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

fun lerp(a: RectF, b: RectF, t: Float) = RectF(
    lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t),
)

/** [v]'s bounds in [relativeTo]'s coordinate space. */
fun rectIn(v: View, relativeTo: View): RectF {
    val a = IntArray(2).also { v.getLocationInWindow(it) }
    val b = IntArray(2).also { relativeTo.getLocationInWindow(it) }
    val x = (a[0] - b[0]).toFloat()
    val y = (a[1] - b[1]).toFloat()
    return RectF(x, y, x + v.width, y + v.height)
}

/**
 * A snapshot of a page that can be placed over any rectangle. The image is always scaled to the
 * rectangle's width and anchored to the top (like the tab cards), and its corners round as it
 * shrinks — so it lands exactly on a tab card's thumbnail, or fills the screen.
 */
@SuppressLint("ViewConstructor")
class PageGhost(context: Context, bitmap: Bitmap?, background: Int, private val fullW: Int, private val fullH: Int) :
    FrameLayout(context) {
    private var visibleH = fullH.toFloat()
    private var radius = 0f

    init {
        setBackgroundColor(background)
        if (bitmap != null) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.MATRIX
                setImageBitmap(bitmap)
                val s = fullW.toFloat() / bitmap.width
                imageMatrix = Matrix().apply { setScale(s, s) }
            }
            addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        pivotX = 0f
        pivotY = 0f
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, fullW, visibleH.toInt().coerceIn(1, fullH * 4), radius)
            }
        }
        isClickable = true // swallow taps mid-flight
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Covers [r] (in the parent's coordinates) with corners of [visibleRadius] on screen. */
    fun place(r: RectF, visibleRadius: Float) {
        val s = (r.width() / fullW).coerceAtLeast(0.001f)
        scaleX = s
        scaleY = s
        translationX = r.left
        translationY = r.top
        visibleH = r.height() / s
        radius = visibleRadius / s
        invalidateOutline()
    }

    companion object {
        /** Adds a ghost sized for [full] to [host], placed at [start]. */
        fun attach(host: ViewGroup, bitmap: Bitmap?, background: Int, full: RectF, start: RectF, startRadius: Float): PageGhost {
            val g = PageGhost(host.context, bitmap, background, full.width().toInt().coerceAtLeast(1), full.height().toInt().coerceAtLeast(1))
            host.addView(g, FrameLayout.LayoutParams(g.fullW, g.fullH))
            g.place(start, startRadius)
            return g
        }
    }
}

/** Flies [ghost] from one rectangle to another, rounding/unrounding its corners on the way. */
fun flyGhost(
    ghost: PageGhost,
    from: RectF,
    to: RectF,
    fromRadius: Float,
    toRadius: Float,
    duration: Long = 400,
    interpolator: TimeInterpolator = Motion.ease,
    fadeTo: Float? = null,
    onUpdate: ((Float) -> Unit)? = null,
    onEnd: () -> Unit,
): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
    this.duration = duration
    this.interpolator = interpolator
    addUpdateListener {
        val t = it.animatedValue as Float
        ghost.place(lerp(from, to, t), lerp(fromRadius, toRadius, t))
        if (fadeTo != null) ghost.alpha = lerp(1f, fadeTo, t)
        // A whisper of depth while it travels.
        ghost.elevation = 10f.dp * (1f - kotlin.math.abs(t - 0.5f) * 2f).coerceAtLeast(0f)
        onUpdate?.invoke(t)
    }
    addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) = onEnd()
    })
    start()
}
