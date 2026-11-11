package tk.mallumo.localai.ui

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import tk.mallumo.localai.actions.CodeFormattingService
import tk.mallumo.localai.git.FileItemStatus
import tk.mallumo.localai.git.GitChangesManager
import tk.mallumo.localai.git.GitFileItem
import tk.mallumo.localai.llm.CommitStrategy
import tk.mallumo.localai.llm.KoogLlmClient
import tk.mallumo.localai.settings.CommitMessageSize
import tk.mallumo.localai.settings.LocalAiCommitSettingsService
import tk.mallumo.localai.settings.LocalAiUrlStorage
import java.awt.*
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

class AiControlPanel(
    private val project: Project,
    private val gitChangesManager: GitChangesManager,
    private val cs: CoroutineScope,
    private val onCommitCompleted: () -> Unit
) : JPanel(BorderLayout()) {

    private val LOG = Logger.getInstance(AiControlPanel::class.java)
    private val settingsService = LocalAiCommitSettingsService.getInstance(project)
    private val settings get() = settingsService.state

    private var currentSelectedFiles = listOf<GitFileItem>()
    private var generationJob: Job? = null
    private var statsCalculationJob: Job? = null
    private var isConnected = false

    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val diffCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Card Layout
    private val cardLayout = CardLayout()
    private val cardContainer = JPanel(cardLayout)

    // 1. Setup / Disconnected Card
    private val setupCard = JPanel(GridBagLayout())
    private val urlField = JBTextField(LocalAiUrlStorage.loadLastUsedUrl(settings.serverUrl), 25)
    private val modelField = JBTextField(settings.modelName, 20)
    private val connectBtn = JButton("Connect & Verify")
    private val connectionStatusLabel = JBLabel("Enter local LLM address to connect", SwingConstants.CENTER)

    // 2. Main / Ready Card
    private val mainCard = JPanel(BorderLayout())
    private val connBadge = JBLabel()
    private val refreshBtn = JButton("Refresh Connection")
    private val changeUrlBtn = JButton("Change URL")
    private val reformatBox = JBCheckBox("Reformat Code", settings.reformatCode)
    private val optimizeBox = JBCheckBox("Optimize Imports", settings.optimizeImports)
    private val rearrangeBox = JBCheckBox("Rearrange Code", settings.rearrangeCode)
    private val reformatXmlBox = JBCheckBox("Reformat XML files", settings.reformatXml)
    private val selectionInfoLabel = JBLabel("No files selected", SwingConstants.CENTER)
    private val generateBtn = JButton("Generate Commit Message")
    private val commitSizeComboBox = ComboBox(DefaultComboBoxModel(CommitMessageSize.values())).apply {
        selectedItem = settings.messageSize
        addActionListener {
            val selected = selectedItem as? CommitMessageSize ?: CommitMessageSize.MEDIUM
            settings.messageSize = selected
        }
    }

    // 3. Generating / Streaming Card
    private val streamCard = JPanel(BorderLayout())
    private val streamStepLabel = JBLabel("Initializing...", SwingConstants.LEFT)
    private val streamConsole = JBTextArea()
    private val cancelGenBtn = JButton("Cancel Generation")

    // 4. Preview Card
    private val previewCard = JPanel(BorderLayout())
    private val previewEditor = JBTextArea()
    private val commitBtn = JButton("Commit")
    private val regenerateBtn = JButton("Regenerate")
    private val cancelPreviewBtn = JButton("Cancel")

    init {
        border = JBUI.Borders.empty(8)
        buildSetupCard()
        buildMainCard()
        buildStreamCard()
        buildPreviewCard()

        cardContainer.add(setupCard, "SETUP")
        cardContainer.add(mainCard, "MAIN")
        cardContainer.add(streamCard, "STREAM")
        cardContainer.add(previewCard, "PREVIEW")

        add(cardContainer, BorderLayout.CENTER)
        cardLayout.show(cardContainer, "SETUP")

        // Try automatic verification on startup
        cs.launch {
            verifyAndConnect(silent = true)
        }
    }

    private fun buildSetupCard() {
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(6)
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.gridx = 0
        gbc.gridy = 0

        val title = JBLabel("Connect to Local LLM", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 15f)
        }
        setupCard.add(title, gbc)

        gbc.gridy++
        val formPanel = JPanel(GridLayout(2, 2, 8, 8))
        formPanel.add(JBLabel("Server URL:"))
        formPanel.add(urlField)
        formPanel.add(JBLabel("Model ID (optional):"))
        formPanel.add(modelField)
        setupCard.add(formPanel, gbc)

        urlField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                persistUrl()
            }

            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                persistUrl()
            }

            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                persistUrl()
            }

            private fun persistUrl() {
                val u = urlField.text.trim()
                if (u.isNotBlank()) {
                    LocalAiUrlStorage.saveLastUsedUrl(u)
                    settings.serverUrl = u
                }
            }
        })

        gbc.gridy++
        connectBtn.addActionListener {
            cs.launch {
                verifyAndConnect(silent = false)
            }
        }
        setupCard.add(connectBtn, gbc)

        gbc.gridy++
        connectionStatusLabel.foreground = JBColor.GRAY
        setupCard.add(connectionStatusLabel, gbc)
    }

    private fun buildMainCard() {
        val topBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        }

        val connInfoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        connInfoPanel.add(connBadge)
        topBar.add(connInfoPanel, BorderLayout.CENTER)

        val rightActionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2))
        refreshBtn.addActionListener {
            refreshConnection()
        }
        changeUrlBtn.addActionListener {
            isConnected = false
            cardLayout.show(cardContainer, "SETUP")
        }
        rightActionsPanel.add(refreshBtn)
        rightActionsPanel.add(changeUrlBtn)
        topBar.add(rightActionsPanel, BorderLayout.EAST)

        val togglesPanel = JPanel(FlowLayout(FlowLayout.CENTER, 14, 6)).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        }
        reformatBox.addActionListener { settings.reformatCode = reformatBox.isSelected }
        optimizeBox.addActionListener { settings.optimizeImports = optimizeBox.isSelected }
        rearrangeBox.addActionListener { settings.rearrangeCode = rearrangeBox.isSelected }
        reformatXmlBox.addActionListener { settings.reformatXml = reformatXmlBox.isSelected }

        togglesPanel.add(reformatBox)
        togglesPanel.add(optimizeBox)
        togglesPanel.add(rearrangeBox)
        togglesPanel.add(reformatXmlBox)

        val topHeader = JPanel(BorderLayout()).apply {
            add(topBar, BorderLayout.NORTH)
            add(togglesPanel, BorderLayout.SOUTH)
        }

        val centerPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(20, 16)
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.CENTER
            fill = GridBagConstraints.NONE
            weightx = 1.0
            insets = JBUI.insets(8)
        }

        selectionInfoLabel.horizontalAlignment = SwingConstants.CENTER
        centerPanel.add(selectionInfoLabel, gbc)

        gbc.gridy = 1
        gbc.insets = JBUI.insets(16, 8, 8, 8)
        generateBtn.font = generateBtn.font.deriveFont(Font.BOLD, 13f)
        generateBtn.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        generateBtn.putClientProperty("JButton.styleDefault", true)
        generateBtn.putClientProperty("ActionToolbar.accentButton", true)
        generateBtn.addActionListener {
            startGeneration()
        }
        centerPanel.add(generateBtn, gbc)

        gbc.gridy = 2
        gbc.insets = JBUI.insets(4, 8, 8, 8)
        val sizePanel = JPanel(FlowLayout(FlowLayout.CENTER, 6, 0)).apply {
            isOpaque = false
            add(JBLabel("Commit message size:").apply {
                font = font.deriveFont(Font.PLAIN, 12f)
            })
            add(commitSizeComboBox)
        }
        centerPanel.add(sizePanel, gbc)

        mainCard.add(topHeader, BorderLayout.NORTH)
        mainCard.add(centerPanel, BorderLayout.CENTER)
    }

    private fun buildStreamCard() {
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(streamStepLabel, BorderLayout.CENTER)
            cancelGenBtn.addActionListener {
                cancelGeneration()
            }
            add(cancelGenBtn, BorderLayout.EAST)
        }

        streamConsole.isEditable = false
        streamConsole.font = Font("Monospaced", Font.PLAIN, 12)
        streamConsole.lineWrap = true
        streamConsole.wrapStyleWord = true

        streamCard.add(top, BorderLayout.NORTH)
        streamCard.add(JBScrollPane(streamConsole), BorderLayout.CENTER)
    }

    private fun buildPreviewCard() {
        val top = JBLabel("Generated Commit Message (Review and edit before committing):").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = JBUI.Borders.emptyBottom(6)
        }

        previewEditor.font = Font("Monospaced", Font.PLAIN, 13)
        previewEditor.lineWrap = true
        previewEditor.wrapStyleWord = true

        val buttonBar = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 4)).apply {
            border = JBUI.Borders.emptyTop(8)
        }

        commitBtn.font = commitBtn.font.deriveFont(Font.BOLD, 13f)
        commitBtn.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        commitBtn.putClientProperty("JButton.styleDefault", true)
        commitBtn.putClientProperty("ActionToolbar.accentButton", true)
        commitBtn.addActionListener {
            executeCommit()
        }

        regenerateBtn.addActionListener {
            startGeneration()
        }

        cancelPreviewBtn.addActionListener {
            cardLayout.show(cardContainer, "MAIN")
        }

        buttonBar.add(cancelPreviewBtn)
        buttonBar.add(regenerateBtn)
        buttonBar.add(commitBtn)

        previewCard.add(top, BorderLayout.NORTH)
        previewCard.add(JBScrollPane(previewEditor), BorderLayout.CENTER)
        previewCard.add(buttonBar, BorderLayout.SOUTH)
    }

    private fun formatModelName(modelId: String): String {
        val clean = modelId.trim().substringAfterLast('/').substringAfterLast('\\')
        return if (clean.isNotBlank()) clean else "default"
    }

    private suspend fun verifyAndConnect(silent: Boolean) {
        val url = urlField.text.trim()
        val model = modelField.text.trim()

        if (url.isNotBlank()) {
            LocalAiUrlStorage.saveLastUsedUrl(url)
            settings.serverUrl = url
        }

        ApplicationManager.getApplication().invokeLater {
            connectBtn.isEnabled = false
            connectionStatusLabel.text = "Checking connection to $url..."
            connectionStatusLabel.foreground = JBColor.GRAY
        }

        val result = KoogLlmClient.verifyConnection(url, model)

        ApplicationManager.getApplication().invokeLater {
            connectBtn.isEnabled = true
            urlField.text = LocalAiUrlStorage.loadLastUsedUrl(url)

            if (result.success) {
                isConnected = true
                settings.serverUrl = url
                settings.modelName = result.modelId
                settings.contextWindowTokens = result.contextWindow

                val displayModel = formatModelName(result.modelId)
                connBadge.text = "● Connected: $url ($displayModel) | Ctx: ${result.contextWindow} tok"
                connBadge.foreground = JBColor(Color(0, 150, 0), Color(80, 200, 80))

                cardLayout.show(cardContainer, "MAIN")
                updateSelectionState(currentSelectedFiles)
            } else {
                isConnected = false
                val lastUrl = LocalAiUrlStorage.loadLastUsedUrl(url)
                connectionStatusLabel.text =
                    "<html><b>Server offline / unreachable.</b><br>${result.message}<br><span style='color: gray;'>Last used URL: $lastUrl</span></html>"
                connectionStatusLabel.foreground = JBColor.RED
                cardLayout.show(cardContainer, "SETUP")
                if (!silent) {
                    Messages.showErrorDialog(
                        project,
                        "${result.message}\n\nLast used URL: $lastUrl",
                        "Connection Failed"
                    )
                }
            }
        }
    }

    private fun refreshConnection() {
        val lastSavedUrl = LocalAiUrlStorage.loadLastUsedUrl(settings.serverUrl)
        settings.serverUrl = lastSavedUrl
        urlField.text = lastSavedUrl

        cs.launch {
            ApplicationManager.getApplication().invokeLater {
                refreshBtn.isEnabled = false
                connBadge.text = "● Checking connection to $lastSavedUrl..."
                connBadge.foreground = JBColor.GRAY
            }

            tokenCache.clear()
            diffCache.clear()

            val result = KoogLlmClient.verifyConnection(lastSavedUrl, settings.modelName)

            ApplicationManager.getApplication().invokeLater {
                refreshBtn.isEnabled = true
                if (result.success) {
                    isConnected = true
                    settings.modelName = result.modelId
                    settings.contextWindowTokens = result.contextWindow

                    val displayModel = formatModelName(result.modelId)
                    connBadge.text =
                        "● Connected: $lastSavedUrl ($displayModel) | Ctx: ${result.contextWindow} tok"
                    connBadge.foreground = JBColor(Color(0, 150, 0), Color(80, 200, 80))

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Local AI Commit")
                        .createNotification(
                            "Local LLM is alive. Context window: ${result.contextWindow} tokens, Model: $displayModel",
                            NotificationType.INFORMATION
                        )
                        .notify(project)

                    updateSelectionState(currentSelectedFiles)
                } else {
                    isConnected = false
                    connBadge.text = "● Offline (Last URL: $lastSavedUrl)"
                    connBadge.foreground = JBColor.RED
                    urlField.text = lastSavedUrl
                    connectionStatusLabel.text =
                        "<html><b>Server offline / unreachable.</b><br>${result.message}<br><span style='color: gray;'>Last used URL: $lastSavedUrl</span></html>"
                    connectionStatusLabel.foreground = JBColor.RED
                    cardLayout.show(cardContainer, "SETUP")
                    Messages.showErrorDialog(
                        project,
                        "Could not reach Local LLM at $lastSavedUrl:\n${result.message}",
                        "Connection Check Failed"
                    )
                }
            }
        }
    }

    fun clearCaches() {
        tokenCache.clear()
        diffCache.clear()
    }

    fun updateSelectionState(selectedFiles: List<GitFileItem>) {
        currentSelectedFiles = selectedFiles
        val totalFiles = selectedFiles.size

        if (totalFiles == 0) {
            statsCalculationJob?.cancel()
            ApplicationManager.getApplication().invokeLater {
                selectionInfoLabel.text = "<html><div style='text-align: center; color: gray;'>" +
                        "No files selected for commit." +
                        "</div></html>"
                generateBtn.isEnabled = false
            }
            return
        }

        if (!isConnected) {
            statsCalculationJob?.cancel()
            ApplicationManager.getApplication().invokeLater {
                selectionInfoLabel.text = "<html><div style='text-align: center;'>" +
                        "<b>Selected:</b> $totalFiles file(s)<br>" +
                        "<span style='color: gray;'>Connect to local LLM to enable generation</span>" +
                        "</div></html>"
                generateBtn.isEnabled = false
            }
            return
        }

        statsCalculationJob?.cancel()

        // Immediate responsive UI update
        ApplicationManager.getApplication().invokeLater {
            selectionInfoLabel.text = "<html><div style='text-align: center;'>" +
                    "<b>Selected:</b> $totalFiles file(s) &nbsp;•&nbsp; <i>Estimating diff tokens...</i>" +
                    "</div></html>"
            generateBtn.isEnabled = true
        }

        statsCalculationJob = cs.launch(Dispatchers.Default) {
            val fileTokens = mutableMapOf<String, Int>()
            val filePaths = selectedFiles.map { it.relativePath }

            for (item in selectedFiles) {
                if (!isActive) return@launch
                val path = item.relativePath
                val diff = diffCache[path] ?: withContext(Dispatchers.IO) {
                    gitChangesManager.getDiffForFile(item)
                }.also { diffCache[path] = it }

                val tokens = tokenCache[path] ?: maxOf(1, (diff.length / 3.2).toInt()).also {
                    tokenCache[path] = it
                }
                fileTokens[path] = tokens
            }

            if (!isActive) return@launch

            val stats = CommitStrategy.calculateSelectionStats(
                filePaths = filePaths,
                fileTokens = fileTokens,
                maxDiffTokens = settings.maxDiffTokens,
                contextWindowTokens = settings.contextWindowTokens
            )

            val callsDesc = when {
                stats.normalFiles == 0 && stats.svgFiles > 0 && stats.binaryFiles == 0 -> "0 (local SVG generator)"
                stats.estimatedCalls <= 1 -> "1 call (single pass)"
                else -> "${stats.estimatedCalls} calls (${stats.numChunks} chunk summaries + 1 consolidation)"
            }

            val statusText = buildString {
                append("<html><div style='text-align: center; line-height: 140%;'>")
                append("<b>Selected:</b> ${stats.totalFiles} file(s)")
                val details = mutableListOf<String>()
                if (stats.normalFiles > 0) details.add("${stats.normalFiles} code/text")
                if (stats.svgFiles > 0) details.add("${stats.svgFiles} SVG")
                if (stats.binaryFiles > 0) details.add("${stats.binaryFiles} binary/media")
                if (details.isNotEmpty()) {
                    append(" <font color='gray'>(${details.joinToString(", ")})</font>")
                }
                append(" &nbsp;•&nbsp; <b>Diff tokens:</b> ~${stats.totalDiffTokens}")
                append(" <font color='gray'>(limit: ${stats.maxDiffTokens})</font>")
                append("<br><b>Estimated calls:</b> $callsDesc")
                append("</div></html>")
            }

            ApplicationManager.getApplication().invokeLater {
                selectionInfoLabel.text = statusText
                generateBtn.isEnabled = true
            }
        }
    }

    private fun startGeneration() {
        if (currentSelectedFiles.isEmpty()) return

        val consoleStreamer = ThinkingConsoleStreamer(streamConsole)
        consoleStreamer.reset()
        streamStepLabel.text = "Starting..."
        cardLayout.show(cardContainer, "STREAM")

        generationJob = cs.launch {
            try {
                // 1. Pre-commit formatting actions
                val vfs = currentSelectedFiles.mapNotNull { it.virtualFile }
                if (settings.reformatCode || settings.optimizeImports || settings.rearrangeCode) {
                    ApplicationManager.getApplication().invokeLater {
                        streamStepLabel.text = "Applying pre-commit code actions (Reformat, Optimize, Rearrange)..."
                        consoleStreamer.appendProgress("Applying code styles and import optimizations...")
                    }
                    CodeFormattingService.formatFiles(
                        project = project,
                        files = vfs,
                        reformat = settings.reformatCode,
                        optimizeImports = settings.optimizeImports,
                        rearrange = settings.rearrangeCode,
                        reformatXml = settings.reformatXml
                    )
                }

                // 2. Diff extraction
                ApplicationManager.getApplication().invokeLater {
                    streamStepLabel.text = "Extracting git diffs..."
                    consoleStreamer.appendProgress("Reading git diffs for ${currentSelectedFiles.size} file(s)...")
                }

                val fileStatuses = mutableMapOf<String, String>()
                val fileDiffs = mutableMapOf<String, String>()
                val filePaths = mutableListOf<String>()

                for (item in currentSelectedFiles) {
                    val path = item.relativePath
                    filePaths.add(path)
                    fileStatuses[path] = when (item.status) {
                        FileItemStatus.DELETED -> "removed"
                        FileItemStatus.ADDED, FileItemStatus.UNVERSIONED -> "added"
                        FileItemStatus.MODIFIED -> "modified"
                    }
                    fileDiffs[path] = gitChangesManager.getDiffForFile(item)
                }

                // 3. Commit generation via Strategy & Koog
                val listener = object : CommitStrategy.GenerationListener {
                    override suspend fun onProgress(message: String) {
                        ApplicationManager.getApplication().invokeLater {
                            streamStepLabel.text = message
                            consoleStreamer.appendProgress(message)
                        }
                    }

                    override suspend fun onToken(token: String) {
                        ApplicationManager.getApplication().invokeLater {
                            consoleStreamer.appendToken(token)
                        }
                    }

                    override suspend fun onChunkComplete(title: String, summary: String) {
                        ApplicationManager.getApplication().invokeLater {
                            consoleStreamer.appendChunkComplete(title)
                        }
                    }
                }

                val result = CommitStrategy.generateCommit(
                    baseUrl = settings.serverUrl,
                    modelId = settings.modelName,
                    files = filePaths,
                    fileStatuses = fileStatuses,
                    fileDiffs = fileDiffs,
                    maxDiffTokens = settings.maxDiffTokens,
                    systemPrompt = settings.getEffectiveSystemPrompt(),
                    chunkSystemPrompt = settings.getEffectiveChunkSystemPrompt(),
                    finalSystemPrompt = settings.getEffectiveFinalSystemPrompt(),
                    listener = listener
                )

                ApplicationManager.getApplication().invokeLater {
                    previewEditor.text = result.commitMessage
                    cardLayout.show(cardContainer, "PREVIEW")
                    rootPane?.defaultButton = commitBtn
                }
            } catch (e: CancellationException) {
                ApplicationManager.getApplication().invokeLater {
                    streamStepLabel.text = "Generation cancelled."
                    streamConsole.append("\n[GENERATION CANCELLED BY USER]\n")
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Generation failed: ${e.message}", "AI Generation Error")
                    cardLayout.show(cardContainer, "MAIN")
                }
            }
        }
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        cardLayout.show(cardContainer, "MAIN")
    }

    private fun executeCommit() {
        val msg = previewEditor.text.trim()
        if (msg.isEmpty()) {
            Messages.showWarningDialog(project, "Commit message cannot be empty.", "Empty Commit Message")
            return
        }

        val filesToCommit = currentSelectedFiles.toList()
        if (filesToCommit.isEmpty()) {
            Messages.showWarningDialog(project, "No files selected to commit.", "Empty Selection")
            return
        }

        commitBtn.isEnabled = false
        cancelPreviewBtn.isEnabled = false
        regenerateBtn.isEnabled = false

        cs.launch {
            try {
                val byRepo = filesToCommit.groupBy { it.repository }
                var allSucceeded = true

                for ((repo, items) in byRepo) {
                    val ok = gitChangesManager.commitFiles(repo, items, msg)
                    if (!ok) allSucceeded = false
                }

                ApplicationManager.getApplication().invokeLater {
                    commitBtn.isEnabled = true
                    cancelPreviewBtn.isEnabled = true
                    regenerateBtn.isEnabled = true

                    tokenCache.clear()
                    diffCache.clear()
                    currentSelectedFiles = emptyList()
                    cardLayout.show(cardContainer, "MAIN")

                    if (allSucceeded) {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Local AI Commit")
                            .createNotification(
                                "Commit successful (${filesToCommit.size} files committed).",
                                NotificationType.INFORMATION
                            )
                            .notify(project)
                        onCommitCompleted()
                    } else {
                        onCommitCompleted()
                        Messages.showErrorDialog(
                            project,
                            "Failed to commit some files. Check git console for details.",
                            "Commit Error"
                        )
                    }
                }
            } catch (e: Exception) {
                LOG.error("Commit failed with error", e)
                ApplicationManager.getApplication().invokeLater {
                    commitBtn.isEnabled = true
                    cancelPreviewBtn.isEnabled = true
                    regenerateBtn.isEnabled = true
                    Messages.showErrorDialog(project, "Commit error: ${e.message}", "Commit Error")
                }
            }
        }
    }
}
