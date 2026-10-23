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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

private val ink = Color(0xFF080A13)
private val panel = Color(0xFF151A2A)
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

private fun requestDownload(context: Context, url: String): String {
    val uri = try { Uri.parse(url) } catch (_: Exception) { return "Invalid download URL" }
    if (uri.scheme != "https" && uri.scheme != "http") return "Only HTTP(S) downloads are supported"
    if (uri.host.isNullOrBlank()) return "Invalid download address"
    if (uri.path?.endsWith(".m3u8", true) == true || uri.path?.endsWith(".mpd", true) == true)
        return "This is a segmented stream, not a single downloadable file"
    return try {
        val original = uri.lastPathSegment?.substringBefore('?')?.takeIf { it.contains('.') } ?: "roohux-media.mp4"
        val filename = original.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(90)
        val request = DownloadManager.Request(uri)
            .setTitle(filename)
            .setDescription("RoohuX media download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        request.addRequestHeader("User-Agent", "Mozilla/5.0 (Android) RoohuX/0.2")
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        "Download queued in RoohuX app storage"
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
        if (!mediaType(valid) || found.any { it.url == valid } || found.size >= 24) return
        found.add(FoundMedia(valid, label.ifBlank { "Media source" }))
        if (autoPrompt && !promptSeenForPage) {
            promptSeenForPage = true
            showDownloadChoices = true
        }
    }
    fun openBrowser(url: String) {
        browserTarget = url
        browserInput = url
        found.clear()
        promptSeenForPage = false
        browserPage = url
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

    if (showDownloadChoices && found.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDownloadChoices = false },
            title = { Text("Video detected") },
            text = {
                Column {
                    Text("Choose an available source. Quality is shown only when the website provides it.")
                    Spacer(Modifier.height(10.dp))
                    found.take(10).forEach { item ->
                        val label = item.label.ifBlank { Uri.parse(item.url).lastPathSegment ?: "Video file" }
                        TextButton(onClick = {
                            status = requestDownload(context, item.url)
                            showDownloadChoices = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("↓  " + label.take(70), maxLines = 2)
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
        Scaffold(containerColor = ink, bottomBar = {
            NavigationBar(containerColor = panel) {
                listOf("Explore", "Player", "Browser", "Saved").forEachIndexed { index, name ->
                    NavigationBarItem(
                        selected = tab == index, onClick = { tab = index },
                        icon = { Text(listOf("✦", "▶", "◉", "★")[index]) },
                        label = { Text(name) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = violet, selectedTextColor = violet)
                    )
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().background(ink).padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Text("ROOHUX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                    color = violet)
                Text("Your media. Your universe.", color = Color(0xFFABB4C8))
                Spacer(Modifier.height(14.dp))
                if (status.isNotBlank()) {
                    Text(status, color = cyan, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                when (tab) {
                    0 -> {
                        Column(Modifier.fillMaxWidth().background(
                            Brush.linearGradient(listOf(Color(0xFF322456), Color(0xFF122D3A))),
                            RoundedCornerShape(24.dp)).padding(20.dp)) {
                            Text("Discover & play", style = MaterialTheme.typography.headlineSmall,
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
                        if (playerError.isNotBlank()) Text(playerError, color = Color(0xFFFFB6B6))
                        Text(current ?: "Open a direct media file to begin",
                            maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.LightGray)
                        Spacer(Modifier.height(12.dp))
                        current?.let { url ->
                            Row {
                                Button(onClick = { save(url); status = "Saved" }) { Text("Save") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { status = requestDownload(context, url) }) {
                                    Text("Download file")
                                }
                            }
                        }
                    }
                    2 -> {
                        Text("Web player", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(browserInput, { browserInput = it }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text("Website or share link") })
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Button(onClick = {
                                normalizedUrl(browserInput)?.let { openBrowser(it) }
                                    ?: run { status = "Invalid website" }
                            }) { Text("Go") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { webView?.reload() }) { Text("Reload") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                webView?.let { view ->
                                    view.evaluateJavascript(
                                        "(function(){return JSON.stringify(Array.from(document.querySelectorAll('video,video source,a[download]')).map(e=>({u:e.currentSrc||e.src||e.href||'',q:e.getAttribute('label')||e.getAttribute('res')||''})).filter(x=>x.u))})()"
                                    ) { raw ->
                                        try {
                                            val unquoted = JSONArray("[$raw]").getString(0)
                                            val array = JSONArray(unquoted)
                                            for (i in 0 until array.length()) {
                                                val entry = array.getJSONObject(i)
                                                addDetected(entry.optString("u"), entry.optString("q"))
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }) { Text("Find media") }
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Auto download popup", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            Switch(checked = autoPrompt, onCheckedChange = { autoPrompt = it })
                            if (found.isNotEmpty()) TextButton(onClick = { showDownloadChoices = true }) { Text("Downloads") }
                        }
                        if (found.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Detected downloadable media (${found.size})", color = cyan,
                                style = MaterialTheme.typography.labelLarge)
                            Column(Modifier.heightIn(max = 170.dp)) {
                                found.take(6).forEach { item ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(item.label.ifBlank { Uri.parse(item.url).lastPathSegment ?: "Media" },
                                            modifier = Modifier.weight(1f), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                        Text(" Play ", color = violet, modifier = Modifier.clickable { play(item.url) })
                                        Text(" ↓ ", color = cyan, modifier = Modifier.clickable {
                                            status = requestDownload(context, item.url)
                                        })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        key(browserTarget) {
                            BrowserView(
                                activity = activity,
                                startUrl = browserTarget,
                                onCreated = { webView = it },
                                onPage = { browserInput = it; browserPage = it },
                                onMedia = { url, label -> addDetected(url, label) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    3 -> {
                        Text("Saved links", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        if (saved.isEmpty()) Text("No links saved yet", color = Color.LightGray)
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
    modifier: Modifier = Modifier
) {
    AndroidView(factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = object : WebChromeClient() {
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
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null) onPage(url)
                    view?.evaluateJavascript("""
                        (function(){
                          if(window.__roohuxDetector) return;
                          window.__roohuxDetector=true;
                          function scan(){
                            document.querySelectorAll("video,video source").forEach(function(el){
                              var v=el.currentSrc||el.src||"";
                              if(!/^https?:\\/\\//i.test(v))return;
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
