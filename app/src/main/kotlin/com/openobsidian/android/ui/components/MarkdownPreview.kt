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
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.SchemeHandler
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

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
 *   - Callouts > [!tipo] Título → blockquote com emoji + título em negrito
 *   - Matemática LaTeX: $$bloco$$, $$inline$$, $inline$, \(inline\), \[bloco\]
 *   - External links in system browser
 */
@Composable
fun MarkdownPreview(
    content: String,
    modifier: Modifier = Modifier,
    onWikilinkClick: (String) -> Unit = {},
    resolveImage: ((String) -> Uri?)? = null,
    onToggleCheckbox: ((String) -> Unit)? = null,
    scrollConnection: PreviewScrollConnection? = null,
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

    val markwon = remember(context, fontSize, textColor) {
        // JLatexMath quer o tamanho em pixels; o TextView recebe o valor em sp.
        val latexTextSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            fontSize,
            context.resources.displayMetrics,
        )
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))

            // ── Matemática LaTeX ($$…$$ bloco e inline) ──────────────────
            // O inline-parser é pré-requisito do modo inline do ext-latex.
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(latexTextSize) { builder ->
                    builder.inlinesEnabled(true)
                    builder.theme().textColor(textColor)
                }
            )

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

            // Registra o handler de "rolar até o heading" usado pelo sumário.
            // Procura a occurrence-ésima linha renderizada idêntica ao texto do
            // heading; se não achar linha exata, cai na primeira ocorrência.
            scrollConnection?.handler = { plainText, occurrence ->
                val layout = tv.layout
                val text   = tv.text?.toString()
                if (layout != null && text != null && plainText.isNotEmpty()) {
                    var target   = -1
                    var firstAny = -1
                    var seen     = 0
                    var from     = 0
                    while (true) {
                        val i = text.indexOf(plainText, from)
                        if (i < 0) break
                        if (firstAny < 0) firstAny = i
                        val end     = i + plainText.length
                        val ownLine = (i == 0 || text[i - 1] == '\n') &&
                                      (end == text.length || text[end] == '\n')
                        if (ownLine) {
                            if (seen == occurrence) { target = i; break }
                            seen++
                        }
                        from = i + 1
                    }
                    if (target < 0) target = firstAny
                    if (target >= 0) {
                        val line = layout.getLineForOffset(target)
                        val y    = (tv.totalPaddingTop + layout.getLineTop(line) - 8)
                            .coerceAtLeast(0)
                        scrollView.smoothScrollTo(0, y)
                    }
                }
            }
        },
        modifier = modifier,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Preprocessing
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Transforms Obsidian-specific syntax before Markwon parses. Fenced code
 * blocks (``` / ~~~) are left untouched.
 *   ![[image.png]]           → ![image.png](content://uri)  (if resolver finds the file)
 *   [[Note Name]]            → [Note Name](openobsidian:Note%20Name)
 *   [[Note|Display Text]]    → [Display Text](openobsidian:Note%20Name)
 *   > [!warning] Título      → > ⚠️ **Título**  (callouts do Obsidian)
 *   \[…\] · \(…\) · $…$      → $$…$$            (formas que o ext-latex entende)
 */
private fun preprocessMarkdown(
    content: String,
    resolveImage: ((String) -> Uri?)? = null,
): String = mapOutsideCodeFences(content) { segment ->
    var out = segment

    // ── Callouts > [!tipo] Título (o sufixo +/- de colapso é ignorado) ────
    out = CALLOUT_REGEX.replace(out) { m ->
        val prefix = m.groupValues[1]
        val type   = m.groupValues[2].lowercase()
        val title  = m.groupValues[3].trim()
        val emoji  = CALLOUT_EMOJI[type] ?: "📝"
        val label  = title.ifEmpty { type.replaceFirstChar { c -> c.uppercase() } }
        "$prefix$emoji **$label**"
    }

    // ── Matemática: normaliza \[…\], \(…\) e $…$ para $$…$$ ──────────────
    out = DISPLAY_MATH_REGEX.replace(out) { m -> "\n$$\n${m.groupValues[1].trim()}\n$$\n" }
    out = PAREN_MATH_REGEX.replace(out)   { m -> "$$${m.groupValues[1].trim()}$$" }
    out = INLINE_MATH_REGEX.replace(out)  { m -> "$$${m.groupValues[1]}$$" }

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

    out
}

/**
 * Applies [transform] only to the stretches of [src] that are outside fenced
 * code blocks, keeping the fences byte-for-byte intact.
 */
private fun mapOutsideCodeFences(src: String, transform: (String) -> String): String {
    val segments = mutableListOf<Pair<Boolean, MutableList<String>>>() // isCode → lines
    var fence: String? = null
    for (line in src.split("\n")) {
        val t = line.trimStart()
        if (fence == null) {
            if (t.startsWith("```") || t.startsWith("~~~")) {
                fence = t.take(3)
                segments.add(true to mutableListOf(line))
            } else {
                if (segments.isEmpty() || segments.last().first) segments.add(false to mutableListOf())
                segments.last().second.add(line)
            }
        } else {
            segments.last().second.add(line)
            if (t.startsWith(fence)) fence = null
        }
    }
    return segments.joinToString("\n") { (isCode, lines) ->
        val text = lines.joinToString("\n")
        if (isCode) text else transform(text)
    }
}

// Callouts do Obsidian: tipo → emoji (espelha os tipos do desktop).
private val CALLOUT_REGEX = Regex("^(\\s{0,3}>\\s*)\\[!([A-Za-z]+)][+-]?\\s?(.*)$", RegexOption.MULTILINE)
private val CALLOUT_EMOJI = mapOf(
    "note" to "📝", "abstract" to "📋", "summary" to "📋", "tldr" to "📋",
    "info" to "ℹ️", "todo" to "☑️",
    "tip" to "💡", "hint" to "💡", "important" to "💡",
    "success" to "✅", "check" to "✅", "done" to "✅",
    "question" to "❓", "help" to "❓", "faq" to "❓",
    "warning" to "⚠️", "caution" to "⚠️", "attention" to "⚠️",
    "failure" to "✖️", "fail" to "✖️", "missing" to "✖️",
    "danger" to "⛔", "error" to "❌", "bug" to "🐛",
    "example" to "🧪", "quote" to "💬", "cite" to "💬",
)

// \[ … \] em bloco e \( … \) inline (sintaxe alternativa, como no desktop).
private val DISPLAY_MATH_REGEX = Regex("""\\\[(.+?)\\]""", RegexOption.DOT_MATCHES_ALL)
private val PAREN_MATH_REGEX   = Regex("""\\\((.+?)\\\)""")

// $…$ inline. Conservador contra falsos positivos de moeda (igual ao desktop):
// nada de espaço logo após o $ de abertura nem antes do de fechamento, e $$ é
// deixado intacto para o parser de blocos/inline do ext-latex.
private val INLINE_MATH_REGEX = Regex("""(?<![\\$])\$(?![\s$])([^$\n]+?)(?<![\s\\])\$(?![\d$])""")

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
