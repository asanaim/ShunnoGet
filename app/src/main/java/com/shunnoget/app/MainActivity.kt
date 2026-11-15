package com.shunnoget.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {

    private val dbHelper by lazy { ShunnoGetDbHelper(this) }

    // Built-in lightweight Ad & Tracker host blocklist
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingSharedUrl = extractIncomingUrl(intent)

        setContent {
            val appleBlue = Color(0xFF0A84FF)
            val appleDeepBlue = Color(0xFF0040DD)
            val appleGreen = Color(0xFF30D158)
            val appleOrange = Color(0xFFFF9F0A)
            val appleRed = Color(0xFFFF3B30)
            val appleBg = Color(0xFFF2F2F7)

            var selectedTab by remember { mutableStateOf(if (incomingSharedUrl != null) 2 else 0) }
            var selectedCategory by remember { mutableStateOf("All") }
            val downloadList = remember { mutableStateListOf<DownloadItem>() }

            // Add Link / Batch / Scheduler state
            var urlInput by remember { mutableStateOf(incomingSharedUrl.orEmpty()) }
            var threadCount by remember { mutableIntStateOf(8) }
            var wifiOnlySchedule by remember { mutableStateOf(false) }

            // Link Refresh Dialog state
            var linkToRefresh by remember { mutableStateOf<DownloadItem?>(null) }
            var refreshedUrlText by remember { mutableStateOf("") }

            // Browser & Media Sniffer state
            var browserUrl by remember { mutableStateOf("https://archive.org/details/audio") }
            var adBlockEnabled by remember { mutableStateOf(true) }
            var blockedAdsCount by remember { mutableIntStateOf(0) }
            val detectedStreams = remember { mutableStateListOf<String>() }
            var webViewRef by remember { mutableStateOf<WebView?>(null) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val saved = dbHelper.getAllDownloads()
                    withContext(Dispatchers.Main) {
                        downloadList.clear()
                        downloadList.addAll(saved)
                    }
                }
            }

            fun updateItemInList(updated: DownloadItem) {
                val idx = downloadList.indexOfFirst { it.id == updated.id }
                if (idx != -1) {
                    downloadList[idx] = updated
                }
            }

            fun enqueueUrl(
                rawUrl: String,
                threads: Int = threadCount,
                wifiOnly: Boolean = wifiOnlySchedule,
                referer: String? = null,
                cookie: String? = null
            ) {
                val clean = rawUrl.trim()
                if (!clean.startsWith("http://") && !clean.startsWith("https://")) return

                val uri = Uri.parse(clean)
                val segment = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?') ?: "download.bin"
                val fileName = if (segment.contains('.')) segment else "${segment}_${System.currentTimeMillis() % 1000}.bin"

                val newItem = DownloadItem(
                    id = System.currentTimeMillis() + (0..999).random(),
                    url = clean,
                    fileName = fileName,
                    totalBytes = -1L,
                    downloadedBytes = 0L,
                    status = if (wifiOnly) "SCHEDULED_WIFI" else "DOWNLOADING",
                    speedBytesPerSec = 0L,
                    threadCount = threads,
                    category = detectCategory(fileName),
                    wifiOnly = wifiOnly,
                    referer = referer,
                    cookie = cookie
                )
                downloadList.add(0, newItem)
                dbHelper.insertOrUpdate(newItem)

                MultiThreadEngine.startTask(
                    context = this@MainActivity,
                    item = newItem,
                    dbHelper = dbHelper,
                    onProgress = { updated -> updateItemInList(updated) }
                )
            }

            fun enqueueBatchOrSingle(input: String) {
                val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
                var queued = 0
                for (line in lines) {
                    // Expand wildcard ranges like track_[01-12].flac
                    val wildcardMatch = Regex("\\[(\\d+)-(\\d+)]").find(line)
                    if (wildcardMatch != null) {
                        val startStr = wildcardMatch.groupValues[1]
                        val endStr = wildcardMatch.groupValues[2]
                        val start = startStr.toIntOrNull() ?: 1
                        val end = endStr.toIntOrNull() ?: start
                        val padLen = startStr.length
                        for (i in start..end.coerceAtMost(start + 100)) {
                            val num = i.toString().padStart(padLen, '0')
                            val expandedUrl = line.replace(wildcardMatch.value, num)
                            enqueueUrl(expandedUrl)
                            queued++
                        }
                    } else {
                        enqueueUrl(line)
                        queued++
                    }
                }
                if (queued > 0) {
                    Toast.makeText(this@MainActivity, "Queued $queued download(s)", Toast.LENGTH_SHORT).show()
                }
            }

            val categories = listOf("All", "Videos", "Lossless Audio", "Archives", "APKs", "Documents")
            val filteredDownloads = remember(downloadList.size, selectedCategory, downloadList.toList()) {
                if (selectedCategory == "All") downloadList
                else downloadList.filter { it.category == selectedCategory }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = appleBg) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Apple Cupertino Header with Sculpted "0↓" Squircle Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.linearGradient(listOf(appleBlue, appleDeepBlue))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("0↓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Shunno Get", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text("Multi-Threaded IDM Engine • HLS • FLAC/ALAC", fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Cupertino Segmented Navigation Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NavPill("Queue (${downloadList.size})", selectedTab == 0, Modifier.weight(1f)) { selectedTab = 0 }
                        NavPill("Sniffer Browser", selectedTab == 1, Modifier.weight(1f)) { selectedTab = 1 }
                        NavPill("+ Add Link", selectedTab == 2, Modifier.weight(1f)) { selectedTab = 2 }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    when (selectedTab) {
                        0 -> {
                            // Category Filter Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                categories.forEach { cat ->
                                    FilterChip(
                                        selected = selectedCategory == cat,
                                        onClick = { selectedCategory = cat },
                                        label = { Text(cat, fontSize = 12.sp) },
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (filteredDownloads.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "No downloads in $selectedCategory.\nTap '+ Add Link' or browse in 'Sniffer Browser'.",
                                        color = Color.Gray,
                                        fontSize = 14.sp
                                    )
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(filteredDownloads, key = { it.id }) { item ->
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color.White),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = item.fileName,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 15.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = Color.Black
                                                        )
                                                        val badge = buildString {
                                                            append(item.category)
                                                            append(" • ")
                                                            append(if (item.supportsRanges) "${item.threadCount} Threads" else "1 Stream")
                                                            append(" • ")
                                                            append(item.status)
                                                        }
                                                        Text(
                                                            text = badge,
                                                            fontSize = 12.sp,
                                                            color = when (item.status) {
                                                                "COMPLETED" -> appleGreen
                                                                "FAILED" -> appleRed
                                                                "PAUSED", "SCHEDULED_WIFI" -> appleOrange
                                                                else -> appleBlue
                                                            }
                                                        )
                                                    }
                                                    Text(
                                                        text = formatSizeProgress(item.downloadedBytes, item.totalBytes),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = Color.Gray
                                                    )
                                                }

                                                Spacer(modifier = Modifier.height(8.dp))

                                                val progress = when {
                                                    item.status == "COMPLETED" -> 1f
                                                    item.totalBytes > 0 -> (item.downloadedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                                                    else -> 0f
                                                }
                                                LinearProgressIndicator(
                                                    progress = { progress },
                                                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                                                    color = if (item.status == "COMPLETED") appleGreen else appleBlue,
                                                    trackColor = Color(0xFFE5E5EA)
                                                )

                                                Spacer(modifier = Modifier.height(8.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val telemetry = when (item.status) {
                                                        "DOWNLOADING" -> "⚡ ${formatSpeed(item.speedBytesPerSec)} • ETA ${formatEta(item.downloadedBytes, item.totalBytes, item.speedBytesPerSec)}"
                                                        "SCHEDULED_WIFI" -> "Waiting for Wi-Fi connection"
                                                        "FAILED" -> item.errorMessage ?: "Connection interrupted"
                                                        "COMPLETED" -> "Saved to Downloads/ShunnoGet/${item.category}"
                                                        else -> "Paused at ${formatBytes(item.downloadedBytes)}"
                                                    }
                                                    Text(
                                                        text = telemetry,
                                                        fontSize = 11.sp,
                                                        color = Color.Gray,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )

                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        if (item.status == "DOWNLOADING") {
                                                            OutlinedButton(
                                                                onClick = { MultiThreadEngine.pauseTask(item.id) },
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                                shape = RoundedCornerShape(8.dp)
                                                            ) {
                                                                Text("Pause", fontSize = 12.sp, color = appleOrange)
                                                            }
                                                        } else if (item.status != "COMPLETED") {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    item.wifiOnly = false
                                                                    MultiThreadEngine.startTask(
                                                                        context = this@MainActivity,
                                                                        item = item,
                                                                        dbHelper = dbHelper,
                                                                        onProgress = { updated -> updateItemInList(updated) }
                                                                    )
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                                shape = RoundedCornerShape(8.dp)
                                                            ) {
                                                                Text("Resume", fontSize = 12.sp, color = appleBlue)
                                                            }
                                                            OutlinedButton(
                                                                onClick = {
                                                                    linkToRefresh = item
                                                                    refreshedUrlText = item.url
                                                                },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                shape = RoundedCornerShape(8.dp)
                                                            ) {
                                                                Text("Fix Link", fontSize = 11.sp, color = Color.DarkGray)
                                                            }
                                                        }
                                                        TextButton(
                                                            onClick = {
                                                                MultiThreadEngine.pauseTask(item.id)
                                                                dbHelper.deleteDownload(item.id)
                                                                downloadList.remove(item)
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 6.dp)
                                                        ) {
                                                            Text("✕", fontSize = 13.sp, color = appleRed)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            // Built-in Ad-Blocking Browser & HLS/FLAC/MP4 Media Sniffer
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = browserUrl,
                                        onValueChange = { browserUrl = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        placeholder = { Text("Enter URL to browse & sniff streams...") }
                                    )
                                    Button(
                                        onClick = {
                                            val target = if (browserUrl.startsWith("http")) browserUrl else "https://$browserUrl"
                                            browserUrl = target
                                            detectedStreams.clear()
                                            webViewRef?.loadUrl(target)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = appleBlue),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Go")
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterChip(
                                        selected = adBlockEnabled,
                                        onClick = { adBlockEnabled = !adBlockEnabled },
                                        label = { Text("🛡️ AdBlock (${blockedAdsCount} blocked)", fontSize = 12.sp) }
                                    )
                                    if (detectedStreams.isNotEmpty()) {
                                        TextButton(onClick = { detectedStreams.clear() }) {
                                            Text("Clear (${detectedStreams.size})", fontSize = 12.sp, color = Color.Gray)
                                        }
                                    }
                                }

                                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))) {
                                    AndroidView(
                                        factory = { ctx ->
                                            WebView(ctx).apply {
                                                layoutParams = ViewGroup.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldInterceptRequest(
                                                        view: WebView?,
                                                        request: WebResourceRequest?
                                                    ): WebResourceResponse? {
                                                        val reqUrl = request?.url?.toString().orEmpty()
                                                        val host = request?.url?.host.orEmpty().lowercase()

                                                        if (adBlockEnabled && blockedHosts.any { host.endsWith(it) }) {
                                                            blockedAdsCount++
                                                            return WebResourceResponse(
                                                                "text/plain",
                                                                "utf-8",
                                                                ByteArrayInputStream(ByteArray(0))
                                                            )
                                                        }

                                                        val lower = reqUrl.substringBefore('?').lowercase()
                                                        if (lower.endsWith(".mp4") || lower.endsWith(".m3u8") ||
                                                            lower.endsWith(".flac") || lower.endsWith(".m4a") ||
                                                            lower.endsWith(".mp3") || lower.endsWith(".zip") ||
                                                            lower.endsWith(".apk") || lower.endsWith(".pdf")
                                                        ) {
                                                            if (!detectedStreams.contains(reqUrl)) {
                                                                detectedStreams.add(0, reqUrl)
                                                            }
                                                        }
                                                        return super.shouldInterceptRequest(view, request)
                                                    }
                                                }
                                                loadUrl(browserUrl)
                                                webViewRef = this
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    // Floating Frosted Media Pill Badge
                                    detectedStreams.firstOrNull()?.let { topStream ->
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.88f),
                                            shape = RoundedCornerShape(22.dp),
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(14.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "🎯 ${detectedStreams.size} Media/File Stream(s) Detected",
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        text = topStream.substringAfterLast('/').substringBefore('?'),
                                                        color = Color.LightGray,
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Button(
                                                    onClick = {
                                                        val cookies = CookieManager.getInstance().getCookie(browserUrl)
                                                        enqueueUrl(
                                                            rawUrl = topStream,
                                                            referer = browserUrl,
                                                            cookie = cookies
                                                        )
                                                        selectedTab = 0
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = appleGreen),
                                                    shape = RoundedCornerShape(14.dp),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                                ) {
                                                    Text("Download", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            // Add Link, Wildcard Batch & Wi-Fi Scheduler Tab
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text("Direct URL, Wildcard Batch & Lossless Grabber", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                OutlinedTextField(
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                    label = { Text("Paste URL(s), FLAC/ALAC, MP4, M3U8, or track_[01-12].flac") },
                                    shape = RoundedCornerShape(12.dp),
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Column {
                                    Text(
                                        text = "Parallel Connections: $threadCount Threads (Auto-fallback if server lacks Range)",
                                        fontSize = 13.sp,
                                        color = Color.DarkGray
                                    )
                                    Slider(
                                        value = threadCount.toFloat(),
                                        onValueChange = { threadCount = it.toInt() },
                                        valueRange = 1f..16f,
                                        steps = 14
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Schedule for Wi-Fi Only", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text("Hold download until connected to unmetered Wi-Fi", fontSize = 12.sp, color = Color.Gray)
                                    }
                                    Switch(
                                        checked = wifiOnlySchedule,
                                        onCheckedChange = { wifiOnlySchedule = it }
                                    )
                                }

                                Button(
                                    onClick = {
                                        val target = urlInput
                                        urlInput = ""
                                        enqueueBatchOrSingle(target)
                                        selectedTab = 0
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = appleBlue),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                ) {
                                    Text(
                                        text = if (wifiOnlySchedule) "Schedule Wi-Fi Download" else "Start Accelerated Download",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Refresh Expired Link Dialog (Resumes .part file from exact byte offset)
                linkToRefresh?.let { targetItem ->
                    AlertDialog(
                        onDismissRequest = { linkToRefresh = null },
                        title = { Text("Refresh Expired Download Link", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Paste a fresh mirror or token URL to resume ${targetItem.fileName} from ${formatBytes(targetItem.downloadedBytes)} without losing downloaded data:",
                                    fontSize = 13.sp
                                )
                                OutlinedTextField(
                                    value = refreshedUrlText,
                                    onValueChange = { refreshedUrlText = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (refreshedUrlText.startsWith("http")) {
                                        targetItem.url = refreshedUrlText.trim()
                                        MultiThreadEngine.startTask(
                                            context = this@MainActivity,
                                            item = targetItem,
                                            dbHelper = dbHelper,
                                            onProgress = { updated -> updateItemInList(updated) }
                                        )
                                    }
                                    linkToRefresh = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = appleBlue)
                            ) {
                                Text("Resume Transfer")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { linkToRefresh = null }) {
                                Text("Cancel", color = Color.Gray)
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun NavPill(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSelected) Color(0xFF0A84FF) else Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            modifier = modifier.height(40.dp)
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
                color = if (isSelected) Color.White else Color.Black,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }

    private fun extractIncomingUrl(intent: Intent?): String? {
        val dataStr = intent?.data?.toString()
        if (!dataStr.isNullOrBlank()) {
            return dataStr.removePrefix("shunnoget://download?url=")
        }
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrBlank()) {
            val match = Regex("https?://\\S+").find(sharedText)
            return match?.value
        }
        return null
    }

    private fun detectCategory(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in listOf("mp4", "mkv", "mov", "webm", "ts", "m3u8") -> "Videos"
            in listOf("flac", "alac", "m4a", "wav", "mp3", "aac", "opus") -> "Lossless Audio"
            in listOf("zip", "rar", "7z", "tar", "gz") -> "Archives"
            "apk" -> "APKs"
            else -> "Documents"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun formatSizeProgress(downloaded: Long, total: Long): String {
        return if (total > 0L) "${formatBytes(downloaded)} / ${formatBytes(total)}"
        else formatBytes(downloaded)
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return "${formatBytes(bytesPerSec)}/s"
    }

    private fun formatEta(downloaded: Long, total: Long, speed: Long): String {
        if (total <= 0L || speed <= 0L || downloaded >= total) return "--"
        val secs = (total - downloaded) / speed
        return if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }
}
