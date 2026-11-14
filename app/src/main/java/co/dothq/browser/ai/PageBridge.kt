package co.dothq.browser.ai

import android.os.Handler
import android.os.Looper
import co.dothq.browser.DotApp
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Talks to the built-in Dot Agent content script in each tab: one native-messaging port per
 * page, reconnected on every navigation. Commands are answered asynchronously by id.
 */
class PageBridge(private val app: DotApp) {
    private val main = Handler(Looper.getMainLooper())
    private val ports = HashMap<GeckoSession, WebExtension.Port>()
    private val pending = HashMap<Int, (JSONObject?) -> Unit>()
    private val portWaiters = HashMap<GeckoSession, MutableList<(WebExtension.Port?) -> Unit>>()
    private var nextId = 1

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = message as? JSONObject ?: return
            main.post {
                if (json.has("id")) {
                    pending.remove(json.optInt("id"))?.invoke(json.optJSONObject("result"))
                }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            main.post {
                val it = ports.entries.firstOrNull { e -> e.value === port }
                if (it != null) ports.remove(it.key)
            }
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            val session = port.sender.session ?: return
            main.post {
                ports[session] = port
                port.setDelegate(portDelegate)
                portWaiters.remove(session)?.forEach { it(port) }
            }
        }
    }

    /** Every tab session (and link preview) gets the bridge. */
    fun attach(session: GeckoSession) {
        app.extensions.whenAgentReady { ext ->
            session.webExtensionController.setMessageDelegate(ext, messageDelegate, "dot")
        }
    }

    fun isConnected(session: GeckoSession) = ports.containsKey(session)

    /** Waits (up to [timeoutMs]) for the page's agent script to connect. */
    fun waitForPage(session: GeckoSession, timeoutMs: Long, done: (Boolean) -> Unit) {
        if (ports.containsKey(session)) { done(true); return }
        var finished = false
        val list = portWaiters.getOrPut(session) { mutableListOf() }
        val waiter: (WebExtension.Port?) -> Unit = { p -> if (!finished) { finished = true; done(p != null) } }
        list += waiter
        main.postDelayed({
            if (!finished) {
                finished = true
                portWaiters[session]?.remove(waiter)
                done(false)
            }
        }, timeoutMs)
    }

    /** Sends a command to the page; [done] gets the result, or null if the page didn't answer. */
    fun call(session: GeckoSession, cmd: String, args: JSONObject = JSONObject(), timeoutMs: Long = 6000, done: (JSONObject?) -> Unit) {
        val send = { port: WebExtension.Port ->
            val id = nextId++
            var answered = false
            pending[id] = { r -> if (!answered) { answered = true; done(r) } }
            port.postMessage(JSONObject().put("id", id).put("cmd", cmd).put("args", args))
            main.postDelayed({
                if (!answered) {
                    answered = true
                    pending.remove(id)
                    done(null)
                }
            }, timeoutMs)
        }
        val port = ports[session]
        if (port != null) { send(port); return }
        waitForPage(session, timeoutMs) { ok ->
            val p = ports[session]
            if (ok && p != null) send(p) else done(null)
        }
    }
}
