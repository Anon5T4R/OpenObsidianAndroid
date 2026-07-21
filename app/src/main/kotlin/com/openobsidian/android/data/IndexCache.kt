package com.openobsidian.android.data

import org.json.JSONObject

/**
 * The text of every note, kept between sessions.
 *
 * Search, backlinks, tags and flashcards all read from an in-memory cache that
 * was rebuilt from zero on every launch — which over the Storage Access
 * Framework means one read per note, every time. On a vault of a few hundred
 * notes that is the slowest thing the app does, and it does it for content that
 * did not change.
 *
 * Entries are keyed by document URI and validated by modification time: a note
 * touched by another app (or by the desktop, on a synced folder) has a
 * different mtime and gets re-read.
 *
 * Works on [Ref], not on `Node.File`, so it carries no Android types and stays
 * testable on the JVM.
 */
object IndexCache {

    /** A note as the cache sees it: an opaque key and a modification time. */
    data class Ref(val key: String, val mtime: Long)

    data class Entry(val mtime: Long, val content: String)

    /**
     * Which notes still have to be read from disk.
     *
     * An mtime of 0 means the provider did not report one; the cache is never
     * trusted then, because staleness could not be detected at all.
     */
    fun staleKeys(files: List<Ref>, cached: Map<String, Entry>): List<Ref> =
        files.filter { f ->
            val entry = cached[f.key]
            entry == null || f.mtime == 0L || entry.mtime != f.mtime
        }

    /** The cached text of the notes that are still current. */
    fun freshContent(files: List<Ref>, cached: Map<String, Entry>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (f in files) {
            val entry = cached[f.key] ?: continue
            if (f.mtime != 0L && entry.mtime == f.mtime) out[f.key] = entry.content
        }
        return out
    }

    /**
     * What to persist after an indexing pass.
     *
     * A note that could not be read is left out entirely. Storing it as empty
     * text with a valid mtime would look current forever, and the note would
     * stay out of search, backlinks and tags across restarts — the exact bug
     * that bit the desktop app.
     */
    fun toEntries(files: List<Ref>, contents: Map<String, String>): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (f in files) {
            val text = contents[f.key] ?: continue
            if (f.mtime == 0L) continue
            out[f.key] = Entry(f.mtime, text)
        }
        return out
    }

    private const val VERSION = 1

    fun toJson(entries: Map<String, Entry>): String {
        val root = JSONObject()
        root.put("version", VERSION)
        val obj = JSONObject()
        for ((key, e) in entries) {
            obj.put(key, JSONObject().put("mtime", e.mtime).put("content", e.content))
        }
        root.put("entries", obj)
        return root.toString()
    }

    /** A cache that cannot be parsed is simply empty — never a crash on launch. */
    fun fromJson(json: String): Map<String, Entry> = runCatching {
        val root = JSONObject(json)
        // A format change invalidates everything rather than being guessed at
        if (root.optInt("version") != VERSION) return emptyMap()
        val obj = root.optJSONObject("entries") ?: return emptyMap()
        val out = LinkedHashMap<String, Entry>()
        for (key in obj.keys()) {
            val e = obj.getJSONObject(key)
            out[key] = Entry(e.optLong("mtime"), e.optString("content"))
        }
        out
    }.getOrElse { emptyMap() }
}
