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
import co.dothq.browser.ui.NavButton
import co.dothq.browser.ui.NewTabHost
import co.dothq.browser.ui.NewTabPage
import co.dothq.browser.ui.Palette
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.Sheet
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

class BrowserActivity : AppCompatActivity(), TabUi, SwitcherHost, NewTabHost, ToolbarView.Listener {

    private val app get() = application as DotApp
    override val tabManager: TabManager get() = app.tabs

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var gecko: GeckoView
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
        gecko = GeckoView(this)
        content.addView(gecko, flp(MATCH, MATCH))
        ntp = NewTabPage(this, app, this)
        content.addView(ntp, flp(MATCH, MATCH))
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

    override fun onStart() {
        super.onStart()
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
        if (::gecko.isInitialized) {
            tabManager.detach(this)
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
            ntp.refresh()
        } else if (!showNtp && ntp.visibility == View.VISIBLE) {
            ntp.animate().alpha(0f).setDuration(120).withEndAction { ntp.visibility = View.GONE; ntp.alpha = 1f }.start()
        }
        if (showNtp) ntp.refresh()
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
        if (switcher.isShowing) switcher.refresh()
    }

    override fun onTabsChanged() {
        selected()?.let { toolbar.tabCount.setCount(countFor(it), animate = true) }
        switcher.refresh()
    }

    override fun onTabChanged(tab: Tab, change: Change) {
        if (switcher.isShowing && change in setOf(Change.TITLE, Change.FAVICON, Change.THUMBNAIL, Change.URL)) {
            switcher.refreshTab(tab)
        }
        if (tab !== selected()) return
        val p = palette()
        when (change) {
            Change.URL -> {
                render(tab)
            }
            Change.PROGRESS -> if (tab.loading) toolbar.aurora.setProgress(tab.progress)
            Change.LOADING -> {
                toolbar.setLoading(tab.loading)
                if (tab.loading) toolbar.aurora.start() else toolbar.aurora.finish()
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
        val entries = mutableListOf<MenuEntry>()
        val title = element.title?.ifBlank { null } ?: element.linkText?.trim()?.ifBlank { null } ?: element.altText?.ifBlank { null }
        entries += MenuEntry.Header(title ?: Urls.host(link ?: src ?: tab.url).ifEmpty { "Link" }, link ?: src)
        entries += MenuEntry.Separator
        if (link != null) {
            entries += MenuEntry.Item(getString(R.string.open_in_new_tab)) { openInBackground(link, tab) }
            entries += MenuEntry.Item(getString(R.string.open_in_private_tab)) { tabManager.newTab(link, private = true) }
            entries += MenuEntry.Item(getString(R.string.copy_link)) { copy(link) }
            entries += MenuEntry.Item(getString(R.string.share_link), chevron = true) { share(link, title) }
        }
        if (src != null) {
            if (link != null) entries += MenuEntry.Separator
            when (element.type) {
                GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> {
                    entries += MenuEntry.Item(getString(R.string.open_image)) { openInBackground(src, tab) }
                    entries += MenuEntry.Item(getString(R.string.save_image)) { fetchAndSave(src, tab.isPrivate) }
                    entries += MenuEntry.Item(getString(R.string.copy_image_link)) { copy(src) }
                }
                GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO, GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> {
                    entries += MenuEntry.Item(getString(R.string.save_media)) { fetchAndSave(src, tab.isPrivate) }
                    entries += MenuEntry.Item(getString(R.string.copy_link)) { copy(src) }
                }
            }
        }
        val offset = IntArray(2).also { gecko.getLocationInWindow(it) }
        val rootOffset = IntArray(2).also { root.getLocationInWindow(it) }
        Haptics.heavy(root)
        showMenu(entries, Anchor.AtPoint(x + offset[0] - rootOffset[0], y + offset[1] - rootOffset[1]))
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
        val (name, _) = Downloads.fileName(response)
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
        val t = tabManager.newTab(url, private = parent.isPrivate, select = false, parent = parent)
        toast(getString(R.string.open_in_new_tab), "Switch") { tabManager.select(t) }
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

    override fun onSwipeTabs(direction: Int) {
        val tab = selected() ?: return
        val list = tabManager.tabs.filter { it.isPrivate == tab.isPrivate }
        val target = list.getOrNull(list.indexOf(tab) + direction) ?: return
        Haptics.tick(toolbar)
        tabManager.select(target)
        toolbar.slideUrl(direction)
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
                MenuEntry.Item(getString(R.string.new_tab)) { tabManager.newTab(); startEditing() },
                MenuEntry.Item(getString(R.string.new_private_tab)) { tabManager.newTab(private = true); startEditing() },
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
                val entries = listOf(
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
                    MenuEntry.Item(getString(R.string.new_tab)) { tabManager.newTab(); startEditing() },
                    MenuEntry.Item(getString(R.string.new_private_tab)) { tabManager.newTab(private = true); startEditing() },
                    MenuEntry.Separator,
                    MenuEntry.Item(getString(R.string.share_page), chevron = true, enabled = !home) { share(tab.url, tab.title) },
                    MenuEntry.Item(getString(R.string.find_in_page), enabled = !home) { openFind() },
                    MenuEntry.Item(getString(R.string.desktop_site), toggle = tab.desktopMode, enabled = !home) {
                        tabManager.setDesktopMode(tab, !tab.desktopMode)
                    },
                    MenuEntry.Separator,
                    MenuEntry.Item(getString(R.string.bookmarks)) { openLibrary(LibraryActivity.PAGE_BOOKMARKS) },
                    MenuEntry.Item(getString(R.string.history)) { openLibrary(LibraryActivity.PAGE_HISTORY) },
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
        gecko.capturePixels().accept({ bmp ->
            if (bmp == null) { toast(getString(R.string.screenshot_failed)); return@accept }
            Haptics.confirm(root)
            flash()
            Downloads.saveBitmap(this, bmp, tab.title) { saved ->
                if (saved == null) toast(getString(R.string.screenshot_failed))
                else toast(getString(R.string.screenshot_saved), getString(R.string.open)) {
                    try { startActivity(Downloads.openIntent(saved)) } catch (_: ActivityNotFoundException) {}
                }
            }
        }, { toast(getString(R.string.screenshot_failed)) })
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
        if (toolbar.editing) stopEditing()
        captureThumbnail(tab)
        switcher.show(tab.isPrivate)
        val p = Palette.of(this, tab.isPrivate)
        setSystemBars(p.frame, p.isDark)
        ntp.setAnimating(false)
    }

    private fun hideSwitcher() {
        switcher.hide()
        val p = palette()
        setSystemBars(p.bg, p.isDark)
        ntp.setAnimating(true)
    }

    private fun captureThumbnail(tab: Tab) {
        if (tab.isHome || gecko.session !== tab.session || gecko.width == 0) return
        gecko.capturePixels().accept({ bmp ->
            if (bmp != null) tabManager.setThumbnail(tab, shrink(bmp))
        }, { })
    }

    private fun shrink(bmp: Bitmap): Bitmap {
        val w = 420
        val h = (bmp.height * (w.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val crop = minOf(h, (w * 1.5f).toInt())
        return if (crop < h) Bitmap.createBitmap(scaled, 0, 0, w, crop) else scaled
    }

    override fun onSwitcherPick(tab: Tab) {
        Haptics.tap(root)
        if (tab !== selected()) tabManager.select(tab)
        hideSwitcher()
    }

    override fun onSwitcherNewTab(private: Boolean) {
        hideSwitcher()
        tabManager.newTab(private = private)
        root.postDelayed({ startEditing() }, 120)
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
        tabManager.close(tab)
        toast(getString(R.string.tab_closed), getString(R.string.undo)) { tabManager.undoClose() }
    }

    /* ---- New tab page ----------------------------------------------------------------------- */

    override fun ntpOpen(url: String, newTab: Boolean) {
        if (newTab) tabManager.newTab(url) else navigate(url)
    }

    override fun ntpSiteMenu(site: Site, anchor: View) {
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        showMenu(
            listOf(
                MenuEntry.Header(site.title.ifBlank { Urls.host(site.url) }, Urls.pretty(site.url)),
                MenuEntry.Separator,
                MenuEntry.Item(getString(R.string.open_in_new_tab)) { tabManager.newTab(site.url) },
                MenuEntry.Item(getString(R.string.open_in_private_tab)) { tabManager.newTab(site.url, private = true) },
                MenuEntry.Item(getString(R.string.delete), danger = true) {
                    io.execute {
                        app.db.deleteHistory(site.url)
                        app.db.removeBookmark(site.url)
                        root.post { ntp.refresh() }
                    }
                },
            ),
            Anchor.AtPoint(loc[0] - rootLoc[0] + anchor.width / 2, loc[1] - rootLoc[1] + anchor.height / 2),
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
