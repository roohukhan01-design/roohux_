package com.roohu.roohux

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal data class DownloadEntry(val id: Long, val name: String, val url: String)
internal data class DownloadState(val status: Int, val done: Long, val total: Long, val reason: Int)

internal object DownloadHistory {
    private const val KEY = "download_history"
    fun read(context: Context): List<DownloadEntry> = try {
        val a = JSONArray(context.getSharedPreferences("roohux", 0).getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            DownloadEntry(o.getLong("id"), o.getString("name"), o.getString("url"))
        }
    } catch (_: Exception) { emptyList() }

    fun add(context: Context, id: Long, name: String, url: String) {
        try {
            val entries = listOf(DownloadEntry(id, name, url)) + read(context).take(99)
            save(context, entries)
        } catch (_: Exception) {}
    }

    private fun save(context: Context, entries: List<DownloadEntry>) {
        val a = JSONArray()
        entries.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        context.getSharedPreferences("roohux", 0).edit().putString(KEY, a.toString()).apply()
    }

    fun remove(context: Context, id: Long) {
        try { save(context, read(context).filterNot { it.id == id }) } catch (_: Exception) {}
    }

    fun state(context: Context, id: Long): DownloadState? = try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun number(key: String) = cursor.getLong(cursor.getColumnIndexOrThrow(key))
            DownloadState(
                number(DownloadManager.COLUMN_STATUS).toInt(),
                number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                number(DownloadManager.COLUMN_REASON).toInt()
            )
        }
    } catch (_: Exception) { null }
}

@Composable
internal fun DownloadCenter(onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var entries by remember { mutableStateOf(DownloadHistory.read(context)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            tick++
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Download center", style = MaterialTheme.typography.headlineSmall)
        Text("Live progress and readable failure reasons", color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { entries = DownloadHistory.read(context) }) { Text("Refresh") }
        if (entries.isEmpty()) Text("No downloads yet. Open a video in Browser to find available files.")
        LazyColumn {
            items(entries, key = { it.id }) { item ->
                val state = remember(item.id, tick) { DownloadHistory.state(context, item.id) }
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        val progress = if (state != null && state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                        val label = when (state?.status) {
                            DownloadManager.STATUS_SUCCESSFUL -> "Completed"
                            DownloadManager.STATUS_RUNNING -> "Downloading"
                            DownloadManager.STATUS_PENDING -> "Waiting"
                            DownloadManager.STATUS_PAUSED -> "Paused by Android"
                            DownloadManager.STATUS_FAILED -> when (state.reason) {
                                DownloadManager.ERROR_CANNOT_RESUME -> "Failed: server cannot resume download"
                                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Failed: storage unavailable"
                                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Failed: file already exists"
                                DownloadManager.ERROR_FILE_ERROR -> "Failed: cannot write file"
                                DownloadManager.ERROR_HTTP_DATA_ERROR -> "Failed: network interrupted"
                                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Failed: not enough storage"
                                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Failed: too many redirects"
                                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Failed: website rejected request"
                                else -> if (state.reason in 400..599) "Failed: server HTTP ${state.reason}" else "Failed (code ${state.reason})"
                            }
                            else -> "Unknown / removed"
                        }
                        Text("$label • ${state?.done?.div(1024L) ?: 0} KB")
                        if (state != null && state.total > 0) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text("${(progress * 100).toInt()}%")
                        } else if (state?.status == DownloadManager.STATUS_RUNNING) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row {
                            if (state?.status == DownloadManager.STATUS_SUCCESSFUL) {
                                TextButton(onClick = {
                                    try {
                                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        val uri = dm.getUriForDownloadedFile(item.id)
                                        if (uri != null) {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        }
                                    } catch (_: Exception) {}
                                }) { Text("Open file") }
                            }
                            TextButton(onClick = { onOpen(item.url) }) { Text("Source") }
                            TextButton(onClick = {
                                try { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.id) } catch (_: Exception) {}
                                DownloadHistory.remove(context, item.id)
                                entries = DownloadHistory.read(context)
                            }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
