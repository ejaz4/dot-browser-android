package co.dothq.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the address bar does with what you type. */
class UrlsTest {
    private val ddg = SearchEngines.DEFAULT

    @Test fun blankInputDoesNothing() {
        assertNull(Urls.fromInput("   ", ddg))
    }

    @Test fun barePlainWordsAreSearched() {
        assertEquals("https://duckduckgo.com/?q=dot+browser", Urls.fromInput("dot browser", ddg))
        assertEquals("https://duckduckgo.com/?q=wikipedia", Urls.fromInput("wikipedia", ddg))
    }

    @Test fun hostsAreUpgradedToHttps() {
        assertEquals("https://dothq.org", Urls.fromInput("dothq.org", ddg))
        assertEquals("https://en.wikipedia.org/wiki/Dot", Urls.fromInput("en.wikipedia.org/wiki/Dot", ddg))
    }

    @Test fun explicitSchemesAreKept() {
        assertEquals("http://example.com", Urls.fromInput("http://example.com", ddg))
        assertEquals("about:config", Urls.fromInput("about:config", ddg))
        assertEquals("view-source:https://dothq.org", Urls.fromInput("view-source:https://dothq.org", ddg))
    }

    @Test fun localAddressesUsePlainHttp() {
        assertEquals("http://localhost:8080", Urls.fromInput("localhost:8080", ddg))
        assertEquals("http://192.168.1.1", Urls.fromInput("192.168.1.1", ddg))
    }

    @Test fun questionsWithDotsAreStillSearches() {
        assertEquals("https://duckduckgo.com/?q=what+is+node.js", Urls.fromInput("what is node.js", ddg))
    }

    @Test fun searchEnginesEncodeQueries() {
        val google = SearchEngines.byId("google@search.dothq.co")
        assertEquals("https://www.google.com/search?q=a%26b", google.searchUrl("a&b"))
    }
}
