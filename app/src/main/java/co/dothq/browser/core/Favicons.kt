package co.dothq.browser.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Site icons. Fetched straight from the site you are already visiting (never via a third-party
 * icon service), cached on disk, and never fetched for private tabs.
 */
object Favicons {
    private val memory = LruCache<String, Bitmap>(96)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val inflight = HashSet<String>()
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private const val RETRY_MS = 3L * 24 * 60 * 60 * 1000

    private fun dir(c: Context) = File(c.cacheDir, "favicons").apply { mkdirs() }
    private fun key(host: String) = host.removePrefix("www.").lowercase()

    fun memoryCached(host: String): Bitmap? = if (host.isEmpty()) null else memory.get(key(host))

    /** Returns an icon from memory/disk immediately if there is one; otherwise fetches (when allowed). */
    fun get(c: Context, host: String, allowNetwork: Boolean, callback: (Bitmap?) -> Unit) {
        if (host.isEmpty()) { callback(null); return }
        val k = key(host)
        memory.get(k)?.let { callback(it); return }
        val ctx = c.applicationContext
        synchronized(inflight) {
            waiting.getOrPut(k) { mutableListOf() }.add(callback)
            if (!inflight.add(k)) return
        }
        pool.execute {
            var bmp: Bitmap? = null
            val file = File(dir(ctx), "$k.png")
            val miss = File(dir(ctx), "$k.none")
            if (file.exists()) bmp = BitmapFactory.decodeFile(file.path)
            if (bmp == null && allowNetwork && !(miss.exists() && System.currentTimeMillis() - miss.lastModified() < RETRY_MS)) {
                bmp = download("https://$host/apple-touch-icon.png") ?: download("https://$host/favicon.ico")
                if (bmp != null) {
                    try { FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
                    miss.delete()
                } else {
                    try { miss.writeText("") ; miss.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
                }
            }
            if (bmp != null) memory.put(k, bmp)
            val result = bmp
            main.post {
                val cbs = synchronized(inflight) { inflight.remove(k); waiting.remove(k) ?: mutableListOf() }
                cbs.forEach { it(result) }
            }
        }
    }

    private fun download(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/png,image/x-icon,image/*;q=0.8")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DotBrowser")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else {
                val bytes = conn.inputStream.use { it.readNBytesCompat(512 * 1024) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width >= 16 && it.height >= 16 }
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    fun clear(c: Context) {
        memory.evictAll()
        dir(c).listFiles()?.forEach { it.delete() }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
