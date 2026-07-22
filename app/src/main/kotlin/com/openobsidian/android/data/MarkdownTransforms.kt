package com.openobsidian.android.data

/**
 * Transformações puras de Markdown.
 *
 * Moram aqui, e não dentro do Composable do preview, porque sem JDK nesta
 * máquina o teste do CI é o único retorno que existe antes do APK — e um passe
 * de regex é exatamente o tipo de código em que um caso esquecido passa
 * despercebido na tela.
 */
object MarkdownTransforms {

    // Preguiçoso (`.+?`) de propósito: com `.+` guloso, dois comentários na
    // mesma nota virariam um só e tudo que estivesse entre eles sumiria.
    // `DOT_MATCHES_ALL` é o que deixa um comentário atravessar linhas.
    private val COMMENT = Regex("%%.+?%%", RegexOption.DOT_MATCHES_ALL)

    /**
     * Remove `%%comentário%%`.
     *
     * A anotação continua no arquivo e some da leitura. Quem chama é
     * responsável por não aplicar isto dentro de bloco de código — no preview
     * é o `mapOutsideCodeFences` que garante isso, para que um exemplo
     * documentando a própria sintaxe não desapareça.
     */
    fun stripComments(md: String): String = COMMENT.replace(md, "")
}
