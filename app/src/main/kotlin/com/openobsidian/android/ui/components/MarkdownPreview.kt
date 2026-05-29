package com.openobsidian.android.ui.components

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.TextView
import io.noties.markwon.ext.tasklist.TaskListSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.openobsidian.android.data.LocalAppSettings
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.SchemeHandler

// Private sentinels for ==highlight== spans
private const val MARK_OPEN  = ''
private const val MARK_CLOSE = ''

/**
 * Renders markdown via Markwon inside a native ScrollView + TextView.
 *
 * Supports:
 *   - [[Wikilinks]] → clickable (fires onWikilinkClick)
 *   - ![[image.png]] → rendered image (if resolveImage is provided)
 *   - ![alt](content://uri) → SAF images via ContentSchemeHandler
 *   - ==Highlight== → yellow background span
 *   - Tables, task lists, strikethrough
 *   - External links in system browser
 */
@Composable
fun MarkdownPreview(
    content: String,
    modifier: Modifier = Modifier,
    onWikilinkClick: (String) -> Unit = {},
    resolveImage: ((String) -> Uri?)? = null,
    onToggleCheckbox: ((String) -> Unit)? = null,
) {
    val context   = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val fontSize  = LocalAppSettings.current.previewFontSize.sp

    // Stable callback refs — updated after each composition
    val callbackRef      = remember { mutableStateOf(onWikilinkClick) }
    val imageResolverRef = remember { mutableStateOf(resolveImage) }
    val contentRef       = remember { mutableStateOf(content) }
    val toggleRef        = remember { mutableStateOf(onToggleCheckbox) }
    SideEffect {
        callbackRef.value      = onWikilinkClick
        imageResolverRef.value = resolveImage
        contentRef.value       = content
        toggleRef.value        = onToggleCheckbox
    }

    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))

            // ── Image rendering (SAF content:// URIs) ────────────────────
            .usePlugin(
                ImagesPlugin.create(ImagesPlugin.ImagesConfigure { plugin ->
                    plugin.addSchemeHandler(ContentSchemeHandler(context))
                })
            )

            // ── ==Highlight== support ────────────────────────────────────
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun processMarkdown(markdown: String): String =
                    markdown.replace(Regex("==([^=\n]+)=="), "$MARK_OPEN$1$MARK_CLOSE")

                override fun afterSetText(textView: TextView) {
                    val original = textView.text ?: return
                    if (!original.contains(MARK_OPEN)) return

                    val ssb = SpannableStringBuilder(original)
                    var offset = 0
                    while (offset < ssb.length) {
                        val raw   = ssb.toString()
                        val open  = raw.indexOf(MARK_OPEN,  offset)
                        if (open < 0) break
                        val close = raw.indexOf(MARK_CLOSE, open + 1)
                        if (close < 0) break

                        ssb.setSpan(
                            BackgroundColorSpan(0xFFFFEE44.toInt()),
                            open + 1, close,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        ssb.delete(close, close + 1)
                        ssb.delete(open,  open  + 1)
                        offset = open
                    }
                    textView.text = ssb
                }
            })

            // ── Wikilinks + external link resolver ───────────────────────
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        if (link.startsWith("openobsidian:")) {
                            val name = Uri.decode(link.removePrefix("openobsidian:"))
                            callbackRef.value(name)
                        } else {
                            runCatching {
                                view.context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        Uri.parse(link),
                                    )
                                )
                            }
                        }
                    }
                }
            })

            .build()
    }

    AndroidView(
        factory = { ctx ->
            val padding = (ctx.resources.displayMetrics.density * 16).toInt()
            val tv = TextView(ctx).apply {
                setPadding(padding, padding, padding, padding * 2)
                textSize = fontSize
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextIsSelectable(true)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            // Tap-to-toggle task-list checkboxes. Maps the tapped rendered line
            // back to the Nth `- [ ]` in the source and flips it.
            tv.setOnTouchListener { _, ev ->
                if (ev.action != MotionEvent.ACTION_UP) return@setOnTouchListener false
                val toggle = toggleRef.value ?: return@setOnTouchListener false
                val layout = tv.layout ?: return@setOnTouchListener false
                val text   = tv.text as? Spanned ?: return@setOnTouchListener false

                val xCoord = ev.x - tv.totalPaddingLeft + tv.scrollX
                val yCoord = (ev.y - tv.totalPaddingTop + tv.scrollY).toInt()
                val line      = layout.getLineForVertical(yCoord)
                val lineStart = layout.getLineStart(line)

                val taskAtLine = text.getSpans(lineStart, lineStart, TaskListSpan::class.java)
                if (taskAtLine.isEmpty()) return@setOnTouchListener false

                // If the tap landed on a link inside the line, let it navigate instead.
                val offset = layout.getOffsetForHorizontal(line, xCoord)
                if (text.getSpans(offset, offset, ClickableSpan::class.java).isNotEmpty()) {
                    return@setOnTouchListener false
                }

                val all = text.getSpans(0, text.length, TaskListSpan::class.java)
                    .sortedBy { text.getSpanStart(it) }
                val idx = all.indexOf(taskAtLine[0])
                if (idx < 0) return@setOnTouchListener false

                val newContent = toggleTaskInMarkdown(contentRef.value, idx)
                if (newContent != contentRef.value) toggle(newContent)
                true
            }
            ScrollView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isScrollbarFadingEnabled = true
                addView(tv)
            }
        },
        update = { scrollView ->
            val tv = scrollView.getChildAt(0) as TextView
            tv.setTextColor(textColor)
            tv.textSize = fontSize
            markwon.setMarkdown(tv, preprocessMarkdown(content, imageResolverRef.value))
        },
        modifier = modifier,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Preprocessing
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Transforms Obsidian-specific syntax before Markwon parses:
 *   ![[image.png]]           → ![image.png](content://uri)  (if resolver finds the file)
 *   [[Note Name]]            → [Note Name](openobsidian:Note%20Name)
 *   [[Note|Display Text]]    → [Display Text](openobsidian:Note%20Name)
 */
private fun preprocessMarkdown(
    content: String,
    resolveImage: ((String) -> Uri?)? = null,
): String {
    var out = content

    // ── Obsidian image syntax ![[filename]] ──────────────────────────────
    if (resolveImage != null) {
        out = out.replace(Regex("!\\[\\[([^\\]\n]+?)]]")) { m ->
            val name = m.groupValues[1].trim()
            val uri  = resolveImage(name)
            if (uri != null) "![$name]($uri)" else m.value
        }
    }

    // ── Wikilinks [[target]] or [[target|display]]
    // Negative lookbehind (?<!!) ensures we skip ![[...]] (image syntax above)
    out = out.replace(Regex("(?<!!)\\[\\[([^\\]|\n]+?)(?:\\|([^\\]\n]+?))?]]")) { m ->
        val target  = m.groupValues[1].trim()
        val display = m.groupValues[2].trim().ifEmpty { target }
        "[$display](openobsidian:${Uri.encode(target)})"
    }

    return out
}

/**
 * Flips the checkbox state of the [taskIndex]-th GFM task item in [src].
 * Order matches Markwon's top-to-bottom TaskListSpan ordering.
 */
private val TASK_REGEX = Regex("^(\\s*[-*+]\\s+\\[)([ xX])(])", RegexOption.MULTILINE)

private fun toggleTaskInMarkdown(src: String, taskIndex: Int): String {
    var i = 0
    return TASK_REGEX.replace(src) { m ->
        if (i++ == taskIndex) {
            val flipped = if (m.groupValues[2] == " ") "x" else " "
            "${m.groupValues[1]}$flipped${m.groupValues[3]}"
        } else m.value
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SAF image scheme handler
// ─────────────────────────────────────────────────────────────────────────────

private class ContentSchemeHandler(private val context: Context) : SchemeHandler() {
    override fun handle(raw: String, uri: Uri): ImageItem {
        return runCatching {
            val stream = context.contentResolver.openInputStream(uri)!!
            ImageItem.withDecodingNeeded(raw, stream)
        }.getOrElse {
            ImageItem.withResult(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    override fun supportedSchemes(): Collection<String> = listOf("content")
}
