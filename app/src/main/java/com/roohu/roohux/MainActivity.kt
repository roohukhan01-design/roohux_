package com.roohu.roohux

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray

private val bg = Color(0xFF090B16)
private val accent = Color(0xFF9A78FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RoohuXApp() }
    }
}

private fun cleanUrl(raw: String): String? {
    val value = raw.trim()
    return if ((value.startsWith("https://") || value.startsWith("http://")) &&
        android.webkit.URLUtil.isValidUrl(value)) value else null
}

private fun isDirectMedia(url: String): Boolean {
    val path = android.net.Uri.parse(url).path?.lowercase() ?: return false
    return listOf(".mp4", ".webm", ".m4v", ".mp3", ".m4a", ".aac", ".ogg", ".wav", ".m3u8", ".mpd")
        .any { path.endsWith(it) }
}

@Composable
fun RoohuXApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("roohux", 0) }
    val saved = remember {
        mutableStateListOf<String>().apply {
            try {
                val arr = JSONArray(prefs.getString("links", "[]"))
                for (i in 0 until arr.length()) add(arr.getString(i))
            } catch (_: Exception) {}
        }
    }
    fun persist() {
        try {
            val arr = JSONArray()
            saved.forEach { arr.put(it) }
            prefs.edit().putString("links", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    var tab by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf("") }
    var currentMedia by remember { mutableStateOf<String?>(null) }
    var browserUrl by remember { mutableStateOf("https://www.google.com") }
    var browserInput by remember { mutableStateOf(browserUrl) }
    var browserView by remember { mutableStateOf<WebView?>(null) }
    var message by remember { mutableStateOf("") }
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(Unit) { onDispose { browserView?.destroy(); player.release() } }

    fun play(url: String) {
        val valid = cleanUrl(url)
        if (valid == null) { message = "Enter a valid http(s) media link"; return }
        currentMedia = valid
        player.setMediaItem(MediaItem.fromUri(valid))
        player.prepare()
        player.play()
        tab = 1
        message = ""
    }

    BackHandler(tab != 0) {
        if (tab == 2 && browserView?.canGoBack() == true) browserView?.goBack()
        else tab = 0
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent, background = bg, surface = Color(0xFF17192B),
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF17192B)) {
                    listOf("Home", "Player", "Browser", "Saved").forEachIndexed { i, name ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(listOf("⌂", "▶", "◎", "★")[i]) },
                            label = { Text(name) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Text("RoohuX", style = MaterialTheme.typography.headlineLarge, color = accent)
                Text("Your personal media universe", color = Color.LightGray)
                Spacer(Modifier.height(18.dp))
                if (message.isNotEmpty()) Text(message, color = Color(0xFFFFB0B0))
                when (tab) {
                    0 -> {
                        Text("Open a direct media URL", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = link, onValueChange = { link = it },
                            label = { Text("https://…/video.mp4 or stream.m3u8") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { play(link) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Play media")
                        }
                        OutlinedButton(onClick = {
                            val valid = cleanUrl(link)
                            if (valid != null) {
                                if (!saved.contains(valid)) { saved.add(0, valid); persist() }
                                message = "Saved to your library"
                            } else message = "Enter a valid URL first"
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save link") }
                        Spacer(Modifier.height(16.dp))
                        Text("Open a website in Browser to view its normal HTML5 videos. " +
                            "Protected videos may not play outside their websites.",
                            color = Color.LightGray)
                    }
                    1 -> {
                        Text("Media player", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    this.player = player
                                    useController = true
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { it.player = player },
                            modifier = Modifier.fillMaxWidth().height(260.dp)
                        )
                        Text(currentMedia ?: "Paste a media URL on Home to start",
                            color = Color.LightGray)
                        Spacer(Modifier.height(12.dp))
                        if (currentMedia != null) {
                            Button(onClick = {
                                val url = currentMedia!!
                                if (!saved.contains(url)) { saved.add(0, url); persist() }
                                message = "Saved to your library"
                            }) { Text("Save current media") }
                        }
                    }
                    2 -> {
                        Text("Built-in browser", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = browserInput, onValueChange = { browserInput = it },
                            label = { Text("Website address") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Row {
                            Button(onClick = {
                                val raw = browserInput.trim()
                                val candidate = if (raw.startsWith("http")) raw else "https://$raw"
                                val valid = cleanUrl(candidate)
                                if (valid != null) {
                                    browserUrl = valid
                                    browserView?.loadUrl(valid)
                                } else message = "Invalid website address"
                            }) { Text("Go") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                val url = browserView?.url ?: browserUrl
                                if (isDirectMedia(url)) play(url)
                                else message = "This page is not a direct media URL. Use the website player."
                            }) { Text("Play direct URL") }
                        }
                        Spacer(Modifier.height(8.dp))
                        BrowserPane(
                            startUrl = browserUrl,
                            onCreated = { browserView = it },
                            onPageChanged = { browserInput = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    3 -> {
                        Text("Saved links", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(10.dp))
                        if (saved.isEmpty()) Text("No saved media yet", color = Color.LightGray)
                        LazyColumn {
                            items(saved.toList()) { url ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(url, maxLines = 2)
                                        Row {
                                            TextButton(onClick = { play(url) }) { Text("Play") }
                                            TextButton(onClick = {
                                                saved.remove(url); persist()
                                            }) { Text("Remove") }
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
private fun BrowserPane(
    startUrl: String,
    onCreated: (WebView) -> Unit,
    onPageChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ): Boolean = false
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url != null) onPageChanged(url)
                    }
                }
                loadUrl(startUrl)
                onCreated(this)
            }
        },
        modifier = modifier
    )
}
