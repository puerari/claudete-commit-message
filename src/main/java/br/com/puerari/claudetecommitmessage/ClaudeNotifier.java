package br.com.puerari.claudetecommitmessage;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

final class ClaudeNotifier {

    private static final String GROUP_ID = "Claudete Commit Message";

    private ClaudeNotifier() {
    }

    static void info(@Nullable Project project, String content) {
        create(content, NotificationType.INFORMATION).notify(project);
    }

    static void warn(@Nullable Project project, String content) {
        create(content, NotificationType.WARNING).notify(project);
    }

    static void error(@Nullable Project project, String content) {
        create(content, NotificationType.ERROR).notify(project);
    }

    /** Notificação de erro com um atalho para abrir a tela de configurações do plugin. */
    static void errorWithSettings(@Nullable Project project, String content) {
        Notification notification = create(content, NotificationType.ERROR);
        notification.addAction(NotificationAction.createSimple("Abrir configurações", () ->
                ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, ClaudeCommitConfigurable.class)));
        notification.notify(project);
    }

    private static Notification create(String content, NotificationType type) {
        return NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification("Claudete Commit Message", content, type);
    }
}
