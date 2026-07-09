package br.com.puerari.claudetecommitmessage;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rastreia, por projeto, a geração de mensagem em andamento, permitindo cancelá-la.
 */
@Service(Service.Level.PROJECT)
public final class ClaudeCommitTaskManager {

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicReference<ProgressIndicator> indicator = new AtomicReference<>();

    public static ClaudeCommitTaskManager getInstance(@NotNull Project project) {
        return project.getService(ClaudeCommitTaskManager.class);
    }

    /** Marca o início. Retorna {@code false} se já houver uma geração em andamento. */
    public boolean tryStart() {
        return active.compareAndSet(false, true);
    }

    /** Associa o indicador de progresso da tarefa (para permitir cancelamento). */
    public void attachIndicator(@Nullable ProgressIndicator ind) {
        indicator.set(ind);
    }

    /** Finaliza a geração (sucesso, erro ou cancelamento). */
    public void finish() {
        indicator.set(null);
        active.set(false);
    }

    public boolean isRunning() {
        return active.get();
    }

    /** Cancela a geração em andamento, se houver. */
    public void cancel() {
        ProgressIndicator ind = indicator.get();
        if (ind != null) {
            ind.cancel();
        } else {
            // Ainda não há indicador (janela mínima entre iniciar e a tarefa arrancar):
            // libera o estado para que o botão volte ao normal.
            active.set(false);
        }
    }
}
