package co.dothq.browser.ai

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Called from native code while a reply streams in. */
interface TokenCallback {
    /** UTF-8 bytes of the next piece; return false to stop. */
    fun onToken(bytes: ByteArray): Boolean
    fun onPromptProgress(progress: Float)
}

/** Called from native code during a chat turn with native tool calling. */
interface ChatCallback {
    /** UTF-8 bytes of the reply text so far, tool-call syntax removed; return false to stop. */
    fun onContent(bytes: ByteArray): Boolean
    fun onPromptProgress(progress: Float)
    /** The finished turn as JSON: {"format", "message": {content, tool_calls}, "raw"}. */
    fun onMessage(bytes: ByteArray)
}

data class ChatMessage(val role: String, val content: String)

data class ToolCall(val id: String, val name: String, val arguments: JSONObject)

data class ChatReply(val content: String, val toolCalls: List<ToolCall>, val raw: String)

/**
 * The on-device model. Everything runs on one dedicated thread; results come back on the main
 * thread. Loading is lazy and the model is unloaded after a while of not being used.
 */
class Llama {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dot-llm").apply { priority = Thread.MAX_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val handle = AtomicLong(0)
    private var loaded: String? = null
    private var visionLoaded = false
    var contextSize = 0
        private set

    val isLoaded get() = handle.get() != 0L
    val hasVision get() = visionLoaded

    /**
     * The model's own tool-call format (from its chat template), or "" when its template can't
     * describe tools. With a format, [chatTools] gets real tool calls back from the model.
     */
    @Volatile var toolFormat = ""
        private set
    val nativeTools get() = toolFormat.isNotEmpty()

    /** The marker to put in a message where a screenshot should go. */
    val mediaMarker: String by lazy { if (available) runCatching { nativeMediaMarker() }.getOrDefault("<__media__>") else "" }

    fun load(model: String, vision: String?, contextTokens: Int, done: (Boolean, String?) -> Unit) {
        worker.execute {
            if (!available) { main.post { done(false, "The assistant isn't available on this device") }; return@execute }
            val key = "$model|$vision|$contextTokens"
            if (loaded == key && handle.get() != 0L) { main.post { done(true, null) }; return@execute }
            freeNow()
            val cpus = Runtime.getRuntime().availableProcessors()
            val threads = (cpus - 2).coerceIn(2, 6)
            val h = nativeLoad(model, vision, contextTokens, threads)
            if (h == 0L) { main.post { done(false, "Couldn't load the model") }; return@execute }
            handle.set(h)
            loaded = key
            visionLoaded = nativeHasVision(h)
            contextSize = nativeContextSize(h)
            toolFormat = runCatching { nativeToolFormat(h) }.getOrDefault("")
            Log.i(TAG, "Tool calling: ${toolFormat.ifEmpty { "prompted" }}")
            main.post { done(true, null) }
        }
    }

    /** Renders [messages] with the model's own chat template. Call from the worker only. */
    private fun format(h: Long, messages: List<ChatMessage>): String =
        nativeFormat(h, messages.map { it.role }.toTypedArray(), messages.map { it.content }.toTypedArray())

    fun countTokensBlocking(text: String): Int {
        val h = handle.get()
        return if (h == 0L) text.length / 4 else nativeCountTokens(h, text)
    }

    /**
     * Streams a reply. [onText] gets the text so far (on the main thread); [onDone] the full
     * text, or an error. [image] is placed where [mediaMarker] appears in the last message.
     */
    fun chat(
        messages: List<ChatMessage>,
        maxTokens: Int = 512,
        temperature: Float = 0.6f,
        image: Bitmap? = null,
        stopWhen: ((String) -> Boolean)? = null,
        onProgress: ((Float) -> Unit)? = null,
        onText: ((String) -> Unit)? = null,
        onDone: (String, String?) -> Unit,
    ) {
        worker.execute {
            val h = handle.get()
            if (h == 0L) { main.post { onDone("", "The model isn't loaded") }; return@execute }
            val prompt = format(h, messages)
            val sb = StringBuilder()
            val decoder = Utf8Accumulator()
            var lastPost = 0L
            val cb = object : TokenCallback {
                override fun onToken(bytes: ByteArray): Boolean {
                    sb.append(decoder.add(bytes))
                    val now = System.currentTimeMillis()
                    if (onText != null && now - lastPost > 40) {
                        lastPost = now
                        val snapshot = sb.toString()
                        main.post { onText(snapshot) }
                    }
                    return stopWhen?.invoke(sb.toString()) != true
                }

                override fun onPromptProgress(progress: Float) {
                    if (onProgress != null) main.post { onProgress(progress) }
                }
            }
            var pixels: IntArray? = null
            var w = 0
            var hgt = 0
            if (image != null && visionLoaded) {
                w = image.width; hgt = image.height
                pixels = IntArray(w * hgt).also { image.getPixels(it, 0, w, 0, 0, w, hgt) }
            }
            val n = try {
                nativeGenerate(h, prompt, pixels, w, hgt, maxTokens, temperature, cb)
            } catch (t: Throwable) {
                Log.e(TAG, "generate failed", t); -9
            }
            val text = sb.toString()
            val error = when (n) {
                -2 -> "That's too long for the model to read at once"
                -1, -3, -4, -9 -> "The model ran into a problem"
                else -> null
            }
            main.post { onText?.invoke(text); onDone(text, error) }
        }
    }

    /**
     * One chat turn with the model's native tool calling. [messages] and [tools] are
     * OpenAI-style. [toolChoice]: 0 lets the model decide, 1 makes it call a tool, 2 forbids
     * tools. [onText] gets the reply so far; [onDone] the parsed reply, or an error.
     */
    fun chatTools(
        messages: JSONArray,
        tools: JSONArray,
        toolChoice: Int = 0,
        maxTokens: Int = 512,
        temperature: Float = 0.3f,
        image: Bitmap? = null,
        onProgress: ((Float) -> Unit)? = null,
        onText: ((String) -> Unit)? = null,
        onDone: (ChatReply?, String?) -> Unit,
    ) {
        worker.execute {
            val h = handle.get()
            if (h == 0L) { main.post { onDone(null, "The model isn't loaded") }; return@execute }
            var lastPost = 0L
            var latest = ""
            var finished: JSONObject? = null
            val cb = object : ChatCallback {
                override fun onContent(bytes: ByteArray): Boolean {
                    latest = String(bytes, Charsets.UTF_8)
                    val now = System.currentTimeMillis()
                    if (onText != null && now - lastPost > 40) {
                        lastPost = now
                        val snapshot = latest
                        main.post { onText(snapshot) }
                    }
                    return true
                }

                override fun onPromptProgress(progress: Float) {
                    if (onProgress != null) main.post { onProgress(progress) }
                }

                override fun onMessage(bytes: ByteArray) {
                    finished = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                }
            }
            var pixels: IntArray? = null
            var w = 0
            var hgt = 0
            if (image != null && visionLoaded) {
                w = image.width; hgt = image.height
                pixels = IntArray(w * hgt).also { image.getPixels(it, 0, w, 0, 0, w, hgt) }
            }
            val n = try {
                nativeChat(
                    h, messages.toString().toByteArray(Charsets.UTF_8), tools.toString().toByteArray(Charsets.UTF_8),
                    toolChoice, pixels, w, hgt, maxTokens, temperature, cb,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "chat failed", t); -9
            }
            if (n == -5) toolFormat = ""   // the template turned out not to handle tools
            val error = when (n) {
                -2 -> "That's too long for the model to read at once"
                -5 -> "This model can't use tools"
                -1, -3, -4, -9 -> "The model ran into a problem"
                else -> null
            }
            val reply = finished?.let { parseReply(it) } ?: ChatReply(latest, emptyList(), latest)
            main.post { onText?.invoke(reply.content); onDone(if (error == null) reply else null, error) }
        }
    }

    private fun parseReply(json: JSONObject): ChatReply {
        val msg = json.optJSONObject("message") ?: JSONObject()
        val content = if (msg.isNull("content")) "" else msg.optString("content")
        val calls = mutableListOf<ToolCall>()
        val arr = msg.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val fn = c.optJSONObject("function") ?: continue
            val args = when (val a = fn.opt("arguments")) {
                is JSONObject -> a
                is String -> runCatching { JSONObject(a.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                else -> JSONObject()
            }
            calls += ToolCall(c.optString("id").ifBlank { "call_$i" }, fn.optString("name"), args)
        }
        return ChatReply(content, calls, json.optString("raw"))
    }

    fun stop() {
        val h = handle.get()
        if (h != 0L) nativeStop(h)
    }

    fun unload() {
        stop()
        worker.execute { freeNow() }
    }

    private fun freeNow() {
        val h = handle.getAndSet(0)
        if (h != 0L) nativeFree(h)
        loaded = null
        visionLoaded = false
        toolFormat = ""
    }

    /** Joins UTF-8 byte pieces into text without breaking multi-byte characters. */
    private class Utf8Accumulator {
        fun add(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "DotLlama"

        /** False on devices without the native library (e.g. 32-bit phones). */
        val available: Boolean = try {
            System.loadLibrary("dotllm")
            nativeInit()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "On-device assistant unavailable: ${t.message}")
            false
        }

        @JvmStatic private external fun nativeInit()
        @JvmStatic external fun nativeSystemInfo(): String
        @JvmStatic private external fun nativeMediaMarker(): String
        @JvmStatic private external fun nativeLoad(model: String, mmproj: String?, nCtx: Int, threads: Int): Long
        @JvmStatic private external fun nativeHasVision(handle: Long): Boolean
        @JvmStatic private external fun nativeContextSize(handle: Long): Int
        @JvmStatic private external fun nativeCountTokens(handle: Long, text: String): Int
        @JvmStatic private external fun nativeFormat(handle: Long, roles: Array<String>, contents: Array<String>): String
        @JvmStatic private external fun nativeToolFormat(handle: Long): String
        @JvmStatic private external fun nativeChat(
            handle: Long, messages: ByteArray, tools: ByteArray, toolChoice: Int, pixels: IntArray?,
            width: Int, height: Int, maxTokens: Int, temperature: Float, callback: ChatCallback,
        ): Int
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeGenerate(
            handle: Long, prompt: String, pixels: IntArray?, width: Int, height: Int,
            maxTokens: Int, temperature: Float, callback: TokenCallback,
        ): Int
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
