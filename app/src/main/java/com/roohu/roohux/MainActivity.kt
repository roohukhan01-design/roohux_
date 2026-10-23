package com.roohu.roohux

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.graphics.Bitmap
import android.content.Intent
import android.content.ActivityNotFoundException
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import java.net.URLDecoder

private val ink = Color(0xFF070916)
private val panel = Color(0xFF141A2F)
private val violet = Color(0xFFAC91FF)
private val cyan = Color(0xFF68E3E0)

private fun normalizedUrl(raw: String): String? {
    val input = raw.trim()
    if (input.isBlank()) return null
    val candidate = if (input.startsWith("http://", true) || input.startsWith("https://", true)) input else "https://$input"
    return try {
        val uri = Uri.parse(candidate)
        if ((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank() && !uri.host!!.contains(" ")) candidate else null
    } catch (_: Exception) { null }
}

private fun mediaType(url: String): Boolean {
    val path = Uri.parse(url).path?.lowercase() ?: return false
    return listOf(".mp4", ".webm", ".m4v", ".mp3", ".m4a", ".aac", ".ogg", ".wav", ".m3u8", ".mpd", ".mov")
        .any { path.endsWith(it) }
}

private data class FoundMedia(val url: String, val label: String)

class MainActivity : ComponentActivity() {
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    fun showVideoFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) { callback.onCustomViewHidden(); return }
        fullscreenView = view
        fullscreenCallback = callback
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
    }

    fun closeVideoFullscreen(): Boolean {
        val view = fullscreenView ?: return false
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenView = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RoohuXScreen(this) }
    }
}

private fun requestDownload(context: Context, url: String, pageUrl: String? = null, userAgent: String? = null): String {
    val uri = try { Uri.parse(url) } catch (_: Exception) { return "Invalid download URL" }
    if (uri.scheme != "https" && uri.scheme != "http") return "Only HTTP(S) downloads are supported"
    if (uri.host.isNullOrBlank()) return "Invalid download address"
    if (uri.path?.endsWith(".m3u8", true) == true || uri.path?.endsWith(".mpd", true) == true)
        return "This is a segmented stream, not a single downloadable file"
    return try {
        val original = uri.lastPathSegment?.substringBefore('?')?.takeIf { it.contains('.') } ?: "roohux-media.mp4"
        val safeName = original.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(70)
        val filename = "${System.currentTimeMillis()}_$safeName"
        val request = DownloadManager.Request(uri)
            .setTitle(filename)
            .setDescription("RoohuX media download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "RoohuX/" + filename)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        request.addRequestHeader("User-Agent", userAgent ?: WebSettings.getDefaultUserAgent(context))
        if (!pageUrl.isNullOrBlank() && pageUrl.startsWith("https://")) request.addRequestHeader("Referer", pageUrl)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        DownloadHistory.add(context, id, filename, url)
        "Download started — see Downloads tab"
    } catch (e: Exception) { "Download failed: ${e.localizedMessage ?: "unsupported link"}" }
}

@Composable
private fun RoohuXScreen(activity: MainActivity) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("roohux", Context.MODE_PRIVATE) }
    val saved = remember {
        mutableStateListOf<String>().apply {
            try {
                val array = JSONArray(prefs.getString("links", "[]"))
                for (i in 0 until array.length()) add(array.getString(i))
            } catch (_: Exception) {}
        }
    }
    fun save(url: String) {
        if (!saved.contains(url)) saved.add(0, url)
        try { prefs.edit().putString("links", JSONArray(saved.toList()).toString()).apply() } catch (_: Exception) {}
    }
    fun remove(url: String) {
        saved.remove(url)
        try { prefs.edit().putString("links", JSONArray(saved.toList()).toString()).apply() } catch (_: Exception) {}
    }

    var tab by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var browserInput by remember { mutableStateOf("https://www.google.com") }
    var browserTarget by remember { mutableStateOf("https://www.google.com") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var current by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<FoundMedia>() }
    var browserPage by remember { mutableStateOf("") }
    var playerError by remember { mutableStateOf("") }
    var showDownloadChoices by remember { mutableStateOf(false) }
    var autoPrompt by remember { mutableStateOf(true) }
    var promptSeenForPage by remember { mutableStateOf(false) }
    var browserExpanded by remember { mutableStateOf(true) }
    var desktopMode by remember { mutableStateOf(false) }
    var browserVisible by remember { mutableStateOf(true) }
    var browserLoading by remember { mutableStateOf(false) }
    var browserProgress by remember { mutableIntStateOf(0) }
    var motionEnabled by remember { mutableStateOf(prefs.getBoolean("motion", true)) }
    var browserError by remember { mutableStateOf("") }
    var downloadFeedback by remember { mutableStateOf("") }
    var browserTabIndex by remember { mutableIntStateOf(0) }
    val browserTabs = remember { mutableStateListOf("https://www.google.com") }
    val bookmarks = remember { mutableStateListOf<String>().apply { try { val a = JSONArray(prefs.getString("bookmarks", "[]")); for (i in 0 until a.length()) add(a.getString(i)) } catch (_: Exception) {} } }
    fun bookmark(url: String) { if (!bookmarks.contains(url)) bookmarks.add(0, url); try { prefs.edit().putString("bookmarks", JSONArray(bookmarks.toList()).toString()).apply() } catch (_: Exception) {} }
    val player = remember {
        ExoPlayer.Builder(context).build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = "Can't play this link directly. Try opening its website in Browser."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun addDetected(url: String, label: String) {
        val valid = normalizedUrl(url) ?: return
        if ((!mediaType(valid) && !label.startsWith("Website download")) || found.any { it.url == valid } || found.size >= 24) return
        found.add(FoundMedia(valid, label.ifBlank { "Media source" }))
        // Do not interrupt playback. A download chip appears in the browser instead.
    }
    fun openBrowser(url: String) {
        browserTarget = url
        browserInput = url
        found.clear()
        promptSeenForPage = false
        browserPage = url
        browserError = ""
        if (browserTabIndex in browserTabs.indices) browserTabs[browserTabIndex] = url
        tab = 2
    }
    fun play(url: String) {
        val valid = normalizedUrl(url) ?: run { status = "Enter a valid link"; return }
        current = valid
        playerError = ""
        player.setMediaItem(MediaItem.fromUri(valid))
        player.prepare()
        player.playWhenReady = true
        tab = 1
        status = ""
    }
    fun openInput(value: String) {
        val valid = normalizedUrl(value)
        if (valid == null) { status = "Enter a website or media URL"; return }
        if (mediaType(valid)) play(valid) else openBrowser(valid)
    }

    BackHandler(tab != 0) {
        if (!activity.closeVideoFullscreen()) {
            if (tab == 2 && webView?.canGoBack() == true) webView?.goBack() else tab = 0
        }
    }

    if (downloadFeedback.isNotBlank()) {
        AlertDialog(onDismissRequest = { downloadFeedback = "" }, title = { Text("Download status") },
            text = { Text(downloadFeedback) }, confirmButton = { TextButton(onClick = { downloadFeedback = ""; tab = 4 }) { Text("View downloads") } },
            dismissButton = { TextButton(onClick = { downloadFeedback = "" }) { Text("Close") } })
    }
    if (showDownloadChoices && found.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDownloadChoices = false },
            title = { Text("Video detected") },
            text = {
                Column {
                    Text("Available files and streams. Resolution depends on the website.")
                    Spacer(Modifier.height(10.dp))
                    found.take(10).forEach { item ->
                        val streamOnly = item.url.substringBefore("?").endsWith(".m3u8", true) || item.url.substringBefore("?").endsWith(".mpd", true)
                        val label = item.label.ifBlank { Uri.parse(item.url).lastPathSegment ?: "Video file" }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(label.take(46), modifier = Modifier.weight(1f), maxLines = 2)
                            TextButton(onClick = { showDownloadChoices = false; play(item.url) }) { Text("Play") }
                            if (!streamOnly) TextButton(onClick = { downloadFeedback = requestDownload(context, item.url, browserPage, webView?.settings?.userAgentString); showDownloadChoices = false }) { Text("↓ Save") }
                            else Text("Stream", style = MaterialTheme.typography.labelSmall, color = cyan)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDownloadChoices = false }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { autoPrompt = false; showDownloadChoices = false }) { Text("Disable auto popup") } }
        )
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = violet, secondary = cyan, background = ink,
        surface = panel, onSurface = Color.White, onBackground = Color.White)) {
        Scaffold(containerColor = Color.Transparent, bottomBar = {
            if (tab != 2 || !browserExpanded) {
            NavigationBar(containerColor = Color(0xF0101730)) {
                listOf("Explore", "Player", "Browser", "Saved", "Downloads").forEachIndexed { index, name ->
                    NavigationBarItem(
                        selected = tab == index, onClick = { tab = index },
                        icon = { Text(listOf("✦", "▶", "◉", "★", "↓")[index]) },
                        label = { Text(name) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = violet, selectedTextColor = violet)
                    )
                }
            }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().background(ink)) {
                if (tab != 2 || !browserExpanded) CosmicBackground(motionEnabled)
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = if (tab == 2 && browserExpanded) 5.dp else 16.dp)) {
                if (tab != 2 || !browserExpanded) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ROOHU", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.White)
                    Text("X", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = cyan)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { motionEnabled = !motionEnabled; prefs.edit().putBoolean("motion", motionEnabled).apply() }) {
                        Text(if (motionEnabled) "✧ Motion" else "◇ Still", color = violet)
                    }
                }
                Text("YOUR MEDIA  /  YOUR UNIVERSE", letterSpacing = 2.sp, color = Color(0xFF9BA9C8), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(14.dp))
                }
                if (status.isNotBlank() && (tab != 2 || !browserExpanded)) {
                    Text(status, color = cyan, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                when (tab) {
                    0 -> {
                        HolographicHero(motionEnabled)
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.fillMaxWidth().background(
                            Brush.linearGradient(listOf(Color(0xDD222B51), Color(0xDD132E3C))),
                            RoundedCornerShape(24.dp)).padding(20.dp)) {
                            Text("OPEN A NEW WORLD", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Paste a website, cloud share link, or direct media URL.",
                                color = Color(0xFFD1D8EA))
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(input, { input = it }, singleLine = true,
                                label = { Text("Paste your link") }, modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { openInput(input) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Open link  →")
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Quick destinations", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        listOf(
                            "Google Drive" to "https://drive.google.com",
                            "TeraBox" to "https://www.terabox.com",
                            "YouTube" to "https://www.youtube.com",
                            "Custom website" to ""
                        ).forEach { (name, url) ->
                            ElevatedCard(onClick = {
                                if (url.isBlank()) { tab = 2 } else openBrowser(url)
                            }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = panel)) {
                                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(name, fontWeight = FontWeight.SemiBold)
                                    Text("↗", color = cyan)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Cloud services open their official websites. Downloads depend on site permissions and available file URLs.",
                            color = Color(0xFFABB4C8), style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("Now playing", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                        } }, update = { it.player = player },
                            modifier = Modifier.fillMaxWidth().height(260.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                TextButton(onClick = { player.setPlaybackSpeed(speed) }) { Text("${speed}×") }
                            }
                        }
                        if (playerError.isNotBlank()) Text(playerError, color = Color(0xFFFFB6B6))
                        Text(current ?: "Open a direct media file to begin",
                            maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.LightGray)
                        Spacer(Modifier.height(12.dp))
                        current?.let { url ->
                            Row {
                                Button(onClick = { save(url); status = "Saved" }) { Text("Save") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { downloadFeedback = requestDownload(context, url, browserPage, webView?.settings?.userAgentString) }) {
                                    Text("Download file")
                                }
                            }
                        }
                    }
                    2 -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedTextField(
                                browserInput, { browserInput = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                label = { Text("Website / video link") }
                            )
                            TextButton(onClick = {
                                normalizedUrl(browserInput)?.let { openBrowser(it) }
                                    ?: run { status = "Invalid website" }
                            }) { Text("Go") }
                            TextButton(onClick = { browserExpanded = !browserExpanded }) {
                                Text(if (browserExpanded) "Tools" else "Full")
                            }
                        }
                        if (!browserExpanded) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                browserTabs.forEachIndexed { index, url ->
                                    TextButton(onClick = { browserTabIndex = index; browserTarget = url; browserInput = url; found.clear() }, modifier = Modifier.weight(1f)) {
                                        Text(if (index == browserTabIndex) "● ${index + 1}" else "○ ${index + 1}")
                                    }
                                }
                                TextButton(onClick = { if (browserTabs.size < 4) { browserTabs.add("https://www.google.com"); browserTabIndex = browserTabs.lastIndex; browserTarget = browserTabs.last(); browserInput = browserTarget; found.clear() } }) { Text("+ Tab") }
                                if (browserTabs.size > 1) TextButton(onClick = { browserTabs.removeAt(browserTabIndex); browserTabIndex = 0; browserTarget = browserTabs[0]; browserInput = browserTarget; found.clear() }) { Text("×") }
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                TextButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) { Text("Back") }
                                TextButton(onClick = { if (webView?.canGoForward() == true) webView?.goForward() }) { Text("Next") }
                                TextButton(onClick = { webView?.reload() }) { Text("Reload") }
                                TextButton(onClick = { showDownloadChoices = found.isNotEmpty() }) {
                                    Text("Downloads (${found.size})")
                                }
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                TextButton(onClick = { webView?.url?.let { bookmark(it) }; status = "Bookmarked" }) { Text("★ Save") }
                                TextButton(onClick = { desktopMode = !desktopMode; webView?.settings?.userAgentString = if (desktopMode) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" else null; webView?.reload() }) { Text(if (desktopMode) "Mobile" else "Desktop") }
                                TextButton(onClick = { webView?.url?.let { save(it) } }) { Text("Save link") }
                            }
                        }
                        if (browserError.isNotBlank()) {
                            Text(browserError, color = Color(0xFFFFB6B6), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { webView?.reload() }) { Text("Try again") }
                        }
                        if (browserLoading) {
                            LinearProgressIndicator(progress = { browserProgress / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                        if (found.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("${found.size} media source(s) detected", color = cyan,
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { showDownloadChoices = true }) { Text("Download options ↓") }
                            }
                        }
                        key(browserTarget) {
                            BrowserView(
                                activity = activity,
                                startUrl = browserTarget,
                                onCreated = { webView = it },
                                onPage = { browserInput = it; browserPage = it; browserError = ""; if (browserTabIndex in browserTabs.indices) browserTabs[browserTabIndex] = it },
                                onMedia = { url, label -> addDetected(url, label) },
                                onProgress = { progress -> browserProgress = progress; browserLoading = progress < 100 },
                                onError = { error -> browserError = error },
                                onDownload = { url -> downloadFeedback = requestDownload(context, url, browserPage, webView?.settings?.userAgentString) },
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                    3 -> {
                        Text("Saved links", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        if (saved.isEmpty() && bookmarks.isEmpty()) Text("No links saved yet", color = Color.LightGray)
                        if (bookmarks.isNotEmpty()) {
                            Text("Browser bookmarks", color = cyan)
                            bookmarks.take(8).forEach { url -> TextButton(onClick = { openBrowser(url) }) { Text(url, maxLines = 1) } }
                        }
                        LazyColumn {
                            items(saved.toList()) { url ->
                                ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    colors = CardDefaults.elevatedCardColors(containerColor = panel)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Row {
                                            TextButton(onClick = { openInput(url) }) { Text("Open") }
                                            TextButton(onClick = { remove(url) }) { Text("Remove") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    4 -> {
                        DownloadCenter(onOpen = { url -> openInput(url) })
                    }
                }
            }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserView(
    activity: MainActivity,
    startUrl: String,
    onCreated: (WebView) -> Unit,
    onPage: (String) -> Unit,
    onMedia: (String, String) -> Unit,
    onProgress: (Int) -> Unit,
    onError: (String) -> Unit,
    onDownload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                if (url.startsWith("https://") || url.startsWith("http://")) onMedia(url, URLUtil.guessFileName(url, contentDisposition, mimeType))
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) { onProgress(newProgress) }
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    val raw = message?.message() ?: return false
                    if (raw.startsWith("ROOHUX_MEDIA:")) {
                        try {
                            val entry = org.json.JSONObject(raw.removePrefix("ROOHUX_MEDIA:"))
                            onMedia(entry.optString("url"), entry.optString("quality"))
                        } catch (_: Exception) {}
                        return true
                    }
                    return super.onConsoleMessage(message)
                }
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view != null && callback != null) activity.showVideoFullscreen(view, callback)
                }
                override fun onHideCustomView() { activity.closeVideoFullscreen() }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (uri.scheme == "http" || uri.scheme == "https") return false
                    return try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (_: Exception) { true }
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) onError(error?.description?.toString() ?: "Page could not load")
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null) onPage(url)
                    view?.evaluateJavascript("""
                        (function(){
                          if(window.__roohuxDetector) return;
                          window.__roohuxDetector=true;
                          function scan(){
                            document.querySelectorAll("video,video source").forEach(function(el){
                              var v=el.currentSrc||el.src||"";
                              if(v.indexOf("https://")!==0 && v.indexOf("http://")!==0)return;
                              var q=el.getAttribute("label")||el.getAttribute("res")||el.getAttribute("size")||
                                (el.videoHeight ? el.videoHeight+"p" : "")||"Video file";
                              console.log("ROOHUX_MEDIA:"+JSON.stringify({url:v,quality:q}));
                            });
                          }
                          document.addEventListener("play",scan,true);
                          document.addEventListener("loadedmetadata",scan,true);
                          scan();
                          new MutationObserver(function(){scan()}).observe(document.documentElement,{childList:true,subtree:true});
                        })();
                    """.trimIndent(), null)
                }
                override fun onLoadResource(view: WebView?, url: String?) {
                    if (url != null && mediaType(url)) onMedia(url, Uri.parse(url).lastPathSegment ?: "Media file")
                }
            }
            loadUrl(startUrl)
            onCreated(this)
        }
    }, modifier = modifier)
}

@Composable
private fun CosmicBackground(enabled: Boolean) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift"
    )
    Canvas(Modifier.fillMaxSize()) {
        val t = if (enabled) phase else 0.3f
        drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF080A19), Color(0xFF101530), Color(0xFF070C18))))
        val w = size.width
        val h = size.height
        for (i in 0..30) {
            val x = ((i * 137.3f + t * (8 + i % 7)) % w)
            val y = ((i * 213.7f + t * 12f) % h)
            drawCircle(
                color = if (i % 3 == 0) Color(0xFF82E6FA) else Color(0xFFAD9AF6),
                radius = if (i % 5 == 0) 2.3f else 1.1f,
                center = Offset(x, y), alpha = 0.24f
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x664C3D9D), Color.Transparent),
                center = Offset(w * (0.75f + t * 0.03f), h * 0.19f), radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = Offset(w * (0.75f + t * 0.03f), h * 0.19f)
        )
    }
}

@Composable
private fun HolographicHero(enabled: Boolean) {
    val transition = rememberInfiniteTransition(label = "hologram")
    val float by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float"
    )
    val tilt = if (enabled) float else 0f
    Box(
        Modifier.fillMaxWidth().height(176.dp)
            .graphicsLayer {
                rotationY = tilt * 3f
                rotationX = tilt * -2f
                translationY = tilt * 3f
                cameraDistance = 14 * density
            }
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF24215B), Color(0xFF123B4C), Color(0xFF151D38))))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * 0.75f
            val cy = size.height * 0.49f
            for (i in 0..5) {
                drawCircle(
                    color = if (i % 2 == 0) Color(0xFF6DEBE7) else Color(0xFFB39AFF),
                    radius = size.height * (0.14f + i * 0.083f),
                    center = Offset(cx, cy), style = Stroke(width = if (i == 0) 3f else 1.4f),
                    alpha = 0.63f - i * 0.07f
                )
            }
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFFA58EFF), Color(0xFF30336F), Color.Transparent),
                    center = Offset(cx, cy), radius = size.height * 0.35f),
                radius = size.height * 0.35f, center = Offset(cx, cy)
            )
            drawLine(Color(0x6674F7EA), Offset(0f, size.height * 0.83f),
                Offset(size.width, size.height * 0.83f), strokeWidth = 2f)
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 23.dp)) {
            Text("ROOHUX  /  05", style = MaterialTheme.typography.labelMedium, color = cyan, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            Text("EXPLORE\nBEYOND", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black, color = Color.White, lineHeight = 34.sp)
            Spacer(Modifier.height(8.dp))
            Text("CINEMATIC MEDIA SPACE", style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFC9C7FF), letterSpacing = 1.sp)
        }
    }
}
