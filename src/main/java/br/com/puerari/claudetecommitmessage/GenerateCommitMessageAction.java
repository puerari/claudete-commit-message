package br.com.puerari.claudetecommitmessage;

import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.ui.Refreshable;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.vcs.commit.CommitWorkflowUi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Ação que gera a mensagem de commit a partir dos arquivos selecionados, usando a CLI do
 * Claude Code. Fica na barra de ferramentas do campo de mensagem de commit.
 *
 * <p>Comporta-se como um botão com estado: enquanto uma geração está em andamento, o ícone
 * vira um quadrado vermelho (stop) e um clique cancela a operação.
 */
public final class GenerateCommitMessageAction extends AnAction {

    private static final Icon GENERATE_ICON =
            IconLoader.getIcon("/icons/generate.svg", GenerateCommitMessageAction.class);
    private static final Icon STOP_ICON =
            IconLoader.getIcon("/icons/stop.svg", GenerateCommitMessageAction.class);

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // EDT: precisamos ler o estado de inclusão (arquivos marcados) da ferramenta de
        // commit, que é estado de UI e deve ser acessado na thread de eventos.
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        Project project = e.getProject();
        boolean running = project != null && ClaudeCommitTaskManager.getInstance(project).isRunning();

        if (running) {
            presentation.setIcon(STOP_ICON);
            presentation.setText("Stop Message Generation");
            presentation.setDescription("Cancel the commit message generation");
            presentation.setEnabled(true);
        } else {
            presentation.setIcon(GENERATE_ICON);
            presentation.setText("Generate Commit Message with Claude");
            presentation.setDescription("Generate the message from the selected files using the Claude Code CLI");
            // Habilita apenas quando há ao menos um arquivo marcado para o commit.
            presentation.setEnabled(project != null && hasSelectedFiles(e.getDataContext()));
        }
    }

    /** @return true se houver ao menos uma mudança ou arquivo unversioned marcado no commit. */
    private static boolean hasSelectedFiles(@NotNull DataContext dc) {
        CommitWorkflowUi workflowUi = VcsDataKeys.COMMIT_WORKFLOW_UI.getData(dc);
        if (workflowUi != null) {
            return !workflowUi.getIncludedChanges().isEmpty()
                    || !workflowUi.getIncludedUnversionedFiles().isEmpty();
        }
        CheckinProjectPanel panel = findCommitPanel(dc);
        if (panel != null) {
            return !panel.getSelectedChanges().isEmpty();
        }
        Change[] changes = VcsDataKeys.CHANGES.getData(dc);
        return changes != null && changes.length > 0;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        ClaudeCommitTaskManager manager = ClaudeCommitTaskManager.getInstance(project);

        // Se já está gerando, este clique é o "stop".
        if (manager.isRunning()) {
            manager.cancel();
            requestToolbarUpdate();
            return;
        }

        DataContext dc = e.getDataContext();
        CheckinProjectPanel panel = findCommitPanel(dc);
        CommitWorkflowUi workflowUi = VcsDataKeys.COMMIT_WORKFLOW_UI.getData(dc);

        // Captura, ainda na EDT, o setter da mensagem, as mudanças e os arquivos unversioned
        // efetivamente MARCADOS (incluídos) para o commit.
        final Consumer<String> messageSetter;
        if (panel != null) {
            messageSetter = panel::setCommitMessage;
        } else {
            var control = VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(dc);
            messageSetter = control != null ? control::setCommitMessage : null;
        }

        final Collection<Change> changes;
        final List<FilePath> unversioned;
        if (workflowUi != null) {
            // Ferramenta de commit moderna: expõe exatamente o que está marcado.
            changes = new ArrayList<>(workflowUi.getIncludedChanges());
            unversioned = new ArrayList<>(workflowUi.getIncludedUnversionedFiles());
        } else if (panel != null) {
            changes = new ArrayList<>(panel.getSelectedChanges());
            unversioned = Collections.emptyList();
        } else {
            Change[] arr = VcsDataKeys.CHANGES.getData(dc);
            changes = arr != null ? new ArrayList<>(Arrays.asList(arr)) : Collections.emptyList();
            unversioned = Collections.emptyList();
        }

        if (messageSetter == null) {
            ClaudeNotifier.error(project, "Could not access the commit message field.");
            return;
        }
        if (changes.isEmpty() && unversioned.isEmpty()) {
            ClaudeNotifier.warn(project, "Select at least one file to include in the commit.");
            return;
        }

        final ClaudeCommitSettings settings = ClaudeCommitSettings.getInstance();

        // Pré-checagem de configuração (modo nativo): dá feedback imediato e acionável, em vez
        // de iniciar a tarefa e falhar silenciosamente.
        if (!settings.useWsl && ClaudeCliRunner.resolveExecutable(settings.claudePath) == null) {
            ClaudeNotifier.errorWithSettings(project,
                    "Claude Code CLI not found on this system. If Claude is installed in WSL, "
                    + "enable \"Run via WSL\" in the settings; otherwise, provide the path to the "
                    + "executable.");
            return;
        }

        // Reserva o estado "em execução" antes de enfileirar a tarefa (evita clique duplo).
        if (!manager.tryStart()) {
            return;
        }
        requestToolbarUpdate();

        // Limpa o campo de mensagem: a geração começa do zero, sem restos da mensagem anterior.
        messageSetter.accept("");

        final String workingDir = project.getBasePath();

        new Task.Backgroundable(project, "Generating commit message with Claude", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                manager.attachIndicator(indicator);
                requestToolbarUpdate();

                indicator.setIndeterminate(true);
                indicator.setText("Collecting the diff of the selected changes…");

                String diff;
                try {
                    diff = DiffCollector.collect(project, changes, unversioned);
                } catch (ProcessCanceledException pce) {
                    throw pce;
                } catch (Exception ex) {
                    ClaudeNotifier.error(project, "Failed to build the diff: " + ex.getMessage());
                    return;
                }

                if (diff.isBlank()) {
                    ClaudeNotifier.warn(project,
                            "No textual changes found in the selected files.");
                    return;
                }

                indicator.setText("Querying Claude Code…");
                ClaudeCliRunner.Result result =
                        ClaudeCliRunner.run(settings, workingDir, diff, indicator);

                if (!result.ok) {
                    // Falhas de execução costumam ser de configuração (caminho/WSL/modelo):
                    // oferecemos o atalho para as configurações.
                    ClaudeNotifier.errorWithSettings(project, result.error);
                    return;
                }

                String message = cleanUp(result.output);
                if (message.isBlank()) {
                    ClaudeNotifier.error(project, "Claude Code returned no message.");
                    return;
                }

                // Preenche o campo de mensagem na EDT. ModalityState.any() garante execução
                // mesmo com o diálogo de commit modal aberto.
                ApplicationManager.getApplication().invokeLater(
                        () -> messageSetter.accept(message), ModalityState.any());
            }

            @Override
            public void onCancel() {
                ClaudeNotifier.info(project, "Message generation canceled.");
            }

            @Override
            public void onFinished() {
                manager.finish();
                requestToolbarUpdate();
            }
        }.queue();
    }

    /** Força a atualização das barras de ferramentas para refletir o novo ícone/estado. */
    private static void requestToolbarUpdate() {
        ApplicationManager.getApplication().invokeLater(
                () -> ActivityTracker.getInstance().inc(), ModalityState.any());
    }

    /**
     * Localiza o painel de commit no contexto de dados. Funciona tanto na ferramenta de
     * commit nova (tool window) quanto no diálogo modal clássico.
     */
    @Nullable
    private static CheckinProjectPanel findCommitPanel(@NotNull DataContext dc) {
        Refreshable refreshable = Refreshable.PANEL_KEY.getData(dc);
        if (refreshable instanceof CheckinProjectPanel) {
            return (CheckinProjectPanel) refreshable;
        }
        return null;
    }

    /**
     * Remove ruídos comuns da saída: cercas de código markdown e aspas envolventes.
     */
    private static String cleanUp(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();

        // Remove cercas de código ```...``` que envolvam toda a mensagem.
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.strip();
        }

        // Remove aspas que envolvam toda a mensagem.
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) {
            String inner = text.substring(1, text.length() - 1);
            if (!inner.contains("\n") || inner.chars().filter(c -> c == '"').count() == 0) {
                text = inner.strip();
            }
        }

        return text;
    }
}
