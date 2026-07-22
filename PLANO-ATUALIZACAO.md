# Plano de atualização — OpenObsidian Android

> Comparativo entre o **desktop v1.2.1** (Electron) e o **Android 3.4.0**.
> Atualizado em 2026-07-22. A versão anterior comparava com o desktop v1.0.0 e
> tinha ficado três releases defasada — o que fazia ela mentir por omissão sobre
> o que faltava.

## Onde cada um está

| Recurso (desktop v1.2.1) | Android | Ação |
|---|---|---|
| Editor com realce + Edit/Preview/Split | ✅ | — |
| Wikilinks, backlinks, busca full-text | ✅ | — |
| Daily note, nota companheira, pin, templates | ✅ | — |
| Tabelas, checkbox clicável, `==destaque==` | ✅ | — |
| Imagens, PDF, DOCX + conversão, export PDF | ✅ | — |
| Matemática, callouts, TOC, find & replace | ✅ | — |
| Renomear reescreve os `[[links]]` | ✅ 1.4.0 | — |
| Gravação que falha aparece; nota ilegível não abre vazia | ✅ 1.4.0 | — |
| Frontmatter YAML + tags com acento/hierarquia | ✅ 1.4.0 | — |
| Flashcards com SM-2 + revisão | ✅ 1.4.0 | — |
| Wikilink com `#seção` e `Pasta/Nota` | ✅ 2.2.0 | — |
| Aliases do frontmatter resolvendo links | ✅ 2.2.0 | — |
| Diagnóstico do vault | ✅ 2.2.0 | — |
| Busca com operadores | ✅ 2.2.0 | — |
| Blocos ```query | ✅ 2.4.0 | — |
| Import de `.apkg` do Anki | ✅ 3.0.0 | zstd é recusado com instrução de reexportar |
| Estatísticas de revisão | ✅ 3.0.0 | — |
| i18n (PT-BR / EN / ES) | ✅ 2.1.0 | ~270 strings |
| Mermaid | ✅ 2.0.0 | — |
| Backup do vault (.zip) | ✅ 2.3.0 | — |
| Cache persistente do índice | ✅ 2.3.0 | — |
| EPUB | ✅ 3.0.0 | — |
| **Catálogo único de inserção (menu + `/`)** | ✅ 3.1.0 | 55 entradas, traduzidas |
| **Autocomplete de tag no `#`** | ✅ 3.1.0 | Ordenado por uso, com contagem |
| **Botão de inserir na barra** | ✅ 3.2.0 | `/` sozinho ninguém descobre no celular |
| **`sort: criado` com aviso quando não serve** | ✅ 3.3.0 | Fecha a divergência de gramática com o desktop |
| Gravação atômica | ⚠️ parcial | SAF não tem substituição atômica; há cópia `.bak` antes de truncar. **Teto da plataforma, não pendência** |
| Ler `.odt` | ✅ 3.4.0 | Parser XML escrito à mão, como no desktop: o `XmlPullParser` do Android é stub em teste unitário |
| Embed `![[Nota]]` de nota (imagem já funciona) | ✅ 3.4.0 | Vira citação, não `<div>`: o Markwon não reprocessa HTML como Markdown |
| Export HTML | ✅ 3.4.0 | Reusa o `buildPrintHtml` da impressão em PDF |
| `%%comentário%%` | ✅ 3.4.0 | `data/MarkdownTransforms.kt`, com teste |
| Abrir nota aleatória | ✅ 3.4.0 | Exclui a nota aberta do sorteio |
| Calendário sobre as notas diárias | ❌ | Fase 3 |
| Grafo (D3) | ❌ | **Não portar** — tentado e removido de propósito (commit 4b90284) |
| Chat/IA local, sistema de plugins | ❌ | Fora de escopo no mobile |

## O que a 3.1.0–3.3.0 fizeram

Descobribilidade, que era o buraco que o desktop tinha fechado nas v1.1/v1.2 e
aqui estava aberto e pior.

O picker do `/` tinha **18 comandos escritos à mão dentro do MarkdownEditor,
com os rótulos em inglês cravado** enquanto o app roda em três idiomas — e não
mencionava nada do que este app serve para fazer: flashcard, callout, Mermaid,
matemática, embed, bloco query. Tudo isso o Android já suportava. A única porta
era digitar `/` no começo da linha, que não é coisa que se descubra num celular.

1. `data/Insertables.kt` — 55 entradas, uma lista só, traduzida. **Um segundo
   lista é o bug**: o menu, o `/` e (quando existir) a ajuda leem daqui.
2. `data/TagComplete.kt` — autocomplete no `#`. No celular pesa mais que no
   desktop: teclado pequeno, corretor hostil a `#sis-cardio`, e nenhuma memória
   muscular para 150 tags. Tag errada não dá erro — cria em silêncio uma tag
   nova com uma nota só dentro.
3. Botão `+` na barra.
4. `sort: criado` passou a existir aqui, e a avisar quando a ordem não pode
   significar nada.

**A posição do cursor vem de um marcador dentro do trecho, não de um
deslocamento contado à mão.** O desktop conta, e errou três vezes — duas delas
deixando o cursor no meio de uma palavra, de modo que a primeira tecla destruía
o trecho recém-inserido. Marcador não tem como ser contado errado. Se o desktop
for retocado algum dia, é para cá que ele deve olhar.

## Lição desta rodada

Um `replace` por script disse "ok" e não sobreviveu ao arquivo: a linha que
traduz `criado` para `SortKey.CREATED` sumiu, e o commit foi com o enum, a
ordenação e o `sortIssues` no lugar mas sem o parser. **O CI pegou** — compilou
e falharam exatamente os cinco testes que afirmavam que a chave é aceita.

Duas conclusões: conferir lendo o arquivo de volta em vez de confiar no retorno
do script; e escrever o teste que afirma a coisa óbvia ("esta chave é aceita"),
porque foi ele que pegou.

## Regras de trabalho (deste repo)

- **Sem build local** nesta máquina (sem JDK/Android SDK) — o juiz é o `ci.yml`
  do GitHub Actions, que roda `testDebugUnitTest` antes do APK. Push primeiro,
  esperar verde.
- Release por tag `vX.Y.Z` (a tag tem que bater com o `versionName`), assinada
  com a keystore dos Secrets `KEYSTORE_B64` e `KEYSTORE_PROPERTIES_B64`.
- Commits em português, sem Co-Authored-By, estilo "vX.Y.Z: descrição".
- **Os ids de flashcard são hash de `relativePath::pergunta`, idênticos aos do
  desktop.** Mexer nisso quebra o `srs.json` compartilhado: cada lado passaria a
  criar seus próprios ids e o mesmo cartão seria agendado duas vezes.
