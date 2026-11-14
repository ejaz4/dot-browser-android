package co.dothq.browser.core

import android.content.Context
import android.text.Html
import android.util.Base64
import org.mozilla.geckoview.WebRequestError

/** Dot-styled pages for when the web doesn't answer. */
object ErrorPages {
    private data class Copy(val title: String, val body: String)

    private fun copyFor(error: WebRequestError, host: String): Copy = when (error.code) {
        WebRequestError.ERROR_UNKNOWN_HOST -> Copy("Hmm. We can't find that site.", "Dot couldn't find <b>$host</b>. Check the address for typos, or try searching for it instead.")
        WebRequestError.ERROR_OFFLINE -> Copy("You're offline.", "Dot can't reach the internet right now. Check your connection, then try again.")
        WebRequestError.ERROR_NET_TIMEOUT -> Copy("That took too long.", "<b>$host</b> is taking too long to respond. It might be busy, or down for a moment.")
        WebRequestError.ERROR_CONNECTION_REFUSED -> Copy("Unable to connect.", "<b>$host</b> refused the connection. The site may be down, or blocking connections from your network.")
        WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> Copy("The connection was interrupted.", "The connection to <b>$host</b> dropped while the page was loading.")
        WebRequestError.ERROR_REDIRECT_LOOP -> Copy("This page isn't redirecting properly.", "<b>$host</b> keeps sending Dot around in circles. Clearing cookies for the site can help.")
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_BAD_HSTS_CERT ->
            Copy("This connection isn't private.", "Someone could be trying to impersonate <b>$host</b>. Dot stopped the page from loading to keep your information safe.")
        WebRequestError.ERROR_HTTPS_ONLY -> Copy("Secure site not available.", "HTTPS-Only mode is on, and <b>$host</b> doesn't support a secure connection. You can turn this off in Settings.")
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI -> Copy("Deceptive site ahead.", "<b>$host</b> has been reported for trying to trick people into sharing personal information.")
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI, WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI, WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI ->
            Copy("This site may harm your device.", "<b>$host</b> has been reported for distributing harmful software.")
        WebRequestError.ERROR_MALFORMED_URI -> Copy("That address doesn't look right.", "Check the address for typos and try again.")
        WebRequestError.ERROR_UNKNOWN_PROTOCOL -> Copy("Dot doesn't know how to open this.", "The address uses a protocol Dot doesn't support.")
        WebRequestError.ERROR_FILE_NOT_FOUND -> Copy("File not found.", "Dot couldn't find that file. It may have been moved or deleted.")
        WebRequestError.ERROR_CONTENT_CRASHED -> Copy("This page crashed.", "Something went wrong while showing this page.")
        WebRequestError.ERROR_PORT_BLOCKED -> Copy("This address is restricted.", "The port in this address is normally used for something other than web browsing, so Dot blocked it.")
        else -> Copy("Something went wrong.", "Dot couldn't load <b>$host</b>.")
    }

    fun build(context: Context, uri: String, error: WebRequestError): String {
        val host = Html.escapeHtml(Urls.host(uri).ifEmpty { uri.take(60) })
        val copy = copyFor(error, host)
        val safeUri = Html.escapeHtml(uri)
        val retry = if (uri.startsWith("http")) """<a class="btn" href="$safeUri">Try again</a>""" else ""
        val html = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark"><title>${Html.escapeHtml(copy.title)}</title>
<style>
:root{--bg:#fff;--text:#18191A;--muted:#6B6D70;--field:#F3F3F3;--ink:#0B0F19;--on-ink:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#1A1A1A;--text:#F3F3F3;--muted:#A3A5A8;--field:#2B2B2B;--ink:#F5F5F5;--on-ink:#0B0F19}}
*{box-sizing:border-box}html,body{margin:0;height:100%}
body{background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
display:flex;flex-direction:column;justify-content:center;padding:32px 28px 64px;-webkit-text-size-adjust:100%}
.ring{width:44px;height:44px;border-radius:50%;margin-bottom:28px;
background:conic-gradient(from 90deg,#1FB364,#16B3A3,#2D8FE0,#6A5CE0,#E0526E,#F2893A,#E9C230,#8CC63F,#1FB364);
-webkit-mask:radial-gradient(circle,transparent 10px,#000 10.5px);mask:radial-gradient(circle,transparent 10px,#000 10.5px);
animation:spin 9s linear infinite;filter:saturate(.9)}
@keyframes spin{to{transform:rotate(360deg)}}
h1{font-size:28px;line-height:1.15;letter-spacing:-.03em;margin:0 0 12px;font-weight:700}
p{color:var(--muted);margin:0 0 28px;max-width:34em}p b{color:var(--text);font-weight:600;word-break:break-all}
.btn{align-self:flex-start;display:inline-block;background:var(--ink);color:var(--on-ink);text-decoration:none;
font-weight:600;padding:12px 22px;border-radius:999px}
.url{margin-top:28px;font-size:13px;color:var(--muted);background:var(--field);padding:10px 12px;border-radius:10px;
word-break:break-all;opacity:.85}
</style></head><body>
<div class="ring"></div><h1>${Html.escapeHtml(copy.title)}</h1><p>${copy.body}</p>$retry
<div class="url">$safeUri</div>
</body></html>"""
        return "data:text/html;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }
}
