package co.dothq.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import co.dothq.browser.core.BrowserDb
import co.dothq.browser.core.Prefs
import co.dothq.browser.tabs.TabManager
import co.dothq.browser.ui.Fonts
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class DotApp : Application() {
    lateinit var prefs: Prefs
        private set
    val db: BrowserDb by lazy { BrowserDb(this) }

    /** Created on first use only, so Gecko's child processes never try to start a runtime. */
    val runtime: GeckoRuntime by lazy { createRuntime() }
    val tabs: TabManager by lazy { TabManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        Fonts.init(this)
        applyTheme()
    }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun colorScheme() = when (prefs.theme) {
        "light" -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        "dark" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
    }

    private fun createRuntime(): GeckoRuntime {
        val strict = prefs.strictTracking
        val blocking = ContentBlocking.Settings.Builder()
            .antiTracking(if (strict) ContentBlocking.AntiTracking.STRICT else ContentBlocking.AntiTracking.DEFAULT)
            .enhancedTrackingProtectionLevel(if (strict) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT)
            .enhancedTrackingProtectionCategory(if (strict) ContentBlocking.EtpCategory.STRICT else ContentBlocking.EtpCategory.STANDARD)
            .strictSocialTrackingProtection(strict)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookiePurging(true)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .queryParameterStrippingEnabled(prefs.stripTrackingParams)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .queryParameterStrippingStripList(*STRIP_PARAMS)
            .build()

        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(blocking)
            .allowInsecureConnections(if (prefs.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
            .globalPrivacyControlEnabled(prefs.globalPrivacyControl)
            .preferredColorScheme(colorScheme())
            .aboutConfigEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .build()
        return GeckoRuntime.create(this, settings)
    }

    /** Pushes privacy preferences into the live runtime (no restart needed). */
    fun applyPrivacySettings() {
        val s = runtime.settings
        val strict = prefs.strictTracking
        s.contentBlocking
            .setAntiTracking(if (strict) ContentBlocking.AntiTracking.STRICT else ContentBlocking.AntiTracking.DEFAULT)
            .setEnhancedTrackingProtectionLevel(if (strict) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT)
            .setEnhancedTrackingProtectionCategory(if (strict) ContentBlocking.EtpCategory.STRICT else ContentBlocking.EtpCategory.STANDARD)
            .setStrictSocialTrackingProtection(strict)
            .setQueryParameterStrippingEnabled(prefs.stripTrackingParams)
        s.setAllowInsecureConnections(if (prefs.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
        s.setGlobalPrivacyControl(prefs.globalPrivacyControl)
        s.setPreferredColorScheme(colorScheme())
    }

    companion object {
        lateinit var instance: DotApp
            private set

        /** Common tracking parameters stripped from navigations. */
        private val STRIP_PARAMS = arrayOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
            "mc_eid", "mc_cid", "igshid", "igsh", "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp",
            "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv", "ref_src",
        )
    }
}
