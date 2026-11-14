package co.dothq.browser.tabs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import co.dothq.browser.DotApp
import co.dothq.browser.core.ErrorPages
import co.dothq.browser.core.Favicons
import co.dothq.browser.core.Urls
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

enum class Change { URL, TITLE, PROGRESS, LOADING, SECURITY, NAVIGATION, FAVICON, TRACKERS, THUMBNAIL }

/** The browser window that is currently showing tabs. The manager outlives it. */
interface TabUi {
    fun onTabChanged(tab: Tab, change: Change)
    fun onTabsChanged()
    fun onSelected(previous: Tab?, tab: Tab)
    fun onContextMenu(tab: Tab, x: Int, y: Int, element: GeckoSession.ContentDelegate.ContextElement)
    fun onFullScreen(tab: Tab, fullScreen: Boolean)
    fun onDownload(tab: Tab, response: WebResponse)
    fun onCrashed(tab: Tab)
    fun onOpenExternal(uri: String)
    fun onFirstPaint(tab: Tab)
    /** Prompts, permissions and text selection need an Activity, so the window provides them. */
    fun installWindowDelegates(session: GeckoSession)
}

/**
 * Owns every tab and its GeckoSession. Application-scoped: the window attaches and detaches,
 * sessions keep running. Normal tabs are persisted and restored lazily after process death.
 */
class TabManager(private val app: DotApp) {
    val tabs = mutableListOf<Tab>()
    var selected: Tab? = null
        private set
    var ui: TabUi? = null
        private set

    private var nextId = 1L
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var restored = false
    private var pendingTrackerFlush = 0L

    /** Recently closed, for undo. */
    private var lastClosed: Triple<Tab, Int, Boolean>? = null

    fun attach(window: TabUi) {
        ui = window
        restoreIfNeeded()
        tabs.forEach { t -> t.session?.let { window.installWindowDelegates(it) } }
    }

    fun detach(window: TabUi) {
        if (ui === window) ui = null
    }

    fun normalTabs() = tabs.filter { !it.isPrivate }
    fun privateTabs() = tabs.filter { it.isPrivate }

    /* ---- Lifecycle of tabs ------------------------------------------------------------------ */

    fun newTab(url: String? = null, private: Boolean = false, select: Boolean = true, parent: Tab? = null): Tab {
        val tab = Tab(nextId++, private)
        tab.url = url ?: ""
        tab.parentId = parent?.id
        val index = if (parent != null) {
            // Open after the parent and its other children, like desktop.
            var i = tabs.indexOf(parent) + 1
            while (i < tabs.size && tabs[i].parentId == parent.id) i++
            i
        } else tabs.size
        tabs.add(index.coerceIn(0, tabs.size), tab)
        if (select) select(tab) else if (!tab.isHome) ensureSession(tab)
        ui?.onTabsChanged()
        scheduleSave()
        return tab
    }

    fun select(tab: Tab) {
        val previous = selected
        if (previous === tab && tab.session != null) return
        previous?.session?.let { it.setActive(false); app.runtime.webExtensionController.setTabActive(it, false) }
        selected = tab
        tab.lastUsed = System.currentTimeMillis()
        val session = ensureSession(tab)
        session.setActive(true)
        // Extensions act on "the current tab" (popups, tabs.query, etc.).
        app.runtime.webExtensionController.setTabActive(session, true)
        ui?.onSelected(previous, tab)
        scheduleSave()
    }

    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasSelected = tab === selected
        tabs.removeAt(index)
        // Dot stops working on a page that's gone (the conversation stays, for undo).
        app.assistant.sessionOrNull(tab)?.stop()
        // Close right away (stops media); undo restores from the saved session state.
        tab.session?.close()
        tab.session = null
        tab.pendingState = tab.savedState
        tab.loading = false
        lastClosed = Triple(tab, index, wasSelected)
        if (wasSelected) {
            val next = pickNextAfterClose(tab, index)
            selected = null
            if (next != null) select(next) else newTab(private = false)
        }
        File(app.cacheDir, "thumbs/${tab.id}.jpg").delete()
        ui?.onTabsChanged()
        scheduleSave()
    }

    fun undoClose(): Tab? {
        val (tab, index, wasSelected) = lastClosed ?: return null
        lastClosed = null
        if (tabs.contains(tab)) return tab
        tabs.add(index.coerceIn(0, tabs.size), tab)
        if (wasSelected) select(tab)
        ui?.onTabsChanged()
        scheduleSave()
        return tab
    }

    fun closeAll(private: Boolean) {
        val victims = tabs.filter { it.isPrivate == private }
        victims.forEach { t ->
            tabs.remove(t)
            app.assistant.dropSession(t)
            t.session?.close()
            t.session = null
            File(app.cacheDir, "thumbs/${t.id}.jpg").delete()
        }
        lastClosed = null
        if (selected == null || !tabs.contains(selected!!)) {
            selected = null
            val fallback = tabs.maxByOrNull { it.lastUsed }
            if (fallback != null) select(fallback) else newTab(private = false)
        }
        ui?.onTabsChanged()
        scheduleSave()
    }

    /**
     * A tab whose session Gecko (or an extension) will open itself: placed after [parent],
     * selected once it's open if [select].
     */
    fun newChildTab(parent: Tab?, url: String, select: Boolean, private: Boolean = parent?.isPrivate ?: false): Tab {
        val child = Tab(nextId++, private)
        child.url = url
        child.parentId = parent?.id
        val s = GeckoSession(settingsFor(child))
        wire(child, s)
        child.session = s
        val at = if (parent != null) tabs.indexOf(parent) + 1 else tabs.size
        tabs.add(at.coerceIn(0, tabs.size), child)
        if (select) selectWhenOpen(child, 0) else main.post { ui?.onTabsChanged(); scheduleSave() }
        return child
    }

    /** Moves [tab] to where [target] is (drag to reorder). */
    fun moveTab(tab: Tab, target: Tab) {
        val from = tabs.indexOf(tab)
        val to = tabs.indexOf(target)
        if (from < 0 || to < 0 || from == to) return
        tabs.removeAt(from)
        tabs.add(to, tab)
        scheduleSave()
    }

    private fun pickNextAfterClose(closed: Tab, index: Int): Tab? {
        closed.parentId?.let { pid -> tabs.firstOrNull { it.id == pid && it.isPrivate == closed.isPrivate }?.let { return it } }
        val sameMode = tabs.filter { it.isPrivate == closed.isPrivate }
        if (sameMode.isNotEmpty()) {
            // The neighbour to the right, else the left.
            return tabs.drop(index).firstOrNull { it.isPrivate == closed.isPrivate }
                ?: sameMode.lastOrNull()
        }
        return tabs.filter { !it.isPrivate }.maxByOrNull { it.lastUsed }
    }

    /* ---- Sessions --------------------------------------------------------------------------- */

    private fun settingsFor(tab: Tab) = GeckoSessionSettings.Builder()
        .usePrivateMode(tab.isPrivate)
        .useTrackingProtection(true)
        .userAgentMode(if (tab.desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        .viewportMode(if (tab.desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        .build()

    fun ensureSession(tab: Tab): GeckoSession {
        tab.session?.let { return it }
        val session = GeckoSession(settingsFor(tab))
        wire(tab, session)
        session.open(app.runtime)
        tab.session = session
        val state = tab.pendingState
        tab.pendingState = null
        val restoredState = state?.let { runCatching { GeckoSession.SessionState.fromString(it) }.getOrNull() }
        when {
            restoredState != null -> session.restoreState(restoredState)
            !tab.isHome -> session.loadUri(tab.url)
        }
        return session
    }

    /** A tab with a live, loading session that isn't in the strip (link peek previews). */
    fun createPreview(url: String, private: Boolean): Tab {
        val tab = Tab(nextId++, private)
        tab.url = url
        tab.preview = true
        val s = GeckoSession(settingsFor(tab))
        wire(tab, s)
        // Previews can't pop dialogs or ask for permissions; Gecko's defaults dismiss/deny.
        s.promptDelegate = null
        s.permissionDelegate = null
        s.open(app.runtime)
        tab.session = s
        s.loadUri(url)
        return tab
    }

    /** Brings a preview into the strip after [parent], live session and all. */
    fun adoptPreview(tab: Tab, parent: Tab?, select: Boolean) {
        if (!tab.preview) return
        tab.preview = false
        tab.parentId = parent?.id
        tab.session?.let { ui?.installWindowDelegates(it) }
        val at = if (parent != null) tabs.indexOf(parent) + 1 else tabs.size
        tabs.add(at.coerceIn(0, tabs.size), tab)
        if (select) select(tab) else tab.session?.setActive(false)
        ui?.onTabsChanged()
        scheduleSave()
    }

    fun discardPreview(tab: Tab) {
        if (!tab.preview) return
        tab.session?.close()
        tab.session = null
    }

    fun load(tab: Tab, url: String) {
        tab.url = url
        tab.resetPageState()
        ensureSession(tab).loadUri(url)
        ui?.onTabChanged(tab, Change.URL)
    }

    fun setDesktopMode(tab: Tab, desktop: Boolean) {
        tab.desktopMode = desktop
        tab.session?.let { s ->
            s.settings.userAgentMode =
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            s.settings.viewportMode =
                if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            s.reload()
        }
    }

    private fun notify(tab: Tab, change: Change) {
        ui?.onTabChanged(tab, change)
    }

    private fun wire(tab: Tab, session: GeckoSession) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean,
            ) {
                val newUrl = url ?: ""
                if (Urls.host(newUrl) != Urls.host(tab.url)) tab.favicon = null
                tab.url = newUrl
                if (tab.isHome) { tab.title = ""; tab.secure = false }
                if (!tab.isPrivate && tab.favicon == null) {
                    Favicons.memoryCached(tab.host)?.let { tab.favicon = it }
                }
                notify(tab, Change.URL)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack = canGoBack
                notify(tab, Change.NAVIGATION)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward = canGoForward
                notify(tab, Change.NAVIGATION)
            }

            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val scheme = runCatching { Uri.parse(request.uri).scheme?.lowercase() }.getOrNull()
                val internal = scheme == null || scheme in setOf(
                    "http", "https", "about", "data", "blob", "file", "resource", "view-source", "moz-extension", "javascript", "chrome",
                )
                if (!internal) {
                    if (!tab.preview) ui?.onOpenExternal(request.uri)
                    return GeckoResult.deny()
                }
                return GeckoResult.allow()
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (tab.preview) return null
                // target=_blank / window.open: a new tab right after this one, selected once open.
                return GeckoResult.fromValue(newChildTab(tab, uri, select = true).session)
            }

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String> {
                return GeckoResult.fromValue(ErrorPages.build(app, uri ?: "", error))
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.loading = true
                tab.progress = 5
                tab.scrollY = 0
                tab.resetPageState()
                notify(tab, Change.LOADING)
                notify(tab, Change.TRACKERS)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.loading = false
                tab.progress = 100
                notify(tab, Change.LOADING)
                val host = tab.host
                if (host.isNotEmpty() && Urls.isWeb(tab.url)) {
                    Favicons.get(app, host, allowNetwork = !tab.isPrivate) { bmp ->
                        if (bmp != null && tab.host == host) {
                            tab.favicon = bmp
                            notify(tab, Change.FAVICON)
                        }
                    }
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.progress = progress
                notify(tab, Change.PROGRESS)
            }

            override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {
                tab.secure = info.isSecure
                notify(tab, Change.SECURITY)
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                tab.savedState = sessionState.toString()
                scheduleSave()
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title ?: ""
                if (!tab.isPrivate && Urls.isWeb(tab.url) && !title.isNullOrBlank()) {
                    val url = tab.url
                    io.execute { app.db.updateTitle(url, title) }
                }
                notify(tab, Change.TITLE)
            }

            override fun onCloseRequest(session: GeckoSession) {
                if (!tab.preview) close(tab)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (!tab.preview) ui?.onFullScreen(tab, fullScreen)
            }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                if (!tab.preview) ui?.onContextMenu(tab, screenX, screenY, element)
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                if (!tab.preview) ui?.onDownload(tab, response)
            }

            override fun onCrash(session: GeckoSession) = recover(tab, session)
            override fun onKill(session: GeckoSession) = recover(tab, session)

            override fun onFirstContentfulPaint(session: GeckoSession) {
                ui?.onFirstPaint(tab)
            }
        }

        session.historyDelegate = object : GeckoSession.HistoryDelegate {
            override fun onVisited(session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int): GeckoResult<Boolean> {
                if (tab.isPrivate || tab.preview || !Urls.isWeb(url)) return GeckoResult.fromValue(false)
                if (flags and GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL == 0) return GeckoResult.fromValue(false)
                val skip = GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE or
                    GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT or
                    GeckoSession.HistoryDelegate.VISIT_UNRECOVERABLE_ERROR
                if (flags and skip != 0) return GeckoResult.fromValue(false)
                val title = if (tab.url == url) tab.title else null
                io.execute { app.db.recordVisit(url, title) }
                return GeckoResult.fromValue(true)
            }

            override fun getVisited(session: GeckoSession, urls: Array<String>): GeckoResult<BooleanArray> {
                if (tab.isPrivate) return GeckoResult.fromValue(BooleanArray(urls.size))
                val result = GeckoResult<BooleanArray>()
                io.execute {
                    val db = app.db.readableDatabase
                    val out = BooleanArray(urls.size) { i ->
                        db.rawQuery("SELECT 1 FROM history WHERE url = ?", arrayOf(urls[i])).use { it.moveToFirst() }
                    }
                    result.complete(out)
                }
                return result
            }
        }

        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                tab.scrollY = scrollY
            }
        }

        session.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
                val host = runCatching { Uri.parse(event.uri).host }.getOrNull() ?: return
                if (!tab.blockedHosts.add(host)) return
                val category = categoryOf(event)
                tab.blockedCategories[category] = (tab.blockedCategories[category] ?: 0) + 1
                pendingTrackerFlush++
                scheduleSave()
                notify(tab, Change.TRACKERS)
            }
        }

        if (!tab.preview) ui?.installWindowDelegates(session)
        app.extensions.attachSession(tab, session)
        app.bridge.attach(session)
    }

    private fun selectWhenOpen(child: Tab, tries: Int) {
        main.postDelayed({
            if (!tabs.contains(child)) return@postDelayed
            val s = child.session
            if (s == null || s.isOpen || tries > 40) {
                if (s != null && !s.isOpen) s.open(app.runtime)
                select(child)
                ui?.onTabsChanged()
            } else selectWhenOpen(child, tries + 1)
        }, 16)
    }

    private fun categoryOf(e: ContentBlocking.BlockEvent): Int {
        val at = e.antiTrackingCategory
        return when {
            at and ContentBlocking.AntiTracking.FINGERPRINTING != 0 -> ContentBlocking.AntiTracking.FINGERPRINTING
            at and ContentBlocking.AntiTracking.CRYPTOMINING != 0 -> ContentBlocking.AntiTracking.CRYPTOMINING
            at and (ContentBlocking.AntiTracking.SOCIAL or ContentBlocking.AntiTracking.STP) != 0 -> ContentBlocking.AntiTracking.SOCIAL
            at and ContentBlocking.AntiTracking.AD != 0 -> ContentBlocking.AntiTracking.AD
            at and ContentBlocking.AntiTracking.ANALYTIC != 0 -> ContentBlocking.AntiTracking.ANALYTIC
            at and ContentBlocking.AntiTracking.CONTENT != 0 -> ContentBlocking.AntiTracking.CONTENT
            e.cookieBehaviorCategory != 0 -> CATEGORY_COOKIES
            else -> CATEGORY_OTHER
        }
    }

    private fun recover(tab: Tab, session: GeckoSession) {
        if (tab.session !== session) return
        runCatching { session.close() }
        tab.session = null
        tab.pendingState = tab.savedState
        tab.loading = false
        if (tab === selected) {
            ensureSession(tab).setActive(true)
            ui?.onCrashed(tab)
            ui?.onSelected(tab, tab)
        }
    }

    /** Lifetime trackers stopped, including ones not yet written to disk. */
    fun totalTrackersStopped(): Long = app.prefs.trackersStopped + pendingTrackerFlush

    /* ---- Thumbnails ------------------------------------------------------------------------- */

    fun setThumbnail(tab: Tab, bitmap: Bitmap) {
        tab.thumbnail = bitmap
        notify(tab, Change.THUMBNAIL)
        if (tab.isPrivate) return
        io.execute {
            runCatching {
                val dir = File(app.cacheDir, "thumbs").apply { mkdirs() }
                FileOutputStream(File(dir, "${tab.id}.jpg")).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            }
        }
    }

    /* ---- Persistence ------------------------------------------------------------------------ */

    private val saveRunnable = Runnable { saveNow() }

    fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1200)
    }

    fun saveNow() {
        main.removeCallbacks(saveRunnable)
        if (pendingTrackerFlush > 0) {
            app.prefs.trackersStopped = app.prefs.trackersStopped + pendingTrackerFlush
            pendingTrackerFlush = 0
        }
        val arr = JSONArray()
        normalTabs().forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("url", t.url)
                put("title", t.title)
                put("desktop", t.desktopMode)
                put("chat", t.chat)
                (t.savedState ?: t.pendingState)?.let { put("state", it) }
                t.parentId?.let { put("parent", it) }
                put("used", t.lastUsed)
            })
        }
        val sel = selected?.takeIf { !it.isPrivate }?.id ?: normalTabs().maxByOrNull { it.lastUsed }?.id ?: -1
        app.prefs.savedTabs = JSONObject().put("selected", sel).put("tabs", arr).toString()
    }

    private fun restoreIfNeeded() {
        if (restored) return
        restored = true
        val json = app.prefs.savedTabs ?: return
        runCatching {
            val root = JSONObject(json)
            val arr = root.getJSONArray("tabs")
            val selId = root.optLong("selected", -1)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = Tab(o.getLong("id"), false)
                t.url = o.optString("url", "")
                t.title = o.optString("title", "")
                t.desktopMode = o.optBoolean("desktop", false)
                t.chat = o.optBoolean("chat", false)
                t.pendingState = o.optString("state", "").ifEmpty { null }
                t.savedState = t.pendingState
                t.parentId = if (o.has("parent")) o.getLong("parent") else null
                t.lastUsed = o.optLong("used", 0L)
                tabs.add(t)
                nextId = maxOf(nextId, t.id + 1)
                val thumb = File(app.cacheDir, "thumbs/${t.id}.jpg")
                if (thumb.exists()) {
                    io.execute {
                        val bmp = BitmapFactory.decodeFile(thumb.path)
                        if (bmp != null) main.post { t.thumbnail = bmp; notify(t, Change.THUMBNAIL) }
                    }
                }
                if (t.host.isNotEmpty()) {
                    Favicons.get(app, t.host, allowNetwork = false) { bmp ->
                        if (bmp != null) { t.favicon = bmp; notify(t, Change.FAVICON) }
                    }
                }
            }
            tabs.firstOrNull { it.id == selId }?.let { selected = null; pendingSelect = it }
        }
    }

    /** Tab to select once the window is ready after a restore. */
    var pendingSelect: Tab? = null

    companion object {
        const val CATEGORY_COOKIES = 1 shl 20
        const val CATEGORY_OTHER = 1 shl 21
    }
}
