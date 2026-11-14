package co.dothq.browser.tabs

import android.graphics.Bitmap
import co.dothq.browser.core.Urls
import org.mozilla.geckoview.GeckoSession

class Tab(val id: Long, val isPrivate: Boolean) {
    var session: GeckoSession? = null

    /** Current URL. Empty or about:blank means this tab is showing the new tab page. */
    var url: String = ""
    var title: String = ""
    var progress: Int = 0
    var loading: Boolean = false
    var secure: Boolean = false
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var desktopMode: Boolean = false

    /** Trackers stopped on the current page, by unique host. */
    val blockedHosts = LinkedHashSet<String>()
    val blockedCategories = HashMap<Int, Int>()
    val trackersBlocked: Int get() = blockedHosts.size

    var thumbnail: Bitmap? = null
    var favicon: Bitmap? = null

    /** Serialised GeckoSession.SessionState, kept up to date for restore. */
    var savedState: String? = null
    /** State to restore the next time a session is created for this tab. */
    var pendingState: String? = null

    /** Tab that opened this one (for returning on close). */
    var parentId: Long? = null
    var lastUsed: Long = System.currentTimeMillis()
    /** Opened from another app: Back with no history returns there. */
    var openedExternally: Boolean = false
    /** A live link preview, not (yet) in the tab strip. Quiet: no history, popups or prompts. */
    var preview: Boolean = false

    val isHome: Boolean get() = Urls.isHome(url)
    val host: String get() = Urls.host(url)

    fun displayTitle(fallbackNew: String): String = when {
        isHome -> fallbackNew
        title.isNotBlank() -> title
        else -> Urls.pretty(url)
    }

    fun resetPageState() {
        blockedHosts.clear()
        blockedCategories.clear()
    }
}
