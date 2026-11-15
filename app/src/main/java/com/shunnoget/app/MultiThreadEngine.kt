package com.shunnoget.app

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object MultiThreadEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val activeFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun pauseTask(taskId: Long) {
        activeFlags[taskId]?.set(false)
    }

    fun startTask(
        context: Context,
        item: DownloadItem,
        dbHelper: ShunnoGetDbHelper,
        onProgress: (DownloadItem) -> Unit
    ) {
        if (item.wifiOnly && !isWifiConnected(context)) {
            item.status = "SCHEDULED_WIFI"
            dbHelper.insertOrUpdate(item)
            onProgress(item.copy())
            return
        }

        val runningFlag = AtomicBoolean(true)
        activeFlags[item.id] = runningFlag
        item.status = "DOWNLOADING"
        item.errorMessage = null
        dbHelper.insertOrUpdate(item)
        onProgress(item.copy())

        DownloadService.start(context, "Downloading ${item.fileName}", 0)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tempDir = File(context.cacheDir, "shunnoget_parts")
                if (!tempDir.exists()) tempDir.mkdirs()
                val partFile = File(tempDir, "${item.id}_${item.fileName}.part")

                if (item.url.contains(".m3u8", ignoreCase = true)) {
                    downloadHlsStream(context, item, partFile, runningFlag, dbHelper, onProgress)
                } else {
                    downloadMultiThreadedFile(context, item, partFile, runningFlag, dbHelper, onProgress)
                }
            } catch (e: Exception) {
                if (runningFlag.get()) {
                    item.status = "FAILED"
                    item.speedBytesPerSec = 0L
                    item.errorMessage = e.localizedMessage ?: "Network error"
                    dbHelper.insertOrUpdate(item)
                    withContext(Dispatchers.Main) { onProgress(item.copy()) }
                }
            } finally {
                activeFlags.remove(item.id)
                if (activeFlags.isEmpty()) {
                    DownloadService.stop(context)
                }
            }
        }
    }

    private suspend fun downloadMultiThreadedFile(
        context: Context,
        item: DownloadItem,
        partFile: File,
        runningFlag: AtomicBoolean,
        dbHelper: ShunnoGetDbHelper,
        onProgress: (DownloadItem) -> Unit
    ) = coroutineScope {
        // 1. Probe server with HEAD request for Content-Length & Accept-Ranges
        val headReqBuilder = Request.Builder().url(item.url).head()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) ShunnoGet/1.0")
        item.referer?.let { headReqBuilder.header("Referer", it) }
        item.cookie?.let { headReqBuilder.header("Cookie", it) }

        var contentLength = -1L
        var acceptRanges = false

        try {
            client.newCall(headReqBuilder.build()).execute().use { resp ->
                contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                acceptRanges = resp.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
            }
        } catch (_: Exception) {
            // Fallback to GET probe if HEAD is blocked
        }

        item.totalBytes = contentLength
        item.supportsRanges = acceptRanges && contentLength > 512 * 1024L
        dbHelper.insertOrUpdate(item)

        val effectiveThreads = if (item.supportsRanges) item.threadCount.coerceIn(1, 16) else 1
        val totalDownloaded = AtomicLong(if (partFile.exists() && !item.supportsRanges) 0L else partFile.length())

        if (item.supportsRanges && contentLength > 0L) {
            // Pre-allocate target file size safely
            RandomAccessFile(partFile, "rw").use { raf ->
                if (raf.length() != contentLength) {
                    raf.setLength(contentLength)
                }
            }

            val chunkSize = contentLength / effectiveThreads
            val speedTrackerJob = launch {
                var lastBytes = totalDownloaded.get()
                while (runningFlag.get() && isActive) {
                    delay(600)
                    val current = totalDownloaded.get()
                    val speed = ((current - lastBytes) * 1000L / 600L).coerceAtLeast(0L)
                    lastBytes = current
                    item.downloadedBytes = current
                    item.speedBytesPerSec = speed
                    val pct = ((current * 100) / contentLength).toInt()
                    DownloadService.start(context, "${item.fileName} ($pct%)", pct)
                    withContext(Dispatchers.Main) { onProgress(item.copy()) }
                }
            }

            // Launch parallel byte-range coroutines
            val jobs = (0 until effectiveThreads).map { threadIdx ->
                async(Dispatchers.IO) {
                    val startByte = threadIdx * chunkSize
                    val endByte = if (threadIdx == effectiveThreads - 1) contentLength - 1 else (startByte + chunkSize - 1)

                    // Check thread progress checkpoint file
                    val metaFile = File(partFile.parentFile, "${item.id}_t$threadIdx.offset")
                    var currentOffset = if (metaFile.exists()) {
                        metaFile.readText().toLongOrNull()?.coerceIn(startByte, endByte + 1) ?: startByte
                    } else {
                        startByte
                    }

                    if (currentOffset > endByte) return@async

                    val reqBuilder = Request.Builder()
                        .url(item.url)
                        .header("Range", "bytes=$currentOffset-$endByte")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) ShunnoGet/1.0")
                    item.referer?.let { reqBuilder.header("Referer", it) }
                    item.cookie?.let { reqBuilder.header("Cookie", it) }

                    client.newCall(reqBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val body = response.body ?: throw Exception("Empty stream")
                        val buffer = ByteArray(16 * 1024)
                        RandomAccessFile(partFile, "rw").use { raf ->
                            raf.seek(currentOffset)
                            body.byteStream().use { input ->
                                while (runningFlag.get()) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    raf.write(buffer, 0, read)
                                    currentOffset += read
                                    totalDownloaded.addAndGet(read.toLong())
                                    metaFile.writeText(currentOffset.toString())
                                }
                            }
                        }
                    }
                }
            }

            jobs.awaitAll()
            speedTrackerJob.cancel()

            if (!runningFlag.get()) {
                item.status = "PAUSED"
                item.speedBytesPerSec = 0L
                dbHelper.insertOrUpdate(item)
                withContext(Dispatchers.Main) { onProgress(item.copy()) }
                return@coroutineScope
            }

            // Clean up thread offset checkpoints
            (0 until effectiveThreads).forEach { idx ->
                File(partFile.parentFile, "${item.id}_t$idx.offset").delete()
            }
        } else {
            // Single-Stream Fallback for servers without Range headers
            val reqBuilder = Request.Builder().url(item.url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) ShunnoGet/1.0")
            item.referer?.let { reqBuilder.header("Referer", it) }
            item.cookie?.let { reqBuilder.header("Cookie", it) }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val total = response.body?.contentLength() ?: -1L
                item.totalBytes = total
                val buffer = ByteArray(16 * 1024)
                var downloaded = 0L
                var lastTick = System.currentTimeMillis()
                var bytesSinceTick = 0L

                partFile.outputStream().use { output ->
                    response.body?.byteStream()?.use { input ->
                        while (runningFlag.get()) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            bytesSinceTick += read
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 600) {
                                item.downloadedBytes = downloaded
                                item.speedBytesPerSec = (bytesSinceTick * 1000L) / (now - lastTick)
                                lastTick = now
                                bytesSinceTick = 0L
                                withContext(Dispatchers.Main) { onProgress(item.copy()) }
                            }
                        }
                    }
                }
                item.downloadedBytes = downloaded
                if (item.totalBytes <= 0L) item.totalBytes = downloaded
            }

            if (!runningFlag.get()) {
                item.status = "PAUSED"
                item.speedBytesPerSec = 0L
                dbHelper.insertOrUpdate(item)
                withContext(Dispatchers.Main) { onProgress(item.copy()) }
                return@coroutineScope
            }
        }

        // Export completed file to Downloads/ShunnoGet/<Category>
        val savedUri = exportToPublicDownloads(context, partFile, item.fileName, item.category)
        partFile.delete()

        item.downloadedBytes = item.totalBytes.coerceAtLeast(item.downloadedBytes)
        item.status = "COMPLETED"
        item.speedBytesPerSec = 0L
        item.savedUriString = savedUri?.toString()
        dbHelper.insertOrUpdate(item)
        withContext(Dispatchers.Main) { onProgress(item.copy()) }
    }

    private suspend fun downloadHlsStream(
        context: Context,
        item: DownloadItem,
        partFile: File,
        runningFlag: AtomicBoolean,
        dbHelper: ShunnoGetDbHelper,
        onProgress: (DownloadItem) -> Unit
    ) {
        // Fetch m3u8 manifest & concatenate media segments into .ts / .mp4
        val req = Request.Builder().url(item.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) ShunnoGet/1.0")
            .build()
        val manifestText = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val baseUrl = item.url.substringBeforeLast('/') + "/"

        val segmentUrls = manifestText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("http")) it else baseUrl + it }

        if (segmentUrls.isEmpty()) {
            throw Exception("No media segments found in M3U8 playlist")
        }

        var downloaded = 0L
        partFile.outputStream().use { out ->
            segmentUrls.forEachIndexed { index, segUrl ->
                if (!runningFlag.get()) return
                val segReq = Request.Builder().url(segUrl).build()
                client.newCall(segReq).execute().use { resp ->
                    resp.body?.byteStream()?.use { input ->
                        val bytes = input.readBytes()
                        out.write(bytes)
                        downloaded += bytes.size
                        item.downloadedBytes = downloaded
                        item.totalBytes = (downloaded * segmentUrls.size) / (index + 1)
                        withContext(Dispatchers.Main) { onProgress(item.copy()) }
                    }
                }
            }
        }

        if (!runningFlag.get()) {
            item.status = "PAUSED"
            dbHelper.insertOrUpdate(item)
            withContext(Dispatchers.Main) { onProgress(item.copy()) }
            return
        }

        val finalName = item.fileName.replace(".m3u8", ".mp4", ignoreCase = true)
        val savedUri = exportToPublicDownloads(context, partFile, finalName, "Videos")
        partFile.delete()

        item.totalBytes = downloaded
        item.downloadedBytes = downloaded
        item.status = "COMPLETED"
        item.speedBytesPerSec = 0L
        item.savedUriString = savedUri?.toString()
        dbHelper.insertOrUpdate(item)
        withContext(Dispatchers.Main) { onProgress(item.copy()) }
    }

    private fun exportToPublicDownloads(
        context: Context,
        sourceFile: File,
        fileName: String,
        category: String
    ): Uri? {
        if (!sourceFile.exists()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ShunnoGet/$category")
            }
            val destUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (destUri != null) {
                FileInputStream(sourceFile).use { input ->
                    context.contentResolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            }
            destUri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ShunnoGet/$category")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, fileName)
            sourceFile.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        }
    }
}
