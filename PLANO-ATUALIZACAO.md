# Plano de atualização — OpenObsidian Android

> Comparativo entre o **desktop v1.0.0** (Electron) e o **Android 1.4.0**.
> Atualizado em 2026-07-21. A versão anterior deste plano comparava com o
> desktop v0.7.5 e ficou quatro versões defasada.

## Onde cada um está

| Recurso (desktop v1.0.0) | Android 1.4.0 | Ação |
|---|---|---|
| Editor com realce + Edit/Preview/Split | ✅ | — |
| Wikilinks, backlinks, busca full-text | ✅ | — |
| Daily note, nota companheira, pin, templates | ✅ | — |
| Tabelas, checkbox clicável, `==destaque==` | ✅ | — |
| Imagens, PDF, DOCX + conversão, export PDF | ✅ | — |
| Matemática (KaTeX / JLatexMath), callouts, TOC, find & replace | ✅ | — |
| **Renomear reescreve os `[[links]]`** | ✅ 1.4.0 | — |
| **Gravação que falha aparece; nota ilegível não abre vazia** | ✅ 1.4.0 | — |
| **Frontmatter YAML + tags com acento/hierarquia** | ✅ 1.4.0 | — |
| **Flashcards com SM-2 + revisão** | ✅ 1.4.0 | — |
| Gravação atômica | ⚠️ parcial | SAF não tem substituição atômica; há cópia `.bak` antes de truncar |
| **Wikilink com `#seção` e `Pasta/Nota`** | ✅ 2.2.0 | — |
| Embed `![[Nota]]` de nota (imagem já funciona) | ❌ | Fase 2 |
| **Aliases do frontmatter resolvendo links** | ✅ 2.2.0 | — |
| **Diagnóstico do vault** | ✅ 2.2.0 | Links mortos, órfãs e nomes duplicados |
| **Busca com operadores** | ✅ 2.2.0 | `tag:`, `path:`, `file:`, frase exata, `-exclusão`, por relevância |
| Blocos ```query | ❌ | Fase 3 |
| Calendário sobre as notas diárias | ❌ | Fase 3 |
| Import de `.apkg` do Anki | ❌ | Fase 3 — sql.js não serve aqui; avaliar SQLite nativo |
| Estatísticas de revisão (retenção, previsão) | ❌ | Fase 3 |
| **i18n (PT-BR / EN / ES)** | ✅ 2.1.0 | ~95 strings em strings.xml + values-pt-rBR + values-es |
| **Mermaid** | ✅ 2.0.0 | Toque abre em tela cheia com zoom; `mermaid.min.js` nos assets, WebView sem rede |
| EPUB, backup do vault, cache persistente do índice | ❌ | Fase 3 |
| Grafo (D3) | ❌ | **Não portar** — tentado e removido de propósito (commit 4b90284) |
| Chat/IA local, sistema de plugins | ❌ | Fora de escopo no mobile |

## O que a 1.4.0 fez

Integridade primeiro, igual ao que o desktop fez na v0.9.0 e na v1.0.0. Cinco
caminhos de perda silenciosa estavam abertos aqui, e o primeiro não existia nem
no desktop:

1. **Leitura que falha virava nota vazia** — `readText` devolvia `""` e o
   chamador ainda tinha `getOrDefault("")`. O editor abria em branco e a
   primeira tecla fazia o autosave gravar esse branco por cima de uma nota
   intacta. Agora `readTextOrNull` devolve `null` e o que não foi lido não é
   sobrescrito.
2. **Gravação que falha dizia que deu certo** — `runCatching` engolia o erro e
   o `isDirty = false` era incondicional.
3. **Gravação truncava antes de escrever** — sem substituição atômica no SAF,
   entra uma cópia `.bak` oculta antes.
4. **Renomear quebrava todos os `[[links]]`**.
5. **Índice guardava `""` para leitura que falhou**, tirando a nota dos
   backlinks e da busca sem sinal nenhum.

E o repo passou a ter testes: **47**, rodando no CI antes do APK. Não havia
nenhum.

## Regras de trabalho (deste repo)

- **Sem build local** nesta máquina (sem JDK/Android SDK) — o juiz é o
  `ci.yml` do GitHub Actions, que agora roda `testDebugUnitTest` antes do APK.
  Push primeiro, esperar verde.
- Release por tag `vX.Y.Z` (a tag tem que bater com o `versionName`), assinada
  com a keystore que vem dos Secrets `KEYSTORE_B64` e `KEYSTORE_PROPERTIES_B64`.
- Commits em português, sem Co-Authored-By, estilo "vX.Y.Z: descrição".
- **Os ids de flashcard são hash de `relativePath::pergunta`, idênticos aos do
  desktop.** Mexer nisso quebra o `srs.json` compartilhado: cada lado passaria
  a criar seus próprios ids e o mesmo cartão seria agendado duas vezes.
