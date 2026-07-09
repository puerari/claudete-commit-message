# Claudete Commit Message

A plugin for JetBrains IDEs (PhpStorm, PyCharm, and other IntelliJ-based IDEs) that generates
Git commit messages from the **files you selected** in the Commit tool, using the
**Claude Code CLI**.

> Independent integration — not affiliated with or endorsed by Anthropic.

The button lives in the commit-message toolbar itself. Trigger it (or press `Ctrl+Alt+K`) and the
plugin builds a diff of the selected changes, sends it to `claude` in non-interactive mode, and
fills the message field with the result.

---

## How it works

1. Open the Commit tool window (or the commit dialog) and check the files you want to commit.
2. Click the **Claude** button next to the message field (or press `Ctrl+Alt+K`).
3. The plugin generates a unified diff of the selected changes (via the platform API — works with
   Git, Mercurial, etc.).
4. It runs `claude -p "<prompt>"` in the project directory, sending the diff on standard input.
5. The response is written into the commit-message field.

Generation runs in the background and can be canceled — while it runs, the icon turns into a red
stop square; click it to abort.

---

## Requirements

- The **Claude Code CLI** installed and **authenticated** (run `claude` once in a terminal to make
  sure it works without asking you to log in).
- A JetBrains IDE **2024.2 or newer** (build 242+).
- To **build** the plugin: **JDK 17**.

The plugin looks up the `claude` executable automatically:

- on the `PATH` (inheriting the user's login-shell environment);
- in standard install locations, for example:
  - Linux/macOS: `~/.local/bin/claude`, `~/.claude/local/claude`, `~/bin/claude`,
    `/usr/local/bin/claude`, `/opt/homebrew/bin/claude`, …
  - Windows: `%APPDATA%\npm\claude.cmd`, `%LOCALAPPDATA%\Programs\claude\claude.exe`, …

You can also set the path manually in **Settings → Tools → Claudete Commit Message** (there is a **Detect**
button that finds and fills in the path, a **Browse…** button to pick the file, and a
**Test integration** button to verify everything end to end).

---

## Building the plugin

The project uses the Gradle Wrapper — no local Gradle install is needed, only JDK 17.

```bash
# Produces the installable .zip in build/distributions/
./gradlew buildPlugin

# Runs a sandbox IDE instance with the plugin loaded
./gradlew runIde
```

The final artifact is `build/distributions/claudete-commit-message-<version>.zip`.

> The plugin is compiled against IntelliJ IDEA 2025.2 (platform APIs only), which keeps it
> compatible with all JetBrains IDEs (build 242+, no upper bound). To develop/run against
> PhpStorm or PyCharm directly, adjust the `intellijPlatform` dependency in `build.gradle.kts`
> (commented examples are provided there).

---

## Installing in the IDE

1. Build with `./gradlew buildPlugin` (or download the `.zip` from a release).
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the `.zip` from `build/distributions/`.
4. Restart the IDE.

---

## Usage

1. Make some changes and open the **Commit** tab.
2. Check the files you want.
3. Click the **Claude** button (icon) in the message toolbar, or press `Ctrl+Alt+K`.
4. Wait: the generated message appears in the field (replacing the current content).

The button is enabled only when at least one file is selected.

---

## Configuration

Under **Settings → Tools → Claudete Commit Message**:

| Setting                 | Description                                                          |
|-------------------------|---------------------------------------------------------------------|
| Executable path         | Manual CLI path. **Detect** fills it in; **Browse…** picks the file. Empty = auto-detection. |
| Run via WSL             | Invoke the CLI through `wsl.exe` (see below).                        |
| WSL distro              | Editable combo; the **Load** button lists distros (`wsl -l -q`). Empty = default distro. |
| Model                   | Editable combo with aliases (`sonnet`/`opus`/`haiku`/`fable`); or type a full name. Empty = CLI default. |
| Additional arguments    | Extra arguments passed to the CLI (quotes respected).               |
| Timeout (seconds)       | Maximum time to wait for a response (default: 120).                 |
| Test integration        | Runs a minimal real call to Claude and reports success/failure.     |
| Prompt                  | Instruction sent to Claude (the diff goes on standard input). **Restore default** resets it. |

### Running on native Windows with Claude in WSL

If PhpStorm/PyCharm runs **natively on Windows** but the Claude Code CLI is installed in **WSL**,
check **Run via WSL**. The plugin invokes:

```
wsl.exe [-d <distro>] -e bash -c 'export PATH=…; exec "$0" "$@"' <claude> -p "<prompt>" …
```

It uses `wsl.exe -e` (execute without the default shell) and a **non-login** `bash -c` so your
dotfiles are not sourced (avoiding shells that `exec zsh`); the `PATH` is extended explicitly so
`claude` is found. Leave the path empty to auto-detect, or set the absolute path **inside WSL**
(e.g. `/home/user/.local/bin/claude`). The **Detect** button probes WSL for the executable.

> If the Claude Code CLI is installed **natively on Windows** (npm/Windows installer), leave this
> option unchecked — auto-detection covers the standard Windows paths.

---

## Known limitations

- The generated message **replaces** the current content of the field.
- **Unversioned** files that are checked for commit are included in the diff as new files
  (in the modern Commit tool window). In the classic commit dialog, only selected versioned
  changes are considered.
- Very large diffs are truncated (safety limit of ~200k characters).

---

## Project structure

```
build.gradle.kts                     # Build config (IntelliJ Platform Gradle Plugin 2.x)
settings.gradle.kts
gradle/ , gradlew , gradlew.bat      # Gradle Wrapper
src/main/resources/META-INF/plugin.xml
src/main/resources/icons/
src/main/java/br/com/puerari/claudetecommitmessage/
    GenerateCommitMessageAction.java # Button / shortcut action (generate & stop toggle)
    DiffCollector.java               # Builds the diff of the selected changes
    ClaudeCliRunner.java             # Locates and runs the Claude Code CLI (native / WSL)
    ClaudeCommitSettings.java        # Persistent settings
    ClaudeCommitConfigurable.java    # Settings screen
    ClaudeCommitTaskManager.java     # Tracks the running task for cancellation
    ClaudeNotifier.java              # Notifications
```

---

## License

[MIT](LICENSE) © Puerari Solutions.
