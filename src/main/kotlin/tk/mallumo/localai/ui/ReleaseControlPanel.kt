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
import tk.mallumo.localai.llm.KoogLlmClient
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

    // Card Layout container
    private val cardLayout = CardLayout()
    private val cardContainer = JPanel(cardLayout)

    // --- CARD 1: FORM (Configuration & Initial State) ---
    private val formCard = JPanel(BorderLayout())

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

    // 2. Form Inputs
    private val configPanel = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(12)
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

    private val formStatusLabel = JBLabel("Ready", SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.GRAY
    }

    private val generateBtn = JButton("⚡ Generate Release Report").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        putClientProperty("JButton.styleDefault", true)
        putClientProperty("ActionToolbar.accentButton", true)
    }

    // --- CARD 2: STREAM (Live generation console) ---
    private val streamCard = JPanel(BorderLayout())
    private val streamProgressLabel = JBLabel("Generating release report...", SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.BOLD, 12f)
    }
    private val streamConsole = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = true
    }
    private val cancelGenBtn = JButton("Cancel Generation", AllIcons.Actions.Cancel)

    // --- CARD 3: PREVIEW (Review & Confirm) ---
    private val previewCard = JPanel(BorderLayout())
    private val previewEditor = JBTextArea().apply {
        isEditable = true
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val confirmReleaseBtn = JButton("✓ Confirm Release", AllIcons.Actions.Checked).apply {
        font = font.deriveFont(Font.BOLD, 13f)
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        putClientProperty("JButton.styleDefault", true)
        putClientProperty("ActionToolbar.accentButton", true)
    }
    private val regenerateBtn = JButton("Regenerate", AllIcons.Actions.Refresh)
    private val cancelPreviewBtn = JButton("Cancel", AllIcons.Actions.Cancel)
    private val copyBtn = JButton("📋 Copy Report", AllIcons.Actions.Copy)

    init {
        border = JBUI.Borders.empty(6)
        buildFormCard()
        buildStreamCard()
        buildPreviewCard()

        cardContainer.add(formCard, "FORM")
        cardContainer.add(streamCard, "STREAM")
        cardContainer.add(previewCard, "PREVIEW")

        add(cardContainer, BorderLayout.CENTER)
        cardLayout.show(cardContainer, "FORM")

        reloadRepositories()
        updateGuideline()
    }

    private fun buildFormCard() {
        // Guideline Banner
        val guideRightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        guideRightPanel.add(resolveChangesBtn)
        guidelinePanel.add(guidelineLabel, BorderLayout.CENTER)
        guidelinePanel.add(guideRightPanel, BorderLayout.EAST)
        formCard.add(guidelinePanel, BorderLayout.NORTH)

        // Config Grid
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(5, 4)
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

        // Row 1: Version File & Version
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

        // Row 3: Commit Diff Mode
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Commit Range:"), gbc)

        val rangeConfigPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        rangeConfigPanel.add(autoCompareBranchesCheckbox)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2
        configPanel.add(rangeConfigPanel, gbc)
        gbc.gridwidth = 1

        // Row 4: Revision input panel (for manual mode or fallback)
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

        // Center Content wrapper
        val centerBox = JPanel(BorderLayout()).apply {
            add(configPanel, BorderLayout.NORTH)
        }
        formCard.add(JBScrollPane(centerBox).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

        // Form Bottom Action Bar
        val bottomBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(10, 12)
            )
        }
        bottomBar.add(formStatusLabel, BorderLayout.WEST)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        generateBtn.addActionListener { startReportGeneration() }
        btnPanel.add(generateBtn)
        bottomBar.add(btnPanel, BorderLayout.EAST)

        formCard.add(bottomBar, BorderLayout.SOUTH)
    }

    private fun buildStreamCard() {
        val topBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(8, 12)
            )
            add(streamProgressLabel, BorderLayout.CENTER)
            cancelGenBtn.addActionListener { cancelGeneration() }
            add(cancelGenBtn, BorderLayout.EAST)
        }

        streamCard.add(topBar, BorderLayout.NORTH)
        streamCard.add(JBScrollPane(streamConsole).apply {
            border = JBUI.Borders.empty(8)
        }, BorderLayout.CENTER)
    }

    private fun buildPreviewCard() {
        val topBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(8, 12)
            )
            val title = JBLabel("Generated Release Report (Review and edit before confirming):").apply {
                font = font.deriveFont(Font.BOLD, 12f)
            }
            add(title, BorderLayout.CENTER)
        }

        previewCard.add(topBar, BorderLayout.NORTH)
        previewCard.add(JBScrollPane(previewEditor).apply {
            border = JBUI.Borders.empty(8)
        }, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 8)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(4, 8)
            )
        }

        confirmReleaseBtn.addActionListener { executeConfirmRelease() }
        regenerateBtn.addActionListener { startReportGeneration() }
        cancelPreviewBtn.addActionListener {
            cardLayout.show(cardContainer, "FORM")
        }
        copyBtn.addActionListener { copyReportToClipboard() }

        bottomBar.add(copyBtn)
        bottomBar.add(cancelPreviewBtn)
        bottomBar.add(regenerateBtn)
        bottomBar.add(confirmReleaseBtn)

        previewCard.add(bottomBar, BorderLayout.SOUTH)
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

        // Populate Branches & Candidates asynchronously on Dispatchers.IO to avoid blocking EDT
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
            formStatusLabel.text = "Detected version $v from ${file.name}"
            formStatusLabel.foreground = JBColor(0x388E3C, 0x81C784)
        } else {
            formStatusLabel.text = "Could not parse version from ${file.name}. Enter manually or use AI Extract."
            formStatusLabel.foreground = JBColor.GRAY
        }
        updateDefaultReleaseBranch()
    }

    private fun extractVersionViaAi() {
        val file = selectedVersionFile
        if (file == null) {
            Messages.showWarningDialog(project, "Please select a version file first.", "No Version File Selected")
            return
        }

        formStatusLabel.text = "Querying AI for version string..."
        formStatusLabel.foreground = JBColor.BLUE
        detectVersionAiBtn.isEnabled = false

        cs.launch {
            val version = VersionExtractor.extractVersionWithAi(file, settingsService)
            ApplicationManager.getApplication().invokeLater {
                detectVersionAiBtn.isEnabled = true
                if (!version.isNullOrBlank()) {
                    detectedVersionField.text = version
                    formStatusLabel.text = "AI extracted version $version"
                    formStatusLabel.foreground = JBColor(0x388E3C, 0x81C784)
                    updateDefaultReleaseBranch()
                } else {
                    formStatusLabel.text = "AI could not detect version in ${file.name}."
                    formStatusLabel.foreground = JBColor.RED
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

        // Switch to STREAM card immediately
        val consoleStreamer = ThinkingConsoleStreamer(streamConsole)
        consoleStreamer.reset()
        streamProgressLabel.text = "Resolving git commit differences between branches..."
        cardLayout.show(cardContainer, "STREAM")

        generationJob = cs.launch(Dispatchers.IO) {
            try {
                val isAuto = autoCompareBranchesCheckbox.isSelected
                if (isAuto && !gitChangesManager.hasPreviousRelease(repo, releaseBranch, sourceBranch)) {
                    val rootCommit = gitChangesManager.getRootCommit(repo)
                    ApplicationManager.getApplication().invokeLater {
                        autoCompareBranchesCheckbox.isSelected = false
                        revisionPanel.isVisible = true
                        if (!rootCommit.isNullOrBlank()) {
                            revisionField.text = rootCommit
                        }
                        formStatusLabel.text =
                            "No previous release detected. Please verify or paste the starting commit revision."
                        formStatusLabel.foreground = JBColor(0xE65100, 0xFFB74D) // Amber warning
                        cardLayout.show(cardContainer, "FORM")
                    }
                    return@launch
                }

                val commitLogs = resolveCommitLogs(repo, sourceBranch, releaseBranch)
                if (commitLogs.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        autoCompareBranchesCheckbox.isSelected = false
                        revisionPanel.isVisible = true
                        formStatusLabel.text =
                            "No commits found. Please paste revision number of branch where commit history starts."
                        formStatusLabel.foreground = JBColor.RED
                        cardLayout.show(cardContainer, "FORM")
                    }
                    return@launch
                }

                ApplicationManager.getApplication().invokeLater {
                    streamProgressLabel.text = "Streaming release report from AI..."
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
                        consoleStreamer.appendToken(token)
                    }
                }

                currentGeneratedReport = KoogLlmClient.sanitizeOutput(fullBuffer.toString())

                ApplicationManager.getApplication().invokeLater {
                    previewEditor.text = currentGeneratedReport
                    previewEditor.caretPosition = 0
                    cardLayout.show(cardContainer, "PREVIEW")
                }
            } catch (e: Exception) {
                LOG.warn("Release report generation failed: ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    formStatusLabel.text = "Generation error: ${e.message}"
                    formStatusLabel.foreground = JBColor.RED
                    cardLayout.show(cardContainer, "FORM")
                }
            }
        }
    }

    private fun resolveCommitLogs(repo: GitRepository, sourceBranch: String, releaseBranch: String): String {
        val isAuto = autoCompareBranchesCheckbox.isSelected
        if (isAuto) {
            val existingBranches = gitChangesManager.getLocalBranches(repo)
            val baseCommit = if (existingBranches.contains(releaseBranch)) {
                gitChangesManager.getMergeBase(repo, releaseBranch, sourceBranch)
            } else {
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
        formStatusLabel.text = "Generation cancelled."
        formStatusLabel.foreground = JBColor.GRAY
        cardLayout.show(cardContainer, "FORM")
    }

    private fun copyReportToClipboard() {
        val text = previewEditor.text
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
        val report = previewEditor.text.trim()

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

                    cardLayout.show(cardContainer, "FORM")
                    formStatusLabel.text = "Release '$releaseBranch' created and committed successfully."
                    formStatusLabel.foreground = JBColor(0x2E7D32, 0x81C784)
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
