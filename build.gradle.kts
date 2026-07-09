import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.0"
}

group = "br.com.puerari"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compilamos contra o IntelliJ IDEA (distribuição unificada — desde 2025.3 não há mais
        // o artefato "IC" separado). O plugin usa apenas APIs da plataforma (VCS/commit),
        // presentes em TODAS as IDEs JetBrains, então o mesmo artefato funciona em PhpStorm,
        // PyCharm, WebStorm, IntelliJ, etc.
        //
        // Para desenvolver/rodar (runIde) diretamente contra uma IDE específica, troque a
        // linha abaixo por uma destas:
        //   phpstorm("2025.2")
        //   pycharmCommunity("2025.2")
        //   pycharmProfessional("2025.2")
        intellijIdeaCommunity("2025.2")

        // Módulo de implementação do VCS: fornece IdeaTextPatchBuilder / UnifiedDiffWriter,
        // usados para montar o diff das mudanças selecionadas. Sempre presente nas IDEs.
        bundledModule("intellij.platform.vcs.impl")
    }
}

intellijPlatform {
    // Não usamos formulários .form nem precisamos das asserções de nulidade instrumentadas.
    instrumentCode = false

    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            // Compilado contra 2025.2 (252), uma geração antes do runtime 261/262 do usuário.
            // sinceBuild baixo maximiza compatibilidade; só usamos APIs estáveis e antigas.
            sinceBuild = "242"
            // Sem limite superior: o plugin usa apenas APIs estáveis da plataforma, então
            // permanece compatível com builds mais novos (262+).
            untilBuild = provider { null }
        }
    }

    // Assinatura (obrigatória para o Marketplace). Lê os segredos de variáveis de ambiente —
    // o build não quebra sem eles; apenas a task signPlugin/publishPlugin os exige.
    // Gere o certificado conforme https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Publicação. Token em "My Tokens" no perfil do Marketplace.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // Plugin Verifier (mesma checagem de compatibilidade que o Marketplace roda).
    pluginVerification {
        ides {
            // Runtime real do usuário (PhpStorm 2026.1 / build 261). Para uma varredura ampla
            // antes de publicar, use recommended().
            create(IntelliJPlatformType.PhpStorm, "2026.1")
        }
    }
}

java {
    toolchain {
        // A plataforma 2024.2 exige Java 17.
        languageVersion = JavaLanguageVersion.of(17)
    }
}
