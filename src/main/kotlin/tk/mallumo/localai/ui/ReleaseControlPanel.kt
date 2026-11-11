package tk.mallumo.localai.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tk.mallumo.localai.buildsystem.BuildSystemDetector
import tk.mallumo.localai.buildsystem.BuildSystemType
import tk.mallumo.localai.buildsystem.DetectedModule
import tk.mallumo.localai.buildsystem.ProjectBuildStructure
import tk.mallumo.localai.git.GitChangesManager
import tk.mallumo.localai.llm.MultiModuleVersionAnalyzer
import tk.mallumo.localai.llm.ReleaseReportStrategy
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
    private var repoUpdateJob: Job? = null
    private var currentGeneratedReport: String = ""

    // Repository memory for branches
    private val repoSourceBranches = mutableMapOf<String, String>()
    private val repoReleaseBranches = mutableMapOf<String, String>()

    // Build system & modules state
    private var currentBuildStructure: ProjectBuildStructure? = null
    private var currentDetectedModules = listOf<DetectedModule>()

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
    private val scanModulesBtn = JButton("AI Scan Modules", AllIcons.Actions.RefactoringBulb)

    // Branch controls
    private val sourceBranchComboBox = ComboBox<String>()
    private val releaseBranchField = JBTextField("release", 14)
    private val branchStatusLabel = JBLabel("Ready").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.GRAY
    }
    private val pushToRemoteBox = JBCheckBox("Push release branch to origin", true)

    // Modules display
    private val modulesSummaryLabel = JBLabel("Scanning build system...").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = JBColor.GRAY
    }
    private val modulesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

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

    private val generateBtn = JButton("Generate Release Report").apply {
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
    private val confirmReleaseBtn = JButton("Confirm Release", AllIcons.Actions.Checked).apply {
        font = font.deriveFont(Font.BOLD, 13f)
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        putClientProperty("JButton.styleDefault", true)
        putClientProperty("ActionToolbar.accentButton", true)
    }
    private val regenerateBtn = JButton("Regenerate", AllIcons.Actions.Refresh)
    private val cancelPreviewBtn = JButton("Cancel", AllIcons.Actions.Cancel)
    private val copyBtn = JButton("Copy Report", AllIcons.Actions.Copy)

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

        // Row 1: Source Branch & Release Branch
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Source Branch:"), gbc)

        sourceBranchComboBox.addActionListener {
            updateBranchStatus()
        }
        gbc.gridx = 1; gbc.weightx = 0.5
        configPanel.add(sourceBranchComboBox, gbc)

        val releaseBranchPanel = JPanel(BorderLayout(6, 0))
        releaseBranchPanel.add(JBLabel("Release Branch:"), BorderLayout.WEST)
        releaseBranchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                updateBranchStatus()
            }

            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                updateBranchStatus()
            }

            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                updateBranchStatus()
            }
        })
        releaseBranchPanel.add(releaseBranchField, BorderLayout.CENTER)
        gbc.gridx = 2; gbc.weightx = 0.5
        configPanel.add(releaseBranchPanel, gbc)

        // Row 2: Branch shipping mode status & Push checkbox
        row++
        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        val branchMetaPanel = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        branchMetaPanel.add(branchStatusLabel, BorderLayout.CENTER)
        branchMetaPanel.add(pushToRemoteBox, BorderLayout.EAST)
        configPanel.add(branchMetaPanel, gbc)
        gbc.gridwidth = 1

        // Row 3: Release Version & Multi-Module Detection
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Release Version:"), gbc)

        val versionRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        versionRow.add(detectedVersionField)
        scanModulesBtn.toolTipText = "Scan build tree, catalogs (libs.versions.toml), and Amper templates using AI"
        scanModulesBtn.addActionListener { scanModulesViaAi() }
        versionRow.add(scanModulesBtn)
        gbc.gridx = 1; gbc.weightx = 0.6
        configPanel.add(versionRow, gbc)

        gbc.gridx = 2; gbc.weightx = 0.4
        configPanel.add(modulesSummaryLabel, gbc)

        // Row 4: Modules Details Table Panel
        row++
        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0
        val modulesContainer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(4, 8)
            )
            background = JBColor.namedColor("Panel.background", JBColor.PanelBackground)
            add(modulesPanel, BorderLayout.CENTER)
        }
        configPanel.add(modulesContainer, gbc)
        gbc.gridwidth = 1

        // Row 5: Commit Diff Range
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
        repoComboBox.model = DefaultComboBoxModel(repos.toTypedArray())
        if (repos.isNotEmpty()) {
            repoComboBox.selectedIndex = 0
            selectedRepository = repos.first()
            onRepositoryChanged()
        } else {
            selectedRepository = null
            onRepositoryChanged()
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
        repoUpdateJob?.cancel()
        val repo = selectedRepository
        detectedVersionField.text = ""
        modulesPanel.removeAll()
        modulesSummaryLabel.text = "Scanning build system..."
        if (repo == null) {
            sourceBranchComboBox.model = DefaultComboBoxModel(emptyArray<String>())
            updateGuideline()
            return
        }

        val rememberedSource = repoSourceBranches[repo.root.path]
        val rememberedRelease = repoReleaseBranches[repo.root.path] ?: "release"
        releaseBranchField.text = rememberedRelease

        // Populate Branches & Candidates asynchronously on Dispatchers.IO to avoid blocking EDT
        repoUpdateJob = cs.launch(Dispatchers.IO) {
            val branches = gitChangesManager.getLocalBranches(repo)
            val currentBranch = repo.currentBranchName
            val buildStructure = BuildSystemDetector.detect(repo, gitChangesManager)
            currentBuildStructure = buildStructure
            currentDetectedModules = buildStructure.modules

            ApplicationManager.getApplication().invokeLater {
                if (selectedRepository != repo) return@invokeLater

                sourceBranchComboBox.model = DefaultComboBoxModel(branches.toTypedArray())
                if (rememberedSource != null && branches.contains(rememberedSource)) {
                    sourceBranchComboBox.selectedItem = rememberedSource
                } else if (currentBranch != null && branches.contains(currentBranch)) {
                    sourceBranchComboBox.selectedItem = currentBranch
                } else if (branches.isNotEmpty()) {
                    sourceBranchComboBox.selectedIndex = 0
                }

                val primaryVer = buildStructure.modules.firstOrNull { !it.version.isNullOrBlank() }?.version ?: ""
                detectedVersionField.text = primaryVer

                renderModulesPanel(buildStructure.modules, buildStructure.primaryBuildSystem)
                updateBranchStatus()
                updateGuideline()
            }
        }
    }

    private fun updateBranchStatus() {
        val repo = selectedRepository ?: return
        val source = (sourceBranchComboBox.selectedItem as? String)?.trim() ?: repo.currentBranchName ?: "main"
        val target = releaseBranchField.text.trim().ifBlank { "release" }

        repoSourceBranches[repo.root.path] = source
        repoReleaseBranches[repo.root.path] = target

        val exists = gitChangesManager.branchOrTagExists(repo, target)
        if (exists) {
            branchStatusLabel.text = "Existing '$target' branch will be updated from '$source' (merge commit topology)"
            branchStatusLabel.foreground = JBColor(0x2E7D32, 0x81C784)
        } else {
            branchStatusLabel.text = "Initial '$target' branch will be created from '$source'"
            branchStatusLabel.foreground = JBColor(0x1976D2, 0x64B5F6)
        }
    }

    private fun renderModulesPanel(modules: List<DetectedModule>, system: BuildSystemType) {
        modulesPanel.removeAll()
        if (modules.isEmpty()) {
            modulesSummaryLabel.text = "Build System: ${system.displayName} (Single project / no submodules)"
        } else {
            val changedCount = modules.count { it.isChangedInReleaseRange }
            val modifiedText = if (changedCount > 0) " ($changedCount modified in this release)" else ""
            modulesSummaryLabel.text = "Build System: ${system.displayName} | ${modules.size} module(s)$modifiedText"

            for (mod in modules) {
                val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { isOpaque = false }
                val nameLabel = JBLabel("${mod.name}:").apply { font = font.deriveFont(Font.BOLD, 11f) }
                val verLabel = JBLabel(mod.version ?: "unspecified").apply { font = font.deriveFont(Font.PLAIN, 11f) }
                row.add(nameLabel)
                row.add(verLabel)
                if (mod.versionReference != null) {
                    val refLabel = JBLabel("(${mod.versionReference})").apply {
                        font = font.deriveFont(Font.ITALIC, 10f)
                        foreground = JBColor.GRAY
                    }
                    row.add(refLabel)
                }
                if (mod.isChangedInReleaseRange) {
                    val badge = JBLabel("MODIFIED").apply {
                        font = font.deriveFont(Font.BOLD, 9f)
                        foreground = JBColor(0x2E7D32, 0x81C784)
                    }
                    row.add(badge)
                }
                modulesPanel.add(row)
            }
        }
        modulesPanel.revalidate()
        modulesPanel.repaint()
    }

    private fun scanModulesViaAi() {
        val repo = selectedRepository ?: return
        val build = currentBuildStructure ?: return
        scanModulesBtn.isEnabled = false
        formStatusLabel.text = "AI scanning project tree, version catalogs, and templates..."
        formStatusLabel.foreground = JBColor.BLUE

        cs.launch(Dispatchers.IO) {
            val result = MultiModuleVersionAnalyzer.analyzeVersions(
                repository = repo,
                buildStructure = build,
                settingsService = settingsService
            )

            ApplicationManager.getApplication().invokeLater {
                scanModulesBtn.isEnabled = true
                if (!result.primaryVersion.isNullOrBlank()) {
                    detectedVersionField.text = result.primaryVersion
                }
                currentDetectedModules = result.modules
                renderModulesPanel(result.modules, build.primaryBuildSystem)
                formStatusLabel.text = "AI module scan completed."
                formStatusLabel.foreground = JBColor(0x2E7D32, 0x81C784)
            }
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
        val target = releaseBranchField.text.trim().ifBlank { "release" }
        guidelineLabel.text =
            "<html><b>Ready:</b> Working tree is clean. Ready to generate release report for <code>${repo.root.name}</code> to branch <code>$target</code>.</html>"
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
        val sourceBranch = (sourceBranchComboBox.selectedItem as? String)?.trim()
            ?: repo.currentBranchName?.trim()
            ?: ""

        if (sourceBranch.isBlank()) {
            Messages.showWarningDialog(
                project,
                "No source branch selected or available in repository.",
                "Branch Required"
            )
            return
        }
        val releaseBranch = releaseBranchField.text.trim().ifBlank { "release" }

        // Switch to STREAM card immediately
        val consoleStreamer = ThinkingConsoleStreamer(streamConsole)
        consoleStreamer.reset()
        streamProgressLabel.text = "Checking release configuration..."
        cardLayout.show(cardContainer, "STREAM")

        generationJob = cs.launch(Dispatchers.IO) {
            try {
                // Detect changed files between target and source to identify which modules are modified
                val changedFiles = if (gitChangesManager.branchOrTagExists(repo, releaseBranch)) {
                    val baseCommit = gitChangesManager.getMergeBase(repo, releaseBranch, sourceBranch)
                    if (!baseCommit.isNullOrBlank()) {
                        gitChangesManager.getChangedFilesBetween(repo, baseCommit, sourceBranch).toSet()
                    } else {
                        emptySet()
                    }
                } else {
                    emptySet()
                }

                // Update modules with changedFilesInRange if available
                if (changedFiles.isNotEmpty() && currentBuildStructure != null) {
                    val updatedBuild = BuildSystemDetector.detect(repo, gitChangesManager, changedFiles)
                    currentBuildStructure = updatedBuild
                    currentDetectedModules = updatedBuild.modules
                    ApplicationManager.getApplication().invokeLater {
                        renderModulesPanel(updatedBuild.modules, updatedBuild.primaryBuildSystem)
                    }
                }

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

                val moduleInfo = if (currentDetectedModules.isNotEmpty()) {
                    buildString {
                        appendLine("Detected Modules & Versions:")
                        for (mod in currentDetectedModules) {
                            val modStatus = if (mod.isChangedInReleaseRange) " (MODIFIED in this release)" else ""
                            appendLine("- Module: ${mod.name}, Version: ${mod.version ?: "unknown"}, Build: ${mod.buildSystem.displayName}$modStatus")
                        }
                    }
                } else ""

                ApplicationManager.getApplication().invokeLater {
                    streamProgressLabel.text = "Streaming release report from AI..."
                }

                val (finalReport, _) = ReleaseReportStrategy.generateReleaseReport(
                    settingsService = settingsService,
                    version = version,
                    commitLogs = commitLogs,
                    moduleInfo = moduleInfo,
                    onToken = { token ->
                        ApplicationManager.getApplication().invokeLater {
                            consoleStreamer.appendToken(token)
                        }
                    }
                )

                currentGeneratedReport = finalReport

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
            copyBtn.text = "Copied!"
            val resetTimer = Timer(2000) { _ ->
                copyBtn.text = "Copy Report"
            }
            resetTimer.isRepeats = false
            resetTimer.start()
        }
    }

    private fun executeConfirmRelease() {
        val repo = selectedRepository
        if (repo == null) {
            Messages.showWarningDialog(project, "Please select a Git repository.", "Repository Required")
            return
        }
        val sourceBranch = (sourceBranchComboBox.selectedItem as? String)?.trim()
            ?: repo.currentBranchName?.trim()
            ?: ""
        if (sourceBranch.isBlank()) {
            Messages.showWarningDialog(
                project,
                "No source branch selected or available in repository.",
                "Branch Required"
            )
            return
        }
        val version = detectedVersionField.text.trim()
        val releaseBranch = releaseBranchField.text.trim().ifBlank { "release" }
        val report = previewEditor.text.trim()
        val pushToRemote = pushToRemoteBox.isSelected

        val commitTitle =
            if (version.isNotBlank()) "chore(release): prepare release v$version" else "chore(release): release report"
        val fullCommitMessage = "$commitTitle\n\n$report"

        val targetExists = gitChangesManager.branchOrTagExists(repo, releaseBranch)
        val actionDesc =
            if (targetExists) "update branch '$releaseBranch' (merge commit topology)" else "create branch '$releaseBranch'"
        val pushDesc = if (pushToRemote) " and push to origin" else ""

        val confirmMsg =
            "Ready to $actionDesc from '$sourceBranch'$pushDesc for repository '${repo.root.name}'?\n\nWorking tree will remain intact on current branch."
        val proceed = Messages.showYesNoDialog(
            project,
            confirmMsg,
            "Confirm Release Branch & Commit",
            "Confirm Release",
            "Cancel",
            Messages.getQuestionIcon()
        )
        if (proceed != Messages.YES) return

        cs.launch(Dispatchers.IO) {
            try {
                val (success, message) = gitChangesManager.shipRelease(
                    repository = repo,
                    sourceBranch = sourceBranch,
                    targetBranch = releaseBranch,
                    message = fullCommitMessage,
                    pushToRemote = pushToRemote
                )

                if (!success) {
                    throw RuntimeException(message)
                }

                ApplicationManager.getApplication().invokeLater {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Local AI Commit")
                        .createNotification(
                            "Release Shipped",
                            message,
                            NotificationType.INFORMATION
                        )
                        .notify(project)

                    cardLayout.show(cardContainer, "FORM")
                    formStatusLabel.text = message
                    formStatusLabel.foreground = JBColor(0x2E7D32, 0x81C784)
                    updateBranchStatus()
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
