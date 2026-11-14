package co.dothq.browser.ai

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import co.dothq.browser.DotApp
import co.dothq.browser.tabs.Tab
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dot's on-device assistant. Owns the model (loaded on first use, unloaded when idle), page
 * summaries, and the chat sessions behind Chat tabs.
 */
class Assistant(private val app: DotApp) {
    val llama = Llama()
    val models = ModelManager(app)
    private val main = Handler(Looper.getMainLooper())
    private val sessions = HashMap<Long, ChatSession>()
    private var lastUse = 0L

    val isAvailable get() = Llama.available
    val isReady get() = isAvailable && models.enabled && models.active != null

    /** Loads the active model if needed. */
    fun ensureLoaded(done: (Boolean, String?) -> Unit) {
        val m = models.active ?: return done(false, "Choose a model in Assistant settings")
        touch()
        llama.load(models.fileFor(m.file).path, models.visionPath(m), m.contextTokens, done)
    }

    private val idleUnload = Runnable {
        if (System.currentTimeMillis() - lastUse >= IDLE_MS && sessions.values.none { it.running }) llama.unload()
    }

    private fun touch() {
        lastUse = System.currentTimeMillis()
        main.removeCallbacks(idleUnload)
        main.postDelayed(idleUnload, IDLE_MS)
    }

    fun session(tab: Tab): ChatSession = sessions.getOrPut(tab.id) { ChatSession(this, app, tab) }

    /** The chat that's working right now, if any (one model, so at most one at a time). */
    val busySession: ChatSession? get() = sessions.values.firstOrNull { it.running }

    private val activityListeners = mutableListOf<() -> Unit>()

    /** Called (on the main thread) whenever any chat starts, progresses or stops. */
    fun addActivityListener(l: () -> Unit) { activityListeners += l }
    fun removeActivityListener(l: () -> Unit) { activityListeners -= l }
    internal fun notifyActivity() = activityListeners.toList().forEach { it() }
    fun sessionOrNull(tab: Tab): ChatSession? = sessions[tab.id]
    fun dropSession(tab: Tab) { sessions.remove(tab.id)?.stop() }

    /** Characters of page text that comfortably fit alongside instructions and the reply. */
    fun pageBudgetChars(replyTokens: Int): Int {
        val ctx = (llama.contextSize.takeIf { it > 0 } ?: models.active?.contextTokens ?: 4096)
        return ((ctx - replyTokens - 700).coerceAtLeast(800) * 3.2).toInt()
    }

    /**
     * Summarises what's on [tab]: a one-line gist, then a few points. Streams into [onText].
     */
    fun summarize(tab: Tab, onStage: (String) -> Unit, onText: (String) -> Unit, onDone: (String, String?) -> Unit) {
        val session = tab.session ?: return onDone("", "There's no page to summarise")
        onStage("Reading the page")
        ensureLoaded { ok, err ->
            if (!ok) return@ensureLoaded onDone("", err)
            val budget = pageBudgetChars(SUMMARY_TOKENS)
            app.bridge.call(session, "readable", JSONObject().put("limit", budget), 8000) { page ->
                val text = page?.optString("text").orEmpty().trim()
                if (text.length < 40) return@call onDone("", "There isn't enough text on this page to summarise")
                val title = page?.optString("title").orEmpty()
                onStage("Summarising")
                touch()
                llama.chat(
                    listOf(
                        ChatMessage("system", SUMMARY_SYSTEM),
                        ChatMessage("user", "Page: $title\nAddress: ${tab.url}\n\n$text\n\n---\nSummarise this page."),
                    ),
                    maxTokens = SUMMARY_TOKENS,
                    temperature = 0.3f,
                    onText = onText,
                ) { out, error -> touch(); onDone(cleanReply(out), error) }
            }
        }
    }

    fun stopAll() {
        llama.stop()
        sessions.values.forEach { it.stop() }
    }

    companion object {
        private const val IDLE_MS = 5 * 60 * 1000L
        private const val SUMMARY_TOKENS = 380

        private val SUMMARY_SYSTEM = """
            You are Dot, a private assistant built into Dot Browser, running entirely on the user's phone.
            Summarise web pages faithfully and briefly. Only use facts from the page.
            Format:
            First line: one sentence capturing the gist.
            Then 3 to 5 short bullet points starting with "- ", each one key fact or takeaway.
            No headings, no preamble, no closing remarks.
        """.trimIndent()

        /** Strips reasoning blocks some models emit, and stray template tokens. */
        fun cleanReply(text: String): String = text
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)^.*?</think>"), "")
            .replace(Regex("<\\|[a-z_]+\\|>"), "")
            .replace("<end_of_turn>", "")
            .trim()

        fun today(): String = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())

        /** Downscales a screenshot for the vision encoder. */
        fun forVision(bmp: Bitmap, maxSide: Int = 896): Bitmap {
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale >= 1f) return bmp
            return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        }
    }
}
