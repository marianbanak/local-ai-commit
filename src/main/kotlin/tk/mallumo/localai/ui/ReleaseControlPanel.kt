package tk.mallumo.localai.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tk.mallumo.localai.git.GitChangesManager
import tk.mallumo.localai.llm.ReleaseReportStrategy
import tk.mallumo.localai.llm.VersionExtractor
import tk.mallumo.localai.settings.LocalAiCommitSettingsService
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

class ReleaseControlPanel(
    private val project: Project,
    private val gitChangesManager: GitChangesManager,
    private val cs: CoroutineScope,
    private val onReleaseCommitted: () -> Unit,
    private val onSwitchToCommitTab: () -> Unit = {}
) : JPanel(BorderLayout()) {

    private val LOG = Logger.getInstance(ReleaseControlPanel::class.java)
    private val settingsService = LocalAiCommitSettingsService.getInstance(project)

    // Current State
    private var selectedRepository: GitRepository? = null
    private var selectedVersionFile: VirtualFile? = null
    private var isCleanWorkingTree = true
    private var generationJob: Job? = null
    private var currentGeneratedReport: String = ""

    // UI Components
    // 1. Guidelines / Warning Banner
    private val guidelinePanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 12)
        )
        background = JBColor.namedColor("Panel.background", JBColor.PanelBackground)
    }
    private val guidelineLabel = JBLabel().apply {
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val resolveChangesBtn = JButton("Go to Commit Tab", AllIcons.Actions.Commit).apply {
        isVisible = false
        addActionListener { onSwitchToCommitTab() }
    }

    // 2. Configuration Form
    private val configPanel = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(8, 12)
    }

    private val repoComboBox = ComboBox<GitRepository>()
    private val versionFileComboBox = ComboBox<String>()
    private val browseVersionFileBtn = JButton(AllIcons.General.OpenDisk)
    private val detectedVersionField = JBTextField(10)
    private val detectVersionAiBtn = JButton("AI Extract", AllIcons.Actions.RefactoringBulb)

    // Branch controls
    private val sourceBranchComboBox = ComboBox<String>()
    private val releaseBranchField = JBTextField(16)

    // Range controls
    private val autoCompareBranchesCheckbox = JBCheckBox("Auto-detect branch diff split (merge-base)", true)
    private val revisionPanel = JPanel(BorderLayout(6, 0))
    private val revisionField = JBTextField(16)
    private val revisionHelpLabel =
        JBLabel("Paste the commit hash/revision from source branch where release history begins (inclusive):").apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = JBColor.GRAY
        }

    // Action buttons
    private val generateBtn = JButton("⚡ Generate Release Report").apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
    }
    private val cancelGenBtn = JButton("Cancel Generation").apply { isVisible = false }
    private val confirmReleaseBtn = JButton("✓ Confirm Release", AllIcons.Actions.Checked).apply {
        isVisible = false
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
    }
    private val cancelReviewBtn = JButton("Cancel", AllIcons.Actions.Cancel).apply { isVisible = false }
    private val copyBtn = JButton("📋 Copy Report", AllIcons.Actions.Copy).apply { isEnabled = false }

    // Output & Progress
    private val statusLabel = JBLabel("Ready", SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.GRAY
    }
    private val reportTextArea = JBTextArea().apply {
        isEditable = true
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    init {
        border = JBUI.Borders.empty()
        buildUI()
        reloadRepositories()
        updateGuideline()
    }

    private fun buildUI() {
        // 1. Top Guidelines
        val guideRightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        guideRightPanel.isOpaque = false
        guideRightPanel.add(resolveChangesBtn)
        guidelinePanel.add(guidelineLabel, BorderLayout.CENTER)
        guidelinePanel.add(guideRightPanel, BorderLayout.EAST)
        add(guidelinePanel, BorderLayout.NORTH)

        // 2. Center Content: Form + Action Bar + Report Area
        val centerContainer = JPanel(BorderLayout())

        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0

        // Row 0: Git Repository Selection
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Git Repository:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2
        repoComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is GitRepository) {
                    text = "${value.root.name} (${value.root.presentableUrl})"
                    icon = AllIcons.Vcs.Branch
                }
                return comp
            }
        }
        repoComboBox.addActionListener {
            val selected = repoComboBox.selectedItem as? GitRepository
            if (selected != selectedRepository) {
                selectedRepository = selected
                onRepositoryChanged()
            }
        }
        configPanel.add(repoComboBox, gbc)
        gbc.gridwidth = 1

        // Row 1: Version File Selection & Version number
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Version File:"), gbc)

        val fileSelectPanel = JPanel(BorderLayout(4, 0))
        fileSelectPanel.add(versionFileComboBox, BorderLayout.CENTER)
        browseVersionFileBtn.toolTipText = "Browse custom version file..."
        browseVersionFileBtn.addActionListener { browseCustomVersionFile() }
        fileSelectPanel.add(browseVersionFileBtn, BorderLayout.EAST)
        versionFileComboBox.addActionListener { onVersionFileSelectionChanged() }

        gbc.gridx = 1; gbc.weightx = 0.6
        configPanel.add(fileSelectPanel, gbc)

        val versionDisplayPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        versionDisplayPanel.add(JBLabel("Version:"))
        detectedVersionField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                updateDefaultReleaseBranch()
            }

            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                updateDefaultReleaseBranch()
            }

            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                updateDefaultReleaseBranch()
            }
        })
        versionDisplayPanel.add(detectedVersionField)
        detectVersionAiBtn.toolTipText = "Extract version string using local AI"
        detectVersionAiBtn.addActionListener { extractVersionViaAi() }
        versionDisplayPanel.add(detectVersionAiBtn)

        gbc.gridx = 2; gbc.weightx = 0.4
        configPanel.add(versionDisplayPanel, gbc)

        // Row 2: Source Branch & Release Branch
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Source Branch:"), gbc)

        gbc.gridx = 1; gbc.weightx = 0.5
        configPanel.add(sourceBranchComboBox, gbc)

        val releaseBranchPanel = JPanel(BorderLayout(6, 0))
        releaseBranchPanel.add(JBLabel("Release Branch:"), BorderLayout.WEST)
        releaseBranchPanel.add(releaseBranchField, BorderLayout.CENTER)
        gbc.gridx = 2; gbc.weightx = 0.5
        configPanel.add(releaseBranchPanel, gbc)

        // Row 3: Commit Diff Mode (Auto vs Manual)
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Commit Range:"), gbc)

        val rangeConfigPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        rangeConfigPanel.add(autoCompareBranchesCheckbox)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2
        configPanel.add(rangeConfigPanel, gbc)
        gbc.gridwidth = 1

        // Row 4: Manual Revision input row (conditional)
        row++
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0; gbc.gridwidth = 2
        revisionPanel.add(JBLabel("Revision:"), BorderLayout.WEST)
        revisionPanel.add(revisionField, BorderLayout.CENTER)
        revisionPanel.add(revisionHelpLabel, BorderLayout.SOUTH)
        revisionPanel.isVisible = !autoCompareBranchesCheckbox.isSelected
        configPanel.add(revisionPanel, gbc)
        gbc.gridwidth = 1

        autoCompareBranchesCheckbox.addActionListener {
            val auto = autoCompareBranchesCheckbox.isSelected
            revisionPanel.isVisible = !auto
            revalidate()
            repaint()
        }

        // Action Toolbar
        val actionToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 10, 6)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0),
                JBUI.Borders.empty(4, 6)
            )
        }
        generateBtn.addActionListener { startReportGeneration() }
        cancelGenBtn.addActionListener { cancelGeneration() }
        confirmReleaseBtn.addActionListener { executeConfirmRelease() }
        cancelReviewBtn.addActionListener { resetReviewState() }
        copyBtn.addActionListener { copyReportToClipboard() }

        actionToolbar.add(generateBtn)
        actionToolbar.add(cancelGenBtn)
        actionToolbar.add(confirmReleaseBtn)
        actionToolbar.add(cancelReviewBtn)
        actionToolbar.add(copyBtn)
        actionToolbar.add(Box.createHorizontalStrut(15))
        actionToolbar.add(statusLabel)

        val topSection = JPanel(BorderLayout())
        topSection.add(configPanel, BorderLayout.CENTER)
        topSection.add(actionToolbar, BorderLayout.SOUTH)
        centerContainer.add(topSection, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(reportTextArea).apply {
            border = JBUI.Borders.empty(8)
        }
        centerContainer.add(scrollPane, BorderLayout.CENTER)

        add(centerContainer, BorderLayout.CENTER)
    }

    fun reloadRepositories() {
        val repos = GitRepositoryManager.getInstance(project).repositories
        repoComboBox.removeAllItems()
        for (repo in repos) {
            repoComboBox.addItem(repo)
        }
        if (repos.isNotEmpty()) {
            repoComboBox.selectedIndex = 0
            selectedRepository = repos.first()
            onRepositoryChanged()
        } else {
            selectedRepository = null
            updateGuideline()
        }
    }

    fun selectRepository(repo: GitRepository) {
        for (i in 0 until repoComboBox.itemCount) {
            if (repoComboBox.getItemAt(i)?.root?.path == repo.root.path) {
                repoComboBox.selectedIndex = i
                return
            }
        }
    }

    private fun onRepositoryChanged() {
        val repo = selectedRepository
        versionFileComboBox.removeAllItems()
        sourceBranchComboBox.removeAllItems()
        detectedVersionField.text = ""
        if (repo == null) {
            updateGuideline()
            return
        }

        // 1. Populate Branches asynchronously on Dispatchers.IO to avoid blocking EDT
        cs.launch(Dispatchers.IO) {
            val branches = gitChangesManager.getLocalBranches(repo)
            val currentBranch = repo.currentBranchName
            val candidates = gitChangesManager.detectCandidateVersionFiles(repo)

            ApplicationManager.getApplication().invokeLater {
                if (selectedRepository != repo) return@invokeLater

                sourceBranchComboBox.removeAllItems()
                for (b in branches) {
                    sourceBranchComboBox.addItem(b)
                }
                if (currentBranch != null && branches.contains(currentBranch)) {
                    sourceBranchComboBox.selectedItem = currentBranch
                } else if (branches.isNotEmpty()) {
                    sourceBranchComboBox.selectedIndex = 0
                }

                versionFileComboBox.removeAllItems()
                for (file in candidates) {
                    val relPath = VfsUtil.getRelativePath(file, repo.root) ?: file.name
                    versionFileComboBox.addItem(relPath)
                }

                if (candidates.isNotEmpty()) {
                    versionFileComboBox.selectedIndex = 0
                    selectedVersionFile = candidates.first()
                    extractVersionFast(selectedVersionFile)
                } else {
                    selectedVersionFile = null
                    updateDefaultReleaseBranch()
                }

                updateGuideline()
            }
        }
    }

    private fun updateDefaultReleaseBranch() {
        val ver = detectedVersionField.text.trim()
        releaseBranchField.text = if (ver.isNotBlank()) "release/$ver" else "release"
    }

    private fun onVersionFileSelectionChanged() {
        val repo = selectedRepository ?: return
        val relPath = versionFileComboBox.selectedItem as? String ?: return
        val file = repo.root.findFileByRelativePath(relPath)
        selectedVersionFile = file
        if (file != null) {
            extractVersionFast(file)
        }
        updateGuideline()
    }

    private fun extractVersionFast(file: VirtualFile?) {
        if (file == null) return
        val v = VersionExtractor.extractVersionFast(file)
        if (!v.isNullOrBlank()) {
            detectedVersionField.text = v
            statusLabel.text = "Detected version $v from ${file.name}"
            statusLabel.foreground = JBColor(0x388E3C, 0x81C784)
        } else {
            statusLabel.text = "Could not parse version from ${file.name}. Enter manually or use AI Extract."
            statusLabel.foreground = JBColor.GRAY
        }
        updateDefaultReleaseBranch()
    }

    private fun extractVersionViaAi() {
        val file = selectedVersionFile
        if (file == null) {
            Messages.showWarningDialog(project, "Please select a version file first.", "No Version File Selected")
            return
        }

        statusLabel.text = "Querying AI for version string..."
        statusLabel.foreground = JBColor.BLUE
        detectVersionAiBtn.isEnabled = false

        cs.launch {
            val version = VersionExtractor.extractVersionWithAi(file, settingsService)
            ApplicationManager.getApplication().invokeLater {
                detectVersionAiBtn.isEnabled = true
                if (!version.isNullOrBlank()) {
                    detectedVersionField.text = version
                    statusLabel.text = "AI extracted version $version"
                    statusLabel.foreground = JBColor(0x388E3C, 0x81C784)
                    updateDefaultReleaseBranch()
                } else {
                    statusLabel.text = "AI could not detect version in ${file.name}."
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    private fun browseCustomVersionFile() {
        val repo = selectedRepository ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        descriptor.title = "Select Version File"
        descriptor.description =
            "Select the file containing the project or app version number (e.g., .toml, .gradle, .properties)"
        descriptor.setRoots(repo.root)

        FileChooser.chooseFile(descriptor, project, repo.root) { chosen ->
            val relPath = VfsUtil.getRelativePath(chosen, repo.root) ?: chosen.name
            var exists = false
            for (i in 0 until versionFileComboBox.itemCount) {
                if (versionFileComboBox.getItemAt(i) == relPath) {
                    versionFileComboBox.selectedIndex = i
                    exists = true
                    break
                }
            }
            if (!exists) {
                versionFileComboBox.addItem(relPath)
                versionFileComboBox.selectedItem = relPath
            }
            selectedVersionFile = chosen
            extractVersionFast(chosen)
            updateGuideline()
        }
    }

    fun updateGuideline() {
        val repo = selectedRepository
        if (repo == null) {
            guidelineLabel.text =
                "<html><b>Guideline:</b> Please select a target Git repository above. Release generation requires exactly one repository root.</html>"
            guidelineLabel.foreground = JBColor.RED
            resolveChangesBtn.isVisible = false
            generateBtn.isEnabled = false
            return
        }

        // Clean working tree check
        isCleanWorkingTree = gitChangesManager.isRepositoryClean(repo)
        if (!isCleanWorkingTree) {
            guidelineLabel.text =
                "<html><b>Warning:</b> Working tree in <code>${repo.root.name}</code> has uncommitted changes. All files must be committed or ignored before creating a release.</html>"
            guidelineLabel.foreground = JBColor.RED
            resolveChangesBtn.isVisible = true
            generateBtn.isEnabled = false
            return
        }

        resolveChangesBtn.isVisible = false
        val file = selectedVersionFile
        if (file == null) {
            guidelineLabel.text =
                "<html><b>Guideline:</b> Selected repository: <code>${repo.root.name}</code> (Clean). Please select or browse the <b>version file</b>.</html>"
            guidelineLabel.foreground = JBColor(0xE65100, 0xFFB74D) // Amber warning
            generateBtn.isEnabled = true
            return
        }

        guidelineLabel.text =
            "<html><b>Ready:</b> Working tree is clean. Ready to generate release report for <code>${repo.root.name}</code>.</html>"
        guidelineLabel.foreground = JBColor(0x2E7D32, 0x81C784) // Green
        generateBtn.isEnabled = true
    }

    private fun startReportGeneration() {
        val repo = selectedRepository
        if (repo == null) {
            Messages.showWarningDialog(project, "Please select exactly one Git repository root.", "Repository Required")
            return
        }

        if (!gitChangesManager.isRepositoryClean(repo)) {
            Messages.showWarningDialog(
                project,
                "Working tree has uncommitted changes. Please commit, stash, or ignore all changes first.",
                "Uncommitted Changes Detected"
            )
            updateGuideline()
            return
        }

        val version = detectedVersionField.text.trim()
        val sourceBranch = sourceBranchComboBox.selectedItem as? String ?: repo.currentBranchName ?: "HEAD"
        val releaseBranch = releaseBranchField.text.trim()

        if (releaseBranch.isBlank()) {
            Messages.showWarningDialog(project, "Please specify a release branch name.", "Release Branch Missing")
            return
        }

        // Check if release branch already exists before generating report
        if (gitChangesManager.branchOrTagExists(repo, releaseBranch)) {
            Messages.showWarningDialog(
                project,
                "The release branch '$releaseBranch' already exists in '${repo.root.name}'.\n\n" +
                        "Please update the version number in your version file (e.g. bump patch or minor version) " +
                        "or specify a unique release branch name before proceeding.",
                "Release Branch Already Exists"
            )
            return
        }

        statusLabel.text = "Resolving git commit differences between branches..."
        statusLabel.foreground = JBColor.BLUE
        generateBtn.isEnabled = false
        cancelGenBtn.isVisible = true
        confirmReleaseBtn.isVisible = false
        cancelReviewBtn.isVisible = false
        copyBtn.isEnabled = false
        reportTextArea.text = ""

        generationJob = cs.launch(Dispatchers.IO) {
            try {
                // Check if this is the first release (no previous release branch or marker detected)
                val isAuto = autoCompareBranchesCheckbox.isSelected
                if (isAuto && !gitChangesManager.hasPreviousRelease(repo, releaseBranch, sourceBranch)) {
                    val rootCommit = gitChangesManager.getRootCommit(repo)
                    ApplicationManager.getApplication().invokeLater {
                        autoCompareBranchesCheckbox.isSelected = false
                        revisionPanel.isVisible = true
                        if (!rootCommit.isNullOrBlank()) {
                            revisionField.text = rootCommit
                        }
                        statusLabel.text =
                            "No previous release detected. Please verify or paste the starting commit revision."
                        statusLabel.foreground = JBColor(0xE65100, 0xFFB74D) // Amber warning
                        generateBtn.isEnabled = true
                        cancelGenBtn.isVisible = false
                    }
                    return@launch
                }

                val commitLogs = resolveCommitLogs(repo, sourceBranch, releaseBranch)
                if (commitLogs.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        autoCompareBranchesCheckbox.isSelected = false
                        revisionPanel.isVisible = true
                        statusLabel.text =
                            "No commits found. Please paste revision number of branch where commit history starts."
                        statusLabel.foreground = JBColor.RED
                        generateBtn.isEnabled = true
                        cancelGenBtn.isVisible = false
                    }
                    return@launch
                }

                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Streaming release report from AI..."
                    statusLabel.foreground = JBColor.BLUE
                }

                val flow = ReleaseReportStrategy.generateReleaseReportStream(
                    settingsService = settingsService,
                    version = version,
                    commitLogs = commitLogs
                )

                val fullBuffer = StringBuilder()

                flow.collect { token ->
                    fullBuffer.append(token)
                    ApplicationManager.getApplication().invokeLater {
                        reportTextArea.append(token)
                        reportTextArea.caretPosition = reportTextArea.document.length
                    }
                }

                currentGeneratedReport = fullBuffer.toString()

                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Release report ready. Review message and confirm release."
                    statusLabel.foreground = JBColor(0x2E7D32, 0x81C784)
                    generateBtn.isVisible = false
                    cancelGenBtn.isVisible = false
                    confirmReleaseBtn.isVisible = true
                    cancelReviewBtn.isVisible = true
                    copyBtn.isEnabled = true
                }
            } catch (e: Exception) {
                LOG.warn("Release report generation failed: ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Generation error: ${e.message}"
                    statusLabel.foreground = JBColor.RED
                    generateBtn.isEnabled = true
                    cancelGenBtn.isVisible = false
                }
            }
        }
    }

    private fun resolveCommitLogs(repo: GitRepository, sourceBranch: String, releaseBranch: String): String {
        val isAuto = autoCompareBranchesCheckbox.isSelected
        if (isAuto) {
            // Find merge-base with release branch or parent/tag
            val existingBranches = gitChangesManager.getLocalBranches(repo)
            val baseCommit = if (existingBranches.contains(releaseBranch)) {
                gitChangesManager.getMergeBase(repo, releaseBranch, sourceBranch)
            } else {
                // If release branch doesn't exist yet, search previous release commit or parent
                val prevRelease = gitChangesManager.findReleaseCommitTimestamp(repo)
                prevRelease?.first
            }

            return if (!baseCommit.isNullOrBlank()) {
                gitChangesManager.getCommitLogsBetween(repo, baseCommit, sourceBranch)
            } else {
                gitChangesManager.getCommitLogsForRevision(repo, sourceBranch)
            }
        } else {
            val rev = revisionField.text.trim()
            return if (rev.isNotBlank()) {
                gitChangesManager.getCommitLogsBetween(repo, rev, sourceBranch, inclusiveFrom = true)
            } else {
                gitChangesManager.getCommitLogsForRevision(repo, sourceBranch)
            }
        }
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        statusLabel.text = "Generation cancelled."
        statusLabel.foreground = JBColor.GRAY
        generateBtn.isEnabled = true
        generateBtn.isVisible = true
        cancelGenBtn.isVisible = false
    }

    private fun resetReviewState() {
        generateBtn.isVisible = true
        generateBtn.isEnabled = true
        confirmReleaseBtn.isVisible = false
        cancelReviewBtn.isVisible = false
        cancelGenBtn.isVisible = false
        statusLabel.text = "Release review cancelled."
        statusLabel.foreground = JBColor.GRAY
    }

    private fun copyReportToClipboard() {
        val text = reportTextArea.text
        if (text.isNotBlank()) {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(text), null)
            copyBtn.text = "✓ Copied!"
            val resetTimer = Timer(2000) { _ ->
                copyBtn.text = "📋 Copy Report"
            }
            resetTimer.isRepeats = false
            resetTimer.start()
        }
    }

    private fun executeConfirmRelease() {
        val repo = selectedRepository ?: return
        val versionFile = selectedVersionFile
        val version = detectedVersionField.text.trim()
        val releaseBranch = releaseBranchField.text.trim().ifBlank { "release/$version" }
        val sourceBranch = sourceBranchComboBox.selectedItem as? String ?: repo.currentBranchName ?: "HEAD"
        val report = reportTextArea.text.trim()

        val commitTitle =
            if (version.isNotBlank()) "chore(release): prepare release v$version" else "chore(release): release report"
        val fullCommitMessage = "$commitTitle\n\n$report"

        val confirmMsg =
            "Create and switch to branch '$releaseBranch' from '$sourceBranch' and commit release report?\n\nTarget File: ${versionFile?.name ?: "Release Report"}"
        val proceed = Messages.showYesNoDialog(
            project,
            confirmMsg,
            "Confirm Release Branch & Commit",
            "Confirm Release",
            "Cancel",
            Messages.getQuestionIcon()
        )
        if (proceed != Messages.YES) return

        if (gitChangesManager.branchOrTagExists(repo, releaseBranch)) {
            Messages.showWarningDialog(
                project,
                "The release branch '$releaseBranch' already exists in '${repo.root.name}'.\n\n" +
                        "Please update the version number or specify a different branch name before proceeding.",
                "Release Branch Already Exists"
            )
            return
        }

        cs.launch(Dispatchers.IO) {
            try {
                // 1. Create and checkout the release branch from source branch
                val branchCreated = gitChangesManager.createAndCheckoutBranch(repo, releaseBranch, sourceBranch)
                if (!branchCreated) {
                    throw RuntimeException("Failed to create or checkout branch '$releaseBranch'")
                }

                // 2. Stage version file if present
                if (versionFile != null) {
                    val relPath = VfsUtil.getRelativePath(versionFile, repo.root) ?: versionFile.name
                    val addHandler = GitLineHandler(project, repo.root, GitCommand.ADD).apply {
                        addParameters("--", relPath)
                    }
                    Git.getInstance().runCommand(addHandler)
                }

                // 3. Commit release message
                val commitHandler = GitLineHandler(project, repo.root, GitCommand.COMMIT).apply {
                    addParameters("-m", fullCommitMessage, "--allow-empty")
                    if (versionFile != null) {
                        val relPath = VfsUtil.getRelativePath(versionFile, repo.root) ?: versionFile.name
                        addParameters("--", relPath)
                    }
                }
                val commitRes = Git.getInstance().runCommand(commitHandler)
                if (!commitRes.success()) {
                    throw RuntimeException("Commit failed: ${commitRes.errorOutputAsJoinedString}")
                }

                repo.update()
                if (versionFile != null) {
                    VfsUtil.markDirtyAndRefresh(true, true, true, versionFile, repo.root)
                }
                VcsDirtyScopeManager.getInstance(project).markEverythingDirty()

                ApplicationManager.getApplication().invokeLater {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Local AI Commit")
                        .createNotification(
                            "Release Branch Created & Committed",
                            "Release branch '$releaseBranch' created and committed successfully.",
                            NotificationType.INFORMATION
                        )
                        .notify(project)

                    resetReviewState()
                    onReleaseCommitted()
                }
            } catch (e: Exception) {
                LOG.error("Failed to execute release: ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Release execution failed: ${e.message}", "Release Error")
                }
            }
        }
    }
}
