# Local AI Commit - IntelliJ IDEA Plugin

**Local AI Commit** is an intelligent, bottom-docked IntelliJ IDEA tool window plugin that replaces the traditional
commit workflow with local LLM-assisted Conventional Commit generation powered by **JetBrains Koog** (`ai.koog`).

---

## Features

### 1. Multi-Root Git Changes Tree (Left Panel)

- **Multi-Level Git Hierarchy**: Automatically detects and separates changes across the main project and nested Git
  submodules.
- **Color-Coded Status**:
    - Blue: Modified uncommitted files (`FileStatus.MODIFIED`)
    - Green: Newly added and unversioned files (`FileStatus.ADDED`)
    - Gray: Deleted files (`FileStatus.DELETED`)
    - Ignored files: Filtered out according to `.gitignore`.
- **Right-Click Context Menu**:
    - **Show Diff** (Native IntelliJ diff viewer)
    - **Show Diff in New Tab**
    - **Rollback Changes**
    - **Add into VCS** (`git add`)
    - **Delete**
    - **Jump to Source**
    - **Copy Path**
    - **Local History** (`LocalHistory.ShowHistory`)
- **Selective Checkboxes**: Choose exactly which files, directories, or repositories to include in generation and
  commit.

### 2. Direct Local LLM Integration via JetBrains Koog (Right Panel)

- **Zero-Service Overhead**: Directly connects to local endpoints (e.g. `http://localhost:9090` or
  `http://localhost:11434`) via HTTP using Koog's OpenAI-compatible client.
- **Dynamic Context Window Detection**: Queries local server properties (e.g. `/props`, `/v1/models`) to determine
  context limits (`contextWindowTokens`) and dynamically computes `maxDiffTokens` (~85% budget).
- **Dynamic Token-Driven Chunker (No static file limits)**: Bottom-up directory tree chunking based strictly on token
  budgets, grouping changes logically.
- **Real-Time Token Streaming**: Token-by-token live stream console with active **[Cancel Generation]** button.
- **Repetition Loop Detector**: Real-time monitoring for repetitive loops from the local LLM, aborting loops and
  deduplicating lines.
- **SVG Asset Optimization**: Automatically generates bullets for SVG files without wasting token context.

### 3. Pre-Commit Code Preparation

Toggleable actions run before commit:

- `[x] Rearrange Code`
- `[x] Reformat Code`
- `[x] Optimize Imports`

### 4. Interactive Review & Commit

- Editable preview text area to refine the generated commit message.
- 3 Primary action buttons:
    - **[ Commit ]**: Stages selected files and executes commit.
    - **[ Regenerate ]**: Re-triggers generation with current selection.
    - **[ Cancel ]**: Returns to selection view.

---

## Installation & Building

### Build Plugin Package

```bash
./gradlew buildPlugin
```

The distribution archive is generated at `build/distributions/local-ai-commit-1.0.0.zip`.

### Install in IntelliJ IDEA

1. Open IntelliJ IDEA -> **Settings / Preferences** (`Ctrl + Alt + S`).
2. Go to **Plugins** -> click the **Gear Icon (⚙)** -> **Install Plugin from Disk...**.
3. Select `build/distributions/local-ai-commit-1.0.0.zip` and restart the IDE.
4. The **AI Commit** tool window appears docked on the bottom bar.
