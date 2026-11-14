package co.dothq.browser

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import co.dothq.browser.ai.ModelCatalog
import co.dothq.browser.ai.ModelManager
import co.dothq.browser.ai.ModelSpec
import co.dothq.browser.ui.AuroraBackdrop
import co.dothq.browser.ui.DotRingView
import co.dothq.browser.ui.DotToast
import co.dothq.browser.ui.Fonts
import co.dothq.browser.ui.Haptics
import co.dothq.browser.ui.MATCH
import co.dothq.browser.ui.MiniSwitch
import co.dothq.browser.ui.Palette
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.WRAP
import co.dothq.browser.ui.addEdgeFades
import co.dothq.browser.ui.dp
import co.dothq.browser.ui.flp
import co.dothq.browser.ui.icon
import co.dothq.browser.ui.iconButton
import co.dothq.browser.ui.label
import co.dothq.browser.ui.lp
import co.dothq.browser.ui.margins
import co.dothq.browser.ui.oval
import co.dothq.browser.ui.ripple
import co.dothq.browser.ui.ringColors
import co.dothq.browser.ui.rounded
import co.dothq.browser.ui.sinkOnPress
import co.dothq.browser.ui.withAlpha
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Dot Assistant: switch it on, and pick the model that suits your phone. */
class AssistantActivity : AppCompatActivity() {
    private val app get() = application as DotApp
    private val models get() = app.assistant.models
    private lateinit var p: Palette
    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var toast: DotToast
    private val cards = HashMap<String, View>()
    private val listener: () -> Unit = { refreshCards() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        p = Palette.normal(this)
        window.statusBarColor = p.frame
        window.navigationBarColor = p.frame
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.isDark
            isAppearanceLightNavigationBars = !p.isDark
        }
        root = FrameLayout(this).apply { setBackgroundColor(p.frame) }
        root.addView(AuroraBackdrop(this).apply { intensity = if (p.isDark) 0.18f else 0.12f }, flp(MATCH, MATCH))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, flp(MATCH, MATCH))
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8.dp, 8.dp, 16.dp, 0)
            addView(iconButton(R.drawable.ic_arrow_left, p.icon, p, desc = getString(R.string.back)).apply { setOnClickListener { finish() } })
        }, lp(MATCH, WRAP))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 0, 16.dp, 40.dp)
        }
        scroll.addView(list)
        val frame = FrameLayout(this)
        frame.addView(scroll, flp(MATCH, MATCH))
        addEdgeFades(frame, scroll, p.frame)
        column.addView(frame, lp(MATCH, 0, 1f))
        setContentView(root)
        toast = DotToast(root)
        render()
    }

    override fun onStart() { super.onStart(); models.addListener(listener) }
    override fun onStop() { super.onStop(); models.removeListener(listener) }

    private fun render() {
        list.removeAllViews()
        cards.clear()
        // Hero
        list.addView(DotRingView(this), lp(68.dp, 68.dp).margins(l = (-6).dp, b = 14.dp))
        list.addView(label("Dot Assistant", 32f, p.text, bold = true, tracking = -0.035f))
        list.addView(label("Summaries, answers and a helper that can use pages for you. It runs entirely on your phone: nothing you ask, and nothing it reads, ever leaves it.", 15f, p.text2).apply {
            setPadding(0, 8.dp, 0, 20.dp)
            setLineSpacing(3f.dp, 1f)
        })

        if (!app.assistant.isAvailable) {
            list.addView(notice("Your phone can't run the on-device assistant", "Dot Assistant needs a 64-bit phone."))
            return
        }

        // Master switch + gestures
        list.addView(card {
            addView(toggleRow("Turn on Dot Assistant", "Download a model below to start.", models.enabled) { on ->
                models.enabled = on
                if (on && models.active == null) {
                    val rec = ModelCatalog.recommended(models.totalRamGb)
                    toast.show(p, "Recommended for your phone: ${rec.name}", bottomOffset = 16.dp)
                }
            })
            addView(divider())
            addView(toggleRow("Shake to summarise", "Shake your phone on any page for a quick summary.", app.prefs.shakeToSummarise) { app.prefs.shakeToSummarise = it })
            addView(divider())
            addView(toggleRow("Screen vision", "Models that can see let Dot look at the page itself. Adds a small extra download.", models.visionWanted) { models.visionWanted = it; render() })
        })

        val rec = ModelCatalog.recommended(models.totalRamGb)
        list.addView(label("MODELS", 12f, p.text3, bold = true, tracking = 0.07f).apply { setPadding(10.dp, 26.dp, 10.dp, 4.dp) })
        list.addView(label("Bigger models are smarter and more reliable; smaller ones are faster and lighter. This phone has ${"%.0f".format(models.totalRamGb)} GB of memory.", 13.5f, p.text2).apply {
            setPadding(10.dp, 0, 10.dp, 10.dp)
            setLineSpacing(2f.dp, 1f)
        })
        ModelCatalog.ALL.forEach { m ->
            val v = modelCard(m, m === rec)
            cards[m.id] = v
            list.addView(v, lp(MATCH, WRAP).margins(b = 10.dp))
        }
        list.addView(label("Models are downloaded from Hugging Face and checked before use. They're yours to keep or delete.", 12.5f, p.text3).apply {
            gravity = Gravity.CENTER
            setPadding(20.dp, 14.dp, 20.dp, 0)
        })
    }

    private fun refreshCards() {
        ModelCatalog.ALL.forEach { m -> cards[m.id]?.let { updateCard(m, it) } }
    }

    private fun modelCard(m: ModelSpec, recommended: Boolean): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 16.dp, 18.dp, 16.dp)
            tag = m.id
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(0, WRAP, 1f)
            val nameRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            nameRow.addView(label(m.name, 17f, p.text, bold = true, tracking = -0.01f))
            if (recommended) nameRow.addView(badge("Recommended", p.accent), lp(WRAP, WRAP).margins(l = 8.dp))
            addView(nameRow)
            addView(label("${m.maker} · ${m.params} · ${ModelCatalog.formatBytes(m.file.bytes + if (models.visionWanted) (m.vision?.bytes ?: 0) else 0)}", 12.5f, p.text3).apply { setPadding(0, 3.dp, 0, 0) })
        })
        c.addView(top)
        c.addView(label(m.tagline, 14f, p.text2).apply { setPadding(0, 8.dp, 0, 10.dp); setLineSpacing(2f.dp, 1f) })
        val meters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        meters.addView(meter("Speed", m.speed))
        meters.addView(meter("Smarts", m.smarts), lp(WRAP, WRAP).margins(l = 18.dp))
        if (m.vision != null) meters.addView(badge("Sees your screen", p.shield), lp(WRAP, WRAP).margins(l = 14.dp))
        c.addView(meters)
        if (models.totalRamGb + 0.5 < m.minRamGb) {
            c.addView(label("Needs about ${m.minRamGb} GB of memory; it may be slow or close on this phone.", 12.5f, getColor(R.color.dot_danger)).apply { setPadding(0, 10.dp, 0, 0) })
        }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14.dp, 0, 0)
            tag = "status"
        }
        c.addView(status)
        updateCard(m, c)
        return c
    }

    private fun updateCard(m: ModelSpec, c: View) {
        val box = c as LinearLayout
        val active = models.activeId == m.id && models.isReady(m)
        box.background = if (active) rounded(p.surface, 16f.dp, p.accent, 2.dp)
        else rounded(p.surface, 16f.dp, p.border, if (p.isDark) 0 else 1)
        val status = box.findViewWithTag<LinearLayout>("status")
        status.removeAllViews()
        val st = models.status(m)
        when (st.state) {
            ModelManager.State.READY -> {
                if (active) {
                    status.addView(icon(R.drawable.ic_check, p.accent, 16).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 6.dp })
                    status.addView(label("In use", 14f, p.accent, bold = true).apply { layoutParams = lp(0, WRAP, 1f) })
                } else {
                    status.addView(View(this), lp(0, 1, 1f))
                    status.addView(button("Use", primary = true) { models.activeId = m.id; models.enabled = true; app.assistant.llama.unload(); Haptics.confirm(c) })
                }
                status.addView(button("Delete", primary = false) { confirmDelete(m) }, lp(WRAP, 38.dp).margins(l = 8.dp))
            }
            ModelManager.State.DOWNLOADING -> {
                val bar = progressBar(st.progress)
                status.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = lp(0, WRAP, 1f)
                    addView(bar, lp(MATCH, 6.dp))
                    addView(label("${(st.progress * 100).toInt()}%  ·  ${ModelCatalog.formatBytes(st.downloaded)} of ${ModelCatalog.formatBytes(st.total)}", 12.5f, p.text2).apply { setPadding(0, 6.dp, 0, 0) })
                })
                status.addView(button("Cancel", primary = false) { models.cancel(m) }, lp(WRAP, 38.dp).margins(l = 12.dp))
            }
            ModelManager.State.VERIFYING -> {
                status.addView(label("Checking the download…", 14f, p.text2))
            }
            ModelManager.State.FAILED, ModelManager.State.NOT_DOWNLOADED -> {
                if (st.error != null) status.addView(label(st.error, 13f, getColor(R.color.dot_danger)).apply { layoutParams = lp(0, WRAP, 1f) })
                else status.addView(View(this), lp(0, 1, 1f))
                status.addView(button("Download", primary = true) {
                    val err = models.download(m)
                    if (err != null) toast.show(p, err, bottomOffset = 16.dp) else { models.enabled = true; Haptics.confirm(c) }
                })
            }
        }
    }

    private fun confirmDelete(m: ModelSpec) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${m.name}?")
            .setMessage("This frees ${ModelCatalog.formatBytes(models.usedBytes(m))}. You can download it again any time.")
            .setPositiveButton(R.string.delete) { _, _ ->
                if (models.activeId == m.id) app.assistant.llama.unload()
                models.delete(m)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /* ---- Pieces ------------------------------------------------------------------------------- */

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.surface, 16f.dp, p.border, if (p.isDark) 0 else 1)
        clipToOutline = true
        build()
    }

    private fun divider() = View(this).apply { setBackgroundColor(p.divider); layoutParams = lp(MATCH, 1).margins(l = 18.dp) }

    private fun toggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(18.dp, 14.dp, 16.dp, 14.dp)
        background = ripple(p.ripple, 0f)
        isClickable = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(0, WRAP, 1f)
            addView(label(title, 16f, p.text))
            addView(label(subtitle, 13f, p.text2).apply { setPadding(0, 3.dp, 12.dp, 0); setLineSpacing(2f.dp, 1f) })
        })
        val sw = MiniSwitch(context, p).apply { setChecked(value, false) }
        addView(sw, lp(42.dp, 25.dp))
        setOnClickListener { Haptics.tick(it); sw.setChecked(!sw.checked, true); onChange(sw.checked) }
    }

    private fun meter(label: String, value: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(label, 12f, p.text3, bold = true).apply { setPadding(0, 0, 7.dp, 0) })
        val ring = ringColors(context)
        for (i in 1..5) {
            addView(View(context).apply {
                background = oval(if (i <= value) ring[(i + 1) % (ring.size - 1)] else withAlpha(p.text, 0.1f))
            }, lp(7.dp, 7.dp).margins(r = 3.dp))
        }
    }

    private fun badge(text: String, color: Int) = label(text, 11f, color, bold = true).apply {
        background = rounded(withAlpha(color, 0.12f), Radius.pill)
        setPadding(8.dp, 3.dp, 8.dp, 3.dp)
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit): TextView = label(text, 14f, if (primary) p.onInk else p.text, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(18.dp, 0, 18.dp, 0)
        background = ripple(withAlpha(if (primary) p.onInk else p.text, 0.15f), Radius.pill, rounded(if (primary) p.ink else p.field, Radius.pill))
        isClickable = true
        minHeight = 38.dp
        sinkOnPress()
        setOnClickListener { Haptics.tap(it); onClick() }
    }

    private fun progressBar(progress: Float) = object : View(this) {
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(p.text, 0.08f) }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            val ring = ringColors(context)
            fill.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(ring[2], ring[3], ring[4]), null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(0f, 0f, (width * progress.coerceIn(0.02f, 1f)), height.toFloat(), r, r, fill)
        }
    }

    private fun notice(title: String, body: String) = card {
        setPadding(18.dp, 16.dp, 18.dp, 16.dp)
        addView(label(title, 16f, p.text, bold = true))
        addView(label(body, 14f, p.text2).apply { setPadding(0, 4.dp, 0, 0) })
    }
}
