package com.openobsidian.android.data

import androidx.annotation.StringRes
import com.openobsidian.android.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Everything the editor can insert, in one list.
 *
 * There used to be a hand-written list of 18 slash commands in MarkdownEditor,
 * with its labels hard-coded in English while the app shipped in three
 * languages — and it mentioned none of the things this app is actually for:
 * flashcards, callouts, Mermaid, maths, note embeds, query blocks. A feature
 * you can only use if you already know it exists does not exist, and on a phone
 * that is doubly true: there is no muscle memory for syntax you type once a week
 * on a small keyboard.
 *
 * The insert sheet, the `/` picker and the help text all read from here.
 *
 * ## Where the cursor lands
 *
 * The snippet carries a [CARET] marker saying where the cursor goes, and
 * [resolve] strips it. The desktop counts the offset backwards from the end of
 * the string by hand, and got it wrong three times — twice destructively, with
 * the caret landing *inside* a word so the first keystroke shredded the snippet.
 * A marker cannot be miscounted.
 */
object Insertables {

    /** Invisible marker for the cursor position. Never reaches the document. */
    const val CARET = '\u0001'

    enum class Category { BASICS, STRUCTURE, LINKS, STUDY, CALLOUTS, DIAGRAMS, DATA, SYMBOLS }

    data class Item(
        val id: String,
        val category: Category,
        /** Typed after `/`. The first one is the label shown in the menu. */
        val slash: List<String>,
        @StringRes val labelRes: Int,
        @StringRes val descRes: Int,
        /** May contain [CARET]; may be empty for items computed at insert time. */
        val raw: String,
        /** Occupies whole lines, so the sheet renders it as a block. */
        val block: Boolean = false,
        /** Opens the gallery instead of inserting text. */
        val isImageImport: Boolean = false,
    )

    data class Insertion(val text: String, val cursor: Int)

    val CATEGORY_ORDER = listOf(
        Category.BASICS, Category.STRUCTURE, Category.LINKS, Category.STUDY,
        Category.CALLOUTS, Category.DIAGRAMS, Category.DATA, Category.SYMBOLS,
    )

    @StringRes
    fun categoryLabel(c: Category): Int = when (c) {
        Category.BASICS -> R.string.ins_cat_basics
        Category.STRUCTURE -> R.string.ins_cat_structure
        Category.LINKS -> R.string.ins_cat_links
        Category.STUDY -> R.string.ins_cat_study
        Category.CALLOUTS -> R.string.ins_cat_callouts
        Category.DIAGRAMS -> R.string.ins_cat_diagrams
        Category.DATA -> R.string.ins_cat_data
        Category.SYMBOLS -> R.string.ins_cat_symbols
    }

    val ALL: List<Item> = listOf(
        // ── Basics ───────────────────────────────────────────────────────────
        Item("h1", Category.BASICS, listOf("h1", "heading1"), R.string.ins_h1, R.string.ins_h1_desc, "# $CARET"),
        Item("h2", Category.BASICS, listOf("h2", "heading2"), R.string.ins_h2, R.string.ins_h2_desc, "## $CARET"),
        Item("h3", Category.BASICS, listOf("h3", "heading3"), R.string.ins_h3, R.string.ins_h3_desc, "### $CARET"),
        Item("bold", Category.BASICS, listOf("bold"), R.string.ins_bold, R.string.ins_bold_desc, "**text$CARET**"),
        Item("italic", Category.BASICS, listOf("italic"), R.string.ins_italic, R.string.ins_italic_desc, "*text$CARET*"),
        Item("strike", Category.BASICS, listOf("strike"), R.string.ins_strike, R.string.ins_strike_desc, "~~text$CARET~~"),
        Item("highlight", Category.BASICS, listOf("highlight", "mark"), R.string.ins_highlight, R.string.ins_highlight_desc, "==text$CARET=="),
        Item("inlineCode", Category.BASICS, listOf("code"), R.string.ins_inline_code, R.string.ins_inline_code_desc, "`code$CARET`"),
        Item("comment", Category.BASICS, listOf("comment", "comentario"), R.string.ins_comment, R.string.ins_comment_desc, "%%text$CARET%%"),

        // ── Structure ────────────────────────────────────────────────────────
        Item("bulletList", Category.STRUCTURE, listOf("list", "bullet"), R.string.ins_bullet, R.string.ins_bullet_desc, "- $CARET"),
        Item("numList", Category.STRUCTURE, listOf("numlist", "ordered"), R.string.ins_numbered, R.string.ins_numbered_desc, "1. $CARET"),
        Item("task", Category.STRUCTURE, listOf("check", "task", "todo"), R.string.ins_task, R.string.ins_task_desc, "- [ ] $CARET"),
        Item("quote", Category.STRUCTURE, listOf("quote"), R.string.ins_quote, R.string.ins_quote_desc, "> $CARET"),
        Item("table", Category.STRUCTURE, listOf("table"), R.string.ins_table, R.string.ins_table_desc, "| Col 1 | Col 2 |\n|---|---|\n| $CARET | |\n", block = true),
        Item("codeBlock", Category.STRUCTURE, listOf("codeblock", "fence"), R.string.ins_code_block, R.string.ins_code_block_desc, "```\n$CARET\n```\n", block = true),
        Item("hr", Category.STRUCTURE, listOf("hr", "divider"), R.string.ins_hr, R.string.ins_hr_desc, "\n---\n$CARET", block = true),

        // ── Links ────────────────────────────────────────────────────────────
        Item("wikilink", Category.LINKS, listOf("wikilink", "link"), R.string.ins_wikilink, R.string.ins_wikilink_desc, "[[$CARET]]"),
        Item("wikilinkAlias", Category.LINKS, listOf("alias"), R.string.ins_wikilink_alias, R.string.ins_wikilink_alias_desc, "[[Note|$CARET]]"),
        Item("headingLink", Category.LINKS, listOf("section"), R.string.ins_heading_link, R.string.ins_heading_link_desc, "[[Note#$CARET]]"),
        Item("embedNote", Category.LINKS, listOf("embed"), R.string.ins_embed, R.string.ins_embed_desc, "![[$CARET]]"),
        Item("embedSection", Category.LINKS, listOf("embedsection"), R.string.ins_embed_section, R.string.ins_embed_section_desc, "![[Note#$CARET]]"),
        Item("webLink", Category.LINKS, listOf("url"), R.string.ins_web_link, R.string.ins_web_link_desc, "[text](https://$CARET)"),
        Item("imageRef", Category.LINKS, listOf("image"), R.string.ins_image, R.string.ins_image_desc, "![[$CARET.png]]"),
        Item("imagePick", Category.LINKS, listOf("gallery", "photo"), R.string.ins_image_pick, R.string.ins_image_pick_desc, "", isImageImport = true),

        // ── Study ────────────────────────────────────────────────────────────
        Item("cardQa", Category.STUDY, listOf("card", "flashcard"), R.string.ins_card_qa, R.string.ins_card_qa_desc, "> [!card]- Question$CARET\n> Answer\n", block = true),
        Item("cardCloze", Category.STUDY, listOf("cloze", "gap"), R.string.ins_card_cloze, R.string.ins_card_cloze_desc, "> [!card] Title\n> A sentence with ==the hidden term$CARET==.\n", block = true),
        Item("mnemonic", Category.STUDY, listOf("mnemonic"), R.string.ins_mnemonic, R.string.ins_mnemonic_desc, "> [!mnemonic]? Title$CARET\n> The mnemonic itself\n", block = true),

        // ── Callouts ─────────────────────────────────────────────────────────
        Item("calloutInfo", Category.CALLOUTS, listOf("info"), R.string.ins_callout_info, R.string.ins_callout_info_desc, "> [!info] Title\n> $CARET\n", block = true),
        Item("calloutTip", Category.CALLOUTS, listOf("tip"), R.string.ins_callout_tip, R.string.ins_callout_tip_desc, "> [!tip] Title\n> $CARET\n", block = true),
        Item("calloutWarning", Category.CALLOUTS, listOf("warning"), R.string.ins_callout_warning, R.string.ins_callout_warning_desc, "> [!warning] Title\n> $CARET\n", block = true),
        Item("calloutDanger", Category.CALLOUTS, listOf("danger"), R.string.ins_callout_danger, R.string.ins_callout_danger_desc, "> [!danger] Title\n> $CARET\n", block = true),
        Item("calloutSuccess", Category.CALLOUTS, listOf("success"), R.string.ins_callout_success, R.string.ins_callout_success_desc, "> [!success] Title\n> $CARET\n", block = true),
        Item("calloutQuestion", Category.CALLOUTS, listOf("question"), R.string.ins_callout_question, R.string.ins_callout_question_desc, "> [!question] Title\n> $CARET\n", block = true),
        Item("calloutExample", Category.CALLOUTS, listOf("example"), R.string.ins_callout_example, R.string.ins_callout_example_desc, "> [!example] Title\n> $CARET\n", block = true),
        Item("calloutFold", Category.CALLOUTS, listOf("fold", "collapse"), R.string.ins_callout_fold, R.string.ins_callout_fold_desc, "> [!note]- Title$CARET\n> Hidden until tapped\n", block = true),

        // ── Diagrams and maths ───────────────────────────────────────────────
        Item("mermaidFlow", Category.DIAGRAMS, listOf("mermaid", "flowchart"), R.string.ins_mermaid_flow, R.string.ins_mermaid_flow_desc, "```mermaid\nflowchart TD\n  A[$CARET] --> B{Decision}\n  B -->|yes| C[Result]\n  B -->|no| A\n```\n", block = true),
        Item("mermaidSequence", Category.DIAGRAMS, listOf("sequence"), R.string.ins_mermaid_sequence, R.string.ins_mermaid_sequence_desc, "```mermaid\nsequenceDiagram\n  A->>B: $CARET\n  B-->>A: Reply\n```\n", block = true),
        Item("mermaidMindmap", Category.DIAGRAMS, listOf("mindmap"), R.string.ins_mermaid_mindmap, R.string.ins_mermaid_mindmap_desc, "```mermaid\nmindmap\n  root(($CARET))\n    Branch\n    Branch 2\n```\n", block = true),
        Item("mathInline", Category.DIAGRAMS, listOf("math"), R.string.ins_math_inline, R.string.ins_math_inline_desc, "\$${CARET}x^2\$"),
        Item("mathBlock", Category.DIAGRAMS, listOf("mathblock", "formula"), R.string.ins_math_block, R.string.ins_math_block_desc, "\$\$\n$CARET\n\$\$\n", block = true),

        // ── Data ─────────────────────────────────────────────────────────────
        Item("frontmatter", Category.DATA, listOf("frontmatter", "yaml"), R.string.ins_frontmatter, R.string.ins_frontmatter_desc, "---\ntipo: $CARET\naliases:\n  - \ntags:\n  - \n---\n", block = true),
        Item("tag", Category.DATA, listOf("tag"), R.string.ins_tag, R.string.ins_tag_desc, "#$CARET"),
        Item("indexNote", Category.DATA, listOf("index", "indice"), R.string.ins_index, R.string.ins_index_desc, "## Section\n\n```query\n# fields: tag, pasta, tem, ordenar, limite — a line starting with # is a comment\ntag: $CARET\nordenar: titulo\n```\n\n## Another section\n\n```query\ntag: \nordenar: titulo\n```\n", block = true),
        Item("queryTag", Category.DATA, listOf("query"), R.string.ins_query_tag, R.string.ins_query_tag_desc, "```query\ntag: $CARET\nordenar: titulo\n```\n", block = true),
        Item("queryField", Category.DATA, listOf("queryfield"), R.string.ins_query_field, R.string.ins_query_field_desc, "```query\ntipo: $CARET\nordenar: modificado desc\nlimite: 20\n```\n", block = true),
        Item("today", Category.DATA, listOf("date", "today"), R.string.ins_today, R.string.ins_today_desc, ""),
        Item("now", Category.DATA, listOf("time", "now"), R.string.ins_now, R.string.ins_now_desc, ""),

        // ── Symbols ──────────────────────────────────────────────────────────
        Item("arrowRight", Category.SYMBOLS, listOf("rarr"), R.string.ins_arrow_right, R.string.ins_arrow_right_desc, "→"),
        Item("arrowLeft", Category.SYMBOLS, listOf("larr"), R.string.ins_arrow_left, R.string.ins_arrow_left_desc, "←"),
        Item("arrowUp", Category.SYMBOLS, listOf("uarr"), R.string.ins_arrow_up, R.string.ins_arrow_up_desc, "↑"),
        Item("arrowDown", Category.SYMBOLS, listOf("darr"), R.string.ins_arrow_down, R.string.ins_arrow_down_desc, "↓"),
        Item("check", Category.SYMBOLS, listOf("tick"), R.string.ins_check, R.string.ins_check_desc, "✓"),
        Item("cross", Category.SYMBOLS, listOf("cross"), R.string.ins_cross, R.string.ins_cross_desc, "✗"),
        Item("emDash", Category.SYMBOLS, listOf("dash"), R.string.ins_em_dash, R.string.ins_em_dash_desc, "—"),
        Item("ellipsis", Category.SYMBOLS, listOf("ellipsis"), R.string.ins_ellipsis, R.string.ins_ellipsis_desc, "…"),
    )

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * The text to insert and where the cursor goes, as an absolute offset into
     * that text.
     *
     * [now] is a parameter because `/date` read from a constant would insert the
     * day the app was launched — on a phone that stays in memory for a week,
     * that is Monday's date on Friday.
     */
    fun resolve(item: Item, now: LocalDateTime = LocalDateTime.now()): Insertion {
        val raw = when (item.id) {
            "today" -> DATE.format(now)
            "now" -> TIME.format(now)
            else -> item.raw
        }
        val at = raw.indexOf(CARET)
        val text = raw.replace(CARET.toString(), "")
        return Insertion(text, if (at < 0) text.length else at)
    }

    /**
     * Items matching [query], best first: an exact slash command, then a command
     * starting with it, then anything containing it. An empty query returns
     * everything in catalogue order, which is grouped by category.
     */
    fun search(query: String, items: List<Item> = ALL): List<Item> {
        val q = query.trim().lowercase().removePrefix("/")
        if (q.isEmpty()) return items

        val exact = mutableListOf<Item>()
        val prefix = mutableListOf<Item>()
        val contains = mutableListOf<Item>()
        for (item in items) {
            when {
                item.slash.any { it == q } -> exact += item
                item.slash.any { it.startsWith(q) } -> prefix += item
                item.slash.any { it.contains(q) } || item.id.lowercase().contains(q) -> contains += item
            }
        }
        return exact + prefix + contains
    }

    /** The command shown in the menu, with its slash. */
    fun primarySlash(item: Item): String = "/" + item.slash.first()
}
