package co.dothq.browser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import co.dothq.browser.R
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------------------------------
 * Dot UI kit: the handful of primitives every surface in the app is built from, so the whole
 * browser shares one set of radii, colours, type and motion — the same language as desktop Dot.
 * ------------------------------------------------------------------------------------------- */

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).roundToInt()
val Float.dp: Float get() = this * Resources.getSystem().displayMetrics.density

fun Context.col(id: Int): Int = ContextCompat.getColor(this, id)

object Motion {
    /** The standard Dot ease: quick out of the gate, long gentle settle. */
    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    val easeIn = PathInterpolator(0.4f, 0f, 1f, 1f)
    /** A soft overshoot for things that "arrive" (menus, cards). */
    val spring = PathInterpolator(0.34f, 1.36f, 0.64f, 1f)
    const val FAST = 140L
    const val NORMAL = 220L
    const val SLOW = 360L
}

object Radius {
    val small = 6.dp.toFloat()
    val field = 10.dp.toFloat()   // dot.ui.roundness = 8 on desktop; a touch more on touch screens
    val card = 12.dp.toFloat()
    val sheet = 20.dp.toFloat()
    val pill = 999.dp.toFloat()
}

object Fonts {
    lateinit var regular: Typeface
    lateinit var bold: Typeface

    fun init(context: Context) {
        if (::regular.isInitialized) return
        regular = ResourcesCompat.getFont(context, R.font.inter_reg) ?: Typeface.DEFAULT
        bold = ResourcesCompat.getFont(context, R.font.inter_bold) ?: Typeface.DEFAULT_BOLD
    }
}

/** The ring colours of the Dot HQ mark, clockwise from 3 o'clock. Closed (first == last). */
fun ringColors(context: Context): IntArray = intArrayOf(
    context.col(R.color.ring_1), context.col(R.color.ring_2), context.col(R.color.ring_3),
    context.col(R.color.ring_4), context.col(R.color.ring_5), context.col(R.color.ring_6),
    context.col(R.color.ring_7), context.col(R.color.ring_8), context.col(R.color.ring_1),
)

/**
 * Chrome colours. Normal tabs follow the desktop light/dark themes; private tabs get their own
 * deep-violet palette so it is always obvious which world you are in.
 */
data class Palette(
    val bg: Int,
    val frame: Int,
    val surface: Int,
    val field: Int,
    val fieldPressed: Int,
    val border: Int,
    val divider: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val icon: Int,
    val iconDisabled: Int,
    val accent: Int,
    val shield: Int,
    val ripple: Int,
    val scrim: Int,
    val ink: Int,
    val onInk: Int,
    val shadow: Int,
    val isDark: Boolean,
    val isPrivate: Boolean,
) {
    companion object {
        fun normal(c: Context) = Palette(
            bg = c.col(R.color.dot_bg),
            frame = c.col(R.color.dot_frame),
            surface = c.col(R.color.dot_surface),
            field = c.col(R.color.dot_field),
            fieldPressed = c.col(R.color.dot_field_pressed),
            border = c.col(R.color.dot_border),
            divider = c.col(R.color.dot_divider),
            text = c.col(R.color.dot_text),
            text2 = c.col(R.color.dot_text_secondary),
            text3 = c.col(R.color.dot_text_tertiary),
            icon = c.col(R.color.dot_icon),
            iconDisabled = c.col(R.color.dot_icon_disabled),
            accent = c.col(R.color.dot_accent),
            shield = c.col(R.color.dot_shield),
            ripple = c.col(R.color.dot_ripple),
            scrim = c.col(R.color.dot_scrim),
            ink = c.col(R.color.dot_ink),
            onInk = c.col(R.color.dot_on_ink),
            shadow = c.col(R.color.dot_shadow),
            isDark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
            isPrivate = false,
        )

        val PRIVATE = Palette(
            bg = 0xFF16111F.toInt(),
            frame = 0xFF0F0B16.toInt(),
            surface = 0xFF221A2E.toInt(),
            field = 0xFF2A2139.toInt(),
            fieldPressed = 0xFF342946.toInt(),
            border = 0xFF372C49.toInt(),
            divider = 0xFF2C2339.toInt(),
            text = 0xFFF2EDFA.toInt(),
            text2 = 0xFFAA9DC2.toInt(),
            text3 = 0xFF76698E.toInt(),
            icon = 0xFFEDE6FA.toInt(),
            iconDisabled = 0xFF524669.toInt(),
            accent = 0xFFA78BFF.toInt(),
            shield = 0xFFB59CFF.toInt(),
            ripple = 0x22FFFFFF,
            scrim = 0x66000000,
            ink = 0xFFEDE6FA.toInt(),
            onInk = 0xFF16111F.toInt(),
            shadow = 0x80000000.toInt(),
            isDark = true,
            isPrivate = true,
        )

        fun of(c: Context, private: Boolean) = if (private) PRIVATE else normal(c)
    }
}

/* ---- Drawables ------------------------------------------------------------------------------ */

fun rounded(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

fun roundedTop(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
}

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** A rounded ripple, optionally on top of [content]. */
fun ripple(rippleColor: Int, radius: Float, content: Drawable? = null): RippleDrawable {
    val mask = rounded(Color.WHITE, radius)
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
}

fun View.pressable(p: Palette, radius: Float = Radius.field, content: Drawable? = null) {
    background = ripple(p.ripple, radius, content)
    isClickable = true
    isFocusable = true
}

/* ---- Views ---------------------------------------------------------------------------------- */

fun TextView.style(sizeSp: Float, color: Int, bold: Boolean = false, tracking: Float = 0f): TextView {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = if (bold) Fonts.bold else Fonts.regular
    letterSpacing = tracking
    includeFontPadding = false
    return this
}

fun Context.label(text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false, tracking: Float = 0f) =
    TextView(this).apply {
        this.text = text
        style(sizeSp, color, bold, tracking)
    }

fun Context.icon(res: Int, tint: Int, sizeDp: Int = 20): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
}

fun ImageView.tint(color: Int) {
    imageTintList = ColorStateList.valueOf(color)
}

/** A square touch target holding a centred icon. */
fun Context.iconButton(res: Int, tint: Int, p: Palette, boxDp: Int = 44, iconDp: Int = 20, desc: String? = null) =
    FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(boxDp.dp, boxDp.dp)
        pressable(p, Radius.field)
        sinkOnPress(0.88f)
        contentDescription = desc
        addView(ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            layoutParams = FrameLayout.LayoutParams(iconDp.dp, iconDp.dp, Gravity.CENTER)
        })
    }

val FrameLayout.iconView: ImageView get() = getChildAt(0) as ImageView

fun Context.hairline(color: Int, vertical: Boolean = false): View = View(this).apply {
    setBackgroundColor(color)
    layoutParams = if (vertical) LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
    else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (0.75f).dp.roundToInt()))
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(l, t, r, b)
}

fun FrameLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(l, t, r, b)
}

fun Drawable?.orTransparent(): Drawable = this ?: ColorDrawable(Color.TRANSPARENT)

/* ---- Scroll edges ---------------------------------------------------------------------------- */

/**
 * Soft gradient fades at a scroller's top and bottom edges, in the colour of whatever it sits on.
 * Each appears only when there's more to scroll in that direction, so lists hint at their depth
 * instead of being cut off.
 */
fun addEdgeFades(
    container: FrameLayout, scroller: View, color: Int,
    topInset: Int = 0, bottomInset: Int = 0, size: Int = 32.dp, top: Boolean = true, bottom: Boolean = true,
) {
    fun fade(orientation: GradientDrawable.Orientation) = View(container.context).apply {
        background = GradientDrawable(orientation, intArrayOf(color, withAlpha(color, 0.72f), withAlpha(color, 0f)))
        alpha = 0f
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val topFade = if (top) fade(GradientDrawable.Orientation.TOP_BOTTOM).also {
        container.addView(it, FrameLayout.LayoutParams(MATCH, size, Gravity.TOP).apply { topMargin = topInset })
    } else null
    val bottomFade = if (bottom) fade(GradientDrawable.Orientation.BOTTOM_TOP).also {
        container.addView(it, FrameLayout.LayoutParams(MATCH, size, Gravity.BOTTOM).apply { bottomMargin = bottomInset })
    } else null
    fun show(v: View?, on: Boolean) {
        if (v == null) return
        val target = if (on) 1f else 0f
        if (v.tag == target) return
        v.tag = target
        v.animate().alpha(target).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
    }
    val update = {
        show(topFade, scroller.canScrollVertically(-1))
        show(bottomFade, scroller.canScrollVertically(1))
    }
    scroller.setOnScrollChangeListener { _, _, _, _, _ -> update() }
    scroller.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    if (scroller is androidx.recyclerview.widget.RecyclerView) {
        scroller.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) = update()
        })
    }
    scroller.post { update() }
}

/* ---- Feel ----------------------------------------------------------------------------------- */

/**
 * Dot's haptic vocabulary. Each gesture has its own feel:
 *  tick    – selection changes, toggles, swiping between tabs
 *  tap     – pressing a button
 *  confirm – something was created or saved (bookmark, new tab, screenshot)
 *  heavy   – long-press, picking something up
 *  reject  – something went away (closing a tab)
 *  spin    – a flourish (the ring)
 *  soft    – a whisper (a slow page finished loading)
 * On Android 11+ hardware with rich actuators this uses composition primitives; otherwise it
 * falls back to the platform's feedback constants. Always respects the system haptics setting.
 */
object Haptics {
    private var vibrator: android.os.Vibrator? = null
    private var rich = false
    private var richPlus = false

    fun init(c: Context) {
        if (vibrator != null) return
        vibrator = if (Build.VERSION.SDK_INT >= 31)
            (c.getSystemService(android.os.VibratorManager::class.java))?.defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            rich = v.areAllPrimitivesSupported(
                android.os.VibrationEffect.Composition.PRIMITIVE_CLICK,
                android.os.VibrationEffect.Composition.PRIMITIVE_TICK,
                android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            richPlus = rich && v.areAllPrimitivesSupported(
                android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                android.os.VibrationEffect.Composition.PRIMITIVE_THUD,
                android.os.VibrationEffect.Composition.PRIMITIVE_SPIN,
            )
        }
    }

    private fun enabled(v: View): Boolean = try {
        android.provider.Settings.System.getInt(v.context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    } catch (_: Exception) { true }

    /** Plays a composition if the device can; returns false so the caller can fall back. */
    private fun play(v: View, plus: Boolean = false, build: (android.os.VibrationEffect.Composition) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 30 || !rich || (plus && !richPlus)) return false
        if (!enabled(v)) return true
        val vib = vibrator ?: return false
        return try {
            val comp = android.os.VibrationEffect.startComposition()
            build(comp)
            vib.vibrate(comp.compose())
            true
        } catch (_: Exception) { false }
    }

    private fun fallback(v: View, constant: Int) {
        v.performHapticFeedback(constant)
    }

    fun tick(v: View) {
        if (!play(v) { it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f) })
            fallback(v, HapticFeedbackConstants.CLOCK_TICK)
    }

    fun tap(v: View) {
        if (!play(v) { it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 0.32f) })
            fallback(v, HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun confirm(v: View) {
        if (!play(v) {
                it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.35f)
                it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 20)
            }) fallback(v, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun heavy(v: View) {
        if (!play(v, plus = true) { it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_THUD, 0.6f) })
            fallback(v, HapticFeedbackConstants.LONG_PRESS)
    }

    fun reject(v: View) {
        if (!play(v) {
                it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f)
                it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 0.3f, 70)
            }) fallback(v, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }

    fun spin(v: View) {
        if (!play(v, plus = true) {
                it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_SPIN, 0.7f)
                it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_SPIN, 0.45f, 140)
            }) fallback(v, HapticFeedbackConstants.LONG_PRESS)
    }

    /** Only on hardware that can make it subtle; otherwise silence beats a buzz. */
    fun soft(v: View) {
        play(v, plus = true) { it.addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f) }
    }
}

/** Little press feedback: things gently sink when touched. */
fun View.sinkOnPress(scale: Float = 0.96f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(Motion.FAST).setInterpolator(Motion.ease).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.spring).start()
        }
        false
    }
}

fun blend(from: Int, to: Int, t: Float): Int {
    val inv = 1f - t
    return Color.argb(
        (Color.alpha(from) * inv + Color.alpha(to) * t).roundToInt(),
        (Color.red(from) * inv + Color.red(to) * t).roundToInt(),
        (Color.green(from) * inv + Color.green(to) * t).roundToInt(),
        (Color.blue(from) * inv + Color.blue(to) * t).roundToInt(),
    )
}

fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((alpha * 255).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
