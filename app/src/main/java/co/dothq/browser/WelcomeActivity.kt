package co.dothq.browser

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import co.dothq.browser.ui.AuroraBackdrop
import co.dothq.browser.ui.DotRingView
import co.dothq.browser.ui.Fonts
import co.dothq.browser.ui.Haptics
import co.dothq.browser.ui.MATCH
import co.dothq.browser.ui.Motion
import co.dothq.browser.ui.Palette
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.WRAP
import co.dothq.browser.ui.dp
import co.dothq.browser.ui.flp
import co.dothq.browser.ui.icon
import co.dothq.browser.ui.label
import co.dothq.browser.ui.lp
import co.dothq.browser.ui.margins
import co.dothq.browser.ui.oval
import co.dothq.browser.ui.ripple
import co.dothq.browser.ui.rounded
import co.dothq.browser.ui.sinkOnPress
import co.dothq.browser.ui.withAlpha
import java.util.Calendar

/** First run. Echoes the dothq.org hero: the ring, one confident line, and a way in. */
class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        val p = Palette.normal(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.isDark
            isAppearanceLightNavigationBars = !p.isDark
        }

        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        root.addView(AuroraBackdrop(this).apply { intensity = if (p.isDark) 0.24f else 0.17f }, flp(MATCH, MATCH))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 20.dp, 28.dp, 24.dp)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        root.addView(scroll, flp(MATCH, MATCH))

        val chip = label(getString(R.string.welcome_preview), 12f, p.text2, bold = true).apply {
            background = rounded(withAlpha(p.surface, 0.7f), Radius.pill, p.border, 1)
            setPadding(12.dp, 7.dp, 12.dp, 7.dp)
        }
        column.addView(chip, lp(WRAP, WRAP))
        column.addView(View(this), lp(MATCH, 0, 1f))

        val ring = DotRingView(this)
        column.addView(ring, lp(96.dp, 96.dp).margins(l = (-10).dp, b = 22.dp))

        val wordmark = label(getString(R.string.app_name), 18f, p.text2, bold = true, tracking = -0.01f)
        column.addView(wordmark, lp(WRAP, WRAP).margins(b = 10.dp))
        val title = label(getString(R.string.welcome_title), 40f, p.text, bold = true, tracking = -0.04f).apply {
            setLineSpacing(0f, 0.98f)
        }
        column.addView(title, lp(MATCH, WRAP).margins(b = 14.dp))
        val body = label(getString(R.string.welcome_body), 16f, p.text2).apply { setLineSpacing(4f.dp, 1f) }
        column.addView(body, lp(MATCH, WRAP).margins(b = 26.dp))

        val points = listOf(
            R.drawable.ic_shield_check to R.string.welcome_point_trackers,
            R.drawable.ic_padlock to R.string.welcome_point_https,
            R.drawable.ic_private to R.string.welcome_point_private,
        ).map { (icon, text) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6.dp, 0, 6.dp)
                addView(FrameLayout(context).apply {
                    background = oval(withAlpha(p.shield, 0.12f))
                    addView(context.icon(icon, p.shield, 16).apply { layoutParams = flp(16.dp, 16.dp, Gravity.CENTER) })
                }, lp(34.dp, 34.dp).margins(r = 14.dp))
                addView(context.label(getString(text), 15f, p.text).apply { layoutParams = lp(0, WRAP, 1f) })
            }
        }
        points.forEach { column.addView(it) }
        column.addView(View(this), lp(MATCH, 0, 1f))

        val start = label(getString(R.string.get_started), 16f, p.onInk, bold = true).apply {
            gravity = Gravity.CENTER
            background = ripple(withAlpha(p.onInk, 0.2f), Radius.pill, rounded(p.ink, Radius.pill))
            elevation = 4f.dp
            isClickable = true
            sinkOnPress()
            setOnClickListener { begin(it) }
        }
        column.addView(start, lp(MATCH, 56.dp).margins(t = 28.dp))
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val copyright = label("© $year Dot HQ · Built on Mozilla GeckoView", 12f, p.text3).apply { gravity = Gravity.CENTER }
        column.addView(copyright, lp(MATCH, WRAP).margins(t = 14.dp))

        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(28.dp, 20.dp + bars.top, 28.dp, 24.dp + bars.bottom)
            insets
        }

        // Entrance: the ring arrives, then everything rises into place.
        ring.alpha = 0f
        ring.scaleX = 0.6f; ring.scaleY = 0.6f; ring.rotation = -90f
        ring.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(900).setInterpolator(Motion.spring).start()
        listOf<View>(chip, wordmark, title, body).plus(points).plus(listOf(start, copyright)).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 18f.dp
            v.animate().alpha(1f).translationY(0f).setStartDelay(260L + i * 70L).setDuration(Motion.SLOW + 120).setInterpolator(Motion.ease).start()
        }
    }

    private fun begin(v: View) {
        Haptics.confirm(v)
        (application as DotApp).prefs.setupComplete = true
        val next = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_NEXT, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_NEXT)
        val launch = Intent(this, BrowserActivity::class.java).apply {
            if (next != null) {
                action = next.action
                data = next.data
                next.extras?.let { putExtras(it) }
            }
        }
        startActivity(launch)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        const val EXTRA_NEXT = "co.dothq.browser.NEXT"
    }
}
