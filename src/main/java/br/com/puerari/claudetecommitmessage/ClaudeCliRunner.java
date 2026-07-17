package br.com.puerari.claudetecommitmessage;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Localiza a CLI do Claude Code e a executa em modo não-interativo ({@code claude -p}),
 * enviando o diff pela entrada padrão e capturando a resposta.
 *
 * <p>Suporta dois modos: execução nativa (o {@code claude} do próprio SO) e execução via
 * {@code wsl.exe} (para PhpStorm/PyCharm nativo do Windows com o Claude Code no WSL).
 */
final class ClaudeCliRunner {

    private ClaudeCliRunner() {
    }

    /** Resultado da execução da CLI. */
    static final class Result {
        final boolean ok;
        final String output;
        final String error;

        private Result(boolean ok, String output, String error) {
            this.ok = ok;
            this.output = output;
            this.error = error;
        }

        static Result ok(String output) {
            return new Result(true, output, null);
        }

        static Result error(String error) {
            return new Result(false, null, error);
        }
    }

    /**
     * Executa a CLI. Deve ser chamado a partir de uma thread de background.
     *
     * @throws ProcessCanceledException se o usuário cancelar via {@code indicator}.
     */
    static Result run(@NotNull ClaudeCommitSettings settings,
                      @Nullable String workingDir,
                      @NotNull String diff,
                      @Nullable ProgressIndicator indicator) {
        GeneralCommandLine cmd;
        String label;
        if (settings.useWsl) {
            String wsl = resolveWslExe();
            if (wsl == null) {
                return Result.error("wsl.exe not found. Check whether WSL is installed.");
            }
            cmd = new GeneralCommandLine(wsl);
            appendWslPrefix(cmd, settings.wslDistro);
            appendClaudeInvocation(cmd, wslClaudeCommand(settings.claudePath), settings);
            label = "Claude Code (via WSL)";
        } else {
            String exe = resolveExecutable(settings.claudePath);
            if (exe == null) {
                return Result.error(
                        "Claude Code executable not found. Install the CLI and/or provide the " +
                        "path in Settings → Tools → Claudete Commit Message.");
            }
            cmd = new GeneralCommandLine(exe);
            if (workingDir != null && new File(workingDir).isDirectory()) {
                cmd.withWorkDirectory(workingDir);
            }
            appendClaudeInvocation(cmd, List.of(exe), settings);
            label = "Claude Code (" + exe + ")";
        }

        // CONSOLE herda o ambiente do shell de login do usuário (PATH, HOME, etc.),
        // essencial quando a IDE é iniciada pela interface gráfica.
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);

        long timeoutMs = Math.max(1, (long) settings.timeoutSeconds) * 1000L;
        return execute(cmd, diff, indicator, timeoutMs, label);
    }

    /** Acrescenta os parâmetros {@code -p <prompt> [--model m] [args...]} ao comando do claude. */
    private static void appendClaudeInvocation(@NotNull GeneralCommandLine cmd,
                                               @NotNull List<String> claudeCommand,
                                               @NotNull ClaudeCommitSettings settings) {
        // No modo nativo, o executável já foi passado ao construtor; evitamos duplicá-lo.
        List<String> extra = new ArrayList<>(claudeCommand);
        if (!extra.isEmpty() && cmd.getExePath().equals(extra.get(0))) {
            extra.remove(0);
        }
        cmd.addParameters(extra);
        cmd.addParameter("-p"); // modo não-interativo (print)
        cmd.addParameter(settings.promptTemplate);
        if (settings.model != null && !settings.model.isBlank()) {
            cmd.addParameter("--model");
            cmd.addParameter(settings.model.trim());
        }
        if (settings.additionalArgs != null && !settings.additionalArgs.isBlank()) {
            cmd.addParameters(ParametersListUtil.parse(settings.additionalArgs.trim()));
        }
    }

    /**
     * Prefixo do wsl.exe: {@code [-d <distro>] -e}.
     *
     * <p>Usa {@code -e} (--exec), que executa o comando <b>diretamente</b>, e NÃO {@code --},
     * que entregaria a linha de comando ao <b>shell padrão de login</b> (zsh) do usuário —
     * causa do erro "código 127 / zsh: can't open input file" (o zsh interpretava o comando
     * como um arquivo de script).
     */
    private static void appendWslPrefix(@NotNull GeneralCommandLine cmd, @Nullable String distro) {
        if (distro != null && !distro.isBlank()) {
            cmd.addParameter("-d");
            cmd.addParameter(distro.trim());
        }
        cmd.addParameter("-e");
    }

    /**
     * Prefixo que estende o PATH dentro do WSL com os locais de instalação padrão do claude.
     * Necessário porque usamos um shell NÃO-login (ver {@link #wslClaudeCommand}).
     */
    private static final String WSL_PATH_SETUP =
            "export PATH=\"$HOME/.local/bin:$HOME/.claude/local:$HOME/bin:$HOME/.npm-global/bin:"
            + "/usr/local/bin:/usr/bin:/bin:$PATH\"; ";

    /**
     * Comando que invoca o claude dentro do WSL.
     *
     * <p>Usa {@code bash -c} (NÃO-login) para NÃO carregar os dotfiles do usuário
     * ({@code ~/.profile}/{@code ~/.zshrc}) — que frequentemente fazem {@code exec zsh} e
     * causavam o erro "código 127 / zsh: can't open input file". Em troca, estendemos o PATH
     * explicitamente ({@link #WSL_PATH_SETUP}) para localizar o claude.
     *
     * <p>O truque {@code exec "$0" "$@"} substitui o bash pelo claude passando os argumentos
     * seguintes SEM precisar escapá-los (o prompt multilinha vai como um único argv).
     */
    private static List<String> wslClaudeCommand(@Nullable String configuredPath) {
        String claude = (configuredPath == null || configuredPath.isBlank())
                ? "claude" : configuredPath.trim();
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add("-c");
        command.add(WSL_PATH_SETUP + "exec \"$0\" \"$@\"");
        command.add(claude); // vira $0
        return command;
    }

    /** Executa o processo já configurado, enviando {@code stdin} e capturando a saída. */
    private static Result execute(@NotNull GeneralCommandLine cmd,
                                  @Nullable String stdin,
                                  @Nullable ProgressIndicator indicator,
                                  long timeoutMs,
                                  @NotNull String label) {
        Process process;
        try {
            process = cmd.createProcess();
        } catch (Exception e) {
            return Result.error("Failed to start " + label + ": " + e.getMessage());
        }

        // Escreve o stdin em uma thread separada para evitar deadlock quando o conteúdo é
        // maior que o buffer do pipe. Fecha o stream em qualquer caso.
        final byte[] input = stdin == null ? null : stdin.getBytes(StandardCharsets.UTF_8);
        Future<?> stdinFuture = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (OutputStream os = process.getOutputStream()) {
                if (input != null) {
                    os.write(input);
                    os.flush();
                }
            } catch (IOException ignored) {
                // O processo pode encerrar antes de consumir todo o stdin.
            }
        });
        Future<String> stdoutFuture =
                ApplicationManager.getApplication().executeOnPooledThread(() -> readFully(process.getInputStream()));
        Future<String> stderrFuture =
                ApplicationManager.getApplication().executeOnPooledThread(() -> readFully(process.getErrorStream()));

        long start = System.currentTimeMillis();
        try {
            while (true) {
                if (indicator != null && indicator.isCanceled()) {
                    process.destroyForcibly();
                    throw new ProcessCanceledException();
                }
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (System.currentTimeMillis() - start > timeoutMs) {
                    process.destroyForcibly();
                    return Result.error("Timed out waiting for " + label + ".");
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ProcessCanceledException();
        }

        stdinFuture.cancel(true);
        String stdout = get(stdoutFuture);
        String stderr = get(stderrFuture);
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            String detail = stderr.isBlank() ? stdout : stderr;
            return Result.error(label + " returned exit code " + exitCode
                    + (detail.isBlank() ? "." : ": " + detail.trim()));
        }
        return Result.ok(stdout);
    }

    // ------------------------------------------------------------------------------------
    // Resolução do executável (modo nativo)
    // ------------------------------------------------------------------------------------

    /**
     * Resolve o caminho do executável nativo. Se o usuário informou um caminho, ele tem
     * prioridade; caso contrário, procura no PATH e nos locais de instalação padrão.
     *
     * @return caminho absoluto do executável, ou {@code null} se nada for encontrado.
     */
    @Nullable
    static String resolveExecutable(@Nullable String configured) {
        // 1. Caminho informado manualmente.
        if (configured != null && !configured.isBlank() && !configured.trim().equals("claude")) {
            String c = configured.trim();
            File f = new File(c);
            if (f.isAbsolute()) {
                return f.canExecute() ? c : null;
            }
            File inPath = PathEnvironmentVariableUtil.findInPath(c);
            return inPath != null ? inPath.getAbsolutePath() : null;
        }

        // 2. Detecção automática: primeiro o PATH.
        String exeName = SystemInfo.isWindows ? "claude.cmd" : "claude";
        File inPath = PathEnvironmentVariableUtil.findInPath(exeName);
        if (inPath != null) {
            return inPath.getAbsolutePath();
        }
        if (SystemInfo.isWindows) {
            File alt = PathEnvironmentVariableUtil.findInPath("claude.exe");
            if (alt == null) {
                alt = PathEnvironmentVariableUtil.findInPath("claude");
            }
            if (alt != null) {
                return alt.getAbsolutePath();
            }
        }

        // 3. Locais de instalação padrão.
        for (String candidate : defaultInstallLocations()) {
            File f = new File(candidate);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Detecção automática pura (ignora qualquer caminho já informado): procura no PATH e nos
     * locais de instalação padrão. Usada pelo botão "Detect" para SEMPRE reescrever o campo.
     */
    @Nullable
    static String autoDetectExecutable() {
        return resolveExecutable(null);
    }

    private static List<String> defaultInstallLocations() {
        List<String> locations = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (SystemInfo.isWindows) {
            String appData = System.getenv("APPDATA");
            String localAppData = System.getenv("LOCALAPPDATA");
            String programFiles = System.getenv("ProgramFiles");
            if (appData != null) {
                locations.add(appData + "\\npm\\claude.cmd");
                locations.add(appData + "\\npm\\claude.exe");
            }
            if (localAppData != null) {
                locations.add(localAppData + "\\Programs\\claude\\claude.exe");
            }
            if (home != null) {
                locations.add(home + "\\.local\\bin\\claude.exe");
                locations.add(home + "\\.claude\\local\\claude.exe");
            }
            if (programFiles != null) {
                locations.add(programFiles + "\\Claude\\claude.exe");
            }
        } else {
            if (home != null) {
                // Instalador nativo/local do Claude Code.
                locations.add(home + "/.local/bin/claude");
                locations.add(home + "/.claude/local/claude");
                locations.add(home + "/bin/claude");
                // npm global (via nvm ou instalação de usuário).
                locations.add(home + "/.npm-global/bin/claude");
            }
            // Locais globais comuns.
            locations.add("/usr/local/bin/claude");
            locations.add("/usr/bin/claude");
            locations.add("/opt/homebrew/bin/claude");     // macOS (Apple Silicon)
            locations.add("/usr/local/opt/claude/bin/claude");
        }
        return locations;
    }

    // ------------------------------------------------------------------------------------
    // Resolução para o modo WSL
    // ------------------------------------------------------------------------------------

    /** @return caminho do {@code wsl.exe}, ou {@code null} se não encontrado. */
    @Nullable
    static String resolveWslExe() {
        File inPath = PathEnvironmentVariableUtil.findInPath("wsl.exe");
        if (inPath != null) {
            return inPath.getAbsolutePath();
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            systemRoot = "C:\\Windows";
        }
        for (String sub : new String[]{"System32\\wsl.exe", "Sysnative\\wsl.exe"}) {
            File f = new File(systemRoot, sub);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        // Em ambiente não-Windows (ex.: testes), simplesmente não existe.
        return SystemInfo.isWindows ? "wsl.exe" : null;
    }

    /**
     * Lista as distribuições do WSL instaladas ({@code wsl.exe -l -q}). Força saída UTF-8 via
     * a variável {@code WSL_UTF8=1}; caso não seja respeitada (WSL antigo), remove bytes nulos
     * do UTF-16 como degradação graciosa.
     *
     * @return nomes das distros, ou lista vazia em caso de falha / fora do Windows.
     */
    @NotNull
    static List<String> listWslDistros() {
        String wsl = resolveWslExe();
        if (wsl == null) {
            return List.of();
        }
        GeneralCommandLine cmd = new GeneralCommandLine(wsl);
        cmd.addParameters("-l", "-q");
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        cmd.withEnvironment("WSL_UTF8", "1");
        Result r = execute(cmd, null, null, 15_000L, "the WSL distro listing");

        List<String> distros = new ArrayList<>();
        if (r.ok && r.output != null) {
            String cleaned = r.output.replaceAll("[\\x00\\x{FEFF}]", "");
            for (String line : cleaned.split("\\r?\\n")) {
                String d = line.trim();
                if (!d.isEmpty()) {
                    distros.add(d);
                }
            }
        }
        return distros;
    }

    /**
     * Sonda o WSL pelo caminho do claude via {@code command -v claude}, usando o mesmo PATH
     * da execução. SEMPRE sonda (ignora qualquer caminho já informado), de modo que o botão
     * "Detect" reescreva o campo.
     *
     * @return o caminho no WSL, ou {@code null} se não encontrado.
     */
    @Nullable
    static String probeWslClaudePath(@Nullable String distro) {
        String wsl = resolveWslExe();
        if (wsl == null) {
            return null;
        }
        GeneralCommandLine cmd = new GeneralCommandLine(wsl);
        appendWslPrefix(cmd, distro);
        cmd.addParameter("bash");
        cmd.addParameter("-c");
        cmd.addParameter(WSL_PATH_SETUP + "command -v claude");
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        Result r = execute(cmd, null, null, 15_000L, "the WSL probe");
        if (r.ok && r.output != null) {
            for (String line : r.output.split("\\r?\\n")) {
                String p = line.trim();
                if (!p.isEmpty()) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Testa a integração ponta-a-ponta: executa uma chamada mínima ao Claude (com os
     * parâmetros informados) e verifica se ele responde — valida executável encontrado,
     * execução (WSL/nativo), autenticação e resposta. Deve rodar em background.
     */
    @NotNull
    static Result testIntegration(@NotNull ClaudeCommitSettings settings) {
        ClaudeCommitSettings probe = new ClaudeCommitSettings();
        probe.claudePath = settings.claudePath;
        probe.useWsl = settings.useWsl;
        probe.wslDistro = settings.wslDistro;
        probe.model = settings.model;
        probe.additionalArgs = settings.additionalArgs;
        probe.timeoutSeconds = Math.max(15, Math.min(settings.timeoutSeconds, 90));
        probe.promptTemplate = "Reply with exactly the word CLAUDE_OK and nothing else.";
        return run(probe, null, "", null);
    }

    private static String readFully(InputStream is) {
        try {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String get(Future<String> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return "";
        }
    }
}
