package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexCacheTest {

    private val a = IndexCache.Ref("uri://a", 1000L)
    private val b = IndexCache.Ref("uri://b", 2000L)

    @Test
    fun `nothing cached means everything is stale`() {
        assertEquals(listOf(a, b), IndexCache.staleKeys(listOf(a, b), emptyMap()))
    }

    @Test
    fun `a matching mtime is not stale`() {
        val cached = mapOf("uri://a" to IndexCache.Entry(1000L, "texto"))
        assertEquals(listOf(b), IndexCache.staleKeys(listOf(a, b), cached))
    }

    @Test
    fun `a changed mtime makes it stale again`() {
        // The note was edited elsewhere — on the desktop, over a synced folder
        val cached = mapOf("uri://a" to IndexCache.Entry(999L, "texto velho"))
        assertEquals(listOf(a), IndexCache.staleKeys(listOf(a), cached))
    }

    @Test
    fun `an mtime of zero is never trusted`() {
        // The provider reported no time, so staleness cannot be detected at all
        val noTime = IndexCache.Ref("uri://a", 0L)
        val cached = mapOf("uri://a" to IndexCache.Entry(0L, "texto"))
        assertEquals(listOf(noTime), IndexCache.staleKeys(listOf(noTime), cached))
        assertTrue(IndexCache.freshContent(listOf(noTime), cached).isEmpty())
    }

    @Test
    fun `fresh content comes back, stale content does not`() {
        val cached = mapOf(
            "uri://a" to IndexCache.Entry(1000L, "de a"),
            "uri://b" to IndexCache.Entry(1L, "de b, velho"),
        )
        val fresh = IndexCache.freshContent(listOf(a, b), cached)
        assertEquals(mapOf("uri://a" to "de a"), fresh)
    }

    @Test
    fun `a note that could not be read is not persisted`() {
        // Persisting it as "" with a valid mtime would look current forever and
        // keep the note out of search and backlinks across restarts
        val entries = IndexCache.toEntries(listOf(a, b), mapOf("uri://a" to "so a"))
        assertEquals(setOf("uri://a"), entries.keys)
    }

    @Test
    fun `a note without an mtime is not persisted either`() {
        val noTime = IndexCache.Ref("uri://x", 0L)
        assertTrue(IndexCache.toEntries(listOf(noTime), mapOf("uri://x" to "texto")).isEmpty())
    }

    @Test
    fun `entries survive a round trip`() {
        val entries = mapOf(
            "uri://a" to IndexCache.Entry(1000L, "com acento é e \"aspas\""),
            "uri://b" to IndexCache.Entry(2000L, "linha1\nlinha2"),
        )
        val back = IndexCache.fromJson(IndexCache.toJson(entries))
        assertEquals(entries, back)
    }

    @Test
    fun `a broken cache is empty instead of a crash`() {
        assertTrue(IndexCache.fromJson("{ nao e json").isEmpty())
        assertTrue(IndexCache.fromJson("").isEmpty())
    }

    @Test
    fun `a cache from another format version is discarded`() {
        val old = """{"version":99,"entries":{"uri://a":{"mtime":1,"content":"x"}}}"""
        assertTrue(IndexCache.fromJson(old).isEmpty())
    }
}
