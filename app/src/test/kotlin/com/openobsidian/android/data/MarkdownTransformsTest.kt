package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTransformsTest {

    @Test
    fun `remove um comentario no meio da frase`() {
        assertEquals(
            "Texto  visível.",
            MarkdownTransforms.stripComments("Texto %%nota minha%% visível."),
        )
    }

    @Test
    fun `remove um comentario que atravessa linhas`() {
        val md = "Antes\n%%isto\nsome\ninteiro%%\nDepois"
        assertEquals("Antes\n\nDepois", MarkdownTransforms.stripComments(md))
    }

    @Test
    fun `dois comentarios nao viram um so`() {
        // Com regex gulosa, "fica" desapareceria junto — é o erro clássico aqui
        assertEquals(
            "a fica b",
            MarkdownTransforms.stripComments("a %%um%% fica %%dois%% b"),
        )
    }

    @Test
    fun `texto sem comentario passa intacto`() {
        val md = "# Título\n\nParágrafo com 100% de certeza e 50% de chance."
        assertEquals(md, MarkdownTransforms.stripComments(md))
    }

    @Test
    fun `um par de porcentos vazio nao e comentario`() {
        // `%%%%` não tem conteúdo; exigir ao menos um caractere evita comer
        // uma linha de separação escrita à toa
        assertEquals("%%%%", MarkdownTransforms.stripComments("%%%%"))
    }

    @Test
    fun `porcento solto sobrevive`() {
        assertEquals("aumento de 5% ao ano", MarkdownTransforms.stripComments("aumento de 5% ao ano"))
    }

    @Test
    fun `comentario pode engolir um bloco inteiro sem deixar restos`() {
        val md = "Visível\n\n%%## Título rascunho\n\n- item\n- item%%\n\nFim"
        assertEquals("Visível\n\n\n\nFim", MarkdownTransforms.stripComments(md))
    }
}
