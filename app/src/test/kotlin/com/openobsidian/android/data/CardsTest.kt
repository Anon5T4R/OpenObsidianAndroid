package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsTest {

    @Test
    fun `reads a question and answer card`() {
        val cards = Cards.extractCards("n.md", "> [!card]- Dose da adrenalina?\n> 0,5 mg IM")
        assertEquals(1, cards.size)
        assertEquals("Dose da adrenalina?", cards[0].q)
        assertEquals("0,5 mg IM", cards[0].a)
        assertEquals(Cards.Kind.QA, cards[0].kind)
    }

    @Test
    fun `each highlight becomes its own gap-fill card`() {
        val md = "> [!card] Krebs\n> ==Citrato sintase== condensa ==acetil-CoA=="
        val cards = Cards.extractCards("n.md", md)
        assertEquals(2, cards.size)
        assertEquals(listOf("Citrato sintase", "acetil-CoA"), cards.map { it.a })
        assertTrue(cards[0].q.contains(Cards.CLOZE_GAP))
        // Only the hidden one is a gap; the other shows its term
        assertTrue(cards[0].q.contains("acetil-CoA"))
    }

    @Test
    fun `finds a card nested inside another callout`() {
        val md = "> [!warning] Anafilaxia\n> Conduta:\n> > [!card]- Dose?\n> > 0,5 mg IM"
        val cards = Cards.extractCards("n.md", md)
        assertEquals(1, cards.size)
        assertEquals("Dose?", cards[0].q)
    }

    @Test
    fun `does not count a nested card twice`() {
        val md = "> [!warning] A\n> > [!card]- P\n> > R\n\n> [!card]- Solto\n> R2"
        assertEquals(listOf("P", "Solto"), Cards.extractCards("n.md", md).map { it.q })
    }

    @Test
    fun `ignores a card written inside a fenced block`() {
        val md = "```\n> [!card]- Exemplo\n> resposta\n```"
        assertEquals(0, Cards.extractCards("n.md", md).size)
    }

    @Test
    fun `a mnemonic only counts when marked reviewable`() {
        assertEquals(0, Cards.extractCards("n.md", "> [!mnemonic] ALICIA\n> corpo").size)
        assertEquals(1, Cards.extractCards("n.md", "> [!mnemonic]? ALICIA\n> corpo").size)
    }

    @Test
    fun `falls back to the first body line when there is no title`() {
        val cards = Cards.extractCards("n.md", "> [!card]\n> So o corpo")
        assertEquals("So o corpo", cards[0].q)
    }

    @Test
    fun `the id is stable while the question does not change`() {
        val a = Cards.extractCards("n.md", "> [!card]- P?\n> resposta velha")[0]
        val b = Cards.extractCards("n.md", "> [!card]- P?\n> resposta nova")[0]
        assertEquals(a.id, b.id)
    }

    @Test
    fun `a different question is a different card`() {
        val a = Cards.extractCards("n.md", "> [!card]- P1?\n> r")[0]
        val b = Cards.extractCards("n.md", "> [!card]- P2?\n> r")[0]
        assertTrue(a.id != b.id)
    }

    @Test
    fun `the same card in another note gets another id`() {
        val a = Cards.extractCards("a.md", "> [!card]- P?\n> r")[0]
        val b = Cards.extractCards("b.md", "> [!card]- P?\n> r")[0]
        assertTrue(a.id != b.id)
    }

    @Test
    fun `the id is 16 hex characters, like the desktop`() {
        val id = Cards.cardId("n.md", "q")
        assertEquals(16, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `a note with no cards yields none`() {
        assertEquals(0, Cards.extractCards("n.md", "# Titulo\n\ntexto").size)
    }
}
