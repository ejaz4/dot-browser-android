package co.dothq.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import co.dothq.browser.R

/**
 * Says that Dot is still working while you look at something else: what it's doing right now,
 * a way to stop it, and a tap to go back to the chat. It rises from the bottom, where the chat
 * comes from, and sinks back when you tap it.
 */
@SuppressLint("ViewConstructor")
class AgentPill(
    context: Context,
    p: Palette,
    private val onOpen: () -> Unit,
    private val onStop: () -> Unit,
) : LinearLayout(context) {
    private val ring = DotRingView(context).apply { halo = false }
    private val title = TextView(context)
    private val step = ShimmerText(context)
    private val stop: FrameLayout = context.iconButton(R.drawable.ic_media_stop, p.text2, p, boxDp = 44, iconDp = 15, desc = "Stop")
    private var leaving = false

    /** True once the work is finished and the pill is offering the answer. */
    var done = false
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 60.dp
        setPadding(16.dp, 8.dp, 8.dp, 8.dp)
        background = rounded(p.surface, 30f.dp, withAlpha(p.text, 0.08f), 1)
        elevation = 14f.dp
        isClickable = true
        isFocusable = true

        addView(ring, lp(24.dp, 24.dp).margins(r = 13.dp))
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = lp(0, WRAP, 1f)
        }
        title.style(14f, p.text, bold = true)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        column.addView(title)
        step.style(12.5f, p.text2)
        step.maxLines = 1
        step.ellipsize = TextUtils.TruncateAt.END
        step.setPadding(0, 2.dp, 0, 0)
        column.addView(step)
        addView(column)
        stop.setOnClickListener { Haptics.tap(it); onStop() }
        addView(stop)
        setOnClickListener { Haptics.tap(it); onOpen() }
    }

    fun working(heading: String, stage: String) {
        done = false
        if (title.text != heading) title.text = heading
        if (step.text != stage) step.text = stage
        ring.spinning = true
        step.shimmering = true
        stop.visibility = View.VISIBLE
        contentDescription = "$heading. $stage. Double-tap to open the chat."
    }

    /** The work is done: the ring settles, the stop button goes, and the pill offers the answer. */
    fun finished(heading: String, detail: String) {
        if (done) return
        done = true
        title.text = heading
        step.text = detail
        step.shimmering = false
        ring.spinning = false
        stop.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(Motion.FAST).withEndAction {
            stop.visibility = View.GONE
        }.start()
        // A small nod so the change is noticed out of the corner of an eye.
        animate().scaleX(1.04f).scaleY(1.04f).setDuration(140).setInterpolator(Motion.ease).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Motion.spring).start()
        }.start()
        Haptics.confirm(this)
        contentDescription = "$heading. $detail."
    }

    /** Rises into place from below the screen edge. */
    fun enter() {
        alpha = 0f
        post {
            translationY = (height + 24.dp).toFloat()
            animate().translationY(0f).alpha(1f).setDuration(Motion.SLOW + 80).setInterpolator(Motion.spring).start()
        }
    }

    /** Sinks back down (towards where the chat comes from) and removes itself. */
    fun leave(then: () -> Unit = {}) {
        if (leaving) return
        leaving = true
        animate().cancel()
        animate().translationY((height + 24.dp).toFloat()).alpha(0f).setDuration(Motion.NORMAL + 40).setInterpolator(Motion.easeIn)
            .withEndAction { (parent as? android.view.ViewGroup)?.removeView(this); then() }.start()
    }
}
