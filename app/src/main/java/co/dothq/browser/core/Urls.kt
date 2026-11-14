package co.dothq.browser.core

import android.net.Uri
import java.net.URLEncoder

data class SearchEngine(val id: String, val name: String, val template: String, val home: String) {
    fun searchUrl(query: String): String = template.replace("%s", URLEncoder.encode(query, "UTF-8"))

    /** Pulls the query back out of a results URL so the address bar can show what you searched. */
    fun queryOf(url: String): String? {
        val prefix = template.substringBefore("%s")
        if (!url.startsWith(prefix)) return null
        val key = prefix.substringAfterLast('?').substringAfterLast('&').removeSuffix("=")
        return try {
            Uri.parse(url).getQueryParameter(key)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

object SearchEngines {
    val ALL = listOf(
        SearchEngine("ddg@search.dothq.co", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "duckduckgo.com"),
        SearchEngine("startpage@search.dothq.co", "Startpage", "https://www.startpage.com/do/search?q=%s", "startpage.com"),
        SearchEngine("brave@search.dothq.co", "Brave Search", "https://search.brave.com/search?q=%s", "search.brave.com"),
        SearchEngine("ecosia@search.dothq.co", "Ecosia", "https://www.ecosia.org/search?q=%s", "ecosia.org"),
        SearchEngine("qwant@search.dothq.co", "Qwant", "https://www.qwant.com/?q=%s", "qwant.com"),
        SearchEngine("google@search.dothq.co", "Google", "https://www.google.com/search?q=%s", "google.com"),
        SearchEngine("bing@search.dothq.co", "Bing", "https://www.bing.com/search?q=%s", "bing.com"),
    )
    val DEFAULT = ALL.first()
    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

object Urls {
    const val HOME = "about:blank"
    private val schemes = listOf(
        "http:", "https:", "about:", "view-source:", "file:", "data:", "resource:", "moz-extension:", "blob:",
    )
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""")
    private val hostLike = Regex("""^([a-z0-9-]+\.)+[a-z][a-z0-9-]{1,62}(:\d+)?([/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val localhost = Regex("""^localhost(:\d+)?([/?#].*)?$""", RegexOption.IGNORE_CASE)

    fun isHome(url: String?) = url.isNullOrEmpty() || url == HOME

    /** Turns whatever was typed into something to load: a URL as-is, a host upgraded to https, or a search. */
    fun fromInput(input: String, engine: SearchEngine): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        if (schemes.any { lower.startsWith(it) } && !t.contains(' ')) return t
        if (!t.contains(' ')) {
            if (localhost.matches(t) || ipv4.matches(t)) return "http://$t"
            if (hostLike.matches(t)) return "https://$t"
        }
        return engine.searchUrl(t)
    }

    fun host(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            (Uri.parse(url).host ?: "").removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    /** Host and the rest, for the two-tone address display. */
    fun split(url: String): Pair<String, String> {
        val uri = try { Uri.parse(url) } catch (_: Exception) { null }
        val host = uri?.host
        if (uri == null || host.isNullOrEmpty()) return url to ""
        val rest = buildString {
            append(uri.encodedPath ?: "")
            uri.encodedQuery?.let { append('?').append(it) }
            uri.encodedFragment?.let { append('#').append(it) }
        }
        return host.removePrefix("www.") to (if (rest == "/") "" else rest)
    }

    fun isWeb(url: String?) = url != null && (url.startsWith("http://") || url.startsWith("https://"))

    /** A friendly label for a page without a title. */
    fun pretty(url: String): String {
        val (h, rest) = split(url)
        return if (h.isNotEmpty()) h + rest.take(40) else url
    }
}
