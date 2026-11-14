package co.dothq.browser

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import co.dothq.browser.managers.StorageManager
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

class BrowserDelegates {
     fun createProgressDelegate(area: String, context: Context, applicationCtx: Context): GeckoSession.ProgressDelegate {
        return object : GeckoSession.ProgressDelegate {

            override fun onPageStop(session: GeckoSession, success: Boolean) = Unit

            override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation)  {

                if (area == "main") {
                    val activity: Activity = (context as Activity)
                    val contextualIdIcon = activity.findViewById<ImageView>(R.id.contextIdentityIcon)

                    if (securityInfo.isSecure) {
                        contextualIdIcon.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_secure_filled))
                    } else {
                        contextualIdIcon.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_unsecure_filled))
                    }

                    StorageManager().set(applicationCtx, "contextualIdentity", securityInfo.isSecure, "appValues");
                }
            }

            override fun onPageStart(session: GeckoSession, url: String) {

            }


            override fun onProgressChange(session: GeckoSession, progress: Int) {

            }
        }
    }

    public fun createNavigationDelegate(area: String, context: Context, applicationCtx: Context): GeckoSession.NavigationDelegate {
        return object : GeckoSession.NavigationDelegate {

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                (context as? BrowserActivity)?.canGoBack = canGoBack
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                StorageManager().set(
                    applicationCtx,
                    "currentUri",
                    url.toString(),
                    "appValues"
                )

                val uri: Uri = url.toString().toUri();
                val host = uri.host.toString().removePrefix("www.")

                val path = url.toString().replace("${uri.scheme}://${uri.host}", "");

                if (area == "main") {
                    val activity: Activity = (context as Activity)

                    activity.findViewById<TextView>(R.id.addressBarDomain).text = host
                    activity.findViewById<TextView>(R.id.deeplinkAddressBarDomain).text = host

                    if (path != "/") activity.findViewById<TextView>(R.id.addressBarPath).text = path
                    if (path == "/") activity.findViewById<TextView>(R.id.addressBarPath).text = ""

                    if (path == "about:blank") {
                        activity.findViewById<TextView>(R.id.addressBarDomain).text = ""
                        activity.findViewById<TextView>(R.id.deeplinkAddressBarDomain).text = ""
                        activity.findViewById<TextView>(R.id.addressBarPath).text = ""
                    }
                }
            }

            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {

                val activity: Activity = (context as Activity)
                val contextualIdIcon = activity.findViewById<ImageView>(R.id.contextIdentityIcon)
                contextualIdIcon.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_globe))
                return (GeckoResult.allow())
            }
        }
    }
}
