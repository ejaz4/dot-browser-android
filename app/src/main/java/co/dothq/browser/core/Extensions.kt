package co.dothq.browser.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import co.dothq.browser.DotApp
import co.dothq.browser.tabs.Tab
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Firefox WebExtensions, through GeckoView: installing (with a permission prompt), enabling,
 * removing, toolbar actions and their popups, and extensions opening or closing tabs.
 * Application-scoped, like the runtime; the window attaches as [ui].
 */
class ExtensionManager(private val app: DotApp) {

    interface Ui {
        /** Ask before installing. [done] gets (install?, allowInPrivateTabs?). */
        fun onInstallPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, done: (Boolean, Boolean) -> Unit)
        /** An extension asks for more permissions at runtime. */
        fun onPermissionPrompt(ext: WebExtension, permissions: List<String>, origins: List<String>, done: (Boolean) -> Unit)
        fun onExtensionsChanged()
        fun onPopup(ext: WebExtension, session: GeckoSession)
        fun openTab(url: String)
    }

    var ui: Ui? = null
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    /** User-installed extensions (built-ins such as the page agent are hidden). */
    val installed = mutableListOf<WebExtension>()

    /** The built-in extension that lets Dot's assistant read and act on pages. */
    var agent: WebExtension? = null
        private set
    private val agentListeners = mutableListOf<(WebExtension) -> Unit>()

    private val defaultActions = HashMap<String, WebExtension.Action>()
    private val tabActions = HashMap<Pair<String, GeckoSession>, WebExtension.Action>()
    private val icons = HashMap<String, Bitmap>()
    private val actionIcons = HashMap<String, Bitmap>()

    private val controller get() = app.runtime.webExtensionController

    fun start() {
        if (started) return
        started = true
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollection: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                if (ui == null) return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
                var shown = false
                val show = {
                    if (!shown) {
                        shown = true
                        val window = ui
                        if (window == null) result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                        else window.onInstallPrompt(extension, permissions.toList(), origins.toList()) { grant, private ->
                            result.complete(WebExtension.PermissionPromptResponse(grant, private, false))
                        }
                    }
                }
                // Show the extension's own icon in the prompt (briefly wait for it).
                extension.metaData.icon.getBitmap(ICON_PX).accept({ bmp ->
                    main.post { if (bmp != null) icons[extension.id] = bmp; show() }
                }, { main.post(show) })
                main.postDelayed(show, 600)
                return result
            }

            override fun onUpdatePrompt(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollection: Array<String>,
            ): GeckoResult<AllowOrDeny>? = ask(extension, permissions, origins)

            override fun onOptionalPrompt(
                extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollection: Array<String>,
            ): GeckoResult<AllowOrDeny>? = ask(extension, permissions, origins)
        }
        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) = refresh()
            override fun onUninstalled(extension: WebExtension) = refresh()
            override fun onEnabled(extension: WebExtension) = refresh()
            override fun onDisabled(extension: WebExtension) = refresh()
            override fun onReady(extension: WebExtension) = refresh()
        })
        controller.ensureBuiltIn(AGENT_URL, AGENT_ID).accept({ ext ->
            // The assistant works in private tabs too (it never stores anything).
            if (ext != null && !ext.metaData.allowedInPrivateBrowsing) controller.setAllowedInPrivateBrowsing(ext, true)
            if (ext != null) main.post {
                agent = ext
                agentListeners.toList().forEach { it(ext) }
            }
        }, { e -> Log.w(TAG, "Could not load the page agent", e) })
        refresh()
    }

    private fun ask(ext: WebExtension, permissions: Array<String>, origins: Array<String>): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        val window = ui ?: return GeckoResult.deny()
        window.onPermissionPrompt(ext, permissions.toList(), origins.toList()) { ok ->
            result.complete(if (ok) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
        }
        return result
    }

    fun whenAgentReady(block: (WebExtension) -> Unit) {
        agent?.let { block(it); return }
        agentListeners += block
    }

    fun refresh() {
        controller.list().accept({ list ->
            main.post {
                installed.clear()
                list?.filter { !it.isBuiltIn && it.id != AGENT_ID }?.sortedBy { it.metaData.name?.lowercase() }
                    ?.let { installed.addAll(it) }
                installed.forEach { register(it) }
                ui?.onExtensionsChanged()
            }
        }, { e -> Log.w(TAG, "Could not list extensions", e) })
    }

    private fun actionDelegate() = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) =
            remember(extension, session, action)

        override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) =
            remember(extension, session, action)

        override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession> =
            openPopup(extension)

        override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession> =
            openPopup(extension)
    }

    private fun register(ext: WebExtension) {
        ext.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) =
                remember(extension, session, action)

            override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) =
                remember(extension, session, action)

            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession> =
                openPopup(extension)

            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession> =
                openPopup(extension)
        })
        ext.tabDelegate = object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, details: WebExtension.CreateTabDetails): GeckoResult<GeckoSession> {
                val tab = app.tabs.newChildTab(parent = app.tabs.selected, url = details.url ?: "", select = details.active != false)
                return GeckoResult.fromValue(tab.session)
            }

            override fun onOpenOptionsPage(source: WebExtension) {
                source.metaData.optionsPageUrl?.let { ui?.openTab(it) }
            }
        }
        // Existing tabs learn about it too.
        app.tabs.tabs.forEach { t -> t.session?.let { attachTabDelegate(ext, t, it) } }
        if (!icons.containsKey(ext.id)) {
            ext.metaData.icon.getBitmap(ICON_PX).accept({ bmp -> if (bmp != null) main.post { icons[ext.id] = bmp; ui?.onExtensionsChanged() } }, { })
        }
    }

    private fun remember(ext: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
        main.post {
            if (session == null) defaultActions[ext.id] = action
            else {
                tabActions[ext.id to session] = action
                // After a restart Gecko may only send per-tab state; it still means there's a button.
                defaultActions.putIfAbsent(ext.id, action)
            }
            action.icon?.getBitmap(ICON_PX)?.accept({ bmp -> if (bmp != null) main.post { actionIcons[ext.id] = bmp; ui?.onExtensionsChanged() } }, { })
            ui?.onExtensionsChanged()
        }
    }

    private fun openPopup(ext: WebExtension): GeckoResult<GeckoSession> {
        val session = GeckoSession()
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                main.post { ui?.openTab(uri) }
                return null
            }
        }
        session.open(app.runtime)
        main.post { ui?.onPopup(ext, session) ?: session.close() }
        return GeckoResult.fromValue(session)
    }

    /** Called for every tab session so extensions can close or update tabs. */
    fun attachSession(tab: Tab, session: GeckoSession) {
        installed.forEach { attachTabDelegate(it, tab, session) }
    }

    private fun attachTabDelegate(ext: WebExtension, tab: Tab, session: GeckoSession) {
        // Per-tab action state (badges, icons) arrives on the session.
        session.webExtensionController.setActionDelegate(ext, actionDelegate())
        session.webExtensionController.setTabDelegate(ext, object : WebExtension.SessionTabDelegate {
            override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
                main.post { app.tabs.close(tab) }
                return GeckoResult.allow()
            }

            override fun onUpdateTab(source: WebExtension, session: GeckoSession, details: WebExtension.UpdateTabDetails): GeckoResult<AllowOrDeny> {
                main.post {
                    if (details.active == true) app.tabs.select(tab)
                    details.url?.let { app.tabs.load(tab, it) }
                }
                return GeckoResult.allow()
            }
        })
    }

    /* ---- Queries for the UI ------------------------------------------------------------------ */

    data class ActionItem(val ext: WebExtension, val title: String, val icon: Bitmap?, val badge: String?, val enabled: Boolean, val click: () -> Unit)

    /** Toolbar actions for the given tab, newest state first. */
    fun actionsFor(session: GeckoSession?): List<ActionItem> = installed.filter { it.metaData.enabled }.mapNotNull { ext ->
        val base = defaultActions[ext.id] ?: return@mapNotNull null
        val action = session?.let { tabActions[ext.id to it]?.withDefault(base) } ?: base
        ActionItem(
            ext, action.title?.ifBlank { null } ?: ext.metaData.name ?: ext.id,
            actionIcons[ext.id] ?: icons[ext.id], action.badgeText?.ifBlank { null }, action.enabled != false,
        ) { action.click() }
    }

    fun iconOf(ext: WebExtension): Bitmap? = icons[ext.id]

    fun install(url: String, done: (WebExtension?, String?) -> Unit) {
        controller.install(url).accept({ ext ->
            main.post { refresh(); done(ext, null) }
        }, { e ->
            val code = (e as? WebExtension.InstallException)?.code
            val message = when (code) {
                WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED -> null
                WebExtension.InstallException.ErrorCodes.ERROR_NETWORK_FAILURE -> "Couldn't download the extension"
                WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE -> "This extension file is damaged"
                WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED -> "This extension isn't signed by Mozilla, so it can't be installed"
                WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED, WebExtension.InstallException.ErrorCodes.ERROR_SOFT_BLOCKED ->
                    "This extension has been blocked for security reasons"
                WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE -> "This extension doesn't work on this version of Dot"
                else -> "Couldn't install the extension"
            }
            main.post { done(null, message) }
        })
    }

    fun setEnabled(ext: WebExtension, enabled: Boolean, done: () -> Unit = {}) {
        val r = if (enabled) controller.enable(ext, WebExtensionController.EnableSource.USER)
        else controller.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept({ main.post { refresh(); done() } }, { main.post { refresh(); done() } })
    }

    fun setPrivate(ext: WebExtension, allowed: Boolean, done: () -> Unit = {}) {
        controller.setAllowedInPrivateBrowsing(ext, allowed).accept({ main.post { refresh(); done() } }, { main.post { done() } })
    }

    fun uninstall(ext: WebExtension, done: () -> Unit = {}) {
        controller.uninstall(ext).accept({ main.post { icons.remove(ext.id); defaultActions.remove(ext.id); refresh(); done() } }, { main.post { done() } })
    }

    companion object {
        private const val TAG = "DotExtensions"
        const val AGENT_ID = "agent@dothq.org"
        const val AGENT_URL = "resource://android/assets/dot-agent/"
        private const val ICON_PX = 96
        const val FIND_EXTENSIONS_URL = "https://addons.mozilla.org/android/"

        /** Human wording for permissions, following Firefox's prompts. */
        fun describe(permission: String): String? = when (permission) {
            "<all_urls>" -> "Access your data for all websites"
            "tabs" -> "Access browser tabs"
            "history" -> "Access browsing history"
            "bookmarks" -> "Read and modify bookmarks"
            "downloads" -> "Download files and read and modify the browser's download history"
            "downloads.open" -> "Open files downloaded to your device"
            "notifications" -> "Display notifications to you"
            "geolocation" -> "Access your location"
            "clipboardRead" -> "Get data from the clipboard"
            "clipboardWrite" -> "Input data to the clipboard"
            "browserSettings" -> "Read and modify browser settings"
            "privacy" -> "Read and modify privacy settings"
            "webNavigation" -> "Access browser activity during navigation"
            "browsingData" -> "Clear recent browsing history, cookies and related data"
            "management" -> "Monitor extension usage and manage themes"
            "nativeMessaging" -> "Exchange messages with programs other than Dot"
            "sessions" -> "Access recently closed tabs"
            "tabHide" -> "Hide and show browser tabs"
            "topSites" -> "Access browsing history"
            "unlimitedStorage" -> "Store unlimited amount of client-side data"
            "declarativeNetRequest", "declarativeNetRequestWithHostAccess" -> "Block content on any page"
            "proxy" -> "Control browser proxy settings"
            else -> null
        }

        fun describeOrigin(origin: String): String {
            if (origin == "<all_urls>" || origin.contains("*://*/") || origin == "*://*/*") return "Access your data for all websites"
            val host = origin.substringAfter("://").substringBefore('/').removePrefix("*.")
            return "Access your data for $host"
        }
    }
}
