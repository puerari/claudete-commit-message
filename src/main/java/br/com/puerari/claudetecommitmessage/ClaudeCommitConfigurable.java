package br.com.puerari.claudetecommitmessage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;

/**
 * Interface de configuração do plugin.
 *
 * <p>Construída apenas com APIs estáveis para que {@code createComponent()} nunca falhe e a
 * página apareça de forma confiável.
 */
public final class ClaudeCommitConfigurable implements Configurable {

    private static final String[] MODEL_SUGGESTIONS =
            {"", "default", "sonnet", "haiku", "opus", "fable"};

    private JBTextField pathField;
    private JButton browseButton;
    private JButton detectButton;
    private JBLabel detectedLabel;

    private JBCheckBox wslCheck;
    private ComboBox<String> distroCombo;
    private JButton loadDistrosButton;
    private JBLabel distroStatusLabel;

    private ComboBox<String> modelCombo;
    private JBTextField argsField;
    private JBTextField timeoutField;
    private JButton testButton;
    private JBLabel testStatusLabel;
    private JBTextArea promptArea;
    private JPanel root;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Claudete Commit Message";
    }

    @Override
    public @Nullable JComponent createComponent() {
        // --- Caminho do executável + Procurar + Detectar ---
        pathField = new JBTextField();
        pathField.getEmptyText().setText("Auto-detect (PATH + default install locations)");
        browseButton = new JButton("Browse…");
        browseButton.addActionListener(ev -> browseForExecutable());
        detectButton = new JButton("Detect");
        detectButton.addActionListener(ev -> detectAsync());
        JPanel pathButtons = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        pathButtons.add(browseButton, BorderLayout.WEST);
        pathButtons.add(detectButton, BorderLayout.EAST);
        JPanel pathRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        pathRow.add(pathField, BorderLayout.CENTER);
        pathRow.add(pathButtons, BorderLayout.EAST);
        detectedLabel = new JBLabel();
        detectedLabel.setForeground(JBColor.GRAY);

        // --- WSL ---
        wslCheck = new JBCheckBox("Run via WSL (native Windows PhpStorm/PyCharm with Claude in WSL)");
        wslCheck.addActionListener(ev -> updateWslEnabled());
        distroCombo = new ComboBox<>();
        distroCombo.setEditable(true);
        loadDistrosButton = new JButton("Load");
        loadDistrosButton.addActionListener(ev -> loadDistrosAsync());
        JPanel distroRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        distroRow.add(distroCombo, BorderLayout.CENTER);
        distroRow.add(loadDistrosButton, BorderLayout.EAST);
        distroStatusLabel = new JBLabel("Check \"Run via WSL\" and click Load to list the distros.");
        distroStatusLabel.setForeground(JBColor.GRAY);

        // --- Modelo (aliases sugeridos; editável) ---
        modelCombo = new ComboBox<>(MODEL_SUGGESTIONS);
        modelCombo.setEditable(true);
        JBLabel modelHint = new JBLabel(
                "Aliases (sonnet/opus/haiku/fable) track the latest model; or type a full name. Empty = CLI default.");
        modelHint.setForeground(JBColor.GRAY);

        // --- Demais campos ---
        argsField = new JBTextField();
        argsField.getEmptyText().setText("Additional CLI arguments (optional)");
        timeoutField = new JBTextField();

        // --- Testar integração ---
        testButton = new JButton("Test integration");
        testButton.addActionListener(ev -> testAsync());
        testStatusLabel = new JBLabel("Makes a minimal call to Claude to validate the executable, execution, and authentication.");
        testStatusLabel.setForeground(JBColor.GRAY);
        JPanel testRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        testRow.add(testButton, BorderLayout.WEST);
        testRow.add(testStatusLabel, BorderLayout.CENTER);

        // --- Prompt + Restaurar padrão ---
        JButton resetPromptButton = new JButton("Restore default");
        resetPromptButton.addActionListener(ev -> {
            promptArea.setText(ClaudeCommitSettings.DEFAULT_PROMPT);
            promptArea.setCaretPosition(0);
        });
        JPanel promptHeader = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        promptHeader.add(new JBLabel("Prompt sent to Claude (the diff is sent via standard input):"),
                BorderLayout.CENTER);
        promptHeader.add(resetPromptButton, BorderLayout.EAST);

        promptArea = new JBTextArea(10, 60);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        JBScrollPane promptScroll = new JBScrollPane(promptArea);
        promptScroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(220)));

        root = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Claude executable path:"), pathRow, 1, false)
                .addComponentToRightColumn(detectedLabel, 1)
                .addComponent(wslCheck)
                .addLabeledComponent(new JBLabel("WSL distro:"), distroRow, 1, false)
                .addComponentToRightColumn(distroStatusLabel, 1)
                .addLabeledComponent(new JBLabel("Model:"), modelCombo, 1, false)
                .addComponentToRightColumn(modelHint, 1)
                .addLabeledComponent(new JBLabel("Additional arguments:"), argsField, 1, false)
                .addLabeledComponent(new JBLabel("Timeout (seconds):"), timeoutField, 1, false)
                .addComponent(testRow)
                .addSeparator()
                .addComponent(promptHeader)
                .addComponentFillVertically(promptScroll, 0)
                .getPanel();
        root.setBorder(JBUI.Borders.empty(10));

        reset();
        return root;
    }

    private void updateWslEnabled() {
        boolean on = wslCheck.isSelected();
        distroCombo.setEnabled(on);
        loadDistrosButton.setEnabled(on);
    }

    /** Abre o seletor de arquivos para escolher o executável do claude. */
    private void browseForExecutable() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Select the Claude Code executable");
        VirtualFile file = FileChooser.chooseFile(descriptor, null, null);
        if (file != null) {
            pathField.setText(toWslPathIfApplicable(file.getPath()));
        }
    }

    /**
     * No modo WSL, converte um caminho UNC do WSL ({@code //wsl.localhost/Distro/...}) para o
     * caminho interno ({@code /...}); caso contrário devolve o caminho como está.
     */
    private String toWslPathIfApplicable(String path) {
        if (path == null || !wslCheck.isSelected()) {
            return path;
        }
        String p = path.replace('\\', '/');
        String lower = p.toLowerCase();
        for (String prefix : new String[]{"//wsl.localhost/", "//wsl$/"}) {
            if (lower.startsWith(prefix)) {
                String rest = p.substring(prefix.length()); // Distro/home/...
                int slash = rest.indexOf('/');
                if (slash >= 0) {
                    return rest.substring(slash); // /home/...
                }
            }
        }
        return path;
    }

    /** Resolve o executável em background e preenche automaticamente o campo. */
    private void detectAsync() {
        final boolean useWsl = wslCheck.isSelected();
        final String distro = comboText(distroCombo);

        detectButton.setEnabled(false);
        detectedLabel.setForeground(JBColor.GRAY);
        detectedLabel.setText("Detecting…");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // Detectar SEMPRE reescreve o campo: no WSL re-sonda; no nativo, procura no
            // Windows (PATH + locais padrão) ignorando o que já estiver digitado.
            final String resolved = useWsl
                    ? ClaudeCliRunner.probeWslClaudePath(distro)
                    : ClaudeCliRunner.autoDetectExecutable();
            final String message = resolved != null
                    ? (useWsl ? "Found in WSL: " : "Found: ") + resolved
                    : "Not found. Enter the path manually or use Browse…";

            ApplicationManager.getApplication().invokeLater(() -> {
                if (resolved != null) {
                    pathField.setText(resolved); // autopreenchimento
                    detectedLabel.setForeground(JBColor.GRAY);
                } else {
                    detectedLabel.setForeground(JBColor.RED);
                }
                detectedLabel.setText(message);
                detectButton.setEnabled(true);
            }, ModalityState.any());
        });
    }

    /** Carrega as distros do WSL em background e popula o ComboBox. */
    private void loadDistrosAsync() {
        loadDistrosButton.setEnabled(false);
        distroStatusLabel.setForeground(JBColor.GRAY);
        distroStatusLabel.setText("Loading WSL distros…");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final List<String> distros = ClaudeCliRunner.listWslDistros();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (distros.isEmpty()) {
                    distroStatusLabel.setForeground(JBColor.RED);
                    distroStatusLabel.setText("No distro found (is WSL installed? This only works on Windows).");
                } else {
                    String current = comboText(distroCombo);
                    distroCombo.removeAllItems();
                    distroCombo.addItem("");
                    for (String d : distros) {
                        distroCombo.addItem(d);
                    }
                    setComboText(distroCombo, current);
                    distroStatusLabel.setForeground(JBColor.GRAY);
                    distroStatusLabel.setText(distros.size() + " distro(s) found.");
                }
                loadDistrosButton.setEnabled(wslCheck.isSelected());
            }, ModalityState.any());
        });
    }

    /** Testa a integração usando os valores ATUAIS da tela (antes mesmo de aplicar). */
    private void testAsync() {
        ClaudeCommitSettings snapshot = new ClaudeCommitSettings();
        snapshot.claudePath = pathField.getText().trim();
        snapshot.useWsl = wslCheck.isSelected();
        snapshot.wslDistro = comboText(distroCombo);
        snapshot.model = comboText(modelCombo);
        snapshot.additionalArgs = argsField.getText().trim();
        snapshot.timeoutSeconds = parseTimeoutOrDefault();

        testButton.setEnabled(false);
        testStatusLabel.setForeground(JBColor.GRAY);
        testStatusLabel.setText("Testing the integration with Claude… (this may take a few seconds)");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final ClaudeCliRunner.Result r = ClaudeCliRunner.testIntegration(snapshot);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (r.ok) {
                    testStatusLabel.setForeground(JBColor.GRAY);
                    testStatusLabel.setText("✔ Integration OK — Claude responded.");
                } else {
                    testStatusLabel.setForeground(JBColor.RED);
                    testStatusLabel.setText("✘ Failed: " + shorten(r.error));
                }
                testButton.setEnabled(true);
            }, ModalityState.any());
        });
    }

    private int parseTimeoutOrDefault() {
        try {
            return Integer.parseInt(timeoutField.getText().trim());
        } catch (NumberFormatException e) {
            return 120;
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "unknown error.";
        }
        String t = s.strip();
        return t.length() > 220 ? t.substring(0, 220) + "…" : t;
    }

    @Override
    public boolean isModified() {
        ClaudeCommitSettings s = ClaudeCommitSettings.getInstance();
        return !pathField.getText().equals(nullToEmpty(s.claudePath))
                || wslCheck.isSelected() != s.useWsl
                || !comboText(distroCombo).equals(nullToEmpty(s.wslDistro))
                || !comboText(modelCombo).equals(nullToEmpty(s.model))
                || !argsField.getText().equals(nullToEmpty(s.additionalArgs))
                || !timeoutField.getText().equals(String.valueOf(s.timeoutSeconds))
                || !promptArea.getText().equals(nullToEmpty(s.promptTemplate));
    }

    @Override
    public void apply() throws ConfigurationException {
        ClaudeCommitSettings s = ClaudeCommitSettings.getInstance();

        int timeout;
        try {
            timeout = Integer.parseInt(timeoutField.getText().trim());
        } catch (NumberFormatException e) {
            throw new ConfigurationException("The timeout must be an integer number (seconds).");
        }
        if (timeout <= 0) {
            throw new ConfigurationException("The timeout must be greater than zero.");
        }

        String prompt = promptArea.getText();
        if (prompt.isBlank()) {
            throw new ConfigurationException("The prompt cannot be empty.");
        }

        s.claudePath = pathField.getText().trim();
        s.useWsl = wslCheck.isSelected();
        s.wslDistro = comboText(distroCombo);
        s.model = comboText(modelCombo);
        s.additionalArgs = argsField.getText().trim();
        s.timeoutSeconds = timeout;
        s.promptTemplate = prompt;
    }

    @Override
    public void reset() {
        ClaudeCommitSettings s = ClaudeCommitSettings.getInstance();
        pathField.setText(nullToEmpty(s.claudePath));
        wslCheck.setSelected(s.useWsl);
        setComboText(distroCombo, nullToEmpty(s.wslDistro));
        setComboText(modelCombo, nullToEmpty(s.model));
        argsField.setText(nullToEmpty(s.additionalArgs));
        timeoutField.setText(String.valueOf(s.timeoutSeconds));
        promptArea.setText(nullToEmpty(s.promptTemplate));
        promptArea.setCaretPosition(0);
        detectedLabel.setText("");
        updateWslEnabled();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return pathField;
    }

    private static String comboText(ComboBox<String> combo) {
        Object item = combo.getEditor().getItem();
        return item == null ? "" : item.toString().trim();
    }

    private static void setComboText(ComboBox<String> combo, String value) {
        combo.setSelectedItem(value);
        combo.getEditor().setItem(value);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
