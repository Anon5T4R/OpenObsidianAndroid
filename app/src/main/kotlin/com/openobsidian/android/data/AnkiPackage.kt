package com.openobsidian.android.data

/**
 * Reading an Anki `.apkg` — the pure half.
 *
 * A package is a ZIP holding a SQLite collection. Android already has SQLite,
 * so the database side needs no dependency at all; what needs care is turning
 * a row of Anki's `notes` table into a card, and that is what lives here.
 *
 * Ported from the desktop, ids included: the same deck imported on either side
 * produces the same cards.
 */
object AnkiPackage {

    /**
     * Anki joins a note's fields with the unit separator.
     * Written as an escape on purpose: the raw character is invisible in an
     * editor and does not survive being copied around.
     */
    const val FIELD_SEP = ""

    data class Card(val q: String, val a: String, val tags: List<String>, val cloze: Boolean = false)

    private val CLOZE_RE = Regex("\\{\\{c\\d+::(.*?)(?:::.*?)?\\}\\}")
    private val SOUND_RE = Regex("\\[sound:[^\\]]*\\]", RegexOption.IGNORE_CASE)
    private val IMG_RE = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("<[^>]+>")

    /**
     * One Anki field → Markdown.
     *
     * Media is dropped rather than left as a broken reference: the files live
     * inside the package under numeric names, and pulling them in is a job for
     * the desktop, which already does it. A dangling `[sound:x.mp3]` in the
     * middle of a card is worse than nothing.
     */
    fun fieldToMarkdown(s: String): String = s
        .replace(CLOZE_RE, "==$1==")
        .replace(Regex("\\[\\$\\$]([\\s\\S]*?)\\[/\\$\\$]"), "$$$1$$")
        .replace(Regex("\\[\\$]([\\s\\S]*?)\\[/\\$]"), "$$$1$$")
        .replace(SOUND_RE, "")
        .replace(IMG_RE, "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " · ")
        .replace(Regex("</?(div|p)\\b[^>]*>", RegexOption.IGNORE_CASE), " ")
        .replace(TAG_RE, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    /**
     * A row of Anki's `notes` table → a card.
     *
     * Cloze is detected from the text, not from the note-type table, whose
     * format changed between Anki versions. A cloze note has no answer field —
     * requiring one silently threw every cloze note away on the desktop, and
     * that bug is not worth having twice.
     */
    fun rowToCard(flds: String, tags: String): Card? {
        val fields = flds.split(FIELD_SEP)
        if (fields.isEmpty()) return null
        val cloze = Regex("\\{\\{c\\d+::").containsMatchIn(fields[0])
        val q = fieldToMarkdown(fields[0])
        val a = fieldToMarkdown(fields.drop(1).joinToString(" "))
        if (q.isEmpty() || (a.isEmpty() && !cloze)) return null
        val tagList = tags.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            // Anki nests tags with `::`; this app nests them with `/`
            .map { it.removePrefix("#").replace("::", "/") }
        return Card(q, a, tagList, cloze)
    }

    /** Deck name from the file name. */
    fun deckNameFor(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.')
            .replace('_', ' ').replace(Regex("\\s+"), " ").trim()
            .ifEmpty { "Anki" }

    /**
     * How many cards go in one note.
     *
     * A single note holding thousands of cards takes seconds to render; at a
     * hundred it is instant. A big deck becomes a folder of notes.
     */
    const val CARDS_PER_NOTE = 100

    fun chunk(cards: List<Card>, per: Int = CARDS_PER_NOTE): List<List<Card>> =
        if (cards.isEmpty()) emptyList() else cards.chunked(per)

    /** Cards → a Markdown note of card callouts. */
    fun toMarkdown(title: String, cards: List<Card>): String {
        val tags = cards.flatMap { it.tags }.distinct()
        val header = if (tags.isEmpty()) "" else tags.joinToString(" ") { "#$it" } + "\n\n"
        val body = cards.joinToString("\n\n") { c ->
            if (c.cloze) {
                // A cloze note becomes `> [!card]` with the sentence in the
                // body, which is the shape extractCards turns into one gap-fill
                // card per highlight
                "> [!card] $title\n> ${c.q.replace("\n", "\n> ")}"
            } else {
                "> [!card]- ${c.q.replace("\n", " ")}\n> ${c.a.replace("\n", "\n> ")}"
            }
        }
        return "# $title\n\n$header$body\n"
    }

    /** Which file inside the package holds the collection, newest layout first. */
    val COLLECTION_NAMES = listOf("collection.anki21b", "collection.anki21", "collection.anki2")

    private const val SQLITE_MAGIC = "SQLite format 3"

    fun isSqlite(head: ByteArray): Boolean =
        head.size >= 15 && String(head, 0, 15, Charsets.ISO_8859_1) == SQLITE_MAGIC

    /** Zstandard frame magic, 0xFD2FB528 little-endian on disk. */
    fun isZstd(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x28.toByte() && head[1] == 0xB5.toByte() &&
            head[2] == 0x2F.toByte() && head[3] == 0xFD.toByte()
}
