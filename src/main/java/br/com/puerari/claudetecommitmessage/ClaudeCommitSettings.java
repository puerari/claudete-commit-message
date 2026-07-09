package br.com.puerari.claudetecommitmessage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Configurações persistentes do plugin (nível de aplicação).
 */
@State(name = "ClaudeCommitSettings", storages = @Storage("claude-commit.xml"))
public final class ClaudeCommitSettings implements PersistentStateComponent<ClaudeCommitSettings> {

    /** Prompt padrão usado para instruir o Claude a gerar a mensagem de commit. */
    public static final String DEFAULT_PROMPT =
            "You are an expert software engineer writing a Git commit message.\n" +
            "The diff of the changes to be committed is provided via standard input.\n" +
            "\n" +
            "Write a clear, concise commit message following the Conventional Commits specification.\n" +
            "\n" +
            "Rules:\n" +
            "- First line: a summary in the imperative mood, max 72 characters, prefixed with a\n" +
            "  type (feat, fix, docs, style, refactor, perf, test, build, ci, chore) and an\n" +
            "  optional scope, e.g. \"feat(parser): ...\".\n" +
            "- Then a blank line, then an optional body explaining WHAT changed and WHY, wrapped\n" +
            "  at about 72 columns. Omit the body for trivial changes.\n" +
            "- Base the message ONLY on the provided diff. Do not invent changes.\n" +
            "- NEVER add any trailer or attribution line, such as \"Co-Authored-By: ...\",\n" +
            "  \"Generated with ...\", or \"Signed-off-by: ...\". Do not mention Claude, AI, or any tool.\n" +
            "- Output ONLY the raw commit message: no markdown, no code fences, no surrounding\n" +
            "  quotes, no preamble or explanation.";

    /**
     * Caminho para o executável do Claude Code.
     * <p>No modo nativo: vazio ou "claude" = detecção automática (PATH + locais padrão).
     * <p>No modo WSL: caminho do executável DENTRO do WSL (ex.: {@code /home/user/.local/bin/claude});
     * vazio ou "claude" = usa o PATH do shell de login do WSL.
     */
    public String claudePath = "";

    /**
     * Executa a CLI via {@code wsl.exe}. Útil quando o PhpStorm/PyCharm roda nativamente no
     * Windows mas o Claude Code está instalado no WSL.
     */
    public boolean useWsl = false;

    /** Nome da distribuição do WSL (ex.: "Ubuntu"). Vazio = distro padrão. */
    public String wslDistro = "";

    /** Modelo a ser usado (ex.: "claude-sonnet-5"). Vazio = padrão da CLI. */
    public String model = "";

    /** Argumentos adicionais passados à CLI (respeita aspas). */
    public String additionalArgs = "";

    /** Tempo máximo de espera pela resposta da CLI, em segundos. */
    public int timeoutSeconds = 120;

    /** Prompt/instrução enviado ao Claude. */
    public String promptTemplate = DEFAULT_PROMPT;

    public static ClaudeCommitSettings getInstance() {
        return ApplicationManager.getApplication().getService(ClaudeCommitSettings.class);
    }

    @Override
    public ClaudeCommitSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull ClaudeCommitSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
