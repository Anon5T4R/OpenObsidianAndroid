# OpenObsidian Android

Um editor de notas em **Markdown** para Android, inspirado no Obsidian. Abre uma
pasta local do seu dispositivo (um "vault") via Storage Access Framework e
trabalha direto nos seus arquivos `.md` — sem nuvem, sem banco de dados, sem
lock-in. Tudo continua sendo arquivos de texto na sua pasta.

> Versão atual: **3.0.0** · minSdk **28** (Android 9+) · 100% Kotlin + Jetpack Compose

---

## ✨ Funcionalidades

### Estudo
- **Flashcards com repetição espaçada (SM-2)** — os cartões moram dentro das
  suas notas, como callouts:

  ```markdown
  > [!card]- Qual a tríade do qSOFA?
  > FR ≥ 22 · PAS ≤ 100 · Glasgow < 15

  > [!card] Ciclo de Krebs
  > ==Citrato sintase== condensa ==acetil-CoA==.
  ```

  Cada `==destaque==` vira um cartão de lacuna. Um cartão dentro de outro
  callout (`> > [!card]` sob um `> [!warning]`) também conta.

- Botão de revisão na barra superior, com o número do que vence hoje. Um cartão
  por vez, ocupando a tela: revisar no celular compete com tudo mais que está
  nele, e uma tela densa faz passar o olho em vez de lembrar.
- O agendamento fica em `.openobsidian/srs.json` — **nunca dentro das notas** —
  e é **o mesmo arquivo que o desktop escreve**: um cartão revisado aqui não é
  cobrado de novo lá à noite.

### Leitura
- **Visualizador de EPUB**: um capítulo por vez, com sumário e zoom. Sem
  biblioteca de leitor — um `.epub` é um ZIP com um manifesto XML, e o
  WebView já renderiza o resto.

### Consultas
- **Blocos ` `query ` `** no preview: um índice que se deriva das notas
  em vez de ser digitado à mão. Filtra por `tag:`, `pasta:`, `has:` e por
  qualquer campo do frontmatter; ordena e limita.
- Linha que a consulta não entendeu aparece **acima** da lista — consulta que
  erra em silêncio devolve uma lista que parece certa.

### Robustez
- **Cache do índice entre sessões**: o texto das notas fica guardado e validado
  por data de modificação. Antes o app relia o vault inteiro a cada abertura —
  uma leitura por nota, através do SAF, que é a coisa mais lenta que ele faz.
- **Backup do vault em `.zip`** (⋮ na gaveta), no destino que você escolher.
  Inclui tudo: anexos, PDFs e o agendamento dos flashcards.

### Navegação e busca
- **Wikilinks completos**: `[[Nota#Seção]]`, `[[Pasta/Nota]]` e **aliases** do
  frontmatter. Antes só funcionava o nome exato — o resto não fazia nada ao ser
  tocado. Nome duplicado prefere a nota mais próxima de quem linka.
- **Busca com operadores**: `tag:`, `path:`, `file:`, `"frase exata"` e
  `-exclusão`. A tag pai encontra as filhas. Resultados por relevância.
- **Diagnóstico do vault** (menu ⋮): links mortos com quem aponta pra eles,
  notas órfãs e nomes duplicados.

### Idiomas
- Interface em **português, inglês e espanhol**, seguindo o idioma do sistema.

### Diagramas
- **Mermaid**: um bloco ` ```mermaid ` vira um link no preview; tocar abre o
  diagrama em **tela cheia, com pinça pra dar zoom**. Inline seria ilegível num
  celular, e é assim que o desktop também faz no clique.
- O `mermaid.min.js` está embutido no app e o WebView roda **com a rede
  bloqueada** — diagrama que depende de CDN é diagrama que falha offline.
- Diagrama que não compila mostra o erro em vez de uma tela em branco.

### Integridade
- **Renomear reescreve os `[[links]]`** que apontavam para a nota, preservando
  `|alias`, `#âncora` e prefixo de pasta, sem tocar em blocos de código.
- **Nota que não pôde ser lida não abre em branco.** Antes abria vazia e a
  primeira tecla gravava esse vazio por cima do arquivo, que estava intacto.
- **Gravação que falha aparece** e a marca de "não salvo" continua acesa.
- Uma cópia oculta é escrita antes de cada gravação, porque o SAF não oferece
  substituição atômica e no Android o processo é morto por rotina.
- **Frontmatter YAML** deixa de aparecer como texto no preview, e as **tags**
  (com acento e hierarquia `#sistema/cardio`) passam a ser indexadas.

### Notas e edição
- **Editor Markdown** com realce de sintaxe ao vivo (títulos, negrito, itálico,
  tachado, código, wikilinks).
- **Atalhos `/`** (slash commands): digite `/` no início da linha para inserir
  rapidamente títulos, **tabela**, listas, **checkbox**, citação, divisor,
  highlight, wikilink, imagem e mais.
- **Toolbar de formatação** ao selecionar texto (negrito, itálico, tachado,
  código, highlight, wikilink, citação).
- **Modos de visualização**: Editar · Preview · Split (lado a lado).
- **Autosave** (~1,5 s após parar de digitar) + `Ctrl+S` em teclado físico.
- **Buscar e substituir na nota** (menu ⋮ → "Find in note"): contador de
  ocorrências, anterior/próximo, substituir uma ou todas.
- **Templates**: arquivos `.md` na pasta `templates/` do vault viram modelos —
  crie notas pelo botão **+** da sidebar ("New from template") com os
  placeholders `{{title}}`, `{{date}}` e `{{time}}`. A **nota do dia** usa
  `templates/daily.md` automaticamente, se existir.

### Preview (renderização)
- Renderização via **Markwon**: tabelas, listas de tarefas, tachado, código.
- **Checkboxes interativos** — toque na caixa no preview para marcar/desmarcar
  (escreve `- [x]` de volta no arquivo).
- **Wikilinks** `[[Nota]]` e `[[Nota|texto]]` clicáveis.
- **Imagens** `![[imagem.png]]` e `![alt](uri)` renderizadas.
- **Highlight** `==texto==`.
- **Matemática LaTeX** via JLatexMath: `$$bloco$$`, `$inline$`, `\(inline\)`
  e `\[bloco\]`.
- **Callouts do Obsidian** (`> [!info]`, `> [!warning] Título`, ~25 tipos) —
  renderizados como citação com emoji + título em negrito.
- **Sumário (outline)** da nota (menu ⋮ → "Outline"): lista os títulos e rola
  o preview (ou move o cursor no modo Editar) até o título tocado.

### Organização
- **Árvore de arquivos** na barra lateral, com pastas, filtro e ordenação
  (nome ↑/↓, modificação).
- **Criar / renomear / excluir / mover** arquivos e pastas. O "Mover" tem
  proteções: bloqueia mover uma pasta pra dentro de si mesma, evita colisão de
  nomes e ignora destino igual ao atual.
- **Notas fixadas** (pin) no topo da barra lateral.
- **Nota do dia** (daily note) e **nota companheira** (companion note).
- **Busca** full-text em todas as notas, com trechos de contexto.
- **Backlinks**: veja quais notas apontam para a nota atual.

### Mídia e formatos
- **Importar imagem** da galeria — copia pra pasta `attachments/` do vault e
  insere `![[nome]]` no cursor.
- **Exportar nota como PDF** (menu ⋮ na barra da nota).
- **Visualizador de PDF** embutido para arquivos `.pdf`.
- **Visualizador de DOCX** com opção de **converter `.docx` → Markdown**.

### Experiência
- **Botão Voltar do Android** navega dentro do app (fecha gaveta → volta no
  histórico → fecha a nota) em vez de sair direto.
- **Temas** claro / escuro / sistema; tamanho da fonte do preview ajustável.
- **Layout adaptativo**: em telas largas (tablet/paisagem) a barra lateral fica
  fixa e pode ser **recolhida/expandida** (botão no cabeçalho da barra e ícone de
  menu na topbar quando recolhida); no retrato, o gesto de puxar continua.
- O teclado não cobre mais o fim da nota ao digitar.

---

## 🧱 Stack

| Camada | Tecnologia |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Linguagem | Kotlin (JVM target 17) |
| Armazenamento | Storage Access Framework (`DocumentsContract`) |
| Preview Markdown | [Markwon](https://github.com/noties/Markwon) 4.6.2 |
| Preferências | DataStore (Preferences) |
| Assíncrono | Kotlin Coroutines |
| Navegação | Estado em `ViewModel` + overlays Compose |

---

## 📁 Estrutura do código

```
app/src/main/kotlin/com/openobsidian/android/
├── MainActivity.kt            # Entry point, tema, navegação de topo
├── OpenObsidianApp.kt         # Application; instancia os repositórios
├── data/
│   ├── AppSettings.kt         # Modelo de settings + CompositionLocal
│   ├── SettingsRepository.kt  # Settings persistidos via DataStore
│   ├── VaultRepository.kt     # URIs de vault persistidos (último/conhecidos)
│   ├── SafFs.kt               # Camada de FS sobre o SAF (listar/ler/escrever/
│   │                          #   CRUD/mover/indexar imagens)
│   └── DocxConverter.kt       # Conversão .docx → Markdown
├── viewmodel/
│   └── VaultViewModel.kt      # Estado do vault, operações de arquivo, busca,
│                              #   backlinks, import de imagem
└── ui/
    ├── screens/
    │   ├── WelcomeScreen.kt   # Escolher a pasta do vault (SAF)
    │   ├── VaultScreen.kt     # Tela principal: gaveta + topbar + área da nota
    │   ├── SearchScreen.kt    # Overlay de busca full-text
    │   └── SettingsScreen.kt  # Tema / fonte / ordenação
    ├── components/
    │   ├── FileTreeContent.kt # Árvore de arquivos + menus de contexto + diálogos
    │   ├── MarkdownEditor.kt  # Editor: realce, slash commands, toolbar, imagem
    │   ├── MarkdownPreview.kt # Preview Markwon, checkboxes, imagens, wikilinks
    │   ├── PdfViewer.kt       # Visualizador de PDF
    │   └── DocxViewer.kt      # Visualizador de DOCX
    └── theme/
        ├── Color.kt
        └── Theme.kt
```

---

## 🔧 Build

Requer **JDK 17+**. Se o `java` do seu PATH for mais antigo, use o JBR que vem
com o Android Studio.

```bash
# Compilar e gerar APK de debug (instalável, assinado com a debug key)
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleDebug

# Só checar compilação (rápido)
./gradlew :app:compileDebugKotlin
```

O APK de debug sai em `app/build/outputs/apk/debug/`.

> Não há emulador/CI configurado neste repositório — a verificação principal é a
> compilação. Comportamentos de runtime (ex.: posicionamento de teclado) devem
> ser testados em um dispositivo real.

---

## 🔐 Build de release (APK assinado)

A assinatura é automática quando existe um `keystore.properties` na raiz do
repositório (esse arquivo e a `.jks` são **git-ignored** — nunca commite).

1. Crie uma keystore (uma vez):
   ```bash
   keytool -genkeypair -v -keystore openobsidian-release.jks \
     -alias openobsidian -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Crie `keystore.properties` na raiz:
   ```properties
   storeFile=openobsidian-release.jks
   storePassword=SUA_SENHA
   keyAlias=openobsidian
   keyPassword=SUA_SENHA
   ```
3. Gere o release (já sai assinado):
   ```bash
   JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleRelease
   ```
   Saída: `app/build/outputs/apk/release/app-release.apk`.

Se o `keystore.properties` não existir, o release é gerado **sem assinatura**
(o build não quebra) — útil para clones/CI sem o segredo.

> ⚠️ Guarde a `.jks` e suas senhas. Atualizações que instalam por cima de uma
> versão publicada precisam ser assinadas com **a mesma chave**.

---

## 📥 Instalação

Baixe o APK mais recente na aba **[Releases](../../releases)** e instale no
Android (permita "instalar de fontes desconhecidas" se solicitado).

---

## 📝 Sintaxe suportada (resumo)

| Recurso | Sintaxe |
|---|---|
| Títulos | `# … ######` |
| Negrito / Itálico | `**negrito**` · `*itálico*` |
| Tachado | `~~texto~~` |
| Highlight | `==texto==` |
| Código | `` `inline` `` · bloco com ` ``` ` |
| Citação | `> texto` |
| Listas | `- item` · `1. item` |
| Checkbox | `- [ ]` / `- [x]` |
| Tabela | `\| col \| col \|` |
| Divisor | `---` |
| Wikilink | `[[Nota]]` · `[[Nota\|texto]]` |
| Imagem (vault) | `![[imagem.png]]` |
| Matemática | `$$bloco$$` · `$inline$` · `\(inline\)` · `\[bloco\]` |
| Callout | `> [!info]` · `> [!warning] Título` |

---

## 📄 Licença

Defina a licença do projeto aqui (ex.: MIT, GPL-3.0).
