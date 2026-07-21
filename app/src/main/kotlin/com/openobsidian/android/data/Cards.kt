package com.openobsidian.android.data

/**
 * Finding flashcards inside a note.
 *
 * A card is a callout plus a highlight — syntax the preview already renders —
 * so the note stays readable in any Markdown editor and the cards degrade into
 * plain prose when there is no review engine around.
 *
 * ```markdown
 * > [!card]- Question
 * > Answer
 *
 * > [!card] Title
 * > a sentence with ==highlights==     ← one gap-fill card per highlight
 * ```
 *
 * Ported from the desktop app, ids included: the same note produces the same
 * ids on both sides, so a card reviewed on the phone is the same card on the
 * computer.
 */
object Cards {

    enum class Kind { QA, CLOZE, MNEMONIC }

    data class Card(
        /** Stable while the question does not change */
        val id: String,
        val kind: Kind,
        /** The question, or the sentence with its gaps */
        val q: String,
        /** The answer, or the term hidden behind the gap */
        val a: String,
    )

    private val CARD_TYPES = setOf("card", "flashcard", "pergunta")
    private val MNEMONIC_TYPES = setOf("mnemonic", "mnemonico", "mnemônico")

    /** Placeholder that replaces the answer inside a cloze sentence. */
    const val CLOZE_GAP = "[ … ]"

    /**
     * Fowler/Noll/Vo 1a. The id only has to be stable and collision-resistant
     * enough for one vault, and identical to the desktop's — hence the same
     * two passes, the second one over the reversed input so near-identical
     * questions stay apart.
     */
    fun cardId(relativePath: String, question: String, suffix: String = ""): String {
        val input = if (suffix.isEmpty()) "$relativePath::$question"
        else "$relativePath::$question::$suffix"
        var h = 0x811c9dc5.toInt()
        for (ch in input) {
            h = h xor ch.code
            h *= 0x01000193
        }
        var h2 = 0x811c9dc5.toInt()
        for (i in input.indices.reversed()) {
            h2 = h2 xor input[i].code
            h2 *= 0x01000193
        }
        return hex8(h) + hex8(h2)
    }

    private fun hex8(v: Int): String =
        (v.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

    private val HIGHLIGHT_RE = Regex("==([^=\\n]+)==")

    /** `==term==` occurrences, in order. */
    fun clozeParts(text: String): List<String> =
        HIGHLIGHT_RE.findAll(text).map { it.groupValues[1].trim() }.toList()

    /** Replaces the nth `==term==` with a gap and unwraps the others. */
    fun renderCloze(text: String, hideIndex: Int): String {
        var i = 0
        return HIGHLIGHT_RE.replace(text) { m ->
            val out = if (i == hideIndex) CLOZE_GAP else m.groupValues[1]
            i++
            out
        }
    }

    private data class Callout(
        val type: String,
        val fold: String,
        val title: String,
        val body: String,
    )

    /** How deep a callout may sit inside other callouts before we stop looking. */
    private const val MAX_NESTING = 3

    private val HEAD_RE = Regex("^>\\s*\\[!([\\p{L}\\p{N}]+)]([-+?]?)\\s*(.*)$")

    /**
     * Callout blocks in raw markdown, `> ` prefixes removed.
     *
     * A card written inside another callout — `> > [!card]` under a
     * `> [!warning]` — is natural to write and used to be dropped without a
     * word. Removing one `>` level makes the inner one top-level.
     */
    private fun parseCallouts(md: String, depth: Int = 0): List<Callout> {
        val out = mutableListOf<Callout>()
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val head = HEAD_RE.find(lines[i])
            if (head == null) { i++; continue }
            val body = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size && lines[j].startsWith(">")) {
                body += lines[j].removePrefix(">").removePrefix(" ")
                j++
            }
            val text = body.joinToString("\n")
            out += Callout(
                type = head.groupValues[1].lowercase(),
                fold = head.groupValues[2],
                title = head.groupValues[3].trim(),
                body = text.trim(),
            )
            if (depth < MAX_NESTING && Regex("^>\\s*\\[!", RegexOption.MULTILINE).containsMatchIn(text)) {
                out += parseCallouts(text, depth + 1)
            }
            i = j
        }
        return out
    }

    private val FENCE_RE = Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~")

    /** Blanks out fenced code so a card written as an example is not collected. */
    private fun stripFences(md: String): String =
        FENCE_RE.replace(md) { block -> block.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }

    /** The cards declared in a note. */
    fun extractCards(relativePath: String, content: String): List<Card> {
        val cards = mutableListOf<Card>()
        for (callout in parseCallouts(stripFences(content))) {
            val isCard = callout.type in CARD_TYPES
            val isMnemonic = callout.type in MNEMONIC_TYPES
            if (!isCard && !isMnemonic) continue
            // Only `?` marks a mnemonic as reviewable; without it, it is decoration
            if (isMnemonic && callout.fold != "?") continue

            val gaps = clozeParts(callout.body)
            if (isCard && gaps.isNotEmpty()) {
                gaps.forEachIndexed { index, term ->
                    val q = renderCloze(callout.body, index)
                    cards += Card(
                        id = cardId(relativePath, callout.body, "c$index"),
                        kind = Kind.CLOZE,
                        q = if (callout.title.isNotEmpty()) "${callout.title} — $q" else q,
                        a = term,
                    )
                }
                continue
            }

            val question = callout.title.ifEmpty { callout.body.lineSequence().firstOrNull().orEmpty() }
            if (question.isEmpty()) continue
            cards += Card(
                id = cardId(relativePath, question),
                kind = if (isMnemonic) Kind.MNEMONIC else Kind.QA,
                q = question,
                a = callout.body,
            )
        }
        return cards
    }
}
