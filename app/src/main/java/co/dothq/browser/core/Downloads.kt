package co.dothq.browser.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/** Saves downloads and screenshots into the shared Downloads / Pictures collections. */
object Downloads {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Saved(val uri: Uri, val name: String, val mime: String)

    fun fileName(response: WebResponse): Pair<String, String> {
        val headers = response.headers.mapKeys { it.key.lowercase() }
        val disposition = headers["content-disposition"]
        var mime = headers["content-type"]?.substringBefore(';')?.trim()?.ifEmpty { null }
        var name = URLUtil.guessFileName(response.uri, disposition, mime)
        if (mime == null || mime == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: mime ?: "application/octet-stream"
        }
        name = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return name to mime
    }

    fun save(context: Context, response: WebResponse, done: (Saved?) -> Unit) {
        val body = response.body ?: run { done(null); return }
        val (name, mime) = fileName(response)
        val ctx = context.applicationContext
        pool.execute {
            val result = try {
                write(ctx, name, mime, Environment.DIRECTORY_DOWNLOADS) { out -> body.use { it.copyTo(out) } }
            } catch (_: Exception) {
                null
            }
            main.post { done(result) }
        }
    }

    fun saveBitmap(context: Context, bitmap: Bitmap, title: String, done: (Saved?) -> Unit) {
        val ctx = context.applicationContext
        val safe = title.replace(Regex("""[^\w\- ]+"""), "").trim().take(40).ifEmpty { "Page" }
        val name = "Dot $safe ${System.currentTimeMillis() / 1000}.png"
        pool.execute {
            val result = try {
                write(ctx, name, "image/png", Environment.DIRECTORY_PICTURES) { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (_: Exception) {
                null
            }
            main.post { done(result) }
        }
    }

    private fun write(ctx: Context, name: String, mime: String, dir: String, writer: (OutputStream) -> Unit): Saved? {
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (dir == Environment.DIRECTORY_PICTURES)
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (dir == Environment.DIRECTORY_PICTURES) "$dir/Dot" else dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use(writer) ?: throw IllegalStateException()
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return Saved(uri, name, mime)
        }
        // Android 8–9: app-specific external storage, shared through our FileProvider.
        val folder = ctx.getExternalFilesDir(dir) ?: File(ctx.filesDir, dir)
        folder.mkdirs()
        var file = File(folder, name)
        var i = 1
        while (file.exists()) file = File(folder, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.', "bin")).also { i++ }
        FileOutputStream(file).use(writer)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        return Saved(uri, file.name, mime)
    }

    fun openIntent(saved: Saved): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(saved.uri, saved.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
