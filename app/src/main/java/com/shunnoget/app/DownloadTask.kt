package com.shunnoget.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class DownloadItem(
    val id: Long,
    var url: String,
    val fileName: String,
    var totalBytes: Long,
    var downloadedBytes: Long,
    var status: String, // "QUEUED", "DOWNLOADING", "PAUSED", "COMPLETED", "FAILED", "SCHEDULED_WIFI"
    var speedBytesPerSec: Long,
    var threadCount: Int,
    val category: String,
    var supportsRanges: Boolean = true,
    var wifiOnly: Boolean = false,
    var referer: String? = null,
    var cookie: String? = null,
    var savedUriString: String? = null,
    var errorMessage: String? = null
)

class ShunnoGetDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        const val DATABASE_NAME = "shunnoget.db"
        const val DATABASE_VERSION = 1
        const val TABLE_DOWNLOADS = "downloads"
        const val COL_ID = "id"
        const val COL_URL = "url"
        const val COL_FILENAME = "filename"
        const val COL_TOTAL = "total_bytes"
        const val COL_DOWNLOADED = "downloaded_bytes"
        const val COL_STATUS = "status"
        const val COL_THREADS = "threads"
        const val COL_CATEGORY = "category"
        const val COL_RANGES = "supports_ranges"
        const val COL_WIFI_ONLY = "wifi_only"
        const val COL_REFERER = "referer"
        const val COL_COOKIE = "cookie"
        const val COL_SAVED_URI = "saved_uri"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_DOWNLOADS (" +
                "$COL_ID INTEGER PRIMARY KEY, " +
                "$COL_URL TEXT, " +
                "$COL_FILENAME TEXT, " +
                "$COL_TOTAL INTEGER, " +
                "$COL_DOWNLOADED INTEGER, " +
                "$COL_STATUS TEXT, " +
                "$COL_THREADS INTEGER, " +
                "$COL_CATEGORY TEXT, " +
                "$COL_RANGES INTEGER, " +
                "$COL_WIFI_ONLY INTEGER, " +
                "$COL_REFERER TEXT, " +
                "$COL_COOKIE TEXT, " +
                "$COL_SAVED_URI TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DOWNLOADS")
        onCreate(db)
    }

    fun insertOrUpdate(item: DownloadItem) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, item.id)
            put(COL_URL, item.url)
            put(COL_FILENAME, item.fileName)
            put(COL_TOTAL, item.totalBytes)
            put(COL_DOWNLOADED, item.downloadedBytes)
            put(COL_STATUS, item.status)
            put(COL_THREADS, item.threadCount)
            put(COL_CATEGORY, item.category)
            put(COL_RANGES, if (item.supportsRanges) 1 else 0)
            put(COL_WIFI_ONLY, if (item.wifiOnly) 1 else 0)
            put(COL_REFERER, item.referer)
            put(COL_COOKIE, item.cookie)
            put(COL_SAVED_URI, item.savedUriString)
        }
        db.insertWithOnConflict(TABLE_DOWNLOADS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllDownloads(): List<DownloadItem> {
        val list = mutableListOf<DownloadItem>()
        val db = readableDatabase
        val cursor = db.query(TABLE_DOWNLOADS, null, null, null, null, null, "$COL_ID DESC")
        cursor.use { c ->
            while (c.moveToNext()) {
                val savedStatus = c.getString(c.getColumnIndexOrThrow(COL_STATUS)) ?: "PAUSED"
                val restoredStatus = if (savedStatus == "DOWNLOADING") "PAUSED" else savedStatus
                list.add(
                    DownloadItem(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                        url = c.getString(c.getColumnIndexOrThrow(COL_URL)) ?: "",
                        fileName = c.getString(c.getColumnIndexOrThrow(COL_FILENAME)) ?: "file.bin",
                        totalBytes = c.getLong(c.getColumnIndexOrThrow(COL_TOTAL)),
                        downloadedBytes = c.getLong(c.getColumnIndexOrThrow(COL_DOWNLOADED)),
                        status = restoredStatus,
                        speedBytesPerSec = 0L,
                        threadCount = c.getInt(c.getColumnIndexOrThrow(COL_THREADS)),
                        category = c.getString(c.getColumnIndexOrThrow(COL_CATEGORY)) ?: "Documents",
                        supportsRanges = c.getInt(c.getColumnIndexOrThrow(COL_RANGES)) == 1,
                        wifiOnly = c.getInt(c.getColumnIndexOrThrow(COL_WIFI_ONLY)) == 1,
                        referer = c.getString(c.getColumnIndexOrThrow(COL_REFERER)),
                        cookie = c.getString(c.getColumnIndexOrThrow(COL_COOKIE)),
                        savedUriString = c.getString(c.getColumnIndexOrThrow(COL_SAVED_URI))
                    )
                )
            }
        }
        return list
    }

    fun deleteDownload(id: Long) {
        writableDatabase.delete(TABLE_DOWNLOADS, "$COL_ID = ?", arrayOf(id.toString()))
    }
}
