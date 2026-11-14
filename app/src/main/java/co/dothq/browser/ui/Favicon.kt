package co.dothq.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/** A letter tile for sites without an icon, tinted from the ring colours by host. */
class Monogram(private val context: Context, host: String, private val radiusFraction: Float = 0.28f) : Drawable() {
    private val letter = host.removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    private val color: Int
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    init {
        val ring = ringColors(context)
        color = ring[abs(host.hashCode()) % (ring.size - 1)]
        Fonts.init(context)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        val r = min(b.width(), b.height()) * radiusFraction
        bg.color = withAlpha(color, 0.16f)
        canvas.drawRoundRect(rect, r, r, bg)
        fg.color = blend(color, 0xFF000000.toInt(), 0.12f)
        fg.typeface = Fonts.bold
        fg.textSize = min(b.width(), b.height()) * 0.52f
        val fm = fg.fontMetrics
        canvas.drawText(letter, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, fg)
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; fg.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

fun ImageView.bindFavicon(icon: Bitmap?, host: String) {
    imageTintList = null
    if (icon != null) {
        setImageBitmap(icon)
    } else {
        setImageDrawable(Monogram(context, host))
    }
}

/** The ring gradient as a rounded-rect border — marks the selected tab. */
class RingBorder(context: Context, private val radius: Float, private val stroke: Float) : Drawable() {
    private val colors = ringColors(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val rect = RectF()
    private val m = Matrix()
    var rotation = 0f
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2)
        val shader = SweepGradient(rect.centerX(), rect.centerY(), colors, null)
        m.setRotate(rotation, rect.centerX(), rect.centerY())
        shader.setLocalMatrix(m)
        paint.shader = shader
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Shows the top of a page screenshot, scaled to the view's width. */
class TopCropImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    init { scaleType = ScaleType.MATRIX }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        fit()
        return changed
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        val vw = width - paddingLeft - paddingRight
        if (vw <= 0 || d.intrinsicWidth <= 0) return
        val scale = vw.toFloat() / d.intrinsicWidth
        imageMatrix = Matrix().apply { setScale(scale, scale) }
    }
}
