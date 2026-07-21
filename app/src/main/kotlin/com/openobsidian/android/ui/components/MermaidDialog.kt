package com.openobsidian.android.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openobsidian.android.R
import org.json.JSONObject

/**
 * A Mermaid diagram, full screen and zoomable.
 *
 * Rendering it inline was the obvious idea and the wrong one. A flowchart at
 * 360 dp is unreadable however it is drawn, and splitting the preview into a
 * column of TextViews and WebViews would put the task-list tapping, the
 * heading scroll and the LaTeX spans at risk — for something you could not
 * read anyway. A tap opens it here instead, which is also what the desktop
 * does when you click a diagram.
 *
 * `mermaid.min.js` is bundled in the assets: this app works with the plane in
 * flight, and a diagram that needs a CDN is a diagram that fails offline.
 */
@Composable
fun MermaidDialog(source: String, onClose: () -> Unit) {
    val dark = !MaterialTheme.colorScheme.background.isLight()
    val bg = MaterialTheme.colorScheme.surface.toArgb()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.diagram_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_diagram))
                    }
                }
                MermaidWebView(
                    source = source,
                    dark = dark,
                    backgroundColor = bg,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.isLight(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) > 0.5

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidWebView(
    source: String,
    dark: Boolean,
    backgroundColor: Int,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                // Pinch to zoom, without the on-screen +/- buttons
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // No network is ever needed; blocking it makes that a guarantee
                // rather than a claim, and a malformed diagram cannot phone home
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(backgroundColor)
            }
        },
        update = { web ->
            web.setBackgroundColor(backgroundColor)
            web.loadDataWithBaseURL(
                // Base URL inside the assets so <script src="mermaid.min.js"> resolves
                "file:///android_asset/",
                buildHtml(source, dark),
                "text/html",
                "utf-8",
                null,
            )
        },
    )
}

/**
 * The diagram source travels as a JSON string literal, so quotes, newlines and
 * backslashes cannot break out of the script and turn a note into arbitrary
 * JavaScript.
 */
private fun buildHtml(source: String, dark: Boolean): String {
    val literal = JSONObject.quote(source)
    val theme = if (dark) "dark" else "default"
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            html, body { margin: 0; padding: 12px; background: transparent; }
            #d { display: flex; justify-content: center; }
            svg { max-width: 100%; height: auto; }
            #err { font-family: monospace; font-size: 12px; white-space: pre-wrap;
                   color: ${if (dark) "#f87171" else "#b91c1c"}; }
          </style>
          <script src="mermaid.min.js"></script>
        </head>
        <body>
          <div id="d"></div>
          <div id="err"></div>
          <script>
            var src = $literal;
            try {
              mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'strict' });
              mermaid.render('g', src).then(function (r) {
                document.getElementById('d').innerHTML = r.svg;
              }).catch(function (e) {
                // A diagram that fails to parse shows why, instead of a blank screen
                document.getElementById('err').textContent = String(e && e.message || e);
              });
            } catch (e) {
              document.getElementById('err').textContent = String(e && e.message || e);
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}
