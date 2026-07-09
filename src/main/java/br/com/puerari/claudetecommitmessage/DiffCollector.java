package br.com.puerari.claudetecommitmessage;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diff.impl.patch.FilePatch;
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder;
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Monta um diff unificado (formato git) a partir das mudanças selecionadas na ferramenta
 * de commit, independentemente do VCS (git, mercurial, etc.).
 *
 * <p>Arquivos <b>unversioned</b> marcados para o commit são tratados explicitamente: cada um
 * é convertido em uma alteração sintética de "arquivo novo" (before = nulo, after = conteúdo
 * atual em disco) e passa pelo mesmo gerador de patch, produzindo um diff de arquivo novo
 * consistente (incluindo detecção de binário e codificação).
 */
final class DiffCollector {

    /** Limite de segurança para o tamanho do diff enviado ao Claude. */
    private static final int MAX_DIFF_CHARS = 200_000;

    private DiffCollector() {
    }

    /**
     * @param changes           mudanças versionadas incluídas no commit
     * @param unversionedFiles  arquivos unversioned marcados no commit (podem ser {@code null})
     * @return o diff unificado, possivelmente truncado; string vazia se não houver conteúdo.
     */
    @NotNull
    static String collect(@NotNull Project project,
                          @NotNull Collection<? extends Change> changes,
                          @Nullable Collection<? extends FilePath> unversionedFiles)
            throws Exception {
        String basePath = project.getBasePath();
        final Path base = Paths.get(basePath != null ? basePath : System.getProperty("user.dir"));

        String diff = ReadAction.compute(() -> {
            List<Change> all = new ArrayList<>(changes);
            if (unversionedFiles != null) {
                for (FilePath fp : unversionedFiles) {
                    // Arquivo novo: sem revisão anterior, conteúdo = arquivo atual em disco.
                    all.add(new Change(null, CurrentContentRevision.create(fp)));
                }
            }
            if (all.isEmpty()) {
                return "";
            }
            List<FilePatch> patches =
                    IdeaTextPatchBuilder.buildPatch(project, all, base, false, false);
            StringWriter writer = new StringWriter();
            UnifiedDiffWriter.write(project, patches, writer, "\n", null);
            return writer.toString();
        });

        if (diff == null) {
            return "";
        }
        if (diff.length() > MAX_DIFF_CHARS) {
            diff = diff.substring(0, MAX_DIFF_CHARS)
                    + "\n\n[... diff truncado por exceder " + MAX_DIFF_CHARS + " caracteres ...]";
        }
        return diff;
    }
}
