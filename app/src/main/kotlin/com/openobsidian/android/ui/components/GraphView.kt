package com.openobsidian.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openobsidian.android.data.Node
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
// Internal graph data model
// ─────────────────────────────────────────────────────────────────────────────

private data class GNode(
    val file: Node.File,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
)

private data class GEdge(val a: Int, val b: Int)

// ─────────────────────────────────────────────────────────────────────────────
// Public composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen force-directed graph of notes + their [[wikilink]] connections.
 *
 * @param nodes          All markdown files in the vault (capped at 200).
 * @param backlinks      Map of target-name (lowercase) → list of files that link to it.
 * @param activeFileUri  URI string of the currently open file (highlighted node).
 * @param onNodeClick    Fired when a node is tapped.
 * @param onClose        Fired when the close button or system Back is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphView(
    nodes: List<Node.File>,
    backlinks: Map<String, List<Node.File>>,
    activeFileUri: String? = null,
    onNodeClick: (Node.File) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cappedNodes = remember(nodes) { nodes.take(200) }
    val n           = cappedNodes.size

    // ── Build mutable node positions (initialised on a circle) ────────────
    val gNodes: MutableList<GNode> = remember(cappedNodes) {
        val radius = maxOf(160f, n * 14f)
        cappedNodes.mapIndexed { i, f ->
            val angle = 2.0 * PI * i / maxOf(n, 1)
            GNode(
                file = f,
                x    = (radius * cos(angle)).toFloat(),
                y    = (radius * sin(angle)).toFloat(),
            )
        }.toMutableList()
    }

    // ── Build undirected edges from backlinks map ──────────────────────────
    val gEdges: List<GEdge> = remember(cappedNodes, backlinks) {
        val nameToIdx = cappedNodes.mapIndexed { i, f -> f.displayName.lowercase() to i }.toMap()
        val edges     = mutableListOf<GEdge>()
        val seen      = mutableSetOf<Long>()
        backlinks.forEach { (target, sources) ->
            val ti = nameToIdx[target] ?: return@forEach
            sources.forEach { src ->
                val si = nameToIdx[src.displayName.lowercase()] ?: return@forEach
                if (si == ti) return@forEach
                // Deduplicate bidirectional links using a canonical key
                val key = minOf(si, ti).toLong() * 1000L + maxOf(si, ti)
                if (seen.add(key)) edges.add(GEdge(si, ti))
            }
        }
        edges
    }

    // ── Simulation state ───────────────────────────────────────────────────
    var tick    by remember { mutableIntStateOf(0) }
    var simDone by remember { mutableStateOf(n <= 1) }

    LaunchedEffect(gNodes, gEdges) {
        if (n <= 1) return@LaunchedEffect
        val steps = 130
        repeat(steps) { iter ->
            if (!isActive) return@LaunchedEffect
            simulateStep(gNodes, gEdges, iter, steps)
            tick++
            delay(16L)
        }
        simDone = true
    }

    // ── Viewport state ─────────────────────────────────────────────────────
    var scale by remember { mutableFloatStateOf(1f) }
    var pan   by remember { mutableStateOf(Offset.Zero) }

    val activeIdx = remember(activeFileUri, cappedNodes) {
        cappedNodes.indexOfFirst { it.uri.toString() == activeFileUri }
    }

    val textMeasurer = rememberTextMeasurer()

    // Colours snapped at composition time (stable across draw frames)
    val nodeCol   = MaterialTheme.colorScheme.primary
    val activeCol = MaterialTheme.colorScheme.tertiary
    val edgeCol   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val labelCol  = MaterialTheme.colorScheme.onSurface
    val bgCol     = MaterialTheme.colorScheme.surface

    Box(modifier = modifier.background(bgCol)) {

        // ── Canvas ──────────────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Multi-touch pan / zoom
                .pointerInput(Unit) {
                    detectTransformGestures { _, panDelta, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.1f, 6f)
                        pan  += panDelta
                    }
                }
                // Single-tap to open a note
                .pointerInput(gNodes) {
                    detectTapGestures { tapPos ->
                        val cx   = size.width  / 2f + pan.x
                        val cy   = size.height / 2f + pan.y
                        val gx   = (tapPos.x - cx) / scale
                        val gy   = (tapPos.y - cy) / scale
                        val hitR = 28f / scale
                        var best: GNode? = null
                        var bestD        = hitR
                        gNodes.forEach { nd ->
                            val d = sqrt((nd.x - gx).pow(2) + (nd.y - gy).pow(2))
                            if (d < bestD) { best = nd; bestD = d }
                        }
                        best?.let { onNodeClick(it.file) }
                    }
                },
        ) {
            // Reading `tick` registers this draw layer as a snapshot observer,
            // so Compose redraws the Canvas whenever the simulation ticks.
            @Suppress("UNUSED_VARIABLE")
            val frame = tick

            val cx = size.width  / 2f + pan.x
            val cy = size.height / 2f + pan.y
            val nr = (9f * scale).coerceIn(5f, 22f)

            // ── Draw edges ───────────────────────────────────────────────────
            gEdges.forEach { e ->
                val a = gNodes[e.a]; val b = gNodes[e.b]
                drawLine(
                    color       = edgeCol,
                    start       = Offset(cx + a.x * scale, cy + a.y * scale),
                    end         = Offset(cx + b.x * scale, cy + b.y * scale),
                    strokeWidth = 1.5f,
                )
            }

            // ── Draw nodes + labels ──────────────────────────────────────────
            val showLabels = scale >= 0.4f
            val labelAlpha = ((scale - 0.4f) / 0.4f).coerceIn(0f, 1f)

            gNodes.forEachIndexed { i, nd ->
                val sx       = cx + nd.x * scale
                val sy       = cy + nd.y * scale
                val isActive = (i == activeIdx)
                val r        = if (isActive) nr * 1.5f else nr

                drawCircle(
                    color  = if (isActive) activeCol else nodeCol,
                    radius = r,
                    center = Offset(sx, sy),
                )

                if (showLabels && labelAlpha > 0.05f) {
                    val fs   = (9f * scale.coerceIn(0.5f, 2.5f)).sp
                    val maxW = (130 * scale.coerceAtLeast(0.5f)).toInt().coerceAtLeast(40)
                    val layout = textMeasurer.measure(
                        text        = AnnotatedString(nd.file.displayName),
                        style       = TextStyle(
                            fontSize   = fs,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color      = labelCol.copy(alpha = labelAlpha),
                        ),
                        constraints = Constraints(maxWidth = maxW),
                        overflow    = TextOverflow.Ellipsis,
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft          = Offset(sx - layout.size.width / 2f, sy + r + 4f),
                    )
                }
            }
        }

        // ── Top bar (stats + close button + progress) ────────────────────────
        Column(
            modifier            = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
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
                            append(gEdges.size); append(if (gEdges.size == 1) " link" else " links")
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style    = MaterialTheme.typography.labelMedium,
                    )
                }

                FilledIconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close graph")
                }
            }

            if (!simDone) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        // ── Zoom controls (bottom-right) ─────────────────────────────────────
        Column(
            modifier            = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(
                onClick        = { scale = (scale * 1.4f).coerceAtMost(6f) },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom in")
            }
            SmallFloatingActionButton(
                onClick        = { scale = (scale / 1.4f).coerceAtLeast(0.1f) },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom out")
            }
        }

        // ── Empty state ──────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────────────
// Force-directed simulation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One step of the force simulation (called every ~16 ms on the main thread).
 * Uses O(n²/2) pairwise repulsion — fine for ≤ 200 nodes.
 */
private fun simulateStep(
    nodes: MutableList<GNode>,
    edges: List<GEdge>,
    iter:  Int,
    total: Int,
) {
    val n = nodes.size
    if (n < 2) return

    val cool      = 1f - iter.toFloat() / total   // cooling → velocities slow down over time
    val repulsion = 2800f
    val spring    = 0.04f
    val target    = 90f                            // desired edge length (px in graph-space)
    val gravity   = 0.015f                         // pull toward origin
    val damp      = 0.82f

    // ── Repulsion between every pair ─────────────────────────────────────────
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val dx   = nodes[j].x - nodes[i].x
            val dy   = nodes[j].y - nodes[i].y
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val f    = repulsion / (dist * dist)
            val fx   = f * dx / dist
            val fy   = f * dy / dist
            nodes[i].vx -= fx; nodes[i].vy -= fy
            nodes[j].vx += fx; nodes[j].vy += fy
        }
    }

    // ── Spring attraction along edges ────────────────────────────────────────
    edges.forEach { e ->
        val a    = nodes[e.a]; val b = nodes[e.b]
        val dx   = b.x - a.x;  val dy = b.y - a.y
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val f    = spring * (dist - target)
        val fx   = f * dx / dist;  val fy = f * dy / dist
        a.vx += fx; a.vy += fy
        b.vx -= fx; b.vy -= fy
    }

    // ── Center gravity ────────────────────────────────────────────────────────
    nodes.forEach { nd ->
        nd.vx -= nd.x * gravity
        nd.vy -= nd.y * gravity
    }

    // ── Integrate positions ───────────────────────────────────────────────────
    nodes.forEach { nd ->
        nd.vx *= damp; nd.vy *= damp
        nd.x  += nd.vx * cool
        nd.y  += nd.vy * cool
    }
}
