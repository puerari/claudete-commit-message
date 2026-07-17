# Makefile — Claudete Commit Message
#
# Atalhos para o ciclo de release. Cada etapa é um alvo separado; há também dois
# alvos unificados: `dist` (tudo menos publicar) e `release` (tudo, inclusive publicar).
#
# Não há JDK no PATH desta máquina — o Make exporta o JAVA_HOME abaixo para todas as
# receitas. Sobrescreva na linha de comando se precisar: `make build JAVA_HOME=/outro/jdk`.

export JAVA_HOME ?= $(HOME)/.jdks/temurin-17.0.12
export PATH := $(JAVA_HOME)/bin:$(PATH)

GRADLE := ./gradlew

# Cores das mensagens de status (verde/negrito). `printf` interpreta os escapes.
OK  := \033[1;32m
RST := \033[0m

.DEFAULT_GOAL := help
.PHONY: help build verify sign publish dist release clean run

help: ## Exibe este menu
	@awk 'BEGIN { \
		FS = ":.*##"; \
		print ""; \
		print "  \033[1mClaudete Commit Message\033[0m — atalhos de build/release"; \
		print "  Uso: \033[36mmake <alvo>\033[0m   (ex.: make dist)"; \
	} \
	/^##@/ { printf "\n  \033[1m%s\033[0m\n", substr($$0, 5); next } \
	/^[a-zA-Z_-]+:.*?##/ { printf "    \033[36m%-9s\033[0m %s\n", $$1, $$2 } \
	END { print "" }' $(MAKEFILE_LIST)

##@ Etapas
build: ## Compila o plugin (zip NÃO assinado) em build/distributions/
	$(GRADLE) buildPlugin
	@printf '$(OK)✔ BUILD SUCCESSFUL$(RST) — unsigned zip in build/distributions/\n'

verify: ## Roda o Plugin Verifier (mesma checagem do Marketplace)
	$(GRADLE) verifyPlugin
	@printf '$(OK)✔ VERIFY PASSED$(RST) — compatible with the target IDE(s)\n'

# sign / publish não imprimem aqui: o sign.sh já emite "SIGNING/PUBLISHING SUCCESSFUL".
sign: ## Assina o plugin (pergunta só a senha; gera o -signed.zip)
	./sign.sh

publish: ## Assina e publica no Marketplace (exige PUBLISH_TOKEN)
	./sign.sh --publish

##@ Unificados
dist: build verify sign ## Compila + verifica + assina (sem publicar)
	@printf '$(OK)✔ DIST READY$(RST) — built, verified and signed (not published)\n'

release: build verify publish ## Compila + verifica + assina + publica
	@printf '$(OK)✔ RELEASE COMPLETE$(RST) — built, verified, signed and published\n'

##@ Utilitários
clean: ## Remove os artefatos de build
	$(GRADLE) clean
	@printf '$(OK)✔ CLEAN DONE$(RST) — build artifacts removed\n'

run: ## Abre uma IDE de teste com o plugin instalado (runIde)
	$(GRADLE) runIde
