---
name: publish-plugin
description: >-
  Build, verify, sign and publish the Claudete Commit Message plugin to the JetBrains
  Marketplace. Use whenever the user wants to release/publish a new version, bump the
  version, sign the plugin (signPlugin), run the Plugin Verifier before release, or
  produce the distributable -signed.zip. Covers the local self-signed certificate flow
  and both first-time (manual web upload + review) and subsequent (publishPlugin) releases.
---

# Publicar o Claudete Commit Message no JetBrains Marketplace

Fluxo de release, ponta a ponta. Java puro, Gradle Wrapper 9.6.1 + IntelliJ Platform
Gradle Plugin 2.18. **Nesta máquina não há JDK no PATH** — exporte-o antes de qualquer
comando Gradle.

> **Atalho:** há um [`Makefile`](../../../Makefile) que embrulha tudo (e já exporta o
> JAVA_HOME). `make help` lista os alvos. Fluxo típico: bump de versão (passo 1) →
> `make dist` (compila+verifica+assina) ou `make release` (idem + publica). Os passos
> abaixo detalham o que cada alvo faz.

## 0. Pré-requisitos (toda sessão)

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17.0.12"
export PATH="$JAVA_HOME/bin:$PATH"
```

O certificado de assinatura já existe na **raiz do projeto** (criado em 2026-07-09,
ignorado pelo `.gitignore` via `*.pem`/`*.crt`):

- `chain.crt` — cadeia de certificados X.509 (autoassinada, válida ~10 anos).
- `private.pem` — chave RSA **protegida por senha** (`openssl genpkey -aes-256-cbc`).
- A **senha NÃO está salva em lugar nenhum** (nem `.env`, nem `secrets/`). Só o usuário a
  tem — **peça a senha** antes de assinar; nunca a persista em arquivo do repositório.

> Se um dia o certificado se perder, gere um novo (autoassinado serve para o Marketplace):
> ```bash
> openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
> openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
> ```

## 1. Bump de versão + changelog

1. `version` em [build.gradle.kts](../../../build.gradle.kts) (linha ~9). Marketplace
   **rejeita reupload da mesma versão** — sempre incremente.
2. `<change-notes>` em
   [plugin.xml](../../../src/main/resources/META-INF/plugin.xml) — adicione um bloco novo
   no topo, em **inglês** (mantenha os blocos anteriores).

## 2. Verificar (mesma checagem do Marketplace)

```bash
./gradlew verifyPlugin
```

Alvo configurado: `PhpStorm 2026.1` (build 261) — runtime real do usuário. Precisa
terminar **`Compatible`**. Avisos de *deprecated API* (ex.: `ReadAction.compute`) **não**
bloqueiam; uso de API **removida** bloqueia (ver armadilha [removal] no CLAUDE.md).

## 3. Build + assinatura

`buildPlugin` **não** assina — quem assina é `signPlugin`, que lê o certificado das
variáveis de ambiente. **Forma padrão: rode o script [`sign.sh`](../../../sign.sh)** na
raiz. Ele lê `chain.crt`/`private.pem`, **pergunta só a senha** (sem eco, sem histórico,
sem gravar em disco), valida a senha e chama o Gradle:

```bash
./sign.sh              # pergunta a senha e gera o -signed.zip
./sign.sh --publish    # após assinar, também publica (exige PUBLISH_TOKEN)
```

A senha também pode vir de `PRIVATE_KEY_PASSWORD` já exportada (ex.: CI) — aí não pergunta.

Saída: `build/distributions/claudete-commit-message-<versão>-signed.zip` — **este** é o
artefato de release (o `.zip` sem sufixo é o não-assinado).

<details><summary>Equivalente manual (se não quiser usar o script)</summary>

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD='<senha da private.pem — pedir ao usuário>'
./gradlew signPlugin
```
</details>

## 4. Publicar

### Primeira publicação de um plugin novo → upload manual (com review)
Plugin ainda não listado passa por **aprovação humana** da JetBrains; não dá para
automatizar. Faça login em <https://plugins.jetbrains.com>, **Upload plugin / Add new
plugin**, envie o `-signed.zip`. Guia:
<https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html>.
Critérios de aprovação:
<https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html>.

### Versões seguintes → automatizável
Depois de aprovado, novas versões podem ir por linha de comando. O `PUBLISH_TOKEN` fica no
**`.env`** (não versionado; template em `.env.example`) — o `sign.sh` o carrega
automaticamente:
```bash
# uma vez: copie o template e cole o token (Marketplace → perfil → My Tokens)
cp .env.example .env && $EDITOR .env
make release            # ou: ./sign.sh --publish
```
Variável já exportada no shell tem prioridade sobre o `.env` (override pontual:
`PUBLISH_TOKEN=xxx ./sign.sh --publish`).
`publishPlugin` (chamado pelo script) assina e envia. Para canal de pré-lançamento, use a
propriedade `channels` (ex.: `eap`) no bloco `publishing` do build.

## Observações
- O Marketplace **assina automaticamente** no upload mesmo sem `signPlugin`; assinamos
  localmente para ter cadeia própria e permitir Install-from-Disk confiável.
- O mesmo `.zip` funciona em PhpStorm/PyCharm/IntelliJ/etc. (só APIs de plataforma).
- Nunca commitar `private.pem`, `chain.crt` ou tokens — já cobertos pelo `.gitignore`.
