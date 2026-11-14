package co.dothq.browser.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Typed access to Dot's preferences. Keys keep the desktop `dot.*` naming. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("dot.prefs", Context.MODE_PRIVATE)

    var setupComplete: Boolean
        get() = p.getBoolean("dot.setup.complete", false)
        set(v) = p.edit { putBoolean("dot.setup.complete", v) }

    var searchEngineId: String
        get() = p.getString("dot.search.engine_id", SearchEngines.DEFAULT.id) ?: SearchEngines.DEFAULT.id
        set(v) = p.edit { putString("dot.search.engine_id", v) }

    val searchEngine: SearchEngine get() = SearchEngines.byId(searchEngineId)

    /** "bottom" (default, thumb-friendly) or "top" (like desktop). */
    var toolbarPosition: String
        get() = p.getString("dot.ui.toolbar.position", "bottom") ?: "bottom"
        set(v) = p.edit { putString("dot.ui.toolbar.position", v) }

    val toolbarAtBottom: Boolean get() = toolbarPosition != "top"

    /** "system", "light" or "dark". */
    var theme: String
        get() = p.getString("dot.ui.theme", "system") ?: "system"
        set(v) = p.edit { putString("dot.ui.theme", v) }

    var strictTracking: Boolean
        get() = p.getBoolean("dot.privacy.tracking.strict", true)
        set(v) = p.edit { putBoolean("dot.privacy.tracking.strict", v) }

    var httpsOnly: Boolean
        get() = p.getBoolean("dot.privacy.https_only", true)
        set(v) = p.edit { putBoolean("dot.privacy.https_only", v) }

    var globalPrivacyControl: Boolean
        get() = p.getBoolean("dot.privacy.gpc", true)
        set(v) = p.edit { putBoolean("dot.privacy.gpc", v) }

    var stripTrackingParams: Boolean
        get() = p.getBoolean("dot.privacy.strip_params", true)
        set(v) = p.edit { putBoolean("dot.privacy.strip_params", v) }

    /** Lifetime count of trackers Dot has stopped. The number on the new tab page. */
    var trackersStopped: Long
        get() = p.getLong("dot.stats.trackers_stopped", 0L)
        set(v) = p.edit { putLong("dot.stats.trackers_stopped", v) }

    var shakeToSummarise: Boolean
        get() = p.getBoolean("dot.ai.shake", true)
        set(v) = p.edit { putBoolean("dot.ai.shake", v) }

    var savedTabs: String?
        get() = p.getString("dot.tabs.saved", null)
        set(v) = p.edit { putString("dot.tabs.saved", v) }
}
