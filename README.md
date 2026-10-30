# <img src="src/main/resources/icons/aiCommit.svg" width="32" height="32" alt="Local AI Commit Icon" valign="middle"/> Local AI Commit - IntelliJ IDEA Plugin

**Local AI Commit** is an intelligent, bottom-docked IntelliJ IDEA tool window plugin that replaces the traditional
commit workflow with local LLM-assisted Conventional Commit generation powered by **JetBrains Koog** (`ai.koog`).

---

## Features

### 1. Multi-Root Git Changes Tree (Left Panel)

- **Multi-Level Git Hierarchy**: Automatically detects and separates changes across the main project and nested Git
  submodules.
- **Hierarchical Directory Folding**: Collapses intermediate single-child folder paths for clean navigation.
- **Color-Coded Status**:
    - Blue: Modified uncommitted files (`FileStatus.MODIFIED`)
    - Green: Newly added and unversioned files (`FileStatus.ADDED`)
    - Gray: Deleted files (`FileStatus.DELETED`)
    - Ignored files: Filtered out according to `.gitignore`.
- **Right-Click Context Menu**:
    - **Show Diff** (Native IntelliJ diff viewer)
    - **Show Diff in New Tab**
  - **Rollback Changes...**
    - **Add into VCS** (`git add`)
  - **Add to .gitignore** (Appends path pattern and immediately refreshes status)
  - **Delete** (Safely deletes unversioned files via write command action)
    - **Jump to Source**
    - **Copy Path**
    - **Local History** (`LocalHistory.ShowHistory`)
- **Selective Checkboxes**: Choose exactly which files, directories, or repositories to include in generation and
  commit. Double-click any file to view its diff or open in editor.
- **Automatic Refresh & Lifecycle Synchronization**: Subscribes to Git repository events, message bus updates, and VFS
  changes without deprecated APIs.

### 2. Direct Local LLM Integration via JetBrains Koog (Right Panel)

- **Zero-Service Overhead**: Directly connects to local endpoints (defaults to Ollama at `http://localhost:11434`, or
  custom endpoints like LM Studio / vLLM / llama.cpp) via HTTP using Koog's OpenAI-compatible client.
- **Dynamic Context Window Detection**: Queries local server properties (e.g. `/props`, `/v1/models`) to determine
  context limits (`contextWindowTokens`) and dynamically computes `maxDiffTokens` (~85% budget).
- **Dynamic Token-Driven Chunker**: Bottom-up directory tree chunking based strictly on token
  budgets, grouping changes logically.
- **Real-Time Token Streaming**: Token-by-token live streaming for both chunk summaries and the final commit message,
  with active **[Cancel Generation]** button.
- **Repetition Loop Detector**: Real-time monitoring for repetitive loops from the local LLM, aborting loops and
  deduplicating lines.
- **Intelligent File & Media Filtering**: Automatically filters out binary files (images, audio, video, archives,
  compiled artifacts, PDFs) from diff inspection, preventing token budget waste.
- **SVG Asset Optimization**: Automatically generates descriptive summary bullets for SVG changes without polluting
  token context with raw XML paths.

### 3. Pre-Commit Code Preparation

Toggleable actions run before commit:

- `[ ] Rearrange Code`
- `[x] Reformat Code`
- `[x] Optimize Imports`

### 4. Interactive Review & Commit

- Editable preview text area to refine the generated commit message.
- 3 Primary action buttons:
  - **[ Commit ]**: Formats code (if enabled), stages selected files, and executes commit only for the selected files.
  - **[ Regenerate ]**: Re-triggers generation with current selection.
  - **[ Cancel ]**: Returns to selection view.

### 5. Release Report Generation & Branch-Based Releases (Release Tab)

The right panel features two tabs: **Commit** (default) and **Release**:

- **Clean Working Tree Validation**: Enforces that all files in the target repository root are already committed or
  ignored (0 pending changes). If dirty, warns the user and provides a one-click button to switch to the **Commit Tab**.
- **Single Git Root Guidelines**: Enforces selecting exactly one repository root per release report.
- **Always-Visible Repository Roots**: All repository roots and submodules stay visible in the left tree even when
  containing 0 changes.
- **Branch-Based Workflow**:
  - **Source Branch**: Select the origin branch (defaults to the active branch, e.g. `master`).
  - **Release Branch**: Default formatted as `release/{version}` (e.g. `release/1.0.0`), auto-populated from the
    detected version.
- **Smart Version File & Number Extraction**:
  - Automatically identifies candidate version files (`libs.versions.toml`, `build.gradle.kts`, `build.gradle`,
    `gradle.properties`, `package.json`, `pom.xml`, etc.).
  - Fast pattern parsing with local AI fallback to detect current release version strings.
- **Commit Range & Diff Modes**:
  - **[x] Auto-detect branch diff split (merge-base)**: Compares branches by finding the fork point (`git merge-base`)
    between the release branch and the source branch, collecting all commits in between.
  - **Manual Mode**: Allows specifying an exact starting revision or commit hash from the source branch.
- **Interactive Review & Confirmation**:
  - Generates and streams the release report into an editable editor area.
  - **[ Confirm Release ]**: Creates and switches to the release branch (`release/{version}`), commits the version file
    with the release report, and updates repository state.
  - **[ Cancel ]**: Discards the preview and returns to parameter configuration.

---

## Tested Local LLM Configuration (8GB VRAM)

The plugin has been fully tested and validated on an **8GB VRAM** setup using `llama-server` (llama.cpp) with an MoE
thinking model and high context window (128k). Below is an example startup command tailored for fast inference,
quantized KV cache, speculative ngram decoding, and CPU offloading of token embeddings:

```bash
llama-server \
  -m Mellum2.1-12B-A2.5B-Thinking-MXFP4_MOE.gguf \
  -ngl 99 \
  -ot "token_embd\\.weight=CPU" \
  --no-mmap \
  -np 1 \
  -fa on \
  -ctk q4_0 -ctv q4_0 \
  -c 131072 \
  -b 1024 -ub 256 \
  --spec-type ngram-mod \
  --spec-ngram-mod-n-max 8 \
  --reasoning-format deepseek \
  --jinja \
  --temp 0.6 --top-p 0.95 --top-k 20 --min-p 0.05 \
  --port 11434
```

> **Key Flags Overview:**
> - `-c 131072`: Generous 128k context window to ingest extensive code diffs.
> - `-ctk q4_0 -ctv q4_0`: 4-bit KV cache quantization to fit 128k context comfortably within 8GB VRAM.
> - `-ot "token_embd\\.weight=CPU"`: Keeps the embedding table in system RAM to preserve dedicated VRAM for model
    layers.
> - `--port 11434`: Matches the plugin's default connection endpoint (`http://localhost:11434`).

---

## Installation & Building

### Build Plugin Package

```bash
./gradlew buildPlugin
```

The distribution archive is generated at `build/distributions/local-ai-commit-x.x.x.zip`.

### Install in IntelliJ IDEA

1. Open IntelliJ IDEA -> **Settings / Preferences** (`Ctrl + Alt + S`).
2. Go to **Plugins** -> click the **Gear Icon (⚙)** -> **Install Plugin from Disk...**.
3. Select `build/distributions/local-ai-commit-x.x.x.zip` and restart the IDE.
4. The **AI Commit** tool window
   (<img src="src/main/resources/icons/aiCommit.svg" width="16" height="16" alt="AI Commit Icon" valign="middle"/>)
   appears docked on the bottom bar.
