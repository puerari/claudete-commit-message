# CLAUDE.md — Claudete Commit Message

> Marketplace display name: **Claudete Commit Message**. Plugin id **and** Java package are the same:
> `br.com.puerari.claudetecommitmessage` (must stay stable after first publish). Action icon:
> `icons/generate.svg` (commit node + sparkle, neutral color — deliberately not Anthropic's brand color).

Plugin JetBrains (Java puro) que gera mensagens de commit a partir dos arquivos **marcados**
na ferramenta de commit, usando a **CLI do Claude Code**. O botão fica na barra do campo de
mensagem (grupo de ação `Vcs.MessageActionGroup`) + atalho `Ctrl+Alt+K`.

## Build / run (requer JDK 17)

- Gradle Wrapper **9.6.1** + IntelliJ Platform Gradle Plugin **2.18**. Não há Gradle no sistema — use sempre `./gradlew`.
- **Nesta máquina não há JDK no PATH.** Antes de qualquer comando Gradle:
  ```bash
  export JAVA_HOME="$HOME/.jdks/temurin-17.0.12"
  export PATH="$JAVA_HOME/bin:$PATH"
  ```
- Comandos:
  ```bash
  ./gradlew buildPlugin   # -> build/distributions/claude-commit-<versão>.zip
  ./gradlew runIde        # IDE de teste com o plugin
  ```
  Em iterações, se o configuration cache atrapalhar: acrescente `-Dorg.gradle.configuration-cache=false`.
- Instalação: **Settings → Plugins → ⚙ → Install Plugin from Disk** → selecione o `.zip` → reiniciar.
- Runtime do usuário: **PhpStorm nativo do Windows**, com o Claude Code no **WSL (Ubuntu)**. O zip é acessível pelo Windows em `\\wsl.localhost\Ubuntu\home\puerari\dev\plugins\claude_commit\build\distributions\`.

## Plataforma-alvo

- Compilado contra `intellijIdeaCommunity("2025.2")` (build **252**) — última versão com o artefato clássico "IC". Desde 253 a JetBrains unificou a distribuição; `intellijIdea("2026.1")` resolve mas o layout modular novo **não montou o classpath base** com o tooling atual (evitar por enquanto).
- `sinceBuild = "242"`, **sem** `untilBuild` (`provider { null }`) → compatível com 242..262+. Runtime real do usuário: **261/262** (2026.1/2026.2).
- `bundledModule("intellij.platform.vcs.impl")` é **obrigatório** no classpath — fornece `IdeaTextPatchBuilder` e `UnifiedDiffWriter`.

## Arquitetura — `src/main/java/br/com/puerari/claudetecommitmessage/`

- **GenerateCommitMessageAction** — ação do botão. Toggle gerar/parar (ícone vira quadrado vermelho durante a geração; clicar cancela). `update()` roda na **EDT** e habilita o botão só quando há arquivo marcado. Trabalho pesado em `Task.Backgroundable`.
- **DiffCollector** — monta o diff unificado das `Change` incluídas **+ arquivos unversioned** marcados (cada um vira um `Change` sintético `new Change(null, CurrentContentRevision.create(fp))`).
- **ClaudeCliRunner** — localiza e executa a CLI (modo nativo ou via WSL); `-p` com o diff pela stdin; detecção do executável; `listWslDistros()`; `describeWslResolution()`.
- **ClaudeCommitSettings** (`PersistentStateComponent`) / **ClaudeCommitConfigurable** (Configurable em *Tools*).
- **ClaudeCommitTaskManager** — `@Service(PROJECT)`; rastreia a execução p/ permitir cancelamento.
- **ClaudeNotifier** — notificações; `errorWithSettings()` inclui ação "Abrir configurações".
- Descriptor: `src/main/resources/META-INF/plugin.xml`. Ícones em `resources/icons/`.

## Armadilhas críticas (aprendidas na marra — não repetir)

1. **`<extensions defaultExtensionNs="com.intellij">`** — o atributo é `defaultExtensionNs`. `defaultExtensionPointName` **não existe**: se usado, a plataforma descarta **todas** as extensões (applicationService, applicationConfigurable, notificationGroup) **silenciosamente**, enquanto `<actions>` e serviços `@Service` continuam funcionando — o que mascara o bug (sintoma: tela de settings some, sem feedback).
2. **APIs marcadas para remoção quebram em 261/262.** Ex.: `TextFieldWithBrowseButton.addBrowseFolderListener(String,String,Project,FileChooserDescriptor)` foi removida no 261 → `NoSuchMethodError` em `createComponent()` (falha logada, não notificada). **Trate qualquer aviso `[removal]` do compilador (contra 252) como erro.**
3. **A CLI do Claude não lista modelos** — não há subcomando `models`/`config` (viram *prompt*). Use os **aliases** de `--model`: `sonnet`/`opus`/`haiku`/`fable`/`default`; ou nome completo.
4. **WSL** — invocar via `wsl.exe [-d <distro>] -- bash -lc 'exec "$0" "$@"' <claude> -p ...` (o `bash -lc` carrega o PATH de login; o truque `exec "$0" "$@"` evita escapar o prompt multilinha). Para `wsl -l -q`, setar env `WSL_UTF8=1` (senão a saída vem em UTF-16).
5. **Testes de registro** — `BasePlatformTestCase` dá **falso-negativo** para o registro de extensões *application-level* do plugin sob teste (serviço/configurable/notificationGroup aparecem como não registrados mesmo funcionando). Para validar registro de verdade, use `./gradlew buildSearchableOptions` (o `p-com.puerari.claude-commit-searchableOptions.json` deve conter o id do configurable) ou uma IDE real.

## Convenções

- Java puro (sem Kotlin). Comentários e textos de UI/notificação em **pt-BR**; identificadores e ids de ação em inglês.
- Autor/vendor: **Puerari Solutions** (`solutions@puerari.com.br`).
