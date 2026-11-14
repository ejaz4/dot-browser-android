package co.dothq.browser

import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.drawToBitmap
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import co.dothq.browser.core.Downloads
import co.dothq.browser.core.SearchEngines
import co.dothq.browser.core.Site
import co.dothq.browser.core.Urls
import co.dothq.browser.gecko.WindowDelegates
import co.dothq.browser.tabs.Change
import co.dothq.browser.tabs.Tab
import co.dothq.browser.tabs.TabManager
import co.dothq.browser.tabs.TabUi
import co.dothq.browser.ui.Anchor
import co.dothq.browser.ui.DotMenu
import co.dothq.browser.ui.DotToast
import co.dothq.browser.ui.FindBar
import co.dothq.browser.ui.Fonts
import co.dothq.browser.ui.Haptics
import co.dothq.browser.ui.MATCH
import co.dothq.browser.ui.MenuEntry
import co.dothq.browser.ui.Motion
import co.dothq.browser.ui.NavButton
import co.dothq.browser.ui.NewTabHost
import co.dothq.browser.ui.NewTabPage
import co.dothq.browser.ui.Palette
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.Sheet
import co.dothq.browser.ui.SheetAction
import co.dothq.browser.ui.Suggestion
import co.dothq.browser.ui.SuggestionsView
import co.dothq.browser.ui.SwitcherHost
import co.dothq.browser.ui.TabSwitcher
import co.dothq.browser.ui.ToolbarView
import co.dothq.browser.ui.WRAP
import co.dothq.browser.ui.bindFavicon
import co.dothq.browser.ui.col
import co.dothq.browser.ui.dp
import co.dothq.browser.ui.flp
import co.dothq.browser.ui.icon
import co.dothq.browser.ui.label
import co.dothq.browser.ui.lp
import co.dothq.browser.ui.margins
import co.dothq.browser.ui.oval
import co.dothq.browser.ui.pressable
import co.dothq.browser.ui.rectIn
import co.dothq.browser.ui.ringColors
import co.dothq.browser.ui.withAlpha
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.Executors

class BrowserActivity : AppCompatActivity(), TabUi, SwitcherHost, NewTabHost, ToolbarView.Listener,
    co.dothq.browser.core.ExtensionManager.Ui, co.dothq.browser.ui.ChatHost, co.dothq.browser.ui.SummaryHost,
    android.hardware.SensorEventListener {

    private val app get() = application as DotApp
    override val tabManager: TabManager get() = app.tabs

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var gecko: co.dothq.browser.ui.DotGeckoView
    private lateinit var refresh: co.dothq.browser.ui.RefreshLayout
    private lateinit var chatView: co.dothq.browser.ui.ChatView
    private var chatBound: Tab? = null
    private lateinit var ntp: NewTabPage
    private lateinit var toolbar: ToolbarView
    private lateinit var toolbarSlot: FrameLayout
    private var findBar: FindBar? = null
    private var suggestions: SuggestionsView? = null
    private var activeMenu: DotMenu? = null
    private lateinit var switcher: TabSwitcher
    private lateinit var toastView: DotToast
    private lateinit var delegates: WindowDelegates

    private var palette: Palette? = null
    private var sheetPalette: Palette? = null
    private var _sheet: Sheet? = null
    private var fullScreen = false
    private var keyboardHidesToolbar = false
    private val io = Executors.newSingleThreadExecutor()
    private var suggestionSeq = 0

    /* ---- Activity results ------------------------------------------------------------------- */

    private var filesCallback: ((List<Uri>) -> Unit)? = null
    private val pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        filesCallback?.invoke(listOfNotNull(uri)); filesCallback = null
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filesCallback?.invoke(uris); filesCallback = null
    }
    private var permissionsCallback: ((Boolean) -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionsCallback?.invoke(result.values.all { it }); permissionsCallback = null
    }
    private val library = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val url = data.getStringExtra(LibraryActivity.EXTRA_URL) ?: return@registerForActivityResult
        when {
            data.getBooleanExtra(LibraryActivity.EXTRA_PRIVATE, false) -> tabManager.newTab(url, private = true)
            data.getBooleanExtra(LibraryActivity.EXTRA_NEW_TAB, false) -> tabManager.newTab(url)
            else -> navigate(url)
        }
    }
    private val extensionsScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(ExtensionsActivity.EXTRA_OPEN_URL)?.let { tabManager.newTab(it) }
    }
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        applyLayout()
        selected()?.let { applyPalette(it, force = true); render(it) }
    }

    /* ---- Setup ------------------------------------------------------------------------------ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.prefs.setupComplete) {
            startActivity(Intent(this, WelcomeActivity::class.java).apply {
                putExtra(WelcomeActivity.EXTRA_NEXT, intent)
            })
            finish()
            return
        }
        Fonts.init(this)
        buildUi()
        delegates = WindowDelegates(this)
        tabManager.attach(this)
        app.extensions.ui = this
        app.extensions.start()
        app.assistant.addActivityListener(agentListener)

        val handled = savedInstanceState == null && handleIntent(intent)
        if (!handled) {
            val sel = tabManager.selected
            if (sel != null) onSelected(null, sel)
            else {
                val pending = tabManager.pendingSelect
                tabManager.pendingSelect = null
                val target = pending ?: tabManager.normalTabs().maxByOrNull { it.lastUsed }
                if (target != null) tabManager.select(target) else tabManager.newTab()
            }
        }
        onBackPressedDispatcher.addCallback(this, back)
    }

    private fun buildUi() {
        root = FrameLayout(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        gecko = co.dothq.browser.ui.DotGeckoView(this).apply { atTop = { (selected()?.scrollY ?: 0) <= 0 } }
        refresh = co.dothq.browser.ui.RefreshLayout(this, gecko, palette()).apply {
            refreshEnabled = { selected()?.isHome == false && !fullScreen && !switcher.isShowing }
            onRefresh = { selected()?.session?.reload() }
        }
        content.addView(refresh, flp(MATCH, MATCH))
        ntp = NewTabPage(this, app, this)
        content.addView(ntp, flp(MATCH, MATCH))
        chatView = co.dothq.browser.ui.ChatView(this, this)
        chatView.visibility = View.GONE
        content.addView(chatView, flp(MATCH, MATCH))
        toolbar = ToolbarView(this).also { it.listener = this }
        toolbarSlot = FrameLayout(this)
        toolbarSlot.addView(toolbar, flp(MATCH, WRAP))
        root.addView(column, flp(MATCH, MATCH))
        setContentView(root)
        switcher = TabSwitcher(root, this)
        toastView = DotToast(root)
        applyLayout()
        watchKeyboard()
    }

    /** Toolbar at the bottom (thumb-friendly, default) or the top (like desktop). */
    private fun applyLayout() {
        val bottom = app.prefs.toolbarAtBottom
        column.removeAllViews()
        if (bottom) {
            column.addView(content, lp(MATCH, 0, 1f))
            column.addView(toolbarSlot, lp(MATCH, WRAP))
        } else {
            column.addView(toolbarSlot, lp(MATCH, WRAP))
            column.addView(content, lp(MATCH, 0, 1f))
        }
        ntp.toolbarAtBottom = bottom
        palette?.let { toolbar.applyPalette(it, bottom) }
    }

    private fun watchKeyboard() {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val r = Rect()
                root.getWindowVisibleDisplayFrame(r)
                val keyboard = root.rootView.height - r.bottom > 160.dp
                // Typing into a page with the toolbar at the bottom: get the toolbar out of the way.
                val hide = keyboard && app.prefs.toolbarAtBottom && !toolbar.editing && findBar == null
                if (hide != keyboardHidesToolbar && !fullScreen) {
                    keyboardHidesToolbar = hide
                    toolbarSlot.visibility = if (hide) View.GONE else View.VISIBLE
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Links and searches from other apps, launcher shortcuts. Returns true if a tab was opened. */
    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val engine = app.prefs.searchEngine
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return false
                if (uri.scheme == "http" || uri.scheme == "https") {
                    dismissOverlays()
                    tabManager.newTab(uri.toString()).openedExternally = true
                    return true
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return false
                val url = Regex("""https?://\S+""").find(text)?.value ?: Urls.fromInput(text, engine) ?: return false
                dismissOverlays()
                tabManager.newTab(url).openedExternally = true
                return true
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> {
                val q = intent.getStringExtra(SearchManager.QUERY) ?: return false
                dismissOverlays()
                tabManager.newTab(Urls.fromInput(q, engine)).openedExternally = true
                return true
            }
            ACTION_NEW_TAB, ACTION_NEW_PRIVATE_TAB -> {
                dismissOverlays()
                tabManager.newTab(private = intent.action == ACTION_NEW_PRIVATE_TAB)
                root.post { startEditing() }
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        if (::gecko.isInitialized) {
            val sm = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager).unregisterListener(this)
    }

    override fun onStart() {
        super.onStart()
        if (::gecko.isInitialized) app.extensions.ui = this
        selected()?.session?.setActive(true)
        ntp.setAnimating(true)
    }

    override fun onStop() {
        super.onStop()
        tabManager.saveNow()
        selected()?.session?.setActive(false)
        ntp.setAnimating(false)
    }

    override fun onDestroy() {
        if (::gecko.isInitialized) app.assistant.removeActivityListener(agentListener)
        if (::gecko.isInitialized) {
            tabManager.detach(this)
            if (app.extensions.ui === this) app.extensions.ui = null
            gecko.releaseSession()
        }
        super.onDestroy()
    }

    /* ---- Helpers ---------------------------------------------------------------------------- */

    private fun selected(): Tab? = tabManager.selected

    fun palette(): Palette = palette ?: Palette.of(this, selected()?.isPrivate == true)

    override fun paletteFor(private: Boolean): Palette = Palette.of(this, private)

    val sheet: Sheet
        get() {
            val p = palette()
            val s = _sheet
            if (s == null || (sheetPalette != p && !s.isShowing)) {
                _sheet = Sheet(root, p).also { it.onScrim = ::dimSystemBars }
                sheetPalette = p
            }
            return _sheet!!
        }

    fun tabForSession(session: GeckoSession): Tab? = tabManager.tabs.firstOrNull { it.session === session }

    fun toast(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val bottom = app.prefs.toolbarAtBottom && toolbarSlot.visibility == View.VISIBLE
        toastView.show(palette(), message, action, bottomOffset = if (bottom) toolbarSlot.height else 0, onAction = onAction)
    }

    fun pickFiles(mimes: Array<String>, multiple: Boolean, done: (List<Uri>) -> Unit) {
        filesCallback = done
        try {
            if (multiple) pickMany.launch(mimes) else pickOne.launch(mimes)
        } catch (_: ActivityNotFoundException) {
            filesCallback = null
            done(emptyList())
        }
    }

    fun requestAndroidPermissions(perms: Array<String>, done: (Boolean) -> Unit) {
        permissionsCallback = done
        permissions.launch(perms)
    }

    private fun searchQueryOf(url: String): String? = SearchEngines.ALL.firstNotNullOfOrNull { it.queryOf(url) }

    private fun applyPalette(tab: Tab, force: Boolean = false) {
        val p = Palette.of(this, tab.isPrivate)
        if (!force && p == palette) return
        val modeChanged = palette?.isPrivate != p.isPrivate || force
        palette = p
        root.setBackgroundColor(p.bg)
        refresh.setPalette(p)
        toolbar.applyPalette(p, app.prefs.toolbarAtBottom)
        setSystemBars(if (switcher.isShowing) p.frame else p.bg, p.isDark)
        if (modeChanged) ntp.bind(p, tab.isPrivate)
        findBar?.let { closeFind() }
    }

    private fun setSystemBars(color: Int, dark: Boolean) {
        window.statusBarColor = color
        window.navigationBarColor = if (switcher.isShowing) color else palette().bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    /** Overlays dim the page; dim the system bars with them so nothing is left glowing. */
    private fun dimSystemBars(overlay: Int) {
        val base = if (switcher.isShowing) palette().frame else palette().bg
        val blended = co.dothq.browser.ui.blend(base, 0xFF000000.toInt(), android.graphics.Color.alpha(overlay) / 255f)
        window.navigationBarColor = blended
        window.statusBarColor = blended
    }

    private fun countFor(tab: Tab) = tabManager.tabs.count { it.isPrivate == tab.isPrivate }

    /* ---- Rendering the selected tab --------------------------------------------------------- */

    private fun render(tab: Tab) {
        val p = palette()
        val showNtp = tab.isHome
        if (showNtp && ntp.visibility != View.VISIBLE) {
            ntp.visibility = View.VISIBLE
            ntp.alpha = 0f
            ntp.animate().alpha(1f).setDuration(160).start()
            ntp.playEntrance()
            ntp.refresh()
        } else if (!showNtp && ntp.visibility == View.VISIBLE) {
            ntp.animate().alpha(0f).setDuration(120).withEndAction { ntp.visibility = View.GONE; ntp.alpha = 1f }.start()
        }
        if (showNtp) ntp.refresh()
        renderChat(tab, animate = false)
        if (!toolbar.editing) toolbar.showUrl(tab.url, tab.isHome, searchQueryOf(tab.url))
        renderIdentity(tab, p)
        toolbar.setTrackers(tab.trackersBlocked, pulse = false)
        toolbar.setLoading(tab.loading)
        if (tab.loading) toolbar.aurora.setProgress(tab.progress) else toolbar.aurora.reset()
        toolbar.tabCount.setCount(countFor(tab), animate = false)
        toolbar.refreshAction()
    }

    private fun renderIdentity(tab: Tab, p: Palette) {
        val url = tab.url
        when {
            tab.chat -> toolbar.setIdentity(R.drawable.ic_sparkle, p.accent)
            tab.isHome || searchQueryOf(url) != null -> toolbar.setIdentity(R.drawable.ic_search, if (tab.isHome) p.text3 else p.icon)
            url.startsWith("https://") && tab.secure -> toolbar.setIdentity(R.drawable.ic_padlock, p.icon)
            url.startsWith("http://") -> toolbar.setIdentity(R.drawable.ic_padlock_open, col(R.color.dot_danger))
            url.startsWith("https://") -> toolbar.setIdentity(R.drawable.ic_padlock, p.icon)
            else -> toolbar.setIdentity(R.drawable.ic_info, p.icon)
        }
    }

    /* ---- TabUi ------------------------------------------------------------------------------ */

    override fun onSelected(previous: Tab?, tab: Tab) {
        if (previous != null && previous !== tab && !previous.isHome && gecko.session === previous.session) {
            captureThumbnail(previous)
        }
        val session = tabManager.ensureSession(tab)
        if (gecko.session !== session) {
            gecko.releaseSession()
            if (session.isOpen) {
                gecko.coverUntilFirstPaint(palette().bg)
                gecko.setSession(session)
            }
        }
        if (toolbar.editing) stopEditing()
        findBar?.let { closeFind() }
        applyPalette(tab)
        render(tab)
        if (previous != null && previous !== tab) toolbar.aurora.pulse()
        if (switcher.isShowing) switcher.refresh()
    }

    override fun onTabsChanged() {
        selected()?.let { toolbar.tabCount.setCount(countFor(it), animate = !flying) }
        switcher.refresh()
    }

    private var flying = false
    private var lastTouch = android.graphics.PointF(0f, 0f)
    private var loadStarted = 0L

    /**
     * Opening a link in the background: a little Dot ring leaves your finger and arcs into the
     * tab counter, which catches it.
     */
    private fun flyToTabs(fromX: Float, fromY: Float) {
        if (toolbarSlot.visibility != View.VISIBLE || toolbar.tabsButton.visibility != View.VISIBLE) {
            toolbar.tabCount.bump(); return
        }
        val t = IntArray(2).also { toolbar.tabsButton.getLocationInWindow(it) }
        val r = IntArray(2).also { root.getLocationInWindow(it) }
        val tx = t[0] - r[0] + toolbar.tabsButton.width / 2f
        val ty = t[1] - r[1] + toolbar.tabsButton.height / 2f
        val size = 22.dp
        val dot = co.dothq.browser.ui.DotRingView(this).apply { halo = false; thickness = 0.62f }
        root.addView(dot, flp(size, size))
        val cx = (fromX + tx) / 2f + (tx - fromX) * 0.15f
        val cy = minOf(fromY, ty) - 140.dp
        flying = true
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = android.view.animation.PathInterpolator(0.35f, 0f, 0.25f, 1f)
            addUpdateListener { a ->
                val k = a.animatedValue as Float
                val u = 1f - k
                dot.x = u * u * fromX + 2 * u * k * cx + k * k * tx - size / 2f
                dot.y = u * u * fromY + 2 * u * k * cy + k * k * ty - size / 2f
                val s = 1f - 0.5f * k
                dot.scaleX = s; dot.scaleY = s
                dot.rotation = k * 300f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.removeView(dot)
                    flying = false
                    selected()?.let { toolbar.tabCount.setCount(countFor(it), animate = false) }
                    toolbar.tabCount.bump()
                    Haptics.tick(root)
                }
            })
            start()
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && ::root.isInitialized) {
            val r = IntArray(2).also { root.getLocationOnScreen(it) }
            lastTouch.set(ev.rawX - r[0], ev.rawY - r[1])
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onTabChanged(tab: Tab, change: Change) {
        if (switcher.isShowing && change in setOf(Change.TITLE, Change.FAVICON, Change.THUMBNAIL, Change.URL)) {
            switcher.refreshTab(tab)
        }
        if (tab === peekTab) {
            val halo = peekHalo
            when (change) {
                Change.PROGRESS -> if (tab.loading) halo?.setProgress(tab.progress)
                Change.LOADING -> if (tab.loading) halo?.start() else halo?.finish()
                else -> Unit
            }
            return
        }
        if (tab !== selected()) return
        val p = palette()
        when (change) {
            Change.URL -> {
                render(tab)
            }
            Change.PROGRESS -> {
                if (tab.loading) toolbar.aurora.setProgress(tab.progress)
                if (tab.chat) chatView.onPageProgress(tab.progress)
            }
            Change.LOADING -> {
                toolbar.setLoading(tab.loading)
                if (tab.loading) {
                    loadStarted = android.os.SystemClock.uptimeMillis()
                    toolbar.aurora.start()
                } else {
                    toolbar.aurora.finish()
                    // You waited; a whisper says it's ready.
                    if (loadStarted > 0 && android.os.SystemClock.uptimeMillis() - loadStarted > 2500 && !tab.isHome) Haptics.soft(root)
                    loadStarted = 0
                }
            }
            Change.SECURITY -> renderIdentity(tab, p)
            Change.TRACKERS -> toolbar.setTrackers(tab.trackersBlocked, pulse = true)
            Change.NAVIGATION, Change.TITLE, Change.FAVICON, Change.THUMBNAIL -> Unit
        }
    }

    override fun onContextMenu(tab: Tab, x: Int, y: Int, element: GeckoSession.ContentDelegate.ContextElement) {
        if (tab !== selected()) return
        val link = element.linkUri
        val src = element.srcUri
        val title = element.title?.ifBlank { null } ?: element.linkText?.trim()?.ifBlank { null } ?: element.altText?.ifBlank { null }
        val isImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
        val isMedia = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO ||
            element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO
        val actions = mutableListOf<SheetAction>()
        if (link != null) {
            actions += SheetAction(R.drawable.ic_private, getString(R.string.open_in_private_tab)) { sheet.dismiss(); tabManager.newTab(link, private = true) }
            actions += SheetAction(R.drawable.ic_copy, getString(R.string.copy_link)) { sheet.dismiss(); copy(link) }
            actions += SheetAction(R.drawable.ic_share, getString(R.string.share_link)) { sheet.dismiss(); share(link, title) }
        }
        if (src != null && isImage) {
            actions += SheetAction(R.drawable.ic_downloads, getString(R.string.save_image)) { sheet.dismiss(); fetchAndSave(src, tab.isPrivate) }
            actions += SheetAction(R.drawable.ic_link, getString(R.string.copy_image_link)) { sheet.dismiss(); copy(src) }
        }
        if (src != null && isMedia) {
            actions += SheetAction(R.drawable.ic_downloads, getString(R.string.save_media)) { sheet.dismiss(); fetchAndSave(src, tab.isPrivate) }
            actions += SheetAction(R.drawable.ic_copy, getString(R.string.copy_link)) { sheet.dismiss(); copy(src) }
        }
        Haptics.heavy(root)
        // Links (and images) get a live preview; media just gets its actions.
        val previewUrl = link ?: src?.takeIf { isImage }
        peek(previewUrl, link ?: src ?: return, title, tab, actions, openLabel = if (link == null && isImage) R.string.open_image else R.string.open_in_new_tab)
    }

    /* ---- Dot Assistant: chat tabs ----------------------------------------------------------- */

    /** Turns [tab] into a Chat tab, or back into a normal tab. The page keeps running underneath. */
    fun setChat(tab: Tab, on: Boolean) {
        if (tab.chat == on) return
        tab.chat = on
        tabManager.scheduleSave()
        if (tab === selected()) {
            if (toolbar.editing) stopEditing()
            renderChat(tab, animate = true)
            renderIdentity(tab, palette())
        }
        if (on && !app.assistant.isReady) root.postDelayed({ chatOpenSettings() }, 450)
    }

    private fun renderChat(tab: Tab, animate: Boolean) {
        val show = tab.chat
        if (show) {
            if (chatBound !== tab) {
                chatView.unbind()
                val session = app.assistant.session(tab)
                session.screen = co.dothq.browser.ai.ScreenSource { t, done ->
                    if (gecko.session === t.session) captureGecko { bmp -> done(bmp) } else done(null)
                }
                chatView.bind(session, palette())
                chatBound = tab
            }
            if (chatView.visibility != View.VISIBLE) {
                chatView.visibility = View.VISIBLE
                if (animate) {
                    // The chat rises over the page, which sinks back a touch.
                    chatView.translationY = content.height * 0.6f
                    chatView.alpha = 0f
                    chatView.animate().translationY(0f).alpha(1f).setDuration(Motion.SLOW + 60).setInterpolator(Motion.ease).start()
                    refresh.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.6f).setDuration(Motion.SLOW).start()
                    Haptics.confirm(root)
                }
            }
        } else if (chatView.visibility == View.VISIBLE) {
            if (animate) {
                refresh.scaleX = 0.94f; refresh.scaleY = 0.94f
                refresh.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Motion.SLOW).setInterpolator(Motion.ease).start()
                chatView.animate().translationY(content.height * 0.6f).alpha(0f).setDuration(Motion.NORMAL + 40).setInterpolator(Motion.easeIn)
                    .withEndAction { chatView.visibility = View.GONE; chatView.translationY = 0f; chatView.alpha = 1f }.start()
                Haptics.tap(root)
            } else {
                chatView.visibility = View.GONE
            }
        } else {
            refresh.scaleX = 1f; refresh.scaleY = 1f; refresh.alpha = 1f
        }
        if (!show && chatBound != null && chatBound !== tab) {
            chatView.unbind()
            chatBound = null
        }
        updateAgentIndicator()
    }

    /* ---- Dot Assistant: work carrying on in the background ---------------------------------- */

    private var agentPill: co.dothq.browser.ui.AgentPill? = null
    private var agentGlow: co.dothq.browser.ui.IntelligenceGlow? = null
    /** The chat the pill is about: the one working, or the one that just finished. */
    private var agentWatched: co.dothq.browser.ai.ChatSession? = null
    private val agentListener: () -> Unit = { updateAgentIndicator() }
    private val agentPillTimeout = Runnable { hideAgentPill() }

    /**
     * When Dot is working on something you can't see (you went back to the page, or another
     * tab), a quiet glow wraps the page it's driving and a pill shows the current step. When
     * the work finishes out of sight, the pill offers the answer for a few seconds.
     */
    private fun updateAgentIndicator() {
        if (!::root.isInitialized) return
        val tab = selected()
        val busy = app.assistant.busySession
        if (busy != null) {
            root.removeCallbacks(agentPillTimeout)
            agentWatched = busy
            val here = busy.tab === tab
            if (here && tab?.chat == true) {
                // You're watching the chat itself; it has its own live strip.
                hideAgentGlow()
                hideAgentPill()
                agentWatched = busy
                return
            }
            val heading = if (here) "Dot is working on this page" else "Dot is working in another tab"
            showAgentPill().working(heading, busy.stage ?: "Working")
            if (here && !tab!!.isHome) showAgentGlow() else hideAgentGlow()
            return
        }
        hideAgentGlow()
        val last = agentWatched ?: return
        val pill = agentPill
        val looking = last.tab === tab && tab.chat
        if (pill == null || looking || !last.finishedWithAnswer || last.tab !in tabManager.tabs) {
            hideAgentPill()
            if (looking || pill == null) agentWatched = null
            return
        }
        if (!pill.done) {
            val answer = (last.items.lastOrNull() as? co.dothq.browser.ai.ChatItem.Reply)?.text.orEmpty()
            val preview = answer.replace(Regex("[*`#]"), "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            pill.finished("Dot has an answer", preview ?: "Tap to see it")
            root.postDelayed(agentPillTimeout, 12_000)
        }
    }

    private fun showAgentPill(): co.dothq.browser.ui.AgentPill {
        agentPill?.let { return it }
        val pill = co.dothq.browser.ui.AgentPill(this, palette(),
            onOpen = { openAgentChat() },
            onStop = { agentWatched?.stop() },
        )
        val bottom = if (app.prefs.toolbarAtBottom && toolbarSlot.visibility == View.VISIBLE) toolbarSlot.height else 0
        val lp = flp(MATCH, WRAP, Gravity.BOTTOM).margins(l = 12.dp, r = 12.dp)
        lp.bottomMargin = bottom + 10.dp
        // Above the page (and its glow), below the tab overview, sheets and toasts.
        root.addView(pill, root.indexOfChild(agentGlow ?: column) + 1, lp)
        pill.enter()
        agentPill = pill
        return pill
    }

    private fun hideAgentPill() {
        root.removeCallbacks(agentPillTimeout)
        val pill = agentPill ?: return
        agentPill = null
        pill.leave()
    }

    private fun showAgentGlow() {
        if (agentGlow != null || glow != null) return
        val g = co.dothq.browser.ui.IntelligenceGlow(this).apply { strength = 0.5f }
        root.addView(g, root.indexOfChild(column) + 1, flp(MATCH, MATCH))
        agentGlow = g
        g.begin()
    }

    private fun hideAgentGlow() {
        val g = agentGlow ?: return
        agentGlow = null
        g.end { root.removeView(g) }
    }

    /** Back to the chat that's working (or just finished): it rises from where the pill was. */
    private fun openAgentChat() {
        val session = agentWatched ?: return
        val tab = session.tab
        hideAgentPill()
        if (tab !in tabManager.tabs) { agentWatched = null; return }
        if (selected() !== tab) tabManager.select(tab)
        if (tab.chat) updateAgentIndicator() else setChat(tab, true)
    }

    override fun chatShowPage() {
        selected()?.let { setChat(it, false) }
    }

    override fun chatModelName(): String? = app.assistant.models.active?.name

    override fun chatCapture(done: (Bitmap?) -> Unit) {
        val tab = selected() ?: return done(null)
        if (tab.isHome || gecko.session !== tab.session) return done(null)
        captureGecko { bmp -> done(bmp?.let { shrink(it) }) }
    }

    override fun chatOpenSettings() {
        startActivity(Intent(this, AssistantActivity::class.java))
    }

    override val chatReady: Boolean get() = app.assistant.isReady

    override fun ntpAskDot() {
        val tab = selected() ?: return
        setChat(tab, true)
        chatView.postDelayed({ chatView.focusInput() }, 380)
    }

    override val ntpAssistantOn: Boolean get() = app.assistant.isAvailable && app.assistant.models.enabled

    /* ---- Dot Assistant: shake to summarise ---------------------------------------------------- */

    private var shakeSpikes = 0
    private var lastSpike = 0L
    private var lastShake = 0L

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = kotlin.math.sqrt(x * x + y * y + z * z) / android.hardware.SensorManager.GRAVITY_EARTH
        if (g < 2.4f) return
        val now = android.os.SystemClock.uptimeMillis()
        shakeSpikes = if (now - lastSpike < 600) shakeSpikes + 1 else 1
        lastSpike = now
        if (shakeSpikes >= 2 && now - lastShake > 2500) {
            lastShake = now
            shakeSpikes = 0
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit

    private fun onShake() {
        if (!app.prefs.shakeToSummarise || summaryCard != null || switcher.isShowing) return
        if (app.assistant.busySession != null) return
        val tab = selected() ?: return
        if (tab.isHome || tab.chat || !Urls.isWeb(tab.url)) return
        summarizeCurrent()
    }

    private var summaryCard: co.dothq.browser.ui.SummaryCard? = null
    private var glow: co.dothq.browser.ui.IntelligenceGlow? = null

    fun summarizeCurrent() {
        val tab = selected() ?: return
        if (tab.isHome || summaryCard != null) return
        if (!app.assistant.isReady) {
            toast("Set up Dot Assistant to summarise pages", "Set up") { chatOpenSettings() }
            return
        }
        if (app.assistant.busySession != null) {
            toast("Dot is busy with a chat")
            return
        }
        activeMenu?.dismiss(false)
        Haptics.spin(root)
        val p = palette()
        hideAgentGlow()
        val g = co.dothq.browser.ui.IntelligenceGlow(this)
        root.addView(g, flp(MATCH, MATCH))
        glow = g
        g.begin()
        // The page takes a small breath as the light arrives.
        content.animate().scaleX(0.985f).scaleY(0.985f).setDuration(260).setInterpolator(Motion.ease).withEndAction {
            content.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SLOW).setInterpolator(Motion.spring).start()
        }.start()
        val card = co.dothq.browser.ui.SummaryCard(this, p, tab.title, tab.host, this)
        root.addView(card, flp(MATCH, MATCH))
        summaryCard = card
        val bottom = if (app.prefs.toolbarAtBottom && toolbarSlot.visibility == View.VISIBLE) toolbarSlot.height else 0
        card.show(bottom)
        app.assistant.summarize(tab,
            onStage = { s -> if (summaryCard === card) card.setStage(s) },
            onText = { t -> if (summaryCard === card) card.setText(t) },
        ) { text, error ->
            if (summaryCard !== card) return@summarize
            if (text.isNotBlank()) card.setText(text)
            card.finish(if (text.isBlank()) error ?: "Couldn't summarise this page" else null)
            glow?.end { root.removeView(g) }
            glow = null
        }
    }

    override fun summaryAsk(summary: String) {
        val tab = selected() ?: return
        app.assistant.session(tab).seed("Summarise this page", summary)
        setChat(tab, true)
        chatView.postDelayed({ chatView.focusInput() }, 400)
    }

    override fun summaryCopy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Summary", text))
        if (android.os.Build.VERSION.SDK_INT < 33) toast("Summary copied")
    }

    override fun summaryDismissed() {
        summaryCard = null
        app.assistant.llama.stop()
        glow?.end { glow?.let { root.removeView(it) } }
        glow = null
    }

    /* ---- Extensions ------------------------------------------------------------------------- */

    private fun installExtension(url: String) {
        toast(getString(R.string.installing_extension))
        app.extensions.install(url) { ext, error ->
            when {
                ext != null -> {
                    Haptics.confirm(root)
                    toast(getString(R.string.extension_added, ext.metaData.name ?: ext.id))
                }
                error != null -> toast(error)
            }
        }
    }

    override fun onInstallPrompt(ext: org.mozilla.geckoview.WebExtension, permissions: List<String>, origins: List<String>, done: (Boolean, Boolean) -> Unit) {
        activeMenu?.dismiss(false)
        ExtensionPrompts.install(this, root, palette(), ext, app.extensions.iconOf(ext), permissions, origins, sheet, done)
    }

    override fun onPermissionPrompt(ext: org.mozilla.geckoview.WebExtension, permissions: List<String>, origins: List<String>, done: (Boolean) -> Unit) {
        ExtensionPrompts.permissions(this, palette(), ext, permissions, origins, sheet, done)
    }

    override fun onExtensionsChanged() = Unit

    /** An extension's toolbar popup, in a sheet. */
    override fun onPopup(ext: org.mozilla.geckoview.WebExtension, session: GeckoSession) {
        val p = palette()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 0, 16.dp, 0)
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp, 0, 6.dp, 10.dp)
            val icon = app.extensions.iconOf(ext)
            if (icon != null) addView(ImageView(context).apply { setImageBitmap(icon) }, lp(20.dp, 20.dp).margins(r = 10.dp))
            addView(label(ext.metaData.name ?: ext.id, 15f, p.text, bold = true))
        })
        val frame = FrameLayout(this).apply {
            background = co.dothq.browser.ui.rounded(p.bg, 16f.dp, p.border, 1)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val view = GeckoView(this).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
        frame.addView(view, flp(MATCH, MATCH))
        box.addView(frame, lp(MATCH, (resources.displayMetrics.heightPixels * 0.55f).toInt()))
        view.setSession(session)
        sheet.onDismiss = {
            sheet.onDismiss = null
            root.postDelayed({ view.releaseSession(); session.close() }, 260)
        }
        sheet.show(box)
    }

    override fun openTab(url: String) {
        if (_sheet?.isShowing == true) _sheet?.dismiss()
        tabManager.newTab(url)
    }

    /* ---- Link peek -------------------------------------------------------------------------- */

    private var peekTab: Tab? = null
    private var peekHalo: co.dothq.browser.ui.AuroraOutline? = null

    /**
     * Long-press a link: a sheet rises with a live preview of the page (loading in its own quiet
     * session, wrapped in the Dot halo), its address, and big thumb-sized actions. Opening in a
     * new tab adopts the preview, so the tab is already loaded.
     */
    private fun peek(previewUrl: String?, url: String, title: String?, parent: Tab, extra: List<SheetAction>, openLabel: Int = R.string.open_in_new_tab) {
        activeMenu?.dismiss(false)
        val p = palette()
        val c = this
        var adopted = false
        val preview = previewUrl?.let { tabManager.createPreview(it, parent.isPrivate) }
        peekTab = preview

        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 2.dp, 16.dp, 0)
        }

        // Header: icon, title, address.
        val host = Urls.host(url)
        box.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp, 0, 6.dp, 12.dp)
            addView(ImageView(c).apply {
                bindFavicon(co.dothq.browser.core.Favicons.memoryCached(host), host)
                co.dothq.browser.core.Favicons.get(c, host, allowNetwork = false) { b -> if (b != null) bindFavicon(b, host) }
            }, lp(22.dp, 22.dp).margins(r = 12.dp))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, WRAP, 1f)
                addView(c.label(title ?: host.ifEmpty { url }, 16f, p.text, bold = true, tracking = -0.01f).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(c.label(Urls.pretty(url), 13f, p.text3).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(0, 3.dp, 0, 0)
                })
            })
        })

        fun open(select: Boolean) {
            val t = preview
            if (t == null) {
                if (select) tabManager.newTab(url, private = parent.isPrivate, parent = parent) else openInBackground(url, parent)
                sheet.dismiss()
                return
            }
            adopted = true
            val from = peekCenter()
            val frame = peekView?.let { rectIn(it, root) }
            val shot = peekView?.let { snapshotTexture(it) }
            // A session can only be shown in one place: let go of the preview first.
            peekView?.releaseSession()
            peekView = null
            if (shot != null) tabManager.setThumbnail(t, shrink(shot))
            sheet.dismiss()
            if (select) {
                tabManager.adoptPreview(t, parent, select = true)
                // The preview lifts out of the sheet and becomes the page.
                if (frame != null) {
                    val full = rectIn(content, root)
                    val g = co.dothq.browser.ui.PageGhost.attach(root, shot, p.bg, full, frame, 18f.dp)
                    co.dothq.browser.ui.flyGhost(g, frame, full, 18f.dp, 0f, duration = 420) {
                        g.animate().alpha(0f).setDuration(140).withEndAction { root.removeView(g) }.start()
                    }
                }
            } else {
                flying = true
                tabManager.adoptPreview(t, parent, select = false)
                flyToTabs(from.x, from.y)
                toast(getString(R.string.opened_in_new_tab), getString(R.string.switch_tab)) { tabManager.select(t) }
            }
        }

        // The live preview.
        if (preview != null) {
            val frame = FrameLayout(c)
            val halo = co.dothq.browser.ui.AuroraOutline(c).apply { cornerRadius = 18f.dp; setPrivate(parent.isPrivate) }
            peekHalo = halo
            frame.addView(halo, flp(MATCH, MATCH))
            val m = halo.glowMargin
            val card = FrameLayout(c).apply {
                background = co.dothq.browser.ui.rounded(p.bg, 18f.dp, p.border, 1)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }
            val view = GeckoView(c).apply {
                setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                coverUntilFirstPaint(p.bg)
            }
            card.addView(view, flp(MATCH, MATCH))
            // The preview is a picture of the page, not the page: one tap opens it.
            card.addView(View(c).apply {
                background = co.dothq.browser.ui.ripple(p.ripple, 18f.dp)
                contentDescription = getString(R.string.open)
                setOnClickListener { Haptics.confirm(it); open(select = true) }
            }, flp(MATCH, MATCH))
            frame.addView(card, flp(MATCH, MATCH).margins(m, m, m, m))
            val h = (resources.displayMetrics.heightPixels * 0.42f).toInt().coerceAtMost(460.dp)
            box.addView(frame, lp(MATCH, h + 2 * m).margins(l = -m, r = -m, b = 6.dp))
            preview.session?.let { view.setSession(it) }
            halo.start()
            peekView = view
        }

        // Actions: open first, then the rest.
        val actions = listOf(SheetAction(R.drawable.ic_open_new, getString(openLabel)) { open(select = false) }) + extra
        box.addView(co.dothq.browser.ui.actionList(c, p, actions), lp(MATCH, WRAP).margins(t = 6.dp))

        sheet.onDismiss = {
            sheet.onDismiss = null
            peekTab = null
            peekHalo = null
            val v = peekView
            peekView = null
            // Let the sheet slide away before letting go of the page.
            root.postDelayed({
                v?.releaseSession()
                if (!adopted && preview != null) tabManager.discardPreview(preview)
            }, 260)
        }
        sheet.show(box)
    }

    private var peekView: GeckoView? = null

    /** Synchronous snapshot of a TextureView-backed GeckoView (the peek preview). */
    private fun snapshotTexture(v: GeckoView): Bitmap? {
        fun find(x: View): android.view.TextureView? = when (x) {
            is android.view.TextureView -> x
            is android.view.ViewGroup -> (0 until x.childCount).firstNotNullOfOrNull { find(x.getChildAt(it)) }
            else -> null
        }
        return find(v)?.takeIf { it.isAvailable }?.bitmap
    }

    private fun peekCenter(): android.graphics.PointF {
        val v = peekView ?: return android.graphics.PointF(root.width / 2f, root.height / 2f)
        val a = IntArray(2).also { v.getLocationInWindow(it) }
        val r = IntArray(2).also { root.getLocationInWindow(it) }
        return android.graphics.PointF(a[0] - r[0] + v.width / 2f, a[1] - r[1] + v.height / 2f)
    }

    override fun onFullScreen(tab: Tab, fullScreen: Boolean) {
        if (tab !== selected()) return
        this.fullScreen = fullScreen
        toolbarSlot.visibility = if (fullScreen || keyboardHidesToolbar) View.GONE else View.VISIBLE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullScreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDownload(tab: Tab, response: WebResponse) {
        val (name, mime) = Downloads.fileName(response)
        if (mime == "application/x-xpinstall" || name.endsWith(".xpi", ignoreCase = true)) {
            runCatching { response.body?.close() }
            installExtension(response.uri)
            if (tab.isHome && !tab.canGoBack && tab.parentId != null) tabManager.close(tab)
            return
        }
        toast(getString(R.string.download_started, name))
        Downloads.save(this, response) { saved ->
            if (saved == null) toast(getString(R.string.download_failed))
            else toast(getString(R.string.download_done, saved.name), getString(R.string.open)) {
                try { startActivity(Downloads.openIntent(saved)) } catch (_: ActivityNotFoundException) {}
            }
        }
        // A tab opened just to start a download has nothing to show; close it.
        if (tab.isHome && !tab.canGoBack && tab.parentId != null) tabManager.close(tab)
    }

    override fun onCrashed(tab: Tab) {
        toast(getString(R.string.tab_crashed))
    }

    override fun onOpenExternal(uri: String) {
        try {
            val intent = if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null && Urls.isWeb(fallback)) selected()?.let { tabManager.load(it, fallback) }
                else toast(getString(R.string.no_app_for_link))
            }
        } catch (_: Exception) {
            toast(getString(R.string.no_app_for_link))
        }
    }

    override fun onFirstPaint(tab: Tab) = Unit

    override fun installWindowDelegates(session: GeckoSession) {
        session.promptDelegate = delegates
        session.permissionDelegate = delegates
        session.selectionActionDelegate = BasicSelectionActionDelegate(this)
    }

    /* ---- Navigation ------------------------------------------------------------------------- */

    private fun navigate(url: String) {
        val tab = selected() ?: return
        dismissOverlays()
        if (tab.isHome) gecko.coverUntilFirstPaint(palette().bg)
        tabManager.load(tab, url)
    }

    private fun openInBackground(url: String, parent: Tab) {
        val from = android.graphics.PointF(lastTouch.x, lastTouch.y)
        flying = true
        val t = tabManager.newTab(url, private = parent.isPrivate, select = false, parent = parent)
        flyToTabs(from.x, from.y)
        toast(getString(R.string.opened_in_new_tab), getString(R.string.switch_tab)) { tabManager.select(t) }
    }

    private fun dismissOverlays() {
        activeMenu?.dismiss(false)
        if (_sheet?.isShowing == true) _sheet?.dismiss(false)
        if (switcher.isShowing) hideSwitcher()
        if (toolbar.editing) stopEditing()
    }

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val tab = selected()
            when {
                activeMenu?.isShowing == true -> activeMenu?.dismiss()
                _sheet?.isShowing == true -> _sheet?.dismiss()
                fullScreen -> tab?.session?.exitFullScreen()
                findBar != null -> closeFind()
                toolbar.editing -> stopEditing()
                summaryCard != null -> summaryCard?.dismiss()
                tab?.chat == true -> setChat(tab, false)
                switcher.isShowing -> tab?.let { onSwitcherPick(it) }
                tab == null -> moveTaskToBack(true)
                tab.canGoBack -> tab.session?.goBack()
                tab.openedExternally -> {
                    tabManager.close(tab)
                    moveTaskToBack(true)
                }
                tab.parentId != null && tabManager.tabs.any { it.id == tab.parentId } -> tabManager.close(tab)
                else -> moveTaskToBack(true)
            }
        }
    }

    /* ---- Address editing & suggestions ------------------------------------------------------ */

    private fun startEditing(prefill: String? = null) {
        val tab = selected() ?: return
        activeMenu?.dismiss(false)
        val initial = prefill ?: if (tab.isHome) "" else (searchQueryOf(tab.url) ?: tab.url)
        toolbar.enterEdit(initial)
        showSuggestions()
        toolbar.edit.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(toolbar.edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun stopEditing() {
        toolbar.exitEdit()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(toolbar.edit.windowToken, 0)
        suggestions?.let { s ->
            suggestions = null
            s.animate().alpha(0f).setDuration(120).withEndAction { content.removeView(s) }.start()
        }
        selected()?.let { render(it) }
    }

    private fun showSuggestions() {
        if (suggestions != null) return
        val s = SuggestionsView(this, palette(), reverse = app.prefs.toolbarAtBottom, onPick = ::pickSuggestion) { fill ->
            toolbar.setEditText(if (fill.kind == Suggestion.Kind.SEARCH) fill.title else fill.url)
        }
        content.addView(s, flp(MATCH, MATCH))
        s.alpha = 0f
        s.animate().alpha(1f).setDuration(140).start()
        suggestions = s
        onQueryChanged(toolbar.edit.text.toString().let { if (toolbar.edit.hasSelection() && it == selected()?.url) "" else it })
    }

    private fun pickSuggestion(s: Suggestion) {
        when (s.kind) {
            Suggestion.Kind.TAB -> {
                stopEditing()
                tabManager.tabs.firstOrNull { it.id == s.tabId }?.let { tabManager.select(it) }
            }
            Suggestion.Kind.HEADER -> Unit
            else -> {
                stopEditing()
                navigate(s.url)
            }
        }
    }

    override fun onQueryChanged(text: String) {
        val view = suggestions ?: return
        val q = text.trim()
        val seq = ++suggestionSeq
        val engine = app.prefs.searchEngine
        val tab = selected()
        val tabsSnapshot = tabManager.tabs.filter { it !== tab && !it.isHome && it.isPrivate == tab?.isPrivate }
        io.execute {
            val out = mutableListOf<Suggestion>()
            if (q.isEmpty()) {
                val recent = if (tab?.isPrivate == true) emptyList() else app.db.history(7)
                if (recent.isNotEmpty()) {
                    out += Suggestion(Suggestion.Kind.HEADER, "Recent", "")
                    recent.forEach { out += Suggestion(Suggestion.Kind.HISTORY, it.title.ifBlank { Urls.pretty(it.url) }, it.url, Urls.pretty(it.url)) }
                }
            } else {
                val asUrl = Urls.fromInput(q, engine)
                val looksLikeUrl = asUrl != null && asUrl != engine.searchUrl(q)
                val search = Suggestion(Suggestion.Kind.SEARCH, q, engine.searchUrl(q), engine.name)
                if (looksLikeUrl) {
                    out += Suggestion(Suggestion.Kind.URL, Urls.pretty(asUrl!!), asUrl, asUrl)
                    out += search
                } else out += search
                tabsSnapshot.filter { it.title.contains(q, true) || it.url.contains(q, true) }.take(3).forEach {
                    out += Suggestion(Suggestion.Kind.TAB, it.displayTitle(""), it.url, getString(R.string.switch_to_tab), tabId = it.id)
                }
                val seen = HashSet<String>()
                app.db.bookmarks(q).take(3).forEach {
                    if (seen.add(it.url)) out += Suggestion(Suggestion.Kind.BOOKMARK, it.title.ifBlank { Urls.pretty(it.url) }, it.url, Urls.pretty(it.url))
                }
                if (tab?.isPrivate != true) app.db.suggest(q, 6).forEach {
                    if (seen.add(it.url)) out += Suggestion(Suggestion.Kind.HISTORY, it.title.ifBlank { Urls.pretty(it.url) }, it.url, Urls.pretty(it.url))
                }
            }
            root.post { if (seq == suggestionSeq && suggestions === view) view.submit(q, out) }
        }
    }

    /* ---- ToolbarView.Listener --------------------------------------------------------------- */

    override fun onAddressTap() = startEditing()

    override fun onAddressLongPress(anchor: View) {
        val tab = selected() ?: return
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        val entries = mutableListOf<MenuEntry>()
        if (!tab.isHome) {
            entries += MenuEntry.Item(getString(R.string.copy_link)) { copy(tab.url) }
            entries += MenuEntry.Item(getString(R.string.share_page), chevron = true) { share(tab.url, tab.title) }
        }
        if (clip.hasPrimaryClip()) {
            entries += MenuEntry.Item(getString(R.string.paste_and_go)) {
                val text = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return@Item
                Urls.fromInput(text, app.prefs.searchEngine)?.let { navigate(it) }
            }
        }
        if (entries.isNotEmpty()) showMenu(entries, Anchor.FromView(anchor, above = app.prefs.toolbarAtBottom))
    }

    override fun onSwipeTabs(direction: Int): Boolean {
        val tab = selected() ?: return false
        val list = tabManager.tabs.filter { it.isPrivate == tab.isPrivate }
        val target = list.getOrNull(list.indexOf(tab) + direction) ?: return false
        Haptics.tick(toolbar)
        val from = rectIn(content, root)
        snapshotPage(tab) { bmp ->
            tabManager.select(target)
            toolbar.slideUrl(direction)
            if (bmp != null) {
                val g = co.dothq.browser.ui.PageGhost.attach(root, bmp, palette().bg, from, from, 0f)
                val to = android.graphics.RectF(from).apply {
                    offset(-direction * from.width() * 0.45f, 0f)
                    inset(from.width() * 0.05f, from.height() * 0.05f)
                }
                co.dothq.browser.ui.flyGhost(g, from, to, 0f, 20f.dp, duration = 320, fadeTo = 0f) { root.removeView(g) }
            }
        }
        return true
    }

    override fun onIdentityTap(anchor: View) {
        val tab = selected() ?: return
        if (tab.isHome) startEditing() else showSiteInfo(tab)
    }

    override fun onPillAction() {
        val tab = selected() ?: return
        when {
            toolbar.editing -> toolbar.setEditText("")
            tab.loading -> tab.session?.stop()
            else -> tab.session?.reload()
        }
    }

    override fun onTabsTap() = showSwitcher()

    override fun onTabsLongPress(anchor: View) {
        val tab = selected() ?: return
        showMenu(
            listOf(
                MenuEntry.Item(getString(R.string.new_tab)) { openNewTab(false, centerOf(anchor)) },
                MenuEntry.Item(getString(R.string.new_private_tab)) { openNewTab(true, centerOf(anchor)) },
                MenuEntry.Separator,
                MenuEntry.Item(getString(R.string.close_tab), danger = true) { closeWithUndo(tab) },
            ),
            Anchor.FromView(anchor, above = app.prefs.toolbarAtBottom),
        )
    }

    override fun onMenuTap(anchor: View) = showMainMenu(anchor)

    override fun onCancelEdit() = stopEditing()

    override fun onSubmit(text: String) {
        val url = Urls.fromInput(text, app.prefs.searchEngine) ?: return
        stopEditing()
        navigate(url)
    }

    /* ---- Menus ------------------------------------------------------------------------------ */

    private fun showMenu(entries: List<MenuEntry>, anchor: Anchor) {
        activeMenu?.dismiss(false)
        val m = DotMenu(root, palette())
        m.onDismiss = { if (activeMenu === m) activeMenu = null }
        m.onScrim = ::dimSystemBars
        activeMenu = m
        m.show(entries, anchor)
    }

    private fun showMainMenu(anchor: View) {
        val tab = selected() ?: return
        val home = tab.isHome
        val url = tab.url
        io.execute {
            val bookmarked = !home && app.db.isBookmarked(url)
            root.post {
                val n = tab.trackersBlocked
                val status = if (home) null else MenuEntry.Status(
                    R.drawable.ic_shield_check,
                    when (n) {
                        0 -> getString(R.string.trackers_on_page_none)
                        1 -> getString(R.string.trackers_on_page_one)
                        else -> getString(R.string.trackers_on_page_many, n)
                    },
                    palette().shield,
                ) { showSiteInfo(tab) }
                val extActions = app.extensions.actionsFor(tab.session).map { a ->
                    co.dothq.browser.ui.ActionChip(a.icon, a.title, a.badge, a.enabled, a.click)
                }
                val entries = listOfNotNull(
                    status,
                    extActions.takeIf { it.isNotEmpty() }?.let { MenuEntry.Actions(it) },
                    MenuEntry.Nav(
                        listOf(
                            NavButton(R.drawable.ic_arrow_left, getString(R.string.back), tab.canGoBack) { tab.session?.goBack() },
                            NavButton(R.drawable.ic_arrow_right, getString(R.string.forward), tab.canGoForward) { tab.session?.goForward() },
                            NavButton(R.drawable.ic_star, getString(R.string.bookmark), !home, bookmarked, R.drawable.ic_star_filled) {
                                toggleBookmark(tab, bookmarked)
                            },
                            NavButton(R.drawable.ic_reload, getString(R.string.reload), !home) { tab.session?.reload() },
                        )
                    ),
                    MenuEntry.Separator,
                    MenuEntry.Item(getString(R.string.new_tab)) { openNewTab(false, centerOf(toolbar.menuButton)) },
                    MenuEntry.Item(getString(R.string.new_private_tab)) { openNewTab(true, centerOf(toolbar.menuButton)) },
                    MenuEntry.Separator,
                    if (tab.chat) MenuEntry.Item("Back to the page") { setChat(tab, false) }
                    else MenuEntry.Item("Ask Dot", hint = "Chat") { setChat(tab, true) },
                    MenuEntry.Item("Summarise page", enabled = !home && !tab.chat) { summarizeCurrent() },
                    MenuEntry.Separator,
                    MenuEntry.Item(getString(R.string.share_page), chevron = true, enabled = !home) { share(tab.url, tab.title) },
                    MenuEntry.Item(getString(R.string.find_in_page), enabled = !home) { openFind() },
                    MenuEntry.Item(getString(R.string.desktop_site), toggle = tab.desktopMode, enabled = !home) {
                        tabManager.setDesktopMode(tab, !tab.desktopMode)
                    },
                    MenuEntry.Separator,
                    MenuEntry.Item(getString(R.string.bookmarks)) { openLibrary(LibraryActivity.PAGE_BOOKMARKS) },
                    MenuEntry.Item(getString(R.string.history)) { openLibrary(LibraryActivity.PAGE_HISTORY) },
                    MenuEntry.Item(getString(R.string.extensions)) {
                        extensionsScreen.launch(Intent(this, ExtensionsActivity::class.java))
                    },
                    MenuEntry.Item(getString(R.string.downloads)) {
                        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) {}
                    },
                    MenuEntry.Separator,
                    MenuEntry.Item(getString(R.string.take_screenshot), enabled = !home) { screenshot(tab) },
                    MenuEntry.Item(getString(R.string.view_page_source), enabled = Urls.isWeb(url)) {
                        tabManager.newTab("view-source:$url", private = tab.isPrivate, parent = tab)
                    },
                    MenuEntry.Separator,
                    MenuEntry.Item(getString(R.string.settings)) {
                        settings.launch(Intent(this, SettingsActivity::class.java))
                    },
                )
                showMenu(entries, Anchor.FromView(anchor, above = app.prefs.toolbarAtBottom))
            }
        }
    }

    private fun openLibrary(page: String) {
        library.launch(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_PAGE, page))
    }

    private fun toggleBookmark(tab: Tab, bookmarked: Boolean) {
        val url = tab.url
        val title = tab.title
        io.execute {
            if (bookmarked) app.db.removeBookmark(url) else app.db.addBookmark(url, title)
            root.post {
                Haptics.confirm(root)
                toast(getString(if (bookmarked) R.string.bookmark_removed else R.string.bookmark_added))
            }
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("URL", text))
        if (android.os.Build.VERSION.SDK_INT < 33) toast(getString(R.string.link_copied))
    }

    private fun share(url: String, title: String?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(send, title))
    }

    private fun screenshot(tab: Tab) {
        captureGecko { bmp ->
            if (bmp == null) { toast(getString(R.string.screenshot_failed)); return@captureGecko }
            Haptics.confirm(root)
            flash()
            Downloads.saveBitmap(this, bmp, tab.title) { saved ->
                if (saved == null) toast(getString(R.string.screenshot_failed))
                else toast(getString(R.string.screenshot_saved), getString(R.string.open)) {
                    try { startActivity(Downloads.openIntent(saved)) } catch (_: ActivityNotFoundException) {}
                }
            }
        }
    }

    /** A soft white flash, like a shutter. */
    private fun flash() {
        val v = View(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()); alpha = 0f }
        content.addView(v, flp(MATCH, MATCH))
        v.animate().alpha(0.7f).setDuration(70).withEndAction {
            v.animate().alpha(0f).setDuration(260).withEndAction { content.removeView(v) }.start()
        }.start()
    }

    private fun fetchAndSave(url: String, private: Boolean) {
        val request = WebRequest.Builder(url).build()
        val flags = if (private) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        GeckoWebExecutor(app.runtime).fetch(request, flags).accept({ response ->
            if (response != null) runOnUiThread { selected()?.let { onDownload(it, response) } }
        }, { runOnUiThread { toast(getString(R.string.download_failed)) } })
    }

    /* ---- Site info -------------------------------------------------------------------------- */

    private fun showSiteInfo(tab: Tab) {
        val p = palette()
        val c = this
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 6.dp, 24.dp, 0)
        }
        // Header: icon + host + connection
        box.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(c).apply {
                background = co.dothq.browser.ui.rounded(p.field, Radius.field)
                addView(ImageView(c).apply { bindFavicon(tab.favicon, tab.host) }, flp(22.dp, 22.dp, Gravity.CENTER))
            }, lp(44.dp, 44.dp).margins(r = 14.dp))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = lp(0, WRAP, 1f)
                addView(c.label(tab.host.ifEmpty { Urls.pretty(tab.url) }, 18f, p.text, bold = true, tracking = -0.015f).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                val secure = tab.url.startsWith("https://")
                val internal = !Urls.isWeb(tab.url)
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 5.dp, 0, 0)
                    addView(c.icon(if (secure || internal) R.drawable.ic_padlock else R.drawable.ic_padlock_open, if (secure || internal) p.text2 else col(R.color.dot_danger), 13).apply {
                        (layoutParams as LinearLayout.LayoutParams).marginEnd = 6.dp
                    })
                    addView(c.label(getString(when {
                        internal -> R.string.connection_internal
                        secure -> R.string.connection_secure
                        else -> R.string.connection_insecure
                    }), 13f, if (secure || internal) p.text2 else col(R.color.dot_danger)))
                })
            })
        })

        // Trackers
        val n = tab.trackersBlocked
        val tracker = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = co.dothq.browser.ui.rounded(p.field, 14f.dp)
            setPadding(18.dp, 16.dp, 18.dp, if (n > 0) 8.dp else 16.dp)
        }
        tracker.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(c.icon(R.drawable.ic_shield_check, p.shield, 20).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 12.dp })
            addView(c.label(
                when (n) {
                    0 -> getString(R.string.trackers_on_page_none)
                    1 -> getString(R.string.trackers_on_page_one)
                    else -> getString(R.string.trackers_on_page_many, n)
                }, 15f, p.text, bold = true,
            ).apply { layoutParams = lp(0, WRAP, 1f) })
        })
        if (n > 0) {
            val ring = ringColors(this)
            tab.blockedCategories.entries.sortedByDescending { it.value }.forEachIndexed { i, (cat, count) ->
                tracker.addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(32.dp, 0, 0, 0)
                    minimumHeight = 36.dp
                    addView(View(c).apply { background = oval(ring[(i * 2 + 1) % (ring.size - 1)]) }, lp(8.dp, 8.dp).margins(r = 12.dp))
                    addView(c.label(categoryName(cat), 14f, p.text2).apply { layoutParams = lp(0, WRAP, 1f) })
                    addView(c.label(count.toString(), 14f, p.text, bold = true))
                })
            }
        }
        box.addView(tracker, lp(MATCH, WRAP).margins(t = 20.dp))

        // Footer row → settings
        box.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 52.dp
            setPadding(4.dp, 0, 0, 0)
            pressable(p, Radius.field)
            addView(c.label(getString(R.string.settings_tracking), 15f, p.text).apply { layoutParams = lp(0, WRAP, 1f) })
            addView(c.label(getString(if (app.prefs.strictTracking) R.string.settings_tracking_strict else R.string.settings_tracking_standard), 14f, p.text2))
            addView(c.icon(R.drawable.ic_chevron_right, p.text3, 14).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 6.dp })
            setOnClickListener {
                sheet.dismiss()
                settings.launch(Intent(c, SettingsActivity::class.java))
            }
        }, lp(MATCH, WRAP).margins(t = 10.dp))
        sheet.show(box)
    }

    private fun categoryName(cat: Int) = getString(
        when (cat) {
            ContentBlocking.AntiTracking.AD -> R.string.category_ads
            ContentBlocking.AntiTracking.ANALYTIC -> R.string.category_analytics
            ContentBlocking.AntiTracking.SOCIAL -> R.string.category_social
            ContentBlocking.AntiTracking.CONTENT -> R.string.category_content
            ContentBlocking.AntiTracking.CRYPTOMINING -> R.string.category_crypto
            ContentBlocking.AntiTracking.FINGERPRINTING -> R.string.category_fingerprinting
            TabManager.CATEGORY_COOKIES -> R.string.category_cookies
            else -> R.string.category_other
        }
    )

    /* ---- Find in page ----------------------------------------------------------------------- */

    private fun openFind() {
        val tab = selected() ?: return
        val session = tab.session ?: return
        val p = palette()
        session.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
        var last = ""
        val bar = FindBar(this, p, onQuery = { q ->
            last = q
            if (q.isEmpty()) { session.finder.clear(); findBar?.setResult(0, 0, q) }
            else session.finder.find(q, 0).accept { r -> if (r != null) findBar?.setResult(r.current, r.total, q) }
        }, onNext = { forward ->
            if (last.isNotEmpty()) session.finder.find(null, if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS)
                .accept { r -> if (r != null) findBar?.setResult(r.current, r.total, last) }
        }, onClose = { closeFind() })
        bar.setBackgroundColor(p.bg)
        findBar = bar
        toolbar.visibility = View.GONE
        toolbarSlot.addView(bar, flp(MATCH, 56.dp))
        bar.alpha = 0f
        bar.translationY = (if (app.prefs.toolbarAtBottom) 16f else -16f).dp
        bar.animate().alpha(1f).translationY(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.ease).start()
        bar.input.requestFocus()
        bar.input.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(bar.input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeFind() {
        val bar = findBar ?: return
        findBar = null
        selected()?.session?.finder?.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(bar.windowToken, 0)
        toolbarSlot.removeView(bar)
        toolbar.visibility = View.VISIBLE
    }

    /* ---- Tab switcher ----------------------------------------------------------------------- */

    private fun showSwitcher() {
        val tab = selected() ?: return
        if (switcher.isShowing) return
        if (toolbar.editing) stopEditing()
        val from = rectIn(content, root)
        snapshotPage(tab) { bmp ->
            if (bmp != null) tabManager.setThumbnail(tab, shrink(bmp))
            switcher.show(tab.isPrivate, bmp, from)
            val p = Palette.of(this, tab.isPrivate)
            setSystemBars(p.frame, p.isDark)
            ntp.setAnimating(false)
        }
    }

    /* ---- Pull into the tab grid (3D) -------------------------------------------------------- */

    private class Pull(val tab: Tab, val area: android.graphics.RectF, val backdrop: View) {
        var card: ImageView? = null
        var bitmap: Bitmap? = null
        var snapshotDone = false
        var progress = 0f
        var armed = false
        var ended = false
        var commit = false
        var radius = 0f
    }

    private var pull: Pull? = null

    override fun onPullStart(): Boolean {
        val tab = selected() ?: return false
        if (switcher.isShowing || toolbar.editing || pull != null || findBar != null) return false
        val area = rectIn(content, root)
        val backdrop = View(this).apply {
            setBackgroundColor(Palette.of(this@BrowserActivity, tab.isPrivate).frame)
            alpha = 0f
        }
        root.addView(backdrop, flp(area.width().toInt(), area.height().toInt()))
        backdrop.x = area.left
        backdrop.y = area.top
        val state = Pull(tab, area, backdrop)
        pull = state
        snapshotPage(tab) { bmp ->
            state.snapshotDone = true
            if (pull !== state && !state.ended) return@snapshotPage
            state.bitmap = bmp
            if (bmp != null) {
                val w = area.width().toInt()
                val h = area.height().toInt()
                val card = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.MATRIX
                    setImageBitmap(bmp)
                    imageMatrix = android.graphics.Matrix().apply { val s = w.toFloat() / bmp.width; setScale(s, s) }
                    setBackgroundColor(palette().bg)
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, state.radius)
                        }
                    }
                    cameraDistance = 9000f * resources.displayMetrics.density
                    pivotX = w / 2f
                    pivotY = if (app.prefs.toolbarAtBottom) h.toFloat() else 0f
                }
                root.addView(card, flp(w, h))
                card.x = area.left
                card.y = area.top
                state.card = card
                applyPull(state)
            }
            if (state.ended) finishPull(state)
        }
        return true
    }

    override fun onPullProgress(progress: Float) {
        val s = pull ?: return
        s.progress = progress
        val armed = progress > 0.32f
        if (armed != s.armed) {
            s.armed = armed
            Haptics.tick(root) // you've passed the point of no return (or come back from it)
        }
        applyPull(s)
    }

    override fun onPullEnd(commit: Boolean) {
        val s = pull ?: return
        s.ended = true
        s.commit = commit
        if (s.snapshotDone) finishPull(s)
    }

    /** Lays the page back in depth: it tilts, shrinks and drifts with your finger. */
    private fun applyPull(s: Pull) {
        val raw = s.progress.coerceAtLeast(0f)
        // Follows the finger, then resists like a rubber band.
        val p = if (raw <= 1f) raw else 1f + (raw - 1f) * 0.25f
        val e = co.dothq.browser.ui.Motion.ease.getInterpolation(p.coerceAtMost(1f)) + (p - p.coerceAtMost(1f))
        s.backdrop.alpha = (e * 1.3f).coerceIn(0f, 1f)
        val card = s.card ?: return
        val into = if (app.prefs.toolbarAtBottom) -1f else 1f
        val scale = 1f - 0.34f * e
        card.scaleX = scale
        card.scaleY = scale
        card.rotationX = into * 18f * e
        card.y = s.area.top + into * s.area.height() * 0.1f * e
        card.elevation = 16f.dp * e.coerceAtMost(1f)
        s.radius = (24f.dp * e.coerceAtMost(1f)) / scale.coerceAtLeast(0.1f)
        card.invalidateOutline()
    }

    private fun finishPull(s: Pull) {
        if (pull === s) pull = null
        val card = s.card
        if (!s.commit || card == null) {
            // Not far enough: the page springs back to where it belongs.
            card?.animate()?.scaleX(1f)?.scaleY(1f)?.rotationX(0f)?.y(s.area.top)?.setDuration(Motion.SLOW)
                ?.setInterpolator(Motion.spring)?.withEndAction { root.removeView(card) }?.start()
            s.radius = 0f
            card?.invalidateOutline()
            s.backdrop.animate().alpha(0f).setDuration(Motion.NORMAL).withEndAction { root.removeView(s.backdrop) }.start()
            return
        }
        Haptics.tap(root)
        // Straighten up, then fly into its card in the grid.
        card.animate().rotationX(0f).setDuration(120).setInterpolator(Motion.ease).withEndAction {
            val sc = card.scaleX
            val left = card.x + card.pivotX * (1f - sc)
            val top = card.y + card.pivotY * (1f - sc)
            val from = android.graphics.RectF(left, top, left + card.width * sc, top + card.height * sc)
            s.bitmap?.let { tabManager.setThumbnail(s.tab, shrink(it)) }
            switcher.show(s.tab.isPrivate, s.bitmap, from, fromRadius = s.radius * sc, backgroundFrom = (s.backdrop.alpha * 255).toInt())
            val p = Palette.of(this, s.tab.isPrivate)
            setSystemBars(p.frame, p.isDark)
            ntp.setAnimating(false)
            root.removeView(card)
            root.removeView(s.backdrop)
        }.start()
    }

    /** A picture of what the tab is showing right now: the page, or the new tab page. */
    private fun snapshotPage(tab: Tab, done: (Bitmap?) -> Unit) {
        if (tab.chat && chatView.visibility == View.VISIBLE) {
            done(runCatching { chatView.drawToBitmap() }.getOrNull())
            return
        }
        if (tab.isHome) {
            done(runCatching { ntp.drawToBitmap() }.getOrNull())
            return
        }
        if (gecko.session !== tab.session || gecko.width == 0) { done(null); return }
        captureGecko(done)
    }

    private fun hideSwitcher() {
        switcher.hide()
        val p = palette()
        setSystemBars(p.bg, p.isDark)
        ntp.setAnimating(true)
    }

    private fun captureThumbnail(tab: Tab) {
        if (tab.isHome || gecko.session !== tab.session || gecko.width == 0) return
        captureGecko { bmp -> if (bmp != null) tabManager.setThumbnail(tab, shrink(bmp)) }
    }

    /**
     * Grabs what Gecko is showing. GeckoView's own capture reads back from GL, which fails on
     * some GPUs/emulators; fall back to PixelCopy on its SurfaceView, which works everywhere.
     */
    private fun captureGecko(done: (Bitmap?) -> Unit) {
        gecko.capturePixels().accept({ bmp -> if (bmp != null) done(bmp) else pixelCopy(done) }, { pixelCopy(done) })
    }

    private fun pixelCopy(done: (Bitmap?) -> Unit) {
        fun find(v: View): android.view.SurfaceView? = when (v) {
            is android.view.SurfaceView -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        val surface = find(gecko)
        if (surface == null || surface.width == 0 || surface.height == 0 || !surface.holder.surface.isValid) {
            done(null); return
        }
        val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(surface, bmp, { result ->
                done(if (result == android.view.PixelCopy.SUCCESS) bmp else null)
            }, android.os.Handler(mainLooper))
        } catch (_: IllegalArgumentException) {
            done(null)
        }
    }

    private fun shrink(bmp: Bitmap): Bitmap {
        val w = 420
        val h = (bmp.height * (w.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val crop = minOf(h, (w * 2.1f).toInt())
        return if (crop < h) Bitmap.createBitmap(scaled, 0, 0, w, crop) else scaled
    }

    override fun onSwitcherPick(tab: Tab) {
        Haptics.tap(root)
        if (tab !== selected()) tabManager.select(tab)
        val p = palette()
        switcher.hideInto(tab, rectIn(content, root)) {
            ntp.setAnimating(true)
        }
        setSystemBars(p.bg, p.isDark)
    }

    override fun onSwitcherNewTab(private: Boolean) {
        hideSwitcher()
        openNewTab(private, android.graphics.PointF(root.width / 2f, root.height - 40f.dp))
    }

    /**
     * A new tab grows out of whatever you pressed — the menu, the tab counter, the New tab pill —
     * as a circle widening from that point.
     */
    private fun openNewTab(private: Boolean, origin: android.graphics.PointF?) {
        tabManager.newTab(private = private)
        if (origin != null && ntp.isAttachedToWindow && ntp.width > 0) {
            val n = rectIn(ntp, root)
            val cx = (origin.x - n.left).coerceIn(0f, ntp.width.toFloat())
            val cy = (origin.y - n.top).coerceIn(0f, ntp.height.toFloat())
            val radius = kotlin.math.hypot(maxOf(cx, ntp.width - cx), maxOf(cy, ntp.height - cy))
            ntp.animate().cancel()
            ntp.alpha = 1f
            ntp.visibility = View.VISIBLE
            android.view.ViewAnimationUtils.createCircularReveal(ntp, cx.toInt(), cy.toInt(), 0f, radius).apply {
                duration = 480
                interpolator = Motion.ease
                start()
            }
        }
        Haptics.confirm(root)
        root.postDelayed({ startEditing() }, 220)
    }

    private fun centerOf(v: View): android.graphics.PointF {
        val r = rectIn(v, root)
        return android.graphics.PointF(r.centerX(), r.centerY())
    }

    override fun onSwitcherClose(tab: Tab) {
        closeWithUndo(tab)
    }

    override fun onSwitcherCloseAll(private: Boolean) {
        tabManager.closeAll(private)
        if (!private) hideSwitcher() else switcher.refresh()
    }

    override fun onSwitcherModeChanged(private: Boolean) {
        val p = Palette.of(this, private)
        setSystemBars(p.frame, p.isDark)
    }

    private fun closeWithUndo(tab: Tab) {
        Haptics.reject(root)
        if (tab === selected() && !switcher.isShowing && toolbar.tabsButton.isShown) {
            val from = rectIn(content, root)
            val to = rectIn(toolbar.tabsButton, root).let { b ->
                android.graphics.RectF(b.centerX() - 8f.dp, b.centerY() - 8f.dp, b.centerX() + 8f.dp, b.centerY() + 8f.dp)
            }
            snapshotPage(tab) { bmp ->
                if (bmp != null) {
                    val g = co.dothq.browser.ui.PageGhost.attach(root, bmp, palette().bg, from, from, 0f)
                    co.dothq.browser.ui.flyGhost(g, from, to, 0f, 8f.dp, duration = 380, fadeTo = 0.2f) {
                        root.removeView(g)
                        toolbar.tabCount.bump()
                    }
                }
                tabManager.close(tab)
                toast(getString(R.string.tab_closed), getString(R.string.undo)) { tabManager.undoClose() }
            }
            return
        }
        tabManager.close(tab)
        toast(getString(R.string.tab_closed), getString(R.string.undo)) { tabManager.undoClose() }
    }

    /* ---- New tab page ----------------------------------------------------------------------- */

    override fun ntpOpen(url: String, newTab: Boolean) {
        if (newTab) tabManager.newTab(url) else navigate(url)
    }

    override fun ntpSiteMenu(site: Site, anchor: View) {
        val parent = selected() ?: return
        peek(
            site.url, site.url, site.title.ifBlank { null }, parent,
            listOf(
                SheetAction(R.drawable.ic_private, getString(R.string.open_in_private_tab)) { sheet.dismiss(); tabManager.newTab(site.url, private = true) },
                SheetAction(R.drawable.ic_share, getString(R.string.share_link)) { sheet.dismiss(); share(site.url, site.title) },
                SheetAction(R.drawable.ic_bin, getString(R.string.remove_shortcut), danger = true) {
                    sheet.dismiss()
                    io.execute {
                        app.db.deleteHistory(site.url)
                        app.db.removeBookmark(site.url)
                        root.post { ntp.refresh() }
                    }
                },
            ),
        )
    }

    override fun ntpPrivacy() {
        val p = palette()
        val c = this
        val prefs = app.prefs
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 6.dp, 24.dp, 0)
        }
        box.addView(c.label("How Dot protects you", 20f, p.text, bold = true, tracking = -0.02f).apply { setPadding(0, 0, 0, 16.dp) })
        listOf(
            Triple(R.drawable.ic_shield_check, getString(R.string.settings_tracking), getString(if (prefs.strictTracking) R.string.settings_tracking_strict_body else R.string.settings_tracking_standard_body)),
            Triple(R.drawable.ic_padlock, getString(R.string.settings_https_only), if (prefs.httpsOnly) getString(R.string.settings_https_only_body) else "Off"),
            Triple(R.drawable.ic_cookie, "Cross-site cookies isolated", "Each site gets its own cookie jar, so trackers can't follow you between them."),
            Triple(R.drawable.ic_link, getString(R.string.settings_strip_params), if (prefs.stripTrackingParams) getString(R.string.settings_strip_params_body) else "Off"),
            Triple(R.drawable.ic_info, getString(R.string.settings_gpc), if (prefs.globalPrivacyControl) getString(R.string.settings_gpc_body) else "Off"),
        ).forEach { (icon, title, body) ->
            box.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8.dp, 0, 8.dp)
                addView(FrameLayout(c).apply {
                    background = oval(withAlpha(p.shield, 0.12f))
                    addView(c.icon(icon, p.shield, 16).apply { layoutParams = flp(16.dp, 16.dp, Gravity.CENTER) })
                }, lp(34.dp, 34.dp).margins(r = 14.dp))
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = lp(0, WRAP, 1f)
                    addView(c.label(title, 15f, p.text, bold = true))
                    addView(c.label(body, 13f, p.text2).apply { setPadding(0, 3.dp, 0, 0); setLineSpacing(2f.dp, 1f) })
                })
            })
        }
        box.addView(c.label(getString(R.string.settings), 15f, p.onInk, bold = true).apply {
            gravity = Gravity.CENTER
            background = co.dothq.browser.ui.ripple(withAlpha(p.onInk, 0.2f), Radius.pill, co.dothq.browser.ui.rounded(p.ink, Radius.pill))
            setOnClickListener {
                sheet.dismiss()
                settings.launch(Intent(c, SettingsActivity::class.java))
            }
        }, lp(MATCH, 50.dp).margins(t = 16.dp))
        sheet.show(box)
    }

    override fun ntpFocusSearch() = startEditing()

    companion object {
        const val ACTION_NEW_TAB = "co.dothq.browser.NEW_TAB"
        const val ACTION_NEW_PRIVATE_TAB = "co.dothq.browser.NEW_PRIVATE_TAB"
    }
}
