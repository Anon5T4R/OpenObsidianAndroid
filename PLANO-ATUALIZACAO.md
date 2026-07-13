# Plano de atualização — OpenObsidian Android

> Comparativo entre o **desktop v0.7.5** (Electron) e o **Android 1.2.1**, com o
> roteiro do que portar. Atualizado em 2026-07-13.

## Onde cada um está

| Recurso (desktop v0.7.5) | Android 1.2.1 | Ação |
|---|---|---|
| Editor com realce + modos Edit/Preview/Split | ✅ | — |
| Wikilinks, backlinks, busca full-text | ✅ | — |
| Daily note, nota companheira, pin | ✅ | — |
| Tabelas, task lists (checkbox clicável), ~~tachado~~, ==highlight== | ✅ | — |
| Imagens no vault + import da galeria | ✅ | — |
| PDF viewer / DOCX viewer + conversão p/ MD | ✅ | — |
| Export PDF | ✅ | — |
| **Matemática KaTeX** (`$$…$$`, `$…$`) | ❌ | **Fase 1** — Markwon `ext-latex` (JLatexMath) |
| **Callouts Obsidian** (`> [!info]`, 20+ tipos) | ❌ | **Fase 1** — preprocessamento no preview |
| **Painel TOC** (sumário da nota) | ❌ | **Fase 1** — bottom sheet + scroll até o heading |
| **Templates** (nova nota de template, template da daily) | ❌ | **Fase 1** — pasta `templates/` do vault |
| **Find & Replace** na nota (Ctrl+F do editor) | ❌ | **Fase 1** — barra de busca no editor |
| **Mermaid** (diagramas) | ❌ | Fase 2 — WebView com mermaid.js embarcado |
| **i18n** (PT-BR / EN / ES) | ❌ (strings em EN hardcoded) | Fase 2 — extrair para `strings.xml` |
| **Backup do vault** (cópia p/ destino) | ❌ | Fase 2 — export .zip via SAF |
| **EPUB viewer** | ❌ | Fase 2 — avaliar lib (readium/epublib) |
| Graph view (D3) | ❌ | **Não portar** — já foi tentado e removido no Android (commit 4b90284) por decisão |
| Chat/ações de IA (node-llama-cpp) | ❌ | Fora de escopo no mobile |
| Sistema de plugins | ❌ | Fora de escopo no mobile |
| Vault index cache (mtime) | parcial (cache em memória) | Fase 2 — persistir cache do índice |

## Fase 1 — v1.3.0 (esta rodada)

1. **Matemática LaTeX no preview** — `io.noties.markwon:ext-latex` +
   `inline-parser` 4.6.2. Blocos `$$…$$` nativos; inline `$…$` convertido no
   preprocessamento com a mesma regra anti-falso-positivo do desktop (cifrão
   seguido de espaço não conta).
2. **Callouts** — `> [!warning] Título` vira blockquote com emoji + título em
   negrito (mapa de ~20 tipos igual ao desktop). O sufixo `-` (colapsável no
   desktop) é aceito e renderizado estático.
3. **Sumário (TOC)** — item no menu ⋮ da nota; bottom sheet lista os headings
   (ignorando code blocks); tocar rola o preview até o heading, ou posiciona o
   cursor no modo Editar.
4. **Templates** — arquivos `.md` da pasta `templates/` do vault; botão na
   sidebar cria nota nova a partir de um template com placeholders `{{title}}`,
   `{{date}}` e `{{time}}`. A daily note passa a usar `templates/daily.md`
   quando existir.
5. **Buscar/substituir na nota** — barra no editor com contador de matches,
   anterior/próximo e substituir (um / todos).

Fechamento: bump `1.3.0` (versionCode 5) + README atualizado + push com CI
verde (`ci.yml`, APK debug).

## Fase 2 — backlog (próximas rodadas)

- **Mermaid**: renderizar blocos ` ```mermaid ` num WebView com mermaid.min.js
  nos assets (~1 MB no APK). Alternativa: placeholder com o código fonte.
- **i18n**: extrair strings hardcoded para `res/values/strings.xml` +
  `values-pt-rBR/` + `values-es/`, espelhando os idiomas do desktop.
- **Backup do vault**: exportar o vault inteiro como `.zip` para um destino
  escolhido via SAF (`CreateDocument`).
- **EPUB**: viewer somente-leitura.
- **Cache persistente do índice**: hoje o índice de busca/backlinks é
  reconstruído em memória a cada sessão; persistir por vault (mtime) como o
  desktop faz.

## Regras de trabalho (deste repo)

- **Sem build local** nesta máquina (sem JDK/Android SDK) — o juiz é o
  `ci.yml` do GitHub Actions (APK debug). Push primeiro, esperar verde.
- Release continua **manual/local** (assinatura via `keystore.properties`
  fora do git); não há release por tag até cadastrar os Secrets da keystore.
- Commits em português, sem Co-Authored-By, estilo "vX.Y.Z: descrição".
