package co.dothq.browser.ai

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Downloads, verifies and stores models. Files live in app-private storage and are only ever
 * read by the on-device engine; nothing about your use of the assistant leaves the phone.
 */
class ModelManager(private val context: Context) {
    enum class State { NOT_DOWNLOADED, DOWNLOADING, VERIFYING, READY, FAILED }

    data class Status(val state: State, val downloaded: Long = 0, val total: Long = 0, val error: String? = null) {
        val progress get() = if (total > 0) downloaded.toFloat() / total else 0f
    }

    private val prefs = context.getSharedPreferences("dot.ai", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()
    private val verifying = HashSet<String>()
    private val failures = HashMap<String, String>()
    private var polling = false

    val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit { putBoolean("enabled", v) }; notifyChanged() }

    var activeId: String?
        get() = prefs.getString("active", null)
        set(v) { prefs.edit { putString("active", v) }; notifyChanged() }

    /** Use screen vision when the model supports it (bigger download, slower per look). */
    var visionWanted: Boolean
        get() = prefs.getBoolean("vision", true)
        set(v) { prefs.edit { putBoolean("vision", v) }; notifyChanged() }

    val active: ModelSpec? get() = ModelCatalog.byId(activeId)?.takeIf { isReady(it) }

    val totalRamGb: Double by lazy {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem / 1_073_741_824.0
    }

    val freeBytes: Long get() = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun fileFor(f: ModelFile) = File(dir, "${f.repo.replace('/', '_')}__${f.name}")

    private fun verifiedKey(f: ModelFile) = "ok:${f.sha256}"

    private fun isFileReady(f: ModelFile) = fileFor(f).let { it.exists() && it.length() == f.bytes } && prefs.getBoolean(verifiedKey(f), false)

    fun isReady(m: ModelSpec): Boolean = isFileReady(m.file) && (m.vision == null || !visionWanted || isFileReady(m.vision))

    /** The vision projector to load with [m], if it's downloaded and wanted. */
    fun visionPath(m: ModelSpec): String? = m.vision?.takeIf { visionWanted && isFileReady(it) }?.let { fileFor(it).path }

    fun filesFor(m: ModelSpec) = listOfNotNull(m.file, m.vision?.takeIf { visionWanted })

    fun status(m: ModelSpec): Status {
        if (isReady(m)) return Status(State.READY, m.totalBytes, m.totalBytes)
        failures[m.id]?.let { return Status(State.FAILED, error = it) }
        if (filesFor(m).any { verifying.contains(it.sha256) }) return Status(State.VERIFYING)
        var done = 0L
        var total = 0L
        var anyRunning = false
        for (f in filesFor(m)) {
            total += f.bytes
            if (isFileReady(f)) { done += f.bytes; continue }
            val id = prefs.getLong("dl:${f.sha256}", -1)
            if (id < 0) continue
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    if (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING || st == DownloadManager.STATUS_PAUSED) anyRunning = true
                    if (st == DownloadManager.STATUS_SUCCESSFUL) { anyRunning = true; done += f.bytes } else done += got
                }
            }
        }
        return if (anyRunning) Status(State.DOWNLOADING, done, total) else Status(State.NOT_DOWNLOADED, 0, total)
    }

    fun download(m: ModelSpec): String? {
        val need = filesFor(m).filter { !isFileReady(it) }.sumOf { it.bytes }
        if (need > freeBytes - 300_000_000) return "Not enough space: ${ModelCatalog.formatBytes(need)} needed"
        failures.remove(m.id)
        for (f in filesFor(m)) {
            if (isFileReady(f)) continue
            val existing = prefs.getLong("dl:${f.sha256}", -1)
            if (existing >= 0 && isActive(existing)) continue
            val target = fileFor(f)
            target.delete()
            val req = DownloadManager.Request(Uri.parse(f.url))
                .setTitle("${m.name}${if (f === m.vision) " vision" else ""}")
                .setDescription("Dot assistant model")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setDestinationUri(Uri.fromFile(target))
            val id = dm.enqueue(req)
            prefs.edit { putLong("dl:${f.sha256}", id) }
        }
        startPolling()
        notifyChanged()
        return null
    }

    private fun isActive(id: Long): Boolean = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
        c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)).let {
            it == DownloadManager.STATUS_RUNNING || it == DownloadManager.STATUS_PENDING || it == DownloadManager.STATUS_PAUSED
        }
    } ?: false

    fun cancel(m: ModelSpec) {
        for (f in filesFor(m)) {
            val id = prefs.getLong("dl:${f.sha256}", -1)
            if (id >= 0) dm.remove(id)
            prefs.edit { remove("dl:${f.sha256}") }
        }
        notifyChanged()
    }

    fun delete(m: ModelSpec) {
        cancel(m)
        listOfNotNull(m.file, m.vision).forEach { f ->
            fileFor(f).delete()
            prefs.edit { remove(verifiedKey(f)) }
        }
        if (activeId == m.id) activeId = null
        notifyChanged()
    }

    fun usedBytes(m: ModelSpec): Long = listOfNotNull(m.file, m.vision).sumOf { f -> fileFor(f).takeIf { it.exists() }?.length() ?: 0 }

    fun addListener(l: () -> Unit) { listeners += l; startPolling() }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    /** Watches downloads, verifies finished files, reports progress. */
    private fun startPolling() {
        if (polling) return
        polling = true
        main.post(object : Runnable {
            override fun run() {
                var active = false
                for (m in ModelCatalog.ALL) {
                    for (f in filesFor(m)) {
                        if (isFileReady(f)) continue
                        val id = prefs.getLong("dl:${f.sha256}", -1)
                        if (id < 0) continue
                        active = true
                        val st = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
                        } ?: -1
                        when (st) {
                            DownloadManager.STATUS_SUCCESSFUL -> verify(m, f)
                            DownloadManager.STATUS_FAILED, -1 -> {
                                prefs.edit { remove("dl:${f.sha256}") }
                                failures[m.id] = "Download failed. Check your connection and try again."
                            }
                        }
                    }
                }
                notifyChanged()
                if (active || verifying.isNotEmpty()) main.postDelayed(this, 700) else polling = false
            }
        })
    }

    private fun verify(m: ModelSpec, f: ModelFile) {
        if (!verifying.add(f.sha256)) return
        io.execute {
            val file = fileFor(f)
            val ok = file.exists() && file.length() == f.bytes && sha256(file) == f.sha256
            main.post {
                verifying.remove(f.sha256)
                prefs.edit {
                    remove("dl:${f.sha256}")
                    putBoolean(verifiedKey(f), ok)
                }
                if (!ok) {
                    file.delete()
                    failures[m.id] = "The download was damaged. Please try again."
                } else if (isReady(m) && active == null) {
                    activeId = m.id
                }
                notifyChanged()
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
