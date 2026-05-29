package com.openobsidian.android.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.openobsidian.android.data.Node
import org.json.JSONArray
import org.json.JSONObject

/**
 * Force-directed graph of notes + their [[wikilink]] connections, rendered with
 * D3.js inside a WebView. D3 and the page are bundled in assets (no network).
 *
 * @param nodes          All markdown files in the vault (capped at 400).
 * @param backlinks      Map of target-name (lowercase) → files that link to it.
 * @param activeFileUri  URI string of the currently open file (highlighted node).
 * @param onNodeClick    Fired when a node is tapped.
 * @param onClose        Fired when the close button or system Back is pressed.
 */
@Composable
fun GraphView(
    nodes: List<Node.File>,
    backlinks: Map<String, List<Node.File>>,
    activeFileUri: String? = null,
    onNodeClick: (Node.File) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val capped = remember(nodes) { nodes.take(400) }
    val n      = capped.size

    val byUri = remember(capped) { capped.associateBy { it.uri.toString() } }

    // Stable click ref so the JS bridge always sees the latest callback.
    val clickRef = remember { mutableStateOf(onNodeClick) }
    SideEffect { clickRef.value = onNodeClick }

    // ── Build node + edge JSON ────────────────────────────────────────────
    val dataJson = remember(capped, backlinks, activeFileUri) {
        val nameToUri = capped.associate { it.displayName.lowercase() to it.uri.toString() }
        val nodesArr  = JSONArray()
        capped.forEach { f ->
            nodesArr.put(
                JSONObject()
                    .put("id", f.uri.toString())
                    .put("label", f.displayName)
                    .put("active", f.uri.toString() == activeFileUri)
            )
        }
        val linksArr = JSONArray()
        val seen     = HashSet<String>()
        backlinks.forEach { (target, sources) ->
            val tUri = nameToUri[target] ?: return@forEach
            sources.forEach { src ->
                val sUri = nameToUri[src.displayName.lowercase()] ?: return@forEach
                if (sUri == tUri) return@forEach
                val key = if (sUri < tUri) "$sUri|$tUri" else "$tUri|$sUri"
                if (seen.add(key)) {
                    linksArr.put(JSONObject().put("source", sUri).put("target", tUri))
                }
            }
        }
        JSONObject().put("nodes", nodesArr).put("links", linksArr).toString()
    }
    val edgeCount = remember(dataJson) {
        JSONObject(dataJson).getJSONArray("links").length()
    }

    // ── Theme colours passed to D3 ────────────────────────────────────────
    fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())
    val themeJson = JSONObject()
        .put("node",   MaterialTheme.colorScheme.primary.hex())
        .put("active", MaterialTheme.colorScheme.tertiary.hex())
        .put("edge",   MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f).hex())
        .put("label",  MaterialTheme.colorScheme.onSurface.hex())
        .put("bg",     MaterialTheme.colorScheme.surface.hex())
        .toString()

    Box(modifier = modifier) {
        if (n > 0) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory  = { ctx ->
                    @SuppressLint("SetJavaScriptEnabled")
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onNodeClick(uri: String) {
                                    Handler(Looper.getMainLooper()).post {
                                        byUri[uri]?.let { clickRef.value(it) }
                                    }
                                }
                            },
                            "AndroidBridge",
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                val d = dataJson.replace("\\", "\\\\").replace("'", "\\'")
                                val t = themeJson.replace("\\", "\\\\").replace("'", "\\'")
                                view.evaluateJavascript("initGraph('$d', '$t');", null)
                            }
                        }
                        loadUrl("file:///android_asset/graph.html")
                    }
                },
            )
        }

        // ── Stats + close button ──────────────────────────────────────────
        Row(
            modifier              = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Surface(
                shape          = MaterialTheme.shapes.small,
                color          = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                tonalElevation = 2.dp,
            ) {
                Text(
                    text     = buildString {
                        append(n); append(if (n == 1) " note" else " notes")
                        append(" · ")
                        append(edgeCount); append(if (edgeCount == 1) " link" else " links")
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style    = MaterialTheme.typography.labelMedium,
                )
            }
            FilledIconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close graph")
            }
        }

        // ── Empty state ───────────────────────────────────────────────────
        if (n == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No notes in this vault yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}
