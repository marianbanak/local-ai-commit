package tk.mallumo.localai.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tk.mallumo.localai.git.GitChangesManager
import tk.mallumo.localai.llm.UserMessageFormatter
import tk.mallumo.localai.llm.UserMessageStrategy
import tk.mallumo.localai.settings.LocalAiCommitSettingsService
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*

class UserMessageControlPanel(
    private val project: Project,
    private val gitChangesManager: GitChangesManager,
    private val cs: CoroutineScope
) : JPanel(BorderLayout()) {

    private val LOG = Logger.getInstance(UserMessageControlPanel::class.java)
    private val settingsService = LocalAiCommitSettingsService.getInstance(project)

    // Current State
    private var selectedRepository: GitRepository? = null
    private var generationJob: Job? = null

    private var currentMarkdown: String = ""
    private var currentHtml: String = ""
    private var currentPlainText: String = ""

    // Card Layout
    private val cardLayout = CardLayout()
    private val cardContainer = JPanel(cardLayout)

    // --- CARD 1: FORM ---
    private val formCard = JPanel(BorderLayout())
    private val guidelinePanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 12)
        )
        background = JBColor.namedColor("Panel.background", JBColor.PanelBackground)
    }
    private val guidelineLabel = JBLabel().apply {
        font = font.deriveFont(Font.PLAIN, 12f)
        text =
            "<html><b>End-User Announcement:</b> Generate a friendly, non-technical 'What's New' message for regular users based on the selected branch (no commits, no push).</html>"
        foreground = JBColor(0x2E7D32, 0x81C784)
    }

    private val configPanel = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(12)
    }
    private val repoComboBox = ComboBox<GitRepository>()
    private val branchComboBox = ComboBox<String>()
    private val formStatusLabel = JBLabel("Ready", SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.GRAY
    }
    private val generateBtn = JButton("Generate User Message", AllIcons.Actions.RefactoringBulb).apply {
        font = font.deriveFont(Font.BOLD, 13f)
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        putClientProperty("JButton.styleDefault", true)
        putClientProperty("ActionToolbar.accentButton", true)
    }

    // --- CARD 2: STREAM ---
    private val streamCard = JPanel(BorderLayout())
    private val streamProgressLabel = JBLabel("Generating user message...", SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.BOLD, 12f)
    }
    private val streamConsole = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = true
    }
    private val cancelGenBtn = JButton("Cancel Generation", AllIcons.Actions.Cancel)

    // --- CARD 3: PREVIEW ---
    private val previewCard = JPanel(BorderLayout())
    private val previewEditor = JBTextArea().apply {
        isEditable = true
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    private val markdownRadio = JBRadioButton("Markdown", true)
    private val plainTextRadio = JBRadioButton("Plain Text")
    private val htmlRadio = JBRadioButton("HTML Source")
    private val formatButtonGroup = ButtonGroup().apply {
        add(markdownRadio)
        add(plainTextRadio)
        add(htmlRadio)
    }

    private val copyCurrentBtn = JButton("Copy Current Format", AllIcons.Actions.Copy)
    private val copyMarkdownBtn = JButton("Copy Markdown", AllIcons.Actions.Copy)
    private val copyPlainTextBtn = JButton("Copy Plain Text", AllIcons.Actions.Copy)
    private val copyHtmlBtn = JButton("Copy HTML", AllIcons.Actions.Copy)
    private val regenerateBtn = JButton("Regenerate", AllIcons.Actions.Refresh)
    private val backToFormBtn = JButton("Back", AllIcons.Actions.Back)

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
    }

    private fun buildFormCard() {
        guidelinePanel.add(guidelineLabel, BorderLayout.CENTER)
        formCard.add(guidelinePanel, BorderLayout.NORTH)

        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(6, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Git Repository:"), gbc)

        gbc.gridx = 1; gbc.weightx = 1.0
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

        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        configPanel.add(JBLabel("Source Branch:"), gbc)

        gbc.gridx = 1; gbc.weightx = 1.0
        configPanel.add(branchComboBox, gbc)

        val centerBox = JPanel(BorderLayout()).apply {
            add(configPanel, BorderLayout.NORTH)
        }
        formCard.add(JBScrollPane(centerBox).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)

        val bottomBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(10, 12)
            )
        }
        bottomBar.add(formStatusLabel, BorderLayout.WEST)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        generateBtn.addActionListener { startGeneration() }
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
                JBUI.Borders.empty(6, 12)
            )
        }

        val title = JBLabel("End-User Announcement:").apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }
        topBar.add(title, BorderLayout.WEST)

        val formatPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        formatPanel.add(JBLabel("Format:"))
        formatPanel.add(markdownRadio)
        formatPanel.add(plainTextRadio)
        formatPanel.add(htmlRadio)

        markdownRadio.addActionListener { updatePreviewText() }
        plainTextRadio.addActionListener { updatePreviewText() }
        htmlRadio.addActionListener { updatePreviewText() }

        topBar.add(formatPanel, BorderLayout.EAST)
        previewCard.add(topBar, BorderLayout.NORTH)

        previewCard.add(JBScrollPane(previewEditor).apply {
            border = JBUI.Borders.empty(8)
        }, BorderLayout.CENTER)

        val bottomBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 6)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(4, 8)
            )
        }

        copyCurrentBtn.addActionListener { copyToClipboard(previewEditor.text, copyCurrentBtn, "Copy Current Format") }
        copyMarkdownBtn.addActionListener { copyToClipboard(currentMarkdown, copyMarkdownBtn, "Copy Markdown") }
        copyPlainTextBtn.addActionListener { copyToClipboard(currentPlainText, copyPlainTextBtn, "Copy Plain Text") }
        copyHtmlBtn.addActionListener { copyToClipboard(currentHtml, copyHtmlBtn, "Copy HTML") }

        regenerateBtn.addActionListener { startGeneration() }
        backToFormBtn.addActionListener { cardLayout.show(cardContainer, "FORM") }

        bottomBar.add(copyMarkdownBtn)
        bottomBar.add(copyPlainTextBtn)
        bottomBar.add(copyHtmlBtn)
        bottomBar.add(copyCurrentBtn)
        bottomBar.add(regenerateBtn)
        bottomBar.add(backToFormBtn)

        previewCard.add(bottomBar, BorderLayout.SOUTH)
    }

    private fun updatePreviewText() {
        previewEditor.text = when {
            markdownRadio.isSelected -> currentMarkdown
            plainTextRadio.isSelected -> currentPlainText
            htmlRadio.isSelected -> currentHtml
            else -> currentMarkdown
        }
        previewEditor.caretPosition = 0
    }

    private fun copyToClipboard(content: String, btn: JButton, originalLabel: String) {
        if (content.isNotBlank()) {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(content), null)
            btn.text = "Copied!"
            val timer = Timer(2000) { _ ->
                btn.text = originalLabel
            }
            timer.isRepeats = false
            timer.start()
        }
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
            branchComboBox.removeAllItems()
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
        val repo = selectedRepository ?: return
        branchComboBox.removeAllItems()

        cs.launch(Dispatchers.IO) {
            val branches = gitChangesManager.getLocalBranches(repo)
            val latestRelease = gitChangesManager.getLatestReleaseBranch(repo)
            val currentBranch = repo.currentBranchName

            ApplicationManager.getApplication().invokeLater {
                if (selectedRepository != repo) return@invokeLater

                branchComboBox.removeAllItems()
                for (b in branches) {
                    branchComboBox.addItem(b)
                }

                // Default to last release branch, otherwise current branch, otherwise first
                if (latestRelease != null && branches.contains(latestRelease)) {
                    branchComboBox.selectedItem = latestRelease
                    formStatusLabel.text = "Auto-selected latest release branch: $latestRelease"
                } else if (currentBranch != null && branches.contains(currentBranch)) {
                    branchComboBox.selectedItem = currentBranch
                    formStatusLabel.text = "Selected current branch: $currentBranch"
                } else if (branches.isNotEmpty()) {
                    branchComboBox.selectedIndex = 0
                    formStatusLabel.text = "Ready"
                } else {
                    formStatusLabel.text = "No branches found"
                }
            }
        }
    }

    private fun startGeneration() {
        val repo = selectedRepository
        if (repo == null) {
            Messages.showWarningDialog(project, "Please select a Git repository.", "Repository Required")
            return
        }

        val branch = branchComboBox.selectedItem as? String
        if (branch.isNullOrBlank()) {
            Messages.showWarningDialog(
                project,
                "Please select a branch to extract release information from.",
                "Branch Required"
            )
            return
        }

        val consoleStreamer = ThinkingConsoleStreamer(streamConsole)
        consoleStreamer.reset()
        streamProgressLabel.text = "Reading branch notes from '$branch'..."
        cardLayout.show(cardContainer, "STREAM")

        generationJob = cs.launch(Dispatchers.IO) {
            try {
                val branchNotes = gitChangesManager.getBranchNotesForUser(repo, branch)
                if (branchNotes.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        cardLayout.show(cardContainer, "FORM")
                        Messages.showWarningDialog(
                            project,
                            "No commit history or release notes found on branch '$branch'.",
                            "No Notes Found"
                        )
                    }
                    return@launch
                }

                ApplicationManager.getApplication().invokeLater {
                    streamProgressLabel.text = "Generating end-user announcement from AI..."
                }

                val (generatedMarkdown, _) = UserMessageStrategy.generateUserMessage(
                    settingsService = settingsService,
                    branchName = branch,
                    branchNotes = branchNotes,
                    onToken = { token ->
                        ApplicationManager.getApplication().invokeLater {
                            consoleStreamer.appendToken(token)
                        }
                    }
                )

                currentMarkdown = generatedMarkdown
                currentHtml = UserMessageFormatter.toHtml(generatedMarkdown)
                currentPlainText = UserMessageFormatter.toPlainText(generatedMarkdown)

                ApplicationManager.getApplication().invokeLater {
                    markdownRadio.isSelected = true
                    updatePreviewText()
                    cardLayout.show(cardContainer, "PREVIEW")
                }
            } catch (e: Exception) {
                LOG.warn("User message generation failed: ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    formStatusLabel.text = "Generation error: ${e.message}"
                    formStatusLabel.foreground = JBColor.RED
                    cardLayout.show(cardContainer, "FORM")
                }
            }
        }
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        formStatusLabel.text = "Generation cancelled."
        formStatusLabel.foreground = JBColor.GRAY
        cardLayout.show(cardContainer, "FORM")
    }
}
