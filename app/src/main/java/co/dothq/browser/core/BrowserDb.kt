package co.dothq.browser.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Site(val url: String, val title: String, val time: Long, val visits: Int = 0)

/** History and bookmarks. Private tabs never write here. */
class BrowserDb(context: Context) : SQLiteOpenHelper(context, "dot.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history (
                url TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                visits INTEGER NOT NULL DEFAULT 0,
                last_visit INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX history_last ON history(last_visit DESC)")
        db.execSQL(
            """CREATE TABLE bookmarks (
                url TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun recordVisit(url: String, title: String?) {
        // No UPSERT: Android 8–10 ship SQLite versions that predate it.
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE history SET visits = visits + 1, last_visit = ? WHERE url = ?", arrayOf<Any>(now, url))
            if (!title.isNullOrBlank()) {
                db.execSQL("UPDATE history SET title = ? WHERE url = ?", arrayOf<Any>(title, url))
            }
            db.insertWithOnConflict(
                "history", null,
                ContentValues().apply {
                    put("url", url); put("title", title ?: ""); put("visits", 1); put("last_visit", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        writableDatabase.update("history", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url))
        writableDatabase.update("bookmarks", ContentValues().apply { put("title", title) }, "url = ? AND title = ''", arrayOf(url))
    }

    fun history(limit: Int = 500, filter: String? = null): List<Site> {
        val (where, args) = filterClause(filter)
        return readableDatabase.rawQuery(
            "SELECT url, title, last_visit, visits FROM history $where ORDER BY last_visit DESC LIMIT $limit", args
        ).use { c ->
            buildList { while (c.moveToNext()) add(Site(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))) }
        }
    }

    /** Frecency-ish: often + recently visited first. */
    fun topSites(limit: Int): List<Site> = readableDatabase.rawQuery(
        """SELECT url, title, last_visit, visits FROM history
           WHERE url LIKE 'http%'
           ORDER BY (visits * 1.0) / (1 + (? - last_visit) / 86400000.0) DESC LIMIT ?""",
        arrayOf(System.currentTimeMillis().toString(), (limit * 4).toString())
    ).use { c ->
        // One entry per host keeps the shortcut grid varied.
        val seen = HashSet<String>()
        buildList {
            while (c.moveToNext() && size < limit) {
                val url = c.getString(0)
                if (seen.add(Urls.host(url))) add(Site(url, c.getString(1), c.getLong(2), c.getInt(3)))
            }
        }
    }

    fun suggest(query: String, limit: Int = 6): List<Site> {
        val like = "%${query.replace("%", "").replace("_", "")}%"
        return readableDatabase.rawQuery(
            """SELECT url, title, last_visit, visits FROM history
               WHERE url LIKE ? OR title LIKE ?
               ORDER BY (url LIKE ?) DESC, visits DESC, last_visit DESC LIMIT ?""",
            arrayOf(like, like, "%://$query%".replace("%%", "%"), limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(Site(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))) }
        }
    }

    fun deleteHistory(url: String) {
        writableDatabase.delete("history", "url = ?", arrayOf(url))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    fun bookmarks(filter: String? = null): List<Site> {
        val (where, args) = filterClause(filter)
        return readableDatabase.rawQuery("SELECT url, title, created FROM bookmarks $where ORDER BY created DESC", args).use { c ->
            buildList { while (c.moveToNext()) add(Site(c.getString(0), c.getString(1), c.getLong(2))) }
        }
    }

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url)).use { it.moveToFirst() }

    fun addBookmark(url: String, title: String) {
        writableDatabase.insertWithOnConflict(
            "bookmarks", null,
            ContentValues().apply {
                put("url", url); put("title", title); put("created", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    private fun filterClause(filter: String?): Pair<String, Array<String>?> {
        if (filter.isNullOrBlank()) return "" to null
        val like = "%${filter.trim()}%"
        return "WHERE url LIKE ? OR title LIKE ?" to arrayOf(like, like)
    }
}
