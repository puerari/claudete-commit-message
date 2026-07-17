#!/usr/bin/env bash
#
# Assina (e opcionalmente publica) o plugin lendo o certificado local
# (chain.crt + private.pem) e pedindo APENAS a senha da chave privada — sem gravá-la
# em disco nem deixá-la no histórico do shell.
#
# Uso:
#   ./sign.sh              # pergunta a senha e gera o -signed.zip
#   ./sign.sh --publish    # após assinar, publica (exige PUBLISH_TOKEN no ambiente)
#
# A senha também pode vir de PRIVATE_KEY_PASSWORD já exportada (ex.: CI): nesse caso
# o script não pergunta nada.
set -euo pipefail
cd "$(dirname "$0")"

# Carrega variáveis do .env (se existir): PUBLISH_TOKEN e, opcionalmente, PRIVATE_KEY_PASSWORD.
# O que já estiver no ambiente tem prioridade (permite override pontual, ex.:
# `PUBLISH_TOKEN=xxx ./sign.sh`). Linhas em branco e comentários (#) são ignorados.
if [ -f .env ]; then
  while IFS='=' read -r key val || [ -n "$key" ]; do
    case "$key" in ''|'#'*) continue ;; esac
    key=$(printf '%s' "$key" | tr -d '[:space:]')
    [ -z "$key" ] && continue
    if [ -z "${!key:-}" ]; then
      export "$key=$val"
    fi
  done < .env
fi

# JDK (não há Java no PATH nesta máquina).
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-17.0.12}"
export PATH="$JAVA_HOME/bin:$PATH"

# Certificado — fica na raiz do projeto, ignorado pelo git (*.pem / *.crt).
for f in chain.crt private.pem; do
  [ -f "$f" ] || { echo "ERROR: '$f' not found in the project root." >&2; exit 1; }
done
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"

# Senha: usa a do ambiente se já existir; senão pergunta sem eco.
if [ -z "${PRIVATE_KEY_PASSWORD:-}" ]; then
  read -rs -p "Pass phrase for private.pem: " PRIVATE_KEY_PASSWORD
  echo
  export PRIVATE_KEY_PASSWORD
fi

# Valida a senha antes de chamar o Gradle (mensagem de erro clara). A senha vai pela
# stdin do openssl, não pela linha de comando, para não aparecer em 'ps'.
if ! printf '%s' "$PRIVATE_KEY_PASSWORD" \
     | openssl pkey -in private.pem -passin stdin -noout 2>/dev/null; then
  echo "ERROR: incorrect password for private.pem." >&2
  exit 1
fi

./gradlew signPlugin

published=0
if [ "${1:-}" = "--publish" ]; then
  : "${PUBLISH_TOKEN:?set PUBLISH_TOKEN (Marketplace -> profile -> My Tokens) to publish}"
  ./gradlew publishPlugin
  published=1
fi

# Mensagem final destacada. Só é alcançada se tudo acima teve sucesso (set -e aborta antes
# em caso de falha). Verde apenas quando a saída é um terminal.
if [ -t 1 ]; then grn=$'\033[1;32m'; bld=$'\033[1m'; rst=$'\033[0m'; else grn=''; bld=''; rst=''; fi
echo
if [ "$published" = "1" ]; then
  echo "${grn}✔ PUBLISHING SUCCESSFUL${rst} — uploaded to the JetBrains Marketplace."
  echo "  Check the status at https://plugins.jetbrains.com/author/me"
else
  echo "${grn}✔ SIGNING SUCCESSFUL${rst}"
fi
echo "${bld}Signed artifact(s):${rst}"
ls -1 build/distributions/*-signed.zip 2>/dev/null || echo "  (none found)"
